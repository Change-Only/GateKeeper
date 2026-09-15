-- ============================================================================
-- T17：系统的「接口信息加密」—— 存储加密 + 按权限解密 + 可见性白名单
-- ----------------------------------------------------------------------------
-- 幂等：可重复执行（ADD COLUMN / ADD INDEX / MODIFY 前均先查 information_schema；
--       建表一律 CREATE TABLE IF NOT EXISTS）。
--
-- ---------------------------------------------------------------------------
-- 一、需求（用户 2026-09-14 明确纠正）
-- ---------------------------------------------------------------------------
-- 「在 gatekeeper 层面做接口加密，并设置开关 …… **不是**第三方调用我接口的时候的
--   加解密，是**系统本身**的加解密，别人在使用这个系统的时候**从控制台看不到我的
--   接口信息**，再加上白名单功能。」
--
-- 即：T15/T16 做的是「网关对外报文加解密」（应用 ↔ 网关之间），
--     本 T17 做的是「平台自身数据的**字段级存储加密** + **控制台可见性收敛**」。
--     两者毫不相干，勿混为一谈。
--
-- 四项口径（用户 2026-09-14 AskUserQuestion 拍板）：
--   ① 保护范围 = 接口**路径与参数**（不含后端地址 backend_url、不含名称类业务信息）
--   ② 加密深度 = **存储加密 + 按权限解密**（DB 存密文；控制台按白名单返回明文，
--      其余人返回掩码）
--   ③ 白名单口径 = **用户 / 角色可见性白名单**
--   ④ 上一轮的「网关报文加解密平台总开关」（T16-1）**移除**
--
-- ---------------------------------------------------------------------------
-- 二、🔴 为什么必须新增 interface_path_hash（盲索引 blind index）
-- ---------------------------------------------------------------------------
-- interface_path 不是普通字段，它是**网关的路由依据**：
--   PermissionHandler 用 GatewayPaths.interfacePathCandidates(...) 生成候选路径，
--   再 `eq("interface_path", candidate)` **直接查库**。
-- 一旦该列改为密文，`eq(密文)` 永远匹配不上 ⇒ **网关对任何接口都回 404**。
--
-- 解法是标准做法「盲索引」：密文列用于回显（随机 IV，不可检索），
-- 另存一列**确定性 HMAC-SHA256**（固定密钥、无盐）专供等值查询。
--   · 网关：`WHERE (interface_path_hash = H(candidate) OR interface_path = candidate)`
--     —— 密文行命中 hash 分支，历史明文行命中第二分支，**两种形态并存**，
--        因此存量明文行**不迁移也能继续工作**（零迁移风险）。
--   · 应用层写入时同时写 hash + 密文；开关关闭时写明文 + hash 置 NULL。
--
-- 为什么用 HMAC 而不是直接对明文做 SHA256：路径是低熵字符串（/order/create），
-- 裸 SHA256 可被彩虹表秒破；加固定密钥后，无 KEK 无法离线枚举。
-- 为什么不用「确定性 AES（ECB）」单列搞定：同一路径密文相同 ⇒ 泄漏相等性，
-- 且路径低熵同样可枚举；随机 IV 密文 + 独立 HMAC 盲索引是更稳的组合。
--
-- ---------------------------------------------------------------------------
-- 三、开关与白名单的语义（刻意的，勿随手"修正"）
-- ---------------------------------------------------------------------------
-- 开关 sys_interface_crypto_config.enabled：
--   · 1 = 启用（**缺行即启用**，与 T16-1 同口径，存量库零迁移）
--        ⇒ 新写入的 interface_path / api_param.field_name|example|description
--          以 AES-256-CBC 密文落库；控制台按可见性白名单决定明文 / 掩码。
--   · 0 = 关闭 ⇒ 新写入一律明文；读取时**仍会解密历史密文行**（可逆），
--       且控制台**不再掩码**（全量可见）。关开关 = 回到改造前观感。
--
-- 可见性判定（开关启用时）—— 命中任一即返回明文：
--   ① 当前用户是 SUPER_ADMIN（兜底，防"把所有人挡在门外"的死锁）
--   ② 当前用户是该接口的 owner_id
--   ③ 白名单命中 (USER, uid)
--   ④ 白名单命中 (ROLE, 当前用户的任一角色)
--   ⑤ **白名单表为空** ⇒ 仅 ①② 可见（其余人掩码）
-- 开关关闭 ⇒ 全部可见（不加密也不掩码）。
--
-- 🔴 fail-safe 方向（与 sys_ip_whitelist 相反，务必区分 —— docs/CONTRACTS §17 通则）：
--   · sys_ip_whitelist 是「**准入**闸门」，异常时 **fail-open 放行**（怕误拦把人挡在门外）；
--   · 本特性是「**保护**闸门」，白名单查询异常时 **按不可见处理（掩码）**（怕误放导致明文泄漏）。
--   方向照抄上一个就错了：一个怕"拦太多"，一个怕"漏太多"。
--
-- ---------------------------------------------------------------------------
-- 四、权限点
-- ---------------------------------------------------------------------------
-- **零新增权限点**：开关与白名单的读写端点一律复用已播种、已授权的
-- `sys:security:update`（在「系统设置 → 安全策略」语义范畴内；
-- 该页既有 CRUD 也用同一码）。避免"新增码忘了授权 ⇒ 连 SUPER_ADMIN 都拿不到"
-- 的历史坑（服务端无超管通配，见 docs/T08-权限执行缺口-契约记录.md）。
--
-- ---------------------------------------------------------------------------
-- 五、T16-1 的处置
-- ---------------------------------------------------------------------------
-- `sys_encryption_config` 表**保留不删**（演进式重构铁律：只加不删，删表不可逆）。
-- T16-1 的代码侧已整体移除，该表自此为死表，仅作痕迹留存。
--
-- ---------------------------------------------------------------------------
-- 六、执行顺序
-- ---------------------------------------------------------------------------
--   init.sql -> schema-v2.sql -> migrate-v2.sql -> t13-group-env-config.sql
--   -> t15-1-group-encryption.sql -> t15-4-whitelist.sql
--   -> t16-1-encryption-master-switch.sql（历史） -> **t17-interface-crypto.sql**
--
-- 本脚本不涉及权限点，**无需** DEL gk:perm:*。
--
-- ---------------------------------------------------------------------------
-- 七、自查（人工执行参考）
-- ---------------------------------------------------------------------------
--   SHOW CREATE TABLE api_interface\G   -- 应含 interface_path_hash / idx_iface_path_hash
--   SHOW CREATE TABLE api_param\G       -- field_name 512 / example 1024 / description 1024
--   SELECT COUNT(*) FROM sys_interface_visibility;       -- 0 = 白名单未配置
--   SELECT COUNT(*) FROM sys_interface_crypto_config;    -- 0 = 缺行 = 启用
--   SELECT id, interface_path, interface_path_hash FROM api_interface;
--       -- 开关启用时 interface_path 应以 enc:v1: 开头，hash 为 64 位十六进制
-- ============================================================================

