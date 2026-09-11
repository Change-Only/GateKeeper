<template>
  <div class="page-container mon-alarm">
    <div class="page-head">
      <h2>告警记录</h2>
      <span class="page-tag">网关运行态运营告警 · 未读 {{ unread }} 条</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="告警等级">
          <el-select v-model="query.level" placeholder="全部" clearable style="width:140px">
            <el-option value="INFO" label="INFO" />
            <el-option value="WARNING" label="WARNING" />
            <el-option value="CRITICAL" label="CRITICAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="告警来源">
          <el-select v-model="query.source" placeholder="全部" clearable style="width:160px">
            <el-option value="GATEWAY" label="网关运行" />
            <el-option value="SECURITY" label="安全检测" />
            <el-option value="RATE_LIMIT" label="限流" />
            <el-option value="SYSTEM" label="系统" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:140px">
            <el-option :value="0" label="未读" />
            <el-option :value="1" label="已读" />
            <el-option :value="2" label="已处理" />
            <el-option :value="3" label="已忽略" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条告警</span>
      <span class="spacer" />
      <el-button size="small" icon="el-icon-check" @click="onMarkAllRead">全部已读</el-button>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :page-size="20" :actions-width="200">
      <template #level="{row}">
        <el-tag size="small" :type="levelTag(row.level)" effect="plain">{{ row.level }}</el-tag>
      </template>
      <template #source="{row}">
        <span>{{ sourceLabel(row.source) }}</span>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'alertStatus'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <el-button v-if="row.status === 0" type="text" size="mini" @click="onRead(row)">标记已读</el-button>
        <PermButton v-if="row.status === 0 || row.status === 1" perm="alarm:handle" type="text" size="mini" @click="onHandle(row, 2)">已处理</PermButton>
        <PermButton v-if="row.status === 0 || row.status === 1" perm="alarm:handle" type="text" size="mini" @click="onHandle(row, 3)">忽略</PermButton>
        <span v-if="row.status === 2 || row.status === 3" class="done-text">已办结</span>
      </template>
    </CrudTable>
  </div>
</template>

<script>
import {
  getAlertList,
  getAlertUnread,
  markAlertRead,
  markAllAlertRead,
  handleAlert
} from '@/api/modules'
import { ALERT_LEVEL } from '@/utils/enum'

const SOURCE_LABEL = { GATEWAY: '网关运行', SECURITY: '安全检测', RATE_LIMIT: '限流', SYSTEM: '系统' }

export default {
  name: 'MonAlarm',
  data() {
    return {
      query: { level: undefined, source: undefined, status: undefined },
      total: 0,
      unread: 0,
      columns: [
        { prop: 'occurredAt', label: '触发时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'level', label: '级别', width: 100, slot: 'level' },
        { prop: 'source', label: '来源', width: 110, slot: 'source' },
        { prop: 'title', label: '告警内容', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'relatedAppName', label: '关联应用', width: 130, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'relatedIp', label: '关联IP', width: 140, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 100, slot: 'status' }
      ]
    }
  },
  created() {
    this.loadUnread()
  },
  methods: {
    levelTag(l) {
      return (ALERT_LEVEL[l] || {}).type || 'info'
    },
    sourceLabel(s) {
      return SOURCE_LABEL[s] || s || '—'
    },
    async loadUnread() {
      try {
        const res = await getAlertUnread()
        this.unread = (res && res.data) || 0
      } catch (e) {
        this.unread = 0
      }
    },
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getAlertList({ current: page, size, ...rest })
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
      this.query.level = undefined
      this.query.source = undefined
      this.query.status = undefined
      this.reload()
    },
    async onRead(row) {
      try {
        await markAlertRead(row.id)
        this.$message.success('已标记为已读')
        this.reload()
        this.loadUnread()
      } catch (e) {}
    },
    onHandle(row, status) {
      const label = status === 2 ? '已处理' : '已忽略'
      this.$prompt(`将告警 #${row.id} 标记为「${label}」，可填写备注`, '处置告警', { inputType: 'textarea' })
        .then(async({ value }) => {
          try {
            await handleAlert(row.id, status, value)
            this.$message.success('处置完成')
            this.reload()
            this.loadUnread()
          } catch (e) {}
        }).catch(() => {})
    },
    onMarkAllRead() {
      this.$confirm('确认将全部告警标记为已读？', '全部已读', { type: 'warning' })
        .then(async() => {
          try {
            await markAllAlertRead()
            this.$message.success('已全部标记为已读')
            this.reload()
            this.loadUnread()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.mon-alarm .filter-card { margin-bottom: 12px; }
.mon-alarm .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.mon-alarm .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.mon-alarm .toolbar .spacer { flex: 1; }
.mon-alarm .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.mon-alarm .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.mon-alarm .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.mon-alarm .done-text { color: #9aa7bf; font-size: 12px; }
</style>
