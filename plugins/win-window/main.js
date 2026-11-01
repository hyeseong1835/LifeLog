// 포커스 창 수집기. window 소스의 이벤트를 구간으로. 제목 마스킹은 privacy 설정.
let cur = null;
export default {
  start(ctx) {
    ctx.on("window", ev => {
      if (cur) ctx.span.close(cur, ev.time);
      const masked = (ctx.privacy.mask_title_apps || []).includes(ev.app);
      cur = ctx.span.open("window", { start: ev.time, app: ev.app, title: masked ? null : ev.title, pid: ev.pid });
    });
  },
  stop(ctx) { if (cur) { ctx.span.close(cur, ctx.now()); cur = null; } }
};
