#!/usr/bin/env bash
# ============================================================
# GateKeeper 纯 Release 离线部署脚本
#
# 目标：在「无源码、无 git、无 Docker 缓存」的干净主机上，
#       仅凭 GitHub Release 的发布物把全栈跑起来，并自动执行/校验初始化脚本。
#
# 用法（在目标机执行）：
#   ./deploy-from-release.sh clean     # 彻底清理：容器 + 卷 + 镜像 + 部署目录
#   ./deploy-from-release.sh all       # 下载 + 校验 + 解包 + 生成配置 + load + up + 断言
#   ./deploy-from-release.sh fetch     # 只做下载/校验/解包/生成配置
#   ./deploy-from-release.sh up        # 只起栈（假定已 fetch + load）
#   ./deploy-from-release.sh verify    # 只跑初始化链断言 + 探活
#   ./deploy-from-release.sh logs      # 看 mysql initdb 执行记录
#
# 可覆盖的环境变量：
#   GK_REPO / GK_TAG / GK_VER / GK_DIR / GK_PARALLEL
#
# ---- 怎么把它弄到目标机上（重要）----
# ⚠️ 不要依赖 raw.githubusercontent.com —— 实测该域名在受限网络里**不可达**，
#    而 github.com / codeload.github.com / api.github.com 通常可达。
# 可行做法（按推荐顺序）：
#   ① 本脚本已作为 Release 资产发布，走资产通道取（不写死 asset id，避免换版本后过期）：
#        TAG=v1.0.3
#        ID=$(curl -s "https://api.github.com/repos/Change-Only/GateKeeper/releases/tags/$TAG" \
#             | python3 -c 'import sys,json;print([a["id"] for a in json.load(sys.stdin)["assets"] \
#                 if a["name"]=="deploy-from-release.sh"][0])')
#        curl -fsSL -o deploy-from-release.sh -H 'Accept: application/octet-stream' \
#             "https://api.github.com/repos/Change-Only/GateKeeper/releases/assets/$ID"
#      （该 URL 会 302 跳到 release-assets.githubusercontent.com —— 资产的真实存储域）
#   ② 直链（github.com 可达时更省事）：
#        curl -fsSL -O https://github.com/Change-Only/GateKeeper/releases/download/v1.0.3/deploy-from-release.sh
# ============================================================
set -uo pipefail

GK_REPO="${GK_REPO:-Change-Only/GateKeeper}"
GK_TAG="${GK_TAG:-v1.0.3}"
GK_VER="${GK_VER:-1.0.3}"
GK_DIR="${GK_DIR:-/opt/gatekeeper-release}"
GK_PARALLEL="${GK_PARALLEL:-4}"

# curl 重试参数由 precheck() 按 curl 版本填充（7.29 不支持 --retry-connrefused）。
# 此处先给默认值：dl_try()/pick_channel() 隐式依赖它，若只单独驱动这些函数
# （排障 / 单测）而没有先跑 precheck，`set -u` 会直接报「CURL_RETRY: 未绑定变量」。
CURL_RETRY="${CURL_RETRY:-}"

GK_API="https://api.github.com/repos/${GK_REPO}/releases"
BACKEND_IMAGE="changeonly/gatekeeper:backend-${GK_VER}"
FRONTEND_IMAGE="changeonly/gatekeeper:frontend-${GK_VER}"

DL_DIR="${GK_DIR}/dl"
DOCKER_ZIP="gatekeeper-docker-${GK_VER}.zip"
SQL_ZIP="gatekeeper-sql-${GK_VER}.zip"
JAR="gatekeeper-backend-${GK_VER}.jar"
SUMS_SRC="gatekeeper-${GK_VER}-SHA256SUMS.txt"
SUMS_DOCKER="gatekeeper-docker-${GK_VER}-SHA256SUMS.txt"

# ---- 版本指纹表 ----
# 这些是「该版本 schema/资产」的事实性断言，随版本变化。
# 换版本时在此登记；未登记的版本会自动跳过这些断言（其余流程照常），
# 因此本脚本可跨版本复用，而不会因为指纹过期产生假 FAIL。
case "$GK_VER" in
  1.0.2|1.0.3)
    # 1.0.3 的 init.sql 与 1.0.2 逐字节相同（本次只改增量脚本的字符集声明
    # 与部署配置，全量基线未动）—— 故两者共用同一指纹。
    EXPECT_INIT_SQL_SHA="7d14bb97c25cf577c94a9f8db8254e69e85d6dc5ead4f6ad47e8f8f22362dcb6"
    EXPECT_TABLES=42
    EXPECT_MENU=121
    EXPECT_CONFIG=6
    # 字符集乱码必须为 0（2026-09-29 定位的缺陷：initdb 客户端 latin1
    # ⇒ 无 SET NAMES 的脚本把中文双重编码）。0.0.0 表示"该版本应有此断言"。
    EXPECT_MOJIBAKE=0
    ;;
  *)
    EXPECT_INIT_SQL_SHA=""
    EXPECT_TABLES=""
    EXPECT_MENU=""
    EXPECT_CONFIG=""
    EXPECT_MOJIBAKE=""
    ;;
esac
# initdb 挂载数不写死 —— 直接从 Release 里的 compose 文件数出来（自洽、跨版本）
EXPECT_INITDB_FILES=""

FAILED=0

# ---------------- 输出helper ----------------
c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_bad()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_inf()  { printf '\033[36m%s\033[0m\n' "$*"; }
step()   { printf '\n\033[1;36m===== %s =====\033[0m\n' "$*"; }
ok()     { c_ok   "  [OK]   $*"; }
bad()    { c_bad  "  [FAIL] $*"; FAILED=$((FAILED+1)); }
info()   { printf '  %s\n' "$*"; }

