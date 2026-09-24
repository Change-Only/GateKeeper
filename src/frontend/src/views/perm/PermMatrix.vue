<template>
  <div class="page-container perm-matrix">
    <!-- page-head：标题 + 描述 + 右侧批量动作（对齐原型 page-head title/desc + slot=extra） -->
    <div class="page-head">
      <div class="ph-main">
        <h2>接口授权总览</h2>
        <p class="ph-desc">App × API 授权矩阵：批量治理与越权排查</p>
      </div>
      <div class="page-head-extra">
        <el-button size="small" icon="el-icon-download" @click="exportMatrix">导出矩阵</el-button>
        <PermButton perm="grant:create" type="primary" size="small" icon="el-icon-plus" @click="openBatch">批量授权</PermButton>
      </div>
    </div>

    <el-alert
      class="mb12"
      type="info"
      :closable="false"
      show-icon
      title="当安全同学需要回答「到底有哪些外部应用能调核心订单接口」时，这个页面能在 10 秒内给出答案。点击已有授权的单元格看详情，点击空白单元格可直接为该「应用 × 接口」发起授权。"
    />

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" size="small" @submit.native.prevent>
        <el-form-item label="环境">
          <el-select v-model="envCode" style="width: 130px">
            <el-option v-for="e in envOptions" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="仅看外部应用">
          <el-switch v-model="onlyExternal" />
        </el-form-item>
        <el-form-item label="视图">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button label="grid">矩阵</el-radio-button>
            <el-radio-button label="list">列表</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button icon="el-icon-refresh" @click="loadAll">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ===================== 矩阵视图 ===================== -->
    <el-card v-show="viewMode === 'grid'" shadow="never" class="matrix-card">
      <div v-loading="loading" class="matrix-wrap">
        <table class="matrix-table">
          <thead>
            <tr>
              <th class="matrix-corner">应用 \ 接口</th>
              <th v-for="api in apis" :key="api.id" class="matrix-col-head">
                <div class="matrix-api-name">{{ api.interfaceName }}</div>
                <div class="matrix-api-path">
                  <span class="method" :class="(api.requestMethod || 'get').toLowerCase()">{{ api.requestMethod }}</span>
                  <code class="matrix-api-uri">{{ api.interfacePath }}</code>
                </div>
              </th>
              <th v-if="!apis.length" class="matrix-col-head matrix-col-empty">暂无接口</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in matrixRows" :key="r.app.id">
              <td class="matrix-row-head">
                <div class="matrix-app-name">{{ r.app.appName }}</div>
                <div class="matrix-app-sub">
                  <el-tag size="mini" :type="appTypeTag(r.app.appType)">{{ appTypeText(r.app.appType) }}</el-tag>
                </div>
              </td>
              <td
                v-for="(g, i) in r.cells"
                :key="i"
                :class="cellClass(g)"
                class="matrix-cell"
                @click="onCell(r.app, apis[i], g)"
              >
                <span class="cell-mark">{{ cellText(g) }}</span>
                <span v-if="g && g.qpsLimit" class="cell-usage">QPS {{ g.qpsLimit }}</span>
              </td>
              <td v-if="!apis.length" class="matrix-cell cell-none"><span class="cell-mark">—</span></td>
            </tr>
            <tr v-if="!matrixRows.length">
              <td :colspan="Math.max(apis.length, 1) + 1" class="matrix-no-data">
                {{ apps.length ? '当前筛选下没有应用' : '暂无应用数据' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="matrix-legend">
        <span><i class="cell-legend cell-ok" /> 已生效</span>
        <span><i class="cell-legend cell-warn" /> 非生效状态（待审批 / 过期 / 撤销 / 驳回）</span>
        <span><i class="cell-legend cell-none" /> 未授权</span>
        <span class="legend-note">当前环境：{{ envLabel(envCode) }} · 共 {{ grants.length }} 条授权</span>
      </div>
    </el-card>

    <!-- ===================== 列表视图 ===================== -->
    <CrudTable
      v-if="viewMode === 'list'"
      ref="table"
      class="mt12"
      :columns="columns"
      :fetch="fetchData"
      :query="query"
      :show-pagination="false"
      :actions-width="240"
    >
      <template #toolbar>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
          <el-option v-for="(m, k) in grantStatusOptions" :key="k" :label="m.label" :value="Number(k)" />
        </el-select>
        <span class="spacer" />
        <span class="ct-tip">列表视图用于逐条审批 / 撤销 / 续期；矩阵视图用于批量治理与越权排查</span>
      </template>

      <template #appName="{ row }">
        <div class="cell-primary">{{ row.appName }}</div>
        <div class="sub-text"><el-tag size="mini" :type="appTypeTag(row.appType)">{{ appTypeText(row.appType) }}</el-tag></div>
      </template>
      <template #apiName="{ row }">
        <div class="cell-primary">{{ row.apiName }}</div>
        <div class="sub-text">
          <span class="method" :class="(row.apiMethod || 'get').toLowerCase()">{{ row.apiMethod || '—' }}</span>
          <code>{{ row.apiPath || '—' }}</code>
        </div>
      </template>
      <template #envCode="{ row }">
        <el-tag size="mini" effect="plain" :type="envTagType(row.envCode)">{{ row.envCode || '—' }}</el-tag>
      </template>
      <template #validTo="{ row }">
        <span>{{ row.validTo || '长期' }}</span>
      </template>
      <template #status="{ row }">
        <StatusTag entity="grant" :value="row.status" />
      </template>
      <template #actions="{ row }">
        <PermButton v-if="row.status === 0" perm="grant:approve" type="text" size="mini" @click="onApprove(row)">通过</PermButton>
        <PermButton v-if="row.status === 0" perm="grant:reject" type="text" size="mini" class="danger-link" @click="onReject(row)">驳回</PermButton>
        <PermButton v-if="row.status === 1" perm="grant:revoke" type="text" size="mini" class="danger-link" @click="onRevoke(row)">撤销</PermButton>
        <PermButton v-if="row.status === 1" perm="grant:approve" type="text" size="mini" @click="onRenew(row)">续期</PermButton>
      </template>
    </CrudTable>

    <!-- ===================== 新建授权（按 应用名称 / 接口类型 / 接口名称 选择） ===================== -->
    <el-dialog title="新建授权" :visible.sync="create.visible" width="640px" :close-on-click-modal="false">
      <el-alert
        class="mb12"
        type="info"
        :closable="false"
        show-icon
        title="按「应用名称 → 接口类型 → 接口名称」三级选择，提交时自动换算为 appId / apiId；新建授权默认进入「待审批」，审批流关闭时由后端直接置为「已生效」。"
      />
      <el-form ref="createForm" :model="create.form" :rules="create.rules" label-width="100px" size="small">
        <el-form-item label="应用名称" prop="appId">
          <el-select v-model="create.form.appId" filterable placeholder="请选择应用" style="width: 100%">
            <el-option v-for="a in apps" :key="a.id" :label="a.appName" :value="a.id">
              <span>{{ a.appName }}</span>
              <span class="opt-extra">{{ appTypeText(a.appType) }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="接口类型" prop="requestMethod">
          <el-select v-model="create.form.requestMethod" placeholder="请选择接口类型（HTTP 方法）" style="width: 100%" @change="onCreateMethodChange">
            <el-option v-for="m in methodOptions" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口名称" prop="apiId">
          <el-select
            v-model="create.form.apiId"
            filterable
            :disabled="!create.form.requestMethod"
            :placeholder="create.form.requestMethod ? '请选择接口' : '请先选择接口类型'"
            style="width: 100%"
          >
            <el-option v-for="a in createApiOptions" :key="a.id" :label="a.interfaceName" :value="a.id">
              <span>{{ a.interfaceName }}</span>
              <span class="opt-extra">{{ a.interfacePath }}</span>
            </el-option>
          </el-select>
          <div v-if="create.form.requestMethod && !createApiOptions.length" class="field-hint">
            该类型下没有接口，请先在「接口管理」登记接口
          </div>
        </el-form-item>
        <el-form-item label="环境" prop="envCode">
          <el-select v-model="create.form.envCode" placeholder="请选择环境" style="width: 100%">
            <el-option v-for="e in envOptions" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="QPS 上限">
          <el-input-number v-model="create.form.qpsLimit" :min="0" :step="1" controls-position="right" style="width: 200px" />
          <span class="field-hint">0 = 不限</span>
        </el-form-item>
        <el-form-item label="日配额">
          <el-input-number v-model="create.form.dailyQuota" :min="0" :step="100" controls-position="right" style="width: 200px" />
          <span class="field-hint">0 = 不限</span>
        </el-form-item>
        <el-form-item label="生效日期">
          <el-date-picker v-model="create.form.validFrom" type="date" value-format="yyyy-MM-dd" placeholder="留空=立即生效" style="width: 200px" />
        </el-form-item>
        <el-form-item label="失效日期">
          <el-date-picker v-model="create.form.validTo" type="date" value-format="yyyy-MM-dd" placeholder="留空=长期有效" style="width: 200px" />
        </el-form-item>
        <el-form-item label="申请理由">
          <el-input v-model="create.form.grantReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="说明申请该授权的业务背景" />
        </el-form-item>
      </el-form>
      <template slot="footer">
        <el-button @click="create.visible = false">取消</el-button>
        <el-button type="primary" :loading="create.loading" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- ===================== 批量授权 ===================== -->
    <el-dialog title="批量授权" :visible.sync="batch.visible" width="600px" :close-on-click-modal="false">
      <el-alert
        class="mb12"
        type="warning"
        :closable="false"
        show-icon
        title="批量授权一次只为「一个应用」授权「多个接口」。后端固定写入 prod 环境（不受上方环境筛选影响），因此这里不提供环境选择。"
      />
      <el-form ref="batchForm" :model="batch.form" :rules="batch.rules" label-width="100px" size="small">
        <el-form-item label="应用名称" prop="appId">
          <el-select v-model="batch.form.appId" filterable placeholder="请选择应用" style="width: 100%">
            <el-option v-for="a in apps" :key="a.id" :label="a.appName" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接口" prop="interfaceIds">
          <el-select v-model="batch.form.interfaceIds" multiple filterable collapse-tags placeholder="可多选接口" style="width: 100%">
            <el-option v-for="a in apis" :key="a.id" :label="a.interfaceName" :value="a.id">
              <span>{{ a.interfaceName }}</span>
              <span class="opt-extra">{{ a.requestMethod }} {{ a.interfacePath }}</span>
            </el-option>
          </el-select>
        </el-form-item>
      </el-form>
      <template slot="footer">
        <el-button @click="batch.visible = false">取消</el-button>
        <el-button type="primary" :loading="batch.loading" @click="submitBatch">提交</el-button>
      </template>
    </el-dialog>

    <!-- ===================== 单元格授权详情 ===================== -->
    <el-dialog title="授权详情" :visible.sync="detail.visible" width="560px">
      <el-descriptions v-if="detail.grant" :column="1" size="small" border>
        <el-descriptions-item label="应用">
          {{ detail.app ? detail.app.appName : '#' + detail.grant.appId }}（{{ appTypeText(detail.app && detail.app.appType) }}）
        </el-descriptions-item>
        <el-descriptions-item label="接口">
          <span class="method" :class="((detail.api && detail.api.requestMethod) || 'get').toLowerCase()">
            {{ (detail.api && detail.api.requestMethod) || '—' }}
          </span>
          <code>{{ (detail.api && detail.api.interfacePath) || '—' }}</code>
          {{ detail.api ? detail.api.interfaceName : '#' + detail.grant.apiId }}
        </el-descriptions-item>
        <el-descriptions-item label="环境">{{ envLabel(detail.grant.envCode) }}</el-descriptions-item>
        <el-descriptions-item label="状态"><StatusTag entity="grant" :value="detail.grant.status" /></el-descriptions-item>
        <el-descriptions-item label="QPS 上限">{{ detail.grant.qpsLimit || '不限' }}</el-descriptions-item>
        <el-descriptions-item label="日配额">{{ detail.grant.dailyQuota || '不限' }}</el-descriptions-item>
        <el-descriptions-item label="有效期至">{{ detail.grant.validTo || '长期' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ detail.grant.applicantName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请理由">{{ detail.grant.grantReason || '—' }}</el-descriptions-item>
        <el-descriptions-item label="审批意见">{{ detail.grant.auditRemark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="撤销原因">{{ detail.grant.revokeReason || '—' }}</el-descriptions-item>
      </el-descriptions>
      <template slot="footer">
        <span class="dialog-hint">逐条审批 / 撤销 / 续期请切到「列表」视图</span>
        <el-button type="primary" @click="detail.visible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 接口授权总览（T04-A 授权域 · perm-matrix）
 * ------------------------------------------------------------------
 * 🔴 2026-09-14 按原型 docs/prototype/api-platform-原型.html 的 page-perm-matrix 重建。
 *    原实现只有一张「审批列表」（列全是 appId / apiId 原始 ID），与原型结构完全不同：
 *    原型是「**矩阵 + 列表双视图**」，矩阵才是本页存在的理由 ——
 *    「到底有哪些外部应用能调核心订单接口」这类越权排查问题，看矩阵 10 秒能答，
 *    看列表要逐行比对。本次恢复：page-head(title+desc) / 导出矩阵 / 批量授权 /
 *    信息提示条 / 筛选卡（环境 · 仅看外部应用 · 视图切换）/ 矩阵表（corner+行列头+三态单元格+图例）/
 *    列表视图（列头改为应用名与接口名，不再是裸 ID）。
 *
 * 数据来源：
 *  - 应用：GET /app/list            → PageResult.records（App 实体，含 appType）
 *  - 接口：GET /interface/list      → PageResult.records（InterfaceListVo，含分组名/路径/方法）
 *  - 授权：GET /grant/list?envCode= → **裸数组** List<AppApiGrant>（无 records/total 信封）
 *  - 环境：GET /env/all             → 裸数组；仅用于决定「默认选中的环境」，
 *          列头 / 下拉仍用字面量 dev/test/pre/prod（未配置的环境也要能出现在矩阵里）
 *
 * 授权对象的契约：`app_api_grant` 是「应用 × 接口 × 环境」三元组，
 * 所以矩阵的行=应用、列=接口、单元格=该环境下的授权记录。
 */
import {
  getAppList,
  getInterfaceList,
  getEnvAll,
  getGrantList,
  createGrant,
  approveGrant,
  rejectGrant,
  revokeGrant,
  renewGrant,
  batchGrantPermission
} from '@/api/modules'
import { ENV_LIST, STATUS_MAP } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'

const ENV_OPTIONS = ENV_LIST.map((e) => ({ value: e.code, label: e.label }))
const ENV_TAG_TYPES = { dev: 'info', test: 'primary', pre: 'warning', prod: 'danger' }
const APP_TYPE_TEXT = { 1: '内部', 2: '外部', 3: '测试' }
const APP_TYPE_TAG = { 1: 'info', 2: 'warning', 3: 'success' }
/** 矩阵单元格文案：索引即 app_api_grant.status（0待审批/1已生效/2已过期/3已撤销/4已驳回） */
const CELL_TEXT = ['待审批', '✓', '已过期', '已撤销', '已驳回']

export default {
  name: 'PermMatrix',
  components: { StatusTag },
  data() {
    return {
      envOptions: ENV_OPTIONS,
      grantStatusOptions: STATUS_MAP.grant,
      envCode: 'dev',
      onlyExternal: false,
      viewMode: 'grid',
      loading: false,
      apps: [],
      apis: [],
      grants: [],
      query: { envCode: 'dev', status: undefined },
      columns: [
        { prop: 'appName', label: '应用', minWidth: 150, slot: 'appName' },
        { prop: 'apiName', label: '接口', minWidth: 190, slot: 'apiName' },
        { prop: 'groupName', label: '分组', width: 110, formatter: (v) => v || '—' },
        { prop: 'envCode', label: '环境', width: 80, slot: 'envCode' },
        { prop: 'qpsLimit', label: 'QPS', width: 70, align: 'right', formatter: (v) => (v ? v : '不限') },
        { prop: 'validTo', label: '有效期至', width: 110, slot: 'validTo' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'applicantName', label: '申请人', width: 90, formatter: (v) => v || '—' },
        { prop: 'grantReason', label: '授权理由', minWidth: 160, formatter: (v) => v || '—' }
      ],
      create: {
        visible: false,
        loading: false,
        form: {},
        rules: {
          appId: [{ required: true, message: '请选择应用名称', trigger: 'change' }],
          requestMethod: [{ required: true, message: '请选择接口类型', trigger: 'change' }],
          apiId: [{ required: true, message: '请选择接口名称', trigger: 'change' }],
          envCode: [{ required: true, message: '请选择环境', trigger: 'change' }]
        }
      },
      batch: {
        visible: false,
        loading: false,
        form: { appId: undefined, interfaceIds: [] },
        rules: {
          appId: [{ required: true, message: '请选择应用名称', trigger: 'change' }],
          interfaceIds: [{ required: true, type: 'array', min: 1, message: '请至少选择一个接口', trigger: 'change' }]
        }
      },
      detail: { visible: false, app: null, api: null, grant: null }
    }
  },
  computed: {
    /** 应用 id → 应用（列表视图补齐应用名用） */
    appMap() {
      const m = {}
      this.apps.forEach((a) => { m[a.id] = a })
      return m
    },
    /** 接口 id → 接口（列表视图补齐接口名/方法/路径/分组用） */
    apiMap() {
      const m = {}
      this.apis.forEach((a) => { m[a.id] = a })
      return m
    },
    /** 接口类型下拉项：取实际存在的 HTTP 方法，按常用顺序排 */
    methodOptions() {
      const order = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH']
      const set = []
      this.apis.forEach((a) => {
        const m = (a.requestMethod || '').toUpperCase()
        if (m && set.indexOf(m) < 0) set.push(m)
      })
      return set.sort((x, y) => {
        const ix = order.indexOf(x)
        const iy = order.indexOf(y)
        return (ix < 0 ? 99 : ix) - (iy < 0 ? 99 : iy)
      })
    },
    /** 新建授权弹窗：按已选「接口类型」过滤后的接口候选 */
    createApiOptions() {
      const m = (this.create.form.requestMethod || '').toUpperCase()
      if (!m) return []
      return this.apis.filter((a) => (a.requestMethod || '').toUpperCase() === m)
    },
    /** 当前环境下的授权索引：`appId-apiId` → grant */
    grantMap() {
      const m = {}
      this.grants.forEach((g) => { m[g.appId + '-' + g.apiId] = g })
      return m
    },
    /** 矩阵行：应用 × 接口 → 单元格 */
    matrixRows() {
      return this.apps
        .filter((a) => !this.onlyExternal || a.appType === 2)
        .map((a) => ({
          app: a,
          cells: this.apis.map((api) => this.grantMap[a.id + '-' + api.id] || null)
        }))
    }
  },
  watch: {
    // 环境变化同时影响矩阵与列表（列表侧还依赖 CrudTable 对 query 的深监听）
    envCode(v) {
      this.query.envCode = v
      this.loadGrants()
    }
  },
  created() {
    this.init()
  },
  methods: {
    // ============================ 数据加载 ============================
    async init() {
      await this.loadRefs()
      await this.resolveDefaultEnv()
      await this.loadGrants()
    },
    /** 应用 + 接口：矩阵的行与列，一次性拉全（矩阵必须要全量，故 size 取大） */
    async loadRefs() {
      this.loading = true
      try {
        const [appRes, apiRes] = await Promise.all([
          getAppList({ current: 1, size: 500 }),
          getInterfaceList({ current: 1, size: 500 })
        ])
        this.apps = (appRes && appRes.data && appRes.data.records) || []
        this.apis = (apiRes && apiRes.data && apiRes.data.records) || []
      } catch (e) {
        // 拦截器已提示；矩阵退化为空表而不是整页崩掉
        this.apps = []
        this.apis = []
      } finally {
        this.loading = false
      }
    },
    /**
     * 决定默认环境：优先「库里有配置的环境」，其次 prod，最后 dev。
     * 只用它定默认选中值，不参与列头生成 —— 未配置的环境照样要能出现在矩阵列里。
     */
    async resolveDefaultEnv() {
      try {
        const res = await getEnvAll()
        const codes = ((res && res.data) || []).map((e) => e.envCode).filter(Boolean)
        if (!codes.length) return
        if (codes.indexOf('prod') >= 0) this.envCode = 'prod'
        else this.envCode = codes[0]
        this.query.envCode = this.envCode
      } catch (e) { /* 保持字面量默认 dev */ }
    },
    async loadGrants() {
      try {
        const res = await getGrantList({ envCode: this.envCode })
        this.grants = (res && res.data) || []
      } catch (e) {
        this.grants = []
      }
    },
    loadAll() {
      this.loadRefs().then(() => this.loadGrants())
      if (this.$refs.table) this.$refs.table.reload()
    },
    // ============================ 展示工具 ============================
    envLabel(code) {
      const e = ENV_OPTIONS.find((x) => x.value === code)
      return e ? e.label : code
    },
    envTagType(code) {
      return ENV_TAG_TYPES[code] || 'info'
    },
    appTypeText(t) {
      return APP_TYPE_TEXT[t] || '未知'
    },
    appTypeTag(t) {
      return APP_TYPE_TAG[t] || 'info'
    },
    cellClass(g) {
      if (!g) return 'cell-none'
      return g.status === 1 ? 'cell-ok' : 'cell-warn'
    },
    cellText(g) {
      if (!g) return '—'
      return CELL_TEXT[g.status] || '—'
    },
    // ============================ 矩阵交互 ============================
    onCell(app, api, grant) {
      if (!api) return
      if (grant) {
        this.detail = { visible: true, app, api, grant }
      } else {
        this.openCreate(app, api)
      }
    },
    /** 导出矩阵为 CSV（带 UTF-8 BOM，Excel 直接可开） */
    exportMatrix() {
      const rows = this.matrixRows
      if (!rows.length || !this.apis.length) {
        this.$message.warning('没有可导出的矩阵数据')
        return
      }
      const head = ['应用 \\ 接口'].concat(this.apis.map((a) => `${a.requestMethod} ${a.interfacePath}`))
      const body = rows.map((r) => {
        return [r.app.appName].concat(
          r.cells.map((g) => (g ? (CELL_TEXT[g.status] || '') : '未授权'))
        )
      })
      const csv = [head].concat(body)
        .map((line) => line.map((c) => `"${String(c == null ? '' : c).replace(/"/g, '""')}"`).join(','))
        .join('\r\n')
      const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `接口授权矩阵_${this.envCode}_${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
      this.$message.success(`矩阵已导出（${rows.length} 个应用 × ${this.apis.length} 个接口）`)
    },
    // ============================ 新建授权（Bug 1） ============================
    /**
     * 打开新建授权。传入 app/api 时预填（从矩阵空白单元格进入）；
     * 否则清空，由用户按「应用名称 → 接口类型 → 接口名称」三级选择。
     *
     * 🔴 这里刻意不用通用 CrudDialog：本表单需要**级联**（选了接口类型要过滤接口名称、
     *    并把已选的接口清空），而 CrudDialog 内部持有 form 的深拷贝、字段定义在打开时一次性生成，
     *    父组件既拿不到用户的选择、也改不了子组件的 form。硬用会把「接口类型」做成假筛选。
     */
    openCreate(app, api) {
      const method = api ? (api.requestMethod || '').toUpperCase() : ''
      this.create.form = {
        appId: app ? app.id : undefined,
        requestMethod: method || undefined,
        apiId: api ? api.id : undefined,
        envCode: this.envCode,
        qpsLimit: 0,
        dailyQuota: 0,
        validFrom: undefined,
        validTo: undefined,
        grantReason: ''
      }
      this.create.visible = true
      this.$nextTick(() => {
        if (this.$refs.createForm) this.$refs.createForm.clearValidate()
      })
    },
    /** 切换接口类型：接口名称必须重选（否则会留下与类型不符的接口） */
    onCreateMethodChange() {
      this.create.form.apiId = undefined
    },
    /** 空串一律转 null，避免 Jackson 解析空字符串成 LocalDate 报错 */
    orNull(v) {
      return v === '' || v === undefined ? undefined : v
    },
    async submitCreate() {
      this.$refs.createForm.validate(async (valid) => {
        if (!valid) return
        this.create.loading = true
        try {
          const f = this.create.form
          await createGrant({
            appId: f.appId,
            apiId: f.apiId,
            envCode: f.envCode,
            qpsLimit: Number(f.qpsLimit) || 0,
            dailyQuota: Number(f.dailyQuota) || 0,
            validFrom: this.orNull(f.validFrom),
            validTo: this.orNull(f.validTo),
            grantReason: f.grantReason || ''
          })
          this.$message.success('授权已提交（待审批）')
          this.create.visible = false
          this.loadGrants()
          if (this.$refs.table) this.$refs.table.reload()
        } catch (e) {
          // 拦截器已弹错
        } finally {
          this.create.loading = false
        }
      })
    },
    // ============================ 批量授权 ============================
    openBatch() {
      this.batch.form = { appId: undefined, interfaceIds: [] }
      this.batch.visible = true
      this.$nextTick(() => {
        if (this.$refs.batchForm) this.$refs.batchForm.clearValidate()
      })
    },
    async submitBatch() {
      this.$refs.batchForm.validate(async (valid) => {
        if (!valid) return
        this.batch.loading = true
        try {
          await batchGrantPermission({
            appId: this.batch.form.appId,
            interfaceIds: this.batch.form.interfaceIds
          })
          this.$message.success(`已为选中的 ${this.batch.form.interfaceIds.length} 个接口批量发起授权`)
          this.batch.visible = false
          this.loadGrants()
          if (this.$refs.table) this.$refs.table.reload()
        } catch (e) {
          // 拦截器已弹错
        } finally {
          this.batch.loading = false
        }
      })
    },
    // ============================ 列表数据 ============================
    /** 把 app_api_grant 的裸 ID 行补齐成可读行；悬空引用（应用/接口已删）显式标注 */
    enrich(g) {
      const app = this.appMap[g.appId]
      const api = this.apiMap[g.apiId]
      return {
        ...g,
        appName: app ? app.appName : `#${g.appId}（应用已删除）`,
        appType: app ? app.appType : null,
        apiName: api ? api.interfaceName : `#${g.apiId}（接口已删除）`,
        apiMethod: api ? api.requestMethod : '',
        apiPath: api ? api.interfacePath : '',
        groupName: api ? api.groupName : ''
      }
    },
    async fetchData(params) {
      const { envCode, status } = params
      const res = await getGrantList({ envCode, status })
      const list = ((res && res.data) || []).map((g) => this.enrich(g))
      return { list, total: list.length }
    },
    // ============================ 审批动作 ============================
    onApprove(row) {
      this.$prompt(`审批通过授权 #${row.id}，可填写审批意见`, '审批通过（高危）', {
        inputType: 'textarea'
      }).then(async ({ value }) => {
        try {
          await approveGrant(row.id, { auditRemark: value, auditorName: '' })
          this.$message.success('已通过')
          this.afterMutation()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    onReject(row) {
      this.$prompt(`驳回授权 #${row.id}，请填写驳回意见`, '审批驳回（高危）', {
        inputType: 'textarea',
        inputValidator: (v) => (v ? true : '驳回意见必填')
      }).then(async ({ value }) => {
        try {
          await rejectGrant(row.id, { auditRemark: value })
          this.$message.success('已驳回')
          this.afterMutation()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    onRevoke(row) {
      this.$prompt(`撤销授权 #${row.id}，请填写撤销原因`, '撤销授权（高危）', {
        inputType: 'textarea',
        inputValidator: (v) => (v ? true : '撤销原因必填')
      }).then(async ({ value }) => {
        try {
          await revokeGrant(row.id, { revokeReason: value })
          this.$message.success('已撤销')
          this.afterMutation()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    onRenew(row) {
      this.$prompt(`为授权 #${row.id} 设置新的失效日期（yyyy-MM-dd）`, '授权续期', {
        inputPlaceholder: 'yyyy-MM-dd',
        inputValidator: (v) => (/^\d{4}-\d{2}-\d{2}$/.test(v) ? true : '日期格式应为 yyyy-MM-dd')
      }).then(async ({ value }) => {
        try {
          await renewGrant(row.id, { validTo: value })
          this.$message.success('已续期')
          this.afterMutation()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    /** 审批/撤销/续期后：矩阵与列表都要刷新，否则两边状态不一致 */
    afterMutation() {
      this.loadGrants()
      if (this.$refs.table) this.$refs.table.reload()
    }
  }
}
</script>

<style scoped>
/* ==================== page-head（对齐原型 §page-head） ==================== */
.perm-matrix .page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.perm-matrix .page-head h2 {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 700;
  color: #17233d;
  letter-spacing: -0.01em;
}
.perm-matrix .ph-desc {
  margin: 0;
  font-size: 13px;
  color: #5c6b8a;
  max-width: 640px;
}
.perm-matrix .page-head-extra { display: flex; gap: 8px; flex: none; }

.mb12 { margin-bottom: 12px; }
.mt12 { margin-top: 12px; }
/* CrudTable 的 toolbar 是插槽内容（带本组件作用域），CrudTable 自身的 .ct-toolbar .spacer
   规则不会命中它，所以这里自己补一条，否则工具栏右侧的提示文案不会贴右。 */
.spacer { flex: 1; }
.perm-matrix .filter-card { margin-bottom: 12px; }
.perm-matrix .matrix-card { margin-top: 12px; }

/* ==================== 矩阵（对齐原型 §授权矩阵） ==================== */
.matrix-wrap {
  overflow-x: auto;
  border-radius: 10px;
  border: 1px solid #e3e8f2;
}
.matrix-table {
  border-collapse: separate;
  border-spacing: 0;
  width: 100%;
  min-width: 900px;
  font-size: 12px;
}
.matrix-table th,
.matrix-table td {
  border-right: 1px solid #e9edf5;
  border-bottom: 1px solid #e9edf5;
  padding: 0;
}
.matrix-table tr:last-child td { border-bottom: none; }
.matrix-table th:last-child,
.matrix-table td:last-child { border-right: none; }

.matrix-corner {
  background: #f7f9fc;
  color: #5c6b8a;
  font-weight: 600;
  padding: 10px 12px !important;
  text-align: left;
  min-width: 190px;
  position: sticky;
  left: 0;
  z-index: 3;
}
.matrix-col-head {
  background: #f7f9fc;
  padding: 10px 8px !important;
  text-align: center;
  min-width: 104px;
  vertical-align: top;
}
.matrix-col-empty { color: #9aa7bf; font-weight: 400; }
.matrix-api-name { font-size: 12px; color: #17233d; font-weight: 500; }
.matrix-api-path { margin-top: 4px; display: flex; align-items: center; justify-content: center; gap: 4px; flex-wrap: wrap; }
.matrix-api-uri { font-size: 11px; color: #5c6b8a; }

.matrix-row-head {
  background: #fff;
  padding: 10px 12px !important;
  min-width: 190px;
  position: sticky;
  left: 0;
  z-index: 1;
  border-right: 2px solid #d3dbe8 !important;
}
.matrix-app-name { font-size: 12px; color: #17233d; font-weight: 500; }
.matrix-app-sub { margin-top: 4px; font-size: 11px; color: #5c6b8a; }

.matrix-cell {
  text-align: center;
  cursor: pointer;
  transition: background 0.15s;
  padding: 10px 6px !important;
}
.matrix-cell:hover { background: #eaf1ff !important; }
.cell-ok { background: #e2f6ee; }
.cell-warn { background: #fdf1dd; }
.cell-none { background: #f7f9fc; }
.cell-mark { font-size: 13px; font-weight: 600; color: #0e7a5f; display: block; }
.cell-warn .cell-mark { color: #96611a; }
.cell-none .cell-mark { color: #9aa7bf; }
.cell-usage { font-size: 10px; color: #5c6b8a; margin-top: 2px; display: block; }
.matrix-no-data { padding: 28px 12px !important; text-align: center; color: #9aa7bf; font-size: 13px; }

.matrix-legend {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 14px;
  font-size: 12px;
  color: #5c6b8a;
  flex-wrap: wrap;
}
.cell-legend {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  margin-right: 6px;
  vertical-align: -2px;
  border: 1px solid #d3dbe8;
}
.legend-note { color: #9aa7bf; margin-left: auto; }

/* ==================== 列表视图补充样式 ==================== */
.cell-primary { color: #17233d; font-weight: 500; }
.sub-text { font-size: 11px; color: #9aa7bf; margin-top: 2px; display: flex; align-items: center; gap: 4px; }
.ct-tip { font-size: 12px; color: #9aa7bf; }
.opt-extra { float: right; color: #9aa7bf; font-size: 12px; margin-left: 12px; }
.field-hint { font-size: 12px; color: #9aa7bf; margin-left: 8px; }
.dialog-hint { font-size: 12px; color: #9aa7bf; margin-right: 12px; }
</style>
