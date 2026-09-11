#!/usr/bin/env bash
# ============================================================
# T03b 端到端验证（11 步主链路 + 收尾校验）
# 适配既有扁平 REST 控制器（/interface /api-param /api-version /api-env-config /api-change-log）
# 前置：已登录前先 DEL gk:perm:1 刷新权限缓存（种子后首次登录会自动回源 DB）
# ============================================================
# 用法：bash docs/sql/t03b-e2e.sh [输出文件]   （默认输出到当前目录 t03b-e2e-result.txt）
BASE="http://localhost:8080/api"
CT="Content-Type: application/json"
PROBE_URL="http://127.0.0.1:8080/api/interface/list"
OUT="${1:-t03b-e2e-result.txt}"
: > "$OUT"
log() { echo "$@" >> "$OUT"; }

# ---- S1 登录 ----
LOGIN=$(curl -s -X POST "$BASE/auth/login" -H "$CT" -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
AUTH="Authorization: Bearer $TOKEN"
log "########## S1 登录 admin/admin123 ##########"
log "permCount含3新码? -> $(echo "$LOGIN" | grep -o 'api_param:import\|api_version:gray\|api_env_config:test' | sort -u | tr '\n' ',')"
log "TOKEN_LEN=${#TOKEN}"
log ""

# ---- S2 接口列表（聚合 groupName/lineName）----
log "########## S2 GET /interface/list (聚合 groupName/lineName) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" "$BASE/interface/list?current=1&size=10" -H "$AUTH" >> "$OUT"
log ""

# ---- S3 接口详情聚合 ----
log "########## S3 GET /interface/1 (详情聚合) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" "$BASE/interface/1" -H "$AUTH" | sed 's/\(.\{700\}\).*/\1.../' >> "$OUT"
log ""

# ---- S4 参数导入模板 ----
log "########## S4 GET /api-param/import-template ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" "$BASE/api-param/import-template" -H "$AUTH" | sed 's/\(.\{200\}\).*/\1.../' >> "$OUT"
log ""

# ---- S5 参数批量保存（header + request）----
log "########## S5 POST /api-param/batch-save (header+request 分区全量替换) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X POST "$BASE/api-param/batch-save?apiId=1" -H "$AUTH" -H "$CT" \
  -d '{"apiId":1,"header":[{"fieldName":"Content-Type","fieldType":"string","required":1,"example":"application/json"}],"request":[{"fieldName":"outOrderNo","fieldType":"string","required":1,"example":"OUT001"},{"fieldName":"amount","fieldType":"number","required":1,"example":"9.9"}]}' >> "$OUT"
log ""

# ---- S6 必填参数就绪度校验 ----
log "########## S6 GET /api-param/check-required?apiId=1 ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" "$BASE/api-param/check-required?apiId=1" -H "$AUTH" >> "$OUT"
log ""

# ---- S7 新建版本 v2 ----
log "########## S7 POST /api-version/create (v2) ##########"
V2=$(curl -s -X POST "$BASE/api-version/create" -H "$AUTH" -H "$CT" \
  -d '{"apiId":1,"version":"v2","status":1,"isCurrent":0,"grayRatio":0,"changeLog":"T03b e2e 新建 v2"}')
VID=$(echo "$V2" | sed -n 's/.*"id":\([0-9]\+\).*/\1/p')
log "CREATE: $V2"
log "V2_ID: $VID"
log ""

# ---- S8 设为当前版本 + 灰度 30（新权限 api_version:gray）----
log "########## S8a POST /api-version/$VID/set-current ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X POST "$BASE/api-version/$VID/set-current" -H "$AUTH" >> "$OUT"
log "########## S8b PUT /api-version/$VID/gray?grayRatio=30 (新权限 api_version:gray) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X PUT "$BASE/api-version/$VID/gray?grayRatio=30" -H "$AUTH" >> "$OUT"
log ""

# ---- S9 环境配置 UPSERT（env=test, 指向 127.0.0.1 保证可探测）----
log "########## S9 POST /api-env-config/upsert (env=test) ##########"
UP=$(curl -s -X POST "$BASE/api-env-config/upsert" -H "$AUTH" -H "$CT" \
  -d "{\"apiId\":1,\"envCode\":\"test\",\"upstreamUrl\":\"$PROBE_URL\",\"connectTimeout\":3000,\"readTimeout\":3000,\"retryCount\":0,\"mockEnabled\":0}")
CID=$(echo "$UP" | sed -n 's/.*"id":\([0-9]\+\).*/\1/p')
log "UPSERT: $UP"
log "CONFIG_ID: $CID"
log ""

# ---- S10 环境连通性测试（新权限 api_env_config:test）----
log "########## S10 POST /api-env-config/$CID/test (新权限 api_env_config:test) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X POST "$BASE/api-env-config/$CID/test" -H "$AUTH" >> "$OUT"
log ""

# ---- S11 发布版本 v2（复用 api:publish，需已验证环境）----
log "########## S11 POST /api-version/$VID/publish (复用 api:publish) ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X POST "$BASE/api-version/$VID/publish" -H "$AUTH" >> "$OUT"
log ""

# ---- 收尾校验 ----
log "########## V1 GET /api-change-log/list?apiId=1 (AOP 自动留痕) ##########"
CL=$(curl -s "$BASE/api-change-log/list?apiId=1" -H "$AUTH")
log "CHANGE_LOG_ROW_COUNT=$(echo "$CL" | grep -o '"changeType"' | wc -l)"
log "CHANGE_TYPES=$(echo "$CL" | grep -o '"changeType":"[A-Z]*"' | sort | uniq -c | tr '\n' ' ')"
log ""

log "########## V2 GET /interface/1 (发布态同步核对) ##########"
curl -s "$BASE/interface/1" -H "$AUTH" | grep -o '"publishStatus":[0-9]*,"currentVersion":"[^"]*"' >> "$OUT"
log ""

log "########## V3 反向：无 token DELETE /interface/1 应 401 ##########"
curl -s -w "\nHTTP_CODE=%{http_code}\n" -X DELETE "$BASE/interface/1" >> "$OUT"
log ""
echo "DONE" >> "$OUT"
