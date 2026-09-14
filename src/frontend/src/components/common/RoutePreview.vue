<template>
  <div class="route-preview">
    <div class="rp-grid">
      <div v-for="c in envCards" :key="c.code" class="rp-card">
        <div class="rp-head">
          <span class="rp-env">{{ c.label }}</span>
          <StatusTag
            v-if="c.configStatus !== undefined && c.configStatus !== null"
            entity="apiEnvConfig"
            :value="c.configStatus"
          />
        </div>
        <div class="rp-row">
          <span class="rp-k">服务前缀</span>
          <span class="rp-v rp-mono">{{ c.upstreamUrl || '—' }}</span>
        </div>
        <div class="rp-row">
          <span class="rp-k">完整地址</span>
          <span class="rp-v rp-mono">{{ fullUrl(c.upstreamUrl) }}</span>
        </div>
        <div class="rp-row">
          <span class="rp-k">当前版本</span>
          <span class="rp-v">{{ c.version || '—' }}</span>
        </div>
        <div class="rp-row">
          <span class="rp-k">灰度比例</span>
          <span class="rp-v">{{ c.grayRatio != null ? c.grayRatio + '%' : '—' }}</span>
        </div>
      </div>
    </div>

    <EmptyState
      v-if="showEmpty && !hasData"
      title="暂无网关路由配置"
      description="选择接口后展示 4 套环境的 upstreamUrl 与当前生效版本"
    />
  </div>
</template>

<script>
/**
 * RoutePreview —— 网关路由/版本预览构件（T05 §4）
 * ------------------------------------------------------------------
 * 给定 apiId（或显式 envs 数据），展示 4 套环境（dev/test/pre/prod）的
 * 服务前缀 + 完整后端地址 + 当前生效版本 + 灰度比例，按环境维度可视化路由。
 *
 * 🔴 语义口径（2026-09-14 更正）：
 *  - `api_env_config.upstream_url` 是**各环境的服务基地址/前缀**（原型值形如
 *    `http://order-svc.dev:8080`，**不含路径**），不是「网关入口地址」。
 *    网关入口是 `env.gateway_url`，属于另一张表、另一个概念，此处不涉及。
 *  - 接口的 URI（`api_interface.interface_path`，如 `/test`）**跨环境完全相同**，
 *    各环境只是前缀不同；完整后端地址 = 服务前缀 + URI。
 *    所以本构件对每个环境同时给出「前缀」与「拼好的完整地址」，避免只看到前缀时
 *    误以为接口在各环境路径不同。
 *
 * Props
 *  - apiId         Number|String  接口 ID；配合 autoLoad 时自动拉取环境配置
 *  - envs          Array           显式环境配置数据（优先于 apiId 拉取）：
 *                  [{ envCode, upstreamUrl, version, grayRatio, configStatus }]
 *  - interfacePath String          接口 URI（跨环境共用），用于拼「完整后端地址」；可空
 *  - autoLoad      Boolean         默认 false；true 时按 apiId 调 /api-env-config/list
 *  - showEmpty     Boolean         无数据时是否展示空态，默认 true
 *
 * 说明：Phase 0 默认不自动请求（避免后端缺失时噪音）；父组件传入 :envs 即可。
 *       后端就绪后设 auto-load 也可。数据缺失的环境显示「未配置」。
 */
import StatusTag from './StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { ENV_LIST } from '@/utils/enum'
import { getApiEnvConfigList } from '@/api/modules'

/**
 * 取一行环境数据的「环境编码」。
 *
 * 🔴 2026-09-14：这里原来只认 `envCode`，而唯一调用方 ApiEnvConfigTab 传进来的 `envs`
 * 已经是**归一化后**的形状（`{ code, label, upstreamUrl, version, grayRatio, configStatus }`），
 * 于是 `x.envCode === e.code` 恒为 false ⇒ 卡片上「服务前缀 / 当前版本」**永远显示 `—`**
 * （实测：接口详情 → 环境配置，库里明明有 dev 的配置，四张卡片全是破折号）。
 * 两种形状都接受，既修好现状，也不再挑剔未来调用方传哪一种。
 */
function envCodeOf(x) {
  return x ? (x.envCode || x.code) : undefined
}

export default {
  name: 'RoutePreview',
  components: { StatusTag, EmptyState },
  props: {
    apiId: { type: [Number, String], default: null },
    envs: { type: Array, default: null },
    /** 接口 URI（跨环境共用）——用于把「服务前缀」拼成完整后端地址 */
    interfacePath: { type: String, default: '' },
    autoLoad: { type: Boolean, default: false },
    showEmpty: { type: Boolean, default: true }
  },
  data() {
    return { fetched: [] }
  },
  computed: {
    source() {
      if (this.envs && this.envs.length) return this.envs
      return this.fetched
    },
    envCards() {
      const list = this.source || []
      return ENV_LIST.map((e) => {
        const d = list.find((x) => envCodeOf(x) === e.code) || {}
        return {
          code: e.code,
          label: e.label,
          upstreamUrl: d.upstreamUrl,
          version: d.version,
          grayRatio: d.grayRatio,
          configStatus: d.configStatus
        }
      })
    },
    hasData() {
      return this.envCards.some((c) => c.upstreamUrl)
    }
  },
  watch: {
    apiId: { immediate: false, handler() { if (this.autoLoad) this.load() } },
    envs: { immediate: false, handler() {} }
  },
  mounted() {
    if (this.autoLoad && this.apiId && !this.envs) this.load()
  },
  methods: {
    /**
     * 完整后端地址 = 服务前缀 + 接口 URI。
     * 前缀去掉尾部 `/`、URI 补上开头 `/`，避免出现 `http://x:8080//test` 这类双斜杠。
     */
    fullUrl(prefix) {
      const p = String(prefix || '').trim()
      if (!p) return '—'
      const base = p.replace(/\/+$/, '')
      const uri = String(this.interfacePath || '').trim()
      if (!uri) return base
      return base + (uri.charAt(0) === '/' ? uri : '/' + uri)
    },
    async load() {
      try {
        const res = await getApiEnvConfigList(this.apiId)
        // GET /api-env-config/list 返回 Result<List<ApiEnvConfigDto>>（裸数组，无 records/total 信封）。
        // 直接裸取 res.data（T09-A，见 docs/T08-ClassG-响应形状审计.md §6-①）
        this.fetched = (res && res.data) || []
      } catch (e) {
        this.fetched = []
      }
    }
  }
}
</script>

<style scoped>
.route-preview { width: 100%; }
.rp-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.rp-card {
  border: 1px solid #e3e8f2;
  border-radius: 10px;
  padding: 12px 14px;
  background: #fff;
}
.rp-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.rp-env { font-size: 13px; font-weight: 600; color: #17233d; }
.rp-row { display: flex; align-items: baseline; gap: 8px; font-size: 12px; margin-top: 6px; }
.rp-k { color: #9aa7bf; flex: none; width: 56px; }
.rp-v { color: #17233d; word-break: break-all; }
.rp-mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 11px; }
@media (max-width: 1100px) {
  .rp-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
