<template>
  <!-- 接入文档页
       双形态：
         · 公开形态（默认）：顶层独立路由 /access-doc，整屏、带深色品牌条，未登录也能看；
         · 内嵌形态（embedded=true）：Layout 子路由 /sys/sys-access-doc，由右侧内容区承载。
       两种形态共用同一套内容（来自 @/utils/accessDoc），避免「页面讲的」与「导出文件里写的」发散。 -->
  <div :class="['doc-wrap', { 'doc-embedded': embedded }]">
    <!-- 顶部品牌条：仅公开形态显示 -->
    <header v-if="!embedded" class="doc-header">
      <div class="header-inner">
        <div class="brand">
          <svg viewBox="0 0 48 48" fill="none" class="logo">
            <path d="M24 4 L42 10 V22 C42 34 34 41 24 44 C14 41 6 34 6 22 V10 Z" fill="rgba(79,140,255,.15)" stroke="#4f8cff" stroke-width="2.5"/>
            <path d="M17 24 l5 5 9 -10" stroke="#6ea8ff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <div>
            <div class="t1">GateKeeper 接入文档</div>
            <div class="t2">APIM 统一接口管理平台 · 调用方接入指南</div>
          </div>
        </div>
        <el-button v-if="!loggedIn" size="small" round @click="$router.push('/login')">返回登录</el-button>
      </div>
    </header>

    <main class="doc-main">
      <!-- 内嵌形态的动作条 -->
      <div v-if="embedded" class="doc-actions">
        <div class="da-text">
          下面所有内容与「创建应用 → 下载接入文档」导出的 Markdown 完全一致。
        </div>
        <el-button size="small" type="primary" plain icon="el-icon-download" @click="downloadGeneric">导出通用文档</el-button>
      </div>

      <!-- 概要卡 -->
      <section class="hero">
        <h1>三步完成接入</h1>
        <p>所有业务接口统一通过网关入口 <code>POST &lt;网关地址&gt;/gateway/&lt;接口路径&gt;</code> 调用。你需要先向管理员申请 <b>AppKey / AppSecret</b>，再按下述规则为每次请求生成签名。</p>
        <div class="steps">
          <div class="step"><div class="no">1</div><div><b>申请密钥</b><span>管理员在「应用管理」创建应用与凭证，获得 AppKey 与 AppSecret</span></div></div>
          <div class="step"><div class="no">2</div><div><b>获得授权</b><span>管理员在「接口授权总览」为你的应用授予目标接口的调用权限</span></div></div>
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
          <li>AppSecret 仅参与本地签名计算，<b>切勿在网络中传输，切勿提交到代码库</b>。</li>
        </ul>
      </section>

      <!-- 工具类 -->
      <section class="block">
        <h2><i class="el-icon-suitcase"></i> 工具类（可直接复制）</h2>
        <p class="desc">两份工具类已把「生成 Nonce → 拼串 → SM3 签名 → 组装请求头 → 发起调用」完整封装，复制到项目里改一下密钥即可跑通。</p>
        <el-tabs v-model="utilTab">
          <el-tab-pane label="Java 工具类 GkSigner.java" name="java">
            <el-alert type="info" :closable="false" show-icon
                      title="依赖：cn.hutool:hutool-all + org.bouncycastle:bcprov-jdk15to18（SM3 由 BouncyCastle 提供实现）" />
            <pre><code class="java">{{ javaUtilCode }}</code></pre>
          </el-tab-pane>
          <el-tab-pane label="JavaScript 工具类 gk-sign.js" name="js">
            <el-alert type="info" :closable="false" show-icon
                      title="依赖：npm i sm-crypto（浏览器可直引 unpkg 上的 UMD 包，把 require 换成 window.smCrypto.sm3）" />
            <pre><code class="javascript">{{ jsUtilCode }}</code></pre>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- 调用方法 -->
      <section class="block">
        <h2><i class="el-icon-guide"></i> 调用方法</h2>
        <p class="desc">工具类对外暴露的方法一览：</p>
        <el-tabs v-model="methodTab">
          <el-tab-pane label="Java" name="java">
            <el-table :data="javaMethods" size="small" border>
              <el-table-column prop="sig" label="方法" min-width="330">
                <template slot-scope="{ row }"><code>{{ row.sig }}</code></template>
              </el-table-column>
              <el-table-column prop="desc" label="说明" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="JavaScript" name="js">
            <el-table :data="jsMethods" size="small" border>
              <el-table-column prop="sig" label="方法" min-width="330">
                <template slot-scope="{ row }"><code>{{ row.sig }}</code></template>
              </el-table-column>
              <el-table-column prop="desc" label="说明" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- 调用示例 -->
      <section class="block">
        <h2><i class="el-icon-document-copy"></i> 调用示例</h2>
        <el-tabs v-model="sampleTab">
          <el-tab-pane label="Java" name="java">
            <pre><code class="java">{{ javaCallCode }}</code></pre>
          </el-tab-pane>
          <el-tab-pane label="JavaScript" name="js">
            <pre><code class="javascript">{{ jsCallCode }}</code></pre>
          </el-tab-pane>
          <el-tab-pane label="Node.js" name="node">
            <pre><code class="javascript">{{ nodeCallCode }}</code></pre>
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
          <li>连续鉴权失败会触发来源 IP 自动封禁，如被误封请联系管理员在「封禁管理」中解封。</li>
        </ul>
      </section>

      <footer class="doc-foot">
        <span>遇到接入问题？请联系系统管理员 · GateKeeper API Gateway</span>
      </footer>
    </main>
  </div>
