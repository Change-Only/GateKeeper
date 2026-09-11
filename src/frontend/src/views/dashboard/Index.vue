<template>
  <div class="page-container" v-loading="loading">
    <!-- 顶部 KPI 指标卡行：今日总调用 / 成功率 / 限流次数 / 安全事件 -->
    <el-row :gutter="16" class="kpi-row">
      <el-col :span="6"><el-card class="kpi-card" shadow="never">
        <div class="kpi-ico blue">📊</div>
        <div class="kpi-main"><div class="kpi-label">今日总调用</div><div class="kpi-val">{{ overview.totalCalls || 0 }}<span class="unit">次</span></div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card class="kpi-card" shadow="never">
        <div class="kpi-ico green">✅</div>
        <div class="kpi-main"><div class="kpi-label">调用成功率</div><div class="kpi-val">{{ overview.successRate || 100 }}<span class="unit">%</span></div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card class="kpi-card" shadow="never">
        <div class="kpi-ico orange">⏱</div>
        <div class="kpi-main"><div class="kpi-label">限流次数</div><div class="kpi-val" style="color:#96611a">{{ overview.rateLimitedCount || 0 }}<span class="unit">次</span></div></div>
      </el-card></el-col>
      <el-col :span="6"><el-card class="kpi-card" shadow="never">
        <div class="kpi-ico red">⚠️</div>
        <div class="kpi-main"><div class="kpi-label">安全事件</div><div class="kpi-val" style="color:#c03337">{{ overview.securityEventCount || 0 }}<span class="unit">起</span></div></div>
      </el-card></el-col>
    </el-row>

    <!-- 调用量趋势折线图（24 小时） -->
    <el-card shadow="never" style="margin-top:16px">
      <div slot="header" class="card-head">调用量趋势（24小时）<span class="sub">单位：次 · 每 30 秒自动刷新</span></div>
      <div ref="trendChart" style="height:300px"></div>
    </el-card>

    <!-- 应用调用量 Top10 与接口热度 Top10 条形图 -->
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="12"><el-card shadow="never"><div slot="header" class="card-head">应用调用量 Top10</div><div ref="appRankChart" style="height:300px"></div></el-card></el-col>
      <el-col :span="12"><el-card shadow="never"><div slot="header" class="card-head">接口热度 Top10</div><div ref="ifaceRankChart" style="height:300px"></div></el-card></el-col>
    </el-row>
  </div>
</template>
<script>
import { getScreenOverview, getScreenTrend, getAppRank, getInterfaceRank } from '@/api/modules'
// echarts 按需引入（仅图表所需组件），避免打包全量 echarts（约 1MB）拖慢页面加载
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, CanvasRenderer])
export default {
  data() {
    return {
      // 总览指标数据
      overview: {},
      // 首次加载状态
      loading: false,
      // 定时刷新定时器
      timer: null
    }
  },
  mounted() {
    // 挂载后加载数据，并每 30 秒自动刷新一次
    this.loadAll(); this.timer = setInterval(this.loadAll, 30000)
  },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    // 并行加载所有图表数据（首次加载显示 loading）
    async loadAll() {
      this.loading = true
      try {
        await Promise.all([this.loadOverview(), this.loadTrend(), this.loadAppRank(), this.loadIfaceRank()])
      } finally { this.loading = false }
    },
    // 加载总览指标
    async loadOverview() { const res = await getScreenOverview(); this.overview = res.data },
    // 渲染 24 小时调用量趋势折线图（V2 品牌蓝）
    async loadTrend() { const res = await getScreenTrend(); const chart = echarts.init(this.$refs.trendChart); chart.setOption({ tooltip: { trigger: 'axis' }, xAxis: { type: 'category', data: res.data.map(d => d.hour), axisLine: { lineStyle: { color: '#d3dbe8' } }, axisLabel: { color: '#5c6b8a' } }, yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } }, series: [{ name: '调用量', type: 'line', smooth: true, symbolSize: 6, areaStyle: { opacity: .18, color: '#2563eb' }, lineStyle: { width: 2.5, color: '#2563eb' }, itemStyle: { color: '#2563eb' }, data: res.data.map(d => d.count) }] }) },
    // 渲染应用调用量 Top10 横向条形图（V2 品牌蓝渐变）
    async loadAppRank() { const res = await getAppRank(); const chart = echarts.init(this.$refs.appRankChart); chart.setOption({ tooltip: {}, grid: { left: '20%', right: '6%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } }, yAxis: { type: 'category', data: res.data.map(d => d.appName), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#17233d' } }, series: [{ type: 'bar', barWidth: 14, itemStyle: { color: '#2563eb', borderRadius: [0, 7, 7, 0] }, data: res.data.map(d => d.callCount) }] }) },
    // 渲染接口热度 Top10 横向条形图（V2 青色）
    async loadIfaceRank() { const res = await getInterfaceRank(); const chart = echarts.init(this.$refs.ifaceRankChart); chart.setOption({ tooltip: {}, grid: { left: '35%', right: '6%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } }, yAxis: { type: 'category', data: res.data.map(d => d.interfacePath), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#17233d' } }, series: [{ type: 'bar', barWidth: 14, itemStyle: { color: '#22d3ee', borderRadius: [0, 7, 7, 0] }, data: res.data.map(d => d.callCount) }] }) }
  }
}
</script>
<style scoped>
/* KPI 卡行 */
.kpi-row { margin-bottom: 0; }
.kpi-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
}
.kpi-ico {
  width: 42px; height: 42px; border-radius: 10px; flex: none;
  display: flex; align-items: center; justify-content: center; font-size: 20px;
}
.kpi-ico.blue { background: #eaf1ff; color: #2563eb; }
.kpi-ico.green { background: #e2f6ee; color: #0e7a5f; }
.kpi-ico.orange { background: #fdf1dd; color: #96611a; }
.kpi-ico.red { background: #fdeaea; color: #c03337; }
.kpi-main { flex: 1; min-width: 0; }
.kpi-label { font-size: 12px; color: #5c6b8a; }
.kpi-val { font-size: 22px; font-weight: 600; line-height: 1.35; color: #17233d; }
.kpi-val .unit { font-size: 12px; font-weight: 400; color: #9aa7bf; margin-left: 2px; }

/* 卡片标题行 */
.card-head { font-size: 14px; font-weight: 600; color: #17233d; display: flex; align-items: center; justify-content: space-between; }
.card-head .sub { font-size: 12px; color: #9aa7bf; font-weight: 400; }
</style>
