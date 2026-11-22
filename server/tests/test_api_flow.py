"""API v1 の通し確認：登録・プレイ送信・台帳・冪等性・認証・エラー。"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import select, text
from sqlalchemy.exc import DBAPIError

from app.db.models import LedgerEntry
from tests.conftest import GAME, uid


def play_body(fc, play_id=None, mode="training_select", dan=3, correct=9, was_offline=False, ended=None):
    ended = ended or fc.now
    answers = [{"seq": i + 1, "a": dan, "b": i + 1, "answer": dan * (i + 1) if i < correct else 0, "elapsed_ms": 1000}
               for i in range(9)]
    return {"play_id": play_id or uid(), "mode": mode, "dan": dan,
            "started_at_device": (ended - timedelta(seconds=60)).isoformat().replace("+00:00", "Z"),
            "ended_at_device": ended.isoformat().replace("+00:00", "Z"),
            "was_offline": was_offline, "client_rule_version": 1, "answers": answers}


def send(env, body):
    return env.c.post("/api/v1/play-sessions", json=body, headers=env.game_headers())


def ledger_count(env):
    with env.session() as db:
        return len(db.scalars(select(LedgerEntry)).all())


def test_children_list_requires_no_auth(env):
    r = env.c.get("/api/v1/family/children")
    assert r.status_code == 200
    assert {c["display_name"] for c in r.json()["children"]} == {"はると", "ゆな"}


def test_play_roundtrip_and_idempotent_resend(env):
    body = play_body(env.fake_clock)
    r = send(env, body)
    assert r.status_code == 201, r.text
    j = r.json()
    assert j["awarded"] == {"study": 9, "streak_bonus": 1, "self_best_bonus": 0}
    assert j["summary"]["cumulative_accuracy"] == 1.0
    assert j["play_date"] == "2026-12-01"
    assert j["summary"]["balance"] == 10 and j["summary"]["streak_days"] == 1
    n = ledger_count(env)
    # 同じ play_id の再送：200で最初の結果、台帳は増えない
    r2 = send(env, body)
    assert r2.status_code == 200
    assert r2.json()["awarded"] == j["awarded"]
    assert ledger_count(env) == n == 2


def test_server_recomputes_correctness(env):
    body = play_body(env.fake_clock, correct=0)
    for a in body["answers"]:
        a["is_correct"] = True  # クライアントが嘘をついても無視する
    r = send(env, body)
    assert r.json()["awarded"]["study"] == 0
    assert r.json()["summary"]["cumulative_accuracy"] == 0.0
    send(env, play_body(env.fake_clock, correct=9))  # 9/18
    assert env.c.get("/api/v1/players/me/summary", headers=env.game_headers()).json()["cumulative_accuracy"] == 0.5


def test_summary_and_next_sequential_dan(env):
    send(env, play_body(env.fake_clock, mode="training_sequential", dan=1))
    r = env.c.get("/api/v1/players/me/summary", headers=env.game_headers())
    j = r.json()
    assert j["next_sequential_dan"] == 2
    assert j["today"]["questions"] == 9 and j["today"]["first_play_done"] is True
    assert j["month"] == {"earned": 10, "cap": 1500}
    assert len(j["dan_recent"]["1"]) == 9


def test_offline_play_date_rules(env):
    fc = env.fake_clock
    env.c.get("/api/v1/players/me/summary", headers=env.game_headers())  # 同期 12/1 9:30
    ended = fc.now + timedelta(hours=1)
    fc.advance(days=2)  # 12/3 9:30 に送信
    r = send(env, play_body(fc, was_offline=True, ended=ended))
    assert r.json()["play_date"] == "2026-12-01"  # 端末の終了時刻の日付
    # 範囲外（同期より前の時刻）: サーバ受信日、clock_suspect
    r = send(env, play_body(fc, was_offline=True, ended=fc.now - timedelta(days=30)))
    assert r.json()["play_date"] == "2026-12-03"
    plays = env.c.get(f"/api/v1/children/{env.child_id}/plays", headers=env.H).json()["plays"]
    assert sorted(p["clock_suspect"] for p in plays) == [False, True]


def test_offline_queue_sent_oldest_first_keeps_play_dates(env):
    """オフラインで溜めた複数件を古い順に送っても、すべて端末の日付で処理される。"""
    fc = env.fake_clock
    env.c.get("/api/v1/players/me/summary", headers=env.game_headers())
    t0 = fc.now
    fc.advance(days=3)
    for i in range(3):
        r = send(env, play_body(fc, was_offline=True, ended=t0 + timedelta(minutes=10 * (i + 1))))
        assert r.json()["play_date"] == "2026-12-01"


def test_auth_errors(env):
    r = env.c.post("/api/v1/play-sessions", json=play_body(env.fake_clock), headers={**GAME, "Authorization": "Bearer nope"})
    assert r.status_code == 401 and r.json()["error"]["code"] == "UNAUTHORIZED"
    r = env.c.get("/api/v1/children/summary")
    assert r.status_code == 401
    r = env.c.get("/api/v1/players/me/summary", headers={"Authorization": f"Bearer {env.device_token}", "X-App-Version": "0.9.0"})
    assert r.status_code == 426 and r.json()["error"]["code"] == "APP_UPDATE_REQUIRED"
    # 子供の端末トークンで親APIは使えない
    r = env.c.get("/api/v1/children/summary", headers={"Authorization": f"Bearer {env.device_token}"})
    assert r.status_code == 401


def test_duplicate_registration_409(env):
    r = env.c.post("/api/v1/game-registrations",
                   json={"game_title_id": "kuku-challenge", "user_id": env.child_id, "device_id": "dev-2"}, headers=GAME)
    assert r.status_code == 409 and r.json()["error"]["code"] == "DUPLICATE_REGISTRATION"
    # 別の子供なら登録できる
    r = env.c.post("/api/v1/game-registrations",
                   json={"game_title_id": "kuku-challenge", "user_id": env.child2_id, "device_id": "dev-2"}, headers=GAME)
    assert r.status_code == 201


def test_revoked_registration_gets_401_then_can_reregister(env):
    from app.services import registrations
    with env.session() as db:
        reg = registrations.list_registrations(db)[0]
        registrations.revoke(db, reg.registration_id)
        db.commit()
    r = send(env, play_body(env.fake_clock))
    assert r.status_code == 401
    r = env.c.post("/api/v1/game-registrations",
                   json={"game_title_id": "kuku-challenge", "user_id": env.child_id, "device_id": "dev-1"}, headers=GAME)
    assert r.status_code == 201


def test_withdrawal_idempotent_and_negative_balance(env):
    send(env, play_body(env.fake_clock))  # 残高10
    tx = uid()
    body = {"transaction_id": tx, "child_user_id": env.child_id, "points": 500, "was_offline": False}
    r = env.c.post("/api/v1/withdrawals", json=body, headers=env.H)
    assert r.status_code == 201 and r.json()["balance"] == -490  # 残高を超えてもよい
    n = ledger_count(env)
    r2 = env.c.post("/api/v1/withdrawals", json=body, headers=env.H)
    assert r2.status_code == 200 and r2.json()["recorded_at"] == r.json()["recorded_at"]
    assert ledger_count(env) == n
    # 同じIDで内容が違う → 400
    r3 = env.c.post("/api/v1/withdrawals", json={**body, "points": 1}, headers=env.H)
    assert r3.status_code == 400


def test_offline_withdrawal_keeps_device_time(env):
    occurred = (env.fake_clock.now - timedelta(days=1)).isoformat().replace("+00:00", "Z")
    r = env.c.post("/api/v1/withdrawals", headers=env.H, json={
        "transaction_id": uid(), "child_user_id": env.child_id, "points": 100, "was_offline": True, "occurred_at_device": occurred})
    assert r.status_code == 201
    e = env.c.get(f"/api/v1/children/{env.child_id}/ledger", headers=env.H).json()["entries"][0]
    assert e["occurred_at"].startswith("2026-11-30") and e["points"] == -100
    # メモは省略可（親用アプリは「おかし」などを付けて送る）
    r = env.c.post("/api/v1/withdrawals", headers=env.H, json={
        "transaction_id": uid(), "child_user_id": env.child_id, "points": 50, "note": "おかし"})
    assert r.status_code == 201
    entries = env.c.get(f"/api/v1/children/{env.child_id}/ledger", headers=env.H).json()["entries"]
    assert next(x for x in entries if x["points"] == -50)["note"] == "おかし"


def test_adjustment_deposit_and_idempotency(env):
    aid = uid()
    body = {"adjustment_id": aid, "child_user_id": env.child_id, "points": 500, "note": "12月の基本のお小遣い"}
    assert env.c.post("/api/v1/adjustments", json=body, headers=env.H).status_code == 201
    assert env.c.post("/api/v1/adjustments", json=body, headers=env.H).status_code == 200
    j = env.c.get(f"/api/v1/children/{env.child_id}/ledger", headers=env.H).json()
    assert j["balance"] == 500 and len(j["entries"]) == 1 and j["entries"][0]["note"] == "12月の基本のお小遣い"


def test_offline_adjustment_keeps_device_time(env):
    """親用アプリが外出先で記録した調整（REQ-APP-10）。端末の時刻で台帳に入り、再送は冪等。"""
    occurred = (env.fake_clock.now - timedelta(days=1)).isoformat().replace("+00:00", "Z")
    body = {"adjustment_id": uid(), "child_user_id": env.child_id, "points": -120, "note": "おかし",
            "was_offline": True, "occurred_at_device": occurred}
    r = env.c.post("/api/v1/adjustments", json=body, headers=env.H)
    assert r.status_code == 201 and r.json()["balance"] == -120
    assert env.c.post("/api/v1/adjustments", json=body, headers=env.H).status_code == 200
    e = env.c.get(f"/api/v1/children/{env.child_id}/ledger", headers=env.H).json()["entries"]
    assert len(e) == 1 and e[0]["occurred_at"].startswith("2026-11-30") and e[0]["points"] == -120
    # その場の調整（was_offline=false）は端末の時刻を使わない
    r = env.c.post("/api/v1/adjustments", headers=env.H, json={**body, "adjustment_id": uid(), "was_offline": False})
    e = env.c.get(f"/api/v1/children/{env.child_id}/ledger", headers=env.H).json()["entries"]
    assert sorted(x["occurred_at"][:10] for x in e) == ["2026-11-30", "2026-12-01"]
    # タイムゾーンなしの日時は拒否
    r = env.c.post("/api/v1/adjustments", headers=env.H,
                   json={**body, "adjustment_id": uid(), "occurred_at_device": "2026-11-30T10:00:00"})
    assert r.status_code == 400


def test_adjustment_503_on_transaction_failure(env, monkeypatch):
    from sqlalchemy.exc import OperationalError

    def boom(*a, **k):
        raise OperationalError("stmt", {}, Exception("database is locked"))
    monkeypatch.setattr("app.api.v1.routes.wallet.adjust", boom)
    r = env.c.post("/api/v1/adjustments", headers=env.H,
                   json={"adjustment_id": uid(), "child_user_id": env.child_id, "points": 10, "was_offline": True})
    assert r.status_code == 503 and r.json()["error"]["code"] == "TRANSACTION_FAILED"


def test_ledger_is_append_only(env):
    send(env, play_body(env.fake_clock))
    with env.session() as db:
        for sql in ("UPDATE ledger_entries SET points = 999", "DELETE FROM ledger_entries"):
            try:
                db.execute(text(sql))
                raise AssertionError(f"{sql} が通ってしまった")
            except DBAPIError as e:
                assert "append-only" in str(e)
                db.rollback()
        assert ledger_count(env) == 2


def test_503_on_transaction_failure(env, monkeypatch):
    from sqlalchemy.exc import OperationalError

    def boom(*a, **k):
        raise OperationalError("stmt", {}, Exception("database is locked"))
    monkeypatch.setattr("app.api.v1.routes.wallet.withdraw", boom)
    r = env.c.post("/api/v1/withdrawals", headers=env.H,
                   json={"transaction_id": uid(), "child_user_id": env.child_id, "points": 10})
    assert r.status_code == 503 and r.json()["error"]["code"] == "TRANSACTION_FAILED"


def test_password_change_required_blocks_other_apis(client):
    r = client.post("/api/v1/auth/login", json={"login_id": "admin", "password": client.initial_password})
    tok = {"Authorization": f"Bearer {r.json()['token']}"}
    assert r.json()["must_change_password"] is True
    assert client.get("/api/v1/children/summary", headers=tok).status_code == 403
    assert client.post("/api/v1/auth/login", json={"login_id": "admin", "password": "wrong"}).status_code == 401
    assert client.post("/api/v1/auth/password", headers=tok,
                       json={"current_password": client.initial_password, "new_password": "short"}).status_code == 400


def test_second_family_rejected(env):
    from app.errors import ApiError
    from app.services import family
    import pytest
    with env.session() as db:
        from app.db.models import User
        admin = db.get(User, env.admin_id)
        with pytest.raises(ApiError) as ei:
            family.create_family(db, admin, "二つ目")
        assert ei.value.status == 409 and ei.value.code == "FAMILY_ALREADY_EXISTS"


def test_children_summary_and_settings(env):
    send(env, play_body(env.fake_clock))
    j = env.c.get("/api/v1/children/summary", headers=env.H).json()["children"]
    hal = next(c for c in j if c["display_name"] == "はると")
    assert hal["balance"] == 10 and hal["month_remaining"] == 1490
    s = env.c.get("/api/v1/settings", headers=env.H).json()
    assert s["version"] == 1 and s["monthly_cap"] == 1500
    assert env.c.get("/api/v1/settings", headers=env.game_headers()).status_code == 200
