<template>
  <el-dialog
    :title="`接口测试 · ${api ? api.interfaceName : ''}`"
    :visible.sync="innerVisible"
    width="780px"
    :close-on-click-modal="false"
    append-to-body
    @open="onOpen"
  >
    <template v-if="api">
      <!-- 接口信息 -->
      <div class="it-head">
        <span class="method" :class="(api.requestMethod || 'GET').toLowerCase()">{{ api.requestMethod || 'GET' }}</span>
        <code class="it-path">{{ api.interfacePath }}</code>
      </div>

      <el-form :model="form" label-width="96px" size="small">
        <el-form-item label="测试模式">
          <el-radio-group v-model="form.mode">
            <el-radio-button label="DIRECT">直连后端</el-radio-button>
            <el-radio-button label="GATEWAY">走网关</el-radio-button>
          </el-radio-group>
          <div class="it-hint">
            <template v-if="form.mode === 'DIRECT'">
              <b>直连后端</b>：绕过网关，直接用「生效环境配置的服务前缀 + 本接口 URI」发请求。
              用来验证<b>上游本身通不通、服务前缀配得对不对</b>；不需要应用凭证。
            </template>
            <template v-else>
              <b>走网关</b>：用真实应用凭证按网关契约签名后调用 <code>/gateway/**</code>，
              把 <b>鉴权 → 限流 → 权限 → Mock → 转发 → 日志</b> 整条责任链跑一遍。
              用来验证<b>某个应用到底能不能调通这个接口</b>。
            </template>
          </div>
        </el-form-item>

        <el-form-item label="环境">
          <el-select v-model="form.envCode" style="width: 200px">
            <el-option v-for="e in envOptions" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.mode === 'GATEWAY'" label="调用应用">
          <el-select v-model="form.appId" clearable placeholder="自动挑选（优先有有效授权的应用）" style="width: 320px">
            <el-option v-for="a in apps" :key="a.id" :label="`${a.appName}（${a.appKey}）`" :value="a.id" />
          </el-select>
          <div class="it-hint">留空则由后端自动挑选：优先选对该接口在该环境下<b>有有效授权</b>的应用。</div>
        </el-form-item>

        <el-form-item label="请求体">
          <el-input
            v-model="form.body"
            type="textarea"
            :rows="5"
            :disabled="!canHaveBody"
            placeholder='JSON 请求体，如 {"id":1}'
          />
          <div v-if="!canHaveBody" class="it-hint">
            {{ api.requestMethod }} 请求不发送请求体（GET/DELETE 无 body）。
          </div>
        </el-form-item>
      </el-form>

      <!-- 结果区 -->
      <div v-if="result" class="it-result">
        <div class="it-result-head">
          <span class="it-status" :class="statusClass">{{ statusText }}</span>
          <span v-if="result.costMs != null" class="it-cost">耗时 {{ result.costMs }} ms</span>
          <span class="it-mode">{{ result.mode === 'GATEWAY' ? '走网关' : '直连后端' }} · {{ envLabelOf(result.envCode) }}</span>
        </div>

        <div class="it-line"><span class="it-k">请求地址</span><code class="it-mono">{{ result.targetUrl || '—' }}</code></div>
        <div class="it-line"><span class="it-k">生效来源</span><span>{{ sourceText }}</span></div>
        <div class="it-line">
          <span class="it-k">生效超时</span>
          <span>连接 {{ result.connectTimeout != null ? result.connectTimeout : '—' }} ms / 读取 {{ result.readTimeout != null ? result.readTimeout : '—' }} ms / 重试 {{ result.retryCount != null ? result.retryCount : '—' }}</span>
        </div>
        <div v-if="result.appName" class="it-line"><span class="it-k">调用应用</span><span>{{ result.appName }}（id={{ result.appId }}）</span></div>
        <div v-if="result.mock" class="it-line"><span class="it-k">Mock</span><span class="it-warn">该环境已开启 Mock</span></div>

        <div v-if="headerEntries.length" class="it-line it-line-top">
          <span class="it-k">请求头</span>
          <div class="it-headers">
            <div v-for="h in headerEntries" :key="h.k"><code class="it-mono">{{ h.k }}: {{ h.v }}</code></div>
          </div>
        </div>

        <div v-if="result.notes && result.notes.length" class="it-line it-line-top">
          <span class="it-k">过程说明</span>
          <ol class="it-notes">
            <li v-for="(n, i) in result.notes" :key="i">{{ n }}</li>
          </ol>
        </div>

        <div v-if="result.error" class="it-line it-line-top">
          <span class="it-k">失败原因</span>
          <span class="it-err">{{ result.error }}</span>
        </div>

        <div class="it-line it-line-top">
          <span class="it-k">响应报文<span v-if="result.truncated" class="it-warn">（已截断）</span></span>
          <pre class="it-body">{{ prettyBody }}</pre>
        </div>
      </div>
    </template>

    <template slot="footer">
      <el-button @click="innerVisible = false">关闭</el-button>
      <el-button type="primary" :loading="sending" @click="send">发送请求</el-button>
    </template>
  </el-dialog>
</template>

<script>
/**
 * 接口测试弹窗（T13 · 用户需求第 2 条「新增接口测试功能」）
 * ------------------------------------------------------------------
 * 两种模式（用户明确要求「两种都要，弹窗内可切换」）：
 *  - DIRECT  直连后端：绕过网关，直接打「生效配置的服务前缀 + 接口URI」。
 *            回答"上游通不通、我配的前缀对不对"，不需要应用凭证。
 *  - GATEWAY 走网关：用真实应用凭证签名调用 /gateway/**，把整条责任链跑一遍。
 *            回答"这个应用到底能不能调通这个接口"。
 *
 * 结果刻意不做"只回成功/失败"的简化：状态码、耗时、实际地址、生效来源、
 * 生效超时、送出的请求头（签名打码）、后端给出的过程说明、响应报文，全部原样展示。
 * 试调的价值就在于出问题时能一眼看出卡在哪一环 —— 尤其「走网关」模式下
 * 403「无权调用此接口」本身就是**最有价值的成功验证**（证明权限拦截生效了）。
 */
