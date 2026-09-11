<template>
  <div class="page-container perm-audit">
    <div class="page-head">
      <h2>操作审计</h2>
      <span class="page-tag">管理后台操作留痕 · 高危操作强制记录</span>
    </div>

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
      <template #costTime="{row}">
        <span>{{ row.costTime != null ? row.costTime + ' ms' : '—' }}</span>
      </template>
    </CrudTable>
  </div>
</template>

<script>
import { getOperationLogList, exportOperationLog } from '@/api/modules'

const TYPE_OPTIONS = ['CREATE', 'UPDATE', 'DELETE', 'LOGIN', 'LOGOUT']
const MODULE_OPTIONS = ['APP', 'INTERFACE', 'PERMISSION', 'SECURITY', 'SYSTEM']
const TYPE_TAG = { CREATE: 'success', UPDATE: 'warning', DELETE: 'danger', LOGIN: 'info', LOGOUT: 'info' }

export default {
  name: 'PermAudit',
  data() {
    return {
      TYPE_OPTIONS,
      MODULE_OPTIONS,
      query: { operationType: undefined, operationModule: undefined },
      total: 0,
      exporting: false,
      columns: [
        { prop: 'createdAt', label: '时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'operatorName', label: '操作人', width: 110, formatter: (v) => v || '—' },
        { prop: 'clientIp', label: '操作IP', width: 140, formatter: (v) => v || '—' },
        { prop: 'operationModule', label: '模块', width: 110, formatter: (v) => v || '—' },
        { prop: 'operationType', label: '操作', width: 100, slot: 'operationType' },
        { prop: 'operationDesc', label: '描述', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'requestMethod', label: '方法', width: 90, formatter: (v) => v || '—' },
        { prop: 'requestUrl', label: '请求URL', minWidth: 200, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'costTime', label: '耗时', width: 100, slot: 'costTime' }
      ]
    }
  },
  methods: {
    typeTag(t) {
      return TYPE_TAG[t] || 'info'
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getOperationLogList({ current: page, size, ...rest })
        const data = (res && res.data) || {}
        self.total = data.total || 0
        return { list: data.records || [], total: data.total || 0 }
      }
    }(),
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
.perm-audit .filter-card { margin-bottom: 12px; }
.perm-audit .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.perm-audit .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.perm-audit .toolbar .spacer { flex: 1; }
.perm-audit .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.perm-audit .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.perm-audit .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
