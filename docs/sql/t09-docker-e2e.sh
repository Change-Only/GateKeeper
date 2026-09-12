#!/usr/bin/env bash
# ============================================================
# T09-D / T10-D · Docker E2E 脚本（草稿，待环境后跑一次）
# ------------------------------------------------------------
# ⚠️ 重要声明（T10-D 派工，2026-09-12）
#   此脚本【未在本机执行】—— 本机（Windows）无 docker 命令、无 Docker Desktop。
#   本轮产出为静态草稿；运行时验证待具备 Docker 环境后按 U0–U7 原文跑一次。
#   验证纪律（承 T07/T08 铁律）：每步带【数据正面断言】，不允许只 curl 200 就算过。
#
# 配套阅读：
#   - docs/T09-Docker-E2E-前置方案.md §3.2/§4（前置方案 + 剧本）
#   - docs/T08-ClassG-响应形状审计.md §9（形状判据，断言写法的依据）
#
# 剧本覆盖：U0 全清起栈 → U1 基础设施健康 → U2 后端探活 → U2b 初始化链落库断言 →
#           U3 登录 → U4 应用 CRUD → U5 审计日志落库 → U6 异步导出 → U7 停栈清理
# ============================================================
set -euo pipefail

# 颜色输出
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn() { echo -e "${YELLOW}[$(date +%H:%M:%S)]${NC} $*"; }
fail() { echo -e "${RED}[$(date +%H:%M:%S)] FATAL: $*${NC}"; exit 1; }
step() { echo; echo "=========================================="; echo "▶ $*"; echo "=========================================="; }

# Python 选择（容错：python3 优先，回落 python）
if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  fail "需要 python（用于 JSON 断言）"
fi

# 路径
COMPOSE_DIR="$(cd "$(dirname "$0")/../.." && pwd)"   # 仓库根（假设脚本在 docs/sql/ 下）
cd "$COMPOSE_DIR"

# 配置
COMPOSE="docker compose"
HOST_BASE="http://localhost:8081"
API_BASE="${HOST_BASE}/api"
ADMIN_USER="admin"
ADMIN_PASS="admin123"

# 前置：.env 必须存在
[ -f .env ] || fail ".env 不存在；请先：cp .env.example .env 并按注释填三密钥（openssl rand -base64 32 生成 ≥32 位）"

# ============================================================================
# U0 · 全清起栈（down -v 强制清卷，initdb 才能重跑）
# ============================================================================
step "U0 · 全清起栈（down -v + up -d --build）"
${COMPOSE} down -v 2>/dev/null || true
${COMPOSE} up -d --build
# 等待 mysql/redis healthy（首次起栈 mysql 初始化 30~60s；healthcheck 间隔 10s）
# 用 docker inspect 读 Health.Status：容器名在 compose 中已固定（container_name），
# 避免依赖 `docker compose ps --format json` 的字段序（该输出键序随 compose 版本变化，
# 旧写法 grep '"Service":"mysql".*"Health":"healthy"' 依赖 Service 在 Health 之前，不稳健）
warn "等待 mysql 初始化（首次起栈 30~60s）..."
mysql_health=""
redis_health=""
for i in {1..60}; do
  mysql_health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' gatekeeper-mysql 2>/dev/null || echo "missing")
  redis_health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' gatekeeper-redis 2>/dev/null || echo "missing")
  if [ "$mysql_health" = "healthy" ] && [ "$redis_health" = "healthy" ]; then
    log "  mysql/redis 已 healthy（等待 $((i*2))s）"
    break
  fi
  sleep 2
done
log "U0 OK：4 业务容器 + 1 mock-upstream 已起"

# ============================================================================
# U1 · 基础设施健康
# ============================================================================
step "U1 · 基础设施健康断言"
mysql_health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' gatekeeper-mysql 2>/dev/null || echo "missing")
[ "$mysql_health" = "healthy" ] || fail "U1 mysql 未 healthy（实际 '$mysql_health'；查 docker compose logs mysql）"
redis_health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' gatekeeper-redis 2>/dev/null || echo "missing")
[ "$redis_health" = "healthy" ] || fail "U1 redis 未 healthy（实际 '$redis_health'）"
mock_status=$(docker inspect -f '{{.State.Status}}' gatekeeper-mock-upstream 2>/dev/null || echo "missing")
[ "$mock_status" = "running" ] || fail "U1 mock-upstream 未 running（实际 '$mock_status'）"
log "U1 OK：mysql/redis healthy；mock-upstream running"

# mock-upstream 自检：脚本在【主机】上执行，主机【无法解析】compose 服务名 mock-upstream
# （该服务无 ports 暴露），故改在容器内经 wget 自检 —— 校验 conf 已挂载且 nginx 正常服务 /healthz
docker compose exec -T mock-upstream sh -c 'wget -q -O /dev/null http://127.0.0.1/healthz' 2>/dev/null \
  || fail "U1 mock-upstream /healthz 非 200（conf 未挂载成功 / nginx 异常）"
