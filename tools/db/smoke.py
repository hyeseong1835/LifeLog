"""등록 함수 검증 (로컬 DB용). 성공·거부 경로를 한 번씩 밟는다.

  python tools/db/smoke.py --dsn postgresql://postgres:pw@localhost:54329/lifelog

관리자 접속으로 기기를 등록해 비밀번호를 받고, 그 기기 롤로 다시 접속해 플러그인을 등록·설정한다.
"""
import argparse
import json
import os
import uuid
from urllib.parse import urlsplit

import psycopg

WIN_WINDOW = {
    "type": "collector", "id": "win-window", "version": "1.0.0", "name": "포커스 창",
    "min_host_version": 1, "runtimes": {"js": "main.js"},
    "capabilities": [{"id": "event:window", "required": True}],
    "outputs": [{"name": "window", "kind": "span", "schema_version": 1,
                 "columns": {"app": "text", "title": "text", "pid": "integer"},
                 "label": "app", "identity": ["app"], "index": ["app"]}],
    "privacy": [{"key": "mask_title_apps", "type": "list", "default": [], "label": "제목을 가릴 앱"}],
}
WIN_WINDOW_V2 = json.loads(json.dumps(WIN_WINDOW))
WIN_WINDOW_V2["version"] = "1.1.0"
WIN_WINDOW_V2["outputs"][0]["schema_version"] = 2
WIN_WINDOW_V2["outputs"][0]["columns"]["hwnd"] = "bigint"

RENDERER = {
    "type": "renderer", "id": "app-timeline", "version": "1.0.0", "name": "앱 타임라인",
    "min_host_version": 1, "runtimes": {"webview": "index.html"},
    "inputs": ["win-window/window@1", "phone-app/app@1"], "slot": "lane",
    "depends": {"win-window": "^1.0"},
    "settings": [{"key": "merge_gap", "type": "duration", "default": "10s", "label": "합칠 간격"}],
}


def expect_error(cur, sql, params, needle):
    try:
        cur.execute(sql, params)
    except psycopg.Error as e:
        msg = str(e)
        assert needle in msg, f"기대한 거부 메시지가 아님: {msg}"
        print(f"  거부 확인: {needle}")
        return
    raise AssertionError(f"거부돼야 하는데 통과했습니다: {needle}")


