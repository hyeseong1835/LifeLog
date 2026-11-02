package dev.hyeseong.lifelog.collector.core

import org.json.JSONArray
import org.json.JSONObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

/**
 * 렌더러의 lifelog.query(table, opts)를 SQL로 바꾼다. collector-windows/src/LifeLog.Core/Query.cs와 같은 규칙.
 * 식별자는 전부 따옴표, 값은 파라미터. 매니페스트에 없는 열은 SQL에 닿기 전에 거부한다.
 * JDBC는 같은 파라미터를 두 번 쓸 수 없어서, GROUP BY에 들어가는 시간 단위·시간대는 화이트리스트·형식 검사 뒤 리터럴로 넣는다.
 */
class QuerySpec(
    val table: String, val start: Instant, val end: Instant, val device: String?,
    val where: Map<String, List<Any?>>, val groupTime: String?, val groupBy: List<String>,
    val agg: Map<String, String>, val tz: String, val limit: Int,
) {
    companion object {
        const val MAX_LIMIT = 50000
        fun parse(table: String, opts: JSONObject): QuerySpec {
            val range = opts.getJSONObject("range")
            val where = LinkedHashMap<String, List<Any?>>()
            opts.optJSONObject("where")?.let { w -> for (k in w.keys()) { val v = w.get(k); where[k] = if (v is JSONArray) (0 until v.length()).map { v.get(it) } else listOf(v) } }
            val group = opts.optJSONObject("group")
            val agg = LinkedHashMap<String, String>()
            opts.optJSONObject("agg")?.let { a -> for (k in a.keys()) agg[k] = a.getString(k) }
            return QuerySpec(
                table, time(range.get("start")), time(range.get("end")),
                opts.optString("device").takeIf { opts.has("device") && it.isNotEmpty() },
                where, group?.optString("time")?.takeIf { group.has("time") },
                group?.optJSONArray("by")?.let { b -> (0 until b.length()).map { b.getString(it) } } ?: emptyList(),
                agg, opts.optString("tz").takeIf { it.isNotEmpty() } ?: "UTC",
                if (opts.has("limit")) opts.getInt("limit").coerceIn(1, MAX_LIMIT) else 5000,
            )
        }
        fun time(v: Any): Instant = when (v) {
            is Number -> Instant.ofEpochMilli(v.toLong())
            is String -> java.time.OffsetDateTime.parse(v).toInstant()
            else -> throw IllegalArgumentException("시각이 아닙니다")
        }
    }
}

object QueryBuilder {
    private val TIME_UNITS = setOf("hour", "day", "week", "month")
    private val NUMERIC = setOf("integer", "bigint", "double precision", "numeric")
    private fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""

    class Built(val sql: String, val params: List<(PreparedStatement, Int) -> Unit>)

