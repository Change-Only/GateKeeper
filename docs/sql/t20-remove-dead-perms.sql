-- =====================================================================
-- T20：移除「已无调用方」的权限点（存量库清理脚本，幂等 · 可重复执行）
-- ---------------------------------------------------------------------
-- 背景
--   需求：「将没用到功能删除」。本轮按四层甄别：A 纯死代码 / B 无调用端点 /
--   C 死种子 / D 未接线配置。本脚本对应 **C 层**：`sys_menu` 中已无任何引用的
--   权限点，及其 `sys_role_menu` 授权行。
--
-- 判定口径（三向审计，脚本可复现）
--   源码侧：`src/backend/src/main/java/**/*.java` 的 `@RequirePerm(value="...")`
--   前端侧：`src/frontend/src/**` 的 `perm:` / `perm="..."` / `hasPerm("...")`
--   播种侧：`sys_menu.perm_code`
--   结论：以下 8 个码「源码与前端均不引用」，且对应端点已随 f06c7b9 删除
--   （`app:credential:create` / `app_credential:list` 属历史遗留的重复码）：
--     id=25   app:credential:create    —— 已被 id=92 `app_credential:create` 取代
--     id=91   app_credential:list      —— `/app-credential/list` 无 @RequirePerm
--     id=101  api_param:import         —— `POST /api-param/import` 已删
--     id=102  api_version:gray         —— `PUT /api-version/{id}/gray` 已删
--     id=103  api_env_config:test      —— `POST /api-env-config/{id}/test` 已删
--     id=315  api_env_config:create    —— `POST /api-env-config/create` 已删
--     id=316  api_env_config:delete    —— `DELETE /api-env-config/{id}` 已删
--     id=317  api_change_log:append    —— `POST /api-change-log/append` 已删
--   ⚠️ `*:list` / `*:view` 一类的码 **不在**本脚本范围内：它们由前端静态菜单
--      （`src/frontend/src/router/menu.js` 的 `perm:`）引用，是活码。
--
-- 明确不做（演进式重构铁律「只加表不删表 / 只加列不删列」）
--   · `app_quota` / `biz_line` 两张表 **保留不 DROP**（README「已下线保留」；
--     参见 docs/sql/t15-remove-bizline.sql 的同类决策）
--   · 仅移除会出现在 UI 与鉴权链路上的「活数据」：权限点 + 角色授权
--
-- 执行顺序
--   必须在**所有**种子脚本之后执行（init.sql / t02 / t03a / t03b / t07a / t08 /
--   migrate-v2 / t13 / t15-* / t16-1 / t17 / t19），因为它做的是「减法」。
--   ⚠️ 已知会重新播种这些权限点的历史脚本：`docs/sql/t03a-seed-permissions.sql`
--   （`app_credential:list`）、`docs/sql/t03b-seed-permissions.sql`
--   （`api_param:import` / `api_version:gray` / `api_env_config:*` /
--     `api_change_log:append` / `app:credential:create`）。
--   T20 已同步从 `init.sql` 删除这些种子行（新装库不再产生），本脚本用于**修复已建库**。
--
-- 执行后必做
--   DEL gk:perm:*      -- 权限缓存优先 TTL 24h，不清缓存会「看着没生效」
-- =====================================================================

-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

USE `gatekeeper`;

-- ---------------------------------------------------------------------
-- 1) 先删子表：角色→菜单授权（避免留下悬空 menu_id）
-- ---------------------------------------------------------------------
-- 1.1 按已知 id 精确删除（init.sql 中共 39 行：25×6 / 91×9 / 101×3 /
--     102×3 / 103×3 / 315×5 / 316×5 / 317×5）
DELETE FROM `sys_role_menu`
 WHERE `menu_id` IN (25, 91, 101, 102, 103, 315, 316, 317);

-- 1.2 兜底：按权限码语义删除（覆盖其它脚本另行插入的新 id）
DELETE `srm` FROM `sys_role_menu` `srm`
  JOIN `sys_menu` `sm` ON `sm`.`id` = `srm`.`menu_id`
 WHERE `sm`.`perm_code` IN (
         'app:credential:create',
         'app_credential:list',
         'api_param:import',
         'api_version:gray',
         'api_env_config:test',
         'api_env_config:create',
         'api_env_config:delete',
         'api_change_log:append');

-- ---------------------------------------------------------------------
-- 2) 再删权限点本体（`sys_menu`）
--    注：这 8 个 id 均未被任何其它菜单作为 `pid` 引用，删除不会产生悬空父子关系
-- ---------------------------------------------------------------------
DELETE FROM `sys_menu`
 WHERE `perm_code` IN (
         'app:credential:create',
         'app_credential:list',
         'api_param:import',
         'api_version:gray',
         'api_env_config:test',
         'api_env_config:create',
         'api_env_config:delete',
         'api_change_log:append');

-- ---------------------------------------------------------------------
-- 3) 自查（前两项应返回 0；后两项是「保留项」的计数，非 0 属正常）
-- ---------------------------------------------------------------------
SELECT '残留死权限点' AS chk, COUNT(*) AS cnt
  FROM `sys_menu`
 WHERE `perm_code` IN (
         'app:credential:create',
         'app_credential:list',
         'api_param:import',
         'api_version:gray',
         'api_env_config:test',
         'api_env_config:create',
         'api_env_config:delete',
         'api_change_log:append')
UNION ALL
SELECT '悬空角色授权', COUNT(*)
  FROM `sys_role_menu` `srm`
  LEFT JOIN `sys_menu` `sm` ON `sm`.`id` = `srm`.`menu_id`
 WHERE `sm`.`id` IS NULL
UNION ALL
SELECT 'app_quota 表（铁律保留）', COUNT(*)
  FROM information_schema.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'app_quota'
UNION ALL
SELECT 'biz_line 表（铁律保留）', COUNT(*)
  FROM information_schema.`TABLES`
 WHERE `TABLE_SCHEMA` = DATABASE() AND `TABLE_NAME` = 'biz_line';
