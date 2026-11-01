// 셸: 날짜 이동, 내장 타임라인(등록부의 모든 수집기 출력을 공통 열 + 라벨 열로 그림), 렌더러 플러그인 iframe.
// 하루 = 날짜 + day_start(설정) ~ 다음 날 같은 시각. 설정은 기기별(Documents/새 구조 설계.md 2.3).
const $ = s => document.querySelector(s);
let settings = { day_start: "04:00", tz: "" };
let dayOffset = 0;          // 0 = 오늘(지금이 속한 하루)
let outputs = [];
let renderers = [];

function dayRange(offset) {
  const [h, m] = settings.day_start.split(":").map(Number);
  const now = new Date();
  let start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), h, m, 0, 0);
  if (start > now) start.setDate(start.getDate() - 1);
  start.setDate(start.getDate() + offset);
  const end = new Date(start); end.setDate(end.getDate() + 1);
  return { start, end };
}
const fmtDate = d => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const fmtTime = d => `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
const hashColor = s => { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) | 0; return `hsl(${Math.abs(h) % 360} 60% 60%)`; };

async function load() {
  const main = $("#main");
  main.innerHTML = '<div class="empty">불러오는 중…</div>';
  const { start, end } = dayRange(dayOffset);
  $("#date").textContent = `${fmtDate(start)} ${fmtTime(start)} ~ ${fmtTime(end)}`;
  const range = { start: start.toISOString(), end: end.toISOString() };
  window.lifelog._broadcastRange(range);

  const frag = document.createDocumentFragment();
  // 시간 축
  const axis = document.createElement("div"); axis.className = "axis";
  for (let i = 0; i <= 24; i += 2) {
    const t = new Date(start.getTime() + i * 3600e3);
    const s = document.createElement("span"); s.style.left = (i / 24 * 100) + "%"; s.textContent = fmtTime(t);
    axis.appendChild(s);
  }
  frag.appendChild(axis);

  // 수집기 출력마다 한 줄
  const seen = new Set();
  let total = 0;
  for (const o of outputs) {
    if (seen.has(o.view)) continue;   // 열 집합 버전이 여럿이어도 뷰는 하나
    seen.add(o.view);
    const lane = document.createElement("div"); lane.className = "lane";
    const name = document.createElement("div"); name.className = "name"; name.textContent = `${o.pluginName} · ${o.output}`; name.title = o.view;
    const track = document.createElement("div"); track.className = "track";
    lane.append(name, track); frag.appendChild(lane);
    try {
      const res = await lifelog.query(o.view, { range, limit: 5000 });
      total += res.rows.length;
      if (res.truncated) name.textContent += " (일부)";
      const span = end - start;
      for (const r of res.rows) {
        const t0 = new Date(r.start ?? r.time), t1 = r.end ? new Date(r.end) : null;
        const label = String(r[o.label] ?? "");
        if (t1) {
          const l = Math.max(0, (t0 - start) / span), w = Math.max(0.0005, Math.min(1, (t1 - start) / span) - l);
          const b = document.createElement("div"); b.className = "block";
          b.style.left = (l * 100) + "%"; b.style.width = (w * 100) + "%"; b.style.background = hashColor(label);
          b.textContent = label; b.dataset.tip = `${fmtTime(t0)}~${fmtTime(t1)}  ${label}` + (r.title ? `\n${r.title}` : "");
          track.appendChild(b);
        } else {
          const k = document.createElement("div"); k.className = "tick";
          k.style.left = ((t0 - start) / span * 100) + "%"; k.dataset.tip = `${fmtTime(t0)}  ${label}`;
          track.appendChild(k);
        }
      }
    } catch (e) { name.textContent += " ⚠"; name.title = e.message; }
  }

  // 렌더러 플러그인: lane은 타임라인 아래 한 줄, page는 맨 아래 큰 영역
  for (const p of renderers) {
    if (!p.enabled) continue;
    const lane = document.createElement("div"); lane.className = "lane plugin";
    const name = document.createElement("div"); name.className = "name"; name.textContent = p.name; name.title = p.id;
    const track = document.createElement("div"); track.className = "track";
    const f = document.createElement("iframe"); f.src = p.entry; f.dataset.plugin = p.id;
    if (p.slot === "page") f.style.height = "480px";
    f.addEventListener("load", () => f.contentWindow.postMessage({ lifelog: "range", range }, "*"));
    track.appendChild(f); lane.append(name, track); frag.appendChild(lane);
  }
  main.innerHTML = "";
  main.appendChild(frag);
  if (total === 0 && outputs.length === 0) main.innerHTML = '<div class="empty">등록된 수집기가 없습니다.</div>';
}

async function init() {
  try {
    settings = Object.assign(settings, await lifelog.settings());
    outputs = await lifelog.outputs();
    renderers = await lifelog.renderers();
    $("#info").textContent = `기기 ${await lifelog.device()} · 하루 시작 ${settings.day_start}`;
    await load();
  } catch (e) {
    $("#main").innerHTML = `<div class="empty">오류: ${e.message}</div>`;
  }
}

$("#prev").onclick = () => { dayOffset--; load(); };
$("#next").onclick = () => { dayOffset++; load(); };
$("#today").onclick = () => { dayOffset = 0; load(); };
$("#reload").onclick = init;
window.addEventListener("message", e => {
  const d = e.data;
  if (d && d.lifelog === "navigate" && d.range) { /* 플러그인의 이동 요청은 아직 날짜 단위만 */ }
});
document.addEventListener("mousemove", e => {
  const t = e.target.closest("[data-tip]"); const tip = $("#tip");
  if (!t) { tip.style.display = "none"; return; }
  tip.textContent = t.dataset.tip; tip.style.display = "block";
  tip.style.left = Math.min(e.clientX + 12, innerWidth - 300) + "px"; tip.style.top = (e.clientY + 14) + "px";
});
init();
