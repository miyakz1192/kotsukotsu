from __future__ import annotations

from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import config
from app.clock import now_utc
from app.db.models import ApiToken, User
from app.errors import ApiError, invalid, unauthorized
from app.security import hash_password, new_token, token_hash, verify_password

MIN_PASSWORD_LEN = 8


def login(db: Session, login_id: str, password: str, kind: str) -> tuple[str, User]:
    user = db.scalars(select(User).where(User.login_id == login_id)).first()
    ok = verify_password(password, user.password_hash if user else None)
    if not user or not ok or not user.is_active or user.role != "parent":
        raise unauthorized("ログインIDまたはパスワードが違います")
    token = new_token()
    now = now_utc()
    ttl = timedelta(hours=config.WEB_SESSION_HOURS) if kind == "web" else timedelta(days=config.API_TOKEN_DAYS)
    db.add(ApiToken(token_hash=token_hash(token), user_id=user.user_id, kind=kind, created_at=now, expires_at=now + ttl))
    db.flush()
    return token, user


def authenticate(db: Session, token: str | None, kind: str | None = None) -> User:
    if not token:
        raise unauthorized()
    row = db.scalars(select(ApiToken).where(ApiToken.token_hash == token_hash(token))).first()
    if not row or row.revoked_at is not None or row.expires_at <= now_utc() or (kind and row.kind != kind):
        raise unauthorized()
    user = db.get(User, row.user_id)
    if not user or not user.is_active or user.role != "parent":
        raise unauthorized()
    return user


def revoke(db: Session, token: str) -> None:
    row = db.scalars(select(ApiToken).where(ApiToken.token_hash == token_hash(token))).first()
    if row and row.revoked_at is None:
        row.revoked_at = now_utc()


def change_password(db: Session, user: User, current: str, new: str) -> None:
    if not verify_password(current, user.password_hash):
        raise ApiError(401, "UNAUTHORIZED", "現在のパスワードが違います")
    validate_new_password(new, current)
    user.password_hash = hash_password(new)
    user.must_change_password = False
    user.updated_at = now_utc()


def validate_new_password(new: str, current: str | None = None) -> None:
    if len(new) < MIN_PASSWORD_LEN:
        raise invalid(f"パスワードは{MIN_PASSWORD_LEN}文字以上にしてください")
    if current is not None and new == current:
        raise invalid("新しいパスワードは現在のものと違うものにしてください")
