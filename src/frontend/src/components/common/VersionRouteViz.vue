<template>
  <div class="version-route-viz">
    <div v-if="apiName" class="vv-title">接口：<b>{{ apiName }}</b></div>

    <!-- 版本列表 -->
    <div class="vv-versions">
      <div
        v-for="v in versions"
        :key="v.version"
        class="vv-item"
        :class="{ 'is-current': v.isCurrent }"
      >
        <span class="vv-v">{{ v.version }}</span>
        <StatusTag entity="apiVersion" :value="v.status" />
        <span v-if="v.isCurrent" class="vv-cur">当前</span>
        <span v-if="v.grayRatio != null && v.grayRatio > 0" class="vv-gray">灰度 {{ v.grayRatio }}%</span>
      </div>
      <div v-if="!versions || !versions.length" class="vv-empty">暂无版本数据</div>
    </div>

    <!-- 灰度/版本路由可视化 -->
    <div v-if="grayRatio > 0" class="vv-bar-wrap">
      <div class="vv-bar">
        <div class="vv-seg stable" :style="{ width: stableRatio + '%' }">
          <span v-if="stableRatio > 12">稳定 {{ stableRatio }}%</span>
        </div>
        <div class="vv-seg gray" :style="{ width: grayRatio + '%' }">
          <span v-if="grayRatio > 12">灰度 {{ grayRatio }}%</span>
        </div>
      </div>
      <div class="vv-note">
        按请求头 <code>X-GK-Version</code> + murmur 哈希对流量分流：默认命中稳定版本，命中灰度名单 / 比例阈值的请求路由至灰度版本。
      </div>
    </div>
  </div>
</template>

<script>
/**
 * VersionRouteViz —— 灰度/版本路由可视化构件（T05 §4）
 * ------------------------------------------------------------------
 * 直观呈现接口多版本与灰度分流比例（murmur 哈希分流示意 + grayRatio 比例条 +
 * X-GK-Version 说明）。供 api-list 版本 Tab、sys-env 网关预览复用。
 *
 * Props
 *  - versions      Array   版本列表：[{ version, status, isCurrent, grayRatio }]
 *  - apiName       String  接口名称（可选，仅展示用）
 *  - currentVersion Object 当前版本显式对象 { grayRatio }（可选，优先于 versions 计算）
 */
import StatusTag from './StatusTag.vue'

export default {
  name: 'VersionRouteViz',
  components: { StatusTag },
  props: {
    versions: { type: Array, default: () => [] },
    apiName: { type: String, default: '' },
    currentVersion: { type: Object, default: null }
  },
  computed: {
    current() {
      if (this.currentVersion) return this.currentVersion
      return (this.versions || []).find((v) => v.isCurrent) || null
    },
    grayRatio() {
      if (!this.current) return 0
      const g = this.current.grayRatio
      return g != null ? Number(g) : 0
    },
    stableRatio() {
      return Math.max(0, 100 - this.grayRatio)
    }
  }
}
</script>

<style scoped>
.version-route-viz { width: 100%; }
.vv-title { font-size: 13px; color: #5c6b8a; margin-bottom: 10px; }
.vv-title b { color: #17233d; }
.vv-versions { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 14px; }
.vv-item {
  display: flex; align-items: center; gap: 8px;
  border: 1px solid #e3e8f2; border-radius: 8px; padding: 6px 12px; background: #fff;
  font-size: 12px;
}
.vv-item.is-current { border-color: #2563eb; background: #eaf1ff; }
.vv-v { font-weight: 600; color: #17233d; }
.vv-cur { color: #2563eb; font-weight: 600; }
.vv-gray { color: #96611a; }
.vv-empty { color: #9aa7bf; font-size: 12px; padding: 6px 0; }
.vv-bar-wrap { margin-top: 4px; }
.vv-bar {
  display: flex; height: 26px; border-radius: 8px; overflow: hidden;
  border: 1px solid #e3e8f2;
}
.vv-seg { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 12px; transition: width .3s; }
.vv-seg.stable { background: #2053c4; }
.vv-seg.gray { background: #f59e0b; }
.vv-note { font-size: 12px; color: #9aa7bf; margin-top: 8px; line-height: 1.6; }
.vv-note code { background: #f0f3f9; padding: 1px 6px; border-radius: 4px; color: #17233d; }
</style>
