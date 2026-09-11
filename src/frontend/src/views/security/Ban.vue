<template>
  <div class="page-container">
    <el-card>
      <!-- 操作区：手动封禁入口 -->
      <div class="toolbar">
        <div class="spacer"></div>
        <el-button type="danger" @click="showBan">⛔ 手动封禁</el-button>
      </div>
      <!-- IP 封禁列表 -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="暂无封禁记录" /></template>
        <el-table-column prop="ipAddress" label="IP地址" width="150" />
        <!-- 关联应用列：无 appId 时显示全局 -->
        <el-table-column prop="appId" label="关联应用" width="100"><template slot-scope="{row}">{{ row.appId||'全局' }}</template></el-table-column>
        <el-table-column prop="banReason" label="封禁原因" show-overflow-tooltip />
        <!-- 封禁类型列：手动/自动（手动=信息蓝，自动=危险红） -->
        <el-table-column prop="banType" label="类型" width="80"><template slot-scope="{row}"><el-tag :type="row.banType==='MANUAL'?'info':'danger'">{{ row.banType==='MANUAL'?'手动':'自动' }}</el-tag></template></el-table-column>
        <el-table-column prop="banStartTime" label="开始时间" width="170" />
        <el-table-column prop="banEndTime" label="结束时间" width="170" />
        <!-- 封禁状态列 -->
        <el-table-column label="状态" width="80"><template slot-scope="{row}"><el-tag :type="row.banStatus===1?'danger':'info'">{{ row.banStatus===1?'封禁中':'已解封' }}</el-tag></template></el-table-column>
        <!-- 操作列：仅封禁中的记录可解封 -->
        <el-table-column label="操作" width="100"><template slot-scope="{row}"><el-button v-if="row.banStatus===1" size="mini" type="primary" @click="unban(row.id)">解封</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>
    <!-- 手动封禁对话框 -->
    <el-dialog title="手动封禁" :visible.sync="banVisible" width="500px">
      <el-form :model="banForm" label-width="100px">
        <el-form-item label="IP地址"><el-input v-model="banForm.ipAddress" /></el-form-item>
        <el-form-item label="关联应用"><el-input-number v-model="banForm.appId" :min="0" placeholder="0=全局封禁" /></el-form-item>
        <el-form-item label="封禁时长(分钟)"><el-input-number v-model="banForm.durationMin" :min="1" /></el-form-item>
        <el-form-item label="封禁原因"><el-input v-model="banForm.reason" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="banVisible=false">取消</el-button><el-button type="danger" :loading="banning" @click="doBan">确认封禁</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import { getBanList, banIp, unbanIp } from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数
      query: { current: 1, size: 10 },
      // 封禁列表数据与总条数
      list: [], total: 0, loading: false, banning: false,
      // 手动封禁对话框显示状态与表单
      banVisible: false, banForm: { ipAddress: '', appId: null, durationMin: 60, reason: '' }
    }
  },
  mounted() { this.loadData() },
  methods: {
    // 加载 IP 封禁列表
    async loadData() {
      this.loading = true
      try {
        const res = await getBanList(this.query)
        this.list = res.data.records; this.total = res.data.total
      } finally { this.loading = false }
    },
    // 打开手动封禁对话框并重置表单
    showBan() { this.banForm = { ipAddress: '', appId: null, durationMin: 60, reason: '' }; this.banVisible = true },
    // 执行封禁操作
    async doBan() {
      if (this.banning) return
      this.banning = true
      try {
        await banIp(this.banForm); this.banVisible = false; this.loadData(); this.$message.success('封禁成功')
      } finally { this.banning = false }
    },
    // 解封 IP
    async unban(id) { await this.$confirm('确认解封?'); await unbanIp(id); this.loadData() }
  }
}
</script>
