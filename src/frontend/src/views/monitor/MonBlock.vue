<template>
  <div class="page-container mon-block">
    <div class="page-head">
      <h2>封禁管理</h2>
      <span class="page-tag">动态封禁规则 + 封禁名单 · 手动封禁高危</span>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ① 封禁规则 -->
      <el-tab-pane label="封禁规则" name="rule">
        <div class="toolbar">
          <span class="toolbar-tip">共 {{ ruleTotal }} 条规则</span>
          <span class="spacer" />
          <PermButton perm="block_rule:create" type="primary" size="small" icon="el-icon-plus" @click="onCreateRule">新建规则</PermButton>
        </div>
        <CrudTable ref="ruleTable" :columns="ruleColumns" :fetch="fetchRules" :show-pagination="false" :actions-width="210">
          <template #scope="{row}">
            <el-tag size="small" effect="plain" :type="row.scope === 'APP' ? 'warning' : 'info'">{{ scopeLabel(row.scope) }}</el-tag>
          </template>
          <template #reasonCode="{row}">
            <span>{{ reasonLabel(row.reasonCode) }}</span>
          </template>
          <template #ttlSeconds="{row}">
            <span>{{ ttlLabel(row.ttlSeconds) }}</span>
          </template>
          <template #autoBlock="{row}">
            <el-tag size="small" effect="plain" :type="row.autoBlock === 1 ? 'danger' : 'info'">{{ row.autoBlock === 1 ? '自动' : '手动' }}</el-tag>
          </template>
          <template #enabled="{row}">
            <StatusTag :entity="'blockRule'" :value="row.enabled" />
          </template>
          <template #actions="{row}">
            <PermButton perm="block_rule:create" type="text" size="mini" @click="onEditRule(row)">编辑</PermButton>
            <PermButton perm="block_rule:create" type="text" size="mini" @click="onToggleRule(row)">{{ row.enabled === 1 ? '停用' : '启用' }}</PermButton>
            <PermButton perm="block_rule:manual" type="text" size="mini" class="danger-link" @click="onManualBlock(row)">手动封禁</PermButton>
          </template>
        </CrudTable>
      </el-tab-pane>

      <!-- ② 封禁名单 -->
      <el-tab-pane label="封禁名单" name="list">
        <div class="toolbar">
          <span class="toolbar-tip">共 {{ banTotal }} 条封禁记录</span>
          <span class="spacer" />
          <PermButton perm="block_rule:manual" type="primary" size="small" icon="el-icon-plus" @click="onCreateBan">新建封禁</PermButton>
        </div>
        <CrudTable ref="banTable" :columns="banColumns" :fetch="fetchBans" :page-size="20" :actions-width="100">
          <template #banStatus="{row}">
            <StatusTag :entity="'ipBan'" :value="row.banStatus" />
          </template>
          <template #banType="{row}">
            <el-tag size="small" effect="plain" :type="row.banType === 'AUTO' ? 'warning' : 'info'">{{ row.banType === 'AUTO' ? '自动' : '手动' }}</el-tag>
          </template>
          <template #actions="{row}">
            <PermButton v-if="row.banStatus === 1" perm="block_rule:manual" type="text" size="mini" @click="onUnban(row)">解封</PermButton>
            <span v-else class="done-text">已解封</span>
          </template>
        </CrudTable>
      </el-tab-pane>
    </el-tabs>

    <!-- 新建/编辑封禁规则 -->
    <CrudDialog
      :visible.sync="ruleDialog.visible"
      :title="ruleDialog.form.id ? '编辑封禁规则' : '新建封禁规则'"
      :model="ruleDialog.form"
      :fields="ruleDialog.fields"
      :rules="ruleDialog.rules"
      :width="'660px'"
      :loading="ruleDialog.loading"
      @submit="onSubmitRule"
    >
      <template #extra>
        <el-alert type="warning" :closable="false" show-icon>
          <template #title>新建规则默认停用，确认阈值后请手动启用，避免误封。</template>
        </el-alert>
      </template>
    </CrudDialog>

    <!-- 新建封禁 -->
    <CrudDialog
      :visible.sync="banDialog.visible"
      title="新建封禁"
      :model="banDialog.form"
      :fields="banDialog.fields"
      :rules="banDialog.rules"
      :width="'520px'"
      :loading="banDialog.loading"
      @submit="onSubmitBan"
    />
  </div>
</template>

