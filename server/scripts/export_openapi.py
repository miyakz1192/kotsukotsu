"""OpenAPI を spec/openapi.yaml に書き出す。  uv run python scripts/export_openapi.py"""
import json
import sys
import tempfile
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import create_app  # noqa: E402

OUT = Path(__file__).resolve().parents[2] / "spec" / "openapi.yaml"


def build() -> str:
    with tempfile.TemporaryDirectory() as d:
        app = create_app(f"sqlite:///{d}/x.db")
        spec = json.loads(json.dumps(app.openapi()))  # 純粋な dict/list にする
        app.state.engine.dispose()
    return yaml.safe_dump(spec, allow_unicode=True, sort_keys=False)


if __name__ == "__main__":
    OUT.write_text(build(), encoding="utf-8")
    print(f"wrote {OUT}")
