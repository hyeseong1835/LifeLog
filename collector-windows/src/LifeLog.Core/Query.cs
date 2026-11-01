using System.Text.Json;
using Npgsql;
using NpgsqlTypes;

namespace LifeLog.Core;

/// <summary>
/// 렌더러의 lifelog.query(table, opts)를 SQL로 바꾼다(Documents/새 구조 설계.md 2.5 "query 집계 옵션").
/// 식별자는 전부 따옴표, 값은 파라미터. 매니페스트에 없는 열은 SQL에 닿기 전에 거부한다.
/// </summary>
public sealed class QuerySpec
{
    public required string Table { get; init; }            // "win-window/window@1" 또는 뷰 "win-window/window"
    public required DateTimeOffset Start { get; init; }
    public required DateTimeOffset End { get; init; }
    public string? Device { get; init; }
    public Dictionary<string, List<JsonElement>> Where { get; init; } = new();   // 열 → 값 목록(동등·IN)
    public string? GroupTime { get; init; }                 // hour | day | week | month
    public List<string> GroupBy { get; init; } = new();
    public Dictionary<string, string> Agg { get; init; } = new();   // 결과 키 → count | duration | sum:col | avg:col | min:col | max:col
    public string Tz { get; init; } = "UTC";
    public int Limit { get; init; } = 5000;

    public const int MaxLimit = 50000;

    public static QuerySpec Parse(string table, JsonElement opts)
    {
        var range = opts.GetProperty("range");
        var spec = new QuerySpec
        {
            Table = table,
            Start = ParseTime(range.GetProperty("start")),
            End = ParseTime(range.GetProperty("end")),
            Device = opts.TryGetProperty("device", out var d) && d.ValueKind == JsonValueKind.String ? d.GetString() : null,
            GroupTime = opts.TryGetProperty("group", out var g) && g.ValueKind == JsonValueKind.Object && g.TryGetProperty("time", out var gt) ? gt.GetString() : null,
            Tz = opts.TryGetProperty("tz", out var tz) && tz.ValueKind == JsonValueKind.String ? tz.GetString()! : "UTC",
            Limit = opts.TryGetProperty("limit", out var l) && l.ValueKind == JsonValueKind.Number ? Math.Clamp(l.GetInt32(), 1, MaxLimit) : 5000,
        };
        if (opts.TryGetProperty("where", out var w) && w.ValueKind == JsonValueKind.Object)
            foreach (var p in w.EnumerateObject())
                spec.Where[p.Name] = p.Value.ValueKind == JsonValueKind.Array ? p.Value.EnumerateArray().ToList() : [p.Value];
        if (opts.TryGetProperty("group", out var g2) && g2.ValueKind == JsonValueKind.Object && g2.TryGetProperty("by", out var by) && by.ValueKind == JsonValueKind.Array)
            spec.GroupBy.AddRange(by.EnumerateArray().Select(e => e.GetString()!));
        if (opts.TryGetProperty("agg", out var a) && a.ValueKind == JsonValueKind.Object)
            foreach (var p in a.EnumerateObject()) spec.Agg[p.Name] = p.Value.GetString()!;
        return spec;
    }

    public static DateTimeOffset ParseTime(JsonElement e) => e.ValueKind switch
    {
        JsonValueKind.String => DateTimeOffset.Parse(e.GetString()!),
        JsonValueKind.Number => DateTimeOffset.FromUnixTimeMilliseconds((long)e.GetDouble()),
        _ => throw new InvalidOperationException("시각이 아닙니다"),
    };
}

public static class QueryBuilder
{
    static readonly HashSet<string> TimeUnits = ["hour", "day", "week", "month"];
    static readonly HashSet<string> NumericTypes = ["integer", "bigint", "double precision", "numeric"];

