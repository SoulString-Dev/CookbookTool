#!/usr/bin/env bash
# 把同步服务端打成 Docker 镜像（在开发机上跑；NAS 上只要有 Docker 就能跑镜像）。
#
#   bash scripts/sync-server-image.sh                  # 构建 cookbook-sync:1
#   IMAGE=cookbook-sync:latest bash scripts/sync-server-image.sh
#   SAVE=1 bash scripts/sync-server-image.sh           # 顺手导出 tar，方便拷到 NAS
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${IMAGE:-cookbook-sync:1}"

command -v docker >/dev/null 2>&1 || {
  echo "这台机器上没装 docker。镜像必须在有 docker 的机器上构建；" >&2
  echo "或者在开发机上 SAVE=1 导出 tar，再到 NAS 上 docker load -i 载入。" >&2
  exit 1
}

echo "== 打胖包"
bash "$ROOT/scripts/build.sh" :sync-server:fatJar
JAR="$ROOT/sync-server/build/libs/sync-server-all.jar"
[[ -f "$JAR" ]] || { echo "没找到胖包：$JAR" >&2; exit 1; }
mkdir -p "$ROOT/deploy"
cp "$JAR" "$ROOT/deploy/cookbook-sync.jar"
echo "   胖包 $(du -h "$ROOT/deploy/cookbook-sync.jar" | cut -f1) → deploy/cookbook-sync.jar"

echo "== 构建镜像 $IMAGE"
docker build -t "$IMAGE" "$ROOT/deploy"

if [[ "${SAVE:-0}" == "1" ]]; then
  mkdir -p "$ROOT/dist"
  OUT="$ROOT/dist/cookbook-sync-image.tar"
  echo "== 导出镜像到 $OUT"
  docker save -o "$OUT" "$IMAGE"
  ls -lh "$OUT"
  echo "   拷到 NAS 后：docker load -i cookbook-sync-image.tar"
fi

echo "好了：$IMAGE"
