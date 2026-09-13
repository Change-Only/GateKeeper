<template>
  <div class="crud-table">
    <!-- 工具栏：筛选 / 主操作插槽 -->
    <div v-if="$slots.toolbar" class="ct-toolbar">
      <slot name="toolbar" />
    </div>

    <el-table
      ref="table"
      v-loading="loading"
      :data="list"
      :row-key="rowKey"
      :size="size"
      :border="border"
      :stripe="stripe"
      :height="height || undefined"
      :default-expand-all="defaultExpandAll"
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="selectable" type="selection" width="48" />
      <el-table-column v-if="showIndex" type="index" label="#" width="50" />

      <template v-for="col in columns">
        <!--
          🔴 必须拆成「插槽列 / 普通列」两个 el-table-column 分支，**不可**合并为
          「一个列 + 用 v-if 包住 slot-scope 模板」的写法：
          Vue 只要解析到带 slot-scope 的 <template>，就会注册 $scopedSlots.default —— 即使 v-if 为假，
          该函数依然存在（只是返回 undefined）。而 Element 的 TableColumn 只要检测到 default 作用域插槽，
          就改走「自定义渲染」路径、不再渲染 prop 值 ⇒ **所有普通列单元格集体空白**
          （实测：应用列表的「应用名称/描述/过期时间/创建时间」全为空 div，
           仅带 slot 的 AppKey/状态 正常；formatter 也因此失效）。
        -->
        <el-table-column
          v-if="col.slot"
          :key="(col.prop || col.label) + '-slot'"
          :prop="col.prop"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :fixed="col.fixed"
          :align="col.align || 'left'"
          :sortable="col.sortable"
          :show-overflow-tooltip="col.showOverflowTooltip !== false"
          :formatter="col.formatter ? fmtBridge(col) : undefined"
        >
          <!-- 列作用域插槽：父组件通过具名插槽（slot 名 = col.slot）自定义渲染 -->
          <template slot-scope="scope">
            <slot :name="col.slot" :row="scope.row" :$index="scope.$index" :value="scope.row[col.prop]" />
          </template>
        </el-table-column>
        <!-- 普通列：不提供任何作用域插槽，交回 Element 默认渲染（prop 值 + formatter 均生效） -->
        <el-table-column
          v-else
          :key="(col.prop || col.label) + '-def'"
          :prop="col.prop"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :fixed="col.fixed"
          :align="col.align || 'left'"
          :sortable="col.sortable"
          :show-overflow-tooltip="col.showOverflowTooltip !== false"
          :formatter="col.formatter ? fmtBridge(col) : undefined"
        />
      </template>

      <!--
        操作列（可选）
        🔴 判定必须用 $scopedSlots，不可写成 $slots：
        各页面传的是**带作用域**的插槽 `<template #actions="{ row }">`，Vue 2 只把它注册进
        $scopedSlots，$slots 里根本没有 actions 这个键（实测：CrudTable 实例
        $slots=["toolbar"]、$scopedSlots=["toolbar","appKey","status","actions"]）。
        原写法导致操作列被整个 v-if 掉 ⇒ **全站列表页都没有「详情/编辑/停用/删除」按钮**
        （实测 rowBtnCount=0，表格 th 里也没有「操作」表头）。
        $scopedSlots 在有作用域与无作用域两种写法下都成立，故统一用它判定。
      -->
      <el-table-column
        v-if="$scopedSlots.actions"
        label="操作"
        :width="actionsWidth"
        :fixed="actionsFixed"
        class-name="ct-actions"
      >
        <template slot-scope="scope">
          <slot name="actions" :row="scope.row" :$index="scope.$index" />
        </template>
      </el-table-column>

      <!-- 空态 -->
      <template slot="empty">
        <slot name="empty">
          <EmptyState :title="emptyText" />
        </slot>
      </template>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-if="showPagination"
      class="ct-pagination"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :current-page="page"
      :page-size="sizeState"
      :page-sizes="pageSizes"
      @current-change="onPage"
      @size-change="onSize"
    />
  </div>
</template>

<script>
/**
 * CrudTable —— 通用分页表格构件（T05 §4）
 * ------------------------------------------------------------------
 * 封装 el-table + 分页 + 列自定义插槽 + 空态 + 加载态，后端无关：
 * 仅需提供一个 fetch(params) 函数，返回 { list, total } 即可。
 *
 * Props
 *  - columns  Array<ColumnDef>   列定义（见下方 ColumnDef）
 *  - fetch    Function           分页拉取函数：(params)=>Promise<{list,total}>
 *  - rowKey   String             行 key 字段，默认 'id'
 *  - query    Object             外部筛选条件，变化即重新加载（深监听）
 *  - pageSize / pageSizes        分页配置
 *  - selectable / showIndex      是否显示选择列 / 序号列
 *  - showPagination / autoLoad / height / size / border / stripe / defaultExpandAll
 *
 * ColumnDef:
 *  { prop, label, width?, minWidth?, fixed?, align?, sortable?, showOverflowTooltip?, slot?, formatter? }
 *  - slot:       具名插槽名，父组件用 <template #slotName="{row}"> 自定义单元格
 *  - formatter:  (value, row) => string，便捷格式化（非 el-table 原生签名）
 *
 * Events
 *  - selection-change(rows)
 *  - loaded({list, total})
 *
 * 暴露方法（ref）：reload() 重置到第 1 页加载；refresh() 刷新当前页；
 *                  getSelection()；clearSelection()
 */
