<template>
  <div class="api-tab">
    <VersionRouteViz :versions="versions" :api-name="apiName" />

    <div class="tab-toolbar">
      <PermButton perm="api_version:create" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新建版本</PermButton>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchVersions"
      :query="query"
      row-key="id"
      :show-pagination="false"
      @loaded="onLoaded"
    >
      <template #status="{ row }">
        <StatusTag entity="apiVersion" :value="row.status" />
      </template>
      <template #isCurrent="{ row }">
        <el-tag v-if="row.isCurrent === 1" type="primary" size="small">当前默认</el-tag>
        <span v-else>—</span>
      </template>
      <template #grayRatio="{ row }">
        <span>{{ row.grayRatio != null ? row.grayRatio + '%' : '—' }}</span>
      </template>
      <template #actions="{ row }">
        <PermButton perm="" type="text" @click="setCurrent(row)">设当前</PermButton>
        <PermButton perm="" type="text" @click="deprecate(row)">弃用</PermButton>
        <PermButton perm="" type="text" class="danger-link" @click="offline(row)">下线</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="520px"
      @submit="submit"
    />
  </div>
</template>

<script>
/**
 * 接口版本管理 Tab（T05 Phase 1 · api-list 详情）
 * 对接 /api-version/* ：list/create/set-current/deprecate/offline。
 * 顶部复用 VersionRouteViz 灰度路由可视化构件。
 */
import { getApiVersionList, createApiVersion, setApiVersionCurrent, deprecateApiVersion, offlineApiVersion } from '@/api/modules'
import StatusTag from '@/components/common/StatusTag.vue'
import VersionRouteViz from '@/components/common/VersionRouteViz.vue'

export default {
  name: 'ApiVersionTab',
  components: { StatusTag, VersionRouteViz },
  props: {
    apiId: { type: [Number, String], default: null },
    apiName: { type: String, default: '' }
  },
  data() {
    return {
      query: { apiId: this.apiId },
      versions: [],
      columns: [
        { prop: 'version', label: '版本号', minWidth: 100 },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'isCurrent', label: '当前', width: 90, slot: 'isCurrent' },
        { prop: 'grayRatio', label: '灰度比例', width: 90, slot: 'grayRatio' },
        { prop: 'changeLog', label: '变更说明', minWidth: 160, showOverflowTooltip: true },
        { prop: 'deprecateTime', label: '弃用时间', width: 120, formatter: (v) => v || '—' },
        { prop: 'offlinePlanTime', label: '计划下线', width: 120, formatter: (v) => v || '—' }
      ],
      dialogVisible: false,
      dialogTitle: '新建版本',
      submitting: false,
      form: {},
      fields: [
        { prop: 'version', label: '版本号', type: 'input', required: true, placeholder: '如 v1 / v2', maxlength: 32, span: 12 },
        { prop: 'grayRatio', label: '灰度比例', type: 'number', min: 0, max: 100, span: 12 },
        { prop: 'changeLog', label: '变更说明', type: 'textarea', span: 24, maxlength: 200 },
        { prop: 'deprecateTime', label: '弃用时间', type: 'date', span: 12 },
        { prop: 'offlinePlanTime', label: '计划下线', type: 'date', span: 12 }
      ]
    }
  },
  watch: {
    apiId(v) { this.query = { apiId: v } }
  },
  methods: {
    async fetchVersions() {
      const res = await getApiVersionList({ apiId: this.apiId })
      const list = res.data || []
      return { list, total: list.length }
    },
    onLoaded({ list }) {
      this.versions = list || []
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    openCreate() {
      this.dialogTitle = '新建版本'
      this.form = { version: '', grayRatio: 0, changeLog: '', deprecateTime: '', offlinePlanTime: '' }
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form, apiId: this.apiId, status: 1 }
        await createApiVersion(payload)
        this.$message.success('版本已创建')
        this.dialogVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    setCurrent(row) {
      this.$confirm(`将版本「${row.version}」设为当前默认版本？`, '设为当前', { type: 'warning' }).then(async () => {
        try {
          await setApiVersionCurrent(row.id)
          this.$message.success('已设为当前版本')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    deprecate(row) {
      this.$confirm(`确认弃用版本「${row.version}」？`, '弃用确认', { type: 'warning' }).then(async () => {
        try {
          await deprecateApiVersion(row.id)
          this.$message.success('已弃用')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    offline(row) {
      this.$confirm(`确认下线版本「${row.version}」？下线后不可恢复流量。`, '下线确认', { type: 'warning' }).then(async () => {
        try {
          await offlineApiVersion(row.id)
          this.$message.success('已下线')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin: 12px 0; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
