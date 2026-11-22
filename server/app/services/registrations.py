from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.clock import now_utc
from app.db.models import GameRegistration, GameTitle, User
from app.errors import ApiError, invalid, unauthorized
from app.security import new_token, token_hash


def version_tuple(v: str) -> tuple[int, ...]:
    try:
        return tuple(int(x) for x in v.strip().split("."))
    except ValueError:
        raise invalid(f"バージョンの形式が不正です: {v}")


def check_app_version(title: GameTitle, app_version: str | None) -> None:
    if not app_version:
        raise invalid("X-App-Version ヘッダが必要です")
    if version_tuple(app_version) < version_tuple(title.min_app_version):
        raise ApiError(426, "APP_UPDATE_REQUIRED", "アプリを更新してください")


def register(db: Session, game_title_id: str, child: User, device_id: str) -> tuple[GameRegistration, str]:
    title = db.get(GameTitle, game_title_id)
    if not title:
        raise invalid("未知のゲームタイトルIDです")
    if child.role != "child" or not child.is_active:
        raise invalid("有効な子供ではありません")
    token = new_token()
    reg = GameRegistration(
        game_title_id=game_title_id, user_id=child.user_id, device_id=device_id,
        token_hash=token_hash(token), registered_at=now_utc(),
    )
    try:
        with db.begin_nested():
            db.add(reg)
            db.flush()
    except IntegrityError:
        raise ApiError(409, "DUPLICATE_REGISTRATION", "このゲームにはすでに登録されています")
    return reg, token


def authenticate_device(db: Session, token: str | None) -> GameRegistration:
    if not token:
        raise unauthorized()
    reg = db.scalars(select(GameRegistration).where(GameRegistration.token_hash == token_hash(token))).first()
    if not reg or reg.revoked_at is not None:
        raise unauthorized("登録が解除されています")
    return reg


def revoke(db: Session, registration_id: str) -> None:
    reg = db.get(GameRegistration, registration_id)
    if not reg:
        raise ApiError(404, "NOT_FOUND", "登録が見つかりません")
    if reg.revoked_at is None:
        reg.revoked_at = now_utc()


def list_registrations(db: Session, include_revoked: bool = True):
    q = select(GameRegistration).order_by(GameRegistration.registered_at.desc())
    if not include_revoked:
        q = q.where(GameRegistration.revoked_at.is_(None))
    return list(db.scalars(q))
