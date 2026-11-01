package dev.hyeseong.lifelog.collector.core

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** 구간 관리(Documents/수집기 호스트 API.md 3절). id 생성·열린 구간의 주기 upsert·큐 투입. 디스패처 스레드에서만. */
class SpanManager(private val q: Queue, private val device: String) {
    private class Open(val table: String, val id: UUID, val start: Instant, val cols: JSONObject)
    private val open = HashMap<UUID, Open>()
    val openCount get() = open.size

    private fun identity(o: Manifest.Output, cols: JSONObject): List<Any?> = o.identity.map { c -> cols.opt(c).let { if (it == JSONObject.NULL) null else it } }
    private fun max(a: Instant, b: Instant) = if (a > b) a else b

    fun open(m: Manifest, o: Manifest.Output, start: Instant, cols: JSONObject): UUID {
        require(o.isSpan) { "${o.name}은 구간 출력이 아닙니다" }
        val id = Ids.rowId(m.id, device, o.name, start, identity(o, cols))
        val table = m.tableName(o)
        open[id] = Open(table, id, start, cols)
        q.enqueueUpsert(Row(table, device, id.toString(), start.toString(), start.toString(), cols))
        return id
    }

    fun update(id: UUID, now: Instant, patch: JSONObject) {
        val s = open[id] ?: throw IllegalStateException("열린 구간이 아닙니다: $id")
        for (k in patch.keys()) s.cols.put(k, patch.get(k))
        q.enqueueUpsert(Row(s.table, device, id.toString(), s.start.toString(), max(now, s.start).toString(), s.cols))
    }

    fun close(id: UUID, end: Instant) {
        val s = open.remove(id) ?: throw IllegalStateException("열린 구간이 아닙니다: $id")
        q.enqueueUpsert(Row(s.table, device, id.toString(), s.start.toString(), max(end, s.start).toString(), s.cols))
    }

    fun tick(now: Instant) {
        for (s in open.values) q.enqueueUpsert(Row(s.table, device, s.id.toString(), s.start.toString(), max(now, s.start).toString(), s.cols))
    }

    fun put(m: Manifest, o: Manifest.Output, start: Instant, end: Instant, cols: JSONObject, key: String?): UUID {
        require(o.isSpan) { "${o.name}은 구간 출력이 아닙니다" }
        val id = if (key == null) Ids.rowId(m.id, device, o.name, start, identity(o, cols)) else Ids.rowId(m.id, device, o.name, key)
        q.enqueueUpsert(Row(m.tableName(o), device, id.toString(), start.toString(), max(end, start).toString(), cols))
        return id
    }

    fun emit(m: Manifest, o: Manifest.Output, time: Instant, cols: JSONObject, key: String?): UUID {
        require(!o.isSpan) { "${o.name}은 시점 출력이 아닙니다" }
        val id = if (key == null) Ids.rowId(m.id, device, o.name, time, identity(o, cols)) else Ids.rowId(m.id, device, o.name, key)
        q.enqueueUpsert(Row(m.tableName(o), device, id.toString(), time.toString(), null, cols))
        return id
    }

    fun delete(m: Manifest, o: Manifest.Output, id: UUID) {
        open.remove(id)
        q.enqueueDelete(m.tableName(o), device, id.toString())
    }

    fun closeAll(now: Instant) { for (id in open.keys.toList()) close(id, now) }
}
