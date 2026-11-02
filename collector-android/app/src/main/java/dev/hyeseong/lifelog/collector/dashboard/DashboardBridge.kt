package dev.hyeseong.lifelog.collector.dashboard

import android.content.Context
import dev.hyeseong.lifelog.collector.core.Config
import dev.hyeseong.lifelog.collector.core.Db
import dev.hyeseong.lifelog.collector.core.Manifest
import dev.hyeseong.lifelog.collector.core.PluginInstaller
import dev.hyeseong.lifelog.collector.core.QuerySpec
import dev.hyeseong.lifelog.collector.core.TableMeta
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 휴대폰 대시보드의 렌더러 호스트 API. dashboard-windows/…/Bridge.cs와 같은 메서드·같은 응답 모양이라 셸(web의 js)과 위젯을 그대로 쓴다.
 * 페이지는 {id, from, method, args}를 보내고 {id, result|error}를 받는다. from은 "shell" 또는 렌더러 id. 렌더러는 inputs에 선언한 출력만 query한다.
 * 위젯 배치·위젯 설정·테마는 이 기기 파일(filesDir/dashboard/settings.json)에만 두고 다른 기기와 공유하지 않는다. 분류 규칙만 DB(0007)에 둔다.
 * 모든 메서드는 IO 스레드에서 부른다.
 */
class DashboardBridge(private val context: Context, private val cfg: Config, private val log: (String) -> Unit) {
    companion object {
        fun dir(context: Context) = File(context.filesDir, "dashboard").apply { mkdirs() }
        fun pluginsDir(context: Context) = File(dir(context), "plugins").apply { mkdirs() }
    }
    private val db = Db(cfg)
    private val settingsFile = File(dir(context), "settings.json")
    private val renderers = LinkedHashMap<String, Manifest>()   // id → 매니페스트(이 기기에 설치된 것)
    private var rules: JSONObject? = null; private var rulesAt = 0L

    fun close() = db.close()

    /** assets의 widget-*을 dashboard/plugins로 풀고(버전이 바뀌었거나 설치를 끝내지 못했으면) 등록·켠다. */
    fun installBundled() {
        val am = context.assets
        for (id in am.list("")!!.filter { it.startsWith("widget-") }) {
            try {
                val m = Manifest(am.open("$id/manifest.json").bufferedReader().readText())
                if (!m.supports("android")) continue
                val dest = File(pluginsDir(context), m.id)
                val done = File(dest, ".source.json")
                val cur = File(dest, "manifest.json").takeIf { it.exists() }?.let { Manifest.load(dest) }
                if (cur?.version == m.version && done.exists()) continue
                dest.deleteRecursively(); dest.mkdirs()
                for (f in am.list(id)!!) am.open("$id/$f").use { i -> File(dest, f).outputStream().use { i.copyTo(it) } }
                db.registerPlugin(m, cfg.device)
                db.setInstall(m.id, cfg.device, m.version, true, null, JSONArray(m.capabilities.map { it.first }).toString())
                done.writeText(JSONObject().put("source", "bundled").put("installed_at", java.time.Instant.now().toString()).toString())
                log("내장 위젯 설치: ${m.id} ${m.version}")
            } catch (e: Exception) { log("내장 위젯 설치 실패 $id: ${e.message}") }
        }
    }

    /** dashboard/plugins의 렌더러를 등록부에 올리고 기억한다. */
    fun mapPlugins() {
        renderers.clear()
        for (d in pluginsDir(context).listFiles().orEmpty().filter { File(it, "manifest.json").exists() }) {
            try {
                val m = Manifest.load(d)
                if (m.type != "renderer") continue
                db.registerPlugin(m, cfg.device)
                renderers[m.id] = m
            } catch (e: Exception) { log("렌더러 로드 실패 ${d.name}: ${e.message}") }
        }
    }

    fun pluginFile(id: String, path: String): File? {
        if (!renderers.containsKey(id)) return null
        val root = File(pluginsDir(context), id).canonicalFile
        val f = File(root, path.trimStart('/').ifEmpty { "index.html" }).canonicalFile
        return if (f.path.startsWith(root.path + File.separator) && f.isFile) f else null
    }

