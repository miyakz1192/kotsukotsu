"""共通API v1（docs/05_api-v1.md）。"""
from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Header, Response
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.clock import now_utc, to_tokyo_date
from app.db.models import User
from app.deps import GameContext, _bearer, game_ctx, game_title_headers, get_db, parent_any, parent_user
from app.errors import ApiError, invalid, unauthorized
from app import config
from app.services import auth, family, notifications, plays, registrations, releases, rules as rules_svc, summary, wallet

from . import schemas as s

router = APIRouter(prefix="/api/v1")

ERR = {400: {"model": s.ErrorResponse}, 401: {"model": s.ErrorResponse}, 403: {"model": s.ErrorResponse},
       409: {"model": s.ErrorResponse}, 426: {"model": s.ErrorResponse}, 503: {"model": s.ErrorResponse}}


# ---------- ゲーム向け ----------

@router.get("/family/children", response_model=s.ChildrenResponse, tags=["game"])
def list_children(db: Session = Depends(get_db)):
    """初回登録の選択画面用。認証なし（家庭内LAN限定、REQ-GAME-09）。"""
    return {"children": [{"user_id": c.user_id, "display_name": c.display_name} for c in family.active_children(db)]}


@router.get("/apps", response_model=s.AppsResponse, tags=["launcher"])
def list_apps(db: Session = Depends(get_db)):
    """ランチャー用のアプリ一覧と最新版。認証なし（家庭内LAN限定、REQ-GAME-09 と同じ扱い）。"""
    items = []
    for a in releases.list_apps(db):
        r = a.latest
        if r is None:
            continue
        items.append({"app_id": a.app_id, "kind": a.kind, "title": a.title, "package_name": r.package_name,
                      "version_code": r.version_code, "version_name": r.version_name, "min_sdk": r.min_sdk,
                      "sha256": r.sha256, "size": r.size,
                      "download_path": f"api/v1/apps/{a.app_id}/releases/{r.version_code}/apk"})
    return {"apps": items}


@router.get("/apps/{app_id}/releases/{version_code}/apk", tags=["launcher"],
            responses={200: {"content": {"application/vnd.android.package-archive": {}}}, 404: {"model": s.ErrorResponse}})
def download_release(app_id: str, version_code: int, db: Session = Depends(get_db)):
    r = releases.get_release(db, app_id, version_code)
    path = config.APK_DIR / r.file_name if r else None
    if not path or not path.exists():
        raise ApiError(404, "NOT_FOUND", "APKが見つかりません")
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=f"{app_id}-{version_code}.apk")


@router.post("/game-registrations", status_code=201, response_model=s.RegistrationResponse, responses=ERR, tags=["game"])
def register_game(body: s.RegistrationRequest, db: Session = Depends(get_db), title=Depends(game_title_headers)):
    if body.game_title_id != title.game_title_id:
        raise invalid("X-Game-Title-Id と game_title_id が一致しません")
    child = db.get(User, body.user_id)
    if not child:
        raise invalid("ユーザが見つかりません")
    reg, token = registrations.register(db, body.game_title_id, child, body.device_id)
    db.commit()
    return {"registration_id": reg.registration_id, "device_token": token,
            "settings": rules_svc.rules_for_response(rules_svc.current_rules(db))}


@router.get("/settings", response_model=s.SettingsModel, responses=ERR, tags=["game", "parent"])
def get_settings(db: Session = Depends(get_db), authorization: str | None = Header(default=None),
                 x_game_title_id: str | None = Header(default=None), x_app_version: str | None = Header(default=None)):
    """現在のポイントルール。ゲーム（端末トークン）・親用アプリ（親トークン）のどちらでも取得できる。"""
    token = _bearer(authorization)
    try:
        registrations.authenticate_device(db, token)
    except ApiError:
        auth.authenticate(db, token, kind="api")
    return rules_svc.rules_for_response(rules_svc.current_rules(db))


@router.get("/notification-settings", response_model=s.NotificationSettingsResponse, responses=ERR, tags=["game"])
def notification_settings(db: Session = Depends(get_db), ctx: GameContext = Depends(game_ctx)):
    """おしらせ通知の設定と文言（docs/06 1.7）。ゲームは同期のたびに取得し、端末内で通知を予約する。"""
    body = notifications.payload(db, to_tokyo_date(now_utc()))
    db.commit()  # 初回は既定値を作る
    return body


@router.get("/players/me/summary", response_model=s.SummaryResponse, responses=ERR, tags=["game"])
def my_summary(db: Session = Depends(get_db), ctx: GameContext = Depends(game_ctx)):
    # 同期済みとみなす時刻を更新する（オフライン分の送信後に呼ばれる前提。docs/05 参照）
    ctx.reg.last_synced_at = now_utc()
    db.commit()
    return summary.player_summary(db, ctx.reg.user_id, ctx.reg.game_title_id, rules_svc.current_rules(db))


