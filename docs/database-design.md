# GateKeeper 数据库设计说明

## 一、数据库概述

- **数据库**：MySQL 8.x
- **缓存**：Redis 6+
- **字符编码**：utf8mb4

## 二、ER 关系概览

```
app (应用)
 ├── app_ip_whitelist (IP白名单, 1:N)
 ├── app_rate_limit (限流配置, 1:1)
 ├── app_encryption_config (加解密配置, 1:1)
 └── app_api_permission (接口权限, 1:N)
      └── api_interface (接口)

api_group (接口分组)
 └── api_interface (接口, 1:N)
      └── api_encryption_config (加解密配置, 1:1)

api_call_log (调用日志, 关联app + interface)

ip_ban (IP封禁, 可选关联app)
security_event (安全事件, 可选关联app)
security_rule (安全检测规则, 独立)

sys_user → sys_user_role → sys_role
sys_operation_log (操作审计)
```

## 三、核心表说明

### 3.1 app — 应用表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | 主键 |
| app_name | VARCHAR(128) | 应用名称 |
| app_key | VARCHAR(64) | AppKey，唯一，32位随机字符串 |
| app_secret | VARCHAR(256) | AppSecret，AES加密后存储 |
| status | TINYINT | 1=启用, 0=停用, 2=已过期 |
| description | VARCHAR(512) | 描述 |
| expire_time | DATETIME | 到期时间，NULL=永不过期 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 3.2 api_call_log — 调用日志表

日志数据量大时按月归档/清理（MySQL 不采用物理分区，通过定时任务迁移历史数据到归档表或直接清理）。

| 字段 | 说明 |
|------|------|
| app_id / app_name | 调用方应用 |
| interface_id / interface_path | 请求接口 |
| request_time | 请求时间（分区键） |
| request_params | 入参（加密接口存密文） |
| response_data | 响应（加密接口存密文） |
| response_status | HTTP 状态码 |
| cost_time | 耗时(ms) |
| client_ip | 调用方IP |
| encryption_algorithm | 加密算法 |
| is_rate_limited | 是否被限流 |
| is_blocked | 是否被拦截 |
| block_reason | 拦截原因 |

### 3.3 ip_ban — IP封禁表

| 字段 | 说明 |
|------|------|
| ip_address | 被封禁的IP |
| app_id | 关联应用ID，NULL=全局封禁 |
| ban_reason | 封禁原因 |
| ban_start_time | 封禁开始时间 |
| ban_end_time | 封禁结束时间 |
| ban_status | 1=封禁中, 0=已解封 |
| ban_type | MANUAL=手动, AUTO=自动 |

## 四、Redis 数据结构设计

### 4.1 限流计数

```
Key: rate_limit:qps:{appId}
Type: String (INCR + EXPIRE)
TTL: 1秒
Desc: 每秒请求计数，用于QPS限流

Key: rate_limit:concurrent:{appId}
Type: String (INCR/DECR)
Desc: 当前并发数，请求开始+1，结束-1

Key: rate_limit:daily:{appId}:{yyyyMMdd}
Type: String (INCR)
TTL: 到当日23:59:59
Desc: 日调用计数
```

### 4.2 IP 封禁状态

```
Key: ip_ban:{ip}:{appId}     (应用级封禁)
Key: ip_ban:{ip}:global      (全局封禁)
Type: String (值为封禁原因)
TTL: 封禁剩余秒数
Desc: 网关查询Redis判断是否封禁，O(1)复杂度
```

### 4.3 实时统计缓存

```
Key: stats:realtime:{yyyyMMdd}
Type: Hash
Fields: total_calls / success_calls / fail_calls / rate_limited / blocked
TTL: 7天
Desc: 大屏实时统计数据，定时聚合写入

Key: stats:app_rank:{yyyyMMdd}
Type: ZSet (member=appId, score=调用量)
TTL: 1天
Desc: 应用调用量排行

Key: stats:iface_rank:{yyyyMMdd}
Type: ZSet (member=interfaceId, score=调用量)
TTL: 1天
Desc: 接口热度排行
```

## 五、安全策略

### 5.1 AppSecret 存储

- 生成：64位随机字符串（SecureRandom）
- 存储：AES-256 加密后存入数据库 `app_secret` 字段
- 运行时：解密后缓存在内存中，不频繁访问数据库

### 5.2 加密密钥存储

- 对称密钥（SM4/AES密钥）：数据库中 AES-256 加密存储
- 非对称私钥（SM2）：数据库中加密存储
- 所有密钥在日志中脱敏（打印为 `****`）
- API 响应中不返回密钥字段

### 5.3 日志归档策略

- `api_call_log` 日志保留 90 天
- 超期日志通过定时任务归档到历史表或直接清理
- 数据量持续增大时可迁移至按月分区表或数据仓库
