"""폰 TSV(60 Daily/로그/YYYY-MM-DD 폰.tsv) → LifeLog 행.

형식은 SecondBrainMobile vault/PhoneLog.kt 주석과 health/HealthReader.kt·SamsungHealthReader.kt의 row(...) 호출.
시각은 HH:mm(분), 날짜는 파일명, 시간대는 폰 로컬(Asia/Seoul).
  a  시작 끝 앱이름                → phone-app/app@1 (package = 앱 이름. TSV에는 패키지가 없다)
  l  시각 위도 경도 정확도(m)      → phone-location/point@1
  m  시작 끝 제목 아티스트          → phone-media/media@1
  건강 줄                          → health/point@1, health/span@1
"""
import glob
import json
import os
from collections import Counter
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

from common import Rows, load_outputs

DEFAULT_DIR = os.path.expanduser(r"~\SecondBrain\60 Daily\로그")
DEVICE = "phone"
TZ = timezone(timedelta(hours=9), "KST")  # Asia/Seoul. DST가 없어 고정 오프셋으로 충분하고 tzdata 의존이 없다

# 하루 합계(구간 00:00~다음 00:00, category="daily"): 코드 → (metric, unit)
# 시간별 걸음(sh)은 category="hourly". 같은 metric의 하루 합계와 00:00 구간이 겹치지 않게 하기 위해서다.
DAILY = {
    "st": ("steps", "count"), "ds": ("distance", "m"), "fl": ("floors", "count"), "el": ("elevation_gain", "m"),
    "kc": ("active_kcal", "kcal"), "tk": ("total_kcal", "kcal"), "rh": ("resting_heart_rate", "bpm"),
    "wa": ("water", "ml"), "es": ("energy_score", "score"), "sc": ("sleep_score", "score"), "at": ("active_minutes", "min"),
}
# 시점 측정(시각 값): 코드 → (metric, unit)
POINT = {
    "hs": ("heart_rate", "bpm"), "hv": ("hrv", "ms"), "o2": ("spo2", "%"), "rr": ("respiration", "/min"),
    "bg": ("glucose", "mmol/L"), "bt": ("body_temp", "C"), "vo": ("vo2max", "ml/kg/min"), "sk": ("skin_temp", "C"),
}
# 시각 없는 시점 측정(체성분): 그날 00:00에 둔다
BODY = {"wt": ("weight", "kg"), "ht": ("height", "cm"), "bf": ("body_fat", "%"), "lm": ("lean_mass", "kg"), "bm": ("bmr", "kcal/day")}
STAGE = {"d": "deep", "l": "light", "r": "rem", "w": "awake", "o": "other"}


def at(d: date, hm: str) -> datetime:
    h, m = hm.split(":")
    return datetime.combine(d, time(int(h), int(m)), tzinfo=TZ)


def num(s: str):
    return float(s) if s not in ("", None) else None


