using System.Text.Json;
using System.Text.Json.Serialization;

namespace LifeLog.Core;

/// <summary>큐와 업로더가 주고받는 행 하나. start/end는 UTC. 시점 행은 end가 null이고 start가 time이다.</summary>
public sealed class Row
{
    [JsonPropertyName("table")] public string Table { get; set; } = "";
    [JsonPropertyName("device")] public string Device { get; set; } = "";
    [JsonPropertyName("id")] public Guid Id { get; set; }
    [JsonPropertyName("start")] public DateTimeOffset Start { get; set; }
    [JsonPropertyName("end")] public DateTimeOffset? End { get; set; }
    [JsonPropertyName("cols")] public Dictionary<string, JsonElement> Cols { get; set; } = new();

    public string Key => Table + "|" + Device + "|" + Id;

    public static Row From(string table, string device, Guid id, DateTimeOffset start, DateTimeOffset? end, IReadOnlyDictionary<string, object?> cols)
    {
        var r = new Row { Table = table, Device = device, Id = id, Start = start, End = end };
        foreach (var (k, v) in cols)
            r.Cols[k] = JsonSerializer.SerializeToElement(v);
        return r;
    }
}

/// <summary>테이블의 kind·열 타입. 업로더가 SQL을 만들 때 쓴다. 매니페스트에서 나온다.</summary>
public sealed record TableMeta(string Table, bool IsSpan, IReadOnlyDictionary<string, string> Columns);
