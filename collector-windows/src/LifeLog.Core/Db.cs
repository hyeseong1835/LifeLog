using System.Text.Json;
using Npgsql;
using NpgsqlTypes;

namespace LifeLog.Core;

/// <summary>DB 접근. 등록 함수 호출, 스키마 버전 확인, 시계 보정, 행 upsert. 기기 롤로 붙는다.</summary>
public sealed class Db : IAsyncDisposable
{
    /// <summary>이 앱이 아는 코어 스키마 버전. DB가 더 높으면 쓰기를 멈춘다(Documents/등록 함수.md 4절).</summary>
    public const int KnownSchemaVersion = 4;

    private readonly NpgsqlDataSource _ds;
    public TimeSpan ClockOffset { get; private set; }   // DB now() - 로컬 now

    public Db(string connectionString)
    {
        var b = new NpgsqlDataSourceBuilder(connectionString);
        _ds = b.Build();
    }

    public async Task<int> SchemaVersionAsync(CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT COALESCE(max(version), 0) FROM lifelog.schema_migrations");
        return Convert.ToInt32(await cmd.ExecuteScalarAsync(ct));
    }

    public async Task SyncClockAsync(CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT now()");
        var before = DateTimeOffset.UtcNow;
        var dbNow = (DateTime)(await cmd.ExecuteScalarAsync(ct))!;
        var after = DateTimeOffset.UtcNow;
        var mid = before + (after - before) / 2;
        ClockOffset = new DateTimeOffset(dbNow, TimeSpan.Zero) - mid;
    }

    public DateTimeOffset Now() => DateTimeOffset.UtcNow + ClockOffset;

    public async Task<JsonElement> RegisterPluginAsync(Manifest m, string device, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT lifelog.register_plugin($1::jsonb, $2)");
        cmd.Parameters.Add(new NpgsqlParameter { Value = m.Raw, NpgsqlDbType = NpgsqlDbType.Jsonb });
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        var s = (string)(await cmd.ExecuteScalarAsync(ct))!;
        return JsonDocument.Parse(s).RootElement.Clone();
    }

    public sealed record Install(string InstalledVersion, bool Enabled, JsonElement Settings, HashSet<string> Granted);

    public async Task<Install?> GetInstallAsync(string plugin, string device, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT installed_version, enabled, settings::text, granted::text FROM lifelog.plugin_installs WHERE plugin_id = $1 AND device = $2");
        cmd.Parameters.Add(new NpgsqlParameter { Value = plugin });
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        await using var r = await cmd.ExecuteReaderAsync(ct);
        if (!await r.ReadAsync(ct)) return null;
        var granted = JsonDocument.Parse(r.GetString(3)).RootElement.EnumerateArray().Select(e => e.GetString()!).ToHashSet();
        return new Install(r.GetString(0), r.GetBoolean(1), JsonDocument.Parse(r.GetString(2)).RootElement.Clone(), granted);
    }

    public async Task<JsonElement> SetInstallAsync(string plugin, string device, string version, bool enabled, string? settingsJson, string grantedJson, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT lifelog.set_install($1, $2, $3, $4, $5::jsonb, $6::jsonb)");
        cmd.Parameters.Add(new NpgsqlParameter { Value = plugin });
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        cmd.Parameters.Add(new NpgsqlParameter { Value = version });
        cmd.Parameters.Add(new NpgsqlParameter { Value = enabled });
        cmd.Parameters.Add(new NpgsqlParameter { Value = (object?)settingsJson ?? DBNull.Value, NpgsqlDbType = NpgsqlDbType.Jsonb });
        cmd.Parameters.Add(new NpgsqlParameter { Value = grantedJson, NpgsqlDbType = NpgsqlDbType.Jsonb });
        var s = (string)(await cmd.ExecuteScalarAsync(ct))!;
        return JsonDocument.Parse(s).RootElement.Clone();
    }

    public async Task TouchLastSeenAsync(string device, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("UPDATE lifelog.devices SET last_seen = now() WHERE id = $1");
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        await cmd.ExecuteNonQueryAsync(ct);
    }

    public async Task<JsonElement?> StateGetAsync(string plugin, string device, string key, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand("SELECT value::text FROM lifelog.plugin_state WHERE plugin_id = $1 AND device = $2 AND key = $3");
        cmd.Parameters.Add(new NpgsqlParameter { Value = plugin });
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        cmd.Parameters.Add(new NpgsqlParameter { Value = key });
        var s = await cmd.ExecuteScalarAsync(ct) as string;
        return s is null ? null : JsonDocument.Parse(s).RootElement.Clone();
    }

    public async Task StateSetAsync(string plugin, string device, string key, string valueJson, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand(
            "INSERT INTO lifelog.plugin_state (plugin_id, device, key, value) VALUES ($1, $2, $3, $4::jsonb) " +
            "ON CONFLICT (plugin_id, device, key) DO UPDATE SET value = EXCLUDED.value");
        cmd.Parameters.Add(new NpgsqlParameter { Value = plugin });
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        cmd.Parameters.Add(new NpgsqlParameter { Value = key });
        cmd.Parameters.Add(new NpgsqlParameter { Value = valueJson, NpgsqlDbType = NpgsqlDbType.Jsonb });
        await cmd.ExecuteNonQueryAsync(ct);
    }

