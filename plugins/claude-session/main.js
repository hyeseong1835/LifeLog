// Claude Code 세션 수집기. ~/.claude/projects/<프로젝트>/<세션>.jsonl을 2분마다 이어 읽어 세션 하나를 구간 하나로 둔다.
// 구간 = 첫 메시지 ~ 마지막 메시지. turns = 사람이 보낸 메시지 수(도구 결과 제외). 내용은 읽지 않고 시각·cwd·브랜치만 본다.
// 파일별 읽은 위치를 상태에 두어 다시 읽지 않는다. 행 id는 세션 id로 고정(key)이라 갱신은 덮어쓴다.
function isTurn(o) {
  if (o.type !== "user" || !o.message) return false;
  const c = o.message.content;
  if (typeof c === "string") return true;
  if (Array.isArray(c)) return c.some(b => b.type === "text") && !c.some(b => b.type === "tool_result");
  return false;
}
export default {
  start(ctx) {
    const root = ctx.settings.root || "~/.claude/projects";
    const scan = () => {
      const st = ctx.state.get("files") || {};
      let changed = 0, parsed = 0;
      for (const d of ctx.file.list(root, "*")) {
        if (!d.dir) continue;
        for (const f of ctx.file.list(d.path, "*.jsonl")) {     // 하위 폴더(서브에이전트)는 보지 않는다
          const s = st[f.path] || { next: 0, turns: 0 };
          if (f.size <= s.next) continue;
          let next = s.next;
          while (next < f.size) {
            const r = ctx.file.read(f.path, { from: next });
            for (const line of r.text.split("\n")) {
              if (!line) continue;
              let o; try { o = JSON.parse(line); } catch (e) { continue; }
              parsed++;
              if (!o.timestamp || (o.type !== "user" && o.type !== "assistant")) continue;
              if (!s.start) { s.start = o.timestamp; s.id = o.sessionId; s.version = o.version; }
              s.end = o.timestamp;
              if (o.cwd) s.cwd = o.cwd;
              if (o.gitBranch) s.branch = o.gitBranch;
              if (isTurn(o)) s.turns++;
            }
            if (r.next <= next) break;
            next = r.next;
          }
          s.next = next; st[f.path] = s;
          if (s.start && s.id) {
            ctx.span.put("session", { start: s.start, end: s.end, session_id: s.id, project: s.cwd || "", branch: s.branch || "", turns: s.turns, version: s.version || "" }, { key: s.id });
            changed++;
          }
        }
      }
      ctx.state.set("files", st);
      if (changed) ctx.log("info", "세션 " + changed + "개 갱신 (" + parsed + "줄)");
    };
    ctx.every("2m", scan);
    scan();
  },
  stop(ctx) {}
};
