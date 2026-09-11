<template>
  <div class="page-container perm-matrix">
    <div class="page-head">
      <h2>接口授权总览</h2>
      <span class="page-tag">授权审批流：待审批 → 已生效 →（过期 / 撤销 / 驳回）</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="环境">
          <el-select v-model="query.envCode" placeholder="全部" clearable style="width:130px">
            <el-option v-for="e in envOptions" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:130px">
            <el-option v-for="(m, k) in grantStatusOptions" :key="k" :label="m.label" :value="Number(k)" />
          </el-select>
        </el-form-item>
        <el-form-item label="仅看待审批">
          <el-switch v-model="query.pendingOnly" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条授权</span>
      <span class="spacer" />
      <PermButton perm="grant:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建授权</PermButton>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :show-pagination="false" :actions-width="260">
      <template #envCode="{row}">
        <el-tag size="small" effect="plain" :type="envTagType(row.envCode)">{{ row.envCode || '—' }}</el-tag>
      </template>
      <template #qpsLimit="{row}">
        <span>{{ row.qpsLimit ? row.qpsLimit : '不限' }}</span>
      </template>
      <template #validTo="{row}">
        <span>{{ row.validTo || '长期' }}</span>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'grant'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton v-if="row.status === 0" perm="grant:approve" type="text" size="mini" @click="onApprove(row)">通过</PermButton>
        <PermButton v-if="row.status === 0" perm="grant:reject" type="text" size="mini" @click="onReject(row)">驳回</PermButton>
        <PermButton v-if="row.status === 1" perm="grant:revoke" type="text" size="mini" class="danger-link" @click="onRevoke(row)">撤销</PermButton>
        <el-button v-if="row.status === 1" type="text" size="mini" @click="onRenew(row)">续期</el-button>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      title="新建授权"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'640px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra>
        <el-alert type="info" :closable="false" show-icon>
          <template #title>新建授权默认进入「待审批」；审批流关闭时由后端直接置为「已生效」。</template>
        </el-alert>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getGrantList,
  getGrantPending,
  createGrant,
  approveGrant,
  rejectGrant,
  revokeGrant,
  renewGrant
} from '@/api/modules'
import { STATUS_MAP } from '@/utils/enum'

const ENV_OPTIONS = [
  { value: 'dev', label: 'dev' },
  { value: 'test', label: 'test' },
  { value: 'pre', label: 'pre' },
  { value: 'prod', label: 'prod' }
]

const ENV_TAG_TYPES = { dev: 'info', test: 'primary', pre: 'warning', prod: 'danger' }

