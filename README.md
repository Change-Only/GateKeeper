# GateKeeper — API 集中权限管理与安全网关系统

## 项目简介

GateKeeper 是一个企业级 API 安全网关管理平台，将企业内各后端系统的接口统一接入、集中管控，对外提供标准化的 API 调用入口。通过应用维度的权限管理、IP 白名单、频率限制、国密加解密、异常调用检测与 IP 封禁，实现"谁可以调用什么接口、从哪里调用、多久调用一次、传输是否加密、异常是否可发现"的全面管控，并对每次调用进行完整的日志审计与数据大屏可视化。

**核心价值**：统一入口、权限可控、加密传输、流量可限、异常可防、调用可审、安全可溯、态势可视。

---

## 核心功能

| 模块 | 说明 |
|------|------|
| 应用管理 | 应用注册、AppKey/AppSecret 生成、启停、**到期时间设置**、**IP 白名单**、**调用频率限制** |
| 接口管理 | 接口注册、分组管理、批量导入、**接口级加解密配置**、超时配置 |
| 权限管理 | 应用-接口授权、按分组授权、批量授权、授权矩阵视图 |
| 加解密管理 | **SM2/SM3/SM4 国密算法 + AES/MD5/SHA256 国际算法**，按应用/接口维度独立配置 |
| 网关代理 | IP 白名单 → 封禁检查 → 应用校验 → 频率限制 → 权限校验 → 加解密 → 转发 → 日志 |
| 调用日志 | 全链路调用记录：应用、接口、时间、入参、响应、耗时、状态码、IP、加密算法、限流/拦截标记 |
| 安全防护 | **异常调用检测**（高频/异常时段/连续失败/异常入参/权限越界）、**IP 封禁**（手动/自动）、告警通知 |
| 告警中心 | **运营告警中心**：按等级（INFO/WARNING/CRITICAL）汇聚网关内部错误、限流、自动封禁、异常入参等告警；支持未读统计、标记已读/全部已读、处置跟进；顶栏铃铛实时红点提示与最近告警下拉 |
| 数据大屏 | **实时调用量、应用访问排行、接口热度、安全态势、调用趋势**，支持投屏展示 |
| 统计仪表盘 | 调用量趋势、接口热度、应用排行、错误率、耗时分布（P50/P90/P99） |
| 系统管理 | 系统配置、用户管理、角色权限、操作审计日志 |

---

## 网关请求处理链路

```
应用请求 → 应用校验(签名/时间戳/Nonce防重放) → IP白名单校验 → IP封禁检查 → 频率限制
    → 权限校验 → 入参解密 → 请求转发 → 响应加密 → 调用日志 → 返回响应
```

---

## 技术栈

| 层面 | 技术选型 |
|------|---------|
| 前端 | Vue 2.x + Element UI + Axios + Vue Router + Vuex + **ECharts 5.x（大屏）** |
| 后端 | Java 8 + Spring Boot 2.7.x + MyBatis-Plus + Hutool |
| 加解密 | **Bouncy Castle（SM2/SM3/SM4）+ JCE（AES）** |
| 限流/缓存 | **Redis 6+（令牌桶限流 / 计数 / 封禁状态）** |
| 数据库 | MySQL 8.x |
| 构建 | Maven 3.6+ / Vue CLI 4.x |
| API 文档 | Knife4j (Swagger) |

---

## 项目目录结构

