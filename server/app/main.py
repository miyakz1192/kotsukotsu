from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI
from sqlalchemy.exc import OperationalError

from app import config
from app.api.v1.routes import router as v1_router
from app.db.models import Base
from app.db.session import make_engine, make_session_factory
from app.errors import ApiError, api_error_handler, db_error_handler
from app.services import bootstrap


def create_app(database_url: str | None = None, create_schema: bool = False) -> FastAPI:
    """create_schema=True はテスト用（本番は alembic upgrade head でスキーマを作る）。"""
    engine = make_engine(database_url)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if create_schema:
            Base.metadata.create_all(engine)
        with app.state.session_factory() as db:
            bootstrap.seed(db)
        yield
        engine.dispose()

    app = FastAPI(title="kotsukotsu 管理サーバ", version="1.0.0", lifespan=lifespan)
    app.state.engine = engine
    app.state.session_factory = make_session_factory(engine)
    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(OperationalError, db_error_handler)
    app.include_router(v1_router)

    from app.web.routes import Redirect, router as web_router, static_files
    from fastapi.responses import RedirectResponse

    @app.exception_handler(Redirect)
    async def _redirect(_, exc: Redirect):
        return RedirectResponse(exc.url, status_code=303)

    app.include_router(web_router)
    app.mount("/static", static_files(), name="static")
    return app


def app_factory() -> FastAPI:  # uvicorn --factory app.main:app_factory
    return create_app()
