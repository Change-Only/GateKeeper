<template>
  <div class="page-container sys-log">
    <div class="page-head">
      <h2>日志与审计</h2>
      <span class="page-tag">系统设置视角下的操作审计 · 归档策略（静态展示，后续由参数配置提供）</span>
    </div>

    <el-card shadow="never" class="policy-card">
      <div class="policy-title">归档与留存策略</div>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="调用日志热数据保留">{{ policy.hotRetentionDays }} 天</el-descriptions-item>
        <el-descriptions-item label="冷归档周期">{{ policy.archiveCycle }}</el-descriptions-item>
        <el-descriptions-item label="脱敏开关">{{ policy.maskEnabled ? '开启' : '关闭' }}</el-descriptions-item>
        <el-descriptions-item label="审计日志导出">CSV（UTF-8 BOM）</el-descriptions-item>
      </el-descriptions>
      <div class="policy-tip">以上策略为前端静态展示；正式值将随后端 <code>/sys/config</code>（configGroup=LOG）提供后对接。</div>
    </el-card>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="操作类型">
          <el-select v-model="query.operationType" placeholder="全部" clearable style="width:150px">
            <el-option v-for="t in TYPE_OPTIONS" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作模块">
          <el-select v-model="query.operationModule" placeholder="全部" clearable style="width:160px">
            <el-option v-for="m in MODULE_OPTIONS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条审计记录</span>
      <span class="spacer" />
      <PermButton perm="audit:export" type="primary" size="small" icon="el-icon-download" :loading="exporting" @click="onExport">导出 CSV</PermButton>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :page-size="20">
      <template #operationType="{row}">
        <el-tag size="small" effect="plain" :type="typeTag(row.operationType)">{{ row.operationType }}</el-tag>
      </template>
    </CrudTable>
  </div>
</template>

<script>
import { getOperationLogList, exportOperationLog, getOperationLogFilterOptions } from '@/api/modules'
import { OPERATION_MODULE_OPTIONS, OPERATION_TYPE_OPTIONS, OPERATION_TYPE_TAG } from '@/utils/enum'

export default {
  name: 'SysLog',
  data() {
    return {
      // 初值用 enum.js 的本地兜底；created 里再用后端 DISTINCT 覆盖（见 loadFilterOptions）。
      // 🔴 两个下拉的取值不得在前端写死为"完整清单"——模块值域由后端 controller 路径首段决定。
      TYPE_OPTIONS: OPERATION_TYPE_OPTIONS.slice(),
      MODULE_OPTIONS: OPERATION_MODULE_OPTIONS.slice(),
      query: { operationType: undefined, operationModule: undefined },
      total: 0,
      exporting: false,
      policy: { hotRetentionDays: 30, archiveCycle: '按月', maskEnabled: true },
      columns: [
        { prop: 'createdAt', label: '时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'operatorName', label: '操作人', width: 110, formatter: (v) => v || '—' },
        { prop: 'operationModule', label: '模块', width: 110, formatter: (v) => v || '—' },
        { prop: 'operationType', label: '操作', width: 100, slot: 'operationType' },
        { prop: 'operationDesc', label: '描述', minWidth: 200, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'clientIp', label: '来源IP', width: 140, formatter: (v) => v || '—' }
      ]
    }
  },
  created() {
    this.loadFilterOptions()
  },
  methods: {
    /**
     * 拉取审计筛选候选项（后端按库里 DISTINCT 下发真实的模块/类型）。
     *
     * 🔴 失败时**静默回退**到 enum.js 的本地兜底常量 —— 筛选下拉只是辅助功能，
     *    不能因为它拉不到就让整页不可用；也**不弹错误**（用户没做错任何事）。
     */
    async loadFilterOptions() {
      try {
        const res = await getOperationLogFilterOptions()
        const data = (res && res.data) || {}
        if (Array.isArray(data.modules) && data.modules.length) this.MODULE_OPTIONS = data.modules
        if (Array.isArray(data.types) && data.types.length) this.TYPE_OPTIONS = data.types
      } catch (e) {
        // 静默：保留本地兜底
      }
    },
    async fetchData(params) {
      const { page, size, ...rest } = params
      const res = await getOperationLogList({ current: page, size, ...rest })
      const data = (res && res.data) || {}
      this.total = data.total || 0
      return { list: data.records || [], total: data.total || 0 }
    },
    typeTag(t) {
      return OPERATION_TYPE_TAG[t] || 'info'
    },
    reload() {
      this.$nextTick(() => {
        if (this.$refs.table && this.$refs.table.reload) this.$refs.table.reload()
      })
    },
    resetQuery() {
      this.query.operationType = undefined
      this.query.operationModule = undefined
      this.reload()
    },
    async onExport() {
      this.exporting = true
      try {
        const res = await exportOperationLog({
          operationType: this.query.operationType,
          operationModule: this.query.operationModule
        })
        const blob = new Blob([res.data || res], { type: 'text/csv;charset=UTF-8' })
        const url = window.URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = 'operation-log.csv'
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        window.URL.revokeObjectURL(url)
        this.$message.success('导出已开始')
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.exporting = false
      }
    }
  }
}
</script>

<style scoped>
.sys-log .policy-card { margin-bottom: 12px; }
.sys-log .policy-title { font-weight: 600; color: #17233d; margin-bottom: 10px; }
.sys-log .policy-tip { margin-top: 10px; font-size: 12px; color: #9aa7bf; }
.sys-log .policy-tip code { background: #f0f3f9; padding: 1px 6px; border-radius: 4px; }
.sys-log .filter-card { margin-bottom: 12px; }
.sys-log .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-log .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-log .toolbar .spacer { flex: 1; }
.sys-log .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-log .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-log .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
