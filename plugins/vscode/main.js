// VS Code 편집 수집기. collector-vscode 확장이 쓰는 events.jsonl을 30초마다 이어 읽어 "포커스된 창에서 한 파일을 보고 있던 구간"을 만든다.
// 파일이 바뀌거나 창이 포커스를 잃으면 구간이 끝난다. 하트비트(1분)가 구간을 늘린다. 5초 미만은 버린다.
// 읽은 위치와 열린 구간은 상태에 둔다. 행 id는 구간 시작 시각 + 파일로 고정(key)이라 늘어날 때마다 덮어쓴다.
const FILE = "%LOCALAPPDATA%/LifeLog/vscode/events.jsonl";
const MIN_MS = 5000, STALE_MS = 3 * 60 * 1000;
export default {
  start(ctx) {
    function put(cur) {
      if (new Date(cur.end) - new Date(cur.start) < MIN_MS) return;
      ctx.span.put("editor", { start: cur.start, end: cur.end, project: cur.project, file: cur.file, language: cur.language }, { key: cur.start + "|" + cur.project + "|" + cur.file });
    }
    const scan = () => {
      const st = ctx.state.get("scan") || { next: 0, cur: null };
      const f = ctx.file.stat(FILE);
      if (!f) return;
      if (f.size < st.next) st.next = 0;            // 파일이 새로 만들어짐
      let cur = st.cur, next = st.next, changed = false;
      while (next < f.size) {
        const r = ctx.file.read(FILE, { from: next, max: 1024 * 1024 });
        for (const line of r.text.split("\n")) {
          if (!line) continue;
          let ev; try { ev = JSON.parse(line); } catch (e) { continue; }
          const active = ev.focused && ev.file && ev.kind !== "blur" && ev.kind !== "stop";
          if (active) {
            if (cur && cur.project === ev.project && cur.file === ev.file && cur.language === ev.language) { cur.end = ev.time; continue; }
            if (cur) { put(cur); changed = true; }
            cur = { start: ev.time, end: ev.time, project: ev.project, file: ev.file, language: ev.language };
          } else if (cur) {
            cur.end = ev.time; put(cur); cur = null; changed = true;
          }
        }
        if (r.next <= next) break;
        next = r.next;
      }
      // 확장이 죽어 하트비트가 끊긴 열린 구간은 마지막 시각에서 닫는다
      if (cur && ctx.now() - new Date(cur.end) > STALE_MS) { put(cur); cur = null; changed = true; }
      if (cur) put(cur);
      ctx.state.set("scan", { next, cur });
      if (changed) ctx.log("info", "편집 구간 갱신");
    };
    ctx.every("30s", scan);
    scan();
  },
  stop(ctx) {}
};
