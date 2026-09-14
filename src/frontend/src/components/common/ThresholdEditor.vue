<template>
  <el-form
    v-if="meta"
    ref="form"
    class="threshold-editor"
    label-width="110px"
    :disabled="disabled"
  >
    <!-- T11：评估对象绑定（仅 scopeType=1「按对象」时出现；scopeType=2 平台全局不展开对象） -->
    <el-form-item v-if="scopeType === 1" label="评估对象">
      <div class="te-target">
        <el-select
          v-model="form.targetType"
          placeholder="对象维度"
          style="width: 130px"
          @change="onTargetTypeChange"
        >
          <el-option value="API" label="按接口" />
          <el-option value="APP" label="按应用" />
        </el-select>
        <el-select
          v-model="form.targetIds"
          multiple
          filterable
          collapse-tags
          style="flex: 1"
          :placeholder="targetPlaceholder"
          :disabled="!form.targetType || !currentTargetOptions.length"
        >
          <el-option v-for="t in currentTargetOptions" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </div>
      <div class="te-tip">{{ targetTip }}</div>
    </el-form-item>

    <!-- 按 alarmType 动态渲染的阈值输入（双模，见 thresholdIsNumber 的说明） -->
    <el-form-item :label="meta.label">
      <el-input-number
        v-if="thresholdIsNumber"
        v-model="form.threshold"
        :min="meta.min"
        :max="meta.max"
        :step="meta.step"
        controls-position="right"
        style="width: 200px"
      />
      <el-input
        v-else
        v-model="form.threshold"
        placeholder="如 >10000"
        style="width: 200px"
      />
      <span class="te-unit">{{ thresholdIsNumber ? meta.unit : '' }}</span>
      <div class="te-tip">{{ thresholdIsNumber ? meta.tip : expressionTip }}</div>
    </el-form-item>

    <!-- 通用字段：统计窗口（单位=分钟，与后端 time_window 列一致） -->
    <el-form-item label="统计窗口">
      <el-select v-model="form.timeWindow" style="width: 200px">
        <el-option v-for="w in windowOptions" :key="w.value" :label="w.label" :value="w.value" />
      </el-select>
    </el-form-item>

    <!-- 通用字段：告警级别 -->
    <el-form-item label="告警级别">
      <el-select v-model="form.alarmLevel" style="width: 200px">
        <el-option v-for="lv in LEVELS" :key="lv.value" :label="lv.label" :value="lv.value" />
      </el-select>
    </el-form-item>

    <!-- 通用字段：静默期（分钟） -->
    <el-form-item label="静默期">
      <el-input-number v-model="form.silencePeriod" :min="0" :max="SILENCE_MAX_MIN" :step="5" controls-position="right" style="width: 200px" />
      <span class="te-unit">分钟</span>
    </el-form-item>

    <!-- 通用字段：通知渠道 -->
    <el-form-item label="通知渠道">
      <el-select
        v-model="form.channelNames"
        multiple
        filterable
        style="width: 100%"
        placeholder="选择通知渠道"
      >
        <el-option v-for="c in channelOptions" :key="c.value" :label="c.label" :value="c.value" />
      </el-select>
    </el-form-item>

    <!-- 通用字段：接收人 -->
    <el-form-item label="接收人">
      <el-select
        v-model="form.receiverNames"
        multiple
        filterable
        allow-create
        default-first-option
        style="width: 100%"
        placeholder="选择或输入接收人"
      >
        <el-option v-for="r in receiverOptions" :key="r.value" :label="r.label" :value="r.value" />
      </el-select>
    </el-form-item>
  </el-form>
</template>

<script>
/**
 * ThresholdEditor —— 告警规则阈值编辑器（T05 §4；T11 增补「评估对象」）
 * ------------------------------------------------------------------
 * 按 alarmType 动态渲染阈值 / 窗口 / 级别 / 静默期 / 渠道 / 接收人，
 * 并在 scopeType=1（按对象）时渲染「评估对象」= 维度(APP/API) + 具体对象多选（空=全部），
 * 供 sys-alarm 页复用。组件本身不依赖任何具体后端接口，纯前端表单构件
 * （候选对象由父组件通过 targetOptions 传入）。
 *
 * Props
 *  - value        Object  (v-model) 阈值配置对象：
 *                  { targetType, targetIds[], threshold, timeWindow, alarmLevel, silencePeriod, channelNames[], receiverNames[] }
 *                  · targetType: 'APP' | 'API' | ''（scopeType=1 必填，后端会校验）
 *                  · targetIds : 对象ID字符串数组（由父组件 join 成逗号串提交）；空 = 全部对象
 *                  · threshold : Number | String —— 纯数字走步进控件；表达式字符串
 *                                （'>10000' / '提前30天'）走文本控件原样往返（见 thresholdIsNumber）
 *                  · timeWindow: Number 统计窗口，**单位=分钟**（与后端 time_window 列一致）；
 *                                非预设值（1440/43200）会被自动补成选项，不会被兜底改写
 *  - alarmType    String  告警类型（FAIL_RATE|AUTH_FAIL|QUOTA_USAGE|AVG_LATENCY|KEY_EXPIRE|ZOMBIE_API|QPS_SURGE）
 *  - scopeType    Number  1=按对象（渲染「评估对象」）, 2=平台全局（不渲染），默认 1
 *  - targetOptions Object 评估对象候选 { APP: [{value,label}], API: [{value,label}] }，默认空
 *  - disabled     Boolean
 *  - channelOptions  Array  通知渠道选项 [{value,label}]（由父组件从 /notify-channel 提供，默认空）
 *  - receiverOptions Array 接收人选项 [{value,label}]，默认空（允许手动输入）
 *
 * Events：input(v-model) / change
 */
