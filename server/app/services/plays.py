"""プレイ結果の受信と確定計算（docs/03 3〜5章）。台帳記録とプレイ保存は同一トランザクション（REQ-NF-04）。"""
from __future__ import annotations

import threading
from dataclasses import dataclass
from datetime import date, datetime

from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.clock import now_utc, to_tokyo_date
from app.db.models import Answer, GameRegistration, LedgerEntry, PlaySession, SelfBestAchievement
from app.domain.points import AnswerIn, PlayInput, PointRules, PriorState, process_play
from app.errors import invalid
from app.services import stats

# 同じ子供のプレイは受信順に1件ずつ処理する（docs/03 4章）。単一プロセス前提
_play_lock = threading.Lock()


@dataclass
class AnswerReq:
    seq: int
    a: int
    b: int
    answer: int | None
    elapsed_ms: int | None = None


@dataclass
class PlayReq:
    play_id: str
    mode: str
    dan: int | None
    started_at_device: datetime
    ended_at_device: datetime
    was_offline: bool
    client_rule_version: int | None
    answers: list[AnswerReq]


@dataclass
class PlayOutcome:
    play_id: str
    play_date: date
    study: int
    streak_bonus: int
    self_best_bonus: int
    self_best_new: list[dict]
    created: bool


def determine_play_date(req: PlayReq, received_at: datetime, last_synced_at: datetime) -> tuple[date, bool]:
    """docs/03 3章。戻り値は (プレイ日, clock_suspect)。"""
    if not req.was_offline:
        return to_tokyo_date(received_at), False
    if last_synced_at <= req.ended_at_device <= received_at:
        return to_tokyo_date(req.ended_at_device), False
    return to_tokyo_date(received_at), True


def validate(req: PlayReq, rules: PointRules) -> None:
    if req.mode not in rules.points_per_correct:
        raise invalid("mode が不正です")
    if not 1 <= len(req.answers) <= 9:
        raise invalid("answers は1〜9件にしてください")
    if sorted(a.seq for a in req.answers) != list(range(1, len(req.answers) + 1)):
        raise invalid("answers.seq は 1 からの連番にしてください")
    for a in req.answers:
        if not (1 <= a.a <= 9 and 1 <= a.b <= 9):
            raise invalid("問題は 1〜9 の九九の範囲にしてください")
    if req.mode == "challenge":
        if req.dan is not None:
            raise invalid("チャレンジでは dan は指定できません")
    else:
        if req.dan is None or not 1 <= req.dan <= 9:
            raise invalid("dan が必要です（1〜9）")
        if any(a.a != req.dan for a in req.answers):
            raise invalid("段の問題と dan が一致しません")


def _outcome_from_db(db: Session, play: PlaySession) -> PlayOutcome:
    sums = dict(db.execute(
        select(LedgerEntry.type, func.sum(LedgerEntry.points)).where(LedgerEntry.play_id == play.play_id).group_by(LedgerEntry.type)
    ).all())
    new = db.execute(
        select(SelfBestAchievement.dan, SelfBestAchievement.threshold).where(SelfBestAchievement.play_id == play.play_id)
        .order_by(SelfBestAchievement.dan, SelfBestAchievement.threshold)
    ).all()
    return PlayOutcome(
        play.play_id, play.play_date, sums.get("study", 0), sums.get("streak_bonus", 0), sums.get("self_best_bonus", 0),
        [{"dan": d, "threshold": t} for d, t in new], created=False,
    )


