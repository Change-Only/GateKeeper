-- ============================================================================
-- T19 配置接线（sys_config 真正生效）+ 错误种子数据修正
-- ============================================================================
-- 背景（2026-09-18 实测）：
--   sys_config 共 19 项，此前【全仓没有任何 Java 运行时读取点】——
--   唯一碰它的是 ConfigController 的增删改查。也就是说「参数配置」页改任何一项，
--   网关行为都不会变化。这对管理员是明确误导，对应急止损是直接的时间浪费。
--
-- 本脚本做两件事：
--   1) 修正「值本身与实现不符」的种子数据（sign.algorithm /
--      gateway.default.read.timeout）；
--   2) 为 6 个已接线项标注读取点（T19 引入 SysConfigAccessor 后真正生效）。
--
-- 【2026-09-27 收敛】原第 3 节是给 13 个「⚠️ 未接线（预留）」项写备注。复核结论：
--   这 13 项自 T19 起始终【没有任何 Java 读取点】，仅把行留在库里、靠 remark 标注，
--   等于在「参数配置」页长期摆着 13 个不发生作用的开关——仍然是误导。故连行一并移除：
--     secret.length / secret.encrypt.algo / key.rotate.period / key.max.valid.days /
--     external.ip.whitelist.required / login.fail.threshold / session.timeout /
--     log.desensitize / audit.log.retention.days / call.log.hot.days /
--     approval.enabled / export.max.rows / gk.schema.version
--   同步点（三处必须一致）：
--     - src/backend/src/main/resources/sql/init.sql（新装库种子，现 6 行）
--     - docs/sql/migrate-v2.sql §1.8（迁移种子，现 6 行）
--     - 本文件（不再为这 13 项写备注）
--   恢复方式：git 历史（commit 16d7c1f 之前）。存量库见本文件第 3 节说明。
--
-- 幂等性：全部为「按 config_key 定向 UPDATE + 固定目标值」，重复执行结果一致。
-- 红线：本脚本不新增/不删除任何行（那 13 项的删除在 init.sql / migrate-v2.sql 完成）；
--       id 与 built_in 不动；权限点零变化。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 修正与实现不符的种子值
-- ---------------------------------------------------------------------------

-- 1.1 签名算法：历史种子写 HmacSHA256，但代码固定 SM3
--     （CryptoService.digest 只支持 SM3/MD5/SHA256，根本没有 HmacSHA256，
--      且客户端 SDK / 文档 / InterfaceTestServiceImpl 内置自测全部按 SM3 实现）
-- ⚠ 必须先声明字符集：MySQL 官方镜像的 docker-entrypoint.sh 调 mysql 客户端时**不指定**
--   字符集，而容器内 LANG/LC_ALL 为空 ⇒ 客户端回退到 latin1，本文件的 UTF-8 中文会被
--   双重编码成乱码（实测 sys_menu.name 出现 "æ–°å¢ž..."）。补此行后按 utf8mb4 解析。
--   2026-09-29 实机验证。手工执行本脚本时同样受益。
SET NAMES utf8mb4;

UPDATE sys_config
SET config_value = 'SM3',
    remark = '客户端契约：固定 SM3（国密摘要），与客户端 SDK/接入文档一致，不支持运行时切换 · 读取点 AppAuthHandler（T19 已接线）'
WHERE config_key = 'sign.algorithm';

-- 1.2 网关默认超时：历史种子写 3000，但 ForwardHandler 的硬编码默认是 5000
--     接线时按"实现现状"对齐（避免接线本身静默收紧线上超时），再改为可配置
UPDATE sys_config
SET config_value = '5000',
    remark = '网关默认超时(ms)：接口与环境配置都未指定时生效 · 读取点 ForwardHandler（T19 已接线；历史值 3000 与实现默认 5000 不符，已按实现对齐）'
WHERE config_key = 'gateway.default.read.timeout';

