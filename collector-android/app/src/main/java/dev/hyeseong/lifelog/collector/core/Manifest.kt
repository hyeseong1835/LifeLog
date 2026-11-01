package dev.hyeseong.lifelog.collector.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 플러그인 매니페스트(Documents/매니페스트.md). 완전한 검증은 DB 등록 함수가 한다. */
class Manifest(val raw: String) {
    val json = JSONObject(raw)
    val type: String = json.getString("type")
    val id: String = json.getString("id")
    val version: String = json.getString("version")
    val name: String = json.optString("name", id)
    val runtimes: Map<String, String> = json.getJSONObject("runtimes").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    val capabilities: List<Pair<String, Boolean>> = json.optJSONArray("capabilities").toList().map { it.getString("id") to it.optBoolean("required", false) }
    val outputs: List<Output> = json.optJSONArray("outputs").toList().map { Output(it) }
    val settingDecls: List<JSONObject> = json.optJSONArray("settings").toList() + json.optJSONArray("privacy").toList()

    class Output(o: JSONObject) {
        val name: String = o.getString("name")
        val kind: String = o.getString("kind")
        val schemaVersion: Int = o.getInt("schema_version")
        val columns: Map<String, String> = o.getJSONObject("columns").let { c -> c.keys().asSequence().associateWith { c.getString(it) } }
        val identity: List<String> = o.optJSONArray("identity").toList().map { it.toString() }.ifEmpty { emptyList() }
        val isSpan get() = kind == "span"
    }

    fun output(name: String): Output? = outputs.firstOrNull { it.name == name }
    fun tableName(o: Output) = "$id/${o.name}@${o.schemaVersion}"

    companion object {
        fun load(dir: File) = Manifest(File(dir, "manifest.json").readText())
        private fun JSONArray?.toList(): List<JSONObject> {
            if (this == null) return emptyList()
            val out = ArrayList<JSONObject>(length())
            for (i in 0 until length()) { val v = opt(i); if (v is JSONObject) out.add(v) else out.add(JSONObject().put("v", v)) }
            return out
        }
    }
}

/** 큐·업로더가 주고받는 행. 시각은 ISO(UTC). 시점 행은 end가 null. */
class Row(val table: String, val device: String, val id: String, val start: String, val end: String?, val cols: JSONObject) {
    val key get() = "$table|$device|$id"
    fun toJson(): String = JSONObject().put("table", table).put("device", device).put("id", id).put("start", start)
        .put("end", end ?: JSONObject.NULL).put("cols", cols).toString()

    companion object {
        fun fromJson(s: String): Row {
            val o = JSONObject(s)
            return Row(o.getString("table"), o.getString("device"), o.getString("id"), o.getString("start"),
                if (o.isNull("end")) null else o.getString("end"), o.getJSONObject("cols"))
        }
    }
}

class TableMeta(val table: String, val isSpan: Boolean, val columns: Map<String, String>)
