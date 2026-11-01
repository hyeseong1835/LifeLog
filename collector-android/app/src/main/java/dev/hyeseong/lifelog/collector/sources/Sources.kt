package dev.hyeseong.lifelog.collector.sources

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** 네이티브 소스(Documents/수집기 호스트 API.md 1절). 원시 값만 낸다. emit은 아무 스레드에서나. */
interface EventSource : AutoCloseable {
    val id: String
    fun start(emit: (JSONObject) -> Unit)
}

interface ReadSource {
    val id: String
    fun read(args: JSONObject): String
}

/** 화면 켬·끔·잠금 해제. { time, kind: on | off | unlock } */
class ScreenSource(private val context: Context) : EventSource {
    override val id = "screen"
    private var receiver: BroadcastReceiver? = null
    override fun start(emit: (JSONObject) -> Unit) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val kind = when (i.action) { Intent.ACTION_SCREEN_ON -> "on"; Intent.ACTION_SCREEN_OFF -> "off"; Intent.ACTION_USER_PRESENT -> "unlock"; else -> return }
                emit(JSONObject().put("time", Instant.now().toString()).put("kind", kind))
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }, Context.RECEIVER_EXPORTED)
    }
    override fun close() { receiver?.let { context.unregisterReceiver(it) }; receiver = null }
}

/** 위치 갱신. 15분 간격·균형 전력. { time, lat, lon, accuracy }. "항상 허용" 권한 */
class LocationSource(private val context: Context) : EventSource {
    override val id = "location"
    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission")
    override fun start(emit: (JSONObject) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("위치 권한이 없습니다")
        val l = LocationListener { loc: Location ->
            emit(JSONObject().put("time", Instant.ofEpochMilli(loc.time).toString()).put("lat", loc.latitude).put("lon", loc.longitude).put("accuracy", loc.accuracy.toDouble()))
        }
        listener = l
        val req = LocationRequest.Builder(15 * 60 * 1000L).setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).setMinUpdateDistanceMeters(0f).build()
        val provider = if (lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        lm.requestLocationUpdates(provider, req, context.mainExecutor, l)
    }
    override fun close() { listener?.let { lm.removeUpdates(it) }; listener = null }
}

/** 앱 전환 이벤트. read({from, to}) → [{ time, package, type: resumed | paused | stopped | screen_off | screen_on | keyguard_hidden }] */
class UsageSource(private val context: Context) : ReadSource {
    override val id = "usage"
    override fun read(args: JSONObject): String {
        val from = Instant.parse(args.getString("from")).toEpochMilli()
        val to = Instant.parse(args.getString("to")).toEpochMilli()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, to)
        val out = JSONArray()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val type = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "resumed"; UsageEvents.Event.ACTIVITY_PAUSED -> "paused"; UsageEvents.Event.ACTIVITY_STOPPED -> "stopped"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "screen_off"; UsageEvents.Event.SCREEN_INTERACTIVE -> "screen_on"; UsageEvents.Event.KEYGUARD_HIDDEN -> "keyguard_hidden"
                else -> continue
            }
            out.put(JSONObject().put("time", Instant.ofEpochMilli(e.timeStamp).toString()).put("package", e.packageName ?: "").put("type", type))
        }
        return out.toString()
    }
}
