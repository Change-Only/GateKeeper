<template>
  <div class="page-container sys-bizline">
    <div class="page-head">
      <h2>业务线管理</h2>
      <span class="page-tag">业务线主数据 · lineCode 全局唯一 · 创建后不可修改</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="编码 / 名称模糊匹配" clearable style="width:220px" @keyup.enter.native="reload" />
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
      <span class="toolbar-tip">共 {{ total }} 条业务线 · 负责人 / 成员数为手填统计</span>
      <span class="spacer" />
      <PermButton perm="biz_line:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建业务线</PermButton>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchData"
      :query="query"
      :actions-width="200"
    >
      <template #lineCode="{row}">
        <el-tag size="small" effect="plain" type="primary">{{ row.lineCode }}</el-tag>
      </template>
      <template #memberCount="{row}">
        <span>{{ row.memberCount || 0 }} 人</span>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'bizLine'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton perm="biz_line:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="biz_line:delete" type="text" size="mini" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑业务线' : '新建业务线'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'600px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-form-item v-if="form.id" label="编码提示">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>lineCode 创建后不可修改（DB 唯一约束 uk_bizline_code）</template>
          </el-alert>
        </el-form-item>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getBizLineList,
  createBizLine,
  updateBizLine,
  deleteBizLine
} from '@/api/modules'

export default {
  name: 'SysBizLine',
  data() {
    return {
      query: { keyword: '', status: undefined },
      total: 0,
      columns: [
        { prop: 'lineCode', label: '业务线编码', width: 130, slot: 'lineCode' },
        { prop: 'lineName', label: '业务线名称', minWidth: 140, showOverflowTooltip: true },
        { prop: 'ownerName', label: '负责人', width: 100, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'memberCount', label: '成员数', width: 90, align: 'right', slot: 'memberCount' },
        { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 80, slot: 'status' },
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
    async fetchData(params) {
      // CrudTable sends {page, size, ...query}; backend expects pageNum/pageSize
      const { page, size, ...rest } = params
      const res = await getBizLineList({
        pageNum: page,
        pageSize: size,
        ...rest
      })
      const data = (res && res.data) || {}
      this.total = data.total || 0
      return { list: data.records || [], total: data.total || 0 }
    },
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    resetQuery() {
      this.query.keyword = ''
      this.query.status = undefined
      this.reload()
    },
    buildFields(editing) {
      return [
        {
          prop: 'lineCode', label: '业务线编码', type: 'input', required: true, span: 12,
          placeholder: editing ? '编辑时不可修改' : '如：trade / pay / user',
          ...(editing ? { disabled: true } : {})
        },
        { prop: 'lineName', label: '业务线名称', type: 'input', required: true, span: 12, placeholder: '业务线中文名称' },
        { prop: 'ownerName', label: '负责人', type: 'input', span: 12, placeholder: '手填，非用户关联' },
        { prop: 'memberCount', label: '成员数', type: 'number', span: 12, min: 0, step: 1, placeholder: '手填统计' },
        { prop: 'remark', label: '备注', type: 'textarea', span: 24, rows: 3, placeholder: '可选 · 不超过 512 字' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    buildRules() {
      return {
        lineCode: [{ required: true, message: '业务线编码不能为空', trigger: 'blur' }],
        lineName: [{ required: true, message: '业务线名称不能为空', trigger: 'blur' }]
      }
    },
    onCreate() {
      this.dialog.form = {
        lineCode: '',
        lineName: '',
        ownerName: '',
        memberCount: 0,
        remark: '',
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
          await updateBizLine(form)
          this.$message.success('业务线已更新')
        } else {
          await createBizLine(form)
          this.$message.success('业务线已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {
        // axios 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onDelete(row) {
      this.$confirm(`确认删除业务线「${row.lineName}(${row.lineCode})」？`, '删除确认（高危）', { type: 'warning' })
        .then(async() => {
          try {
            await deleteBizLine(row.id)
            this.$message.success('已删除')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-bizline .filter-card { margin-bottom: 12px; }
.sys-bizline .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-bizline .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-bizline .toolbar .spacer { flex: 1; }
.sys-bizline .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-bizline .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-bizline .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
