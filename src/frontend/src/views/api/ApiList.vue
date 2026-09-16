<template>
  <div class="page-container">
    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchInterfaces"
      :query="query"
      row-key="id"
      :show-index="true"
      :actions-width="300"
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
        <!-- 所属分组：按分组的层级结构树形展示，任意层级均可选。
             这里**刻意不写 @change="reload"** —— CrudTable 对 query 有 deep watcher，
             query.groupId 一变即自动回第 1 页重载；再绑 @change 会让每次选择发两次列表请求。 -->
        <el-cascader
          v-model="query.groupId"
          :options="groupTree"
          :props="groupCascaderProps"
          placeholder="所属分组"
          clearable
          style="width: 200px"
        />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="reload">
          <el-option :value="1" label="启用" />
          <el-option :value="0" label="停用" />
        </el-select>
        <span class="spacer" />
        <!-- T18：导入 OpenAPI 3.0。权限点与「新建接口」同为 api:create ——
             导入的产物就是新建出来的接口资产，是同一能力
             （详见后端 InterfaceController#importSpec 的说明）。 -->
        <PermButton perm="api:create" icon="el-icon-upload2" @click="openImport">导入</PermButton>
        <PermButton perm="api:create" type="primary" icon="el-icon-plus" @click="openCreate">新建接口</PermButton>
      </template>

      <template #path="{ row }">
        <span class="method" :class="(row.requestMethod || 'GET').toLowerCase()">{{ row.requestMethod }}</span>
        <!-- T17 掩码态：不在「接口信息保护」可见性白名单内时后端只回 ****，
             这里给出可解释的锁标记，避免用户以为数据坏了 -->
        <el-tooltip v-if="row.pathMasked" placement="top" content="当前账号无权查看接口路径明文（受「接口信息保护」限制）">
          <span class="mask-path"><i class="el-icon-lock" /> {{ row.interfacePath }}</span>
        </el-tooltip>
        <code v-else class="mono">{{ row.interfacePath }}</code>
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
        <PermButton perm="interface:test" type="text" @click="openTest(row)">测试</PermButton>
        <PermButton perm="api:update" type="text" @click="openEdit(row)">编辑</PermButton>
        <PermButton perm="api:disable" type="text" @click="toggleStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</PermButton>
        <PermButton perm="api:publish" type="text" @click="publish(row)">发布</PermButton>
        <PermButton perm="api:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
      </template>
    </CrudTable>

    <!-- 接口详情抽屉：顶部「概览区」（方法徽标 + 名称/路径 + 启停状态 + 关键字段栅格），
         下方 参数 / 版本 / 环境配置 / 变更历史 4 Tab。
         原实现只在头部平铺「接口名 + 路径」两行，method/分组/后端地址/超时/描述等
         关键信息全部看不到，是「详情页单薄」的主因。 -->
    <el-drawer title="接口详情" :visible.sync="drawerVisible" direction="rtl" size="64%" @open="onDrawerOpen">
      <div v-if="currentApi" class="detail-body">
        <div class="detail-hero">
          <div class="hero-top">
            <span class="method" :class="(currentApi.requestMethod || 'GET').toLowerCase()">{{ currentApi.requestMethod || 'GET' }}</span>
            <div class="hero-title-wrap">
              <div class="hero-title">{{ currentApi.interfaceName }}</div>
              <code class="hero-path">{{ currentApi.interfacePath }}</code>
              <el-tag v-if="currentApi.pathMasked" size="mini" type="warning" effect="plain">
                路径对当前账号隐藏
              </el-tag>
            </div>
            <el-tag :type="currentApi.status === 1 ? 'success' : 'info'" size="small">
              {{ currentApi.status === 1 ? '启用中' : '已停用' }}
            </el-tag>
          </div>

          <!-- 关键字段栅格：与「新建/编辑接口」弹窗字段同口径，只读展示 -->
          <el-descriptions class="hero-meta" :column="2" size="mini" border>
            <el-descriptions-item label="所属分组">{{ currentGroupName }}</el-descriptions-item>
            <el-descriptions-item label="入参类型">{{ currentApi.requestParamType || '—' }}</el-descriptions-item>
            <el-descriptions-item label="默认后端地址" :span="2">
              <span class="mono">{{ currentApi.backendUrl || '—' }}</span>
              <span class="sub-text">（兜底地址：接口所在分组在对应环境下没有配服务前缀时，网关转发到这里）</span>
            </el-descriptions-item>
            <el-descriptions-item label="转发超时">
              {{ currentApi.timeoutMs != null ? currentApi.timeoutMs + ' ms' : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ fmtTime(currentApi.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="接口描述" :span="2">{{ currentApi.description || '—' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <el-tabs v-model="activeTab" class="detail-tabs">
          <el-tab-pane label="参数定义" name="param"><ApiParamTab :api-id="currentApiId" /></el-tab-pane>
          <el-tab-pane label="版本管理" name="version"><ApiVersionTab :api-id="currentApiId" :api-name="currentApiName" /></el-tab-pane>
          <el-tab-pane label="环境配置" name="env">
            <!-- 透传 interfacePath / requestMethod / groupId：
                 环境配置 Tab 需要 ① 展示「各环境共用的 URI」，把服务前缀拼成完整后端地址；
                 ② 用 groupId 作为**继承链起点**去解析生效配置（T13 环境配置下沉到分组侧）。 -->
            <ApiEnvConfigTab
              :api-id="currentApiId"
              :group-id="currentApi.groupId"
              :interface-path="currentApi.interfacePath"
              :request-method="currentApi.requestMethod"
            />
          </el-tab-pane>
          <el-tab-pane label="变更历史" name="log"><ApiChangeLogTab :api-id="currentApiId" /></el-tab-pane>
        </el-tabs>
      </div>
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

    <!-- 接口测试弹窗（T13 · 需求第 2 条）：弹窗内可切换「直连后端 / 走网关」两种模式 -->
    <InterfaceTestDialog :visible.sync="testVisible" :api="testApi" />

    <!-- OpenAPI 3.0 导入弹窗（T18）：分组树由本页已加载的 groupTree 透传，避免重复请求 -->
    <InterfaceImportDialog :visible.sync="importVisible" :group-tree="groupTree" @imported="onImported" />
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
  getInterfaceList, createInterface, updateInterface, updateInterfaceStatus, deleteInterface, publishInterface, getGroupList, getGroupTree
} from '@/api/modules'
import ApiParamTab from './tabs/ApiParamTab.vue'
import ApiVersionTab from './tabs/ApiVersionTab.vue'
import ApiEnvConfigTab from './tabs/ApiEnvConfigTab.vue'
import ApiChangeLogTab from './tabs/ApiChangeLogTab.vue'
import InterfaceTestDialog from './InterfaceTestDialog.vue'
import InterfaceImportDialog from './InterfaceImportDialog.vue'

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
  components: { ApiParamTab, ApiVersionTab, ApiEnvConfigTab, ApiChangeLogTab, InterfaceTestDialog, InterfaceImportDialog },
  data() {
    return {
      groupTree: [],
      // el-cascader 配置：值/标签/子节点字段名对齐后端 /group/tree 的返回形状；
      // emitPath:false ⇒ v-model 直接是分组 id（单值），与 el-select 契约一致，提交逻辑无需改造；
      // checkStrictly:true ⇒ 任意层级都可选（父分组本身也是合法的归属目标）。
      groupCascaderProps: { value: 'id', label: 'groupName', children: 'children', checkStrictly: true, emitPath: false },
      groupNameMap: {},
      query: { kw: '', groupId: '', status: '' },
      columns: [
        { prop: 'interfaceName', label: '接口名称', minWidth: 140 },
        { prop: 'interfacePath', label: '路径 / 方法', minWidth: 240, slot: 'path' },
        { prop: 'groupId', label: '分组', minWidth: 120, slot: 'groupName' },
        { prop: 'backendUrl', label: '后端地址', minWidth: 180, showOverflowTooltip: true },
        { prop: 'status', label: '状态', width: 80, slot: 'status' },
        { prop: 'timeoutMs', label: '超时(ms)', width: 90, align: 'center' }
        // 🔴 刻意**去掉「创建时间」列**（2026-09-14）：
        //    操作列默认宽 160px 装不下 6 个按钮（本轮新增「测试」），而操作列是 fixed="right"；
        //    固定列变宽会挤压中间列 —— 这正是用户反馈的「数字列看不到值」的同一机制
        //    （接口侧环境配置表 10 列 + 固定操作列把数字列挤出可视区）。
        //    创建时间在「详情 → 概览区」里本来就有，列表去掉不丢信息，换来 160px 空间，
        //    比"看得见但被挤变形"更诚实。
      ],
      drawerVisible: false,
      activeTab: 'param',
      currentApi: null,
      /** T17：当前编辑行的路径是否为掩码（不在可见性白名单内）——决定弹窗里路径字段是否锁定 */
      pathMasked: false,
      dialogVisible: false,
      dialogTitle: '新建接口',
      submitting: false,
      form: {},
      // 接口测试弹窗（T13）
      testVisible: false,
      testApi: null,
      // OpenAPI 导入弹窗（T18）
      importVisible: false,
      fields: [
        { prop: 'interfaceName', label: '接口名称', type: 'input', required: true, maxlength: 64, span: 12 },
        { prop: 'interfacePath', label: '网关路径', type: 'input', required: true, placeholder: '/gateway/xxx', maxlength: 128, span: 12 },
        { prop: 'requestMethod', label: '请求方法', type: 'select', required: true, options: METHOD_OPTIONS, span: 12 },
        { prop: 'requestParamType', label: '入参类型', type: 'select', options: PARAM_TYPE_OPTIONS, span: 12 },
        { prop: 'groupId', label: '所属分组', type: 'tree-select', labelKey: 'groupName', options: [], span: 12, required: true, placeholder: '请选择所属分组（必填）' },
        { prop: 'status', label: '状态', type: 'select', required: true, options: [{ value: 1, label: '启用' }, { value: 0, label: '停用' }], span: 12 },
        { prop: 'backendUrl', label: '后端服务地址', type: 'input', span: 24, maxlength: 200 },
        { prop: 'timeoutMs', label: '转发超时(ms)', type: 'number', min: 0, max: 60000, span: 12 },
        { prop: 'description', label: '接口描述', type: 'textarea', span: 24, maxlength: 200 }
      ]
    }
  },
  computed: {
    currentApiId() { return this.currentApi ? this.currentApi.id : null },
    currentApiName() { return this.currentApi ? this.currentApi.interfaceName : '' },
    /** 详情概览里的所属分组名：优先用列表 VO 的 groupName，前端 map 仅兜底（同列表列的口径） */
    currentGroupName() {
      const row = this.currentApi
      if (!row) return '—'
      return row.groupName || this.groupNameMap[row.groupId] || '未分组'
    }
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
        // 树（/group/tree，带 children）供「查询 + 新增/编辑」的树形选择器使用；
        // 扁平列表（/group/list）只用于列表单元格的名称兜底 map —— 两者职责不同，不可互相替代。
        const [treeRes, listRes] = await Promise.all([getGroupTree(), getGroupList()])
        this.groupTree = treeRes.data || []
        const list = listRes.data || []
        this.groupNameMap = {}
        list.forEach((g) => { this.groupNameMap[g.id] = g.groupName })
        // 🔴 必须把分组树注入 fields，否则「新建/编辑接口」弹窗的「所属分组」恒为空：
        // fields 是在 data() 里静态定义的，其 options 写的是 []，若不在数据到达后回填，
        // 用户永远选不到分组；更糟的是编辑时 form.groupId 落成空串，提交会把既有分组清掉
        // （2026-09-13 实测：未做任何修改点「确定」，payload 带 groupId:"" 覆盖原分组）。
        this.buildGroupOptions()
      } catch (e) { /* 拦截器已提示 */ }
    },
    /**
     * 回填 fields 的动态部分（分组树 options + T17 路径掩码态）。
     *
     * 之所以把两件事放同一个方法：fields 是「整体重建」的（map 出新的数组），
     * 若分成两个方法各重建一次，后执行的会把前一次的结果丢掉。
     */
    buildGroupOptions() {
      this.fields = this.fields.map((f) => {
        if (f.prop === 'groupId') return { ...f, options: this.groupTree }
        if (f.prop === 'interfacePath') {
          return this.pathMasked
            ? {
              ...f,
              disabled: true,
              hint: '当前账号无权查看该接口路径明文（受「接口信息保护」限制）。'
                + '此字段本次不会被修改 —— 直接保存不会影响已有的路径。'
            }
            : { ...f, disabled: false, hint: '' }
        }
        return f
      })
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
    /** 打开接口测试弹窗（T13 · 需求第 2 条）。先把 row 赋给 testApi，弹窗挂载时字段才是齐的。 */
    openTest(row) {
      this.testApi = row
      this.testVisible = true
    },
    /**
     * 打开 OpenAPI 导入弹窗（T18）。
     *
     * <p>分组树若还没加载完（用户一进页面就点导入），先补一次请求 ——
     * 否则弹窗里的分组下拉是空的，而「必须先选分组」就变成了死路。</p>
     */
    async openImport() {
      if (!this.groupTree || this.groupTree.length === 0) {
        await this.loadGroups()
      }
      this.importVisible = true
    },
    /** 导入完成：结果面板由弹窗内部展示，这里只负责把列表刷新到最新。 */
    onImported() {
      this.reload()
    },
    onDrawerOpen() {},
    openCreate() {
      this.dialogTitle = '新建接口'
      this.pathMasked = false
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
      // T17：行级 pathMasked 决定路径字段是否锁定（锁定后提交的仍是 ****，
      // 后端 InterfaceServiceImpl.applyPathWriteBackGuard 会按「本次不改」处理）
      this.pathMasked = !!row.pathMasked
      this.form = { ...row, groupId: row.groupId || null }
      this.buildGroupOptions()
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form }
        // 空串归一为 null：控件清空后可能置 ''（旧 el-select）或 []（多选 cascader），
        // 传到后端 Long 字段虽能被 Jackson 转成 null，但语义上是「非法值」；
        // 单选 cascader（emitPath:false）清空本就置 null，这里只是把三种形态统一收敛。
        if (payload.groupId === '' || (Array.isArray(payload.groupId) && payload.groupId.length === 0)) payload.groupId = null
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
/* T17 掩码态：路径无权查看时的锁定样式（与明文 mono 明显区分，但不刺眼） */
.mask-path {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  color: #b8860b;
  background: #fdf6ec;
  border: 1px dashed #f0c78a;
  border-radius: 4px;
  padding: 1px 6px;
  cursor: help;
}
/* 概览区里跟在主值后面的补充说明（如「默认后端地址」的兜底语义） */
.sub-text { font-size: 11px; color: #9aa7bf; margin-left: 6px; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }

/* ===================== 详情抽屉 · 概览区 ===================== */
/* 抽屉 body 的内边距由 global.scss 统一给（.el-drawer__body 默认无 padding，
   内容会贴住抽屉边缘），这里只负责概览区自身的排版。 */
.detail-body { display: flex; flex-direction: column; }

/* 概览头条：方法徽标 + 接口名/路径 + 启停状态 */
.hero-top { display: flex; align-items: flex-start; gap: 10px; padding-bottom: 12px; }
.hero-title-wrap { flex: 1; min-width: 0; }
.hero-title { font-size: 15px; font-weight: 600; line-height: 1.35; color: #17233d; }
.hero-path {
  display: inline-block; margin-top: 2px; line-height: 1.4;
  color: #5c6b8a; word-break: break-all;
}
/* 头部徽标比列表页略大，与标题同高；不参与 flex 拉伸 */
.hero-top .method { height: 22px; padding: 0 8px; margin: 1px 0 0; flex: none; }

/* 关键字段栅格：借用 el-descriptions 的表格对齐能力，压成「企业控制台」观感 */
.hero-meta { margin-bottom: 4px; }
::v-deep .hero-meta .el-descriptions__body { background: transparent; }
::v-deep .hero-meta .el-descriptions-item__label.is-bordered-label {
  background: #f2f6ff; color: #5c6b8a; font-weight: 500;
  white-space: nowrap; width: 96px;
}
::v-deep .hero-meta .is-bordered .el-descriptions-item__cell { border-color: #e8eefb; }
::v-deep .hero-meta .el-descriptions-item__content { color: #17233d; }

.detail-tabs { margin-top: 8px; }
</style>
