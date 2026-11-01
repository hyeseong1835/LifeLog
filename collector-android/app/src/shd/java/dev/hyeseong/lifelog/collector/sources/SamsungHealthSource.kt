package dev.hyeseong.lifelog.collector.sources

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 삼성 헬스 Data SDK 읽기 소스(`read:shealth`). `app/libs/`에 SDK aar가 있을 때만 빌드에 들어간다(없으면 src/noshd의 빈 구현).
 * 삼성 헬스 > 설정 > 삼성 헬스 정보 > 버전 10번 탭 > 개발자 모드 > "Developer Mode for Data Read"가 켜져 있어야 한다.
 * read({ metric, from, to }) → HealthSource와 같은 행 모양: 구간 { start, end, value, unit, category, extra } | 시점 { time, value, unit, extra }.
 * 평탄화만 하고 해석하지 않는다. Health Connect와 겹치는 지표는 이름 앞에 sh_를 붙인다(같은 테이블에 따로 저장).
 * SDK 1.1.0에는 스트레스 데이터 타입이 없다.
 */
class SamsungHealthSource(private val context: Context) : ReadSource {
    override val id = "shealth"
    private val zone: ZoneId = ZoneId.systemDefault()
    private val store: HealthDataStore by lazy { HealthDataService.getStore(context.applicationContext) }

    companion object {
        const val INCLUDED = true
        val TYPES: List<DataType> = listOf(
            DataTypes.ENERGY_SCORE, DataTypes.SLEEP, DataTypes.SKIN_TEMPERATURE, DataTypes.ACTIVITY_SUMMARY, DataTypes.STEPS, DataTypes.FLOORS_CLIMBED,
            DataTypes.HEART_RATE, DataTypes.BLOOD_OXYGEN, DataTypes.BLOOD_PRESSURE, DataTypes.BLOOD_GLUCOSE, DataTypes.BODY_COMPOSITION, DataTypes.BODY_TEMPERATURE,
            DataTypes.EXERCISE, DataTypes.WATER_INTAKE, DataTypes.NUTRITION, DataTypes.SLEEP_APNEA, DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION,
            DataTypes.STEPS_GOAL, DataTypes.ACTIVE_CALORIES_BURNED_GOAL, DataTypes.ACTIVE_TIME_GOAL, DataTypes.WATER_INTAKE_GOAL, DataTypes.SLEEP_GOAL, DataTypes.NUTRITION_GOAL,
        )
        val PERMISSIONS: Set<Permission> = TYPES.map { Permission.of(it, AccessType.READ) }.toSet()
        /** 지표 이름 → 필요한 데이터 타입. 플러그인 설정에 쓰는 이름 목록이기도 하다 */
        val METRICS: Map<String, DataType> = linkedMapOf(
            "energy_score" to DataTypes.ENERGY_SCORE, "sleep_score" to DataTypes.SLEEP, "sh_sleep" to DataTypes.SLEEP, "sh_sleep_stage" to DataTypes.SLEEP,
            "skin_temperature" to DataTypes.SKIN_TEMPERATURE, "active_time" to DataTypes.ACTIVITY_SUMMARY, "sh_active_kcal" to DataTypes.ACTIVITY_SUMMARY,
            "sh_total_kcal" to DataTypes.ACTIVITY_SUMMARY, "sh_distance" to DataTypes.ACTIVITY_SUMMARY, "sh_steps" to DataTypes.STEPS, "sh_floors" to DataTypes.FLOORS_CLIMBED,
            "sh_heart_rate" to DataTypes.HEART_RATE, "sh_spo2" to DataTypes.BLOOD_OXYGEN, "blood_pressure" to DataTypes.BLOOD_PRESSURE, "blood_glucose" to DataTypes.BLOOD_GLUCOSE,
            "body_composition" to DataTypes.BODY_COMPOSITION, "sh_body_temp" to DataTypes.BODY_TEMPERATURE, "sh_exercise" to DataTypes.EXERCISE,
            "water" to DataTypes.WATER_INTAKE, "nutrition" to DataTypes.NUTRITION, "nutrition_kcal" to DataTypes.NUTRITION,
            "sleep_apnea" to DataTypes.SLEEP_APNEA, "irregular_heart_rhythm" to DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION,
            "steps_goal" to DataTypes.STEPS_GOAL, "active_kcal_goal" to DataTypes.ACTIVE_CALORIES_BURNED_GOAL, "active_time_goal" to DataTypes.ACTIVE_TIME_GOAL,
            "water_goal" to DataTypes.WATER_INTAKE_GOAL, "sleep_goal" to DataTypes.SLEEP_GOAL, "nutrition_goal" to DataTypes.NUTRITION_GOAL,
        )

        fun available(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.sec.android.app.shealth", 0); true
        } catch (_: Exception) { false }

        suspend fun granted(context: Context): Set<DataType> =
            HealthDataService.getStore(context.applicationContext).getGrantedPermissions(PERMISSIONS).map { it.dataType }.toSet()

        suspend fun requestPermissions(activity: Activity): Int =
            HealthDataService.getStore(activity.applicationContext).requestPermissions(PERMISSIONS, activity).size
    }

