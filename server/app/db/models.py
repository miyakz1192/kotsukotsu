"""テーブル定義（docs/04_data-model.md）。日時は UTC で保存。"""
from __future__ import annotations

import uuid
from datetime import date, datetime, timezone

from sqlalchemy import (
    DDL,
    JSON,
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    TypeDecorator,
    UniqueConstraint,
    event,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class UTCDateTime(TypeDecorator):
    """SQLite は tz を保持しないので、UTC の naive で保存し、読み出し時に UTC を付ける。"""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("timezone-aware datetime が必要です")
        return value.astimezone(timezone.utc).replace(tzinfo=None)

    def process_result_value(self, value, dialect):
        return None if value is None else value.replace(tzinfo=timezone.utc)


def new_id() -> str:
    return str(uuid.uuid4())


class Base(DeclarativeBase):
    pass


class Family(Base):
    __tablename__ = "families"
    family_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    name: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)


class User(Base):
    __tablename__ = "users"
    user_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    family_id: Mapped[str | None] = mapped_column(ForeignKey("families.family_id"))
    role: Mapped[str] = mapped_column(String)  # parent / child
    display_name: Mapped[str] = mapped_column(Text)
    login_id: Mapped[str | None] = mapped_column(String, unique=True)
    password_hash: Mapped[str | None] = mapped_column(Text)
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)


class GameTitle(Base):
    __tablename__ = "game_titles"
    game_title_id: Mapped[str] = mapped_column(String, primary_key=True)
    title: Mapped[str] = mapped_column(Text)
    latest_app_version: Mapped[str] = mapped_column(String)
    min_app_version: Mapped[str] = mapped_column(String)
    api_major_version: Mapped[int] = mapped_column(Integer, default=1)
    apk_path: Mapped[str | None] = mapped_column(Text)


class AppRelease(Base):
    """配布するAPKの版の履歴（REQ-GAME-14〜17、REQ-WEB-07）。app_id はゲームタイトルID または "launcher"。"""

    __tablename__ = "app_releases"
    release_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    app_id: Mapped[str] = mapped_column(String, index=True)
    package_name: Mapped[str] = mapped_column(String)
    version_code: Mapped[int] = mapped_column(Integer)
    version_name: Mapped[str | None] = mapped_column(String)
    min_sdk: Mapped[int | None] = mapped_column(Integer)
    sha256: Mapped[str] = mapped_column(String)
    size: Mapped[int] = mapped_column(Integer)
    cert_sha256: Mapped[str] = mapped_column(String)
    file_name: Mapped[str] = mapped_column(Text)
    uploaded_at: Mapped[datetime] = mapped_column(UTCDateTime)
    uploaded_by: Mapped[str | None] = mapped_column(ForeignKey("users.user_id"))

    __table_args__ = (UniqueConstraint("app_id", "version_code", name="uq_app_release_version"),)


class GameRegistration(Base):
    __tablename__ = "game_registrations"
    registration_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    game_title_id: Mapped[str] = mapped_column(ForeignKey("game_titles.game_title_id"))
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"))
    device_id: Mapped[str] = mapped_column(String)
    token_hash: Mapped[str] = mapped_column(String, index=True)
    registered_at: Mapped[datetime] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    last_synced_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    __table_args__ = (
        # 有効な登録は ゲーム×子供 で1件まで（REQ-GAME-06）
        Index(
            "uq_active_registration",
            "game_title_id",
            "user_id",
            unique=True,
            sqlite_where=text("revoked_at IS NULL"),
        ),
    )


class PointRuleVersion(Base):
    __tablename__ = "point_rule_versions"
    version: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    settings_json: Mapped[dict] = mapped_column(JSON)
    effective_from: Mapped[datetime] = mapped_column(UTCDateTime)
    created_by: Mapped[str | None] = mapped_column(ForeignKey("users.user_id"))
    note: Mapped[str | None] = mapped_column(Text)


class NotificationSetting(Base):
    """おしらせ通知の設定（1行、docs/06 1.7）。ポイントに影響しないので版は持たない。"""
    __tablename__ = "notification_settings"
    setting_id: Mapped[int] = mapped_column(Integer, primary_key=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    inactive_days: Mapped[int] = mapped_column(Integer)
    weekday_times: Mapped[list] = mapped_column(JSON)
    holiday_times: Mapped[list] = mapped_column(JSON)
    point_message_every: Mapped[int] = mapped_column(Integer)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)
    updated_by: Mapped[str | None] = mapped_column(ForeignKey("users.user_id"))


class NotificationMessage(Base):
    __tablename__ = "notification_messages"
    message_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    kind: Mapped[str] = mapped_column(String)  # encourage / points
    text: Mapped[str] = mapped_column(Text)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    sort: Mapped[int] = mapped_column(Integer, default=0)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime)