    /// <summary>SQL과 파라미터를 만든다. meta.Columns는 자유 열만(공통 열 제외).</summary>
    public static (string sql, List<NpgsqlParameter> ps) Build(QuerySpec q, TableMeta meta)
    {
        var ps = new List<NpgsqlParameter>();
        string P(object v, NpgsqlDbType? t = null) { ps.Add(t is null ? new NpgsqlParameter { Value = v } : new NpgsqlParameter { Value = v, NpgsqlDbType = t.Value }); return "$" + ps.Count; }
        string Q(string id) => "\"" + id.Replace("\"", "\"\"") + "\"";
        string ColOf(string c) => meta.Columns.ContainsKey(c) ? Q(c) : throw new InvalidOperationException($"선언되지 않은 열: {c}");

        var tcol = meta.IsSpan ? "start" : "time";
        var where = new List<string>();
        var pStart = P(q.Start.UtcDateTime, NpgsqlDbType.TimestampTz);
        var pEnd = P(q.End.UtcDateTime, NpgsqlDbType.TimestampTz);
        where.Add(meta.IsSpan ? $"\"end\" >= {pStart} AND start < {pEnd}" : $"time >= {pStart} AND time < {pEnd}");
        if (q.Device is not null) where.Add($"device = {P(q.Device)}");
        foreach (var (col, vals) in q.Where)
        {
            var c = ColOf(col);
            var type = meta.Columns[col];
            var list = vals.Select(v => P(ToClr(v, type))).ToList();
            where.Add(list.Count == 1 ? $"{c} = {list[0]}" : $"{c} IN ({string.Join(", ", list)})");
        }
        var whereSql = string.Join(" AND ", where);
        var table = "lifelog." + Q(q.Table);

        if (q.GroupTime is null && q.GroupBy.Count == 0)
        {
            var cols = string.Join(", ", meta.Columns.Keys.OrderBy(k => k, StringComparer.Ordinal).Select(Q));
            var common = meta.IsSpan ? "device, id, start, \"end\"" : "device, id, time";
            var extra = cols.Length > 0 ? ", " + cols : "";
            return ($"SELECT {common}{extra} FROM {table} WHERE {whereSql} ORDER BY {tcol} LIMIT {q.Limit + 1}", ps);
        }

        // 집계 모드
        if (q.Agg.Count == 0) throw new InvalidOperationException("group이 있으면 agg가 필요합니다");
        var groupExprs = new List<string>();
        var selectExprs = new List<string>();
        if (q.GroupTime is not null)
        {
            if (!TimeUnits.Contains(q.GroupTime)) throw new InvalidOperationException("group.time은 hour|day|week|month");
            // 구간은 시작 시각의 버킷에 통째로(1차). tz 기준.
            var expr = $"date_trunc({P(q.GroupTime)}, {tcol} AT TIME ZONE {P(q.Tz)}) AT TIME ZONE {P(q.Tz)}";
            groupExprs.Add(expr);
            selectExprs.Add($"{expr} AS \"time\"");
        }
        foreach (var b in q.GroupBy) { var c = ColOf(b); groupExprs.Add(c); selectExprs.Add(c); }
        foreach (var (key, fn) in q.Agg)
        {
            if (key is "time" || meta.Columns.ContainsKey(key)) throw new InvalidOperationException($"agg 키 {key}는 열 이름과 겹칩니다");
            string expr;
            if (fn == "count") expr = "count(*)";
            else if (fn == "duration")
            {
                if (!meta.IsSpan) throw new InvalidOperationException("duration은 구간 출력에만");
                expr = $"sum(extract(epoch from (least(\"end\", {pEnd}) - greatest(start, {pStart}))))";
            }
            else
            {
                var parts = fn.Split(':', 2);
                if (parts.Length != 2 || parts[0] is not ("sum" or "avg" or "min" or "max")) throw new InvalidOperationException($"모르는 집계: {fn}");
                var c = ColOf(parts[1]);
                if (!NumericTypes.Contains(meta.Columns[parts[1]])) throw new InvalidOperationException($"{parts[1]}은 숫자 열이 아닙니다");
                expr = $"{parts[0]}({c})";
            }
            selectExprs.Add($"{expr} AS {Q(key)}");
        }
        var groupSql = groupExprs.Count > 0 ? " GROUP BY " + string.Join(", ", groupExprs) : "";
        var orderSql = groupExprs.Count > 0 ? " ORDER BY " + string.Join(", ", groupExprs) : "";
        return ($"SELECT {string.Join(", ", selectExprs)} FROM {table} WHERE {whereSql}{groupSql}{orderSql} LIMIT {q.Limit + 1}", ps);
    }

    static object ToClr(JsonElement v, string type) => type switch
    {
        "text" => v.GetString()!,
        "boolean" => v.GetBoolean(),
        "integer" => (int)v.GetDouble(),
        "bigint" => (long)v.GetDouble(),
        "double precision" => v.GetDouble(),
        "numeric" => v.GetDecimal(),
        _ => v.GetRawText(),
    };
}
