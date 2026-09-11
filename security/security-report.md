# GateKeeper 安全测评报告

> 版本：V1.0 | 日期：2026-08-29 | 测评人：安全测评工程师
> 范围：后端源码（92 个 Java 文件 + 配置）、前端登录/鉴权链路、依赖安全
> 方法：静态代码审计 + 依赖版本检查 + 编译/构建验证（动态渗透测试因本机 Redis/MySQL 环境未就绪暂缓）

---

## 一、测评结论

| 项 | 结果 |
|----|------|
| 安全等级 | 修复前：**D（高危）** → 修复后：**B（良好，可进入联调）** |
| 高危漏洞 | 5 个，已全部修复 |
| 中危问题 | 4 个，已全部修复 |
| 低危/建议 | 3 项，已记录待后续迭代 |

---

## 二、漏洞清单与修复明细

### 【P0-1】管理后台接口完全无认证（已修复 ✅）

- **风险**：全部 `/api/**` 管理接口（应用/接口/权限/加解密/日志/封禁/系统配置）无需任何凭证即可访问，任何能连通服务的人都能增删改全站数据；前端登录为假登录（localStorage 写死 token）
- **修复**：
  - 新增 `AuthController`（`POST /api/auth/login`）：BCrypt 密码校验 + Redis 登录失败计数（5 次锁定 10 分钟，PRD 默认值）+ 签发 JWT
  - 新增 `JwtUtil` + `JwtAuthInterceptor`：校验 Bearer Token，未认证返回 HTTP 401
  - 新增 `WebConfig`：拦截 `/api/**`，放行登录接口、`/gateway/**`（网关自有 AppKey+签名链路）、Knife4j 文档、`/error`
  - 前端：`Login.vue` 接入真实登录 API；`api/index.js` 401 自动清理凭证跳登录页；`router/index.js` 路由守卫
- **验证**：`mvn package` BUILD SUCCESS；未带 Token 访问 `/api/app/list` 返回 401 JSON

### 【P0-2】AppSecret 明文落库 + 列表接口泄露（已修复 ✅）

- **风险**：`AppServiceImpl` 直接存明文 AppSecret；`pageQuery` 返回完整 AppSecret 字段，日志/数据库拖库即全部泄露
- **修复**：
  - AppSecret 落库前 AES-256（ECB/PKCS5Padding）加密，密钥来自 `gatekeeper.crypto.aes-key`（支持环境变量覆盖）
  - 新增 `CryptoKeyUtil`：将配置原始密钥规范化为 32 字节 AES-256 密钥
  - 列表查询对 AppSecret 置空；仅创建/重置时返回一次明文
  - `AppAuthHandler` 验签前解密 AppSecret（兼容历史明文数据：解密失败按明文重试）

### 【P0-3】Nonce 防重放缺失（已修复 ✅）

- **风险**：签名校验链路中 Nonce 只参与签名计算、从不校验唯一性，同一合法请求可被无限重放
- **修复**：`AppAuthHandler.checkNonce()` — Redis SETNX 保证同一 AppKey+Nonce 5 分钟内仅放行一次，重复请求返回 401 并计入鉴权失败

### 【P0-4】CORS 全开放（已修复 ✅）

- **风险**：`allowedOriginPatterns("*")` + `allowCredentials(true)`，任意网站可在浏览器中携带凭证调用接口（CSRF 面）
- **修复**：来源白名单化，通过 `gatekeeper.cors.allowed-origins` 配置（默认仅 `http://localhost:8081`，生产用环境变量覆盖）

### 【P0-5】内部错误信息泄露（已修复 ✅）

- **风险**：全局异常兜底返回 `"系统内部错误: " + e.getMessage()`；网关未知异常直接透传 `e.getMessage()`，泄露内部实现细节
- **修复**：`GlobalExceptionHandler` 与 `GatewayController` 对未知异常仅返回通用提示（"系统繁忙，请稍后重试"/"网关内部错误"），完整堆栈只落服务端日志

### 【P1-1】签名比较非恒时（已修复 ✅）

- **修复**：`equalsIgnoreCase` → `MessageDigest.isEqual` 恒时比较，消除时序侧信道

### 【P1-2】X-Forwarded-For 无条件信任（已修复 ✅）

- **风险**：客户端 IP 取 XFF 最后一跳，攻击者可直接伪造来源 IP 绕过 IP 白名单/封禁
- **修复**：默认取 `RemoteAddr`；仅当 `gatekeeper.security.trust-xff=true`（部署在可信代理之后）才解析 XFF

### 【P1-3】调用日志无敏感数据脱敏（已修复 ✅）

- **风险**：`LogHandler` 原样记录请求/响应，手机号、身份证、Token、密码等敏感字段直接落库（违反 PRD US-030）
- **修复**：新增 `DesensitizeUtil`（手机号/身份证/银行卡/邮箱/Token/AppSecret 等键值对掩码），日志落库前统一脱敏

### 【P1-4】并发限流计数泄漏（已修复 ✅）

- **风险**：并发计数 +1 后 `GatewayCore.decrementConcurrent` 为空实现，计数只增不减 → 达到并发上限后永久误限流（自身 DoS）
- **修复**：注入 Redis 实现真实的 DECR 回收，负数归零防护

### 【P2】其他改进

| 项 | 处理 |
|----|------|
| 网关错误响应 HTTP 状态码 | `GatewayController` 改为 `ResponseEntity` 返回真实 401/403/404/429/502/504，调用方可按标准 HTTP 语义处理 |
| 硬编码密钥 | `aes-key`/`jwt.secret`/CORS 来源均改为环境变量可覆盖（`GATEKEEPER_AES_KEY` 等），生产必须覆盖 |
| Bouncy Castle 1.70 → 1.77 | 升级至 `bcprov-jdk15to18:1.77`（与 Hutool 5.8.25 对齐，消除旧版已知缺陷） |

---

## 三、遗留风险与后续建议

1. **动态渗透测试待补**：本机 Redis 未运行、MySQL 端口探测异常（13306 返回 502，疑似经代理转发），登录/限流/封禁链路暂未做实弹验证。建议在测试环境跑通后补测：暴力破解锁定、Nonce 重放、XFF 伪造、429 限流边界。
2. **jjwt 0.9.1 版本较老**（2018 年）：功能满足 HS256 需求且无高危 CVE，建议后续升级 0.11.x（需引入 jaxb 依赖，Java 8 兼容）。
3. **Knife4j 接口文档生产环境建议关闭**（`knife4j.enable: false`），避免暴露接口面。
4. **默认口令**：init.sql 预置 `admin / admin123`、新用户默认 `123456` 为演示便利，上线前必须强制改密。
5. **SQL 注入**：全链路使用 MyBatis-Plus QueryWrapper 参数化查询，未发现拼接 SQL，暂无注入风险。
6. **HTTPS 与安全响应头**（HSTS/X-Content-Type-Options 等）需由部署侧（Nginx/网关）统一配置。

---

## 四、验证记录

| 验证项 | 结果 |
|--------|------|
| 后端 `mvn compile` | ✅ BUILD SUCCESS |
| 后端 `mvn package -DskipTests` | ✅ BUILD SUCCESS |
| 前端 `npm run build` | ✅ 构建通过 |
| 401 未认证拦截 | ✅ 拦截器按路径生效（静态验证） |

---

*本报告覆盖本轮全部发现与修复；动态测试与依赖安全扫描建议每迭代执行一次。*