def with_role(dsn, role, pw):
    # 비밀번호에 base64 문자(/ +)가 들어가므로 URL이 아니라 키워드 형식으로 만든다
    u = urlsplit(dsn)
    return psycopg.conninfo.make_conninfo(host=u.hostname, port=u.port, dbname=u.path.lstrip("/"), user=role, password=pw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=os.environ.get("LIFELOG_ADMIN_DSN"))
    a = ap.parse_args()

    admin = psycopg.connect(a.dsn, autocommit=True)
    cur = admin.cursor()

    print("1. 기기 등록")
    cur.execute("SELECT lifelog.revoke_device('laptop')")
    cur.execute("SELECT lifelog.register_device('laptop', '노트북', 'Windows 11')")
    pw = cur.fetchone()[0]
    assert len(pw) > 20
    expect_error(cur, "SELECT lifelog.register_device('laptop', 'x', 'x')", None, "이미 등록된 기기")
    expect_error(cur, "SELECT lifelog.register_device('Bad Id', 'x', 'x')", None, "기기 id는")

    dev = psycopg.connect(with_role(a.dsn, "lifelog_device_laptop", pw), autocommit=True)
    dcur = dev.cursor()
    dcur.execute("SELECT lifelog.current_device()")
    assert dcur.fetchone()[0] == "laptop"

    print("2. 수집기 등록 (기기 롤)")
    # 깨끗한 상태로
    for t in ("win-window/window@1", "win-window/window@2"):
        cur.execute(f'DROP TABLE IF EXISTS lifelog."{t}" CASCADE')
    cur.execute("DELETE FROM lifelog.collector_tables; DELETE FROM lifelog.plugin_installs; DELETE FROM lifelog.plugin_versions; DELETE FROM lifelog.plugins")
    dcur.execute("SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(WIN_WINDOW),))
    r = dcur.fetchone()[0]
    assert r["created_tables"] == ["win-window/window@1"], r
    dcur.execute("SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(WIN_WINDOW),))
    assert dcur.fetchone()[0]["created_tables"] == [], "멱등이어야 함"
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'phone')", (json.dumps(WIN_WINDOW),), "다른 기기")
    bad = json.loads(json.dumps(WIN_WINDOW)); bad["version"] = "1.0.1"; bad["outputs"][0]["columns"]["title"] = "jsonb"
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(bad),), "schema_version을 올리세요")
    bad = json.loads(json.dumps(WIN_WINDOW)); bad["version"] = "1.0.2"; bad["foo"] = 1
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(bad),), "모르는 항목")
    same = json.loads(json.dumps(WIN_WINDOW)); same["name"] = "다른 이름"
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(same),), "다른 내용으로 이미")
    bad = json.loads(json.dumps(WIN_WINDOW)); bad["version"] = "1.0.3"; bad["outputs"][0]["columns"]["select"] = "text"
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(bad),), "예약어")
    bad = json.loads(json.dumps(WIN_WINDOW)); bad["version"] = "1.0.4"; bad["runtimes"] = {"webview": "index.html"}
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(bad),), "js 또는 lua")

    print("3. 행 쓰기와 RLS")
    rid = uuid.uuid4()
    dcur.execute('INSERT INTO lifelog."win-window/window@1" (device, id, start, "end", app, title, pid) VALUES (%s, %s, now(), now(), %s, %s, %s)',
                 ("laptop", rid, "Code.exe", "hello", 1))
    dcur.execute('UPDATE lifelog."win-window/window@1" SET "end" = now() + interval \'1 minute\' WHERE id = %s', (rid,))
    dcur.execute('SELECT updated_at > start FROM lifelog."win-window/window@1" WHERE id = %s', (rid,))
    assert dcur.fetchone()[0], "updated_at 트리거"
    expect_error(dcur, 'INSERT INTO lifelog."win-window/window@1" (device, id, start, "end", app) VALUES (%s, %s, now(), now(), %s)',
                 ("phone", uuid.uuid4(), "x"), "row-level security")
    dcur.execute('SELECT count(*) FROM lifelog."win-window/window"')
    assert dcur.fetchone()[0] == 1
    expect_error(dcur, "CREATE TABLE lifelog.hack (x int)", None, "permission denied")

    print("4. 열 집합 버전 올리기와 뷰")
    dcur.execute("SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(WIN_WINDOW_V2),))
    assert dcur.fetchone()[0]["created_tables"] == ["win-window/window@2"]
    dcur.execute('INSERT INTO lifelog."win-window/window@2" (device, id, start, "end", app, title, pid, hwnd) VALUES (%s, %s, now(), now(), %s, %s, %s, %s)',
                 ("laptop", uuid.uuid4(), "msedge.exe", "t", 2, 99))
    dcur.execute('SELECT schema_version, hwnd FROM lifelog."win-window/window" ORDER BY schema_version')
    rows = dcur.fetchall()
    assert rows == [(1, None), (2, 99)], rows
    cur.execute("SELECT latest_version FROM lifelog.plugins WHERE id = 'win-window'")
    assert cur.fetchone()[0] == "1.1.0"
    bad = json.loads(json.dumps(WIN_WINDOW_V2)); bad["version"] = "1.2.0"; bad["outputs"][0]["schema_version"] = 3; bad["outputs"][0]["columns"]["pid"] = "text"
    expect_error(dcur, "SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(bad),), "타입을 바꾸려면")

    print("5. set_install")
    dcur.execute("SELECT lifelog.set_install('win-window', 'laptop', '1.1.0', true, %s::jsonb, %s::jsonb)",
                 (json.dumps({"mask_title_apps": ["KakaoTalk.exe"]}), json.dumps(["event:window"])))
    row = dcur.fetchone()[0]
    assert row["enabled"] is True and row["settings"]["mask_title_apps"] == ["KakaoTalk.exe"], row
    dcur.execute("SELECT lifelog.set_install('win-window', 'laptop', '1.1.0', true, NULL, '[]'::jsonb)")
    row = dcur.fetchone()[0]
    assert row["enabled"] is False, "필수 권한 없이는 켜지지 않아야 함"
    assert row["settings"]["mask_title_apps"] == [], "빠진 설정은 기본값"
    expect_error(dcur, "SELECT lifelog.set_install('win-window', 'laptop', '1.1.0', true, %s::jsonb, '[]'::jsonb)",
                 (json.dumps({"nope": 1}),), "없는 설정 키")
    expect_error(dcur, "SELECT lifelog.set_install('win-window', 'laptop', '1.1.0', true, NULL, %s::jsonb)",
                 (json.dumps(["clock"]),), "매니페스트에 없는 권한")

    print("6. 렌더러 등록과 미충족 입력")
    dcur.execute("SELECT lifelog.register_plugin(%s::jsonb, 'laptop')", (json.dumps(RENDERER),))
    dcur.execute("SELECT lifelog.set_install('app-timeline', 'laptop', '1.0.0', true, NULL, '[]'::jsonb)")
    row = dcur.fetchone()[0]
    assert row["unmet"] == ["phone-app/app@1"], row
    assert row["enabled"] is True

    print("7. 기기 폐기")
    cur.execute("SELECT lifelog.revoke_device('laptop')")
    try:
        psycopg.connect(with_role(a.dsn, "lifelog_device_laptop", pw), autocommit=True)
        raise AssertionError("폐기된 롤로 접속되면 안 됨")
    except psycopg.OperationalError:
        print("  폐기 확인")

    print("전부 통과")


if __name__ == "__main__":
    main()
