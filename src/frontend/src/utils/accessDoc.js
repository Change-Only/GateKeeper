/**
 * 接入文档内容源（T14）
 * ------------------------------------------------------------------
 * 为什么要单独一个模块：
 *   接入文档现在有两个出口 —— ① 页面右侧展示（AccessDoc.vue，含公开页与登录后 Layout 页）；
 *   ② 创建应用后导出的 Markdown 文件（AppList.vue）。两处若各写一份示例代码，
 *   版本一发散就会变成「文件里的签名算法和页面上讲的不一样」，这是最坑文档使用者的缺陷。
 *   ⇒ 代码片段、请求头表、错误码表、工具方法表**全部只在这里定义一次**，
 *     页面渲染与文件导出都从这里取。
 *
 * 签名口径（必须与后端一致，勿改）：
 *   sign = SM3(AppKey + AppSecret + Timestamp + Nonce)，无分隔符拼接，十六进制小写。
 *   后端实现见 security/... 与 util/JwtUtil 之外的网关鉴权链 AppAuthHandler + SmUtil.sm3。
 */

/** 网关认证请求头（4 个，缺一不可） */
export const GK_HEADERS = [
  { name: 'X-App-Key', desc: '应用唯一标识，管理员创建应用时分配', example: 'ak_prod_9f2c8e1b…' },
  { name: 'X-Timestamp', desc: '毫秒级时间戳，与服务器偏差超过 5 分钟拒绝', example: '1772438400000' },
  { name: 'X-Nonce', desc: '随机串（建议 UUID），5 分钟内不可重复（防重放）', example: 'e7b8a4d0-…' },
  { name: 'X-Signature', desc: '请求签名，算法见「签名算法」', example: 'sm3 摘要的十六进制串' }
]

/** 网关错误码 */
export const GK_ERRORS = [
  { code: '400', msg: '参数错误', desc: '请求参数缺失或格式不正确' },
  { code: '401', msg: '鉴权失败', desc: 'AppKey 无效 / 缺少请求头 / 时间戳过期 / Nonce 重复 / 签名不匹配，请检查密钥与签名算法' },
  { code: '403', msg: '无权限或被拒绝', desc: '应用停用或过期、IP 不在白名单、IP 被封禁，或未获该接口授权' },
  { code: '404', msg: '接口不存在', desc: '接口路径错误，或接口已下线' },
  { code: '429', msg: '请求过于频繁', desc: '触发限流，请按响应提示降低调用频率' },
  { code: '502', msg: '上游服务异常', desc: '后端业务服务返回异常，请联系接口提供方' },
  { code: '504', msg: '上游服务超时', desc: '后端业务服务响应超时，请稍后重试' }
]

/** Java 工具类方法说明 */
export const JAVA_METHODS = [
  { sig: 'new GkSigner(appKey, appSecret, gatewayUrl)', desc: '构造。gatewayUrl 是网关入口，如 http://gateway.example.com:8080' },
  { sig: 'String sign(long timestamp, String nonce)', desc: '计算签名：SM3(AppKey+AppSecret+Timestamp+Nonce)' },
  { sig: 'Map<String,String> buildHeaders()', desc: '生成 4 个认证请求头（内部自动取当前毫秒时间戳与随机 Nonce）' },
  { sig: 'String post(String interfacePath, String jsonBody)', desc: '带签名 POST 到网关并返回响应体；interfacePath 如 /user/detail' },
  { sig: 'String post(String interfacePath, String jsonBody, Map<String,String> extraHeaders)', desc: '同上，额外附加自定义请求头（做加解密时放密文标记等）' }
]

/** JavaScript 工具类方法说明 */
export const JS_METHODS = [
  { sig: 'new GkSigner(appKey, appSecret, gatewayUrl)', desc: '构造。gatewayUrl 末尾斜杠会被自动去掉' },
  { sig: 'sign(timestamp, nonce)', desc: '计算签名，返回十六进制小写字符串' },
  { sig: 'buildHeaders()', desc: '生成 4 个认证请求头对象（可直接给 fetch / axios 使用）' },
  { sig: 'request(interfacePath, body, options)', desc: '带签名调用网关，返回 Promise<Response>（options.method 可传 GET/PUT 等）' },
  { sig: 'GkSigner.uuid()', desc: '静态方法，生成随机 Nonce' }
]

