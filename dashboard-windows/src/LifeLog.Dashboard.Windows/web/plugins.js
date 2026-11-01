// 플러그인 페이지: 등록부(모든 기기)의 수집기·렌더러 목록, 이 기기에서 켜고 끄기, GitHub URL·폴더·zip으로 설치.
// 설치는 이 기기에만 적용된다. 수집기 플러그인은 수집기의 plugins/에 넣고 실행 중인 수집기에 다시 읽기를 요청한다.
(function () {
  const $ = s => document.querySelector(s);
  const page = $("#pluginsPage"), main = $("#main"), btn = $("#plugins");
  let open = false, device = "";
  const fmt = t => t ? new Date(t).toLocaleString("ko-KR", { hour12: false }) : "—";
  const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  async function render() {
    page.innerHTML = '<div class="empty">불러오는 중…</div>';
    try {
      device = await lifelog.device();
      const reg = await lifelog.registry();
      const devices = reg.devices.filter(d => !d.revoked);
      let html = `<div class="row"><input id="src" placeholder="GitHub URL (https://github.com/소유자/저장소[/tree/브랜치/폴더]) · 폴더 경로 · zip 경로" size="72">` +
        `<button id="installSrc">설치</button><button id="installDir">폴더 고르기…</button><span id="installMsg" class="mute"></span></div>` +
        `<p class="mute">설치·켜기는 이 기기(${esc(device)})에만 적용됩니다. 다른 기기는 그 기기의 앱에서 같은 출처로 설치합니다. 지금은 권한을 전부 허용하고 켭니다.</p>` +
        `<table class="plugins"><thead><tr><th>플러그인</th><th>종류</th><th>최신</th>` +
        devices.map(d => `<th>${esc(d.name)}<br><span class="mute">${esc(d.id)}</span></th>`).join("") + `<th>출처</th></tr></thead><tbody>`;
      for (const p of reg.plugins) {
        html += `<tr><td><b>${esc(p.name || p.id)}</b><br><span class="mute">${esc(p.id)}</span>` +
          (p.tables.length ? `<br><span class="mute">${p.tables.map(esc).join(", ")}</span>` : "") + `</td><td>${esc(p.type)}</td><td>${esc(p.latest_version)}</td>`;
        for (const d of devices) {
          const i = p.installs.find(x => x.device === d.id);
          if (!i) { html += `<td class="mute">—</td>`; continue; }
          html += `<td><span class="chip ${i.enabled ? "on" : "off"}">${i.enabled ? "켜짐" : "꺼짐"} ${esc(i.version)}</span>` +
            (i.unmet && i.unmet.length ? `<br><span class="warn">미허용: ${i.unmet.map(esc).join(", ")}</span>` : "") +
            `<br><span class="mute">${fmt(i.last_seen)}</span>` +
            (d.id === device ? `<br><button data-toggle="${esc(p.id)}" data-on="${!i.enabled}">${i.enabled ? "끄기" : "켜기"}</button>` : "") + `</td>`;
        }
        html += `<td>${p.source_url ? `<span title="${esc(p.source_url)}">${esc(p.source_url.replace(/^https?:\/\//, ""))}</span>` : '<span class="mute">로컬</span>'}</td></tr>`;
      }
      html += `</tbody></table>`;
      if (reg.plugins.length === 0) html += '<div class="empty">등록된 플러그인이 없습니다.</div>';
      page.innerHTML = html;
      $("#installSrc").onclick = () => install($("#src").value.trim());
      $("#installDir").onclick = () => install(null);
      page.querySelectorAll("[data-toggle]").forEach(b => b.onclick = async () => {
        b.disabled = true;
        try { await lifelog.setEnabled(b.dataset.toggle, b.dataset.on === "true"); await render(); }
        catch (e) { alert(e.message); b.disabled = false; }
      });
    } catch (e) { page.innerHTML = `<div class="empty">오류: ${esc(e.message)}</div>`; }
  }

  async function install(source) {
    if (source === "") return;
    const msg = $("#installMsg"); msg.textContent = "설치 중…";
    try {
      const r = await lifelog.installPlugin(source);
      if (!r.installed) { msg.textContent = "취소"; return; }
      await render();
      $("#installMsg").textContent = `${r.id} (${r.type}) 설치됨` + (r.reloaded ? " · 수집기가 다시 읽음" : r.type === "collector" ? " · 수집기를 다시 시작하면 반영" : "");
      if (r.type === "renderer" && typeof init === "function") init();
    } catch (e) { msg.textContent = "실패: " + e.message; }
  }

  btn.onclick = () => {
    open = page.style.display !== "block";
    window.showPage(open ? "pluginsPage" : "main");
    if (open) render();
  };
})();
