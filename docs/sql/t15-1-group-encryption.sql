-- ============================================================
-- T15-1 迁移：接口分组加解密配置（分组树向上继承 + 三态可显式关闭）
-- ============================================================
--
-- 需求背景（2026-09-15 用户第 1 条）：
--   「部分业务接口需要加解密配置，在接口分组中添加接口加解密，
--     下级继承上级的加解密逻辑，可以选择不需要加解密。」
--
-- 设计要点：
--   1. **新增表而不是改 api_encryption_config / app_encryption_config**：
--      两张老表一行不动、全部兼容（演进式重构铁律：只加表，不做破坏性变更）。
--   2. **三态 mode 是本需求的关键**：老的 request_encrypted 只有「是/否」两态，
--      表达不了「继承上级」与「显式不加密」的区别。若不引入三态，
--      子分组一旦落库就意味着"切断继承"，用户无法表达"我要继承"。
--        · INHERIT  继承上级（默认值；本行等价于"未配置"，继续沿 parent_id 上溯）
--        · ENABLED  本分组启用加解密（终止上溯，采用本行配置）
--        · DISABLED 显式不需要加解密（终止上溯，且**不回退**应用级）
--   3. **唯一键 (group_id)**：一个分组一条加解密配置（加解密是接口契约，不分环境）。
--      刻意不设 env_code —— 与 api_group_env_config 不同，加解密配置与环境无关；
--      也不设 version 列（老表 api_encryption_config 的 version 坑：可空导致唯一键失效）。
--   4. **网关解析优先级**（高 → 低）：
--        接口级 api_encryption_config  >  分组级（本表，沿 parent_id 上溯）  >  应用级 app_encryption_config
--      其中「接口级存在行即终止」是既有语义（保留），分组级引入后排在两者之间。
--      解析由 com.gatekeeper.gateway.EncryptionConfigResolver 统一负责，杜绝多处各写一份父链遍历。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE（sys_menu 有 uk_menu_perm，sys_role_menu 有主键）。
--
-- 执行顺序（全量初始化）：
--   init.sql -> schema-v2.sql -> migrate-v2.sql -> t13-group-env-config.sql -> **t15-1-group-encryption.sql**
-- 存量库（已是 v2）：直接执行本文件即可。
--
-- 执行后必做：DEL gk:perm:*   —— 权限缓存优先且 TTL 24h，不清会「看着没生效」。
--
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 分组加解密配置表
-- ---------------------------------------------------------------------------
-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `api_group_encryption_config` (
  `id`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_id`           bigint       NOT NULL                COMMENT '接口分组ID（api_group.id）',
  `mode`               varchar(16)  NOT NULL DEFAULT 'INHERIT'
                       COMMENT 'INHERIT=继承上级, ENABLED=本分组启用, DISABLED=显式不加密（阻断继承）',
  `request_encrypted`  tinyint      NOT NULL DEFAULT '0'    COMMENT '1=入参加密（mode=ENABLED 时有效）',
  `request_algorithm`  varchar(16)           DEFAULT NULL   COMMENT '入参算法 SM4/AES',
  `request_mode`       varchar(16)           DEFAULT NULL   COMMENT '入参模式 ECB/CBC/CFB/OFB/CTR',
  `request_key`        varchar(512)          DEFAULT NULL   COMMENT '入参密钥（Base64）',
  `request_iv`         varchar(256)          DEFAULT NULL   COMMENT '入参 IV（Base64，ECB 可空）',
  `request_padding`    varchar(32)           DEFAULT NULL   COMMENT '入参填充方式',
  `response_encrypted` tinyint      NOT NULL DEFAULT '0'    COMMENT '1=返参加密（mode=ENABLED 时有效）',
  `response_algorithm` varchar(16)           DEFAULT NULL   COMMENT '返参算法',
  `response_mode`      varchar(16)           DEFAULT NULL   COMMENT '返参模式',
  `response_key`       varchar(512)          DEFAULT NULL   COMMENT '返参密钥（Base64）',
  `response_iv`        varchar(256)          DEFAULT NULL   COMMENT '返参 IV（Base64）',
  `response_padding`   varchar(32)           DEFAULT NULL   COMMENT '返参填充方式',
  `remark`             varchar(256)          DEFAULT NULL   COMMENT '备注（说明为何启用/关闭）',
  `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gec_group` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='接口分组加解密配置表（分组树向上继承；接口级覆盖见 api_encryption_config）';

-- ---------------------------------------------------------------------------
-- 2) 新增权限点（pid=3 接口管理；ID 段 356-358，T13 已占用至 355）
-- ---------------------------------------------------------------------------
-- risk_flag=1 = 高危（SysOperationLogAspect 强制写审计日志）：
--   改密钥/算法会直接导致线上调用方解密失败，属高危变更。
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(356, 3, '新增分组加解密配置', 3, 'api_group_encryption:create', NULL, 1, 16, 1),
(357, 3, '编辑分组加解密配置', 3, 'api_group_encryption:update', NULL, 1, 17, 1),
(358, 3, '删除分组加解密配置', 3, 'api_group_encryption:delete', NULL, 1, 18, 1);

-- ---------------------------------------------------------------------------
-- 3) 角色授权（沿用 T13/T03b 口径：照抄同语义既有权限点的持有角色集合）
-- ---------------------------------------------------------------------------
-- 🔴 服务端**没有**超管通配（'*' 只在前端 utils/perm.js），未播种的码连 SUPER_ADMIN 都拿不到。
--    因此必须显式授权；这里用「模板权限点的持有角色」动态推导，而不是硬编码 role_id，
--    未来角色矩阵调整后重跑本脚本仍然正确。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT tmpl_rm.role_id, new_menu.id
FROM sys_menu new_menu
JOIN sys_menu tmpl ON tmpl.perm_code = CASE new_menu.perm_code
        WHEN 'api_group_encryption:create' THEN 'api_group:create'
        WHEN 'api_group_encryption:update' THEN 'api_group:update'
        WHEN 'api_group_encryption:delete' THEN 'api_group:delete'
    END
JOIN sys_role_menu tmpl_rm ON tmpl_rm.menu_id = tmpl.id
WHERE new_menu.perm_code IN ('api_group_encryption:create',
                             'api_group_encryption:update',
                             'api_group_encryption:delete');

-- ---------------------------------------------------------------------------
-- 4) 校验（人工执行参考）
-- ---------------------------------------------------------------------------
-- -- 表结构
-- SHOW CREATE TABLE api_group_encryption_config\G
--
-- -- 新增权限点（应 3 行）
-- SELECT id, pid, name, perm_code, risk_flag FROM sys_menu
--  WHERE perm_code LIKE 'api_group_encryption:%' ORDER BY id;
--
-- -- 角色授权（应与 api_group:create/update/delete 的持有角色完全一致）
-- SELECT r.role_code, COUNT(*) c FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
--  WHERE m.perm_code LIKE 'api_group_encryption:%' GROUP BY r.role_code ORDER BY r.role_code;
--
-- -- 权限缓存必须清
-- -- redis-cli --scan --pattern 'gk:perm:*' | xargs -r redis-cli DEL