# ---------------- 前置检查 ----------------
precheck() {
  step "0) 前置检查"
  local miss=0
  for c in curl python3 docker docker-compose openssl sha256sum; do
    if command -v "$c" >/dev/null 2>&1; then ok "$c -> $(command -v "$c")"
    else bad "$c 缺失"; miss=1; fi
  done
  python3 - <<'PY'
import sys
if sys.version_info < (3, 5):
    print("  [FAIL] python3 版本过低: %s" % sys.version.split()[0]); sys.exit(1)
print("  [OK]   python3 %s" % sys.version.split()[0])
PY
  # curl 能力探测：--retry-connrefused 需 curl >= 7.52（CentOS 7 自带 7.29 无此选项）
  CURL_RETRY="--retry 3 --retry-delay 3"
  if curl --retry-connrefused --version >/dev/null 2>&1; then
    CURL_RETRY="${CURL_RETRY} --retry-connrefused"
    info "curl $(curl --version 2>/dev/null | head -1 | awk '{print $2}')：支持 --retry-connrefused，已启用"
  else
    info "curl $(curl --version 2>/dev/null | head -1 | awk '{print $2}')：不支持 --retry-connrefused，已自动降级（仅 --retry）"
  fi
  [ "$miss" = "0" ] || fail "前置依赖缺失，无法继续"
  # unzip 通常缺失，仅提示（本脚本用 python3 zipfile 替代）
  command -v unzip >/dev/null 2>&1 || info "提示：系统无 unzip，解包改用 python3 zipfile（已内置）"
}

fail() { c_bad "!! $*"; exit 1; }

# ---------------- 1) 彻底清理 ----------------
do_clean() {
  step "1) 彻底清理（容器 / 卷 / 镜像 / 网络 / 源码目录）"

  info "-- 停止并删除所有 gatekeeper 容器 + 卷 --"
  for d in "$GK_DIR" /opt/gatekeeper; do
    if [ -f "$d/docker-compose.yml" ]; then
      ( cd "$d" && docker-compose down -v --remove-orphans 2>&1 | sed 's/^/     /' ) || true
    fi
  done
  # 兜底：按名字硬删（compose 文件已不在时）
  for n in gatekeeper-frontend gatekeeper-backend gatekeeper-mysql gatekeeper-redis gatekeeper-mock-upstream; do
    docker rm -f "$n" >/dev/null 2>&1 && info "removed container $n" || true
  done

  info "-- 删除全部镜像（含悬空 none） --"
  # 单轮 rmi 会因镜像间引用关系残留（删父镜像后子镜像仍在）⇒ 迭代至空
  local round=0 left=1
  while [ "$round" -lt 6 ]; do
    local ids cnt
    ids=$(docker images -aq 2>/dev/null | sort -u)
    cnt=$(printf '%s\n' "$ids" | grep -c . 2>/dev/null || true)
    [ "$cnt" = "0" ] && { left=0; break; }
    round=$((round+1))
    info "  第 $round 轮：待删 $cnt 个"
    for i in $ids; do docker rmi -f "$i" >/dev/null 2>&1 || true; done
  done
  docker image prune -a -f >/dev/null 2>&1 || true
  if [ "$left" = "0" ]; then info "  镜像已全部清空"
  else info "  仍有 $(docker images -aq 2>/dev/null | sort -u | grep -c . || true) 个镜像残留"; fi

  info "-- 清理卷 / 网络 / build 缓存 --"
  docker volume prune -f  >/dev/null 2>&1 && info "volume prune done"
  docker network prune -f >/dev/null 2>&1 && info "network prune done"
  docker builder prune -f >/dev/null 2>&1 && info "builder prune done"

  info "-- 删除源码与工具目录 --"
  for d in /opt/gatekeeper /opt/gatekeeper-tools /opt/gatekeeper-release; do
    [ -e "$d" ] && rm -rf "$d" && info "rm -rf $d" || true
  done

  info ""
  info "残留核验："
  printf '    容器  : %s 个\n' "$(docker ps -aq 2>/dev/null | wc -l)"
  printf '    镜像  : %s 个\n' "$(docker images -aq 2>/dev/null | sort -u | wc -l)"
  printf '    卷    : %s 个\n' "$(docker volume ls -q 2>/dev/null | wc -l)"
  printf '    /opt  : %s\n' "$(ls /opt | tr '\n' ' ')"
  printf '    docker 磁盘占用: %s\n' "$(docker system df --format '{{.Type}}={{.Size}}' 2>/dev/null | tr '\n' ' ')"
  c_inf "  注：/etc/docker/daemon.json 的镜像加速器已保留（否则 Docker Hub 不可达，基础镜像无法拉取）"
}

