<template>
  <div class="page-container sys-config">
    <div class="page-head">
      <h2>参数配置</h2>
      <span class="page-tag">按 SECURITY / GATEWAY / LOG / DEFAULT 分组管理</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="配置键 / 名称" clearable style="width:220px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-tabs v-model="activeGroup" class="group-tabs" @tab-click="onGroupChange">
      <el-tab-pane v-for="g in groups" :key="g.code" :name="g.code" :label="g.label" />
    </el-tabs>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条配置 · 当前分组：{{ activeGroupLabel }}</span>
      <span class="spacer" />
      <PermButton perm="sys:config:update" type="primary" icon="el-icon-plus" size="small" @click="onCreate">新建配置</PermButton>
    </div>

    <!--
      🔴 不存在 #status 插槽 / 「状态」列（2026-09-13 移除）：
      sys_config 表**没有 status 列**（实测 SHOW COLUMNS：id/config_key/config_value/config_group/
      config_name/sensitive/built_in/remark/created_at/updated_at）；后端 ConfigServiceImpl.pageQuery
      也注明「status 入参不参与过滤（前端兼容保留）」。
      原先前端既留了「状态」筛选下拉（选了没任何效果）、又留了「状态」列 + StatusTag，
      实际渲染结果是整列字面量 "undefined"（getStatusMeta 对 undefined 走 String(value) 兜底）。
      这类「原型遗留但真实 schema 没有」的字段一律以前端对齐真实字段处理，不新增后端列。
    -->
    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchData"
      :query="query"
      :actions-width="200"
    >
      <template #sensitive="{row}">
        <el-tag :type="row.sensitive === 1 ? 'danger' : 'info'" size="small" effect="plain">{{ row.sensitive === 1 ? '是' : '否' }}</el-tag>
      </template>
      <template #builtIn="{row}">
        <el-tag :type="row.builtIn === 1 ? 'warning' : 'info'" size="small" effect="plain">{{ row.builtIn === 1 ? '内置' : '普通' }}</el-tag>
      </template>
      <template #actions="{row}">
        <PermButton perm="sys:config:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton
          perm="sys:config:update"
          type="text"
          size="mini"
          :disabled="row.builtIn === 1"
          @click="onDelete(row)"
        >删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialog.form.id ? '编辑配置' : '新建配置'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'640px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-form-item v-if="form.sensitive === 1" label="敏感提示">
          <el-alert type="warning" :closable="false" show-icon>
            <template #title>该配置标记为敏感，写入后列表展示脱敏为 ******，但明文仍按入参落库。</template>
          </el-alert>
        </el-form-item>
        <el-form-item v-if="form.builtIn === 1" label="内置保护">
          <el-tag type="warning" size="small" effect="plain">内置配置不可删除</el-tag>
        </el-form-item>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getConfigList,
  createConfig,
  updateConfig,
  deleteConfig
} from '@/api/modules'

const GROUP_OPTIONS = [
  { code: 'SECURITY', label: '安全策略' },
  { code: 'GATEWAY', label: '网关路由' },
  { code: 'LOG', label: '日志策略' },
  { code: 'DEFAULT', label: '通用' }
]

export default {
  name: 'SysConfig',
  data() {
    return {
      groups: GROUP_OPTIONS,
      activeGroup: 'SECURITY',
      query: { keyword: '', configGroup: 'SECURITY' },
      total: 0,
      columns: [
        { prop: 'configName', label: '配置名称', minWidth: 160 },
        { prop: 'configKey', label: '配置键', minWidth: 180, showOverflowTooltip: true },
        {
          prop: 'configValue',
          label: '配置值',
          minWidth: 200,
          formatter: (v, row) => (row.sensitive === 1 ? '******' : v || '—')
        },
        {
          prop: 'configGroup',
          label: '分组',
          width: 100,
          formatter: (v) => (GROUP_OPTIONS.find((g) => g.code === v) || {}).label || v
        },
        { prop: 'sensitive', label: '敏感', width: 70, slot: 'sensitive' },
        { prop: 'builtIn', label: '内置', width: 70, slot: 'builtIn' },
        { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true },
        { prop: 'createdAt', label: '创建时间', width: 170, formatter: (v) => v || '—' }
      ],
      dialogVisible: false,
      dialog: {
        loading: false,
        form: {},
        fields: [],
        rules: {}
      }
    }
  },
  computed: {
    activeGroupLabel() {
      const g = this.groups.find((x) => x.code === this.activeGroup)
      return g ? `${g.code} · ${g.label}` : this.activeGroup
    }
  },
  methods: {
    async fetchData(params) {
      // CrudTable sends {page, size, ...query}; backend expects pageNum/pageSize
      const { page, size, ...rest } = params
      const res = await getConfigList({
        pageNum: page,
        pageSize: size,
        ...rest
      })
      const data = (res && res.data) || {}
      this.total = data.total || 0
      return { list: data.records || [], total: data.total || 0 }
    },
    onGroupChange(tab) {
      this.query.configGroup = tab.name
      this.reload()
    },
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    resetQuery() {
      this.query.keyword = ''
      this.reload()
    },
    buildFields() {
      return [
        { prop: 'configKey', label: '配置键', type: 'input', required: true, placeholder: '请输入配置键', span: 12 },
        { prop: 'configName', label: '配置名称', type: 'input', required: true, placeholder: '请输入配置名称', span: 12 },
        {
          prop: 'configGroup', label: '配置分组', type: 'select', required: true, span: 12,
          options: GROUP_OPTIONS.map((g) => ({ value: g.code, label: g.label }))
        },
        {
          prop: 'configValue', label: '配置值', type: 'textarea', required: false, span: 12, rows: 3,
          placeholder: '请输入配置值'
        },
        { prop: 'sensitive', label: '敏感配置', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 },
        { prop: 'remark', label: '备注', type: 'textarea', span: 24, rows: 2, placeholder: '可选' }
      ]
    },
    buildRules() {
      return {
        configKey: [{ required: true, message: '配置键不能为空', trigger: 'blur' }],
        configName: [{ required: true, message: '配置名称不能为空', trigger: 'blur' }]
      }
    },
    onCreate() {
      this.dialog.form = {
        configKey: '',
        configName: '',
        configValue: '',
        configGroup: this.activeGroup,
        sensitive: 0,
        builtIn: 0,
        remark: ''
      }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.dialogVisible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.dialogVisible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateConfig(form)
          this.$message.success('配置已更新')
        } else {
          await createConfig(form)
          this.$message.success('配置已创建')
        }
        this.dialogVisible = false
        this.reload()
      } catch (e) {
        // axios 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onDelete(row) {
      this.$confirm(`确认删除配置「${row['configName'] || row['configKey']}」？`, '删除确认', {
        type: 'warning'
      }).then(async() => {
        try {
          await deleteConfig(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) {}
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-config .filter-card { margin-bottom: 12px; }
.sys-config .group-tabs { background: #fff; padding: 0 16px; margin-bottom: 8px; border-radius: 4px; box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.sys-config .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-config .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-config .toolbar .spacer { flex: 1; }
.sys-config .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-config .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-config .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>