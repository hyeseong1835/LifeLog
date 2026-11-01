// 셸: 날짜 이동, 뷰 탭, 위젯 보드(칸 좌표 x·y와 크기 w·h. 위치가 고정이라 창 크기가 바뀌어도 위젯이 움직이지 않고 스크롤만 생긴다),
// 편집 모드(추가·삭제·머리띠를 끌어 이동·손잡이로 크기, 칸 단위 스냅), 설정(테마·하루 시작·칸 크기·분류 규칙). 설정·뷰는 기기별(Documents/대시보드 뷰·위젯.md).
const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const params = new URLSearchParams(location.search);
let settings = { day_start: "04:00", tz: "", theme: "system", grid: { cell: 60, gap: 10 }, views: [], categories: [], phone_categories: [] };
let dayOffset = Number(params.get("offset")) || 0;   // 0 = 오늘(지금이 속한 하루). ?offset=-1은 개발용
let outputs = [];
let renderers = [];
let registry = null;
let viewId = null;
let editing = params.get("edit") === "1";            // ?edit=1은 개발용
let range = null;

const DEFAULT_WIDGETS = [
  { type: "builtin:timeline", x: 0, y: 0, w: 20, h: 5 },
  { type: "widget-summary", x: 0, y: 5, w: 5, h: 5 },
  { type: "widget-top-apps", x: 5, y: 5, w: 5, h: 5 },
  { type: "widget-categories", x: 10, y: 5, w: 5, h: 5 },
  { type: "widget-media", x: 15, y: 5, w: 5, h: 5 },
  { type: "widget-hourly", x: 0, y: 10, w: 5, h: 5 },
  { type: "widget-location", x: 5, y: 10, w: 5, h: 5 },
];
const defaultWidgets = () => DEFAULT_WIDGETS.map(w => Object.assign({ id: uid(), settings: {} }, w));
const DEFAULT_VIEWS = [{ id: "default", name: "기본", widgets: DEFAULT_WIDGETS.map((w, i) => Object.assign({ id: "w" + (i + 1), settings: {} }, w)) }];

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

// ---- 테마 ----
function effectiveTheme() {
  const t = params.get("theme") || settings.theme || "system";   // ?theme=light는 개발용
  return t === "system" ? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light") : t;
}
function applyTheme() {
  const t = effectiveTheme();
  document.documentElement.dataset.theme = t;
  for (const f of document.querySelectorAll("iframe")) f.contentWindow.postMessage({ lifelog: "theme", theme: t }, "*");
}
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

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

// ---- 위젯 보드 ----
function gridUnits() {
  const g = settings.grid || {};
  return { cell: Math.max(20, Number(g.cell) || 60), gap: Math.max(0, Number(g.gap ?? 10)) };
}
const px = (n, u) => n * (u.cell + u.gap);                       // 칸 좌표 → px(왼쪽 위)
const size = (n, u) => n * u.cell + Math.max(0, n - 1) * u.gap;   // 칸 수 → px(크기)
function widgetTitle(w) {
  if (w.type === "builtin:timeline") return "타임라인";
  const p = renderers.find(r => r.id === w.type);
  return p ? p.name : w.type;
}
function applyRect(card, w, u) {
  card.style.left = px(w.x || 0, u) + "px"; card.style.top = px(w.y || 0, u) + "px";
  card.style.width = size(Math.max(1, w.w || 1), u) + "px"; card.style.height = size(Math.max(1, w.h || 1), u) + "px";
}
function fitBoard(board, view, u) {
  let mx = 20, my = 6;
  for (const w of view.widgets) { mx = Math.max(mx, (w.x || 0) + (w.w || 1)); my = Math.max(my, (w.y || 0) + (w.h || 1)); }
  board.style.width = px(mx + 1, u) + "px"; board.style.height = px(my + 1, u) + "px";
}
/** 설정 파일에 x·y가 없는(옛 형식) 뷰는 기본 배치로 되돌린다. */
function migrateView(view) {
  if (view.widgets.every(w => Number.isInteger(w.x) && Number.isInteger(w.y))) return false;
  const old = view.widgets;
  view.widgets = defaultWidgets().map(w => { const o = old.find(x => x.type === w.type); if (o && o.settings) w.settings = o.settings; return w; });
  return true;
}

