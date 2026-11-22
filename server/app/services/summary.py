from __future__ import annotations

from sqlalchemy.orm import Session

from app.clock import now_utc, today_tokyo
from app.domain.points import PointRules, compute_current_streak
from app.services import stats


def player_summary(db: Session, child_id: str, game_title_id: str, rules: PointRules) -> dict:
    today = today_tokyo()
    dates = stats.played_dates(db, child_id)
    window = rules.self_best.window
    recent = {}
    for dan in range(1, 10):
        h = stats.dan_recent(db, child_id, game_title_id, dan, window)
        if h:
            recent[str(dan)] = h
    ach = stats.achieved(db, child_id, game_title_id)
    return {
        "as_of": now_utc(),
        "streak_days": compute_current_streak(today, dates),
        "cumulative_accuracy": stats.cumulative_accuracy(db, child_id),
        "balance": stats.balance(db, child_id),
        "today": {
            "questions": stats.day_questions(db, child_id, today),
            "question_cap": rules.daily_question_cap,
            "study_points": stats.day_study_points(db, child_id, today),
            "point_cap": rules.daily_point_cap,
            "first_play_done": today in dates,
        },
        "month": {"earned": stats.month_earned(db, child_id, today), "cap": rules.monthly_cap},
        "dan_recent": recent,
        "self_best_achieved": [{"dan": d, "threshold": t} for d, t in sorted(ach)],
        "next_sequential_dan": stats.next_sequential_dan(db, child_id, game_title_id),
        "settings_version": rules.version,
    }


def child_card(db: Session, child, rules: PointRules) -> dict:
    """親向け：子供ごとの要約（管理画面ダッシュボード・GET /children/summary）。"""
    today = today_tokyo()
    earned = stats.month_earned(db, child.user_id, today)
    return {
        "user_id": child.user_id,
        "display_name": child.display_name,
        "is_active": child.is_active,
        "balance": stats.balance(db, child.user_id),
        "streak_days": compute_current_streak(today, stats.played_dates(db, child.user_id)),
        "month_earned": earned,
        "month_cap": rules.monthly_cap,
        "month_remaining": max(0, rules.monthly_cap - earned),
        "cumulative_accuracy": stats.cumulative_accuracy(db, child.user_id),
        "last_play_at": stats.last_play_at(db, child.user_id),
        "as_of": now_utc(),
    }
