<template>
  <div class="page-container">
    <el-card>
      <!-- 搜索区：按事件类型、处理状态筛选 -->
      <div class="toolbar">
        <el-select v-model="query.eventType" placeholder="事件类型" clearable style="width:180px">
          <el-option label="高频调用" value="HIGH_FREQUENCY" /><el-option label="异常时段" value="ABNORMAL_TIME" /><el-option label="鉴权失败" value="AUTH_FAIL" /><el-option label="异常入参" value="ABNORMAL_PARAM" /><el-option label="权限越界" value="PERMISSION_BREACH" />
        </el-select>
        <el-select v-model="query.handleStatus" placeholder="处理状态" clearable style="width:120px">
          <el-option label="待处理" :value="0" /><el-option label="已处理" :value="1" /><el-option label="已忽略" :value="2" />
        </el-select>
        <el-button type="primary" @click="loadData">查询</el-button>
      </div>
      <!-- 安全事件列表 -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="暂无安全事件，系统运行良好" /></template>
        <!-- 事件类型列：按类型映射语义色标签 -->
        <el-table-column prop="eventType" label="类型" width="130"><template slot-scope="{row}"><el-tag :type="eventTagType(row.eventType)" size="mini">{{ row.eventType }}</el-tag></template></el-table-column>
        <el-table-column prop="eventDesc" label="描述" show-overflow-tooltip />
        <el-table-column prop="appName" label="应用" width="120" /><el-table-column prop="clientIp" label="IP" width="130" />
        <!-- 处理状态列：待处理/已处理/已忽略 -->
        <el-table-column label="状态" width="80"><template slot-scope="{row}"><el-tag :type="row.handleStatus===0?'warning':row.handleStatus===1?'success':'info'">{{ row.handleStatus===0?'待处理':row.handleStatus===1?'已处理':'已忽略' }}</el-tag></template></el-table-column>
        <el-table-column prop="occurredAt" label="发生时间" width="170" />
        <!-- 操作列：仅待处理事件可执行处理/忽略 -->
        <el-table-column label="操作" width="150"><template slot-scope="{row}"><el-button v-if="row.handleStatus===0" size="mini" type="success" @click="handle(row.id,1)">处理</el-button><el-button v-if="row.handleStatus===0" size="mini" @click="handle(row.id,2)">忽略</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>
  </div>
</template>
<script>
import { getEventList, handleEvent } from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数 + 事件类型 + 处理状态
      query: { current: 1, size: 10, eventType: null, handleStatus: null },
      // 事件列表数据与总条数
      list: [], total: 0, loading: false
    }
  },
  mounted() { this.loadData() },
  methods: {
    // 加载安全事件列表
    async loadData() {
      this.loading = true
      try {
        const res = await getEventList(this.query)
        this.list = res.data.records; this.total = res.data.total
      } finally { this.loading = false }
    },
    // 处理安全事件：status 1=已处理，2=已忽略
    async handle(id, status) { await handleEvent(id, status, ''); this.loadData(); this.$message.success('操作成功') },
    // 事件类型 → Element 标签语义色映射（异常类=危险，监控类=警告）
    eventTagType(type) { const map = { AUTH_FAIL: 'danger', ABNORMAL_PARAM: 'danger', PERMISSION_BREACH: 'danger', HIGH_FREQUENCY: 'warning', ABNORMAL_TIME: 'warning' }; return map[type] || 'info' }
  }
}
</script>
