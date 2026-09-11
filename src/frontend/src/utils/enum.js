/**
 * 枚举 / 状态映射（权威来源：docs/原型枚举字典.md）
 * ------------------------------------------------------------------
 * 不同实体的 status 语义完全不同，必须由 entity 类型分派，严禁跨实体套用。
 * Element UI 的 el-tag 仅支持 type: success | info | warning | danger（及默认）。
 */

// 各实体 status 映射：statusValue -> { label, type }
export const STATUS_MAP = {
  // 应用启停态（对齐后端 App.status：0=停用 1=启用 2=过期）
  app: {
    0: { label: '已停用', type: 'info' },
    1: { label: '已启用', type: 'success' },
    2: { label: '已过期', type: 'warning' }
  },
  // 凭证状态
  credential: {
    0: { label: '未分配', type: 'info' },
    1: { label: '启用中', type: 'success' },
    2: { label: '已停用', type: 'info' },
    3: { label: '已吊销', type: 'danger' },
    4: { label: '已过期', type: 'warning' }
  },
  // 授权状态（审批流 0待审批→1已生效→2过期/3撤销/4驳回）
  grant: {
    0: { label: '待审批', type: 'warning' },
    1: { label: '已生效', type: 'success' },
    2: { label: '已过期', type: 'info' },
    3: { label: '已撤销', type: 'danger' },
    4: { label: '已驳回', type: 'danger' }
  },
  // 接口状态（发布态）
  api: {
    0: { label: '草稿', type: 'info' },
    1: { label: '待审核', type: 'warning' },
    2: { label: '已发布', type: 'success' },
    3: { label: '已弃用', type: 'warning' },
    4: { label: '已下线', type: 'info' }
  },
  // 接口分组（与原型共享 apis 标签数组）
  apiGroup: {
    0: { label: '草稿', type: 'info' },
    1: { label: '待审核', type: 'warning' },
    2: { label: '已发布', type: 'success' },
    3: { label: '已弃用', type: 'warning' },
    4: { label: '已下线', type: 'info' }
  },
  // 接口版本（对齐后端 ApiVersionDto.status：1=生效中 2=已弃用 3=已下线）
  apiVersion: {
    0: { label: '开发中', type: 'info' },
    1: { label: '生效中', type: 'success' },
    2: { label: '已弃用', type: 'warning' },
    3: { label: '已下线', type: 'info' }
  },
  // 用户启停态
  user: {
    0: { label: '已停用', type: 'danger' },
    1: { label: '启用中', type: 'success' },
    2: { label: '锁定', type: 'warning' }
  },
  // 环境
  env: {
    0: { label: '已停用', type: 'danger' },
    1: { label: '启用中', type: 'success' },
    2: { label: '已废弃', type: 'info' }
  },
  // 业务线启停态（对齐后端 BizLineDto.status：0=停用, 1=启用；无 app 的过期态）
  bizLine: {
    0: { label: '已停用', type: 'info' },
    1: { label: '启用中', type: 'success' }
  },
  // 告警规则启停态
  alarmRule: {
    0: { label: '已停用', type: 'info' },
    1: { label: '启用中', type: 'success' }
  },
  // 告警处理态（alert.status / alarmRecords.handleStatus）
  alarmHandle: {
    0: { label: '未处理', type: 'danger' },
    1: { label: '处理中', type: 'warning' },
    2: { label: '已处理', type: 'success' },
    3: { label: '已忽略', type: 'info' }
  },
  // 封禁名单
  block: {
    0: { label: '已解封', type: 'info' },
    1: { label: '生效中', type: 'danger' },
    2: { label: '已过期', type: 'warning' }
  },
  // 字典启停态
  dict: {
    0: { label: '停用', type: 'info' },
    1: { label: '启用', type: 'success' }
  },
  // 通知渠道启停态
  notifyChannel: {
    0: { label: '停用', type: 'info' },
    1: { label: '启用', type: 'success' }
  },
  // 接口环境配置状态（configStatus）
  apiEnvConfig: {
    0: { label: '未配置', type: 'info' },
    1: { label: '已配置', type: 'warning' },
    2: { label: '已验证', type: 'success' }
  },
  // 参数配置启停态
  sysConfig: {
    0: { label: '停用', type: 'info' },
    1: { label: '启用', type: 'success' }
  },
  // 数据范围 dataScope（角色级）
  dataScope: {
    ALL: { label: '全部', type: 'success' },
    BIZ_LINE: { label: '仅本业务线', type: 'warning' },
    CUSTOM: { label: '自定义', type: 'primary' },
    SELF: { label: '仅本人', type: 'info' }
  },
  // 系统角色启停态（sys_role.status：0=停用, 1=启用）
  role: {
    0: { label: '已停用', type: 'info' },
    1: { label: '启用中', type: 'success' }
  },
  // IP 封禁状态（ip_ban.ban_status：0=已解封, 1=封禁中）
  ipBan: {
    0: { label: '已解封', type: 'info' },
    1: { label: '封禁中', type: 'danger' }
  },
  // 动态封禁规则启停态（block_rule.enabled：0=停用, 1=启用）
  blockRule: {
    0: { label: '已停用', type: 'info' },
    1: { label: '启用中', type: 'success' }
  },
  // 告警处理/读取态（alert.status：0=未读, 1=已读, 2=已处理, 3=已忽略）
  alertStatus: {
    0: { label: '未读', type: 'danger' },
    1: { label: '已读', type: 'info' },
    2: { label: '已处理', type: 'success' },
    3: { label: '已忽略', type: 'warning' }
  }
}

