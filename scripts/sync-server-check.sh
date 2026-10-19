#!/usr/bin/env bash
# 同步服务端自检：起一个临时实例，真用 curl 走一遍完整流程
#（配对 → 推送 → 另一台设备拉取 → 传图 → 删除墓碑 → 健康检查）。
#
#   bash scripts/sync-server-check.sh
#   PORT=19099 KEEP=1 bash scripts/sync-server-check.sh   # 换端口 / 保留临时数据
#
# 依赖 curl 与 python3（解析 JSON）。不需要装任何第三方服务。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
PORT="${PORT:-18099}"
PAIR_CODE="246810"
WORK="$(mktemp -d)"
LOGFILE="$WORK/server.log"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  if [[ "${KEEP:-0}" == "1" ]]; then
    echo "临时数据保留在 $WORK（日志 $LOGFILE）"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

step() { echo; echo "== $*"; }
ok() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; exit 1; }

# 从 JSON 里取值：field '<json>' 'd["token"]'（表达式由本脚本自己写，不来自外部输入）
field() { printf '%s' "$1" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$2"; }
assert_eq() { [[ "$2" == "$3" ]] || fail "$1：期望 [$2]，实际 [$3]"; ok "$1"; }

STEP_TOTAL=7
step "1/$STEP_TOTAL 打胖包"
bash "$ROOT/scripts/build.sh" :sync-server:fatJar >"$WORK/build.log" 2>&1 ||
  { tail -30 "$WORK/build.log" >&2; fail "构建失败"; }
JAR="$(ls "$ROOT"/sync-server/build/libs/sync-server-all.jar 2>/dev/null || true)"
[[ -f "$JAR" ]] || fail "没找到胖包（sync-server/build/libs/sync-server-all.jar）"
ok "$(basename "$JAR")（$(du -h "$JAR" | cut -f1)）"

step "2/$STEP_TOTAL 起服务并等健康检查"
"$TC/jdk/bin/java" -jar "$JAR" --port "$PORT" --data "$WORK/data" --pair-code "$PAIR_CODE" \
  >"$LOGFILE" 2>&1 &
SERVER_PID=$!
HEALTH=""
for _ in $(seq 1 40); do
  if HEALTH=$(curl -fsS "http://127.0.0.1:$PORT/api/v1/health" 2>/dev/null); then break; fi
  HEALTH=""
  sleep 0.25
done
if [[ -z "$HEALTH" ]]; then
  tail -40 "$LOGFILE" >&2 || true
  fail "服务没起来（端口 $PORT 是不是被占了？）"
fi
assert_eq "健康检查 ok" "True" "$(field "$HEALTH" 'd["ok"]')"
assert_eq "空库时水位是 0" "0" "$(field "$HEALTH" 'd["serverRev"]')"

step "3/$STEP_TOTAL 两台设备配对"
pair() {
  curl -fsS -X POST "http://127.0.0.1:$PORT/api/v1/pair" -H 'Content-Type: application/json' \
    -d "{\"code\":\"$PAIR_CODE\",\"deviceName\":\"$1\"}"
}
PAIR_A=$(pair "甲的手机")
PAIR_B=$(pair "乙的手机")
TOKEN_A=$(field "$PAIR_A" 'd["token"]')
TOKEN_B=$(field "$PAIR_B" 'd["token"]')
DEV_A=$(field "$PAIR_A" 'd["deviceId"]')
DEV_B=$(field "$PAIR_B" 'd["deviceId"]')
[[ -n "$TOKEN_A" && -n "$TOKEN_B" ]] || fail "没拿到令牌"
[[ "$TOKEN_A" != "$TOKEN_B" ]] || fail "两台设备拿到了同一个令牌"
ok "两台设备各拿到一个令牌"
WRONG=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$PORT/api/v1/pair" \
  -H 'Content-Type: application/json' -d '{"code":"000000","deviceName":"坏设备"}')
assert_eq "错的配对码被拒（403）" "403" "$WRONG"

step "4/$STEP_TOTAL 甲推一条带分类和图片的食谱"
UUID="check-recipe-1"
printf '假装是一张图' >"$WORK/photo.jpg"
IMAGE_SHA=$(sha256sum <"$WORK/photo.jpg" | cut -d' ' -f1)
PUSH_BODY=$(python3 - "$UUID" "$DEV_A" "$IMAGE_SHA" <<'PY'
import json, sys
uuid, dev, sha = sys.argv[1:4]
payload = {
    "title": "红烧肉",
    "createdAt": 1700000000000,
    "updatedAt": 1700000000000,
    "categories": [{"uuid": "cat-1", "name": "家常菜"}],
    "ingredients": [{"name": "五花肉", "amount": "500", "unit": "克"}],
    "content": [{"section": "DIRECTIONS", "kind": "LIST_ITEM", "text": "切块焯水"}],
    "images": [{"sha256": sha, "width": 800, "height": 600, "isCover": True}],
}
print(json.dumps({"deviceId": dev, "baseRev": 0,
                  "upserts": [{"uuid": uuid, "updatedAt": 1700000000000, "payload": payload}]}))
PY
)
PUSHED=$(curl -fsS -X POST "http://127.0.0.1:$PORT/api/v1/sync" \
  -H "Authorization: Bearer $TOKEN_A" -H 'Content-Type: application/json' -d "$PUSH_BODY")
assert_eq "服务端落地 1 条" "1" "$(field "$PUSHED" 'len(d["records"])')"
assert_eq "这条拿到自己的 rev=1" "1" "$(field "$PUSHED" 'd["records"][0]["rev"]')"

step "5/$STEP_TOTAL 乙拉取（增量水位 + 分类走 uuid）"
PULLED=$(curl -fsS "http://127.0.0.1:$PORT/api/v1/sync?since=0" -H "Authorization: Bearer $TOKEN_B")
assert_eq "乙拉到 1 条" "1" "$(field "$PULLED" 'len(d["recipes"])')"
assert_eq "菜名对得上" "红烧肉" "$(field "$PULLED" 'd["recipes"][0]["payload"]["title"]')"
assert_eq "水位推进到这条自己的 rev" "1" "$(field "$PULLED" 'd["nextSince"]')"
assert_eq "分类带的是 uuid 不是本机 id" "cat-1" "$(field "$PULLED" 'd["recipes"][0]["payload"]["categories"][0]["uuid"]')"

step "6/$STEP_TOTAL 图片按内容哈希传与取"
PUT_STATUS=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "http://127.0.0.1:$PORT/api/v1/blobs/$IMAGE_SHA" \
  -H "Authorization: Bearer $TOKEN_A" --data-binary "@$WORK/photo.jpg")
