<template>
  <el-button
    v-if="visible"
    v-bind="$attrs"
    v-on="$listeners"
    :type="type"
    :size="size"
    :icon="icon"
    :plain="plain"
    :round="round"
    :circle="circle"
    :disabled="disabled"
    :loading="loading"
  >
    <slot />
  </el-button>
</template>

<script>
/**
 * PermButton —— 权限闸门按钮（T05 §4）
 * ------------------------------------------------------------------
 * 包裹 el-button，按 perm 编码控制显隐。无该权限时整按钮不渲染（v-if=false）。
 * 权限来源：登录返回的 perms 数组（store.state.perms）。
 *
 * Props
 *  - perm     String   权限点编码；为空表示无需权限，始终可见
 *  - type/size/icon/plain/round/circle/disabled/loading  透传给 el-button
 *
 * 透传：其余原生属性与事件（@click 等）通过 $attrs / $listeners 转发，使用方式
 * 与普通 el-button 完全一致。例如：
 *   <PermButton perm="grant:revoke" type="danger" @click="revoke(row)">撤销</PermButton>
 */
import { hasPerm } from '@/utils/perm'

export default {
  name: 'PermButton',
  inheritAttrs: false,
  props: {
    perm: { type: String, default: '' },
    type: { type: String, default: 'default' },
    size: { type: String, default: '' },
    icon: { type: String, default: '' },
    plain: { type: Boolean, default: false },
    round: { type: Boolean, default: false },
    circle: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    loading: { type: Boolean, default: false }
  },
  computed: {
    visible() {
      return hasPerm(this.perm, this.$store.state.perms)
    }
  }
}
</script>
