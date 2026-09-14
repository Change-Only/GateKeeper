-- ============================================================================
-- GateKeeper → APIM V2 数据迁移脚本
-- ----------------------------------------------------------------------------
-- 执行顺序：init.sql  →  schema-v2.sql  →  migrate-v2.sql
-- 环境：MySQL 8.x
--
-- 幂等性保证（可重复执行，结果一致）：
--   1. 种子数据（环境/字典/配置/权限点…）一律 INSERT IGNORE，靠唯一键去重
--   2. 存量数据回填（UPDATE）全部带 IS NULL / 确定值 守卫，二次执行无匹配行
--   3. 存量表 → 新表 的搬迁（INSERT ... SELECT）全部带 NOT EXISTS 反连守卫
--   4. 统计字段（api_count / grant_count）每次重算，天然幂等
--
-- 默认值策略：
--   - 环境：默认补 dev/test/pre/prod 四条；存量数据的 env_code 一律回填 'prod'
--   - 业务线：默认补 id=100 的「默认业务线」，存量 app/group/interface 挂到该线下
--   - 接口发布状态：存量 status=1(启用) → publish_status=2(已发布)；status=0 → 0(草稿)
--   - 接口版本：每个存量接口补一条 v1（is_current=1, gray_ratio=100）
--   - 授权：存量 app_api_permission(status=1) → app_api_grant(status=1 已生效)，
--           有效期默认 今天 ~ 一年后
-- ============================================================================

SET NAMES utf8mb4;
USE `gatekeeper`;

-- 定义默认业务线ID（多处引用，集中在此）
SET @GK_DEFAULT_LINE_ID = 100;
SET @GK_DEFAULT_ENV     = 'prod';


-- ############################################################################
-- 一、主数据种子
-- ############################################################################

-- ---------------------------------------------------------------------------
-- 1.1 环境（env）—— 原型 MOCK.envs
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO env (id, env_code, env_name, gateway_url, sort_order, status) VALUES
(1, 'dev',  '开发环境', 'https://api-dev.example.com',  1, 1),
(2, 'test', '测试环境', 'https://api-test.example.com', 2, 1),
(3, 'pre',  '预发环境', 'https://api-pre.example.com',  3, 1),
(4, 'prod', '生产环境', 'https://api.example.com',      4, 1);

-- ---------------------------------------------------------------------------
-- 1.2 业务线（biz_line）—— 原型 MOCK.bizLines + 一条迁移默认线
--      防御性归位：若历史运行因会话变量丢失导致 common 被写入 id=6，此处显式 UPDATE 到 id=100
-- ---------------------------------------------------------------------------
UPDATE biz_line SET id = @GK_DEFAULT_LINE_ID
WHERE id <> @GK_DEFAULT_LINE_ID AND line_code = 'common';

INSERT IGNORE INTO biz_line (id, line_code, line_name, owner_name, status, remark) VALUES
(1, 'trade',  '交易业务线',   '张三', 1, '订单、购物车、履约'),
(2, 'pay',    '支付业务线',   '李四', 1, '支付、结算、对账'),
(3, 'user',   '用户业务线',   '王五', 1, '账号、会员、权限'),
(4, 'data',   '数据业务线',   '赵六', 1, '数仓、报表、算法'),
(5, 'supply', '供应链业务线', '孙七', 1, '商品、库存、采购'),
(@GK_DEFAULT_LINE_ID, 'common', '默认业务线', NULL, 1, '迁移存量数据时为历史应用/接口分配的默认归属业务线（推断）');

-- ---------------------------------------------------------------------------
-- 1.3 角色（sys_role）—— 原型 MOCK.roles
--      存量 3 个角色（SUPER_ADMIN/OPERATOR/SECURITY_AUDITOR）role_code 不与原型冲突，
--      采取「原型角色新增 + 存量角色映射权限」的双轨策略，保证既有账号权限不丢失。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role (id, role_code, role_name, role_type, data_scope, description, status) VALUES
(10, 'ADMIN',        '平台管理员',   1, 'ALL',      '系统内置，拥有全部权限', 1),
(11, 'BIZ_ADMIN',    '业务线管理员', 1, 'BIZ_LINE', '管理本业务线内的应用与接口', 1),
(12, 'API_PROVIDER', '接口提供方',   1, 'CUSTOM',   '管理自己负责的接口分组', 1),
(13, 'API_CONSUMER', '接口调用方',   1, 'SELF',     '管理自己的应用与密钥，申请授权', 1),
(14, 'AUDITOR',      '审计员',       1, 'ALL',      '全局只读 + 审计日志导出', 1),
(15, 'EXTERNAL_PM',  '外部对接人',   2, 'SELF',     '外部合作方对接账号，仅可查看自己的应用', 1);

