-- ============================================================
-- T15-4 迁移：IP 访问白名单（应用级补全 + 系统级新增）
-- ============================================================
--
-- 需求背景（2026-09-15 用户第 4 条）：
--   「系统访问白名单在应用设置中可配置。」
--   经确认口径为「两者都做」：
--     ① 完善既有**应用级** IP 白名单（app_ip_whitelist）
--     ② 新增**系统级**访问白名单（sys_ip_whitelist）
--
-- ---------------------------------------------------------------------------
-- 一、应用级（app_ip_whitelist）：**不加列，只修行为**
-- ---------------------------------------------------------------------------
-- 该表早已存在，且线上表结构里就有 `env_code`(默认 'prod') 与 `status`(默认 1) 两列
-- —— 缺陷不在表，而在代码：
--   · AppIpWhitelist 实体未映射这两列；
--   · IpWhitelistHandler 查白名单**不带 status 过滤**
--     ⇒ 被"停用"(status=0)的条目依然在拦人，用户无法通过停用临时放行某段 IP。
-- 因此本迁移对应用级表**不做任何 DDL**（只加列不删列的演进铁律下也无需加列），
-- 仅由代码侧补齐映射与过滤。
--
-- 语义澄清（避免误用）：`env_code` 是**归类标注**维度（便于按环境分组查看），
-- **不参与网关校验**。若按 env 过滤，会出现在 A 环境配了 B 环境的行就把人拦掉的情况，
-- 而白名单是"要么放行要么拦死"的强语义，误拦代价远高于误放，故校验只看 `status`。
--
-- ---------------------------------------------------------------------------
-- 二、系统级（sys_ip_whitelist）：新表
-- ---------------------------------------------------------------------------
-- 作用域：**网关 API 入口的全局前置校验**（责任链最前，@Order(0)，
--        先于 AppAuthHandler 执行）。语义是"本系统的入口只对这些来源开放"。
-- 生效规则（与既有 Redis fail-open 取向一致）：
--   · 表为空 或 无 status=1 的行  ⇒ **不限制**（默认放行，不改变现状）
--   · 存在 status=1 的行          ⇒ 只有命中任一 CIDR 的来源 IP 可访问**任何**应用的接口
--   · 查询异常                   ⇒ fail-open 放行 + WARN（不因配置面故障拖垮整个网关）
-- 配置入口：应用管理页「系统访问白名单」（系统级配置，非某应用专属）。
--
-- ---------------------------------------------------------------------------
-- 三、新增权限点
-- ---------------------------------------------------------------------------
-- 只加 1 个：`app:ipwhitelist:update`（应用级白名单的「编辑 / 启用停用」）。
-- 系统级白名单的写操作**刻意复用既有的 `sys:security:update`**：
--   · 它的维护入口在「系统设置 → 安全策略」语义范畴内，该页既有 CRUD 也已用同一码；
--   · 复用已播种、已授权的码，避免"新增码忘了授权 ⇒ 连 SUPER_ADMIN 都拿不到"的老坑
--     （服务端无超管通配，详见 docs/T08-权限执行缺口-契约记录.md）。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（sys_menu 有 uk_menu_perm，sys_role_menu 有主键）。
--
-- 执行顺序（全量初始化）：
--   init.sql -> schema-v2.sql -> migrate-v2.sql -> t13-group-env-config.sql
--   -> t15-1-group-encryption.sql -> **t15-4-whitelist.sql**
--
-- 执行后必做：DEL gk:perm:*   —— 权限缓存优先且 TTL 24h，不清会「看着没生效」。
--
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 系统级访问白名单表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_ip_whitelist` (
  `id`         bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ip_cidr`    varchar(64) NOT NULL                COMMENT '允许访问的 IP 或 CIDR（如 10.0.0.0/24）',
  `remark`     varchar(256)         DEFAULT NULL   COMMENT '备注（说明为何放行）',
  `status`     tinyint     NOT NULL DEFAULT '1'    COMMENT '1=启用（参与校验），0=停用（不参与，便于临时摘除）',
  `created_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_syswl_cidr` (`ip_cidr`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='系统级访问白名单（网关入口全局前置校验；表空=不限制）';

-- 刻意不播种任何行：表空 = 不限制 = 与改造前行为完全一致。
-- 「空表即不限制」是本次改造对存量环境**零影响**的关键，
-- 也避免迁移脚本一跑就把测试机/管理员自己挡在门外。

-- ---------------------------------------------------------------------------
-- 2) 新增权限点（pid=2 应用管理；ID 段 359）
-- ---------------------------------------------------------------------------
-- risk_flag=1 = 高危（SysOperationLogAspect 强制写审计日志）：
--   IP 白名单直接决定"谁能进来"，改错等于把人挡在门外（或放进来）。
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(359, 2, '编辑IP白名单', 3, 'app:ipwhitelist:update', NULL, 1, 12, 1);

-- ---------------------------------------------------------------------------
-- 3) 角色授权（沿用 T13/T15-1 口径：照抄同语义既有权限点的持有角色集合）
-- ---------------------------------------------------------------------------
-- 编辑/启停白名单的能力边界与「新增白名单」完全一致（都是改这个应用的来源限制），
-- 故直接继承 `app:ipwhitelist:add` 的持有角色集合。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT tmpl_rm.role_id, new_menu.id
FROM sys_menu new_menu
JOIN sys_menu tmpl ON tmpl.perm_code = 'app:ipwhitelist:add'
JOIN sys_role_menu tmpl_rm ON tmpl_rm.menu_id = tmpl.id
WHERE new_menu.perm_code = 'app:ipwhitelist:update';

-- ---------------------------------------------------------------------------
-- 4) 校验（人工执行参考）
-- ---------------------------------------------------------------------------
-- -- 新表结构
-- SHOW CREATE TABLE sys_ip_whitelist\G
--
-- -- 新表应为空（空 = 不限制）
-- SELECT COUNT(*) FROM sys_ip_whitelist;
--
-- -- 新增权限点（应 1 行）
-- SELECT id, pid, name, perm_code, risk_flag FROM sys_menu
--  WHERE perm_code = 'app:ipwhitelist:update';
--
-- -- 授权应与 app:ipwhitelist:add 的持有角色集合完全一致
-- SELECT r.role_code FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
--  WHERE m.perm_code = 'app:ipwhitelist:update' ORDER BY r.role_code;
-- SELECT r.role_code FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
--  WHERE m.perm_code = 'app:ipwhitelist:add' ORDER BY r.role_code;
--
-- -- 权限缓存必须清
-- -- redis-cli --scan --pattern 'gk:perm:*' | xargs -r redis-cli DEL
