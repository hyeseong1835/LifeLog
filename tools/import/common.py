"""이관 공통: 매니페스트 로드, 결정적 id, 해상도 올리기.

id 규칙은 호스트의 span.put과 같아야 한다(Documents/수집기 호스트 API.md 3절):
  key가 있으면  uuid5(NS, plugin | device | output | key)
  없으면        uuid5(NS, plugin | device | output | start(ISO, UTC) | identity 열 값들(JSON))
NS는 LifeLog 고정 네임스페이스. 호스트 구현(C#·Kotlin)도 같은 값을 쓴다 → spec/에 적는다.
"""
import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

NS = uuid.UUID("7d1b3c2e-5a4f-4e8b-9c6d-2f1e0a9b8c7d")
SEP = "\x1f"
MANIFESTS = Path(__file__).resolve().parent / "manifests"


class Output:
    def __init__(self, plugin: str, out: dict):
        self.plugin = plugin
        self.name = out["name"]
        self.kind = out["kind"]
        self.schema_version = out["schema_version"]
        self.columns = out["columns"]
        self.identity = out.get("identity", [])
        self.table = f"{plugin}/{self.name}@{self.schema_version}"

    def row_id(self, device: str, start: datetime, row: dict, key: str | None = None) -> uuid.UUID:
        if key is not None:
            return uuid.uuid5(NS, SEP.join([self.plugin, device, self.name, key]))
        ident = json.dumps([row.get(c) for c in self.identity], ensure_ascii=False, separators=(",", ":"))
        return uuid.uuid5(NS, SEP.join([self.plugin, device, self.name, start.astimezone(timezone.utc).isoformat(), ident]))


def load_outputs() -> dict[str, Output]:
    """table 이름 → Output"""
    outs = {}
    for p in sorted(MANIFESTS.glob("*.json")):
        m = json.loads(p.read_text(encoding="utf-8"))
        for o in m["outputs"]:
            out = Output(m["id"], o)
            outs[out.table] = out
    return outs


class Rows:
    """테이블 하나에 쌓이는 행. finish()에서 id를 만들고 겹치는 (start, identity)를 1초씩 밀어 푼다."""

    def __init__(self, out: Output, device: str, policy: str = "bump"):
        """policy:
        bump  - 같은 (start, identity)가 또 나오면 1초씩 밀어 다른 기록으로 둔다(TSV의 분 해상도).
        merge - 같은 (start, identity)는 같은 기록으로 보고 끝이 가장 늦은 행만 남긴다(AW 하트비트 흔적).
        """
        self.out = out
        self.device = device
        self.policy = policy
        self.rows: list[dict] = []
        self.bumped = 0
        self.merged = 0
        self.examples: list[tuple[dict, dict]] = []

    def add(self, start: datetime, end: datetime | None, **cols):
        unknown = set(cols) - set(self.out.columns)
        if unknown:
            raise ValueError(f"{self.out.table}: 선언되지 않은 열 {unknown}")
        r = {"device": self.device, "start": start, "end": end, **cols}
        self.rows.append(r)

    def finish(self):
        key_of = lambda r: (r["start"], tuple(r.get(c) for c in self.out.identity))
        if self.policy == "merge":
            best: dict[tuple, dict] = {}
            for r in self.rows:
                k = key_of(r)
                if k in best:
                    self.merged += 1
                    if len(self.examples) < 5 and best[k]["end"] != r["end"]:
                        self.examples.append((best[k], r))
                    if r["end"] is not None and (best[k]["end"] is None or r["end"] > best[k]["end"]):
                        best[k] = r
                else:
                    best[k] = r
            self.rows = list(best.values())
        else:
            seen: dict[tuple, int] = {}
            for r in self.rows:  # 파일(입력) 순서를 지킨다
                k = key_of(r)
                n = seen.get(k, 0)
                seen[k] = n + 1
                if n:
                    self.bumped += 1
                    if len(self.examples) < 5:
                        self.examples.append((dict(r), r))
                    r["start"] = r["start"] + timedelta(seconds=n)
                    if r["end"] is not None and r["end"] < r["start"]:
                        r["end"] = r["start"]
        for r in self.rows:
            r["id"] = self.out.row_id(self.device, r["start"], r)
        return self.rows

    def summary(self):
        if not self.rows:
            return {"table": self.out.table, "rows": 0}
        starts = [r["start"] for r in self.rows]
        return {"table": self.out.table, "rows": len(self.rows), "from": min(starts).isoformat(), "to": max(starts).isoformat(), "bumped": self.bumped, "merged": self.merged}
