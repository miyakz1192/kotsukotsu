from __future__ import annotations

import re
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app import clock
from app.main import create_app

GAME = {"X-Game-Title-Id": "kuku-challenge", "X-App-Version": "1.0.0"}


class FakeClock:
    """now_utc を差し替えて日付をまたぐテストをする。"""

    def __init__(self):
        self.now = datetime(2026, 12, 1, 0, 30, tzinfo=timezone.utc)  # 日本時間 12/1 9:30

    def set_tokyo(self, y, m, d, hh=12, mm=0):
        self.now = datetime(y, m, d, hh, mm, tzinfo=clock.TOKYO).astimezone(timezone.utc)

    def advance(self, **kw):
        self.now += timedelta(**kw)


@pytest.fixture
def fake_clock(monkeypatch):
    fc = FakeClock()
    # `from app.clock import now_utc` している各モジュールの参照を差し替える
    for mod in ("app.clock", "app.services.plays", "app.services.wallet", "app.services.auth", "app.services.family",
                "app.services.registrations", "app.services.summary", "app.services.notifications", "app.services.rules", "app.services.bootstrap",
                "app.api.v1.routes", "app.web.routes"):
        monkeypatch.setattr(f"{mod}.now_utc", lambda: fc.now, raising=False)
    return fc


@pytest.fixture
def client(tmp_path, monkeypatch, fake_clock):
    monkeypatch.setattr("app.services.bootstrap.generate_password", lambda: "Initial-Pass-1")
    monkeypatch.setattr("app.config.APK_DIR", tmp_path / "apk")
    app = create_app(f"sqlite:///{tmp_path/'test.db'}", create_schema=True)
    with TestClient(app) as c:
        c.initial_password = "Initial-Pass-1"
        yield c


def post_json(c, path, body, headers=None):
    return c.post(path, json=body, headers=headers or {})


class Env:
    """初期管理者ログイン → パスワード変更 → 家族作成 → 子供登録 → ゲーム登録までを済ませた環境。"""

    def __init__(self, c: TestClient):
        self.c = c
        self.session = c.app.state.session_factory

    def api_login(self, login_id="admin", password=None):
        r = self.c.post("/api/v1/auth/login", json={"login_id": login_id, "password": password})
        assert r.status_code == 200, r.text
        return r.json()["token"]

    @property
    def H(self):
        return {"Authorization": f"Bearer {self.parent_token}"}

    def game_headers(self, token=None):
        return {**GAME, "Authorization": f"Bearer {token or self.device_token}"}


@pytest.fixture
def env(client, fake_clock):
    from app.services import family as fam_svc
    e = Env(client)
    e.fake_clock = fake_clock
    # 初期管理者: ログイン → パスワード変更必須
    token = e.api_login(password=client.initial_password)
    r = client.post("/api/v1/auth/password", json={"current_password": client.initial_password, "new_password": "new-password-1"},
                    headers={"Authorization": f"Bearer {token}"})
    assert r.status_code == 204, r.text
    e.parent_token = e.api_login(password="new-password-1")
    # 家族・子供は管理画面側の機能なのでサービス層で作る
    with e.session() as db:
        from app.db.models import User
        from sqlalchemy import select
        admin = db.scalars(select(User).where(User.login_id == "admin")).one()
        f = fam_svc.create_family(db, admin, "テスト家")
        child = fam_svc.add_child(db, f, "はると")
        child2 = fam_svc.add_child(db, f, "ゆな")
        db.commit()
        e.admin_id, e.child_id, e.child2_id = admin.user_id, child.user_id, child2.user_id
    r = client.post("/api/v1/game-registrations", json={"game_title_id": "kuku-challenge", "user_id": e.child_id, "device_id": "dev-1"},
                    headers=GAME)
    assert r.status_code == 201, r.text
    e.device_token = r.json()["device_token"]
    return e


def uid() -> str:
    return str(uuid.uuid4())
