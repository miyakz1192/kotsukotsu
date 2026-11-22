"""spec/point-rules-cases.json を読み、プレイ列に展開する共通ヘルパ。"""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path

SPEC = Path(__file__).resolve().parents[2] / "spec" / "point-rules-cases.json"


def load_spec() -> dict:
    return json.loads(SPEC.read_text(encoding="utf-8"))


@dataclass
class PlaySpec:
    number: int
    date: date
    mode: str
    dan: int | None
    answers: list[tuple[int, int, bool]]  # (a, b, is_correct)


def _answers(step: dict) -> list[tuple[int, int, bool]]:
    correct = step.get("correct", 9)
    if step["pattern"] == "dan":
        pairs = [(step["dan"], b) for b in range(1, 10)]
    elif step["pattern"] == "one_per_dan":
        pairs = [(a, 1) for a in range(1, 10)]
    else:
        raise ValueError(step["pattern"])
    return [(a, b, i < correct) for i, (a, b) in enumerate(pairs)]


def expand(case: dict) -> list[PlaySpec]:
    plays: list[PlaySpec] = []
    for step in case["steps"]:
        if "date_range" in step:
            d = date.fromisoformat(step["date_range"]["from"])
            end = date.fromisoformat(step["date_range"]["to"])
            dates = []
            while d <= end:
                dates.append(d)
                d += timedelta(days=1)
        else:
            dates = [date.fromisoformat(step["date"])]
        for d in dates:
            for _ in range(step.get("repeat", 1)):
                plays.append(PlaySpec(len(plays) + 1, d, step["mode"], step.get("dan"), _answers(step)))
    return plays


def check_case(case: dict, results: list[dict], month_totals: dict[date, int] | None = None, cap: int = 1500):
    """results[i] = {'study','streak_bonus','self_best','over_cap'} （i番目のプレイ）。"""
    cid = case["id"]
    for exp in case.get("expect", []):
        got = results[exp["play"] - 1]
        for key in ("study", "streak_bonus", "self_best", "over_cap"):
            if key in exp:
                assert got[key] == exp[key], f"{cid} play {exp['play']} {key}: got {got[key]}, want {exp[key]}"
    totals = case.get("expect_totals")
    if totals:
        assert month_totals is not None
        # month_totals: プレイ番号ごとの (date, その時点の月合計)
        assert month_totals["final"] == totals["month_total"], f"{cid} month_total {month_totals['final']}"
        assert str(month_totals["cap_reached_on"]) == totals["cap_reached_on"], (
            f"{cid} cap_reached_on {month_totals['cap_reached_on']}"
        )