-- ---------------------------------------------------------------------------
-- 2. 已接线项：标注读取点（T19）
--    这 6 项自本次改造起真正生效，写后由 ConfigServiceImpl 主动失效缓存 ⇒ 改完立刻生效
-- ---------------------------------------------------------------------------

UPDATE sys_config
SET remark = '时间戳容差(毫秒)，默认 ±5 分钟 · 读取点 AppAuthHandler（T19 已接线）'
WHERE config_key = 'sign.timestamp.tolerance';

UPDATE sys_config
SET remark = 'Nonce 有效期(秒)，应 ≥ 2 倍时间戳容差 · 读取点 AppAuthHandler（T19 已接线）'
WHERE config_key = 'sign.nonce.ttl';

UPDATE sys_config
SET remark = '是否开启签名校验；关闭后跳过防伪造/防重放（AppKey/应用状态/到期仍强制校验）· 读取点 AppAuthHandler（T19 已接线）'
WHERE config_key = 'gateway.auth.enabled';

UPDATE sys_config
SET remark = '是否开启限流；关闭后 QPS/并发/日配额全部失效 · 读取点 RateLimitHandler（T19 已接线）'
WHERE config_key = 'gateway.ratelimit.enabled';

-- （sign.algorithm 与 gateway.default.read.timeout 的备注已在第 1 节一并写好）

-- ---------------------------------------------------------------------------
-- 3. 关于被移除的 13 个「未接线」项（2026-09-27）
-- ---------------------------------------------------------------------------
-- 本节原为 13 个 `UPDATE ... SET remark = '⚠️ 未接线（预留）：...'`。
-- 这 13 项在【任何代码路径上都查不到读取点】，仅靠 remark 前缀提示"不生效"，
-- 关闭状态与开启状态对系统行为完全等价。故按「不留误导性开关」的原则连行移除，
-- 新装库（init.sql）与迁移种子（migrate-v2.sql §1.8）均已只剩 6 行。
--
-- 存量库：本次一致按「存量库不动」处理（与两张死表 app_quota / biz_line 同一口径），
--   因此本脚本不自动删行。若确认要清理，请先备份再手工执行下面这段（幂等，可重复跑）：
--
--   -- 备份：CREATE TABLE sys_config_bak_20260927 AS SELECT * FROM sys_config;
--   DELETE FROM sys_config WHERE config_key IN (
--     'secret.length','secret.encrypt.algo','key.rotate.period','key.max.valid.days',
--     'external.ip.whitelist.required','login.fail.threshold','session.timeout',
--     'log.desensitize','audit.log.retention.days','call.log.hot.days',
--     'approval.enabled','export.max.rows','gk.schema.version'
--   );
--   -- 自查（期望 0 行）：SELECT config_key FROM sys_config WHERE
--   --   config_key IN ('secret.length','secret.encrypt.algo','key.rotate.period',
--   --     'key.max.valid.days','external.ip.whitelist.required','login.fail.threshold',
--   --     'session.timeout','log.desensitize','audit.log.retention.days',
--   --     'call.log.hot.days','approval.enabled','export.max.rows','gk.schema.version');
--   -- 删完记得让缓存失效：DEL gk:config:*  （或重启应用）
--
--   注意：sys_config.id 为自增主键，删行不影响其余行 id；但若线上有人按 id 硬编码
--   引用（本仓已确认 0 处），请先自查。

-- ---------------------------------------------------------------------------
-- 4. 执行后自检（人工核对，应输出 6 行，且每行都带「已接线」）
-- ---------------------------------------------------------------------------
-- SELECT id, config_key, config_value,
--        SUBSTRING_INDEX(remark, '（', 1) AS remark_head
-- FROM sys_config
-- ORDER BY id;
--
-- 一键核对"是否还有没标读取点的行"（期望结果为空集）：
-- SELECT config_key, remark FROM sys_config
-- WHERE remark NOT LIKE '%已接线%';
