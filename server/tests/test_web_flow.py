"""管理画面の通し実行（フェーズ1 完了条件）：初回起動 → パスワード変更 → 家族作成 → 子供登録 → 調整で入金 → 引き出し。"""
from __future__ import annotations

import re

from tests.conftest import GAME


def follow(c, method, url, **kw):
    return getattr(c, method)(url, follow_redirects=True, **kw)


def test_full_admin_journey(client):
    c = client
    assert c.get("/", follow_redirects=False).status_code == 303  # 未ログインはログインへ
    r = follow(c, "post", "/login", data={"login_id": "admin", "password": "bad"})
    assert "違います" in r.text
    r = follow(c, "post", "/login", data={"login_id": "admin", "password": c.initial_password})
    assert "パスワード変更" in r.text and "初回ログイン" in r.text  # 変更を強制
    assert "パスワード変更" in follow(c, "get", "/users").text  # 他の画面へは行けない
    r = follow(c, "post", "/password", data={"current_password": c.initial_password, "new_password": "new-password-1", "new_password2": "new-password-1"})
    assert "家族を作成" in r.text  # 次は家族作成
    r = follow(c, "post", "/setup", data={"name": "みやざき家"})
    assert "子供を登録" in r.text or "家族を作成しました" in r.text
    # 2つ目の家族は作れない
    r = follow(c, "post", "/setup", data={"name": "二つ目"})
    assert "すでに作成" in r.text
    r = follow(c, "post", "/users/child", data={"display_name": "はると"})
    assert "子供を追加しました" in r.text
    child_id = re.search(r"/children/([0-9a-f-]{36})/wallet", r.text).group(1)

    # ゲーム登録（重複はエラー）
    body = {"game_title_id": "kuku-challenge", "user_id": child_id, "device_id": "d1"}
    assert c.post("/api/v1/game-registrations", json=body, headers=GAME).status_code == 201
    assert c.post("/api/v1/game-registrations", json=body, headers=GAME).status_code == 409
    r = follow(c, "get", "/registrations")
    assert "はると" in r.text and "有効" in r.text

    # 調整で入金 → 引き出し（二重送信しても1回）
    r = follow(c, "get", f"/children/{child_id}/wallet")
    adj_id = re.search(r'name="adjustment_id" value="([^"]+)"', r.text).group(1)
    wd_id = re.search(r'name="transaction_id" value="([^"]+)"', r.text).group(1)
    r = follow(c, "post", f"/children/{child_id}/adjust", data={"adjustment_id": adj_id, "points": 500, "note": "12月の基本のお小遣い"})
    assert "+500pt" in r.text
    follow(c, "post", f"/children/{child_id}/withdraw", data={"transaction_id": wd_id, "points": 300})
    r = follow(c, "post", f"/children/{child_id}/withdraw", data={"transaction_id": wd_id, "points": 300})
    assert "記録済み" in r.text
    assert "200" in re.search(r'class="big[^"]*">([^<]+)<', r.text).group(1)  # 500-300
    # 残高を超える引き出しも可能でマイナス表示
    r = follow(c, "post", f"/children/{child_id}/withdraw", data={"transaction_id": "t-over-12345", "points": 1000})
    assert "neg" in r.text and "マイナス" in r.text

    # ダッシュボード・学習記録・ユーザ管理
    assert "みやざき家" in follow(c, "get", "/").text
    assert "プレイはまだありません" in follow(c, "get", f"/children/{child_id}/plays").text
    r = follow(c, "get", "/users")
    assert "初期管理者" in r.text


def test_rules_change_creates_new_version_and_affects_calculation(env):
    from tests.test_api_flow import play_body, send
    c = env.c
    follow(c, "post", "/login", data={"login_id": "admin", "password": "new-password-1"})
    form = {"ppc_training_sequential": 1, "ppc_training_select": 3, "ppc_challenge": 2, "streak_bonus_per_day": 1,
            "daily_point_cap": 40, "daily_question_cap": 117, "monthly_cap": 1500, "window": 27,
            "th_rate_0": 80, "th_bonus_0": 10, "th_rate_1": 90, "th_bonus_1": 20, "th_rate_2": 100, "th_bonus_2": 30, "note": "段選択を3ptに"}
    r = follow(c, "post", "/rules", data=form)
    assert "版2として保存" in r.text
    assert "変更理由" in follow(c, "post", "/rules", data={**form, "note": ""}).text  # 理由は必須
    assert "設定値が不正" in follow(c, "post", "/rules", data={**form, "th_rate_1": 70}).text  # 昇順でない
    assert c.get("/api/v1/settings", headers=env.H).json()["version"] == 2
    j = send(env, play_body(env.fake_clock)).json()
    assert j["awarded"]["study"] == 27  # 設定値(3pt)が使われる。ハードコードではない
    assert c.get("/api/v1/settings", headers=env.H).json()["graduation"] is None  # チェックなし＝卒業なし
    grad = {**form, "grad_on": "1", "grad_rate": 100, "grad_points": 0, "grad_mode_training_select": "1", "note": "卒業を入れる"}
    assert "版3として保存" in follow(c, "post", "/rules", data=grad).text
    assert c.get("/api/v1/settings", headers=env.H).json()["graduation"] == {
        "modes": ["training_select"], "rate": 100, "points_per_correct": 0}
    assert "到達点" in follow(c, "post", "/rules", data={**grad, "grad_rate": 95}).text  # 到達点にない正解率
    assert 'name="grad_on" value="1" style="width:auto" checked' in follow(c, "get", "/rules").text


def test_revoke_from_web_makes_device_401(env):
    from tests.test_api_flow import play_body, send
    c = env.c
    follow(c, "post", "/login", data={"login_id": "admin", "password": "new-password-1"})
    r = follow(c, "get", "/registrations")
    rid = re.search(r"/registrations/([0-9a-f-]{36})/revoke", r.text).group(1)
    assert "解除しました" in follow(c, "post", f"/registrations/{rid}/revoke").text
    assert send(env, play_body(env.fake_clock)).status_code == 401


def test_login_id_change_and_parent_management(env):
    c = env.c
    follow(c, "post", "/login", data={"login_id": "admin", "password": "new-password-1"})
    r = follow(c, "post", "/users/parent", data={"display_name": "ママ", "login_id": "mama", "initial_password": "mama-pass-1"})
    assert "親を追加しました" in r.text
    assert "すでに使われています" in follow(c, "post", "/users/parent", data={"display_name": "x", "login_id": "mama", "initial_password": "mama-pass-1"}).text
    # ログインIDを変更しても記録は user_id に紐づく
    r = follow(c, "post", f"/users/{env.admin_id}", data={"display_name": "パパ", "login_id": "papa"})
    assert "更新しました" in r.text
    assert env.c.post("/api/v1/auth/login", json={"login_id": "papa", "password": "new-password-1"}).status_code == 200
    assert env.c.post("/api/v1/auth/login", json={"login_id": "admin", "password": "new-password-1"}).status_code == 401
    # 自分自身は無効化できない
    assert "自分自身" in follow(c, "post", f"/users/{env.admin_id}/active", data={"active": 0}).text
