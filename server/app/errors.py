from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse
from sqlalchemy.exc import OperationalError


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)


def err_body(code: str, message: str) -> dict:
    return {"error": {"code": code, "message": message}}


def unauthorized(msg: str = "認証に失敗しました") -> ApiError:
    return ApiError(401, "UNAUTHORIZED", msg)


def invalid(msg: str) -> ApiError:
    return ApiError(400, "INVALID_REQUEST", msg)


def forbidden(msg: str = "権限がありません") -> ApiError:
    return ApiError(403, "FORBIDDEN", msg)


async def api_error_handler(_: Request, exc: ApiError):
    return JSONResponse(err_body(exc.code, exc.message), status_code=exc.status)


async def db_error_handler(_: Request, exc: OperationalError):
    # DB のトランザクション異常（ロック・I/Oエラーなど）。クライアントは API ごとの規則に従う
    return JSONResponse(err_body("TRANSACTION_FAILED", "サーバでトランザクションエラーが発生しました"), status_code=503)
