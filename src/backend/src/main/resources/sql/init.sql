-- ============================================================
-- GateKeeper API 网关管理系统 - 数据库初始化脚本
-- Database: MySQL 8.x
-- Encoding: utf8mb4
-- ============================================================

SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS `gatekeeper` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `gatekeeper`;

-- ============================================================
-- 1. 应用表（app）
--    用途：应用接入方信息，存储 AppKey/AppSecret/状态/描述/到期时间，网关鉴权核心表
-- ============================================================
CREATE TABLE app (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    app_name        VARCHAR(128)  NOT NULL COMMENT '应用名称',
    app_key         VARCHAR(64)   NOT NULL COMMENT 'AppKey',
    app_secret      VARCHAR(256)  NOT NULL COMMENT 'AppSecret（AES加密存储）',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用, 0=停用, 2=已过期',
    description     VARCHAR(512)  DEFAULT NULL COMMENT '描述',
    expire_time     DATETIME      DEFAULT NULL COMMENT '到期时间，NULL=永不过期',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_key (app_key),
    KEY idx_app_status (status),
    KEY idx_app_expire (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用表';

-- ============================================================
-- 2. 应用IP白名单表（app_ip_whitelist）
--    用途：应用来源IP访问控制，来源IP不在白名单内的请求将被网关拦截
-- ============================================================
CREATE TABLE app_ip_whitelist (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    app_id          BIGINT        NOT NULL COMMENT '应用ID',
    ip_cidr         VARCHAR(64)   NOT NULL COMMENT '单IP或CIDR格式',
    remark          VARCHAR(256)  DEFAULT NULL COMMENT '备注',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ipwl_app (app_id),
    CONSTRAINT fk_ipwl_app FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用IP白名单表';

-- ============================================================
-- 3. 应用限流配置表（app_rate_limit）
--    用途：应用流量控制策略（QPS/并发/日调用量），与 app 一对一
-- ============================================================
CREATE TABLE app_rate_limit (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    app_id          BIGINT        NOT NULL COMMENT '应用ID',
    qps_limit       INT           NOT NULL DEFAULT 0 COMMENT '每秒最大请求数，0=不限',
    concurrent_limit INT          NOT NULL DEFAULT 0 COMMENT '最大并发数，0=不限',
    daily_limit     INT           NOT NULL DEFAULT 0 COMMENT '日调用上限，0=不限',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rl_app (app_id),
    CONSTRAINT fk_rl_app FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用限流配置表';

-- ============================================================
-- 4. 接口分组表（api_group）
--    用途：接口分组管理，通过 parent_id 自关联支持树形多层级
-- ============================================================
CREATE TABLE api_group (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    group_name      VARCHAR(128)  NOT NULL COMMENT '分组名称',
    parent_id       BIGINT        DEFAULT NULL COMMENT '父分组ID，NULL=顶级分组',
    description     VARCHAR(512)  DEFAULT NULL COMMENT '描述',
    sort_order      INT           NOT NULL DEFAULT 0 COMMENT '排序',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_group_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口分组表（支持多层级）';

-- ============================================================
-- 5. 接口表（api_interface）
--    用途：网关代理接口定义，映射网关对外路径与后端真实服务地址
-- ============================================================
CREATE TABLE api_interface (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    interface_name  VARCHAR(128)  NOT NULL COMMENT '接口名称',
    interface_path  VARCHAR(256)  NOT NULL COMMENT '网关路径 /gateway/ 开头',
    request_method  VARCHAR(10)   NOT NULL COMMENT 'GET/POST/PUT/DELETE',
    request_param_type VARCHAR(20) DEFAULT 'JSON' COMMENT '入参类型 JSON/FORM/QUERY',
    group_id        BIGINT        DEFAULT NULL COMMENT '分组ID',
    backend_url     VARCHAR(512)  NOT NULL COMMENT '后端真实服务地址',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用, 0=停用',
    timeout_ms      INT           NOT NULL DEFAULT 5000 COMMENT '超时时间(ms)',
    description     VARCHAR(512)  DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_iface_path (interface_path, request_method),
    KEY idx_iface_group (group_id),
    KEY idx_iface_status (status),
    CONSTRAINT fk_iface_group FOREIGN KEY (group_id) REFERENCES api_group(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口表';

-- ============================================================
-- 6. 接口加解密配置表（api_encryption_config）
--    用途：接口级请求/响应加解密配置，与 api_interface 一对一
-- ============================================================
CREATE TABLE api_encryption_config (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    interface_id            BIGINT        NOT NULL COMMENT '接口ID',
    request_encrypted       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '入参是否加密',
    request_algorithm       VARCHAR(20)   DEFAULT NULL COMMENT 'SM4/AES',
    request_mode            VARCHAR(20)   DEFAULT NULL COMMENT 'ECB/CBC/CFB/OFB/CTR',
    request_key             VARCHAR(512)  DEFAULT NULL COMMENT 'Base64，库中AES加密',
    request_iv              VARCHAR(256)  DEFAULT NULL COMMENT 'Base64',
    request_padding         VARCHAR(30)   DEFAULT NULL COMMENT '填充方式',
    response_encrypted      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '返参是否加密',
    response_algorithm      VARCHAR(20)   DEFAULT NULL,
    response_mode           VARCHAR(20)   DEFAULT NULL,
    response_key            VARCHAR(512)  DEFAULT NULL,
    response_iv             VARCHAR(256)  DEFAULT NULL,
    response_padding        VARCHAR(30)   DEFAULT NULL,
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_enc_iface (interface_id),
    CONSTRAINT fk_enc_iface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接口加解密配置表';

-- ============================================================
-- 7. 应用加解密配置表（app_encryption_config）
--    用途：应用级密钥与加解密算法配置，密钥加密落库，与 app 一对一
-- ============================================================
CREATE TABLE app_encryption_config (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    app_id          BIGINT        NOT NULL COMMENT '应用ID',
    algorithm       VARCHAR(20)   NOT NULL COMMENT 'SM2/SM4/AES',
    public_key      TEXT          DEFAULT NULL COMMENT 'SM2公钥',
    private_key     TEXT          DEFAULT NULL COMMENT 'SM2私钥(加密存储)',
    secret_key      VARCHAR(512)  DEFAULT NULL COMMENT '对称密钥(Base64,加密存储)',
    iv              VARCHAR(256)  DEFAULT NULL COMMENT 'IV向量(Base64)',
    mode            VARCHAR(20)   DEFAULT NULL COMMENT 'ECB/CBC/CFB/OFB/CTR',
    padding         VARCHAR(30)   DEFAULT NULL COMMENT '填充方式',
    sign_algorithm  VARCHAR(20)   DEFAULT NULL COMMENT '签名算法 SM3/SHA256',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_aec_app (app_id),
    CONSTRAINT fk_aec_app FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用加解密配置表';

-- ============================================================
-- 8. 应用接口权限表（app_api_permission）
--    用途：应用-接口授权关系，网关据此判断请求是否越权
-- ============================================================
CREATE TABLE app_api_permission (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    app_id          BIGINT        NOT NULL COMMENT '应用ID',
    interface_id    BIGINT        NOT NULL COMMENT '接口ID',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=已授权, 0=已取消',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_iface (app_id, interface_id),
    KEY idx_perm_app (app_id),
    KEY idx_perm_iface (interface_id),
    CONSTRAINT fk_perm_app FOREIGN KEY (app_id) REFERENCES app(id) ON DELETE CASCADE,
    CONSTRAINT fk_perm_iface FOREIGN KEY (interface_id) REFERENCES api_interface(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用接口权限表';

-- ============================================================
-- 9. 调用日志表（api_call_log）
--    用途：网关转发的调用记录，数据量大时按月归档/清理，不物理分区
-- ============================================================
CREATE TABLE api_call_log (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    app_id                  BIGINT        DEFAULT NULL COMMENT '应用ID',
    app_name                VARCHAR(128)  DEFAULT NULL COMMENT '应用名（冗余）',
    interface_id            BIGINT        DEFAULT NULL COMMENT '接口ID',
    interface_path          VARCHAR(256)  DEFAULT NULL COMMENT '接口路径',
    request_method          VARCHAR(10)   DEFAULT NULL,
    request_time            DATETIME      NOT NULL COMMENT '请求时间',
    request_params          TEXT          DEFAULT NULL COMMENT '入参(加密接口存密文)',
    response_data           TEXT          DEFAULT NULL COMMENT '响应(加密接口存密文)',
    response_status         INT           DEFAULT NULL COMMENT '状态码',
    cost_time               INT           DEFAULT NULL COMMENT '耗时(ms)',
    client_ip               VARCHAR(64)   DEFAULT NULL COMMENT '调用方IP',
    encryption_algorithm    VARCHAR(20)   DEFAULT 'NONE' COMMENT '加密算法',
    is_rate_limited         TINYINT(1)    DEFAULT 0 COMMENT '是否被限流',
    is_blocked              TINYINT(1)    DEFAULT 0 COMMENT '是否被拦截',
    block_reason            VARCHAR(256)  DEFAULT NULL COMMENT '拦截原因',
    created_at              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    -- 组合索引策略（替换原 7 个单列索引，降低高 QPS 下的写放大）：
    -- 高频查询模式均为「过滤列 + request_time 范围/排序」，组合索引一次覆盖；
    -- is_rate_limited / is_blocked 值域仅 0/1（选择性≈0），单列索引基本不被优化器使用，已移除。
    KEY idx_log_time (request_time),
    KEY idx_log_app_time (app_id, request_time),
    KEY idx_log_iface_time (interface_id, request_time),
    KEY idx_log_ip_time (client_ip, request_time),
    KEY idx_log_status_time (response_status, request_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用日志表';

-- ============================================================
-- 10. IP封禁表（ip_ban）
--    用途：恶意来源IP封禁记录，支持全局封禁（app_id=NULL）与应用级封禁
-- ============================================================
CREATE TABLE ip_ban (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    ip_address      VARCHAR(64)   NOT NULL COMMENT 'IP地址',
    app_id          BIGINT        DEFAULT NULL COMMENT 'NULL=全局封禁',
    ban_reason      VARCHAR(512)  NOT NULL COMMENT '封禁原因',
    ban_start_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ban_end_time    DATETIME      NOT NULL COMMENT '封禁结束时间',
    ban_status      TINYINT       NOT NULL DEFAULT 1 COMMENT '1=封禁中, 0=已解封',
    ban_type        VARCHAR(20)   NOT NULL COMMENT 'MANUAL=手动, AUTO=自动',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ban_ip (ip_address),
    KEY idx_ban_app (app_id),
    KEY idx_ban_status (ban_status),
    KEY idx_ban_end (ban_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP封禁表';

-- ============================================================
-- 11. 安全事件表（security_event）
--    用途：安全检测规则触发后产生的告警/拦截事件，供安全审计跟进处置
-- ============================================================
CREATE TABLE security_event (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(50)   NOT NULL COMMENT 'HIGH_FREQUENCY/ABNORMAL_TIME/AUTH_FAIL/ABNORMAL_PARAM/PERMISSION_BREACH',
    event_desc      VARCHAR(512)  NOT NULL COMMENT '事件描述',
    app_id          BIGINT        DEFAULT NULL,
    app_name        VARCHAR(128)  DEFAULT NULL,
    client_ip       VARCHAR(64)   DEFAULT NULL,
    trigger_rule    VARCHAR(256)  DEFAULT NULL COMMENT '触发规则',
    handle_status   TINYINT       NOT NULL DEFAULT 0 COMMENT '0=待处理, 1=已处理, 2=已忽略',
    handle_remark   VARCHAR(512)  DEFAULT NULL,
    occurred_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    handled_at      DATETIME      DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_event_type (event_type),
    KEY idx_event_status (handle_status),
    KEY idx_event_time (occurred_at),
    KEY idx_event_app (app_id),
    KEY idx_event_ip (client_ip)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全事件表';

-- ============================================================
-- 12. 安全检测规则配置表（security_rule）
--    用途：安全防护规则引擎配置，定义检测规则及触发后的处置动作
-- ============================================================
CREATE TABLE security_rule (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    rule_name       VARCHAR(128)  NOT NULL COMMENT '规则名称',
    rule_type       VARCHAR(50)   NOT NULL COMMENT '规则类型',
    rule_config     TEXT          NOT NULL COMMENT 'JSON配置',
    trigger_action  VARCHAR(50)   NOT NULL COMMENT 'ALERT/AUTO_BAN/ALERT_AND_BAN/BLOCK',
    ban_duration_min INT          DEFAULT NULL COMMENT '自动封禁时长(分钟)',
    enabled         TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
    description     VARCHAR(512)  DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='安全检测规则配置表';

-- 初始化默认安全规则
INSERT INTO security_rule (rule_name, rule_type, rule_config, trigger_action, ban_duration_min, enabled, description) VALUES
('高频调用检测', 'HIGH_FREQUENCY', '{"qps_threshold":100,"duration_sec":30}', 'ALERT', NULL, 1, '单应用QPS超过100持续30秒'),
('异常时段调用检测', 'ABNORMAL_TIME', '{"start_time":"23:00","end_time":"06:00"}', 'ALERT', NULL, 1, '非工作时间(23:00-06:00)的调用'),
('连续鉴权失败检测', 'AUTH_FAIL', '{"time_window_min":5,"fail_count":10}', 'AUTO_BAN', 60, 1, '5分钟内连续鉴权失败10次,自动封禁1小时'),
('异常入参检测', 'ABNORMAL_PARAM', '{"max_payload_kb":1024,"check_sql_injection":true,"check_path_traversal":true}', 'BLOCK', NULL, 1, 'SQL注入/路径穿越/超大payload'),
('权限越界检测', 'PERMISSION_BREACH', '{"time_window_min":10,"attempt_count":20}', 'ALERT', NULL, 1, '10分钟内尝试未授权接口超过20次');

-- ============================================================
-- 13. 系统用户表（sys_user）
--    用途：管理后台登录账号，密码采用 BCrypt 加密存储
-- ============================================================
CREATE TABLE sys_user (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)   NOT NULL COMMENT '账号',
    password        VARCHAR(256)  NOT NULL COMMENT 'BCrypt加密',
    real_name       VARCHAR(64)   DEFAULT NULL,
    email           VARCHAR(128)  DEFAULT NULL,
    phone           VARCHAR(20)   DEFAULT NULL,
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=启用, 0=停用',
    last_login_at   DATETIME      DEFAULT NULL,
    last_login_ip   VARCHAR(64)   DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 默认管理员账号（账号 admin / 密码 admin123）
INSERT INTO sys_user (username, password, real_name, status) VALUES
('admin', '$2b$10$1REIS.9.l6F3VtI2SI0DLe.aVWREj4//wUEZKF/9gE3oK0B9zBtTa', '系统管理员', 1);

-- ============================================================
-- 14. 角色表（sys_role）
--    用途：系统角色定义，通过 sys_user_role 与用户建立多对多关联
-- ============================================================
CREATE TABLE sys_role (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    role_name       VARCHAR(64)   NOT NULL COMMENT '角色名称',
    role_code       VARCHAR(64)   NOT NULL COMMENT '角色编码',
    description     VARCHAR(256)  DEFAULT NULL,
    status          TINYINT       NOT NULL DEFAULT 1,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_name (role_name),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

INSERT INTO sys_role (role_name, role_code, description) VALUES
('超级管理员', 'SUPER_ADMIN', '拥有全部权限'),
('运维人员', 'OPERATOR', '应用管理、接口管理、日志查看'),
('安全审计', 'SECURITY_AUDITOR', '安全防护、日志审计、只读');

-- ============================================================
-- 15. 用户角色关联表（sys_user_role）
--    用途：用户与角色的多对多关联中间表
-- ============================================================
CREATE TABLE sys_user_role (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    role_id         BIGINT        NOT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- 默认管理员分配超级管理员角色
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- ============================================================
-- 16. 操作审计日志表（sys_operation_log）
--    用途：管理后台操作审计记录，用于安全审计与责任追溯
-- ============================================================
CREATE TABLE sys_operation_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    operator_id     BIGINT        DEFAULT NULL,
    operator_name   VARCHAR(64)   DEFAULT NULL,
    operation_type  VARCHAR(50)   NOT NULL COMMENT 'CREATE/UPDATE/DELETE/LOGIN/LOGOUT',
    operation_module VARCHAR(50)  DEFAULT NULL COMMENT 'APP/INTERFACE/PERMISSION/SECURITY/SYSTEM',
    operation_desc  VARCHAR(512)  DEFAULT NULL,
    request_method  VARCHAR(10)   DEFAULT NULL,
    request_url     VARCHAR(512)  DEFAULT NULL,
    request_params  TEXT          DEFAULT NULL,
    client_ip       VARCHAR(64)   DEFAULT NULL,
    cost_time       INT           DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_oplog_operator (operator_id),
    KEY idx_oplog_type (operation_type),
    KEY idx_oplog_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志表';

-- ============================================================
-- 17. 告警表（alert）
--    用途：网关运行态运营告警中心，覆盖网关内部错误、限流、自动封禁、异常入参等，
--          带等级（INFO/WARNING/CRITICAL）与已读/未读状态，供运维人员在顶栏铃铛实时查看
-- ============================================================
CREATE TABLE alert (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    title            VARCHAR(128)  NOT NULL COMMENT '告警标题',
    level            VARCHAR(20)   NOT NULL COMMENT 'INFO/WARNING/CRITICAL',
    source           VARCHAR(30)   NOT NULL COMMENT 'GATEWAY/SECURITY/RATE_LIMIT/SYSTEM',
    content          VARCHAR(512)  DEFAULT NULL COMMENT '告警详细内容',
    related_app_id   BIGINT        DEFAULT NULL COMMENT '关联应用ID',
    related_app_name VARCHAR(128)  DEFAULT NULL COMMENT '关联应用名（冗余）',
    related_ip       VARCHAR(64)   DEFAULT NULL COMMENT '关联客户端IP',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0=未读,1=已读,2=已处理,3=已忽略',
    handle_remark    VARCHAR(512)  DEFAULT NULL COMMENT '处理备注',
    occurred_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '告警发生时间',
    read_at          DATETIME      DEFAULT NULL COMMENT '已读时间',
    handled_at       DATETIME      DEFAULT NULL COMMENT '处理时间',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_alert_level (level),
    KEY idx_alert_source (source),
    KEY idx_alert_status (status),
    KEY idx_alert_time (occurred_at),
    KEY idx_alert_app (related_app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警表';

-- ============================================================
-- 导出任务表（异步下载中心：日志导出任务的状态与文件登记）
-- ============================================================
CREATE TABLE IF NOT EXISTS export_task (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type        VARCHAR(32)  NOT NULL COMMENT '导出类型: CALL_LOG',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    file_name   VARCHAR(128) DEFAULT NULL COMMENT '下载文件名',
    file_path   VARCHAR(256) DEFAULT NULL COMMENT '服务器文件相对路径（相对导出目录）',
    total_rows  BIGINT       NOT NULL DEFAULT 0 COMMENT '导出行数',
    error_msg   VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
    created_by  VARCHAR(64)  DEFAULT NULL COMMENT '创建人（登录用户名）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finished_at DATETIME     DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (id),
    KEY idx_export_status (status),
    KEY idx_export_created (created_at),
    KEY idx_export_creator (created_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出任务表';

-- ============================================================
-- 完成提示
-- ============================================================
-- 1. 默认管理员：admin / admin123
-- 2. 调用日志表数据量大时按月归档/清理（可定时任务迁移历史数据）
-- 3. 应用层配置正确的 MySQL 连接信息（application.yml）
-- ============================================================
