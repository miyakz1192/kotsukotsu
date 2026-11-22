"""管理画面（Web）。FastAPI + Jinja2 + htmx のサーバレンダリング（docs/06 3章）。
認証は Cookie セッション。Cookie は SameSite=Strict（CSRF 対策。家庭内LAN前提）。"""
from __future__ import annotations

import uuid
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.orm import Session

from app import config
from app.clock import TOKYO, now_utc, to_tokyo_date, today_tokyo
from app.db.models import Answer, GameTitle, User
from app.deps import get_db
from app.errors import ApiError
from app.services import auth, family, notifications as notif, plays, registrations, releases, rules as rules_svc, stats, summary, wallet

HERE = Path(__file__).parent
templates = Jinja2Templates(directory=HERE / "templates")
router = APIRouter(include_in_schema=False)
COOKIE = "kotsu_session"
MODE_LABEL = {"training_sequential": "トレーニング（順番）", "training_select": "トレーニング（段選択）", "challenge": "チャレンジ"}
TYPE_LABEL = {"study": "学習ポイント", "streak_bonus": "連続日数ボーナス", "self_best_bonus": "自己ベストボーナス",
              "withdrawal": "引き出し", "adjustment": "調整"}


def static_files():
    return StaticFiles(directory=HERE / "static")


def jst(dt: datetime | None, fmt: str = "%Y-%m-%d %H:%M") -> str:
    return dt.astimezone(TOKYO).strftime(fmt) if dt else "-"


templates.env.filters["jst"] = jst
templates.env.globals.update(MODE_LABEL=MODE_LABEL, TYPE_LABEL=TYPE_LABEL)


class Redirect(Exception):
    def __init__(self, url: str):
        self.url = url


def redirect(url: str, msg: str | None = None, err: str | None = None) -> RedirectResponse:
    sep = "&" if "?" in url else "?"
    if msg:
        url += f"{sep}msg={quote(msg)}"
    elif err:
        url += f"{sep}err={quote(err)}"
    return RedirectResponse(url, status_code=303)


def render(request: Request, name: str, user: User | None = None, **ctx) -> HTMLResponse:
    ctx.update(user=user, msg=request.query_params.get("msg"), err=request.query_params.get("err"))
    return templates.TemplateResponse(request, name, ctx)


# ---------- 認証まわり ----------

def session_user(request: Request, db: Session = Depends(get_db)) -> User:
    try:
        return auth.authenticate(db, request.cookies.get(COOKIE), kind="web")
    except ApiError:
        raise Redirect("/login")


def password_ok_user(user: User = Depends(session_user)) -> User:
    return user


def admin(user: User = Depends(session_user)) -> User:
    if user.must_change_password:
        raise Redirect("/password")
    if user.family_id is None:
        raise Redirect("/setup")
    return user




@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return render(request, "login.html")


@router.post("/login")
def login_submit(request: Request, login_id: str = Form(), password: str = Form(), db: Session = Depends(get_db)):
    try:
        token, _ = auth.login(db, login_id, password, kind="web")
    except ApiError as e:
        return redirect("/login", err=e.message)
    db.commit()
    resp = redirect("/")
    resp.set_cookie(COOKIE, token, httponly=True, samesite="strict", secure=config.COOKIE_SECURE,
                    max_age=config.WEB_SESSION_HOURS * 3600)
    return resp


@router.post("/logout")
def logout(request: Request, db: Session = Depends(get_db)):
    tok = request.cookies.get(COOKIE)
    if tok:
        auth.revoke(db, tok)
        db.commit()
    resp = redirect("/login")
    resp.delete_cookie(COOKIE)
    return resp


@router.get("/password", response_class=HTMLResponse)
def password_page(request: Request, user: User = Depends(session_user)):
    return render(request, "password.html", user)


