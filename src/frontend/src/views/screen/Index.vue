<template>
  <!-- 数据大屏容器 -->
  <div class="screen-container">
    <!-- 大屏顶部页头：标题 + 实时时钟 -->
    <div class="screen-header">
      <span class="title">GateKeeper API 监控大屏</span>
      <span class="clock">{{ currentTime }}</span>
    </div>
    <div class="screen-body">
      <!-- 第一行：核心指标卡片（今日总调用 / 成功率 / 限流次数 / 安全事件） -->
      <el-row :gutter="16">
        <el-col :span="6"><div class="metric-card"><div class="metric-value">{{ overview.totalCalls || 0 }}</div><div class="metric-label">今日总调用</div></div></el-col>
        <el-col :span="6"><div class="metric-card"><div class="metric-value success">{{ overview.successRate || 100 }}%</div><div class="metric-label">调用成功率</div></div></el-col>
        <el-col :span="6"><div class="metric-card"><div class="metric-value warning">{{ overview.rateLimitedCount || 0 }}</div><div class="metric-label">限流次数</div></div></el-col>
        <el-col :span="6"><div class="metric-card"><div class="metric-value danger">{{ overview.securityEventCount || 0 }}</div><div class="metric-label">安全事件</div></div></el-col>
      </el-row>
      <!-- 第二行：调用量趋势折线图 + 调用成功率图 -->
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="12"><div class="chart-card"><div class="chart-title">调用量趋势（24h）</div><div ref="trendChart" class="chart-area"></div></div></el-col>
        <el-col :span="12"><div class="chart-card"><div class="chart-title">调用成功率</div><div ref="rateChart" class="chart-area"></div></div></el-col>
      </el-row>
      <!-- 第三行：应用访问量 Top10 + 接口热度 Top10 -->
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="12"><div class="chart-card"><div class="chart-title">应用访问量 Top10</div><div ref="appRankChart" class="chart-area"></div></div></el-col>
        <el-col :span="12"><div class="chart-card"><div class="chart-title">接口热度 Top10</div><div ref="ifaceRankChart" class="chart-area"></div></div></el-col>
      </el-row>
      <!-- 第四行：安全态势面板 + 最近异常事件列表 -->
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="24">
          <div class="chart-card">
            <div class="chart-title">安全态势 & 最近异常事件</div>
            <!-- 安全态势指标面板 -->
            <div class="security-panel">
              <div class="security-stat"><span class="stat-num danger">{{ overview.activeBanCount || 0 }}</span><span class="stat-name">封禁IP数</span></div>
              <div class="security-stat"><span class="stat-num warning">{{ overview.rateLimitedCount || 0 }}</span><span class="stat-name">限流次数</span></div>
              <div class="security-stat"><span class="stat-num danger">{{ overview.blockedCount || 0 }}</span><span class="stat-name">拦截次数</span></div>
              <div class="security-stat"><span class="stat-num danger">{{ overview.securityEventCount || 0 }}</span><span class="stat-name">安全事件</span></div>
            </div>
            <!-- 最近异常事件滚动列表 -->
            <div class="event-list">
              <div v-for="event in events" :key="event.id" class="event-item">
                <el-tag size="mini" :type="eventTagType(event.eventType)">{{ event.eventType }}</el-tag>
                <span class="event-desc">{{ event.eventDesc }}</span>
                <span class="event-time">{{ event.occurredAt }}</span>
              </div>
            </div>
          </div>
        </el-col>
      </el-row>
    </div>
  </div>