def read(folder: str = DEFAULT_DIR):
    outs = load_outputs()
    app = Rows(outs["phone-app/app@1"], DEVICE)
    loc = Rows(outs["phone-location/point@1"], DEVICE)
    media = Rows(outs["phone-media/media@1"], DEVICE)
    point = Rows(outs["health/point@1"], DEVICE)
    span = Rows(outs["health/span@1"], DEVICE)
    skipped = Counter()

    for path in sorted(glob.glob(os.path.join(folder, "* 폰.tsv"))):
        d = date.fromisoformat(Path(path).name[:10])
        day0 = datetime.combine(d, time(0, 0), tzinfo=TZ)
        day1 = day0 + timedelta(days=1)
        sleep_start_hm = None  # 현재 수면 세션이 자정을 넘기면 그 시작 HH:mm (ss 줄의 날짜 판정용)
        for raw in open(path, encoding="utf-8"):
            raw = raw.rstrip("\n")
            if not raw:
                continue
            f = raw.split("\t")
            c = f[0]
            try:
                if c == "a":
                    s, e = at(d, f[1]), at(d, f[2])
                    if e < s:
                        e += timedelta(days=1)
                    app.add(s, e, package=f[3])
                elif c == "l":
                    loc.add(at(d, f[1]), None, lat=num(f[2]), lon=num(f[3]), accuracy=num(f[4]))
                elif c == "m":
                    s, e = at(d, f[1]), at(d, f[2])
                    if e < s:
                        e += timedelta(days=1)
                    media.add(s, e, package=None, title=f[3], artist=f[4] if len(f) > 4 else None, album=None, duration_ms=None)
                elif c in DAILY:
                    metric, unit = DAILY[c]
                    span.add(day0, day1, metric=metric, value=num(f[1]), category="daily", extra=json.dumps({"unit": unit}))
                elif c in POINT:
                    metric, unit = POINT[c]
                    point.add(at(d, f[1]), None, metric=metric, value=num(f[2]), unit=unit)
                elif c in BODY:
                    metric, unit = BODY[c]
                    point.add(day0, None, metric=metric, value=num(f[1]), unit=unit)
                elif c == "bp":
                    t = at(d, f[1])
                    point.add(t, None, metric="bp_systolic", value=num(f[2]), unit="mmHg")
                    point.add(t, None, metric="bp_diastolic", value=num(f[3]), unit="mmHg")
                elif c == "hr":  # 최저 평균 최고
                    span.add(day0, day1, metric="heart_rate_summary", value=num(f[2]), category="daily",
                             extra=json.dumps({"min": num(f[1]), "max": num(f[3]), "unit": "bpm"}))
                elif c == "nu":  # 칼로리 단백질 탄수화물 지방
                    span.add(day0, day1, metric="nutrition", value=num(f[1]), category="daily",
                             extra=json.dumps({"protein_g": num(f[2]), "carbs_g": num(f[3]), "fat_g": num(f[4]), "unit": "kcal"}))
                elif c == "sh":  # 시각 걸음 (그 시각부터 1시간)
                    s = at(d, f[1])
                    span.add(s, s + timedelta(hours=1), metric="steps", value=num(f[2]), category="hourly", extra=json.dumps({"unit": "count"}))
                elif c == "sl":  # 시작 끝 단계별 분. 시작이 끝보다 늦으면 전날
                    s, e = at(d, f[1]), at(d, f[2])
                    sleep_start_hm = None
                    if s > e:
                        s -= timedelta(days=1)
                        sleep_start_hm = f[1]
                    stages = {STAGE.get(k, k): int(v) for k, v in (p.split("=") for p in f[3].split(" ") if p)} if len(f) > 3 and f[3] else {}
                    span.add(s, e, metric="sleep", value=(e - s).total_seconds() / 60, category=None,
                             extra=json.dumps({"stages_min": stages, "unit": "min"}))
                elif c == "ss":  # 시작 끝 단계. 직전 sl 세션이 자정을 넘기면 세션 시작 이후 시각은 전날
                    s, e = at(d, f[1]), at(d, f[2])
                    if sleep_start_hm:
                        if f[1] >= sleep_start_hm:
                            s -= timedelta(days=1)
                        if f[2] >= sleep_start_hm:
                            e -= timedelta(days=1)
                    if e < s:
                        e += timedelta(days=1)
                    span.add(s, e, metric="sleep_stage", value=(e - s).total_seconds() / 60, category=STAGE.get(f[3], f[3]), extra=None)
                elif c == "ex":  # 시작 끝 운동 칼로리 거리(m) 걸음
                    s, e = at(d, f[1]), at(d, f[2])
                    if e < s:
                        e += timedelta(days=1)
                    span.add(s, e, metric="exercise", value=num(f[4]) if len(f) > 4 else None, category=f[3],
                             extra=json.dumps({"distance_m": num(f[5]) if len(f) > 5 else None, "steps": num(f[6]) if len(f) > 6 else None, "unit": "kcal"}))
                elif c == "ap":  # 시각 DETECTED|NOT_DETECTED
                    point.add(at(d, f[1]), None, metric="sleep_apnea_sign", value=1.0 if f[2] == "DETECTED" else 0.0, unit="flag")
                elif c == "ih":  # 시각
                    point.add(at(d, f[1]), None, metric="irregular_heart_rhythm", value=1.0, unit="flag")
                else:
                    skipped[f"모르는 코드 {c}"] += 1
            except (IndexError, ValueError) as e:
                skipped[f"파싱 실패 {c}: {e.__class__.__name__}"] += 1
    return [app, loc, media, point, span], skipped
