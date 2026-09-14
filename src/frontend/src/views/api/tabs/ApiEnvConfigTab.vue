<template>
  <div class="api-tab">
    <!-- 共享 URI 条：先把「各环境一致的东西」说清楚，再看各环境差异 -->
    <div class="uri-strip">
      <div class="uri-main">
        <span class="uri-label">接口 URI（各环境共用）</span>
        <span v-if="requestMethod" class="method" :class="String(requestMethod).toLowerCase()">{{ requestMethod }}</span>
        <code class="uri-code">{{ interfacePath || '—' }}</code>
      </div>
      <div class="uri-note">
        dev / test / pre / prod 的 <b>URI 完全相同</b>，只有「服务前缀」不同；完整后端地址 = 服务前缀 + URI
      </div>
    </div>

    <RoutePreview :envs="envCards" :interface-path="interfacePath" />

    <div class="tab-toolbar">
      <PermButton perm="api_env_config:create" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新增环境配置</PermButton>
      <span class="tab-hint">
        逐环境配置<b>服务前缀（不含 URI）</b>、超时与 Mock；configStatus 由 upstreamUrl 推导。写操作接口（POST/PUT/DELETE）重试次数应保持 0，防止重复提交
      </span>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchEnvConfigs"
      :query="query"
      row-key="id"
      :show-pagination="false"
      :actions-width="300"
      @loaded="onLoaded"
    >
      <template #envCode="{ row }">
        <span>{{ envLabel(row.envCode) }}</span>
      </template>
      <template #upstreamUrl="{ row }">
        <code class="mono">{{ row.upstreamUrl || '—' }}</code>
      </template>
      <template #fullUrl="{ row }">
        <code class="mono">{{ fullUrl(row.upstreamUrl) }}</code>
      </template>
      <template #mockEnabled="{ row }">
        <el-tag :type="row.mockEnabled === 1 ? 'warning' : 'info'" size="small">{{ row.mockEnabled === 1 ? 'Mock 开启' : '关闭' }}</el-tag>
      </template>
      <template #configStatus="{ row }">
        <StatusTag entity="apiEnvConfig" :value="row.configStatus" />
      </template>
      <template #actions="{ row }">
        <PermButton perm="api:env:update" type="text" @click="toggleMock(row)">切换 Mock</PermButton>
        <PermButton perm="api:env:update" type="text" @click="openEdit(row)">编辑</PermButton>
        <PermButton
          perm="api_env_config:test"
          type="text"
          :loading="testingId === row.id"
          @click="testConnectivity(row)"
        >测试连通</PermButton>
        <PermButton perm="api_env_config:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="620px"
      @submit="submit"
    >
      <!-- 实时预览：前缀改了立刻看到完整地址，避免把 URI 也写进「服务前缀」里 -->
      <template #extra="{ form }">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>
            URI <code>{{ interfacePath || '—' }}</code> 各环境共用，此处只填「服务前缀」。
            完整后端地址预览：<code>{{ fullUrl(form.upstreamUrl) }}</code>
          </template>
        </el-alert>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
/**
 * 接口环境配置 Tab（T05 Phase 1 · api-list 详情）
 * 对接 /api-env-config/* ：list/create/update/toggle-mock/delete + 连通性测试 test。
 * 顶部复用 RoutePreview 网关路由/版本预览构件。
 *
 * 🔴 多环境语义（2026-09-14）：接口在 dev/test/pre/prod 的 **URI 完全相同**，
 *    各环境只是「服务前缀/基地址」（api_env_config.upstream_url）不同，
 *    完整后端地址 = 服务前缀 + api_interface.interface_path。
 *    所以本 Tab 必须：
 *      ① 把共享 URI 显式展示出来（顶部 uri-strip）；
 *      ② 表格同时给出「服务前缀」与「完整后端地址」两列，防止把前缀当成完整地址；
 *      ③ 表单标签写明「不含 URI」，并在弹窗内实时预览拼好的完整地址。
 *    原实现把 upstream_url 标成「后端服务地址 / 后端地址」，看的人会以为里面含路径。
 */
