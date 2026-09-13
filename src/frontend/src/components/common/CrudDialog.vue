<template>
  <el-dialog
    :title="title"
    :visible.sync="visible"
    :width="width"
    :close-on-click-modal="false"
    :destroy-on-close="destroyOnClose"
    @open="onOpen"
  >
    <el-form
      ref="form"
      :model="form"
      :rules="formRules"
      :label-width="labelWidth"
      :disabled="loading"
      :validate-on-rule-change="false"
    >
      <el-row :gutter="16">
        <el-col v-for="f in fields" :key="f.prop" :span="f.span || 24">
          <el-form-item :label="f.label" :prop="f.prop">
            <el-input
              v-if="f.type === 'input'"
              v-model="form[f.prop]"
              :placeholder="f.placeholder"
              :clearable="f.clearable !== false"
              :maxlength="f.maxlength"
              :show-word-limit="f.showWordLimit"
            />
            <el-input
              v-else-if="f.type === 'textarea'"
              v-model="form[f.prop]"
              type="textarea"
              :rows="f.rows || 3"
              :placeholder="f.placeholder"
              :maxlength="f.maxlength"
              :show-word-limit="f.showWordLimit"
            />
            <el-input-number
              v-else-if="f.type === 'number'"
              v-model="form[f.prop]"
              :min="f.min"
              :max="f.max"
              :step="f.step"
              :precision="f.precision"
              :controls-position="f.controlsPosition"
              style="width: 100%"
            />
            <el-select
              v-else-if="f.type === 'select'"
              v-model="form[f.prop]"
              :placeholder="f.placeholder || '请选择'"
              :clearable="f.clearable !== false"
              :multiple="f.multiple"
              style="width: 100%"
              @change="onFieldChange(f)"
            >
              <el-option
                v-for="o in f.options || []"
                :key="o.value"
                :label="o.label"
                :value="o.value"
              />
            </el-select>
            <DictSelect
              v-else-if="f.type === 'dict'"
              v-model="form[f.prop]"
              :dict-code="f.dictCode"
              :placeholder="f.placeholder"
              :clearable="f.clearable !== false"
              :multiple="f.multiple"
              :remote="f.remote"
              @change="onFieldChange(f)"
            />
            <el-switch
              v-else-if="f.type === 'switch'"
              v-model="form[f.prop]"
              :active-value="f.activeValue !== undefined ? f.activeValue : 1"
              :inactive-value="f.inactiveValue !== undefined ? f.inactiveValue : 0"
            />
            <el-date-picker
              v-else-if="f.type === 'date'"
              v-model="form[f.prop]"
              type="date"
              :value-format="f.valueFormat || 'yyyy-MM-dd'"
              :placeholder="f.placeholder || '选择日期'"
              style="width: 100%"
            />
            <el-date-picker
              v-else-if="f.type === 'datetime'"
              v-model="form[f.prop]"
              type="datetime"
              :value-format="f.valueFormat || 'yyyy-MM-dd HH:mm:ss'"
              :placeholder="f.placeholder || '选择时间'"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <!-- 额外自定义表单项插槽 -->
      <slot name="extra" :form="form" />
    </el-form>

    <template slot="footer">
      <slot name="footer">
        <el-button @click="onClose">取消</el-button>
        <el-button type="primary" :loading="loading" @click="onSubmit">{{ submitText }}</el-button>
      </slot>
    </template>
  </el-dialog>
</template>

<script>
/**
 * CrudDialog —— 通用新建/编辑弹窗构件（T05 §4）
 * ------------------------------------------------------------------
 * 声明式渲染表单字段（fields），内置校验与提交。后端无关：提交时 emit
 * 'submit' 并把本地表单数据抛出，由父组件调用对应 API。
 *
 * Props
 *  - visible  Boolean   (sync) 弹窗显隐，父组件用 :visible.sync
 *  - title    String    弹窗标题
 *  - model    Object    表单初始数据（打开时深拷贝到本地，避免直接改动父对象）
 *  - fields   Array<FieldDef>  字段定义（见下）
 *  - rules    Object    额外/覆盖校验规则（按 prop 覆盖）
 *  - width / loading / submitText / labelWidth / destroyOnClose
 *
 * FieldDef:
 *  { prop, label, type, options?, dictCode?, placeholder?, required?, rules?,
 *    span?, multiple?, min?, max?, step?, ... }
 *  - type: 'input' | 'textarea' | 'number' | 'select' | 'dict' | 'switch' | 'date' | 'datetime'
 *  - required: true 时自动生成必填校验；更精细规则用 field.rules
 *
 * Events
 *  - submit(form)   点击确定且校验通过后触发
 *  - update:visible  由 :visible.sync 处理
 *
 * 注意：本组件不直接修改 model，父组件在 submit 回调里负责写库并关闭弹窗。
 */
import DictSelect from './DictSelect.vue'

export default {
  name: 'CrudDialog',
  components: { DictSelect },
  props: {
    visible: { type: Boolean, default: false },
    title: { type: String, default: '编辑' },
    model: { type: Object, default: () => ({}) },
    fields: { type: Array, default: () => [] },
    rules: { type: Object, default: () => ({}) },
    width: { type: [String, Number], default: '560px' },
    loading: { type: Boolean, default: false },
    submitText: { type: String, default: '确定' },
    labelWidth: { type: String, default: '110px' },
    destroyOnClose: { type: Boolean, default: true }
  },
  data() {
    return { form: {} }
  },
  computed: {
    formRules() {
      const built = {}
      this.fields.forEach((f) => {
        const rs = []
        if (f.required) {
          rs.push({
            required: true,
            message: `请填写${f.label}`,
            trigger: f.type === 'select' || f.type === 'dict' || f.type === 'switch' || f.type === 'date' || f.type === 'datetime' ? 'change' : 'blur'
          })
        }
        if (f.rules) rs.push(...(Array.isArray(f.rules) ? f.rules : [f.rules]))
        if (rs.length) built[f.prop] = rs
      })
      // 父组件 rules 覆盖（按 prop）
      return { ...built, ...this.rules }
    }
  },
  methods: {
    clone(obj) {
      try {
        return JSON.parse(JSON.stringify(obj || {}))
      } catch (e) {
        return { ...(obj || {}) }
      }
    },
    onOpen() {
      this.form = this.clone(this.model)
      // 确保每个字段 key 存在，便于 v-model 双向绑定
      this.fields.forEach((f) => {
        if (this.form[f.prop] === undefined) {
          const def = f.type === 'switch'
            ? (f.inactiveValue !== undefined ? f.inactiveValue : 0)
            : (f.multiple ? [] : '')
          this.$set(this.form, f.prop, def)
        }
      })
      this.$nextTick(() => {
        if (this.$refs.form) this.$refs.form.clearValidate()
      })
    },
    onFieldChange() {
      // 预留：字段联动钩子（父组件可监听 submit 内读取最新 form）
    },
    onSubmit() {
      this.$refs.form.validate((valid) => {
        if (!valid) return
        this.$emit('submit', this.clone(this.form))
      })
    },
    onClose() {
      this.$emit('update:visible', false)
    }
  }
}
</script>

<style scoped>
/* 弹窗内部样式由全局样式统一，这里仅保留最小占位 */
</style>
