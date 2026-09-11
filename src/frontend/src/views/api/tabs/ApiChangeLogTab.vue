<template>
  <div class="api-tab">
    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchLogs"
      :query="query"
      row-key="id"
      :show-pagination="false"
    >
      <template #changeType="{ row }">
        <el-tag :type="changeTypeMeta(row.changeType).type" size="small">{{ changeTypeMeta(row.changeType).label }}</el-tag>
      </template>
      <template #value="{ row }">
        <span class="mono old">{{ row.oldValue || '—' }}</span>
        <i class="el-icon-right" />
        <span class="mono new">{{ row.newValue || '—' }}</span>
      </template>
    </CrudTable>
  </div>
</template>

<script>
/**
 * 接口变更历史 Tab（T05 Phase 1 · api-list 详情 · 只读）
 * 对接 /api-change-log/list。变更由后端 AOP 自动追加，前端只读展示。
 */
import { getApiChangeLogList } from '@/api/modules'

const CHANGE_TYPES = {
  CREATE: { label: '创建', type: 'success' },
  UPDATE: { label: '更新', type: 'primary' },
  PUBLISH: { label: '发布', type: 'warning' },
  OFFLINE: { label: '下线', type: 'info' },
  DELETE: { label: '删除', type: 'danger' }
}

export default {
  name: 'ApiChangeLogTab',
  props: {
    apiId: { type: [Number, String], default: null }
  },
  data() {
    return {
      query: { apiId: this.apiId },
      columns: [
        { prop: 'changeType', label: '类型', width: 80, slot: 'changeType' },
        { prop: 'fieldLabel', label: '字段', width: 120 },
        { prop: 'value', label: '变更前 → 后', minWidth: 240, slot: 'value' },
        { prop: 'operatorName', label: '操作人', width: 100 },
        { prop: 'changeReason', label: '变更原因', minWidth: 160, showOverflowTooltip: true },
        { prop: 'createTime', label: '时间', width: 160, formatter: (v) => v || '—' }
      ]
    }
  },
  watch: {
    apiId(v) { this.query = { apiId: v } }
  },
  methods: {
    changeTypeMeta(v) {
      return CHANGE_TYPES[v] || { label: String(v), type: 'info' }
    },
    async fetchLogs() {
      const res = await getApiChangeLogList({ apiId: this.apiId })
      const list = res.data || []
      return { list, total: list.length }
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }
.mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; }
.mono.old { color: #9aa7bf; }
.mono.new { color: #17233d; }
</style>