log "  mock-upstream /healthz = 200（容器内自检）"

# ============================================================================
# U2 · 后端探活（不依赖业务表）
# ============================================================================
step "U2 · 后端探活（knife4j swagger UI）"
code=$(curl -s -o /dev/null -w '%{http_code}' "${HOST_BASE}/api/doc.html")
[ "$code" = "200" ] || fail "U2 期望 200，实际 $code（backend 未起或 knife4j 不可达）"
log "U2 OK：api/doc.html = 200"

# ============================================================================
# U2b · 初始化链落库断言（把「静态推出的顺序/幂等」升级成能失败的运行时用例）
#   t09-hygiene.sql 是【真实变更】而非 no-op：它删掉了 04-t03a-seed-permissions.sql:33
#   播下的野行 sys_menu.id=221。空卷首跑完成整条链后应满足：
#     · sys_menu 总数 = 112（t09-hygiene.sql 自注「113-1」）
#     · sys_menu WHERE id=221 计数 = 0（野行已被 hygiene 清除）
#   任一条不成立 ⇒ 初始化链未按序完整执行（bind-mount 源缺失 / 顺序错 / hygiene 未生效）。
#   取数走容器内 mysql 客户端（凭据用容器运行时 env，host 侧不落明文）。
# ============================================================================
step "U2b · 初始化链落库断言（sys_menu 总数=112 且野行 221 已被 hygiene 清除）"
menu_total=$(${COMPOSE} exec -T mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -D "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM sys_menu;"' \
  2>/dev/null | tr -d '\r' | head -n1 || true)
[ "$menu_total" = "112" ] \
  || fail "U2b sys_menu 总数期望 112，实际 '$menu_total'（初始化链未完整执行 / 顺序错 / hygiene 未生效）"

menu_221=$(${COMPOSE} exec -T mysql sh -c \
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B -D "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM sys_menu WHERE id=221;"' \
  2>/dev/null | tr -d '\r' | head -n1 || true)
[ "$menu_221" = "0" ] \
  || fail "U2b 野行 sys_menu.id=221 期望 0，实际 '$menu_221'（t03a 已播 221 但 10-t09-hygiene 未删除它）"

log "U2b OK：sys_menu total=112，野行 id=221 计数=0（hygiene 已生效，链顺序正确）"

# ============================================================================
# U3 · 登录
# ============================================================================
step "U3 · admin 登录（断言 code=0 + token 非空）"
login_resp=$(curl -s -X POST "${API_BASE}/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${ADMIN_USER}\",\"password\":\"${ADMIN_PASS}\"}")
code_field=$(echo "$login_resp" | ${PYTHON} -c "import json,sys; d=json.load(sys.stdin); print(d.get('code','N/A'))")
# 注意：Result.success 的 code 是 200（见 common/Result.java:40），【不是 0】；
# 前端 api/index.js:36 也是按 code!==200 判错。此处期望值必须为 200。
[ "$code_field" = "200" ] \
  || fail "U3 登录 code 期望 200（Result.success 的 code=200），实际 $code_field；响应：$login_resp；若 500 ⇒ §3.2 初始化链未生效；若 401 ⇒ 账号/密码错误或种子缺失"
TOKEN=$(echo "$login_resp" | ${PYTHON} -c "import json,sys; print(json.load(sys.stdin)['data']['token'])")
[ -n "$TOKEN" ] || fail "U3 token 为空"
log "U3 OK：token 获取成功（前 16 字符 ${TOKEN:0:16}...）"
AUTH="Authorization: Bearer ${TOKEN}"

# ============================================================================
# U4 · 应用 CRUD 冒烟（data-positive：列表可见新建）
# ============================================================================
step "U4 · 应用 CRUD 冒烟"
app_key="e2e-$(date +%s)-$RANDOM"
create=$(curl -s -X POST "${API_BASE}/app" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"appName\":\"e2e-test-app\",\"appKey\":\"${app_key}\",\"description\":\"e2e fixture\"}")
new_id=$(echo "$create" | ${PYTHON} -c "import json,sys; d=json.load(sys.stdin); print(d.get('data',{}).get('id',''))" 2>/dev/null || true)
[ -n "$new_id" ] || fail "U4.1 POST /api/app 未返回 id：$create"
log "  U4.1 OK：创建 app id=$new_id key=$app_key"

# 列表断言：total ≥ 1 且新 app 在列表里
list=$(curl -s "${API_BASE}/app/list?current=1&size=10" -H "$AUTH")
total=$(echo "$list" | ${PYTHON} -c "import json,sys; print(json.load(sys.stdin)['data'].get('total', 0))")
[ "$total" -ge 1 ] || fail "U4.2 列表 total 期望 ≥1，实际 $total（CRUD 写入但列表空 ⇒ 分页查询链路断裂）"
# 校验新 app 在 records 里
echo "$list" | ${PYTHON} -c "
import json, sys
d = json.load(sys.stdin)['data']
ids = [r['id'] for r in d.get('records', [])]
target = $new_id
assert target in ids, f'U4.2 新建 app id={target} 不在列表中：{ids}'
" || fail "U4.2 列表不含新 app"
log "  U4.2 OK：列表 total=$total 且含新 app"

# 更新
update_code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${API_BASE}/app/${new_id}" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"appName":"e2e-test-app-renamed","status":1}')
[ "$update_code" = "200" ] || fail "U4.3 PUT /api/app/$new_id 期望 200，实际 $update_code"
log "  U4.3 OK：更新"

# 删除（保留 id 给 U5 使用——U5 检查 audit_log 是否记到此次写入，故本轮保留行；U4.4 改 PUT 删除后 1s 再 U5）
# 实际 E2E 中：U4 完成 CRUD 后所有写入均已被 SysOperationLogAspect 捕获，U5 即可断言。
del_code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${API_BASE}/app/${new_id}" -H "$AUTH")
[ "$del_code" = "200" ] || [ "$del_code" = "204" ] || fail "U4.4 DELETE 期望 200/204，实际 $del_code"
log "  U4.4 OK：删除"
log "U4 OK：应用 CRUD 全链路通过"

# ============================================================================
# U5 · 审计日志落库（data-positive：total ≥ 1）
#   - 完整 U5（mock-upstream 网关转发 → api_call_log）需 SM3 签名脚本，gmssl 库非通用，
#     本草稿以【等价的 operation-log 落库】覆盖：U4 全部 CRUD 均经 @RequirePerm 拦截，
#     SysOperationLogAspect 必然写 sys_operation_log；total ≥ 1 即数据正面断言。
#   - 完整 U5 实现路径：U4 不删 → /app/{id}/reset-secret 拿明文 secret →
#     SM3(appKey + secret + timestamp + nonce) → POST /api/gateway/{path} → 查 api_call_log。
# ============================================================================
step "U5 · 审计日志落库断言（data-positive：total ≥ 1）"
oplog=$(curl -s "${API_BASE}/system/operation-log/list?current=1&size=1" -H "$AUTH")
oplog_total=$(echo "$oplog" | ${PYTHON} -c "import json,sys; print(json.load(sys.stdin)['data'].get('total', 0))")
[ "$oplog_total" -ge 1 ] \
  || fail "U5 sys_operation_log total 期望 ≥1（U4 CRUD 必然触发 SysOperationLogAspect），实际 $oplog_total；若为 0 ⇒ AOP 失效或 operation-log 列表权限缺失"
log "U5 OK：sys_operation_log total=$oplog_total ≥ 1（U4 CRUD 触发落库已验）"

# ============================================================================
# U6 · 异步导出
# ============================================================================
step "U6 · 异步导出（创建任务 → 轮询 SUCCESS → 下载 CSV）"
task_resp=$(curl -s -X POST "${API_BASE}/log/export" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"clientIp":""}')
taskid=$(echo "$task_resp" | ${PYTHON} -c "import json,sys; print(json.load(sys.stdin)['data'])" 2>/dev/null || true)
[ -n "$taskid" ] || fail "U6 创建导出任务未返回 taskId：$task_resp"
log "  U6.1 OK：taskId=$taskid"

done=0
for i in {1..30}; do
  tasks_resp=$(curl -s "${API_BASE}/log/export/tasks?current=1&size=10" -H "$AUTH")
  status=$(echo "$tasks_resp" | ${PYTHON} -c "
import json, sys
d = json.loads('''$tasks_resp''')
rows = [r for r in d['data']['records'] if r['id']==$taskid]
print(rows[0]['status'] if rows else 'NOT_FOUND')
" 2>/dev/null || echo "PARSE_ERR")
  case "$status" in
    SUCCESS) done=1; break ;;
    FAILED) fail "U6 导出任务 FAILED（taskId=$taskid）" ;;
  esac
  sleep 2
done
[ "$done" = "1" ] || fail "U6 导出任务 60s 内未达 SUCCESS（taskId=$taskid，最终 status=$status）"
log "  U6.2 OK：任务 SUCCESS"

dl_code=$(curl -s -o /tmp/t09-e2e-export.csv -w '%{http_code}' \
  "${API_BASE}/log/export/${taskid}/download" -H "$AUTH")
[ "$dl_code" = "200" ] || fail "U6 下载期望 200，实际 $dl_code"
grep -q ',' /tmp/t09-e2e-export.csv || fail "U6 下载文件非 CSV（无逗号）"
log "  U6.3 OK：下载 CSV 含逗号分隔"
rm -f /tmp/t09-e2e-export.csv
log "U6 OK：异步导出全链路通过"

# ============================================================================
# U7 · 停栈清理
# ============================================================================
step "U7 · 停栈清理（down -v 防脏卷）"
${COMPOSE} down -v
log "U7 OK：容器/卷已清"

echo
echo "=========================================="
echo -e "  E2E 全部通过 ${GREEN}✅${NC}  (U0–U2b–U7 全绿)"
echo "=========================================="