```
GateKeeper/
├── docs/                  # 产品文档、PRD、API 文档
│   ├── PRD.md             # 产品需求文档
│   ├── api.md             # API 接口文档
│   ├── database-design.md # 数据库设计说明
│   └── release-report.md  # 最终交付报告
├── design/                # UI 设计稿、原型
│   ├── spec.md            # 设计规范
│   ├── 大屏/             # 数据大屏设计稿
│   └── *.html             # 页面设计稿
├── src/                   # 前后端代码
│   ├── frontend/          # Vue2 前端项目
│   │   ├── src/
│   │   │   ├── views/     # 页面组件
│   │   │   │   ├── dashboard/    # 统计仪表盘
│   │   │   │   ├── screen/       # 数据大屏
│   │   │   │   ├── app/          # 应用管理
│   │   │   │   ├── interface/    # 接口管理
│   │   │   │   ├── permission/   # 权限管理
│   │   │   │   ├── encryption/   # 加解密配置
│   │   │   │   ├── log/          # 调用日志
│   │   │   │   ├── security/     # 安全防护
│   │   │   │   └── system/       # 系统管理
│   │   │   ├── components/# 公共组件
│   │   │   ├── router/    # 路由配置
│   │   │   ├── store/     # Vuex 状态管理
│   │   │   ├── api/       # API 请求封装
│   │   │   └── utils/     # 工具函数（含加密工具）
│   │   └── package.json
│   └── backend/           # Java8 后端项目
│       ├── src/main/java/com/gatekeeper/
│       │   ├── config/        # 配置类
│       │   ├── controller/    # 控制器层
│       │   ├── service/       # 业务逻辑层
│       │   ├── mapper/        # 数据访问层
│       │   ├── entity/        # 实体类
│       │   ├── gateway/       # 网关代理核心
│       │   │   ├── handler/   # 请求处理链路
│       │   │   │   ├── IpWhitelistHandler.java      # IP白名单校验
│       │   │   │   ├── IpBanCheckHandler.java       # IP封禁检查
│       │   │   │   ├── AppAuthHandler.java           # 应用状态校验
│       │   │   │   ├── RateLimitHandler.java        # 频率限制
│       │   │   │   ├── PermissionHandler.java       # 权限校验
│       │   │   │   ├── EncryptionHandler.java       # 加解密处理
│       │   │   │   ├── ForwardHandler.java           # 请求转发
│       │   │   │   └── LogHandler.java               # 日志记录
│       │   │   └── GatewayCore.java                  # 网关核心调度
│       │   ├── crypto/       # 加解密模块
│       │   │   ├── sm2/      # SM2 实现
│       │   │   ├── sm3/      # SM3 实现
│       │   │   ├── sm4/      # SM4 实现
│       │   │   ├── aes/      # AES 实现
│       │   │   └── CryptoService.java # 统一加解密服务
│       │   ├── ratelimit/    # 限流模块
│       │   ├── security/     # 安全检测模块
│       │   │   ├── detector/ # 异常检测器
│       │   │   ├── banner/   # IP封禁管理
│       │   │   └── alert/    # 告警通知
│       │   ├── interceptor/  # 拦截器
│       │   └── common/       # 公共组件
│       ├── src/main/resources/
│       │   ├── application.yml
│       │   ├── mapper/       # MyBatis XML
│       │   └── sql/init.sql   # 数据库初始化脚本
│       └── pom.xml
├── tests/                 # 测试（后端单元测试位于 src/backend/src/test，覆盖加解密/脱敏/鉴权/网关上下文/限流等）
├── security/              # 安全测评报告
├── README.md              # 项目说明（本文件）
└── project.json           # 项目元信息
```

---

## 角色分工

| 角色 | 职责 | 主要产出 |
|------|------|---------|
| 项目负责人 | 统筹规划、任务分解、进度管控、最终验收 | project.json, README.md, release-report.md |
| 产品经理 | 需求分析、PRD 编写、用户故事、验收标准 | docs/PRD.md |
| UI 设计师 | 界面设计、**大屏设计**、交互原型、设计规范 | design/ 目录 |
| 前端开发 | Vue2 前端编码、**大屏可视化**、接口对接 | src/frontend/ |
| 后端开发 | Java8 后端编码、网关核心、**加解密**、**限流**、**安全检测**、数据库设计 | src/backend/, docs/api.md |
| 安全测评 | 安全审计、**加解密验证**、**防护有效性验证**、安全报告 | security/security-report.md |
| 测试工程师 | 测试用例、功能测试、**加解密测试**、**限流测试**、UAT 验收 | tests/ 目录 |

---

## 里程碑计划

```
需求阶段  →  设计阶段  →  开发阶段  →  测试阶段  →  交付阶段
   PRD       UI+大屏设计    前后端代码     测试+安全报告   交付报告
```

1. **需求阶段**：完成 PRD，覆盖全部 10 大核心模块
2. **设计阶段**：完成 UI 设计稿（含数据大屏）和数据库 ER 设计
3. **开发阶段**：前后端编码，网关代理完整链路打通（含 IP 白名单/限流/加解密/安全检测）
4. **测试阶段**：功能测试 + 安全测评，加解密和限流测试覆盖
5. **交付阶段**：最终验收，生成交付报告

---

## 快速开始

### 方式一：Docker 一键部署（推荐）

```bash
# 1. 准备环境变量（必填三项，缺一不可——启动自检会拒绝启动）
cp .env.example .env
# 编辑 .env 填入：GATEKEEPER_JWT_SECRET / GATEKEEPER_AES_KEY / GATEKEEPER_DB_PASSWORD
# 生成随机密钥：openssl rand -base64 32

# 2. 启动全部服务（MySQL + Redis + 后端网关 + 前端 Nginx）
docker compose up -d

# 3. 访问 http://localhost:8081
```

> 安全说明：本项目采用**激进密钥策略**——JWT/AES/数据库密码不设任何默认值，
> `SecurityStartupCheck` 在启动阶段强校验（缺失、过短、等于历史默认值均拒绝启动），
> 杜绝默认密钥被提交到仓库后可伪造管理员令牌的风险。

### 方式二：本地开发

```bash
# 后端（需先导出环境变量，否则启动自检不通过）
export GATEKEEPER_JWT_SECRET=$(openssl rand -base64 32)
export GATEKEEPER_AES_KEY=$(openssl rand -base64 32)
export GATEKEEPER_DB_PASSWORD=你的数据库密码
cd src/backend
mvn clean package
java -jar target/gatekeeper-1.0.0.jar

# 前端
cd src/frontend
npm install
npm run serve
```

---

## 协作说明

本项目采用 WorkBuddy 多角色协作开发模式，各角色在独立任务中完成各自工作，通过共享工作空间目录传递上下文。
