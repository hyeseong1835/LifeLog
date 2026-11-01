"""이관 쓰기. 관리자 접속으로 기기·매니페스트를 등록하고 파서가 만든 행을 COPY → ON CONFLICT upsert로 넣는다.

  python tools/import/write.py --dsn postgresql://... [--laptop laptop] [--phone phone] [--only aw|phone]

- 기기가 없으면 register_device로 만들고 비밀번호를 한 번 출력한다(앱 설정에 쓰거나 reset_device_password로 다시 받는다).
- 매니페스트(manifests/*.json)를 register_plugin으로 등록해 v1 테이블을 만든다. 관리자 호출이라 어느 기기 이름으로도 등록할 수 있다.
- 재실행해도 안전하다: (device, id)가 같으면 덮어쓴다.
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import psycopg
from psycopg.types.json import Jsonb

sys.path.insert(0, str(Path(__file__).resolve().parent))
import aw
import phone
from common import MANIFESTS, load_outputs

DEVICE_OF_PLUGIN = {"win-window": "laptop", "win-afk": "laptop", "win-power": "laptop", "win-media": "laptop",
                    "phone-app": "phone", "phone-screen": "phone", "phone-location": "phone", "phone-media": "phone", "health": "phone"}


def ensure_device(cur, dev_id, name, platform):
    cur.execute("SELECT 1 FROM lifelog.devices WHERE id = %s AND revoked_at IS NULL", (dev_id,))
    if cur.fetchone():
        return
    cur.execute("SELECT lifelog.register_device(%s, %s, %s)", (dev_id, name, platform))
    pw = cur.fetchone()[0]
    print(f"기기 {dev_id} 등록. 비밀번호(한 번만 표시): {pw}")


def register_manifests(cur, devices):
    for p in sorted(MANIFESTS.glob("*.json")):
        m = json.loads(p.read_text(encoding="utf-8"))
        dev = devices[DEVICE_OF_PLUGIN[m["id"]]]
        cur.execute("SELECT lifelog.register_plugin(%s::jsonb, %s)", (p.read_text(encoding="utf-8"), dev))
        r = cur.fetchone()[0]
        if r["created_tables"]:
            print(f"  테이블 생성: {', '.join(r['created_tables'])}")


def write_table(conn, rows_obj, device_map):
    out = rows_obj.out
    rows = rows_obj.rows
    if not rows:
        return 0
    table = out.table
    device = device_map[rows_obj.device]
    free = sorted(out.columns)
    time_cols = ["start", '"end"'] if out.kind == "span" else ["time"]
    cols = ["device", "id"] + time_cols + [f'"{c}"' for c in free]
    col_list = ", ".join(cols)
    with conn.cursor() as cur:
        cur.execute(f'CREATE TEMP TABLE tmp_import (LIKE lifelog."{table}" INCLUDING DEFAULTS) ON COMMIT DROP')
        with cur.copy(f"COPY tmp_import ({col_list}) FROM STDIN") as copy:
            for r in rows:
                vals = [device, r["id"]]
                vals += [r["start"], r["end"]] if out.kind == "span" else [r["start"]]
                for c in free:
                    v = r.get(c)
                    if out.columns[c] == "jsonb" and isinstance(v, str):
                        v = Jsonb(json.loads(v))
                    elif out.columns[c] == "jsonb" and v is not None:
                        v = Jsonb(v)
                    vals.append(v)
                copy.write_row(vals)
        updates = ", ".join(([' "end" = EXCLUDED."end"'] if out.kind == "span" else []) + [f'"{c}" = EXCLUDED."{c}"' for c in free])
        cur.execute(f'INSERT INTO lifelog."{table}" ({col_list}) SELECT {col_list} FROM tmp_import '
                    f"ON CONFLICT (device, id) DO UPDATE SET {updates}")
        n = cur.rowcount
    conn.commit()
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=os.environ.get("LIFELOG_ADMIN_DSN"))
    ap.add_argument("--laptop", default="laptop")
    ap.add_argument("--phone", default="phone")
    ap.add_argument("--only", choices=["aw", "phone"])
    ap.add_argument("--aw", default=aw.DEFAULT_DB)
    ap.add_argument("--phone-dir", default=phone.DEFAULT_DIR)
    a = ap.parse_args()
    if not a.dsn:
        raise SystemExit("--dsn 또는 LIFELOG_ADMIN_DSN이 필요합니다")
    devices = {"laptop": a.laptop, "phone": a.phone}

    with psycopg.connect(a.dsn, autocommit=True) as conn, conn.cursor() as cur:
        print("1. 기기·매니페스트 등록")
        ensure_device(cur, a.laptop, "노트북", "Windows 11")
        ensure_device(cur, a.phone, "폰", "Android")
        register_manifests(cur, devices)

    with psycopg.connect(a.dsn) as conn:
        for name, reader, arg in (("AW", aw.read, a.aw), ("폰 TSV", phone.read, a.phone_dir)):
            if a.only and ((a.only == "aw") != (name == "AW")):
                continue
            print(f"2. {name} 읽기")
            tables, skipped = reader(arg)
            for t in tables:
                t.finish()
                t0 = time.time()
                n = write_table(conn, t, devices)
                print(f"  {t.out.table}: {n:,}행 ({time.time() - t0:.1f}s)")
            for k, v in skipped.items():
                print(f"  건너뜀 {k}: {v:,}")
    print("완료")


if __name__ == "__main__":
    main()
