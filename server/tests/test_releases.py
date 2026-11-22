"""APKの配布とランチャー用API（フェーズ2.5、REQ-GAME-14〜17、REQ-WEB-07）。
フィクスチャは tests/fixtures/apk/make_fixtures.sh で作った小さなAPK（テスト専用の鍵で署名）。"""
from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from app.apk import ApkError, inspect_apk

FIX = Path(__file__).parent / "fixtures" / "apk"


def apk(name: str) -> bytes:
    return (FIX / name).read_bytes()


def follow(c, method, url, **kw):
    return getattr(c, method)(url, follow_redirects=True, **kw)


def upload(c, app_id, name, **data):
    return follow(c, "post", f"/apk/{app_id}", data=data, files={"file": (name, apk(name))})


@pytest.fixture
def web(env):
    follow(env.c, "post", "/login", data={"login_id": "admin", "password": "new-password-1"})
    return env.c


def test_inspect_apk_reads_manifest_and_signer():
    i = inspect_apk(apk("kuku-v3.apk"))
    assert (i.package_name, i.version_code, i.version_name, i.debuggable, i.min_sdk) == ("com.kotsukotsu.kuku", 3, "1.0.2", False, 26)
    assert i.sha256 == hashlib.sha256(apk("kuku-v3.apk")).hexdigest()
    assert i.cert_sha256 and i.cert_sha256 == inspect_apk(apk("kuku-v4.apk")).cert_sha256
    assert inspect_apk(apk("kuku-otherkey.apk")).cert_sha256 != i.cert_sha256
    assert inspect_apk(apk("kuku-debug.apk")).debuggable is True
    with pytest.raises(ApkError):
        inspect_apk(b"PK\x03\x04dummy")
    with pytest.raises(ApkError):
        inspect_apk(b"not-a-zip")


def test_apps_api_empty_until_uploaded(env):
    assert env.c.get("/api/v1/apps").json() == {"apps": []}


def test_upload_then_launcher_api_and_download(web):
    c = web
    r = upload(c, "kuku-challenge", "kuku-v3.apk")
    assert "登録しました" in r.text and "1.0.2" in r.text
    assert "登録しました" in upload(c, "launcher", "launcher-v1.apk").text
    apps = {a["app_id"]: a for a in c.get("/api/v1/apps").json()["apps"]}  # 認証なし
    k = apps["kuku-challenge"]
    assert k["kind"] == "game" and k["title"] == "九九チャレンジ" and k["package_name"] == "com.kotsukotsu.kuku"
    assert (k["version_code"], k["version_name"], k["min_sdk"]) == (3, "1.0.2", 26)
    assert apps["launcher"]["kind"] == "launcher" and apps["launcher"]["package_name"] == "com.kotsukotsu.launcher"
    d = c.get("/" + k["download_path"])
    assert d.status_code == 200 and hashlib.sha256(d.content).hexdigest() == k["sha256"] and len(d.content) == k["size"]
    # 新しい版を登録すると、一覧は新しい版を指し、古い版も取得できる（ダウンロード中に差し替わっても照合が合う）
    upload(c, "kuku-challenge", "kuku-v4.apk")
    k2 = {a["app_id"]: a for a in c.get("/api/v1/apps").json()["apps"]}["kuku-challenge"]
    assert k2["version_code"] == 4 and k2["download_path"].endswith("/releases/4/apk")
    assert c.get("/" + k["download_path"]).status_code == 200
    assert c.get("/api/v1/apps/kuku-challenge/releases/99/apk").status_code == 404
    # 最初の導入用URL（ブラウザ、ログイン不要）は最新版
    c.cookies.clear()
    d = c.get("/apk/launcher/download")
    assert d.status_code == 200 and d.content == apk("launcher-v1.apk")
    d = c.get("/apk/kuku-challenge/download")
    assert d.content == apk("kuku-v4.apk")
    # ゲームの最新版表示も追従する
    with_settings = c.get("/apk", follow_redirects=False)
    assert with_settings.status_code == 303  # 管理画面はログインが必要


def test_upload_rejections(web):
    c = web
    assert "APKファイルではない" in follow(c, "post", "/apk/kuku-challenge", files={"file": ("a.apk", b"not-a-zip")}).text
    assert "デバッグ版" in upload(c, "kuku-challenge", "kuku-debug.apk").text
    upload(c, "kuku-challenge", "kuku-v4.apk")
    assert "増えていません" in upload(c, "kuku-challenge", "kuku-v4.apk").text  # 同じ版
    assert "増えていません" in upload(c, "kuku-challenge", "kuku-v3.apk").text  # 古い版
    assert "パッケージ名が前の版と違います" in upload(c, "kuku-challenge", "other-pkg.apk").text
    assert "用です" in upload(c, "launcher", "kuku-v3.apk").text  # 九九のAPKをランチャー枠に
    assert "署名の鍵が前の版と違います" in upload(c, "kuku-challenge", "kuku-otherkey.apk").text
    assert "ゲームが見つかりません" in upload(c, "no-such-game", "kuku-v3.apk").text
    apps = c.get("/api/v1/apps").json()["apps"]
    assert [(a["app_id"], a["version_code"]) for a in apps] == [("kuku-challenge", 4)]  # 拒否したものは登録されない
    # 鍵の変更を明示的に認めれば登録できる
    assert "登録しました" in upload(c, "kuku-challenge", "kuku-otherkey.apk", allow_key_change="1").text
    assert c.get("/api/v1/apps").json()["apps"][0]["version_code"] == 6


def test_apk_page_shows_launcher_and_history(web):
    c = web
    upload(c, "kuku-challenge", "kuku-v3.apk")
    upload(c, "kuku-challenge", "kuku-v4.apk")
    t = c.get("/apk").text
    assert "こつこつランチャー" in t and "/apk/launcher/download" not in t  # ランチャー未登録ならURLは出さない
    assert "versionCode 4" in t and "1.0.2" in t  # 履歴
    assert "ファミリーリンク" in t


def test_parent_app_release(web):
    """親用アプリ（REQ-APP-13）。一覧に kind=parent で出て、ブラウザ用URLで落とせる。"""
    c = web
    assert "親用アプリ" in c.get("/apk").text
    assert "登録しました" in upload(c, "parent-app", "parent-v1.apk").text
    assert "パッケージ名が前の版と違います" in upload(c, "parent-app", "launcher-v1.apk").text
    p = {a["app_id"]: a for a in c.get("/api/v1/apps").json()["apps"]}["parent-app"]
    assert p["kind"] == "parent" and p["package_name"] == "com.kotsukotsu.parent" and p["version_code"] == 1
    assert "/apk/parent-app/download" in c.get("/apk").text
    c.cookies.clear()
    assert c.get("/apk/parent-app/download").content == apk("parent-v1.apk")