# ---------------- 2) 枚举 + 下载 ----------------
fetch_assets() {
  step "2) 从 GitHub Release 下载发布物"
  mkdir -p "$DL_DIR"

  info "-- 枚举 $GK_TAG 的 asset（元数据统一走 api.github.com，它比 github.com 稳定）--"
  if ! curl -s --max-time 60 -H "Accept: application/vnd.github+json" \
        -o "${DL_DIR}/.release.json" -w '     http=%{http_code}\n' \
        "${GK_API}/tags/${GK_TAG}"; then
    fail "Release 元数据获取失败"
  fi
  python3 - "$DL_DIR" <<'PY' || fail "asset 枚举解析失败"
import json, sys, os
d = sys.argv[1]
p = os.path.join(d, '.release.json')
try:
    rel = json.load(open(p, encoding='utf-8'))
except Exception as e:
    print('  [FAIL] release.json 解析失败: %s' % e); sys.exit(1)
if 'assets' not in rel:
    print('  [FAIL] 返回体无 assets 字段（可能是 API 限流或 tag 不存在）'); sys.exit(1)
rows = []
for a in rel['assets']:
    if a.get('state') != 'uploaded':
        continue
    rows.append('%s\t%d\t%d' % (a['name'], a['id'], a['size']))
open(os.path.join(d, '.assets.tsv'), 'w').write('\n'.join(rows) + '\n')
print('  [OK]   tag=%s  资产数=%d  合计=%.1f MB' % (
    rel.get('tag_name'), len(rows), sum(int(r.split('\t')[2]) for r in rows) / 1048576))
PY

  pick_channel

  info "-- 下载（逐资产故障转移：首选=$(dl_modes | awk '{print $1}')，失败即换通道；大文件切 ${GK_PARALLEL} 段）--"
  local t0 t1 dl_fail=0
  t0=$(date +%s)
  while IFS=$'\t' read -r name id size; do
    [ -n "$name" ] || continue
    dl_one "$name" "$id" "$size" || dl_fail=$((dl_fail + 1))
  done < "${DL_DIR}/.assets.tsv"
  t1=$(date +%s)
  info ""
  info "下载耗时 $((t1-t0)) 秒；下载失败 $dl_fail 项"
  info ""
  info "文件清单："
  ls -la "$DL_DIR" | grep -v '^total' | grep -v '^d' | awk '{printf "     %12d  %s\n", $5, $9}'
}

# ---------------- 下载通道选择 ----------------
# 为什么要有「多通道」：不同网络对 GitHub 各域名的放通策略不同，**且会随策略变动**。
# 同一台目标机上的实测反差：
#   · 第一轮：github.com:443 超时，只有 api.github.com 通 ⇒ 直链不可用
#   · 复核轮：github.com / codeload / api 全 200，而 raw.githubusercontent.com 仍不通
# 所以不把「某域名不可达」写死成前提：起下载前先用【最小的资产】探一次，选定通道后全局沿用
# （可达性是网络级属性，与单个资产无关）。
#
#   通道 direct：https://github.com/<repo>/releases/download/<tag>/<name>
#   通道 api   ：https://api.github.com/repos/<repo>/releases/assets/<id>
#                （Accept: application/octet-stream → 302 跳到
#                  release-assets.githubusercontent.com，资产的真实存储域）
# 🔴 探测结果只是【首选通道】，不是全局开关。
# 2026-09-29 实测（v1.0.3 复验）：探测时 github.com 直链可用 ⇒ CH_MODE=direct，下载途中
# github.com:443 被阻断（curl: (7) 拒绝连接），后续 5 个资产的下载全部失败，整批 fetch
# 在第 3 步 SHA256 校验处中止。**同一台机上** direct 尝试全部返回 0 字节，而 api 回落全部成功；
# 随后 github.com 又短暂恢复（同一批里最后一项直链成功）⇒ 是**间歇性**劣化，不是域名级永久不可达。
# 故不能「探一次、全局沿用」：dl_one() 必须做【逐资产故障转移】——
# 某资产在某通道失败（curl 非 0 或尺寸不符），立刻用另一通道重试**同一资产**。
CH_MODE="api"                                   # 首选通道，由 pick_channel 覆盖
GK_DL_MODE="${GK_DL_MODE:-auto}"                # auto（默认，带回落）| direct | api（指定则不回落）

ch_url() {  # ch_url <name> <id> <mode>
  if [ "$3" = "direct" ]; then
    printf '%s' "https://github.com/${GK_REPO}/releases/download/${GK_TAG}/$1"
  else
    printf '%s' "${GK_API}/assets/$2"
  fi
}

# 尝试顺序：auto ⇒ 首选通道在前、另一通道兜底；显式指定 ⇒ 只试该通道（便于复现/排障）
dl_modes() {
  case "$GK_DL_MODE" in
    direct) printf 'direct' ;;
    api)    printf 'api' ;;
    *)      if [ "$CH_MODE" = "direct" ]; then printf 'direct api'; else printf 'api direct'; fi ;;
  esac
}

pick_channel() {
  local nm id sz got
  sort -t"$(printf '\t')" -k3 -n "${DL_DIR}/.assets.tsv" 2>/dev/null | head -1 > "${DL_DIR}/.smallest"
  read -r nm id sz < "${DL_DIR}/.smallest" || true
  [ -n "${nm:-}" ] || { CH_MODE="api"; info "     （资产清单为空，默认 assets API）"; return 0; }
  printf '     探测下载通道（用最小资产 %s，%s B）... ' "$nm" "$sz"
  got=$(curl -sL --max-time 60 $CURL_RETRY -H "Accept: application/octet-stream" \
          "https://github.com/${GK_REPO}/releases/download/${GK_TAG}/${nm}" 2>/dev/null | wc -c)
  if [ "$got" = "$sz" ]; then
    CH_MODE="direct"; printf 'github.com 直链可用 ✅（仅作首选，失败会自动回落 api）\n'
  else
    CH_MODE="api"; printf '直链得 %s B（期望 %s），改用 assets API ✅（失败会自动回落 direct）\n' "$got" "$sz"
  fi
}

