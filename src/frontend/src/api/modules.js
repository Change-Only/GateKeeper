import request from './index'

// ============ 认证 ============
// 管理员登录：校验账号密码并换取 JWT 令牌
export function login(data) {
  return request.post('/auth/login', data)
}

// ============ 应用管理 ============
// 分页查询应用列表
export function getAppList(params) {
  return request.get('/app/list', { params })
}
// 创建应用
export function createApp(data) {
  return request.post('/app', data)
}
// 更新应用信息
export function updateApp(id, data) {
  return request.put(`/app/${id}`, data)
}
// 启用/停用应用（切换应用状态）
export function updateAppStatus(id, status) {
  return request.put(`/app/${id}/status/${status}`)
}
// 删除应用
export function deleteApp(id) {
  return request.delete(`/app/${id}`)
}
// 查询应用的 IP 白名单
export function getIpWhitelist(appId) {
  return request.get(`/app/${appId}/ip-whitelist`)
}
// 新增应用的 IP 白名单条目
export function addIpWhitelist(appId, data) {
  return request.post(`/app/${appId}/ip-whitelist`, data)
}
// 删除 IP 白名单条目
export function removeIpWhitelist(id) {
  return request.delete(`/app/ip-whitelist/${id}`)
}
// 查询应用的频率限制配置
export function getRateLimit(appId) {
  return request.get(`/app/${appId}/rate-limit`)
}
// 更新应用的频率限制配置
export function updateRateLimit(appId, data) {
  return request.put(`/app/${appId}/rate-limit`, data)
}
// 重置应用的 AppSecret
export function resetSecret(id) {
  return request.post(`/app/${id}/reset-secret`)
}

// ============ 接口管理 ============
// 分页查询接口列表
export function getInterfaceList(params) {
  return request.get('/interface/list', { params })
}
// 创建接口
export function createInterface(data) {
  return request.post('/interface', data)
}
// 更新接口信息
export function updateInterface(id, data) {
  return request.put(`/interface/${id}`, data)
}
// 启用/停用接口
export function updateInterfaceStatus(id, status) {
  return request.put(`/interface/${id}/status/${status}`)
}
// 删除接口
export function deleteInterface(id) {
  return request.delete(`/interface/${id}`)
}

// ============ 接口分组（多层级） ============
// 获取多层级分组树
export function getGroupTree() {
  return request.get('/group/tree')
}
// 获取分组列表（扁平结构）
export function getGroupList() {
  return request.get('/group/list')
}
// 获取指定分组下的接口列表
export function getGroupInterfaces(id) {
  return request.get(`/group/${id}/interfaces`)
}
// 创建分组
export function createGroup(data) {
  return request.post('/group', data)
}
// 更新分组
export function updateGroup(id, data) {
  return request.put(`/group/${id}`, data)
}
// 删除分组
export function deleteGroup(id) {
  return request.delete(`/group/${id}`)
}

// ============ 权限管理 ============
// 查询授权列表
export function getPermissionList(params) {
  return request.get('/permission/list', { params })
}
// 单个接口授权
export function grantPermission(data) {
  return request.post('/permission', data)
}
// 批量授权
export function batchGrantPermission(data) {
  return request.post('/permission/batch', data)
}
// 按分组整组授权
export function grantByGroup(data) {
  return request.post('/permission/grant-by-group', data)
}
// 取消应用对接口的授权
export function revokePermission(appId, interfaceId) {
  return request.delete('/permission', { params: { appId, interfaceId } })
}

// ============ 调用日志 ============
// 分页查询调用日志
export function getLogList(params) {
  return request.get('/log/list', { params })
}
// 查询日志详情
export function getLogDetail(id) {
  return request.get(`/log/${id}`)
}

// ============ 安全防护 ============
// 分页查询 IP 封禁列表
export function getBanList(params) {
  return request.get('/security/ip-ban/list', { params })
}
// 封禁 IP
export function banIp(data) {
  return request.post('/security/ip-ban', data)
}
// 解封 IP
export function unbanIp(id) {
  return request.put(`/security/ip-ban/${id}/unban`)
}
// 分页查询安全事件列表
export function getEventList(params) {
  return request.get('/security/event/list', { params })
}
// 处理安全事件（标记为已处理或已忽略）
export function handleEvent(id, status, remark) {
  return request.put(`/security/event/${id}/handle`, null, { params: { status, remark } })
}