/** 편집 모드: 머리띠를 끌면 이동, 오른쪽 아래 손잡이를 끌면 크기. 칸 단위로 스냅하고 놓으면 저장한다(다시 그리지 않는다). */
function attachEdit(card, head, w, view) {
  const handle = document.createElement("div"); handle.className = "handle"; handle.title = "끌어서 크기 조절";
  card.appendChild(handle);
  const drag = (e, apply) => {
    e.preventDefault();
    const u = gridUnits(), start = { x: e.clientX, y: e.clientY, wx: w.x || 0, wy: w.y || 0, ww: w.w || 1, wh: w.h || 1 };
    const move = ev => { const dx = Math.round((ev.clientX - start.x) / (u.cell + u.gap)), dy = Math.round((ev.clientY - start.y) / (u.cell + u.gap)); apply(start, dx, dy); applyRect(card, w, u); };
    const up = async () => { window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); card.classList.remove("resizing"); fitBoard(card.parentElement, view, u); await saveSettings(); };
    card.classList.add("resizing");
    window.addEventListener("mousemove", move); window.addEventListener("mouseup", up);
  };
  handle.addEventListener("mousedown", e => drag(e, (st, dx, dy) => { w.w = Math.max(1, st.ww + dx); w.h = Math.max(1, st.wh + dy); }));
  head.addEventListener("mousedown", e => { if (e.target.closest("button")) return; drag(e, (st, dx, dy) => { w.x = Math.max(0, st.wx + dx); w.y = Math.max(0, st.wy + dy); }); });
  head.classList.add("movable");
}

async function load() {
  const main = $("#main");
  const { start, end } = dayRange(dayOffset);
  range = { start: start.toISOString(), end: end.toISOString() };
  $("#date").textContent = `${fmtDate(start)} ${fmtTime(start)} ~ ${fmtTime(end)}`;
  renderTabs();
  const view = currentView();
  if (!view) { main.innerHTML = '<div class="empty">뷰가 없습니다. 편집에서 뷰를 추가하세요.</div>'; return; }
  const u = gridUnits();
  const grid = document.createElement("div"); grid.className = "board";
  fitBoard(grid, view, u);
  view.widgets.forEach((w, i) => {
    const card = document.createElement("section"); card.className = "widget"; applyRect(card, w, u);
    const head = document.createElement("div"); head.className = "whead";
    head.innerHTML = `<span class="wtitle">${esc(widgetTitle(w))}</span>` + (editing ? `<span class="wtools">
        ${w.type.startsWith("builtin:") ? "" : '<button data-act="settings" title="설정">⚙</button>'}
        <button data-act="remove" title="지우기">✕</button></span>` : "");
    head.querySelectorAll("[data-act]").forEach(b => b.onclick = () => widgetAction(view, i, b.dataset.act));
    const body = document.createElement("div"); body.className = "wbody";
    card.append(head, body); grid.appendChild(card);
    if (editing) attachEdit(card, head, w, view);
    if (w.type === "builtin:timeline") { body.classList.add("timeline"); renderTimeline(body, { start, end }); return; }
    const p = renderers.find(r => r.id === w.type);
    if (!p) { body.innerHTML = `<div class="empty">설치되지 않은 위젯: ${esc(w.type)}</div>`; return; }
    if (!p.enabled) { body.innerHTML = `<div class="empty">꺼진 플러그인: ${esc(p.id)}</div>`; return; }
    const f = document.createElement("iframe"); f.src = p.entry; f.dataset.plugin = p.id; f.dataset.widget = w.id;
    f.addEventListener("load", () => {
      f.contentWindow.postMessage({ lifelog: "theme", theme: effectiveTheme() }, "*");
      f.contentWindow.postMessage({ lifelog: "widget", widget: { id: w.id, w: w.w || 1, h: w.h || 1, settings: w.settings || {} } }, "*");
      f.contentWindow.postMessage({ lifelog: "range", range }, "*");
    });
    body.appendChild(f);
  });
  main.innerHTML = ""; main.appendChild(grid);
  if (editing) {
    const add = document.createElement("div"); add.className = "addbar";
    const opts = [{ id: "builtin:timeline", name: "타임라인 (내장)" }, ...renderers.filter(r => r.slot === "card").map(r => ({ id: r.id, name: `${r.name} (${r.id})` }))];
    add.innerHTML = `<select id="addType">${opts.map(o => `<option value="${esc(o.id)}">${esc(o.name)}</option>`).join("")}</select><button id="addWidget">위젯 추가</button>
      <span class="spacer"></span><button id="resetView" title="위젯 목록과 크기를 기본값으로">뷰 초기화</button><button id="renameView">뷰 이름</button><button id="deleteView">뷰 지우기</button>`;
    main.appendChild(add);
    add.querySelector("#addWidget").onclick = async () => {
      const type = add.querySelector("#addType").value;
      const bottom = view.widgets.reduce((m, x) => Math.max(m, (x.y || 0) + (x.h || 1)), 0);   // 맨 아래 빈 줄에
      view.widgets.push({ id: uid(), type, x: 0, y: bottom, w: type === "builtin:timeline" ? 20 : 5, h: 5, settings: {} });
      await saveSettings(); load();
    };
    add.querySelector("#resetView").onclick = async () => {
      if (!confirm(`뷰 "${view.name}"의 위젯을 기본 구성으로 되돌릴까요?`)) return;
      view.widgets = defaultWidgets();
      await saveSettings(); load();
    };
    add.querySelector("#renameView").onclick = async () => { const n = prompt("뷰 이름", view.name); if (n) { view.name = n; await saveSettings(); load(); } };
    add.querySelector("#deleteView").onclick = async () => {
      if (!confirm(`뷰 "${view.name}"을 지울까요?`)) return;
      settings.views = settings.views.filter(v => v !== view); viewId = settings.views[0]?.id ?? null; await saveSettings(); load();
    };
  }
  window.lifelog._broadcastRange(range);
}

