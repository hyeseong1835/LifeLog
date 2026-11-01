"""코어 스키마 마이그레이션 실행기 (개발용).

앱(수집기·대시보드)은 같은 규칙을 C#·Kotlin으로 구현한다. 이 스크립트는 로컬 테스트와 첫 설정용이다.

사용:
  python tools/db/migrate.py --dsn postgresql://user:pw@host:port/db          # 적용
  python tools/db/migrate.py --dsn ... --status                                # 현재 버전만
  LIFELOG_ADMIN_DSN 환경 변수로 --dsn을 대신할 수 있다. 접속 문자열은 파일에 적지 않는다.

규칙 (Documents/등록 함수.md 4절):
- db/migrations/NNNN_*.sql을 번호 순으로, schema_migrations에 없는 것만 적용한다. 전진만.
- 전체를 advisory lock 안에서 돌려 여러 기기가 동시에 적용하지 못하게 한다.
- 파일 하나가 트랜잭션 하나다. 실패하면 그 파일부터 다시.
"""
import argparse
import os
import re
import sys
from pathlib import Path

import psycopg

LOCK_KEY = 0x4C4C4F47  # 'LLOG'
MIGRATIONS = Path(__file__).resolve().parents[2] / "db" / "migrations"


def files():
    out = []
    for p in sorted(MIGRATIONS.glob("*.sql")):
        m = re.match(r"^(\d{4})_", p.name)
        if not m:
            raise SystemExit(f"파일 이름이 NNNN_이름.sql 꼴이 아닙니다: {p.name}")
        out.append((int(m.group(1)), p))
    return out


def current_version(cur):
    cur.execute("SELECT to_regclass('lifelog.schema_migrations')")
    if cur.fetchone()[0] is None:
        return 0
    cur.execute("SELECT COALESCE(max(version), 0) FROM lifelog.schema_migrations")
    return cur.fetchone()[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", default=os.environ.get("LIFELOG_ADMIN_DSN"))
    ap.add_argument("--status", action="store_true")
    a = ap.parse_args()
    if not a.dsn:
        raise SystemExit("--dsn 또는 LIFELOG_ADMIN_DSN이 필요합니다")

    with psycopg.connect(a.dsn, autocommit=True) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT pg_advisory_lock(%s)", (LOCK_KEY,))
            try:
                cur_v = current_version(cur)
                latest = max((v for v, _ in files()), default=0)
                print(f"DB 버전 {cur_v}, 파일 최신 {latest}")
                if a.status:
                    return
                if cur_v > latest:
                    raise SystemExit("DB가 이 도구가 아는 버전보다 높습니다. 도구를 갱신하세요. 쓰기를 하지 않습니다.")
                for v, p in files():
                    if v <= cur_v:
                        continue
                    sql = p.read_text(encoding="utf-8")
                    print(f"적용 {p.name} ...", end=" ", flush=True)
                    with conn.transaction():
                        cur.execute(sql)
                        cur.execute("INSERT INTO lifelog.schema_migrations (version) VALUES (%s)", (v,))
                    print("완료")
            finally:
                cur.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))


if __name__ == "__main__":
    main()
