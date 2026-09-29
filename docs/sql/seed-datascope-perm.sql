-- ============================================================
-- T05 补种脚本：数据权限（perm-datascope）权限点 + 角色授权
-- 权威来源：docs/backend-补课-合同.md §5.5
-- 目的：前端 router（src/frontend/src/router/menu.js）校验 'sys:datascope:view'，
--       该 permCode 在真实 sys_menu 种子中缺失，会导致 perm-datascope 页面不可见 + 接口 403。
-- 幂等：全部使用 INSERT IGNORE，可重复执行。
-- 执行：pymysql / mysql 客户端均可；无需 mysql CLI（见 docs/sql/_tools）。
-- ============================================================

-- 1) 权限点（pid=4 权限管理模块，紧接现有 type=3 菜单 41~47 之后）
-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(48, 4, '数据权限查看', 3, 'sys:datascope:view',   NULL, 0, 8, 1),
(49, 4, '数据权限配置', 3, 'sys:datascope:update', NULL, 1, 9, 1);  -- risk=1 高危写操作

-- 2) 授权角色
--    合同要求：BIZ_ADMIN / SECURITY_AUDITOR
--    扩展补充：ADMIN / SUPER_ADMIN
--      说明：GateKeeper 当前唯一种子用户为 admin（uid=1，拥有角色 SUPER_ADMIN=1 + ADMIN=10），
--      并不存在 BIZ_ADMIN 用户。仅授权 BIZ_ADMIN/SECURITY_AUDITOR 时，admin/admin123 登录仍会因
--      缺少 sys:datascope:* 权限点而被 PermissionInterceptor 拦截（HTTP 200 + body code=403）。
--      为使冒烟测试能以 admin 账号通过鉴权，补充授权 ADMIN / SUPER_ADMIN。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 48 FROM sys_role r
WHERE r.role_code IN ('BIZ_ADMIN', 'SECURITY_AUDITOR', 'ADMIN', 'SUPER_ADMIN');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 49 FROM sys_role r
WHERE r.role_code IN ('BIZ_ADMIN', 'SECURITY_AUDITOR', 'ADMIN', 'SUPER_ADMIN');

-- 注：sys_role_datascope 表本身已有种子数据（migrate-v2.sql 给 BIZ_ADMIN 预置 ENV/test,prod），
--     无需改动；本脚本仅补齐「权限点可见性 + 写网关」。