</template>

<script>
/**
 * 接入文档页（T05 建；T14 重构）
 *
 * T14 改动：
 *   1. 支持 embedded 形态，挂到 Layout 里做左侧菜单「接入文档」的落点；
 *   2. 代码片段 / 表数据全部搬到 @/utils/accessDoc —— 与「创建应用后导出的 Markdown」
 *      共用同一份内容，避免两边各写一份导致算法说明发散；
 *   3. 补齐 Java 工具类、JavaScript 工具类、调用方法、多语言调用示例。
 */
import {
  GK_HEADERS, GK_ERRORS, JAVA_METHODS, JS_METHODS,
  JAVA_UTIL_CODE, JAVA_CALL_CODE, JS_UTIL_CODE, JS_CALL_CODE, NODE_CALL_CODE, CURL_CODE,
  exportAccessDoc
} from '@/utils/accessDoc'

export default {
  name: 'AccessDoc',
  props: {
    /** true = 内嵌在 Layout 内容区（隐藏品牌条、取消整屏高度） */
    embedded: { type: Boolean, default: false }
  },
  data() {
    return {
      loggedIn: !!localStorage.getItem('gatekeeper_token'),
      headers: GK_HEADERS,
      errors: GK_ERRORS,
      javaMethods: JAVA_METHODS,
      jsMethods: JS_METHODS,
      javaUtilCode: JAVA_UTIL_CODE,
      javaCallCode: JAVA_CALL_CODE,
      jsUtilCode: JS_UTIL_CODE,
      jsCallCode: JS_CALL_CODE,
      nodeCallCode: NODE_CALL_CODE,
      curlCode: CURL_CODE,
      utilTab: 'java',
      methodTab: 'java',
      sampleTab: 'java'
    }
  },
  methods: {
    downloadGeneric() {
      const name = exportAccessDoc({ appName: 'GateKeeper' })
      this.$message.success('已导出 ' + name)
    }
  }
}
</script>

<style scoped>
.doc-wrap { min-height: 100vh; background: #f4f6fa; }
/* 内嵌形态：Layout 已经提供了背景与侧栏，这里只做内容区容器 */
.doc-embedded { min-height: auto; background: transparent; }

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
.doc-embedded .doc-main { max-width: none; padding: 16px 20px 28px; }

/* 内嵌形态动作条 */
.doc-actions {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  background: #fff; border: 1px solid #e8edf5; border-radius: 10px;
  padding: 10px 16px; margin-bottom: 16px;
}
.da-text { font-size: 12px; color: #7d93b8; }

/* 概要卡 */
.hero {
  background: #fff; border-radius: 12px; padding: 28px 32px; margin-bottom: 20px;
  border: 1px solid #e8edf5;
  box-shadow: 0 1px 3px rgba(23, 35, 61, .05);
}
.hero h1 { font-size: 22px; color: #17233d; margin-bottom: 10px; }
.hero p { color: #5c6b8a; font-size: 14px; line-height: 1.8; margin-bottom: 20px; }
.steps { display: flex; gap: 16px; flex-wrap: wrap; }
.step { flex: 1; min-width: 220px; display: flex; gap: 12px; align-items: flex-start; padding: 14px; border-radius: 10px; background: #f7f9fc; border: 1px solid #edf1f7; }
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

/* 表格里的代码不换行溢出 */
.block .el-table code { white-space: nowrap; }

/* 代码块 */
pre {
  background: #0b1c33; color: #e8eefb; border-radius: 10px;
  padding: 18px 20px; overflow: auto; margin: 12px 0 0;
  max-height: 560px;
}
pre code { background: transparent; color: inherit; padding: 0; font-size: 12.5px; line-height: 1.8; }

.doc-foot { text-align: center; color: #9aa7bf; font-size: 12px; padding: 8px 0 0; }
</style>