assert_eq "上传返回 204" "204" "$PUT_STATUS"
curl -fsS "http://127.0.0.1:$PORT/api/v1/blobs/$IMAGE_SHA" \
  -H "Authorization: Bearer $TOKEN_B" -o "$WORK/downloaded.jpg"
assert_eq "乙取到的图与甲传的逐字节一致" \
  "$(sha256sum <"$WORK/photo.jpg" | cut -d' ' -f1)" \
  "$(sha256sum <"$WORK/downloaded.jpg" | cut -d' ' -f1)"
ZERO64=$(python3 -c 'print("0" * 64)')
MISSING=$(curl -fsS -X POST "http://127.0.0.1:$PORT/api/v1/blobs/check" \
  -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' \
  -d "{\"sha256\":[\"$IMAGE_SHA\",\"$ZERO64\"]}")
assert_eq "批量问只报缺的那张" "1" "$(field "$MISSING" 'len(d["missing"])')"
assert_eq "缺的正是没传过的那张" "$ZERO64" "$(field "$MISSING" 'd["missing"][0]')"
WRONG_HASH=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT "http://127.0.0.1:$PORT/api/v1/blobs/$ZERO64" \
  -H "Authorization: Bearer $TOKEN_A" --data-binary "@$WORK/photo.jpg")
assert_eq "内容与文件名不符被拒（400）" "400" "$WRONG_HASH"

step "7/$STEP_TOTAL 删除变墓碑，别的设备拉得到"
DELETE_BODY=$(python3 - "$UUID" "$DEV_B" <<'PY'
import json, sys
print(json.dumps({"deviceId": sys.argv[2], "baseRev": 1,
                  "deletes": [{"uuid": sys.argv[1], "deletedAt": 1700000009000}]}))
PY
)
DELETED=$(curl -fsS -X POST "http://127.0.0.1:$PORT/api/v1/sync" \
  -H "Authorization: Bearer $TOKEN_B" -H 'Content-Type: application/json' -d "$DELETE_BODY")
assert_eq "删除落地成墓碑" "True" "$(field "$DELETED" 'd["records"][0]["deleted"]')"
PULLED2=$(curl -fsS "http://127.0.0.1:$PORT/api/v1/sync?since=0" -H "Authorization: Bearer $TOKEN_A")
assert_eq "甲拉得到墓碑" "True" "$(field "$PULLED2" 'd["recipes"][-1]["deleted"]')"
assert_eq "墓碑不带载荷" "True" "$(field "$PULLED2" '"payload" not in d["recipes"][-1]')"
HEALTH2=$(curl -fsS "http://127.0.0.1:$PORT/api/v1/health")
assert_eq "活着的食谱数是 0" "0" "$(field "$HEALTH2" 'd["recipes"]')"
assert_eq "服务端水位到 2" "2" "$(field "$HEALTH2" 'd["serverRev"]')"

echo
echo "全部通过 ✅"
