# 05. 共通API v1

## 共通事項

- ベースパス：`/api/v1`。JSON（UTF-8）。日時は ISO 8601（UTC、`Z` 付き）。
- ゲームは毎回、次のヘッダを送る：`X-Game-Title-Id`、`X-App-Version`。
- 認証
  - ゲーム：`Authorization: Bearer <device_token>`（初回登録で発行）
  - 親用アプリ：`Authorization: Bearer <parent_token>`（ログインで発行）
  - 管理画面：Cookie セッション（API ではなく Web 画面側で扱う）
- エラー形式：

```json
{ "error": { "code": "DUPLICATE_REGISTRATION", "message": "このゲームにはすでに登録されています" } }
```

| HTTP | code | 意味・クライアントの扱い |
|---|---|---|
| 400 | `INVALID_REQUEST` | 入力不正 |
| 401 | `UNAUTHORIZED` | トークン不正・失効（登録解除された端末を含む）。ゲームは初回登録画面に戻る |
| 403 | `FORBIDDEN` | 権限なし |
| 409 | `DUPLICATE_REGISTRATION` | 同じゲーム×子供の有効な登録が既にある |
| 409 | `FAMILY_ALREADY_EXISTS` | 家族の2件目作成 |
| 426 | `APP_UPDATE_REQUIRED` | `min_app_version` 未満。更新を促す |
| 503 | `TRANSACTION_FAILED` | DBのトランザクション異常。クライアントの扱いは各APIを参照 |

冪等なAPI（`play-sessions`、`withdrawals`、`adjustments`）は、同じIDの再送に **最初の処理結果をそのまま** 返す（HTTP 200）。ただし応答に含まれる `summary`・`balance` は再送時点の現在値。同じIDで内容（子供・金額）が違う場合は 400。

補足：パスワード未変更の親トークンは `/auth/password` 以外で 403 `PASSWORD_CHANGE_REQUIRED`。サーバは `is_correct` を信用せず `answer` から再計算する（原則1）。`answers` は1〜9件、`dan` は順番・段選択で必須／チャレンジで指定不可。

## ゲーム向け

### GET /family/children
初回登録の選択画面用。**認証なし**（家庭内LAN限定、REQ-GAME-09）。

```json
200 { "children": [ { "user_id": "…", "display_name": "はると" } ] }
```

### POST /game-registrations

```json
// request
{ "game_title_id": "kuku-challenge", "user_id": "…", "device_id": "…" }
// 201
{ "registration_id": "…", "device_token": "…", "settings": { /* 03_point-rules.md 1章 */ } }
```
失敗（通信不可、409、503）のとき、ゲームは起動しない（REQ-GAME-05）。

### GET /settings
現在のポイントルール（版番号付き）。ゲーム・親用アプリが起動時に取得。

### GET /notification-settings（2026-11-17 追加）
おしらせ通知の設定（端末トークン）。ゲームは同期のたびに取得して保存し、オフライン中は最後の値で通知する（docs/06 1.7）。

```json
200 {
  "enabled": true,
  "inactive_days": 1,
  "weekday_times": ["07:15", "17:00", "20:00"],
  "holiday_times": ["10:00", "17:00", "20:00"],
  "point_message_every": 3,
  "messages": [{"message_id": "…", "kind": "encourage", "text": "{name}、きょうも 九九 やってみよう！"}],
  "holidays": ["2026-11-23", "2027-01-01"],
  "holidays_until": "2027-02-14",
  "updated_at": "2026-11-17T10:00:00Z"
}
```
`holidays` は今日（日本時間）から90日分の祝日・振替休日・国民の休日。`messages` は有効なものだけ。

### GET /players/me/summary
ゲーム起動時の表示用。

```json
200 {
  "as_of": "2026-12-05T09:00:00Z",
  "streak_days": 5,
  "cumulative_accuracy": 0.87,
  "balance": 420,
  "today": { "questions": 27, "question_cap": 117, "study_points": 22, "point_cap": 40, "first_play_done": true },
  "month": { "earned": 310, "cap": 1500 },
  "dan_recent": { "1": [true, true, false], "…": [] },
  "self_best_achieved": [ { "dan": 7, "threshold": 80 } ],
  "next_sequential_dan": 4,
  "settings_version": 1
}
```
`dan_recent` は段ごとの直近27回答の正誤（ゲーム側の自己ベスト即時計算用）。

**ゲームの呼び出し順**：起動時は「未送信キューを古い順にすべて送る → `summary` を取得」の順にする。`summary` の取得が「前回同期時刻」を更新するため（`04_data-model.md` の `last_synced_at`）、キューが残ったまま呼ぶと、残りのオフライン分が `clock_suspect` になる。

