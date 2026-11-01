// 미디어 재생 수집기. media 소스. 재생 중인 동안 하나의 구간. 곡이 바뀌면 새 구간.
let cur = null; // { id, app, title }
export default {
  start(ctx) {
    ctx.on("media", ev => {
      const same = cur && cur.app === ev.app && cur.title === ev.title;
      if (ev.playing) {
        if (same) return;
        if (cur) ctx.span.close(cur.id, ev.time);
        cur = { id: ctx.span.open("media", { start: ev.time, app: ev.app, title: ev.title, artist: ev.artist, album: ev.album }), app: ev.app, title: ev.title };
      } else if (cur && same) {
        ctx.span.close(cur.id, ev.time);
        cur = null;
      }
    });
  },
  stop(ctx) { if (cur) { ctx.span.close(cur.id, ctx.now()); cur = null; } }
};
