<template>
  <div class="page-container sys-alarm">
    <div class="page-head">
      <h2>告警规则</h2>
      <span class="page-tag">按告警类型动态配置阈值 / 窗口 / 级别 · 写操作高危</span>
    </div>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条规则</span>
      <el-select v-model="query.status" placeholder="全部状态" clearable size="small" style="width:140px" @change="reload">
        <el-option :value="1" label="启用" />
        <el-option :value="0" label="停用" />
      </el-select>
      <span class="spacer" />
      <PermButton perm="alarm_rule:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建规则</PermButton>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :show-pagination="false" :actions-width="180">
      <template #alarmType="{row}">
        <el-tag size="small" effect="plain" type="warning">{{ alarmTypeLabel(row.alarmType) }}</el-tag>
      </template>
      <template #scopeType="{row}">
        <span>{{ row.scopeType === 2 ? '平台全局' : '按对象' }}</span>
      </template>
      <template #alarmLevel="{row}">
        <el-tag size="small" :type="levelTag(row.alarmLevel)" effect="plain">{{ levelLabel(row.alarmLevel) }}</el-tag>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'alarmRule'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <el-button type="text" size="mini" @click="onEdit(row)">编辑</el-button>
        <el-button type="text" size="mini" @click="onToggle(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        <el-button type="text" size="mini" @click="onTest(row)">测试</el-button>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑告警规则' : '新建告警规则'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'680px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-divider content-position="left">阈值配置（按告警类型动态渲染）</el-divider>
        <ThresholdEditor
          :value="alarmDraft"
          :alarm-type="form.alarmType || 'FAIL_RATE'"
          :channel-options="channelOptions"
          @input="onDraftChange"
        />
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getAlarmRuleList,
  createAlarmRule,
  updateAlarmRule,
  toggleAlarmRule,
  testAlarmRule,
  getNotifyChannelList
} from '@/api/modules'
import { ALARM_TYPE, ALARM_LEVEL } from '@/utils/enum'

const ALARM_TYPES = ['FAIL_RATE', 'AUTH_FAIL', 'QUOTA_USAGE', 'AVG_LATENCY', 'KEY_EXPIRE', 'ZOMBIE_API', 'QPS_SURGE']
const WINDOW_TO_MIN = { '1m': 1, '5m': 5, '15m': 15, '1h': 60 }
const MIN_TO_WINDOW = { 1: '1m', 5: '5m', 15: '15m', 60: '1h' }