// ============ 数据大屏 ============
// 查询大屏总览指标
export function getScreenOverview() {
  return request.get('/dashboard/screen/overview')
}
// 查询大屏调用量趋势
export function getScreenTrend() {
  return request.get('/dashboard/screen/trend')
}
// 查询应用访问量排行
export function getAppRank() {
  return request.get('/dashboard/screen/app-rank')
}
// 查询接口热度排行
export function getInterfaceRank() {
  return request.get('/dashboard/screen/interface-rank')
}
// 查询最近异常事件
export function getRecentEvents() {
  return request.get('/dashboard/screen/recent-events')
}

// ============ 加解密配置 ============
// 查询接口级加解密配置
export function getInterfaceEncryptionConfig(interfaceId) {
  return request.get(`/encryption/interface/${interfaceId}`)
}
// 保存接口级加解密配置
export function saveInterfaceEncryptionConfig(data) {
  return request.post('/encryption/interface', data)
}
// 查询应用级加解密配置
export function getAppEncryptionConfig(appId) {
  return request.get(`/encryption/app/${appId}`)
}
// 保存应用级加解密配置
export function saveAppEncryptionConfig(data) {
  return request.post('/encryption/app', data)
}

// ============ 系统管理 ============
// 分页查询用户列表
export function getUserList(params) {
  return request.get('/system/user/list', { params })
}
// 创建用户
export function createUser(data) {
  return request.post('/system/user', data)
}
// 更新用户
export function updateUser(id, data) {
  return request.put(`/system/user/${id}`, data)
}
// 删除用户
export function deleteUser(id) {
  return request.delete(`/system/user/${id}`)
}
// 查询角色列表
export function getRoleList() {
  return request.get('/system/role/list')
}
// 分页查询操作日志
export function getOperationLogList(params) {
  return request.get('/system/operation-log/list', { params })
}

// ============ 安全规则 ============
// 查询安全规则列表
export function getRuleList() {
  return request.get('/security/rule/list')
}
// 创建安全规则
export function createRule(data) {
  return request.post('/security/rule', data)
}
// 更新安全规则
export function updateRule(id, data) {
  return request.put(`/security/rule/${id}`, data)
}
// 删除安全规则
export function deleteRule(id) {
  return request.delete(`/security/rule/${id}`)
}

// ============ 系统管理（补充接口） ============
// 启停用户
export function updateUserStatus(id, status) {
  return request.put(`/system/user/${id}/status/${status}`)
}
// 重置用户密码
export function resetUserPassword(id, data) {
  return request.put(`/system/user/${id}/password`, data)
}
// 新增角色
export function createRole(data) {
  return request.post('/system/role', data)
}
// 修改角色
export function updateRole(id, data) {
  return request.put(`/system/role/${id}`, data)
}
// 删除角色
export function deleteRole(id) {
  return request.delete(`/system/role/${id}`)
}

// ============ 调用日志导出（异步任务） ============
// 创建导出任务（百万级数据后台分批生成 CSV，返回任务 ID）
export function createLogExport(params) {
  return request.post('/log/export', null, { params })
}
// 查询导出任务列表（按当前登录人，倒序）
export function getExportTasks(params) {
  return request.get('/log/export/tasks', { params })
}
// 下载导出文件（blob）
export function downloadExportTask(taskId) {
  return request.get(`/log/export/${taskId}/download`, { responseType: 'blob' })
}

// ============ 告警中心 ============
// 分页查询告警列表（支持等级/来源/状态筛选）
export function getAlertList(params) {
  return request.get('/alert/list', { params })
}
// 查询未读告警数量（供顶栏铃铛轮询）
export function getAlertUnread() {
  return request.get('/alert/unread-count')
}
// 标记单条告警为已读
export function markAlertRead(id) {
  return request.put(`/alert/${id}/read`)
}
// 一键全部标记已读
export function markAllAlertRead() {
  return request.put('/alert/read-all')
}
// 处置告警（已处理/已忽略）
export function handleAlert(id, status, remark) {
  return request.put(`/alert/${id}/handle`, null, { params: { status, remark } })
}

