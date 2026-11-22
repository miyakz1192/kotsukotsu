# CLAUDE.md

## このプロジェクト

子供が学習ゲーム（Android）で学習すると、管理サーバでポイントが計算され、子供ごとのお財布に貯まる家庭内システム。1ポイント＝1円。親は管理画面（Web）と親用管理アプリ（Android）で残高を確認し、引き出す。第一弾のゲームは九九（ゲームタイトルID `kuku-challenge`）。

利用者は1家族（親と子供）。管理サーバは **家庭内LANのみ** に置き、外部公開しない。

## 仕様の場所

- 要件：`docs/01_requirements.md`（要件ID `REQ-xxx` で参照する）
- ポイント計算：`docs/03_point-rules.md`（テストケースあり）
- API：`docs/05_api-v1.md`／データ：`docs/04_data-model.md`
- 画面とオフライン同期：`docs/06_clients.md`
- 作業順と完了条件：`docs/07_roadmap.md`
- 未決事項：`docs/08_open-items.md`

仕様が曖昧・矛盾していると感じたら、推測で実装せず、ユーザに確認する。確認した結果は該当する docs に反映する。

## 守るべき原則（変更する場合は必ずユーザに確認）

1. **管理サーバがデータの正。** ゲームと親用アプリの値は同期時点の写し。ポイントの確定値は必ずサーバが計算する。
2. **台帳は追記のみ。** `ledger_entries` を UPDATE／DELETE しない。訂正は「調整」の取引を追加する。残高は台帳の合計。
3. **冪等性。** プレイ結果は `play_id`、引き出しは `transaction_id`（どちらもクライアントが採番する UUID）で重複を防ぐ。同じIDの再送は、最初の結果をそのまま返す。
4. **ルールの値をハードコードしない。** 単価・ボーナス・上限はすべて `point_rule_versions` の設定値から読む。サーバとゲームで同じ設定値を使う。
5. **日付は日本時間（Asia/Tokyo）。** 連続日数・月初リセット・1日の上限の判定に使う。
6. **ポイント計算は純粋関数として独立させ、`docs/03_point-rules.md` のテストケースを全部テストにする。** 計算ロジックを変えるときは、先にテストを追加する。
7. **APIはバージョン付き（`/api/v1/...`）。** 互換性を壊す変更は v2 として追加し、v1 を残す。
8. **ユーザIDとログインIDを分ける。** 記録の紐づけは常に内部の `user_id`。ログインIDは親だけが持ち、変更できる。

## 進め方

- 技術スタックは `docs/02_architecture.md` に推奨案がある。**最初のコードを書く前に、採用するスタックをユーザに確認する。**
- フェーズ単位で進め、各フェーズの完了条件（`docs/07_roadmap.md`）を満たしたら報告する。
- UIの文言は日本語。子供向け画面はひらがな多めにする。
- 秘密情報（初期パスワード、トークン署名鍵）をリポジトリに入れない。

## コマンド

技術スタックは `docs/02_architecture.md` の推奨案で確定（2026-11-10 ユーザ承認）。サーバは `server/` で `uv` を使う。

```bash
cd server
uv sync                                   # 依存関係
uv run pytest -q                          # 全テスト（TC1〜TC13 をエンジン単体と実DB経由の両方で実行）
uv run alembic upgrade head               # スキーマ作成・更新
KOTSU_DATA_DIR=./data uv run uvicorn --factory app.main:app_factory --port 8000   # 起動（初回は初期管理者のパスワードがコンソールに出る）
uv run alembic revision --autogenerate -m "..." --rev-id 000N   # モデル変更時（台帳トリガーは手で残す）
uv run python scripts/export_openapi.py   # spec/openapi.yaml を再生成（テストが差分を検出する）
tests/fixtures/apk/make_fixtures.sh       # APK検査テスト用の小さなAPK（テスト専用の鍵）を再生成

cd deploy && docker compose up -d --build # 自宅サーバでの実行（データは deploy/data）
deploy/backup.sh                          # DBのオンラインバックアップ（BACKUP_REMOTE で別機器へ rsync）
deploy/demo-game.sh http://localhost:8000 <親ID> <パスワード>   # ゲームを模した登録→送信→台帳確認
```

Android は `android/`（マルチモジュール：`core`＋`game-kuku`＋`launcher`＋`parent-app`）。配布するAPKは versionCode を必ず上げる（サーバが同じ・古い番号を拒否する）。JDK17・SDK は `~/android-tools` にある。

```bash
cd android
export JAVA_HOME=~/android-tools/jdk-17.0.13+11      # local.properties の sdk.dir は ~/android-tools/android-sdk
./gradlew test                       # JVMテスト（TC1〜TC13 を spec/point-rules-cases.json から実行、同期、出題）
./gradlew :game-kuku:assembleDebug   # APK → game-kuku/build/outputs/apk/debug/game-kuku-debug.apk
./gradlew :game-kuku:assembleDebug -PkotsuDefaultServer=http://192.168.0.2:8000/   # 初回登録画面のサーバ既定値を変える（既定はこの値）
./gradlew :game-kuku:lintDebug :launcher:lintDebug :parent-app:lintDebug
./gradlew assembleRelease            # 配布用（九九＋ランチャー＋親用アプリ）→ */build/outputs/apk/release/*.apk を管理画面「ゲーム配信」へ。署名鍵は ~/.kotsukotsu-secrets/（リポジトリ外、release-signing.gradle）
KOTSU_E2E_URL=http://127.0.0.1:8000/ ./gradlew :core:testDebugUnitTest --tests '*ServerE2ETest*' --rerun  # 実サーバ相手の通し（子供登録済み・ゲーム未登録のサーバで）
KOTSU_E2E_URL=http://127.0.0.1:8000/ KOTSU_E2E_LOGIN=<親ID> KOTSU_E2E_PASSWORD=<パスワード> ./gradlew :parent-app:testDebugUnitTest --tests '*ParentServerE2ETest*' --rerun  # 親用アプリの外出→帰宅の通し（子供が1人以上いるサーバで）
python3 tools/gen_sounds.py          # 効果音（合成）を res/raw に再生成
```

環境変数：`KOTSU_DATA_DIR`（DB・APKの置き場、既定 `./data`）、`KOTSU_DB_PATH`、`KOTSU_COOKIE_SECURE`（HTTPS化したら 1）。