    override fun read(args: JSONObject): String = runBlocking {
        val metric = args.getString("metric")
        val type = METRICS[metric] ?: throw IllegalArgumentException("모르는 지표: $metric")
        val grantedTypes = store.getGrantedPermissions(PERMISSIONS).map { it.dataType }.toSet()
        if (type !in grantedTypes) throw IllegalStateException("삼성 헬스 권한 없음: $metric")
        val from = Instant.parse(args.getString("from")); val to = Instant.parse(args.getString("to"))
        val out = JSONArray()
        fun span(start: Instant, end: Instant, value: Double?, unit: String, category: String? = null, extra: JSONObject? = null) =
            out.put(JSONObject().put("start", start.toString()).put("end", end.toString()).put("value", value ?: JSONObject.NULL).put("unit", unit)
                .put("category", category ?: JSONObject.NULL).put("extra", extra ?: JSONObject.NULL))
        fun point(time: Instant, value: Double, unit: String, extra: JSONObject? = null) =
            out.put(JSONObject().put("time", time.toString()).put("value", value).put("unit", unit).put("extra", extra ?: JSONObject.NULL))
        val lFrom = LocalDateTime.ofInstant(from, zone); val lTo = LocalDateTime.ofInstant(to, zone)
        val dFrom = lFrom.toLocalDate(); val dTo = lTo.toLocalDate().plusDays(1)
        val timeFilter = LocalTimeFilter.of(lFrom, lTo)
        fun dayStart(d: LocalDate): Instant = d.atStartOfDay(zone).toInstant()
        fun days(): List<LocalDate> = generateSequence(dFrom) { it.plusDays(1) }.takeWhile { it < dTo }.toList()
        fun f(v: Float?) = v?.toDouble()

        when (metric) {
            "energy_score" -> readLocalDate(DataTypes.ENERGY_SCORE.readDataRequestBuilder.setLocalDateFilter(LocalDateFilter.of(dFrom, dTo)).build()).forEach { p ->
                val d = p.startTime.atZone(zone).toLocalDate()
                p.getValue(DataType.EnergyScoreType.ENERGY_SCORE)?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "score", "daily") }
            }
            "sleep_score", "sh_sleep", "sh_sleep_stage" -> {
                // 범위 안에서 끝난 잠. 긴 잠은 하루 전에 시작하므로 시작 필터를 하루 넓힌다
                // SLEEP 레코드의 startTime/endTime은 날짜 경계(00:00)다. 실제 잠든 시각은 세션(SESSIONS)에 있다
                val sleeps = readDual(DataTypes.SLEEP.readDataRequestBuilder.setLocalTimeFilter(LocalTimeFilter.of(lFrom.minusHours(24), lTo)).build())
                    .map { p -> val ss = p.getValue(DataType.SleepType.SESSIONS).orEmpty(); Triple(p, ss.minOfOrNull { it.startTime } ?: p.startTime, ss.maxOfOrNull { it.endTime } ?: p.endTime) }
                    .filter { (_, _, end) -> end != null && end >= from && end < to }
                for ((p, start, end0) in sleeps) {
                    val end = end0 ?: continue
                    when (metric) {
                        "sleep_score" -> p.getValue(DataType.SleepType.SLEEP_SCORE)?.let { span(start, end, it.toDouble(), "score", "sleep") }
                        "sh_sleep" -> {
                            val sessions = p.getValue(DataType.SleepType.SESSIONS).orEmpty()
                            val stages = JSONObject()
                            sessions.flatMap { it.stages.orEmpty() }.groupBy { stageName(it.stage) }.forEach { (k, l) -> stages.put(k, l.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }) }
                            span(start, end, Duration.between(start, end).toMinutes().toDouble(), "min", null,
                                JSONObject().put("stages_min", stages).put("sessions", sessions.size).put("score", p.getValue(DataType.SleepType.SLEEP_SCORE) ?: JSONObject.NULL))
                        }
                        else -> p.getValue(DataType.SleepType.SESSIONS).orEmpty().flatMap { it.stages.orEmpty() }.forEach { st ->
                            span(st.startTime, st.endTime, Duration.between(st.startTime, st.endTime).toMinutes().toDouble(), "min", stageName(st.stage))
                        }
                    }
                }
            }
            "skin_temperature" -> readLocal(DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val series = p.getValue(DataType.SkinTemperatureType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) series.forEach { point(it.startTime, it.skinTemperature.toDouble(), "C") }
                else p.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE)?.let { point(p.startTime, it.toDouble(), "C") }
            }
            "active_time", "sh_active_kcal", "sh_total_kcal", "sh_distance" -> for (d in days()) {
                val filter = LocalTimeFilter.of(d.atStartOfDay(), d.plusDays(1).atStartOfDay())
                val s = dayStart(d); val e = dayStart(d.plusDays(1))
                when (metric) {
                    "active_time" -> agg(DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME.requestBuilder.setLocalTimeFilter(filter).build())?.let { if (!it.isZero) span(s, e, it.toMinutes().toDouble(), "min", "daily") }
                    "sh_active_kcal" -> agg(DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED.requestBuilder.setLocalTimeFilter(filter).build())?.let { span(s, e, it.toDouble(), "kcal", "daily") }
                    "sh_total_kcal" -> agg(DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED.requestBuilder.setLocalTimeFilter(filter).build())?.let { span(s, e, it.toDouble(), "kcal", "daily") }
                    else -> agg(DataType.ActivitySummaryType.TOTAL_DISTANCE.requestBuilder.setLocalTimeFilter(filter).build())?.let { span(s, e, it.toDouble(), "m", "daily") }
                }
            }
            "sh_steps" -> for (d in days()) agg(DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilter(LocalTimeFilter.of(d.atStartOfDay(), d.plusDays(1).atStartOfDay())).build())
                ?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "count", "daily") }
            "sh_floors" -> for (d in days()) agg(DataType.FloorsClimbedType.TOTAL.requestBuilder.setLocalTimeFilter(LocalTimeFilter.of(d.atStartOfDay(), d.plusDays(1).atStartOfDay())).build())
                ?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "count", "daily") }
            "sh_heart_rate" -> readLocal(DataTypes.HEART_RATE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val series = p.getValue(DataType.HeartRateType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) series.forEach { point(it.startTime, it.heartRate.toDouble(), "bpm") }
                else p.getValue(DataType.HeartRateType.HEART_RATE)?.let { point(p.startTime, it.toDouble(), "bpm") }
            }
            "sh_spo2" -> readLocal(DataTypes.BLOOD_OXYGEN.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val series = p.getValue(DataType.BloodOxygenType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) series.forEach { point(it.startTime, it.oxygenSaturation.toDouble(), "%") }
                else p.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION)?.let { point(p.startTime, it.toDouble(), "%") }
            }
            "blood_pressure" -> readLocal(DataTypes.BLOOD_PRESSURE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val sys = p.getValue(DataType.BloodPressureType.SYSTOLIC) ?: return@forEach
                point(p.startTime, sys.toDouble(), "mmHg", JSONObject().put("diastolic", f(p.getValue(DataType.BloodPressureType.DIASTOLIC)) ?: JSONObject.NULL)
                    .put("mean", f(p.getValue(DataType.BloodPressureType.MEAN)) ?: JSONObject.NULL).put("pulse", p.getValue(DataType.BloodPressureType.PULSE_RATE) ?: JSONObject.NULL))
            }
            "blood_glucose" -> readLocal(DataTypes.BLOOD_GLUCOSE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val extra = JSONObject().put("meal", p.getValue(DataType.BloodGlucoseType.MEAL_STATUS)?.name ?: JSONObject.NULL).put("measurement", p.getValue(DataType.BloodGlucoseType.MEASUREMENT_TYPE)?.name ?: JSONObject.NULL)
                val series = p.getValue(DataType.BloodGlucoseType.SERIES_DATA).orEmpty()
                if (series.isNotEmpty()) series.forEach { point(it.timestamp, it.glucose.toDouble(), "mmol/L", extra) }
                else p.getValue(DataType.BloodGlucoseType.GLUCOSE_LEVEL)?.let { point(p.startTime, it.toDouble(), "mmol/L", extra) }
            }
            "body_composition" -> readLocal(DataTypes.BODY_COMPOSITION.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val w = p.getValue(DataType.BodyCompositionType.WEIGHT) ?: return@forEach
                val extra = JSONObject()
                mapOf("height" to DataType.BodyCompositionType.HEIGHT, "body_fat" to DataType.BodyCompositionType.BODY_FAT, "skeletal_muscle" to DataType.BodyCompositionType.SKELETAL_MUSCLE,
                    "muscle_mass" to DataType.BodyCompositionType.MUSCLE_MASS, "body_fat_mass" to DataType.BodyCompositionType.BODY_FAT_MASS, "fat_free_mass" to DataType.BodyCompositionType.FAT_FREE_MASS,
                    "skeletal_muscle_mass" to DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS, "total_body_water" to DataType.BodyCompositionType.TOTAL_BODY_WATER, "bmi" to DataType.BodyCompositionType.BODY_MASS_INDEX)
                    .forEach { (k, field) -> p.getValue(field)?.let { extra.put(k, it.toDouble()) } }
                p.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE)?.let { extra.put("bmr", it) }
                point(p.startTime, w.toDouble(), "kg", extra)
            }
            "sh_body_temp" -> readLocal(DataTypes.BODY_TEMPERATURE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                p.getValue(DataType.BodyTemperatureType.BODY_TEMPERATURE)?.let { point(p.startTime, it.toDouble(), "C") }
            }
            "sh_exercise" -> readLocal(DataTypes.EXERCISE.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val end = p.endTime ?: return@forEach
                val type = p.getValue(DataType.ExerciseType.EXERCISE_TYPE)?.toString() ?: "OTHER"
                val title = p.getValue(DataType.ExerciseType.CUSTOM_TITLE)
                val sessions = p.getValue(DataType.ExerciseType.SESSIONS).orEmpty()
                val kcal = sessions.sumOf { it.calories.toDouble() }
                val dist = sessions.mapNotNull { it.distance?.toDouble() }.sum()
                span(p.startTime, end, if (sessions.isEmpty()) null else kcal, "kcal", title?.takeIf { it.isNotBlank() } ?: type.lowercase(),
                    JSONObject().put("type", type).put("distance_m", dist).put("sessions", sessions.size))
            }
            "water" -> for (d in days()) agg(DataType.WaterIntakeType.TOTAL.requestBuilder.setLocalTimeFilter(LocalTimeFilter.of(d.atStartOfDay(), d.plusDays(1).atStartOfDay())).build())
                ?.let { if (it > 0f) span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "ml", "daily") }
            "nutrition_kcal" -> for (d in days()) agg(DataType.NutritionType.TOTAL_CALORIES.requestBuilder.setLocalTimeFilter(LocalTimeFilter.of(d.atStartOfDay(), d.plusDays(1).atStartOfDay())).build())
                ?.let { if (it > 0f) span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "kcal", "daily") }
            "nutrition" -> readLocal(DataTypes.NUTRITION.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                val extra = JSONObject().put("title", p.getValue(DataType.NutritionType.TITLE) ?: JSONObject.NULL)
                mapOf("fat" to DataType.NutritionType.TOTAL_FAT, "carbohydrate" to DataType.NutritionType.CARBOHYDRATE, "protein" to DataType.NutritionType.PROTEIN,
                    "sugar" to DataType.NutritionType.SUGAR, "sodium" to DataType.NutritionType.SODIUM, "fiber" to DataType.NutritionType.DIETARY_FIBER)
                    .forEach { (k, field) -> p.getValue(field)?.let { extra.put(k, it.toDouble()) } }
                span(p.startTime, p.endTime ?: p.startTime.plusSeconds(60), f(p.getValue(DataType.NutritionType.CALORIES)), "kcal",
                    p.getValue(DataType.NutritionType.MEAL_TYPE)?.name?.lowercase() ?: "meal", extra)
            }
            "sleep_apnea" -> readLocal(DataTypes.SLEEP_APNEA.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                p.getValue(DataType.SleepApneaType.DETECTED_SIGN)?.let { point(p.startTime, if (it.name == "DETECTED") 1.0 else 0.0, "flag", JSONObject().put("sign", it.name)) }
            }
            "irregular_heart_rhythm" -> readLocal(DataTypes.IRREGULAR_HEART_RHYTHM_NOTIFICATION.readDataRequestBuilder.setLocalTimeFilter(timeFilter).build()).forEach { p ->
                p.getValue(DataType.IrregularHeartRhythmNotificationType.STATUS)?.let { point(p.startTime, 1.0, "flag", JSONObject().put("status", it.name)) }
            }
            "steps_goal" -> for (d in days()) aggDate(DataType.StepsGoalType.LAST.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "count", "goal") }
            "active_kcal_goal" -> for (d in days()) aggDate(DataType.ActiveCaloriesBurnedGoalType.LAST.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "kcal", "goal") }
            "active_time_goal" -> for (d in days()) aggDate(DataType.ActiveTimeGoalType.LAST.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toMinutes().toDouble(), "min", "goal") }
            "water_goal" -> for (d in days()) aggDate(DataType.WaterIntakeGoalType.LAST.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "ml", "goal") }
            "nutrition_goal" -> for (d in days()) aggDate(DataType.NutritionGoalType.LAST_CALORIES.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())?.let { span(dayStart(d), dayStart(d.plusDays(1)), it.toDouble(), "kcal", "goal") }
            "sleep_goal" -> for (d in days()) {
                val bed = aggDate(DataType.SleepGoalType.LAST_BED_TIME.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())
                val wake = aggDate(DataType.SleepGoalType.LAST_WAKE_UP_TIME.requestBuilder.setLocalDateFilter(LocalDateFilter.of(d, d.plusDays(1))).build())
                if (bed != null || wake != null) span(dayStart(d), dayStart(d.plusDays(1)), wake?.toSecondOfDay()?.div(60.0), "min",
                    "goal", JSONObject().put("bed_time", bed?.toString() ?: JSONObject.NULL).put("wake_time", wake?.toString() ?: JSONObject.NULL))
            }
            else -> throw IllegalArgumentException("모르는 지표: $metric")
        }
        out.toString()
    }

    private suspend fun readLocal(req: ReadDataRequest<HealthDataPoint>): List<HealthDataPoint> = store.readData(req).dataList
    private suspend fun readDual(req: ReadDataRequest<HealthDataPoint>): List<HealthDataPoint> = store.readData(req).dataList
    private suspend fun readLocalDate(req: ReadDataRequest<HealthDataPoint>): List<HealthDataPoint> = store.readData(req).dataList
    private suspend fun <T : Any> agg(req: AggregateRequest<T>): T? = store.aggregateData(req).dataList.firstOrNull()?.value
    private suspend fun <T : Any> aggDate(req: AggregateRequest<T>): T? = store.aggregateData(req).dataList.firstOrNull()?.value

    private fun stageName(stage: DataType.SleepType.StageType) = when (stage) {
        DataType.SleepType.StageType.DEEP -> "deep"; DataType.SleepType.StageType.LIGHT -> "light"; DataType.SleepType.StageType.REM -> "rem"
        DataType.SleepType.StageType.AWAKE -> "awake"; else -> "other"
    }
}