// ============ 字典管理 sys-dict（T05 Phase 2 · 后端 DictController 已就绪） ============
// 分页查询字典
export function getDictList(params) {
  return request.get('/dict/list', { params })
}
// 全量字典（下拉）
export function getDictAll() {
  return request.get('/dict/all')
}
// 字典详情（含项）
export function getDictDetail(dictCode) {
  return request.get(`/dict/${dictCode}`)
}
// 字典项列表
export function getDictItems(dictCode) {
  return request.get(`/dict/${dictCode}/items`)
}
// 新建字典
export function createDict(data) {
  return request.post('/dict/create', data)
}
// 更新字典
export function updateDict(data) {
  return request.put('/dict/update', data)
}
// 删除字典
export function deleteDict(id) {
  return request.delete(`/dict/${id}`)
}
// 新增字典项
export function createDictItem(dictCode, data) {
  return request.post(`/dict/${dictCode}/items`, data)
}
// 更新字典项
export function updateDictItem(itemId, data) {
  return request.put(`/dict/items/${itemId}`, data)
}
// 删除字典项
export function deleteDictItem(itemId) {
  return request.delete(`/dict/items/${itemId}`)
}

// ============ 接口环境配置（网关路由/版本预览构件数据源） ============
// 按 apiId 查询该接口在各环境下的 upstreamUrl / 当前版本 / 灰度比例
export function getApiEnvConfigList(apiId) {
  return request.get('/api-env-config/list', { params: { apiId } })
}

// ============ 业务线 / 环境 / 通知渠道 / 告警规则（Phase 1+ 页面复用，Phase 0 仅声明） ============
// 业务线
export function getBizLineList(params) {
  return request.get('/biz-line/list', { params })
}
export function getBizLineAll() {
  return request.get('/biz-line/all')
}
export function getBizLineDetail(id) {
  return request.get(`/biz-line/${id}`)
}
export function createBizLine(data) {
  return request.post('/biz-line/create', data)
}
export function updateBizLine(data) {
  return request.put('/biz-line/update', data)
}
export function deleteBizLine(id) {
  return request.delete(`/biz-line/${id}`)
}
// 环境
export function getEnvList(params) {
  return request.get('/env/list', { params })
}
export function getEnvAll() {
  return request.get('/env/all')
}
export function getEnvDetail(id) {
  return request.get(`/env/${id}`)
}
export function createEnv(data) {
  return request.post('/env/create', data)
}
export function updateEnv(data) {
  return request.put('/env/update', data)
}
export function deleteEnv(id) {
  return request.delete(`/env/${id}`)
}
// 通知渠道
export function getNotifyChannelList(params) {
  return request.get('/notify-channel/list', { params })
}
// 告警规则
export function getAlarmRuleList(params) {
  return request.get('/alarm-rule/list', { params })
}

// ============ 接口参数定义（api-param 子域 · T05 Phase 1） ============
// 按接口查询参数列表（扁平，含 parentId 嵌套关系）
export function getApiParamList(params) {
  return request.get('/api-param/list', { params })
}
// 按接口查询参数树（嵌套 children）
export function getApiParamTree(params) {
  return request.get('/api-param/tree', { params })
}
// 创建参数（高危：返回一次明文？参数无密钥，仅落库）
export function createApiParam(data) {
  return request.post('/api-param/create', data)
}
// 更新参数
export function updateApiParam(id, data) {
  return request.put(`/api-param/${id}/update`, data)
}
// 删除参数
export function deleteApiParam(id) {
  return request.delete(`/api-param/${id}`)
}

// ============ 接口版本（api-version 子域 · T05 Phase 1） ============
// 按接口查询版本列表
export function getApiVersionList(params) {
  return request.get('/api-version/list', { params })
}
// 查询接口当前生效版本
export function getApiVersionCurrent(params) {
  return request.get('/api-version/current', { params })
}
// 创建版本（高危）
export function createApiVersion(data) {
  return request.post('/api-version/create', data)
}
// 设为当前默认版本
export function setApiVersionCurrent(id) {
  return request.post(`/api-version/${id}/set-current`)
}
// 弃用版本
export function deprecateApiVersion(id) {
  return request.post(`/api-version/${id}/deprecate`)
}
// 下线版本
export function offlineApiVersion(id) {
  return request.post(`/api-version/${id}/offline`)
}

