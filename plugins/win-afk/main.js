// 자리 비움 수집기. idle 소스. afk=false 구간과 afk=true 구간을 번갈아 연다(AW afk 워처와 같은 모양).
let cur = null;
function open(ctx, afk, start) { cur = ctx.span.open("afk", { start, afk }); }
export default {
  start(ctx) {
    open(ctx, false, ctx.now());
    ctx.on("idle", ev => {
      if (ev.idle) {               // 자리 비움 시작: 마지막 입력 시각(since)에서 갈라진다
        if (cur) ctx.span.close(cur, ev.since);
        open(ctx, true, ev.since);
      } else {
        if (cur) ctx.span.close(cur, ev.time);
        open(ctx, false, ev.time);
      }
    });
  },
  stop(ctx) { if (cur) { ctx.span.close(cur, ctx.now()); cur = null; } }
};