def submit_play(db: Session, reg: GameRegistration, req: PlayReq, rules: PointRules, app_version: str | None) -> PlayOutcome:
    """プレイ結果を保存し、ポイントを確定する。同じ play_id の再送は最初の結果を返す（原則3）。
    この関数の中で commit する。"""
    validate(req, rules)
    with _play_lock:
        existing = db.get(PlaySession, req.play_id)
        if existing:
            if existing.user_id != reg.user_id:
                raise invalid("この play_id は別のユーザのものです")
            return _outcome_from_db(db, existing)

        child_id = reg.user_id
        gid = reg.game_title_id
        received_at = now_utc()
        last_sync = reg.last_synced_at or reg.registered_at
        play_date, suspect = determine_play_date(req, received_at, last_sync)

        answers_in = [AnswerIn(a.a, a.b, a.answer == a.a * a.b) for a in sorted(req.answers, key=lambda x: x.seq)]
        window = rules.self_best.window
        dans = {a.a for a in answers_in}
        prior = PriorState(
            day_questions=stats.day_questions(db, child_id, play_date),
            day_study_points=stats.day_study_points(db, child_id, play_date),
            month_earned=stats.month_earned(db, child_id, play_date),
            played_dates=stats.played_dates(db, child_id),
            dan_history={d: stats.dan_recent(db, child_id, gid, d, window) for d in dans},
            achieved=stats.achieved(db, child_id, gid),
        )
        result = process_play(rules, prior, PlayInput(req.mode, play_date, answers_in))

        occurred = received_at if suspect or not req.was_offline else req.ended_at_device
        play = PlaySession(
            play_id=req.play_id, user_id=child_id, game_title_id=gid, registration_id=reg.registration_id,
            mode=req.mode, dan=req.dan, started_at_device=req.started_at_device, ended_at_device=req.ended_at_device,
            received_at=received_at, was_offline=req.was_offline, play_date=play_date, clock_suspect=suspect,
            client_rule_version=req.client_rule_version, server_rule_version=rules.version,
            correct_count=sum(1 for a in answers_in if a.is_correct), app_version=app_version,
        )
        try:
            with db.begin_nested():
                db.add(play)
                db.flush()
        except IntegrityError:
            existing = db.get(PlaySession, req.play_id)
            if existing is None:
                raise
            return _outcome_from_db(db, existing)

        for a, ai, over in zip(sorted(req.answers, key=lambda x: x.seq), answers_in, result.over_cap):
            db.add(Answer(
                play_id=req.play_id, seq=a.seq, user_id=child_id, game_title_id=gid, a=a.a, b=a.b, answer=a.answer,
                is_correct=ai.is_correct, elapsed_ms=a.elapsed_ms, over_cap=over,
            ))

        def add_entry(typ: str, pts: int, note: str | None = None):
            if pts > 0:
                db.add(LedgerEntry(
                    child_user_id=child_id, type=typ, points=pts, play_id=req.play_id, occurred_at=occurred,
                    recorded_at=received_at, play_date=play_date, note=note,
                ))

        add_entry("study", result.study)
        add_entry("streak_bonus", result.streak_bonus, f"連続{result.streak_days}日" if result.streak_days else None)
        for s in result.self_best:
            add_entry("self_best_bonus", s.bonus, f"{s.dan}のだん {s.threshold}%")
            db.add(SelfBestAchievement(
                child_user_id=child_id, game_title_id=gid, dan=s.dan, threshold=s.threshold,
                achieved_at=received_at, play_id=req.play_id,
            ))
        if not req.was_offline:
            reg.last_synced_at = received_at
        db.commit()  # 次のプレイの集計が確定値を読めるよう、ロックを持ったままコミットする
        return PlayOutcome(
            req.play_id, play_date, result.study, result.streak_bonus, result.self_best_total,
            [{"dan": s.dan, "threshold": s.threshold} for s in result.self_best], created=True,
        )


def list_plays(db: Session, child_id: str, d_from: date | None, d_to: date | None):
    q = select(PlaySession).where(PlaySession.user_id == child_id)
    if d_from:
        q = q.where(PlaySession.play_date >= d_from)
    if d_to:
        q = q.where(PlaySession.play_date <= d_to)
    return list(db.scalars(q.order_by(PlaySession.received_at.desc())))


def answers_of(db: Session, play_id: str):
    return list(db.scalars(select(Answer).where(Answer.play_id == play_id).order_by(Answer.seq)))
