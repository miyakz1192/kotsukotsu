from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.clock import now_utc
from app.db.models import Family, User
from app.errors import ApiError, invalid
from app.security import hash_password
from app.services.auth import validate_new_password


def get_family(db: Session) -> Family | None:
    return db.scalars(select(Family)).first()


def create_family(db: Session, parent: User, name: str) -> Family:
    """家族は1つだけ（REQ-USR-02）。作成者（初期管理者）はその家族の親になる（REQ-USR-05）。"""
    name = name.strip()
    if not name:
        raise invalid("家族の名前を入力してください")
    if get_family(db):
        raise ApiError(409, "FAMILY_ALREADY_EXISTS", "家族はすでに作成されています")
    fam = Family(name=name, created_at=now_utc())
    db.add(fam)
    db.flush()
    parent.family_id = fam.family_id
    parent.updated_at = now_utc()
    return fam


def members(db: Session, role: str | None = None, active_only: bool = False) -> list[User]:
    q = select(User).where(User.family_id.is_not(None))
    if role:
        q = q.where(User.role == role)
    if active_only:
        q = q.where(User.is_active.is_(True))
    return list(db.scalars(q.order_by(User.created_at, User.display_name)))


def active_children(db: Session) -> list[User]:
    return members(db, "child", active_only=True)


def get_child(db: Session, user_id: str) -> User:
    u = db.get(User, user_id)
    if not u or u.role != "child" or u.family_id is None:
        raise ApiError(404, "NOT_FOUND", "子供が見つかりません")
    return u


def add_child(db: Session, family: Family, display_name: str) -> User:
    display_name = display_name.strip()
    if not display_name:
        raise invalid("表示名を入力してください")
    now = now_utc()
    u = User(family_id=family.family_id, role="child", display_name=display_name, created_at=now, updated_at=now)
    db.add(u)
    db.flush()
    return u


def add_parent(db: Session, family: Family, display_name: str, login_id: str, initial_password: str) -> User:
    display_name, login_id = display_name.strip(), login_id.strip()
    if not display_name or not login_id:
        raise invalid("表示名とログインIDを入力してください")
    validate_new_password(initial_password)
    now = now_utc()
    u = User(
        family_id=family.family_id, role="parent", display_name=display_name, login_id=login_id,
        password_hash=hash_password(initial_password), must_change_password=True, created_at=now, updated_at=now,
    )
    db.add(u)
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, "LOGIN_ID_TAKEN", "そのログインIDはすでに使われています")
    return u


def update_user(db: Session, user: User, display_name: str | None = None, login_id: str | None = None) -> None:
    """ログインIDの変更は親だけ（原則8）。記録の紐づけは user_id なので影響しない。"""
    if display_name is not None:
        if not display_name.strip():
            raise invalid("表示名を入力してください")
        user.display_name = display_name.strip()
    if login_id is not None and login_id != user.login_id:
        if user.role != "parent":
            raise invalid("子供にログインIDはありません")
        if not login_id.strip():
            raise invalid("ログインIDを入力してください")
        user.login_id = login_id.strip()
    user.updated_at = now_utc()
    try:
        db.flush()
    except IntegrityError:
        db.rollback()
        raise ApiError(409, "LOGIN_ID_TAKEN", "そのログインIDはすでに使われています")


def set_active(db: Session, user: User, active: bool, acting: User) -> None:
    if not active and user.user_id == acting.user_id:
        raise invalid("自分自身は無効にできません")
    if not active and user.role == "parent":
        others = [p for p in members(db, "parent", active_only=True) if p.user_id != user.user_id]
        if not others:
            raise invalid("有効な親が1人もいなくなるため無効にできません")
    user.is_active = active
    user.updated_at = now_utc()