import { ALARM_TYPE } from '@/utils/enum'

/**
 * 统计窗口预设（**单位=分钟**，与后端 alarm_rule.time_window 列语义一致）。
 *
 * ⚠ 这里刻意不再用 '1m'/'5m' 这类字符串键 + 双向映射表：
 *   旧实现把分钟映射成字符串键（MIN_TO_WINDOW），种子里 1440（1 天）/ 43200（30 天）
 *   落不进映射表，回填时被 `|| '5m'` 兜底成 5 分钟 —— 即「打开编辑、什么都没改、
 *   点确定，规则的时间窗口就从 1 天变成 5 分钟」。直接以分钟为唯一表示可根除该映射层。
 */
const WINDOWS = [
  { value: 1, label: '1 分钟' },
  { value: 5, label: '5 分钟' },
  { value: 15, label: '15 分钟' },
  { value: 60, label: '1 小时' }
]

/**
 * 静默期上限（分钟）= 30 天。
 *
 * ⚠ 旧值 1440（=1 天）会把种子里「僵尸接口告警」的 10080（7 天）直接钳到 1440，
 *   又是一次 no-op 往返里的静默改写。上限取 30 天，覆盖现有种子最长值并留足余量。
 */
const SILENCE_MAX_MIN = 43200

/** 非预设窗口值的中文备注：1440 → '（1 天）'、43200 → '（30 天）'、120 → '（2 小时）' */
function windowAlias(min) {
  if (typeof min !== 'number' || !isFinite(min)) return ''
  if (min >= 1440 && min % 1440 === 0) return `（${min / 1440} 天）`
  if (min >= 60 && min % 60 === 0) return `（${min / 60} 小时）`
  return ''
}

const LEVELS = [
  { value: 1, label: 'INFO' },
  { value: 2, label: 'WARNING' },
  { value: 3, label: 'CRITICAL' }
]

// 各 alarmType 的阈值元信息
const TYPE_META = {
  FAIL_RATE: { label: '失败率阈值', unit: '%', min: 0, max: 100, step: 0.1, tip: '调用失败率超过该百分比触发告警' },
  AUTH_FAIL: { label: '鉴权失败次数', unit: '次', min: 0, max: 9999, step: 1, tip: '单位时间鉴权失败次数超过阈值触发告警' },
  QUOTA_USAGE: { label: '配额使用率', unit: '%', min: 0, max: 100, step: 1, tip: '配额使用率超过该百分比触发告警' },
  AVG_LATENCY: { label: '平均延迟', unit: 'ms', min: 0, max: 60000, step: 100, tip: '后端平均响应延迟超过该毫秒数触发告警' },
  KEY_EXPIRE: { label: '提前提醒', unit: '天', min: 1, max: 90, step: 1, tip: '密钥到期前 N 天开始提醒' },
  ZOMBIE_API: { label: '无调用天数', unit: '天', min: 1, max: 365, step: 1, tip: '连续 N 天无调用视为僵尸接口' },
  QPS_SURGE: { label: 'QPS 突增倍数', unit: 'x', min: 1, max: 100, step: 0.1, tip: 'QPS 较基线突增超过该倍数触发告警' }
}

