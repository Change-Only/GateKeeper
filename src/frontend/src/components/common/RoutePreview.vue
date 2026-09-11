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
          <span class="rp-k">网关入口</span>
          <span class="rp-v rp-mono">{{ c.upstreamUrl || '—' }}</span>
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
 * upstreamUrl + 当前生效版本 + 灰度比例，按环境维度可视化网关路由。
 *
 * Props
 *  - apiId      Number|String  接口 ID；配合 autoLoad 时自动拉取环境配置
 *  - envs       Array           显式环境配置数据（优先于 apiId 拉取）：
 *                [{ envCode, upstreamUrl, version, grayRatio, configStatus }]
 *  - autoLoad   Boolean         默认 false；true 时按 apiId 调 /api-env-config/list
 *  - showEmpty  Boolean         无数据时是否展示空态，默认 true
 *
 * 说明：Phase 0 默认不自动请求（避免后端缺失时噪音）；父组件传入 :envs 即可。
 *       后端就绪后设 auto-load 也可。数据缺失的环境显示「未配置」。
 */
import StatusTag from './StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import { ENV_LIST } from '@/utils/enum'
import { getApiEnvConfigList } from '@/api/modules'

export default {
  name: 'RoutePreview',
  components: { StatusTag, EmptyState },
  props: {
    apiId: { type: [Number, String], default: null },
    envs: { type: Array, default: null },
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
        const d = list.find((x) => x.envCode === e.code) || {}
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
    async load() {
      try {
        const res = await getApiEnvConfigList(this.apiId)
        this.fetched = (res.data && res.data.records) || res.data || []
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
