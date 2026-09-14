-- ============================================================================
-- GateKeeper → APIM 统一接口管理平台 · V2 增量 DDL
-- ----------------------------------------------------------------------------
-- 执行前提：已执行 src/backend/src/main/resources/sql/init.sql（18 张存量表）
-- 执行顺序：init.sql  →  schema-v2.sql  →  migrate-v2.sql
-- 环境：MySQL 8.x / InnoDB / utf8mb4
--
-- 设计原则：
--   1. 增量演进，绝不重写存量表（存量表一律 ADD COLUMN，不改列名、不改列类型语义）
--   2. 字段名/枚举值直接取自原型 MOCK 数据（docs/prototype/api-platform-原型.html）
--      原型 camelCase → 本文件 snake_case；凡属推断的字段/枚举，注释中标注「（推断）」
--   3. 索引沿用存量 api_call_log 的「过滤列 + 时间列」组合索引思路，热表控制索引数量
--   4. 维度键：环境用 env_code 冗余（低基数、稳定、网关零 JOIN），业务线用 line_id 外键
--
-- 幂等性：本文件通过 gk_add_column / gk_add_index 两个存储过程实现「列/索引不存在才添加」，
--         可重复执行；建表一律 CREATE TABLE IF NOT EXISTS。
--         注意：使用了 DELIMITER，请通过 mysql CLI / Navicat / DataGrip 执行
--         （init.sql 同样使用 CREATE DATABASE/USE，即 CLI 语义）。
-- ============================================================================

SET NAMES utf8mb4;
USE `gatekeeper`;

-- ============================================================================
-- 0. 幂等辅助存储过程（执行完自动清理）
-- ============================================================================
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


-- ############################################################################
-- 第一部分：新建表（18 张）
-- ############################################################################

