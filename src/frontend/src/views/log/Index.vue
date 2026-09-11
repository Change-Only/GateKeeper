<template>
  <div class="page-container">
    <el-card>
      <!-- 搜索区：按 IP、状态码、时间范围筛选日志 -->
      <div class="toolbar">
        <el-input v-model="query.clientIp" placeholder="调用IP" clearable style="width:150px" />
        <el-select v-model="query.responseStatus" placeholder="状态码" clearable style="width:120px">
          <el-option label="200" :value="200" /><el-option label="403" :value="403" /><el-option label="429" :value="429" />
        </el-select>
        <el-date-picker v-model="query.timeRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" value-format="yyyy-MM-ddTHH:mm:ss" />
        <el-button type="primary" @click="loadData">查询</el-button>
        <!-- 导出中心：大数据量异步导出，避免卡死页面 -->
        <el-button icon="el-icon-download" @click="openExport">导出中心</el-button>
      </div>
      <!-- 调用日志表格 -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="当前筛选条件下暂无调用日志" /></template>
        <el-table-column prop="appName" label="应用" width="120" />
        <el-table-column prop="interfacePath" label="接口" width="200" show-overflow-tooltip />
        <!-- 方法列：彩色方法标签 -->
        <el-table-column prop="requestMethod" label="方法" width="80"><template slot-scope="{row}"><span class="method" :class="row.requestMethod.toLowerCase()">{{ row.requestMethod }}</span></template></el-table-column>
        <el-table-column prop="requestTime" label="请求时间" width="170" />
        <!-- 状态码列：2xx 为成功绿色，其余为失败红色 -->
        <el-table-column prop="responseStatus" label="状态码" width="80"><template slot-scope="{row}"><el-tag :type="row.responseStatus>=200&&row.responseStatus<300?'success':'danger'">{{ row.responseStatus }}</el-tag></template></el-table-column>
        <el-table-column prop="costTime" label="耗时(ms)" width="90" />
        <el-table-column prop="clientIp" label="IP" width="130" />
        <el-table-column prop="encryptionAlgorithm" label="加密" width="80" />
        <!-- 标记列：限流/拦截标签 -->
        <el-table-column label="标记" width="120"><template slot-scope="{row}"><el-tag v-if="row.isRateLimited" type="warning" size="mini">限流</el-tag><el-tag v-if="row.isBlocked" type="danger" size="mini">拦截</el-tag></template></el-table-column>
        <el-table-column label="操作" width="80"><template slot-scope="{row}"><el-button size="mini" @click="showDetail(row)">详情</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>

    <!-- 日志详情对话框：展示请求/响应明细 -->
    <el-dialog title="日志详情" :visible.sync="detailVisible" width="700px">
      <el-descriptions :column="2" border v-if="detail">
        <el-descriptions-item label="应用">{{ detail.appName }}</el-descriptions-item>
        <el-descriptions-item label="接口">{{ detail.interfacePath }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ detail.requestMethod }}</el-descriptions-item>
        <el-descriptions-item label="状态码">{{ detail.responseStatus }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ detail.costTime }}ms</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detail.clientIp }}</el-descriptions-item>
        <el-descriptions-item label="加密算法">{{ detail.encryptionAlgorithm }}</el-descriptions-item>
        <el-descriptions-item label="请求时间">{{ detail.requestTime }}</el-descriptions-item>
      </el-descriptions>
      <!-- 入参与响应内容展示区 -->
      <div style="margin-top:15px"><h4>入参</h4><pre style="background:#f5f5f5;padding:10px;max-height:200px;overflow:auto">{{ detail.requestParams }}</pre></div>
      <div style="margin-top:10px"><h4>响应</h4><pre style="background:#f5f5f5;padding:10px;max-height:200px;overflow:auto">{{ detail.responseData }}</pre></div>
    </el-dialog>

    <!-- 导出中心对话框：异步任务创建/状态轮询/下载 -->
    <el-dialog title="导出中心" :visible.sync="exportVisible" width="860px" @open="onExportOpen" @closed="onExportClosed">
      <div class="export-tip">
        导出在<b>当前筛选条件</b>下后台生成 CSV 文件，大数据量（百万级）时请耐心等待；任务完成后即可下载，不影响页面其他操作。
      </div>
      <div class="export-actions">
        <el-button type="primary" icon="el-icon-plus" :loading="creating" @click="createExport">创建导出任务</el-button>
        <span class="export-hint">共 {{ exportTasks.length }} 条任务 · 状态每 5 秒自动刷新</span>
      </div>
      <el-table :data="exportTasks" border size="small">
        <el-table-column prop="id" label="任务ID" width="70" />
        <!-- 状态列：语义色标签 -->
        <el-table-column label="状态" width="90">
          <template slot-scope="{row}">
            <el-tag v-if="row.status==='PENDING'" type="info" size="mini">排队中</el-tag>
            <el-tag v-else-if="row.status==='RUNNING'" type="warning" size="mini">生成中</el-tag>
            <el-tag v-else-if="row.status==='SUCCESS'" type="success" size="mini">可下载</el-tag>
            <el-tag v-else type="danger" size="mini">失败</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalRows" label="导出行数" width="100"><template slot-scope="{row}">{{ row.totalRows ? row.totalRows.toLocaleString() : '-' }}</template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="160" />
        <el-table-column prop="finishedAt" label="完成时间" width="160"><template slot-scope="{row}">{{ row.finishedAt || '-' }}</template></el-table-column>
        <el-table-column label="失败原因" min-width="160" show-overflow-tooltip><template slot-scope="{row}"><span class="err-msg">{{ row.errorMsg || '' }}</span></template></el-table-column>
        <el-table-column label="操作" width="90">
          <template slot-scope="{row}">
            <el-button v-if="row.status==='SUCCESS'" size="mini" type="primary" plain @click="downloadTask(row)">下载</el-button>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>
