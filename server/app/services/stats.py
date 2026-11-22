"""台帳・プレイ記録からの集計。集計値は保存せず都度計算する（docs/04）。"""
from __future__ import annotations

from datetime import date

from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from app.db.models import Answer, LedgerEntry, PlaySession, SelfBestAchievement

EARN_TYPES = ("study", "streak_bonus", "self_best_bonus")


def balance(db: Session, child_id: str) -> int:
    return db.scalar(select(func.coalesce(func.sum(LedgerEntry.points), 0)).where(LedgerEntry.child_user_id == child_id)) or 0


def played_dates(db: Session, child_id: str) -> set[date]:
    return set(db.scalars(select(PlaySession.play_date).where(PlaySession.user_id == child_id).distinct()))


def day_questions(db: Session, child_id: str, d: date) -> int:
    return db.scalar(
        select(func.count()).select_from(Answer).join(PlaySession, PlaySession.play_id == Answer.play_id)
        .where(Answer.user_id == child_id, PlaySession.play_date == d, Answer.over_cap.is_(False))
    ) or 0


def day_study_points(db: Session, child_id: str, d: date) -> int:
    return db.scalar(
        select(func.coalesce(func.sum(LedgerEntry.points), 0))
        .where(LedgerEntry.child_user_id == child_id, LedgerEntry.type == "study", LedgerEntry.play_date == d)
    ) or 0


def month_earned(db: Session, child_id: str, d: date) -> int:
    first = d.replace(day=1)
    nxt = date(first.year + (first.month == 12), first.month % 12 + 1, 1)
    return db.scalar(
        select(func.coalesce(func.sum(LedgerEntry.points), 0))
        .where(LedgerEntry.child_user_id == child_id, LedgerEntry.type.in_(EARN_TYPES),
               LedgerEntry.play_date >= first, LedgerEntry.play_date < nxt)
    ) or 0


def dan_recent(db: Session, child_id: str, game_title_id: str, dan: int, window: int) -> list[bool]:
    rows = db.scalars(
        select(Answer.is_correct)
        .where(Answer.user_id == child_id, Answer.game_title_id == game_title_id, Answer.a == dan, Answer.over_cap.is_(False))
        .order_by(Answer.answer_id.desc()).limit(window)
    ).all()
    return list(reversed(rows))


def achieved(db: Session, child_id: str, game_title_id: str) -> set[tuple[int, int]]:
    rows = db.execute(
        select(SelfBestAchievement.dan, SelfBestAchievement.threshold)
        .where(SelfBestAchievement.child_user_id == child_id, SelfBestAchievement.game_title_id == game_title_id)
    ).all()
    return {(d, t) for d, t in rows}


def cumulative_accuracy(db: Session, child_id: str) -> float:
    total, correct = db.execute(
        select(func.count(), func.coalesce(func.sum(case((Answer.is_correct.is_(True), 1), else_=0)), 0))
        .where(Answer.user_id == child_id, Answer.over_cap.is_(False))
    ).one()
    return round(correct / total, 4) if total else 0.0


def last_play_at(db: Session, child_id: str):
    return db.scalar(select(func.max(PlaySession.received_at)).where(PlaySession.user_id == child_id))


def next_sequential_dan(db: Session, child_id: str, game_title_id: str) -> int:
    last = db.scalars(
        select(PlaySession.dan)
        .where(PlaySession.user_id == child_id, PlaySession.game_title_id == game_title_id,
               PlaySession.mode == "training_sequential")
        .order_by(PlaySession.received_at.desc(), PlaySession.play_id.desc()).limit(1)
    ).first()
    return 1 if last is None else last % 9 + 1
