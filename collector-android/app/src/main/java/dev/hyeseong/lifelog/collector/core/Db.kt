package dev.hyeseong.lifelog.collector.core

import org.json.JSONArray
import org.json.JSONObject
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/** DB 접근(JDBC). 기기 롤로 붙는다. 연결은 하나를 유지하고 끊기면 다시 연다. 호출은 업로더·디스패처 스레드에서. */
class Db(private val cfg: Config) : AutoCloseable {
    companion object {
        /** 이 앱이 아는 코어 스키마 버전. DB가 더 높으면 쓰기를 멈춘다. */
        const val KNOWN_SCHEMA_VERSION = 5
        init { Class.forName("org.postgresql.Driver") }
    }

    private var conn: Connection? = null
    var clockOffsetMs = 0L; private set

    @Synchronized
    fun connection(): Connection {
        conn?.let { if (!it.isClosed && it.isValid(5)) return it }
        val c = DriverManager.getConnection(cfg.jdbcUrl(), cfg.user, cfg.password)
        c.autoCommit = true
        conn = c
        return c
    }

    fun schemaVersion(): Int = connection().createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(max(version), 0) FROM lifelog.schema_migrations").use { r -> r.next(); r.getInt(1) }
    }

    fun syncClock() {
        val before = System.currentTimeMillis()
        val dbNow = connection().createStatement().use { st -> st.executeQuery("SELECT now()").use { r -> r.next(); r.getTimestamp(1).time } }
        val after = System.currentTimeMillis()
        clockOffsetMs = dbNow - (before + after) / 2
    }

    fun now(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() + clockOffsetMs)

    fun registerPlugin(m: Manifest, device: String): JSONObject = connection().prepareStatement("SELECT lifelog.register_plugin(?::jsonb, ?)").use { ps ->
        ps.setString(1, m.raw); ps.setString(2, device)
        ps.executeQuery().use { r -> r.next(); JSONObject(r.getString(1)) }
    }

    class Install(val installedVersion: String, val enabled: Boolean, val settings: JSONObject, val granted: Set<String>)

    fun getInstall(plugin: String, device: String): Install? = connection().prepareStatement(
        "SELECT installed_version, enabled, settings::text, granted::text FROM lifelog.plugin_installs WHERE plugin_id = ? AND device = ?").use { ps ->
        ps.setString(1, plugin); ps.setString(2, device)
        ps.executeQuery().use { r ->
            if (!r.next()) return null
            val g = JSONArray(r.getString(4)); val set = HashSet<String>(); for (i in 0 until g.length()) set.add(g.getString(i))
            Install(r.getString(1), r.getBoolean(2), JSONObject(r.getString(3)), set)
        }
    }

    fun setInstall(plugin: String, device: String, version: String, enabled: Boolean, settingsJson: String?, grantedJson: String): JSONObject =
        connection().prepareStatement("SELECT lifelog.set_install(?, ?, ?, ?, ?::jsonb, ?::jsonb)").use { ps ->
            ps.setString(1, plugin); ps.setString(2, device); ps.setString(3, version); ps.setBoolean(4, enabled)
            if (settingsJson == null) ps.setNull(5, Types.OTHER) else ps.setString(5, settingsJson)
            ps.setString(6, grantedJson)
            ps.executeQuery().use { r -> r.next(); JSONObject(r.getString(1)) }
        }

    fun touchLastSeen(device: String) = connection().prepareStatement("UPDATE lifelog.devices SET last_seen = now() WHERE id = ?").use { ps -> ps.setString(1, device); ps.executeUpdate() }

    fun stateGet(plugin: String, device: String, key: String): String? = connection().prepareStatement(
        "SELECT value::text FROM lifelog.plugin_state WHERE plugin_id = ? AND device = ? AND key = ?").use { ps ->
        ps.setString(1, plugin); ps.setString(2, device); ps.setString(3, key)
        ps.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    fun stateSet(plugin: String, device: String, key: String, valueJson: String) = connection().prepareStatement(
        "INSERT INTO lifelog.plugin_state (plugin_id, device, key, value) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT (plugin_id, device, key) DO UPDATE SET value = EXCLUDED.value").use { ps ->
        ps.setString(1, plugin); ps.setString(2, device); ps.setString(3, key); ps.setString(4, valueJson); ps.executeUpdate()
    }

    /** Android의 java.sql.Timestamp에는 from(Instant)이 없다 */
    private fun ts(iso: String): Timestamp { val i = Instant.parse(iso); return Timestamp(i.toEpochMilli()).also { it.nanos = i.nano } }
    private fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""

    /** 한 테이블의 행 여러 개를 (device, id) upsert. 한 트랜잭션. */
    fun upsert(meta: TableMeta, rows: List<Row>) {
        if (rows.isEmpty()) return
        val cols = meta.columns.keys.sorted()
        val timeCols = if (meta.isSpan) "start, \"end\"" else "time"
        val colList = cols.joinToString(", ") { q(it) }
        val updates = (if (meta.isSpan) listOf("\"end\" = EXCLUDED.\"end\"") else emptyList<String>()) + cols.map { "${q(it)} = EXCLUDED.${q(it)}" }
        val n = 2 + (if (meta.isSpan) 2 else 1) + cols.size
        val sql = "INSERT INTO lifelog.${q(meta.table)} (device, id, $timeCols${if (cols.isEmpty()) "" else ", $colList"}) VALUES (${List(n) { "?" }.joinToString(", ")}) " +
            "ON CONFLICT (device, id) DO UPDATE SET ${updates.joinToString(", ")}"
        val c = connection()
        c.autoCommit = false
        try {
            c.prepareStatement(sql).use { ps ->
                for (r in rows) {
                    var i = 1
                    ps.setString(i++, r.device)
                    ps.setObject(i++, UUID.fromString(r.id))
                    ps.setTimestamp(i++, ts(r.start))
                    if (meta.isSpan) ps.setTimestamp(i++, ts(r.end ?: r.start))
                    for (col in cols) setParam(ps, i++, meta.columns.getValue(col), r.cols.opt(col))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (e: Exception) { c.rollback(); throw e } finally { c.autoCommit = true }
    }

    fun delete(table: String, device: String, id: String) = connection().prepareStatement("DELETE FROM lifelog.${q(table)} WHERE device = ? AND id = ?").use { ps ->
        ps.setString(1, device); ps.setObject(2, UUID.fromString(id)); ps.executeUpdate()
    }

    private fun setParam(ps: java.sql.PreparedStatement, i: Int, type: String, v: Any?) {
        val isNull = v == null || v == JSONObject.NULL
        when (type) {
            "text" -> if (isNull) ps.setNull(i, Types.VARCHAR) else ps.setString(i, v.toString())
            "boolean" -> if (isNull) ps.setNull(i, Types.BOOLEAN) else ps.setBoolean(i, v as Boolean)
            "integer" -> if (isNull) ps.setNull(i, Types.INTEGER) else ps.setInt(i, (v as Number).toInt())
            "bigint" -> if (isNull) ps.setNull(i, Types.BIGINT) else ps.setLong(i, (v as Number).toLong())
            "double precision" -> if (isNull) ps.setNull(i, Types.DOUBLE) else ps.setDouble(i, (v as Number).toDouble())
            "numeric" -> if (isNull) ps.setNull(i, Types.NUMERIC) else ps.setBigDecimal(i, java.math.BigDecimal(v.toString()))
            "timestamptz" -> if (isNull) ps.setNull(i, Types.TIMESTAMP) else ps.setTimestamp(i, ts(v.toString()))
            "date" -> if (isNull) ps.setNull(i, Types.DATE) else ps.setDate(i, java.sql.Date.valueOf(v.toString()))
            "jsonb" -> { val o = org.postgresql.util.PGobject(); o.type = "jsonb"; o.value = if (isNull) null else v.toString(); ps.setObject(i, o) }
            "text[]" -> if (isNull) ps.setNull(i, Types.ARRAY) else {
                val a = v as JSONArray; ps.setArray(i, ps.connection.createArrayOf("text", Array(a.length()) { a.getString(it) }))
            }
            else -> throw IllegalStateException("모르는 열 타입: $type")
        }
    }

    override fun close() { try { conn?.close() } catch (_: Exception) {} }
}
