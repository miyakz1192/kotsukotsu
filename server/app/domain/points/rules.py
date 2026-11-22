"""ポイントルールの設定値（docs/03_point-rules.md 1章）。

実行時の値は DB の point_rule_versions から読む。ここにある DEFAULT_RULES_JSON は
初回起動時に版1として DB に投入するための初期値であり、計算ロジックは参照しない。
"""
from __future__ import annotations

from pydantic import BaseModel, Field, model_validator

MODES = ("training_sequential", "training_select", "challenge")


class SelfBestThreshold(BaseModel):
    rate: int = Field(ge=1, le=100)
    bonus: int = Field(ge=0)


class SelfBest(BaseModel):
    window: int = Field(ge=1)
    thresholds: list[SelfBestThreshold]


class Graduation(BaseModel):
    """卒業した段（docs/03 5.2.1）。modes のモードで、rate の自己ベストを達成済みの段は単価を points_per_correct にする。"""

    modes: list[str]
    rate: int = Field(ge=1, le=100)
    points_per_correct: int = Field(ge=0)


class PointRules(BaseModel):
    version: int = 0  # DB 側の版番号で上書きする
    timezone: str = "Asia/Tokyo"
    points_per_correct: dict[str, int]
    streak_bonus_per_day: int = Field(ge=0)
    daily_point_cap: int = Field(ge=0)
    daily_question_cap: int = Field(ge=0)
    monthly_cap: int = Field(ge=0)
    self_best: SelfBest
    graduation: Graduation | None = None  # 省略・null なら卒業なし（2026-11-13 以前の版）

    @model_validator(mode="after")
    def _check(self) -> "PointRules":
        for m in MODES:
            if m not in self.points_per_correct or self.points_per_correct[m] < 0:
                raise ValueError(f"points_per_correct.{m} が必要です（0以上）")
        rates = [t.rate for t in self.self_best.thresholds]
        if rates != sorted(set(rates)):
            raise ValueError("self_best.thresholds は rate の昇順・重複なしにしてください")
        if self.graduation is not None:
            bad = [m for m in self.graduation.modes if m not in MODES]
            if bad:
                raise ValueError(f"graduation.modes に不明なモードがあります: {bad}")
            if self.graduation.rate not in rates:
                raise ValueError("graduation.rate は自己ベストの到達点（正解率）のどれかと同じにしてください")
        return self


DEFAULT_RULES_JSON = {
    "timezone": "Asia/Tokyo",
    "points_per_correct": {"training_sequential": 1, "training_select": 1, "challenge": 2},
    "streak_bonus_per_day": 1,
    "daily_point_cap": 40,
    "daily_question_cap": 117,
    "monthly_cap": 1500,
    "self_best": {
        "window": 27,
        "thresholds": [
            {"rate": 80, "bonus": 10},
            {"rate": 90, "bonus": 20},
            {"rate": 100, "bonus": 30},
        ],
    },
    "graduation": {"modes": ["training_select"], "rate": 100, "points_per_correct": 0},
}
