<template>
  <div class="page-container mon-calllog">
    <div class="page-head">
      <h2>调用日志</h2>
      <span class="page-tag">网关转发调用记录 · 百万级数据支持异步导出</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="应用ID">
          <el-input v-model.number="query.appId" placeholder="应用ID" clearable style="width:120px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item label="接口ID">
          <el-input v-model.number="query.interfaceId" placeholder="接口ID" clearable style="width:120px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item label="客户端IP">
          <el-input v-model="query.clientIp" placeholder="IP" clearable style="width:150px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item label="HTTP状态">
          <el-input v-model.number="query.responseStatus" placeholder="如 200" clearable style="width:110px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条调用记录</span>
      <span class="spacer" />
      <el-button type="primary" size="small" icon="el-icon-download" :loading="exporting" @click="onCreateExport">导出 CSV</el-button>
      <el-button size="small" icon="el-icon-tickets" @click="openTasks">导出任务</el-button>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :page-size="20" :actions-width="100">
      <template #responseStatus="{row}">
        <el-tag size="small" :type="statusTag(row.responseStatus)" effect="plain">{{ row.responseStatus }}</el-tag>
      </template>
      <template #costTime="{row}">
        <span>{{ row.costTime != null ? row.costTime + ' ms' : '—' }}</span>
      </template>
      <template #flags="{row}">
        <el-tag v-if="row.isBlocked" size="mini" type="danger" effect="plain">已拦截</el-tag>
        <el-tag v-if="row.isRateLimited" size="mini" type="warning" effect="plain">限流</el-tag>
        <span v-if="!row.isBlocked && !row.isRateLimited">—</span>
      </template>
      <template #actions="{row}">
        <el-button type="text" size="mini" @click="onDetail(row)">详情</el-button>
      </template>
    </CrudTable>

    <!-- 详情 -->
    <el-dialog title="调用日志详情" :visible.sync="detail.visible" width="720px">
      <el-descriptions v-if="detail.row" :column="2" border size="small">
        <el-descriptions-item label="应用">{{ detail.row.appName || detail.row.appId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="接口路径">{{ detail.row.interfacePath || '—' }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ detail.row.requestMethod || '—' }}</el-descriptions-item>
        <el-descriptions-item label="客户端IP">{{ detail.row.clientIp || '—' }}</el-descriptions-item>
        <el-descriptions-item label="HTTP状态">{{ detail.row.responseStatus }}</el-descriptions-item>
        <el-descriptions-item label="总耗时">{{ detail.row.costTime != null ? detail.row.costTime + ' ms' : '—' }}</el-descriptions-item>
        <el-descriptions-item label="加密算法">{{ detail.row.encryptionAlgorithm || '无' }}</el-descriptions-item>
        <el-descriptions-item label="请求时间">{{ detail.row.requestTime || '—' }}</el-descriptions-item>
        <el-descriptions-item label="拦截原因" :span="2">{{ detail.row.blockReason || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div class="body-block" v-if="detail.row">
        <div class="body-title">请求入参</div>
        <pre class="body-pre">{{ detail.row.requestParams || '—' }}</pre>
        <div class="body-title">响应数据</div>
        <pre class="body-pre">{{ detail.row.responseData || '—' }}</pre>
      </div>
    </el-dialog>

    <!-- 导出任务列表 -->
    <el-dialog title="导出任务" :visible.sync="tasks.visible" width="680px">
      <div class="tasks-tip">
        <el-button size="mini" icon="el-icon-refresh" @click="loadTasks">刷新</el-button>
        <span>任务完成后可下载。</span>
      </div>
      <el-table v-loading="tasks.loading" :data="tasks.list" border size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column prop="totalRows" label="行数" width="90" />
        <el-table-column prop="status" label="状态" width="100">
          <template slot-scope="{row}">
            <el-tag size="small" :type="taskStatusTag(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column label="操作" width="90">
          <template slot-scope="{row}">
            <el-button v-if="row.status === 'SUCCESS'" type="text" size="mini" @click="download(row)">下载</el-button>
            <span v-else class="done-text">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script>
import {
  getLogList,
  getLogDetail,
  createLogExport,
  getExportTasks,
  downloadExportTask
} from '@/api/modules'

const TASK_TAG = { PENDING: 'info', RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger' }

export default {
  name: 'MonCalllog',
  data() {
    return {
      query: { appId: undefined, interfaceId: undefined, clientIp: undefined, responseStatus: undefined },
      total: 0,
      exporting: false,
      columns: [
        { prop: 'requestTime', label: '时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'appName', label: '应用', width: 130, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'interfacePath', label: '接口', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'requestMethod', label: '方法', width: 80, formatter: (v) => v || '—' },
        { prop: 'clientIp', label: '来源IP', width: 140, formatter: (v) => v || '—' },
        { prop: 'responseStatus', label: 'HTTP', width: 90, slot: 'responseStatus' },
        { prop: 'costTime', label: '耗时', width: 100, slot: 'costTime' },
        { prop: 'flags', label: '拦截/限流', width: 130, slot: 'flags' }
      ],
      detail: { visible: false, row: null },
      tasks: { visible: false, loading: false, list: [] }
    }
  },
  methods: {
    statusTag(code) {
      if (code == null) return 'info'
      if (code >= 200 && code < 300) return 'success'
      if (code >= 300 && code < 400) return 'warning'
      if (code >= 400) return 'danger'
      return 'info'
    },
    taskStatusTag(s) {
      return TASK_TAG[s] || 'info'
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getLogList({ current: page, size, ...rest })
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
      this.query.appId = undefined
      this.query.interfaceId = undefined
      this.query.clientIp = undefined
      this.query.responseStatus = undefined
      this.reload()
    },
    async onDetail(row) {
      this.detail.row = row
      this.detail.visible = true
      try {
        const res = await getLogDetail(row.id)
        if (res && res.data) this.detail.row = res.data
      } catch (e) {
        // 列表行数据已足够展示，详情拉取失败忽略
      }
    },
    async onCreateExport() {
      this.exporting = true
      try {
        await createLogExport({
          appId: this.query.appId,
          interfaceId: this.query.interfaceId,
          clientIp: this.query.clientIp,
          responseStatus: this.query.responseStatus
        })
        this.$message.success('导出任务已创建，可在「导出任务」中查看进度')
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.exporting = false
      }
    },
    async openTasks() {
      this.tasks.visible = true
      this.loadTasks()
    },
    async loadTasks() {
      this.tasks.loading = true
      try {
        const res = await getExportTasks({ current: 1, size: 50 })
        const data = (res && res.data) || {}
        this.tasks.list = data.records || []
      } catch (e) {
        this.tasks.list = []
      } finally {
        this.tasks.loading = false
      }
    },
    async download(row) {
      try {
        const res = await downloadExportTask(row.id)
        const blob = new Blob([res.data || res], { type: 'text/csv;charset=UTF-8' })
        const url = window.URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = row.fileName || ('call-log-' + row.id + '.csv')
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        window.URL.revokeObjectURL(url)
      } catch (e) {
        // 拦截器已弹错
      }
    }
  }
}
</script>

<style scoped>
.mon-calllog .filter-card { margin-bottom: 12px; }
.mon-calllog .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.mon-calllog .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.mon-calllog .toolbar .spacer { flex: 1; }
.mon-calllog .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.mon-calllog .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.mon-calllog .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.mon-calllog .body-block { margin-top: 16px; }
.mon-calllog .body-title { font-weight: 600; color: #17233d; margin: 10px 0 6px; }
.mon-calllog .body-pre { background: #f7f8fa; border-radius: 4px; padding: 10px; max-height: 200px; overflow: auto; font-size: 12px; margin: 0; white-space: pre-wrap; word-break: break-all; }
.mon-calllog .tasks-tip { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; color: #5c6b8a; font-size: 13px; }
.mon-calllog .done-text { color: #9aa7bf; font-size: 12px; }
</style>
