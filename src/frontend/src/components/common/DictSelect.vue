<template>
  <el-select
    :value="value"
    :placeholder="placeholder"
    :clearable="clearable"
    :disabled="disabled"
    :size="size"
    :multiple="multiple"
    :filterable="filterable"
    style="width: 100%"
    @input="$emit('input', $event)"
    @change="$emit('change', $event)"
  >
    <el-option
      v-for="o in options"
      :key="o.value"
      :label="o.label"
      :value="o.value"
    />
  </el-select>
</template>

<script>
/**
 * DictSelect —— 字典下拉构件（T05 §4）
 * ------------------------------------------------------------------
 * 所有「类型 / 可见性 / 状态」等下拉的统一数据源。后端 DictController 就绪前
 * 使用静态种子兜底（utils/dict.js）；ready 模式（remote=true）改从 /sys/dict 拉取。
 *
 * Props
 *  - value       v-model 值
 *  - dictCode    String   字典编码：app_type | visibility | api_status |
 *                         grant_status | cred_status | alarm_level
 *  - placeholder / clearable / disabled / size / multiple / filterable
 *  - remote      Boolean  默认 false（静态种子）；true 时从 /sys/dict-item 拉取
 *
 * Events：input(v-model) / change
 *
 * 设计为 v-model 兼容：使用 :value + @input（而非 .sync），父组件用 v-model 即可。
 */
import { getStaticDictItems } from '@/utils/dict'
import { getDictItems } from '@/api/modules'

export default {
  name: 'DictSelect',
  props: {
    value: { type: [String, Number, Array], default: '' },
    dictCode: { type: String, required: true },
    placeholder: { type: String, default: '请选择' },
    clearable: { type: Boolean, default: true },
    disabled: { type: Boolean, default: false },
    size: { type: String, default: '' },
    multiple: { type: Boolean, default: false },
    filterable: { type: Boolean, default: false },
    remote: { type: Boolean, default: false }
  },
  data() {
    return { options: [] }
  },
  watch: {
    dictCode: { immediate: true, handler() { this.load() } }
  },
  methods: {
    async load() {
      if (this.remote) {
        try {
          const res = await getDictItems(this.dictCode)
          const raw = (res.data && res.data.items) || res.data || []
          this.options = raw.map((x) => ({
            value: x.itemValue !== undefined ? x.itemValue : x.value,
            label: x.itemLabel !== undefined ? x.itemLabel : x.label
          }))
          return
        } catch (e) {
          // 后端缺失 / 异常时回退静态种子，避免下拉空白
        }
      }
      this.options = getStaticDictItems(this.dictCode)
    }
  }
}
</script>