/**
 * 获取状态标签元数据
 * @param {string} entity 实体类型（见 STATUS_MAP 键）
 * @param {number|string} value 状态值
 * @returns {{label:string, type:string}}
 */
export function getStatusMeta(entity, value) {
  const map = STATUS_MAP[entity]
  if (!map) return { label: String(value), type: 'info' }
  const meta = map[value]
  if (!meta) return { label: String(value), type: 'info' }
  return meta
}

// 告警等级 1 INFO / 2 WARNING / 3 CRITICAL
export const ALARM_LEVEL = {
  1: { label: 'INFO', type: 'info' },
  2: { label: 'WARNING', type: 'warning' },
  3: { label: 'CRITICAL', type: 'danger' }
}

// 告警等级（alert.level 字符串形态：INFO / WARNING / CRITICAL）
export const ALERT_LEVEL = {
  INFO: { label: 'INFO', type: 'info' },
  WARNING: { label: 'WARNING', type: 'warning' },
  CRITICAL: { label: 'CRITICAL', type: 'danger' }
}

// 告警类型标签
export const ALARM_TYPE = {
  FAIL_RATE: '调用失败率告警',
  AUTH_FAIL: '鉴权失败告警',
  QUOTA_USAGE: '配额使用率告警',
  AVG_LATENCY: '后端超时告警',
  KEY_EXPIRE: '密钥即将过期',
  ZOMBIE_API: '僵尸接口告警',
  QPS_SURGE: 'QPS 突增告警'
}

// 通用固定枚举（用于 DictSelect 之外的小型下拉）
export const ENUM_OPTIONS = {
  appType: [
    { value: 1, label: '内部系统' },
    { value: 2, label: '外部合作方' },
    { value: 3, label: '三方 SaaS' },
    { value: 4, label: '个人开发者' }
  ],
  visibility: [
    { value: 1, label: '内部' },
    { value: 2, label: '对外' },
    { value: 3, label: '公开' }
  ],
  transportSecurity: [
    { value: 'NONE', label: '明文' },
    { value: 'TLS', label: 'TLS' },
    { value: 'MTLS', label: '双向 TLS' }
  ],
  authRequired: [
    { value: 0, label: '不需鉴权' },
    { value: 1, label: '必须鉴权' }
  ],
  paramType: [
    { value: 1, label: 'Header' },
    { value: 3, label: 'Request' },
    { value: 4, label: 'Response' },
    { value: 5, label: 'Error' }
  ],
  fieldType: [
    { value: 'string', label: '字符串' },
    { value: 'int', label: '整数' },
    { value: 'number', label: '数字' },
    { value: 'array', label: '数组' },
    { value: 'boolean', label: '布尔' },
    { value: 'object', label: '对象' }
  ],
  alarmLevel: [
    { value: 1, label: 'INFO' },
    { value: 2, label: 'WARNING' },
    { value: 3, label: 'CRITICAL' }
  ],
  channelType: [
    { value: 'WECOM', label: '企业微信' },
    { value: 'DINGTALK', label: '钉钉' },
    { value: 'EMAIL', label: '邮件' },
    { value: 'WEBHOOK', label: 'Webhook' }
  ],
  blockScope: [
    { value: 'IP', label: 'IP' },
    { value: 'APP', label: '应用' }
  ]
}

// 四套环境（固定维度；前端仅作查看维度，不下发到网关请求）
export const ENV_LIST = [
  { code: 'dev', label: '开发环境', sort: 1 },
  { code: 'test', label: '测试环境', sort: 2 },
  { code: 'pre', label: '预发环境', sort: 3 },
  { code: 'prod', label: '生产环境', sort: 4 }
]

export function getEnvLabel(code) {
  const e = ENV_LIST.find((x) => x.code === code)
  return e ? e.label : code
}
