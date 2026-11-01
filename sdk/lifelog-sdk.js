// LifeLog 렌더러 SDK. 해석은 보는 시점에 렌더러가 한다(Documents/새 구조 설계.md 4절). 하루 경계 계산은 없다.
// 행은 lifelog.query가 주는 그대로: 구간 { start, end, ... }, 시점 { time, ... }. 시각은 ISO 문자열이거나 Date.
(function (g) {
  const t = v => v instanceof Date ? v.getTime() : typeof v === "number" ? v : Date.parse(v);
  const pad = n => String(n).padStart(2, "0");

  /** 구간을 [start, end)로 자른다. 밖이면 버린다. 결과는 { ...row, s, e }(ms). */
  function clip(rows, start, end) {
    const S = t(start), E = t(end), out = [];
    for (const r of rows) {
      const s = Math.max(S, t(r.start)), e = Math.min(E, t(r.end));
      if (e > s) out.push(Object.assign({}, r, { s, e }));
    }
    return out;
  }
  /** rows(이미 clip한 것)에서 cuts 구간을 뺀다. 창 − 자리 비움. 둘 다 s/e를 가진다. */
  function subtract(rows, cuts) {
    const cs = cuts.map(c => [c.s, c.e]).sort((a, b) => a[0] - b[0]);
    const out = [];
    for (const r of rows) {
      let pieces = [[r.s, r.e]];
      for (const [cs0, ce0] of cs) {
        if (ce0 <= r.s) continue; if (cs0 >= r.e) break;
        const next = [];
        for (const [a, b] of pieces) {
          if (ce0 <= a || cs0 >= b) { next.push([a, b]); continue; }
          if (cs0 > a) next.push([a, cs0]);
          if (ce0 < b) next.push([ce0, b]);
        }
        pieces = next;
      }
      for (const [a, b] of pieces) if (b > a) out.push(Object.assign({}, r, { s: a, e: b }));
    }
    return out;
  }
  /** 같은 key가 join(ms) 안에 이어지면 합치고 min(ms) 미만은 버린다. */
  function merge(rows, keyFn, join, min) {
    const sorted = rows.slice().sort((a, b) => a.s - b.s);
    const out = [];
    for (const r of sorted) {
      const last = out[out.length - 1];
      if (last && keyFn(last) === keyFn(r) && r.s - last.e <= (join || 0)) { last.e = Math.max(last.e, r.e); continue; }
      out.push(Object.assign({}, r));
    }
    return out.filter(r => r.e - r.s >= (min || 0));
  }
  const sumMs = rows => rows.reduce((a, r) => a + (r.e - r.s), 0);
  /** keyFn별 합계(ms), 큰 순. [{ key, ms, n }] */
  function groupSum(rows, keyFn) {
    const m = new Map();
    for (const r of rows) { const k = keyFn(r); const v = m.get(k) || { key: k, ms: 0, n: 0 }; v.ms += r.e - r.s; v.n++; m.set(k, v); }
    return [...m.values()].sort((a, b) => b.ms - a.ms);
  }
  /** start부터 end까지 시간대 버킷별 ms. [{ hour: Date, ms }] */
  function bucketHours(rows, start, end) {
    const S = t(start), E = t(end), out = [];
    for (let h = S; h < E; h += 3600e3) {
      let ms = 0;
      for (const r of rows) { const a = Math.max(h, r.s), b = Math.min(h + 3600e3, r.e); if (b > a) ms += b - a; }
      out.push({ hour: new Date(h), ms });
    }
    return out;
  }
  /** 켬/끔 같은 시점 이벤트를 구간으로. on(ev)·off(ev)는 불리언. 범위 끝에서 열린 것은 end로 닫는다. */
  function pairEvents(events, on, off, start, end) {
    const S = t(start), E = t(end), out = [];
    let open = null;
    for (const ev of events.slice().sort((a, b) => t(a.time) - t(b.time))) {
      const at = Math.min(Math.max(t(ev.time), S), E);
      if (on(ev)) { if (open === null) open = at; }
      else if (off(ev)) { if (open !== null) { if (at > open) out.push({ s: open, e: at }); open = null; } }
    }
    if (open !== null && E > open) out.push({ s: open, e: E });
    return out;
  }
  /**
   * 분류 규칙 [{ name, app, title, color? }](정규식, 대소문자 무시). 위에서부터 처음 맞는 규칙의 name을 돌려준다. 없으면 null.
   * 이름의 "/"가 계층이다("개발/VSCode"). 구체적인 규칙을 위에 두면 먼저 맞는다. 자식 규칙 개념은 없다(2026-11-02 제거).
   */
  function matchRules(rules, app, title) {
    for (const r of rules || []) {
      if (!r || !r.name || (!r.app && !r.title)) continue;
      try {
        if (r.app && !new RegExp(r.app, "i").test(app || "")) continue;
        if (r.title && !new RegExp(r.title, "i").test(title || "")) continue;
        return r.name;
      } catch (e) { /* 잘못된 정규식은 건너뜀 */ }
    }
    return null;
  }
  /** 경로의 마지막 조각("개발/VSCode" → "VSCode"). 렌더러는 이것을 보여 준다. */
  const leafName = path => String(path).split("/").pop();
  function fmtDur(ms) {
    const m = Math.round(ms / 60000); if (m < 1) return ms >= 1000 ? Math.round(ms / 1000) + "초" : "0분";
    const h = Math.floor(m / 60); return h ? `${h}시간 ${m % 60}분` : `${m}분`;
  }
  const fmtTime = d => { d = new Date(t(d)); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const hashColor = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) | 0; return `hsl(${Math.abs(h) % 360} 60% 60%)`; };
  const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  /** 흔한 조합: 노트북 창 구간에서 자리 비움을 뺀 것. query 결과 두 개를 받는다. */
  function activeWindows(windowRows, afkRows, range) {
    const w = clip(windowRows, range.start, range.end);
    const afk = clip(afkRows.filter(r => r.afk === true), range.start, range.end);
    return subtract(w, afk);
  }

  const UNCATEGORIZED = "미분류";
  /**
   * 분류 위젯 공통: 창(컴퓨터)·앱(휴대폰) 행을 규칙으로 분류한 항목 목록.
   * { win, afk, app, range, devices(비우면 전부), pcRules, phRules } → [{ s, e, device, app, title, path, top }]
   * path는 "웹사이트/유튜브" 같은 경로, 안 맞으면 "미분류". top은 첫 조각.
   */
  function categorize({ win = [], afk = [], app = [], range, devices = [], pcRules = [], phRules = [] }) {
    useRuleColors(pcRules, phRules);
    const include = d => devices.length === 0 || devices.includes(d);
    const out = [];
    for (const d of [...new Set(win.map(r => r.device))].filter(include)) {
      for (const r of activeWindows(win.filter(x => x.device === d), afk.filter(x => x.device === d), range)) {
        const path = matchRules(pcRules, r.app, r.title) || UNCATEGORIZED;
        out.push({ s: r.s, e: r.e, device: d, app: String(r.app || "?").replace(/\.exe$/i, ""), title: r.title || "", path, top: path.split("/")[0] });
      }
    }
    for (const d of [...new Set(app.map(r => r.device))].filter(include)) {
      for (const r of clip(app.filter(x => x.device === d), range.start, range.end)) {
        const path = matchRules(phRules, r.package, "") || UNCATEGORIZED;
        out.push({ s: r.s, e: r.e, device: d, app: "📱 " + String(r.package || "?").split(".").slice(-1)[0], title: "", path, top: path.split("/")[0] });
      }
    }
    return out;
  }
  /** 규칙에 적힌 색(경로 → color). categorize가 규칙을 받을 때 채운다. */
  const ruleColors = new Map();
  function collectColors(rules) { for (const r of rules || []) if (r && r.name && r.color) ruleColors.set(r.name, r.color); }
  function useRuleColors(pcRules, phRules) { ruleColors.clear(); collectColors(pcRules); collectColors(phRules); }
  /** 분류 색: 규칙에 색이 있으면 그것(가장 가까운 상위 규칙의 색을 물려받음), 없으면 최상위 이름 해시. 모든 위젯에서 같다. 미분류는 회색. */
  function categoryColor(path) {
    const parts = String(path).split("/");
    if (parts[0] === UNCATEGORIZED) return "#8a8a8a";
    for (let i = parts.length; i >= 1; i--) { const c = ruleColors.get(parts.slice(0, i).join("/")); if (c) return c; }
    return hashColor(parts[0]);
  }
  /** 경로별 합계 트리. { name, path, ms, children: Map, apps: Map(app → ms) } */
  function categoryTree(entries) {
    const root = { name: "", path: "", ms: 0, children: new Map(), apps: new Map() };
    for (const en of entries) {
      const ms = en.e - en.s; root.ms += ms;
      let node = root, path = "";
      for (const part of en.path.split("/")) {
        path = path ? path + "/" + part : part;
        if (!node.children.has(part)) node.children.set(part, { name: part, path, ms: 0, children: new Map(), apps: new Map() });
        node = node.children.get(part); node.ms += ms;
      }
      node.apps.set(en.app, (node.apps.get(en.app) || 0) + ms);
    }
    return root;
  }

  g.LifeLogSdk = { categorize, categoryColor, categoryTree, useRuleColors, leafName, UNCATEGORIZED, clip, subtract, merge, sumMs, groupSum, bucketHours, pairEvents, matchRules, fmtDur, fmtTime, hashColor, esc, activeWindows, ms: t };
})(typeof window !== "undefined" ? window : globalThis);
