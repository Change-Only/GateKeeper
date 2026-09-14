<template>
  <div class="api-tab">
    <div class="tab-toolbar">
      <PermButton perm="app_credential:create" type="primary" size="small" icon="el-icon-plus" @click="openCreate">创建凭证</PermButton>
      <span class="tab-hint">每个环境独立凭证；创建 / 灰度轮换仅返回一次明文 AppSecret，请妥善保存</span>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchCredentials"
      :query="query"
      row-key="id"
      :show-pagination="false"
    >
      <template #envCode="{ row }">
        <span>{{ envLabel(row.envCode) }}</span>
      </template>
      <template #status="{ row }">
        <StatusTag entity="credential" :value="row.status" />
      </template>
      <template #rotateFlag="{ row }">
        <el-tag v-if="row.rotateFlag === 1" type="warning" size="small">轮换中</el-tag>
        <span v-else>—</span>
      </template>
      <template #actions="{ row }">
        <PermButton perm="app_credential:rotate" type="text" @click="rotate(row)">灰度轮换</PermButton>
        <PermButton perm="app_credential:complete" type="text" @click="completeRotate(row)">完成轮换</PermButton>
        <PermButton perm="app:credential:revoke" type="text" class="danger-link" @click="revoke(row)">吊销</PermButton>
        <PermButton perm="app_credential:update" type="text" @click="openEdit(row)">改别名</PermButton>
      </template>
    </CrudTable>

    <!-- 新建 / 编辑凭证 -->
    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="520px"
      @submit="submit"
    />

    <!-- 明文密钥展示（仅创建 / 轮换时返回一次）
         append-to-body 必加：本 Tab 位于「应用详情」抽屉内，抽屉的 .el-drawer__wrapper
         是 position:fixed + z-index 自成的层叠上下文；弹窗若内联渲染会被困在里面，
         被 Element 的单例遮罩 .v-modal（z 跟随顶层弹窗、挂在 body 上）整片压住。 -->
    <el-dialog title="密钥已生成（请立即保存）" :visible.sync="secretVisible" width="520px" :close-on-click-modal="false" append-to-body>
      <el-alert type="warning" :closable="false" show-icon title="AppSecret 明文仅在此展示一次，关闭后不可再查看">
        <template #title>AppSecret 明文仅在此展示一次</template>
      </el-alert>
      <el-form label-width="110px" class="secret-form">
        <el-form-item label="环境"><span>{{ envLabel(secretInfo.envCode) }}</span></el-form-item>
        <el-form-item label="AppKey"><el-input :value="secretInfo.appKey" readonly><el-button slot="append" @click="copy(secretInfo.appKey)">复制</el-button></el-input></el-form-item>
        <el-form-item label="AppSecret"><el-input type="textarea" :rows="2" :value="secretInfo.appSecret" readonly><el-button slot="append" @click="copy(secretInfo.appSecret)">复制</el-button></el-input></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 应用密钥凭证 Tab（T05 Phase 1 · app-list 详情）
 * 对接 /app-credential/* ：list/create/rotate/complete-rotate/revoke/update。
 * appSecret 明文仅在 create / rotate 响应中返回一次，通过专用弹窗展示。
 */
import { getAppCredentialList, createAppCredential, rotateAppCredential, completeRotateAppCredential, revokeAppCredential, updateAppCredential } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'