    /** meta.columns는 자유 열만(공통 열 제외). */
    fun build(qs: QuerySpec, meta: TableMeta): Built {
        val ps = ArrayList<(PreparedStatement, Int) -> Unit>()
        fun p(set: (PreparedStatement, Int) -> Unit): String { ps += set; return "?" }
        fun ts(i: Instant) = p { s, n -> s.setTimestamp(n, Timestamp(i.toEpochMilli()).apply { nanos = i.nano }) }
        fun colOf(c: String) = if (meta.columns.containsKey(c)) q(c) else throw IllegalArgumentException("선언되지 않은 열: $c")
        val tcol = if (meta.isSpan) "start" else "time"
        val where = ArrayList<String>()
        where += if (meta.isSpan) "\"end\" >= ${ts(qs.start)} AND start < ${ts(qs.end)}" else "time >= ${ts(qs.start)} AND time < ${ts(qs.end)}"
        qs.device?.let { d -> where += "device = ${p { s, n -> s.setString(n, d) }}" }
        for ((col, vals) in qs.where) {
            val c = colOf(col); val type = meta.columns.getValue(col)
            val list = vals.map { v -> p { s, n -> bind(s, n, type, v) } }
            where += if (list.size == 1) "$c = ${list[0]}" else "$c IN (${list.joinToString(", ")})"
        }
        val whereSql = where.joinToString(" AND ")
        val table = "lifelog." + q(qs.table)
        if (qs.groupTime == null && qs.groupBy.isEmpty()) {
            val cols = meta.columns.keys.sorted().joinToString(", ") { q(it) }
            val common = if (meta.isSpan) "device, id, start, \"end\"" else "device, id, time"
            return Built("SELECT $common${if (cols.isNotEmpty()) ", $cols" else ""} FROM $table WHERE $whereSql ORDER BY $tcol LIMIT ${qs.limit + 1}", ps)
        }
        if (qs.agg.isEmpty()) throw IllegalArgumentException("group이 있으면 agg가 필요합니다")
        val groupExprs = ArrayList<String>(); val selectExprs = ArrayList<String>()
        qs.groupTime?.let { unit ->
            if (unit !in TIME_UNITS) throw IllegalArgumentException("group.time은 hour|day|week|month")
            if (!Regex("^[A-Za-z0-9_+\\-/]{1,64}$").matches(qs.tz)) throw IllegalArgumentException("tz 형식이 잘못됐습니다")
            val tzLit = "'" + qs.tz.replace("'", "''") + "'"
            val expr = "date_trunc('$unit', $tcol AT TIME ZONE $tzLit) AT TIME ZONE $tzLit"
            groupExprs += expr; selectExprs += "$expr AS \"time\""
        }
        for (b in qs.groupBy) { val c = colOf(b); groupExprs += c; selectExprs += c }
        for ((key, fn) in qs.agg) {
            if (key == "time" || meta.columns.containsKey(key)) throw IllegalArgumentException("agg 키 ${key}는 열 이름과 겹칩니다")
            val expr = when {
                fn == "count" -> "count(*)"
                fn == "duration" -> {
                    if (!meta.isSpan) throw IllegalArgumentException("duration은 구간 출력에만")
                    "sum(extract(epoch from (least(\"end\", ${ts(qs.end)}) - greatest(start, ${ts(qs.start)}))))"
                }
                else -> {
                    val parts = fn.split(":", limit = 2)
                    if (parts.size != 2 || parts[0] !in setOf("sum", "avg", "min", "max")) throw IllegalArgumentException("모르는 집계: $fn")
                    val c = colOf(parts[1])
                    if (meta.columns.getValue(parts[1]) !in NUMERIC) throw IllegalArgumentException("${parts[1]}은 숫자 열이 아닙니다")
                    "${parts[0]}($c)"
                }
            }
            selectExprs += "$expr AS ${q(key)}"
        }
        val groupSql = if (groupExprs.isNotEmpty()) " GROUP BY " + groupExprs.joinToString(", ") else ""
        val orderSql = if (groupExprs.isNotEmpty()) " ORDER BY " + groupExprs.joinToString(", ") else ""
        // 파라미터 순서: SELECT의 duration(end, start) → WHERE. SQL 문자열 순서대로 다시 맞춘다
        return Built("SELECT ${selectExprs.joinToString(", ")} FROM $table WHERE $whereSql$groupSql$orderSql LIMIT ${qs.limit + 1}", reorder(ps, selectExprs, whereSql))
    }

    /** ps는 WHERE를 먼저 만들고 SELECT(duration)를 나중에 만들었다. SQL에서는 SELECT가 먼저 나오므로 순서를 바꾼다. */
    private fun reorder(ps: List<(PreparedStatement, Int) -> Unit>, select: List<String>, whereSql: String): List<(PreparedStatement, Int) -> Unit> {
        val nWhere = whereSql.count { it == '?' }
        val nSelect = select.sumOf { s -> s.count { it == '?' } }
        if (nSelect == 0) return ps
        return ps.subList(nWhere, nWhere + nSelect) + ps.subList(0, nWhere)
    }

    private fun bind(s: PreparedStatement, n: Int, type: String, v: Any?) {
        if (v == null || v == JSONObject.NULL) { s.setNull(n, Types.NULL); return }
        when (type) {
            "text" -> s.setString(n, v.toString())
            "boolean" -> s.setBoolean(n, v as? Boolean ?: v.toString().toBoolean())
            "integer" -> s.setInt(n, (v as Number).toInt())
            "bigint" -> s.setLong(n, (v as Number).toLong())
            "double precision" -> s.setDouble(n, (v as Number).toDouble())
            "numeric" -> s.setBigDecimal(n, java.math.BigDecimal(v.toString()))
            else -> s.setString(n, v.toString())
        }
    }
}

/** 연결 하나로 query를 실행한다(단위 테스트가 Config 없이 부른다). */
fun runQuery(conn: Connection, spec: QuerySpec, meta: TableMeta): JSONObject {
    val b = QueryBuilder.build(spec, meta)
    return conn.prepareStatement(b.sql).use { ps ->
        b.params.forEachIndexed { i, set -> set(ps, i + 1) }
        ps.executeQuery().use { r ->
            val md = r.metaData; val rows = JSONArray()
            while (r.next()) {
                val o = JSONObject()
                for (i in 1..md.columnCount) {
                    val v: Any? = when (val x = r.getObject(i)) {
                        null -> JSONObject.NULL
                        is java.sql.Timestamp -> x.toInstant().toString()
                        is java.util.UUID -> x.toString()
                        is java.math.BigDecimal -> x.toDouble()
                        is org.postgresql.util.PGobject -> x.value
                        is Number, is Boolean, is String -> x
                        else -> x.toString()
                    }
                    o.put(md.getColumnLabel(i), v)
                }
                rows.put(o)
            }
            val truncated = rows.length() > spec.limit
            if (truncated) rows.remove(rows.length() - 1)
            JSONObject().put("rows", rows).put("truncated", truncated)
        }
    }
}