# 单通道下载：**尺寸精确匹配**才算成功（返回 0），否则返回 1 交由 dl_one 换通道
dl_try() {  # dl_try <name> <id> <size> <mode>
  local name="$1" id="$2" size="$3" mode="$4"
  local out="${DL_DIR}/${name}"
  local url; url=$(ch_url "$name" "$id" "$mode")
  local minsz=$((8 * 1024 * 1024))

  rm -f "$out" "${out}".part*

  if [ "$size" -le "$minsz" ] || [ "${GK_PARALLEL}" -le 1 ]; then
    printf '     [%-6s] 下载 %-40s %10d B ... ' "$mode" "$name" "$size"
    if ! curl -sL --max-time 900 $CURL_RETRY \
         -H "Accept: application/octet-stream" -o "$out" "$url"; then
      printf 'curl 失败\n'; return 1
    fi
  else
    local n="$GK_PARALLEL"
    local seg=$(( (size + n - 1) / n ))
    printf '     [%-6s] 分段 %-40s %10d B (%d 段) ... ' "$mode" "$name" "$size" "$n"
    local i=0
    while [ $i -lt $n ]; do
      local s=$(( i * seg ))
      local e=$(( s + seg - 1 ))
      [ "$e" -ge "$size" ] && e=$(( size - 1 ))
      [ "$s" -gt "$e" ] && break
      (
        curl -sL --max-time 1800 $CURL_RETRY \
          -H "Accept: application/octet-stream" \
          -r "${s}-${e}" -o "$(printf '%s.part%03d' "$out" "$i")" "$url"
      ) &
      i=$(( i + 1 ))
    done
    wait
    # 按序号拼接
    cat "${out}".part* > "$out" 2>/dev/null
    rm -f "${out}".part*
  fi

  local got; got=$(stat -c%s "$out" 2>/dev/null || echo 0)
  if [ "$got" = "$size" ]; then printf 'OK\n'; return 0; fi
  printf '尺寸不符 (%s/%s)\n' "$got" "$size"
  return 1
}

# 逐资产故障转移：某通道失败就地换另一通道重试**同一资产**
dl_one() {
  local name="$1" id="$2" size="$3"
  local out="${DL_DIR}/${name}"

  if [ -f "$out" ]; then
    local cur; cur=$(stat -c%s "$out" 2>/dev/null || echo 0)
    if [ "$cur" = "$size" ]; then info "跳过（已完整）: $name"; return 0; fi
  fi

  local m
  for m in $(dl_modes); do
    if dl_try "$name" "$id" "$size" "$m"; then return 0; fi
    info "            ↑ 通道 $m 失败，改用下一通道重试同一资产"
  done

  bad "下载失败（已试遍通道：$(dl_modes)）: $name"
  return 1
}

# ---------------- 3) 校验 ----------------
verify_assets() {
  step "3) SHA256 校验（发布物完整性）"
  cd "$DL_DIR" || fail "无法进入 $DL_DIR"
  local rc=0
  for f in "$SUMS_SRC" "$SUMS_DOCKER"; do
    if [ ! -f "$f" ]; then bad "缺校验和文件 $f"; rc=1; continue; fi
    info "-- $f --"
    while read -r want file; do
      [ -n "$file" ] || continue
      file="${file#\*}"
      if [ ! -f "$file" ]; then bad "$file 缺失（校验和文件要求它存在）"; rc=1; continue; fi
      got=$(sha256sum "$file" | awk '{print $1}')
      if [ "$got" = "$want" ]; then ok "$file  ${got:0:16}…"
      else bad "$file sha256 不符"; echo "         期望 ${want}"; echo "         实际 ${got}"; rc=1; fi
    done < "$f"
  done
  return $rc
}

