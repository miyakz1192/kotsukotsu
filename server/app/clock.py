"""現在時刻の取得を1か所にまとめる（テストで差し替える）。日付判定は Asia/Tokyo（原則5）。"""
from __future__ import annotations

from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

TOKYO = ZoneInfo("Asia/Tokyo")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def to_tokyo_date(dt: datetime) -> date:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(TOKYO).date()


def today_tokyo() -> date:
    return to_tokyo_date(now_utc())
