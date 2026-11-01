namespace LifeLog.Core;

/// <summary>
/// 구간 관리(Documents/수집기 호스트 API.md 3절). 플러그인은 open/update/close/put/delete만 부르고,
/// id 생성·열린 구간의 주기 upsert·큐 투입은 여기서 한다. 호출은 디스패처 스레드에서만 온다.
/// </summary>
public sealed class SpanManager
{
    private sealed class OpenSpan
    {
        public required string Table;
        public required string Plugin;
        public required string Output;
        public required Guid Id;
        public required DateTimeOffset Start;
        public required Dictionary<string, object?> Cols;
    }

    private readonly Queue _q;
    private readonly string _device;
    private readonly Dictionary<Guid, OpenSpan> _open = new();

    public SpanManager(Queue q, string device) { _q = q; _device = device; }

    public int OpenCount => _open.Count;

    private static IReadOnlyList<object?> IdentityValues(Manifest.Output o, IReadOnlyDictionary<string, object?> cols)
        => o.Identity.Select(c => cols.TryGetValue(c, out var v) ? v : null).ToList();

    public Guid Open(Manifest m, Manifest.Output o, DateTimeOffset start, Dictionary<string, object?> cols)
    {
        if (!o.IsSpan) throw new InvalidOperationException($"{o.Name}은 구간 출력이 아닙니다");
        var table = m.TableName(o);
        var id = Ids.RowId(m.Id, _device, o.Name, start, IdentityValues(o, cols));
        _open[id] = new OpenSpan { Table = table, Plugin = m.Id, Output = o.Name, Id = id, Start = start, Cols = cols };
        _q.EnqueueUpsert(Row.From(table, _device, id, start, start, cols));
        return id;
    }

    public void Update(Guid id, DateTimeOffset now, IReadOnlyDictionary<string, object?> patch)
    {
        if (!_open.TryGetValue(id, out var s)) throw new InvalidOperationException("열린 구간이 아닙니다: " + id);
        foreach (var (k, v) in patch) s.Cols[k] = v;
        _q.EnqueueUpsert(Row.From(s.Table, _device, s.Id, s.Start, Max(now, s.Start), s.Cols));
    }

    public void Close(Guid id, DateTimeOffset end)
    {
        if (!_open.Remove(id, out var s)) throw new InvalidOperationException("열린 구간이 아닙니다: " + id);
        _q.EnqueueUpsert(Row.From(s.Table, _device, s.Id, s.Start, Max(end, s.Start), s.Cols));
    }

    /// <summary>열린 구간 전부를 end = now로 다시 보낸다. 주기(기본 1분)마다 호스트가 부른다.</summary>
    public void Tick(DateTimeOffset now)
    {
        foreach (var s in _open.Values)
            _q.EnqueueUpsert(Row.From(s.Table, _device, s.Id, s.Start, Max(now, s.Start), s.Cols));
    }

    /// <summary>닫힌 구간을 통째로. id는 key가 있으면 key로, 없으면 start + identity 열로.</summary>
    public Guid Put(Manifest m, Manifest.Output o, DateTimeOffset start, DateTimeOffset end, Dictionary<string, object?> cols, string? key)
    {
        if (!o.IsSpan) throw new InvalidOperationException($"{o.Name}은 구간 출력이 아닙니다");
        var id = key is null ? Ids.RowId(m.Id, _device, o.Name, start, IdentityValues(o, cols)) : Ids.RowId(m.Id, _device, o.Name, key);
        _q.EnqueueUpsert(Row.From(m.TableName(o), _device, id, start, Max(end, start), cols));
        return id;
    }

    public Guid Emit(Manifest m, Manifest.Output o, DateTimeOffset time, Dictionary<string, object?> cols, string? key)
    {
        if (o.IsSpan) throw new InvalidOperationException($"{o.Name}은 시점 출력이 아닙니다");
        var id = key is null ? Ids.RowId(m.Id, _device, o.Name, time, IdentityValues(o, cols)) : Ids.RowId(m.Id, _device, o.Name, key);
        _q.EnqueueUpsert(Row.From(m.TableName(o), _device, id, time, null, cols));
        return id;
    }

    public void Delete(Manifest m, Manifest.Output o, Guid id)
    {
        _open.Remove(id);
        _q.EnqueueDelete(m.TableName(o), _device, id);
    }

    /// <summary>앱이 정상 종료할 때: 열린 구간을 지금 시각으로 닫는다.</summary>
    public void CloseAll(DateTimeOffset now)
    {
        foreach (var id in _open.Keys.ToList()) Close(id, now);
    }

    private static DateTimeOffset Max(DateTimeOffset a, DateTimeOffset b) => a > b ? a : b;
}
