package dev.hyeseong.lifelog.collector.sources

import android.app.Activity
import android.content.Context
import org.json.JSONObject

/** 삼성 헬스 Data SDK 빈 구현. `app/libs/`에 SDK aar가 없을 때 빌드에 들어간다. */
class SamsungHealthSource(@Suppress("unused") private val context: Context) : ReadSource {
    override val id = "shealth"
    companion object {
        const val INCLUDED = false
        val METRICS: Map<String, Any> = emptyMap()
        fun available(@Suppress("unused") context: Context) = false
        suspend fun granted(@Suppress("unused") context: Context): Set<Any> = emptySet()
        suspend fun requestPermissions(@Suppress("unused") activity: Activity): Int = throw IllegalStateException("이 빌드에는 삼성 헬스 SDK가 없습니다")
    }
    override fun read(args: JSONObject): String = throw IllegalStateException("이 빌드에는 삼성 헬스 SDK가 없습니다")
}
