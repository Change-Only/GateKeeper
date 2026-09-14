-- ============================================================
-- T13 种子/迁移：接口环境配置「下沉到接口分组 + 分组树向上继承」
-- ============================================================
--
-- 需求背景（2026-09-14 用户第 4 条）：
--   「接口环境配置可以不用在接口侧，改为在接口分组侧，这样维护的地方比较明确，
--     而且环境配置可以继承父级数据」
--
-- 设计要点：
--   1. **新增表而不是改 api_env_config**：老表 api_env_config（接口级）一行都不动，
--      全部保留、全部兼容（网关解析优先级：接口级覆盖 > 分组继承 > api_interface.backend_url 兜底）。
--      铁律「演进式重构，不推翻重写」——只加表、不加破坏性列。
--   2. **一个分组一个环境只允许一条配置**：UNIQUE KEY (group_id, env_code)。
--      ⚠️ 刻意**不设 version 列**：老表 uk_api_env_ver(api_id, env_code, version) 里
--      version 可空 ⇒ MySQL 唯一索引对 NULL 不生效，同 (api_id, env_code) 可以塞进多条，
--      这正是历史坑的来源之一。分组配置不需要版本维度，故从根上不给这个口子。
--   3. **继承语义 = 沿分组树向上找"最近的、配了该环境的那一层"**，整条配置整体继承
--      （前缀 / 连接超时 / 读取超时 / 重试 / Mock 一起来），不做逐字段合并。
--   4. **Mock 真正生效**：mock_enabled=1 时网关**短路、不转发**，按 mock_status + mock_response 返回。
--      老实现只存不用（网关从不读 mock_enabled），这是用户第 1 条 bug 的根因。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（sys_menu 有 uk_menu_perm，sys_role_menu 有主键）。
--       重复执行不产生重复行、不报错。
--
-- 执行顺序（全量初始化）：
--   init.sql -> schema-v2.sql -> migrate-v2.sql -> **t13-group-env-config.sql**
-- 存量库（已是 v2）：直接执行本文件即可。
--
-- 应用执行：
--   mysql -h <host> -P <port> -u <user> -p<pass> gatekeeper < docs/sql/t13-group-env-config.sql
--
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 分组环境配置表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `api_group_env_config` (
  `id`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_id`        bigint       NOT NULL                COMMENT '接口分组ID（api_group.id）',
  `env_code`        varchar(32)  NOT NULL                COMMENT '环境编码 dev/test/pre/prod',
  `upstream_url`    varchar(512) NOT NULL                COMMENT '服务前缀（不含 URI；完整地址=前缀+接口URI）',
  `connect_timeout` int          NOT NULL DEFAULT '1000' COMMENT '连接超时(ms)',
  `read_timeout`    int          NOT NULL DEFAULT '3000' COMMENT '读取超时(ms)',
  `retry_count`     int          NOT NULL DEFAULT '0'    COMMENT '重试次数',
  `mock_enabled`    tinyint      NOT NULL DEFAULT '0'    COMMENT '1=开启Mock（网关短路不转发）',
  `mock_status`     int          NOT NULL DEFAULT '200'  COMMENT 'Mock 返回的 HTTP 状态码',
  `mock_response`   text                  DEFAULT NULL   COMMENT 'Mock 返回体（留空=返回默认提示 JSON）',
  `config_status`   tinyint      NOT NULL DEFAULT '1'    COMMENT '1=已配置, 2=已验证（连通性测试通过）',
  `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gec_group_env` (`group_id`, `env_code`),
  KEY `idx_gec_env` (`env_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='接口分组环境配置表（分组树向上继承；接口级覆盖见 api_env_config）';

-- ---------------------------------------------------------------------------
-- 2) 新增权限点（pid=3 接口管理；ID 段 351-355，实测 350 之后空闲）
-- ---------------------------------------------------------------------------
-- risk_flag=1 表示高危操作（SysOperationLogAspect 强制写审计日志）：
--   - api_group_env_config:create / :update / :delete  改上游地址与 Mock 开关 = 直接影响线上流量
--   - api_group_env_config:test / interface:test       只读诊断动作，非高危
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(351, 3, '新增分组环境配置', 3, 'api_group_env_config:create', NULL, 1, 11, 1),
(352, 3, '编辑分组环境配置', 3, 'api_group_env_config:update', NULL, 1, 12, 1),
(353, 3, '删除分组环境配置', 3, 'api_group_env_config:delete', NULL, 1, 13, 1),
(354, 3, '分组环境连通测试', 3, 'api_group_env_config:test',   NULL, 0, 14, 1),
(355, 3, '接口试调测试',     3, 'interface:test',              NULL, 0, 15, 1);

