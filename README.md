# ハンドオフ資料：学習スコア連動型お小遣いシステム

Claude Code で実装を始めるための資料一式です。提案書（Word、第3.1版）の確定内容を、実装に必要な粒度で書き直しています。提案書と食い違う場合は **本資料を正** とします（本資料のほうが新しく、具体的なため）。

## 資料の構成

| ファイル | 内容 | 読むタイミング |
|---|---|---|
| `CLAUDE.md` | リポジトリ直下に置く。Claude Code が毎回読む前提・守るべき原則 | 最初にリポジトリへ配置 |
| `docs/01_requirements.md` | 要件一覧（ID付き）。由来（要望／決定）を明記 | 全体把握 |
| `docs/02_architecture.md` | 構成要素、技術スタック案（要確認）、リポジトリ構成 | 着手前 |
| `docs/03_point-rules.md` | ポイント計算の仕様とテストケース | フェーズ1（最重要） |
| `docs/04_data-model.md` | テーブル定義 | フェーズ1 |
| `docs/05_api-v1.md` | 共通API v1 の仕様 | フェーズ1〜4 |
| `docs/06_clients.md` | 九九ゲーム・親用アプリ・管理画面の画面仕様とオフライン同期 | フェーズ1〜4 |
| `docs/07_roadmap.md` | フェーズ別の作業と完了条件 | 各フェーズの開始時 |
| `docs/08_open-items.md` | 実装前に人が決めること、試行で調整すること。**いま回答待ちのこと** は冒頭の表 | 着手前・作業を再開するとき |

## 使い方

1. 新しいリポジトリを作り、`CLAUDE.md` をルートに、`docs/` をそのまま置く。
2. Claude Code に「`docs/08_open-items.md` の未決事項を確認してから、`docs/07_roadmap.md` のフェーズ1を始めて」と指示する。
3. 技術スタック（`docs/02_architecture.md` 2章）は推奨案です。Claude Code は着手前に確認を求めるよう `CLAUDE.md` に書いてあります。

## 別の環境でビルドする

リポジトリに入っていないもの（環境ごとに用意する）と手順。コマンドの詳細は `CLAUDE.md` の「コマンド」。

| 用意するもの | 用途 | 無いとき |
|---|---|---|
| `uv` | サーバ（`cd server && uv sync && uv run pytest -q`） | — |
| Docker（compose v2） | 自宅サーバでの実行 | — |
| JDK 17、Android SDK（platform 35、build-tools 35.0.0） | Android のビルド | — |
| `android/local.properties`（`sdk.dir=...`）または環境変数 `ANDROID_HOME` | SDK の場所 | Gradle が SDK を見つけられない |
| `deploy/.env`（中身は `KOTSUKOTSU_PORT=8000` の1行） | ホスト側のポート | 8000 を使う |
| **release 署名鍵**：`~/.kotsukotsu-secrets/signing.properties` と、そこに書いた `.jks` | 配布用 APK の署名 | 署名なしの APK になり配布できない |

- **署名鍵は今と同じものを持っていく。** 別の鍵で署名すると、子供の端末で更新できない（アンインストールして入れ直しになり、九九の登録や未送信分が消える）。鍵の場所は `KOTSU_SIGNING_PROPS` で変えられる。リポジトリやチャットには置かない。
- DB とアップロード済みの APK（`deploy/data`）、初期管理者のパスワードはリポジトリに入っていない。サーバを移す場合は `deploy/backup.sh` のバックアップと `deploy/data/apk` を運ぶ。
- 配布する APK は versionCode を必ず上げる（サーバが同じ・古い番号を拒否する）。

```bash
cd server && uv sync && uv run pytest -q
cd ../android && export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
./gradlew test assembleDebug          # 鍵なしでも通る
./gradlew assembleRelease             # 鍵が必要
cd ../deploy && docker compose up -d --build   # ポートを変えるなら先に deploy/.env を作る
```

## 参照実装について

`docs/03_point-rules.md` のテストケースの期待値は、Python の参照実装で計算して確かめたものです。本番コードではないので、リポジトリには含めていません。
