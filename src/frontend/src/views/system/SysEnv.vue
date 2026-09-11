<template>
  <div class="page-container sys-env">
    <div class="page-head">
      <h2>环境与网关</h2>
      <span class="page-tag">dev / test / pre / prod 四套环境主数据 · envCode 创建后不可修改</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="环境编码">
          <el-input v-model="query.envCode" placeholder="模糊匹配环境编码" clearable style="width:200px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:120px">
            <el-option :value="1" label="启用" />
            <el-option :value="0" label="停用" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条环境 · 四套环境固定为 dev/test/pre/prod</span>
      <span class="spacer" />
      <PermButton perm="env:update" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建环境</PermButton>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchData"
      :query="query"
      :actions-width="220"
    >
      <template #envCode="{row}">
        <el-tag size="small" effect="plain" :type="envCodeTagType(row.envCode)">{{ row.envCode }}</el-tag>
      </template>
      <template #https="{row}">
        <el-tag size="small" :type="row.https === 1 ? 'success' : 'warning'" effect="plain">{{ row.https === 1 ? 'HTTPS' : 'HTTP' }}</el-tag>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'env'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton perm="env:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="env:delete" type="text" size="mini" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑环境' : '新建环境'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'600px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-form-item v-if="form.id" label="环境编码提示">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>envCode 创建后不可修改（架构 D1）</template>
          </el-alert>
        </el-form-item>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getEnvList,
  createEnv,
  updateEnv,
  deleteEnv
} from '@/api/modules'

const ENV_CODE_OPTIONS = [
  { value: 'dev', label: 'dev · 开发环境' },
  { value: 'test', label: 'test · 测试环境' },
  { value: 'pre', label: 'pre · 预发环境' },
  { value: 'prod', label: 'prod · 生产环境' }
]

const ENV_CODE_TAG_TYPES = { dev: 'info', test: 'primary', pre: 'warning', prod: 'danger' }

export default {
  name: 'SysEnv',
  data() {
    return {
      query: { envCode: '', status: undefined },
      total: 0,
      columns: [
        { prop: 'envCode', label: '环境编码', width: 110, slot: 'envCode' },
        { prop: 'envName', label: '环境名称', minWidth: 120, showOverflowTooltip: true },
        { prop: 'gatewayUrl', label: '网关入口地址', minWidth: 220, showOverflowTooltip: true },
        { prop: 'https', label: '协议', width: 80, slot: 'https' },
        { prop: 'sortOrder', label: '排序', width: 70, align: 'right' },
        { prop: 'status', label: '状态', width: 80, slot: 'status' },
        { prop: 'updatedAt', label: '更新时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'createdAt', label: '创建时间', width: 170, formatter: (v) => v || '—' }
      ],
      dialog: {
        visible: false,
        loading: false,
        form: {},
        fields: [],
        rules: {}
      }
    }
  },
  methods: {
    envCodeTagType(code) {
      return ENV_CODE_TAG_TYPES[code] || 'info'
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getEnvList({
          pageNum: page,
          pageSize: size,
          ...rest
        })
        const data = (res && res.data) || {}
        self.total = data.total || 0
        return { list: data.records || [], total: data.total || 0 }
      }
    }(),
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    resetQuery() {
      this.query.envCode = ''
      this.query.status = undefined
      this.reload()
    },
    buildFields(editing) {
      return [
        {
          prop: 'envCode', label: '环境编码', type: 'select', required: true, span: 12,
          options: ENV_CODE_OPTIONS,
          placeholder: editing
            ? '编辑时不可修改'
            : '请选择环境编码',
          ...(editing ? { disabled: true } : {})
        },
        { prop: 'envName', label: '环境名称', type: 'input', required: true, span: 12, placeholder: '如：开发环境' },
        { prop: 'gatewayUrl', label: '网关入口地址', type: 'input', required: true, span: 24, placeholder: '如：https://api-pre.example.com' },
        { prop: 'sortOrder', label: '排序', type: 'number', span: 12, min: 0, step: 1, placeholder: '数字越小越靠前' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    buildRules() {
      return {
        envCode: [{ required: true, message: '环境编码不能为空', trigger: 'change' }],
        envName: [{ required: true, message: '环境名称不能为空', trigger: 'blur' }],
        gatewayUrl: [{ required: true, message: '网关地址不能为空', trigger: 'blur' }]
      }
    },
    onCreate() {
      this.dialog.form = {
        envCode: undefined,
        envName: '',
        gatewayUrl: '',
        sortOrder: 0,
        status: 1
      }
      this.dialog.fields = this.buildFields(false)
      this.dialog.rules = this.buildRules()
      this.dialog.visible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.buildFields(true)
      this.dialog.rules = this.buildRules()
      this.dialog.visible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateEnv(form)
          this.$message.success('环境已更新')
        } else {
          await createEnv(form)
          this.$message.success('环境已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {} finally {
        this.dialog.loading = false
      }
    },
    onDelete(row) {
      this.$confirm(`确认删除环境「${row.envName}(${row.envCode})」？`, '删除确认（高危）', { type: 'warning' })
        .then(async() => {
          try {
            await deleteEnv(row.id)
            this.$message.success('已删除')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-env .filter-card { margin-bottom: 12px; }
.sys-env .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-env .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-env .toolbar .spacer { flex: 1; }
.sys-env .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-env .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-env .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>