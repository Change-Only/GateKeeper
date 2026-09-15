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
        <!-- T14 二次查看：走 app_credential:rotate 同码权限（明文暴露面与轮换完全相同，详见后端注释） -->
        <PermButton perm="app_credential:rotate" type="text" @click="openReveal(row)">查看密钥</PermButton>
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

    <!-- T14 二次查看密钥：先输入「当前登录账号」的密码做二次确认
         append-to-body 必加（与下面的密钥弹窗同理）：本 Tab 位于「应用详情」抽屉内，
         抽屉自成层叠上下文，内联渲染的弹窗会被 Element 的单例遮罩整片压住、点不动。 -->
    <el-dialog title="查看密钥 · 身份确认" :visible.sync="pwdVisible" width="440px" :close-on-click-modal="false" append-to-body>
      <el-alert type="warning" :closable="false" show-icon
                title="密钥属敏感信息，请输入「当前登录账号」的密码以确认身份" />
      <el-form ref="pwdForm" :model="pwdForm" :rules="pwdRules" label-width="90px" class="secret-form" @submit.native.prevent>
        <el-form-item label="当前账号">
          <span class="pwd-user">{{ currentUsername }}</span>
        </el-form-item>
        <el-form-item label="登录密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password
                    placeholder="请输入当前账号密码" autocomplete="off"
                    @keyup.enter.native="submitReveal" />
        </el-form-item>
        <div class="pwd-target">
          即将查看：<b>{{ pwdTarget ? (pwdTarget.alias || pwdTarget.envCode) : '' }}</b>
          （{{ pwdTarget ? envLabel(pwdTarget.envCode) : '' }}）
        </div>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSubmitting" @click="submitReveal">确认查看</el-button>
      </template>
    </el-dialog>

    <!-- 明文密钥展示（创建 / 轮换返回一次；T14 起也可经密码二次确认再次查看）
         append-to-body 必加：本 Tab 位于「应用详情」抽屉内，抽屉的 .el-drawer__wrapper
         是 position:fixed + z-index 自成的层叠上下文；弹窗若内联渲染会被困在里面，
         被 Element 的单例遮罩 .v-modal（z 跟随顶层弹窗、挂在 body 上）整片压住。 -->
    <el-dialog :title="secretTitle" :visible.sync="secretVisible" width="520px" :close-on-click-modal="false" append-to-body>
      <el-alert :type="secretIsReveal ? 'success' : 'warning'" :closable="false" show-icon>
        <template #title>{{ secretNotice }}</template>
      </el-alert>
      <el-form label-width="110px" class="secret-form">
        <el-form-item label="环境"><span>{{ envLabel(secretInfo.envCode) }}</span></el-form-item>
        <el-form-item label="AppKey"><el-input :value="secretInfo.appKey" readonly><el-button slot="append" @click="copy(secretInfo.appKey)">复制</el-button></el-input></el-form-item>
        <el-form-item label="AppSecret"><el-input type="textarea" :rows="2" :value="secretInfo.appSecret" readonly><el-button slot="append" @click="copy(secretInfo.appSecret)">复制</el-button></el-input></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="secretVisible = false">{{ secretIsReveal ? '关闭' : '我已保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 应用密钥凭证 Tab（T05 Phase 1 · app-list 详情）
 * 对接 /app-credential/* ：list/create/rotate/complete-rotate/revoke/update/reveal。
 * appSecret 明文窗口有 3 个：create、rotate（各返回一次）、reveal（T14 新增，
 * 需先输入「当前登录账号」的密码做二次确认）。四处出口共用同一个明文弹窗。
 */
import { getAppCredentialList, createAppCredential, rotateAppCredential, completeRotateAppCredential, revokeAppCredential, updateAppCredential, revealAppCredential } from '@/api/modules'
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
      secretInfo: { envCode: '', appKey: '', appSecret: '' },
      // 密钥弹窗的两种文案：create/rotate 是「只此一次」，reveal 是「已二次确认」
      secretTitle: '密钥已生成（请立即保存）',
      secretNotice: 'AppSecret 明文仅在此展示一次，关闭后不可再查看',
      secretIsReveal: false,
      // T14 二次查看的密码确认
      pwdVisible: false,
      pwdSubmitting: false,
      pwdForm: { password: '' },
      pwdTarget: null,
      pwdRules: {
        password: [{ required: true, message: '请输入当前账号密码', trigger: 'blur' }]
      }
    }
  },
  computed: {
    /** 当前登录账号名（仅作提示，真正校验在后端） */
    currentUsername() {
      try {
        const u = JSON.parse(localStorage.getItem('gatekeeper_user') || 'null')
        return (u && (u.username || u.realName)) || '当前登录账号'
      } catch (e) {
        return '当前登录账号'
      }
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
          this.showSecret(res.data, false)
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
        this.showSecret(res.data, false)
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
    // ===== T14 二次查看密钥 =====

    /** 打开身份确认弹窗 */
    openReveal(row) {
      this.pwdTarget = row
      this.pwdForm = { password: '' }
      this.pwdVisible = true
      this.$nextTick(() => {
        if (this.$refs.pwdForm) this.$refs.pwdForm.clearValidate()
      })
    },
    /** 提交密码 → 后端校验通过后返回明文密钥 */
    submitReveal() {
      const form = this.$refs.pwdForm
      if (!form) return
      form.validate(async (valid) => {
        if (!valid) return
        this.pwdSubmitting = true
        try {
          const res = await revealAppCredential(this.pwdTarget.id, { password: this.pwdForm.password })
          this.pwdVisible = false
          this.showSecret(res.data, true)
        } catch (e) {
          // 拦截器已提示（密码错误时后端回 code=400 —— 刻意不用 401，否则会被当成登录过期踢下线）
          this.pwdForm.password = ''
        } finally {
          this.pwdSubmitting = false
        }
      })
    },
    /**
     * 统一的明文密钥弹窗。
     * @param {object} dto      后端返回的凭证 DTO
     * @param {boolean} isReveal true=二次查看（文案与告警级别不同）
     */
    showSecret(dto, isReveal) {
      const d = dto || {}
      this.secretInfo = { envCode: d.envCode, appKey: d.appKey, appSecret: d.appSecret }
      this.secretIsReveal = !!isReveal
      this.secretTitle = isReveal ? '密钥详情（已通过密码二次确认）' : '密钥已生成（请立即保存）'
      this.secretNotice = isReveal
        ? '本次查看已记入操作审计；请勿截屏外传，用完请及时关闭'
        : 'AppSecret 明文仅在此展示一次，关闭后不可再查看'
      this.secretVisible = true
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
.pwd-user { font-weight: 600; color: #17233d; }
.pwd-target { margin: -4px 0 0 90px; font-size: 12px; color: #7d93b8; }
.pwd-target b { color: #17233d; }
</style>
