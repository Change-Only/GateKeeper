<template>
  <div class="page-container" v-loading="loading">
    <!-- 顶部 KPI 指标卡行 -->
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
        <div class="kpi-main"><div class="kpi-label">安全事件</div><div class="kpi-val" style="color:#c03337">{{ securityEventTotal || 0 }}<span class="unit">起</span></div></div>
      </el-card></el-col>
    </el-row>

    <!-- 待办清单 + 风险看板 -->
    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14">
        <el-card shadow="never" class="todo-card">
          <div slot="header" class="card-head">待办清单<span class="sub">待处理事项 · 点击前往处理</span></div>
          <ul class="todo-list">
            <li v-for="t in todos" :key="t.type" class="todo-item" @click="go(t.route)">
              <span class="todo-ico" :class="todoMeta(t).cls">{{ todoMeta(t).icon }}</span>
              <div class="todo-text">
                <span class="todo-name">{{ t.label }}</span>
                <span v-if="t.desc" class="todo-desc">{{ t.desc }}</span>
              </div>
              <span class="todo-num" :class="todoMeta(t).numCls">{{ t.count }}</span>
              <i class="el-icon-arrow-right todo-arrow" />
            </li>
            <li v-if="!todos.length" class="todo-empty">暂无待办事项</li>
          </ul>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="risk-card">
          <div slot="header" class="card-head">风险看板<span class="sub">按告警级别 · 仅未处置</span></div>
          <div class="risk-grid">
            <div class="risk-item high" @click="go('/mon/mon-alarm')">
              <div class="risk-num">{{ risk.high }}</div><div class="risk-label">高危 · CRITICAL</div>
            </div>
            <div class="risk-item mid" @click="go('/mon/mon-alarm')">
              <div class="risk-num">{{ risk.mid }}</div><div class="risk-label">中危 · WARNING</div>
            </div>
            <div class="risk-item low" @click="go('/mon/mon-alarm')">
              <div class="risk-num">{{ risk.low }}</div><div class="risk-label">低危 · INFO</div>
            </div>
          </div>
        </el-card>
      </el-col>
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
/**
 * 概览仪表盘（T06-B 改造）
 * KPI + 待办清单 + 风险看板 + 调用量趋势/Top 排行（echarts）。
 *
 * 数据来源（全部为服务端预聚合，前端不再直查各域列表计数）：
 *   - /dashboard/screen/overview|trend|app-rank|interface-rank   既有 screen 端点（未改动）
 *   - /dashboard/todo    待办清单（固定顺序 4 类待处理事项）      ← T06-B 新增消费
 *   - /dashboard/risk    风险看板（按 alert.level 分级，仅未处理）← T06-B 新增消费
 *   - /security/event/list  安全事件 KPI：读取 PageResult 的 total 真实计数
 *                           （overview.securityEventCount 后端并不存在，故不再使用）
 *
 * 任一聚合接口异常时以 0 / 空兜底，不阻塞整体渲染；每 30 秒自动刷新。
 */
import { getScreenOverview, getScreenTrend, getAppRank, getInterfaceRank, getDashboardTodo, getDashboardRisk, getEventList } from '@/api/modules'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, CanvasRenderer])

// 待办项按 type 分派的展示元数据（图标 / 配色），与后端返回的 type 一一对应
const TODO_META = {
  api: { icon: '🔌', cls: 'blue', numCls: '' },
  grant: { icon: '🔐', cls: 'green', numCls: '' },
  cred: { icon: '🔑', cls: 'orange', numCls: 'warn' },
  alarm: { icon: '🔔', cls: 'red', numCls: 'danger' }
}

