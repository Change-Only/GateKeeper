<template>
  <!-- 数据大屏容器 -->
  <div class="screen-container">
    <!-- 大屏顶部页头：标题 + 实时时钟 + 返回入口 -->
    <!-- 注意：本页是**顶层独立路由**（/screen，不在 Layout 内）⇒ 页面没有侧边栏，
         必须自带返回按钮，否则用户进来后只能改地址栏才能出去。 -->
    <div class="screen-header">
      <span class="title">GateKeeper API 监控大屏</span>
      <div class="header-right">
        <span class="clock">{{ currentTime }}</span>
        <button class="back-btn" type="button" @click="goBack">返回控制台</button>
      </div>
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
      <!-- 空数据占位（必要）：实测 ECharts 在**数据为空**时连 canvas 都不创建
           （category 轴 data=[] ⇒ 容器里只剩一个空的 zrender 根 div），页面就是一大块无声空白。
           全新部署、库里还没有调用日志时，这两块恒为空 ⇒ 必须显式给「暂无数据」，
           否则用户看到的是两个没有任何解释的空框。 -->
      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="12"><div class="chart-card"><div class="chart-title">应用访问量 Top10</div><div ref="appRankChart" class="chart-area"><div v-if="!appRank.length" class="chart-empty">暂无数据</div></div></div></el-col>
        <el-col :span="12"><div class="chart-card"><div class="chart-title">接口热度 Top10</div><div ref="ifaceRankChart" class="chart-area"><div v-if="!ifaceRank.length" class="chart-empty">暂无数据</div></div></div></el-col>
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
import { LineChart, BarChart, GaugeChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([LineChart, BarChart, GaugeChart, GridComponent, TooltipComponent, CanvasRenderer])
export default {
  data() {
    return {
      // 总览指标数据
      overview: {},
      // 最近异常事件列表
      events: [],
      // Top10 排行数据：为空时模板显示「暂无数据」占位
      // （ECharts 空数据不产 canvas，不能指望图表自己给提示）
      appRank: [],
      ifaceRank: [],
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
    // 返回控制台（本页是全屏独立路由，没有侧边栏可点）
    goBack() { this.$router.push('/dashboard') },
    // 更新大屏顶部时钟
    updateClock() { const d = new Date(); this.currentTime = d.toLocaleString('zh-CN', { hour12: false }) },
    // 并行加载所有大屏数据
    async loadAll() { await Promise.all([this.loadOverview(), this.loadTrend(), this.loadAppRank(), this.loadIfaceRank(), this.loadEvents()]) },
    // 加载总览指标，并同步渲染调用成功率仪表盘
    async loadOverview() { const res = await getScreenOverview(); this.overview = res.data; this.renderRate(res.data) },
    // 渲染调用成功率仪表盘（V2 青色主题）；loadAll 每 10s 重跑，故复用已有实例避免重复 init
    renderRate(d) { const dom = this.$refs.rateChart; if (!dom) return; const chart = echarts.getInstanceByDom(dom) || echarts.init(dom); chart.setOption({ series: [{ type: 'gauge', startAngle: 210, endAngle: -30, min: 0, max: 100, radius: '82%', center: ['50%', '58%'], progress: { show: true, width: 14, roundCap: true, itemStyle: { color: '#35c8f0' } }, axisLine: { lineStyle: { width: 14, color: [[1, 'rgba(86,150,255,.18)']] } }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false }, anchor: { show: false }, detail: { valueAnimation: true, offsetCenter: [0, '2%'], fontSize: 40, fontWeight: 'bold', color: '#2fd49b', formatter: '{value}%' }, data: [{ value: d && d.successRate != null ? d.successRate : 0 }] }] }) },
    // 渲染 24 小时调用量趋势折线图（V2 青色主题）
    async loadTrend() { const res = await getScreenTrend(); const dom = this.$refs.trendChart; if (!dom) return; const chart = echarts.getInstanceByDom(dom) || echarts.init(dom); chart.setOption({ tooltip: { trigger: 'axis' }, grid: { left: '5%', right: '5%', bottom: '10%', top: '15%' }, xAxis: { type: 'category', data: res.data.map(d => d.hour), axisLine: { lineStyle: { color: 'rgba(86,150,255,.3)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, series: [{ name: '调用量', type: 'line', smooth: true, symbolSize: 5, lineStyle: { width: 2.5, color: '#35c8f0' }, areaStyle: { opacity: .22, color: '#35c8f0' }, itemStyle: { color: '#35c8f0' }, data: res.data.map(d => d.count) }] }) },
    // 加载应用访问量 Top10：数据存进 data（模板据此决定是否显示占位），再交给渲染
    async loadAppRank() { const res = await getAppRank(); this.appRank = res.data || []; this.renderAppRank() },
    // 渲染应用访问量 Top10 横向条形图（V2 品牌蓝）
    // 空数据时**不 init**：反正 ECharts 不产 canvas，init 只会留下一个空的 zrender 根 div；
    // loadAll 每 10s 重跑 ⇒ 复用已有实例，别重复 init
    renderAppRank() { const dom = this.$refs.appRankChart; if (!dom || !this.appRank.length) return; const chart = echarts.getInstanceByDom(dom) || echarts.init(dom); chart.setOption({ grid: { left: '25%', right: '5%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'category', data: this.appRank.map(d => d.appName), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#9db8e8' } }, series: [{ type: 'bar', barWidth: 12, itemStyle: { color: '#4d7cfe', borderRadius: [0, 6, 6, 0] }, data: this.appRank.map(d => d.callCount) }] }) },
    // 加载接口热度 Top10：同上
    async loadIfaceRank() { const res = await getInterfaceRank(); this.ifaceRank = res.data || []; this.renderIfaceRank() },
    // 渲染接口热度 Top10 横向条形图（V2 青色主题）
    renderIfaceRank() { const dom = this.$refs.ifaceRankChart; if (!dom || !this.ifaceRank.length) return; const chart = echarts.getInstanceByDom(dom) || echarts.init(dom); chart.setOption({ grid: { left: '35%', right: '5%', bottom: '5%', top: '5%' }, xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(86,150,255,.1)' } }, axisLabel: { color: '#7fa0d8' } }, yAxis: { type: 'category', data: this.ifaceRank.map(d => d.interfacePath), inverse: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#9db8e8' } }, series: [{ type: 'bar', barWidth: 12, itemStyle: { color: '#35c8f0', borderRadius: [0, 6, 6, 0] }, data: this.ifaceRank.map(d => d.callCount) }] }) },
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
.screen-header .header-right { display: flex; align-items: center; gap: 16px; }
.screen-header .clock { font-size: 16px; color: #7fa8e8; font-family: monospace; }
/* 返回按钮：投屏时鼠标通常不动 ⇒ 平时低调，悬停才明显 */
.screen-header .back-btn {
  background: rgba(86, 150, 255, .12);
  color: #9db8e8;
  border: 1px solid rgba(86, 150, 255, .3);
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
  transition: background .2s, color .2s;
}
.screen-header .back-btn:hover { background: rgba(86, 150, 255, .26); color: #eaf2ff; }
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
.chart-area { height: 220px; position: relative; }
/* 空数据占位：ECharts 在 data 为空时不创建 canvas（见模板注释）⇒ 必须自己给提示文字，
   否则整块面板是无声空白 —— 新部署、尚无调用日志时就是默认状态。 */
.chart-empty {
  position: absolute; left: 0; right: 0; top: 0; bottom: 0;
  display: flex; align-items: center; justify-content: center;
  color: #5a7099; font-size: 13px; letter-spacing: .12em;
}
.security-panel { display: flex; justify-content: space-around; padding: 16px 0; border-bottom: 1px dashed rgba(86, 150, 255, .14); }
.security-stat { text-align: center; } .stat-num { display: block; font-size: 28px; font-weight: 700; } .stat-num.danger { color: #ff5f6e; } .stat-num.warning { color: #f5a524; }
.stat-name { font-size: 12px; color: #7fa0d8; margin-top: 4px; display: block; }
.event-list { max-height: 200px; overflow-y: auto; }
.event-item { display: flex; align-items: center; padding: 7px 0; border-bottom: 1px dashed rgba(86, 150, 255, .12); }
.event-desc { color: #b8cdf2; margin-left: 10px; flex: 1; font-size: 13px; }
.event-time { color: #5d7ba8; font-size: 12px; }
</style>