SET NAMES utf8mb4;
USE `gatekeeper`;

-- ---------------------------------------------------------------------------
-- 0. 幂等辅助存储过程（执行完自动清理）
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS gk_add_column;
DELIMITER $$
CREATE PROCEDURE gk_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
  ) THEN
    SET @gk_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE gk_stmt FROM @gk_sql;
    EXECUTE gk_stmt;
    DEALLOCATE PREPARE gk_stmt;
  END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS gk_add_index;
DELIMITER $$
CREATE PROCEDURE gk_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
  ) THEN
    SET @gk_sql = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE gk_stmt FROM @gk_sql;
    EXECUTE gk_stmt;
    DEALLOCATE PREPARE gk_stmt;
  END IF;
END$$
DELIMITER ;

-- 幂等「扩容」存储过程：仅当现存字符长度**小于** p_min_len 时才 MODIFY。
-- schema-v2.sql 的 gk_add_column 只会 ADD，不会 MODIFY，故本脚本自带一个。
-- 用 CHARACTER_MAXIMUM_LENGTH 判定，重复执行不会反复重建表。
DROP PROCEDURE IF EXISTS gk_widen_column;
DELIMITER $$
CREATE PROCEDURE gk_widen_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                 IN p_min_len BIGINT, IN p_ddl TEXT)
BEGIN
  DECLARE v_len BIGINT DEFAULT NULL;
  SELECT CHARACTER_MAXIMUM_LENGTH INTO v_len
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column;
  IF v_len IS NOT NULL AND v_len < p_min_len THEN
    SET @gk_sql = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN ', p_ddl);
    PREPARE gk_stmt FROM @gk_sql;
    EXECUTE gk_stmt;
    DEALLOCATE PREPARE gk_stmt;
  END IF;
END$$
DELIMITER ;

-- ---------------------------------------------------------------------------
-- 1. api_interface：盲索引列 + 索引 + 密文扩容
-- ---------------------------------------------------------------------------
-- 1.1 盲索引列（确定性 HMAC-SHA256，64 位十六进制）
--     可空：开关关闭时或历史明文行为 NULL（NULL 不参与匹配，正好对应"无 hash 可查"）
CALL gk_add_column('api_interface', 'interface_path_hash',
    'interface_path_hash CHAR(64) DEFAULT NULL COMMENT ''接口路径盲索引：HMAC-SHA256(interface_path) 小写十六进制；密文行用于网关等值路由，明文行/未启用为 NULL（T17）''');

-- 1.2 盲索引索引：与 request_method 组成组合索引，贴合网关的查询形状
--     （原来是 idx_iface_path(interface_path, request_method)，改用 hash 前缀）
CALL gk_add_index('api_interface', 'idx_iface_path_hash',
    'KEY idx_iface_path_hash (interface_path_hash, request_method)');

