-- ============================================================
-- GateKeeper 存量库修复：双重编码乱码（mojibake）就地纠正
-- ============================================================
--
-- 适用对象：v1.0.2 及更早版本，用 docker-compose 做过「全新初始化」的库。
--
-- 症状：在「角色管理 → 配置权限」弹窗里，部分权限点的名称显示成
--       "æ–°å¢žåˆ†ç»„çŽ¯å¢ƒé…ç½®" 这类拉丁扩展字符（而权限码本身正常）。
--
-- 根因（2026-09-29 实机定位并复现）：
--   MySQL 官方镜像的 /usr/local/bin/docker-entrypoint.sh 在执行
--   /docker-entrypoint-initdb.d/*.sql 时调用 `mysql` 客户端，**不传**
--   --default-character-set；容器内 LANG / LC_ALL 为空 ⇒ 客户端回退到
--   latin1 ⇒ 脚本里的 UTF-8 中文字节被当作 cp1252 再编码一次入库。
--   表现为「只有部分行坏」：带 SET NAMES utf8mb4 的脚本（init.sql /
--   schema-v2 / migrate-v2 / seed-perm-alignment / t17）播的行是好的，
--   其余脚本（t03a / t03b / t09-hygiene / t13 / t15-1 / t15-4）播的行是坏的。
--   实测受害面：sys_menu.name 17 行 + sys_dict.remark 1 行。
--
-- 修复原理：把字符串按 latin1 取回原始字节，再按 utf8mb4 重新解释——
--     UPDATE t SET c = CONVERT(BINARY(CONVERT(c USING latin1)) USING utf8mb4)
--   之所以正确：MySQL 的 latin1 就是 cp1252，与当初"错误解读"用的是同一张
--   映射表，因此这一步是当初那一步的严格逆运算。
--
-- ⚠️ 安全性：本脚本只对「确实是双重编码」的行动手，WHERE 用了两道护栏
--   （① 含 UTF-8 的 U+00C0~U+00FF 首字节 C3；② 转 latin1 无信息丢失，
--   即不含 '?'）。正常的中文行两条都不可能同时满足，绝不会被误改。
--
-- 幂等：修复后乱码特征消失，重复执行命中 0 行、不报错。
-- 备份：执行前请先 mysqldump，或至少单独备份 sys_menu / sys_dict。
-- 执行：mysql -h <host> -P <port> -u <user> -p<pass> <db> < fix-mojibake.sql
-- ============================================================


-- ⚠ 必须先声明字符集：手工在 Windows 控制台/无 LANG 的容器里执行本脚本时，
--   mysql 客户端同样可能回退 latin1，反而把修复结果再次弄坏。
SET NAMES utf8mb4;


-- ------------------------------------------------------------------
-- 0) 修复前快照：列出即将被纠正的行（人工过目；已经是干净库时这里为空）
-- ------------------------------------------------------------------
SELECT '0) BEFORE / sys_menu' AS stage, id, perm_code, name AS value_to_be_fixed
  FROM sys_menu
 WHERE HEX(name) REGEXP '^(..)*C3'
   AND LOCATE('?', CONVERT(name USING latin1)) = 0
 ORDER BY id;

SELECT '0) BEFORE / sys_dict' AS stage, id, dict_code, remark AS value_to_be_fixed
  FROM sys_dict
 WHERE remark IS NOT NULL
   AND HEX(remark) REGEXP '^(..)*C3'
   AND LOCATE('?', CONVERT(remark USING latin1)) = 0
 ORDER BY id;


-- ------------------------------------------------------------------
-- 1) 修复 sys_menu.name —— 菜单/权限点名称（角色管理「配置权限」弹窗的数据源）
-- ------------------------------------------------------------------
UPDATE sys_menu
   SET name = CONVERT(BINARY(CONVERT(name USING latin1)) USING utf8mb4)
 WHERE HEX(name) REGEXP '^(..)*C3'
   AND LOCATE('?', CONVERT(name USING latin1)) = 0;

SELECT ROW_COUNT() AS `1) sys_menu 已修复行数`;


-- ------------------------------------------------------------------
-- 2) 修复 sys_dict.remark —— 数据字典说明（由 t09-hygiene.sql 覆盖写入的那一行）
-- ------------------------------------------------------------------
UPDATE sys_dict
   SET remark = CONVERT(BINARY(CONVERT(remark USING latin1)) USING utf8mb4)
 WHERE remark IS NOT NULL
   AND HEX(remark) REGEXP '^(..)*C3'
   AND LOCATE('?', CONVERT(remark USING latin1)) = 0;

SELECT ROW_COUNT() AS `2) sys_dict 已修复行数`;


-- ------------------------------------------------------------------
-- 3) 修复后复核：下面两条都必须返回 0，否则不要继续，先查原因
-- ------------------------------------------------------------------
SELECT '3) AFTER / sys_menu 残留乱码行数（必须为 0）' AS chk, COUNT(*) AS n
  FROM sys_menu
 WHERE HEX(name) REGEXP '^(..)*C3';

SELECT '3) AFTER / sys_dict 残留乱码行数（必须为 0）' AS chk, COUNT(*) AS n
  FROM sys_dict
 WHERE remark IS NOT NULL
   AND HEX(remark) REGEXP '^(..)*C3';

-- 3.1) 人工抽查：这几条是本次修复的典型样本，应显示为正常中文
SELECT id, perm_code, name
  FROM sys_menu
 WHERE perm_code IN ('biz_line:list', 'app_credential:list', 'api_param:import',
                     'api_version:gray', 'api_env_config:test', 'interface:test',
                     'api_group_env_config:create', 'api_group_env_config:update',
                     'api_group_env_config:delete', 'api_group_env_config:test',
                     'api_group_encryption:create', 'api_group_encryption:update',
                     'api_group_encryption:delete', 'app:ipwhitelist:update')
 ORDER BY id;


-- ------------------------------------------------------------------
-- 4) 全库排查（可选）：想确认别处还有没有乱码，把下面这段的注释去掉执行。
--    原理：在 UTF-8 中，字符 U+00C0~U+00FF 的首字节是 C3，而常用汉字
--    (U+4E00~U+9FFF) 的首字节是 E4~E9。所以「HEX 在偶数位出现 C3」就是乱码特征。
--    注意必须用 REGEXP '^(..)*C3' 而不是 LIKE '%C3%'——后者会在半字节边界误报
--    （例如字节 4C 33 的十六进制串 "4C33" 里也含 "C3"）。
-- ------------------------------------------------------------------
-- SELECT 'alarm_rule.channel_ids' AS tbl_col, COUNT(*) AS n FROM alarm_rule
--  WHERE HEX(channel_ids) REGEXP '^(..)*C3'
-- UNION ALL SELECT 'sys_dict.remark', COUNT(*) FROM sys_dict WHERE HEX(remark) REGEXP '^(..)*C3'
-- UNION ALL SELECT 'sys_role.description', COUNT(*) FROM sys_role WHERE HEX(description) REGEXP '^(..)*C3'
-- UNION ALL SELECT 'sys_user.nickname', COUNT(*) FROM sys_user WHERE HEX(nickname) REGEXP '^(..)*C3';
