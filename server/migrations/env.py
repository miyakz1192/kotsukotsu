from alembic import context

from app.db.models import Base
from app.db.session import make_engine

target_metadata = Base.metadata


def run() -> None:
    # テストなどから接続を渡された場合はそれを使う
    connectable = context.config.attributes.get("connection")
    if connectable is None:
        engine = make_engine(context.config.get_main_option("sqlalchemy.url") or None)
        with engine.connect() as conn:
            _migrate(conn)
        engine.dispose()
    else:
        _migrate(connectable)


def _migrate(conn) -> None:
    context.configure(connection=conn, target_metadata=target_metadata, render_as_batch=True, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


run()
