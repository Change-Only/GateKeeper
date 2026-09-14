-- ============================================================
-- GateKeeper API 网关管理系统 - 数据库初始化脚本
-- Database: MySQL 8.x
-- Encoding: utf8mb4
-- 最后更新: 2026-09-13
-- ============================================================
--
-- 说明：
--   1. 本脚本用于【全新初始化】：建库 + 建表 + 写入基础种子数据。
--      含基础种子数据（系统用户/角色/菜单权限/字典/系统配置/数据权限）
--      以及 PRD 指定的 7 条初始化告警规则（alarm_rule，T11 起随本脚本落地）；
--      不含任何应用、接口、调用日志等业务/运行数据。
--   2. 表结构以线上库为唯一事实来源（SHOW CREATE TABLE 逐字导出），
--      共 36 张表；表顺序按外键依赖拓扑排列（父表在前）。
--   3. 可重复执行（幂等）：
--      - 建表：CREATE TABLE IF NOT EXISTS；
--      - 系统类种子：INSERT ... ON DUPLICATE KEY UPDATE（按主键/唯一键覆盖），
--        重复执行不报错且结果一致；不使用 DELETE，避免级联误伤关联数据；
--      - 业务类种子（告警规则）：INSERT IGNORE —— 不覆盖运营在页面上的修改。
--   4. 如需彻底重置，请手动取消下面 DROP DATABASE 的注释后执行：
--      -- DROP DATABASE IF EXISTS `gatekeeper`;
--
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `gatekeeper` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `gatekeeper`;

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 表结构（共 36 张）
-- ============================================================

