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
        <span :title="scopeTitle(row)">{{ scopeLabel(row) }}</span>
      </template>
      <template #alarmLevel="{row}">
        <el-tag size="small" :type="levelTag(row.alarmLevel)" effect="plain">{{ levelLabel(row.alarmLevel) }}</el-tag>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'alarmRule'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton perm="alarm_rule:create" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="alarm_rule:create" type="text" size="mini" @click="onToggle(row)">{{ row.status === 1 ? '停用' : '启用' }}</PermButton>
        <PermButton perm="alarm_rule:test" type="text" size="mini" @click="onTest(row)">测试</PermButton>
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
          :scope-type="form.scopeType || 1"
          :channel-options="channelOptions"
          :target-options="targetOptions"
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
  getNotifyChannelList,
  getAlarmTargetOptions
} from '@/api/modules'
import { ALARM_TYPE, ALARM_LEVEL } from '@/utils/enum'

const ALARM_TYPES = ['FAIL_RATE', 'AUTH_FAIL', 'QUOTA_USAGE', 'AVG_LATENCY', 'KEY_EXPIRE', 'ZOMBIE_API', 'QPS_SURGE']
const TARGET_DIM_LABEL = { APP: '按应用', API: '按接口' }
/**
 * ThresholdEditor 草案默认值（T11 起含评估对象绑定）。
 *
 * ⚠ timeWindow 用**分钟数字**而不是 '5m' 这种字符串键：
 *   旧实现维护了 WINDOW_TO_MIN / MIN_TO_WINDOW 双向映射表，而种子里存在 1440（1 天）/
 *   43200（30 天）这类不在映射表里的值 —— 回填时 `|| '5m'` 兜底，
 *   一次 no-op 编辑往返就把窗口静默改成 5 分钟。以分钟为唯一表示可根除该映射层。
 */
const EMPTY_DRAFT = () => ({
  targetType: '',
  targetIds: [],
  threshold: undefined,
  timeWindow: 5,
  alarmLevel: 2,
  silencePeriod: 30,
  channelNames: [],
  receiverNames: []
})

/**
 * 阈值回填：**无损**保留 threshold 的两种形态（纯数字 / 表达式字符串）。
 *
 * threshold 的后端契约是表达式字符串（AlarmRuleService.parseThreshold 认
 * `>` `>=` `<` `<=` + 数字），种子里就有 `>5` / `>10000` / `提前30天` / `30天无调用`。
 *
 * 🔴 旧实现直接 `Number(row.threshold)`：表达式 → NaN → ThresholdEditor 的 JSON 深拷贝
 *    把 NaN 变 null → el-input-number 的 `Number(null) === 0` 再被 `<= min` 钳到 min
 *    ⇒ 「打开编辑弹窗、什么都没改、点确定」就把阈值写坏（实测 `>10000`→`0`、`提前30天`→`1`）。
 *    现在表达式一律原样透传为字符串，由 ThresholdEditor 用文本控件呈现。
 */
function toDraftThreshold(raw) {
  if (raw === null || raw === undefined || raw === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) ? n : String(raw)
}

