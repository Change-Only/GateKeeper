<template>
  <div class="api-tab">
    <!-- 共享 URI 条：先把「各环境一致的东西」说清楚，再看各环境差异 -->
    <div class="uri-strip">
      <div class="uri-main">
        <span class="uri-label">接口 URI（各环境共用）</span>
        <span v-if="requestMethod" class="method" :class="String(requestMethod).toLowerCase()">{{ requestMethod }}</span>
        <code class="uri-code">{{ interfacePath || '—' }}</code>
      </div>
      <div class="uri-note">
        dev / test / pre / prod 的 <b>URI 完全相同</b>，只有「服务前缀」不同；完整后端地址 = 服务前缀 + URI
      </div>
    </div>

    <!-- 维护入口指引：本 Tab 是**只读生效预览**，编辑能力已按 T13 迁到分组侧 -->
    <el-alert type="info" :closable="false" show-icon class="ro-tip">
      <template #title>
        <b>本页只读</b>：T13 起环境配置的维护入口统一在「<b>接口管理 → 接口分组 → 行操作「环境配置」</b>」，
        按分组维护并按分组树<b>向上继承</b>。这里展示本接口在 4 个环境下的<b>生效结果</b>与来源，
        与网关实际转发用的是同一套解析逻辑。
        <span v-if="!groupId" class="ro-warn">（本接口未设置所属分组，无法继承任何环境配置）</span>
      </template>
    </el-alert>

    <!-- 存量接口级配置提醒：老表 api_env_config 优先级**高于**分组继承，若存在会盖掉分组配置 -->
    <el-alert v-if="legacyRows.length" type="warning" :closable="false" show-icon class="ro-tip">
      <template #title>
        检测到 <b>{{ legacyRows.length }}</b> 条<b>接口级</b>环境配置（{{ legacyEnvLabels }}）。
        接口级配置的优先级高于分组继承 —— 这会让对应环境<b>忽略分组侧配置</b>。
        若已无特殊需要，建议清除这些历史行，统一改为分组侧维护。
      </template>
    </el-alert>

    <RoutePreview :envs="envCards" :interface-path="interfacePath" />

    <el-table v-loading="loading" :data="rows" size="small" border class="eff-table">
      <el-table-column label="环境" width="120">
        <template #default="{ row }">
          <span class="eff-env">{{ row.envLabel }}</span>
          <StatusTag v-if="row.configStatus != null" entity="apiEnvConfig" :value="row.configStatus" />
        </template>
      </el-table-column>

      <el-table-column label="生效来源" min-width="220">
        <template #default="{ row }">
          <el-tag v-if="row.source === 'INTERFACE'" type="danger" size="mini">接口级覆盖</el-tag>
          <el-tag v-else-if="row.source === 'GROUP'" :type="row.isOwnGroup ? 'success' : 'warning'" size="mini">
            {{ row.isOwnGroup ? '本分组维护' : '继承自父级' }}
          </el-tag>
          <el-tag v-else type="info" size="mini">未配置</el-tag>
          <div v-if="row.sourcePath" class="eff-src">{{ row.sourcePath }}</div>
        </template>
      </el-table-column>

      <el-table-column label="超时 / 重试" min-width="190">
        <template #default="{ row }">
          <div class="eff-kv"><span class="eff-k">连接</span><span class="eff-v">{{ fmtNum(row.connectTimeout) }}</span></div>
          <div class="eff-kv"><span class="eff-k">读取</span><span class="eff-v">{{ fmtNum(row.readTimeout) }}</span></div>
          <div class="eff-kv"><span class="eff-k">重试</span><span class="eff-v">{{ fmtNum(row.retryCount) }}</span></div>
        </template>
      </el-table-column>

      <el-table-column label="Mock" width="120">
        <template #default="{ row }">
          <el-tag :type="row.mockEnabled === 1 ? 'warning' : 'info'" size="mini">
            {{ row.mockEnabled === 1 ? '开启' : '关闭' }}
          </el-tag>
          <div v-if="row.mockEnabled === 1" class="eff-src">HTTP {{ row.mockStatus || 200 }}</div>
        </template>
      </el-table-column>

      <el-table-column label="说明" min-width="260">
        <template #default="{ row }">
          <span class="eff-note">{{ row.note }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script>
/**
 * 接口环境配置 Tab（T05 Phase 1 创建 · **T13 改为只读生效预览**）
 * ------------------------------------------------------------------
 * 用户需求（2026-09-14 第 4 条）：「接口环境配置可以不用在接口侧，改为在接口分组侧，
 * 这样维护的地方比较明确，而且环境配置可以继承父级数据」。
 * 于是本 Tab 的定位从「逐环境增删改」变成「只看不改的生效预览」：
 *   ① 展示 4 个环境的**生效配置**（服务前缀 / 超时 / 重试 / Mock）；
 *   ② 明确标出每一行的**来源**：接口级覆盖（存量，优先级最高）/ 本分组维护 / 继承自父级 / 未配置；
 *   ③ 给出维护入口指引（接口分组 → 行操作「环境配置」）。
 *
 * 🔴 为什么必须显示「来源」：环境配置的解析有优先级
 *     接口级 api_env_config  >  分组继承 api_group_env_config（沿 parent_id 向上）  >  api_interface.backend_url 兜底。
 *    若不标来源，用户会在分组页改了配置却看到网关没变（其实是被存量接口级配置盖住了），
 *    这是最难排查的一类"改了不生效"。
 *
 * 🔴 与 Bug 1 的关系（2026-09-14 用户反馈「连接超时/读取超时/重试次数看不到输入的值」）：
 *    老版本本 Tab 有 10 列 + fixed="right" 操作列，容器放不下时中间的数字列被固定列挤出可视区，
 *    看上去像"值没存进去"。现在本页只读、不再有被挤走的输入框；
 *    分组侧的维护表也已刻意控制列数并**不使用固定列**（见 GroupEnvConfigPanel.vue 注释）。
 *
 * 数据来源：
 *   GET /api-env-config/list?apiId=           接口级覆盖（存量，可为空）
 *   GET /api-group-env-config/effective?groupId=  分组生效配置（含继承来源）
 * 两者按「接口级优先」合并，与后端 EnvConfigResolver 的口径一致。
 */
import { getApiEnvConfigList, getGroupEnvConfigEffective } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'
import RoutePreview from '@/components/common/RoutePreview.vue'

export default {
  name: 'ApiEnvConfigTab',
  components: { StatusTag, RoutePreview },
  props: {
    apiId: { type: [Number, String], default: null },
    /** 接口所属分组 ID（继承链起点）；未分组时为 null */
    groupId: { type: [Number, String], default: null },
    /** 接口 URI（跨环境共用），如 `/test`；由父级详情抽屉透传 */
    interfacePath: { type: String, default: '' },
    /** 接口请求方法，仅用于展示徽标；由父级详情抽屉透传 */
    requestMethod: { type: String, default: '' }
  },
  data() {
    return {
      loading: false,
      /** 接口级覆盖（老表，优先级最高） */
      legacyRows: [],
      /** 分组侧 4 个环境的生效配置 */
      groupEffective: []
    }
  },
  computed: {
    /** 归一化分组 ID，避免 JSON 数字与 prop 字符串比较时恒 false */
    gid() {
      const n = Number(this.groupId)
      return Number.isNaN(n) ? null : n
    },
    legacyEnvLabels() {
      return this.legacyRows.map((r) => this.envLabelOf(r.envCode)).join('、')
    },
    rows() {
      return ENV_LIST.map((e) => {
        const legacy = this.legacyRows.find((x) => x.envCode === e.code)
        const geo = this.groupEffective.find((x) => x.envCode === e.code) || {}
        // 优先级：接口级覆盖 > 分组继承 > 兜底
        if (legacy) {
          return {
            envCode: e.code,
            envLabel: e.label,
            source: 'INTERFACE',
            sourcePath: '接口级环境配置（api_env_config）·优先级高于分组继承',
            isOwnGroup: false,
            upstreamUrl: legacy.upstreamUrl,
            connectTimeout: legacy.connectTimeout,
            readTimeout: legacy.readTimeout,
            retryCount: legacy.retryCount,
            mockEnabled: legacy.mockEnabled,
            mockStatus: null,
            configStatus: legacy.configStatus,
            note: '该环境走接口级配置，分组侧配置对它无效。'
          }
        }
        const isGroup = geo.sourceType === 'GROUP'
        const srcGid = Number(geo.sourceGroupId)
        const isOwnGroup = isGroup && !Number.isNaN(srcGid) && srcGid === this.gid
        return {
          envCode: e.code,
          envLabel: e.label,
          source: isGroup ? 'GROUP' : 'DEFAULT',
          sourcePath: isGroup ? (geo.sourcePath || geo.sourceGroupName) : '',
          isOwnGroup,
          upstreamUrl: geo.upstreamUrl,
          connectTimeout: geo.connectTimeout,
          readTimeout: geo.readTimeout,
          retryCount: geo.retryCount,
          mockEnabled: geo.mockEnabled,
          mockStatus: geo.mockStatus,
          configStatus: geo.configStatus,
          note: isGroup
            ? (isOwnGroup ? '来自本分组的环境配置。' : '本分组未配置，继承最近祖先分组的环境配置。')
            : (this.gid ? '本分组及其父级都未配置该环境，转发回退到接口自身的「默认后端地址」。'
                        : '接口未归入分组，无法继承环境配置，转发回退到接口自身的「默认后端地址」。')
        }
      })
    },
    /** 给 RoutePreview 的 4 张卡片喂「生效后的前缀」，保证卡片与表格口径一致 */
    envCards() {
      return this.rows.map((r) => ({
        code: r.envCode,
        label: r.envLabel,
        upstreamUrl: r.upstreamUrl,
        version: null,
        grayRatio: null,
        configStatus: r.configStatus
      }))
    }
  },
  watch: {
    apiId() { this.load() },
    groupId() { this.load() }
  },
  mounted() {
    this.load()
  },
  methods: {
    envLabelOf(code) {
      const e = ENV_LIST.find((x) => x.code === code)
      return e ? e.label : code
    },
    fmtNum(v) {
      return v === null || v === undefined ? '—' : v
    },
    async load() {
      this.loading = true
      try {
        await this.loadAll()
      } finally {
        this.loading = false
      }
    },
    async loadAll() {
      // 接口级覆盖（存量）：失败不影响分组侧展示
      try {
        const res = await getApiEnvConfigList(this.apiId)
        this.legacyRows = (res && res.data) || []
      } catch (e) {
        this.legacyRows = []
      }
      // 分组生效配置：未分组时不请求（后端 groupId 必填，会 400）
      if (this.gid == null) {
        this.groupEffective = []
        return
      }
      try {
        const res = await getGroupEnvConfigEffective(this.gid)
        this.groupEffective = (res && res.data) || []
      } catch (e) {
        this.groupEffective = []
      }
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }

/* 共享 URI 条：各环境一致的部分 */
.uri-strip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  border: 1px solid #e3e8f2;
  border-radius: 10px;
  background: #f7f9fc;
  padding: 10px 14px;
  margin-bottom: 12px;
}
.uri-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.uri-label { font-size: 12px; color: #5c6b8a; }
.uri-code { font-size: 13px; color: #17233d; font-weight: 600; }
.uri-note { font-size: 12px; color: #9aa7bf; }
.uri-note b { color: #5c6b8a; }

.ro-tip { margin-bottom: 10px; }
.ro-warn { color: #c03337; }

.eff-table { margin-top: 12px; width: 100%; }
.eff-env { font-size: 13px; font-weight: 500; color: #17233d; margin-right: 6px; }
.eff-src { font-size: 11px; color: #5c6b8a; margin-top: 2px; word-break: break-all; }
.eff-kv { display: flex; gap: 6px; font-size: 12px; line-height: 18px; }
.eff-k { color: #9aa7bf; flex: none; width: 32px; }
.eff-v { color: #17233d; }
.eff-note { font-size: 12px; color: #5c6b8a; line-height: 1.5; }
</style>