-- 存量角色补齐数据权限范围（幂等：每次执行结果一致）
UPDATE sys_role SET data_scope = 'ALL',      role_type = 1 WHERE role_code IN ('SUPER_ADMIN', 'SECURITY_AUDITOR');
UPDATE sys_role SET data_scope = 'BIZ_LINE', role_type = 1 WHERE role_code = 'OPERATOR';

-- ---------------------------------------------------------------------------
-- 1.4 菜单权限点（sys_menu）—— 原型 MOCK.menus
--      type: 1=模块（分组节点）, 2=页面（承接 MENU_TREE 的 20 个页面）, 3=权限点（按钮/接口）
--      id 直接沿用原型 MOCK.menus 的 id，便于 rolePerms 原样落地
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
-- 模块节点（type=1）
(1, 0, '概览',     1, NULL, '/dashboard',  0, 1, 1),
(2, 0, '应用管理', 1, NULL, NULL,          0, 2, 1),
(3, 0, '接口管理', 1, NULL, NULL,          0, 3, 1),
(4, 0, '权限管理', 1, NULL, NULL,          0, 4, 1),
(5, 0, '系统设置', 1, NULL, NULL,          0, 5, 1),
(6, 0, '监控与审计', 1, NULL, NULL,        0, 6, 1),
-- 应用管理 · 权限点（type=3）
(21, 2, '查看应用',       3, 'app:list',              NULL, 0, 1, 1),
(22, 2, '新增应用',       3, 'app:create',            NULL, 0, 2, 1),
(23, 2, '编辑应用',       3, 'app:update',            NULL, 0, 3, 1),
(24, 2, '停用/注销应用',  3, 'app:disable',           NULL, 1, 4, 1),
(25, 2, '创建密钥',       3, 'app:credential:create', NULL, 1, 5, 1),
(26, 2, '重置 Secret',    3, 'app:credential:reset',  NULL, 1, 6, 1),
(27, 2, '吊销密钥',       3, 'app:credential:revoke', NULL, 1, 7, 1),
-- 接口管理 · 权限点
(31, 3, '查看接口', 3, 'api:list',       NULL, 0, 1, 1),
(32, 3, '新增接口', 3, 'api:create',     NULL, 0, 2, 1),
(33, 3, '编辑接口', 3, 'api:update',     NULL, 0, 3, 1),
(34, 3, '发布接口', 3, 'api:publish',    NULL, 1, 4, 1),
(35, 3, '下线接口', 3, 'api:offline',    NULL, 1, 5, 1),
(36, 3, '配置环境', 3, 'api:env:update', NULL, 0, 6, 1),
-- 权限管理 · 权限点
(41, 4, '查看授权',     3, 'grant:list',     NULL, 0, 1, 1),
(42, 4, '新增授权',     3, 'grant:create',   NULL, 0, 2, 1),
(43, 4, '审批授权',     3, 'grant:approve',  NULL, 0, 3, 1),
(44, 4, '撤销授权',     3, 'grant:revoke',   NULL, 1, 4, 1),
(45, 4, '用户管理',     3, 'sys:user:update',NULL, 1, 5, 1),
(46, 4, '角色授权',     3, 'sys:role:grant', NULL, 1, 6, 1),
(47, 4, '审计日志导出', 3, 'audit:export',   NULL, 1, 7, 1),
-- 系统设置 · 权限点
(51, 5, '查看配置',     3, 'sys:config:list',   NULL, 0, 1, 1),
(52, 5, '修改配置',     3, 'sys:config:update', NULL, 0, 2, 1),
(53, 5, '修改安全策略', 3, 'sys:security:update', NULL, 1, 3, 1),
(54, 5, '字典管理',     3, 'sys:dict:update',   NULL, 0, 4, 1),
(55, 5, '告警配置',     3, 'sys:alarm:update',  NULL, 0, 5, 1),
-- 监控与审计 · 权限点
(61, 6, '查看调用日志', 3, 'log:call:list',   NULL, 0, 1, 1),
(62, 6, '查看调用详情', 3, 'log:call:detail', NULL, 0, 2, 1),
(63, 6, '处理告警',     3, 'alarm:handle',    NULL, 0, 3, 1);

