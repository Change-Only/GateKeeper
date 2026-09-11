<template>
  <div class="page-container sys-security">
    <div class="page-head">
      <h2>安全策略</h2>
      <span class="page-tag">安全检测规则 + 安全事件处置 · 规则写操作高危</span>
    </div>

    <el-tabs v-model="activeTab">
      <!-- ① 安全检测规则 -->
      <el-tab-pane label="安全检测规则" name="rule">
        <div class="toolbar">
          <span class="toolbar-tip">共 {{ ruleTotal }} 条规则</span>
          <span class="spacer" />
          <PermButton perm="sys:security:update" type="primary" size="small" icon="el-icon-plus" @click="onCreateRule">新增规则</PermButton>
        </div>
        <CrudTable ref="ruleTable" :columns="ruleColumns" :fetch="fetchRules" :show-pagination="false" :actions-width="150">
          <template #ruleType="{row}">
            <el-tag size="small" effect="plain" :type="typeTag(row.ruleType)">{{ row.ruleType }}</el-tag>
          </template>
          <template #triggerAction="{row}">
            <el-tag size="small" :type="actionTag(row.triggerAction)">{{ actionLabel(row.triggerAction) }}</el-tag>
          </template>
          <template #enabled="{row}">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
          <template #actions="{row}">
            <PermButton perm="sys:security:update" type="text" size="mini" @click="onEditRule(row)">编辑</PermButton>
            <PermButton perm="sys:security:update" type="text" size="mini" class="danger-link" @click="onDeleteRule(row)">删除</PermButton>
          </template>
        </CrudTable>
      </el-tab-pane>

      <!-- ② 安全事件 -->
      <el-tab-pane label="安全事件" name="event">
        <div class="toolbar">
          <span class="toolbar-tip">共 {{ eventTotal }} 条事件</span>
          <span class="spacer" />
          <el-select v-model="eventQuery.handleStatus" placeholder="全部状态" clearable size="small" style="width:150px" @change="reloadEvents">
            <el-option :value="0" label="待处理" />
            <el-option :value="1" label="已处理" />
            <el-option :value="2" label="已忽略" />
          </el-select>
        </div>
        <CrudTable ref="eventTable" :columns="eventColumns" :fetch="fetchEvents" :query="eventQuery" :actions-width="120">
          <template #handleStatus="{row}">
            <el-tag size="small" :type="eventStatusTag(row.handleStatus)" effect="plain">{{ eventStatusLabel(row.handleStatus) }}</el-tag>
          </template>
          <template #actions="{row}">
            <PermButton v-if="row.handleStatus === 0" perm="alarm:handle" type="text" size="mini" @click="onHandleEvent(row)">处理</PermButton>
            <span v-else class="done-text">已办结</span>
          </template>
        </CrudTable>
      </el-tab-pane>
    </el-tabs>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑安全规则' : '新增安全规则'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'640px'"
      :loading="dialog.loading"
      @submit="onSubmitRule"
    >
      <template #extra="{form}">
        <el-alert v-if="hasBanAction(form.triggerAction)" type="warning" :closable="false" show-icon>
          <template #title>命中动作含封禁，请在「封禁时长」填写自动封禁分钟数。</template>
        </el-alert>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getRuleList,
  createRule,
  updateRule,
  deleteRule,
  getEventList,
  handleEvent
} from '@/api/modules'

const RULE_TYPES = ['RATE_LIMIT', 'HIGH_FREQUENCY', 'IP_ABNORMAL', 'SIGN_INVALID', 'SQL_INJECT', 'XSS', 'BLACKLIST']
const TYPE_TAG = { RATE_LIMIT: 'warning', HIGH_FREQUENCY: 'warning', IP_ABNORMAL: 'danger', SIGN_INVALID: 'danger', SQL_INJECT: 'danger', XSS: 'danger', BLACKLIST: 'info' }
const ACTIONS = [
  { value: 'ALERT', label: '告警' },
  { value: 'AUTO_BAN', label: '自动封禁' },
  { value: 'ALERT_AND_BAN', label: '告警并封禁' },
  { value: 'BLOCK', label: '直接拦截' }
]
const ACTION_LABEL = { ALERT: '告警', AUTO_BAN: '自动封禁', ALERT_AND_BAN: '告警并封禁', BLOCK: '直接拦截' }
const ACTION_TAG = { ALERT: 'warning', AUTO_BAN: 'danger', ALERT_AND_BAN: 'danger', BLOCK: 'info' }
const EVENT_STATUS = { 0: { label: '待处理', tag: 'danger' }, 1: { label: '已处理', tag: 'success' }, 2: { label: '已忽略', tag: 'info' } }

