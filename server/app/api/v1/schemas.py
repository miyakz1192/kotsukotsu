from __future__ import annotations

from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field


class Model(BaseModel):
    model_config = ConfigDict(extra="ignore")


class ErrorDetail(BaseModel):
    code: str
    message: str


class ErrorResponse(BaseModel):
    error: ErrorDetail


class Child(BaseModel):
    user_id: str
    display_name: str


class ChildrenResponse(BaseModel):
    children: list[Child]


class RegistrationRequest(Model):
    game_title_id: str
    user_id: str
    device_id: str = Field(min_length=1)


class SelfBestThresholdModel(BaseModel):
    rate: int
    bonus: int


class SelfBestModel(BaseModel):
    window: int
    thresholds: list[SelfBestThresholdModel]


class GraduationModel(BaseModel):
    """卒業した段（docs/03 5.2.1）。null なら卒業なし。"""

    modes: list[str]
    rate: int
    points_per_correct: int


class SettingsModel(BaseModel):
    version: int
    timezone: str
    points_per_correct: dict[str, int]
    streak_bonus_per_day: int
    daily_point_cap: int
    daily_question_cap: int
    monthly_cap: int
    self_best: SelfBestModel
    graduation: GraduationModel | None = None


class RegistrationResponse(BaseModel):
    registration_id: str
    device_token: str
    settings: SettingsModel


class TodayModel(BaseModel):
    questions: int
    question_cap: int
    study_points: int
    point_cap: int
    first_play_done: bool


class MonthModel(BaseModel):
    earned: int
    cap: int


class SelfBestAchieved(BaseModel):
    dan: int
    threshold: int


class SummaryResponse(BaseModel):
    as_of: datetime
    streak_days: int
    cumulative_accuracy: float
    balance: int
    today: TodayModel
    month: MonthModel
    dan_recent: dict[str, list[bool]]
    self_best_achieved: list[SelfBestAchieved]
    next_sequential_dan: int
    settings_version: int


class AnswerModel(Model):
    seq: int
    a: int
    b: int
    answer: int | None = None
    is_correct: bool | None = None  # 参考値。サーバは answer から再計算する
    elapsed_ms: int | None = None


class PlaySessionRequest(Model):
    play_id: str = Field(min_length=8, max_length=64)
    mode: str
    dan: int | None = None
    started_at_device: datetime
    ended_at_device: datetime
    was_offline: bool = False
    client_rule_version: int | None = None
    answers: list[AnswerModel]


class Awarded(BaseModel):
    study: int
    streak_bonus: int
    self_best_bonus: int


class PlaySessionResponse(BaseModel):
    play_id: str
    play_date: date
    awarded: Awarded
    self_best_new: list[SelfBestAchieved]
    summary: SummaryResponse


class LoginRequest(Model):
    login_id: str
    password: str


class LoginResponse(BaseModel):
    token: str
    must_change_password: bool


class PasswordRequest(Model):
    current_password: str
    new_password: str


class ChildCard(BaseModel):
    user_id: str
    display_name: str
    is_active: bool
    balance: int
    streak_days: int
    month_earned: int
    month_cap: int
    month_remaining: int
    cumulative_accuracy: float
    last_play_at: datetime | None
    as_of: datetime


class ChildrenSummaryResponse(BaseModel):
    children: list[ChildCard]


class LedgerEntryModel(BaseModel):
    entry_id: str
    type: str
    points: int
    play_id: str | None
    transaction_id: str | None
    actor_user_id: str | None
    occurred_at: datetime
    recorded_at: datetime
    play_date: date | None
    note: str | None


class LedgerResponse(BaseModel):
    entries: list[LedgerEntryModel]
    balance: int


class AnswerOut(BaseModel):
    seq: int
    a: int
    b: int
    answer: int | None
    is_correct: bool
    elapsed_ms: int | None
    over_cap: bool


class PlayOut(BaseModel):
    play_id: str
    game_title_id: str
    mode: str
    dan: int | None
    started_at_device: datetime
    ended_at_device: datetime
    received_at: datetime
    was_offline: bool
    play_date: date
    clock_suspect: bool
    correct_count: int
    server_rule_version: int
    answers: list[AnswerOut]


class PlaysResponse(BaseModel):
    plays: list[PlayOut]


class WithdrawalRequest(Model):
    transaction_id: str = Field(min_length=8, max_length=64)
    child_user_id: str
    points: int = Field(gt=0)
    occurred_at_device: datetime | None = None
    was_offline: bool = False
    note: str | None = Field(None, max_length=200, description="メモ（例 おかし）。省略可")


class WithdrawalResponse(BaseModel):
    transaction_id: str
    recorded_at: datetime
    balance: int


class AdjustmentRequest(Model):
    adjustment_id: str = Field(min_length=8, max_length=64)
    child_user_id: str
    points: int
    note: str | None = None
    occurred_at_device: datetime | None = Field(None, description="was_offline=true のとき台帳の発生時刻に使う（親用アプリの外出先での記録）")
    was_offline: bool = False


class AdjustmentResponse(BaseModel):
    adjustment_id: str
    recorded_at: datetime
    balance: int


class AppItem(BaseModel):
    """ランチャーが表示・更新するアプリ（最新版が登録されているものだけ）。"""

    app_id: str = Field(description="ゲームタイトルID、またはランチャー自身は launcher")
    kind: str = Field(description="game / launcher / parent（親用アプリ）")
    title: str
    package_name: str
    version_code: int
    version_name: str | None
    min_sdk: int | None
    sha256: str = Field(description="APKファイル全体の SHA-256（16進小文字）。ダウンロード後に照合する")
    size: int
    download_path: str = Field(description="サーバのアドレスからの相対パス（例 api/v1/apps/kuku-challenge/releases/3/apk）")


class AppsResponse(BaseModel):
    apps: list[AppItem]


class NotificationMessageItem(BaseModel):
    message_id: str
    kind: str = Field(description="encourage（はげまし）／points（ポイント入り）")
    text: str = Field(description="差し込み {name} {balance} {today_left} {month_left} {streak} {next_streak} {streak_bonus} を含むことがある")


class NotificationSettingsResponse(BaseModel):
    enabled: bool
    inactive_days: int = Field(description="今日を含めて何日やっていなければ通知するか")
    weekday_times: list[str] = Field(description="平日の通知時刻 HH:MM（日本時間、昇順、0〜3件）")
    holiday_times: list[str] = Field(description="土日祝の通知時刻")
    point_message_every: int = Field(description="何回に1回ポイント入りにするか（0＝出さない）")
    messages: list[NotificationMessageItem]
    holidays: list[date] = Field(description="今日から holidays_until までの祝日・振替休日・国民の休日")
    holidays_until: date
    updated_at: datetime