<script>
import {
  getBlockRuleList,
  createBlockRule,
  updateBlockRule,
  toggleBlockRule,
  manualBlock,
  getBanList,
  banIp,
  unbanIp
} from '@/api/modules'

const SCOPES = [{ value: 'IP', label: 'IP' }, { value: 'APP', label: '应用/AppKey' }]
const REASONS = [
  { value: 'REPLAY_ATTACK', label: '重放攻击' },
  { value: 'SIGNATURE_MISMATCH', label: '签名不匹配' },
  { value: 'RATE_LIMIT_EXCEEDED', label: '超过限流阈值' },
  { value: 'IP_NOT_ALLOWED', label: 'IP 不在白名单' },
  { value: 'MANUAL', label: '人工封禁' }
]
const REASON_LABEL = REASONS.reduce((acc, r) => { acc[r.value] = r.label; return acc }, {})

export default {
  name: 'MonBlock',
  data() {
    return {
      activeTab: 'rule',
      ruleTotal: 0,
      banTotal: 0,
      ruleColumns: [
        { prop: 'scope', label: '层级', width: 120, slot: 'scope' },
        { prop: 'reasonCode', label: '触发原因', width: 140, slot: 'reasonCode' },
        { prop: 'thresholdDesc', label: '阈值描述', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'thresholdCount', label: '次数', width: 80, align: 'right', formatter: (v) => (v == null ? '—' : v) },
        { prop: 'windowMinutes', label: '窗口(分)', width: 90, align: 'right', formatter: (v) => (v == null ? '—' : v) },
        { prop: 'ttlSeconds', label: '时长', width: 100, slot: 'ttlSeconds' },
        { prop: 'autoBlock', label: '触发', width: 90, slot: 'autoBlock' },
        { prop: 'enabled', label: '状态', width: 90, slot: 'enabled' }
      ],
      banColumns: [
        { prop: 'ipAddress', label: '对象', width: 160, showOverflowTooltip: true },
        { prop: 'appId', label: '关联应用ID', width: 110, formatter: (v) => (v == null ? '全局' : v) },
        { prop: 'banType', label: '类型', width: 90, slot: 'banType' },
        { prop: 'banReason', label: '原因', minWidth: 160, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'banStartTime', label: '开始时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'banEndTime', label: '结束时间', width: 170, formatter: (v) => v || '永久' },
        { prop: 'banStatus', label: '状态', width: 100, slot: 'banStatus' }
      ],
      ruleDialog: { visible: false, loading: false, form: {}, fields: [], rules: {} },
      banDialog: { visible: false, loading: false, form: {}, fields: [], rules: {} }
    }
  },
  methods: {
    async fetchBans(params) {
      const { page, size } = params
      const res = await getBanList({ current: page, size })
      const data = (res && res.data) || {}
      this.banTotal = data.total || 0
      return { list: data.records || [], total: data.total || 0 }
    },
    async fetchRules() {
      const res = await getBlockRuleList()
      const list = (res && res.data) || []
      this.ruleTotal = list.length
      return { list, total: list.length }
    },
    scopeLabel(s) {
      return s === 'APP' ? '应用/AppKey' : 'IP'
    },
    reasonLabel(r) {
      return REASON_LABEL[r] || r || '—'
    },
    ttlLabel(sec) {
      if (sec == null) return '—'
      if (sec === 0) return '永久'
      if (sec < 60) return sec + ' 秒'
      if (sec < 3600) return Math.round(sec / 60) + ' 分钟'
      return Math.round(sec / 3600) + ' 小时'
    },
    reloadRules() {
      this.$nextTick(() => {
        if (this.$refs.ruleTable && this.$refs.ruleTable.reload) this.$refs.ruleTable.reload()
      })
    },
    reloadBans() {
      this.$nextTick(() => {
        if (this.$refs.banTable && this.$refs.banTable.reload) this.$refs.banTable.reload()
      })
    },
    buildRuleFields() {
      return [
        { prop: 'scope', label: '封禁层级', type: 'select', required: true, span: 12, options: SCOPES },
        { prop: 'reasonCode', label: '触发原因', type: 'select', required: true, span: 12, options: REASONS },
        { prop: 'thresholdDesc', label: '阈值描述', type: 'input', span: 12, placeholder: '如：同IP 5min ≥ 20次' },
        { prop: 'thresholdCount', label: '阈值次数', type: 'number', span: 12, min: 1, step: 1 },
        { prop: 'windowMinutes', label: '统计窗口(分)', type: 'number', span: 12, min: 1, step: 1 },
        { prop: 'ttlSeconds', label: '封禁时长(秒)', type: 'number', span: 12, min: 0, step: 60, placeholder: '0=永久' },
        { prop: 'autoBlock', label: '自动封禁', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 },
        { prop: 'enabled', label: '启用', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 },
        { prop: 'description', label: '说明', type: 'textarea', span: 24, rows: 2, placeholder: '可选' }
      ]
    },
    buildRuleRules() {
      return {
        scope: [{ required: true, message: '封禁层级不能为空', trigger: 'change' }],
        reasonCode: [{ required: true, message: '触发原因不能为空', trigger: 'change' }]
      }
    },
    onCreateRule() {
      this.ruleDialog.form = { scope: 'IP', reasonCode: 'REPLAY_ATTACK', thresholdDesc: '', thresholdCount: 20, windowMinutes: 5, ttlSeconds: 3600, autoBlock: 0, enabled: 0, description: '' }
      this.ruleDialog.fields = this.buildRuleFields()
      this.ruleDialog.rules = this.buildRuleRules()
      this.ruleDialog.visible = true
    },
    onEditRule(row) {
      this.ruleDialog.form = { ...row }
      this.ruleDialog.fields = this.buildRuleFields()
      this.ruleDialog.rules = this.buildRuleRules()
      this.ruleDialog.visible = true
    },
    async onSubmitRule(form) {
      this.ruleDialog.loading = true
      try {
        if (form.id) {
          await updateBlockRule(form.id, form)
          this.$message.success('规则已更新')
        } else {
          await createBlockRule(form)
          this.$message.success('规则已创建（默认停用）')
        }
        this.ruleDialog.visible = false
        this.reloadRules()
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.ruleDialog.loading = false
      }
    },
    onToggleRule(row) {
      const next = row.enabled === 1 ? 0 : 1
      this.$confirm(`确认${next === 1 ? '启用' : '停用'}该封禁规则？`, '状态变更', { type: 'warning' })
        .then(async() => {
          try {
            await toggleBlockRule(row.id, next)
            this.$message.success('状态已更新')
            this.reloadRules()
          } catch (e) {}
        }).catch(() => {})
    },
    onManualBlock(row) {
      this.$prompt('请输入封禁目标（IP 或 AppKey）', '人工封禁（高危）', {
        inputValidator: (v) => (v ? true : '封禁目标不能为空')
      }).then(async({ value }) => {
        try {
          await manualBlock(row.id, { target: value, ttlSeconds: row.ttlSeconds || 3600, reason: '人工封禁' })
          this.$message.success('已封禁')
          this.reloadBans()
        } catch (e) {}
      }).catch(() => {})
    },
    buildBanFields() {
      return [
        { prop: 'ipAddress', label: '封禁对象', type: 'input', required: true, span: 24, placeholder: 'IP 地址' },
        { prop: 'appId', label: '关联应用ID', type: 'number', span: 12, min: 1, step: 1, placeholder: '留空=全局封禁' },
        { prop: 'durationMin', label: '封禁时长(分)', type: 'number', span: 12, min: 1, step: 1, placeholder: '分钟' },
        { prop: 'reason', label: '封禁原因', type: 'textarea', span: 24, rows: 2, placeholder: '封禁原因说明' }
      ]
    },
    buildBanRules() {
      return {
        ipAddress: [{ required: true, message: '封禁对象不能为空', trigger: 'blur' }]
      }
    },
    onCreateBan() {
      this.banDialog.form = { ipAddress: '', appId: undefined, durationMin: 60, reason: '' }
      this.banDialog.fields = this.buildBanFields()
      this.banDialog.rules = this.buildBanRules()
      this.banDialog.visible = true
    },
    async onSubmitBan(form) {
      this.banDialog.loading = true
      try {
        await banIp(form)
        this.$message.success('已封禁')
        this.banDialog.visible = false
        this.reloadBans()
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.banDialog.loading = false
      }
    },
    onUnban(row) {
      this.$confirm(`确认解封「${row.ipAddress}」？`, '解封确认', { type: 'warning' })
        .then(async() => {
          try {
            await unbanIp(row.id)
            this.$message.success('已解封')
            this.reloadBans()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.mon-block .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.mon-block .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.mon-block .toolbar .spacer { flex: 1; }
.mon-block .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.mon-block .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.mon-block .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.mon-block .done-text { color: #9aa7bf; font-size: 12px; }
</style>
