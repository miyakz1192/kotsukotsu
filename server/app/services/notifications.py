"""おしらせ通知の設定と文言（docs/06 1.7、REQ-GAME-19〜22、REQ-WEB-09）。

通知そのものは九九アプリが端末内で予約して出す。サーバは設定と文言を配るだけ。
"""
from __future__ import annotations

import re
from datetime import date, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.clock import now_utc
from app.db.models import NotificationMessage, NotificationSetting
from app.domain.holidays import holidays_between
from app.errors import ApiError

KINDS = ("encourage", "points")
KIND_LABEL = {"encourage": "はげまし", "points": "ポイント入り"}
PLACEHOLDERS = {
    "name": "表示名",
    "balance": "いまのポイント",
    "today_left": "きょう あと ためられる ポイント",
    "month_left": "こんげつ あと ためられる ポイント",
    "streak": "いまの れんぞく日数",
    "next_streak": "きょう やると なる れんぞく日数",
    "streak_bonus": "きょう やると もらえる れんぞくボーナス",
}
MAX_TIMES = 3
MAX_TEXT = 60
HOLIDAY_DAYS = 90

DEFAULTS = dict(enabled=True, inactive_days=1, weekday_times=["07:15", "17:00", "20:00"],
                holiday_times=["10:00", "17:00", "20:00"], point_message_every=3)

DEFAULT_MESSAGES = [
    ("encourage", "{name}、きょうも 九九 やってみよう！ちょっとだけでも いいよ"),
    ("encourage", "がっこう おつかれさま。5ふんだけ 九九 しない？"),
    ("encourage", "まいにち すこしずつが いちばん つよいよ"),
    ("encourage", "{name}の 九九、きょうも まってるよ"),
    ("encourage", "1かいだけ やってみる？きっと できるよ"),
    ("encourage", "まちがえても だいじょうぶ。やるたびに つよくなるよ"),
    ("encourage", "きのうより ちょっとだけ はやく こたえられるかな？"),
    ("encourage", "{name}なら できる！九九で あたまの じゅんび たいそう"),
    ("encourage", "すきな だんから はじめても いいよ"),
    ("encourage", "ひといき ついたら、九九 で ひとあそび しよう"),
    ("encourage", "コツコツが ちからに なるよ。きょうも ちょっとずつ"),
    ("encourage", "おうえん してるよ！九九 やってみよう"),
    ("points", "いま {balance}ポイント！きょう やると あと {today_left}ポイント ふやせるよ"),
    ("points", "おさいふは いま {balance}えん。九九で もっと ためよう！"),
    ("points", "きょう やると れんぞく {next_streak}にち！ボーナス {streak_bonus}ポイント"),
    ("points", "こんげつは あと {month_left}ポイント ためられるよ"),
    ("points", "{name}の おさいふ {balance}えん。きょうも ふやしちゃおう"),
    ("points", "きょうの ポイントは まだ {today_left}ポイント のこってるよ"),
]

_TIME = re.compile(r"^([01]?\d|2[0-3]):([0-5]\d)$")
_PH = re.compile(r"\{([a-z_]+)\}")


def _bad(msg: str) -> ApiError:
    return ApiError(400, "INVALID_REQUEST", msg)


def get_settings(db: Session) -> NotificationSetting:
    """設定（なければ既定値で作る）。文言が1件もなければ初期文言も入れる。"""
    s = db.get(NotificationSetting, 1)
    if s is None:
        s = NotificationSetting(setting_id=1, updated_at=now_utc(), **DEFAULTS)
        db.add(s)
        if db.scalars(select(NotificationMessage).limit(1)).first() is None:
            for i, (kind, text) in enumerate(DEFAULT_MESSAGES):
                db.add(NotificationMessage(kind=kind, text=text, is_active=True, sort=i, updated_at=now_utc()))
        db.flush()
    return s


def _times(values: list[str], label: str) -> list[str]:
    out = []
    for v in values:
        v = (v or "").strip()
        if not v:
            continue
        m = _TIME.match(v)
        if not m:
            raise _bad(f"{label}の時刻は 07:15 のように入力してください（{v}）")
        out.append(f"{int(m.group(1)):02d}:{m.group(2)}")
    if len(out) > MAX_TIMES:
        raise _bad(f"{label}の時刻は{MAX_TIMES}つまでです")
    if len(set(out)) != len(out):
        raise _bad(f"{label}の時刻が重複しています")
    return sorted(out)


