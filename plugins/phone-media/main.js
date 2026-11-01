// 미디어 재생 수집기(안드로이드). media 소스. 재생 중인 동안 하나의 구간. 곡이 바뀌면 새 구간. 앱마다 따로 센다.
const cur = {}; // package → { id, title }
export default {
  start(ctx) {
    ctx.on("media", ev => {
      const c = cur[ev.package];
      const same = c && c.title === ev.title;
      if (ev.playing) {
        if (same) return;
        if (c) ctx.span.close(c.id, ev.time);
        cur[ev.package] = { id: ctx.span.open("media", { start: ev.time, package: ev.package, title: ev.title, artist: ev.artist, album: ev.album, duration_ms: ev.duration_ms }), title: ev.title };
      } else if (c && same) {
        ctx.span.close(c.id, ev.time);
        delete cur[ev.package];
      }
    });
  },
  stop(ctx) { for (const p in cur) ctx.span.close(cur[p].id, ctx.now()); for (const p in cur) delete cur[p]; }
};
