<template>
  <div class="page-container">
    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchInterfaces"
      :query="query"
      row-key="id"
      :show-index="true"
      @loaded="onLoaded"
    >
      <template #toolbar>
        <el-input
          v-model="query.kw"
          placeholder="接口名称 / 路径"
          clearable
          style="width: 220px"
          @keyup.enter.native="reload"
          @clear="reload"
        />
        <el-select v-model="query.groupId" placeholder="所属分组" clearable style="width: 160px" @change="reload">
          <el-option v-for="g in groupOptions" :key="g.value" :label="g.label" :value="g.value" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="reload">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="停用" />
        </el-select>
        <span class="spacer" />
        <PermButton perm="api:create" type="primary" icon="el-icon-plus" @click="openCreate">新建接口</PermButton>
      </template>

      <template #path="{ row }">
        <span class="method" :class="(row.requestMethod || 'GET').toLowerCase()">{{ row.requestMethod }}</span>
        <code class="mono">{{ row.interfacePath }}</code>
      </template>
      <template #groupName="{ row }">
        <!-- 优先用后端 JOIN 出的 groupName（列表 VO 已带），前端 map 仅作兜底：
             loadGroups 失败时不会整列退化成 '—' -->
        <span>{{ row.groupName || groupNameMap[row.groupId] || '—' }}</span>
      </template>
      <template #status="{ row }">
        <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
      </template>
      <template #actions="{ row }">
        <PermButton perm="" type="text" @click="openDetail(row)">详情</PermButton>
        <PermButton perm="api:update" type="text" @click="openEdit(row)">编辑</PermButton>
        <PermButton perm="api:disable" type="text" @click="toggleStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</PermButton>
        <PermButton perm="api:publish" type="text" @click="publish(row)">发布</PermButton>
        <PermButton perm="api:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
      </template>
    </CrudTable>

    <!-- 接口详情抽屉：参数 / 版本 / 环境配置 / 变更历史 4 Tab -->
    <el-drawer title="接口详情" :visible.sync="drawerVisible" direction="rtl" size="64%" @open="onDrawerOpen">
      <div v-if="currentApi" class="drawer-head">
        <div class="dh-name">{{ currentApi.interfaceName }}</div>
        <code class="mono dh-path">{{ currentApi.interfacePath }}</code>
      </div>
      <el-tabs v-model="activeTab" class="detail-tabs">
        <el-tab-pane label="参数定义" name="param"><ApiParamTab :api-id="currentApiId" /></el-tab-pane>
        <el-tab-pane label="版本管理" name="version"><ApiVersionTab :api-id="currentApiId" :api-name="currentApiName" /></el-tab-pane>
        <el-tab-pane label="环境配置" name="env"><ApiEnvConfigTab :api-id="currentApiId" /></el-tab-pane>
        <el-tab-pane label="变更历史" name="log"><ApiChangeLogTab :api-id="currentApiId" /></el-tab-pane>
      </el-tabs>
    </el-drawer>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="620px"
      @submit="submit"
    />
  </div>
</template>

<script>
/**
 * 接口列表管理页（T05 Phase 1 · 接口管理）
 * 列表对接 /interface/list（分页，current/size）；详情抽屉含 4 个子域 Tab：
 *   参数定义(api-param) / 版本管理(api-version) / 环境配置(api-env-config) / 变更历史(api-change-log)
 * 注意：后端 ApiInterface 实体字段为 interfaceName/interfacePath/requestMethod/requestParamType/
 *       groupId/backendUrl/status/timeoutMs/description，与原型计划的 apiCode/visibility/authRequired
 *       等字段不同，表单以真实后端字段为准。
 */
import {
  getInterfaceList, createInterface, updateInterface, updateInterfaceStatus, deleteInterface, publishInterface, getGroupList
} from '@/api/modules'
import ApiParamTab from './tabs/ApiParamTab.vue'
import ApiVersionTab from './tabs/ApiVersionTab.vue'
import ApiEnvConfigTab from './tabs/ApiEnvConfigTab.vue'
import ApiChangeLogTab from './tabs/ApiChangeLogTab.vue'

const METHOD_OPTIONS = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' }
]
const PARAM_TYPE_OPTIONS = [
  { value: 'JSON', label: 'JSON' },
  { value: 'FORM', label: 'FORM' },
  { value: 'QUERY', label: 'QUERY' }
]

