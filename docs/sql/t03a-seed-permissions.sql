-- ============================================================
-- T03a 种子：业务线 / 环境 / 应用凭证 三大基础域 13 个新权限点
-- ============================================================
--
-- 用途：
--   在 T01（54 个 sys_menu 节点：6 模块 + 28 权限点 + 20 页面）与 T02（@RequirePerm + 权限拦截）
--   的基础上，为 T03a 三大主数据域补充 13 个权限点，供 PermissionCacheService 加载使用。
--
-- 幂等：所有 INSERT 走 sys_menu.uk_menu_perm 唯一键，重复执行不会产生重复行。
--
-- 应用执行：
--   mysql -h <host> -P <port> -u <user> -p<pass> gatekeeper < docs/sql/t03a-seed-permissions.sql
--
-- 权限点风险标记说明：
--   risk_flag=1 表示「高危操作」，SysOperationLogAspect 会强制写审计日志。
--   本批 T03a 共 4 个高危：
--     - biz_line:delete         删除业务线
--     - env:delete              删除环境
--     - app_credential:create   创建凭证
--     - app:credential:revoke   吊销凭证（T02 已落地，T03a 仅复用权限点）

-- ---------------------------------------------------------------------------
-- 1) 业务线（biz_line）—— 新增 4 个权限点（1 高危）
-- ---------------------------------------------------------------------------
-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(71, 5, '查看业务线',   3, 'biz_line:list',     NULL, 0, 11, 1),
(72, 5, '新建业务线',   3, 'biz_line:create',   NULL, 0, 12, 1),
(73, 5, '编辑业务线',   3, 'biz_line:update',   NULL, 0, 13, 1),
(74, 5, '删除业务线',   3, 'biz_line:delete',   NULL, 1, 14, 1);

-- 业务线管理页面节点（type=2）
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(221, 5, '业务线管理',   2, NULL, '/sys/bizline',    0, 3, 1);

-- ---------------------------------------------------------------------------
-- 2) 环境（env）—— 新增 4 个权限点（1 高危）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(81, 5, '查看环境',     3, 'env:list',     NULL, 0, 21, 1),
(82, 5, '新建环境',     3, 'env:create',   NULL, 0, 22, 1),
(83, 5, '编辑环境',     3, 'env:update',   NULL, 0, 23, 1),
(84, 5, '删除环境',     3, 'env:delete',   NULL, 1, 24, 1);

-- 环境与网关页面节点（已由 T01 建过 route='/sys/env'，此处不重复插入）

-- ---------------------------------------------------------------------------
-- 3) 应用凭证（app_credential）—— 新增 5 个权限点（2 高危）
--    app_credential:create / app:credential:revoke 高危；
--    app_credential:list / app_credential:rotate / app_credential:update 常规
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(91,  2, '查看凭证',          3, 'app_credential:list',     NULL, 0, 11, 1),
(92,  2, '创建凭证',          3, 'app_credential:create',   NULL, 1, 12, 1),
(93,  2, '轮换凭证',          3, 'app_credential:rotate',   NULL, 0, 13, 1),
(94,  2, '吊销凭证',          3, 'app:credential:revoke',   NULL, 1, 14, 1),
(95,  2, '编辑凭证',          3, 'app_credential:update',   NULL, 0, 15, 1);

-- ---------------------------------------------------------------------------
-- 4) 角色授权（sys_role_menu）—— 为 ADMIN/BIZ_ADMIN/API_PROVIDER/API_CONSUMER
--    分配新增的 13 个权限点，便于 admin 登录后无感升级
-- ---------------------------------------------------------------------------

-- ADMIN（平台管理员）→ 全部 13 个
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'ADMIN'
  AND m.perm_code IN (
    'biz_line:list', 'biz_line:create', 'biz_line:update', 'biz_line:delete',
    'env:list',      'env:create',      'env:update',      'env:delete',
    'app_credential:list', 'app_credential:create', 'app_credential:rotate',
    'app:credential:revoke', 'app_credential:update'
  );

-- BIZ_ADMIN（业务线管理员）→ 业务线全部 + 环境全部 + 应用凭证全部
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'BIZ_ADMIN'
  AND m.perm_code IN (
    'biz_line:list', 'biz_line:create', 'biz_line:update', 'biz_line:delete',
    'env:list',      'env:create',      'env:update',      'env:delete',
    'app_credential:list', 'app_credential:create', 'app_credential:rotate',
    'app:credential:revoke', 'app_credential:update'
  );

-- API_PROVIDER（接口提供方）→ 只读 + 凭证基本管理
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'API_PROVIDER'
  AND m.perm_code IN (
    'biz_line:list',
    'env:list',
    'app_credential:list', 'app_credential:rotate'
  );

-- API_CONSUMER（接口调用方）→ 只能看自己应用下的凭证
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'API_CONSUMER'
  AND m.perm_code IN (
    'biz_line:list',
    'env:list',
    'app_credential:list', 'app_credential:rotate'
  );

-- AUDITOR（审计员）→ 只读
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'AUDITOR'
  AND m.perm_code IN (
    'biz_line:list',
    'env:list',
    'app_credential:list'
  );

-- EXTERNAL_PM（外部对接人）→ 与 API_CONSUMER 一致（仅查看）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'EXTERNAL_PM'
  AND m.perm_code IN (
    'biz_line:list',
    'env:list',
    'app_credential:list'
  );

-- 存量 SUPER_ADMIN / OPERATOR / SECURITY_AUDITOR 通过 T01 的双轨映射自动获取新权限
-- （T01 把 SUPER_ADMIN ← ADMIN，OPERATOR ← BIZ_ADMIN，SECURITY_AUDITOR ← AUDITOR）

-- ---------------------------------------------------------------------------
-- 5) 数据权限（sys_role_datascope）—— 给 BIZ_ADMIN 补 ENV/test,prod
--     migrate-v2.sql 已包含，这里保持幂等
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_datascope (role_id, scope_type, scope_value)
SELECT r.id, 'ENV', e.env_code
FROM sys_role r JOIN env e
WHERE r.role_code = 'BIZ_ADMIN' AND e.env_code IN ('test', 'prod');

-- ---------------------------------------------------------------------------
-- 6) 校验：列出新增 13 个权限点
-- ---------------------------------------------------------------------------
-- SELECT id, pid, name, perm_code, risk_flag, sort_order, status
-- FROM sys_menu
-- WHERE perm_code IN (
--   'biz_line:list', 'biz_line:create', 'biz_line:update', 'biz_line:delete',
--   'env:list',      'env:create',      'env:update',      'env:delete',
--   'app_credential:list', 'app_credential:create', 'app_credential:rotate',
--   'app:credential:revoke', 'app_credential:update'
-- )
-- ORDER BY id;