export default {
  name: 'Dashboard',
  data() {
    return {
      overview: {},
      // 待办清单：由 /dashboard/todo 提供（{type,label,count,desc,route}，顺序固定）
      todos: [],
      // 风险看板：由 /dashboard/risk 提供（{high,mid,low}，按告警级别）
      risk: { high: 0, mid: 0, low: 0 },
      // 安全事件 KPI：由 /security/event/list 的 PageResult.total 提供（真实计数）
      securityEventTotal: 0,
      loading: false,
      timer: null
    }
  },
  mounted() {
    this.loadAll()
    this.timer = setInterval(this.loadAll, 30000)
  },
  beforeDestroy() { clearInterval(this.timer) },
  methods: {
    go(path) {
      if (path) this.$router.push(path)
    },
    todoMeta(t) {
      return TODO_META[t.type] || { icon: '📌', cls: 'blue', numCls: '' }
    },
    async loadAll() {
      this.loading = true
      try {
        await Promise.all([
          this.loadOverview(),
          this.loadTrend(),
          this.loadAppRank(),
          this.loadIfaceRank(),
          this.loadTodo(),
          this.loadRisk(),
          this.loadSecurityEventCount()
        ])
      } finally { this.loading = false }
    },
    async loadOverview() {
      try { const res = await getScreenOverview(); this.overview = res.data || {} } catch (e) { this.overview = {} }
    },
    // 待办清单：服务端预聚合，直接消费（前端不再发 4 个 list 请求凑计数）
    async loadTodo() {
      try {
        const res = await getDashboardTodo()
        this.todos = (res && res.data) || []
      } catch (e) { this.todos = [] }
    },
    // 风险看板：按告警级别（CRITICAL/WARNING/INFO）统计未处置告警
    async loadRisk() {
      try {
        const res = await getDashboardRisk()
        const d = (res && res.data) || {}
        this.risk = { high: d.high || 0, mid: d.mid || 0, low: d.low || 0 }
      } catch (e) { this.risk = { high: 0, mid: 0, low: 0 } }
    },
    // 安全事件 KPI：读 /security/event/list 的 PageResult.total（只取 1 条，拿总数）
    async loadSecurityEventCount() {
      try {
        const res = await getEventList({ current: 1, size: 1 })
        const page = (res && res.data) || {}
        this.securityEventTotal = page.total || 0
      } catch (e) { this.securityEventTotal = 0 }
    },
    async loadTrend() {
      const res = await getScreenTrend()
      const chart = echarts.init(this.$refs.trendChart)
      chart.setOption({
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', data: (res.data || []).map((d) => d.hour), axisLine: { lineStyle: { color: '#d3dbe8' } }, axisLabel: { color: '#5c6b8a' } },
        yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } },
        series: [{ name: '调用量', type: 'line', smooth: true, symbolSize: 6, areaStyle: { opacity: 0.18, color: '#2563eb' }, lineStyle: { width: 2.5, color: '#2563eb' }, itemStyle: { color: '#2563eb' }, data: (res.data || []).map((d) => d.count) }]
      })
    },
    async loadAppRank() {
      const res = await getAppRank()
      const chart = echarts.init(this.$refs.appRankChart)
      chart.setOption({
        tooltip: {},
        grid: { left: '20%', right: '6%', bottom: '5%', top: '5%' },
        xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } },
        yAxis: { type: 'category', data: (res.data || []).map((d) => d.appName), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#17233d' } },
        series: [{ type: 'bar', barWidth: 14, itemStyle: { color: '#2563eb', borderRadius: [0, 7, 7, 0] }, data: (res.data || []).map((d) => d.callCount) }]
      })
    },
    async loadIfaceRank() {
      const res = await getInterfaceRank()
      const chart = echarts.init(this.$refs.ifaceRankChart)
      chart.setOption({
        tooltip: {},
        grid: { left: '35%', right: '6%', bottom: '5%', top: '5%' },
        xAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f7' } }, axisLabel: { color: '#5c6b8a' } },
        yAxis: { type: 'category', data: (res.data || []).map((d) => d.interfacePath), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#17233d' } },
        series: [{ type: 'bar', barWidth: 14, itemStyle: { color: '#22d3ee', borderRadius: [0, 7, 7, 0] }, data: (res.data || []).map((d) => d.callCount) }]
      })
    }
  }
}
</script>

<style scoped>
.kpi-row { margin-bottom: 0; }
.card-head { font-size: 14px; font-weight: 600; color: #17233d; display: flex; align-items: center; justify-content: space-between; }
.card-head .sub { font-size: 12px; color: #9aa7bf; font-weight: 400; }
.todo-card, .risk-card { min-height: 240px; }
.todo-list { list-style: none; margin: 0; padding: 0; }
.todo-item { display: flex; align-items: center; gap: 12px; padding: 12px 8px; border-bottom: 1px solid #f0f3f9; cursor: pointer; transition: background .15s; }
.todo-item:last-child { border-bottom: none; }
.todo-item:hover { background: #f7faff; }
.todo-empty { padding: 24px 8px; text-align: center; color: #9aa7bf; font-size: 13px; }
.todo-ico { width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 16px; flex: none; }
.todo-ico.blue { background: #eaf1ff; color: #2563eb; }
.todo-ico.green { background: #e2f6ee; color: #0e7a5f; }
.todo-ico.orange { background: #fdf1dd; color: #96611a; }
.todo-ico.red { background: #fdeaea; color: #c03337; }
.todo-text { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.todo-name { font-size: 13px; color: #17233d; }
.todo-desc { font-size: 12px; color: #9aa7bf; }
.todo-num { font-size: 18px; font-weight: 600; color: #17233d; }
.todo-num.warn { color: #96611a; }
.todo-num.danger { color: #c03337; }
.todo-arrow { color: #c0c8d8; }
.risk-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding-top: 6px; }
.risk-item { border-radius: 10px; padding: 16px 12px; text-align: center; cursor: pointer; }
.risk-item.high { background: #fdeaea; }
.risk-item.mid { background: #fdf1dd; }
.risk-item.low { background: #eaf1ff; }
.risk-num { font-size: 26px; font-weight: 700; }
.risk-item.high .risk-num { color: #c03337; }
.risk-item.mid .risk-num { color: #96611a; }
.risk-item.low .risk-num { color: #2563eb; }
.risk-label { font-size: 12px; color: #5c6b8a; margin-top: 4px; }
</style>
