"""alembic upgrade head で作ったスキーマが、モデル（create_all）と一致し、台帳トリガーを持つこと。"""
from alembic import command
from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError
import pytest

from app.db.models import Base
from app.db.session import make_engine


def test_upgrade_head_matches_models_and_has_triggers(tmp_path):
    url = f"sqlite:///{tmp_path/'m.db'}"
    cfg = Config("alembic.ini")
    cfg.set_main_option("sqlalchemy.url", url)
    command.upgrade(cfg, "head")
    engine = make_engine(url)
    with engine.connect() as conn:
        diff = compare_metadata(MigrationContext.configure(conn, opts={"compare_type": False}), Base.metadata)
        assert diff == [], diff
        conn.execute(text("INSERT INTO users(user_id, role, display_name, must_change_password, is_active, created_at, updated_at) "
                          "VALUES ('c1','child','x',0,1,'2026-01-01','2026-01-01')"))
        conn.execute(text("INSERT INTO ledger_entries(entry_id, child_user_id, type, points, occurred_at, recorded_at) "
                          "VALUES ('e1','c1','adjustment',5,'2026-01-01','2026-01-01')"))
        with pytest.raises(DBAPIError):
            conn.execute(text("UPDATE ledger_entries SET points=1"))
        conn.rollback()
    command.downgrade(cfg, "base")


def test_openapi_yaml_is_up_to_date():
    """spec/openapi.yaml がコードと一致していること。ずれたら `uv run python scripts/export_openapi.py` を実行する。"""
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
    import export_openapi
    assert export_openapi.OUT.read_text(encoding="utf-8") == export_openapi.build()
