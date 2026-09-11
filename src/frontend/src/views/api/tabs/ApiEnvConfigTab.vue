<template>
  <div class="api-tab">
    <RoutePreview :envs="envCards" />

    <div class="tab-toolbar">
      <PermButton perm="api_env_config:create" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新增环境配置</PermButton>
      <span class="tab-hint">为接口在 dev/test/pre/prod 各环境下配置后端地址、超时与 Mock；configStatus 由 upstreamUrl 推导</span>
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
      <template #mockEnabled="{ row }">
        <el-tag :type="row.mockEnabled === 1 ? 'warning' : 'info'" size="small">{{ row.mockEnabled === 1 ? 'Mock 开启' : '关闭' }}</el-tag>
      </template>
      <template #configStatus="{ row }">
        <StatusTag entity="apiEnvConfig" :value="row.configStatus" />
      </template>
      <template #actions="{ row }">
        <PermButton perm="" type="text" @click="toggleMock(row)">切换 Mock</PermButton>
        <PermButton perm="" type="text" @click="openEdit(row)">编辑</PermButton>
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
      width="560px"
      @submit="submit"
    />
  </div>
</template>

<script>
/**
 * 接口环境配置 Tab（T05 Phase 1 · api-list 详情）
 * 对接 /api-env-config/* ：list/create/update/toggle-mock/delete + 连通性测试 test。
 * 顶部复用 RoutePreview 网关路由/版本预览构件。
 */
import { getApiEnvConfigList, createApiEnvConfig, updateApiEnvConfig, toggleApiEnvConfigMock, deleteApiEnvConfig, testApiEnvConfig } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'
import RoutePreview from '@/components/common/RoutePreview.vue'

export default {
  name: 'ApiEnvConfigTab',
  components: { StatusTag, RoutePreview },
  props: {
    apiId: { type: [Number, String], default: null }
  },
  data() {
    return {
      query: { apiId: this.apiId },
      envOptions: ENV_LIST.map((e) => ({ value: e.code, label: e.label })),
      configs: [],
      columns: [
        { prop: 'envCode', label: '环境', width: 100, slot: 'envCode' },
        { prop: 'version', label: '版本', width: 90, formatter: (v) => v || '通用' },
        { prop: 'upstreamUrl', label: '后端地址', minWidth: 200, showOverflowTooltip: true },
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
        { prop: 'upstreamUrl', label: '后端服务地址', type: 'input', required: true, span: 24, maxlength: 200 },
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
    async fetchEnvConfigs() {
      const res = await getApiEnvConfigList({ apiId: this.apiId })
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
      this.$confirm(`对「${this.envLabel(row.envCode)}」环境的后端地址发起连通性测试？`, '测试连通', { type: 'info' }).then(async () => {
        this.testingId = row.id
        try {
          const res = await testApiEnvConfig(row.id)
          const cfg = (res && res.data) || {}
          if (cfg.configStatus === 2) {
            this.$message.success('连通测试通过 · 已标记为「已验证」')
          } else {
            this.$message.warning('连通测试未通过 · 仍为「已配置」，请检查后端地址')
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
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin: 12px 0; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