<script>
import { getLogList, getLogDetail, createLogExport, getExportTasks, downloadExportTask } from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数 + IP + 状态码 + 时间范围
      query: { current: 1, size: 20, clientIp: '', responseStatus: null, timeRange: null },
      // 日志列表数据与总条数
      list: [], total: 0, loading: false,
      // 详情对话框显示状态与详情数据
      detailVisible: false, detail: null,
      // 导出中心：对话框/任务列表/创建中标记/轮询定时器
      exportVisible: false, exportTasks: [], creating: false, exportTimer: null
    }
  },
  mounted() { this.loadData() },
  beforeDestroy() { this.stopPolling() },
  methods: {
    // 加载调用日志列表，时间范围拆分为起止时间参数
    async loadData() {
      this.loading = true
      try {
        const params = { current: this.query.current, size: this.query.size, clientIp: this.query.clientIp, responseStatus: this.query.responseStatus }
        // 若选择了时间范围，则附加开始与结束时间参数
        if (this.query.timeRange && this.query.timeRange.length === 2) { params.startTime = this.query.timeRange[0]; params.endTime = this.query.timeRange[1] }
        const res = await getLogList(params); this.list = res.data.records; this.total = res.data.total
      } finally { this.loading = false }
    },
    // 打开日志详情对话框并加载详情
    async showDetail(row) { const res = await getLogDetail(row.id); this.detail = res.data; this.detailVisible = true },

    // ---- 导出中心 ----
    // 把当前查询条件组装为导出参数
    buildExportParams() {
      const params = { clientIp: this.query.clientIp, responseStatus: this.query.responseStatus }
      if (this.query.timeRange && this.query.timeRange.length === 2) { params.startTime = this.query.timeRange[0]; params.endTime = this.query.timeRange[1] }
      return params
    },
    openExport() { this.exportVisible = true },
    // 对话框打开：加载任务列表并启动轮询
    onExportOpen() { this.loadExportTasks(); this.startPolling() },
    // 对话框关闭：停止轮询
    onExportClosed() { this.stopPolling() },
    startPolling() {
      this.stopPolling()
      this.exportTimer = setInterval(() => this.loadExportTasks(), 5000)
    },
    stopPolling() { if (this.exportTimer) { clearInterval(this.exportTimer); this.exportTimer = null } },
    // 加载导出任务列表（当前登录人）
    async loadExportTasks() {
      const res = await getExportTasks({ current: 1, size: 20 })
      this.exportTasks = res.data.records || []
    },
    // 创建导出任务：固化当前筛选条件，后台异步生成
    async createExport() {
      this.creating = true
      try {
        await createLogExport(this.buildExportParams())
        this.$message.success('导出任务已创建，完成后可下载')
        await this.loadExportTasks()
      } finally { this.creating = false }
    },
    // 下载 CSV：blob 转本地 URL 触发浏览器保存（拦截器已解包，返回值即 Blob）
    async downloadTask(row) {
      const blob = await downloadExportTask(row.id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = row.fileName || 'call-log.csv'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    }
  }
}
</script>
<style scoped>
.export-tip { background: #f0f5ff; border-left: 3px solid #2563eb; padding: 10px 12px; border-radius: 4px; font-size: 13px; color: #3c4a63; margin-bottom: 12px; }
.export-actions { display: flex; align-items: center; margin-bottom: 12px; }
.export-hint { margin-left: 12px; font-size: 12px; color: #8a94a6; }
.err-msg { color: #c03337; font-size: 12px; }
.muted { color: #c0c4cc; }
</style>
