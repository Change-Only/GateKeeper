-- ============================================================
-- T09-N1 通知渠道数据卫生（可选，非阻塞）— 方案 §3.4
-- 幂等：可重复执行；纯数据级操作，零 DDL、零种子。
-- 背景（R3）：存量渠道行 channel_config 全 NULL（历史桩发时期未存配置），
--   但 last_test_result 被旧桩发逻辑回写了「发送成功」—— 配置都没有，
--   「成功」系失真数据，会误导排障（以为渠道可用）。
--   T09-N1 起桩发已收紧（R4/R5），前端「测试」按钮即可重写真实结果，
--   本 SQL 仅清理历史失真行，不强依赖。
-- 执行收口：docs/sql/ 为文档库 SQL 目录，由 lead 统一执行（本批次施工方不执行）。
-- ============================================================

-- 1) 置空存量失真的 last_test_result（channel_config 为 NULL 的行不可能真发送成功）
UPDATE notify_channel
SET last_test_result = NULL
WHERE channel_config IS NULL
  AND last_test_result = '发送成功';

-- 验证（执行后应看到）：
--   SELECT COUNT(*) FROM notify_channel WHERE channel_config IS NULL AND last_test_result = '发送成功';  -- 0
--   SELECT id, channel_name, channel_config IS NULL AS cfg_null, last_test_result FROM notify_channel;
