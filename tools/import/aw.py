"""ActivityWatch SQLite → LifeLog 행.

버킷 id가 재사용돼 섞여 있으므로 datastr의 키 모양으로 분류한다(Documents/테이블 설계.md 6절).
같은 시작·identity의 행은 AW 하트비트가 구간을 늘리며 남긴 흔적이라 끝이 가장 늦은 것 하나만 남긴다(policy="merge").
  {app, title}             → win-window/window@1
  {status}                 → win-afk/afk@1
  {player, title, artist?} → win-media/media@1
  {file, language, project}→ 나중에(VS Code 수집기)
"""
import json
import os
import sqlite3
from collections import Counter
from datetime import datetime, timedelta, timezone

from common import Rows, load_outputs

DEFAULT_DB = os.path.expandvars(r"%LOCALAPPDATA%\activitywatch\activitywatch\aw-server\peewee-sqlite.v2.db")
DEVICE = "laptop"


def parse_ts(s: str) -> datetime:
    d = datetime.fromisoformat(s)
    return d if d.tzinfo else d.replace(tzinfo=timezone.utc)


def read(db_path: str = DEFAULT_DB):
    outs = load_outputs()
    window = Rows(outs["win-window/window@1"], DEVICE, policy="merge")
    afk = Rows(outs["win-afk/afk@1"], DEVICE, policy="merge")
    media = Rows(outs["win-media/media@1"], DEVICE, policy="merge")
    skipped = Counter()

    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        cur = con.execute("SELECT timestamp, duration, datastr FROM eventmodel ORDER BY timestamp, id")
        for ts, dur, ds in cur:
            data = json.loads(ds)
            keys = frozenset(data)
            start = parse_ts(ts)
            end = start + timedelta(seconds=dur or 0)
            if keys == {"app", "title"}:
                window.add(start, end, app=data["app"], title=data["title"], pid=None)
            elif keys == {"status"}:
                afk.add(start, end, afk=(data["status"] == "afk"))
            elif keys in ({"player", "title", "artist"}, {"player", "title"}):
                media.add(start, end, app=data["player"], title=data["title"], artist=data.get("artist"), album=None)
            elif keys == {"file", "language", "project"}:
                skipped["vscode (나중에)"] += 1
            else:
                skipped[f"분류 불가 {sorted(keys)}"] += 1
    finally:
        con.close()
    return [window, afk, media], skipped