@router.post("/password")
def password_submit(current_password: str = Form(), new_password: str = Form(), new_password2: str = Form(),
                    user: User = Depends(session_user), db: Session = Depends(get_db)):
    if new_password != new_password2:
        return redirect("/password", err="新しいパスワードが一致しません")
    try:
        auth.change_password(db, user, current_password, new_password)
    except ApiError as e:
        db.rollback()
        return redirect("/password", err=e.message)
    db.commit()
    return redirect("/", msg="パスワードを変更しました")


@router.get("/setup", response_class=HTMLResponse)
def setup_page(request: Request, user: User = Depends(session_user), db: Session = Depends(get_db)):
    if user.must_change_password:
        raise Redirect("/password")
    if user.family_id:
        raise Redirect("/")
    return render(request, "setup.html", user)


@router.post("/setup")
def setup_submit(name: str = Form(), user: User = Depends(session_user), db: Session = Depends(get_db)):
    if user.must_change_password:
        raise Redirect("/password")
    try:
        family.create_family(db, user, name)
    except ApiError as e:
        db.rollback()
        return redirect("/setup" if not family.get_family(db) else "/", err=e.message)
    db.commit()
    return redirect("/users", msg="家族を作成しました。子供を登録してください")


# ---------- ダッシュボード ----------