-- ---------------------------------------------------------------------------
-- 3) 角色授权（沿用 T03b 的角色矩阵口径）
-- ---------------------------------------------------------------------------
-- 🔴 铁律修正（2026-09-14 实测踩坑）：**服务端没有超管通配** —— '`*`' 只存在于前端
--    utils/perm.js，后端 PermissionCacheService 从 sys_menu 取权、从不产出 '*'。
--    因此「SUPER_ADMIN 自动拥有全部权限」是**错误假设**：本文件首版只给
--    ADMIN / BIZ_ADMIN / API_PROVIDER 插了授权，实测 SUPER_ADMIN(1) 与 OPERATOR(2)
--    对新权限点的授权数为 **0**，超级管理员登录后调本域任何写接口都会 403。
--
-- 修正口径（不拍脑袋）：**逐个新权限点照抄同语义既有权限点的持有角色集合**——
--     api_group_env_config:create / :update / :delete  <- api_group:create / :update / :delete
--     api_group_env_config:test                       <- api_env_config:test
--     interface:test                                  <- api_env_config:test
--   为什么按 api_group:* 而不是 api_env_config:* 取 CRUD：T13 起环境配置的**归属域**
--   已从「接口」变为「分组」，api_group:* 才是同域兄弟。
--   实测补齐后持有者为 5 个角色：SUPER_ADMIN / ADMIN / BIZ_ADMIN / API_PROVIDER / OPERATOR。
--
-- 未授权角色（有意为之）：SECURITY_AUDITOR / API_CONSUMER / AUDITOR / EXTERNAL_PM
-- —— 它们既不维护接口资产，也不参与环境配置。
--
-- 幂等：sys_role_menu 主键 (role_id, menu_id) + INSERT IGNORE，重复执行不产生重复行。
-- 注意：这里用「模板权限点的持有角色」动态推导，而不是硬编码 role_id，
--       这样未来角色矩阵调整后重跑本脚本仍然是正确的。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT tmpl_rm.role_id, new_menu.id
FROM sys_menu new_menu
JOIN sys_menu tmpl ON tmpl.perm_code = CASE new_menu.perm_code
        WHEN 'api_group_env_config:create' THEN 'api_group:create'
        WHEN 'api_group_env_config:update' THEN 'api_group:update'
        WHEN 'api_group_env_config:delete' THEN 'api_group:delete'
        WHEN 'api_group_env_config:test'   THEN 'api_env_config:test'
        WHEN 'interface:test'              THEN 'api_env_config:test'
    END
JOIN sys_role_menu tmpl_rm ON tmpl_rm.menu_id = tmpl.id
WHERE new_menu.perm_code IN ('api_group_env_config:create', 'api_group_env_config:update',
                             'api_group_env_config:delete', 'api_group_env_config:test',
                             'interface:test');

-- ---------------------------------------------------------------------------
-- 4) 校验（人工执行参考）
-- ---------------------------------------------------------------------------
-- -- 表结构
-- SHOW CREATE TABLE api_group_env_config\G
--
-- -- 新增权限点（应 5 行）
-- SELECT id, pid, name, perm_code, risk_flag FROM sys_menu
--  WHERE perm_code LIKE 'api_group_env_config:%' OR perm_code = 'interface:test' ORDER BY id;
--
-- -- 角色授权（应为 5 个角色各 5 行 = 25 行：SUPER_ADMIN/ADMIN/BIZ_ADMIN/API_PROVIDER/OPERATOR）
-- SELECT r.role_code, COUNT(*) c FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
--  WHERE m.perm_code IN ('api_group_env_config:create','api_group_env_config:update',
--        'api_group_env_config:delete','api_group_env_config:test','interface:test')
--  GROUP BY r.role_code ORDER BY r.role_code;
--
-- -- 权限缓存必须清（否则登录后 24h 内看不到新权限点）
-- -- redis-cli --scan --pattern 'gk:perm:*' | xargs -r redis-cli DEL