// ============ 接口环境配置（api-env-config 子域 · T05 Phase 1，list 已声明） ============
// 创建环境配置
export function createApiEnvConfig(data) {
  return request.post('/api-env-config/create', data)
}
// 更新环境配置
export function updateApiEnvConfig(id, data) {
  return request.put(`/api-env-config/${id}/update`, data)
}
// 切换 Mock 开关
export function toggleApiEnvConfigMock(id) {
  return request.post(`/api-env-config/${id}/toggle-mock`)
}
// 删除环境配置
export function deleteApiEnvConfig(id) {
  return request.delete(`/api-env-config/${id}`)
}

// ============ 接口变更历史（api-change-log 子域 · T05 Phase 1，只读） ============
// 按接口查询变更历史列表
export function getApiChangeLogList(params) {
  return request.get('/api-change-log/list', { params })
}

// ============ 应用凭证（app-credential 子域 · T05 Phase 1） ============
// 按应用查询凭证列表（GET /app-credential/list?appId=）
export function getAppCredentialList(params) {
  return request.get('/app-credential/list', { params })
}
// 创建凭证（高危：仅此一次返回明文 appSecret）
export function createAppCredential(data) {
  return request.post('/app-credential/create', data)
}
// 发起灰度轮换（生成轮换中新密钥，返回一次明文 appSecret）
export function rotateAppCredential(id) {
  return request.post(`/app-credential/${id}/rotate`)
}
// 完成轮换（新密钥转正，旧密钥作废）
export function completeRotateAppCredential(id) {
  return request.post(`/app-credential/${id}/complete-rotate`)
}
// 吊销凭证（高危）
export function revokeAppCredential(id) {
  return request.post(`/app-credential/${id}/revoke`)
}
// 更新凭证别名 / 过期时间
export function updateAppCredential(id, data) {
  return request.put(`/app-credential/${id}/update`, data)
}

// ============ 接口发布（interface 子域 · T05 Phase 1） ============
// 发布接口（高危）
export function publishInterface(id) {
  return request.post(`/interface/${id}/publish`)
}

// ============ 参数配置 sys-config（T05 Phase 2 · 后端 ConfigController 已就绪） ============
// 分页查询配置
export function getConfigList(params) {
  return request.get('/config/list', { params })
}
// 全量配置（下拉，可选 configGroup）
export function getConfigAll(params) {
  return request.get('/config/all', { params })
}
// 配置详情（sensitive=1 返回脱敏）
export function getConfigDetail(id) {
  return request.get(`/config/${id}`)
}
// 新建配置（高危）
export function createConfig(data) {
  return request.post('/config/create', data)
}
// 更新配置（高危）
export function updateConfig(data) {
  return request.put('/config/update', data)
}
// 删除配置（高危，built_in=1 后端拒绝）
export function deleteConfig(id) {
  return request.delete(`/config/${id}`)
}

// ============ 数据权限 role-data-scope（T05 Phase 2 · 后端 DataScopeController 已就绪） ============
// 角色选择器
export function getDataScopeRoles(params) {
  return request.get('/role-data-scope/roles', { params })
}
// 范围值选项（业务线/环境/接口分组）
export function getDataScopeOptions() {
  return request.get('/role-data-scope/options')
}
// 某角色当前数据范围（空=不限）
export function getDataScopeByRole(roleId) {
  return request.get(`/role-data-scope/${roleId}`)
}
// 全量覆盖保存（高危 risk=true）
export function saveDataScope(roleId, data) {
  return request.put(`/role-data-scope/${roleId}`, data)
}

// ============ 通知渠道 notify-channel（T05 Phase 2） ============
// 通知渠道详情
export function getNotifyChannelDetail(id) {
  return request.get(`/notify-channel/${id}`)
}
// 新建通知渠道
export function createNotifyChannel(data) {
  return request.post('/notify-channel/create', data)
}
// 更新通知渠道
export function updateNotifyChannel(id, data) {
  return request.put(`/notify-channel/${id}/update`, data)
}
// 删除通知渠道
export function deleteNotifyChannel(id) {
  return request.delete(`/notify-channel/${id}`)
}
// 测试通知渠道
export function testNotifyChannel(id) {
  return request.post(`/notify-channel/${id}/test`)
}