### POST /play-sessions
プレイ結果の送信。オフライン分は古い順に1件ずつ送る（受信順＝時系列順を保つため、まとめ送りでも配列の順序を保証する）。

```json
// request
{
  "play_id": "…",
  "mode": "training_select",
  "dan": 7,
  "started_at_device": "…", "ended_at_device": "…",
  "was_offline": false,
  "client_rule_version": 1,
  "answers": [ { "seq": 1, "a": 7, "b": 3, "answer": 21, "is_correct": true, "elapsed_ms": 2300 } ]
}
// 201（再送時は200で同じ内容）
{
  "play_id": "…",
  "play_date": "2026-12-05",
  "awarded": { "study": 9, "streak_bonus": 0, "self_best_bonus": 10 },
  "self_best_new": [ { "dan": 7, "threshold": 80 } ],
  "summary": { /* /players/me/summary と同じ */ }
}
```
503 のとき、ゲームは未送信キューに残して再送する。

## ランチャー向け（フェーズ2.5）

どちらも**認証なし**（家庭内LAN限定。`/family/children` と同じ扱い）。

### GET /apps
ランチャーが表示・更新するアプリの最新版。版が1つも登録されていないアプリは含めない。`kind` は `launcher`／`game`／`parent`（親用アプリ。ランチャーは表示しない。親用アプリが自分の新しい版の確認に使う）。

```json
{"apps": [
  {"app_id": "launcher", "kind": "launcher", "title": "こつこつランチャー", "package_name": "com.kotsukotsu.launcher",
   "version_code": 1, "version_name": "1.0.0", "min_sdk": 26, "sha256": "…", "size": 7506127,
   "download_path": "api/v1/apps/launcher/releases/1/apk"},
  {"app_id": "kuku-challenge", "kind": "game", "title": "九九チャレンジ", "package_name": "com.kotsukotsu.kuku",
   "version_code": 3, "version_name": "1.0.2", "min_sdk": 26, "sha256": "…", "size": 7617743,
   "download_path": "api/v1/apps/kuku-challenge/releases/3/apk"},
  {"app_id": "parent-app", "kind": "parent", "title": "親用アプリ", "package_name": "com.kotsukotsu.parent", "…": "…"}
]}
```

### GET /apps/{app_id}/releases/{version_code}/apk
APKファイル。版を指定するので、ダウンロード中に新しい版が登録されても `sha256` の照合が合う。無ければ 404 `NOT_FOUND`。

## 親向け（親用アプリ・管理画面共通）

### POST /auth/login
`{ "login_id": "…", "password": "…" }` → `200 { "token": "…", "must_change_password": false }`

### POST /auth/password
`{ "current_password": "…", "new_password": "…" }`

### GET /children/summary
子供ごとの残高・連続日数・今月の獲得・月の上限・最終プレイ日時。

### GET /children/{user_id}/ledger?from=&to=
台帳。`{ "entries": [...], "balance": 420 }`

### GET /children/{user_id}/plays?from=&to=
学習記録（プレイと回答、`clock_suspect` を含む）。

### POST /withdrawals

```json
// request
{ "transaction_id": "…", "child_user_id": "…", "points": 500, "occurred_at_device": "…", "was_offline": false, "note": "おかし" }
// 201（再送時は200で同じ内容）
{ "transaction_id": "…", "recorded_at": "…", "balance": -80 }
```
- 残高不足でも受け付ける（REQ-PT-05）。`note` は省略可（2026-11-14 追加、親用アプリのメモ）。
- 503 `TRANSACTION_FAILED`：`was_offline=false`（その場での引き出し）なら、アプリは失敗を表示し記録しない。`was_offline=true`（キューからの再送）なら、キューに残して再試行する。

### POST /adjustments

```json
// request（occurred_at_device と was_offline は省略可。省略時はその場の調整）
{ "adjustment_id": "…", "child_user_id": "…", "points": 500, "note": "12月の基本のお小遣い",
  "occurred_at_device": "…", "was_offline": false }
// 201（再送時は200で同じ内容）
{ "adjustment_id": "…", "recorded_at": "…", "balance": 920 }
```
- `points` は0以外の整数（＋で入金、−で訂正）。`adjustment_id` で冪等。
- `was_offline=true` のとき、台帳の `occurred_at` に `occurred_at_device` を使う（引き出しと同じ）。
- 503 `TRANSACTION_FAILED` の扱いは `/withdrawals` と同じ（親用アプリが外出先で記録した調整の再送に使う。REQ-APP-10）。

## 管理画面専用（APIとして公開しなくてよい）

家族の作成、ユーザの追加・変更・無効化、ゲーム登録の解除、ポイントルールの変更、APKの登録・ダウンロード。
