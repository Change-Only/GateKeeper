<template>
  <div class="page-container">
    <el-card>
      <!-- 搜索区：按应用名称、状态筛选 + 主操作 -->
      <div class="toolbar">
        <el-input v-model="query.appName" placeholder="应用名称" clearable style="width:200px" />
        <el-select v-model="query.status" placeholder="状态" clearable style="width:120px">
          <el-option label="启用" :value="1" /><el-option label="停用" :value="0" /><el-option label="已过期" :value="2" />
        </el-select>
        <el-button type="primary" @click="loadData">查询</el-button>
        <div class="spacer"></div>
        <el-button type="primary" @click="showDialog()">＋ 创建应用</el-button>
      </div>
      <!-- 应用列表表格 -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="当前筛选条件下暂无应用，点击右上角「创建应用」开始" /></template>
        <el-table-column prop="appName" label="应用名称" /><el-table-column prop="appKey" label="AppKey" width="280" />
        <!-- 状态列：根据状态值渲染不同颜色的标签 -->
        <el-table-column label="状态" width="80">
          <template slot-scope="{row}"><el-tag :type="row.status===1?'success':row.status===0?'info':'danger'">{{ row.status===1?'启用':row.status===0?'停用':'已过期' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="expireTime" label="到期时间" width="170" /><el-table-column prop="createdAt" label="创建时间" width="170" />
        <!-- 操作列：详情 / 编辑 / 启停 / 删除 -->
        <el-table-column label="操作" width="350" fixed="right">
          <template slot-scope="{row}">
            <el-button size="mini" @click="showDetail(row)">详情</el-button>
            <el-button size="mini" @click="showDialog(row)">编辑</el-button>
            <el-button size="mini" :type="row.status===1?'warning':'success'" @click="toggleStatus(row)">{{ row.status===1?'停用':'启用' }}</el-button>
            <el-button size="mini" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 分页控件 -->
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current"
        :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>

    <!-- 新增/编辑应用对话框 -->
    <el-dialog :title="form.id?'编辑应用':'新增应用'" :visible.sync="dialogVisible" width="500px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="应用名称"><el-input v-model="form.appName" /></el-form-item>
        <el-form-item label="到期时间"><el-date-picker v-model="form.expireTime" type="date" value-format="yyyy-MM-dd HH:mm:ss" placeholder="留空=永不过期" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></div>
    </el-dialog>

    <!-- 应用详情对话框：含基本信息 / IP 白名单 / 频率限制三个标签页 -->
    <el-dialog title="应用详情" :visible.sync="detailVisible" width="700px">
      <el-tabs v-model="detailTab">
        <!-- 基本信息标签页 -->
        <el-tab-pane label="基本信息" name="info">
          <el-descriptions :column="1" border><el-descriptions-item label="AppKey">{{ detail.appKey }}</el-descriptions-item>
          <el-descriptions-item label="AppSecret"><span>{{ detail.appSecret || '••••••••（仅创建/重置时展示一次）' }}</span><el-button size="mini" @click="resetSecret" style="margin-left:10px">重置</el-button></el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.status===1?'启用':detail.status===0?'停用':'已过期' }}</el-descriptions-item>
          <el-descriptions-item label="到期时间">{{ detail.expireTime||'永不过期' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ detail.description }}</el-descriptions-item>
          </el-descriptions>
        </el-tab-pane>
        <!-- IP 白名单标签页 -->
        <el-tab-pane label="IP白名单" name="ip">
          <div style="margin-bottom:10px"><el-input v-model="newIp.cidr" placeholder="IP或CIDR (如192.168.1.0/24)" style="width:280px;margin-right:10px" /><el-button type="primary" @click="addIp">添加</el-button></div>
          <el-table :data="ipList" border><el-table-column prop="ipCidr" label="IP/CIDR" /><el-table-column prop="remark" label="备注" /><el-table-column label="操作" width="80"><template slot-scope="{row}"><el-button size="mini" type="danger" @click="removeIp(row.id)">删除</el-button></template></el-table-column></el-table>
        </el-tab-pane>
        <!-- 频率限制标签页：QPS / 并发 / 日调用上限 -->
        <el-tab-pane label="频率限制" name="rate">
          <el-form :model="rateLimit" label-width="120px">
            <el-form-item label="QPS限制"><el-input-number v-model="rateLimit.qpsLimit" :min="0" /> <span style="color:#909399">0=不限</span></el-form-item>
            <el-form-item label="并发限制"><el-input-number v-model="rateLimit.concurrentLimit" :min="0" /> <span style="color:#909399">0=不限</span></el-form-item>
            <el-form-item label="日调用上限"><el-input-number v-model="rateLimit.dailyLimit" :min="0" /> <span style="color:#909399">0=不限</span></el-form-item>
            <el-form-item><el-button type="primary" @click="saveRateLimit">保存</el-button></el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 密钥展示对话框：只读展示 + 一键复制（替代 HTML 注入弹窗，消除 XSS 隐患） -->
    <el-dialog title="请妥善保存密钥" :visible.sync="secretVisible" width="480px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="密钥仅展示一次，请立即复制保存，关闭后无法再次查看" style="margin-bottom:14px" />
      <el-form label-width="80px">
        <el-form-item v-if="secretData.appKey" label="AppKey">
          <el-input :value="secretData.appKey" readonly>
            <el-button slot="append" @click="copySecret('appKey')">复制</el-button>
          </el-input>
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input :value="secretData.appSecret" readonly>
            <el-button slot="append" @click="copySecret('appSecret')">复制</el-button>
          </el-input>
        </el-form-item>
      </el-form>
      <span slot="footer"><el-button type="primary" @click="secretVisible=false">我已保存</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import { getAppList, createApp, updateApp, updateAppStatus, deleteApp, getIpWhitelist, addIpWhitelist, removeIpWhitelist, getRateLimit, updateRateLimit, resetSecret } from '@/api/modules'