def save_settings(db: Session, actor_id: str | None, *, enabled: bool, inactive_days: int, weekday_times: list[str],
                  holiday_times: list[str], point_message_every: int) -> NotificationSetting:
    if not 1 <= inactive_days <= 7:
        raise _bad("やっていない日数は 1〜7 で入力してください")
    if not 0 <= point_message_every <= 10:
        raise _bad("ポイント入りの頻度は 0〜10 で入力してください")
    wd, hd = _times(weekday_times, "平日"), _times(holiday_times, "土日祝")
    s = get_settings(db)
    s.enabled, s.inactive_days, s.weekday_times, s.holiday_times = enabled, inactive_days, wd, hd
    s.point_message_every, s.updated_at, s.updated_by = point_message_every, now_utc(), actor_id
    db.flush()
    return s


def _check_message(kind: str, text: str) -> str:
    if kind not in KINDS:
        raise _bad("文言の種類が不正です")
    text = (text or "").strip()
    if not text:
        raise _bad("文言を入力してください")
    if len(text) > MAX_TEXT:
        raise _bad(f"文言は{MAX_TEXT}字以内にしてください")
    names = _PH.findall(text)
    unknown = [n for n in names if n not in PLACEHOLDERS]
    if unknown:
        raise _bad(f"使えない差し込みです：{{{unknown[0]}}}")
    if "{" in _PH.sub("", text) or "}" in _PH.sub("", text):
        raise _bad("差し込みの { } が閉じていません")
    return text


def list_messages(db: Session, active_only: bool = False) -> list[NotificationMessage]:
    get_settings(db)
    q = select(NotificationMessage).order_by(NotificationMessage.sort, NotificationMessage.updated_at)
    if active_only:
        q = q.where(NotificationMessage.is_active.is_(True))
    return list(db.scalars(q))


def add_message(db: Session, kind: str, text: str) -> NotificationMessage:
    text = _check_message(kind, text)
    last = max((m.sort for m in list_messages(db)), default=-1)
    m = NotificationMessage(kind=kind, text=text, is_active=True, sort=last + 1, updated_at=now_utc())
    db.add(m)
    db.flush()
    return m


def update_message(db: Session, message_id: str, kind: str, text: str, is_active: bool) -> NotificationMessage:
    m = db.get(NotificationMessage, message_id)
    if m is None:
        raise ApiError(404, "NOT_FOUND", "文言が見つかりません")
    m.kind, m.text, m.is_active, m.updated_at = kind, _check_message(kind, text), is_active, now_utc()
    db.flush()
    return m


def render(text: str, values: dict) -> str:
    return _PH.sub(lambda mo: str(values.get(mo.group(1), mo.group(0))), text)


def payload(db: Session, today: date) -> dict:
    s = get_settings(db)
    until = today + timedelta(days=HOLIDAY_DAYS)
    return {
        "enabled": s.enabled,
        "inactive_days": s.inactive_days,
        "weekday_times": list(s.weekday_times),
        "holiday_times": list(s.holiday_times),
        "point_message_every": s.point_message_every,
        "messages": [{"message_id": m.message_id, "kind": m.kind, "text": m.text} for m in list_messages(db, active_only=True)],
        "holidays": holidays_between(today, until),
        "holidays_until": until,
        "updated_at": s.updated_at,
    }


def sample_values(display_name: str, summary: dict, streak_bonus_per_day: int, played_yesterday: bool) -> dict:
    """管理画面の見本表示用。ゲームの計算（core ReminderPlanner）と同じ意味の値。"""
    today, month = summary["today"], summary["month"]
    nxt = summary["streak_days"] + 1 if played_yesterday else 1
    return {
        "name": display_name,
        "balance": summary["balance"],
        "today_left": max(0, today["point_cap"] - today["study_points"]),
        "month_left": max(0, month["cap"] - month["earned"]),
        "streak": summary["streak_days"],
        "next_streak": nxt,
        "streak_bonus": nxt * streak_bonus_per_day,
    }
