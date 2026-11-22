# 04. データモデル

型は SQLite／SQLAlchemy を想定した論理型。日時はすべて UTC の ISO 8601 で保存し、日付判定のときだけ Asia/Tokyo に変換する。ID はサーバ採番のものを UUID（文字列）、クライアント採番のもの（`play_id`、`transaction_id`）も UUID とする。

## families

| 列 | 型 | 制約・説明 |
|---|---|---|
| family_id | uuid | PK |
| name | text | |
| created_at | datetime | |

アプリ側で1件のみに制限する（2件目の作成はエラー、REQ-USR-02）。

## users

| 列 | 型 | 制約・説明 |
|---|---|---|
| user_id | uuid | PK。記録の紐づけは常にこれ |
| family_id | uuid | FK、初期管理者は家族作成前のみ NULL |
| role | text | `parent` / `child` |
| display_name | text | |
| login_id | text | 親のみ。UNIQUE。変更可 |
| password_hash | text | 親のみ（Argon2id） |
| must_change_password | bool | 初期管理者は true |
| is_active | bool | 無効化（削除はしない） |
| created_at / updated_at | datetime | |

## game_titles

| 列 | 型 | 制約・説明 |
|---|---|---|
| game_title_id | text | PK。例 `kuku-challenge` |
| title | text | 例「九九チャレンジ」 |
| latest_app_version | text | 例 `1.0.0` |
| min_app_version | text | これ未満は更新を促す |
| api_major_version | int | 例 1 |
| apk_path | text | 配布ファイル（最新の `app_releases.file_name` の写し） |

## app_releases（配布するAPKの版の履歴、フェーズ2.5）

| 列 | 型 | 制約・説明 |
|---|---|---|
| release_id | text | PK（UUID） |
| app_id | text | ゲームタイトルID、またはランチャー自身は `launcher` |
| package_name | text | APKから読み取る。同じ app_id では変えられない。別の app_id と同じものは拒否 |
| version_code | int | APKから読み取る。UNIQUE(app_id, version_code)。前の版より大きいものだけ登録できる |
| version_name | text | APKから読み取る。ゲームは `game_titles.latest_app_version` にも写す |
| min_sdk | int | APKから読み取る |
| sha256 | text | APKファイル全体。ランチャーがダウンロード後に照合する |
| size | int | バイト数 |
| cert_sha256 | text | 署名証明書の SHA-256。前の版と違えば拒否（管理画面で明示的に認めた場合のみ登録） |
| file_name | text | `{app_id}-{version_code}.apk`（`KOTSU_DATA_DIR/apk/`） |
| uploaded_at | datetime | |
| uploaded_by | text | FK users |

デバッグ版（`android:debuggable=true`）と署名のないAPKは登録しない。

## game_registrations

| 列 | 型 | 制約・説明 |
|---|---|---|
| registration_id | uuid | PK |
| game_title_id | text | FK |
| user_id | uuid | FK（子供） |
| device_id | text | 端末側で生成して保持するID |
| token_hash | text | 端末トークンのハッシュ（平文は保存しない） |
| registered_at | datetime | |
| revoked_at | datetime | NULL＝有効 |
| last_synced_at | datetime | 最後に同期した時刻（プレイ日判定に使う）。`GET /players/me/summary` と、オンライン送信（`was_offline=false`）のプレイで更新する。`/settings` など他の通信では更新しない（オフライン分の送信途中で更新されると、後続の古いプレイが `clock_suspect` になるため） |

部分一意制約：`(game_title_id, user_id) WHERE revoked_at IS NULL`（REQ-GAME-06）。

## point_rule_versions

| 列 | 型 | 制約・説明 |
|---|---|---|
| version | int | PK、連番 |
| settings_json | json | `03_point-rules.md` 1章の形式 |
| effective_from | datetime | |
| created_by | uuid | FK（親） |
| note | text | 変更理由 |

最新の版を「現在の設定」とする。過去の版は削除しない。

## notification_settings（おしらせ通知の設定、1行、2026-11-17）

| 列 | 型 | 制約・説明 |
|---|---|---|
| setting_id | int | PK（常に1） |
| enabled | bool | |
| inactive_days | int | 1〜7。今日を含めて何日やっていなければ通知するか |
| weekday_times | json | `["07:15","17:00","20:00"]`（0〜3件、`HH:MM`） |
| holiday_times | json | 土日祝の時刻（同上） |
| point_message_every | int | 0〜10。何回に1回ポイント入りにするか（0＝出さない） |
| updated_at | datetime | |
| updated_by | uuid | FK（親）、NULL可 |

