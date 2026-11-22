from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.clock import TOKYO, now_utc
from app.db.models import LedgerEntry, User
from app.errors import invalid
from app.services import stats


def _find(db: Session, tx_id: str) -> LedgerEntry | None:
    return db.scalars(select(LedgerEntry).where(LedgerEntry.transaction_id == tx_id)).first()


def _append(db: Session, entry: LedgerEntry) -> tuple[LedgerEntry, bool]:
    """追記。同じ transaction_id が既にあれば最初のものを返す（原則3、冪等）。"""
    try:
        with db.begin_nested():
            db.add(entry)
            db.flush()
    except IntegrityError:
        existing = _find(db, entry.transaction_id) if entry.transaction_id else None
        if existing is None:
            raise
        return existing, False
    return entry, True


def withdraw(db: Session, actor: User, child: User, transaction_id: str, points: int,
             occurred_at: datetime | None, was_offline: bool, note: str | None = None) -> tuple[LedgerEntry, bool]:
    if points <= 0:
        raise invalid("引き出し額は1以上にしてください")
    existing = _find(db, transaction_id)
    if existing:
        _same(existing, "withdrawal", child, -points)
        return existing, False
    now = now_utc()
    occurred = occurred_at if (was_offline and occurred_at) else now
    entry = LedgerEntry(
        child_user_id=child.user_id, type="withdrawal", points=-points, transaction_id=transaction_id,
        actor_user_id=actor.user_id, occurred_at=occurred, recorded_at=now, note=(note or None),
    )
    entry, created = _append(db, entry)
    if not created:
        _same(entry, "withdrawal", child, -points)
    return entry, created


def adjust(db: Session, actor: User, child: User, adjustment_id: str, points: int, note: str | None,
           occurred_at: datetime | None = None, was_offline: bool = False) -> tuple[LedgerEntry, bool]:
    """調整（入金・訂正）。台帳の訂正は取引の追加で行う（原則2）。"""
    if points == 0:
        raise invalid("調整額は0以外にしてください")
    existing = _find(db, adjustment_id)
    if existing:
        _same(existing, "adjustment", child, points)
        return existing, False
    now = now_utc()
    entry = LedgerEntry(
        child_user_id=child.user_id, type="adjustment", points=points, transaction_id=adjustment_id,
        actor_user_id=actor.user_id, occurred_at=occurred_at if (was_offline and occurred_at) else now,
        recorded_at=now, note=(note or None),
    )
    entry, created = _append(db, entry)
    if not created:
        _same(entry, "adjustment", child, points)
    return entry, created


def _same(entry: LedgerEntry, typ: str, child: User, points: int) -> None:
    if entry.type != typ or entry.child_user_id != child.user_id or entry.points != points:
        raise invalid("同じIDで内容の違う取引がすでに記録されています")


def ledger(db: Session, child_id: str, d_from: date | None = None, d_to: date | None = None) -> list[LedgerEntry]:
    q = select(LedgerEntry).where(LedgerEntry.child_user_id == child_id)
    if d_from:
        q = q.where(LedgerEntry.occurred_at >= datetime.combine(d_from, datetime.min.time(), TOKYO))
    if d_to:
        from datetime import timedelta
        q = q.where(LedgerEntry.occurred_at < datetime.combine(d_to + timedelta(days=1), datetime.min.time(), TOKYO))
    return list(db.scalars(q.order_by(LedgerEntry.recorded_at.desc(), LedgerEntry.entry_id)))


def balance(db: Session, child_id: str) -> int:
    return stats.balance(db, child_id)