import { getApiEnvConfigList, createApiEnvConfig, updateApiEnvConfig, toggleApiEnvConfigMock, deleteApiEnvConfig, testApiEnvConfig } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'
import RoutePreview from '@/components/common/RoutePreview.vue'

export default {
  name: 'ApiEnvConfigTab',
  components: { StatusTag, RoutePreview },
  props: {
    apiId: { type: [Number, String], default: null },
    /** 接口 URI（跨环境共用），如 `/test`；由父级详情抽屉透传 */
    interfacePath: { type: String, default: '' },
    /** 接口请求方法，仅用于展示徽标；由父级详情抽屉透传 */
    requestMethod: { type: String, default: '' }
  },
  data() {
    return {
      query: { apiId: this.apiId },
      envOptions: ENV_LIST.map((e) => ({ value: e.code, label: e.label })),
      configs: [],
      columns: [
        { prop: 'envCode', label: '环境', width: 100, slot: 'envCode' },
        { prop: 'version', label: '版本', width: 90, formatter: (v) => v || '通用' },
        { prop: 'upstreamUrl', label: '服务前缀（不含 URI）', minWidth: 200, slot: 'upstreamUrl' },
        { prop: 'fullUrl', label: '完整后端地址', minWidth: 220, slot: 'fullUrl' },
        { prop: 'connectTimeout', label: '连接超时(ms)', width: 110, align: 'center' },
        { prop: 'readTimeout', label: '读取超时(ms)', width: 110, align: 'center' },
        { prop: 'retryCount', label: '重试', width: 70, align: 'center' },
        { prop: 'mockEnabled', label: 'Mock', width: 100, slot: 'mockEnabled' },
        { prop: 'configStatus', label: '配置状态', width: 90, slot: 'configStatus' }
      ],
      dialogVisible: false,
      dialogTitle: '新增环境配置',
      submitting: false,
      testingId: null,
      form: {},
      fields: [
        { prop: 'envCode', label: '环境', type: 'select', required: true, options: ENV_LIST.map((e) => ({ value: e.code, label: e.label })), span: 12 },
        { prop: 'version', label: '版本', type: 'input', span: 12, placeholder: '留空=所有版本通用' },
        { prop: 'upstreamUrl', label: '服务前缀', type: 'input', required: true, span: 24, maxlength: 200, placeholder: '如 http://order-svc.dev:8080（不含 URI）' },
        { prop: 'connectTimeout', label: '连接超时(ms)', type: 'number', min: 0, max: 60000, span: 8 },
        { prop: 'readTimeout', label: '读取超时(ms)', type: 'number', min: 0, max: 60000, span: 8 },
        { prop: 'retryCount', label: '重试次数', type: 'number', min: 0, max: 10, span: 8 },
        { prop: 'mockEnabled', label: '开启 Mock', type: 'switch', activeValue: 1, inactiveValue: 0, span: 12 }
      ]
    }
  },
  computed: {
    envCards() {
      return ENV_LIST.map((e) => {
        const d = (this.configs || []).find((x) => x.envCode === e.code) || {}
        return {
          code: e.code,
          label: e.label,
          upstreamUrl: d.upstreamUrl,
          version: d.version,
          grayRatio: null,
          configStatus: d.configStatus
        }
      })
    }
  },
  watch: {
    apiId(v) { this.query = { apiId: v } }
  },
  methods: {
    envLabel(code) {
      const e = ENV_LIST.find((x) => x.code === code)
      return e ? e.label : code
    },
    /**
     * 完整后端地址 = 服务前缀 + 接口 URI（URI 跨环境共用）。
     * 前缀去尾 `/`、URI 补头 `/`，避免 `http://x:8080//test`。
     */
    fullUrl(prefix) {
      const p = String(prefix || '').trim()
      if (!p) return '—'
      const base = p.replace(/\/+$/, '')
      const uri = String(this.interfacePath || '').trim()
      if (!uri) return base
      return base + (uri.charAt(0) === '/' ? uri : '/' + uri)
    },
    async fetchEnvConfigs() {
      // 🔴 这里必须传「apiId 标量」，不能传对象：
      // modules.js 的 getApiEnvConfigList(apiId) 内部写的是 { params: { apiId } }，
      // 若传 { apiId: this.apiId } 就会变成 { params: { apiId: { apiId: 1 } } }，
      // axios 把对象值 JSON.stringify 后拼成 ?apiId={"apiId":1}，
      // 后端 @RequestParam Long apiId 类型转换失败 ⇒ HTTP 500。
      // 实测（2026-09-13）：500 /api/api-env-config/list?apiId=%7B%22apiId%22:1%7D，
      // 表现为详情抽屉「环境配置」Tab 永远加载失败、控制台 [CrudTable] fetch 失败。
      // 同一函数的另一调用点 RoutePreview.vue:103 传的就是标量，此处与之对齐。
      const res = await getApiEnvConfigList(this.apiId)
      const list = res.data || []
      return { list, total: list.length }
    },
    onLoaded({ list }) {
      this.configs = list || []
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    openCreate() {
      this.dialogTitle = '新增环境配置'
      this.form = {
        envCode: '',
        version: '',
        upstreamUrl: '',
        connectTimeout: 3000,
        readTimeout: 5000,
        retryCount: 0,
        mockEnabled: 0
      }
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '编辑环境配置'
      this.form = { ...row }
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form, apiId: this.apiId }
        if (payload.id) {
          await updateApiEnvConfig(payload.id, payload)
          this.$message.success('配置已更新')
        } else {
          await createApiEnvConfig(payload)
          this.$message.success('配置已创建')
        }
        this.dialogVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    toggleMock(row) {
      this.$confirm(`切换环境「${this.envLabel(row.envCode)}」的 Mock 开关？`, '切换 Mock', { type: 'warning' }).then(async () => {
        try {
          await toggleApiEnvConfigMock(row.id)
          this.$message.success('Mock 状态已切换')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    // 连通性测试（api_env_config:test）：后端对 upstreamUrl 发 HEAD，通过置 configStatus=2 已验证
    testConnectivity(row) {
      this.$confirm(`对「${this.envLabel(row.envCode)}」环境的服务前缀发起连通性测试？`, '测试连通', { type: 'info' }).then(async () => {
        this.testingId = row.id
        try {
          const res = await testApiEnvConfig(row.id)
          const cfg = (res && res.data) || {}
          if (cfg.configStatus === 2) {
            this.$message.success('连通测试通过 · 已标记为「已验证」')
          } else {
            this.$message.warning('连通测试未通过 · 仍为「已配置」，请检查服务前缀')
          }
          this.reload()
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.testingId = null
        }
      }).catch(() => {})
    },
    remove(row) {
      this.$confirm(`确认删除环境「${this.envLabel(row.envCode)}」的配置？`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await deleteApiEnvConfig(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }

/* 共享 URI 条：各环境一致的部分 */
.uri-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  border: 1px solid #e3e8f2;
  border-radius: 10px;
  background: #f7f9fc;
  padding: 10px 14px;
  margin-bottom: 12px;
}
.uri-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.uri-label { font-size: 12px; color: #5c6b8a; }
.uri-code { font-size: 13px; color: #17233d; font-weight: 600; }
.uri-note { font-size: 12px; color: #9aa7bf; }
.uri-note b { color: #5c6b8a; }

.tab-toolbar { display: flex; align-items: center; gap: 12px; margin: 12px 0; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.tab-hint b { color: #5c6b8a; }
.mono { font-family: 'SFMono-Regular', Consolas, monospace; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
