package dev.hyeseong.lifelog.collector.core

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * 수집기 설정. 기기 롤 비밀번호는 Keystore 기반 EncryptedSharedPreferences에만 둔다(Documents/인증·권한.md 3절).
 * 풀러(Supabase)는 사용자 이름이 `롤.프로젝트ref` 꼴이어야 한다 → tenant.
 */
class Config(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "lifelog-config", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    var device: String get() = prefs.getString("device", "") ?: ""; set(v) = prefs.edit().putString("device", v).apply()
    var host: String get() = prefs.getString("host", "") ?: ""; set(v) = prefs.edit().putString("host", v).apply()
    var port: Int get() = prefs.getInt("port", 5432); set(v) = prefs.edit().putInt("port", v).apply()
    var database: String get() = prefs.getString("database", "postgres") ?: "postgres"; set(v) = prefs.edit().putString("database", v).apply()
    var tenant: String get() = prefs.getString("tenant", "") ?: ""; set(v) = prefs.edit().putString("tenant", v).apply()
    var ssl: Boolean get() = prefs.getBoolean("ssl", true); set(v) = prefs.edit().putBoolean("ssl", v).apply()
    var password: String get() = prefs.getString("password", "") ?: ""; set(v) = prefs.edit().putString("password", v).apply()

    val user get() = "lifelog_device_$device" + if (tenant.isEmpty()) "" else ".$tenant"
    val isComplete get() = device.isNotEmpty() && host.isNotEmpty() && password.isNotEmpty()

    fun jdbcUrl() = "jdbc:postgresql://$host:$port/$database?sslmode=${if (ssl) "require" else "prefer"}&ApplicationName=lifelog-collector-android&connectTimeout=15&socketTimeout=60"

    companion object {
        fun pluginsDir(context: Context) = File(context.filesDir, "plugins")
        fun logFile(context: Context) = File(context.filesDir, "collector.log")
        fun queuePath(context: Context) = File(context.filesDir, "queue.db").path
    }
}
