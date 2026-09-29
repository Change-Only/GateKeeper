-- ============================================================
-- T08 种子：系统管理域（用户 / 角色）新增权限点 + app:create 换码配套补授
-- ============================================================
--
-- 背景（T08 = 修复「前端有闸门、后端零注解」的权限执行缺口；本文件只做权限表侧改动）：
--   T08 一次性给约 30 个变更端点补 @RequirePerm，是本项目史上爆炸半径最大的一次权限改动。
--   T05 血泪教训：服务端**无超管通配**（'*' 只在前端），任何加了 @RequirePerm 却未写入
--   sys_menu 的码，任何角色（含 SUPER_ADMIN）永远拿不到 → 端点对所有人恒定 403。
--   因此「新码 → 播种 sys_menu + sys_role_menu」必须与代码注解同批落地，并清 Redis 权限缓存。
--
-- 本文件包含两部分：
--   【1】6 个新增权限点（id 345–350）：系统-用户的写操作 + 系统-角色的写操作
--   【2】换码配套：给 EXTERNAL_PM 补授 app:create（保证零能力回归）
--
-- 授权集合 = **镜像同域既有码的真实授权集合（DB 实测，非字面假设）**：
--   sys:user:update (id=45, risk_flag=1) → {SUPER_ADMIN, ADMIN}
--       ⇒ 345/346/347 镜像之
--   sys:role:grant  (id=46, risk_flag=1) → {SUPER_ADMIN, ADMIN}
--       ⇒ 348/349/350 镜像之
--   risk_flag 亦沿用同域既有码取值 = 1（不改动任何既有码的 risk_flag）。
--
-- ID 段：sys_menu 实测 MAX(id)=344（2026-09-11 复核），本脚本用 **345–350**，不触碰任何既有 id。
--
-- 幂等：sys_menu 走 uk_menu_perm(perm_code)；sys_role_menu 走 uk_role_menu(role_id,menu_id)；
--       全部 INSERT IGNORE，可重复执行。
--
-- 执行后必须清 Redis 权限缓存：DEL gk:perm:*   （否则登录走 24h 缓存，看起来"没生效"）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 新增 6 个权限点（pid=4 系统管理模块；type=3 权限点；risk_flag=1）
-- ---------------------------------------------------------------------------
-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(345, 4, '新建用户',    3, 'sys:user:create',   NULL, 1, 15, 1),
(346, 4, '删除用户',    3, 'sys:user:delete',   NULL, 1, 16, 1),
(347, 4, '重置用户密码', 3, 'sys:user:resetpwd', NULL, 1, 17, 1),
(348, 4, '新建角色',    3, 'sys:role:create',   NULL, 1, 18, 1),
(349, 4, '编辑角色',    3, 'sys:role:update',   NULL, 1, 19, 1),
(350, 4, '删除角色',    3, 'sys:role:delete',   NULL, 1, 20, 1);

-- ---------------------------------------------------------------------------
-- 2) 角色授权：新增码（镜像同域既有码 = SUPER_ADMIN + ADMIN）
-- ---------------------------------------------------------------------------
-- 2a) 用户写操作 → SUPER_ADMIN / ADMIN
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
  AND m.perm_code IN ('sys:user:create', 'sys:user:delete', 'sys:user:resetpwd');

-- 2b) 角色写操作 → SUPER_ADMIN / ADMIN
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
  AND m.perm_code IN ('sys:role:create', 'sys:role:update', 'sys:role:delete');

-- ---------------------------------------------------------------------------
-- 3) 换码配套：AppController#create 由 app:credential:create 改为 app:create
--    DB 实测 app:create(id=22) 现授权 = {SUPER_ADMIN, OPERATOR, ADMIN, BIZ_ADMIN, API_CONSUMER}
--    —— 不含 EXTERNAL_PM；而 EXTERNAL_PM 原本持 app:credential:create 才能建应用。
--    故必须补授，否则换码会**静默收回 EXTERNAL_PM 的既有能力**（违反"只加不减"）。
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code = 'EXTERNAL_PM'
  AND m.perm_code = 'app:create';

-- ---------------------------------------------------------------------------
-- 4) 校验（执行后手工核对）
-- ---------------------------------------------------------------------------
-- -- 4.1 新码落库（应 6 行，risk_flag=1）：
-- SELECT id, pid, name, perm_code, risk_flag, sort_order, status
-- FROM sys_menu WHERE id BETWEEN 345 AND 350 ORDER BY id;
--
-- -- 4.2 授权展开：
-- SELECT m.perm_code, r.role_code
-- FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE m.perm_code IN ('sys:user:create','sys:user:delete','sys:user:resetpwd',
--                       'sys:role:create','sys:role:update','sys:role:delete')
-- ORDER BY m.perm_code, r.role_code;
--
-- -- 4.3 EXTERNAL_PM 现在应同时持有 app:create 与 app:credential:create：
-- SELECT m.perm_code FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE r.role_code = 'EXTERNAL_PM' AND m.perm_code LIKE 'app%' ORDER BY m.perm_code;
--
-- -- 4.4 行数核对（基线 107/396；执行后应 113 / +8+1=+9）：
-- SELECT (SELECT COUNT(*) FROM sys_menu) AS menu_rows,
--        (SELECT MAX(id)  FROM sys_menu) AS menu_max_id,
--        (SELECT COUNT(*) FROM sys_role_menu) AS role_menu_rows;