-- ============================================================
-- 1. 告警规则表（alarm_rule）
--    用途：T04-C 告警域规则配置，按告警类型/阈值/统计窗口定义触发条件，并绑定通知渠道与接收人
-- ============================================================
CREATE TABLE IF NOT EXISTS `alarm_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `rule_name` varchar(128) NOT NULL COMMENT '规则名称（原型 ruleName）',
  `alarm_type` varchar(32) NOT NULL COMMENT 'FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE（原型 alarmType）',
  `scope_type` tinyint NOT NULL DEFAULT '1' COMMENT '1=按对象(应用/接口),2=平台全局（原型 scopeType，语义为推断）',
  `target_type` varchar(16) DEFAULT NULL COMMENT 'T11：评估对象维度 APP=按应用 / API=按接口；scope_type=1 时必填，scope_type=2 时为 NULL',
  `target_ids` varchar(512) DEFAULT NULL COMMENT 'T11：评估对象ID，逗号分隔（同 channel_ids 约定）；NULL/空=该维度下全部对象',
  `threshold` varchar(64) NOT NULL COMMENT '阈值表达式 如 >5 / >200%基线 / 提前30天（原型 threshold）',
  `time_window` int NOT NULL DEFAULT '5' COMMENT '统计窗口(分钟)（原型 timeWindow）',
  `alarm_level` tinyint NOT NULL DEFAULT '2' COMMENT '1=提示,2=警告,3=严重（原型 alarmLevel / dict alarm_level）',
  `silence_period` int NOT NULL DEFAULT '30' COMMENT '静默期(分钟)（原型 silencePeriod）',
  `channel_ids` varchar(255) DEFAULT NULL COMMENT '通知渠道ID，逗号分隔（原型 channelNames 归一化）',
  `receiver_scope` varchar(32) DEFAULT NULL COMMENT 'ASSIGNEE=对象负责人,USER=指定用户,ROLE=指定角色（推断）',
  `receiver_ids` varchar(255) DEFAULT NULL COMMENT '接收人ID，逗号分隔（原型 receiverNames 归一化）',
  `receiver_desc` varchar(255) DEFAULT NULL COMMENT '接收人描述，如 各应用负责人（原型 receiverNames 中的动态分组文案）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（原型 status）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_alarmrule_type` (`alarm_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警规则表';

-- ============================================================
-- 2. 告警表（alert）
--    用途：网关运行态运营告警中心，覆盖网关内部错误、限流、自动封禁、异常入参等，带等级（INFO/WARNING/CRITICAL）与已读/未读状态，供运维人员在顶栏铃铛实时查看
-- ============================================================
CREATE TABLE IF NOT EXISTS `alert` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL COMMENT '告警标题',
  `level` varchar(20) NOT NULL COMMENT 'INFO/WARNING/CRITICAL',
  `source` varchar(30) NOT NULL COMMENT 'GATEWAY/SECURITY/RATE_LIMIT/SYSTEM',
  `content` varchar(512) DEFAULT NULL COMMENT '告警详细内容',
  `related_app_id` bigint DEFAULT NULL COMMENT '关联应用ID',
  `related_app_name` varchar(128) DEFAULT NULL COMMENT '关联应用名（冗余）',
  `related_ip` varchar(64) DEFAULT NULL COMMENT '关联客户端IP',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=未读,1=已读,2=已处理,3=已忽略',
  `handle_remark` varchar(512) DEFAULT NULL COMMENT '处理备注',
  `occurred_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '告警发生时间',
  `read_at` datetime DEFAULT NULL COMMENT '已读时间',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `rule_id` bigint DEFAULT NULL COMMENT '触发的告警规则ID（原型 ruleId）',
  `rule_name` varchar(128) DEFAULT NULL COMMENT '告警规则名称（原型 ruleName）',
  `alarm_type` varchar(32) DEFAULT NULL COMMENT 'FAIL_RATE/AUTH_FAIL/QUOTA_USAGE/AVG_LATENCY/KEY_EXPIRE/ZOMBIE_API/QPS_SURGE（推断，冗余自 alarm_rule）',
  `alarm_level` tinyint DEFAULT NULL COMMENT '【T09 起废弃·死列】无代码写入；实际告警等级用 level VARCHAR（INFO/WARNING/CRITICAL）。勿新增消费方，如需启用须先出评审',
  `scope_desc` varchar(255) DEFAULT NULL COMMENT '告警范围描述（原型 scopeDesc）',
  `trigger_value` varchar(64) DEFAULT NULL COMMENT '触发值 如 8.2%（原型 triggerValue）',
  `env_code` varchar(32) DEFAULT NULL COMMENT '环境编码（推断，告警按环境隔离）',
  `related_api_id` bigint DEFAULT NULL COMMENT '关联接口ID（推断）',
  `handler_name` varchar(64) DEFAULT NULL COMMENT '处理人姓名（原型 handlerName）',
  `notify_status` tinyint NOT NULL DEFAULT '0' COMMENT '0=未通知,1=已通知,2=通知失败（推断）',
  PRIMARY KEY (`id`),
  KEY `idx_alert_level` (`level`),
  KEY `idx_alert_source` (`source`),
  KEY `idx_alert_status` (`status`),
  KEY `idx_alert_time` (`occurred_at`),
  KEY `idx_alert_app` (`related_app_id`),
  KEY `idx_alert_rule` (`rule_id`),
  KEY `idx_alert_type_level` (`alarm_type`,`alarm_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='告警表';

-- ============================================================
-- 3. 调用日志表（api_call_log）
--    用途：网关转发的调用记录，数据量大时按月归档/清理，不物理分区
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_call_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` bigint DEFAULT NULL COMMENT '应用ID',
  `app_name` varchar(128) DEFAULT NULL COMMENT '应用名（冗余）',
  `interface_id` bigint DEFAULT NULL COMMENT '接口ID',
  `interface_path` varchar(256) DEFAULT NULL COMMENT '接口路径',
  `request_method` varchar(10) DEFAULT NULL,
  `request_time` datetime NOT NULL COMMENT '请求时间',
  `request_params` text COMMENT '入参(加密接口存密文)',
  `response_data` text COMMENT '响应(加密接口存密文)',
  `response_status` int DEFAULT NULL COMMENT '状态码',
  `cost_time` int DEFAULT NULL COMMENT '耗时(ms)',
  `client_ip` varchar(64) DEFAULT NULL COMMENT '调用方IP',
  `encryption_algorithm` varchar(20) DEFAULT 'NONE' COMMENT '加密算法',
  `is_rate_limited` tinyint(1) DEFAULT '0' COMMENT '是否被限流',
  `is_blocked` tinyint(1) DEFAULT '0' COMMENT '是否被拦截',
  `block_reason` varchar(256) DEFAULT NULL COMMENT '拦截原因',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `trace_id` varchar(64) DEFAULT NULL COMMENT '链路追踪ID（原型 traceId）',
  `env_code` varchar(32) DEFAULT NULL COMMENT '环境编码（原型 envCode）',
  `app_key` varchar(64) DEFAULT NULL COMMENT '调用使用的AppKey（推断：密钥轮换后仍可追溯，非冗余）',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码 API_NOT_AUTHORIZED/RATE_LIMIT_EXCEEDED/...（原型 errorCode）',
  `reject_stage` varchar(32) DEFAULT NULL COMMENT '拒绝阶段 APPKEY/SIGNATURE/NONCE/ACL/RATELIMIT/UPSTREAM（原型 rejectStage）',
  `auth_cost` int DEFAULT NULL COMMENT '鉴权耗时(ms)（原型 authCost）',
  `upstream_cost` int DEFAULT NULL COMMENT '后端耗时(ms)（原型 upstreamCost）',
  `api_version` varchar(20) DEFAULT NULL COMMENT '命中的接口版本（推断：灰度排障需要）',
  PRIMARY KEY (`id`),
  KEY `idx_log_app` (`app_id`),
  KEY `idx_log_iface` (`interface_id`),
  KEY `idx_log_time` (`request_time`),
  KEY `idx_log_status` (`response_status`),
  KEY `idx_log_ip` (`client_ip`),
  KEY `idx_log_rate` (`is_rate_limited`),
  KEY `idx_log_block` (`is_blocked`),
  KEY `idx_log_env_time` (`env_code`,`request_time`),
  KEY `idx_log_trace` (`trace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调用日志表';

-- ============================================================
-- 4. 接口变更历史表（api_change_log）
--    用途：记录接口的创建/更新/发布/下线/删除等变更轨迹，支持字段级变更前后值对比与审计追溯
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_change_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `api_id` bigint NOT NULL COMMENT '接口ID（原型 apiId）',
  `change_type` varchar(20) NOT NULL COMMENT 'CREATE/UPDATE/PUBLISH/OFFLINE/DELETE（原型 changeType）',
  `field_name` varchar(128) DEFAULT NULL COMMENT '变更字段名（原型 fieldName）',
  `field_label` varchar(128) DEFAULT NULL COMMENT '变更字段中文名（原型 fieldLabel）',
  `old_value` varchar(1024) DEFAULT NULL COMMENT '变更前值（原型 oldValue）',
  `new_value` varchar(1024) DEFAULT NULL COMMENT '变更后值（原型 newValue）',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID（推断）',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人姓名（原型 operatorName）',
  `change_reason` varchar(512) DEFAULT NULL COMMENT '变更原因（原型 changeReason）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间（原型 createTime）',
  PRIMARY KEY (`id`),
  KEY `idx_acl_api_time` (`api_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口变更历史表';

-- ============================================================
-- 5. 接口环境配置表（api_env_config）
--    用途：接口在各环境（dev/test/pre/prod）下的后端上游地址与超时/重试配置，可按版本灰度设置独立上游
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_env_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `api_id` bigint NOT NULL COMMENT '接口ID（原型 apiId）',
  `env_code` varchar(32) NOT NULL COMMENT '环境编码（原型 envCode）',
  `version` varchar(20) DEFAULT NULL COMMENT '版本号，NULL=所有版本通用（推断：支撑灰度版本独立上游）',
  `upstream_url` varchar(512) NOT NULL COMMENT '后端服务地址（原型 upstreamUrl）',
  `connect_timeout` int NOT NULL DEFAULT '1000' COMMENT '连接超时(ms)（原型 connectTimeout）',
  `read_timeout` int NOT NULL DEFAULT '3000' COMMENT '读取超时(ms)（原型 readTimeout）',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数（原型 retryCount）',
  `mock_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '1=开启Mock,0=关闭（原型 mockEnabled）',
  `config_status` tinyint NOT NULL DEFAULT '1' COMMENT '1=已配置,2=未配置（原型 configStatus）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_env_ver` (`api_id`,`env_code`,`version`),
  KEY `idx_aec_api` (`api_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口环境配置表';

-- ============================================================
-- 6. 接口分组表（api_group）
--    用途：接口分组管理，通过 parent_id 自关联支持树形多层级
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_group` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `group_name` varchar(128) NOT NULL COMMENT '分组名称',
  `parent_id` bigint DEFAULT NULL COMMENT '父分组ID，NULL=顶级分组',
  `description` varchar(512) DEFAULT NULL COMMENT '描述',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `group_code` varchar(64) DEFAULT NULL COMMENT '分组编码（原型 groupCode）',
  `line_id` bigint DEFAULT NULL COMMENT '业务线ID（原型 lineId）',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人用户ID（推断）',
  `owner_name` varchar(64) DEFAULT NULL COMMENT '负责人姓名（原型 ownerName）',
  `api_count` int NOT NULL DEFAULT '0' COMMENT '接口数量（原型 apiCount，统计冗余）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（原型 status）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_code` (`group_code`),
  KEY `idx_group_parent` (`parent_id`),
  KEY `idx_group_line` (`line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口分组表（支持多层级）';

-- ============================================================
-- 7. 接口参数定义表（api_param）
--    用途：接口入参/出参/错误码的字段定义（含嵌套层级、必填、示例、敏感与加解密规则），支撑接口文档与调试
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_param` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `api_id` bigint NOT NULL COMMENT '接口ID（原型 apiId）',
  `param_type` tinyint NOT NULL COMMENT '1=HEADER,2=QUERY,3=BODY,4=RESPONSE,5=ERROR_CODE（原型 paramType）',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父级参数ID，0=顶层，支持嵌套（原型 parentId）',
  `field_name` varchar(128) NOT NULL COMMENT '字段名 / 错误码KEY（原型 fieldName）',
  `field_type` varchar(32) DEFAULT NULL COMMENT 'string/int/number/array/object/bool（原型 fieldType）',
  `required` tinyint NOT NULL DEFAULT '0' COMMENT '1=必填,0=选填（原型 required）',
  `example` varchar(512) DEFAULT NULL COMMENT '示例值（原型 example）',
  `error_code` varchar(64) DEFAULT NULL COMMENT '错误码，param_type=5 时有效（原型 errorCode）',
  `http_status` int DEFAULT NULL COMMENT 'HTTP状态码，param_type=5 时有效（原型 httpStatus）',
  `sensitive` tinyint NOT NULL DEFAULT '0' COMMENT '1=敏感字段,0=否（原型 sensitive）',
  `encrypt_rule` varchar(32) DEFAULT NULL COMMENT '加解密/脱敏规则 SYMMETRIC/MASK/NONE（原型 encryptRule）',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序（推断）',
  `description` varchar(512) DEFAULT NULL COMMENT '字段说明（原型 desc）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_param_api` (`api_id`,`param_type`),
  KEY `idx_param_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口参数定义表';

-- ============================================================
-- 8. 接口版本表（api_version）
--    用途：接口的多版本管理，记录版本状态（生效/弃用/下线）、当前默认版本、灰度比例与下线计划
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `api_id` bigint NOT NULL COMMENT '接口ID（原型 apiId）',
  `version` varchar(20) NOT NULL COMMENT '版本号 v1/v2（原型 version）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=生效中,2=已弃用,3=已下线（原型 status）',
  `is_current` tinyint NOT NULL DEFAULT '0' COMMENT '1=当前默认版本,0=非默认（原型 isCurrent）',
  `gray_ratio` int NOT NULL DEFAULT '0' COMMENT '灰度流量百分比 0-100（原型 grayRatio）',
  `change_log` varchar(512) DEFAULT NULL COMMENT '版本变更说明（原型 changeLog）',
  `deprecate_time` date DEFAULT NULL COMMENT '弃用时间（原型 deprecateTime）',
  `offline_plan_time` date DEFAULT NULL COMMENT '计划下线时间（原型 offlinePlanTime）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_version_api` (`api_id`,`version`),
  KEY `idx_version_current` (`api_id`,`is_current`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口版本表';

-- ============================================================
-- 9. 应用表（app）
--    用途：应用接入方信息，存储 AppKey/AppSecret/状态/描述/到期时间，网关鉴权核心表
-- ============================================================
CREATE TABLE IF NOT EXISTS `app` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_name` varchar(128) NOT NULL COMMENT '应用名称',
  `app_key` varchar(64) NOT NULL COMMENT 'AppKey',
  `app_secret` varchar(256) NOT NULL COMMENT 'AppSecret（AES加密存储）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用, 0=停用, 2=已过期',
  `description` varchar(512) DEFAULT NULL COMMENT '描述',
  `expire_time` datetime DEFAULT NULL COMMENT '到期时间，NULL=永不过期',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `app_code` varchar(64) DEFAULT NULL COMMENT '应用编码（原型 appCode）',
  `line_id` bigint DEFAULT NULL COMMENT '业务线ID（原型 lineId）',
  `app_type` tinyint NOT NULL DEFAULT '1' COMMENT '1=内部系统,2=外部合作方,3=测试应用（原型 appType / dict app_type）',
  `env_scope` varchar(64) DEFAULT NULL COMMENT '可用环境编码，逗号分隔 如 dev,test,prod（原型 envScope）',
  `owner_name` varchar(64) DEFAULT NULL COMMENT '负责人姓名（原型 ownerName）',
  `contact_name` varchar(64) DEFAULT NULL COMMENT '联系人（原型 contactName）',
  `contact_info` varchar(128) DEFAULT NULL COMMENT '联系方式（原型 contactInfo）',
  `approval_required` tinyint NOT NULL DEFAULT '0' COMMENT '1=授权需审批,0=免审批（原型 approvalRequired）',
  `audit_status` tinyint NOT NULL DEFAULT '1' COMMENT '【T09 核实：预留列】语义 0=待审核,1=已通过；前后端零消费方、未接任何字典/UI；启用前须先补齐字典与前后端链路',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_key` (`app_key`),
  UNIQUE KEY `uk_app_code` (`app_code`),
  KEY `idx_app_status` (`status`),
  KEY `idx_app_expire` (`expire_time`),
  KEY `idx_app_line` (`line_id`),
  KEY `idx_app_audit` (`audit_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用表';

-- ============================================================
-- 10. 应用接口授权表（app_api_grant）
--    用途：应用对接口的授权申请与审批记录，含授权QPS/日配额、有效期、审批流与撤销原因
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_api_grant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` bigint NOT NULL COMMENT '应用ID（原型 appId）',
  `api_id` bigint NOT NULL COMMENT '接口ID（原型 apiId）',
  `env_code` varchar(32) NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
  `qps_limit` int NOT NULL DEFAULT '0' COMMENT '授权QPS，0=不限（原型 qpsLimit）',
  `daily_quota` bigint NOT NULL DEFAULT '0' COMMENT '授权日配额，0=不限（原型 dailyQuota）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=待审批,1=已生效,2=已过期,3=已撤销,4=已驳回（原型 status / dict grant_status）',
  `valid_from` date DEFAULT NULL COMMENT '生效日期（原型 validFrom）',
  `valid_to` date DEFAULT NULL COMMENT '失效日期（原型 validTo）',
  `grant_reason` varchar(512) DEFAULT NULL COMMENT '申请理由（原型 grantReason）',
  `applicant_id` bigint DEFAULT NULL COMMENT '申请人ID（推断）',
  `applicant_name` varchar(64) DEFAULT NULL COMMENT '申请人姓名（原型 applicantName）',
  `auditor_id` bigint DEFAULT NULL COMMENT '审批人ID（推断）',
  `auditor_name` varchar(64) DEFAULT NULL COMMENT '审批人姓名（原型 auditorName）',
  `audit_time` datetime DEFAULT NULL COMMENT '审批时间（原型 auditTime）',
  `audit_remark` varchar(512) DEFAULT NULL COMMENT '审批意见（推断）',
  `revoke_reason` varchar(512) DEFAULT NULL COMMENT '撤销原因（推断）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grant_app_api_env` (`app_id`,`api_id`,`env_code`),
  KEY `idx_grant_api` (`api_id`),
  KEY `idx_grant_status` (`status`,`valid_to`),
  KEY `idx_grant_env` (`env_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用接口授权表（含审批流与有效期）';

-- ============================================================
-- 11. 应用凭证表（app_credential）
--    用途：应用在各环境下的 AppKey/AppSecret 凭证，支持多凭证、轮换标记、有效期与最近使用信息
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_credential` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` bigint NOT NULL COMMENT '应用ID（原型 appId）',
  `app_key` varchar(64) NOT NULL COMMENT 'AppKey（原型 appKey）',
  `app_secret` varchar(256) NOT NULL COMMENT 'AppSecret（AES加密存储；原型仅展示 secretMask）',
  `secret_mask` varchar(64) DEFAULT NULL COMMENT '密钥掩码展示 如 Yk3m****J5sU（原型 secretMask）',
  `alias` varchar(128) DEFAULT NULL COMMENT '密钥别名（原型 alias）',
  `env_code` varchar(32) NOT NULL DEFAULT 'prod' COMMENT '所属环境编码（原型 envCode）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用中,2=已停用,3=已吊销,4=已过期（原型 status / dict cred_status）',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间，NULL=永不过期（原型 expireTime）',
  `last_used_time` datetime DEFAULT NULL COMMENT '最近使用时间（原型 lastUsedTime）',
  `last_used_ip` varchar(64) DEFAULT NULL COMMENT '最近使用IP（原型 lastUsedIp）',
  `rotate_flag` tinyint NOT NULL DEFAULT '0' COMMENT '1=轮换中的新密钥,0=常规（原型 rotateFlag）',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（推断）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（原型 createTime）',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cred_key` (`app_key`),
  KEY `idx_cred_app_env` (`app_id`,`env_code`),
  KEY `idx_cred_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用凭证表';

-- ============================================================
-- 12. 应用配额表（app_quota）
--    用途：应用按环境的调用配额上限（全局QPS/日/月调用量/并发），0=不限
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_quota` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` bigint NOT NULL COMMENT '应用ID（原型 appId）',
  `env_code` varchar(32) NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
  `global_qps` int NOT NULL DEFAULT '0' COMMENT '全局QPS上限，0=不限（原型 globalQps）',
  `daily_quota` bigint NOT NULL DEFAULT '0' COMMENT '日调用配额，0=不限（原型 dailyQuota）',
  `monthly_quota` bigint NOT NULL DEFAULT '0' COMMENT '月调用配额，0=不限（原型 monthlyQuota）',
  `concurrency` int NOT NULL DEFAULT '0' COMMENT '并发上限，0=不限（原型 concurrency）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quota_app_env` (`app_id`,`env_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用配额表（按环境）';

-- ============================================================
-- 13. 业务线表（biz_line）
--    用途：业务线主数据，用于应用归属与数据权限（datascope）划分，含负责人与成员数冗余
-- ============================================================
CREATE TABLE IF NOT EXISTS `biz_line` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '业务线ID',
  `line_code` varchar(64) NOT NULL COMMENT '业务线编码（原型 lineCode）',
  `line_name` varchar(128) NOT NULL COMMENT '业务线名称（原型 lineName）',
  `owner_name` varchar(64) DEFAULT NULL COMMENT '负责人姓名（原型 ownerName）',
  `member_count` int NOT NULL DEFAULT '0' COMMENT '成员数量（原型 memberCount，统计冗余）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用, 0=停用（原型 status）',
  `remark` varchar(512) DEFAULT NULL COMMENT '备注（原型 remark）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bizline_code` (`line_code`),
  KEY `idx_bizline_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务线表';

-- ============================================================
-- 14. 动态封禁规则表（block_rule）
--    用途：自动/人工封禁策略配置，按来源IP或应用维度定义触发原因、阈值窗口与封禁时长
-- ============================================================
CREATE TABLE IF NOT EXISTS `block_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scope` varchar(16) NOT NULL COMMENT 'IP=按来源IP, APP=按应用/AppKey（原型 scope）',
  `reason_code` varchar(40) NOT NULL COMMENT 'REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL（原型 reasonCode）',
  `threshold_desc` varchar(128) DEFAULT NULL COMMENT '阈值描述 如 同IP 5min ≥ 20次（原型 threshold）',
  `threshold_count` int NOT NULL DEFAULT '0' COMMENT '窗口内触发次数阈值（推断：由 threshold 文本结构化）',
  `window_minutes` int NOT NULL DEFAULT '5' COMMENT '统计窗口(分钟)（推断：由 threshold 文本结构化）',
  `ttl_seconds` int NOT NULL DEFAULT '0' COMMENT '封禁时长(秒)，0=永久（原型 ttl 结构化）',
  `auto_block` tinyint NOT NULL DEFAULT '1' COMMENT '1=自动封禁,0=人工触发（原型 auto）',
  `enabled` tinyint NOT NULL DEFAULT '0' COMMENT '1=启用,0=停用（原型 enabled）',
  `description` varchar(255) DEFAULT NULL COMMENT '规则说明（原型 desc）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blockrule` (`scope`,`reason_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态封禁规则表';

-- ============================================================
-- 15. 环境表（env）
--    用途：环境主数据（dev/test/pre/prod），定义环境编码、名称、网关入口地址与排序，供接口环境配置与数据权限引用
-- ============================================================
CREATE TABLE IF NOT EXISTS `env` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '环境ID',
  `env_code` varchar(32) NOT NULL COMMENT '环境编码 dev/test/pre/prod，创建后不可修改（原型 envCode）',
  `env_name` varchar(64) NOT NULL COMMENT '环境名称（原型 envName）',
  `gateway_url` varchar(256) DEFAULT NULL COMMENT '该环境网关入口地址（原型 gatewayUrl）',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序（原型 sort）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用, 0=停用（原型 status）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_env_code` (`env_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='环境表';

-- ============================================================
-- 16. 导出任务表（export_task）
--    用途：异步下载中心：日志导出任务的状态与文件登记
-- ============================================================
CREATE TABLE IF NOT EXISTS `export_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `type` varchar(32) NOT NULL COMMENT '导出类型: CALL_LOG',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
  `file_name` varchar(128) DEFAULT NULL COMMENT '下载文件名',
  `file_path` varchar(256) DEFAULT NULL COMMENT '服务器文件相对路径（相对导出目录）',
  `total_rows` bigint NOT NULL DEFAULT '0' COMMENT '导出行数',
  `error_msg` varchar(512) DEFAULT NULL COMMENT '失败原因',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建人（登录用户名）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `finished_at` datetime DEFAULT NULL COMMENT '完成时间',
  PRIMARY KEY (`id`),
  KEY `idx_export_status` (`status`),
  KEY `idx_export_created` (`created_at`),
  KEY `idx_export_creator` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导出任务表';

-- ============================================================
-- 17. IP封禁表（ip_ban）
--    用途：恶意来源IP封禁记录，支持全局封禁（app_id=NULL）与应用级封禁
-- ============================================================
CREATE TABLE IF NOT EXISTS `ip_ban` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ip_address` varchar(64) DEFAULT NULL COMMENT 'IP地址（scope=IP 时必填，与 target 同值；scope=APP 时为NULL）',
  `app_id` bigint DEFAULT NULL COMMENT 'NULL=全局封禁',
  `ban_reason` varchar(512) NOT NULL COMMENT '封禁原因',
  `ban_start_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `ban_end_time` datetime NOT NULL COMMENT '封禁结束时间',
  `ban_status` tinyint NOT NULL DEFAULT '1' COMMENT '1=封禁中, 0=已解封',
  `ban_type` varchar(20) NOT NULL COMMENT 'MANUAL=手动, AUTO=自动',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `scope` varchar(16) NOT NULL DEFAULT 'IP' COMMENT 'IP=来源IP/CIDR, APP=应用或AppKey（原型 scope）',
  `target` varchar(128) DEFAULT NULL COMMENT '封禁对象：IP/CIDR/AppKey/AppId（原型 target）；scope=IP 时与 ip_address 同值',
  `env_code` varchar(32) NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
  `reason_code` varchar(40) DEFAULT NULL COMMENT 'REPLAY_ATTACK/SIGNATURE_MISMATCH/RATE_LIMIT_EXCEEDED/IP_NOT_ALLOWED/MANUAL（原型 reasonCode）',
  `block_times` int NOT NULL DEFAULT '1' COMMENT '累计封禁次数（原型 blockTimes）',
  `ttl_seconds` int NOT NULL DEFAULT '0' COMMENT '封禁时长(秒)，0=永久（原型 ttlSeconds）',
  `created_by` bigint NOT NULL DEFAULT '0' COMMENT '封禁操作人，0=系统自动（原型 blockBy）',
  `unblock_by` bigint DEFAULT NULL COMMENT '解封操作人（原型 unblockBy）',
  `unblock_reason` varchar(512) DEFAULT NULL COMMENT '解封原因（原型 unblockReason）',
  `unblock_time` datetime DEFAULT NULL COMMENT '解封时间（原型 unblockTime）',
  PRIMARY KEY (`id`),
  KEY `idx_ban_ip` (`ip_address`),
  KEY `idx_ban_app` (`app_id`),
  KEY `idx_ban_status` (`ban_status`),
  KEY `idx_ban_end` (`ban_end_time`),
  KEY `idx_ban_target` (`target`),
  KEY `idx_ban_scope_env` (`scope`,`env_code`,`ban_status`),
  KEY `idx_ban_reason` (`reason_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IP封禁表';

-- ============================================================
-- 18. 通知渠道表（notify_channel）
--    用途：告警/通知的发送渠道配置（企微/钉钉/邮件/短信/Webhook），含渠道配置JSON与最近测试结果
-- ============================================================
CREATE TABLE IF NOT EXISTS `notify_channel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `channel_name` varchar(128) NOT NULL COMMENT '渠道名称（原型 channelName）',
  `channel_type` varchar(32) NOT NULL COMMENT 'WECOM/DINGTALK/EMAIL/SMS/WEBHOOK/HTTP（原型 channelType；HTTP=自定义外部接口，T12）',
  `channel_config` varchar(1024) DEFAULT NULL COMMENT '渠道配置JSON（webhook地址/SMTP等）（推断）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（原型 status）',
  `last_test_time` datetime DEFAULT NULL COMMENT '最近测试时间（原型 lastTestTime）',
  `last_test_result` varchar(255) DEFAULT NULL COMMENT '最近测试结果（原型 lastTestResult）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notify_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知渠道表';

-- ============================================================
-- 19. 安全事件表（security_event）
--    用途：安全检测规则触发后产生的告警/拦截事件，供安全审计跟进处置
-- ============================================================
CREATE TABLE IF NOT EXISTS `security_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `event_type` varchar(50) NOT NULL COMMENT 'HIGH_FREQUENCY/ABNORMAL_TIME/AUTH_FAIL/ABNORMAL_PARAM/PERMISSION_BREACH',
  `event_desc` varchar(512) NOT NULL COMMENT '事件描述',
  `app_id` bigint DEFAULT NULL,
  `app_name` varchar(128) DEFAULT NULL,
  `client_ip` varchar(64) DEFAULT NULL,
  `trigger_rule` varchar(256) DEFAULT NULL COMMENT '触发规则',
  `handle_status` tinyint NOT NULL DEFAULT '0' COMMENT '0=待处理, 1=已处理, 2=已忽略',
  `handle_remark` varchar(512) DEFAULT NULL,
  `occurred_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `handled_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_event_type` (`event_type`),
  KEY `idx_event_status` (`handle_status`),
  KEY `idx_event_time` (`occurred_at`),
  KEY `idx_event_app` (`app_id`),
  KEY `idx_event_ip` (`client_ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全事件表';

-- ============================================================
-- 20. 安全检测规则配置表（security_rule）
--    用途：安全防护规则引擎配置，定义检测规则及触发后的处置动作
-- ============================================================
CREATE TABLE IF NOT EXISTS `security_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rule_name` varchar(128) NOT NULL COMMENT '规则名称',
  `rule_type` varchar(50) NOT NULL COMMENT '规则类型',
  `rule_config` text NOT NULL COMMENT 'JSON配置',
  `trigger_action` varchar(50) NOT NULL COMMENT 'ALERT/AUTO_BAN/ALERT_AND_BAN/BLOCK',
  `ban_duration_min` int DEFAULT NULL COMMENT '自动封禁时长(分钟)',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `description` varchar(512) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全检测规则配置表';

-- ============================================================
-- 21. 系统参数配置表（sys_config）
--    用途：平台级可运营参数（签名/密钥/网关/日志等分组），支持内置保护与敏感值标记，应用启动自检读取 schema 版本
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_key` varchar(128) NOT NULL COMMENT '配置键（原型 configKey）',
  `config_value` varchar(1024) DEFAULT NULL COMMENT '配置值（原型 configValue）',
  `config_group` varchar(32) NOT NULL DEFAULT 'DEFAULT' COMMENT 'SECURITY/GATEWAY/LOG/DEFAULT（原型 configGroup）',
  `config_name` varchar(128) DEFAULT NULL COMMENT '配置名称（原型 configName）',
  `sensitive` tinyint NOT NULL DEFAULT '0' COMMENT '1=敏感配置(响应脱敏),0=普通（原型 sensitive）',
  `built_in` tinyint NOT NULL DEFAULT '0' COMMENT '1=内置不可删除,0=可删除（原型 builtIn）',
  `remark` varchar(512) DEFAULT NULL COMMENT '备注（原型 remark）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_config_group` (`config_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数配置表';

-- ============================================================
-- 22. 数据字典表（sys_dict）
--    用途：枚举类字典主表，统一维护状态码、类型等下拉取值，供前端与后端共用
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_code` varchar(64) NOT NULL COMMENT '字典编码（原型 dictCode）',
  `dict_name` varchar(128) NOT NULL COMMENT '字典名称（原型 dictName）',
  `built_in` tinyint NOT NULL DEFAULT '0' COMMENT '1=内置不可删除,0=可删除（原型 builtIn）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（原型 status）',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注（推断）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_code` (`dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据字典表';

-- ============================================================
-- 23. 数据字典项表（sys_dict_item）
--    用途：字典的具体取值项（值/标签/排序/状态），逻辑关联 sys_dict.dict_code
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_dict_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_code` varchar(64) NOT NULL COMMENT '字典编码（外键逻辑关联 sys_dict.dict_code）',
  `item_value` varchar(64) NOT NULL COMMENT '字典项值（原型 itemValue）',
  `item_label` varchar(128) NOT NULL COMMENT '字典项标签（原型 itemLabel）',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序（原型 sort）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（原型 status）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_item` (`dict_code`,`item_value`),
  KEY `idx_dictitem_code` (`dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据字典项表';

-- ============================================================
-- 24. 菜单权限点表（sys_menu）
--    用途：三级菜单/权限点定义（模块/菜单/权限点），perm_code 为全站 @RequirePerm 取权来源，route_path 映射前端路由
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限点ID',
  `pid` bigint NOT NULL DEFAULT '0' COMMENT '父级ID，0=顶级（原型 pid）',
  `name` varchar(64) NOT NULL COMMENT '菜单/权限点名称（原型 name）',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '1=模块,2=菜单,3=权限点（原型 type）',
  `perm_code` varchar(64) DEFAULT NULL COMMENT '权限点编码 如 app:create（原型 permCode，模块节点为NULL）',
  `route_path` varchar(128) DEFAULT NULL COMMENT '前端路由路径，type=2 页面节点使用 如 /app/list（推断：承接原型 MENU_TREE 20 个页面）',
  `risk_flag` tinyint NOT NULL DEFAULT '0' COMMENT '1=高危操作,0=普通（原型 risk）',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序（推断：原型按数组顺序）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（推断）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_perm` (`perm_code`),
  KEY `idx_menu_pid` (`pid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限点表';

-- ============================================================
-- 25. 操作审计日志表（sys_operation_log）
--    用途：管理后台操作审计记录，用于安全审计与责任追溯
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `operator_id` bigint DEFAULT NULL,
  `operator_name` varchar(64) DEFAULT NULL,
  `operation_type` varchar(50) NOT NULL COMMENT 'CREATE/UPDATE/DELETE/LOGIN/LOGOUT',
  `operation_module` varchar(50) DEFAULT NULL COMMENT 'APP/INTERFACE/PERMISSION/SECURITY/SYSTEM',
  `operation_desc` varchar(512) DEFAULT NULL,
  `request_method` varchar(10) DEFAULT NULL,
  `request_url` varchar(512) DEFAULT NULL,
  `request_params` text,
  `client_ip` varchar(64) DEFAULT NULL,
  `cost_time` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `perm_code` varchar(64) DEFAULT NULL COMMENT '触发的权限点编码 如 grant:revoke（原型 permCode）',
  `risk_flag` tinyint NOT NULL DEFAULT '0' COMMENT '1=高危操作,0=普通（原型 riskFlag）',
  `object_desc` varchar(255) DEFAULT NULL COMMENT '操作对象描述（原型 objectDesc）',
  `change_content` text COMMENT '变更内容JSON（原型 changeContent）',
  `result` tinyint NOT NULL DEFAULT '1' COMMENT '1=成功,0=失败（原型 result）',
  `fail_reason` varchar(255) DEFAULT NULL COMMENT '失败原因（原型 failReason）',
  PRIMARY KEY (`id`),
  KEY `idx_oplog_operator` (`operator_id`),
  KEY `idx_oplog_type` (`operation_type`),
  KEY `idx_oplog_time` (`created_at`),
  KEY `idx_oplog_perm` (`perm_code`),
  KEY `idx_oplog_risk_time` (`risk_flag`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志表';

-- ============================================================
-- 26. 角色表（sys_role）
--    用途：系统角色定义，通过 sys_user_role 与用户建立多对多关联
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `role_name` varchar(64) NOT NULL COMMENT '角色名称',
  `role_code` varchar(64) NOT NULL COMMENT '角色编码',
  `description` varchar(256) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `role_type` tinyint NOT NULL DEFAULT '1' COMMENT '1=内置角色,2=外部/自定义角色（原型 roleType）',
  `data_scope` varchar(20) NOT NULL DEFAULT 'ALL' COMMENT 'ALL=全部数据,BIZ_LINE=仅本业务线,CUSTOM=自定义,SELF=仅本人（原型 dataScope）',
  `user_count` int NOT NULL DEFAULT '0' COMMENT '用户数（原型 userCount，统计冗余）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_name` (`role_name`),
  UNIQUE KEY `uk_role_code` (`role_code`),
  KEY `idx_role_scope` (`data_scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

-- ============================================================
-- 27. 角色数据权限范围表（sys_role_datascope）
--    用途：角色的数据可见范围（业务线/环境/接口分组三维度），用于行级数据权限过滤
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_role_datascope` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `scope_type` varchar(20) NOT NULL COMMENT 'BIZ_LINE=业务线, ENV=环境, API_GROUP=接口分组（推断自原型 datascope 表单三维度）',
  `scope_value` varchar(64) NOT NULL COMMENT '范围值：业务线ID / 环境编码 / 接口分组ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_scope` (`role_id`,`scope_type`,`scope_value`),
  KEY `idx_ds_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色数据权限范围表';

-- ============================================================
-- 28. 角色权限点关联表（sys_role_menu）
--    用途：角色与权限点（sys_menu）的多对多授权关系，是权限校验的数据源
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_role_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` bigint NOT NULL COMMENT '角色ID（原型 rolePerms 的 key）',
  `menu_id` bigint NOT NULL COMMENT '权限点ID（原型 rolePerms 的 value 数组）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`),
  KEY `idx_rm_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限点关联表';

-- ============================================================
-- 29. 系统用户表（sys_user）
--    用途：管理后台登录账号，密码采用 BCrypt 加密存储
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(64) NOT NULL COMMENT '账号',
  `password` varchar(256) NOT NULL COMMENT 'BCrypt加密',
  `real_name` varchar(64) DEFAULT NULL,
  `email` varchar(128) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用, 0=停用',
  `last_login_at` datetime DEFAULT NULL,
  `last_login_ip` varchar(64) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `emp_no` varchar(32) DEFAULT NULL COMMENT '工号（原型 empNo）',
  `dept` varchar(128) DEFAULT NULL COMMENT '所属部门（原型 dept）',
  `line_id` bigint DEFAULT NULL COMMENT '所属业务线ID（原型 lineName 归一化）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_user_empno` (`emp_no`),
  KEY `idx_user_line` (`line_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统用户表';

-- ============================================================
-- 30. 接口表（api_interface）
--    用途：网关代理接口定义，映射网关对外路径与后端真实服务地址
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_interface` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `interface_name` varchar(128) NOT NULL COMMENT '接口名称',
  `interface_path` varchar(256) NOT NULL COMMENT '网关路径 /gateway/ 开头',
  `request_method` varchar(10) NOT NULL COMMENT 'GET/POST/PUT/DELETE',
  `request_param_type` varchar(20) DEFAULT 'JSON' COMMENT '入参类型 JSON/FORM/QUERY',
  `group_id` bigint DEFAULT NULL COMMENT '分组ID',
  `backend_url` varchar(512) NOT NULL COMMENT '后端真实服务地址',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用, 0=停用',
  `timeout_ms` int NOT NULL DEFAULT '5000' COMMENT '超时时间(ms)',
  `description` varchar(512) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `api_code` varchar(64) DEFAULT NULL COMMENT '接口编码 如 order.create（原型 apiCode）',
  `line_id` bigint DEFAULT NULL COMMENT '业务线ID（原型 lineId）',
  `owner_id` bigint DEFAULT NULL COMMENT '负责人用户ID（推断）',
  `owner_name` varchar(64) DEFAULT NULL COMMENT '负责人姓名（原型 ownerName）',
  `visibility` tinyint NOT NULL DEFAULT '1' COMMENT '1=内部,2=对外公开（原型 visibility / dict visibility）',
  `auth_required` tinyint NOT NULL DEFAULT '1' COMMENT '1=需鉴权,0=免鉴权（原型 authRequired）',
  `publish_status` tinyint NOT NULL DEFAULT '2' COMMENT '0=草稿,1=待审核,2=已发布,3=已弃用,4=已下线（原型 status / dict api_status）',
  `current_version` varchar(20) DEFAULT NULL COMMENT '当前版本号（原型 version，冗余自 api_version.is_current）',
  `sla` varchar(128) DEFAULT NULL COMMENT 'SLA承诺 如 99.9%, P99<200ms（原型 sla）',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签，逗号分隔 如 核心链路,只读（原型 tags 数组归一化）',
  `transport_security` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT 'NONE/TLS/MTLS（原型 transportSecurity）',
  `grant_count` int NOT NULL DEFAULT '0' COMMENT '授权数（原型 grantCount，统计冗余）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_iface_code` (`api_code`),
  KEY `idx_iface_path` (`interface_path`,`request_method`),
  KEY `idx_iface_group` (`group_id`),
  KEY `idx_iface_status` (`status`),
  KEY `idx_iface_line` (`line_id`),
  KEY `idx_iface_publish` (`publish_status`),
  CONSTRAINT `fk_iface_group` FOREIGN KEY (`group_id`) REFERENCES `api_group` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口表';

-- ============================================================
-- 31. 应用加解密配置表（app_encryption_config）
--    用途：应用级密钥与加解密算法配置，密钥加密落库，与 app 一对一
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_encryption_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `algorithm` varchar(20) NOT NULL COMMENT 'SM2/SM4/AES',
  `public_key` text COMMENT 'SM2公钥',
  `private_key` text COMMENT 'SM2私钥(加密存储)',
  `secret_key` varchar(512) DEFAULT NULL COMMENT '对称密钥(Base64,加密存储)',
  `iv` varchar(256) DEFAULT NULL COMMENT 'IV向量(Base64)',
  `mode` varchar(20) DEFAULT NULL COMMENT 'ECB/CBC/CFB/OFB/CTR',
  `padding` varchar(30) DEFAULT NULL COMMENT '填充方式',
  `sign_algorithm` varchar(20) DEFAULT NULL COMMENT '签名算法 SM3/SHA256',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_aec_app` (`app_id`),
  CONSTRAINT `fk_aec_app` FOREIGN KEY (`app_id`) REFERENCES `app` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用加解密配置表';

-- ============================================================
-- 32. 应用IP白名单表（app_ip_whitelist）
--    用途：应用来源IP访问控制，来源IP不在白名单内的请求将被网关拦截
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_ip_whitelist` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `ip_cidr` varchar(64) NOT NULL COMMENT '单IP或CIDR格式',
  `remark` varchar(256) DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `env_code` varchar(32) NOT NULL DEFAULT 'prod' COMMENT '环境编码（原型 envCode）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=启用,0=停用（推断）',
  PRIMARY KEY (`id`),
  KEY `idx_ipwl_app` (`app_id`),
  KEY `idx_ipwl_app_env` (`app_id`,`env_code`),
  CONSTRAINT `fk_ipwl_app` FOREIGN KEY (`app_id`) REFERENCES `app` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用IP白名单表';

-- ============================================================
-- 33. 应用限流配置表（app_rate_limit）
--    用途：应用流量控制策略（QPS/并发/日调用量），与 app 一对一
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_rate_limit` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `qps_limit` int NOT NULL DEFAULT '0' COMMENT '每秒最大请求数，0=不限',
  `concurrent_limit` int NOT NULL DEFAULT '0' COMMENT '最大并发数，0=不限',
  `daily_limit` int NOT NULL DEFAULT '0' COMMENT '日调用上限，0=不限',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rl_app` (`app_id`),
  CONSTRAINT `fk_rl_app` FOREIGN KEY (`app_id`) REFERENCES `app` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用限流配置表';

-- ============================================================
-- 34. 用户角色关联表（sys_user_role）
--    用途：用户与角色的多对多关联中间表
-- ============================================================
CREATE TABLE IF NOT EXISTS `sys_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `fk_ur_role` (`role_id`),
  CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表';

-- ============================================================
-- 35. 接口加解密配置表（api_encryption_config）
--    用途：接口级请求/响应加解密配置，与 api_interface 一对一
-- ============================================================
CREATE TABLE IF NOT EXISTS `api_encryption_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `request_encrypted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '入参是否加密',
  `request_algorithm` varchar(20) DEFAULT NULL COMMENT 'SM4/AES',
  `request_mode` varchar(20) DEFAULT NULL COMMENT 'ECB/CBC/CFB/OFB/CTR',
  `request_key` varchar(512) DEFAULT NULL COMMENT 'Base64，库中AES加密',
  `request_iv` varchar(256) DEFAULT NULL COMMENT 'Base64',
  `request_padding` varchar(30) DEFAULT NULL COMMENT '填充方式',
  `response_encrypted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '返参是否加密',
  `response_algorithm` varchar(20) DEFAULT NULL,
  `response_mode` varchar(20) DEFAULT NULL,
  `response_key` varchar(512) DEFAULT NULL,
  `response_iv` varchar(256) DEFAULT NULL,
  `response_padding` varchar(30) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_enc_iface` (`interface_id`),
  CONSTRAINT `fk_enc_iface` FOREIGN KEY (`interface_id`) REFERENCES `api_interface` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接口加解密配置表';

-- ============================================================
-- 36. 应用接口权限表（app_api_permission）
--    用途：应用-接口授权关系，网关据此判断请求是否越权
-- ============================================================
CREATE TABLE IF NOT EXISTS `app_api_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `app_id` bigint NOT NULL COMMENT '应用ID',
  `interface_id` bigint NOT NULL COMMENT '接口ID',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1=已授权, 0=已取消',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_iface` (`app_id`,`interface_id`),
  KEY `idx_perm_app` (`app_id`),
  KEY `idx_perm_iface` (`interface_id`),
  CONSTRAINT `fk_perm_app` FOREIGN KEY (`app_id`) REFERENCES `app` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_perm_iface` FOREIGN KEY (`interface_id`) REFERENCES `api_interface` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用接口权限表';

-- ============================================================
-- 基础种子数据（幂等：INSERT ... ON DUPLICATE KEY UPDATE）
-- ============================================================

-- ---- sys_user（1 行）----
INSERT INTO `sys_user` (`id`,`username`,`password`,`real_name`,`email`,`phone`,`status`,`last_login_at`,`last_login_ip`,`created_at`,`updated_at`,`emp_no`,`dept`,`line_id`) VALUES
(1,'admin','$2b$10$1REIS.9.l6F3VtI2SI0DLe.aVWREj4//wUEZKF/9gE3oK0B9zBtTa','系统管理员',NULL,NULL,1,NULL,NULL,'2026-08-28 16:25:00','2026-09-10 14:08:21',NULL,NULL,100)
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`username`=VALUES(`username`),`password`=VALUES(`password`),`real_name`=VALUES(`real_name`),`email`=VALUES(`email`),`phone`=VALUES(`phone`),`status`=VALUES(`status`),`last_login_at`=VALUES(`last_login_at`),`last_login_ip`=VALUES(`last_login_ip`),`created_at`=VALUES(`created_at`),`updated_at`=VALUES(`updated_at`),`emp_no`=VALUES(`emp_no`),`dept`=VALUES(`dept`),`line_id`=VALUES(`line_id`);

-- ---- sys_role（9 行）----
INSERT INTO `sys_role` (`id`,`role_name`,`role_code`,`description`,`status`,`created_at`,`role_type`,`data_scope`,`user_count`) VALUES
(1,'超级管理员','SUPER_ADMIN','拥有全部权限',1,'2026-08-28 16:25:00',1,'ALL',1),
(2,'运维人员','OPERATOR','应用管理、接口管理、日志查看',1,'2026-08-28 16:25:00',1,'BIZ_LINE',0),
(3,'安全审计','SECURITY_AUDITOR','安全防护、日志审计、只读',1,'2026-08-28 16:25:00',1,'ALL',0),
(10,'平台管理员','ADMIN','系统内置，拥有全部权限',1,'2026-09-10 14:07:45',1,'ALL',0),
(11,'业务线管理员','BIZ_ADMIN','管理本业务线内的应用与接口',1,'2026-09-10 14:07:45',1,'BIZ_LINE',0),
(12,'接口提供方','API_PROVIDER','管理自己负责的接口分组',1,'2026-09-10 14:07:45',1,'CUSTOM',0),
(13,'接口调用方','API_CONSUMER','管理自己的应用与密钥，申请授权',1,'2026-09-10 14:07:45',1,'SELF',0),
(14,'审计员','AUDITOR','全局只读 + 审计日志导出',1,'2026-09-10 14:07:45',1,'ALL',0),
(15,'外部对接人','EXTERNAL_PM','外部合作方对接账号，仅可查看自己的应用',1,'2026-09-10 14:07:45',2,'SELF',0)
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_name`=VALUES(`role_name`),`role_code`=VALUES(`role_code`),`description`=VALUES(`description`),`status`=VALUES(`status`),`created_at`=VALUES(`created_at`),`role_type`=VALUES(`role_type`),`data_scope`=VALUES(`data_scope`),`user_count`=VALUES(`user_count`);

-- ---- sys_menu（112 行）----
INSERT INTO `sys_menu` (`id`,`pid`,`name`,`type`,`perm_code`,`route_path`,`risk_flag`,`sort_order`,`status`,`created_at`) VALUES
(1,0,'概览',1,NULL,'/dashboard',0,1,1,'2026-09-10 14:07:45'),
(2,0,'应用管理',1,NULL,NULL,0,2,1,'2026-09-10 14:07:45'),
(3,0,'接口管理',1,NULL,NULL,0,3,1,'2026-09-10 14:07:45'),
(4,0,'权限管理',1,NULL,NULL,0,4,1,'2026-09-10 14:07:45'),
(5,0,'系统设置',1,NULL,NULL,0,5,1,'2026-09-10 14:07:45'),
(6,0,'监控与审计',1,NULL,NULL,0,6,1,'2026-09-10 14:07:45'),
(21,2,'查看应用',3,'app:list',NULL,0,1,1,'2026-09-10 14:07:45'),
(22,2,'新增应用',3,'app:create',NULL,0,2,1,'2026-09-10 14:07:45'),
(23,2,'编辑应用',3,'app:update',NULL,0,3,1,'2026-09-10 14:07:45'),
(24,2,'停用/注销应用',3,'app:disable',NULL,1,4,1,'2026-09-10 14:07:45'),
(25,2,'创建密钥',3,'app:credential:create',NULL,1,5,1,'2026-09-10 14:07:45'),
(26,2,'重置 Secret',3,'app:credential:reset',NULL,1,6,1,'2026-09-10 14:07:45'),
(27,2,'吊销密钥',3,'app:credential:revoke',NULL,1,7,1,'2026-09-10 14:07:45'),
(31,3,'查看接口',3,'api:list',NULL,0,1,1,'2026-09-10 14:07:45'),
(32,3,'新增接口',3,'api:create',NULL,0,2,1,'2026-09-10 14:07:45'),
(33,3,'编辑接口',3,'api:update',NULL,0,3,1,'2026-09-10 14:07:45'),
(34,3,'发布接口',3,'api:publish',NULL,1,4,1,'2026-09-10 14:07:45'),
(35,3,'下线接口',3,'api:offline',NULL,1,5,1,'2026-09-10 14:07:45'),
(36,3,'配置环境',3,'api:env:update',NULL,0,6,1,'2026-09-10 14:07:45'),
(41,4,'查看授权',3,'grant:list',NULL,0,1,1,'2026-09-10 14:07:45'),
(42,4,'新增授权',3,'grant:create',NULL,0,2,1,'2026-09-10 14:07:45'),
(43,4,'审批授权',3,'grant:approve',NULL,0,3,1,'2026-09-10 14:07:45'),
(44,4,'撤销授权',3,'grant:revoke',NULL,1,4,1,'2026-09-10 14:07:45'),
(45,4,'用户管理',3,'sys:user:update',NULL,1,5,1,'2026-09-10 14:07:45'),
(46,4,'角色授权',3,'sys:role:grant',NULL,1,6,1,'2026-09-10 14:07:45'),
(47,4,'审计日志导出',3,'audit:export',NULL,1,7,1,'2026-09-10 14:07:45'),
(48,4,'数据权限查看',3,'sys:datascope:view',NULL,0,8,1,'2026-09-11 05:55:26'),
(49,4,'数据权限配置',3,'sys:datascope:update',NULL,1,9,1,'2026-09-11 05:55:26'),
(51,5,'查看配置',3,'sys:config:list',NULL,0,1,1,'2026-09-10 14:07:45'),
(52,5,'修改配置',3,'sys:config:update',NULL,0,2,1,'2026-09-10 14:07:45'),
(53,5,'修改安全策略',3,'sys:security:update',NULL,1,3,1,'2026-09-10 14:07:45'),
(54,5,'字典管理',3,'sys:dict:update',NULL,0,4,1,'2026-09-10 14:07:45'),
(55,5,'告警配置',3,'sys:alarm:update',NULL,0,5,1,'2026-09-10 14:07:45'),
(61,6,'查看调用日志',3,'log:call:list',NULL,0,1,1,'2026-09-10 14:07:45'),
(62,6,'查看调用详情',3,'log:call:detail',NULL,0,2,1,'2026-09-10 14:07:45'),
(63,6,'处理告警',3,'alarm:handle',NULL,0,3,1,'2026-09-10 14:07:45'),
(71,5,'查看业务线',3,'biz_line:list',NULL,0,11,1,'2026-09-10 15:56:09'),
(72,5,'新建业务线',3,'biz_line:create',NULL,0,12,1,'2026-09-10 15:56:09'),
(73,5,'编辑业务线',3,'biz_line:update',NULL,0,13,1,'2026-09-10 15:56:09'),
(74,5,'删除业务线',3,'biz_line:delete',NULL,1,14,1,'2026-09-10 15:56:09'),
(81,5,'查看环境',3,'env:list',NULL,0,21,1,'2026-09-10 15:56:09'),
(82,5,'新建环境',3,'env:create',NULL,0,22,1,'2026-09-10 15:56:09'),
(83,5,'编辑环境',3,'env:update',NULL,0,23,1,'2026-09-10 15:56:09'),
(84,5,'删除环境',3,'env:delete',NULL,1,24,1,'2026-09-10 15:56:09'),
(91,2,'查看凭证',3,'app_credential:list',NULL,0,11,1,'2026-09-10 15:56:09'),
(92,2,'创建凭证',3,'app_credential:create',NULL,1,12,1,'2026-09-10 15:56:09'),
(93,2,'轮换凭证',3,'app_credential:rotate',NULL,0,13,1,'2026-09-10 15:56:09'),
(95,2,'编辑凭证',3,'app_credential:update',NULL,0,15,1,'2026-09-10 15:56:09'),
(101,3,'导入接口参数',3,'api_param:import',NULL,1,8,1,'2026-09-11 08:46:01'),
(102,3,'版本灰度发布',3,'api_version:gray',NULL,1,9,1,'2026-09-11 08:46:01'),
(103,3,'环境连通性测试',3,'api_env_config:test',NULL,0,10,1,'2026-09-11 08:46:01'),
(201,1,'概览',2,NULL,'/dashboard',0,1,1,'2026-09-10 14:07:45'),
(202,2,'应用列表',2,NULL,'/app/list',0,1,1,'2026-09-10 14:07:45'),
(203,3,'接口分组',2,NULL,'/api/group',0,1,1,'2026-09-10 14:07:45'),
(204,3,'接口列表',2,NULL,'/api/list',0,2,1,'2026-09-10 14:07:45'),
(205,4,'用户管理',2,NULL,'/perm/user',0,1,1,'2026-09-10 14:07:45'),
(206,4,'角色管理',2,NULL,'/perm/role',0,2,1,'2026-09-10 14:07:45'),
(207,4,'接口授权总览',2,NULL,'/perm/matrix',0,3,1,'2026-09-10 14:07:45'),
(208,4,'数据权限',2,NULL,'/perm/datascope',0,4,1,'2026-09-10 14:07:45'),
(209,4,'操作审计',2,NULL,'/perm/audit',0,5,1,'2026-09-10 14:07:45'),
(210,5,'环境与网关',2,NULL,'/sys/env',0,1,1,'2026-09-10 14:07:45'),
(211,5,'安全策略',2,NULL,'/sys/security',0,2,1,'2026-09-10 14:07:45'),
(212,5,'业务线管理',2,NULL,'/sys/bizline',0,3,1,'2026-09-10 14:07:45'),
(213,5,'字典管理',2,NULL,'/sys/dict',0,4,1,'2026-09-10 14:07:45'),
(214,5,'告警规则',2,NULL,'/sys/alarm',0,5,1,'2026-09-10 14:07:45'),
(215,5,'通知渠道',2,NULL,'/sys/notify',0,6,1,'2026-09-10 14:07:45'),
(216,5,'参数配置',2,NULL,'/sys/config',0,7,1,'2026-09-10 14:07:45'),
(217,5,'日志与审计',2,NULL,'/sys/log',0,8,1,'2026-09-10 14:07:45'),
(218,6,'调用日志',2,NULL,'/mon/calllog',0,1,1,'2026-09-10 14:07:45'),
(219,6,'告警记录',2,NULL,'/mon/alarm',0,2,1,'2026-09-10 14:07:45'),
(220,6,'封禁管理',2,NULL,'/mon/block',0,3,1,'2026-09-10 14:07:45'),
(301,1,'概览查看',3,'dashboard:view',NULL,0,1,1,'2026-09-11 08:30:34'),
(302,4,'查看用户',3,'sys:user:list',NULL,0,10,1,'2026-09-11 08:30:34'),
(303,4,'查看角色',3,'sys:role:list',NULL,0,11,1,'2026-09-11 08:30:34'),
(304,4,'查看审计',3,'audit:list',NULL,0,12,1,'2026-09-11 08:30:34'),
(305,5,'查看安全策略',3,'sys:security:view',NULL,0,6,1,'2026-09-11 08:30:34'),
(306,5,'查看通知渠道',3,'sys:notify:list',NULL,0,7,1,'2026-09-11 08:30:34'),
(307,5,'查看日志审计',3,'sys:log:list',NULL,0,8,1,'2026-09-11 08:30:34'),
(308,6,'查看告警记录',3,'alarm:list',NULL,0,4,1,'2026-09-11 08:30:34'),
(309,6,'查看封禁名单',3,'block:list',NULL,0,5,1,'2026-09-11 08:30:34'),
(311,3,'删除接口',3,'api:delete',NULL,1,7,1,'2026-09-11 08:30:34'),
(312,3,'新增参数',3,'api_param:create',NULL,1,8,1,'2026-09-11 08:30:34'),
(313,3,'删除参数',3,'api_param:delete',NULL,1,9,1,'2026-09-11 08:30:34'),
(314,3,'新建版本',3,'api_version:create',NULL,1,10,1,'2026-09-11 08:30:34'),
(315,3,'新增环境配置',3,'api_env_config:create',NULL,1,11,1,'2026-09-11 08:30:34'),
(316,3,'删除环境配置',3,'api_env_config:delete',NULL,1,12,1,'2026-09-11 08:30:34'),
(317,3,'追加变更历史',3,'api_change_log:append',NULL,1,13,1,'2026-09-11 08:30:34'),
(318,4,'驳回授权',3,'grant:reject',NULL,1,13,1,'2026-09-11 08:30:34'),
(319,5,'新建告警规则',3,'alarm_rule:create',NULL,1,9,1,'2026-09-11 08:30:34'),
(320,5,'新建通知渠道',3,'notify_channel:create',NULL,1,10,1,'2026-09-11 08:30:34'),
(321,6,'新建封禁规则',3,'block_rule:create',NULL,1,6,1,'2026-09-11 08:30:34'),
(322,6,'手动封禁',3,'block_rule:manual',NULL,1,7,1,'2026-09-11 08:30:34'),
(331,2,'删除应用',3,'app:delete',NULL,0,8,1,'2026-09-11 08:30:34'),
(332,2,'修改配额',3,'app:quota:update',NULL,0,9,1,'2026-09-11 08:30:34'),
(333,2,'新增白名单',3,'app:ipwhitelist:add',NULL,0,10,1,'2026-09-11 08:30:34'),
(334,2,'删除白名单',3,'app:ipwhitelist:delete',NULL,0,11,1,'2026-09-11 08:30:34'),
(335,2,'完成密钥轮换',3,'app_credential:complete',NULL,0,12,1,'2026-09-11 08:30:34'),
(336,3,'停用/启用接口',3,'api:disable',NULL,0,14,1,'2026-09-11 08:30:34'),
(337,3,'编辑参数',3,'api_param:update',NULL,0,15,1,'2026-09-11 08:30:34'),
(338,3,'新建分组',3,'api_group:create',NULL,0,16,1,'2026-09-11 08:30:34')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`pid`=VALUES(`pid`),`name`=VALUES(`name`),`type`=VALUES(`type`),`perm_code`=VALUES(`perm_code`),`route_path`=VALUES(`route_path`),`risk_flag`=VALUES(`risk_flag`),`sort_order`=VALUES(`sort_order`),`status`=VALUES(`status`),`created_at`=VALUES(`created_at`);

INSERT INTO `sys_menu` (`id`,`pid`,`name`,`type`,`perm_code`,`route_path`,`risk_flag`,`sort_order`,`status`,`created_at`) VALUES
(339,3,'编辑分组',3,'api_group:update',NULL,0,17,1,'2026-09-11 08:30:34'),
(340,3,'删除分组',3,'api_group:delete',NULL,0,18,1,'2026-09-11 08:30:34'),
(341,5,'编辑通知渠道',3,'sys:notify:update',NULL,0,11,1,'2026-09-11 08:30:34'),
(342,5,'发送测试告警',3,'alarm_rule:test',NULL,1,12,1,'2026-09-11 15:23:52'),
(343,5,'发送测试通知',3,'notify_channel:test',NULL,1,13,1,'2026-09-11 15:23:52'),
(344,5,'删除通知渠道',3,'notify_channel:delete',NULL,1,14,1,'2026-09-11 15:23:52'),
(345,4,'新建用户',3,'sys:user:create',NULL,1,15,1,'2026-09-11 15:42:18'),
(346,4,'删除用户',3,'sys:user:delete',NULL,1,16,1,'2026-09-11 15:42:18'),
(347,4,'重置用户密码',3,'sys:user:resetpwd',NULL,1,17,1,'2026-09-11 15:42:18'),
(348,4,'新建角色',3,'sys:role:create',NULL,1,18,1,'2026-09-11 15:42:18'),
(349,4,'编辑角色',3,'sys:role:update',NULL,1,19,1,'2026-09-11 15:42:18'),
(350,4,'删除角色',3,'sys:role:delete',NULL,1,20,1,'2026-09-11 15:42:18')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`pid`=VALUES(`pid`),`name`=VALUES(`name`),`type`=VALUES(`type`),`perm_code`=VALUES(`perm_code`),`route_path`=VALUES(`route_path`),`risk_flag`=VALUES(`risk_flag`),`sort_order`=VALUES(`sort_order`),`status`=VALUES(`status`),`created_at`=VALUES(`created_at`);

-- ---- sys_user_role（2 行）----
INSERT INTO `sys_user_role` (`id`,`user_id`,`role_id`,`created_at`) VALUES
(1,1,1,'2026-08-28 16:25:00'),
(2,1,10,'2026-09-10 14:54:42')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`user_id`=VALUES(`user_id`),`role_id`=VALUES(`role_id`),`created_at`=VALUES(`created_at`);

-- ---- sys_role_menu（409 行）----
INSERT INTO `sys_role_menu` (`id`,`role_id`,`menu_id`,`created_at`) VALUES
(1,10,1,'2026-09-10 14:07:45'),
(2,10,21,'2026-09-10 14:07:45'),
(3,10,22,'2026-09-10 14:07:45'),
(4,10,23,'2026-09-10 14:07:45'),
(5,10,24,'2026-09-10 14:07:45'),
(6,10,25,'2026-09-10 14:07:45'),
(8,10,27,'2026-09-10 14:07:45'),
(9,10,31,'2026-09-10 14:07:45'),
(10,10,32,'2026-09-10 14:07:45'),
(11,10,33,'2026-09-10 14:07:45'),
(12,10,34,'2026-09-10 14:07:45'),
(13,10,35,'2026-09-10 14:07:45'),
(14,10,36,'2026-09-10 14:07:45'),
(15,10,41,'2026-09-10 14:07:45'),
(16,10,42,'2026-09-10 14:07:45'),
(17,10,43,'2026-09-10 14:07:45'),
(18,10,44,'2026-09-10 14:07:45'),
(19,10,45,'2026-09-10 14:07:45'),
(20,10,46,'2026-09-10 14:07:45'),
(21,10,47,'2026-09-10 14:07:45'),
(22,10,51,'2026-09-10 14:07:45'),
(23,10,52,'2026-09-10 14:07:45'),
(24,10,53,'2026-09-10 14:07:45'),
(25,10,54,'2026-09-10 14:07:45'),
(26,10,55,'2026-09-10 14:07:45'),
(27,10,61,'2026-09-10 14:07:45'),
(28,10,62,'2026-09-10 14:07:45'),
(29,10,63,'2026-09-10 14:07:45'),
(32,11,1,'2026-09-10 14:07:45'),
(33,11,21,'2026-09-10 14:07:45'),
(34,11,22,'2026-09-10 14:07:45'),
(35,11,23,'2026-09-10 14:07:45'),
(36,11,24,'2026-09-10 14:07:45'),
(37,11,25,'2026-09-10 14:07:45'),
(39,11,27,'2026-09-10 14:07:45'),
(40,11,31,'2026-09-10 14:07:45'),
(41,11,32,'2026-09-10 14:07:45'),
(42,11,33,'2026-09-10 14:07:45'),
(43,11,34,'2026-09-10 14:07:45'),
(44,11,35,'2026-09-10 14:07:45'),
(45,11,36,'2026-09-10 14:07:45'),
(46,11,41,'2026-09-10 14:07:45'),
(47,11,42,'2026-09-10 14:07:45'),
(48,11,43,'2026-09-10 14:07:45'),
(49,11,44,'2026-09-10 14:07:45'),
(50,11,51,'2026-09-10 14:07:45'),
(51,11,54,'2026-09-10 14:07:45'),
(52,11,55,'2026-09-10 14:07:45'),
(53,11,61,'2026-09-10 14:07:45'),
(54,11,62,'2026-09-10 14:07:45'),
(55,11,63,'2026-09-10 14:07:45'),
(63,12,1,'2026-09-10 14:07:45'),
(64,12,21,'2026-09-10 14:07:45'),
(65,12,31,'2026-09-10 14:07:45'),
(66,12,32,'2026-09-10 14:07:45'),
(67,12,33,'2026-09-10 14:07:45'),
(68,12,34,'2026-09-10 14:07:45'),
(69,12,35,'2026-09-10 14:07:45'),
(70,12,36,'2026-09-10 14:07:45'),
(71,12,41,'2026-09-10 14:07:45'),
(72,12,42,'2026-09-10 14:07:45'),
(73,12,43,'2026-09-10 14:07:45'),
(74,12,61,'2026-09-10 14:07:45'),
(75,12,62,'2026-09-10 14:07:45'),
(78,13,1,'2026-09-10 14:07:45'),
(79,13,21,'2026-09-10 14:07:45'),
(80,13,22,'2026-09-10 14:07:45'),
(81,13,23,'2026-09-10 14:07:45'),
(82,13,25,'2026-09-10 14:07:45'),
(83,13,31,'2026-09-10 14:07:45'),
(84,13,41,'2026-09-10 14:07:45'),
(85,13,61,'2026-09-10 14:07:45'),
(86,13,62,'2026-09-10 14:07:45'),
(93,14,1,'2026-09-10 14:07:45'),
(94,14,21,'2026-09-10 14:07:45'),
(95,14,31,'2026-09-10 14:07:45'),
(96,14,41,'2026-09-10 14:07:45'),
(97,14,47,'2026-09-10 14:07:45'),
(98,14,51,'2026-09-10 14:07:45'),
(99,14,61,'2026-09-10 14:07:45'),
(100,14,62,'2026-09-10 14:07:45'),
(101,14,63,'2026-09-10 14:07:45'),
(108,15,1,'2026-09-10 14:07:45'),
(109,15,21,'2026-09-10 14:07:45'),
(110,15,25,'2026-09-10 14:07:45'),
(111,15,41,'2026-09-10 14:07:45'),
(112,15,61,'2026-09-10 14:07:45'),
(113,15,62,'2026-09-10 14:07:45'),
(115,1,1,'2026-09-10 14:07:45'),
(116,1,21,'2026-09-10 14:07:45'),
(117,1,22,'2026-09-10 14:07:45'),
(118,1,23,'2026-09-10 14:07:45'),
(119,1,24,'2026-09-10 14:07:45'),
(120,1,25,'2026-09-10 14:07:45'),
(122,1,27,'2026-09-10 14:07:45'),
(123,1,31,'2026-09-10 14:07:45'),
(124,1,32,'2026-09-10 14:07:45'),
(125,1,33,'2026-09-10 14:07:45'),
(126,1,34,'2026-09-10 14:07:45'),
(127,1,35,'2026-09-10 14:07:45')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`menu_id`=VALUES(`menu_id`),`created_at`=VALUES(`created_at`);

INSERT INTO `sys_role_menu` (`id`,`role_id`,`menu_id`,`created_at`) VALUES
(128,1,36,'2026-09-10 14:07:45'),
(129,1,41,'2026-09-10 14:07:45'),
(130,1,42,'2026-09-10 14:07:45'),
(131,1,43,'2026-09-10 14:07:45'),
(132,1,44,'2026-09-10 14:07:45'),
(133,1,45,'2026-09-10 14:07:45'),
(134,1,46,'2026-09-10 14:07:45'),
(135,1,47,'2026-09-10 14:07:45'),
(136,1,51,'2026-09-10 14:07:45'),
(137,1,52,'2026-09-10 14:07:45'),
(138,1,53,'2026-09-10 14:07:45'),
(139,1,54,'2026-09-10 14:07:45'),
(140,1,55,'2026-09-10 14:07:45'),
(141,1,61,'2026-09-10 14:07:45'),
(142,1,62,'2026-09-10 14:07:45'),
(143,1,63,'2026-09-10 14:07:45'),
(146,2,1,'2026-09-10 14:07:45'),
(147,2,21,'2026-09-10 14:07:45'),
(148,2,22,'2026-09-10 14:07:45'),
(149,2,23,'2026-09-10 14:07:45'),
(150,2,24,'2026-09-10 14:07:45'),
(151,2,25,'2026-09-10 14:07:45'),
(153,2,27,'2026-09-10 14:07:45'),
(154,2,31,'2026-09-10 14:07:45'),
(155,2,32,'2026-09-10 14:07:45'),
(156,2,33,'2026-09-10 14:07:45'),
(157,2,34,'2026-09-10 14:07:45'),
(158,2,35,'2026-09-10 14:07:45'),
(159,2,36,'2026-09-10 14:07:45'),
(160,2,41,'2026-09-10 14:07:45'),
(161,2,42,'2026-09-10 14:07:45'),
(162,2,43,'2026-09-10 14:07:45'),
(163,2,44,'2026-09-10 14:07:45'),
(164,2,51,'2026-09-10 14:07:45'),
(165,2,54,'2026-09-10 14:07:45'),
(166,2,55,'2026-09-10 14:07:45'),
(167,2,61,'2026-09-10 14:07:45'),
(168,2,62,'2026-09-10 14:07:45'),
(169,2,63,'2026-09-10 14:07:45'),
(177,3,1,'2026-09-10 14:07:45'),
(178,3,21,'2026-09-10 14:07:45'),
(179,3,31,'2026-09-10 14:07:45'),
(180,3,41,'2026-09-10 14:07:45'),
(181,3,47,'2026-09-10 14:07:45'),
(182,3,51,'2026-09-10 14:07:45'),
(183,3,61,'2026-09-10 14:07:45'),
(184,3,62,'2026-09-10 14:07:45'),
(185,3,63,'2026-09-10 14:07:45'),
(237,1,26,'2026-09-10 15:09:45'),
(238,3,26,'2026-09-10 15:09:45'),
(239,10,26,'2026-09-10 15:09:45'),
(240,14,26,'2026-09-10 15:09:45'),
(241,2,26,'2026-09-10 15:09:45'),
(242,11,26,'2026-09-10 15:09:45'),
(243,12,26,'2026-09-10 15:09:45'),
(244,13,26,'2026-09-10 15:09:45'),
(245,15,26,'2026-09-10 15:09:45'),
(253,10,92,'2026-09-10 15:56:09'),
(254,10,91,'2026-09-10 15:56:09'),
(255,10,93,'2026-09-10 15:56:09'),
(256,10,95,'2026-09-10 15:56:09'),
(257,10,72,'2026-09-10 15:56:09'),
(258,10,74,'2026-09-10 15:56:09'),
(259,10,71,'2026-09-10 15:56:09'),
(260,10,73,'2026-09-10 15:56:09'),
(261,10,82,'2026-09-10 15:56:09'),
(262,10,84,'2026-09-10 15:56:09'),
(263,10,81,'2026-09-10 15:56:09'),
(264,10,83,'2026-09-10 15:56:09'),
(268,11,92,'2026-09-10 15:56:09'),
(269,11,91,'2026-09-10 15:56:09'),
(270,11,93,'2026-09-10 15:56:09'),
(271,11,95,'2026-09-10 15:56:09'),
(272,11,72,'2026-09-10 15:56:09'),
(273,11,74,'2026-09-10 15:56:09'),
(274,11,71,'2026-09-10 15:56:09'),
(275,11,73,'2026-09-10 15:56:09'),
(276,11,82,'2026-09-10 15:56:09'),
(277,11,84,'2026-09-10 15:56:09'),
(278,11,81,'2026-09-10 15:56:09'),
(279,11,83,'2026-09-10 15:56:09'),
(283,12,91,'2026-09-10 15:56:09'),
(284,12,93,'2026-09-10 15:56:09'),
(285,12,71,'2026-09-10 15:56:09'),
(286,12,81,'2026-09-10 15:56:09'),
(290,13,91,'2026-09-10 15:56:09'),
(291,13,93,'2026-09-10 15:56:09'),
(292,13,71,'2026-09-10 15:56:09'),
(293,13,81,'2026-09-10 15:56:09'),
(297,14,91,'2026-09-10 15:56:09'),
(298,14,71,'2026-09-10 15:56:09'),
(299,14,81,'2026-09-10 15:56:09'),
(300,15,91,'2026-09-10 15:56:09'),
(301,15,71,'2026-09-10 15:56:09'),
(302,15,81,'2026-09-10 15:56:09'),
(303,10,48,'2026-09-11 05:55:26'),
(304,11,48,'2026-09-11 05:55:26'),
(305,3,48,'2026-09-11 05:55:26'),
(306,1,48,'2026-09-11 05:55:26'),
(310,10,49,'2026-09-11 05:55:26')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`menu_id`=VALUES(`menu_id`),`created_at`=VALUES(`created_at`);

INSERT INTO `sys_role_menu` (`id`,`role_id`,`menu_id`,`created_at`) VALUES
(311,11,49,'2026-09-11 05:55:26'),
(312,3,49,'2026-09-11 05:55:26'),
(313,1,49,'2026-09-11 05:55:26'),
(317,1,319,'2026-09-11 08:30:34'),
(318,10,319,'2026-09-11 08:30:34'),
(319,1,308,'2026-09-11 08:30:34'),
(320,10,308,'2026-09-11 08:30:34'),
(321,1,317,'2026-09-11 08:30:34'),
(322,10,317,'2026-09-11 08:30:34'),
(323,1,315,'2026-09-11 08:30:34'),
(324,10,315,'2026-09-11 08:30:34'),
(325,1,316,'2026-09-11 08:30:34'),
(326,10,316,'2026-09-11 08:30:34'),
(327,1,338,'2026-09-11 08:30:34'),
(328,10,338,'2026-09-11 08:30:34'),
(329,1,340,'2026-09-11 08:30:34'),
(330,10,340,'2026-09-11 08:30:34'),
(331,1,339,'2026-09-11 08:30:34'),
(332,10,339,'2026-09-11 08:30:34'),
(333,1,312,'2026-09-11 08:30:34'),
(334,10,312,'2026-09-11 08:30:34'),
(335,1,313,'2026-09-11 08:30:34'),
(336,10,313,'2026-09-11 08:30:34'),
(337,1,337,'2026-09-11 08:30:34'),
(338,10,337,'2026-09-11 08:30:34'),
(339,1,314,'2026-09-11 08:30:34'),
(340,10,314,'2026-09-11 08:30:34'),
(341,1,311,'2026-09-11 08:30:34'),
(342,10,311,'2026-09-11 08:30:34'),
(343,1,336,'2026-09-11 08:30:34'),
(344,10,336,'2026-09-11 08:30:34'),
(345,1,335,'2026-09-11 08:30:34'),
(346,10,335,'2026-09-11 08:30:34'),
(347,1,331,'2026-09-11 08:30:34'),
(348,10,331,'2026-09-11 08:30:34'),
(349,1,333,'2026-09-11 08:30:34'),
(350,10,333,'2026-09-11 08:30:34'),
(351,1,334,'2026-09-11 08:30:34'),
(352,10,334,'2026-09-11 08:30:34'),
(353,1,332,'2026-09-11 08:30:34'),
(354,10,332,'2026-09-11 08:30:34'),
(355,1,304,'2026-09-11 08:30:34'),
(356,10,304,'2026-09-11 08:30:34'),
(357,1,321,'2026-09-11 08:30:34'),
(358,10,321,'2026-09-11 08:30:34'),
(359,1,322,'2026-09-11 08:30:34'),
(360,10,322,'2026-09-11 08:30:34'),
(361,1,309,'2026-09-11 08:30:34'),
(362,10,309,'2026-09-11 08:30:34'),
(363,1,301,'2026-09-11 08:30:34'),
(364,10,301,'2026-09-11 08:30:34'),
(365,1,318,'2026-09-11 08:30:34'),
(366,10,318,'2026-09-11 08:30:34'),
(367,1,320,'2026-09-11 08:30:34'),
(368,10,320,'2026-09-11 08:30:34'),
(369,1,307,'2026-09-11 08:30:34'),
(370,10,307,'2026-09-11 08:30:34'),
(371,1,306,'2026-09-11 08:30:34'),
(372,10,306,'2026-09-11 08:30:34'),
(373,1,341,'2026-09-11 08:30:34'),
(374,10,341,'2026-09-11 08:30:34'),
(375,1,303,'2026-09-11 08:30:34'),
(376,10,303,'2026-09-11 08:30:34'),
(377,1,305,'2026-09-11 08:30:34'),
(378,10,305,'2026-09-11 08:30:34'),
(379,1,302,'2026-09-11 08:30:34'),
(380,10,302,'2026-09-11 08:30:34'),
(444,2,319,'2026-09-11 08:30:34'),
(445,11,319,'2026-09-11 08:30:34'),
(446,2,308,'2026-09-11 08:30:34'),
(447,11,308,'2026-09-11 08:30:34'),
(448,2,317,'2026-09-11 08:30:34'),
(449,11,317,'2026-09-11 08:30:34'),
(450,2,315,'2026-09-11 08:30:34'),
(451,11,315,'2026-09-11 08:30:34'),
(452,2,316,'2026-09-11 08:30:34'),
(453,11,316,'2026-09-11 08:30:34'),
(454,2,338,'2026-09-11 08:30:34'),
(455,11,338,'2026-09-11 08:30:34'),
(456,2,340,'2026-09-11 08:30:34'),
(457,11,340,'2026-09-11 08:30:34'),
(458,2,339,'2026-09-11 08:30:34'),
(459,11,339,'2026-09-11 08:30:34'),
(460,2,312,'2026-09-11 08:30:34'),
(461,11,312,'2026-09-11 08:30:34'),
(462,2,313,'2026-09-11 08:30:34'),
(463,11,313,'2026-09-11 08:30:34'),
(464,2,337,'2026-09-11 08:30:34'),
(465,11,337,'2026-09-11 08:30:34'),
(466,2,314,'2026-09-11 08:30:34'),
(467,11,314,'2026-09-11 08:30:34'),
(468,2,311,'2026-09-11 08:30:34'),
(469,11,311,'2026-09-11 08:30:34'),
(470,2,336,'2026-09-11 08:30:34'),
(471,11,336,'2026-09-11 08:30:34'),
(472,2,335,'2026-09-11 08:30:34'),
(473,11,335,'2026-09-11 08:30:34'),
(474,2,331,'2026-09-11 08:30:34'),
(475,11,331,'2026-09-11 08:30:34'),
(476,2,333,'2026-09-11 08:30:34')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`menu_id`=VALUES(`menu_id`),`created_at`=VALUES(`created_at`);

INSERT INTO `sys_role_menu` (`id`,`role_id`,`menu_id`,`created_at`) VALUES
(477,11,333,'2026-09-11 08:30:34'),
(478,2,334,'2026-09-11 08:30:34'),
(479,11,334,'2026-09-11 08:30:34'),
(480,2,332,'2026-09-11 08:30:34'),
(481,11,332,'2026-09-11 08:30:34'),
(482,2,301,'2026-09-11 08:30:34'),
(483,11,301,'2026-09-11 08:30:34'),
(484,2,318,'2026-09-11 08:30:34'),
(485,11,318,'2026-09-11 08:30:34'),
(486,2,306,'2026-09-11 08:30:34'),
(487,11,306,'2026-09-11 08:30:34'),
(507,12,317,'2026-09-11 08:30:34'),
(508,12,315,'2026-09-11 08:30:34'),
(509,12,316,'2026-09-11 08:30:34'),
(510,12,338,'2026-09-11 08:30:34'),
(511,12,340,'2026-09-11 08:30:34'),
(512,12,339,'2026-09-11 08:30:34'),
(513,12,312,'2026-09-11 08:30:34'),
(514,12,313,'2026-09-11 08:30:34'),
(515,12,337,'2026-09-11 08:30:34'),
(516,12,314,'2026-09-11 08:30:34'),
(517,12,311,'2026-09-11 08:30:34'),
(518,12,336,'2026-09-11 08:30:34'),
(519,12,301,'2026-09-11 08:30:34'),
(522,13,335,'2026-09-11 08:30:34'),
(523,13,333,'2026-09-11 08:30:34'),
(524,13,334,'2026-09-11 08:30:34'),
(525,13,332,'2026-09-11 08:30:34'),
(526,13,301,'2026-09-11 08:30:34'),
(529,3,308,'2026-09-11 08:30:34'),
(530,14,308,'2026-09-11 08:30:34'),
(531,3,304,'2026-09-11 08:30:34'),
(532,14,304,'2026-09-11 08:30:34'),
(533,3,321,'2026-09-11 08:30:34'),
(534,14,321,'2026-09-11 08:30:34'),
(535,3,322,'2026-09-11 08:30:34'),
(536,14,322,'2026-09-11 08:30:34'),
(537,3,309,'2026-09-11 08:30:34'),
(538,14,309,'2026-09-11 08:30:34'),
(539,3,301,'2026-09-11 08:30:34'),
(540,14,301,'2026-09-11 08:30:34'),
(541,3,307,'2026-09-11 08:30:34'),
(542,14,307,'2026-09-11 08:30:34'),
(543,3,303,'2026-09-11 08:30:34'),
(544,14,303,'2026-09-11 08:30:34'),
(545,3,305,'2026-09-11 08:30:34'),
(546,14,305,'2026-09-11 08:30:34'),
(547,3,302,'2026-09-11 08:30:34'),
(548,14,302,'2026-09-11 08:30:34'),
(560,15,301,'2026-09-11 08:30:34'),
(561,1,71,'2026-09-11 08:30:34'),
(562,1,72,'2026-09-11 08:30:34'),
(563,1,73,'2026-09-11 08:30:34'),
(564,1,74,'2026-09-11 08:30:34'),
(565,1,81,'2026-09-11 08:30:34'),
(566,1,82,'2026-09-11 08:30:34'),
(567,1,83,'2026-09-11 08:30:34'),
(568,1,84,'2026-09-11 08:30:34'),
(569,1,91,'2026-09-11 08:30:34'),
(570,1,92,'2026-09-11 08:30:34'),
(571,1,93,'2026-09-11 08:30:34'),
(572,1,95,'2026-09-11 08:30:34'),
(576,2,48,'2026-09-11 08:30:34'),
(577,2,49,'2026-09-11 08:30:34'),
(578,2,71,'2026-09-11 08:30:34'),
(579,2,72,'2026-09-11 08:30:34'),
(580,2,73,'2026-09-11 08:30:34'),
(581,2,74,'2026-09-11 08:30:34'),
(582,2,81,'2026-09-11 08:30:34'),
(583,2,82,'2026-09-11 08:30:34'),
(584,2,83,'2026-09-11 08:30:34'),
(585,2,84,'2026-09-11 08:30:34'),
(586,2,91,'2026-09-11 08:30:34'),
(587,2,92,'2026-09-11 08:30:34'),
(588,2,93,'2026-09-11 08:30:34'),
(589,2,95,'2026-09-11 08:30:34'),
(591,3,71,'2026-09-11 08:30:34'),
(592,3,81,'2026-09-11 08:30:34'),
(593,3,91,'2026-09-11 08:30:34'),
(607,10,103,'2026-09-11 08:46:01'),
(608,10,101,'2026-09-11 08:46:01'),
(609,10,102,'2026-09-11 08:46:01'),
(610,11,103,'2026-09-11 08:46:01'),
(611,11,101,'2026-09-11 08:46:01'),
(612,11,102,'2026-09-11 08:46:01'),
(613,12,103,'2026-09-11 08:46:01'),
(614,12,101,'2026-09-11 08:46:01'),
(615,12,102,'2026-09-11 08:46:01'),
(619,10,342,'2026-09-11 15:23:52'),
(620,11,342,'2026-09-11 15:23:52'),
(621,2,342,'2026-09-11 15:23:52'),
(622,1,342,'2026-09-11 15:23:52'),
(626,1,344,'2026-09-11 15:23:52'),
(627,10,344,'2026-09-11 15:23:52'),
(628,1,343,'2026-09-11 15:23:52'),
(629,10,343,'2026-09-11 15:23:52'),
(637,1,345,'2026-09-11 15:42:18'),
(638,10,345,'2026-09-11 15:42:18'),
(639,1,346,'2026-09-11 15:42:18'),
(640,10,346,'2026-09-11 15:42:18')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`menu_id`=VALUES(`menu_id`),`created_at`=VALUES(`created_at`);

INSERT INTO `sys_role_menu` (`id`,`role_id`,`menu_id`,`created_at`) VALUES
(641,1,347,'2026-09-11 15:42:18'),
(642,10,347,'2026-09-11 15:42:18'),
(644,1,348,'2026-09-11 15:42:18'),
(645,10,348,'2026-09-11 15:42:18'),
(646,1,350,'2026-09-11 15:42:18'),
(647,10,350,'2026-09-11 15:42:18'),
(648,1,349,'2026-09-11 15:42:18'),
(649,10,349,'2026-09-11 15:42:18'),
(651,15,22,'2026-09-11 15:42:18')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`menu_id`=VALUES(`menu_id`),`created_at`=VALUES(`created_at`);

-- ---- sys_role_datascope（2 行）----
INSERT INTO `sys_role_datascope` (`id`,`role_id`,`scope_type`,`scope_value`,`created_at`) VALUES
(12,11,'ENV','prod','2026-09-11 06:10:26'),
(13,11,'ENV','test','2026-09-11 06:10:26')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`role_id`=VALUES(`role_id`),`scope_type`=VALUES(`scope_type`),`scope_value`=VALUES(`scope_value`),`created_at`=VALUES(`created_at`);

-- ---- sys_dict（6 行）----
INSERT INTO `sys_dict` (`id`,`dict_code`,`dict_name`,`built_in`,`status`,`remark`,`created_at`,`updated_at`) VALUES
(1,'app_type','应用类型',1,1,NULL,'2026-09-10 14:07:46','2026-09-10 14:07:46'),
(2,'visibility','接口可见性',1,1,NULL,'2026-09-10 14:07:46','2026-09-10 14:07:46'),
(3,'api_status','接口状态',1,1,NULL,'2026-09-10 14:07:46','2026-09-10 14:07:46'),
(4,'grant_status','授权状态',1,1,NULL,'2026-09-10 14:07:46','2026-09-10 14:07:46'),
(5,'cred_status','密钥状态',1,1,NULL,'2026-09-10 14:07:46','2026-09-10 14:07:46'),
(6,'alarm_level','告警级别',1,1,'仅映射 alarm_rule.alarm_level（告警规则配置等级 1提示/2警告/3严重）。注意：alert.level 是字符串枚举 INFO/WARNING/CRITICAL，与本字典无关；alert.alarm_level 为废弃死列，勿用','2026-09-10 14:07:46','2026-09-11 23:42:09')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`dict_code`=VALUES(`dict_code`),`dict_name`=VALUES(`dict_name`),`built_in`=VALUES(`built_in`),`status`=VALUES(`status`),`remark`=VALUES(`remark`),`created_at`=VALUES(`created_at`),`updated_at`=VALUES(`updated_at`);

-- ---- sys_dict_item（22 行）----
INSERT INTO `sys_dict_item` (`id`,`dict_code`,`item_value`,`item_label`,`sort_order`,`status`) VALUES
(1,'app_type','1','内部系统',1,1),
(2,'app_type','2','外部合作方',2,1),
(3,'app_type','3','测试应用',3,1),
(4,'visibility','1','内部',1,1),
(5,'visibility','2','对外公开',2,1),
(6,'api_status','0','草稿',1,1),
(7,'api_status','1','待审核',2,1),
(8,'api_status','2','已发布',3,1),
(9,'api_status','3','已弃用',4,1),
(10,'api_status','4','已下线',5,1),
(11,'grant_status','0','待审批',1,1),
(12,'grant_status','1','已生效',2,1),
(13,'grant_status','2','已过期',3,1),
(14,'grant_status','3','已撤销',4,1),
(15,'grant_status','4','已驳回',5,1),
(16,'cred_status','1','启用中',1,1),
(17,'cred_status','2','已停用',2,1),
(18,'cred_status','3','已吊销',3,1),
(19,'cred_status','4','已过期',4,1),
(20,'alarm_level','1','提示',1,1),
(21,'alarm_level','2','警告',2,1),
(22,'alarm_level','3','严重',3,1)
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`dict_code`=VALUES(`dict_code`),`item_value`=VALUES(`item_value`),`item_label`=VALUES(`item_label`),`sort_order`=VALUES(`sort_order`),`status`=VALUES(`status`);

-- ---- sys_config（19 行）----
INSERT INTO `sys_config` (`id`,`config_key`,`config_value`,`config_group`,`config_name`,`sensitive`,`built_in`,`remark`,`created_at`,`updated_at`) VALUES
(1,'sign.algorithm','HmacSHA256','SECURITY','签名算法',0,1,'支持 HmacSHA256 / HmacSHA512','2026-09-10 14:11:04','2026-09-10 14:11:04'),
(2,'sign.timestamp.tolerance','300000','SECURITY','时间戳容差（毫秒）',0,1,'默认 ±5 分钟，超出直接拒绝','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(3,'sign.nonce.ttl','600','SECURITY','Nonce 有效期（秒）',0,1,'应 ≥ 2 倍时间戳容差','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(4,'secret.length','32','SECURITY','Secret 长度',0,1,'生成时的随机串长度','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(5,'secret.encrypt.algo','AES-256-GCM','SECURITY','Secret 存储加密算法',1,1,'可逆加密，签名校验需原始值','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(6,'key.rotate.period','180','SECURITY','密钥强制轮换周期（天）',0,1,'超期在概览页告警','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(7,'key.max.valid.days','365','SECURITY','密钥最长有效期（天）',0,1,'到期自动失效','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(8,'external.ip.whitelist.required','true','SECURITY','外部应用强制 IP 白名单',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(9,'login.fail.threshold','5','SECURITY','登录失败锁定阈值',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(10,'session.timeout','480','SECURITY','会话超时（分钟）',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(11,'log.desensitize','true','SECURITY','日志敏感字段脱敏',0,1,'手机号/身份证/银行卡','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(12,'gateway.auth.enabled','true','GATEWAY','是否开启签名校验',1,1,'关闭等于裸奔，仅应急临时关闭','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(13,'gateway.ratelimit.enabled','true','GATEWAY','是否开启限流',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(14,'gateway.default.read.timeout','3000','GATEWAY','默认读取超时（毫秒）',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(15,'audit.log.retention.days','180','LOG','审计日志保留天数',0,1,'等保三级要求 ≥180 天','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(16,'call.log.hot.days','30','LOG','调用日志热数据保留天数',0,1,'MySQL 保留时长','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(17,'approval.enabled','false','DEFAULT','是否开启审批流',0,1,'MVP 关闭，V2 开启','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(18,'export.max.rows','50000','DEFAULT','单次导出最大行数',0,1,'','2026-09-10 14:08:21','2026-09-10 14:08:21'),
(1000,'gk.schema.version','v2','DEFAULT','数据模型版本',0,1,'由 migrate-v2.sql 写入，用于应用启动自检','2026-09-10 14:11:37','2026-09-10 14:11:37')
ON DUPLICATE KEY UPDATE `id`=VALUES(`id`),`config_key`=VALUES(`config_key`),`config_value`=VALUES(`config_value`),`config_group`=VALUES(`config_group`),`config_name`=VALUES(`config_name`),`sensitive`=VALUES(`sensitive`),`built_in`=VALUES(`built_in`),`remark`=VALUES(`remark`),`created_at`=VALUES(`created_at`),`updated_at`=VALUES(`updated_at`);

-- ---- alarm_rule（7 行，PRD「7 条初始化规则」；幂等用 INSERT IGNORE —— 不覆盖运营在页面上的修改）----
-- target_type 为 T11 新增的「评估对象维度」：APP=按应用 / API=按接口（scope_type=1 必填，=2 时为 NULL）；
-- target_ids 为 NULL 表示"该维度下全部对象"（与 channel_ids/receiver_ids 的逗号串约定一致）。
-- 与 docs/sql/migrate-v2.sql §1.10 的同一批种子保持数值一致（本脚本为全新初始化主入口）。
INSERT IGNORE INTO `alarm_rule` (`id`,`rule_name`,`alarm_type`,`scope_type`,`target_type`,`target_ids`,`threshold`,`time_window`,`alarm_level`,`silence_period`,`channel_ids`,`receiver_scope`,`receiver_ids`,`receiver_desc`,`status`) VALUES
(1,'调用失败率告警','FAIL_RATE',   1,'API',NULL,'>5',        5,    3,30,   '1,3','ASSIGNEE',NULL,'各接口负责人',1),
(2,'鉴权失败告警',  'AUTH_FAIL',   1,'APP',NULL,'>10',       5,    3,10,   '1,3','USER',     NULL,'张三、周八',  1),
(3,'配额使用率告警','QUOTA_USAGE', 1,'APP',NULL,'>80',       60,   2,120,  '1',  'ASSIGNEE',NULL,'各应用负责人',1),
(4,'后端超时告警',  'AVG_LATENCY', 1,'API',NULL,'>10000',    5,    2,30,   '1',  'ASSIGNEE',NULL,'各接口负责人',1),
(5,'密钥即将过期',  'KEY_EXPIRE',  1,'APP',NULL,'提前30天',  1440, 2,1440, '3,1','ASSIGNEE',NULL,'各应用负责人',1),
(6,'僵尸接口告警',  'ZOMBIE_API',  1,'API',NULL,'30天无调用',43200,1,10080,'3',  'ASSIGNEE',NULL,'各接口负责人',1),
(7,'QPS 突增告警',  'QPS_SURGE',   2,NULL, NULL,'>200%基线', 5,    2,30,   '1',  'USER',     NULL,'张三',        0);

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 完成提示
-- ============================================================
-- 1. 默认管理员：admin / admin123（请首次登录后立即修改）
-- 2. 调用日志表数据量大时按月归档/清理（可定时任务迁移历史数据）
-- 3. 应用层配置正确的 MySQL 连接信息（application.yml）
-- ============================================================