async function widgetAction(view, i, act) {
  const w = view.widgets[i];
  if (act === "remove") view.widgets.splice(i, 1);
  else if (act === "left" && i > 0) { view.widgets.splice(i, 1); view.widgets.splice(i - 1, 0, w); }
  else if (act === "right" && i < view.widgets.length - 1) { view.widgets.splice(i, 1); view.widgets.splice(i + 1, 0, w); }
  else if (act === "settings") { if (!await editWidgetSettings(w)) return; }
  await saveSettings(); load();
}

/** 매니페스트 settings 선언으로 작은 폼을 만든다. key가 devices인 list는 등록부의 기기 목록에서 고른다. 저장했으면 true. */
async function editWidgetSettings(w) {
  const p = renderers.find(r => r.id === w.type);
  const decl = (p && Array.isArray(p.decl)) ? p.decl : [];
  if (decl.length === 0) { alert("이 위젯은 설정이 없습니다."); return false; }
  if (!registry) { try { registry = await lifelog.registry(); } catch (e) { registry = { devices: [] }; } }
  const dlg = $("#dlg"); const form = $("#dlgForm");
  form.innerHTML = decl.map(d => {
    const v = (w.settings || {})[d.key] ?? d.default ?? "";
    let input;
    if (d.type === "boolean") input = `<input type="checkbox" name="${esc(d.key)}" ${v ? "checked" : ""}>`;
    else if (d.type === "enum") input = `<select name="${esc(d.key)}">${(d.options || []).map(o => `<option ${o === v ? "selected" : ""}>${esc(o)}</option>`).join("")}</select>`;
    else if (d.type === "number") input = `<input type="number" name="${esc(d.key)}" value="${esc(v)}">`;
    else if (d.type === "list" && d.key === "devices") {
      const sel = new Set(Array.isArray(v) ? v : []);
      const devs = (registry.devices || []).filter(x => !x.revoked);
      input = `<span class="checks" data-list="${esc(d.key)}">${devs.map(x => `<label class="chk"><input type="checkbox" value="${esc(x.id)}" ${sel.has(x.id) ? "checked" : ""}> ${esc(x.name)} <span class="mute">${esc(x.id)}</span></label>`).join("")}<span class="mute">(아무것도 안 고르면 전부)</span></span>`;
    }
    else if (d.type === "list") input = `<input name="${esc(d.key)}" value="${esc(Array.isArray(v) ? v.join(", ") : v)}" placeholder="쉼표로 구분">`;
    else input = `<input name="${esc(d.key)}" value="${esc(v)}">`;
    return `<label><span>${esc(d.label || d.key)}</span>${input}</label>`;
  }).join("");
  $("#dlgTitle").textContent = `${p.name} 설정`;
  dlg.showModal();
  await new Promise(res => { $("#dlgOk").onclick = () => { dlg.close("ok"); res(); }; $("#dlgCancel").onclick = () => { dlg.close("cancel"); res(); }; });
  if (dlg.returnValue !== "ok") return false;
  const out = {};
  for (const d of decl) {
    const el = form.querySelector(`[name="${CSS.escape(d.key)}"]`);
    if (d.type === "list" && d.key === "devices") out[d.key] = [...form.querySelectorAll(`[data-list="${CSS.escape(d.key)}"] input:checked`)].map(x => x.value);
    else if (d.type === "boolean") out[d.key] = el.checked;
    else if (d.type === "number") out[d.key] = Number(el.value);
    else if (d.type === "list") out[d.key] = el.value.split(",").map(s => s.trim()).filter(Boolean);
    else out[d.key] = el.value;
  }
  w.settings = out;
  return true;
}