/** Java 工具类（完整可复制） */
export const JAVA_UTIL_CODE = `// Maven 依赖：
//   <dependency><groupId>cn.hutool</groupId><artifactId>hutool-all</artifactId><version>5.8.25</version></dependency>
//   <dependency><groupId>org.bouncycastle</groupId><artifactId>bcprov-jdk15to18</artifactId><version>1.76</version></dependency>
//   （Hutool 的 SmUtil.sm3 依赖 BouncyCastle 提供国密算法实现）
package com.example.gk;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.SmUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * GateKeeper 网关签名工具类
 * 签名口径：SM3(AppKey + AppSecret + Timestamp + Nonce)，十六进制小写
 */
public class GkSigner {

    private final String appKey;
    private final String appSecret;
    /** 网关入口，如 http://gateway.example.com:8080（不含 /gateway 后缀） */
    private final String gatewayUrl;

    public GkSigner(String appKey, String appSecret, String gatewayUrl) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.gatewayUrl = gatewayUrl == null ? "" : gatewayUrl.replaceAll("/+$", "");
    }

    /** 计算签名 */
    public String sign(long timestamp, String nonce) {
        return SmUtil.sm3(appKey + appSecret + timestamp + nonce);
    }

    /** 生成 4 个认证请求头（时间戳 / Nonce 每次调用都重新生成） */
    public Map<String, String> buildHeaders() {
        long timestamp = System.currentTimeMillis();
        String nonce = IdUtil.fastSimpleUUID();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-App-Key", appKey);
        headers.put("X-Timestamp", String.valueOf(timestamp));
        headers.put("X-Nonce", nonce);
        headers.put("X-Signature", sign(timestamp, nonce));
        return headers;
    }

    /** 调用网关接口，返回响应体 */
    public String post(String interfacePath, String jsonBody) {
        return post(interfacePath, jsonBody, null);
    }

    /** 调用网关接口（附加自定义请求头），返回响应体 */
    public String post(String interfacePath, String jsonBody, Map<String, String> extraHeaders) {
        String path = interfacePath == null ? "" : interfacePath.replaceAll("^/+", "");
        String url = gatewayUrl + "/gateway/" + path;
        HttpRequest req = HttpRequest.post(url)
                .addHeaders(buildHeaders())
                .timeout(10000);
        if (extraHeaders != null) {
            req.addHeaders(extraHeaders);
        }
        if (jsonBody != null) {
            req.body(jsonBody);
        }
        HttpResponse resp = req.execute();
        return resp.body();
    }
}`

/** Java 调用示例 */
export const JAVA_CALL_CODE = `import com.example.gk.GkSigner;

public class Demo {
    public static void main(String[] args) {
        // 1) 构造签名器：AppKey / AppSecret 来自「应用详情 → 密钥凭证」
        GkSigner signer = new GkSigner(
                "ak_prod_9f2c8e1b1a2b3c4d",
                "在此填入 AppSecret（仅本地持有，切勿提交到代码库）",
                "http://gateway.example.com:8080");

        // 2) 直接调用已授权的接口
        String resp = signer.post("/user/detail", "{\\"userId\\":\\"10001\\"}");
        System.out.println(resp);

        // 3) 需要自己控制 HTTP 客户端时，只取请求头即可
        //    Map<String, String> headers = signer.buildHeaders();
        //    headers.forEach((k, v) -> System.out.println(k + ": " + v));
    }
}`

/** JavaScript 工具类（完整可复制，浏览器 / Node 通用） */
export const JS_UTIL_CODE = `// 依赖：npm i sm-crypto
//   浏览器直引：<script src="https://unpkg.com/sm-crypto/dist/sm-crypto.min.js"></script>
//              然后把下面的 require 换成：const sm3 = window.smCrypto.sm3
const { sm3 } = require('sm-crypto')

/**
 * GateKeeper 网关签名工具类
 * 签名口径：SM3(AppKey + AppSecret + Timestamp + Nonce)，十六进制小写
 */
class GkSigner {
  constructor(appKey, appSecret, gatewayUrl) {
    this.appKey = appKey
    this.appSecret = appSecret
    this.gatewayUrl = (gatewayUrl || '').replace(/\\/+$/, '')
  }

  /** 生成随机 Nonce */
  static uuid() {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
      const r = (Math.random() * 16) | 0
      const v = c === 'x' ? r : (r & 0x3) | 0x8
      return v.toString(16)
    })
  }

  /** 计算签名 */
  sign(timestamp, nonce) {
    return sm3(this.appKey + this.appSecret + timestamp + nonce)
  }

  /** 生成 4 个认证请求头 */
  buildHeaders() {
    const timestamp = Date.now()
    const nonce = GkSigner.uuid()
    return {
      'Content-Type': 'application/json',
      'X-App-Key': this.appKey,
      'X-Timestamp': String(timestamp),
      'X-Nonce': nonce,
      'X-Signature': this.sign(timestamp, nonce)
    }
  }

  /** 调用网关接口，返回 fetch 的 Response */
  request(interfacePath, body, options) {
    const opts = options || {}
    const path = String(interfacePath || '').replace(/^\\/+/, '')
    const init = {
      method: opts.method || 'POST',
      headers: Object.assign({}, this.buildHeaders(), opts.headers || {})
    }
    if (init.method !== 'GET' && body !== undefined && body !== null) {
      init.body = typeof body === 'string' ? body : JSON.stringify(body)
    }
    return fetch(this.gatewayUrl + '/gateway/' + path, init)
  }
}

module.exports = GkSigner`

