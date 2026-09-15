<template>
  <div class="page-container">
    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchApps"
      :query="query"
      row-key="id"
      :show-index="true"
      @loaded="onLoaded"
    >
      <template #toolbar>
        <el-input
          v-model="query.kw"
          placeholder="应用名称"
          clearable
          style="width: 200px"
          @keyup.enter.native="reload"
          @clear="reload"
        />
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px" @change="reload">
          <el-option :value="1" label="已启用" />
          <el-option :value="0" label="已停用" />
          <el-option :value="2" label="已过期" />
        </el-select>
        <span class="spacer" />
        <PermButton perm="app:create" type="primary" icon="el-icon-plus" @click="openCreate">新建应用</PermButton>
      </template>

      <template #appKey="{ row }">
        <code class="mono">{{ row.appKey || '—' }}</code>
      </template>
      <template #status="{ row }">
        <StatusTag entity="app" :value="row.status" />
      </template>
      <template #actions="{ row }">
        <PermButton perm="" type="text" @click="openDetail(row)">详情</PermButton>
        <PermButton perm="" type="text" @click="downloadDoc(row)">接入文档</PermButton>
        <PermButton perm="app:update" type="text" @click="openEdit(row)">编辑</PermButton>
        <PermButton perm="app:disable" type="text" @click="toggleStatus(row)">{{ row.status === 1 ? '停用' : '启用' }}</PermButton>
        <PermButton perm="app:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
      </template>
    </CrudTable>

    <!-- 应用详情抽屉：密钥凭证 / 授权接口 / 配额 / IP 白名单 4 Tab -->
    <el-drawer title="应用详情" :visible.sync="drawerVisible" direction="rtl" size="64%">
      <div v-if="currentApp" class="drawer-head">
        <div class="dh-name">{{ currentApp.appName }}</div>
        <code class="mono dh-key">{{ currentApp.appKey }}</code>
        <StatusTag entity="app" :value="currentApp.status" />
        <span class="dh-spacer" />
        <el-button size="mini" plain icon="el-icon-download" @click="downloadDoc(currentApp)">接入文档</el-button>
      </div>
      <el-tabs v-model="activeTab" class="detail-tabs">
        <el-tab-pane label="密钥凭证" name="cred"><CredentialTab :app-id="currentAppId" /></el-tab-pane>
        <el-tab-pane label="授权接口" name="grant">
          <div class="grant-tip">
            <p>应用的接口授权在「接口授权总览」中统一管理（按应用 + 环境 + 接口维度授权）。</p>
            <PermButton perm="grant:list" type="primary" @click="goGrant">前往授权总览</PermButton>
          </div>
        </el-tab-pane>
        <el-tab-pane label="配额" name="quota"><QuotaTab :app-id="currentAppId" /></el-tab-pane>
        <el-tab-pane label="IP 白名单" name="ip"><IpWhitelistTab :app-id="currentAppId" /></el-tab-pane>
      </el-tabs>
    </el-drawer>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="560px"
      @submit="submit"
    />

    <!-- T14 创建成功后的「接入材料」弹窗：让用户建完应用立刻拿到能交付给调用方的东西 -->
    <el-dialog title="应用已创建" :visible.sync="createdVisible" width="560px" :close-on-click-modal="false" append-to-body>
      <el-alert type="success" :closable="false" show-icon title="应用已创建，且已自动生成 AppKey" />
      <div class="created-body">
        <div class="cb-row"><span class="cb-k">应用名称</span><span>{{ createdApp.appName }}</span></div>
        <div class="cb-row"><span class="cb-k">AppKey</span><code class="mono">{{ createdApp.appKey || '—' }}</code></div>
      </div>
      <el-alert type="warning" :closable="false" show-icon>
        <template #title>
          真正用于调用网关的密钥在「详情 → 密钥凭证」里按环境创建；AppSecret 明文只在创建凭证 /
          灰度轮换时展示一次，之后可在凭证列表用「查看密钥」+ 当前账号密码二次确认再次查看。
        </template>
      </el-alert>
      <template #footer>
        <el-button @click="createdVisible = false">关闭</el-button>
        <el-button type="primary" icon="el-icon-download" @click="downloadDoc(createdApp)">下载接入文档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 应用列表管理页（T05 Phase 1 · 应用管理）
 * 列表对接 /app/list（分页，current/size）；详情抽屉含 4 个 Tab：
 *   密钥凭证(app-credential) / 授权接口(grant) / 配额(rate-limit) / IP 白名单(ip-whitelist)
 * 注意：后端 App 实体字段为 appName/appKey/appSecret/status/description/expireTime，
 *       与原型计划的 appCode/lineId/appType/ownerName/envScope 等字段不同，表单以真实后端字段为准。
 */
import { getAppList, createApp, updateApp, updateAppStatus, deleteApp } from '@/api/modules'
// T14：接入文档的正文与「页面右侧展示的那份」共用同一来源（@/utils/accessDoc），
// 避免导出文件里写的签名算法与页面讲的不是一回事
import { exportAccessDoc } from '@/utils/accessDoc'
import StatusTag from '@/components/common/StatusTag.vue'
import CredentialTab from './tabs/CredentialTab.vue'
import QuotaTab from './tabs/QuotaTab.vue'
import IpWhitelistTab from './tabs/IpWhitelistTab.vue'

