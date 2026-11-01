package dev.hyeseong.lifelog.collector.sources

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Health Connect 읽기 소스. read({ metric, from, to }) → [{ start, end? | time, value, unit, category?, extra? }].
 * 소스는 평탄화만 하고 해석하지 않는다. 1차는 시간 범위 읽기(변경 토큰은 나중).
 * metric: steps(시간별) | steps_daily | distance_daily | active_kcal_daily | total_kcal_daily | floors_daily
 *         heart_rate | resting_heart_rate_daily | sleep | sleep_stage | exercise | weight | spo2 | hrv | body_temp
 */
class HealthSource(private val context: Context) : ReadSource {
    override val id = "health"
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone: ZoneId = ZoneId.systemDefault()

    companion object {
        val PERMISSIONS: Set<String> = setOf(
            StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, FloorsClimbedRecord::class,
            HeartRateRecord::class, RestingHeartRateRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class, WeightRecord::class,
            OxygenSaturationRecord::class, HeartRateVariabilityRmssdRecord::class, BodyTemperatureRecord::class,
        ).map { HealthPermission.getReadPermission(it) }.toSet() + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        fun available(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    /** 시점 지표 → 레코드 종류. 변경 토큰 읽기는 이 지표들만 지원한다(집계 지표는 2일치 재읽기가 싸다). */
    private val pointTypes: Map<String, KClass<out Record>> = mapOf(
        "heart_rate" to HeartRateRecord::class, "spo2" to OxygenSaturationRecord::class, "hrv" to HeartRateVariabilityRmssdRecord::class,
        "body_temp" to BodyTemperatureRecord::class, "weight" to WeightRecord::class)

    private fun pointsOf(metric: String, records: List<Record>, out: JSONArray) {
        fun point(time: Instant, value: Double, unit: String) = out.put(JSONObject().put("time", time.toString()).put("value", value).put("unit", unit))
        for (r in records) when (r) {
            is HeartRateRecord -> r.samples.forEach { s -> point(s.time, s.beatsPerMinute.toDouble(), "bpm") }
            is OxygenSaturationRecord -> point(r.time, r.percentage.value, "%")
            is HeartRateVariabilityRmssdRecord -> point(r.time, r.heartRateVariabilityMillis, "ms")
            is BodyTemperatureRecord -> point(r.time, r.temperature.inCelsius, "C")
            is WeightRecord -> point(r.time, r.weight.inKilograms, "kg")
            else -> {}
        }
    }

    /**
     * 변경 토큰 읽기. read({ metric, token }) → { rows, token, reset }.
     * token이 비어 있거나 만료됐으면 새 토큰을 만들고 지난 2일을 통째로 읽는다(reset=true). 삭제 변경은 무시한다(시점 행은 남는다).
     */
    private suspend fun readChanges(metric: String, token: String): String {
        val cls = pointTypes[metric] ?: throw IllegalArgumentException("변경 토큰을 지원하지 않는 지표: $metric")
        val rows = JSONArray()
        suspend fun reset(): String {
            val t = client.getChangesToken(ChangesTokenRequest(setOf(cls)))
            val now = Instant.now()
            pointsOf(metric, read(cls, TimeRangeFilter.between(now.minus(Duration.ofDays(2)), now)), rows)
            return JSONObject().put("rows", rows).put("token", t).put("reset", true).toString()
        }
        if (token.isEmpty()) return reset()
        var next = token
        val recs = ArrayList<Record>()
        while (true) {
            val r = client.getChanges(next)
            if (r.changesTokenExpired) return reset()
            for (c in r.changes) if (c is UpsertionChange) recs.add(c.record)
            next = r.nextChangesToken
            if (!r.hasMore) break
        }
        pointsOf(metric, recs, rows)
        return JSONObject().put("rows", rows).put("token", next).put("reset", false).toString()
    }

    override fun read(args: JSONObject): String = runBlocking {
        val metric = args.getString("metric")
        if (args.has("token")) return@runBlocking readChanges(metric, args.optString("token", ""))
        val from = Instant.parse(args.getString("from"))
        val to = Instant.parse(args.getString("to"))
        val range = TimeRangeFilter.between(from, to)
        val out = JSONArray()
        fun span(start: Instant, end: Instant, value: Double?, unit: String, category: String? = null, extra: JSONObject? = null) =
            out.put(JSONObject().put("start", start.toString()).put("end", end.toString()).put("value", value ?: JSONObject.NULL).put("unit", unit)
                .put("category", category ?: JSONObject.NULL).put("extra", extra ?: JSONObject.NULL))

        if (metric in pointTypes) { pointsOf(metric, read(pointTypes.getValue(metric), range), out); return@runBlocking out.toString() }
        when (metric) {
            "steps" -> client.aggregateGroupByDuration(AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofHours(1)))
                .forEach { b -> b.result[StepsRecord.COUNT_TOTAL]?.let { span(b.startTime, b.endTime, it.toDouble(), "count", "hourly") } }
            "steps_daily" -> daily(StepsRecord.COUNT_TOTAL, from, to) { b, v -> span(b.first, b.second, v.toDouble(), "count", "daily") }
            "distance_daily" -> daily(DistanceRecord.DISTANCE_TOTAL, from, to) { b, v -> span(b.first, b.second, v.inMeters, "m", "daily") }
            "active_kcal_daily" -> daily(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, from, to) { b, v -> span(b.first, b.second, v.inKilocalories, "kcal", "daily") }
            "total_kcal_daily" -> daily(TotalCaloriesBurnedRecord.ENERGY_TOTAL, from, to) { b, v -> span(b.first, b.second, v.inKilocalories, "kcal", "daily") }
            "floors_daily" -> daily(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, from, to) { b, v -> span(b.first, b.second, v, "count", "daily") }
            "resting_heart_rate_daily" -> daily(RestingHeartRateRecord.BPM_AVG, from, to) { b, v -> span(b.first, b.second, v.toDouble(), "bpm", "daily") }
            "sleep" -> read(SleepSessionRecord::class, range).forEach { s ->
                val stages = JSONObject()
                s.stages.groupBy { stageName(it.stage) }.forEach { (k, l) -> stages.put(k, l.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }) }
                span(s.startTime, s.endTime, Duration.between(s.startTime, s.endTime).toMinutes().toDouble(), "min", null, JSONObject().put("stages_min", stages))
            }
            "sleep_stage" -> read(SleepSessionRecord::class, range).forEach { s -> s.stages.forEach { st ->
                span(st.startTime, st.endTime, Duration.between(st.startTime, st.endTime).toMinutes().toDouble(), "min", stageName(st.stage)) } }
            "exercise" -> read(ExerciseSessionRecord::class, range).forEach { e ->
                span(e.startTime, e.endTime, null, "kcal", e.title ?: "exercise:${e.exerciseType}") }
            else -> throw IllegalArgumentException("모르는 지표: $metric")
        }
        out.toString()
    }

    private suspend fun <T : Record> read(cls: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(cls, range, pageToken = token, pageSize = 1000))
            out.addAll(r.records); token = r.pageToken
        } while (token != null)
        return out
    }

    private suspend fun <T : Any> daily(metric: AggregateMetric<T>, from: Instant, to: Instant, each: (Pair<Instant, Instant>, T) -> Unit) {
        val start = LocalDateTime.ofInstant(from, zone).toLocalDate().atStartOfDay()
        val end = LocalDateTime.ofInstant(to, zone).toLocalDate().plusDays(1).atStartOfDay()
        client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric), TimeRangeFilter.between(start, end), Period.ofDays(1))).forEach { b ->
            b.result[metric]?.let { each(b.startTime.atZone(zone).toInstant() to b.endTime.atZone(zone).toInstant(), it) }
        }
    }

    private fun stageName(stage: Int) = when (stage) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"; SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"; SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
        else -> "other"
    }
}