</template>
<script>
import { getScreenOverview, getScreenTrend, getAppRank, getInterfaceRank, getRecentEvents } from '@/api/modules'
// echarts 按需引入（仅图表所需组件），避免打包全量 echarts（约 1MB）拖慢大屏加载
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
      // 最近异常事件列表
      events: [],
      // 当前时间字符串（大屏时钟显示）
      currentTime: '',
      // 时钟更新定时器
      timer: null,
      // 数据刷新定时器
      refreshTimer: null
    }
  },
  mounted() {
    // 启动时钟：每秒刷新一次当前时间
    this.updateClock(); this.timer = setInterval(this.updateClock, 1000)
    // 加载大屏数据，并每 10 秒自动刷新一次
    this.loadAll(); this.refreshTimer = setInterval(this.loadAll, 10000)
  },
  beforeDestroy() { clearInterval(this.timer); clearInterval(this.refreshTimer) },
  methods: {
    // 更新大屏顶部时钟
    updateClock() { const d = new Date(); this.currentTime = d.toLocaleString('zh-CN', { hour12: false }) },
    // 并行加载所有大屏数据
    async loadAll() { await Promise.all([this.loadOverview(), this.loadTrend(), this.loadAppRank(), this.loadIfaceRank(), this.loadEvents()]) },
    // 加载总览指标
    async loadOverview() { const res = await getScreenOverview(); this.overview = res.data },
    // 渲染 24 小时调用量趋势折线图（V2 青色主题）
    async loadTrend() { const res = await getScreenTrend(); const chart = echarts.init(this.$refs.trendChart); chart.setOption({ tooltip: { trigger: 'axis' }, grid: { left: '5%', right: '5%', bottom: '10%', top: '15%' }, xAxis: { type: 'category', data: res.data.map(d => d.hour), axisLine: { lineStyle: { color: 'rgba(86,150,255,.3)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, series: [{ name: '调用量', type: 'line', smooth: true, symbolSize: 5, lineStyle: { width: 2.5, color: '#35c8f0' }, areaStyle: { opacity: .22, color: '#35c8f0' }, itemStyle: { color: '#35c8f0' }, data: res.data.map(d => d.count) }] }) },
    // 渲染应用访问量 Top10 横向条形图（V2 品牌蓝）
    async loadAppRank() { const res = await getAppRank(); const chart = echarts.init(this.$refs.appRankChart); chart.setOption({ grid: { left: '25%', right: '5%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'category', data: res.data.map(d => d.appName), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#9db8e8' } }, series: [{ type: 'bar', barWidth: 12, itemStyle: { color: '#4d7cfe', borderRadius: [0, 6, 6, 0] }, data: res.data.map(d => d.callCount) }] }) },
    // 渲染接口热度 Top10 横向条形图（V2 青色主题）
    async loadIfaceRank() { const res = await getInterfaceRank(); const chart = echarts.init(this.$refs.ifaceRankChart); chart.setOption({ grid: { left: '35%', right: '5%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'category', data: res.data.map(d => d.interfacePath), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#9db8e8' } }, series: [{ type: 'bar', barWidth: 12, itemStyle: { color: '#35c8f0', borderRadius: [0, 6, 6, 0] }, data: res.data.map(d => d.callCount) }] }) },
    // 加载最近异常事件
    async loadEvents() { const res = await getRecentEvents(); this.events = res.data || [] },
    // 根据事件类型返回对应的标签颜色类型
    eventTagType(type) { const map = { HIGH_FREQUENCY: 'warning', ABNORMAL_TIME: 'warning', AUTH_FAIL: 'danger', ABNORMAL_PARAM: 'danger', PERMISSION_BREACH: 'danger' }; return map[type] || 'info' }
  }
}
</script>
<style scoped>
/* V2 大屏：深空蓝底 + 玻璃面板 + 青色数据高亮 */
.screen-container { background: #070d1a; min-height: 100vh; padding: 16px; color: #cfe0ff; }
.screen-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 24px; margin-bottom: 16px;
  border-bottom: 1px solid rgba(86, 150, 255, .22);
}
.screen-header .title {
  font-size: 24px; font-weight: 700; letter-spacing: .08em;
  background: linear-gradient(180deg, #eaf2ff, #7fa8e8);
  -webkit-background-clip: text; background-clip: text;
  -webkit-text-fill-color: transparent;
}
.screen-header .clock { font-size: 16px; color: #7fa8e8; font-family: monospace; }
.metric-card {
  background: linear-gradient(180deg, rgba(15, 27, 54, .85), rgba(10, 19, 38, .85));
  border: 1px solid rgba(86, 150, 255, .22);
  border-radius: 10px;
  padding: 20px;
  text-align: center;
  position: relative;
  overflow: hidden;
}
/* 左侧发光竖条 */
.metric-card::after {
  content: "";
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 3px;
  background: linear-gradient(180deg, #35c8f0, #2563eb);
}
.metric-value { font-size: 34px; font-weight: 700; color: #35c8f0; }
.metric-value.success { color: #2fd49b; } .metric-value.warning { color: #f5a524; } .metric-value.danger { color: #ff5f6e; }
.metric-label { font-size: 13px; color: #7fa0d8; margin-top: 8px; }
.chart-card {
  background: linear-gradient(180deg, rgba(15, 27, 54, .72), rgba(10, 19, 38, .72));
  border: 1px solid rgba(86, 150, 255, .2);
  border-radius: 10px;
  padding: 16px;
}
.chart-title { font-size: 14px; color: #eaf2ff; font-weight: 600; margin-bottom: 8px; padding-left: 10px; position: relative; }
.chart-title::before { content: ""; position: absolute; left: 0; top: 3px; bottom: 3px; width: 4px; background: linear-gradient(180deg, #35c8f0, #2563eb); border-radius: 2px; }
.chart-area { height: 220px; }
.security-panel { display: flex; justify-content: space-around; padding: 16px 0; border-bottom: 1px dashed rgba(86, 150, 255, .14); }
.security-stat { text-align: center; } .stat-num { display: block; font-size: 28px; font-weight: 700; } .stat-num.danger { color: #ff5f6e; } .stat-num.warning { color: #f5a524; }
.stat-name { font-size: 12px; color: #7fa0d8; margin-top: 4px; display: block; }
.event-list { max-height: 200px; overflow-y: auto; }
.event-item { display: flex; align-items: center; padding: 7px 0; border-bottom: 1px dashed rgba(86, 150, 255, .12); }
.event-desc { color: #b8cdf2; margin-left: 10px; flex: 1; font-size: 13px; }
.event-time { color: #5d7ba8; font-size: 12px; }
</style>
