# collector-windows

Windows 수집기 앱 = 호스트 + 네이티브 소스. 수집기 플러그인은 전부 JS(`plugins/`)다. 설계는 `Documents/수집기 호스트 API.md`(로컬 전용).

```
src/LifeLog.Core/                 공통(대시보드도 쓴다)
  Ids.cs        결정적 id. tools/import/common.py와 바이트 단위로 같다(Python isoformat·json.dumps 재현)
  Manifest.cs   매니페스트 모델. 원문 JSON을 그대로 등록 함수에 보낸다
  Config.cs     %LOCALAPPDATA%\LifeLog\collector\config.json. 기기 롤 비밀번호는 DPAPI
  Db.cs         등록 함수 호출, 스키마 버전 확인, 시계 보정, (device, id) upsert 배치
  Queue.cs      로컬 큐(SQLite). 같은 키의 upsert는 최신 하나만 남긴다
  Uploader.cs   큐 → DB. 실패하면 5초~2분 물러났다 재시도
  SpanManager.cs 구간 관리: open/update/close/put/delete/emit, 1분마다 열린 구간 upsert
src/LifeLog.Collector.Windows/    exe (net10.0-windows)
  Program.cs    setup / install / enable / disable / run / status
  Host.cs       플러그인 등록·로드, 소스 켜기, 주기 작업, 종료
  JsPlugin.cs   Jint 엔진 하나 = 플러그인 하나. ctx(on, every, span, event, state, settings, privacy, has, now, log)
  Dispatcher.cs 모든 JS는 이 스레드에서만
  Sources/      window(SetWinEventHook), idle(GetLastInputInfo), power(SystemEvents), media(GSMTC)
```

## 쓰는 법
```bash
dotnet build collector-windows
EXE=collector-windows/src/LifeLog.Collector.Windows/bin/Debug/net10.0-windows10.0.19041.0/lifelog-collector.exe
LIFELOG_ADMIN_DSN="Host=...;Port=...;Database=...;Username=...;Password=..." $EXE setup --device laptop --name 노트북 --host H --port P --database DB
$EXE install plugins/win-window --grant-all      # 개발용: 권한 전부 허용하고 켬
$EXE run
```
- `setup`은 관리자 접속으로 `register_device`를 부르고 받은 비밀번호만 저장한다. 관리자 접속 문자열은 저장하지 않는다.
- `install`은 폴더나 zip을 `plugins/<id>`로 복사하고 `register_plugin`을 부른다. `--grant-all`이 없으면 꺼진 채 설치되고 `enable <id>`로 켠다(지금은 권한 전부 허용. 항목별 허용 UI는 대시보드 몫).
- `run`은 DB 코어 스키마 버전이 앱보다 높으면 쓰기를 거부한다.

## 플러그인 작성
`plugins/win-window/main.js`가 가장 작은 예다. `export default { start(ctx), stop(ctx) }`. 행의 시각은 `Date`(소스 이벤트의 `ev.time`을 그대로 넘기면 된다). 선언되지 않은 열을 넣으면 거부된다. 핸들러 예외는 격리되고 연속 5회면 그 기기에서 꺼진다.

## 아직 없는 것
- 트레이 아이콘·로그온 자동 실행(지금은 콘솔). 설치 화면의 권한 항목별 허용.
- GitHub URL 설치와 갱신 확인. Lua 런타임. JS 수집기용 원시 기능(`http`, `file`, `sqlite`, `process`, `registry`).
- 소스 이벤트 버퍼(플러그인이 꺼졌다 켜질 때 재전달)와 자원 상한의 메모리 쪽(시간 상한은 있음).