export default {
  name: 'PermMatrix',
  data() {
    return {
      envOptions: ENV_OPTIONS,
      grantStatusOptions: STATUS_MAP.grant,
      query: { envCode: undefined, status: undefined, pendingOnly: false },
      total: 0,
      columns: [
        { prop: 'id', label: 'ID', width: 70 },
        { prop: 'appId', label: '应用ID', width: 90 },
        { prop: 'apiId', label: '接口ID', width: 90 },
        { prop: 'envCode', label: '环境', width: 90, slot: 'envCode' },
        { prop: 'qpsLimit', label: 'QPS', width: 90, slot: 'qpsLimit' },
        { prop: 'dailyQuota', label: '日配额', width: 100, formatter: (v) => (v ? v : '不限') },
        { prop: 'validTo', label: '有效期至', width: 120, slot: 'validTo' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'applicantName', label: '申请人', width: 110, formatter: (v) => v || '—' },
        { prop: 'grantReason', label: '申请理由', minWidth: 160, showOverflowTooltip: true, formatter: (v) => v || '—' }
      ],
      dialog: { visible: false, loading: false, form: {}, fields: [], rules: {} }
    }
  },
  methods: {
    envTagType(code) {
      return ENV_TAG_TYPES[code] || 'info'
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, pendingOnly, ...rest } = params
        const res = pendingOnly
          ? await getGrantPending({ envCode: rest.envCode })
          : await getGrantList(rest)
        const list = (res && res.data) || []
        self.total = list.length
        return { list, total: list.length }
      }
    }(),
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    resetQuery() {
      this.query.envCode = undefined
      this.query.status = undefined
      this.query.pendingOnly = false
      this.reload()
    },
    buildFields() {
      return [
        { prop: 'appId', label: '应用ID', type: 'number', required: true, span: 12, min: 1, step: 1 },
        { prop: 'apiId', label: '接口ID', type: 'number', required: true, span: 12, min: 1, step: 1 },
        { prop: 'envCode', label: '环境', type: 'select', required: true, span: 12, options: ENV_OPTIONS },
        { prop: 'qpsLimit', label: 'QPS 上限', type: 'number', span: 12, min: 0, step: 1, placeholder: '0=不限' },
        { prop: 'dailyQuota', label: '日配额', type: 'number', span: 12, min: 0, step: 1, placeholder: '0=不限' },
        { prop: 'validFrom', label: '生效日期', type: 'date', span: 12 },
        { prop: 'validTo', label: '失效日期', type: 'date', span: 12 },
        { prop: 'grantReason', label: '申请理由', type: 'textarea', span: 24, rows: 2, placeholder: '说明申请该授权的业务背景' }
      ]
    },
    buildRules() {
      return {
        appId: [{ required: true, message: '应用ID不能为空', trigger: 'blur' }],
        apiId: [{ required: true, message: '接口ID不能为空', trigger: 'blur' }],
        envCode: [{ required: true, message: '环境不能为空', trigger: 'change' }]
      }
    },
    onCreate() {
      this.dialog.form = { appId: undefined, apiId: undefined, envCode: 'prod', qpsLimit: 0, dailyQuota: 0, validFrom: '', validTo: '', grantReason: '' }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.dialog.visible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        await createGrant(form)
        this.$message.success('授权已提交（待审批）')
        this.dialog.visible = false
        this.reload()
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onApprove(row) {
      this.$prompt(`审批通过授权 #${row.id}，可填写审批意见`, '审批通过（高危）', {
        inputType: 'textarea'
      }).then(async({ value }) => {
        try {
          await approveGrant(row.id, { auditRemark: value, auditorName: '' })
          this.$message.success('已通过')
          this.reload()
        } catch (e) {}
      }).catch(() => {})
    },
    onReject(row) {
      this.$prompt(`驳回授权 #${row.id}，请填写驳回意见`, '审批驳回（高危）', {
        inputType: 'textarea',
        inputValidator: (v) => (v ? true : '驳回意见必填')
      }).then(async({ value }) => {
        try {
          await rejectGrant(row.id, { auditRemark: value })
          this.$message.success('已驳回')
          this.reload()
        } catch (e) {}
      }).catch(() => {})
    },
    onRevoke(row) {
      this.$prompt(`撤销授权 #${row.id}，请填写撤销原因`, '撤销授权（高危）', {
        inputType: 'textarea',
        inputValidator: (v) => (v ? true : '撤销原因必填')
      }).then(async({ value }) => {
        try {
          await revokeGrant(row.id, { revokeReason: value })
          this.$message.success('已撤销')
          this.reload()
        } catch (e) {}
      }).catch(() => {})
    },
    onRenew(row) {
      this.$prompt(`为授权 #${row.id} 设置新的失效日期（yyyy-MM-dd）`, '授权续期', {
        inputPlaceholder: 'yyyy-MM-dd',
        inputValidator: (v) => (/^\d{4}-\d{2}-\d{2}$/.test(v) ? true : '日期格式应为 yyyy-MM-dd')
      }).then(async({ value }) => {
        try {
          await renewGrant(row.id, { validTo: value })
          this.$message.success('已续期')
          this.reload()
        } catch (e) {}
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.perm-matrix .filter-card { margin-bottom: 12px; }
.perm-matrix .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.perm-matrix .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.perm-matrix .toolbar .spacer { flex: 1; }
.perm-matrix .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.perm-matrix .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.perm-matrix .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
