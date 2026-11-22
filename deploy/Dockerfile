FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 TZ=Asia/Tokyo
WORKDIR /srv
COPY server/pyproject.toml ./
RUN pip install . 2>/dev/null || pip install fastapi uvicorn sqlalchemy alembic argon2-cffi jinja2 python-multipart pyyaml
COPY server/ ./
ENV KOTSU_DATA_DIR=/data
VOLUME /data
EXPOSE 8000
# スキーマを最新にしてから起動する。初回起動時は初期管理者のパスワードがコンソール(docker logs)に出る
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn --factory app.main:app_factory --host 0.0.0.0 --port 8000"]