/** JavaScript 调用示例（浏览器） */
export const JS_CALL_CODE = `import GkSigner from './gk-sign'

// AppKey / AppSecret 来自「应用详情 → 密钥凭证」
const signer = new GkSigner(
  'ak_prod_9f2c8e1b1a2b3c4d',
  '在此填入 AppSecret',
  'http://gateway.example.com:8080'
)

async function callUserDetail() {
  const resp = await signer.request('/user/detail', { userId: '10001' })
  // 注意：网关的鉴权失败会返回 401，业务异常可能由上游以 200 包体返回
  if (!resp.ok) {
    console.error('调用失败', resp.status, await resp.text())
    return
  }
  console.log(await resp.json())
}

callUserDetail()`

/** Node.js 调用示例 */
export const NODE_CALL_CODE = `// Node.js 18+ 自带 global fetch；低版本请先 npm i node-fetch 并 global.fetch = require('node-fetch')
const GkSigner = require('./gk-sign')

const signer = new GkSigner(
  process.env.GK_APP_KEY,
  process.env.GK_APP_SECRET,
  process.env.GK_GATEWAY_URL || 'http://gateway.example.com:8080'
)

// 建议把密钥放环境变量，不要硬编码进仓库
signer.request('/user/detail', { userId: '10001' })
  .then((resp) => resp.json().then((data) => console.log(resp.status, data)))
  .catch((err) => console.error('调用异常', err))`

/** curl 调用示例 */
export const CURL_CODE = `# 1. 生成签名（openssl 不支持 SM3，需要 gmssl / hutool 等）
APP_KEY="ak_prod_9f2c8e1b1a2b3c4d"
APP_SECRET="在此填入 AppSecret"
TIMESTAMP=$(date +%s%3N)
NONCE=$(cat /proc/sys/kernel/random/uuid | tr -d '-')
SIGN=$(printf '%s' "\${APP_KEY}\${APP_SECRET}\${TIMESTAMP}\${NONCE}" | gmssl sm3)

# 2. 调用网关（网关入口 = http://<网关地址>/gateway/<接口路径>）
curl -X POST "http://gateway.example.com:8080/gateway/user/detail" \\
  -H "X-App-Key: \${APP_KEY}" \\
  -H "X-Timestamp: \${TIMESTAMP}" \\
  -H "X-Nonce: \${NONCE}" \\
  -H "X-Signature: \${SIGN}" \\
  -H "Content-Type: application/json" \\
  -d '{"userId":"10001"}'`

/**
 * 生成「某个应用专属」的接入文档（Markdown）。
 *
 * @param {object} app  应用对象，至少含 appName / appKey；可选 status / description / expireTime
 * @param {object} [opt] { gatewayUrl, generatedAt, operator }
 * @returns {string} Markdown 文本
 */
export function buildAccessDocMarkdown(app, opt) {
  const a = app || {}
  const o = opt || {}
  const gatewayUrl = o.gatewayUrl || defaultGatewayUrl()
  const generatedAt = o.generatedAt || formatNow()

  return `# ${a.appName || '应用'} 接入文档

> 本文档由 GateKeeper 管理后台生成，可直接交给调用方开发同学。
> 生成时间：${generatedAt}${o.operator ? '　生成人：' + o.operator : ''}

## 0. 本应用信息

| 项 | 值 |
| --- | --- |
| 应用名称 | ${a.appName || '—'} |
| AppKey | \`${a.appKey || '—'}\` |
| 应用状态 | ${a.status === 1 ? '已启用' : a.status === 0 ? '已停用' : a.status === 2 ? '已过期' : '—'} |
| 过期时间 | ${a.expireTime ? String(a.expireTime).replace('T', ' ') : '永不过期'} |
| 网关入口 | \`${gatewayUrl}/gateway/<接口路径>\` |

> 🔴 **AppSecret 不在本文档中**。AppSecret 明文只在「创建凭证 / 灰度轮换」时展示一次；
> 若已丢失，请用「应用详情 → 密钥凭证 → 查看密钥」输入当前账号密码二次确认后查看。

## 1. 三步完成接入

1. **申请密钥** —— 管理员在「应用管理」创建应用与凭证，得到 AppKey / AppSecret。
2. **获得授权** —— 管理员在「接口授权总览」把你的应用与目标接口、环境关联起来。
3. **签名调用** —— 每次请求携带 4 个认证请求头，签名通过后即可调用已授权接口。

## 2. 认证请求头

| 请求头 | 说明 | 示例 |
| --- | --- | --- |
${GK_HEADERS.map((h) => `| \`${h.name}\` | ${h.desc} | \`${h.example}\` |`).join('\n')}