-- 页面节点（type=2）—— 承接原型 MENU_TREE 的 20 个页面
-- 说明：页面节点 perm_code 置 NULL，页面可见性 = 该模块下用户是否拥有任一 type=3 权限点
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(201, 1, '概览',         2, NULL, '/dashboard',      0, 1, 1),
(202, 2, '应用列表',     2, NULL, '/app/list',       0, 1, 1),
(203, 3, '接口分组',     2, NULL, '/api/group',      0, 1, 1),
(204, 3, '接口列表',     2, NULL, '/api/list',       0, 2, 1),
(205, 4, '用户管理',     2, NULL, '/perm/user',      0, 1, 1),
(206, 4, '角色管理',     2, NULL, '/perm/role',      0, 2, 1),
(207, 4, '接口授权总览', 2, NULL, '/perm/matrix',    0, 3, 1),
(208, 4, '数据权限',     2, NULL, '/perm/datascope', 0, 4, 1),
(209, 4, '操作审计',     2, NULL, '/perm/audit',     0, 5, 1),
(210, 5, '环境与网关',   2, NULL, '/sys/env',        0, 1, 1),
(211, 5, '安全策略',     2, NULL, '/sys/security',   0, 2, 1),
(212, 5, '业务线管理',   2, NULL, '/sys/bizline',    0, 3, 1),
(213, 5, '字典管理',     2, NULL, '/sys/dict',       0, 4, 1),
(214, 5, '告警规则',     2, NULL, '/sys/alarm',      0, 5, 1),
(215, 5, '通知渠道',     2, NULL, '/sys/notify',     0, 6, 1),
(216, 5, '参数配置',     2, NULL, '/sys/config',     0, 7, 1),
(217, 5, '日志与审计',   2, NULL, '/sys/log',        0, 8, 1),
(218, 6, '调用日志',     2, NULL, '/mon/calllog',    0, 1, 1),
(219, 6, '告警记录',     2, NULL, '/mon/alarm',      0, 2, 1),
(220, 6, '封禁管理',     2, NULL, '/mon/block',      0, 3, 1);

-- ---------------------------------------------------------------------------
-- 1.5 角色-权限点关联（sys_role_menu）—— 原型 MOCK.rolePerms 原样落地
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'ADMIN'
  AND m.id IN (1,21,22,23,24,25,26,27,31,32,33,34,35,36,41,42,43,44,45,46,47,51,52,53,54,55,61,62,63);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'BIZ_ADMIN'
  AND m.id IN (1,21,22,23,24,25,26,27,31,32,33,34,35,36,41,42,43,44,51,54,55,61,62,63);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'API_PROVIDER'
  AND m.id IN (1,21,31,32,33,34,35,36,41,42,43,61,62);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'API_CONSUMER'
  AND m.id IN (1,21,22,23,25,31,41,61,62);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'AUDITOR'
  AND m.id IN (1,21,31,41,51,61,62,63,47);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'EXTERNAL_PM'
  AND m.id IN (1,21,25,41,61,62);

-- 存量角色权限映射（保证既有 admin 账号迁移后权限不丢失）
--   SUPER_ADMIN   ← 平台管理员(ADMIN) 全部权限点
--   OPERATOR      ← 业务线管理员(BIZ_ADMIN) 权限点
--   SECURITY_AUDITOR ← 审计员(AUDITOR) 权限点
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id
FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'ADMIN'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'SUPER_ADMIN';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id
FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'BIZ_ADMIN'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'OPERATOR';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r1.id, rm.menu_id
FROM sys_role r1
JOIN sys_role r2 ON r2.role_code = 'AUDITOR'
JOIN sys_role_menu rm ON rm.role_id = r2.id
WHERE r1.role_code = 'SECURITY_AUDITOR';

