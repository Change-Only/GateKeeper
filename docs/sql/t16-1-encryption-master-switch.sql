-- =====================================================================
-- T16-1：平台级加解密「总开关」（单行配置，幂等 · 可重复执行）
-- ---------------------------------------------------------------------
-- 需求：「在 GateKeeper 层面做接口加密，并设置开关」。
-- 口径（用户 2026-09-15 拍板）：**仅总闸，不留平台密钥**。
--   · enabled = 1 ⇒ 网关按既有优先级正常解析：
--       接口级 > 分组级（沿分组树向上继承）> 应用级 > 明文
--   · enabled = 0 ⇒ **全局强制明文**：跳过整条解析链，
--       忽略接口级 / 分组级 / 应用级的所有加解密配置
--   · 平台级**不提供算法与密钥兜底** ⇒ 本表没有 algorithm/key/iv/mode/padding 列
--
-- 设计要点
--   1. 单行表：id 恒为 1（不做自增；实体用 IdType.INPUT）。
--   2. **刻意不播种任何行**：缺行 ⇒ 视为「启用」。
--      这样存量库、新建库、以及本脚本没跑过的环境，行为完全一致 —— 零迁移风险。
--   3. 只加表，不改任何既有表（演进式重构铁律：只加列/只加表，不做破坏性 DDL）。
--
-- 执行后
--   · 无需 DEL gk:perm:*（本表不涉及权限点；读写端点复用已播种的
--     sys:security:view / sys:security:update）
--   · 网关侧有 10s 内存缓存：写后本实例立即失效，多实例最长 10s 生效
--
-- 自查：SELECT * FROM sys_encryption_config;   -- 预期 0 行（未配置 = 启用）
-- =====================================================================

-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

USE `gatekeeper`;

CREATE TABLE IF NOT EXISTS `sys_encryption_config` (
  `id`         BIGINT       NOT NULL COMMENT '固定为 1：本表是单行配置',
  `enabled`    TINYINT      NOT NULL DEFAULT 1 COMMENT '平台加解密总开关：1=启用；0=全局强制明文',
  `remark`     VARCHAR(255) DEFAULT NULL COMMENT '备注：为什么开/关（允许清空）',
  `updated_by` BIGINT       DEFAULT NULL COMMENT '最后修改人（sys_user.id）',
  `created_at` DATETIME     DEFAULT NULL,
  `updated_at` DATETIME     DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台级加解密总开关（单行；缺行=启用）';
