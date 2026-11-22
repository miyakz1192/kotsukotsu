"""SQLite のオンラインバックアップ（REQ-NF-06）。サーバを止めずに整合したコピーを作る。

使い方: python -m app.backup <出力ディレクトリ> [--keep 14]
"""
from __future__ import annotations

import argparse
import sqlite3
from datetime import datetime
from pathlib import Path

from app import config


def backup(dest_dir: Path, keep: int = 14, db_path: Path | None = None) -> Path:
    db_path = db_path or config.DB_PATH
    dest_dir.mkdir(parents=True, exist_ok=True)
    out = dest_dir / f"kotsukotsu-{datetime.now().strftime('%Y%m%d-%H%M%S')}.db"
    src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    dst = sqlite3.connect(out)
    try:
        src.backup(dst)  # オンラインバックアップ API
        ok = dst.execute("PRAGMA integrity_check").fetchone()[0]
        if ok != "ok":
            raise RuntimeError(f"バックアップの整合性チェックに失敗: {ok}")
    finally:
        dst.close()
        src.close()
    old = sorted(dest_dir.glob("kotsukotsu-*.db"))[:-keep] if keep > 0 else []
    for f in old:
        f.unlink()
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("dest_dir", type=Path)
    ap.add_argument("--keep", type=int, default=14, help="残す世代数")
    args = ap.parse_args()
    print(backup(args.dest_dir, args.keep))