export default {
  name: 'ThresholdEditor',
  props: {
    value: { type: Object, default: () => ({}) },
    alarmType: { type: String, required: true },
    scopeType: { type: Number, default: 1 },
    targetOptions: { type: Object, default: () => ({ APP: [], API: [] }) },
    disabled: { type: Boolean, default: false },
    channelOptions: { type: Array, default: () => [] },
    receiverOptions: { type: Array, default: () => [] }
  },
  data() {
    return {
      WINDOWS,
      LEVELS,
      SILENCE_MAX_MIN,
      form: this.clone(this.value)
    }
  },
  computed: {
    meta() {
      return TYPE_META[this.alarmType] || null
    },
    typeLabel() {
      return ALARM_TYPE[this.alarmType] || this.alarmType
    },
    /** 当前维度下的候选对象 */
    currentTargetOptions() {
      const t = this.form.targetType
      if (!t) return []
      return (this.targetOptions && this.targetOptions[t]) || []
    },
    targetPlaceholder() {
      if (!this.form.targetType) return '请先选择对象维度'
      if (!this.currentTargetOptions.length) return '该维度下暂无对象'
      return '不选 = 该维度下全部对象'
    },
    targetTip() {
      if (!this.form.targetType) {
        return '「按对象」必须指定维度：按接口 / 按应用（未选将无法保存）'
      }
      if (!this.currentTargetOptions.length) {
        const dim = this.form.targetType === 'API' ? '接口' : '应用'
        return `当前平台上暂无${dim}，规则保存后不会产生告警；可先去「${dim === '接口' ? '接口列表' : '应用列表'}」创建`
      }
      if (!this.form.targetIds.length) {
        return '未选择具体对象 = 该维度下全部对象；每个对象独立评估、独立静默'
      }
      return `已选 ${this.form.targetIds.length} 个对象；每个对象独立评估、独立静默`
    },
    /**
     * 阈值是否走「数字控件」—— 决定用 el-input-number 还是 el-input（文本）。
     *
     * threshold 的后端契约是**表达式字符串**（AlarmRuleService.parseThreshold 认
     * `>` `>=` `<` `<=` + 数字，javadoc 示例 `>5` / `>200%` / `<10`；init.sql 的 7 条
     * 种子规则里就有 `>10000` / `提前30天` / `30天无调用`）。
     *
     * 🔴 非数字表达式**绝不能**交给 el-input-number：它把入参 Number() 后 `<= min`
     *    直接钳到 min（实测 `>10000` → `0`、`提前30天` → `1`），
     *    于是「打开编辑弹窗、什么都没改、点确定」就能写坏一整条规则的阈值。
     */
    thresholdIsNumber() {
      const t = this.form.threshold
      if (t === undefined || t === null || t === '') return true
      return typeof t === 'number' && !isNaN(t)
    },
    /** 文本模式下的提示：说清什么表达式后端真的会解析 */
    expressionTip() {
      return '表达式原样保存；后端按「比较符 + 数字」解析（如 >10000），其他描述式仅作备忘、不参与评估'
    },
    /**
     * 统计窗口选项 = 预设 ∪ {库中当前值}。
     *
     * ⚠ 「补当前值」这一步是必须的：el-select 遇到「v-model 有值、但选项列表里没有该值」
     *   时显示为空，用户会以为窗口已被清掉；而若此时点确定，
     *   回填链会把空值兜底成默认 5 分钟 ⇒ 又是一次静默改写。
     */
    windowOptions() {
      const cur = this.form.timeWindow
      if (typeof cur === 'number' && !WINDOWS.some((w) => w.value === cur)) {
        return WINDOWS.concat([{ value: cur, label: `${cur} 分钟${windowAlias(cur)}` }]).sort((a, b) => a.value - b.value)
      }
      return WINDOWS
    }
  },
  watch: {
    value: {
      deep: true,
      handler(v) {
        if (JSON.stringify(v) !== JSON.stringify(this.form)) this.form = this.clone(v)
      }
    },
    form: {
      deep: true,
      handler() {
        const out = this.clone(this.form)
        this.$emit('input', out)
        this.$emit('change', out)
      }
    }
  },
  methods: {
    /**
     * 切换对象维度时清空已选对象。
     *
     * ⚠ 必须挂在 el-select 的 @change 上（只由用户操作触发），不能用 watch('form.targetType')：
     * 后者在父组件回填（编辑弹窗打开）时也会触发，会把已有序号清掉
     * —— 与本项目「列表 VO 子集 + CrudDialog 补空串」同类的静默清空坑。
     */
    onTargetTypeChange() {
      this.form.targetIds = []
    },
    /**
     * 浅拷贝出表单副本（断开与父组件对象的引用，数组单独复制）。
     *
     * 🔴 这里**不能用** `JSON.parse(JSON.stringify(obj))` 做深拷贝：
     *    JSON 没有 NaN 字面量，会把 `NaN` 静默转成 `null`，而 `null` 交给
     *    el-input-number 后 `Number(null) === 0` 会被钳成 min ——
     *    这正是「阈值 `>10000` 在编辑往返后变成 `0`」链条上的关键一跳。
     *    本对象是扁平结构（标量 + 字符串数组），浅拷贝 + 数组复制已足够断开引用。
     */
    clone(obj) {
      const src = obj || {}
      const base = {
        targetType: '',
        targetIds: [],
        threshold: undefined,
        timeWindow: 5,
        alarmLevel: 2,
        silencePeriod: 30,
        channelNames: [],
        receiverNames: []
      }
      try {
        return {
          ...base,
          ...src,
          targetIds: Array.isArray(src.targetIds) ? src.targetIds.slice() : [],
          channelNames: Array.isArray(src.channelNames) ? src.channelNames.slice() : [],
          receiverNames: Array.isArray(src.receiverNames) ? src.receiverNames.slice() : []
        }
      } catch (e) {
        return { ...base }
      }
    }
  }
}
</script>

<style scoped>
.threshold-editor { width: 100%; }
.te-unit { margin-left: 8px; color: #5c6b8a; font-size: 12px; }
.te-tip { font-size: 12px; color: #9aa7bf; margin-top: 4px; }
.te-target { display: flex; align-items: center; gap: 8px; width: 100%; }
</style>
