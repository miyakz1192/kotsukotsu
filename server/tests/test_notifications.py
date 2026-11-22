"""おしらせ通知の設定（docs/06 1.7、docs/05 GET /notification-settings、REQ-WEB-09）。"""
from __future__ import annotations

import re

import pytest

from app.errors import ApiError
from app.services import notifications as ns
from tests.test_web_flow import follow


def test_api_returns_defaults_holidays_and_needs_device_token(env):
    c = env.c
    env.fake_clock.set_tokyo(2026, 12, 20)
    r = c.get("/api/v1/notification-settings", headers=env.game_headers())
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["enabled"] is True and j["inactive_days"] == 1
    assert j["weekday_times"] == ["07:15", "17:00", "20:00"]
    assert j["holiday_times"] == ["10:00", "17:00", "20:00"]
    assert j["point_message_every"] == 3
    kinds = {m["kind"] for m in j["messages"]}
    assert kinds == {"encourage", "points"}
    assert all(m["message_id"] and m["text"] for m in j["messages"])
    assert "2027-01-01" in j["holidays"] and "2027-01-11" in j["holidays"]
    assert j["holidays_until"] == "2027-03-20"  # 今日から90日
    # 親トークンや不正なトークンでは取れない（端末向け）
    assert c.get("/api/v1/notification-settings", headers=env.H).status_code == 401
    assert c.get("/api/v1/notification-settings", headers=env.game_headers("bad")).status_code == 401


def test_validation(env):
    with env.session() as db:
        base = dict(enabled=True, inactive_days=1, weekday_times=["07:15"], holiday_times=[], point_message_every=3)
        ns.save_settings(db, env.admin_id, **base)
        for bad in (dict(inactive_days=0), dict(inactive_days=8), dict(weekday_times=["7:15x"]),
                    dict(weekday_times=["24:00"]), dict(weekday_times=["07:00", "07:00"]),
                    dict(holiday_times=["06:00", "07:00", "08:00", "09:00"]), dict(point_message_every=-1),
                    dict(point_message_every=11)):
            with pytest.raises(ApiError):
                ns.save_settings(db, env.admin_id, **{**base, **bad})
        s = ns.save_settings(db, env.admin_id, **{**base, "weekday_times": ["20:00", "7:05"]})
        assert s.weekday_times == ["07:05", "20:00"]  # そろえて昇順
        for kind, text in (("other", "やろう"), ("encourage", ""), ("encourage", "あ" * 61),
                           ("points", "{unknown} ポイント"), ("points", "{balance ポイント"), ("points", "}{")):
            with pytest.raises(ApiError):
                ns.add_message(db, kind, text)
        m = ns.add_message(db, "points", "いま {balance}ポイント、あと {today_left}")
        assert m.is_active


def test_render_sample():
    v = {"name": "はると", "balance": 120, "today_left": 40, "month_left": 900, "streak": 3, "next_streak": 4, "streak_bonus": 4}
    assert ns.render("{name}、いま {balance}ポイント！あと {today_left}", v) == "はると、いま 120ポイント！あと 40"


def test_admin_page_saves_and_api_reflects(env):
    c = env.c
    follow(c, "post", "/login", data={"login_id": "admin", "password": "new-password-1"})
    page = follow(c, "get", "/notifications").text
    assert "おしらせ" in page and "07:15" in page
    r = follow(c, "post", "/notifications", data={"enabled": "1", "inactive_days": "2", "wd_0": "06:50", "wd_1": "", "wd_2": "19:30",
                                                  "hd_0": "", "hd_1": "", "hd_2": "", "point_message_every": "0"})
    assert "保存しました" in r.text
    assert "時刻" in follow(c, "post", "/notifications", data={"inactive_days": "1", "wd_0": "25:00", "point_message_every": "3"}).text
    r = follow(c, "post", "/notifications/messages", data={"kind": "encourage", "text": "{name}、ためしの ぶん"})
    assert "追加しました" in r.text and "ためしの ぶん" in r.text
    assert "差し込み" in follow(c, "post", "/notifications/messages", data={"kind": "points", "text": "{nanika}"}).text
    j = c.get("/api/v1/notification-settings", headers=env.game_headers()).json()
    assert j["inactive_days"] == 2 and j["weekday_times"] == ["06:50", "19:30"] and j["holiday_times"] == []
    assert j["point_message_every"] == 0
    mid = next(m["message_id"] for m in j["messages"] if "ためしの" in m["text"])
    # 編集と無効化（無効にすると配られない）
    r = follow(c, "post", f"/notifications/messages/{mid}", data={"kind": "encourage", "text": "{name}、かえた ぶん"})
    assert "かえた ぶん" in r.text
    j = c.get("/api/v1/notification-settings", headers=env.game_headers()).json()
    assert not any("ためしの" in m["text"] for m in j["messages"]) and any("かえた" in m["text"] for m in j["messages"])
    follow(c, "post", f"/notifications/messages/{mid}", data={"kind": "encourage", "text": "{name}、かえた ぶん", "inactive": "1"})
    j = c.get("/api/v1/notification-settings", headers=env.game_headers()).json()
    assert not any(m["message_id"] == mid for m in j["messages"])
    # 無効の設定も届く
    follow(c, "post", "/notifications", data={"inactive_days": "1", "wd_0": "07:00", "point_message_every": "3"})
    assert c.get("/api/v1/notification-settings", headers=env.game_headers()).json()["enabled"] is False
    assert re.search(r"はると", follow(c, "get", "/notifications").text)  # 見本表示に子供の名前