import { testInterface, getAppList } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'

export default {
  name: 'InterfaceTestDialog',
  props: {
    visible: { type: Boolean, default: false },
    /** 接口行对象（需含 id / interfaceName / interfacePath / requestMethod） */
    api: { type: Object, default: null }
  },
  data() {
    return {
      sending: false,
      apps: [],
      result: null,
      envOptions: ENV_LIST.map((e) => ({ value: e.code, label: e.label })),
      form: { mode: 'DIRECT', envCode: 'prod', appId: null, body: '' }
    }
  },
  computed: {
    innerVisible: {
      get() { return this.visible },
      set(v) { this.$emit('update:visible', v) }
    },
    canHaveBody() {
      const m = String((this.api && this.api.requestMethod) || 'GET').toUpperCase()
      return m === 'POST' || m === 'PUT'
    },
    statusText() {
      const r = this.result
      if (!r) return ''
      if (r.statusCode == null) return '请求失败'
      return `HTTP ${r.statusCode}`
    },
    statusClass() {
      const r = this.result
      if (!r) return ''
      if (r.statusCode == null) return 'is-fail'
      return r.success ? 'is-ok' : 'is-fail'
    },
    sourceText() {
      const r = this.result
      if (!r) return '—'
      if (r.sourceType === 'GROUP') return `分组继承 · ${r.sourcePath || ''}`
      if (r.sourceType === 'INTERFACE') return '接口级环境配置（存量，优先级最高）'
      return '未命中环境配置，回退接口自身默认后端地址'
    },
    headerEntries() {
      const r = this.result
      if (!r || !r.requestHeaders) return []
      return Object.keys(r.requestHeaders).map((k) => ({ k, v: r.requestHeaders[k] }))
    },
    prettyBody() {
      const r = this.result
      if (!r || r.responseBody == null || r.responseBody === '') return '（空响应体）'
      const s = String(r.responseBody)
      try {
        return JSON.stringify(JSON.parse(s), null, 2)
      } catch (e) {
        return s
      }
    }
  },
  methods: {
    envLabelOf(code) {
      const e = ENV_LIST.find((x) => x.code === code)
      return e ? e.label : code
    },
    onOpen() {
      this.result = null
      this.form.mode = 'DIRECT'
      this.form.envCode = 'prod'
      this.form.appId = null
      this.form.body = ''
      this.loadApps()
    },
    async loadApps() {
      try {
        const res = await getAppList({ current: 1, size: 200 })
        const data = (res && res.data) || {}
        // 分页响应形状兼容：{records,total} 或裸数组（见项目「响应形状审计」）
        this.apps = data.records || data.list || (Array.isArray(data) ? data : [])
      } catch (e) {
        this.apps = []
      }
    },
    async send() {
      if (!this.api || !this.api.id) return
      this.sending = true
      this.result = null
      try {
        const payload = {
          mode: this.form.mode,
          envCode: this.form.envCode,
          body: this.canHaveBody ? this.form.body : null,
          appId: this.form.mode === 'GATEWAY' ? this.form.appId : null
        }
        const res = await testInterface(this.api.id, payload)
        this.result = (res && res.data) || null
      } catch (e) {
        /* 拦截器已提示 */
      } finally {
        this.sending = false
      }
    }
  }
}
</script>

<style scoped>
.it-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.it-path { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 13px; color: #17233d; }
.it-hint { font-size: 11px; color: #9aa7bf; line-height: 1.6; margin-top: 4px; }
.it-hint b { color: #5c6b8a; }
.it-hint code { color: #1e40af; }

.it-result { border: 1px solid #e3e8f2; border-radius: 10px; padding: 12px 14px; background: #fbfcff; }
.it-result-head { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; flex-wrap: wrap; }
.it-status { font-size: 13px; font-weight: 600; padding: 2px 10px; border-radius: 12px; }
.it-status.is-ok { color: #1a7f37; background: #e8f6ec; }
.it-status.is-fail { color: #c03337; background: #fdecec; }
.it-cost { font-size: 12px; color: #5c6b8a; }
.it-mode { font-size: 12px; color: #9aa7bf; }

.it-line { display: flex; gap: 8px; font-size: 12px; line-height: 1.7; margin-top: 4px; }
.it-line-top { align-items: flex-start; }
.it-k { color: #9aa7bf; flex: none; width: 68px; }
.it-mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; color: #17233d; word-break: break-all; }
.it-warn { color: #b8860b; }
.it-err { color: #c03337; }
.it-headers { display: flex; flex-direction: column; gap: 2px; }
.it-notes { margin: 0; padding-left: 18px; color: #5c6b8a; font-size: 12px; }
.it-notes li { line-height: 1.7; }
.it-body {
  flex: 1;
  min-width: 0;
  margin: 0;
  max-height: 260px;
  overflow: auto;
  background: #0f172a;
  color: #d7e2f2;
  border-radius: 8px;
  padding: 10px 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