export default {
  data() {
    return {
      // 查询条件：分页参数 + 应用名称 + 状态筛选
      query: { current: 1, size: 10, appName: '', status: null },
      // 列表数据与总条数
      list: [], total: 0, loading: false, saving: false,
      // 新增/编辑对话框显示状态与表单数据
      dialogVisible: false, form: {},
      // 详情对话框显示状态、详情数据与当前标签页
      detailVisible: false, detail: {}, detailTab: 'info',
      // IP 白名单列表与待新增的 IP 条目
      ipList: [], newIp: {},
      // 频率限制配置
      rateLimit: { qpsLimit: 0, concurrentLimit: 0, dailyLimit: 0 },
      // 密钥展示对话框（只读 + 复制，替代 HTML 注入弹窗）
      secretVisible: false, secretData: {}
    }
  },
  mounted() { this.loadData() },
  methods: {
    // 加载应用列表（带分页）
    async loadData() {
      this.loading = true
      try {
        const res = await getAppList(this.query)
        this.list = res.data.records; this.total = res.data.total
      } finally { this.loading = false }
    },
    // 打开新增/编辑对话框，有行数据则为编辑，否则为新增
    showDialog(row) { this.form = row ? { ...row } : {}; this.dialogVisible = true },
    // 保存应用：有 id 则更新，否则创建
    async save() {
      if (this.saving) return
      this.saving = true
      try {
        if (this.form.id) {
          await updateApp(this.form.id, this.form)
        } else {
          // 新建应用：AppSecret 仅本次返回，弹窗展示一次后不再可见
          const res = await createApp(this.form)
          this.dialogVisible = false
          this.loadData()
          if (res.data && res.data.appSecret) {
            this.openSecret(res.data)
            return
          }
        }
        this.dialogVisible = false; this.loadData(); this.$message.success('保存成功')
      } finally { this.saving = false }
    },
    // 切换应用启用/停用状态
    async toggleStatus(row) { await updateAppStatus(row.id, row.status === 1 ? 0 : 1); this.loadData() },
    // 删除应用
    async remove(row) { await this.$confirm('确认删除?'); await deleteApp(row.id); this.loadData() },
    // 打开详情对话框，并加载白名单与频率限制数据
    async showDetail(row) { this.detail = row; this.detailVisible = true; this.detailTab = 'info'; this.loadIpList(row.id); this.loadRateLimit(row.id) },
    // 加载指定应用的 IP 白名单
    async loadIpList(appId) { const res = await getIpWhitelist(appId); this.ipList = res.data },
    // 添加 IP 白名单条目
    async addIp() { if (!this.newIp.cidr) return; await addIpWhitelist(this.detail.id, this.newIp); this.newIp = {}; this.loadIpList(this.detail.id) },
    // 删除 IP 白名单条目
    async removeIp(id) { await removeIpWhitelist(id); this.loadIpList(this.detail.id) },
    // 加载指定应用的频率限制配置
    async loadRateLimit(appId) { const res = await getRateLimit(appId); if (res.data) this.rateLimit = res.data },
    // 保存频率限制配置
    async saveRateLimit() { await updateRateLimit(this.detail.id, this.rateLimit); this.$message.success('保存成功') },
    // 重置 AppSecret（需二次确认，新密钥仅本次弹窗展示一次）
    async resetSecret() {
      await this.$confirm('重置后旧AppSecret将立即失效，确认?')
      const res = await resetSecret(this.detail.id)
      this.openSecret({ appSecret: res.data.appSecret })
    },
    // 打开密钥展示对话框（只读 + 复制，替代 HTML 注入弹窗）
    openSecret(data) { this.secretData = data || {}; this.secretVisible = true },
    // 一键复制密钥到剪贴板（优先 Clipboard API，兼容降级 execCommand）
    copySecret(field) {
      const val = this.secretData[field]
      if (!val) return
      const ok = () => this.$message.success('已复制到剪贴板')
      const fail = () => this.$message.error('复制失败，请手动选择复制')
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(val).then(ok).catch(fail)
      } else {
        const ta = document.createElement('textarea')
        ta.value = val
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        try { document.execCommand('copy') ? ok() : fail() } catch (e) { fail() }
        document.body.removeChild(ta)
      }
    }
  }
}
</script>
