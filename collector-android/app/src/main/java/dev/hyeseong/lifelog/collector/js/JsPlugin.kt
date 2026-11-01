package dev.hyeseong.lifelog.collector.js

import app.cash.quickjs.QuickJs
import dev.hyeseong.lifelog.collector.core.Manifest
import java.io.File

/**
 * JS 수집기 플러그인 하나 = QuickJs 인스턴스 하나. 모든 호출은 디스패처 스레드에서만.
 * quickjs-android는 인터페이스 메서드로 문자열·숫자·불리언만 넘길 수 있으므로 행·이벤트는 JSON 문자열로 오간다.
 * 프렐류드(PRELUDE)가 JS 쪽 ctx를 만들어 호스트 API(Documents/수집기 호스트 API.md 3절)와 같은 모양을 제공한다.
 */
class JsPlugin(val manifest: Manifest, val dir: File, private val host: HostApi) : AutoCloseable {
    /** JS → 호스트. 이름은 소문자. 시각은 ISO 문자열. */
    interface HostApi {
        fun subscribe(source: String)
        fun every(interval: String, timerIndex: Int)
        fun read(source: String, argsJson: String): String
        fun spanOpen(output: String, rowJson: String): String
        fun spanUpdate(id: String, patchJson: String)
        fun spanClose(id: String, endIso: String)
        fun spanPut(output: String, rowJson: String, key: String): String
        fun spanDelete(output: String, id: String)
        fun emit(output: String, rowJson: String, key: String): String
        fun stateGet(key: String): String
        fun stateSet(key: String, valueJson: String)
        fun settings(): String
        fun has(capability: String): Boolean
        fun now(): String
        fun log(msg: String)
    }

    /** 호스트 → JS. 프렐류드의 __entry. */
    interface Entry {
        fun start()
        fun stop()
        fun deliver(source: String, eventJson: String)
        fun tick(timerIndex: Int)
    }

    private var qjs: QuickJs? = null
    private var entry: Entry? = null
    var failures = 0; private set
    var disabled = false; private set
    val subscribed = LinkedHashSet<String>()

    fun load() {
        val file = manifest.runtimes["js"] ?: throw IllegalStateException("js 런타임이 아닙니다")
        var code = File(dir, file).readText()
        // ES 모듈 default export를 전역 변수로. import는 지원하지 않는다(플러그인은 파일 하나).
        code = code.replace(Regex("""export\s+default\s+"""), "var __plugin = ")
        val q = QuickJs.create()
        q.set("__host", HostApi::class.java, host)
        q.evaluate(PRELUDE, "prelude.js")
        q.evaluate(code, file)
        q.evaluate("if (typeof __plugin !== 'object' || !__plugin.start) throw new Error('main.js는 { start, stop }을 default export 해야 합니다');", "check.js")
        entry = q.get("__entry", Entry::class.java)
        qjs = q
    }

    fun start() = entry!!.start()
    fun stop() = guard { entry?.stop() }
    fun deliver(source: String, eventJson: String) { if (!disabled && source in subscribed) guard { entry!!.deliver(source, eventJson) } }
    fun tick(i: Int) = guard { entry!!.tick(i) }

    private inline fun guard(block: () -> Unit) {
        try { block(); failures = 0 }
        catch (e: Exception) {
            failures++
            host.log("[${manifest.id}] 핸들러 실패 ${failures}회: ${e.message}")
            if (failures >= 5 && !disabled) { disabled = true; host.log("[${manifest.id}] 연속 실패로 이 기기에서 끕니다") }
        }
    }

    override fun close() { try { qjs?.close() } catch (_: Exception) {} }

    companion object {
        val PRELUDE = """
            var __handlers = {};
            var __timers = [];
            function __j(v) { return JSON.stringify(v === undefined ? null : v); }
            function __iso(t) { if (t == null) return ""; if (t instanceof Date) return t.toISOString(); return String(t); }
            var ctx = {
              on: function (src, fn) { (__handlers[src] = __handlers[src] || []).push(fn); __host.subscribe(src); },
              every: function (interval, fn) { var i = __timers.push(fn) - 1; __host.every(interval, i); },
              read: function (src, args) { return JSON.parse(__host.read(src, __j(args || {}))); },
              span: {
                open: function (o, row) { return __host.spanOpen(o, __j(row)); },
                update: function (id, p) { __host.spanUpdate(id, __j(p)); },
                close: function (id, end) { __host.spanClose(id, __iso(end)); },
                put: function (o, row, opts) { return __host.spanPut(o, __j(row), opts && opts.key != null ? String(opts.key) : ""); },
                delete: function (o, id) { __host.spanDelete(o, id); }
              },
              event: { emit: function (o, row, opts) { return __host.emit(o, __j(row), opts && opts.key != null ? String(opts.key) : ""); } },
              state: {
                get: function (k) { var s = __host.stateGet(k); return s === "" ? undefined : JSON.parse(s); },
                set: function (k, v) { __host.stateSet(k, __j(v)); }
              },
              get settings() { return JSON.parse(__host.settings()); },
              get privacy() { return JSON.parse(__host.settings()); },
              has: function (c) { return __host.has(c); },
              now: function () { return new Date(__host.now()); },
              log: function (a, b) { __host.log(b === undefined ? String(a) : a + ": " + b); }
            };
            var __entry = {
              start: function () { __plugin.start(ctx); },
              stop: function () { if (__plugin.stop) __plugin.stop(ctx); },
              deliver: function (src, json) { var ev = JSON.parse(json); var hs = __handlers[src] || []; for (var i = 0; i < hs.length; i++) hs[i](ev); },
              tick: function (i) { __timers[i](); }
            };
        """.trimIndent()
    }
}