@router.get("/", response_class=HTMLResponse)
def dashboard(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    rules = rules_svc.current_rules(db)
    cards = [summary.child_card(db, c, rules) for c in family.members(db, "child", active_only=True)]
    return render(request, "dashboard.html", user, cards=cards, family=family.get_family(db), rules=rules)


# ---------- ユーザ管理 ----------

@router.get("/users", response_class=HTMLResponse)
def users_page(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    return render(request, "users.html", user, parents=family.members(db, "parent"), children=family.members(db, "child"))


@router.post("/users/child")
def add_child(display_name: str = Form(), user: User = Depends(admin), db: Session = Depends(get_db)):
    try:
        family.add_child(db, family.get_family(db), display_name)
    except ApiError as e:
        db.rollback()
        return redirect("/users", err=e.message)
    db.commit()
    return redirect("/users", msg="子供を追加しました")


@router.post("/users/parent")
def add_parent(display_name: str = Form(), login_id: str = Form(), initial_password: str = Form(),
               user: User = Depends(admin), db: Session = Depends(get_db)):
    try:
        family.add_parent(db, family.get_family(db), display_name, login_id, initial_password)
    except ApiError as e:
        db.rollback()
        return redirect("/users", err=e.message)
    db.commit()
    return redirect("/users", msg="親を追加しました（初回ログイン時にパスワード変更が必要です）")


@router.post("/users/{user_id}")
def update_user(user_id: str, display_name: str = Form(), login_id: str | None = Form(default=None),
                user: User = Depends(admin), db: Session = Depends(get_db)):
    target = db.get(User, user_id)
    if not target or target.family_id != user.family_id:
        return redirect("/users", err="ユーザが見つかりません")
    try:
        family.update_user(db, target, display_name, login_id if target.role == "parent" else None)
    except ApiError as e:
        db.rollback()
        return redirect("/users", err=e.message)
    db.commit()
    return redirect("/users", msg="更新しました")


@router.post("/users/{user_id}/active")
def set_active(user_id: str, active: int = Form(), user: User = Depends(admin), db: Session = Depends(get_db)):
    target = db.get(User, user_id)
    if not target or target.family_id != user.family_id:
        return redirect("/users", err="ユーザが見つかりません")
    try:
        family.set_active(db, target, bool(active), user)
    except ApiError as e:
        db.rollback()
        return redirect("/users", err=e.message)
    db.commit()
    return redirect("/users", msg="有効にしました" if active else "無効にしました")


# ---------- 学習記録 ----------

def _child_or_redirect(db: Session, user: User, child_id: str) -> User | None:
    c = db.get(User, child_id)
    return c if c and c.role == "child" and c.family_id == user.family_id else None


def _date_arg(v: str | None) -> date | None:
    try:
        return date.fromisoformat(v) if v else None
    except ValueError:
        return None


@router.get("/children/{child_id}/plays", response_class=HTMLResponse)
def plays_page(request: Request, child_id: str, d_from: str | None = None, d_to: str | None = None,
               user: User = Depends(admin), db: Session = Depends(get_db)):
    child = _child_or_redirect(db, user, child_id)
    if not child:
        return redirect("/", err="子供が見つかりません")
    f, t = _date_arg(d_from), _date_arg(d_to)
    rows = []
    for p in plays.list_plays(db, child_id, f, t):
        # 端末時刻とサーバ時刻のずれ（REQ-WEB-08）
        skew = (p.ended_at_device - p.received_at).total_seconds() if p.was_offline else None
        rows.append({"p": p, "skew": skew, "answers": plays.answers_of(db, p.play_id)})
    # 段ごとの正解率・苦手な問題（全期間、over_cap除く）
    dan_stat: dict[int, list[int]] = defaultdict(lambda: [0, 0])
    q_stat: dict[tuple[int, int], list[int]] = defaultdict(lambda: [0, 0])
    for a in db.scalars(select(Answer).where(Answer.user_id == child_id, Answer.over_cap.is_(False))):
        dan_stat[a.a][0] += 1
        dan_stat[a.a][1] += a.is_correct
        q_stat[(a.a, a.b)][0] += 1
        q_stat[(a.a, a.b)][1] += a.is_correct
    weak = sorted(((k, v) for k, v in q_stat.items() if v[0] >= 2 and v[1] < v[0]),
                  key=lambda kv: (kv[1][1] / kv[1][0], -kv[1][0]))[:10]
    return render(request, "plays.html", user, child=child, rows=rows, dan_stat=sorted(dan_stat.items()), weak=weak,
                  d_from=d_from or "", d_to=d_to or "", accuracy=stats.cumulative_accuracy(db, child_id))


# ---------- お財布 ----------

@router.get("/children/{child_id}/wallet", response_class=HTMLResponse)
def wallet_page(request: Request, child_id: str, d_from: str | None = None, d_to: str | None = None,
                user: User = Depends(admin), db: Session = Depends(get_db)):
    child = _child_or_redirect(db, user, child_id)
    if not child:
        return redirect("/", err="子供が見つかりません")
    names = {u.user_id: u.display_name for u in db.scalars(select(User))}
    return render(request, "wallet.html", user, child=child, entries=wallet.ledger(db, child_id, _date_arg(d_from), _date_arg(d_to)),
                  balance=wallet.balance(db, child_id), names=names, d_from=d_from or "", d_to=d_to or "",
                  withdraw_id=str(uuid.uuid4()), adjust_id=str(uuid.uuid4()))


@router.post("/children/{child_id}/withdraw")
def withdraw(child_id: str, transaction_id: str = Form(), points: int = Form(), user: User = Depends(admin),
             db: Session = Depends(get_db)):
    child = _child_or_redirect(db, user, child_id)
    if not child or not child.is_active:
        return redirect("/", err="子供が見つかりません")
    try:
        _, created = wallet.withdraw(db, user, child, transaction_id, points, None, False)
    except ApiError as e:
        db.rollback()
        return redirect(f"/children/{child_id}/wallet", err=e.message)
    db.commit()
    return redirect(f"/children/{child_id}/wallet",
                    msg=f"{points}円を引き出しました" if created else "この引き出しは記録済みです（二重には記録していません）")


@router.post("/children/{child_id}/adjust")
def adjust(child_id: str, adjustment_id: str = Form(), points: int = Form(), note: str = Form(default=""),
           user: User = Depends(admin), db: Session = Depends(get_db)):
    child = _child_or_redirect(db, user, child_id)
    if not child or not child.is_active:
        return redirect("/", err="子供が見つかりません")
    try:
        _, created = wallet.adjust(db, user, child, adjustment_id, points, note.strip())
    except ApiError as e:
        db.rollback()
        return redirect(f"/children/{child_id}/wallet", err=e.message)
    db.commit()
    return redirect(f"/children/{child_id}/wallet",
                    msg=f"{points:+d}ptの調整を記録しました" if created else "この調整は記録済みです（二重には記録していません）")


# ---------- ゲーム登録 ----------

@router.get("/registrations", response_class=HTMLResponse)
def registrations_page(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    names = {u.user_id: u.display_name for u in db.scalars(select(User))}
    titles = {t.game_title_id: t.title for t in db.scalars(select(GameTitle))}
    return render(request, "registrations.html", user, regs=registrations.list_registrations(db), names=names, titles=titles)


@router.post("/registrations/{registration_id}/revoke")
def revoke_registration(registration_id: str, user: User = Depends(admin), db: Session = Depends(get_db)):
    try:
        registrations.revoke(db, registration_id)
    except ApiError as e:
        return redirect("/registrations", err=e.message)
    db.commit()
    return redirect("/registrations", msg="登録を解除しました。端末は次回の通信で初回登録画面に戻ります")


# ---------- ポイントルール ----------

def _int(form, key: str) -> int:
    try:
        return int(form.get(key, "").strip())
    except ValueError:
        raise ApiError(400, "INVALID_REQUEST", f"{key} は整数で入力してください")


@router.get("/rules", response_class=HTMLResponse)
def rules_page(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    names = {u.user_id: u.display_name for u in db.scalars(select(User))}
    return render(request, "rules.html", user, rules=rules_svc.current_rules(db), history=rules_svc.history(db), names=names)


@router.post("/rules")
async def rules_submit(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    form = await request.form()
    try:
        note = (form.get("note") or "").strip()
        if not note:
            raise ApiError(400, "INVALID_REQUEST", "変更理由を入力してください")
        cur = rules_svc.current_rules(db)
        thresholds = []
        for i in range(3):
            r, b = (form.get(f"th_rate_{i}") or "").strip(), (form.get(f"th_bonus_{i}") or "").strip()
            if r or b:
                thresholds.append({"rate": _int(form, f"th_rate_{i}"), "bonus": _int(form, f"th_bonus_{i}")})
        settings = {
            "timezone": cur.timezone,
            "points_per_correct": {m: _int(form, f"ppc_{m}") for m in cur.points_per_correct},
            "streak_bonus_per_day": _int(form, "streak_bonus_per_day"),
            "daily_point_cap": _int(form, "daily_point_cap"),
            "daily_question_cap": _int(form, "daily_question_cap"),
            "monthly_cap": _int(form, "monthly_cap"),
            "self_best": {"window": _int(form, "window"), "thresholds": thresholds},
            "graduation": {
                "modes": [m for m in cur.points_per_correct if form.get(f"grad_mode_{m}")],
                "rate": _int(form, "grad_rate"),
                "points_per_correct": _int(form, "grad_points"),
            } if form.get("grad_on") else None,
        }
        row = rules_svc.create_version(db, settings, user.user_id, note)
    except ApiError as e:
        db.rollback()
        return redirect("/rules", err=e.message)
    except (ValidationError, ValueError) as e:
        db.rollback()
        return redirect("/rules", err="設定値が不正です: " + "; ".join(x["msg"] for x in e.errors()) if isinstance(e, ValidationError) else str(e))
    db.commit()
    return redirect("/rules", msg=f"版{row.version}として保存しました。ゲームは次回の通信で新しい設定を受け取ります")


# ---------- おしらせ（通知、docs/06 1.7） ----------

@router.get("/notifications", response_class=HTMLResponse)
def notifications_page(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    s = notif.get_settings(db)
    msgs = notif.list_messages(db)
    db.commit()  # 初回は既定値を作る
    rules = rules_svc.current_rules(db)
    sample = None
    kids = family.members(db, "child", active_only=True)
    if kids:
        child = kids[0]
        today = to_tokyo_date(now_utc())
        sm = summary.player_summary(db, child.user_id, "kuku-challenge", rules)
        played_yesterday = (today - timedelta(days=1)) in stats.played_dates(db, child.user_id)
        sample = notif.sample_values(child.display_name, sm, rules.streak_bonus_per_day, played_yesterday)
    previews = {m.message_id: notif.render(m.text, sample) for m in msgs} if sample else {}
    return render(request, "notifications.html", user, s=s, msgs=msgs, previews=previews, sample=sample,
                  placeholders=notif.PLACEHOLDERS, kind_label=notif.KIND_LABEL)


@router.post("/notifications")
async def notifications_submit(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    form = await request.form()
    try:
        notif.save_settings(db, user.user_id, enabled=bool(form.get("enabled")), inactive_days=_int(form, "inactive_days"),
                            weekday_times=[form.get(f"wd_{i}") or "" for i in range(notif.MAX_TIMES)],
                            holiday_times=[form.get(f"hd_{i}") or "" for i in range(notif.MAX_TIMES)],
                            point_message_every=_int(form, "point_message_every"))
    except ApiError as e:
        db.rollback()
        return redirect("/notifications", err=e.message)
    db.commit()
    return redirect("/notifications", msg="保存しました。子供の端末が次に家のWi-Fiにつながったときに届きます")


@router.post("/notifications/messages")
def notification_message_add(kind: str = Form(), text: str = Form(default=""), user: User = Depends(admin),
                             db: Session = Depends(get_db)):
    try:
        notif.add_message(db, kind, text)
    except ApiError as e:
        db.rollback()
        return redirect("/notifications", err=e.message)
    db.commit()
    return redirect("/notifications", msg="文言を追加しました")


@router.post("/notifications/messages/{message_id}")
def notification_message_update(message_id: str, kind: str = Form(), text: str = Form(default=""),
                                inactive: str | None = Form(default=None), user: User = Depends(admin),
                                db: Session = Depends(get_db)):
    try:
        notif.update_message(db, message_id, kind, text, is_active=not inactive)
    except ApiError as e:
        db.rollback()
        return redirect("/notifications", err=e.message)
    db.commit()
    return redirect("/notifications", msg="文言を保存しました")


# ---------- ゲーム配信（APK） ----------

@router.get("/apk", response_class=HTMLResponse)
def apk_page(request: Request, user: User = Depends(admin), db: Session = Depends(get_db)):
    apps = releases.list_apps(db)
    return render(request, "apk.html", user, apps=apps, histories={a.app_id: releases.history(db, a.app_id)[:5] for a in apps},
                  base=str(request.base_url).rstrip("/"))


@router.post("/apk/{app_id}")
async def apk_upload(app_id: str, file: UploadFile = File(), allow_key_change: str | None = Form(default=None),
                     user: User = Depends(admin), db: Session = Depends(get_db)):
    data = await file.read()
    try:
        rel = releases.add_release(db, app_id, data, user, allow_key_change=bool(allow_key_change))
        db.commit()
    except ApiError as e:
        db.rollback()
        return redirect("/apk", err=e.message)
    return redirect("/apk", msg=f"{rel.package_name} の版 {rel.version_name}（{rel.version_code}）を登録しました。"
                               "ランチャーは次にゲームを選んだとき（または充電中）に更新します")


@router.get("/apk/{app_id}/download")
def apk_download(app_id: str, db: Session = Depends(get_db)):
    """端末のブラウザから取得するため、ログイン不要（家庭内LANのみ公開、REQ-NF-01）。最初のランチャーの導入に使う。"""
    rel = releases.latest_release(db, app_id)
    path = config.APK_DIR / rel.file_name if rel else None
    if not path or not path.exists():
        return HTMLResponse("APKはまだ登録されていません", status_code=404)
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=f"{app_id}.apk")
