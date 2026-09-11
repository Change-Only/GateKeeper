-- ============================================================
-- T09 数据卫生三件套（team-lead 亲自核查并执行，2026-09-12）
-- 幂等：可重复执行；全部为数据/注释级操作，不改表结构、不删业务数据
-- 核查依据（逐条实测，非推断）：
--   * sys_menu id=221 系种子脚本之外的野行（migrate-v2.sql:136 只播了 212），
--     212/221 均为 pid=5 type=2 route_path='/sys/bizline'，221 零 sys_role_menu 引用、零子节点；
--     getRoleMenu 授权树（PermRole.vue → GET /sys/menu/list）会渲染出两个「业务线管理」
--   * alert.level 才是真实等级列（VARCHAR，INFO/WARNING/CRITICAL，AlertServiceImpl.publish 写入）；
--     alert.alarm_level 为死列（1 行数据中 0 非 NULL，后端零引用），
--     且列注释「1=提示,2=警告,3=严重」本身就有误导性（RiskVo 注释亦警示过）
--   * app.audit_status 前后端零消费方（grep 全仓确认），属预留列
--   * sys_menu id=1 与 id=201（「概览」目录+子菜单，同名同路由）为 migrate-v2.sql:82/125
--     有意设计，id=1 有 9 处 sys_role_menu 引用 —— 刻意不动，防后人误"修"
-- ============================================================

-- 1) 删除 sys_menu 野行 221（带四重守卫：任一不满足即不删）
DELETE FROM sys_menu
WHERE id = 221
  AND pid = 5
  AND type = 2
  AND route_path = '/sys/bizline'
  AND name = '业务线管理'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE menu_id = 221)
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_menu WHERE pid = 221) AS _children);

-- 2) alert.alarm_level 标记为废弃死列（仅改注释；原有索引 idx_alert_type_level 保留）
ALTER TABLE alert MODIFY COLUMN alarm_level TINYINT NULL DEFAULT NULL
  COMMENT '【T09 起废弃·死列】无代码写入；实际告警等级用 level VARCHAR（INFO/WARNING/CRITICAL）。勿新增消费方，如需启用须先出评审';

-- 3) app.audit_status 标记为预留列（仅改注释；原有索引 idx_app_audit 保留）
ALTER TABLE app MODIFY COLUMN audit_status TINYINT NOT NULL DEFAULT 1
  COMMENT '【T09 核实：预留列】语义 0=待审核,1=已通过；前后端零消费方、未接任何字典/UI；启用前须先补齐字典与前后端链路';

-- 4) 字典 alarm_level 的 remark 澄清作用域（字典本身正确：映射 alarm_rule.alarm_level）
UPDATE sys_dict
SET remark = '仅映射 alarm_rule.alarm_level（告警规则配置等级 1提示/2警告/3严重）。注意：alert.level 是字符串枚举 INFO/WARNING/CRITICAL，与本字典无关；alert.alarm_level 为废弃死列，勿用',
    updated_at = NOW()
WHERE dict_code = 'alarm_level';

-- 验证（执行后应看到）：
--   SELECT COUNT(*) FROM sys_menu WHERE id=221;                        -- 0
--   SELECT COUNT(*) FROM sys_menu;                                     -- 112（113-1）
--   SELECT remark FROM sys_dict WHERE dict_code='alarm_level';         -- 作用域澄清文案
--   SHOW FULL COLUMNS FROM alert LIKE 'alarm_level';                   -- 废弃注释
--   SHOW FULL COLUMNS FROM app LIKE 'audit_status';                    -- 预留列注释
