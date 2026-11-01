package dev.hyeseong.lifelog.collector.core

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 결정적 id. Windows LifeLog.Core/Ids.cs, tools/import/common.py와 바이트 단위로 같아야 한다.
 *   key가 있으면  uuid5(NS, plugin | device | output | key)
 *   없으면        uuid5(NS, plugin | device | output | start(Python isoformat, UTC) | identity 값들(Python json.dumps))
 */
object Ids {
    val NS: UUID = UUID.fromString("7d1b3c2e-5a4f-4e8b-9c6d-2f1e0a9b8c7d")
    private const val SEP = "\u001f"

    fun rowId(plugin: String, device: String, output: String, key: String): UUID =
        uuid5(NS, listOf(plugin, device, output, key).joinToString(SEP))

    fun rowId(plugin: String, device: String, output: String, start: Instant, identity: List<Any?>): UUID =
        uuid5(NS, listOf(plugin, device, output, pyIso(start), pyJsonList(identity)).joinToString(SEP))

    fun uuid5(ns: UUID, name: String): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(toBytes(ns))
        md.update(name.toByteArray(Charsets.UTF_8))
        val h = md.digest()
        h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte()
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
        return UUID(msb, lsb)
    }

    private fun toBytes(u: UUID): ByteArray {
        val b = ByteArray(16)
        var msb = u.mostSignificantBits; var lsb = u.leastSignificantBits
        for (i in 7 downTo 0) { b[i] = (msb and 0xff).toByte(); msb = msb ushr 8 }
        for (i in 15 downTo 8) { b[i] = (lsb and 0xff).toByte(); lsb = lsb ushr 8 }
        return b
    }

    private val base = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** Python datetime.isoformat(): 마이크로초가 0이면 소수 없음. */
    fun pyIso(t: Instant): String {
        val micro = t.nano / 1000
        val s = base.format(t)
        return if (micro == 0) "$s+00:00" else "$s.${micro.toString().padStart(6, '0')}+00:00"
    }

    /** Python json.dumps(list, ensure_ascii=False, separators=(",",":")) */
    fun pyJsonList(values: List<Any?>): String = values.joinToString(",", "[", "]") { pyJsonValue(it) }

    private fun pyJsonValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is String -> pyJsonString(v)
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> pyFloat(v)
        is Float -> pyFloat(v.toDouble())
        else -> pyJsonString(v.toString())
    }

    private fun pyFloat(d: Double): String {
        val s = d.toString()   // Kotlin: 1.0 → "1.0", 0.1 → "0.1", 1e20 → "1.0E20" (identity에 지수 표기는 쓰지 않는다고 가정)
        return if (s.contains('.') || s.contains('E')) s else "$s.0"
    }

    private fun pyJsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b"); '\u000c' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
