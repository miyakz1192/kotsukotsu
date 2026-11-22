#!/usr/bin/env bash
# ゲームを模したスクリプト：子供選択 → 登録 → プレイ送信（再送含む）→ 台帳で確認。
# 使い方: deploy/demo-game.sh [http://localhost:8000] <親のログインID> <親のパスワード>
set -euo pipefail
BASE="${1:-http://localhost:8000}"; LOGIN="${2:?login_id}"; PASS="${3:?password}"
G=(-H "X-Game-Title-Id: kuku-challenge" -H "X-App-Version: 1.0.0" -H "Content-Type: application/json")
uuid() { python3 -c 'import uuid;print(uuid.uuid4())'; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
echo "== 子供一覧"; curl -s "$BASE/api/v1/family/children"; echo
CHILD=$(curl -s "$BASE/api/v1/family/children" | python3 -c 'import sys,json;print(json.load(sys.stdin)["children"][0]["user_id"])')
echo "== 登録"; REG=$(curl -s "${G[@]}" -X POST "$BASE/api/v1/game-registrations" -d "{\"game_title_id\":\"kuku-challenge\",\"user_id\":\"$CHILD\",\"device_id\":\"demo-$(uuid)\"}")
echo "$REG" | head -c 300; echo
TOKEN=$(echo "$REG" | python3 -c 'import sys,json;print(json.load(sys.stdin)["device_token"])')
PLAY=$(uuid)
BODY=$(python3 - "$PLAY" "$(now)" <<'PY'
import json,sys
pid,t=sys.argv[1:3]
print(json.dumps({"play_id":pid,"mode":"training_select","dan":7,"started_at_device":t,"ended_at_device":t,"was_offline":False,"client_rule_version":1,
 "answers":[{"seq":i,"a":7,"b":i,"answer":7*i,"elapsed_ms":1500} for i in range(1,10)]}))
PY
)
echo "== プレイ送信（1回目）"; curl -s -o /dev/null -w "HTTP %{http_code}\n" "${G[@]}" -H "Authorization: Bearer $TOKEN" -X POST "$BASE/api/v1/play-sessions" -d "$BODY"
echo "== 同じ play_id を再送（200・台帳は増えない）"; curl -s -w "\nHTTP %{http_code}\n" "${G[@]}" -H "Authorization: Bearer $TOKEN" -X POST "$BASE/api/v1/play-sessions" -d "$BODY" | head -c 400; echo
PT=$(curl -s -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"login_id\":\"$LOGIN\",\"password\":\"$PASS\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
echo "== 台帳"; curl -s -H "Authorization: Bearer $PT" "$BASE/api/v1/children/$CHILD/ledger"; echo
