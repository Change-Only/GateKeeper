-- ============================================================================
-- T19 配置接线（sys_config 真正生效）+ 错误种子数据修正
-- ============================================================================
-- 背景（2026-09-18 实测）：
--   sys_config 共 19 项，此前【全仓没有任何 Java 运行时读取点】——
--   唯一碰它的是 ConfigController 的增删改查。也就是说「参数配置」页改任何一项，
--   网关行为都不会变化。这对管理员是明确误导，对应急止损是直接的时间浪费。
--
-- 本脚本做三件事：
--   1) 修正「值本身与实现不符」的种子数据（sign.algorithm / secret.encrypt.algo /
--      gateway.default.read.timeout）；
--   2) 为 6 个已接线项标注读取点（T19 引入 SysConfigAccessor 后真正生效）；
--   3) 为 13 个仍未接线项如实标注「⚠️ 未接线（预留）」，不再让页面显得"能改"。
--
-- 幂等性：全部为「按 config_key 定向 UPDATE + 固定目标值」，重复执行结果一致。
-- 红线：不新增/不删除任何行；id 与 built_in 不动；权限点零变化。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 修正与实现不符的种子值
-- ---------------------------------------------------------------------------

-- 1.1 签名算法：历史种子写 HmacSHA256，但代码固定 SM3
--     （CryptoService.digest 只支持 SM3/MD5/SHA256，根本没有 HmacSHA256，
--      且客户端 SDK / 文档 / InterfaceTestServiceImpl 内置自测全部按 SM3 实现）
UPDATE sys_config
SET config_value = 'SM3',
    remark = '客户端契约：固定 SM3（国密摘要），与客户端 SDK/接入文档一致，不支持运行时切换 · 读取点 AppAuthHandler（T19 已接线）'
WHERE config_key = 'sign.algorithm';

-- 1.2 AppSecret 存储加密：历史种子写 AES-256-GCM，但实际是 AES/ECB/PKCS5Padding
--     （读取点 AppAuthHandler.verifySignature 的解密参数即为 "AES","ECB","PKCS5Padding"）
UPDATE sys_config
SET config_value = 'AES/ECB/PKCS5Padding',
    remark = '⚠️ 未接线（预留）：AppSecret 存储加密算法；当前实现为 AES/ECB/PKCS5Padding（历史值 AES-256-GCM 与实现不符）'
WHERE config_key = 'secret.encrypt.algo';

-- 1.3 网关默认超时：历史种子写 3000，但 ForwardHandler 的硬编码默认是 5000
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
-- 3. 未接线项：如实标注（避免"看起来能改、实际不生效"的误导）
-- ---------------------------------------------------------------------------

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：生成应用密钥时的随机串长度'
WHERE config_key = 'secret.length';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：密钥强制轮换周期(天)，超期应在概览页告警'
WHERE config_key = 'key.rotate.period';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：密钥最长有效期(天)，到期应自动失效'
WHERE config_key = 'key.max.valid.days';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：外部应用强制 IP 白名单'
WHERE config_key = 'external.ip.whitelist.required';

UPDATE sys_config
SET remark = '⚠️ 未接线：本项不生效——登录失败锁定阈值实际由 application.yml 的 gatekeeper.security.login-fail-threshold 控制'
WHERE config_key = 'login.fail.threshold';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：会话超时(分钟)'
WHERE config_key = 'session.timeout';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：日志敏感字段脱敏（手机号/身份证/银行卡）'
WHERE config_key = 'log.desensitize';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：审计日志保留天数（等保三级要求 ≥180 天）'
WHERE config_key = 'audit.log.retention.days';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：调用日志热数据保留天数；当前实际由 application.yml 的 gatekeeper.log.retention-days（默认 90）控制'
WHERE config_key = 'call.log.hot.days';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：是否开启接口授权审批流'
WHERE config_key = 'approval.enabled';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：单次导出最大行数'
WHERE config_key = 'export.max.rows';

UPDATE sys_config
SET remark = '⚠️ 未接线（预留）：数据模型版本，由 migrate-v2.sql 写入'
WHERE config_key = 'gk.schema.version';

-- ---------------------------------------------------------------------------
-- 4. 执行后自检（人工核对，应输出 6 条"已接线" + 13 条"未接线"）
-- ---------------------------------------------------------------------------
-- SELECT config_key,
--        config_value,
--        SUBSTRING_INDEX(remark, '（', 1) AS remark_head
-- FROM sys_config
-- WHERE config_group IN ('SECURITY','GATEWAY')
-- ORDER BY id;
--
-- 一键核对"是否还有既未接线又未标注的行"（期望结果为空集）：
-- SELECT config_key, remark FROM sys_config
-- WHERE remark NOT LIKE '%已接线%' AND remark NOT LIKE '%未接线%';