export default {
  name: 'CrudTable',
  props: {
    columns: { type: Array, default: () => [] },
    fetch: { type: Function, required: true },
    rowKey: { type: String, default: 'id' },
    query: { type: Object, default: () => ({}) },
    pageSize: { type: Number, default: 10 },
    pageSizes: { type: Array, default: () => [10, 20, 50] },
    selectable: { type: Boolean, default: false },
    showIndex: { type: Boolean, default: false },
    showPagination: { type: Boolean, default: true },
    autoLoad: { type: Boolean, default: true },
    height: { type: [String, Number], default: '' },
    size: { type: String, default: '' },
    border: { type: Boolean, default: true },
    stripe: { type: Boolean, default: true },
    defaultExpandAll: { type: Boolean, default: false },
    actionsWidth: { type: [String, Number], default: 160 },
    actionsFixed: { type: [String, Boolean], default: 'right' },
    emptyText: { type: String, default: '暂无数据' }
  },
  data() {
    return {
      loading: false,
      list: [],
      total: 0,
      page: 1,
      sizeState: this.pageSize,
      selection: []
    }
  },
  watch: {
    query: {
      deep: true,
      handler() {
        // 外部筛选条件变化 -> 回到第 1 页重新加载
        this.reload()
      }
    }
  },
  mounted() {
    if (this.autoLoad) this.reload()
  },
  methods: {
    // 将多种后端返回形状归一化为 { list, total }
    normalize(res) {
      if (!res) return { list: [], total: 0 }
      if (Array.isArray(res.list)) return { list: res.list, total: res.total || 0 }
      if (res.data) {
        const d = res.data
        // 裸数组响应（Result<List<T>> 未被外层 fetch 拆包，data 本身即列表）。
        // 仅新增此分支，其余判定顺序不变（T09-A，见 docs/T08-ClassG-响应形状审计.md §6-②）
        if (Array.isArray(d)) return { list: d, total: d.length }
        if (Array.isArray(d.list)) return { list: d.list, total: d.total || 0 }
        if (Array.isArray(d.records)) return { list: d.records, total: d.total || 0 }
      }
      if (Array.isArray(res.records)) return { list: res.records, total: res.total || 0 }
      return { list: [], total: 0 }
    },
    // el-table formatter 桥接：把 (row, column, cellValue, index) 转成 (value, row)
    fmtBridge(col) {
      return (row, column, cellValue) => col.formatter(cellValue, row)
    },
    async doFetch() {
      this.loading = true
      try {
        const params = { page: this.page, size: this.sizeState, ...this.query }
        const res = await this.fetch(params)
        const { list, total } = this.normalize(res)
        this.list = list
        this.total = total
        this.$emit('loaded', { list, total })
      } catch (e) {
        // 🔴 这里必须留下 console.error，不能静默：
        // fetch 抛错时把列表清空是对的（保证表格不卡死），但**静默**会让整类缺陷隐形——
        // 2026-09-13 实测：14 个页面把 fetch 写成 `function(){ const self=this; ... }()` 的
        // 立即执行函数，严格模式下 this 是 undefined（不是组件实例），于是 `self.total = ...`
        // 抛 TypeError；该异常被此处吞掉后，表现为「接口明明返回数据、页面却恒显『共 0 条』
        // 且控制台一片安静」，排查成本极高。保留错误输出，让此类问题一眼可见。
        // eslint-disable-next-line no-console
        console.error('[CrudTable] fetch 失败：', e)
        this.list = []
        this.total = 0
      } finally {
        this.loading = false
      }
    },
    onPage(p) {
      this.page = p
      this.doFetch()
    },
    onSize(s) {
      this.sizeState = s
      this.page = 1
      this.doFetch()
    },
    onSelectionChange(rows) {
      this.selection = rows
      this.$emit('selection-change', rows)
    },
    /** 重置到第 1 页并加载 */
    reload() {
      this.page = 1
      this.doFetch()
    },
    /** 刷新当前页 */
    refresh() {
      this.doFetch()
    },
    getSelection() {
      return this.selection
    },
    clearSelection() {
      if (this.$refs.table) this.$refs.table.clearSelection()
    }
  }
}
</script>

<style scoped>
.ct-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.ct-toolbar .spacer { flex: 1; }
.ct-pagination { text-align: right; margin-top: 16px; }
</style>
