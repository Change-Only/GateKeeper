<template>
  <div class="page-container">
    <el-card class="head-card">
      <div class="head">
        <div class="head-title">
          <i class="el-icon-bell"></i>
          <div>
            <h2>告警中心</h2>
            <p>汇聚网关运行态运营告警，按等级实时提示，支持标记已读与处置跟进</p>
          </div>
        </div>
        <div class="head-stats">
          <div class="stat">
            <span class="num">{{ stats.unread }}</span>
            <span class="lbl">未读告警</span>
          </div>
          <div class="stat critical">
            <span class="num">{{ stats.critical }}</span>
            <span class="lbl">严重未处理</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card>
      <!-- 筛选区：等级 / 来源 / 状态 -->
      <div class="toolbar">
        <el-select v-model="query.level" placeholder="告警等级" clearable style="width:140px">
          <el-option label="提示 INFO" value="INFO" />
          <el-option label="警告 WARNING" value="WARNING" />
          <el-option label="严重 CRITICAL" value="CRITICAL" />
        </el-select>
        <el-select v-model="query.source" placeholder="告警来源" clearable style="width:150px">
          <el-option label="网关运行" value="GATEWAY" />
          <el-option label="安全检测" value="SECURITY" />
          <el-option label="限流" value="RATE_LIMIT" />
          <el-option label="系统" value="SYSTEM" />
        </el-select>
        <el-select v-model="query.status" placeholder="处理状态" clearable style="width:130px">
          <el-option label="未读" :value="0" />
          <el-option label="已读" :value="1" />
          <el-option label="已处理" :value="2" />
          <el-option label="已忽略" :value="3" />
        </el-select>
        <el-button type="primary" @click="loadData">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <div class="tb-right">
          <el-button :disabled="stats.unread === 0" type="warning" plain @click="markAllRead">
            <i class="el-icon-check"></i> 全部已读
          </el-button>
        </div>
      </div>

      <!-- 告警列表 -->
      <el-table :data="list" border class="alert-table" v-loading="loading" @row-dblclick="openHandle">
        <template slot="empty"><EmptyState description="当前筛选条件下暂无告警，系统运行正常" /></template>
        <!-- 等级列：按等级映射语义色标签（严重=红 / 警告=橙 / 提示=蓝） -->
        <el-table-column label="等级" width="110">
          <template slot-scope="{row}">
            <el-tag :type="levelTag(row.level)" size="mini" effect="dark">{{ levelText(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="来源" width="110">
          <template slot-scope="{row}"><el-tag size="mini">{{ sourceText(row.source) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="content" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="relatedAppName" label="应用" width="120" show-overflow-tooltip />
        <el-table-column prop="relatedIp" label="IP" width="130" />
        <!-- 状态列：未读/已读/已处理/已忽略 -->
        <el-table-column label="状态" width="90">
          <template slot-scope="{row}">
            <el-tag :type="statusTag(row.status)" size="mini">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="occurredAt" label="发生时间" width="160" />
        <!-- 操作列：未读可标记已读；未处理/已读可处置 -->
        <el-table-column label="操作" width="170" fixed="right">
          <template slot-scope="{row}">
            <el-button v-if="row.status === 0" size="mini" type="primary" plain @click="markRead(row)">标记已读</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" size="mini" type="success" plain @click="openHandle(row)">处理</el-button>
            <el-button v-if="row.status === 2 || row.status === 3" size="mini" type="info" plain disabled>已处置</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v => { query.current = v; loadData() }"
                      :current-page="query.current" :page-size="query.size" :total="total"
                      layout="total, prev, pager, next" />
    </el-card>

    <!-- 处置弹窗 -->
    <el-dialog title="处置告警" :visible.sync="dialogVisible" width="460px">
      <el-form label-width="80px">
        <el-form-item label="告警标题">
          <span>{{ current.title }}</span>
        </el-form-item>
        <el-form-item label="处置结果">
          <el-radio-group v-model="handleForm.status">
            <el-radio :label="2">已处理</el-radio>
            <el-radio :label="3">已忽略</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="处理备注">
          <el-input v-model="handleForm.remark" type="textarea" :rows="3" placeholder="请填写处置说明（可选）" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="handling" @click="submitHandle">确定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { getAlertList, getAlertUnread, markAlertRead, markAllAlertRead, handleAlert } from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页 + 等级 + 来源 + 状态
      query: { current: 1, size: 10, level: null, source: null, status: null },
      list: [], total: 0, loading: false, handling: false,
      // 顶部统计：未读数 / 严重未处理数
      stats: { unread: 0, critical: 0 },
      dialogVisible: false,
      current: {},
      handleForm: { status: 2, remark: '' }
    }
  },
  mounted() { this.loadData() },
  methods: {
    // 加载告警列表并刷新顶部统计
    async loadData() {
      this.loading = true
      try {
        const res = await getAlertList(this.query)
        this.list = res.data.records
        this.total = res.data.total
        this.refreshStats()
      } finally { this.loading = false }
    },
    // 刷新未读 / 严重未处理统计
    async refreshStats() {
      try {
        this.stats.unread = (await getAlertUnread()).data || 0
      } catch (e) { /* 统计失败不影响列表 */ }
      // 严重未处理：CRITICAL + 状态为未读/已读（0/1）
      const r = await getAlertList({ current: 1, size: 1, level: 'CRITICAL' })
      this.stats.critical = r.data.total
    },
    resetQuery() {
      this.query = { current: 1, size: 10, level: null, source: null, status: null }
      this.loadData()
    },
    async markRead(row) {
      await markAlertRead(row.id)
      this.$message.success('已标记为已读')
      this.loadData()
    },
    async markAllRead() {
      await markAllAlertRead()
      this.$message.success('已全部标记为已读')
      this.loadData()
    },
    openHandle(row) { this.current = row; this.handleForm = { status: 2, remark: '' }; this.dialogVisible = true },
    async submitHandle() {
      if (this.handling) return
      this.handling = true
      try {
        await handleAlert(this.current.id, this.handleForm.status, this.handleForm.remark)
        this.$message.success('处置成功')
        this.dialogVisible = false
        this.loadData()
      } finally { this.handling = false }
    },
    // 等级 → 标签语义色（严重=危险红，警告=警告橙，提示=信息蓝）
    levelTag(level) { return level === 'CRITICAL' ? 'danger' : level === 'WARNING' ? 'warning' : 'info' },
    levelText(level) { return level === 'CRITICAL' ? '严重' : level === 'WARNING' ? '警告' : '提示' },
    sourceText(source) {
      return { GATEWAY: '网关运行', SECURITY: '安全检测', RATE_LIMIT: '限流', SYSTEM: '系统' }[source] || source
    },
    statusTag(status) {
      return status === 0 ? 'danger' : status === 1 ? 'info' : status === 2 ? 'success' : 'warning'
    },
    statusText(status) { return ['未读', '已读', '已处理', '已忽略'][status] || '未知' }
  }
}
</script>

<style scoped>
.page-container { padding: 18px; }
.head-card { margin-bottom: 16px; border: none; box-shadow: 0 2px 12px rgba(16, 38, 74, .06); }
.head { display: flex; align-items: center; justify-content: space-between; }
.head-title { display: flex; align-items: center; gap: 14px; }
.head-title i { font-size: 30px; color: #2563eb; }
.head-title h2 { margin: 0; font-size: 18px; color: #17233d; }
.head-title p { margin: 4px 0 0; font-size: 12px; color: #9aa7bf; }
.head-stats { display: flex; gap: 14px; }
.stat {
  min-width: 110px; padding: 10px 18px; border-radius: 12px;
  background: #f4f7ff; text-align: center;
}
.stat.critical { background: #fdeeee; }
.stat .num { display: block; font-size: 22px; font-weight: 700; color: #2563eb; }
.stat.critical .num { color: #c03337; }
.stat .lbl { font-size: 12px; color: #7a89a8; }

.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.tb-right { margin-left: auto; }
.alert-table { margin-bottom: 14px; }
.pagination { text-align: right; }
</style>
