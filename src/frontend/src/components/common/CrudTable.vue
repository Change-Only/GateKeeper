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
      :height="height"
      :default-expand-all="defaultExpandAll"
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="selectable" type="selection" width="48" />
      <el-table-column v-if="showIndex" type="index" label="#" width="50" />

      <template v-for="col in columns">
        <el-table-column
          :key="col.prop || col.label"
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
          <template v-if="col.slot" slot-scope="scope">
            <slot :name="col.slot" :row="scope.row" :$index="scope.$index" :value="scope.row[col.prop]" />
          </template>
        </el-table-column>
      </template>

      <!-- 操作列（可选） -->
      <el-table-column
        v-if="$slots.actions"
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
        // fetch 异常由调用方 / 拦截器处理，这里仅保证表格不卡死
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
