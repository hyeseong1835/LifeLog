// 앱 사용 수집기. usage 읽기 소스를 15분마다 읽어 resumed→paused 짝을 구간으로. 5분 겹쳐 읽어도 id가 같아 안전하다.
function toSpans(events) {
  const out = []; let cur = null; let since = null;
  for (const e of events) {
    if (e.type === "resumed") {
      if (cur && cur !== e.package) out.push({ start: since, end: e.time, package: cur });
      if (cur !== e.package) { cur = e.package; since = e.time; }
    } else if ((e.type === "paused" || e.type === "stopped" || e.type === "screen_off") && cur) {
      if (e.type === "stopped" && e.package !== cur) continue;
      out.push({ start: since, end: e.time, package: cur }); cur = null;
    }
  }
  return out.filter(s => new Date(s.end) - new Date(s.start) >= 60000);   // 1분 미만은 버린다(창 전환 잡음)
}
export default {
  start(ctx) {
    ctx.every("15m", () => {
      const now = ctx.now();
      const from = ctx.state.get("last") || new Date(now - 24 * 3600 * 1000).toISOString();
      const events = ctx.read("usage", { from, to: now.toISOString() });
      for (const s of toSpans(events)) ctx.span.put("app", s);
      ctx.state.set("last", new Date(now - 5 * 60 * 1000).toISOString());
    });
  },
  stop(ctx) {}
};