    /// <summary>한 테이블의 행 여러 개를 (device, id) upsert. 한 트랜잭션.</summary>
    public async Task UpsertAsync(TableMeta meta, IReadOnlyList<Row> rows, CancellationToken ct = default)
    {
        if (rows.Count == 0) return;
        var cols = meta.Columns.Keys.OrderBy(k => k, StringComparer.Ordinal).ToList();
        var timeCols = meta.IsSpan ? "start, \"end\"" : "time";
        var colList = string.Join(", ", cols.Select(Q));
        var updates = string.Join(", ", (meta.IsSpan ? new[] { "\"end\" = EXCLUDED.\"end\"" } : Array.Empty<string>())
            .Concat(cols.Select(c => $"{Q(c)} = EXCLUDED.{Q(c)}")));
        var n = 2 + (meta.IsSpan ? 2 : 1) + cols.Count;
        var placeholders = string.Join(", ", Enumerable.Range(1, n).Select(i => "$" + i));
        var sql = $"INSERT INTO lifelog.{Q(meta.Table)} (device, id, {timeCols}{(cols.Count > 0 ? ", " + colList : "")}) VALUES ({placeholders}) " +
                  $"ON CONFLICT (device, id) DO UPDATE SET {updates}";

        await using var conn = await _ds.OpenConnectionAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        await using var batch = new NpgsqlBatch(conn, tx);
        foreach (var r in rows)
        {
            var cmd = new NpgsqlBatchCommand(sql);
            cmd.Parameters.Add(new NpgsqlParameter { Value = r.Device });
            cmd.Parameters.Add(new NpgsqlParameter { Value = r.Id, NpgsqlDbType = NpgsqlDbType.Uuid });
            cmd.Parameters.Add(new NpgsqlParameter { Value = r.Start.UtcDateTime, NpgsqlDbType = NpgsqlDbType.TimestampTz });
            if (meta.IsSpan)
                cmd.Parameters.Add(new NpgsqlParameter { Value = (r.End ?? r.Start).UtcDateTime, NpgsqlDbType = NpgsqlDbType.TimestampTz });
            foreach (var c in cols)
                cmd.Parameters.Add(Param(meta.Columns[c], r.Cols.TryGetValue(c, out var v) ? v : default));
            batch.BatchCommands.Add(cmd);
        }
        await batch.ExecuteNonQueryAsync(ct);
        await tx.CommitAsync(ct);
    }

    public async Task DeleteAsync(string table, string device, Guid id, CancellationToken ct = default)
    {
        await using var cmd = _ds.CreateCommand($"DELETE FROM lifelog.{Q(table)} WHERE device = $1 AND id = $2");
        cmd.Parameters.Add(new NpgsqlParameter { Value = device });
        cmd.Parameters.Add(new NpgsqlParameter { Value = id, NpgsqlDbType = NpgsqlDbType.Uuid });
        await cmd.ExecuteNonQueryAsync(ct);
    }

    private static string Q(string ident) => "\"" + ident.Replace("\"", "\"\"") + "\"";

    /// <summary>매니페스트 열 타입에 맞춰 JSON 값을 파라미터로. null/undefined는 NULL.</summary>
    private static NpgsqlParameter Param(string type, JsonElement v)
    {
        bool isNull = v.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null;
        return type switch
        {
            "text" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Text, Value = isNull ? DBNull.Value : (v.ValueKind == JsonValueKind.String ? v.GetString()! : v.GetRawText()) },
            "boolean" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Boolean, Value = isNull ? DBNull.Value : v.GetBoolean() },
            "integer" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Integer, Value = isNull ? DBNull.Value : (int)v.GetDouble() },
            "bigint" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Bigint, Value = isNull ? DBNull.Value : (long)v.GetDouble() },
            "double precision" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Double, Value = isNull ? DBNull.Value : v.GetDouble() },
            "numeric" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Numeric, Value = isNull ? DBNull.Value : v.GetDecimal() },
            "timestamptz" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.TimestampTz, Value = isNull ? DBNull.Value : DateTimeOffset.Parse(v.GetString()!).UtcDateTime },
            "date" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Date, Value = isNull ? DBNull.Value : DateOnly.Parse(v.GetString()!) },
            "jsonb" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Jsonb, Value = isNull ? DBNull.Value : v.GetRawText() },
            "text[]" => new NpgsqlParameter { NpgsqlDbType = NpgsqlDbType.Array | NpgsqlDbType.Text, Value = isNull ? DBNull.Value : v.EnumerateArray().Select(e => e.GetString()).ToArray() },
            _ => throw new InvalidOperationException($"모르는 열 타입: {type}"),
        };
    }

    public ValueTask DisposeAsync() => _ds.DisposeAsync();
}
