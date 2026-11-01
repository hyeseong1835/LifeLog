# dashboard-windows

Windows 대시보드 = WPF 창 하나에 WebView2. 화면은 전부 HTML/JS(`web/`)이고, 호스트는 `lifelog` 브리지(`Bridge.cs`)로 DB 질의·등록부·설정만 준다. 설계는 `Documents/새 구조 설계.md` 2.5 "렌더러"(로컬 전용).

```
src/LifeLog.Dashboard.Windows/
  MainWindow.xaml(.cs)   WebView2 준비, 가상 호스트(app.lifelog → web/, plugin-<id>.lifelog → plugins/<id>), 브리지 주입
  Bridge.cs              query(집계 옵션 포함, 플러그인은 inputs만), outputs(등록부), renderers, settings, installPlugin
  web/bridge.js          window.lifelog. 셸은 chrome.webview로, 플러그인 iframe은 부모(셸) 중계로 호스트와 통신
  web/index.html, shell.js  셸: 날짜 이동(day_start 설정), 내장 타임라인(모든 수집기 출력을 공통 열 + 라벨 열로), 렌더러 iframe
```
코어(`LifeLog.Core`)는 수집기와 같은 프로젝트를 참조한다. `Query.cs`가 `lifelog.query`의 SQL 변환(화이트리스트)이다.

## 설정
- `%LOCALAPPDATA%\LifeLog\dashboard\config.json`. 없으면 수집기의 `LifeLog\collector\config.json`을 그대로 읽는다(같은 기기 = 같은 롤, DPAPI는 같은 사용자).
- `settings.json`: `day_start`(기본 `04:00`), `tz`. 기기별.
- 렌더러 플러그인: `%LOCALAPPDATA%\LifeLog\dashboard\plugins\<id>\`. "렌더러 설치" 버튼이 폴더를 골라 복사·등록·전부 허용한다.

## 개발
```bash
dotnet build dashboard-windows
dashboard-windows/src/LifeLog.Dashboard.Windows/bin/Debug/net10.0-windows10.0.19041.0/lifelog-dashboard.exe
# 화면 캡처(개발용): 로드 4초 뒤 PNG로 저장하고 종료
lifelog-dashboard.exe --screenshot out.png
```
로그: `%LOCALAPPDATA%\LifeLog\dashboard\dashboard.log`.

## 렌더러 플러그인 작성
`manifest.json`(`type: renderer`, `runtimes.webview`, `inputs`, `slot`)과 `index.html`. 페이지 안에서 `lifelog.query("win-window/window", { range, limit })`, `lifelog.onRangeChange(fn)`, `lifelog.settings()`. `inputs`에 없는 출력을 질의하면 거부된다. `slot`은 `lane`(타임라인 한 줄, 높이 120px) 또는 `page`(480px).

## 아직 없는 것
- 날짜 외 범위(주·월)와 `query` 집계를 쓰는 내장 화면. 렌더러의 `navigate`.
- 플러그인 권한 항목별 허용 화면, GitHub URL 설치, 렌더러 켜고 끄기 UI.
- 테마 전환(지금은 다크 고정). 겹치는 구간(하루 합계와 시간별)의 표시 구분.
