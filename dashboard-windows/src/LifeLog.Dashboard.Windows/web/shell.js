// 셸: 날짜 이동, 뷰 탭, 위젯 격자(내장 타임라인 + card 렌더러 iframe), 편집 모드, 설정(하루 시작·분류 규칙).
// 하루 = 날짜 + day_start(설정) ~ 다음 날 같은 시각. 설정·뷰는 기기별(Documents/대시보드 뷰·위젯.md).
const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
let settings = { day_start: "04:00", tz: "", views: [], categories: [] };
let dayOffset = Number(new URLSearchParams(location.search).get("offset")) || 0;   // 0 = 오늘(지금이 속한 하루). ?offset=-1은 개발용
let outputs = [];
let renderers = [];
let viewId = null;
let editing = new URLSearchParams(location.search).get("edit") === "1";   // ?edit=1은 개발용

let range = null;

const DEFAULT_VIEWS = [
  { id: "default", name: "기본", widgets: [
    { id: "w1", type: "builtin:timeline", w: 3 },
    { id: "w2", type: "widget-summary", w: 1, settings: {} },
    { id: "w3", type: "widget-top-apps", w: 1, settings: {} },
    { id: "w4", type: "widget-categories", w: 1, settings: {} },
    { id: "w5", type: "widget-hourly", w: 2, settings: {} },
    { id: "w6", type: "widget-media", w: 1, settings: {} },
    { id: "w7", type: "widget-location", w: 1, settings: {} },
  ] },
];

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
const uid = () => "w" + Math.random().toString(36).slice(2, 8);

function currentView() { return settings.views.find(v => v.id === viewId) || settings.views[0]; }
async function saveSettings() { settings = Object.assign(settings, await lifelog.setSettings(settings)); }

