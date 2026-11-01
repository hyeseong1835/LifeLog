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
  /** 분류 규칙 [{ name, app, title }](정규식, 대소문자 무시). 첫 번째로 맞는 규칙의 name, 없으면 null. */
  function matchRules(rules, app, title) {
    for (const r of rules || []) {
      try {
        if (r.app && !new RegExp(r.app, "i").test(app || "")) continue;
        if (r.title && !new RegExp(r.title, "i").test(title || "")) continue;
        if (!r.app && !r.title) continue;
        return r.name;
      } catch (e) { /* 잘못된 정규식은 건너뜀 */ }
    }
    return null;
  }
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

  g.LifeLogSdk = { clip, subtract, merge, sumMs, groupSum, bucketHours, pairEvents, matchRules, fmtDur, fmtTime, hashColor, esc, activeWindows, ms: t };
})(typeof window !== "undefined" ? window : globalThis);