function renderTabs() {
  const tabs = $("#tabs");
  tabs.innerHTML = settings.views.map(v => `<button class="tab ${v.id === viewId ? "on" : ""}" data-view="${esc(v.id)}">${esc(v.name)}</button>`).join("") +
    (editing ? '<button class="tab add" id="addView">+ 뷰</button>' : "");
  tabs.querySelectorAll("[data-view]").forEach(b => b.onclick = () => { viewId = b.dataset.view; load(); });
  const add = $("#addView");
  if (add) add.onclick = async () => { const n = prompt("새 뷰 이름"); if (!n) return; const v = { id: uid(), name: n, widgets: [] }; settings.views.push(v); viewId = v.id; await saveSettings(); load(); };
}

// ---- 설정 페이지(테마·하루 시작·그리드·분류 규칙) ----
function renderSettingsPage() {
  const page = $("#settingsPage");
  const pc = settings.categories || (settings.categories = []);
  const ph = settings.phone_categories || (settings.phone_categories = []);
  const u = gridUnits();
  const table = (rules, kind, cols) => `<table class="rules" data-kind="${kind}"><thead><tr><th>이름</th>${cols.map(c => `<th>${esc(c[1])}</th>`).join("")}<th></th></tr></thead><tbody>
    ${rules.map((r, i) => `<tr><td><input data-i="${i}" data-k="name" value="${esc(r.name)}"></td>${cols.map(c => `<td><input data-i="${i}" data-k="${c[0]}" value="${esc(r[c[0]] || "")}"></td>`).join("")}<td><button data-del="${i}">✕</button></td></tr>`).join("")}
    </tbody></table><div class="row"><button data-add="${kind}">규칙 추가</button></div>`;
  page.innerHTML = `<h2>설정 (이 기기)</h2>
    <div class="row">
      <label>테마 <select id="theme"><option value="system" ${settings.theme === "system" || !settings.theme ? "selected" : ""}>시스템 따름</option><option value="dark" ${settings.theme === "dark" ? "selected" : ""}>다크</option><option value="light" ${settings.theme === "light" ? "selected" : ""}>라이트</option></select></label>
      <label>하루 시작 <input id="dayStart" value="${esc(settings.day_start)}" size="6"></label>
      <label>한 칸 크기(px) <input id="gridCell" type="number" value="${u.cell}" size="5"></label>
      <label>간격(px) <input id="gridGap" type="number" value="${u.gap}" size="4"></label>
    </div>
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
  $("#theme").onchange = () => { settings.theme = $("#theme").value; applyTheme(); };
  $("#saveRules").onclick = async () => {
    collect();
    settings.theme = $("#theme").value;
    settings.day_start = $("#dayStart").value.trim() || "04:00";
    settings.grid = { cell: Number($("#gridCell").value) || 60, gap: Number($("#gridGap").value) || 0 };
    settings.categories = pc.filter(r => r.name);
    settings.phone_categories = ph.filter(r => r.name);
    await saveSettings(); applyTheme(); $("#ruleMsg").textContent = "저장됨"; load();
  };
}

async function init() {
  try {
    settings = Object.assign(settings, await lifelog.settings());
    if (!Array.isArray(settings.views) || settings.views.length === 0) { settings.views = JSON.parse(JSON.stringify(DEFAULT_VIEWS)); await saveSettings(); }
    if (!Array.isArray(settings.categories)) settings.categories = [];
    if (!Array.isArray(settings.phone_categories)) settings.phone_categories = [];
    if (settings.views.map(migrateView).some(Boolean)) await saveSettings();
    if (!settings.grid || !settings.grid.cell) { settings.grid = { cell: 60, gap: 10 }; await saveSettings(); }
    applyTheme();
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
