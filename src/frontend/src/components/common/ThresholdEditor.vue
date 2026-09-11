<template>
  <el-form
    v-if="meta"
    ref="form"
    class="threshold-editor"
    label-width="110px"
    :disabled="disabled"
  >
    <!-- 按 alarmType 动态渲染的阈值输入 -->
    <el-form-item :label="meta.label">
      <el-input-number
        v-model="form.threshold"
        :min="meta.min"
        :max="meta.max"
        :step="meta.step"
        controls-position="right"
        style="width: 200px"
      />
      <span class="te-unit">{{ meta.unit }}</span>
      <div class="te-tip">{{ meta.tip }}</div>
    </el-form-item>

    <!-- 通用字段：统计窗口 -->
    <el-form-item label="统计窗口">
      <el-select v-model="form.timeWindow" style="width: 200px">
        <el-option v-for="w in WINDOWS" :key="w.value" :label="w.label" :value="w.value" />
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
      <el-input-number v-model="form.silencePeriod" :min="0" :max="1440" :step="5" controls-position="right" style="width: 200px" />
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
 * ThresholdEditor —— 告警规则阈值编辑器（T05 §4）
 * ------------------------------------------------------------------
 * 按 alarmType 动态渲染阈值 / 窗口 / 级别 / 静默期 / 渠道 / 接收人，供 sys-alarm 页复用。
 * 组件本身不依赖任何具体后端接口，纯前端表单构件。
 *
 * Props
 *  - value        Object  (v-model) 阈值配置对象：
 *                  { threshold, timeWindow, alarmLevel, silencePeriod, channelNames[], receiverNames[] }
 *  - alarmType    String  告警类型（FAIL_RATE|AUTH_FAIL|QUOTA_USAGE|AVG_LATENCY|KEY_EXPIRE|ZOMBIE_API|QPS_SURGE）
 *  - disabled     Boolean
 *  - channelOptions  Array  通知渠道选项 [{value,label}]（由父组件从 /notify-channel 提供，默认空）
 *  - receiverOptions Array 接收人选项 [{value,label}]，默认空（允许手动输入）
 *
 * Events：input(v-model) / change
 */
import { ALARM_TYPE } from '@/utils/enum'

const WINDOWS = [
  { value: '1m', label: '1 分钟' },
  { value: '5m', label: '5 分钟' },
  { value: '15m', label: '15 分钟' },
  { value: '1h', label: '1 小时' }
]

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
    disabled: { type: Boolean, default: false },
    channelOptions: { type: Array, default: () => [] },
    receiverOptions: { type: Array, default: () => [] }
  },
  data() {
    return {
      WINDOWS,
      LEVELS,
      form: this.clone(this.value)
    }
  },
  computed: {
    meta() {
      return TYPE_META[this.alarmType] || null
    },
    typeLabel() {
      return ALARM_TYPE[this.alarmType] || this.alarmType
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
    clone(obj) {
      const base = {
        threshold: undefined,
        timeWindow: '5m',
        alarmLevel: 2,
        silencePeriod: 30,
        channelNames: [],
        receiverNames: []
      }
      try {
        return { ...base, ...JSON.parse(JSON.stringify(obj || {})) }
      } catch (e) {
        return { ...base, ...(obj || {}) }
      }
    }
  }
}
</script>

<style scoped>
.threshold-editor { width: 100%; }
.te-unit { margin-left: 8px; color: #5c6b8a; font-size: 12px; }
.te-tip { font-size: 12px; color: #9aa7bf; margin-top: 4px; }
</style>