export default {
  name: 'ApiList',
  components: { ApiParamTab, ApiVersionTab, ApiEnvConfigTab, ApiChangeLogTab },
  data() {
    return {
      groupOptions: [],
      groupNameMap: {},
      query: { kw: '', groupId: '', status: '' },
      columns: [
        { prop: 'interfaceName', label: '接口名称', minWidth: 140 },
        { prop: 'interfacePath', label: '路径 / 方法', minWidth: 260, slot: 'path' },
        { prop: 'groupId', label: '分组', minWidth: 120, slot: 'groupName' },
        { prop: 'backendUrl', label: '后端地址', minWidth: 200, showOverflowTooltip: true },
        { prop: 'status', label: '状态', width: 80, slot: 'status' },
        { prop: 'timeoutMs', label: '超时(ms)', width: 90, align: 'center' },
        { prop: 'createdAt', label: '创建时间', width: 160, formatter: (v) => this.fmtTime(v) }
      ],
      drawerVisible: false,
      activeTab: 'param',
      currentApi: null,
      dialogVisible: false,
      dialogTitle: '新建接口',
      submitting: false,
      form: {},
      fields: [
        { prop: 'interfaceName', label: '接口名称', type: 'input', required: true, maxlength: 64, span: 12 },
        { prop: 'interfacePath', label: '网关路径', type: 'input', required: true, placeholder: '/gateway/xxx', maxlength: 128, span: 12 },
        { prop: 'requestMethod', label: '请求方法', type: 'select', required: true, options: METHOD_OPTIONS, span: 12 },
        { prop: 'requestParamType', label: '入参类型', type: 'select', options: PARAM_TYPE_OPTIONS, span: 12 },
        { prop: 'groupId', label: '所属分组', type: 'select', options: [], span: 12 },
        { prop: 'status', label: '状态', type: 'select', required: true, options: [{ value: 1, label: '启用' }, { value: 0, label: '停用' }], span: 12 },
        { prop: 'backendUrl', label: '后端服务地址', type: 'input', span: 24, maxlength: 200 },
        { prop: 'timeoutMs', label: '转发超时(ms)', type: 'number', min: 0, max: 60000, span: 12 },
        { prop: 'description', label: '接口描述', type: 'textarea', span: 24, maxlength: 200 }
      ]
    }
  },
  computed: {
    currentApiId() { return this.currentApi ? this.currentApi.id : null },
    currentApiName() { return this.currentApi ? this.currentApi.interfaceName : '' }
  },
  mounted() {
    this.loadGroups()
  },
  methods: {
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async loadGroups() {
      try {
        const res = await getGroupList()
        const list = res.data || []
        this.groupOptions = list.map((g) => ({ value: g.id, label: g.groupName }))
        this.groupNameMap = {}
        list.forEach((g) => { this.groupNameMap[g.id] = g.groupName })
        // 🔴 必须把分组选项注入 fields，否则「新建/编辑接口」弹窗的「所属分组」下拉恒为空：
        // fields 是在 data() 里静态定义的，其 options 写的是 []，若不在数据到达后回填，
        // 用户永远选不到分组；更糟的是编辑时 form.groupId 落成空串，提交会把既有分组清掉
        // （2026-09-13 实测：未做任何修改点「确定」，payload 带 groupId:"" 覆盖原分组）。
        this.buildGroupOptions()
      } catch (e) { /* 拦截器已提示 */ }
    },
    /** 把已加载的分组选项注入 fields（沿用 ApiParamTab.buildParentOptions 的同款写法） */
    buildGroupOptions() {
      this.fields = this.fields.map((f) => f.prop === 'groupId' ? { ...f, options: this.groupOptions } : f)
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    onLoaded() {},
    async fetchInterfaces(params) {
      const { page, size, ...rest } = params
      const q = { current: page, size }
      if (rest.kw) q.interfaceName = rest.kw
      if (rest.groupId !== '' && rest.groupId != null) q.groupId = rest.groupId
      if (rest.status !== '' && rest.status != null) q.status = rest.status
      return getInterfaceList(q)
    },
    openDetail(row) {
      this.currentApi = row
      this.drawerVisible = true
    },
    onDrawerOpen() {},
    openCreate() {
      this.dialogTitle = '新建接口'
      this.form = {
        interfaceName: '',
        interfacePath: '',
        requestMethod: 'GET',
        requestParamType: 'JSON',
        groupId: null,
        status: 1,
        backendUrl: '',
        timeoutMs: 5000,
        description: ''
      }
      this.buildGroupOptions()
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '编辑接口'
      this.form = { ...row, groupId: row.groupId || null }
      this.buildGroupOptions()
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form }
        // 空串归一为 null：el-select 清空后会置 ''，而 '' 传到后端 Long 字段虽能被 Jackson
        // 转成 null，但语义上是「非法值」；统一在提交前收敛，避免把分组清成脏值。
        if (payload.groupId === '') payload.groupId = null
        if (payload.id) {
          await updateInterface(payload.id, payload)
          this.$message.success('接口已更新')
        } else {
          await createInterface(payload)
          this.$message.success('接口已创建')
        }
        this.dialogVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    toggleStatus(row) {
      const next = row.status === 1 ? 0 : 1
      const tip = next === 0 ? '停用' : '启用'
      this.$confirm(`确认${tip}接口「${row.interfaceName}」？`, `${tip}确认`, { type: 'warning' }).then(async () => {
        try {
          await updateInterfaceStatus(row.id, next)
          this.$message.success(`已${tip}`)
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    publish(row) {
      this.$confirm(`确认发布接口「${row.interfaceName}」？发布后将对外提供网关流量。`, '发布确认', { type: 'warning' }).then(async () => {
        try {
          await publishInterface(row.id)
          this.$message.success('接口已发布')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    remove(row) {
      this.$confirm(`确认删除接口「${row.interfaceName}」？删除后相关授权与配置将不可用。`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await deleteInterface(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; color: #17233d; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
.drawer-head { display: flex; align-items: center; gap: 12px; padding: 0 4px 12px; border-bottom: 1px solid #eef1f7; margin-bottom: 8px; }
.dh-name { font-size: 15px; font-weight: 600; color: #17233d; }
.dh-path { color: #5c6b8a; }
.detail-tabs { margin-top: 4px; }
</style>
