"""APKの配布（版の履歴）。ランチャーが一覧と最新版を取得し、ゲームとランチャー自身を更新する（REQ-GAME-14〜17）。"""
from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from app import config
from app.apk import ApkError, inspect_apk
from app.clock import now_utc
from app.db.models import AppRelease, GameTitle, User
from app.errors import ApiError

LAUNCHER_ID = "launcher"
LAUNCHER_TITLE = "こつこつランチャー"
PARENT_ID = "parent-app"
PARENT_TITLE = "親用アプリ"
# ゲーム以外の決まった枠（app_id → (kind, 表示名)）
FIXED_APPS = {LAUNCHER_ID: ("launcher", LAUNCHER_TITLE), PARENT_ID: ("parent", PARENT_TITLE)}


@dataclass
class AppEntry:
    app_id: str
    kind: str  # game / launcher / parent
    title: str
    latest: AppRelease | None


def latest_release(db: Session, app_id: str) -> AppRelease | None:
    return db.scalars(
        select(AppRelease).where(AppRelease.app_id == app_id).order_by(AppRelease.version_code.desc()).limit(1)
    ).first()


def list_apps(db: Session) -> list[AppEntry]:
    out = [AppEntry(i, kind, title, latest_release(db, i)) for i, (kind, title) in FIXED_APPS.items()]
    for t in db.scalars(select(GameTitle).order_by(GameTitle.game_title_id)):
        out.append(AppEntry(t.game_title_id, "game", t.title, latest_release(db, t.game_title_id)))
    return out


def history(db: Session, app_id: str) -> list[AppRelease]:
    return list(db.scalars(select(AppRelease).where(AppRelease.app_id == app_id).order_by(AppRelease.version_code.desc())))


def get_release(db: Session, app_id: str, version_code: int) -> AppRelease | None:
    return db.scalars(
        select(AppRelease).where(AppRelease.app_id == app_id, AppRelease.version_code == version_code)
    ).first()


def _bad(msg: str) -> ApiError:
    return ApiError(400, "INVALID_APK", msg)


def add_release(db: Session, app_id: str, data: bytes, user: User | None, allow_key_change: bool = False) -> AppRelease:
    """APKを読み取り、検査して登録する。呼び出し側で commit する。"""
    title = db.get(GameTitle, app_id) if app_id not in FIXED_APPS else None
    if app_id not in FIXED_APPS and title is None:
        raise ApiError(404, "NOT_FOUND", "ゲームが見つかりません")
    try:
        info = inspect_apk(data)
    except ApkError as e:
        raise _bad(str(e)) from e
    if info.debuggable:
        raise _bad("デバッグ版のAPKです。配布には release 版（./gradlew assembleRelease）を使ってください")
    if not info.cert_sha256:
        raise _bad("署名のないAPKです。release 署名の設定（android/release-signing.gradle）を確認してください")
    other = db.scalars(
        select(AppRelease).where(AppRelease.package_name == info.package_name, AppRelease.app_id != app_id).limit(1)
    ).first()
    if other:
        raise _bad(f"このAPK（{info.package_name}）は「{other.app_id}」用です。登録先を確認してください")
    prev = latest_release(db, app_id)
    if prev:
        if prev.package_name != info.package_name:
            raise _bad(f"パッケージ名が前の版と違います（前: {prev.package_name}、今回: {info.package_name}）")
        if info.version_code <= prev.version_code:
            raise _bad(f"バージョン番号（versionCode）が増えていません（登録済み: {prev.version_code}、今回: {info.version_code}）。"
                       "Android は同じ番号や古い番号への更新を受け付けません")
        if prev.cert_sha256 != info.cert_sha256 and not allow_key_change:
            raise _bad("署名の鍵が前の版と違います。このままでは端末で更新できません（入れ直しが必要）。"
                       "鍵を変えたのが意図どおりなら「署名の鍵の変更を認める」にチェックして登録してください")
    name = f"{app_id}-{info.version_code}.apk"
    config.APK_DIR.mkdir(parents=True, exist_ok=True)
    (config.APK_DIR / name).write_bytes(data)
    rel = AppRelease(
        app_id=app_id, package_name=info.package_name, version_code=info.version_code, version_name=info.version_name,
        min_sdk=info.min_sdk, sha256=info.sha256, size=info.size, cert_sha256=info.cert_sha256, file_name=name,
        uploaded_at=now_utc(), uploaded_by=user.user_id if user else None,
    )
    db.add(rel)
    if title is not None:
        title.apk_path = name
        if info.version_name:
            title.latest_app_version = info.version_name
    return rel
