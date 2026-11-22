from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Header, Request
from sqlalchemy.orm import Session

from app.db.models import GameRegistration, GameTitle, User
from app.errors import ApiError, forbidden, invalid, unauthorized
from app.services import auth, registrations


def get_db(request: Request) -> Iterator[Session]:
    db = request.app.state.session_factory()
    try:
        yield db
    finally:
        db.close()


def _bearer(authorization: str | None) -> str | None:
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:].strip()
    return None


def parent_any(db: Session = Depends(get_db), authorization: str | None = Header(default=None)) -> User:
    """親のBearerトークン。パスワード変更前でも通る（/auth/password 用）。"""
    return auth.authenticate(db, _bearer(authorization), kind="api")


def parent_user(user: User = Depends(parent_any)) -> User:
    if user.must_change_password:
        raise ApiError(403, "PASSWORD_CHANGE_REQUIRED", "先にパスワードを変更してください")
    if user.family_id is None:
        raise forbidden("先に管理画面で家族を作成してください")
    return user


class GameContext:
    def __init__(self, reg: GameRegistration, title: GameTitle, app_version: str):
        self.reg, self.title, self.app_version = reg, title, app_version


def game_ctx(
    db: Session = Depends(get_db),
    authorization: str | None = Header(default=None),
    x_game_title_id: str | None = Header(default=None),
    x_app_version: str | None = Header(default=None),
) -> GameContext:
    reg = registrations.authenticate_device(db, _bearer(authorization))
    if x_game_title_id and x_game_title_id != reg.game_title_id:
        raise invalid("X-Game-Title-Id が登録と一致しません")
    title = db.get(GameTitle, reg.game_title_id)
    registrations.check_app_version(title, x_app_version)
    return GameContext(reg, title, x_app_version)


def game_title_headers(
    db: Session = Depends(get_db),
    x_game_title_id: str | None = Header(default=None),
    x_app_version: str | None = Header(default=None),
) -> GameTitle:
    if not x_game_title_id:
        raise invalid("X-Game-Title-Id ヘッダが必要です")
    title = db.get(GameTitle, x_game_title_id)
    if not title:
        raise invalid("未知のゲームタイトルIDです")
    registrations.check_app_version(title, x_app_version)
    return title