-- ---------------------------------------------------------------------------
-- 1.6 角色数据权限范围（sys_role_datascope）—— 原型「数据权限」页默认值
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_datascope (role_id, scope_type, scope_value)
SELECT r.id, 'ENV', e.env_code
FROM sys_role r JOIN env e
WHERE r.role_code = 'BIZ_ADMIN' AND e.env_code IN ('test', 'prod');

-- ---------------------------------------------------------------------------
-- 1.7 数据字典（sys_dict + sys_dict_item）—— 原型 MOCK.dicts
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_dict (id, dict_code, dict_name, built_in, status, remark) VALUES
(1, 'app_type',     '应用类型',   1, 1, NULL),
(2, 'visibility',   '接口可见性', 1, 1, NULL),
(3, 'api_status',   '接口状态',   1, 1, NULL),
(4, 'grant_status', '授权状态',   1, 1, NULL),
(5, 'cred_status',  '密钥状态',   1, 1, NULL),
(6, 'alarm_level',  '告警级别',   1, 1, '仅映射 alarm_rule.alarm_level（告警规则配置等级 1提示/2警告/3严重）。注意：alert.level 是字符串枚举 INFO/WARNING/CRITICAL，与本字典无关；alert.alarm_level 为废弃死列，勿用');

INSERT IGNORE INTO sys_dict_item (dict_code, item_value, item_label, sort_order, status) VALUES
('app_type',     '1', '内部系统',     1, 1),
('app_type',     '2', '外部合作方',   2, 1),
('app_type',     '3', '测试应用',     3, 1),
('visibility',   '1', '内部',         1, 1),
('visibility',   '2', '对外公开',     2, 1),
('api_status',   '0', '草稿',         1, 1),
('api_status',   '1', '待审核',       2, 1),
('api_status',   '2', '已发布',       3, 1),
('api_status',   '3', '已弃用',       4, 1),
('api_status',   '4', '已下线',       5, 1),
('grant_status', '0', '待审批',       1, 1),
('grant_status', '1', '已生效',       2, 1),
('grant_status', '2', '已过期',       3, 1),
('grant_status', '3', '已撤销',       4, 1),
('grant_status', '4', '已驳回',       5, 1),
('cred_status',  '1', '启用中',       1, 1),
('cred_status',  '2', '已停用',       2, 1),
('cred_status',  '3', '已吊销',       3, 1),
('cred_status',  '4', '已过期',       4, 1),
('alarm_level',  '1', '提示',         1, 1),
('alarm_level',  '2', '警告',         2, 1),
('alarm_level',  '3', '严重',         3, 1);

-- ---------------------------------------------------------------------------
-- 1.8 参数配置（sys_config）—— 原型 MOCK.configs
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_config (id, config_key, config_value, config_group, config_name, `sensitive`, built_in, remark) VALUES
(1,  'sign.algorithm',                  'HmacSHA256',   'SECURITY', '签名算法',                 0, 1, '支持 HmacSHA256 / HmacSHA512'),
(2,  'sign.timestamp.tolerance',        '300000',       'SECURITY', '时间戳容差（毫秒）',       0, 1, '默认 ±5 分钟，超出直接拒绝'),
(3,  'sign.nonce.ttl',                  '600',          'SECURITY', 'Nonce 有效期（秒）',       0, 1, '应 ≥ 2 倍时间戳容差'),
(4,  'secret.length',                   '32',           'SECURITY', 'Secret 长度',              0, 1, '生成时的随机串长度'),
(5,  'secret.encrypt.algo',             'AES-256-GCM',  'SECURITY', 'Secret 存储加密算法',      1, 1, '可逆加密，签名校验需原始值'),
(6,  'key.rotate.period',               '180',          'SECURITY', '密钥强制轮换周期（天）',   0, 1, '超期在概览页告警'),
(7,  'key.max.valid.days',              '365',          'SECURITY', '密钥最长有效期（天）',     0, 1, '到期自动失效'),
(8,  'external.ip.whitelist.required',  'true',         'SECURITY', '外部应用强制 IP 白名单',   0, 1, ''),
(9,  'login.fail.threshold',            '5',            'SECURITY', '登录失败锁定阈值',         0, 1, ''),
(10, 'session.timeout',                 '480',          'SECURITY', '会话超时（分钟）',         0, 1, ''),
(11, 'log.desensitize',                 'true',         'SECURITY', '日志敏感字段脱敏',         0, 1, '手机号/身份证/银行卡'),
(12, 'gateway.auth.enabled',            'true',         'GATEWAY',  '是否开启签名校验',         1, 1, '关闭等于裸奔，仅应急临时关闭'),
(13, 'gateway.ratelimit.enabled',       'true',         'GATEWAY',  '是否开启限流',             0, 1, ''),
(14, 'gateway.default.read.timeout',    '3000',         'GATEWAY',  '默认读取超时（毫秒）',     0, 1, ''),
(15, 'audit.log.retention.days',        '180',          'LOG',      '审计日志保留天数',         0, 1, '等保三级要求 ≥180 天'),
(16, 'call.log.hot.days',               '30',           'LOG',      '调用日志热数据保留天数',   0, 1, 'MySQL 保留时长'),
(17, 'approval.enabled',                'false',        'DEFAULT',  '是否开启审批流',           0, 1, 'MVP 关闭，V2 开启'),
(18, 'export.max.rows',                 '50000',        'DEFAULT',  '单次导出最大行数',         0, 1, '');

