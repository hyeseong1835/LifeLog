package dev.hyeseong.lifelog.collector.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * 플러그인 설치(GitHub URL·zip URL). collector-windows/src/LifeLog.Core/PluginInstaller.cs와 같은 규칙.
 * https://github.com/소유자/저장소[/tree/브랜치[/폴더]] → codeload zip. plugins/<id>로 풀고 등록, 출처 URL을 등록부에 적는다.
 */
object PluginInstaller {
    class Result(val manifest: Manifest, val dir: File, val registered: JSONObject, val install: JSONObject?)

    fun resolveUrl(url: String): Pair<String, String?> {
        val u = URL(url)
        if (u.host.equals("github.com", true) || u.host.equals("www.github.com", true)) {
            val seg = u.path.trim('/').split('/').filter { it.isNotEmpty() }
            if (seg.size < 2) throw IllegalArgumentException("GitHub URL은 https://github.com/소유자/저장소 꼴이어야 합니다")
            val owner = seg[0]; val repo = seg[1].removeSuffix(".git")
            var ref: String? = null; var sub: String? = null
            if (seg.size >= 4 && (seg[2] == "tree" || seg[2] == "blob")) { ref = seg[3]; if (seg.size > 4) sub = seg.drop(4).joinToString("/") }
            val zip = if (ref == null) "https://codeload.github.com/$owner/$repo/zip/HEAD" else "https://codeload.github.com/$owner/$repo/zip/refs/heads/$ref"
            return zip to sub
        }
        if (u.path.endsWith(".zip", true)) return url to null
        throw IllegalArgumentException("GitHub 저장소 URL이나 .zip URL이어야 합니다: $url")
    }

    private fun download(url: String, into: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "lifelog-plugin-installer")
        if (c.responseCode !in 200..299) throw IllegalStateException("내려받기 실패 ${c.responseCode}: $url")
        c.inputStream.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val f = File(into, e.name)
                    if (!f.canonicalPath.startsWith(into.canonicalPath)) throw IllegalStateException("zip 경로가 밖을 가리킵니다: ${e.name}")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile!!.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                    z.closeEntry(); e = z.nextEntry
                }
            }
        }
    }

    private fun findManifestDir(root: File, sub: String?): File {
        var top = root
        val entries = root.listFiles() ?: emptyArray()
        if (entries.size == 1 && entries[0].isDirectory) top = entries[0]
        if (sub != null) {
            val d = File(top, sub)
            if (File(d, "manifest.json").exists()) return d
            throw IllegalStateException("$sub 에 manifest.json이 없습니다")
        }
        if (File(top, "manifest.json").exists()) return top
        val found = top.walkTopDown().filter { it.name == "manifest.json" }.toList()
        if (found.size == 1) return found[0].parentFile!!
        throw IllegalStateException(if (found.isEmpty()) "manifest.json이 없습니다" else "manifest.json이 여럿입니다. URL 뒤에 /tree/<브랜치>/<폴더>를 붙이세요")
    }

    fun install(db: Db, device: String, pluginsDir: File, source: String, grantAll: Boolean, cacheDir: File, expectType: String = "collector"): Result {
        val (zipUrl, sub) = resolveUrl(source)
        val tmp = File(cacheDir, "plugin-" + System.nanoTime()).apply { mkdirs() }
        try {
            download(zipUrl, tmp)
            val src = findManifestDir(tmp, sub)
            val m = Manifest.load(src)
            if (m.type != expectType) throw IllegalStateException("${if (expectType == "collector") "수집기" else "렌더러"} 매니페스트가 아닙니다: ${m.type}")
            if (!m.supports("android")) throw IllegalStateException("${m.id}는 휴대폰(android)을 지원하지 않습니다(platforms: ${m.platforms.joinToString()})")
            val dest = File(pluginsDir, m.id)
            dest.deleteRecursively(); dest.mkdirs()
            src.walkTopDown().filter { it.isFile && !it.relativeTo(src).path.startsWith(".git") }.forEach { f ->
                val t = File(dest, f.relativeTo(src).path); t.parentFile!!.mkdirs(); f.copyTo(t, true)
            }
            val reg = db.registerPlugin(m, device)
            db.setPluginSource(m.id, source)
            File(dest, ".source.json").writeText(JSONObject().put("source", source).put("installed_at", java.time.Instant.now().toString()).toString())
            val inst = if (grantAll) db.setInstall(m.id, device, m.version, true, null, JSONArray(m.capabilities.map { it.first }).toString()) else null
            return Result(m, dest, reg, inst)
        } finally { tmp.deleteRecursively() }
    }
}