    /** 한 메시지를 처리해 응답 JSON 문자열을 만든다. */
    fun handle(json: String): String {
        val msg = JSONObject(json)
        val id = msg.optString("id"); val from = msg.optString("from", "shell"); val method = msg.optString("method")
        val args = msg.optJSONArray("args") ?: JSONArray()
        return try {
            val result: Any? = when (method) {
                "query" -> query(from, args)
                "outputs" -> outputs()
                "renderers" -> renderersJson()
                "settings" -> loadSettings()
                "setSettings" -> setSettings(args.getJSONObject(0))
                "rules" -> rulesJson()
                "setRules" -> setRules(args.getString(0), args.getJSONArray(1))
                "platform" -> "android"
                "installPlugin" -> installPlugin(args.optString(0, ""))
                "registry" -> db.listRegistry()
                "setEnabled" -> setEnabled(args.getString(0), args.getBoolean(1))
                "reloadCollector" -> false
                "device" -> cfg.device
                "log" -> { log("[js $from] ${args.optString(0)}"); true }
                else -> throw IllegalArgumentException("모르는 메서드: $method")
            }
            JSONObject().put("id", id).put("result", result ?: JSONObject.NULL).put("error", JSONObject.NULL).toString()
        } catch (e: Throwable) {
            JSONObject().put("id", id).put("result", JSONObject.NULL).put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    private fun query(from: String, args: JSONArray): JSONObject {
        val table = args.getString(0); val opts = args.getJSONObject(1)
        val base = table.substringBefore('@')
        if (from != "shell") {
            val m = renderers[from] ?: throw IllegalStateException("등록되지 않은 렌더러: $from")
            val inputs = m.json.optJSONArray("inputs")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (inputs.none { it == table || it.substringBefore('@') == base }) throw IllegalStateException("inputs에 선언되지 않은 출력: $table")
        }
        val matches = db.listOutputs().filter { it.view == base }
        if (matches.isEmpty()) throw IllegalStateException("없는 출력: $table")
        val cols = LinkedHashMap<String, String>(); for (o in matches) cols.putAll(o.columns)   // 뷰 = 모든 열 집합 버전의 합집합
        return db.query(QuerySpec.parse(base, opts), TableMeta(base, matches[0].isSpan, cols))
    }

    private fun outputs(): JSONArray = JSONArray().apply {
        for (o in db.listOutputs()) put(JSONObject().put("plugin", o.plugin).put("output", o.output).put("schemaVersion", o.schemaVersion).put("isSpan", o.isSpan)
            .put("columns", JSONObject(o.columns)).put("label", o.label).put("pluginName", o.pluginName).put("table", "${o.plugin}/${o.output}@${o.schemaVersion}").put("view", o.view))
    }

    private fun renderersJson(): JSONArray = JSONArray().apply {
        for ((id, m) in renderers) {
            val inst = db.getInstall(id, cfg.device)
            put(JSONObject().put("id", id).put("name", m.name).put("version", m.version).put("slot", m.json.optString("slot"))
                .put("entry", "https://plugin-$id.lifelog/" + (m.runtimes["webview"] ?: "index.html"))
                .put("enabled", inst?.enabled ?: false).put("unmet", JSONArray()).put("settings", inst?.settings ?: JSONObject.NULL)
                .put("decl", m.json.optJSONArray("settings") ?: JSONObject.NULL)
                .put("platforms", m.json.optJSONArray("platforms") ?: JSONObject.NULL).put("requires", m.json.optJSONArray("requires") ?: JSONArray())
                .put("supported", m.supports("android")))
        }
    }

    // ---- 설정(로컬) + 분류 규칙(DB) ----
    private fun loadLocal(): JSONObject = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
    private fun sharedRules(fresh: Boolean = false): JSONObject? {
        if (!fresh && rules != null && System.currentTimeMillis() - rulesAt < 5000) return rules
        rules = db.categoryRules(); rulesAt = System.currentTimeMillis(); return rules
    }
    private fun loadSettings(): JSONObject {
        val o = loadLocal()
        val shared = sharedRules()
        if (shared != null) { o.put("categories", shared.getJSONArray("pc")); o.put("phone_categories", shared.getJSONArray("phone")) }
        o.put("rules_shared", shared != null)
        if (!o.has("day_start")) o.put("day_start", "04:00")
        if (!o.has("views")) o.put("views", JSONArray())
        if (!o.has("categories")) o.put("categories", JSONArray())
        if (!o.has("phone_categories")) o.put("phone_categories", JSONArray())
        if (!o.has("theme")) o.put("theme", "system")
        return o
    }
    private fun setSettings(s: JSONObject): JSONObject {
        s.remove("rules_shared")
        if (sharedRules() != null) { s.remove("categories"); s.remove("phone_categories") }
        settingsFile.writeText(s.toString(2))
        return loadSettings()
    }
    private fun rulesJson(): JSONObject { val s = loadSettings(); return JSONObject().put("pc", s.getJSONArray("categories")).put("phone", s.getJSONArray("phone_categories")).put("shared", s.getBoolean("rules_shared")) }
    private fun setRules(kind0: String, list: JSONArray): JSONObject {
        val kind = if (kind0 == "pc") "pc" else "phone"
        if (sharedRules() != null) { db.setCategoryRules(kind, list.toString()); sharedRules(fresh = true) }
        else { val o = loadLocal(); o.put(if (kind == "pc") "categories" else "phone_categories", list); settingsFile.writeText(o.toString(2)) }
        return rulesJson()
    }

    // ---- 플러그인 ----
    private fun installPlugin(source: String): JSONObject {
        if (source.isBlank()) throw IllegalArgumentException("휴대폰에서는 GitHub URL로만 설치합니다")
        val r = PluginInstaller.install(db, cfg.device, pluginsDir(context), source.trim(), true, context.cacheDir, expectType = "renderer")
        mapPlugins()
        return JSONObject().put("installed", true).put("id", r.manifest.id).put("type", "renderer").put("reloaded", false)
    }
    private fun setEnabled(id: String, on: Boolean): JSONObject {
        val inst = db.getInstall(id, cfg.device) ?: throw IllegalStateException("이 기기에 설치되지 않은 플러그인입니다")
        val m = renderers[id]
        val granted = if (on && m != null) JSONArray(m.capabilities.map { it.first }) else JSONArray(inst.granted.toList())
        val row = db.setInstall(id, cfg.device, inst.installedVersion, on, inst.settings.toString(), granted.toString())
        return JSONObject().put("row", row).put("reloaded", false)
    }
}
