"""ポイント計算エンジン単体: spec/point-rules-cases.json の全ケース（TC1〜TC16）。"""
from __future__ import annotations

from datetime import date

import pytest

from app.domain.points import AnswerIn, PlayInput, PointRules, PriorState, process_play
from app.domain.points.engine import compute_current_streak, compute_streak
from tests.cases import check_case, expand, load_spec

SPEC = load_spec()
RULES = PointRules(**{k: v for k, v in SPEC["settings"].items()})


def run_case(case: dict):
    init = case.get("init", {})
    played: set[date] = {date.fromisoformat(d) for d in init.get("played_dates", [])}
    month_earned: dict[tuple[int, int], int] = {}
    if "month_earned" in init:
        # 初期状態の月は最初のプレイの月
        first = expand(case)[0].date
        month_earned[(first.year, first.month)] = init["month_earned"]
    day_q: dict[date, int] = {}
    day_pts: dict[date, int] = {}
    hist: dict[int, list[bool]] = {}
    achieved: set[tuple[int, int]] = set()
    results = []
    cap_reached_on = None
    last_month = None
    for p in expand(case):
        key = (p.date.year, p.date.month)
        prior = PriorState(
            day_questions=day_q.get(p.date, 0),
            day_study_points=day_pts.get(p.date, 0),
            month_earned=month_earned.get(key, 0),
            played_dates=set(played),
            dan_history={k: list(v) for k, v in hist.items()},
            achieved=set(achieved),
        )
        r = process_play(RULES, prior, PlayInput(p.mode, p.date, [AnswerIn(*a) for a in p.answers]))
        played.add(p.date)
        day_pts[p.date] = day_pts.get(p.date, 0) + r.study
        month_earned[key] = month_earned.get(key, 0) + r.total
        day_q[p.date] = day_q.get(p.date, 0) + sum(1 for o in r.over_cap if not o)
        for (a, _b, ok), o in zip(p.answers, r.over_cap):
            if not o:
                hist.setdefault(a, []).append(ok)
        for s in r.self_best:
            achieved.add((s.dan, s.threshold))
        if cap_reached_on is None and month_earned[key] >= RULES.monthly_cap:
            cap_reached_on = p.date
        last_month = key
        results.append(
            {
                "study": r.study,
                "streak_bonus": r.streak_bonus,
                "self_best": r.self_best_total,
                "over_cap": sum(r.over_cap),
            }
        )
    totals = {"final": month_earned[last_month], "cap_reached_on": cap_reached_on}
    return results, totals


@pytest.mark.parametrize("case", SPEC["cases"], ids=[c["id"] for c in SPEC["cases"]])
def test_point_rule_cases(case):
    results, totals = run_case(case)
    check_case(case, results, totals)


def test_all_13_cases_present():
    assert [c["id"] for c in SPEC["cases"]] == [f"TC{i}" for i in range(1, 17)]


def test_streak_helpers():
    d = date
    assert compute_streak(d(2026, 12, 5), {d(2026, 12, d_) for d_ in (1, 2, 3, 4)}) == 5
    assert compute_streak(d(2026, 12, 1), {d(2026, 11, 30)}) == 1
    # 表示用：今日未プレイなら昨日まで
    assert compute_current_streak(d(2026, 12, 5), {d(2026, 12, 3), d(2026, 12, 4)}) == 2
    assert compute_current_streak(d(2026, 12, 5), {d(2026, 12, 2)}) == 0
    assert compute_current_streak(d(2026, 12, 1), {d(2026, 11, 30)}) == 0


def test_graduation_can_be_disabled_or_changed():
    """卒業（docs/03 5.2.1）も設定値から読む。null なら卒業なし。"""
    prior = PriorState(achieved={(7, 80), (7, 90), (7, 100)})
    p = PlayInput("training_select", date(2026, 12, 1), [AnswerIn(7, b, True) for b in range(1, 10)])
    assert process_play(RULES, prior, p).study == 0
    off = RULES.model_copy(update={"graduation": None})
    assert process_play(off, prior, p).study == 9
    half = RULES.model_copy(deep=True)
    half.graduation.points_per_correct = 1
    half.graduation.rate = 90
    half.points_per_correct["training_select"] = 2
    assert process_play(half, PriorState(achieved={(7, 90)}), p).study == 9
    assert process_play(half, PriorState(), p).study == 18
    # 順番モードは対象外
    seq = PlayInput("training_sequential", date(2026, 12, 1), p.answers)
    assert process_play(RULES, prior, seq).study == 9


def test_graduation_validation():
    import pytest
    from pydantic import ValidationError
    base = {k: v for k, v in SPEC["settings"].items()}
    with pytest.raises(ValidationError):
        PointRules(**{**base, "graduation": {"modes": ["training_select"], "rate": 95, "points_per_correct": 0}})
    with pytest.raises(ValidationError):
        PointRules(**{**base, "graduation": {"modes": ["nope"], "rate": 100, "points_per_correct": 0}})
    assert PointRules(**{k: v for k, v in base.items() if k != "graduation"}).graduation is None  # 古い版


def test_rule_values_come_from_settings():
    """値のハードコードがないこと：設定を変えれば結果が変わる。"""
    rules = RULES.model_copy(deep=True)
    rules.points_per_correct["challenge"] = 3
    rules.daily_point_cap = 1000
    p = PlayInput("challenge", date(2026, 12, 1), [AnswerIn(1, 1, True)] * 9)
    assert process_play(rules, PriorState(), p).study == 27