-- ============================================================================
-- 1. 业务线表（biz_line）            —— 原型 MOCK.bizLines
--    作用：一级组织维度。app / api_interface / api_group / sys_user 均挂 line_id
-- ============================================================================
CREATE TABLE IF NOT EXISTS biz_line (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '业务线ID',
    line_code     VARCHAR(64)   NOT NULL                COMMENT '业务线编码（原型 lineCode）',
    line_name     VARCHAR(128)  NOT NULL                COMMENT '业务线名称（原型 lineName）',
    owner_name    VARCHAR(64)   DEFAULT NULL            COMMENT '负责人姓名（原型 ownerName）',
    member_count  INT           NOT NULL DEFAULT 0      COMMENT '成员数量（原型 memberCount，统计冗余）',
    status        TINYINT       NOT NULL DEFAULT 1      COMMENT '1=启用, 0=停用（原型 status）',
    remark        VARCHAR(512)  DEFAULT NULL            COMMENT '备注（原型 remark）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bizline_code (line_code),
    KEY idx_bizline_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务线表';

-- ============================================================================
-- 2. 环境表（env）                   —— 原型 MOCK.envs
--    作用：运行环境的「主数据」。env_code 一旦创建不可修改（防止冗余列不一致）
-- ============================================================================
CREATE TABLE IF NOT EXISTS env (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '环境ID',
    env_code     VARCHAR(32)   NOT NULL                COMMENT '环境编码 dev/test/pre/prod，创建后不可修改（原型 envCode）',
    env_name     VARCHAR(64)   NOT NULL                COMMENT '环境名称（原型 envName）',
    gateway_url  VARCHAR(256)  DEFAULT NULL            COMMENT '该环境网关入口地址（原型 gatewayUrl）',
    sort_order   INT           NOT NULL DEFAULT 0      COMMENT '排序（原型 sort）',
    status       TINYINT       NOT NULL DEFAULT 1      COMMENT '1=启用, 0=停用（原型 status）',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_env_code (env_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='环境表';

-- ============================================================================
-- 3. 菜单权限点表（sys_menu）        —— 原型 MOCK.menus
--    type: 1=模块（分组节点）, 2=菜单（页面）, 3=权限点（按钮/接口）
--    risk: 1=高危操作，需在审计日志中强制留痕
--    说明：模块节点的 perm_code 原型为空串，本表以 NULL 存储以便建唯一索引
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '权限点ID',
    pid         BIGINT        NOT NULL DEFAULT 0      COMMENT '父级ID，0=顶级（原型 pid）',
    name        VARCHAR(64)   NOT NULL                COMMENT '菜单/权限点名称（原型 name）',
    type        TINYINT       NOT NULL DEFAULT 1      COMMENT '1=模块,2=菜单,3=权限点（原型 type）',
    perm_code   VARCHAR(64)   DEFAULT NULL            COMMENT '权限点编码 如 app:create（原型 permCode，模块节点为NULL）',
    route_path  VARCHAR(128)  DEFAULT NULL            COMMENT '前端路由路径，type=2 页面节点使用 如 /app/list（推断：承接原型 MENU_TREE 20 个页面）',
    risk_flag   TINYINT       NOT NULL DEFAULT 0      COMMENT '1=高危操作,0=普通（原型 risk）',
    sort_order  INT           NOT NULL DEFAULT 0      COMMENT '排序（推断：原型按数组顺序）',
    status      TINYINT       NOT NULL DEFAULT 1      COMMENT '1=启用,0=停用（推断）',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_perm (perm_code),
    KEY idx_menu_pid (pid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单权限点表';

-- ============================================================================
-- 4. 角色-权限点关联表（sys_role_menu）—— 原型 MOCK.rolePerms
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id     BIGINT   NOT NULL                COMMENT '角色ID（原型 rolePerms 的 key）',
    menu_id     BIGINT   NOT NULL                COMMENT '权限点ID（原型 rolePerms 的 value 数组）',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_rm_menu (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限点关联表';

-- ============================================================================
-- 5. 角色数据权限范围表（sys_role_datascope）—— 原型「数据权限」页
--    scope_type: BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组
--    空表（该角色无任何范围记录）= 不限；避免 Null/全部二义性
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_role_datascope (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id      BIGINT       NOT NULL                COMMENT '角色ID',
    scope_type   VARCHAR(20)  NOT NULL                COMMENT 'BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组（推断自原型 datascope 表单三维度）',
    scope_value  VARCHAR(64)  NOT NULL                COMMENT '范围值：业务线ID / 环境编码 / 接口分组ID',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_scope (role_id, scope_type, scope_value),
    KEY idx_ds_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色数据权限范围表';

-- ============================================================================
-- 6. 接口参数定义表（api_param）     —— 原型 MOCK.apiParams
--    param_type: 1=HEADER, 2=QUERY（推断，原型未出现）, 3=BODY, 4=RESPONSE, 5=ERROR_CODE
--    parent_id: 支持嵌套结构（items.skuId）
-- ============================================================================
CREATE TABLE IF NOT EXISTS api_param (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_id        BIGINT        NOT NULL                COMMENT '接口ID（原型 apiId）',
    param_type    TINYINT       NOT NULL                COMMENT '1=HEADER,2=QUERY,3=BODY,4=RESPONSE,5=ERROR_CODE（原型 paramType）',
    parent_id     BIGINT        NOT NULL DEFAULT 0      COMMENT '父级参数ID，0=顶层，支持嵌套（原型 parentId）',
    field_name    VARCHAR(128)  NOT NULL                COMMENT '字段名 / 错误码KEY（原型 fieldName）',
    field_type    VARCHAR(32)   DEFAULT NULL            COMMENT 'string/int/number/array/object/bool（原型 fieldType）',
    required      TINYINT       NOT NULL DEFAULT 0      COMMENT '1=必填,0=选填（原型 required）',
    example       VARCHAR(512)  DEFAULT NULL            COMMENT '示例值（原型 example）',
    error_code    VARCHAR(64)   DEFAULT NULL            COMMENT '错误码，param_type=5 时有效（原型 errorCode）',
    http_status   INT           DEFAULT NULL            COMMENT 'HTTP状态码，param_type=5 时有效（原型 httpStatus）',
    `sensitive`   TINYINT       NOT NULL DEFAULT 0      COMMENT '1=敏感字段,0=否（原型 sensitive）',
    encrypt_rule  VARCHAR(32)   DEFAULT NULL            COMMENT '加解密/脱敏规则 SYMMETRIC/MASK/NONE（原型 encryptRule）',
    sort_order    INT           NOT NULL DEFAULT 0      COMMENT '排序（推断）',
    description   VARCHAR(512)  DEFAULT NULL            COMMENT '字段说明（原型 desc）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_param_api (api_id, param_type),
    KEY idx_param_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口参数定义表';

-- ============================================================================
-- 7. 接口版本表（api_version）       —— 原型 MOCK.apiVersions
--    status 复用字典 api_status：1=生效中, 2=已弃用, 3=已下线（推断对齐）
--    gray_ratio：灰度流量百分比，配合 is_current 决定数据面分流
-- ============================================================================
CREATE TABLE IF NOT EXISTS api_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_id            BIGINT       NOT NULL                COMMENT '接口ID（原型 apiId）',
    version           VARCHAR(20)  NOT NULL                COMMENT '版本号 v1/v2（原型 version）',
    status            TINYINT      NOT NULL DEFAULT 1      COMMENT '1=生效中,2=已弃用,3=已下线（原型 status）',
    is_current        TINYINT      NOT NULL DEFAULT 0      COMMENT '1=当前默认版本,0=非默认（原型 isCurrent）',
    gray_ratio        INT          NOT NULL DEFAULT 0      COMMENT '灰度流量百分比 0-100（原型 grayRatio）',
    change_log        VARCHAR(512) DEFAULT NULL            COMMENT '版本变更说明（原型 changeLog）',
    deprecate_time    DATE         DEFAULT NULL            COMMENT '弃用时间（原型 deprecateTime）',
    offline_plan_time DATE         DEFAULT NULL            COMMENT '计划下线时间（原型 offlinePlanTime）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_api (api_id, version),
    KEY idx_version_current (api_id, is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口版本表';

-- ============================================================================
-- 8. 接口环境配置表（api_env_config）—— 原型 MOCK.apiEnvConfigs
--    version 为 NULL 表示该环境所有版本通用；灰度版本可配独立上游
--    config_status: 1=已配置, 2=未配置（原型 configStatus）
-- ============================================================================
CREATE TABLE IF NOT EXISTS api_env_config (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_id           BIGINT       NOT NULL                COMMENT '接口ID（原型 apiId）',
    env_code         VARCHAR(32)  NOT NULL                COMMENT '环境编码（原型 envCode）',
    version          VARCHAR(20)  DEFAULT NULL            COMMENT '版本号，NULL=所有版本通用（推断：支撑灰度版本独立上游）',
    upstream_url     VARCHAR(512) NOT NULL                COMMENT '后端服务地址（原型 upstreamUrl）',
    connect_timeout  INT          NOT NULL DEFAULT 1000   COMMENT '连接超时(ms)（原型 connectTimeout）',
    read_timeout     INT          NOT NULL DEFAULT 3000   COMMENT '读取超时(ms)（原型 readTimeout）',
    retry_count      INT          NOT NULL DEFAULT 0      COMMENT '重试次数（原型 retryCount）',
    mock_enabled     TINYINT      NOT NULL DEFAULT 0      COMMENT '1=开启Mock,0=关闭（原型 mockEnabled）',
    config_status    TINYINT      NOT NULL DEFAULT 1      COMMENT '1=已配置,2=未配置（原型 configStatus）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_env_ver (api_id, env_code, version),
    KEY idx_aec_api (api_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口环境配置表';

-- ============================================================================
-- 9. 接口变更历史表（api_change_log）—— 原型 MOCK.apiChangeLogs
-- ============================================================================
CREATE TABLE IF NOT EXISTS api_change_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_id         BIGINT        NOT NULL                COMMENT '接口ID（原型 apiId）',
    change_type    VARCHAR(20)   NOT NULL                COMMENT 'CREATE/UPDATE/PUBLISH/OFFLINE/DELETE（原型 changeType）',
    field_name     VARCHAR(128)  DEFAULT NULL            COMMENT '变更字段名（原型 fieldName）',
    field_label    VARCHAR(128)  DEFAULT NULL            COMMENT '变更字段中文名（原型 fieldLabel）',
    old_value      VARCHAR(1024) DEFAULT NULL            COMMENT '变更前值（原型 oldValue）',
    new_value      VARCHAR(1024) DEFAULT NULL            COMMENT '变更后值（原型 newValue）',
    operator_id    BIGINT        DEFAULT NULL            COMMENT '操作人ID（推断）',
    operator_name  VARCHAR(64)   DEFAULT NULL            COMMENT '操作人姓名（原型 operatorName）',
    change_reason  VARCHAR(512)  DEFAULT NULL            COMMENT '变更原因（原型 changeReason）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间（原型 createTime）',
    PRIMARY KEY (id),
    KEY idx_acl_api_time (api_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口变更历史表';

-- ============================================================================
-- 10. 应用凭证表（app_credential）   —— 原型 MOCK.credentials
--     演进说明：存量 app.app_key / app.app_secret 是「一应用一密钥」，
--               本表升级为「一应用多密钥 + 环境隔离 + 轮换」。
--               存量 app 表的两个字段保留为冗余兼容列，由迁移脚本回填本表。
--     status 复用字典 cred_status：1=启用中,2=已停用,3=已吊销,4=已过期
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_credential (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_id          BIGINT        NOT NULL                COMMENT '应用ID（原型 appId）',
    app_key         VARCHAR(64)   NOT NULL                COMMENT 'AppKey（原型 appKey）',
    app_secret      VARCHAR(256)  NOT NULL                COMMENT 'AppSecret（AES加密存储；原型仅展示 secretMask）',
    secret_mask     VARCHAR(64)   DEFAULT NULL            COMMENT '密钥掩码展示 如 Yk3m****J5sU（原型 secretMask）',
    alias           VARCHAR(128)  DEFAULT NULL            COMMENT '密钥别名（原型 alias）',
    env_code        VARCHAR(32)   NOT NULL DEFAULT 'prod' COMMENT '所属环境编码（原型 envCode）',
    status          TINYINT       NOT NULL DEFAULT 1      COMMENT '1=启用中,2=已停用,3=已吊销,4=已过期（原型 status / dict cred_status）',
    expire_time     DATETIME      DEFAULT NULL            COMMENT '过期时间，NULL=永不过期（原型 expireTime）',
    last_used_time  DATETIME      DEFAULT NULL            COMMENT '最近使用时间（原型 lastUsedTime）',
    last_used_ip    VARCHAR(64)   DEFAULT NULL            COMMENT '最近使用IP（原型 lastUsedIp）',
    rotate_flag     TINYINT       NOT NULL DEFAULT 0      COMMENT '1=轮换中的新密钥,0=常规（原型 rotateFlag）',
    created_by      VARCHAR(64)   DEFAULT NULL            COMMENT '创建人（推断）',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（原型 createTime）',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cred_key (app_key),
    KEY idx_cred_app_env (app_id, env_code),
    KEY idx_cred_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用凭证表';

-- ============================================================================
-- 11. 应用配额表（app_quota）        —— 原型 MOCK.appQuotas
--     演进说明：替代存量 app_rate_limit 的「应用级单档限流」为「应用 × 环境」配额。
--               app_rate_limit 保留不删，作为网关降级回退来源（见架构文档 D2）。
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_quota (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_id         BIGINT   NOT NULL                COMMENT '应用ID（原型 appId）',
    env_code       VARCHAR(32) NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
    global_qps     INT      NOT NULL DEFAULT 0      COMMENT '全局QPS上限，0=不限（原型 globalQps）',
    daily_quota    BIGINT   NOT NULL DEFAULT 0      COMMENT '日调用配额，0=不限（原型 dailyQuota）',
    monthly_quota  BIGINT   NOT NULL DEFAULT 0      COMMENT '月调用配额，0=不限（原型 monthlyQuota）',
    concurrency    INT      NOT NULL DEFAULT 0      COMMENT '并发上限，0=不限（原型 concurrency）',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota_app_env (app_id, env_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用配额表（按环境）';

-- ============================================================================
-- 12. 应用接口授权表（app_api_grant）—— 原型 MOCK.grants
--     演进说明：替代存量 app_api_permission（无审批、无有效期、无环境）。
--               app_api_permission 保留为回滚快照，迁移后不再写入。
--     status 复用字典 grant_status：0=待审批,1=已生效,2=已过期,3=已撤销,4=已驳回
--     注意：原型 usedToday 不落库，走 Redis 日计数器 gk:grant:used:{yyyyMMdd}:{grantId}，
--           避免每调用一次 UPDATE 热行造成写放大
-- ============================================================================
CREATE TABLE IF NOT EXISTS app_api_grant (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_id         BIGINT        NOT NULL                COMMENT '应用ID（原型 appId）',
    api_id         BIGINT        NOT NULL                COMMENT '接口ID（原型 apiId）',
    env_code       VARCHAR(32)   NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
    qps_limit      INT           NOT NULL DEFAULT 0      COMMENT '授权QPS，0=不限（原型 qpsLimit）',
    daily_quota    BIGINT        NOT NULL DEFAULT 0      COMMENT '授权日配额，0=不限（原型 dailyQuota）',
    status         TINYINT       NOT NULL DEFAULT 0      COMMENT '0=待审批,1=已生效,2=已过期,3=已撤销,4=已驳回（原型 status / dict grant_status）',
    valid_from     DATE          DEFAULT NULL            COMMENT '生效日期（原型 validFrom）',
    valid_to       DATE          DEFAULT NULL            COMMENT '失效日期（原型 validTo）',
    grant_reason   VARCHAR(512)  DEFAULT NULL            COMMENT '申请理由（原型 grantReason）',
    applicant_id   BIGINT        DEFAULT NULL            COMMENT '申请人ID（推断）',
    applicant_name VARCHAR(64)   DEFAULT NULL            COMMENT '申请人姓名（原型 applicantName）',
    auditor_id     BIGINT        DEFAULT NULL            COMMENT '审批人ID（推断）',
    auditor_name   VARCHAR(64)   DEFAULT NULL            COMMENT '审批人姓名（原型 auditorName）',
    audit_time     DATETIME      DEFAULT NULL            COMMENT '审批时间（原型 auditTime）',
    audit_remark   VARCHAR(512)  DEFAULT NULL            COMMENT '审批意见（推断）',
    revoke_reason  VARCHAR(512)  DEFAULT NULL            COMMENT '撤销原因（推断）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant_app_api_env (app_id, api_id, env_code),
    KEY idx_grant_api (api_id),
    KEY idx_grant_status (status, valid_to),
    KEY idx_grant_env (env_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用接口授权表（含审批流与有效期）';

-- ============================================================================
-- 13. 告警规则表（alarm_rule）       —— 原型 MOCK.alarmRules
--     alarm_type: FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE
--     scope_type: 1=按对象(应用/接口)评估, 2=平台全局评估（推断）
--     channel_ids / receiver_ids：逗号分隔ID串，避免引入额外关联表（数据量 <100 行）
-- ============================================================================
CREATE TABLE IF NOT EXISTS alarm_rule (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    rule_name       VARCHAR(128) NOT NULL                COMMENT '规则名称（原型 ruleName）',
    alarm_type      VARCHAR(32)  NOT NULL                COMMENT 'FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE（原型 alarmType）',
    scope_type      TINYINT      NOT NULL DEFAULT 1      COMMENT '1=按对象(应用/接口),2=平台全局（原型 scopeType，语义为推断）',
    threshold       VARCHAR(64)  NOT NULL                COMMENT '阈值表达式 如 >5 / >200%基线 / 提前30天（原型 threshold）',
    time_window     INT          NOT NULL DEFAULT 5      COMMENT '统计窗口(分钟)（原型 timeWindow）',
    alarm_level     TINYINT      NOT NULL DEFAULT 2      COMMENT '1=提示,2=警告,3=严重（原型 alarmLevel / dict alarm_level）',
    silence_period  INT          NOT NULL DEFAULT 30     COMMENT '静默期(分钟)（原型 silencePeriod）',
    channel_ids     VARCHAR(255) DEFAULT NULL            COMMENT '通知渠道ID，逗号分隔（原型 channelNames 归一化）',
    receiver_scope  VARCHAR(32)  DEFAULT NULL            COMMENT 'ASSIGNEE=对象负责人,USER=指定用户,ROLE=指定角色（推断）',
    receiver_ids    VARCHAR(255) DEFAULT NULL            COMMENT '接收人ID，逗号分隔（原型 receiverNames 归一化）',
    receiver_desc   VARCHAR(255) DEFAULT NULL            COMMENT '接收人描述，如 各应用负责人（原型 receiverNames 中的动态分组文案）',
    status          TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用,0=停用（原型 status）',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_alarmrule_type (alarm_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警规则表';

-- ============================================================================
-- 14. 通知渠道表（notify_channel）   —— 原型 MOCK.notifyChannels
--     channel_type: WECOM/DINGTALK/EMAIL/SMS/WEBHOOK/HTTP（HTTP=自定义外部接口，T12）
-- ============================================================================
CREATE TABLE IF NOT EXISTS notify_channel (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    channel_name      VARCHAR(128)  NOT NULL                COMMENT '渠道名称（原型 channelName）',
    channel_type      VARCHAR(32)   NOT NULL                COMMENT 'WECOM/DINGTALK/EMAIL/SMS/WEBHOOK/HTTP（原型 channelType；HTTP=自定义外部接口，T12）',
    channel_config    VARCHAR(1024) DEFAULT NULL            COMMENT '渠道配置JSON（webhook地址/SMTP等）（推断）',
    status            TINYINT       NOT NULL DEFAULT 1      COMMENT '1=启用,0=停用（原型 status）',
    last_test_time    DATETIME      DEFAULT NULL            COMMENT '最近测试时间（原型 lastTestTime）',
    last_test_result  VARCHAR(255)  DEFAULT NULL            COMMENT '最近测试结果（原型 lastTestResult）',
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_notify_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知渠道表';

-- ============================================================================
-- 15. 参数配置表（sys_config）       —— 原型 MOCK.configs
--     config_group: SECURITY/GATEWAY/LOG/DEFAULT
--     sensitive=1 的配置接口返回时脱敏，不在前端明文展示
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_config (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    config_key    VARCHAR(128)  NOT NULL                COMMENT '配置键（原型 configKey）',
    config_value  VARCHAR(1024) DEFAULT NULL            COMMENT '配置值（原型 configValue）',
    config_group  VARCHAR(32)   NOT NULL DEFAULT 'DEFAULT' COMMENT 'SECURITY/GATEWAY/LOG/DEFAULT（原型 configGroup）',
    config_name   VARCHAR(128)  DEFAULT NULL            COMMENT '配置名称（原型 configName）',
    `sensitive`   TINYINT       NOT NULL DEFAULT 0      COMMENT '1=敏感配置(响应脱敏),0=普通（原型 sensitive）',
    built_in      TINYINT       NOT NULL DEFAULT 0      COMMENT '1=内置不可删除,0=可删除（原型 builtIn）',
    remark        VARCHAR(512)  DEFAULT NULL            COMMENT '备注（原型 remark）',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_config_group (config_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数配置表';

-- ============================================================================
-- 16. 数据字典表（sys_dict）         —— 原型 MOCK.dicts
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_dict (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dict_code   VARCHAR(64)  NOT NULL                COMMENT '字典编码（原型 dictCode）',
    dict_name   VARCHAR(128) NOT NULL                COMMENT '字典名称（原型 dictName）',
    built_in    TINYINT      NOT NULL DEFAULT 0      COMMENT '1=内置不可删除,0=可删除（原型 builtIn）',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用,0=停用（原型 status）',
    remark      VARCHAR(255) DEFAULT NULL            COMMENT '备注（推断）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_code (dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据字典表';

-- ============================================================================
-- 17. 数据字典项表（sys_dict_item）  —— 原型 MOCK.dicts[].items
-- ============================================================================
CREATE TABLE IF NOT EXISTS sys_dict_item (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dict_code   VARCHAR(64)  NOT NULL                COMMENT '字典编码（外键逻辑关联 sys_dict.dict_code）',
    item_value  VARCHAR(64)  NOT NULL                COMMENT '字典项值（原型 itemValue）',
    item_label  VARCHAR(128) NOT NULL                COMMENT '字典项标签（原型 itemLabel）',
    sort_order  INT          NOT NULL DEFAULT 0      COMMENT '排序（原型 sort）',
    status      TINYINT      NOT NULL DEFAULT 1      COMMENT '1=启用,0=停用（原型 status）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_item (dict_code, item_value),
    KEY idx_dictitem_code (dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据字典项表';

-- ============================================================================
-- 18. 动态封禁规则表（block_rule）   —— 原型 MOCK.blockRules
--     与存量 security_rule 的分工：
--       security_rule = 安全「检测」规则（是否产生 security_event，已有能力保留）
--       block_rule    = 风控「封禁」规则（命中后写入 ip_ban/封禁名单，本次新增）
--     enabled 默认 0：除人工封禁外，自动封禁规则默认关闭，上线需人工确认阈值
-- ============================================================================
CREATE TABLE IF NOT EXISTS block_rule (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    scope            VARCHAR(16)  NOT NULL                COMMENT 'IP=按来源IP, APP=按应用/AppKey（原型 scope）',
    reason_code      VARCHAR(40)  NOT NULL                COMMENT 'REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL（原型 reasonCode）',
    threshold_desc   VARCHAR(128) DEFAULT NULL            COMMENT '阈值描述 如 同IP 5min ≥ 20次（原型 threshold）',
    threshold_count  INT          NOT NULL DEFAULT 0      COMMENT '窗口内触发次数阈值（推断：由 threshold 文本结构化）',
    window_minutes   INT          NOT NULL DEFAULT 5      COMMENT '统计窗口(分钟)（推断：由 threshold 文本结构化）',
    ttl_seconds      INT          NOT NULL DEFAULT 0      COMMENT '封禁时长(秒)，0=永久（原型 ttl 结构化）',
    auto_block       TINYINT      NOT NULL DEFAULT 1      COMMENT '1=自动封禁,0=人工触发（原型 auto）',
    enabled          TINYINT      NOT NULL DEFAULT 0      COMMENT '1=启用,0=停用（原型 enabled）',
    description      VARCHAR(255) DEFAULT NULL            COMMENT '规则说明（原型 desc）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blockrule (scope, reason_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态封禁规则表';


-- ############################################################################
-- 第二部分：存量表 ALTER（10 张）
-- 说明：只加列 / 加索引，不改列名、不改存量列语义。
--       凡是原型枚举与存量枚举冲突的，一律「新增一列承载原型语义 + 保留存量列」，
--       避免破坏既有 91 个单测与网关链路（冲突点见架构文档「兼容与迁移」章节）。
-- ############################################################################

-- ============================================================================
-- A1. app（应用表）                  —— 原型 MOCK.apps
--     冲突处理：存量 app.status（1启用/0停用/2已过期）与原型 apps.status（0=待审核）语义冲突，
--               故新增 audit_status 单独承载「待审核」状态，status 语义保持不变。
--     env_scope：原型为数组，MySQL 无数组类型，用逗号分隔 env_code（长度极小，无需关联表）
-- ============================================================================
CALL gk_add_column('app', 'app_code',
    'app_code VARCHAR(64) DEFAULT NULL COMMENT ''应用编码（原型 appCode）''');
CALL gk_add_column('app', 'line_id',
    'line_id BIGINT DEFAULT NULL COMMENT ''业务线ID（原型 lineId）''');
CALL gk_add_column('app', 'app_type',
    'app_type TINYINT NOT NULL DEFAULT 1 COMMENT ''1=内部系统,2=外部合作方,3=测试应用（原型 appType / dict app_type）''');
CALL gk_add_column('app', 'env_scope',
    'env_scope VARCHAR(64) DEFAULT NULL COMMENT ''可用环境编码，逗号分隔 如 dev,test,prod（原型 envScope）''');
CALL gk_add_column('app', 'owner_name',
    'owner_name VARCHAR(64) DEFAULT NULL COMMENT ''负责人姓名（原型 ownerName）''');
CALL gk_add_column('app', 'contact_name',
    'contact_name VARCHAR(64) DEFAULT NULL COMMENT ''联系人（原型 contactName）''');
CALL gk_add_column('app', 'contact_info',
    'contact_info VARCHAR(128) DEFAULT NULL COMMENT ''联系方式（原型 contactInfo）''');
CALL gk_add_column('app', 'approval_required',
    'approval_required TINYINT NOT NULL DEFAULT 0 COMMENT ''1=授权需审批,0=免审批（原型 approvalRequired）''');
CALL gk_add_column('app', 'audit_status',
    'audit_status TINYINT NOT NULL DEFAULT 1 COMMENT ''【T09 核实：预留列】语义 0=待审核,1=已通过；前后端零消费方、未接任何字典/UI；启用前须先补齐字典与前后端链路''');
CALL gk_add_index('app', 'uk_app_code',
    'UNIQUE KEY uk_app_code (app_code)');
CALL gk_add_index('app', 'idx_app_line',
    'KEY idx_app_line (line_id)');
CALL gk_add_index('app', 'idx_app_audit',
    'KEY idx_app_audit (audit_status)');

-- ============================================================================
-- A2. app_ip_whitelist（应用IP白名单）—— 原型 MOCK.ipWhitelists
--     列映射：原型 ipValue → 存量 ip_cidr（同义，不新增冗余列）
-- ============================================================================
CALL gk_add_column('app_ip_whitelist', 'env_code',
    'env_code VARCHAR(32) NOT NULL DEFAULT ''prod'' COMMENT ''环境编码（原型 envCode）''');
CALL gk_add_column('app_ip_whitelist', 'status',
    'status TINYINT NOT NULL DEFAULT 1 COMMENT ''1=启用,0=停用（推断）''');
CALL gk_add_index('app_ip_whitelist', 'idx_ipwl_app_env',
    'KEY idx_ipwl_app_env (app_id, env_code)');

-- ============================================================================
-- A3. api_group（接口分组）          —— 原型 MOCK.apiGroups
-- ============================================================================
CALL gk_add_column('api_group', 'group_code',
    'group_code VARCHAR(64) DEFAULT NULL COMMENT ''分组编码（原型 groupCode）''');
CALL gk_add_column('api_group', 'line_id',
    'line_id BIGINT DEFAULT NULL COMMENT ''业务线ID（原型 lineId）''');
CALL gk_add_column('api_group', 'owner_id',
    'owner_id BIGINT DEFAULT NULL COMMENT ''负责人用户ID（推断）''');
CALL gk_add_column('api_group', 'owner_name',
    'owner_name VARCHAR(64) DEFAULT NULL COMMENT ''负责人姓名（原型 ownerName）''');
CALL gk_add_column('api_group', 'api_count',
    'api_count INT NOT NULL DEFAULT 0 COMMENT ''接口数量（原型 apiCount，统计冗余）''');
CALL gk_add_column('api_group', 'status',
    'status TINYINT NOT NULL DEFAULT 1 COMMENT ''1=启用,0=停用（原型 status）''');
CALL gk_add_index('api_group', 'uk_group_code',
    'UNIQUE KEY uk_group_code (group_code)');
CALL gk_add_index('api_group', 'idx_group_line',
    'KEY idx_group_line (line_id)');

-- ============================================================================
-- A4. api_interface（接口表）        —— 原型 MOCK.apis
--     冲突处理：存量 status（1启用/0停用）是「网关开关」，
--               原型 api_status（0草稿/1待审核/2已发布/3已弃用/4已下线）是「发布生命周期」，
--               两者并存：网关放行条件 = status=1 AND publish_status=2。
--     tags 用逗号分隔；version 由 api_version 维护，current_version 为列表展示冗余
-- ============================================================================
CALL gk_add_column('api_interface', 'api_code',
    'api_code VARCHAR(64) DEFAULT NULL COMMENT ''接口编码 如 order.create（原型 apiCode）''');
CALL gk_add_column('api_interface', 'line_id',
    'line_id BIGINT DEFAULT NULL COMMENT ''业务线ID（原型 lineId）''');
CALL gk_add_column('api_interface', 'owner_id',
    'owner_id BIGINT DEFAULT NULL COMMENT ''负责人用户ID（推断）''');
CALL gk_add_column('api_interface', 'owner_name',
    'owner_name VARCHAR(64) DEFAULT NULL COMMENT ''负责人姓名（原型 ownerName）''');
CALL gk_add_column('api_interface', 'visibility',
    'visibility TINYINT NOT NULL DEFAULT 1 COMMENT ''1=内部,2=对外公开（原型 visibility / dict visibility）''');
CALL gk_add_column('api_interface', 'auth_required',
    'auth_required TINYINT NOT NULL DEFAULT 1 COMMENT ''1=需鉴权,0=免鉴权（原型 authRequired）''');
CALL gk_add_column('api_interface', 'publish_status',
    'publish_status TINYINT NOT NULL DEFAULT 2 COMMENT ''0=草稿,1=待审核,2=已发布,3=已弃用,4=已下线（原型 status / dict api_status）''');
CALL gk_add_column('api_interface', 'current_version',
    'current_version VARCHAR(20) DEFAULT NULL COMMENT ''当前版本号（原型 version，冗余自 api_version.is_current）''');
CALL gk_add_column('api_interface', 'sla',
    'sla VARCHAR(128) DEFAULT NULL COMMENT ''SLA承诺 如 99.9%, P99<200ms（原型 sla）''');
CALL gk_add_column('api_interface', 'tags',
    'tags VARCHAR(255) DEFAULT NULL COMMENT ''标签，逗号分隔 如 核心链路,只读（原型 tags 数组归一化）''');
CALL gk_add_column('api_interface', 'transport_security',
    'transport_security VARCHAR(16) NOT NULL DEFAULT ''NONE'' COMMENT ''NONE/TLS/MTLS（原型 transportSecurity）''');
CALL gk_add_column('api_interface', 'grant_count',
    'grant_count INT NOT NULL DEFAULT 0 COMMENT ''授权数（原型 grantCount，统计冗余）''');
CALL gk_add_index('api_interface', 'uk_iface_code',
    'UNIQUE KEY uk_iface_code (api_code)');
CALL gk_add_index('api_interface', 'idx_iface_line',
    'KEY idx_iface_line (line_id)');
CALL gk_add_index('api_interface', 'idx_iface_publish',
    'KEY idx_iface_publish (publish_status)');

-- ============================================================================
-- A5. api_call_log（调用日志）       —— 原型 MOCK.callLogs
--     索引策略：本表为最热写入表，仅新增 2 个索引，沿用「过滤列 + request_time」组合思路。
--               error_code 检索复用既有 idx_log_status_time（response_status 与 error_code 强相关）。
-- ============================================================================
CALL gk_add_column('api_call_log', 'trace_id',
    'trace_id VARCHAR(64) DEFAULT NULL COMMENT ''链路追踪ID（原型 traceId）''');
CALL gk_add_column('api_call_log', 'env_code',
    'env_code VARCHAR(32) DEFAULT NULL COMMENT ''环境编码（原型 envCode）''');
CALL gk_add_column('api_call_log', 'app_key',
    'app_key VARCHAR(64) DEFAULT NULL COMMENT ''调用使用的AppKey（推断：密钥轮换后仍可追溯，非冗余）''');
CALL gk_add_column('api_call_log', 'error_code',
    'error_code VARCHAR(64) DEFAULT NULL COMMENT ''错误码 API_NOT_AUTHORIZED/RATE_LIMIT_EXCEEDED/...（原型 errorCode）''');
CALL gk_add_column('api_call_log', 'reject_stage',
    'reject_stage VARCHAR(32) DEFAULT NULL COMMENT ''拒绝阶段 APPKEY/SIGNATURE/NONCE/ACL/RATELIMIT/UPSTREAM（原型 rejectStage）''');
CALL gk_add_column('api_call_log', 'auth_cost',
    'auth_cost INT DEFAULT NULL COMMENT ''鉴权耗时(ms)（原型 authCost）''');
CALL gk_add_column('api_call_log', 'upstream_cost',
    'upstream_cost INT DEFAULT NULL COMMENT ''后端耗时(ms)（原型 upstreamCost）''');
CALL gk_add_column('api_call_log', 'api_version',
    'api_version VARCHAR(20) DEFAULT NULL COMMENT ''命中的接口版本（推断：灰度排障需要）''');
CALL gk_add_index('api_call_log', 'idx_log_env_time',
    'KEY idx_log_env_time (env_code, request_time)');
CALL gk_add_index('api_call_log', 'idx_log_trace',
    'KEY idx_log_trace (trace_id)');

-- ============================================================================
-- A6. ip_ban（IP封禁表）→ 统一封禁名单 —— 原型 MOCK.blocklists
--     演进说明：不新建 blocklist 表，而是把 ip_ban 扩展为「IP + 应用」双 scope 的统一封禁名单，
--               使既有 IpBanCheckHandler / IpBanService / SecurityController 继续可用。
--     列映射：ban_reason ↔ reasonDetail；ban_end_time ↔ expireTime；ban_type ↔ blockType（取值已一致）
-- ============================================================================
CALL gk_add_column('ip_ban', 'scope',
    'scope VARCHAR(16) NOT NULL DEFAULT ''IP'' COMMENT ''IP=来源IP/CIDR, APP=应用或AppKey（原型 scope）''');
CALL gk_add_column('ip_ban', 'target',
    'target VARCHAR(128) DEFAULT NULL COMMENT ''封禁对象：IP/CIDR/AppKey/AppId（原型 target）；scope=IP 时与 ip_address 同值''');
CALL gk_add_column('ip_ban', 'env_code',
    'env_code VARCHAR(32) NOT NULL DEFAULT ''prod'' COMMENT ''环境编码（原型 envCode）''');
CALL gk_add_column('ip_ban', 'reason_code',
    'reason_code VARCHAR(40) DEFAULT NULL COMMENT ''REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL（原型 reasonCode）''');
CALL gk_add_column('ip_ban', 'block_times',
    'block_times INT NOT NULL DEFAULT 1 COMMENT ''累计封禁次数（原型 blockTimes）''');
CALL gk_add_column('ip_ban', 'ttl_seconds',
    'ttl_seconds INT NOT NULL DEFAULT 0 COMMENT ''封禁时长(秒)，0=永久（原型 ttlSeconds）''');
CALL gk_add_column('ip_ban', 'created_by',
    'created_by BIGINT NOT NULL DEFAULT 0 COMMENT ''封禁操作人，0=系统自动（原型 blockBy）''');
CALL gk_add_column('ip_ban', 'unblock_by',
    'unblock_by BIGINT DEFAULT NULL COMMENT ''解封操作人（原型 unblockBy）''');
CALL gk_add_column('ip_ban', 'unblock_reason',
    'unblock_reason VARCHAR(512) DEFAULT NULL COMMENT ''解封原因（原型 unblockReason）''');
CALL gk_add_column('ip_ban', 'unblock_time',
    'unblock_time DATETIME DEFAULT NULL COMMENT ''解封时间（原型 unblockTime）''');
-- ip_address 原为 NOT NULL，扩展为 APP scope 后允许为空
ALTER TABLE ip_ban MODIFY COLUMN ip_address VARCHAR(64) DEFAULT NULL COMMENT 'IP地址（scope=IP 时必填，与 target 同值；scope=APP 时为NULL）';
CALL gk_add_index('ip_ban', 'idx_ban_target',
    'KEY idx_ban_target (target)');
CALL gk_add_index('ip_ban', 'idx_ban_scope_env',
    'KEY idx_ban_scope_env (scope, env_code, ban_status)');
CALL gk_add_index('ip_ban', 'idx_ban_reason',
    'KEY idx_ban_reason (reason_code)');

-- ============================================================================
-- A7. alert（告警表）→ 告警记录中心    —— 原型 MOCK.alarmRecords
--     演进说明：不新建 alarm_record 表，扩展既有 alert（AlertService / RedisHealthMonitor 已依赖）。
--     status 语义扩展：0=待处理(原未读),1=处理中(原已读),2=已处理,3=已忽略 —— 旧数据无需迁移
-- ============================================================================
CALL gk_add_column('alert', 'rule_id',
    'rule_id BIGINT DEFAULT NULL COMMENT ''触发的告警规则ID（原型 ruleId）''');
CALL gk_add_column('alert', 'rule_name',
    'rule_name VARCHAR(128) DEFAULT NULL COMMENT ''告警规则名称（原型 ruleName）''');
CALL gk_add_column('alert', 'alarm_type',
    'alarm_type VARCHAR(32) DEFAULT NULL COMMENT ''FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE（推断，冗余自 alarm_rule）''');
CALL gk_add_column('alert', 'alarm_level',
    'alarm_level TINYINT DEFAULT NULL COMMENT ''【T09 起废弃·死列】无代码写入；实际告警等级用 level VARCHAR（INFO/WARNING/CRITICAL）。勿新增消费方，如需启用须先出评审''');
CALL gk_add_column('alert', 'scope_desc',
    'scope_desc VARCHAR(255) DEFAULT NULL COMMENT ''告警范围描述（原型 scopeDesc）''');
CALL gk_add_column('alert', 'trigger_value',
    'trigger_value VARCHAR(64) DEFAULT NULL COMMENT ''触发值 如 8.2%（原型 triggerValue）''');
CALL gk_add_column('alert', 'env_code',
    'env_code VARCHAR(32) DEFAULT NULL COMMENT ''环境编码（推断，告警按环境隔离）''');
CALL gk_add_column('alert', 'related_api_id',
    'related_api_id BIGINT DEFAULT NULL COMMENT ''关联接口ID（推断）''');
CALL gk_add_column('alert', 'handler_name',
    'handler_name VARCHAR(64) DEFAULT NULL COMMENT ''处理人姓名（原型 handlerName）''');
CALL gk_add_column('alert', 'notify_status',
    'notify_status TINYINT NOT NULL DEFAULT 0 COMMENT ''0=未通知,1=已通知,2=通知失败（推断）''');
CALL gk_add_index('alert', 'idx_alert_rule',
    'KEY idx_alert_rule (rule_id)');
CALL gk_add_index('alert', 'idx_alert_type_level',
    'KEY idx_alert_type_level (alarm_type, alarm_level)');

-- ============================================================================
-- A8. sys_user（系统用户）            —— 原型 MOCK.users
--     列映射：原型 mobile → 存量 phone（同义，不新增冗余列）
-- ============================================================================
CALL gk_add_column('sys_user', 'emp_no',
    'emp_no VARCHAR(32) DEFAULT NULL COMMENT ''工号（原型 empNo）''');
CALL gk_add_column('sys_user', 'dept',
    'dept VARCHAR(128) DEFAULT NULL COMMENT ''所属部门（原型 dept）''');
CALL gk_add_column('sys_user', 'line_id',
    'line_id BIGINT DEFAULT NULL COMMENT ''所属业务线ID（原型 lineName 归一化）''');
CALL gk_add_column('sys_user', 'status',
    'status TINYINT NOT NULL DEFAULT 1 COMMENT ''1=启用,0=停用（存量已有列，此处为兜底声明）''');
CALL gk_add_index('sys_user', 'idx_user_line',
    'KEY idx_user_line (line_id)');
CALL gk_add_index('sys_user', 'uk_user_empno',
    'UNIQUE KEY uk_user_empno (emp_no)');

-- ============================================================================
-- A9. sys_role（角色表）              —— 原型 MOCK.roles
--     data_scope: ALL=全部数据, BIZ_LINE=仅本业务线, CUSTOM=自定义, SELF=仅本人
--                 （由原型 dataScope 文案「全部数据/仅本业务线/自定义/仅本人」归一化）
-- ============================================================================
CALL gk_add_column('sys_role', 'role_type',
    'role_type TINYINT NOT NULL DEFAULT 1 COMMENT ''1=内置角色,2=外部/自定义角色（原型 roleType）''');
CALL gk_add_column('sys_role', 'data_scope',
    'data_scope VARCHAR(20) NOT NULL DEFAULT ''ALL'' COMMENT ''ALL=全部数据,BIZ_LINE=仅本业务线,CUSTOM=自定义,SELF=仅本人（原型 dataScope）''');
CALL gk_add_column('sys_role', 'user_count',
    'user_count INT NOT NULL DEFAULT 0 COMMENT ''用户数（原型 userCount，统计冗余）''');
CALL gk_add_index('sys_role', 'idx_role_scope',
    'KEY idx_role_scope (data_scope)');

-- ============================================================================
-- A10. sys_operation_log（操作审计）→ 原型 MOCK.auditLogs
--      演进说明：不新建 audit_log 表。存量表字段与原型 auditLogs 高度重合，
--                仅补充 perm_code / risk_flag / object_desc / change_content / result / fail_reason。
--                operation_module 取值扩展为 APP/API/GRANT/ROLE/SYSTEM/AUTH/SECURITY
-- ============================================================================
CALL gk_add_column('sys_operation_log', 'perm_code',
    'perm_code VARCHAR(64) DEFAULT NULL COMMENT ''触发的权限点编码 如 grant:revoke（原型 permCode）''');
CALL gk_add_column('sys_operation_log', 'risk_flag',
    'risk_flag TINYINT NOT NULL DEFAULT 0 COMMENT ''1=高危操作,0=普通（原型 riskFlag）''');
CALL gk_add_column('sys_operation_log', 'object_desc',
    'object_desc VARCHAR(255) DEFAULT NULL COMMENT ''操作对象描述（原型 objectDesc）''');
CALL gk_add_column('sys_operation_log', 'change_content',
    'change_content TEXT DEFAULT NULL COMMENT ''变更内容JSON（原型 changeContent）''');
CALL gk_add_column('sys_operation_log', 'result',
    'result TINYINT NOT NULL DEFAULT 1 COMMENT ''1=成功,0=失败（原型 result）''');
CALL gk_add_column('sys_operation_log', 'fail_reason',
    'fail_reason VARCHAR(255) DEFAULT NULL COMMENT ''失败原因（原型 failReason）''');
CALL gk_add_index('sys_operation_log', 'idx_oplog_perm',
    'KEY idx_oplog_perm (perm_code)');
CALL gk_add_index('sys_operation_log', 'idx_oplog_risk_time',
    'KEY idx_oplog_risk_time (risk_flag, created_at)');


-- ============================================================================
-- A11. alarm_rule（告警规则表）        —— T11 增量：评估对象绑定
--      背景：scope_type=1「按对象」此前没有对象字段，评估时 scopeKey 直接拿规则ID
--            当占位（AlarmRuleServiceImpl#evaluateRealtime），"按对象"形同空转。
--      T11 补 target_type（APP/API）+ target_ids（逗号分隔，空=全部对象），
--      让按对象评估真正展开，静默粒度也随之细化为「规则 × 对象」。
--      注：init.sql 的 CREATE TABLE 已含这两列（全新初始化走 00 脚本即可）；
--          本段是为「已建库」的存量环境补列，幂等。
-- ============================================================================
CALL gk_add_column('alarm_rule', 'target_type',
    'target_type VARCHAR(16) DEFAULT NULL COMMENT ''T11：评估对象维度 APP=按应用 / API=按接口；scope_type=1 时必填，scope_type=2 时为 NULL'' AFTER `scope_type`');
CALL gk_add_column('alarm_rule', 'target_ids',
    'target_ids VARCHAR(512) DEFAULT NULL COMMENT ''T11：评估对象ID，逗号分隔（同 channel_ids 约定）；NULL/空=该维度下全部对象'' AFTER `target_type`');


-- ============================================================================
-- 清理辅助存储过程
-- ============================================================================
DROP PROCEDURE IF EXISTS gk_add_column;
DROP PROCEDURE IF EXISTS gk_add_index;

-- ============================================================================
-- 本文件变更清单
--   新建表 18 张：biz_line, env, sys_menu, sys_role_menu, sys_role_datascope,
--                api_param, api_version, api_env_config, api_change_log,
--                app_credential, app_quota, app_api_grant,
--                alarm_rule, notify_channel, sys_config, sys_dict, sys_dict_item,
--                block_rule
--   ALTER  10 张：app, app_ip_whitelist, api_group, api_interface, api_call_log,
--                ip_ban, alert, sys_user, sys_role, sys_operation_log
--   ALTER（T11 增量）1 张：alarm_rule（+target_type / +target_ids，评估对象绑定）
--   保持不变 8 张：app_rate_limit（降级回退源）, app_api_permission（回滚快照）,
--                api_encryption_config, app_encryption_config,
--                security_rule, security_event, export_task, sys_user_role
-- ============================================================================
