#!/usr/bin/env bash
# =============================================================================
#  GateKeeper 镜像构建 / 打标签 / 推送 / 导出（一键）
# =============================================================================
#  用法（在仓库任意位置执行均可）：
#      bash docker/build-and-push.sh                  # 只构建并打标签
#      bash docker/build-and-push.sh --push           # 构建后推送到 Docker Hub
#      bash docker/build-and-push.sh --push --save    # 再额外导出镜像 tar 包
#      bash docker/build-and-push.sh --latest         # 附带 latest 标签
#
#  可覆盖的环境变量：
#      GK_DOCKER_NAMESPACE   Docker Hub 命名空间，默认 changeonly
#      GK_VERSION            版本号，默认从 src/backend/pom.xml 读取
#
#  标签命名（单仓库多 tag）：
#      <namespace>/gatekeeper:backend-<version>
#      <namespace>/gatekeeper:frontend-<version>
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NAMESPACE="${GK_DOCKER_NAMESPACE:-changeonly}"

# ---------- 版本号：优先环境变量，否则从后端 pom.xml 读取 ----------
if [ -z "${GK_VERSION:-}" ]; then
  GK_VERSION="$(sed -n '/<artifactId>gatekeeper<\/artifactId>/{n;s/.*<version>\([^<]*\)<\/version>.*/\1/p;q;}' \
                 "$REPO_ROOT/src/backend/pom.xml")"
fi
if [ -z "${GK_VERSION:-}" ]; then
  echo "FATAL: 无法确定版本号，请显式设置 GK_VERSION=x.y.z" >&2
  exit 1
fi

# 前端 package.json 的 version 必须与后端一致，否则镜像内容与标签会不自洽
FE_VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
              "$REPO_ROOT/src/frontend/package.json" | head -1)"
if [ "$FE_VERSION" != "$GK_VERSION" ]; then
  echo "FATAL: 版本号不一致 —— 后端 pom.xml=$GK_VERSION，前端 package.json=$FE_VERSION" >&2
  exit 1
fi

PUSH=0; SAVE=0; LATEST=0
for a in "$@"; do
  case "$a" in
    --push)   PUSH=1 ;;
    --save)   SAVE=1 ;;
    --latest) LATEST=1 ;;
    *) echo "未知参数: $a" >&2; exit 2 ;;
  esac
done

BE_TAG="$NAMESPACE/gatekeeper:backend-$GK_VERSION"
FE_TAG="$NAMESPACE/gatekeeper:frontend-$GK_VERSION"

echo "=============================================================="
echo " 仓库根目录 : $REPO_ROOT"
echo " 命名空间   : $NAMESPACE"
echo " 版本号     : $GK_VERSION"
echo " 后端标签   : $BE_TAG"
echo " 前端标签   : $FE_TAG"
echo "=============================================================="

# ---------- 前置安全校验 ----------
# 这是本项目最容易出事的一步：src/main/resources/application.yml 未入库但本地存在，
# 含真实密钥与内网库地址。.gitignore 管不住 docker build 的上下文，
# 一旦 .dockerignore 漏排，密钥就会被烤进镜像并推到公开仓库。
if ! grep -qE '^src/main/resources/application\.yml[[:space:]]*$' \
        "$REPO_ROOT/src/backend/.dockerignore"; then
  echo "FATAL: src/backend/.dockerignore 未显式排除 src/main/resources/application.yml" >&2
  echo "       继续构建会把真实密钥与内网地址打进镜像。已中止。" >&2
  exit 1
fi
if ! grep -qE 'cp +src/main/resources/application\.example\.yml' \
        "$REPO_ROOT/src/backend/Dockerfile"; then
  echo "FATAL: src/backend/Dockerfile 未用模板顶替运行配置，镜像将缺少 server/dataSource 配置。" >&2
  exit 1
fi
echo "[preflight] .dockerignore 与 Dockerfile 的安全前置条件均满足 ✅"

# ---------- 构建 ----------
docker build \
  --build-arg "VERSION=$GK_VERSION" \
  -t "$BE_TAG" \
  -f "$REPO_ROOT/src/backend/Dockerfile" \
  "$REPO_ROOT/src/backend"

docker build \
  --build-arg "VERSION=$GK_VERSION" \
  -t "$FE_TAG" \
  -f "$REPO_ROOT/src/frontend/Dockerfile" \
  "$REPO_ROOT/src/frontend"

if [ "$LATEST" = "1" ]; then
  docker tag "$BE_TAG" "$NAMESPACE/gatekeeper:backend-latest"
  docker tag "$FE_TAG" "$NAMESPACE/gatekeeper:frontend-latest"
  echo "[tag] 已附加 backend-latest / frontend-latest"
fi

echo "[build] 完成。镜像内 OCI 标签 org.opencontainers.image.version=$GK_VERSION"
docker image inspect "$BE_TAG" --format '  {{.RepoTags}} size={{.Size}}'
docker image inspect "$FE_TAG" --format '  {{.RepoTags}} size={{.Size}}'

# ---------- 推送 ----------
if [ "$PUSH" = "1" ]; then
  echo "--------------------------------------------------------------"
  echo " 推送到 Docker Hub ..."
  # 注意：Docker Hub 的命名空间通常是小写；若你的账号含大写请确认已按 Hub 规则转换
  docker push "$BE_TAG"
  docker push "$FE_TAG"
  if [ "$LATEST" = "1" ]; then
    docker push "$NAMESPACE/gatekeeper:backend-latest"
    docker push "$NAMESPACE/gatekeeper:frontend-latest"
  fi
  echo "[push] 完成。建议在 Hub 上核对 digest："
  docker image inspect "$BE_TAG" --format '  {{index .RepoDigests 0}}'
  docker image inspect "$FE_TAG" --format '  {{index .RepoDigests 0}}'
fi

# ---------- 导出 ----------
if [ "$SAVE" = "1" ]; then
  OUTDIR="$REPO_ROOT/dist/docker"
  mkdir -p "$OUTDIR"
  # 注意：镜像内已含真实密钥的替代品（模板），此 tar 可安全分发
  docker save -o "$OUTDIR/gatekeeper-backend-$GK_VERSION.tar" "$BE_TAG"
  docker save -o "$OUTDIR/gatekeeper-frontend-$GK_VERSION.tar" "$FE_TAG"
  ( cd "$OUTDIR" && sha256sum "gatekeeper-backend-$GK_VERSION.tar" \
                              "gatekeeper-frontend-$GK_VERSION.tar" \
      > "gatekeeper-docker-$GK_VERSION-SHA256SUMS.txt" )
  echo "[save] 已导出到 $OUTDIR"
  ls -la "$OUTDIR"
fi

echo "=============================================================="
echo " 全部完成。"
echo " 运行示例（需先准备 MySQL / Redis，或直接 docker compose up -d）："
echo "   docker run -d --name gk-backend -p 8080:8080 \\"
echo "     -e GATEKEEPER_DB_HOST=<db> -e GATEKEEPER_DB_PASSWORD=<pwd> \\"
echo "     -e GATEKEEPER_REDIS_HOST=<redis> -e GATEKEEPER_REDIS_PASSWORD=<pwd> \\"
echo "     -e GATEKEEPER_JWT_SECRET=<32+位随机串> -e GATEKEEPER_AES_KEY=<32+位随机串> \\"
echo "     $BE_TAG"
echo "   docker run -d --name gk-frontend -p 8081:80 $FE_TAG"
echo "=============================================================="
