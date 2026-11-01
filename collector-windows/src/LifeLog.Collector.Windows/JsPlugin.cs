using System.Text.Json;
using Jint;
using Jint.Native;
using Jint.Native.Object;
using LifeLog.Core;

namespace LifeLog.Collector.Windows;

/// <summary>
/// JS 수집기 플러그인 하나. 엔진 인스턴스를 따로 가진다. 모든 호출은 디스패처 스레드에서만.
/// ctx의 모양은 Documents/수집기 호스트 API.md 3절.
/// </summary>
public sealed class JsPlugin
{
    public Manifest Manifest { get; }
    public string Dir { get; }
    private readonly Host _host;
    private Engine? _engine;
    private JsValue _default = JsValue.Undefined;
    private readonly Dictionary<string, List<JsValue>> _handlers = new();
    private readonly List<System.Threading.Timer> _timers = new();
    private int _failures;
    public bool Disabled { get; private set; }
    public IEnumerable<string> SubscribedSources => _handlers.Keys;

    public JsPlugin(Manifest m, string dir, Host host)
    {
        Manifest = m; Dir = dir; _host = host;
    }

    public void Load()
    {
        var entry = Manifest.Runtimes.TryGetValue("js", out var e) ? e : throw new InvalidOperationException("js 런타임이 아닙니다");
        var code = File.ReadAllText(Path.Combine(Dir, entry));
        _engine = new Engine(o =>
        {
            o.Strict();
            o.TimeoutInterval(TimeSpan.FromSeconds(5));
            o.LimitMemory(64 * 1024 * 1024);
            o.LimitRecursion(200);
        });
        _engine.Modules.Add("main", code);
        var ns = _engine.Modules.Import("main");
        _default = ns.Get("default");
        if (!_default.IsObject()) throw new InvalidOperationException("main.js는 { start, stop }을 default export 해야 합니다");
    }

    public void Start()
    {
        var start = _default.AsObject().Get("start");
        if (start.IsUndefined()) throw new InvalidOperationException("start(ctx)가 없습니다");
        _engine!.Invoke(start, _default, [new Ctx(this, _host)]);
    }

    public void Stop()
    {
        foreach (var t in _timers) t.Dispose();
        _timers.Clear();
        if (_engine is null || !_default.IsObject()) return;
        var stop = _default.AsObject().Get("stop");
        if (stop.IsUndefined()) return;
        Guard(() => _engine.Invoke(stop, _default, [new Ctx(this, _host)]));
    }

    public void Deliver(string source, Dictionary<string, object?> ev)
    {
        if (Disabled || !_handlers.TryGetValue(source, out var list)) return;
        foreach (var fn in list)
            Guard(() => _engine!.Invoke(fn, JsValue.Undefined, [ev]));
    }

    /// <summary>핸들러 예외를 격리한다. 연속 5회면 플러그인을 끈다(Documents/수집기 호스트 API.md 4절).</summary>
    private void Guard(Action a)
    {
        try { a(); _failures = 0; }
        catch (Exception e)
        {
            _failures++;
            _host.Log($"[{Manifest.Id}] 핸들러 실패 {_failures}회: {e.Message}");
            if (_failures >= 5 && !Disabled)
            {
                Disabled = true;
                _host.Log($"[{Manifest.Id}] 연속 실패로 이 기기에서 끕니다");
                _host.DisablePlugin(this);
            }
        }
    }

    // ---- ctx: JS에서 ctx.on(...) 처럼 소문자 이름으로 부른다 ----
    public sealed class Ctx
    {
        private readonly JsPlugin _p;
        private readonly Host _h;
        public SpanApi span { get; }
        public EventApi @event { get; }
        public StateApi state { get; }

        public Ctx(JsPlugin p, Host h)
        {
            _p = p; _h = h;
            span = new SpanApi(p, h); @event = new EventApi(p, h); state = new StateApi(p, h);
        }

        public void on(string source, JsValue handler)
        {
            _h.RequireCapability(_p, "event:" + source);
            if (!_p._handlers.TryGetValue(source, out var list)) _p._handlers[source] = list = new();
            list.Add(handler);
        }

        public void every(string interval, JsValue fn)
        {
            _h.RequireCapability(_p, "timer");
            var period = Intervals.Parse(interval);
            var timer = new System.Threading.Timer(_ => _h.Dispatcher.Post(() => _p.Guard(() => _p._engine!.Invoke(fn, JsValue.Undefined, []))), null, period, period);
            _p._timers.Add(timer);
        }

        public JsValue settings => JsValue.FromObject(_p._engine!, Json.ToClr(_h.SettingsOf(_p)));
        public JsValue privacy => settings;   // privacy 값도 같은 settings jsonb에 들어 있다
        public bool has(string capability) => _h.HasCapability(_p, capability);
        public DateTime now() => _h.Now().UtcDateTime;
        public void log(string level, string msg) => _h.Log($"[{_p.Manifest.Id}] {level}: {msg}");
        public void log(string msg) => _h.Log($"[{_p.Manifest.Id}] {msg}");
    }

    public sealed class SpanApi
    {
        private readonly JsPlugin _p; private readonly Host _h;
        public SpanApi(JsPlugin p, Host h) { _p = p; _h = h; }