-- ---------------------------------------------------------------------------
-- 1.9 通知渠道（notify_channel）—— 原型 MOCK.notifyChannels
--      id 沿用原型，便于 alarm_rule.channel_ids 直接引用
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO notify_channel (id, channel_name, channel_type, status, last_test_time, last_test_result) VALUES
(1, '交易研发-企微机器人', 'WECOM',    1, '2026-08-28 15:22:10', '发送成功'),
(2, '支付研发-钉钉机器人', 'DINGTALK', 1, '2026-08-27 10:05:33', '发送成功'),
(3, '平台告警邮件',        'EMAIL',    1, '2026-08-26 09:00:00', '发送成功'),
(4, '安全事件 Webhook',    'WEBHOOK',  0, '2026-08-20 14:30:00', '连接超时，请检查配置');

-- ---------------------------------------------------------------------------
-- 1.10 告警规则（alarm_rule）—— 原型 MOCK.alarmRules
--      T11 增量：补 target_type / target_ids（评估对象绑定）
--        · target_type：APP=按应用 / API=按接口（scope_type=1 必填；=2 平台全局时为 NULL）
--        · target_ids ：NULL = 该维度下全部对象（与 channel_ids/receiver_ids 的逗号串约定一致）
--      同一批种子在 src/backend/src/main/resources/sql/init.sql 也有一份（全新初始化主入口），
--      链式执行时 00 脚本先落库、本段 INSERT IGNORE 自然跳过；两处数值需保持一致。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO alarm_rule (id, rule_name, alarm_type, scope_type, target_type, target_ids, threshold, time_window, alarm_level, silence_period, channel_ids, receiver_scope, receiver_ids, receiver_desc, status) VALUES
(1, '调用失败率告警', 'FAIL_RATE',    1, 'API', NULL, '>5',          5,     3, 30,    '1,3', 'ASSIGNEE', NULL, '各接口负责人', 1),
(2, '鉴权失败告警',   'AUTH_FAIL',    1, 'APP', NULL, '>10',         5,     3, 10,    '1,3', 'USER',     NULL, '张三、周八',   1),
(3, '配额使用率告警', 'QUOTA_USAGE',  1, 'APP', NULL, '>80',         60,    2, 120,   '1',   'ASSIGNEE', NULL, '各应用负责人', 1),
(4, '后端超时告警',   'AVG_LATENCY',  1, 'API', NULL, '>10000',      5,     2, 30,    '1',   'ASSIGNEE', NULL, '各接口负责人', 1),
(5, '密钥即将过期',   'KEY_EXPIRE',   1, 'APP', NULL, '提前30天',    1440,  2, 1440,  '3,1', 'ASSIGNEE', NULL, '各应用负责人', 1),
(6, '僵尸接口告警',   'ZOMBIE_API',   1, 'API', NULL, '30天无调用',  43200, 1, 10080, '3',   'ASSIGNEE', NULL, '各接口负责人', 1),
(7, 'QPS 突增告警',   'QPS_SURGE',    2, NULL,  NULL, '>200%基线',   5,     2, 30,    '1',   'USER',     NULL, '张三',         0);