export default {
  name: 'SysAlarm',
  data() {
    return {
      query: { status: undefined },
      total: 0,
      channelOptions: [],
      alarmDraft: { threshold: undefined, timeWindow: '5m', alarmLevel: 2, silencePeriod: 30, channelNames: [], receiverNames: [] },
      columns: [
        { prop: 'ruleName', label: '规则名称', minWidth: 150, showOverflowTooltip: true },
        { prop: 'alarmType', label: '告警类型', width: 140, slot: 'alarmType' },
        { prop: 'scopeType', label: '评估范围', width: 100, slot: 'scopeType' },
        { prop: 'threshold', label: '阈值', width: 110, formatter: (v) => (v == null || v === '' ? '—' : v) },
        { prop: 'timeWindow', label: '窗口(分)', width: 90, align: 'right', formatter: (v) => (v == null ? '—' : v) },
        { prop: 'alarmLevel', label: '级别', width: 100, slot: 'alarmLevel' },
        { prop: 'silencePeriod', label: '静默(分)', width: 90, align: 'right', formatter: (v) => (v == null ? '—' : v) },
        { prop: 'receiverDesc', label: '接收人', minWidth: 130, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' }
      ],
      dialog: { visible: false, loading: false, form: {}, fields: [], rules: {} }
    }
  },
  created() {
    this.loadChannels()
  },
  methods: {
    alarmTypeLabel(t) {
      return ALARM_TYPE[t] || t
    },
    levelLabel(l) {
      return (ALARM_LEVEL[l] || {}).label || l
    },
    levelTag(l) {
      return (ALARM_LEVEL[l] || {}).type || 'info'
    },
    async loadChannels() {
      try {
        const res = await getNotifyChannelList({ pageNum: 1, pageSize: 100 })
        const data = (res && res.data) || {}
        const records = data.records || []
        this.channelOptions = records.map((c) => ({ value: String(c.id), label: c.channelName || ('渠道#' + c.id) }))
      } catch (e) {
        this.channelOptions = []
      }
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { status } = params
        const res = await getAlarmRuleList({ status })
        const list = (res && res.data) || []
        self.total = list.length
        return { list, total: list.length }
      }
    }(),
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    onDraftChange(v) {
      this.alarmDraft = v
    },
    buildFields() {
      return [
        { prop: 'ruleName', label: '规则名称', type: 'input', required: true, span: 12, placeholder: '如：调用失败率告警' },
        { prop: 'alarmType', label: '告警类型', type: 'select', required: true, span: 12, options: ALARM_TYPES.map((t) => ({ value: t, label: `${t} · ${ALARM_TYPE[t]}` })) },
        { prop: 'scopeType', label: '评估范围', type: 'select', required: true, span: 12, options: [{ value: 1, label: '按对象（应用/接口）' }, { value: 2, label: '平台全局' }] },
        { prop: 'receiverScope', label: '接收人范围', type: 'select', span: 12, options: [{ value: 'ASSIGNEE', label: '对象负责人' }, { value: 'USER', label: '指定用户' }, { value: 'ROLE', label: '指定角色' }] },
        { prop: 'receiverDesc', label: '接收人描述', type: 'input', span: 24, placeholder: '如：各应用负责人' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    buildRules() {
      return {
        ruleName: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
        alarmType: [{ required: true, message: '告警类型不能为空', trigger: 'change' }],
        scopeType: [{ required: true, message: '评估范围不能为空', trigger: 'change' }]
      }
    },
    resetDraft() {
      this.alarmDraft = { threshold: undefined, timeWindow: '5m', alarmLevel: 2, silencePeriod: 30, channelNames: [], receiverNames: [] }
    },
    onCreate() {
      this.dialog.form = { ruleName: '', alarmType: 'FAIL_RATE', scopeType: 1, receiverScope: 'ASSIGNEE', receiverDesc: '', status: 1 }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.resetDraft()
      this.dialog.visible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.alarmDraft = {
        threshold: row.threshold != null && row.threshold !== '' ? Number(row.threshold) : undefined,
        timeWindow: MIN_TO_WINDOW[row.timeWindow] || '5m',
        alarmLevel: row.alarmLevel || 2,
        silencePeriod: row.silencePeriod != null ? row.silencePeriod : 30,
        channelNames: row.channelIds ? String(row.channelIds).split(',').filter(Boolean) : [],
        receiverNames: row.receiverIds ? String(row.receiverIds).split(',').filter(Boolean) : []
      }
      this.dialog.visible = true
    },
    async onSubmit(form) {
      const payload = {
        ...form,
        threshold: this.alarmDraft.threshold != null ? String(this.alarmDraft.threshold) : null,
        timeWindow: WINDOW_TO_MIN[this.alarmDraft.timeWindow] || 5,
        alarmLevel: this.alarmDraft.alarmLevel,
        silencePeriod: this.alarmDraft.silencePeriod,
        channelIds: (this.alarmDraft.channelNames || []).join(','),
        receiverIds: (this.alarmDraft.receiverNames || []).join(',')
      }
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateAlarmRule(form.id, payload)
          this.$message.success('规则已更新')
        } else {
          await createAlarmRule(payload)
          this.$message.success('规则已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onToggle(row) {
      const next = row.status === 1 ? 0 : 1
      this.$confirm(`确认${next === 1 ? '启用' : '停用'}规则「${row.ruleName}」？`, '状态变更', { type: 'warning' })
        .then(async() => {
          try {
            await toggleAlarmRule(row.id, next)
            this.$message.success('状态已更新')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    },
    onTest(row) {
      this.$confirm(`通过规则「${row.ruleName}」已配置渠道发送测试告警？`, '发送测试告警', { type: 'info' })
        .then(async() => {
          try {
            await testAlarmRule(row.id)
            this.$message.success('测试告警已发送')
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-alarm .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-alarm .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-alarm .toolbar .spacer { flex: 1; }
.sys-alarm .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-alarm .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-alarm .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
