from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.clock import now_utc
from app.db.models import PointRuleVersion
from app.domain.points import PointRules


def to_rules(row: PointRuleVersion) -> PointRules:
    r = PointRules(**row.settings_json)
    r.version = row.version
    return r


def current_row(db: Session) -> PointRuleVersion:
    return db.scalars(select(PointRuleVersion).order_by(PointRuleVersion.version.desc()).limit(1)).one()


def current_rules(db: Session) -> PointRules:
    return to_rules(current_row(db))


def rules_for_response(rules: PointRules) -> dict:
    return rules.model_dump()


def create_version(db: Session, settings: dict, actor_id: str | None, note: str | None) -> PointRuleVersion:
    settings = {k: v for k, v in settings.items() if k != "version"}
    PointRules(**settings)  # 検証（不正なら ValidationError）
    row = PointRuleVersion(settings_json=settings, effective_from=now_utc(), created_by=actor_id, note=note)
    db.add(row)
    db.flush()
    return row


def history(db: Session) -> list[PointRuleVersion]:
    return list(db.scalars(select(PointRuleVersion).order_by(PointRuleVersion.version.desc())))