-- ---------------------------------------------------------------------------
-- 1.11 动态封禁规则（block_rule）—— 原型 MOCK.blockRules
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO block_rule (id, scope, reason_code, threshold_desc, threshold_count, window_minutes, ttl_seconds, auto_block, enabled, description) VALUES
(1, 'IP',  'REPLAY_ATTACK',      '同 IP 5min ≥ 20 次',       20, 5,  600,  1, 0, '重放攻击'),
(2, 'APP', 'SIGNATURE_MISMATCH', '同 AppKey 10min ≥ 50 次',  50, 10, 3600, 1, 0, '签名连续失败（疑似爆破/泄露）'),
(3, 'APP', 'RATE_LIMIT_EXCEEDED','持续命中限流 > 5min',       5, 5,  1800, 1, 0, '配额击穿（恶意刷接口）'),
(4, 'IP',  'IP_NOT_ALLOWED',     '同 IP 5min ≥ 30 次',       30, 5,  3600, 1, 0, '白名单外反复探测'),
(5, 'APP', 'MANUAL',             '人工触发',                  0, 0,  0,    0, 1, '人工封禁（含永久）');


-- ############################################################################
-- 二、存量数据回填（只补 NULL / 默认值，不覆盖已有业务数据）
-- ############################################################################

-- ---------------------------------------------------------------------------
-- 2.1 app：应用编码 / 业务线 / 类型 / 环境范围 / 审核状态
-- ---------------------------------------------------------------------------
UPDATE app SET app_code = CONCAT('app_', id)                     WHERE app_code IS NULL OR app_code = '';
UPDATE app SET line_id  = @GK_DEFAULT_LINE_ID                    WHERE line_id IS NULL;
UPDATE app SET env_scope = @GK_DEFAULT_ENV                       WHERE env_scope IS NULL OR env_scope = '';
UPDATE app SET app_type = 1                                      WHERE app_type IS NULL;
UPDATE app SET audit_status = 1                                  WHERE audit_status IS NULL;
UPDATE app SET approval_required = 0                             WHERE approval_required IS NULL;
UPDATE app SET contact_info = description                        WHERE (contact_info IS NULL OR contact_info = '') AND description IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2.2 app_ip_whitelist：环境回填
-- ---------------------------------------------------------------------------
UPDATE app_ip_whitelist SET env_code = @GK_DEFAULT_ENV WHERE env_code IS NULL OR env_code = '';

-- ---------------------------------------------------------------------------
-- 2.3 api_group：分组编码 / 业务线 / 状态
-- ---------------------------------------------------------------------------
UPDATE api_group SET group_code = CONCAT('group_', id) WHERE group_code IS NULL OR group_code = '';
UPDATE api_group SET line_id    = @GK_DEFAULT_LINE_ID  WHERE line_id IS NULL;
UPDATE api_group SET status     = 1                    WHERE status IS NULL;

-- ---------------------------------------------------------------------------
-- 2.4 api_interface：接口编码 / 业务线 / 发布状态 / 版本
--      · 存量 status=1(启用) → publish_status=2(已发布)
--      · 存量 status=0(停用) → publish_status=0(草稿)
--      该 UPDATE 幂等：首次执行后条件不再成立
-- ---------------------------------------------------------------------------
UPDATE api_interface SET api_code = CONCAT('api_', id)   WHERE api_code IS NULL OR api_code = '';
UPDATE api_interface SET line_id  = @GK_DEFAULT_LINE_ID  WHERE line_id IS NULL;
UPDATE api_interface SET publish_status = 0              WHERE status = 0 AND publish_status = 2;
UPDATE api_interface SET current_version = 'v1'          WHERE current_version IS NULL OR current_version = '';
UPDATE api_interface SET transport_security = 'NONE'     WHERE transport_security IS NULL OR transport_security = '';

-- ---------------------------------------------------------------------------
-- 2.5 api_call_log：环境回填
--     说明：原型 callLogs.apiId 对应存量 interface_id，属同义字段，不新增冗余列；
--           trace_id 历史数据留空（无法反推），仅新写入的日志带 trace_id
-- ---------------------------------------------------------------------------
UPDATE api_call_log SET env_code = @GK_DEFAULT_ENV WHERE env_code IS NULL OR env_code = '';