@router.post("/play-sessions", response_model=s.PlaySessionResponse, status_code=201, responses=ERR, tags=["game"])
def post_play(body: s.PlaySessionRequest, response: Response, db: Session = Depends(get_db),
              ctx: GameContext = Depends(game_ctx)):
    rules = rules_svc.current_rules(db)
    req = plays.PlayReq(
        play_id=body.play_id, mode=body.mode, dan=body.dan, started_at_device=_aware(body.started_at_device),
        ended_at_device=_aware(body.ended_at_device), was_offline=body.was_offline,
        client_rule_version=body.client_rule_version,
        answers=[plays.AnswerReq(a.seq, a.a, a.b, a.answer, a.elapsed_ms) for a in body.answers],
    )
    out = plays.submit_play(db, ctx.reg, req, rules, ctx.app_version)
    if not out.created:
        response.status_code = 200
    return {
        "play_id": out.play_id, "play_date": out.play_date,
        "awarded": {"study": out.study, "streak_bonus": out.streak_bonus, "self_best_bonus": out.self_best_bonus},
        "self_best_new": out.self_best_new,
        "summary": summary.player_summary(db, ctx.reg.user_id, ctx.reg.game_title_id, rules),
    }


def _aware(dt):
    from datetime import timezone
    if dt.tzinfo is None:
        raise invalid("日時には UTC 表記（Z 付き）を使ってください")
    return dt.astimezone(timezone.utc)


# ---------- 親向け ----------

@router.post("/auth/login", response_model=s.LoginResponse, responses=ERR, tags=["parent"])
def login(body: s.LoginRequest, db: Session = Depends(get_db)):
    token, user = auth.login(db, body.login_id, body.password, kind="api")
    db.commit()
    return {"token": token, "must_change_password": user.must_change_password}


@router.post("/auth/password", status_code=204, responses=ERR, tags=["parent"])
def change_password(body: s.PasswordRequest, db: Session = Depends(get_db), user: User = Depends(parent_any)):
    auth.change_password(db, user, body.current_password, body.new_password)
    db.commit()
    return Response(status_code=204)


@router.get("/children/summary", response_model=s.ChildrenSummaryResponse, responses=ERR, tags=["parent"])
def children_summary(db: Session = Depends(get_db), user: User = Depends(parent_user)):
    rules = rules_svc.current_rules(db)
    return {"children": [summary.child_card(db, c, rules) for c in family.members(db, "child", active_only=True)]}


@router.get("/children/{user_id}/ledger", response_model=s.LedgerResponse, responses=ERR, tags=["parent"])
def child_ledger(user_id: str, from_: date | None = None, to: date | None = None, db: Session = Depends(get_db),
                 user: User = Depends(parent_user)):
    child = family.get_child(db, user_id)
    return {"entries": wallet.ledger(db, child.user_id, from_, to), "balance": wallet.balance(db, child.user_id)}


@router.get("/children/{user_id}/plays", response_model=s.PlaysResponse, responses=ERR, tags=["parent"])
def child_plays(user_id: str, from_: date | None = None, to: date | None = None, db: Session = Depends(get_db),
                user: User = Depends(parent_user)):
    child = family.get_child(db, user_id)
    out = []
    for p in plays.list_plays(db, child.user_id, from_, to):
        out.append({**{k: getattr(p, k) for k in s.PlayOut.model_fields if k != "answers"},
                    "answers": plays.answers_of(db, p.play_id)})
    return {"plays": out}


def _active_child(db: Session, user_id: str) -> User:
    child = family.get_child(db, user_id)
    if not child.is_active:
        raise invalid("無効化された子供です")
    return child


@router.post("/withdrawals", status_code=201, response_model=s.WithdrawalResponse, responses=ERR, tags=["parent"])
def post_withdrawal(body: s.WithdrawalRequest, response: Response, db: Session = Depends(get_db),
                    user: User = Depends(parent_user)):
    """残高不足でも受け付ける（REQ-PT-05）。同じ transaction_id の再送は200で最初の記録を返す。"""
    child = _active_child(db, body.child_user_id)
    occurred = _aware(body.occurred_at_device) if body.occurred_at_device else None
    entry, created = wallet.withdraw(db, user, child, body.transaction_id, body.points, occurred, body.was_offline,
                                     body.note)
    db.commit()
    if not created:
        response.status_code = 200
    return {"transaction_id": entry.transaction_id, "recorded_at": entry.recorded_at,
            "balance": wallet.balance(db, child.user_id)}


@router.post("/adjustments", status_code=201, response_model=s.AdjustmentResponse, responses=ERR, tags=["parent"])
def post_adjustment(body: s.AdjustmentRequest, response: Response, db: Session = Depends(get_db),
                    user: User = Depends(parent_user)):
    """外出先で記録した調整（was_offline=true）は端末の時刻を発生時刻にする（REQ-APP-10）。"""
    child = _active_child(db, body.child_user_id)
    occurred = _aware(body.occurred_at_device) if body.occurred_at_device else None
    entry, created = wallet.adjust(db, user, child, body.adjustment_id, body.points, body.note, occurred, body.was_offline)
    db.commit()
    if not created:
        response.status_code = 200
    return {"adjustment_id": entry.transaction_id, "recorded_at": entry.recorded_at,
            "balance": wallet.balance(db, child.user_id)}