        public string open(string output, JsValue row)
        {
            var (o, cols, start, _) = _h.ParseRow(_p, output, row, needStart: true);
            return _h.Spans.Open(_p.Manifest, o, start!.Value, cols).ToString();
        }
        public void update(string id, JsValue patch)
            => _h.Spans.Update(Guid.Parse(id), _h.Now(), Json.ToCols(patch));
        public void close(string id, JsValue end)
            => _h.Spans.Close(Guid.Parse(id), Json.ToTime(end) ?? _h.Now());
        public string put(string output, JsValue row, JsValue? opts = null)
        {
            var (o, cols, start, end) = _h.ParseRow(_p, output, row, needStart: true, needEnd: true);
            var key = opts is { } op && op.IsObject() && op.AsObject().Get("key") is { } k && !k.IsUndefined() && !k.IsNull() ? k.ToString() : null;
            return _h.Spans.Put(_p.Manifest, o, start!.Value, end!.Value, cols, key).ToString();
        }
        public void delete(string output, string id)
        {
            var o = _p.Manifest.FindOutput(output) ?? throw new InvalidOperationException("없는 출력: " + output);
            _h.Spans.Delete(_p.Manifest, o, Guid.Parse(id));
        }
    }

    public sealed class EventApi
    {
        private readonly JsPlugin _p; private readonly Host _h;
        public EventApi(JsPlugin p, Host h) { _p = p; _h = h; }
        public string emit(string output, JsValue row, JsValue? opts = null)
        {
            var (o, cols, time, _) = _h.ParseRow(_p, output, row, needTime: true);
            var key = opts is { } op && op.IsObject() && op.AsObject().Get("key") is { } k && !k.IsUndefined() && !k.IsNull() ? k.ToString() : null;
            return _h.Spans.Emit(_p.Manifest, o, time!.Value, cols, key).ToString();
        }
    }

    public sealed class StateApi
    {
        private readonly JsPlugin _p; private readonly Host _h;
        public StateApi(JsPlugin p, Host h) { _p = p; _h = h; }
        public JsValue get(string key)
        {
            var v = _h.StateGet(_p, key);
            return v is null ? JsValue.Undefined : JsValue.FromObject(_p._engine!, Json.ToClr(v.Value));
        }
        public void set(string key, JsValue value) => _h.StateSet(_p, key, Json.FromJs(value));
    }
}

public static class Intervals
{
    /// <summary>"500ms" "30s" "15m" "1h" "1d"</summary>
    public static TimeSpan Parse(string s)
    {
        s = s.Trim();
        if (s.EndsWith("ms")) return TimeSpan.FromMilliseconds(double.Parse(s[..^2]));
        var n = double.Parse(s[..^1]);
        return s[^1] switch
        {
            's' => TimeSpan.FromSeconds(n), 'm' => TimeSpan.FromMinutes(n), 'h' => TimeSpan.FromHours(n), 'd' => TimeSpan.FromDays(n),
            _ => throw new FormatException("기간 형식이 아닙니다: " + s),
        };
    }
}

/// <summary>JS 값 ↔ CLR/JSON 변환.</summary>
public static class Json
{
    public static object? ToClr(JsonElement e) => e.ValueKind switch
    {
        JsonValueKind.Object => e.EnumerateObject().ToDictionary(p => p.Name, p => ToClr(p.Value)),
        JsonValueKind.Array => e.EnumerateArray().Select(ToClr).ToList(),
        JsonValueKind.String => e.GetString(),
        JsonValueKind.Number => e.TryGetInt64(out var l) ? l : e.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        _ => null,
    };

    public static string FromJs(JsValue v) => JsonSerializer.Serialize(Plain(v.ToObject()));

    /// <summary>JS 객체 → 열 사전. 값은 JSON으로 직렬화 가능한 CLR 값으로 바꾼다.</summary>
    public static Dictionary<string, object?> ToCols(JsValue v)
    {
        if (!v.IsObject()) throw new InvalidOperationException("행은 객체여야 합니다");
        var d = new Dictionary<string, object?>();
        foreach (var (k, desc) in v.AsObject().GetOwnProperties())
            d[k.ToString()] = Plain(desc.Value.ToObject());
        return d;
    }

    private static object? Plain(object? o) => o switch
    {
        null => null,
        DateTime dt => new DateTimeOffset(dt.Kind == DateTimeKind.Unspecified ? DateTime.SpecifyKind(dt, DateTimeKind.Utc) : dt).ToUniversalTime().ToString("O"),
        System.Dynamic.ExpandoObject ex => ((IDictionary<string, object?>)ex).ToDictionary(kv => kv.Key, kv => Plain(kv.Value)),
        IDictionary<string, object?> dict => dict.ToDictionary(kv => kv.Key, kv => Plain(kv.Value)),
        object[] arr => arr.Select(Plain).ToList(),
        _ => o,
    };

    public static DateTimeOffset? ToTime(object? v) => v switch
    {
        null => null,
        DateTimeOffset dto => dto,
        DateTime dt => new DateTimeOffset(dt.Kind == DateTimeKind.Unspecified ? DateTime.SpecifyKind(dt, DateTimeKind.Utc) : dt),
        double ms => DateTimeOffset.FromUnixTimeMilliseconds((long)ms),
        long ms => DateTimeOffset.FromUnixTimeMilliseconds(ms),
        string s => DateTimeOffset.Parse(s),
        JsValue jv => jv.IsUndefined() || jv.IsNull() ? null : ToTime(jv.ToObject()),
        _ => throw new InvalidOperationException("시각이 아닙니다: " + v),
    };
}
