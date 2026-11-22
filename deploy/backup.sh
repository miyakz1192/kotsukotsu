#!/usr/bin/env bash
# 毎日実行する（cron 例: 30 3 * * * /path/to/deploy/backup.sh）。
#  1. SQLite のオンラインバックアップを作る（サーバは止めない）
#  2. BACKUP_REMOTE が設定されていれば、別の機器へ rsync でコピーする（REQ-NF-06）
# 環境変数: BACKUP_DIR(既定 ./backups) / BACKUP_KEEP(既定 14) / BACKUP_REMOTE(例 user@nas:/volume1/kotsu-backup/)
set -euo pipefail
cd "$(dirname "$0")"
DATA_DIR="${KOTSU_DATA_DIR:-$PWD/data}"
BACKUP_DIR="${BACKUP_DIR:-$PWD/backups}"
if docker compose ps --status running server >/dev/null 2>&1 && [ -n "$(docker compose ps -q server 2>/dev/null)" ]; then
  # コンテナ内で作成し、ホストのバックアップ先へ取り出す
  docker compose exec -T server python -m app.backup /data/backups --keep "${BACKUP_KEEP:-14}"
  mkdir -p "$BACKUP_DIR"
  rsync -a "$DATA_DIR/backups/" "$BACKUP_DIR/"
else
  (cd ../server && KOTSU_DATA_DIR="$DATA_DIR" uv run python -m app.backup "$BACKUP_DIR" --keep "${BACKUP_KEEP:-14}")
fi
if [ -n "${BACKUP_REMOTE:-}" ]; then
  rsync -a --delete "$BACKUP_DIR/" "$BACKUP_REMOTE"
  echo "別の機器へコピーしました: $BACKUP_REMOTE"
else
  echo "注意: BACKUP_REMOTE が未設定です。同じ機器の中だけにバックアップがあります" >&2
fi