-- ---------------------------------------------------------------------------
-- 2.6 ip_ban：扩展为统一封禁名单（scope/target/reason_code/ttl）
-- ---------------------------------------------------------------------------
UPDATE ip_ban
SET target       = ip_address,
    scope        = 'IP',
    reason_code  = 'MANUAL',
    block_times  = 1,
    ttl_seconds  = GREATEST(0, COALESCE(TIMESTAMPDIFF(SECOND, ban_start_time, ban_end_time), 0)),
    created_by   = 0
WHERE target IS NULL OR target = '';

UPDATE ip_ban SET env_code = @GK_DEFAULT_ENV WHERE env_code IS NULL OR env_code = '';

-- ---------------------------------------------------------------------------
-- 2.7 alert：告警级别由 level 文本映射（INFO→1 / WARNING→2 / CRITICAL→3）
-- ---------------------------------------------------------------------------
UPDATE alert
SET alarm_level = CASE level WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END
WHERE alarm_level IS NULL;

UPDATE alert SET env_code = @GK_DEFAULT_ENV WHERE env_code IS NULL;

-- ---------------------------------------------------------------------------
-- 2.8 sys_user：业务线回填（历史账号统一挂默认业务线，后续由管理员调整）
-- ---------------------------------------------------------------------------
UPDATE sys_user SET line_id = @GK_DEFAULT_LINE_ID WHERE line_id IS NULL;


-- ############################################################################
-- 三、存量表 → 新表 数据搬迁（全部带 NOT EXISTS 反连，可重复执行）
-- ############################################################################

-- ---------------------------------------------------------------------------
-- 3.1 app.app_key/app_secret → app_credential（一应用一密钥 → 一应用多密钥）
-- ---------------------------------------------------------------------------
INSERT INTO app_credential
    (app_id, app_key, app_secret, secret_mask, alias, env_code, status, expire_time, rotate_flag, create_time)
SELECT a.id,
       a.app_key,
       a.app_secret,
       CONCAT(LEFT(a.app_secret, 4), '****', RIGHT(a.app_secret, 4)),
       '默认密钥（迁移生成）',
       @GK_DEFAULT_ENV,
       1,
       a.expire_time,
       0,
       a.created_at
FROM app a
WHERE NOT EXISTS (SELECT 1 FROM app_credential c WHERE c.app_key = a.app_key);

-- ---------------------------------------------------------------------------
-- 3.2 app_rate_limit → app_quota（应用级限流 → 应用 × 环境 配额）
-- ---------------------------------------------------------------------------
INSERT INTO app_quota (app_id, env_code, global_qps, daily_quota, monthly_quota, concurrency)
SELECT r.app_id, @GK_DEFAULT_ENV, r.qps_limit, r.daily_limit, 0, r.concurrent_limit
FROM app_rate_limit r
WHERE NOT EXISTS (SELECT 1 FROM app_quota q WHERE q.app_id = r.app_id AND q.env_code = @GK_DEFAULT_ENV);

-- 无 app_rate_limit 记录的应用补一条「不限」配额，保证配额页不出现空白
INSERT INTO app_quota (app_id, env_code, global_qps, daily_quota, monthly_quota, concurrency)
SELECT a.id, @GK_DEFAULT_ENV, 0, 0, 0, 0
FROM app a
WHERE NOT EXISTS (SELECT 1 FROM app_quota q WHERE q.app_id = a.id AND q.env_code = @GK_DEFAULT_ENV);

-- ---------------------------------------------------------------------------
-- 3.3 api_interface → api_version（每个接口补一条 v1 作为当前版本）
-- ---------------------------------------------------------------------------
INSERT INTO api_version (api_id, version, status, is_current, gray_ratio, change_log)
SELECT i.id, 'v1', 1, 1, 100, '存量接口迁移自动生成的初始版本'
FROM api_interface i
WHERE NOT EXISTS (SELECT 1 FROM api_version v WHERE v.api_id = i.id AND v.version = 'v1');

-- ---------------------------------------------------------------------------
-- 3.4 api_interface.backend_url → api_env_config（仅补 prod，其余环境按需在控制台配置）
-- ---------------------------------------------------------------------------
INSERT INTO api_env_config
    (api_id, env_code, version, upstream_url, connect_timeout, read_timeout, retry_count, mock_enabled, config_status)
