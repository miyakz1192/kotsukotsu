"""ポイント計算エンジン（純粋関数）。docs/03_point-rules.md 4〜5章。

I/O を持たない。DB から作った PriorState と、これから処理するプレイを受け取り、
付与するポイントと各回答の over_cap を返す。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta

from .rules import PointRules


@dataclass(frozen=True)
class AnswerIn:
    a: int
    b: int
    is_correct: bool


@dataclass(frozen=True)
class PlayInput:
    mode: str
    play_date: date
    answers: list[AnswerIn]


@dataclass
class PriorState:
    """このプレイを処理する直前の、その子供の状態。"""

    day_questions: int = 0  # プレイ日の回答数（over_cap を除く）
    day_study_points: int = 0  # プレイ日の正解ポイント実績
    month_earned: int = 0  # プレイ日の月の獲得合計（正解＋連続＋自己ベスト）
    played_dates: set[date] = field(default_factory=set)  # 過去にプレイがある日（プレイ日を含んでよい）
    # 段ごとの回答履歴（回答順、over_cap 除く）。直近 window 件以上を含めること
    dan_history: dict[int, list[bool]] = field(default_factory=dict)
    achieved: set[tuple[int, int]] = field(default_factory=set)  # (dan, rate)


@dataclass(frozen=True)
class SelfBestAward:
    dan: int
    threshold: int  # rate
    bonus: int  # 月の上限で切り詰めた後の実付与額


@dataclass
class PlayResult:
    study: int
    streak_bonus: int
    self_best: list[SelfBestAward]
    over_cap: list[bool]  # answers と同じ順序
    is_first_play_of_day: bool
    streak_days: int  # 連続日数（初プレイでなければ 0）

    @property
    def self_best_total(self) -> int:
        return sum(s.bonus for s in self.self_best)

    @property
    def total(self) -> int:
        return self.study + self.streak_bonus + self.self_best_total


def compute_streak(play_date: date, played_dates: set[date]) -> int:
    """play_date を含み、同じ月の中で1日ずつさかのぼって連続する日数（毎月1日にリセット）。"""
    n = 1
    d = play_date - timedelta(days=1)
    while d.month == play_date.month and d in played_dates:
        n += 1
        d -= timedelta(days=1)
    return n


def compute_current_streak(today: date, played_dates: set[date]) -> int:
    """表示用の現在の連続日数。今日プレイ済みなら今日まで、未プレイなら昨日まで（同じ月のみ）。"""
    if today in played_dates:
        return compute_streak(today, played_dates)
    y = today - timedelta(days=1)
    if y.month == today.month and y in played_dates:
        return compute_streak(y, played_dates)
    return 0


def process_play(rules: PointRules, prior: PriorState, play: PlayInput) -> PlayResult:
    per_mode = rules.points_per_correct[play.mode]
    grad = rules.graduation
    graduated: set[int] = set()  # 5.2.1 プレイの処理前に卒業済みの段
    if grad is not None and play.mode in grad.modes:
        graduated = {dan for dan, rate in prior.achieved if rate == grad.rate}
    day_q = prior.day_questions
    day_pts = prior.day_study_points
    month = prior.month_earned

    # 5.1 問題数上限 / 5.2 正解ポイント
    over_cap: list[bool] = []
    study = 0
    for ans in play.answers:
        if day_q >= rules.daily_question_cap:
            over_cap.append(True)
            continue
        over_cap.append(False)
        day_q += 1
        if ans.is_correct:
            per = grad.points_per_correct if ans.a in graduated else per_mode
            room_day = rules.daily_point_cap - day_pts
            room_month = rules.monthly_cap - month
            pts = max(0, min(per, room_day, room_month))
            day_pts += pts
            month += pts
            study += pts

    # 5.3 連続日数ボーナス（その日の最初のプレイのみ）
    first = play.play_date not in prior.played_dates
    streak_days = 0
    streak_bonus = 0
    if first:
        streak_days = compute_streak(play.play_date, prior.played_dates)
        want = streak_days * rules.streak_bonus_per_day
        streak_bonus = max(0, min(want, rules.monthly_cap - month))
        month += streak_bonus

    # 5.4 自己ベストボーナス
    awards: list[SelfBestAward] = []
    dans = sorted({a.a for a, o in zip(play.answers, over_cap) if not o})
    window = rules.self_best.window
    for dan in dans:
        hist = list(prior.dan_history.get(dan, []))
        hist += [a.is_correct for a, o in zip(play.answers, over_cap) if not o and a.a == dan]
        if len(hist) < window:
            continue
        recent = hist[-window:]
        correct = sum(recent)
        for th in rules.self_best.thresholds:  # rate の昇順
            if correct * 100 >= th.rate * window and (dan, th.rate) not in prior.achieved:
                bonus = max(0, min(th.bonus, rules.monthly_cap - month))
                month += bonus
                awards.append(SelfBestAward(dan, th.rate, bonus))

    return PlayResult(study, streak_bonus, awards, over_cap, first, streak_days)
