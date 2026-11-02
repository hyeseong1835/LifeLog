package dev.hyeseong.lifelog.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * 휴대폰 대시보드의 쿼리 빌더(Query.kt)를 로컬 시험 DB(Docker lifelog-pg, localhost:54329)에 대 본다.
 * DB가 없으면 건너뛴다. 같은 질의를 손으로 쓴 SQL과 비교해 파라미터 순서·형 변환을 확인한다.
 */
class QueryTest {
    private fun conn(): Connection? = try {
        Class.forName("org.postgresql.Driver")
        DriverManager.getConnection("jdbc:postgresql://localhost:54329/lifelog?connectTimeout=3", "postgres", "lifelog")
    } catch (e: Exception) { null }

    private val start = Instant.parse("2026-10-31T19:00:00Z")   // 11/1 04:00 KST
    private val end = Instant.parse("2026-11-01T19:00:00Z")
    private val window = TableMeta("win-window/window", true, mapOf("app" to "text", "title" to "text", "pid" to "integer"))
    private val point = TableMeta("health/point", false, mapOf("metric" to "text", "value" to "double precision", "unit" to "text"))

    private fun scalar(c: Connection, sql: String, vararg args: Any): Double = c.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> if (a is Instant) ps.setTimestamp(i + 1, java.sql.Timestamp(a.toEpochMilli())) else ps.setObject(i + 1, a) }
        ps.executeQuery().use { r -> r.next(); r.getDouble(1) }
    }

    @Test fun plainSpanRows() {
        val c = conn(); assumeTrue("로컬 DB 없음", c != null); c!!.use {
            val spec = QuerySpec("win-window/window", start, end, null, emptyMap(), null, emptyList(), emptyMap(), "UTC", 5000)
            val res = runQuery(it, spec, window)
            val n = scalar(it, "SELECT count(*) FROM lifelog.\"win-window/window\" WHERE \"end\" >= ? AND start < ?", start, end).toInt()
            assertEquals(minOf(n, 5000), res.getJSONArray("rows").length())
            if (n > 0) { val r = res.getJSONArray("rows").getJSONObject(0); assertTrue(r.getString("start").endsWith("Z")); assertTrue(r.has("app")) }
        }
    }

    @Test fun pointWhereIn() {
        val c = conn(); assumeTrue(c != null); c!!.use {
            val spec = QuerySpec("health/point", start, end, null, mapOf("metric" to listOf("heart_rate", "skin_temp")), null, emptyList(), emptyMap(), "UTC", 20000)
            val res = runQuery(it, spec, point)
            val n = scalar(it, "SELECT count(*) FROM lifelog.\"health/point\" WHERE time >= ? AND time < ? AND metric IN ('heart_rate','skin_temp')", start, end).toInt()
            assertEquals(n, res.getJSONArray("rows").length())
        }
    }

    @Test fun groupByColumnWithDuration() {   // SELECT의 duration 파라미터가 WHERE보다 먼저 나온다
        val c = conn(); assumeTrue(c != null); c!!.use {
            val spec = QuerySpec("win-window/window", start, end, null, emptyMap(), null, listOf("app"), mapOf("dur" to "duration"), "UTC", 20000)
            val res = runQuery(it, spec, window)
            var total = 0.0; val rows = res.getJSONArray("rows"); for (i in 0 until rows.length()) total += rows.getJSONObject(i).getDouble("dur")
            val expect = scalar(it, "SELECT COALESCE(sum(extract(epoch from (least(\"end\", ?) - greatest(start, ?)))),0) FROM lifelog.\"win-window/window\" WHERE \"end\" >= ? AND start < ?", end, start, start, end)
            assertEquals(expect, total, 0.5)
        }
    }

    @Test fun groupByTimeWithAvg() {
        val c = conn(); assumeTrue(c != null); c!!.use {
            val spec = QuerySpec("health/point", start, end, null, mapOf("metric" to listOf("heart_rate")), "hour", emptyList(), mapOf("n" to "count", "avg" to "avg:value"), "Asia/Seoul", 1000)
            val res = runQuery(it, spec, point)
            var n = 0; val rows = res.getJSONArray("rows"); for (i in 0 until rows.length()) n += rows.getJSONObject(i).getInt("n")
            val expect = scalar(it, "SELECT count(*) FROM lifelog.\"health/point\" WHERE time >= ? AND time < ? AND metric = 'heart_rate'", start, end).toInt()
            assertEquals(expect, n)
        }
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownColumn() {
        QueryBuilder.build(QuerySpec("health/point", start, end, null, mapOf("nope" to listOf("x")), null, emptyList(), emptyMap(), "UTC", 10), point)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadTz() {
        QueryBuilder.build(QuerySpec("health/point", start, end, null, emptyMap(), "hour", emptyList(), mapOf("n" to "count"), "UTC'; drop", 10), point)
    }
}
