package dev.hyeseong.lifelog.collector.js

import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.QuickJSContext
import dev.hyeseong.lifelog.collector.core.Manifest
import java.io.File

/**
 * JS 수집기 플러그인 하나 = QuickJS 컨텍스트 하나(quickjs-wrapper, 16KB 페이지 정렬). 만든 스레드(디스패처)에서만 쓴다.
 * 호스트 함수는 __host 객체의 JSCallFunction으로 붙이고, 행·이벤트는 JSON 문자열로 오간다.
 * 프렐류드(PRELUDE)가 JS 쪽 ctx를 만들어 호스트 API(Documents/수집기 호스트 API.md 3절)와 같은 모양을 제공한다.
 */
class JsPlugin(val manifest: Manifest, val dir: File, private val host: HostApi) : AutoCloseable {
    /** JS → 호스트. 시각은 ISO 문자열. */
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

    private var qjs: QuickJSContext? = null
    private var fStart: JSFunction? = null
    private var fStop: JSFunction? = null
    private var fDeliver: JSFunction? = null
    private var fTick: JSFunction? = null
    var failures = 0; private set
    var disabled = false; private set
    val subscribed = LinkedHashSet<String>()

    fun load() {
        ensureLoaded()
        val file = manifest.runtimes["js"] ?: throw IllegalStateException("js 런타임이 아닙니다")
        var code = File(dir, file).readText()
        // ES 모듈 default export를 전역 변수로. import는 지원하지 않는다(플러그인은 파일 하나).
        code = code.replace(Regex("""export\s+default\s+"""), "var __plugin = ")
        val q = QuickJSContext.create()
        q.setMemoryLimit(64 * 1024 * 1024)
        q.setMaxStackSize(1024 * 1024)
        val h = q.createNewJSObject()
        fun s(a: Array<out Any?>, i: Int) = a.getOrNull(i)?.toString() ?: ""
        h.setProperty("subscribe", JSCallFunction { a -> host.subscribe(s(a, 0)); null })
        h.setProperty("every", JSCallFunction { a -> host.every(s(a, 0), (a[1] as Number).toInt()); null })
        h.setProperty("read", JSCallFunction { a -> host.read(s(a, 0), s(a, 1)) })
        h.setProperty("spanOpen", JSCallFunction { a -> host.spanOpen(s(a, 0), s(a, 1)) })
        h.setProperty("spanUpdate", JSCallFunction { a -> host.spanUpdate(s(a, 0), s(a, 1)); null })
        h.setProperty("spanClose", JSCallFunction { a -> host.spanClose(s(a, 0), s(a, 1)); null })
        h.setProperty("spanPut", JSCallFunction { a -> host.spanPut(s(a, 0), s(a, 1), s(a, 2)) })
        h.setProperty("spanDelete", JSCallFunction { a -> host.spanDelete(s(a, 0), s(a, 1)); null })
        h.setProperty("emit", JSCallFunction { a -> host.emit(s(a, 0), s(a, 1), s(a, 2)) })
        h.setProperty("stateGet", JSCallFunction { a -> host.stateGet(s(a, 0)) })
        h.setProperty("stateSet", JSCallFunction { a -> host.stateSet(s(a, 0), s(a, 1)); null })
        h.setProperty("settings", JSCallFunction { host.settings() })
        h.setProperty("has", JSCallFunction { a -> host.has(s(a, 0)) })
        h.setProperty("now", JSCallFunction { host.now() })
        h.setProperty("log", JSCallFunction { a -> host.log(s(a, 0)); null })
        q.globalObject.setProperty("__host", h)
        q.evaluate(PRELUDE, "prelude.js")
        q.evaluate(code, file)
        q.evaluate("if (typeof __plugin !== 'object' || !__plugin.start) throw new Error('main.js는 { start, stop }을 default export 해야 합니다');", "check.js")
        val entry = q.globalObject.getJSObject("__entry")
        fStart = entry.getJSFunction("start").also { it.hold() }
        fStop = entry.getJSFunction("stop").also { it.hold() }
        fDeliver = entry.getJSFunction("deliver").also { it.hold() }
        fTick = entry.getJSFunction("tick").also { it.hold() }
        qjs = q
    }

    fun start() { fStart!!.callVoid() }
    fun stop() = guard { fStop?.callVoid() }
    fun deliver(source: String, eventJson: String) { if (!disabled && source in subscribed) guard { fDeliver!!.callVoid(source, eventJson) } }
    fun tick(i: Int) = guard { fTick!!.callVoid(i) }

    private inline fun guard(block: () -> Unit) {
        try { block(); failures = 0 }
        catch (e: Exception) {
            failures++
            host.log("[${manifest.id}] 핸들러 실패 ${failures}회: ${e.message}")
            if (failures >= 5 && !disabled) { disabled = true; host.log("[${manifest.id}] 연속 실패로 이 기기에서 끕니다") }
        }
    }

    override fun close() { try { qjs?.destroy() } catch (_: Exception) {}; qjs = null }

    companion object {
        @Volatile private var loaded = false
        private fun ensureLoaded() { if (!loaded) { QuickJSLoader.init(); loaded = true } }

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
