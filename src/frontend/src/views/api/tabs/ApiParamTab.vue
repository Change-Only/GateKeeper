<template>
  <div class="api-tab">
    <div class="tab-toolbar">
      <PermButton perm="api_param:create" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新建参数</PermButton>
      <span class="tab-hint">参数类型：Header/Query/Body/Response/Error；支持通过「父级参数」构造嵌套结构（如 items.skuId）</span>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchParams"
      :query="query"
      row-key="id"
      :show-pagination="false"
    >
      <template #paramType="{ row }">
        <el-tag size="small" :type="paramTypeMeta(row.paramType).type">{{ paramTypeMeta(row.paramType).label }}</el-tag>
      </template>
      <template #required="{ row }">
        <el-tag :type="row.required === 1 ? 'danger' : 'info'" size="small">{{ row.required === 1 ? '必填' : '选填' }}</el-tag>
      </template>
      <template #sensitive="{ row }">
        <el-tag v-if="row.sensitive === 1" type="warning" size="small">敏感</el-tag>
        <span v-else>—</span>
      </template>
      <template #fieldType="{ row }">
        <code class="mono">{{ row.fieldType || '—' }}</code>
      </template>
      <template #actions="{ row }">
        <PermButton perm="api_param:update" type="text" @click="openEdit(row)">编辑</PermButton>
        <PermButton perm="api_param:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
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
 * 接口参数定义 Tab（T05 Phase 1 · api-list 详情）
 * 对接 /api-param/* ：list/create/update/delete。字段严格对齐 ApiParamDto。
 */
import { getApiParamList, createApiParam, updateApiParam, deleteApiParam } from '@/api/modules'

const PARAM_TYPES = [
  { value: 1, label: 'Header' },
  { value: 2, label: 'Query' },
  { value: 3, label: 'Body' },
  { value: 4, label: 'Response' },
  { value: 5, label: 'Error' }
]
const FIELD_TYPES = [
  { value: 'string', label: '字符串' },
  { value: 'int', label: '整数' },
  { value: 'number', label: '数字' },
  { value: 'array', label: '数组' },
  { value: 'boolean', label: '布尔' },
  { value: 'object', label: '对象' }
]
const ENCRYPT_RULES = [
  { value: 'NONE', label: '不处理' },
  { value: 'SYMMETRIC', label: '对称加密' },
  { value: 'MASK', label: '脱敏' }
]

export default {
  name: 'ApiParamTab',
  props: {
    apiId: { type: [Number, String], default: null }
  },
  data() {
    return {
      query: { apiId: this.apiId },
      columns: [
        { prop: 'fieldName', label: '字段名', minWidth: 140 },
        { prop: 'paramType', label: '参数类型', width: 90, slot: 'paramType' },
        { prop: 'fieldType', label: '字段类型', width: 100, slot: 'fieldType' },
        { prop: 'required', label: '必填', width: 70, slot: 'required' },
        { prop: 'sensitive', label: '敏感', width: 70, slot: 'sensitive' },
        { prop: 'example', label: '示例值', minWidth: 140, showOverflowTooltip: true },
        { prop: 'description', label: '说明', minWidth: 160, showOverflowTooltip: true },
        { prop: 'sortOrder', label: '排序', width: 70, align: 'center' }
      ],
      dialogVisible: false,
      dialogTitle: '新建参数',
      submitting: false,
      form: {},
      fields: [
        { prop: 'fieldName', label: '字段名', type: 'input', required: true, maxlength: 64, span: 12 },
        { prop: 'paramType', label: '参数类型', type: 'select', required: true, options: PARAM_TYPES, span: 12 },
        { prop: 'fieldType', label: '字段类型', type: 'select', options: FIELD_TYPES, span: 12 },
        { prop: 'parentId', label: '父级参数', type: 'select', options: [], span: 12, placeholder: '顶层参数（无父级）' },
        { prop: 'required', label: '是否必填', type: 'switch', activeValue: 1, inactiveValue: 0, span: 12 },
        { prop: 'sensitive', label: '敏感字段', type: 'switch', activeValue: 1, inactiveValue: 0, span: 12 },
        { prop: 'encryptRule', label: '加解密规则', type: 'select', options: ENCRYPT_RULES, span: 12 },
        { prop: 'example', label: '示例值', type: 'input', span: 24, maxlength: 200 },
        { prop: 'description', label: '字段说明', type: 'textarea', span: 24, maxlength: 200 },
        { prop: 'sortOrder', label: '排序', type: 'number', min: 0, max: 9999, span: 12 }
      ]
    }
  },
  watch: {
    apiId(v) {
      this.query = { apiId: v }
    }
  },
  methods: {
    paramTypeMeta(v) {
      const m = PARAM_TYPES.find((x) => x.value === v)
      return m ? { label: m.label, type: 'info' } : { label: String(v), type: 'info' }
    },
    async fetchParams() {
      const res = await getApiParamList({ apiId: this.apiId })
      const list = res.data || []
      return { list, total: list.length }
    },
    async buildParentOptions(excludeId) {
      const res = await getApiParamList({ apiId: this.apiId })
      const opts = [{ value: 0, label: '顶层参数（无父级）' }]
      ;(res.data || []).forEach((p) => {
        const pid = p.parentId
        if ((pid == null || pid === 0) && (excludeId == null || p.id !== excludeId)) {
          opts.push({ value: p.id, label: p.fieldName })
        }
      })
      this.fields = this.fields.map((f) => f.prop === 'parentId' ? { ...f, options: opts } : f)
    },
    openCreate() {
      this.dialogTitle = '新建参数'
      this.form = {
        apiId: this.apiId,
        fieldName: '',
        paramType: 1,
        fieldType: 'string',
        required: 0,
        sensitive: 0,
        encryptRule: 'NONE',
        example: '',
        description: '',
        sortOrder: 0,
        parentId: 0
      }
      this.buildParentOptions(null)
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '编辑参数'
      this.form = { ...row, parentId: row.parentId || 0 }
      this.buildParentOptions(row.id)
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form, apiId: this.apiId }
        if (payload.parentId === 0) payload.parentId = null
        if (payload.id) {
          await updateApiParam(payload.id, payload)
          this.$message.success('参数已更新')
        } else {
          await createApiParam(payload)
          this.$message.success('参数已创建')
        }
        this.dialogVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    remove(row) {
      this.$confirm(`确认删除参数「${row.fieldName}」？`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await deleteApiParam(row.id)
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
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; color: #17233d; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