export default {
  name: 'CredentialTab',
  components: { StatusTag },
  props: {
    appId: { type: [Number, String], default: null }
  },
  data() {
    return {
      query: { appId: this.appId },
      columns: [
        { prop: 'alias', label: '别名', minWidth: 120 },
        { prop: 'envCode', label: '环境', width: 100, slot: 'envCode' },
        { prop: 'appKey', label: 'AppKey', minWidth: 160, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'rotateFlag', label: '轮换', width: 80, slot: 'rotateFlag' },
        // expireTime / lastUsedTime 后端是 LocalDateTime，按 ISO 输出（2026-09-10T14:11:04），
        // 直接展示会带 T，故统一走 fmtTime 转成可读形式
        { prop: 'expireTime', label: '过期时间', width: 150, formatter: (v) => (v ? this.fmtTime(v) : '永不过期') },
        { prop: 'lastUsedTime', label: '最近使用', width: 150, formatter: (v) => (v ? this.fmtTime(v) : '—') }
      ],
      dialogVisible: false,
      dialogTitle: '创建凭证',
      submitting: false,
      form: {},
      fields: [],
      secretVisible: false,
      secretInfo: { envCode: '', appKey: '', appSecret: '' }
    }
  },
  watch: {
    appId(v) { this.query = { appId: v } }
  },
  methods: {
    envLabel(code) {
      const e = ENV_LIST.find((x) => x.code === code)
      return e ? e.label : code
    },
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async fetchCredentials() {
      const res = await getAppCredentialList({ appId: this.appId })
      const list = res.data || []
      return { list, total: list.length }
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    buildFields(isEdit) {
      if (isEdit) {
        return [
          { prop: 'alias', label: '别名', type: 'input', required: true, maxlength: 128, span: 24 },
          { prop: 'expireTime', label: '过期时间', type: 'datetime', valueFormat: 'yyyy-MM-ddTHH:mm:ss', span: 24 }
        ]
      }
      return [
        { prop: 'alias', label: '别名', type: 'input', required: true, maxlength: 128, span: 24, placeholder: '如「生产-主密钥」' },
        { prop: 'envCode', label: '环境', type: 'select', required: true, options: ENV_LIST.map((e) => ({ value: e.code, label: e.label })), span: 24 },
        // expireTime 后端是 LocalDateTime（ISO），值格式必须带 T：
        // 原 type:'date' 会送出 yyyy-MM-dd，后端反序列化失败 ⇒ 填了过期时间就 500
        { prop: 'expireTime', label: '过期时间', type: 'datetime', valueFormat: 'yyyy-MM-ddTHH:mm:ss', span: 24 }
      ]
    },
    openCreate() {
      this.dialogTitle = '创建凭证'
      this.form = { alias: '', envCode: '', expireTime: '' }
      this.fields = this.buildFields(false)
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '修改别名 / 过期时间'
      this.form = { ...row }
      this.fields = this.buildFields(true)
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form, appId: this.appId }
        if (payload.id) {
          await updateAppCredential(payload.id, payload)
          this.$message.success('已更新')
          this.dialogVisible = false
          this.reload()
        } else {
          const res = await createAppCredential(payload)
          this.dialogVisible = false
          const dto = res.data || {}
          this.secretInfo = { envCode: dto.envCode, appKey: dto.appKey, appSecret: dto.appSecret }
          this.secretVisible = true
          this.reload()
        }
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    async rotate(row) {
      try {
        // 后端接 @RequestBody CredentialRotateRequest{ appId, envCode }，不是 path 上的 id
        const res = await rotateAppCredential({ appId: row.appId, envCode: row.envCode })
        const dto = res.data || {}
        this.secretInfo = { envCode: dto.envCode, appKey: dto.appKey, appSecret: dto.appSecret }
        this.secretVisible = true
        this.$message.success('已发起灰度轮换')
        this.reload()
      } catch (e) { /* 拦截器已提示 */ }
    },
    completeRotate(row) {
      this.$confirm('确认完成轮换？新密钥转正、旧密钥作废。', '完成轮换', { type: 'warning' }).then(async () => {
        try {
          await completeRotateAppCredential({ appId: row.appId, envCode: row.envCode })
          this.$message.success('轮换完成')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    revoke(row) {
      this.$confirm(`确认吊销凭证「${row.alias || row.envCode}」？吊销后该环境凭证立即失效。`, '吊销确认', { type: 'warning' }).then(async () => {
        try {
          await revokeAppCredential(row.id)
          this.$message.success('已吊销')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    copy(text) {
      if (!text) return
      const input = document.createElement('textarea')
      input.value = text
      document.body.appendChild(input)
      input.select()
      try { document.execCommand('copy'); this.$message.success('已复制') } catch (e) { /* noop */ }
      document.body.removeChild(input)
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
.secret-form { margin-top: 12px; }
</style>
