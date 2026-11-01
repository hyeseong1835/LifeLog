// lifelog 브리지. 모든 문서(셸과 플러그인 iframe)에 주입된다.
// 최상위(셸)는 chrome.webview로 호스트와 직접 통신하고, iframe(플러그인)은 부모(셸)에 중계를 맡긴다.
(function () {
  if (window.lifelog) return;
  const isTop = window === window.top;
  const pending = new Map();
  let seq = 0;
  const pluginId = (() => {
    const m = /^plugin-([a-z0-9-]+)\.lifelog$/.exec(location.hostname);
    return m ? m[1] : "shell";
  })();

  function call(method, ...args) {
    const id = pluginId + ":" + (++seq);
    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });
      const msg = { id, from: pluginId, method, args };
      if (isTop) chrome.webview.postMessage(msg);
      else window.parent.postMessage({ lifelog: "call", msg }, "*");
    });
  }

  function settle(reply) {
    const p = pending.get(reply.id);
    if (!p) return false;
    pending.delete(reply.id);
    reply.error ? p.reject(new Error(reply.error)) : p.resolve(reply.result);
    return true;
  }

  // 스크립트 오류를 호스트 로그(dashboard.log)로. 플러그인 iframe의 오류는 셸을 거친다
  window.addEventListener("error", e => { try { call("log", `${pluginId} ${location.pathname}: ${e.message} (${e.lineno})`); } catch (_) {} });
  window.addEventListener("unhandledrejection", e => { try { call("log", `${pluginId}: ${e.reason && e.reason.message || e.reason}`); } catch (_) {} });
  if (isTop) {
    chrome.webview.addEventListener("message", e => {
      const reply = e.data;
      if (settle(reply)) return;
      // 플러그인 iframe의 호출에 대한 응답: id 접두가 플러그인 id
      const from = reply.id.split(":")[0];
      for (const f of document.querySelectorAll("iframe")) {
        if (f.dataset.plugin === from) f.contentWindow.postMessage({ lifelog: "reply", reply }, "*");
      }
    });
    window.addEventListener("message", e => {
      const d = e.data;
      if (!d || d.lifelog !== "call") return;
      chrome.webview.postMessage(d.msg);   // 그대로 호스트로. 권한 검사는 호스트가 from으로 한다
    });
  } else {
    window.addEventListener("message", e => {
      const d = e.data;
      if (d && d.lifelog === "reply") settle(d.reply);
      if (d && d.lifelog === "range") { current.range = d.range; listeners.forEach(fn => fn(d.range)); }
      if (d && d.lifelog === "theme") { current.theme = d.theme; }
      if (d && d.lifelog === "widget") { current.widget = d.widget; widgetListeners.forEach(fn => fn(d.widget)); }
    });
  }

  const listeners = [], widgetListeners = [];
  const current = { range: null, widget: null, theme: matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light" };

  window.lifelog = {
    id: pluginId,
    query: (table, opts) => call("query", table, opts),
    outputs: () => call("outputs"),
    renderers: () => call("renderers"),
    settings: () => call("settings"),
    setSettings: s => call("setSettings", s),
    installPlugin: source => call("installPlugin", source ?? null),
    registry: () => call("registry"),
    setEnabled: (id, on) => call("setEnabled", id, on),
    reloadCollector: () => call("reloadCollector"),
    device: () => call("device"),
    get range() { return current.range; },
    get theme() { return current.theme; },
    /** 위젯 인스턴스 정보 { id, w, h, settings }. 셸이 iframe에 준다. */
    get widget() { return current.widget; },
    onWidgetChange(fn) { widgetListeners.push(fn); if (current.widget) fn(current.widget); },
    /** 범위와 위젯 설정이 둘 다 준비됐을 때 한 번, 이후 바뀔 때마다 부른다 */
    ready(fn) { const go = () => { if (current.range) fn(current.range, current.widget || { settings: {} }); }; listeners.push(go); widgetListeners.push(go); go(); },
    onRangeChange(fn) { listeners.push(fn); if (current.range) fn(current.range); },
    navigate(range) { if (!isTop) window.parent.postMessage({ lifelog: "navigate", range }, "*"); },
    // 셸이 iframe에 범위를 알릴 때 쓴다
    _broadcastRange(range) { current.range = range; listeners.forEach(fn => fn(range)); for (const f of document.querySelectorAll("iframe")) f.contentWindow.postMessage({ lifelog: "range", range }, "*"); },
  };
})();
