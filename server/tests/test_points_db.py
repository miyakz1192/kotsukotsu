"""TC1〜TC16 を、実DB（SQLite）経由のサービス層でも実行し、エンジン単体と同じ結果になることを確認する。"""
from __future__ import annotations

from datetime import date, datetime, timezone

import pytest
from sqlalchemy import select

from app.db.models import GameRegistration, LedgerEntry, PlaySession
from app.services import family, plays, registrations, rules as rules_svc, stats
from tests.cases import check_case, expand, load_spec
from tests.conftest import uid

SPEC = load_spec()


@pytest.mark.parametrize("case", SPEC["cases"], ids=[c["id"] for c in SPEC["cases"]])
def test_cases_through_database(env, case):
    fc = env.fake_clock
    with env.session() as db:
        fam = family.get_family(db)
        child = family.add_child(db, fam, f"case-{case['id']}")
        reg, _ = registrations.register(db, "kuku-challenge", child, "dev-x")
        db.commit()
        cid, reg_id = child.user_id, reg.registration_id

        init = case.get("init", {})
        for d in init.get("played_dates", []):
            db.add(PlaySession(
                play_id=uid(), user_id=cid, game_title_id="kuku-challenge", registration_id=reg_id, mode="challenge",
                dan=None, started_at_device=fc.now, ended_at_device=fc.now, received_at=fc.now, was_offline=False,
                play_date=date.fromisoformat(d), clock_suspect=False, server_rule_version=1, correct_count=0))
        if "month_earned" in init:
            first = expand(case)[0].date
            other = first.replace(day=15)  # 別の日に獲得済みとして積む（1日の上限の対象外にする）
            db.add(LedgerEntry(child_user_id=cid, type="study", points=init["month_earned"], occurred_at=fc.now,
                               recorded_at=fc.now, play_date=other))
        db.commit()

    results, cap_reached_on, last = [], None, None
    for p in expand(case):
        fc.set_tokyo(p.date.year, p.date.month, p.date.day, 12, 0)
        with env.session() as db:
            reg = db.get(GameRegistration, reg_id)
            req = plays.PlayReq(
                play_id=uid(), mode=p.mode, dan=p.dan, started_at_device=fc.now, ended_at_device=fc.now,
                was_offline=False, client_rule_version=1,
                answers=[plays.AnswerReq(i + 1, a, b, a * b if ok else a * b + 1) for i, (a, b, ok) in enumerate(p.answers)])
            out = plays.submit_play(db, reg, req, rules_svc.current_rules(db), "1.0.0")
            over = len([1 for a in plays.answers_of(db, req.play_id) if a.over_cap])
            results.append({"study": out.study, "streak_bonus": out.streak_bonus, "self_best": out.self_best_bonus, "over_cap": over})
            month = stats.month_earned(db, cid, p.date)
            if cap_reached_on is None and month >= 1500:
                cap_reached_on = p.date
            last = month
    check_case(case, results, {"final": last, "cap_reached_on": cap_reached_on})
