"""이관 건조 실행: 원본을 읽어 대상 테이블별 행 수·기간·표본·분류 불가 건수를 출력한다. DB에 쓰지 않는다.

  python tools/import/dry_run.py                 # AW DB와 폰 TSV 기본 경로
  python tools/import/dry_run.py --aw PATH --phone DIR --samples 5
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import aw
import phone


def show(name, tables, skipped, samples):
    print(f"== {name}")
    for t in tables:
        t.finish()
        s = t.summary()
        if s["rows"] == 0:
            print(f"  {s['table']}: 0행")
            continue
        print(f"  {s['table']}: {s['rows']:,}행  {s['from'][:16]} ~ {s['to'][:16]}  밀어낸 행 {s['bumped']}  합친 행 {s['merged']}")
        for a, b in t.examples[:3]:
            print(f"     겹침 예: {a['start'].strftime('%m-%d %H:%M:%S')} end {a['end']} / {b['end']}  {[(c, b.get(c)) for c in t.out.identity]}")
        for r in t.rows[:samples]:
            cols = {k: v for k, v in r.items() if k not in ("device", "id", "start", "end")}
            end = f" ~ {r['end'].strftime('%H:%M:%S')}" if r["end"] else ""
            print(f"     {r['start'].strftime('%Y-%m-%d %H:%M:%S%z')}{end}  {cols}")
    if skipped:
        print("  건너뜀:")
        for k, n in skipped.most_common():
            print(f"     {k}: {n:,}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aw", default=aw.DEFAULT_DB)
    ap.add_argument("--phone", default=phone.DEFAULT_DIR)
    ap.add_argument("--samples", type=int, default=3)
    a = ap.parse_args()
    tables, skipped = aw.read(a.aw)
    show("ActivityWatch", tables, skipped, a.samples)
    tables, skipped = phone.read(a.phone)
    show("폰 TSV", tables, skipped, a.samples)


if __name__ == "__main__":
    main()