export default {
  name: 'AppList',
  components: { StatusTag, CredentialTab, QuotaTab, IpWhitelistTab },
  data() {
    return {
      query: { kw: '', status: '' },
      columns: [
        { prop: 'appName', label: '应用名称', minWidth: 140 },
        { prop: 'appKey', label: 'AppKey', minWidth: 200, slot: 'appKey' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'description', label: '描述', minWidth: 180, showOverflowTooltip: true },
        { prop: 'expireTime', label: '过期时间', width: 160, formatter: (v) => (v ? this.fmtTime(v) : '永不过期') },
        { prop: 'createdAt', label: '创建时间', width: 160, formatter: (v) => this.fmtTime(v) }
      ],
      drawerVisible: false,
      activeTab: 'cred',
      currentApp: null,
      // T14：创建成功后的接入材料弹窗
      createdVisible: false,
      createdApp: {},
      dialogVisible: false,
      dialogTitle: '新建应用',
      submitting: false,
      form: {},
      fields: [
        { prop: 'appName', label: '应用名称', type: 'input', required: true, maxlength: 64, span: 24 },
        { prop: 'status', label: '状态', type: 'select', required: true, options: [{ value: 1, label: '已启用' }, { value: 0, label: '已停用' }, { value: 2, label: '已过期' }], span: 12 },
        // expireTime 后端是 LocalDateTime（ISO 输出 2026-09-10T14:11:04）。
        // 原 type:'date' 会送出 yyyy-MM-dd，后端反序列化失败 ⇒ 一旦填了过期时间就 500，
        // 且回填时 el-date-picker 无法按 yyyy-MM-dd 解析 ISO 串，控件显示为空。
        { prop: 'expireTime', label: '过期时间', type: 'datetime', valueFormat: 'yyyy-MM-ddTHH:mm:ss', span: 12 },
        { prop: 'description', label: '描述', type: 'textarea', span: 24, maxlength: 200 }
      ]
    }
  },
  computed: {
    currentAppId() { return this.currentApp ? this.currentApp.id : null }
  },
  methods: {
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    onLoaded() {},
    async fetchApps(params) {
      const { page, size, ...rest } = params
      const q = { current: page, size }
      if (rest.kw) q.appName = rest.kw
      if (rest.status !== '' && rest.status != null) q.status = rest.status
      return getAppList(q)
    },
    openDetail(row) {
      this.currentApp = row
      this.activeTab = 'cred'
      this.drawerVisible = true
    },
    openCreate() {
      this.dialogTitle = '新建应用'
      this.form = { appName: '', status: 1, expireTime: '', description: '' }
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '编辑应用'
      this.form = { ...row }
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form }
        if (payload.id) {
          await updateApp(payload.id, payload)
          this.$message.success('应用已更新')
        } else {
          const res = await createApp(payload)
          const created = (res && res.data) || {}
          this.$message.success('应用已创建（已自动生成 AppKey）')
          // T14：建完立刻把「可交付给调用方的东西」摆出来（AppKey + 一键下载接入文档）。
          // ⚠️ 刻意不把 createApp 响应里的明文 app.appSecret 写进文档：
          //    网关鉴权走的是 app_credential（按环境），legacy 的 app.app_secret 不是调用凭证，
          //    写进文件只会误导调用方。
          this.createdApp = created
          this.createdVisible = true
        }
        this.dialogVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    toggleStatus(row) {
      const next = row.status === 1 ? 0 : 1
      const tip = next === 0 ? '停用' : '启用'
      this.$confirm(`确认${tip}应用「${row.appName}」？`, `${tip}确认`, { type: 'warning' }).then(async () => {
        try {
          await updateAppStatus(row.id, next)
          this.$message.success(`已${tip}`)
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    remove(row) {
      this.$confirm(`确认删除应用「${row.appName}」？其密钥与授权将一并失效。`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await deleteApp(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    goGrant() {
      this.$router.push('/perm/perm-matrix')
    },
    /**
     * 导出该应用的接入文档（Markdown）。
     * 纯前端生成：文档正文来自 @/utils/accessDoc，与本应用无关的内容不会带出去。
     */
    downloadDoc(app) {
      if (!app || !app.id) return
      try {
        const name = exportAccessDoc(app)
        this.$message.success('已导出 ' + name)
      } catch (e) {
        this.$message.error('导出失败：' + (e && e.message ? e.message : '未知错误'))
      }
    }
  }
}
</script>

<style scoped>
.mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; color: #17233d; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
.drawer-head { display: flex; align-items: center; gap: 12px; padding: 0 4px 12px; border-bottom: 1px solid #eef1f7; margin-bottom: 8px; }
.dh-spacer { flex: 1; }
.dh-name { font-size: 15px; font-weight: 600; color: #17233d; }
.dh-key { color: #5c6b8a; }
.detail-tabs { margin-top: 4px; }
.grant-tip { padding: 16px; color: #5c6b8a; line-height: 1.8; }
.grant-tip p { margin: 0 0 12px; }
/* T14 创建成功弹窗 */
.created-body { margin: 14px 0; }
.cb-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 13px; color: #17233d; }
.cb-k { width: 76px; color: #7d93b8; flex: none; }
</style>
