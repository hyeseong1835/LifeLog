package dev.hyeseong.lifelog.collector.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 큐 → DB. 실패하면 큐에 남기고 5초~2분 물러났다 재시도. */
class Uploader(private val q: Queue, private val db: Db, private val meta: (String) -> TableMeta?, private val log: (String) -> Unit) {
    @Volatile var lastError: String? = null; private set

    suspend fun run() {
        var backoff = 5_000L
        while (true) {
            try {
                val ops = q.peek(500)
                if (ops.isEmpty()) { delay(5_000); continue }
                var i = 0
                val sent = LinkedHashMap<String, Int>()
                while (i < ops.size) {
                    val first = ops[i]
                    var j = i
                    while (j < ops.size && ops[j].kind == first.kind && ops[j].table == first.table) j++
                    val chunk = ops.subList(i, j)
                    if (first.kind == "upsert") {
                        val m = meta(first.table) ?: throw IllegalStateException("테이블 메타가 없습니다: ${first.table}")
                        db.upsert(m, chunk.map { Row.fromJson(it.payload) })
                    } else {
                        for (o in chunk) { val d = JSONObject(o.payload); db.delete(first.table, d.getString("device"), d.getString("id")) }
                    }
                    q.ack(chunk.map { it.seq })
                    sent[first.table] = (sent[first.table] ?: 0) + chunk.size
                    i = j
                }
                lastError = null
                backoff = 5_000L
                log("전송 " + sent.entries.joinToString(", ") { "${it.key} ${it.value}" })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                lastError = e.message
                log("전송 실패, ${backoff / 1000}초 뒤 재시도: ${e.message}")
                delay(backoff)
                backoff = minOf(backoff * 2, 120_000L)
            }
        }
    }
}