# ---------------- 4) 解包 + 摆目录骨架 ----------------
unpack() {
  step "4) 解包并重建 compose 所需的目录骨架"
  mkdir -p "${GK_DIR}/dist-docker" "${GK_DIR}/docs/sql" \
           "${GK_DIR}/src/backend/src/main/resources/sql" \
           "${GK_DIR}/docker/mysql-conf.d" "${GK_DIR}/images"

  python3 - "$GK_DIR" "$GK_VER" "$DOCKER_ZIP" "$SQL_ZIP" "$JAR" <<'PY' || fail "解包失败"
import os, sys, zipfile, hashlib, shutil

base, ver, dzip, szip, jar = sys.argv[1:6]
dl   = os.path.join(base, 'dl')
dd   = os.path.join(base, 'dist-docker')
sqld = os.path.join(base, 'docs', 'sql')
initsqld = os.path.join(base, 'src', 'backend', 'src', 'main', 'resources', 'sql')

def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()

# ① docker 元数据 zip -> dist-docker/
with zipfile.ZipFile(os.path.join(dl, dzip)) as z:
    n = 0
    for m in z.namelist():
        if m.endswith('/'):
            continue
        tgt = os.path.join(dd, os.path.basename(m))
        with z.open(m) as src, open(tgt, 'wb') as dst:
            shutil.copyfileobj(src, dst)
        n += 1
print('  [OK]   docker zip 解出 %d 个文件 -> %s' % (n, dd))

# ② sql zip -> 按 MANIFEST.tsv 还原到 compose 期望的路径
#    v1.0.3 起 SQL 包改为结构化（full / incremental / standalone / fix），
#    且 incremental/ 用的是 initdb 挂载名（01-schema-v2.sql），与仓库原名
#    （docs/sql/schema-v2.sql）不同名 ⇒ 必须按 MANIFEST 落位，不能按 basename 平铺。
with zipfile.ZipFile(os.path.join(dl, szip)) as z:
    names = z.namelist()
    prefix = names[0].split('/')[0] + '/'
    man = None
    for cand in ('MANIFEST.tsv',):
        if prefix + cand in names:
            man = z.read(prefix + cand).decode('utf-8')
    n = 0
    pkg_full = None          # (路径, sha256)：包内全量脚本，供 ③ 交叉校验
    if man:
        for line in man.splitlines():
            line = line.strip()
            if not line or '\t' not in line:
                continue
            rel, repo_rel = line.split('\t', 1)
            arc = prefix + rel
            if arc not in names:
                print('  [FAIL] MANIFEST 指向包内不存在的条目: %s' % arc); sys.exit(1)
            tgt = os.path.join(base, repo_rel.replace('/', os.sep))
            os.makedirs(os.path.dirname(tgt), exist_ok=True)
            with z.open(arc) as src, open(tgt, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            if rel.startswith('full/'):
                pkg_full = (tgt, sha(tgt))
            n += 1
        print('  [OK]   sql zip   按 MANIFEST 还原 %d 个文件（全量+增量+修复）' % n)
        if pkg_full:
            print('         包内全量脚本 sha256 = %s' % pkg_full[1])
    else:
        # 兼容旧包（无 MANIFEST）：退化为平铺到 docs/sql/
        for m in names:
            if m.endswith('/') or not m.lower().endswith('.sql'):
                continue
            tgt = os.path.join(sqld, os.path.basename(m))
            with z.open(m) as src, open(tgt, 'wb') as dst:
                shutil.copyfileobj(src, dst)
            n += 1
        print('  [WARN] sql zip 无 MANIFEST.tsv（旧包），已平铺 %d 个 .sql -> %s' % (n, sqld))

# ③ 从 jar 抽 init.sql（compose 的 00-t01-base.sql 挂的就是这个文件）
src_init = 'BOOT-INF/classes/sql/init.sql'
with zipfile.ZipFile(os.path.join(dl, jar)) as z:
    if src_init not in z.namelist():
        print('  [FAIL] jar 内不存在 %s' % src_init); sys.exit(1)
    data = z.read(src_init)
dst = os.path.join(initsqld, 'init.sql')
jar_sha = hashlib.sha256(data).hexdigest()
# ③.1 交叉校验必须在覆盖之前用已记录的包内哈希比 —— 先比再写
if pkg_full is not None and pkg_full[1] != jar_sha:
    print('  [FAIL] SQL 包里的全量脚本与 jar 内置 init.sql 不一致')
    print('         包内 %s' % pkg_full[1])
    print('         jar  %s' % jar_sha)
    sys.exit(1)
with open(dst, 'wb') as f:
    f.write(data)
print('  [OK]   init.sql  从 jar 抽出 %d B -> %s' % (len(data), dst))
print('         sha256 = %s' % sha(dst))
if pkg_full is not None:
    print('  [OK]   SQL 包全量脚本 == jar 内置 init.sql（逐字节一致）')

# ④ 顺带把镜像元数据中的 tag 记下来，供后续断言
import json
meta = os.path.join(dd, 'images-meta.json')
if os.path.exists(meta):
    m = json.load(open(meta, encoding='utf-8'))
    for im in m.get('images', []):
        print('  [info] images-meta: %-42s %s' % (im.get('tag'), im.get('image_id', '')[:26]))
PY

  # 断言 init.sql 指纹（仅登记过的版本）
  if [ -z "$EXPECT_INIT_SQL_SHA" ]; then
    info "（未登记版本 $GK_VER，跳过 init.sql 指纹断言）"
  else
    local got
    got=$(sha256sum "${GK_DIR}/src/backend/src/main/resources/sql/init.sql" | awk '{print $1}')
    if [ "$got" = "$EXPECT_INIT_SQL_SHA" ]; then
      ok "init.sql 指纹匹配 ${got:0:16}…（逗号缺陷不存在于该发布物）"
    else
      bad "init.sql 指纹不符！期望 ${EXPECT_INIT_SQL_SHA:0:16}… 实际 ${got:0:16}…"
    fi
  fi
}

# ---------------- 5) 生成配置（override / .env / mock conf） ----------------
scaffold() {
  step "5) 生成部署配置"

  # 5.1 compose：官方文件用 build:，纯净环境无源码 ⇒ 加 override 换成 image: + --no-build
  cp "${GK_DIR}/dist-docker/docker-compose.yml" "${GK_DIR}/docker-compose.yml"
  cat > "${GK_DIR}/docker-compose.release.yml" <<EOF
# 由 deploy-from-release.sh 生成：把 build: 换成 Release 镜像
# 不覆盖官方 compose，配合 --no-build 使用
services:
  backend:
    image: ${BACKEND_IMAGE}
  frontend:
    image: ${FRONTEND_IMAGE}
EOF
  ok "docker-compose.yml（来自 Release）+ docker-compose.release.yml（override image）"

  # 5.2 .env（Release 无 .env.example，脚本生成强随机值）
  if [ -f "${GK_DIR}/.env" ]; then
    info ".env 已存在，沿用（不重新生成密钥，避免与既有数据卷的加密数据失配）"
  else
    local jwt aes dbp redp ip
    jwt=$(openssl rand -hex 32)
    aes=$(openssl rand -hex 32)
    dbp=$(openssl rand -hex 16)
    redp=$(openssl rand -hex 16)
    ip=$(hostname -I 2>/dev/null | awk '{print $1}')
    [ -n "$ip" ] || ip=$(ip route get 1 2>/dev/null | awk '{print $NF; exit}')
    [ -n "$ip" ] || ip="127.0.0.1"
    cat > "${GK_DIR}/.env" <<EOF
# 由 deploy-from-release.sh 生成（$(date '+%Y-%m-%d %H:%M:%S')）
GATEKEEPER_JWT_SECRET=${jwt}
GATEKEEPER_AES_KEY=${aes}
GATEKEEPER_DB_PASSWORD=${dbp}
GATEKEEPER_DB_NAME=gatekeeper
GATEKEEPER_DB_USERNAME=root
GATEKEEPER_REDIS_PASSWORD=${redp}
GATEKEEPER_CORS_ORIGINS=http://localhost:8081,http://${ip}:8081
EOF
    chmod 600 "${GK_DIR}/.env"
    ok ".env 已生成（4 个强随机密钥；CORS 含 localhost:8081 与 http://${ip}:8081）"
    info "   变量名（值已隐去）：$(grep -oE '^[A-Z_]+=' "${GK_DIR}/.env" | tr -d '=' | tr '\n' ' ')"
  fi

  # 5.3 docker/ 下的 bind-mount 源：优先用 Release 自带文件，缺失时回落到脚本内置
  #     —— docker 会把「不存在的 bind-mount 源」当成目录创建，导致容器启动异常，
  #        所以这两个文件必须真实存在。
  #     v1.0.3 起 docker zip 已收录二者（v1.0.2 及更早只收录了 compose，见 README）。

  # 5.3.1 mock-upstream.conf（E2E 网关转发用例的上游 mock）
  if [ -f "${GK_DIR}/dist-docker/mock-upstream.conf" ]; then
    cp "${GK_DIR}/dist-docker/mock-upstream.conf" "${GK_DIR}/docker/mock-upstream.conf"
    ok "docker/mock-upstream.conf（取自 Release 的 docker zip）"
  else
    cat > "${GK_DIR}/docker/mock-upstream.conf" <<'NGX'
# 由 deploy-from-release.sh 内置生成
# 背景：该 Release 的 gatekeeper-docker-<ver>.zip 未收录 docker/mock-upstream.conf，
#       而官方 compose 的 mock-upstream 服务会 bind-mount 它。
#       缺失时 docker 会把「不存在的文件」当成目录创建，导致 nginx 启动失败。
# 用途：E2E 网关转发用例的上游 mock（复用 nginx:1.25-alpine，零新增下载）
server {
    listen 80 default_server;
    server_name _;

    location / {
        default_type application/json;
        return 200 '{"mock":"true","service":"gatekeeper-e2e-mock-upstream"}';
    }

    location = /healthz {
        default_type application/json;
        return 200 '{"status":"ok"}';
    }
}
NGX
    ok "docker/mock-upstream.conf（脚本内置，补该 Release 的资产缺口）"
  fi

  # 5.3.2 🔴 MySQL 客户端字符集（决定 initdb 脚本里的中文会不会被写成乱码）
  #   不挂它时：容器内 LANG 为空 ⇒ mysql 客户端回退 latin1 ⇒ 没写 SET NAMES 的
  #   脚本会把 UTF-8 中文双重编码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。
  #   这里按「语义」校验，而不是只看文件存在：必须含 [client] 且 charset=utf8mb4，
  #   否则一律用内置版本覆盖（宁可覆盖也不放过乱码）。
  local cnf="${GK_DIR}/docker/mysql-conf.d/99-client-charset.cnf"
  local src_cnf="${GK_DIR}/dist-docker/99-client-charset.cnf"
  local usability="missing"
  if [ -f "$src_cnf" ]; then
    if grep -q '^\[client\]' "$src_cnf" && grep -q 'default-character-set' "$src_cnf" \
       && grep -qi 'utf8mb4' "$src_cnf"; then
      cp "$src_cnf" "$cnf"; usability="release"
    else
      usability="release-malformed"
    fi
  fi
  if [ "$usability" != "release" ]; then
    cat > "$cnf" <<'CNF'
# 由 deploy-from-release.sh 内置生成（补 Release 资产缺口 / 纠正不合规内容）
# 作用：把 mysql 客户端字符集固定为 utf8mb4 —— 官方 mysql 镜像的 entrypoint
#       执行 /docker-entrypoint-initdb.d/*.sql 时不指定字符集，容器内又无 LANG，
#       客户端会回退 latin1，导致脚本里的 UTF-8 中文被双重编码成乱码。
[client]
default-character-set = utf8mb4
CNF
    ok "docker/mysql-conf.d/99-client-charset.cnf（脚本内置，原因为 $usability）"
  else
    ok "docker/mysql-conf.d/99-client-charset.cnf（取自 Release 的 docker zip）"
  fi

  # 5.4 起栈前的总闸门：核对 compose 的每一个挂载源都已就位
  #     必须放在 scaffold 之后 —— mock-upstream.conf 是本脚本生成的，
  #     放在 unpack 里核对会必然报 MISS。
  info "-- 核对 compose 挂载清单的文件是否齐备（起栈前总闸门）--"
  local missing=0 rel
  while read -r rel; do
    [ -n "$rel" ] || continue
    if [ -f "${GK_DIR}/${rel}" ]; then
      printf '     [OK]   %s\n' "$rel"
    else
      printf '     [MISS] %s\n' "$rel"; missing=$((missing+1))
    fi
  done < <(grep -oE '^[[:space:]]+-[[:space:]]+\./[^:]+' "${GK_DIR}/docker-compose.yml" \
             | sed -E 's/^[[:space:]]+-[[:space:]]+//' | sort -u)
  [ "$missing" = "0" ] || fail "有 $missing 个 compose 挂载源文件缺失（见上方 [MISS]）"
  ok "compose 全部挂载源就位"

  # 从 compose 数出 initdb 挂载数（供 §8 的自洽断言用，不写死常数）
  EXPECT_INITDB_FILES=$(grep -c 'docker-entrypoint-initdb\.d' "${GK_DIR}/docker-compose.yml" || true)
  info "compose 声明的 initdb 挂载数 = $EXPECT_INITDB_FILES"
}

# ---------------- 6) 加载镜像 ----------------
load_images() {
  step "6) docker load 两个镜像包"
  local t=""
  for t in gatekeeper-backend-image-${GK_VER}.tar gatekeeper-frontend-image-${GK_VER}.tar; do
    info "-- load ${t} --"
    docker load -i "${DL_DIR}/${t}" 2>&1 | sed 's/^/     /'
    [ "${PIPESTATUS[0]}" = "0" ] || bad "docker load ${t} 失败"
  done
  info ""
  info "已加载的 gatekeeper 镜像："
  docker images --format '     {{.Repository}}:{{.Tag}}  {{.ID}}  {{.Size}}' | grep -i gatekeeper || bad "未找到 gatekeeper 镜像"
  docker image inspect "${BACKEND_IMAGE}"  >/dev/null 2>&1 && ok "backend 镜像 tag 存在: ${BACKEND_IMAGE}"  || bad "backend 镜像 tag 缺失: ${BACKEND_IMAGE}"
  docker image inspect "${FRONTEND_IMAGE}" >/dev/null 2>&1 && ok "frontend 镜像 tag 存在: ${FRONTEND_IMAGE}" || bad "frontend 镜像 tag 缺失: ${FRONTEND_IMAGE}"
}

# ---------------- 7) 拉取基础镜像 + 起栈 ----------------
compose_up() {
  step "7) 起栈"
  cd "$GK_DIR" || fail "无法进入 $GK_DIR"

  info "-- 先拉取 compose 直接引用的基础镜像（clean 环境下必做）--"
  for img in mysql:8.0 redis:7-alpine nginx:1.25-alpine; do
    printf '     pull %-22s ... ' "$img"
    if docker pull "$img" >/dev/null 2>&1; then printf 'OK\n'; else printf '失败（若无加速器需自备镜像）\n'; fi
  done

  info ""
  info "-- docker-compose up -d --no-build（--no-build 保证不去 build 不存在的源码）--"
  docker-compose -f docker-compose.yml -f docker-compose.release.yml \
    up -d --no-build --wait --wait-timeout 420 2>&1 | sed 's/^/     /'
  local rc=${PIPESTATUS[0]}

  info ""
  info "-- 容器状态 --"
  docker-compose -f docker-compose.yml -f docker-compose.release.yml ps 2>&1 | sed 's/^/     /'
  [ "$rc" = "0" ] && ok "up 返回 0" || bad "up 返回 $rc（继续做断言判定，不直接失败）"
}

# ---------------- 8) 初始化链断言 ----------------
assert_chain() {
  step "8) 初始化链断言（证明初始化脚本确实被执行）"
  local pw
  pw=$(grep '^GATEKEEPER_DB_PASSWORD=' "${GK_DIR}/.env" | cut -d= -f2-)

  info "-- 8.0 mysql initdb 执行记录（docker-entrypoint 的 running 行）--"
  # 期望值现场推导（来自 compose 的挂载数），不依赖上游是否跑过 scaffold
  local ef="$EXPECT_INITDB_FILES"
  if [ -z "$ef" ] && [ -f "${GK_DIR}/docker-compose.yml" ]; then
    ef=$(grep -c 'docker-entrypoint-initdb\.d' "${GK_DIR}/docker-compose.yml" || true)
  fi
  local nrun
  nrun=$(docker logs gatekeeper-mysql 2>&1 | grep -c 'running /docker-entrypoint-initdb.d/')
  info "     记录到 $nrun 次 'running /docker-entrypoint-initdb.d/...'（期望 $ef）"
  docker logs gatekeeper-mysql 2>&1 | grep 'running /docker-entrypoint-initdb.d/' | sed 's/^/       /'
  if [ -z "$ef" ]; then
    info "     （无法确定期望挂载数，跳过本断言）"
  elif [ "$nrun" = "$ef" ]; then
    ok "初始化脚本执行次数 = $ef"
  else
    bad "初始化脚本执行次数 = $nrun，期望 $ef（链被中断？）"
    info "-- 8.0b 检查 entrypoint 是否报错 --"
    docker logs gatekeeper-mysql 2>&1 | grep -iE 'error|ERROR 1064|Aborting|failed' | sed 's/^/       /' | head -20
  fi

  info ""
  info "-- 8.1 落库计数 + 字符集乱码闸门 --"
  # moji_* 两行是 2026-09-29 定位的缺陷的回归闸门：
  #   initdb 时 mysql 客户端字符集回退 latin1 ⇒ 没写 SET NAMES 的脚本
  #   把 UTF-8 中文双重编码 ⇒ sys_menu.name 出现 "æ–°å¢ž..." 这类字符
  #   （角色管理「配置权限」弹窗里看到的就是它们）。
  #   判据说明：HEX 串里"偶数位出现 C3"＝该字符串含 U+00C0~U+00FF 的字符，
  #   而正常汉字(U+4E00~U+9FFF)的 UTF-8 首字节是 E4~E9，不会命中。
  #   ⚠ 必须用 REGEXP '^(..)*C3' 而不是 LIKE '%C3%' —— 后者会在半字节边界
  #     误报（例如字节 4C 33 的十六进制串 "4C33" 里也含子串 "C3"）。
  local q="
SELECT CONCAT('tables=',  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='gatekeeper'));
SELECT CONCAT('sys_menu=',(SELECT COUNT(*) FROM gatekeeper.sys_menu));
SELECT CONCAT('sys_config=',(SELECT COUNT(*) FROM gatekeeper.sys_config));
SELECT CONCAT('junk221=', (SELECT COUNT(*) FROM gatekeeper.sys_menu WHERE id=221));
SELECT CONCAT('moji_menu=',(SELECT COUNT(*) FROM gatekeeper.sys_menu WHERE HEX(name) REGEXP '^(..)*C3'));
SELECT CONCAT('moji_dict=',(SELECT COUNT(*) FROM gatekeeper.sys_dict WHERE remark IS NOT NULL AND HEX(remark) REGEXP '^(..)*C3'));
"
  local out
  out=$(docker exec gatekeeper-mysql sh -c \
      'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "$0"' "$q" 2>/dev/null)
  info "$out" | sed 's/^/     /'

  chk() { # key expect
    local k="$1" e="$2"
    if [ -z "$e" ]; then info "$k：未登记期望值，跳过"; return 0; fi
    local v
    v=$(printf '%s\n' "$out" | grep "^${k}=" | cut -d= -f2)
    if [ "$v" = "$e" ]; then ok "$k = $v"
    else bad "$k = ${v:-<无>}，期望 $e"; fi
  }
  chk tables      "$EXPECT_TABLES"
  chk sys_menu    "$EXPECT_MENU"
  chk sys_config  "$EXPECT_CONFIG"
  chk junk221     0
  chk moji_menu   "$EXPECT_MOJIBAKE"
  chk moji_dict   "$EXPECT_MOJIBAKE"

  info ""
  info "-- 8.2 容器健康态 --"
  local st
  st=$(docker ps --format '{{.Names}}|{{.Status}}')
  printf '%s\n' "$st" | sed 's/^/     /'
  for n in gatekeeper-mysql gatekeeper-redis gatekeeper-backend gatekeeper-frontend gatekeeper-mock-upstream; do
    if printf '%s\n' "$st" | grep -q "^${n}|"; then ok "$n 运行中"; else bad "$n 未运行"; fi
  done
  for n in gatekeeper-mysql gatekeeper-redis gatekeeper-backend; do
    if printf '%s\n' "$st" | grep "^${n}|" | grep -q 'healthy'; then ok "$n healthy"
    else bad "$n 非 healthy"; fi
  done
}