-- 1.3 interface_path 扩容 256 -> 512
--     密文长度 = 'enc:v1:'(7) + base64(16B IV + 16B 块对齐填充后的密文)。
--     256 字符明文 ⇒ 密文 272B ⇒ base64 364 字符 ⇒ 加前缀 371 > 256。
--     512 明文 ⇒ 544B 密文 ⇒ base64 728 字符 —— 仍可能溢出，故实际约束是
--     "接口路径入库长度上限约 340 字符"（见 CONTRACTS §18）。路径本身不该那么长，
--     接口层另有 maxlength=128 的表单约束，此处留足余量。
--     ⚠ 原 idx_iface_path(interface_path, request_method) **不删**：
--       演进铁律只加不删；它对新写入的密文行不再有效，但无害，留给后续清理专项。
CALL gk_widen_column('api_interface', 'interface_path', 512,
    'interface_path VARCHAR(512) NOT NULL COMMENT ''网关对外暴露路径（开关启用时为 AES 密文 enc:v1:，历史/关闭时明文）''');

-- ---------------------------------------------------------------------------
-- 2. api_param：密文扩容（field_name / example / description 三列转为密文载体）
-- ---------------------------------------------------------------------------
-- 口径：加密「契约内容」三列，保留「结构/行为」列明文
--   · 加密：field_name(128->512)、example(512->1024)、description(512->1024)
--   · 明文：param_type / parent_id / field_type / required / error_code /
--           http_status / sensitive / encrypt_rule / sort_order
-- 理由：结构列不泄漏契约本身，且被前端渲染逻辑与校验逻辑直接消费；
--       全加密会让参数页对非白名单用户彻底空白，反而伤了可用性而无收益。
CALL gk_widen_column('api_param', 'field_name', 512,
    'field_name VARCHAR(512) NOT NULL COMMENT ''字段名 / 错误码KEY（开关启用时为 AES 密文 enc:v1:）''');
CALL gk_widen_column('api_param', 'example', 1024,
    'example VARCHAR(1024) DEFAULT NULL COMMENT ''示例值（开关启用时为 AES 密文 enc:v1:）''');
CALL gk_widen_column('api_param', 'description', 1024,
    'description VARCHAR(1024) DEFAULT NULL COMMENT ''字段说明（开关启用时为 AES 密文 enc:v1:）''');

-- ---------------------------------------------------------------------------
-- 3. 可见性白名单表（用户 / 角色）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_interface_visibility` (
  `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `subject_type` varchar(16)  NOT NULL                COMMENT '授权主体类型：USER=具体用户，ROLE=角色',
  `subject_id`   bigint       NOT NULL                COMMENT '主体ID：subject_type=USER 时为 sys_user.id，=ROLE 时为 sys_role.id',
  `remark`       varchar(256)          DEFAULT NULL   COMMENT '备注（说明为何允许其查看接口明文）',
  `status`       tinyint      NOT NULL DEFAULT 1      COMMENT '1=启用（参与判定），0=停用（便于临时摘除）',
  `created_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iface_vis_subject` (`subject_type`, `subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='接口信息可见性白名单（控制台看接口路径/参数明文的授权；空表=仅超管与owner可见）';

-- 刻意不播种任何行。空表语义 = 仅 SUPER_ADMIN 与接口 owner 可见明文，
-- 其余人一律掩码 —— 这是"保护类闸门"的 fail-safe 方向（见头注第三节）。

-- ---------------------------------------------------------------------------
-- 4. 接口信息加密开关表（单行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_interface_crypto_config` (
  `id`         BIGINT       NOT NULL COMMENT '固定为 1：本表是单行配置',
  `enabled`    TINYINT      NOT NULL DEFAULT 1 COMMENT '接口信息加密开关：1=启用（密文落库+按白名单解密）；0=关闭（明文落库+全量可见）',
  `remark`     VARCHAR(255) DEFAULT NULL COMMENT '备注：为什么开/关（允许清空）',
  `updated_by` BIGINT       DEFAULT NULL COMMENT '最后修改人（sys_user.id）',
  `created_at` DATETIME     DEFAULT NULL,
  `updated_at` DATETIME     DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='接口信息加密开关（单行；缺行=启用，存量库零迁移）';

-- 刻意不播种：缺行 = 启用。存量库/新建库/未跑脚本的环境行为完全一致。
--
-- ⚠ 与 T16-1 的区别（别搞混）：
--   sys_encryption_config（T16-1，已撤销，表保留）管的是**网关对外报文加解密**的总闸；
--   本表管的是**平台自身字段级存储加密**。两者无任何代码依赖关系。

-- ---------------------------------------------------------------------------
-- 5. 清理临时存储过程
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS gk_add_column;
DROP PROCEDURE IF EXISTS gk_add_index;
DROP PROCEDURE IF EXISTS gk_widen_column;
