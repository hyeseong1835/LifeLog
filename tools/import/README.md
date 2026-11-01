# tools/import — 기존 데이터 이관

근거: `Documents/테이블 설계.md` 6절. 일회성 도구이며 `lifelog_admin`으로 쓴다(RLS 우회, `device` 직접 지정).

```
manifests/*.json   첫 수집기 매니페스트. 열·identity·테이블 이름의 근거. 실제 수집기 플러그인 저장소와 같아야 한다
common.py          매니페스트 로드, 결정적 id(uuid5), 겹침 정책(merge / bump)
aw.py              ActivityWatch SQLite → win-window/window@1, win-afk/afk@1, win-media/media@1
phone.py           폰 TSV → phone-app/app@1, phone-location/point@1, phone-media/media@1, health/point@1, health/span@1
dry_run.py         원본을 읽어 건수·기간·표본·건너뜀을 출력. DB에 쓰지 않는다
write.py           관리자 접속으로 기기·매니페스트를 등록하고 COPY → ON CONFLICT upsert로 넣는다. 재실행 안전
```

## 건조 실행
```bash
python -I tools/import/dry_run.py
python -I tools/import/dry_run.py --aw PATH --phone DIR --samples 5
```

## id 규칙
호스트의 `span.put`과 같다. `uuid5(NS, plugin | device | output | start(UTC ISO) | identity 값 JSON)`. `NS`는 `common.py`의 고정값이고 호스트 구현도 같은 값을 써야 한다(→ `spec/`에 옮길 것).

## 겹침 정책
같은 `(start, identity)`가 둘 이상일 때:
- **merge** (AW): 같은 기록이다. AW 하트비트가 구간을 늘리며 남긴 흔적이라 끝이 가장 늦은 행만 남긴다. 창 7,265행·자리 비움 12,062행이 여기에 해당한다.
- **bump** (TSV): 다른 기록이다. 분 해상도 때문에 겹친 것이므로 파일 순서대로 `start`에 1초씩 더한다.

## 분류·변환 메모
- AW는 버킷 id가 재사용돼 있어 `datastr` 키 모양으로 분류한다. VS Code 이벤트(63,229)는 그 수집기를 만들 때 이관한다.
- 폰 TSV 건강 줄의 하루 합계는 `category = "daily"`, 시간별 걸음은 `"hourly"`. 같은 metric이 00:00에 겹치지 않게 하기 위해서다.
- 수면 세션(`sl`)이 자정을 넘기면 시작은 전날. 그 세션의 단계(`ss`)는 세션 시작 시각 이후 HH:mm이면 전날로 본다.
- `phone-app.package`에는 TSV의 표시 이름을 넣는다(원본에 패키지가 없다).
- 쓰기: `python tools/import/write.py --dsn <관리자 DSN>`. 기기가 없으면 등록하고 비밀번호를 한 번 출력한다. 폰 앱은 나중에 `reset_device_password('phone')`로 비밀번호를 받는다.