export default {
  name: 'SysSecurity',
  data() {
    return {
      activeTab: 'rule',
      ruleTotal: 0,
      eventTotal: 0,
      eventQuery: { handleStatus: undefined },
      ruleColumns: [
        { prop: 'ruleName', label: '规则名称', minWidth: 140, showOverflowTooltip: true },
        { prop: 'ruleType', label: '类型', width: 130, slot: 'ruleType' },
        { prop: 'triggerAction', label: '命中动作', width: 120, slot: 'triggerAction' },
        { prop: 'banDurationMin', label: '封禁时长(分)', width: 120, align: 'center', formatter: (v, row) => (this.hasBanAction(row.triggerAction) && v ? v : '—') },
        { prop: 'enabled', label: '启用', width: 90, slot: 'enabled' },
        { prop: 'description', label: '说明', minWidth: 160, showOverflowTooltip: true, formatter: (v) => v || '—' }
      ],
      eventColumns: [
        { prop: 'occurredAt', label: '发生时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'eventType', label: '事件类型', width: 150, showOverflowTooltip: true },
        { prop: 'appName', label: '应用', width: 120, formatter: (v) => v || '—' },
        { prop: 'clientIp', label: '来源IP', width: 140, formatter: (v) => v || '—' },
        { prop: 'triggerRule', label: '触发规则', minWidth: 140, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'eventDesc', label: '描述', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'handleStatus', label: '处理状态', width: 100, slot: 'handleStatus' }
      ],
      dialog: { visible: false, loading: false, form: {}, fields: [], rules: {} }
    }
  },
  methods: {
    typeTag(t) {
      return TYPE_TAG[t] || 'info'
    },
    actionLabel(a) {
      return ACTION_LABEL[a] || a
    },
    actionTag(a) {
      return ACTION_TAG[a] || 'info'
    },
    hasBanAction(a) {
      return a === 'AUTO_BAN' || a === 'ALERT_AND_BAN'
    },
    eventStatusTag(s) {
      return (EVENT_STATUS[s] || {}).tag || 'info'
    },
    eventStatusLabel(s) {
      return (EVENT_STATUS[s] || {}).label || s
    },
    fetchRules: function() {
      const self = this
      return async() => {
        const res = await getRuleList()
        const list = (res && res.data) || []
        self.ruleTotal = list.length
        return { list, total: list.length }
      }
    }(),
    fetchEvents: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getEventList({ current: page, size, ...rest })
        const data = (res && res.data) || {}
        self.eventTotal = data.total || 0
        return { list: data.records || [], total: data.total || 0 }
      }
    }(),
    reloadRules() {
      this.$nextTick(() => {
        if (this.$refs.ruleTable && this.$refs.ruleTable.reload) this.$refs.ruleTable.reload()
      })
    },
    reloadEvents() {
      this.$nextTick(() => {
        if (this.$refs.eventTable && this.$refs.eventTable.reload) this.$refs.eventTable.reload()
      })
    },
    buildRuleFields() {
      return [
        { prop: 'ruleName', label: '规则名称', type: 'input', required: true, span: 12, placeholder: '如：登录接口频率限制' },
        { prop: 'ruleType', label: '规则类型', type: 'select', required: true, span: 12, options: RULE_TYPES.map((t) => ({ value: t, label: t })) },
        { prop: 'triggerAction', label: '命中动作', type: 'select', required: true, span: 12, options: ACTIONS },
        { prop: 'banDurationMin', label: '封禁时长(分)', type: 'number', span: 12, min: 1, step: 1, placeholder: '仅封禁动作有效' },
        { prop: 'ruleConfig', label: '规则配置(JSON)', type: 'textarea', span: 24, rows: 3, placeholder: '如 {"threshold":100,"window":"1m"}' },
        { prop: 'description', label: '说明', type: 'textarea', span: 24, rows: 2, placeholder: '可选' },
        { prop: 'enabled', label: '启用', type: 'switch', span: 12, activeValue: true, inactiveValue: false }
      ]
    },
    buildRuleRules() {
      return {
        ruleName: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
        ruleType: [{ required: true, message: '规则类型不能为空', trigger: 'change' }],
        triggerAction: [{ required: true, message: '命中动作不能为空', trigger: 'change' }]
      }
    },
    onCreateRule() {
      this.dialog.form = { ruleName: '', ruleType: 'RATE_LIMIT', triggerAction: 'ALERT', banDurationMin: 60, ruleConfig: '', description: '', enabled: true }
      this.dialog.fields = this.buildRuleFields()
      this.dialog.rules = this.buildRuleRules()
      this.dialog.visible = true
    },
    onEditRule(row) {
      this.dialog.form = { ...row, enabled: !!row.enabled }
      this.dialog.fields = this.buildRuleFields()
      this.dialog.rules = this.buildRuleRules()
      this.dialog.visible = true
    },
    async onSubmitRule(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateRule(form.id, form)
          this.$message.success('规则已更新')
        } else {
          await createRule(form)
          this.$message.success('规则已创建')
        }
        this.dialog.visible = false
        this.reloadRules()
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onDeleteRule(row) {
      this.$confirm(`确认删除规则「${row.ruleName}」？`, '删除确认（高危）', { type: 'warning' })
        .then(async() => {
          try {
            await deleteRule(row.id)
            this.$message.success('已删除')
            this.reloadRules()
          } catch (e) {}
        }).catch(() => {})
    },
    onHandleEvent(row) {
      this.$prompt(`处理安全事件 #${row.id}，可填写处理备注`, '事件处理', { inputType: 'textarea' })
        .then(async({ value }) => {
          try {
            await handleEvent(row.id, 1, value)
            this.$message.success('已处理')
            this.reloadEvents()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-security .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-security .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-security .toolbar .spacer { flex: 1; }
.sys-security .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-security .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-security .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.sys-security .done-text { color: #9aa7bf; font-size: 12px; }
</style>