// ---- 내장 타임라인(등록부의 모든 출력을 레인으로) ----
async function renderTimeline(el, { start, end }) {
  const frag = document.createDocumentFragment();
  const axis = document.createElement("div"); axis.className = "axis";
  for (let i = 0; i <= 24; i += 2) {
    const t = new Date(start.getTime() + i * 3600e3);
    const s = document.createElement("span"); s.style.left = (i / 24 * 100) + "%"; s.textContent = fmtTime(t);
    axis.appendChild(s);
  }
  frag.appendChild(axis);
  const seen = new Set();
  const qrange = { start: start.toISOString(), end: end.toISOString() };
  for (const o of outputs) {
    if (seen.has(o.view)) continue;   // 열 집합 버전이 여럿이어도 뷰는 하나
    seen.add(o.view);
    const lane = document.createElement("div"); lane.className = "lane";
    const name = document.createElement("div"); name.className = "name"; name.textContent = `${o.pluginName} · ${o.output}`; name.title = o.view;
    const track = document.createElement("div"); track.className = "track";
    lane.append(name, track); frag.appendChild(lane);
    try {
      const res = await lifelog.query(o.view, { range: qrange, limit: 5000 });
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
  el.innerHTML = ""; el.appendChild(frag);
  if (outputs.length === 0) el.innerHTML = '<div class="empty">등록된 수집기가 없습니다.</div>';
}

// ---- 위젯 격자 ----
function widgetTitle(w) {
  if (w.type === "builtin:timeline") return "타임라인";
  const p = renderers.find(r => r.id === w.type);
  return p ? p.name : w.type;
}

async function load() {
  const main = $("#main");
  resizeObservers.splice(0).forEach(o => o.disconnect());   // 이전 카드의 관찰자는 끊는다
  const { start, end } = dayRange(dayOffset);
  range = { start: start.toISOString(), end: end.toISOString() };
  $("#date").textContent = `${fmtDate(start)} ${fmtTime(start)} ~ ${fmtTime(end)}`;
  renderTabs();
  const view = currentView();
  if (!view) { main.innerHTML = '<div class="empty">뷰가 없습니다. 편집에서 뷰를 추가하세요.</div>'; return; }
  const grid = document.createElement("div"); grid.className = "grid";
  view.widgets.forEach((w, i) => {
    const card = document.createElement("section"); card.className = "widget"; card.style.gridColumn = `span ${Math.min(3, Math.max(1, w.w || 1))}`;
    if (w.h) card.style.height = w.h + "px";
    if (editing) attachResize(card, view, w);
    const head = document.createElement("div"); head.className = "whead";
    head.innerHTML = `<span class="wtitle">${esc(widgetTitle(w))}</span>` + (editing ? `<span class="wtools">
        <button data-act="left" title="앞으로">◀</button><button data-act="right" title="뒤로">▶</button>
        <button data-act="narrow" title="좁게">−</button><button data-act="wide" title="넓게">+</button>
        ${w.type.startsWith("builtin:") ? "" : '<button data-act="settings" title="설정">⚙</button>'}
        <button data-act="remove" title="지우기">✕</button></span>` : "");
    head.querySelectorAll("[data-act]").forEach(b => b.onclick = () => widgetAction(view, i, b.dataset.act));
    const body = document.createElement("div"); body.className = "wbody";
    card.append(head, body); grid.appendChild(card);
    if (w.type === "builtin:timeline") { body.classList.add("timeline"); renderTimeline(body, { start, end }); return; }
    const p = renderers.find(r => r.id === w.type);
    if (!p) { body.innerHTML = `<div class="empty">설치되지 않은 위젯: ${esc(w.type)}</div>`; return; }
    if (!p.enabled) { body.innerHTML = `<div class="empty">꺼진 플러그인: ${esc(p.id)}</div>`; return; }
    const f = document.createElement("iframe"); f.src = p.entry; f.dataset.plugin = p.id; f.dataset.widget = w.id;
    f.style.height = "100%";
    f.addEventListener("load", () => {
      f.contentWindow.postMessage({ lifelog: "widget", widget: { id: w.id, w: w.w || 1, h: w.h || 1, settings: w.settings || {} } }, "*");
      f.contentWindow.postMessage({ lifelog: "range", range }, "*");
    });
    body.appendChild(f);
  });
  if (editing) {
    const add = document.createElement("div"); add.className = "addbar";
    const opts = [{ id: "builtin:timeline", name: "타임라인 (내장)" }, ...renderers.filter(r => r.slot === "card").map(r => ({ id: r.id, name: `${r.name} (${r.id})` }))];
    add.innerHTML = `<select id="addType">${opts.map(o => `<option value="${esc(o.id)}">${esc(o.name)}</option>`).join("")}</select><button id="addWidget">위젯 추가</button>
      <span class="spacer"></span><button id="renameView">뷰 이름</button><button id="deleteView">뷰 지우기</button>`;
    grid.appendChild(add);
    add.querySelector("#addWidget").onclick = async () => {
      const type = add.querySelector("#addType").value;
      view.widgets.push({ id: uid(), type, w: type === "builtin:timeline" ? 3 : 1, settings: {} });
      await saveSettings(); load();
    };
    add.querySelector("#renameView").onclick = async () => { const n = prompt("뷰 이름", view.name); if (n) { view.name = n; await saveSettings(); load(); } };
    add.querySelector("#deleteView").onclick = async () => {
      if (!confirm(`뷰 "${view.name}"을 지울까요?`)) return;
      settings.views = settings.views.filter(v => v !== view); viewId = settings.views[0]?.id ?? null; await saveSettings(); load();
    };
  }
  main.innerHTML = ""; main.appendChild(grid);
  window.lifelog._broadcastRange(range);
}

/** 편집 모드: 카드 모서리를 끌어 크기를 바꾼다. 놓으면 너비는 열 수(1~3)로, 높이는 px로 저장한다.
 * 다시 그리지 않고 카드 스타일만 바꾼다(다시 그리면 iframe이 전부 다시 읽는다). 카드가 DOM에서 빠지거나 0×0이면 무시한다. */
const resizeObservers = [];
function attachResize(card, view, w) {
  card.classList.add("resizable");
  let timer = null, last = null, first = null;
  const ro = new ResizeObserver(entries => {
    const r = entries[0].contentRect;
    if (!card.isConnected || r.width === 0 || r.height === 0) return;
    if (first === null) { first = { width: r.width, height: r.height }; return; }   // 첫 측정은 레이아웃 결과
    last = { width: r.width, height: r.height };
    clearTimeout(timer); timer = setTimeout(commit, 300);
  });
  async function commit() {
    if (!last || !card.isConnected || !card.parentElement) return;
    const colW = (card.parentElement.clientWidth - 20) / 3;    // gap 10px × 2
    const cols = Math.min(3, Math.max(1, Math.round((last.width + 10) / (colW + 10))));
    const h = Math.max(120, Math.round(last.height));
    if (cols === (w.w || 1) && Math.abs(h - (w.h || 0)) < 4) return;
    w.w = cols; w.h = h;
    card.style.width = "";                         // 끌어서 생긴 px 너비는 버리고 열 수로
    card.style.gridColumn = `span ${cols}`;
    card.style.height = h + "px";
    await saveSettings();
  }
  ro.observe(card); resizeObservers.push(ro);
}

async function widgetAction(view, i, act) {
  const w = view.widgets[i];
  if (act === "remove") view.widgets.splice(i, 1);
  else if (act === "left" && i > 0) { view.widgets.splice(i, 1); view.widgets.splice(i - 1, 0, w); }
  else if (act === "right" && i < view.widgets.length - 1) { view.widgets.splice(i, 1); view.widgets.splice(i + 1, 0, w); }
  else if (act === "narrow") w.w = Math.max(1, (w.w || 1) - 1);
  else if (act === "wide") w.w = Math.min(3, (w.w || 1) + 1);
  else if (act === "settings") { await editWidgetSettings(w); }
  await saveSettings(); load();
}

/** 매니페스트 settings 선언으로 작은 폼을 만든다. */
async function editWidgetSettings(w) {
  const p = renderers.find(r => r.id === w.type);
  const decl = (p && Array.isArray(p.decl)) ? p.decl : [];
  if (decl.length === 0) { alert("이 위젯은 설정이 없습니다."); return; }
  const dlg = $("#dlg"); const form = $("#dlgForm");
  form.innerHTML = decl.map(d => {
    const v = (w.settings || {})[d.key] ?? d.default ?? "";
    let input;
    if (d.type === "boolean") input = `<input type="checkbox" name="${esc(d.key)}" ${v ? "checked" : ""}>`;
    else if (d.type === "enum") input = `<select name="${esc(d.key)}">${(d.options || []).map(o => `<option ${o === v ? "selected" : ""}>${esc(o)}</option>`).join("")}</select>`;
    else if (d.type === "number") input = `<input type="number" name="${esc(d.key)}" value="${esc(v)}">`;
    else if (d.type === "list") input = `<input name="${esc(d.key)}" value="${esc(Array.isArray(v) ? v.join(", ") : v)}" placeholder="쉼표로 구분">`;
    else input = `<input name="${esc(d.key)}" value="${esc(v)}">`;
    return `<label><span>${esc(d.label || d.key)}</span>${input}</label>`;
  }).join("");
  $("#dlgTitle").textContent = `${p.name} 설정`;
  dlg.showModal();
  await new Promise(res => { $("#dlgOk").onclick = () => { dlg.close("ok"); res(); }; $("#dlgCancel").onclick = () => { dlg.close("cancel"); res(); }; });
  if (dlg.returnValue !== "ok") return;
  const out = {};
  for (const d of decl) {
    const el = form.querySelector(`[name="${CSS.escape(d.key)}"]`);
    if (d.type === "boolean") out[d.key] = el.checked;
    else if (d.type === "number") out[d.key] = Number(el.value);
    else if (d.type === "list") out[d.key] = el.value.split(",").map(s => s.trim()).filter(Boolean);
    else out[d.key] = el.value;
  }
  w.settings = out;
}

function renderTabs() {
  const tabs = $("#tabs");
  tabs.innerHTML = settings.views.map(v => `<button class="tab ${v.id === viewId ? "on" : ""}" data-view="${esc(v.id)}">${esc(v.name)}</button>`).join("") +
    (editing ? '<button class="tab add" id="addView">+ 뷰</button>' : "");
  tabs.querySelectorAll("[data-view]").forEach(b => b.onclick = () => { viewId = b.dataset.view; load(); });
  const add = $("#addView");
  if (add) add.onclick = async () => { const n = prompt("새 뷰 이름"); if (!n) return; const v = { id: uid(), name: n, widgets: [] }; settings.views.push(v); viewId = v.id; await saveSettings(); load(); };
}

// ---- 설정 페이지(하루 시작·분류 규칙: 컴퓨터용 app·title, 휴대폰용 package) ----
function renderSettingsPage() {
  const page = $("#settingsPage");
  const pc = settings.categories || (settings.categories = []);
  const ph = settings.phone_categories || (settings.phone_categories = []);
  const table = (rules, kind, cols) => `<table class="rules" data-kind="${kind}"><thead><tr><th>이름</th>${cols.map(c => `<th>${esc(c[1])}</th>`).join("")}<th></th></tr></thead><tbody>
    ${rules.map((r, i) => `<tr><td><input data-i="${i}" data-k="name" value="${esc(r.name)}"></td>${cols.map(c => `<td><input data-i="${i}" data-k="${c[0]}" value="${esc(r[c[0]] || "")}"></td>`).join("")}<td><button data-del="${i}">✕</button></td></tr>`).join("")}
    </tbody></table><div class="row"><button data-add="${kind}">규칙 추가</button></div>`;
  page.innerHTML = `<h2>설정 (이 기기)</h2>
    <label>하루 시작 <input id="dayStart" value="${esc(settings.day_start)}" size="6"></label>
    <h3>컴퓨터 분류 규칙 <span class="mute">위에서부터 처음 맞는 규칙. 정규식, 대소문자 무시. 이름은 "개발/코딩"처럼 /로 계층</span></h3>
    ${table(pc, "pc", [["app", "app (exe 이름)"], ["title", "title (창 제목)"]])}
    <h3>휴대폰 분류 규칙 <span class="mute">package 이름으로 맞춘다(예: com.google.android.youtube)</span></h3>
    ${table(ph, "ph", [["app", "package"]])}
    <div class="row"><button id="saveRules">저장</button><span id="ruleMsg" class="mute"></span></div>`;
  const collect = () => page.querySelectorAll("table.rules").forEach(t => {
    const rules = t.dataset.kind === "pc" ? pc : ph;
    t.querySelectorAll("input[data-i]").forEach(el => { rules[Number(el.dataset.i)][el.dataset.k] = el.value; });
  });
  page.querySelectorAll("[data-del]").forEach(b => b.onclick = () => { collect(); (b.closest("table").dataset.kind === "pc" ? pc : ph).splice(Number(b.dataset.del), 1); renderSettingsPage(); });
  page.querySelectorAll("[data-add]").forEach(b => b.onclick = () => { collect(); (b.dataset.add === "pc" ? pc : ph).push({ name: "", app: "", title: "" }); renderSettingsPage(); });
  $("#saveRules").onclick = async () => {
    collect();
    settings.day_start = $("#dayStart").value.trim() || "04:00";
    settings.categories = pc.filter(r => r.name);
    settings.phone_categories = ph.filter(r => r.name);
    await saveSettings(); $("#ruleMsg").textContent = "저장됨"; load();
  };
}

async function init() {
  try {
    settings = Object.assign(settings, await lifelog.settings());
    if (!Array.isArray(settings.views) || settings.views.length === 0) { settings.views = JSON.parse(JSON.stringify(DEFAULT_VIEWS)); await saveSettings(); }
    if (!Array.isArray(settings.categories)) settings.categories = [];
    if (!Array.isArray(settings.phone_categories)) settings.phone_categories = [];
    if (!viewId) viewId = settings.views[0].id;
    outputs = await lifelog.outputs();
    renderers = await lifelog.renderers();
    $("#info").textContent = `기기 ${await lifelog.device()}`;
    await load();
  } catch (e) {
    $("#main").innerHTML = `<div class="empty">오류: ${esc(e.message)}</div>`;
  }
}

function showPage(name) {
  for (const id of ["main", "pluginsPage", "settingsPage"]) $("#" + id).style.display = id === name ? "block" : "none";
  $("#plugins").textContent = name === "pluginsPage" ? "타임라인" : "플러그인";
  $("#settings").textContent = name === "settingsPage" ? "타임라인" : "설정";
  if (name === "settingsPage") renderSettingsPage();
}
window.showPage = showPage;

$("#prev").onclick = () => { dayOffset--; load(); };
$("#next").onclick = () => { dayOffset++; load(); };
$("#today").onclick = () => { dayOffset = 0; load(); };
$("#reload").onclick = init;
$("#edit").onclick = () => { editing = !editing; $("#edit").classList.toggle("on", editing); load(); };
$("#settings").onclick = () => showPage($("#settingsPage").style.display === "block" ? "main" : "settingsPage");
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
