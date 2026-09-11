<template>
  <!-- 接入文档页：公开可访问（无需登录），风格对齐登录页品牌 -->
  <div class="doc-wrap">
    <!-- 顶部品牌条 -->
    <header class="doc-header">
      <div class="header-inner">
        <div class="brand">
          <svg viewBox="0 0 48 48" fill="none" class="logo">
            <path d="M24 4 L42 10 V22 C42 34 34 41 24 44 C14 41 6 34 6 22 V10 Z" fill="rgba(79,140,255,.15)" stroke="#4f8cff" stroke-width="2.5"/>
            <path d="M17 24 l5 5 9 -10" stroke="#6ea8ff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <div>
            <div class="t1">GateKeeper 接入文档</div>
            <div class="t2">API 集中权限管理与安全网关 · 调用方接入指南</div>
          </div>
        </div>
        <el-button v-if="!loggedIn" size="small" round @click="$router.push('/login')">返回登录</el-button>
      </div>
    </header>

    <main class="doc-main">
      <!-- 概要卡 -->
      <section class="hero">
        <h1>三步完成接入</h1>
        <p>所有业务接口统一通过网关入口 <code>POST /gateway/&lt;接口路径&gt;</code> 调用。你需要先向管理员申请 <b>AppKey / AppSecret</b>，再按下述规则为每次请求生成签名。</p>
        <div class="steps">
          <div class="step"><div class="no">1</div><div><b>申请密钥</b><span>管理员在「应用管理」创建应用，获得 AppKey 与 AppSecret（Secret 仅展示一次，请妥善保存）</span></div></div>
          <div class="step"><div class="no">2</div><div><b>获得授权</b><span>管理员在「权限管理」为你的应用授予目标接口的调用权限</span></div></div>
          <div class="step"><div class="no">3</div><div><b>签名调用</b><span>每次请求携带 4 个认证请求头，签名通过后即可调用已授权接口</span></div></div>
        </div>
      </section>

      <!-- 认证请求头 -->
      <section class="block">
        <h2><i class="el-icon-key"></i> 认证请求头</h2>
        <p class="desc">每次调用必须同时携带以下 4 个请求头，缺一不可：</p>
        <el-table :data="headers" size="small" border>
          <el-table-column prop="name" label="请求头" width="140">
            <template slot-scope="{ row }"><code class="hd">{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column prop="desc" label="说明" />
          <el-table-column prop="example" label="示例" width="220">
            <template slot-scope="{ row }"><code>{{ row.example }}</code></template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 签名算法 -->
      <section class="block">
        <h2><i class="el-icon-lock"></i> 签名算法（国密 SM3）</h2>
        <div class="formula">sign = <b>SM3</b> ( AppKey + AppSecret + Timestamp + Nonce )</div>
        <ul class="rules">
          <li>四个参数按上述顺序<b>直接字符串拼接</b>（无分隔符），对拼接结果做 <b>SM3 摘要</b>，取<b>十六进制小写</b>输出。</li>
          <li><b>Timestamp</b> 为毫秒级时间戳，与服务器时间偏差超过 <b>5 分钟</b>将被拒绝。</li>
          <li><b>Nonce</b> 为每次请求唯一的随机串（建议 UUID），同一 Nonce <b>5 分钟内只允许使用一次</b>（防重放）。</li>
          <li>AppSecret 仅参与本地签名计算，<b>切勿在网络中传输</b>。</li>
        </ul>
      </section>

      <!-- 调用示例 -->
      <section class="block">
        <h2><i class="el-icon-document-copy"></i> 调用示例</h2>
        <el-tabs value="java">
          <el-tab-pane label="Java (Hutool)" name="java">
            <pre><code class="java">{{ javaCode }}</code></pre>
          </el-tab-pane>
          <el-tab-pane label="curl" name="curl">
            <pre><code class="bash">{{ curlCode }}</code></pre>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- 错误码 -->
      <section class="block">
        <h2><i class="el-icon-warning-outline"></i> 错误码</h2>
        <el-table :data="errors" size="small" border>
          <el-table-column prop="code" label="HTTP 状态码" width="120" />
          <el-table-column prop="msg" label="含义" width="180" />
          <el-table-column prop="desc" label="排查建议" />
        </el-table>
      </section>

      <!-- 安全说明 -->
      <section class="block">
        <h2><i class="el-icon-safety-certificate"></i> 安全与加密说明</h2>
        <ul class="rules">
          <li>若你的应用或目标接口在「加解密管理」中配置了加密策略，请求 Body 需按配置的算法（SM2 / SM4 / AES）加密后传输，响应同样为密文。</li>
          <li>网关内置限流、高频调用检测、异常入参检测与 IP 封禁，请合理控制调用频率。</li>
          <li>连续鉴权失败会触发来源 IP 自动封禁，如被误封请联系管理员在「IP 封禁」中解封。</li>
        </ul>
      </section>

      <footer class="doc-foot">
        <span>遇到接入问题？请联系系统管理员 · GateKeeper API Gateway</span>
      </footer>
    </main>
  </div>
</template>

<script>
export default {
  name: 'AccessDoc',
  data() {
    return {
      loggedIn: !!localStorage.getItem('gatekeeper_token'),
      headers: [
        { name: 'X-App-Key', desc: '应用唯一标识，管理员创建应用时分配', example: 'ak_9f2c8e1b...' },
        { name: 'X-Timestamp', desc: '毫秒级时间戳，偏差超过 5 分钟拒绝', example: '1772438400000' },
        { name: 'X-Nonce', desc: '随机串（建议 UUID），5 分钟内不可重复', example: 'e7b8a4d0-...' },
        { name: 'X-Signature', desc: '请求签名，算法见下文', example: 'SM3 摘要的十六进制串' }
      ],
      errors: [
        { code: '400', msg: '参数错误', desc: '请求参数缺失或格式不正确' },
        { code: '401', msg: '鉴权失败', desc: 'AppKey 无效 / 缺少请求头 / 时间戳过期 / Nonce 重复 / 签名不匹配，请检查密钥与签名算法' },
        { code: '403', msg: '无权限或被拒绝', desc: '应用停用/过期、IP 不在白名单、IP 被封禁或未获接口授权' },
        { code: '404', msg: '接口不存在', desc: '接口路径错误或接口已下线' },
        { code: '429', msg: '请求过于频繁', desc: '触发限流，请按响应中的提示降低调用频率' },
        { code: '502', msg: '上游服务异常', desc: '后端业务服务返回异常，请联系管理员' },
        { code: '504', msg: '上游服务超时', desc: '后端业务服务响应超时，请稍后重试' }
      ],
      javaCode: `// Maven 依赖：cn.hutool:hutool-crypto:5.8.x（内置国密 SM3，需配合 BouncyCastle）
import cn.hutool.crypto.SmUtil;
import cn.hutool.core.util.IdUtil;

String appKey  = "你的AppKey";
String secret  = "你的AppSecret";              // 仅本地使用，切勿传输
long   ts      = System.currentTimeMillis();   // 毫秒时间戳
String nonce   = IdUtil.fastSimpleUUID();      // 每次请求唯一

// 签名：SM3(AppKey + AppSecret + Timestamp + Nonce)，十六进制小写
String sign = SmUtil.sm3(appKey + secret + ts + nonce);

// 携带认证请求头调用网关
// POST http://<网关地址>/gateway/<接口路径>
// X-App-Key: appKey
// X-Timestamp: ts
// X-Nonce: nonce
// X-Signature: sign`,
      curlCode: `# 1. 生成签名（示例使用 openssl 不支持 SM3，可用 hutool / gmssl 等工具生成）
APP_KEY="你的AppKey"
APP_SECRET="你的AppSecret"
TIMESTAMP=$(date +%s%3N)
NONCE=$(cat /proc/sys/kernel/random/uuid | tr -d '-')
SIGN=$(echo -n "\${APP_KEY}\${APP_SECRET}\${TIMESTAMP}\${NONCE}" | gmssl sm3)

# 2. 调用网关
curl -X POST "http://<网关地址>/gateway/<接口路径>" \\
  -H "X-App-Key: \${APP_KEY}" \\
  -H "X-Timestamp: \${TIMESTAMP}" \\
  -H "X-Nonce: \${NONCE}" \\
  -H "X-Signature: \${SIGN}" \\
  -H "Content-Type: application/json" \\
  -d '{"bizParam":"value"}'`
    }
  }
}
</script>

<style scoped>
.doc-wrap { min-height: 100vh; background: #f4f6fa; }

/* 顶部品牌条 */
.doc-header {
  background: linear-gradient(135deg, #0b1c33 0%, #0f2a52 60%, #12336a 100%);
  color: #fff;
  padding: 18px 0;
}
.header-inner { max-width: 960px; margin: 0 auto; padding: 0 24px; display: flex; align-items: center; justify-content: space-between; }
.brand { display: flex; align-items: center; gap: 14px; }
.logo { width: 40px; height: 40px; }
.brand .t1 { font-size: 18px; font-weight: 700; }
.brand .t2 { font-size: 12px; color: #8ea4c6; margin-top: 2px; }

.doc-main { max-width: 960px; margin: 0 auto; padding: 28px 24px 40px; }

/* 概要卡 */
.hero {
  background: #fff; border-radius: 12px; padding: 28px 32px; margin-bottom: 20px;
  border: 1px solid #e8edf5;
  box-shadow: 0 1px 3px rgba(23, 35, 61, .05);
}
.hero h1 { font-size: 22px; color: #17233d; margin-bottom: 10px; }
.hero p { color: #5c6b8a; font-size: 14px; line-height: 1.8; margin-bottom: 20px; }
.steps { display: flex; gap: 16px; }
.step { flex: 1; display: flex; gap: 12px; align-items: flex-start; padding: 14px; border-radius: 10px; background: #f7f9fc; border: 1px solid #edf1f7; }
.step .no {
  width: 26px; height: 26px; border-radius: 50%; flex: none;
  background: #2563eb; color: #fff; font-size: 13px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
.step b { display: block; font-size: 13px; color: #17233d; margin-bottom: 3px; }
.step span { font-size: 12px; color: #7d93b8; line-height: 1.7; }

/* 内容块 */
.block {
  background: #fff; border-radius: 12px; padding: 24px 32px; margin-bottom: 20px;
  border: 1px solid #e8edf5;
  box-shadow: 0 1px 3px rgba(23, 35, 61, .05);
}
.block h2 { font-size: 16px; color: #17233d; margin-bottom: 14px; }
.block h2 i { color: #2563eb; margin-right: 6px; }
.desc { color: #5c6b8a; font-size: 13px; margin-bottom: 12px; }
code { background: #f0f4fa; border-radius: 4px; padding: 2px 6px; font-size: 12px; color: #c03337; font-family: Consolas, Monaco, monospace; }
code.hd { color: #2563eb; font-weight: 600; }

/* 签名公式 */
.formula {
  background: #0b1c33; color: #e8eefb; border-radius: 10px;
  padding: 16px 20px; font-family: Consolas, Monaco, monospace;
  font-size: 14px; margin-bottom: 14px;
}
.formula b { color: #6ea8ff; }
.rules { margin: 0; padding-left: 18px; color: #5c6b8a; font-size: 13px; line-height: 2; }
.rules b { color: #17233d; }

/* 代码块 */
pre {
  background: #0b1c33; color: #e8eefb; border-radius: 10px;
  padding: 18px 20px; overflow: auto; margin: 0;
}
pre code { background: transparent; color: inherit; padding: 0; font-size: 12.5px; line-height: 1.8; }

.doc-foot { text-align: center; color: #9aa7bf; font-size: 12px; padding: 8px 0 0; }
</style>