class PlaySession(Base):
    __tablename__ = "play_sessions"
    play_id: Mapped[str] = mapped_column(String, primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    game_title_id: Mapped[str] = mapped_column(ForeignKey("game_titles.game_title_id"))
    registration_id: Mapped[str] = mapped_column(ForeignKey("game_registrations.registration_id"))
    mode: Mapped[str] = mapped_column(String)
    dan: Mapped[int | None] = mapped_column(Integer)
    started_at_device: Mapped[datetime] = mapped_column(UTCDateTime)
    ended_at_device: Mapped[datetime] = mapped_column(UTCDateTime)
    received_at: Mapped[datetime] = mapped_column(UTCDateTime)
    was_offline: Mapped[bool] = mapped_column(Boolean)
    play_date: Mapped[date] = mapped_column(Date)
    clock_suspect: Mapped[bool] = mapped_column(Boolean, default=False)
    client_rule_version: Mapped[int | None] = mapped_column(Integer)
    server_rule_version: Mapped[int] = mapped_column(Integer)
    correct_count: Mapped[int] = mapped_column(Integer)
    app_version: Mapped[str | None] = mapped_column(String)

    __table_args__ = (Index("ix_play_user_date", "user_id", "play_date"),)


class Answer(Base):
    __tablename__ = "answers"
    # 回答順（受信順）を表す代理キー。自己ベスト判定の「直近27問」に使う
    answer_id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    play_id: Mapped[str] = mapped_column(ForeignKey("play_sessions.play_id"))
    seq: Mapped[int] = mapped_column(Integer)
    user_id: Mapped[str] = mapped_column(String)  # 非正規化（自己ベスト判定用）
    game_title_id: Mapped[str] = mapped_column(String)  # 非正規化
    a: Mapped[int] = mapped_column(Integer)
    b: Mapped[int] = mapped_column(Integer)
    answer: Mapped[int | None] = mapped_column(Integer)
    is_correct: Mapped[bool] = mapped_column(Boolean)
    elapsed_ms: Mapped[int | None] = mapped_column(Integer)
    over_cap: Mapped[bool] = mapped_column(Boolean, default=False)

    __table_args__ = (
        UniqueConstraint("play_id", "seq", name="uq_answer_play_seq"),
        Index("ix_answers_user_dan", "user_id", "game_title_id", "a", "answer_id"),
    )


class LedgerEntry(Base):
    """お財布の台帳。追記のみ（原則2）。UPDATE／DELETE は DB トリガーで拒否する。"""

    __tablename__ = "ledger_entries"
    entry_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    child_user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    type: Mapped[str] = mapped_column(String)  # study/streak_bonus/self_best_bonus/withdrawal/adjustment
    points: Mapped[int] = mapped_column(Integer)
    play_id: Mapped[str | None] = mapped_column(ForeignKey("play_sessions.play_id"), index=True)
    # 引き出しは transaction_id、調整は adjustment_id を入れる（冪等キー）
    transaction_id: Mapped[str | None] = mapped_column(String, unique=True)
    actor_user_id: Mapped[str | None] = mapped_column(ForeignKey("users.user_id"))
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime)
    recorded_at: Mapped[datetime] = mapped_column(UTCDateTime)
    play_date: Mapped[date | None] = mapped_column(Date)
    note: Mapped[str | None] = mapped_column(Text)

    __table_args__ = (Index("ix_ledger_child_date", "child_user_id", "play_date"),)


LEDGER_TRIGGERS = [
    """CREATE TRIGGER ledger_no_update BEFORE UPDATE ON ledger_entries
       BEGIN SELECT RAISE(ABORT, 'ledger_entries is append-only'); END""",
    """CREATE TRIGGER ledger_no_delete BEFORE DELETE ON ledger_entries
       BEGIN SELECT RAISE(ABORT, 'ledger_entries is append-only'); END""",
]
for _ddl in LEDGER_TRIGGERS:
    event.listen(LedgerEntry.__table__, "after_create", DDL(_ddl))


class SelfBestAchievement(Base):
    __tablename__ = "self_best_achievements"
    child_user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), primary_key=True)
    game_title_id: Mapped[str] = mapped_column(String, primary_key=True)
    dan: Mapped[int] = mapped_column(Integer, primary_key=True)
    threshold: Mapped[int] = mapped_column(Integer, primary_key=True)
    achieved_at: Mapped[datetime] = mapped_column(UTCDateTime)
    play_id: Mapped[str] = mapped_column(ForeignKey("play_sessions.play_id"))


class ApiToken(Base):
    """親のログインセッション。kind=web（管理画面Cookie）／api（親用アプリのBearer）。"""

    __tablename__ = "api_tokens"
    token_id: Mapped[str] = mapped_column(String, primary_key=True, default=new_id)
    token_hash: Mapped[str] = mapped_column(String, unique=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.user_id"), index=True)
    kind: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
