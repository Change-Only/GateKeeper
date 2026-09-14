<template>
  <el-dialog
    :title="title"
    :visible.sync="visible"
    :width="width"
    :close-on-click-modal="false"
    :destroy-on-close="destroyOnClose"
    :append-to-body="appendToBody"
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
            <!-- 树形下拉（层级数据，如「所属分组」）：el-cascader 而非 el-select ——
                 Element UI 2.x 没有 el-tree-select，cascader 是原生可用的树形选择控件。
                 配 emitPath:false 后 v-model 直接是节点 id（单值契约与 el-select 完全一致），
                 调用方的 form / 提交逻辑无需任何改造。 -->
            <el-cascader
              v-else-if="f.type === 'tree-select'"
              v-model="form[f.prop]"
              :options="f.options || []"
              :props="treeProps(f)"
              :placeholder="f.placeholder || '请选择'"
              :clearable="f.clearable !== false"
              :show-all-levels="f.showAllLevels !== false"
              style="width: 100%"
              @change="onFieldChange(f)"
            />
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
 *  - width / loading / submitText / labelWidth / destroyOnClose / appendToBody
 *
 * FieldDef:
 *  { prop, label, type, options?, dictCode?, placeholder?, required?, rules?,
 *    span?, multiple?, min?, max?, step?, ... }
 *  - type: 'input' | 'textarea' | 'number' | 'select' | 'tree-select' | 'dict' | 'switch' | 'date' | 'datetime'
 *  - tree-select: 层级数据下拉（内部用 el-cascader）。options 传树（children 嵌套），
 *    labelKey / valueKey / childrenKey 指定字段名（默认 label / id / children）；
 *    默认 checkStrictly=true 即任意层级可选，v-model 为节点 id（单值，非路径数组）
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
    destroyOnClose: { type: Boolean, default: true },
    /**
     * 是否把弹窗 DOM 挂到 document.body。**默认 true，且不要轻易改成 false。**
     *
     * 原因（2026-09-14 实测，详情抽屉内「新建参数」的表单被遮罩整片压住、点不动）：
     *  - `el-drawer` 的 `.el-drawer__wrapper` 是 `position:fixed` + 内联 `z-index:2001`
     *    ⇒ 自成一个**层叠上下文**。弹窗若内联渲染（appendToBody=false）就会被困在里面，
     *    只能拿到「抽屉 2001」这一层，弹窗自身的 z-index 再高也只在抽屉内部有效。
     *  - Element 的遮罩 `.v-modal` 是**全局单例**，位置/层级跟随**当前顶层弹窗**：
     *    抽屉先开（modal z=2000），弹窗再开时同一个 `.v-modal` 被提到 z=2002，
     *    而它挂在 body 的静态层上 ⇒ 直接盖住整个抽屉子树（含弹窗自己的表单）。
     *  - 实测命中测试：弹窗中心 `elementFromPoint` 命中的是 `DIV.v-modal`，
     *    10 个 `el-form-item` 全部 `hitInside:false`、`hitCls:"v-modal"`。
     * 挂到 body 后三者成为兄弟节点，层级单调递增：抽屉 2001 < 遮罩 2002 < 弹窗 2003。
     *
     * 顶层弹窗挂 body 与内联渲染**视觉完全一致**（`.el-dialog__wrapper` 本身就是
     * position:fixed 全屏居中），因此可以安全地作为默认值。
     */
    appendToBody: { type: Boolean, default: true }
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
            trigger: ['select', 'tree-select', 'dict', 'switch', 'date', 'datetime'].indexOf(f.type) >= 0 ? 'change' : 'blur'
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
          let def = f.multiple ? [] : ''
          if (f.type === 'switch') {
            def = f.inactiveValue !== undefined ? f.inactiveValue : 0
          } else if (f.type === 'tree-select') {
            // el-cascader 的「未选中」是 null，不是 ''（'' 会被当作无效值、不显示占位符）
            def = f.multiple ? [] : null
          }
          this.$set(this.form, f.prop, def)
        }
      })
      this.$nextTick(() => {
        if (this.$refs.form) this.$refs.form.clearValidate()
      })
    },
    /**
     * tree-select 的 el-cascader 配置。
     *  - checkStrictly 默认 true：允许选中**任意层级**的节点。父分组本身也是合法的归属目标，
     *    若为 false 则只有叶子节点可选，父分组下的数据将永远无法归类。
     *  - emitPath 恒为 false：v-model 绑定节点自身的值，而不是从根到该节点的路径数组。
     * 字段名可用 labelKey / valueKey / childrenKey 简写覆盖，也可整体传 treeProps。
     */
    treeProps(f) {
      return {
        value: f.valueKey || 'id',
        label: f.labelKey || 'label',
        children: f.childrenKey || 'children',
        checkStrictly: f.checkStrictly !== false,
        emitPath: false,
        ...(f.treeProps || {})
      }
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
