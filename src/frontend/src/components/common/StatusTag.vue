<template>
  <el-tag :type="meta.type" :size="size" :effect="effect" class="gk-status-tag">
    <slot>{{ meta.label }}</slot>
  </el-tag>
</template>

<script>
/**
 * StatusTag —— 枚举驱动的状态彩色标签（T05 §4）
 * ------------------------------------------------------------------
 * 按「实体类型 + status 值」渲染彩色 el-tag，避免各页硬编码颜色映射。
 * 实体与 status 语义严格遵循 docs/原型枚举字典.md（不同实体 status 含义不同）。
 *
 * Props
 *  - entity  String   实体类型：app | credential | grant | api | apiGroup |
 *                        apiVersion | user | env | alarmRule | alarmHandle |
 *                        block | dict | notifyChannel | apiEnvConfig
 *  - value   Number|String  状态值
 *  - size    String   el-tag 尺寸，默认 'small'
 *  - effect  String   'light' | 'dark' | 'plain'，默认 'light'
 *
 * 未知实体 / 未知值：回退为 { label: value, type: 'info' }，不报错。
 */
import { getStatusMeta } from '@/utils/enum'

export default {
  name: 'StatusTag',
  props: {
    entity: { type: String, required: true },
    value: { type: [Number, String], required: true },
    size: { type: String, default: 'small' },
    effect: { type: String, default: 'light' }
  },
  computed: {
    meta() {
      return getStatusMeta(this.entity, this.value)
    }
  }
}
</script>

<style scoped>
.gk-status-tag { border: none; }
</style>
