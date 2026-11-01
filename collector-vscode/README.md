# collector-vscode

VS Code 확장. 편집기 전환·창 포커스·1분 하트비트를 `%LOCALAPPDATA%\LifeLog\vscode\events.jsonl`에 덧붙인다. 파일 내용은 보지 않는다.
읽는 쪽은 수집기 플러그인 `plugins/vscode`(원시 기능 `ctx.file`)이고, 거기서 "포커스된 창에서 한 파일을 보던 구간"을 만든다.

```powershell
powershell -ExecutionPolicy Bypass -File collector-vscode/install.ps1   # ~/.vscode/extensions에 복사. VS Code 재시작
lifelog-collector.exe install plugins/vscode --grant-all                # 수집기 플러그인
```

이벤트: `{ time, kind: start|editor|focus|blur|heartbeat|stop, focused, project, file, language }`. `project`는 워크스페이스 폴더 이름, `file`은 그 안의 상대 경로.
