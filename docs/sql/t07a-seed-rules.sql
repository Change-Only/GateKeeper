-- ============================================================
-- T07-A 种子：告警规则 / 阻断规则 / 通知渠道 三域「缺口」权限点
-- ============================================================
--
-- 背景（T07-A 权威前提，已实测复核）：
--   三域（alarm_rule / block_rule / notify_channel）的 Controller / Service / Mapper
--   **早已存在**，位于 `com.gatekeeper.alarm` 与 `com.gatekeeper.block` 子包，
--   并非「服务层为零」。T07-A 真实缺口只有两处：
--     1) 缺失端点 `DELETE /notify-channel/{id}`（前端 deleteNotifyChannel(id) 已在调用）；
--     2) 三个「有外发 / 破坏性副作用」的端点当时**无权限点**：
--          - POST /alarm-rule/{id}/test      发送测试告警（外发）
--          - POST /notify-channel/{id}/test  发送测试通知（外发）
--          - DELETE /notify-channel/{id}     删除通知渠道（破坏性）
--
--   本脚本为上述 3 个新码播种 sys_menu + 角色授权，使 @RequirePerm 不再「挂着却无码」。
--
-- 🔴 T05 血泪铁律（本脚本存在的唯一理由）：
--   服务端**没有超管通配、'*' 只存在于前端**。任何加了 @RequirePerm 却未写入 sys_menu 的码，
--   任何角色（含 SUPER_ADMIN）都永远拿不到 → 该端点对**所有人恒定 403**。
--   因此「加注解」与「播种 + 授权」必须同批落地，且改完必须清 Redis 权限缓存。
--
-- 幂等：sys_menu 走 uk_menu_perm(perm_code) 唯一键 + INSERT IGNORE；
--       sys_role_menu 走 uk_role_menu(role_id,menu_id) 唯一键 + INSERT IGNORE。可重复执行。
--
-- ID 段约定：紧接现有 MAX(id)=341，使用 **342 / 343 / 344**（DB 实测 sys_menu MAX(id)=341，
--            AUTO_INCREMENT=342）。本脚本仅用这 3 个 id，不触碰任何既有 id。
--
-- 授权集合 = 镜像同域既有「create」码的真实授权集合（非臆造）：
--     319 alarm_rule:create     → {SUPER_ADMIN, OPERATOR, ADMIN, BIZ_ADMIN}
--     320 notify_channel:create → {SUPER_ADMIN, ADMIN}
--   ⇒ alarm_rule:test          → 镜像 319
--     notify_channel:test/delete→ 镜像 320
--
-- 应用执行：
--   mysql -h <host> -P <port> -u <user> -p<pass> gatekeeper < docs/sql/t07a-seed-rules.sql
--   然后（必须）：redis-cli DEL gk:perm:*    # 清权限缓存，否则 24h TTL 内看起来"没生效"
--
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 新增 3 个权限点（pid=5 = 告警/通知域模块节点，与 319/320 同父）
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO sys_menu (id, pid, name, type, perm_code, route_path, risk_flag, sort_order, status) VALUES
(342, 5, '发送测试告警', 3, 'alarm_rule:test',       NULL, 1, 12, 1),
(343, 5, '发送测试通知', 3, 'notify_channel:test',   NULL, 1, 13, 1),
(344, 5, '删除通知渠道', 3, 'notify_channel:delete', NULL, 1, 14, 1);

-- ---------------------------------------------------------------------------
-- 2) 角色授权（sys_role_menu）
-- ---------------------------------------------------------------------------
-- 2a) alarm_rule:test → SUPER_ADMIN / OPERATOR / ADMIN / BIZ_ADMIN（镜像 alarm_rule:create=319）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code IN ('SUPER_ADMIN', 'OPERATOR', 'ADMIN', 'BIZ_ADMIN')
  AND m.perm_code = 'alarm_rule:test';

-- 2b) notify_channel:test + notify_channel:delete → SUPER_ADMIN / ADMIN（镜像 notify_channel:create=320）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
WHERE r.role_code IN ('SUPER_ADMIN', 'ADMIN')
  AND m.perm_code IN ('notify_channel:test', 'notify_channel:delete');

-- ---------------------------------------------------------------------------
-- 3) 与 team-lead 建议口径的一处**有意偏离**（已在上报中说明理由）
-- ---------------------------------------------------------------------------
-- team-lead 建议授权集合为 ADMIN / BIZ_ADMIN / API_PROVIDER。本脚本改为「镜像同域既有
-- create 码的真实授权集合」，理由：
--   (a) **必须含 SUPER_ADMIN(1)**：主账号 admin 虽同时持有 ADMIN，但同域既有 create 码
--       （319/320/321/322）在库中**均显式授予 SUPER_ADMIN**；不授 SUPER_ADMIN 会破坏该域
--       既有的授权一致性，且对"仅持 SUPER_ADMIN"的账号会造成 403。
--   (b) **不授 API_PROVIDER**：该域（告警/通知/封禁）4 个既有码**无一**授予 API_PROVIDER；
--       且 notify_channel:delete 为破坏性操作，授予"接口提供方"与既有权限模型相悖
--       （API_PROVIDER 现有 34 个权限全部为 api/app 域）。
--   若 team-lead 坚持纳入 API_PROVIDER，追加一行即可（幂等，可安全追加）：
--     INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
--     SELECT r.id, m.id FROM sys_role r CROSS JOIN sys_menu m
--     WHERE r.role_code = 'API_PROVIDER'
--       AND m.perm_code IN ('alarm_rule:test', 'notify_channel:test', 'notify_channel:delete');

-- ---------------------------------------------------------------------------
-- 4) 校验（执行后手工核对）
-- ---------------------------------------------------------------------------
-- -- 4.1 新码是否落库（应 3 行）：
-- SELECT id, pid, name, perm_code, risk_flag, sort_order, status
-- FROM sys_menu WHERE id IN (342, 343, 344) ORDER BY id;
--
-- -- 4.2 授权展开（每个码应各返回其角色集合）：
-- SELECT m.perm_code, r.role_code
-- FROM sys_role_menu rm
-- JOIN sys_role r ON r.id = rm.role_id
-- JOIN sys_menu m ON m.id = rm.menu_id
-- WHERE m.perm_code IN ('alarm_rule:test', 'notify_channel:test', 'notify_channel:delete')
-- ORDER BY m.perm_code, r.role_code;
--
-- -- 4.3 行数核对（执行前/后）：
-- SELECT (SELECT COUNT(*) FROM sys_menu)       AS menu_rows,
--        (SELECT COUNT(*) FROM sys_role_menu)  AS role_menu_rows,
--        (SELECT MAX(id) FROM sys_menu)        AS menu_max_id;
