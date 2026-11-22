import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("KOTSU_DATA_DIR", "./data")).resolve()
DB_PATH = Path(os.environ.get("KOTSU_DB_PATH", str(DATA_DIR / "kotsukotsu.db")))
APK_DIR = Path(os.environ.get("KOTSU_APK_DIR", str(DATA_DIR / "apk")))
WEB_SESSION_HOURS = int(os.environ.get("KOTSU_WEB_SESSION_HOURS", "24"))
API_TOKEN_DAYS = int(os.environ.get("KOTSU_API_TOKEN_DAYS", "90"))
# 家庭内LANのHTTP運用が前提（docs/08 A4）。HTTPS化したら 1 にする
COOKIE_SECURE = os.environ.get("KOTSU_COOKIE_SECURE", "0") == "1"


def database_url() -> str:
    return f"sqlite:///{DB_PATH}"