## 3. 签名算法

\`\`\`
sign = SM3 ( AppKey + AppSecret + Timestamp + Nonce )
\`\`\`

- 四个参数按上述顺序**直接字符串拼接**（无分隔符），对拼接结果做 SM3 摘要，取**十六进制小写**输出。
- **Timestamp** 为毫秒级时间戳，与服务器时间偏差超过 5 分钟将被拒绝。
- **Nonce** 为每次请求唯一的随机串（建议 UUID），同一 Nonce 5 分钟内只允许使用一次。
- AppSecret 只参与本地签名计算，**切勿在网络上传输，切勿提交到代码库**。

## 4. Java 工具类

\`\`\`java
${JAVA_UTIL_CODE}
\`\`\`

### 4.1 方法说明

| 方法 | 说明 |
| --- | --- |
${JAVA_METHODS.map((m) => `| \`${m.sig}\` | ${m.desc} |`).join('\n')}

### 4.2 调用示例

\`\`\`java
${JAVA_CALL_CODE}
\`\`\`

## 5. JavaScript 工具类

\`\`\`javascript
${JS_UTIL_CODE}
\`\`\`

### 5.1 方法说明

| 方法 | 说明 |
| --- | --- |
${JS_METHODS.map((m) => `| \`${m.sig}\` | ${m.desc} |`).join('\n')}

### 5.2 浏览器调用示例

\`\`\`javascript
${JS_CALL_CODE}
\`\`\`

### 5.3 Node.js 调用示例

\`\`\`javascript
${NODE_CALL_CODE}
\`\`\`

## 6. curl 调用示例

\`\`\`bash
${CURL_CODE}
\`\`\`

## 7. 错误码

| HTTP 状态码 | 含义 | 排查建议 |
| --- | --- | --- |
${GK_ERRORS.map((e) => `| ${e.code} | ${e.msg} | ${e.desc} |`).join('\n')}

## 8. 安全与加密说明

- 若应用或目标接口在「加解密管理」中配置了加密策略，请求体需按配置算法（SM2 / SM4 / AES）加密后传输，响应同样为密文。
- 网关内置限流、高频调用检测、异常入参检测与 IP 封禁，请合理控制调用频率。
- 连续鉴权失败会触发来源 IP 自动封禁；如被误封，请联系管理员在「封禁管理」中解封。
`
}

/** 默认网关入口：按当前访问地址推导（生产环境请替换为真实网关域名） */
export function defaultGatewayUrl() {
  if (typeof window === 'undefined' || !window.location) return 'http://gateway.example.com:8080'
  return window.location.origin + '/api'
}

/** 便于页面展示的当前时间 */
export function formatNow() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
    ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

/** 文件名里的非法字符替换掉（Windows / macOS 都安全） */
export function safeFileName(name) {
  return String(name || 'app').replace(/[\\/:*?"<>|]/g, '_').trim() || 'app'
}

/**
 * 触发浏览器下载一个文本文件。
 *
 * @param {string} filename 文件名（含扩展名）
 * @param {string} content  文本内容
 * @param {string} [mime]   MIME 类型，默认 text/markdown
 */
export function downloadTextFile(filename, content, mime) {
  const type = (mime || 'text/markdown') + ';charset=utf-8'
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 立即 revoke 在部分浏览器会打断下载，放到下一轮事件循环
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/**
 * 一步到位：为指定应用导出接入文档。
 *
 * @returns {string} 实际使用的文件名
 */
export function exportAccessDoc(app, opt) {
  const markdown = buildAccessDocMarkdown(app, opt)
  const name = safeFileName(app && app.appName) + '-接入文档.md'
  downloadTextFile(name, markdown)
  return name
}
