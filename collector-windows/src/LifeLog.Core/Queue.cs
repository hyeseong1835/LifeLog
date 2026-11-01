using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace LifeLog.Core;

/// <summary>
/// 로컬 큐(SQLite). DB가 꺼져 있어도 행을 잃지 않는다. 같은 (table, device, id)의 upsert가 다시 들어오면
/// 기존 대기 항목을 최신 상태로 바꾼다(열린 구간을 1분마다 늘려도 큐가 불지 않는다).
/// </summary>
public sealed class Queue : IDisposable
{
    private readonly SqliteConnection _c;
    private readonly object _lock = new();

    public Queue(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        _c = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path }.ToString());
        _c.Open();
        Exec("PRAGMA journal_mode=WAL");
        Exec("""
            CREATE TABLE IF NOT EXISTS ops (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              op TEXT NOT NULL,            -- upsert | delete
              key TEXT NOT NULL,           -- table|device|id
              tbl TEXT NOT NULL,
              payload TEXT NOT NULL,       -- Row JSON (delete면 device,id만)
              created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ops_key ON ops(key);
            """);
    }

    public void EnqueueUpsert(Row row)
    {
        lock (_lock)
        {
            using var tx = _c.BeginTransaction();
            // 같은 키의 대기 중 upsert는 하나만 둔다(seq는 유지해 순서를 지킨다)
            using (var up = _c.CreateCommand())
            {
                up.Transaction = tx;
                up.CommandText = "UPDATE ops SET payload = $p, created_at = $t WHERE key = $k AND op = 'upsert'";
                up.Parameters.AddWithValue("$p", JsonSerializer.Serialize(row));
                up.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToString("O"));
                up.Parameters.AddWithValue("$k", row.Key);
                if (up.ExecuteNonQuery() == 0)
                {
                    using var ins = _c.CreateCommand();
                    ins.Transaction = tx;
                    ins.CommandText = "INSERT INTO ops (op, key, tbl, payload, created_at) VALUES ('upsert', $k, $tbl, $p, $t)";
                    ins.Parameters.AddWithValue("$k", row.Key);
                    ins.Parameters.AddWithValue("$tbl", row.Table);
                    ins.Parameters.AddWithValue("$p", JsonSerializer.Serialize(row));
                    ins.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToString("O"));
                    ins.ExecuteNonQuery();
                }
            }
            tx.Commit();
        }
    }

    public void EnqueueDelete(string table, string device, Guid id)
    {
        lock (_lock)
        {
            using var tx = _c.BeginTransaction();
            var key = table + "|" + device + "|" + id;
            using (var del = _c.CreateCommand())
            {
                del.Transaction = tx;
                del.CommandText = "DELETE FROM ops WHERE key = $k";   // 대기 중인 upsert는 의미가 없어진다
                del.Parameters.AddWithValue("$k", key);
                del.ExecuteNonQuery();
            }
            using (var ins = _c.CreateCommand())
            {
                ins.Transaction = tx;
                ins.CommandText = "INSERT INTO ops (op, key, tbl, payload, created_at) VALUES ('delete', $k, $tbl, $p, $t)";
                ins.Parameters.AddWithValue("$k", key);
                ins.Parameters.AddWithValue("$tbl", table);
                ins.Parameters.AddWithValue("$p", JsonSerializer.Serialize(new { device, id }));
                ins.Parameters.AddWithValue("$t", DateTimeOffset.UtcNow.ToString("O"));
                ins.ExecuteNonQuery();
            }
            tx.Commit();
        }
    }

    public sealed record Op(long Seq, string Kind, string Table, string Payload);

    public List<Op> Peek(int limit)
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "SELECT seq, op, tbl, payload FROM ops ORDER BY seq LIMIT $n";
            cmd.Parameters.AddWithValue("$n", limit);
            using var r = cmd.ExecuteReader();
            var list = new List<Op>();
            while (r.Read()) list.Add(new Op(r.GetInt64(0), r.GetString(1), r.GetString(2), r.GetString(3)));
            return list;
        }
    }

    public void Ack(IEnumerable<long> seqs)
    {
        lock (_lock)
        {
            using var tx = _c.BeginTransaction();
            foreach (var s in seqs)
            {
                using var cmd = _c.CreateCommand();
                cmd.Transaction = tx;
                cmd.CommandText = "DELETE FROM ops WHERE seq = $s";
                cmd.Parameters.AddWithValue("$s", s);
                cmd.ExecuteNonQuery();
            }
            tx.Commit();
        }
    }

    public long Count()
    {
        lock (_lock)
        {
            using var cmd = _c.CreateCommand();
            cmd.CommandText = "SELECT count(*) FROM ops";
            return (long)cmd.ExecuteScalar()!;
        }
    }

    private void Exec(string sql)
    {
        using var cmd = _c.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    public void Dispose() => _c.Dispose();
}