# ---------------- 9) 探活 ----------------
# ⚠️ 口径修正（2026-09-29 实机暴露，旧版此处为假 FAIL）：
#   官方 compose 里 backend 服务【没有 ports: 映射】—— 它只在 compose 网络内以
#   `backend:8080` 暴露，对外统一经 frontend 的 nginx 反代 /api/* 访问。
#   所以「从宿主 curl 127.0.0.1:8080」必然连不上（curl 返回 000）——
#   这是设计口径，不是故障。旧版在此断言宿主 :8080，白等 30×2s 后报假 FAIL。
#   正确口径两条腿：① backend 容器内真的在监听 8080
#                   ② 经前端反代端到端可达（这才是用户真实路径）
smoke() {
  step "9) 探活"
  local i code=""

  # 9.1 frontend —— 宿主唯一对外端口 8081（compose: "8081:80"）
  for i in $(seq 1 15); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8081/)
    [ "$code" = "200" ] && break
    sleep 2
  done
  [ "$code" = "200" ] && ok "frontend  http://127.0.0.1:8081/ -> 200" || bad "frontend -> $code"

  # 9.2 backend 容器内监听 8080 —— eclipse-temurin:8-jre 镜像内无 curl/wget，
  #     故与 healthcheck 同口径，用 bash 的 /dev/tcp 探测
  local btcp=""
  for i in $(seq 1 15); do
    if docker exec gatekeeper-backend bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' >/dev/null 2>&1; then
      btcp="OK"; break
    fi
    sleep 2
  done
  if [ "$btcp" = "OK" ]; then
    ok "backend  容器内 127.0.0.1:8080 已监听（宿主未发布此端口，属设计）"
  else
    bad "backend 容器内 8080 未监听"
  fi

  # 9.3 端到端 —— 经前端反代访问后端（真实用户路径）
  code=""
  for i in $(seq 1 15); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:8081/api/doc.html)
    [ "$code" = "200" ] && break
    sleep 2
  done
  [ "$code" = "200" ] && ok "端到端  经前端反代 /api/doc.html -> 200（用户真实路径）" \
                      || bad "经前端反代 -> $code"
}

# ---------------- 主流程 ----------------
summary() {
  step "汇总"
  if [ "$FAILED" = "0" ]; then
    c_ok "全部断言通过 ✅   部署目录：$GK_DIR"
    c_ok "访问入口：http://<本机IP>:8081   默认账号见 README「默认账号与密钥」"
  else
    c_bad "有 $FAILED 项失败 ❌   部署目录：$GK_DIR"
  fi
  exit $([ "$FAILED" = "0" ] && echo 0 || echo 1)
}

CMD="${1:-all}"
case "$CMD" in
  clean)  do_clean ;;
  fetch)  precheck; fetch_assets; verify_assets || fail "校验失败"; unpack; scaffold ;;
  up)     compose_up ;;
  verify) assert_chain; smoke; summary ;;
  logs)   docker logs gatekeeper-mysql 2>&1 | grep -E 'docker-entrypoint|running /docker|ERROR' ;;
  all)
    precheck
    fetch_assets
    verify_assets || fail "SHA256 校验失败，拒绝继续（发布物可能损坏）"
    unpack
    scaffold
    load_images
    compose_up
    assert_chain
    smoke
    summary
    ;;
  *) echo "用法: $0 {clean|all|fetch|up|verify|logs}"; exit 2 ;;
esac
