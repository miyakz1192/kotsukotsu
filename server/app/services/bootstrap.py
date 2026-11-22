"""初回起動処理（REQ-USR-03/04）。"""
from __future__ import annotations

import sys

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.clock import now_utc
from app.db.models import GameTitle, PointRuleVersion, User
from app.domain.points import DEFAULT_RULES_JSON
from app.security import generate_password, hash_password

INITIAL_ADMIN_LOGIN_ID = "admin"


def seed(db: Session, out=sys.stdout) -> str | None:
    """不足している初期データを投入する。初期管理者を作ったときだけ初期パスワードを返す。"""
    if db.scalar(select(func.count()).select_from(PointRuleVersion)) == 0:
        db.add(PointRuleVersion(settings_json=DEFAULT_RULES_JSON, effective_from=now_utc(), note="初期値"))
    if db.get(GameTitle, "kuku-challenge") is None:
        db.add(GameTitle(game_title_id="kuku-challenge", title="九九チャレンジ", latest_app_version="1.0.0",
                         min_app_version="1.0.0", api_major_version=1))
    password = None
    if db.scalar(select(func.count()).select_from(User)) == 0:
        password = generate_password()
        now = now_utc()
        db.add(User(
            family_id=None, role="parent", display_name="初期管理者", login_id=INITIAL_ADMIN_LOGIN_ID,
            password_hash=hash_password(password), must_change_password=True, created_at=now, updated_at=now,
        ))
    db.commit()
    if password:
        bar = "=" * 60
        print(f"\n{bar}\n 初期管理者アカウントを作成しました（この表示は一度だけです）\n"
              f"   ログインID : {INITIAL_ADMIN_LOGIN_ID}\n   パスワード : {password}\n"
              f" 管理画面にログインし、パスワードを変更してから家族を作成してください。\n{bar}\n", file=out, flush=True)
    return password