SELECT i.id, @GK_DEFAULT_ENV, NULL, i.backend_url, 1000, i.timeout_ms, 0, 0, 1
FROM api_interface i
WHERE NOT EXISTS (
    SELECT 1 FROM api_env_config c
    WHERE c.api_id = i.id AND c.env_code = @GK_DEFAULT_ENV AND c.version IS NULL
);

-- ---------------------------------------------------------------------------
-- 3.5 app_api_permission → app_api_grant（无审批授权 → 带审批/有效期的授权）
--      status: 存量 1=已授权 → 1=已生效；存量 0=已取消 → 3=已撤销
-- ---------------------------------------------------------------------------
INSERT INTO app_api_grant
    (app_id, api_id, env_code, qps_limit, daily_quota, status, valid_from, valid_to,
     grant_reason, applicant_name, auditor_name, audit_time)
SELECT p.app_id, p.interface_id, @GK_DEFAULT_ENV, 0, 0, 1,
       CURDATE(), DATE_ADD(CURDATE(), INTERVAL 1 YEAR),
       '存量授权迁移', '系统迁移', '系统迁移', NOW()
FROM app_api_permission p
WHERE p.status = 1
  AND NOT EXISTS (
      SELECT 1 FROM app_api_grant g
      WHERE g.app_id = p.app_id AND g.api_id = p.interface_id AND g.env_code = @GK_DEFAULT_ENV
  );

INSERT INTO app_api_grant
    (app_id, api_id, env_code, qps_limit, daily_quota, status, valid_from, valid_to,
     grant_reason, applicant_name, auditor_name, audit_time)
SELECT p.app_id, p.interface_id, @GK_DEFAULT_ENV, 0, 0, 3,
       CURDATE(), CURDATE(),
       '存量授权迁移（原已取消）', '系统迁移', '系统迁移', NOW()
FROM app_api_permission p
WHERE p.status = 0
  AND NOT EXISTS (
      SELECT 1 FROM app_api_grant g
      WHERE g.app_id = p.app_id AND g.api_id = p.interface_id AND g.env_code = @GK_DEFAULT_ENV
  );


-- ############################################################################
-- 四、统计冗余字段重算（每次执行全量重算，天然幂等）
-- ############################################################################

UPDATE api_group g
SET api_count = (SELECT COUNT(*) FROM api_interface i WHERE i.group_id = g.id);

UPDATE api_interface i
SET grant_count = (SELECT COUNT(*) FROM app_api_grant gr WHERE gr.api_id = i.id AND gr.status = 1);

UPDATE sys_role r
SET user_count = (SELECT COUNT(*) FROM sys_user_role ur WHERE ur.role_id = r.id);


-- ############################################################################
-- 五、迁移完成标记（可用于应用启动自检）
-- ############################################################################
INSERT IGNORE INTO sys_config (id, config_key, config_value, config_group, config_name, `sensitive`, built_in, remark)
VALUES (1000, 'gk.schema.version', 'v2', 'DEFAULT', '数据模型版本', 0, 1, '由 migrate-v2.sql 写入，用于应用启动自检');

-- ============================================================================
-- 迁移自检（可选）：执行后人工核对
--   SELECT 'biz_line' t, COUNT(*) c FROM biz_line
--   UNION ALL SELECT 'env',        COUNT(*) FROM env
--   UNION ALL SELECT 'sys_menu',   COUNT(*) FROM sys_menu
--   UNION ALL SELECT 'app_credential', COUNT(*) FROM app_credential
--   UNION ALL SELECT 'app_quota',  COUNT(*) FROM app_quota
--   UNION ALL SELECT 'app_api_grant', COUNT(*) FROM app_api_grant
--   UNION ALL SELECT 'api_version',COUNT(*) FROM api_version
--   UNION ALL SELECT 'api_env_config', COUNT(*) FROM api_env_config;
--
-- 期望：biz_line=6, env=4, sys_menu=54(6模块+28权限点+20页面),
--       app_credential ≥ 存量 app 数, app_quota ≥ 存量 app 数,
--       app_api_grant = 存量 app_api_permission 数,
--       api_version ≥ 存量 api_interface 数, api_env_config ≥ 存量 api_interface 数
-- ============================================================================