ポイントルールと違い版は持たない（通知はポイントに影響しないため、上書き保存）。行がなければ既定値で作る。

## notification_messages（通知の文言）

| 列 | 型 | 制約・説明 |
|---|---|---|
| message_id | uuid | PK |
| kind | text | `encourage`／`points` |
| text | text | 60字以内。差し込み `{name}` などは docs/06 1.7 のものだけ |
| is_active | bool | 消さずに無効化する |
| sort | int | 表示順 |
| updated_at | datetime | |

初期文言は、行が1件もないときにサーバが入れる。

## play_sessions

| 列 | 型 | 制約・説明 |
|---|---|---|
| play_id | uuid | PK（クライアント採番、冪等キー） |
| user_id | uuid | FK |
| game_title_id | text | FK |
| registration_id | uuid | FK |
| mode | text | `training_sequential` / `training_select` / `challenge` |
| dan | int | 順番・段選択モードの段。チャレンジは NULL |
| started_at_device / ended_at_device | datetime | 端末時刻 |
| received_at | datetime | サーバ受信時刻 |
| was_offline | bool | オフラインで保存されてから送られたか |
| play_date | date | Asia/Tokyo。`03_point-rules.md` 3章で決定 |
| clock_suspect | bool | 端末時刻が妥当範囲外 |
| client_rule_version | int | ゲームが計算に使った設定の版 |
| server_rule_version | int | サーバが確定計算に使った版 |
| correct_count | int | |
| app_version | text | |

## answers

| 列 | 型 | 制約・説明 |
|---|---|---|
| answer_id | int | PK（自動採番）。受信順＝回答順を表し、自己ベスト判定の「直近27問」に使う |
| play_id | uuid | FK |
| seq | int | 1〜9。(play_id, seq) は UNIQUE |
| user_id / game_title_id | | 自己ベスト判定用の非正規化列 |
| a / b | int | 問題 `a × b`。段は `a` |
| answer | int | NULL＝無回答 |
| is_correct | bool | |
| elapsed_ms | int | 回答時間（ポイントには使わない） |
| over_cap | bool | 1日の問題数上限超過 |

インデックス：自己ベスト判定のため `(user_id, a, 回答順)` で直近27件を引けるようにする（`play_sessions` と結合、または `answers` に `user_id` と `answered_seq` を非正規化して持つ）。

## ledger_entries（お財布の台帳、追記のみ）

| 列 | 型 | 制約・説明 |
|---|---|---|
| entry_id | uuid | PK |
| child_user_id | uuid | FK |
| type | text | `study` / `streak_bonus` / `self_best_bonus` / `withdrawal` / `adjustment` |
| points | int | ＋／−。引き出しは負の値 |
| play_id | uuid | 学習・ボーナスの場合 |
| transaction_id | uuid | 引き出し・調整の場合。UNIQUE（冪等キー）。調整では `adjustment_id` をここに入れる |
| actor_user_id | uuid | 引き出し・調整を行った親 |
| occurred_at | datetime | 発生時刻（オフライン引き出しは端末時刻） |
| recorded_at | datetime | サーバ反映時刻 |
| play_date | date | ポイント獲得の場合、月の上限の集計に使う |
| note | text | 調整理由など |

UPDATE／DELETE を禁止する（アプリ層で禁止し、可能ならトリガでも防ぐ）。残高＝`SUM(points)`。

## self_best_achievements

| 列 | 型 | 制約・説明 |
|---|---|---|
| child_user_id | uuid | PK の一部 |
| game_title_id | text | PK の一部（第2弾以降のゲームとの区別） |
| dan | int | PK の一部 |
| threshold | int | PK の一部（80／90／100） |
| achieved_at | datetime | |
| play_id | uuid | 達成したプレイ |

## parent_sessions / api_tokens

親のログインセッション（管理画面は Cookie、親用アプリは Bearer トークン）。トークンはハッシュで保存し、失効できるようにする。

## 集計値（保存せず、都度計算またはビュー）

- 連続日数：`play_sessions.play_date` から計算
- 累積正解率：`answers` から計算（全期間、`over_cap` を除く）
- 今日の正解ポイント実績・今月の獲得合計：`ledger_entries` の `play_date` で集計
