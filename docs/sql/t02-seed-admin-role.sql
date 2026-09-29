-- ============================================================
-- T02 兜底 SQL：admin 用户补 platform_admin（实际 role_code='ADMIN'）角色关联
-- ============================================================
--
-- 目的：
--   当 T01 部署后 / 任何一次 ROLLBACK 后 / 全新 MySQL 部署时，
--   sys_user_role 表里 admin (user_id=1) 与 ADMIN 角色（role_code='ADMIN'）的关联
--   可能不存在，此时 admin 登录后 permSet 为空，全部 @RequirePerm 接口会拒（403）。
--
-- 本 SQL 是幂等的，仅当关联不存在时 INSERT IGNORE。
--
-- 仅在 T01 后台缺角色时人工或初始化脚本里执行：
--   1) 用本 SQL 确认 admin → ADMIN(role_code) 关联存在
--   2) 重新登录 admin → 自动加载 29 个权限点
--   3) 所有 @RequirePerm 接口对 admin 通过
--
-- 不修改任何 18 张基线数据，仅在 sys_user_role 缺失关联时落一条。

-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT 1, r.id
FROM sys_role r
WHERE r.role_code = 'ADMIN';
