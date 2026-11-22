import sqlite3

from app.backup import backup
from tests.test_api_flow import play_body, send


def test_online_backup_contains_data_and_rotates(env, tmp_path):
    send(env, play_body(env.fake_clock))
    db_file = env.c.app.state.engine.url.database
    outs = [backup(tmp_path / "bk", keep=2, db_path=__import__("pathlib").Path(db_file)) for _ in range(3)]
    files = sorted((tmp_path / "bk").glob("*.db"))
    assert len(files) <= 2 and outs[-1] in files
    con = sqlite3.connect(outs[-1])
    assert con.execute("SELECT SUM(points) FROM ledger_entries").fetchone()[0] == 10
