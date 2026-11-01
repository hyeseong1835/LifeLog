// 셸: 날짜 이동, 뷰 탭, 위젯 격자(고정 픽셀 단위 그리드. 창 크기가 바뀌어도 위젯 크기는 그대로, 줄바꿈만 달라짐),
// 편집 모드(추가·삭제·순서·그리드 단위로 끌어서 크기), 설정(테마·하루 시작·그리드 단위·분류 규칙). 설정·뷰는 기기별(Documents/대시보드 뷰·위젯.md).
const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const params = new URLSearchParams(location.search);
let settings = { day_start: "04:00", tz: "", theme: "system", grid: { col: 280, row: 120, gap: 10 }, views: [], categories: [], phone_categories: [] };
let dayOffset = Number(params.get("offset")) || 0;   // 0 = 오늘(지금이 속한 하루). ?offset=-1은 개발용
let outputs = [];
let renderers = [];
let registry = null;
let viewId = null;
let editing = params.get("edit") === "1";            // ?edit=1은 개발용
let range = null;

const DEFAULT_VIEWS = [
  { id: "default", name: "기본", widgets: [
    { id: "w1", type: "builtin:timeline", w: 4, h: 3 },
    { id: "w2", type: "widget-summary", w: 1, h: 2, settings: {} },
    { id: "w3", type: "widget-top-apps", w: 1, h: 2, settings: {} },
    { id: "w4", type: "widget-categories", w: 1, h: 2, settings: {} },
    { id: "w5", type: "widget-hourly", w: 2, h: 2, settings: {} },
    { id: "w6", type: "widget-media", w: 1, h: 2, settings: {} },
    { id: "w7", type: "widget-location", w: 1, h: 2, settings: {} },
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

// ---- 위젯 격자 ----
function gridUnits() {
  const g = settings.grid || {};
  return { col: Math.max(120, Number(g.col) || 280), row: Math.max(60, Number(g.row) || 120), gap: Math.max(0, Number(g.gap ?? 10)) };
}
function widgetTitle(w) {
  if (w.type === "builtin:timeline") return "타임라인";
  const p = renderers.find(r => r.id === w.type);
  return p ? p.name : w.type;
}
function applySize(card, w) {
  card.style.gridColumn = `span ${Math.max(1, w.w || 1)}`;
  card.style.gridRow = `span ${Math.max(1, w.h || 1)}`;
}

/** 편집 모드: 오른쪽 아래 손잡이를 끌면 그리드 단위로 너비·높이가 바뀐다. 놓으면 저장한다(다시 그리지 않는다). */
function attachResize(card, w) {
  const handle = document.createElement("div"); handle.className = "handle"; handle.title = "끌어서 크기 조절";
  card.appendChild(handle);
  handle.addEventListener("mousedown", e => {
    e.preventDefault();
    const u = gridUnits();
    const start = { x: e.clientX, y: e.clientY, w: w.w || 1, h: w.h || 1 };
    const move = ev => {
      const nw = Math.max(1, Math.round(start.w + (ev.clientX - start.x) / (u.col + u.gap)));
      const nh = Math.max(1, Math.round(start.h + (ev.clientY - start.y) / (u.row + u.gap)));
      if (nw !== w.w || nh !== w.h) { w.w = nw; w.h = nh; applySize(card, w); }
    };
    const up = async () => { window.removeEventListener("mousemove", move); window.removeEventListener("mouseup", up); card.classList.remove("resizing"); await saveSettings(); };
    card.classList.add("resizing");
    window.addEventListener("mousemove", move); window.addEventListener("mouseup", up);
  });
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
  const grid = document.createElement("div"); grid.className = "grid";
  grid.style.gridTemplateColumns = `repeat(auto-fill, ${u.col}px)`;
  grid.style.gridAutoRows = `${u.row}px`;
  grid.style.gap = `${u.gap}px`;
  view.widgets.forEach((w, i) => {
    const card = document.createElement("section"); card.className = "widget"; applySize(card, w);
    const head = document.createElement("div"); head.className = "whead";
    head.innerHTML = `<span class="wtitle">${esc(widgetTitle(w))}</span>` + (editing ? `<span class="wtools">
        <button data-act="left" title="앞으로">◀</button><button data-act="right" title="뒤로">▶</button>
        ${w.type.startsWith("builtin:") ? "" : '<button data-act="settings" title="설정">⚙</button>'}
        <button data-act="remove" title="지우기">✕</button></span>` : "");
    head.querySelectorAll("[data-act]").forEach(b => b.onclick = () => widgetAction(view, i, b.dataset.act));
    const body = document.createElement("div"); body.className = "wbody";
    card.append(head, body); grid.appendChild(card);
    if (editing) attachResize(card, w);
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
      view.widgets.push({ id: uid(), type, w: type === "builtin:timeline" ? 4 : 1, h: type === "builtin:timeline" ? 3 : 2, settings: {} });
      await saveSettings(); load();
    };
    add.querySelector("#resetView").onclick = async () => {
      if (!confirm(`뷰 "${view.name}"의 위젯을 기본 구성으로 되돌릴까요?`)) return;
      view.widgets = JSON.parse(JSON.stringify(DEFAULT_VIEWS[0].widgets)).map(w => Object.assign(w, { id: uid() }));
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
      <label>그리드 한 칸 너비(px) <input id="gridCol" type="number" value="${u.col}" size="5"></label>
      <label>높이(px) <input id="gridRow" type="number" value="${u.row}" size="5"></label>
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
    settings.grid = { col: Number($("#gridCol").value) || 280, row: Number($("#gridRow").value) || 120, gap: Number($("#gridGap").value) || 0 };
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
