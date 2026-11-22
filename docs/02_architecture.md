# 02. アーキテクチャ

## 1. 構成要素

```
[九九ゲーム (Android)] ──┐
[第2弾以降のゲーム]   ───┤   家庭内LAN    ┌───────── 管理サーバ ─────────┐
[親用管理アプリ (Android)]┼──────────────▶│ 共通API /api/v1               │
[管理画面 (Webブラウザ)] ─┘                │ 管理画面（Web）               │
                                            │ ポイント計算エンジン          │
                                            │ DB（データの正）／APK配布     │
                                            └───────────────────────────────┘
```

- ゲームと親用アプリは、家の外では常にオフライン。オフライン対応は日常的に使う機能として作る。
- 子供は1人1台の Android 端末を使う。

## 2. 技術スタック（推奨案・要確認）

**着手前にユーザに確認すること。** 以下は推奨で、未確定。

| 対象 | 推奨 | 理由 |
|---|---|---|
| サーバ言語・FW | Python 3.12 + FastAPI | OpenAPI を自動生成でき、Android 側のAPIクライアント生成と型を揃えやすい |
| DB | SQLite（WALモード）＋ SQLAlchemy 2.x ＋ Alembic | 1家族・低負荷なので十分。バックアップがファイルコピーで済む |
| 管理画面 | FastAPI ＋ Jinja2 ＋ htmx（サーバレンダリング） | SPA を別に持たずに済み、作る量が少ない |
| パスワードハッシュ | argon2-cffi | 標準的 |
| Android 共通 | Kotlin ＋ Jetpack Compose | 現在の標準 |
| Android 通信 | Retrofit ＋ kotlinx.serialization（または OpenAPI Generator で生成） | |
| Android ローカル保存 | Room（未送信キュー、設定値のキャッシュ） | |
| Android 同期 | WorkManager（ネットワーク接続を条件に自動再送） | 再起動後も再送が続く |
| 効果音 | SoundPool | 低遅延 |
| サーバの実行 | Docker Compose（自宅サーバ上） | |

代替案：サーバを Kotlin（Ktor）にすると Android と言語を揃えられ、ポイント計算ロジックを共有モジュールにできる。ゲーム側の即時表示とサーバの確定値を同じコードで計算できる利点があるが、管理画面の作成はやや重くなる。

## 3. ポイント計算をどこで行うか

- **確定値はサーバ**（REQ-APP-03）。
- ゲームは演出のために同じ計算をローカルで行う。表示とサーバの確定値が食い違った場合は、次回同期時にサーバの値で上書きし、「ポイントが更新されました」と伝える。
- サーバ（Python）とゲーム（Kotlin）で同じロジックを二重に持つことになるため、`03_point-rules.md` のテストケースを **両方** で実行し、一致を保証する。テストケースを JSON ファイル（`spec/point-rules-cases.json`）にして共有することを推奨。

## 4. リポジトリ構成（案）

```
/
├─ CLAUDE.md
├─ docs/                     # 本ハンドオフ資料
├─ spec/
│   ├─ openapi.yaml          # FastAPI から生成してコミット
│   └─ point-rules-cases.json
├─ server/                   # 管理サーバ＋管理画面
│   ├─ app/
│   │   ├─ api/v1/
│   │   ├─ web/              # 管理画面
│   │   ├─ domain/points/    # ポイント計算（純粋関数）
│   │   ├─ db/
│   │   └─ main.py
│   ├─ migrations/
│   └─ tests/
├─ android/
│   ├─ core/                 # APIクライアント、同期、設定キャッシュ、ポイント計算（共通）
│   ├─ game-kuku/            # 九九ゲーム
│   └─ parent-app/           # 親用管理アプリ
└─ deploy/                   # docker-compose、バックアップスクリプト
```

Android はマルチモジュール1プロジェクトにし、`core` をゲームと親用アプリで共有する。第2弾以降のゲームは `game-xxx` として追加する。
