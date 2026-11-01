using System.Text.Json;

namespace LifeLog.Core;

/// <summary>큐를 DB로 보낸다. 실패하면 큐에 남기고 물러났다 다시 시도한다(5초 → 최대 2분).</summary>
public sealed class Uploader
{
    private readonly Queue _q;
    private readonly Db _db;
    private readonly Func<string, TableMeta?> _meta;
    private readonly Action<string> _log;
    public Exception? LastError { get; private set; }

    public Uploader(Queue q, Db db, Func<string, TableMeta?> meta, Action<string> log)
    {
        _q = q; _db = db; _meta = meta; _log = log;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(5);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var ops = _q.Peek(500);
                if (ops.Count == 0) { await Task.Delay(TimeSpan.FromSeconds(5), ct); continue; }

                // 순서 보존: 연속된 같은 종류·같은 테이블끼리 묶어 보낸다
                int i = 0;
                while (i < ops.Count)
                {
                    var first = ops[i];
                    int j = i;
                    while (j < ops.Count && ops[j].Kind == first.Kind && ops[j].Table == first.Table) j++;
                    var chunk = ops.GetRange(i, j - i);
                    if (first.Kind == "upsert")
                    {
                        var meta = _meta(first.Table) ?? throw new InvalidOperationException($"테이블 메타가 없습니다: {first.Table}");
                        var rows = chunk.Select(o => JsonSerializer.Deserialize<Row>(o.Payload)!).ToList();
                        await _db.UpsertAsync(meta, rows, ct);
                    }
                    else
                    {
                        foreach (var o in chunk)
                        {
                            var d = JsonDocument.Parse(o.Payload).RootElement;
                            await _db.DeleteAsync(first.Table, d.GetProperty("device").GetString()!, d.GetProperty("id").GetGuid(), ct);
                        }
                    }
                    _q.Ack(chunk.Select(o => o.Seq));
                    i = j;
                }
                LastError = null;
                backoff = TimeSpan.FromSeconds(5);
            }
            catch (OperationCanceledException) { break; }
            catch (Exception e)
            {
                LastError = e;
                _log($"전송 실패, {backoff.TotalSeconds:F0}초 뒤 재시도: {e.Message}");
                try { await Task.Delay(backoff, ct); } catch (OperationCanceledException) { break; }
                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 120));
            }
        }
    }
}