export default {
  name: 'SysAlarm',
  data() {
    return {
      query: { status: undefined },
      total: 0,
      channelOptions: [],
      // T11：评估对象候选，形如 { APP: [{value,label}], API: [{value,label}] }
      targetOptions: { APP: [], API: [] },
      alarmDraft: EMPTY_DRAFT(),
      columns: [
        { prop: 'ruleName', label: '规则名称', minWidth: 150, showOverflowTooltip: true },
        { prop: 'alarmType', label: '告警类型', width: 140, slot: 'alarmType' },
        { prop: 'scopeType', label: '评估对象', width: 140, slot: 'scopeType' },
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
    this.loadTargetOptions()
  },
  methods: {
    async fetchData(params) {
      const { status } = params
      const res = await getAlarmRuleList({ status })
      const list = (res && res.data) || []
      this.total = list.length
      return { list, total: list.length }
    },
    alarmTypeLabel(t) {
      return ALARM_TYPE[t] || t
    },
    levelLabel(l) {
      return (ALARM_LEVEL[l] || {}).label || l
    },
    levelTag(l) {
      return (ALARM_LEVEL[l] || {}).type || 'info'
    },
    /** 评估对象列文案：平台全局 / 按接口·全部 / 按应用·2 项 */
    scopeLabel(row) {
      if (row.scopeType === 2) return '平台全局'
      const dim = TARGET_DIM_LABEL[row.targetType] || '未指定维度'
      const n = row.targetIds ? String(row.targetIds).split(',').filter(Boolean).length : 0
      return n ? `${dim} · ${n} 项` : `${dim} · 全部`
    },
    /** 评估对象列 tooltip：列出已绑定的对象 ID（名称需另查，列表接口不带名称） */
    scopeTitle(row) {
      if (row.scopeType === 2) return '平台全局评估（不按对象展开）'
      if (!row.targetType) return '未指定对象维度 —— 该规则按单次评估处理，请在编辑中补选维度'
      const n = row.targetIds ? String(row.targetIds).split(',').filter(Boolean).length : 0
      return n ? `按${row.targetType === 'APP' ? '应用' : '接口'}评估，已绑定 ID：${row.targetIds}` : `按${row.targetType === 'APP' ? '应用' : '接口'}评估，覆盖全部对象`
    },
    /**
     * 加载评估对象候选（两个维度并行）。
     *
     * 失败只降级为空选项，不阻断页面 —— 与 loadChannels 同策略。
     */
    async loadTargetOptions() {
      try {
        const [apiRes, appRes] = await Promise.all([
          getAlarmTargetOptions('API'),
          getAlarmTargetOptions('APP')
        ])
        this.targetOptions = {
          API: ((apiRes && apiRes.data) || []).map((o) => ({ value: String(o.id), label: o.extra ? `${o.label}（${o.extra}）` : o.label })),
          APP: ((appRes && appRes.data) || []).map((o) => ({ value: String(o.id), label: o.extra ? `${o.label}（${o.extra}）` : o.label }))
        }
      } catch (e) {
        this.targetOptions = { APP: [], API: [] }
      }
    },
    async loadChannels() {
      try {
        // GET /notify-channel/list 返回裸数组（无分页信封），与同页 fetchData 的解析保持一致
        const res = await getNotifyChannelList()
        const list = (res && res.data) || []
        this.channelOptions = list.map((c) => ({ value: String(c.id), label: c.channelName || ('渠道#' + c.id) }))
      } catch (e) {
        this.channelOptions = []
      }
    },
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
      this.alarmDraft = EMPTY_DRAFT()
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
        // T11：回填评估对象绑定（targetIds 为逗号串 → 转数组供多选框使用）
        targetType: row.targetType || '',
        targetIds: row.targetIds ? String(row.targetIds).split(',').filter(Boolean) : [],
        threshold: toDraftThreshold(row.threshold),
        timeWindow: row.timeWindow != null ? row.timeWindow : 5,
        alarmLevel: row.alarmLevel || 2,
        silencePeriod: row.silencePeriod != null ? row.silencePeriod : 30,
        channelNames: row.channelIds ? String(row.channelIds).split(',').filter(Boolean) : [],
        receiverNames: row.receiverIds ? String(row.receiverIds).split(',').filter(Boolean) : []
      }
      this.dialog.visible = true
    },
    async onSubmit(form) {
      // T11 前置校验：按对象必须指定维度。放在这里而不是 CrudDialog 的 rules 里，
      // 是因为「评估对象」渲染在 extra 插槽（ThresholdEditor 内部），CrudDialog 不校验插槽内容。
      if (Number(form.scopeType) === 1 && !this.alarmDraft.targetType) {
        this.$message.warning('「按对象」必须指定评估对象维度：按接口 / 按应用')
        return
      }
      const payload = {
        ...form,
        targetType: Number(form.scopeType) === 1 ? this.alarmDraft.targetType : null,
        // scopeType=1 时对象的空数组 ⇒ 空串提交（后端语义：空 = 该维度下全部对象）
        targetIds: Number(form.scopeType) === 1 ? (this.alarmDraft.targetIds || []).join(',') : null,
        threshold: this.alarmDraft.threshold != null ? String(this.alarmDraft.threshold) : null,
        timeWindow: this.alarmDraft.timeWindow,
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
