<template>
  <div class="page-container sys-notify">
    <div class="page-head">
      <h2>通知渠道</h2>
      <span class="page-tag">配置告警通知投递渠道（企业微信/钉钉/邮件/Webhook）</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="渠道名称" clearable style="width:200px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item label="渠道类型">
          <el-select v-model="query.channelType" placeholder="全部" clearable style="width:140px">
            <el-option v-for="t in channelTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width:120px">
            <el-option :value="1" label="启用" />
            <el-option :value="0" label="停用" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 条渠道</span>
      <span class="spacer" />
      <PermButton perm="sys:notify:update" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建渠道</PermButton>
    </div>

    <CrudTable
      ref="table"
      :columns="columns"
      :fetch="fetchData"
      :query="query"
      :actions-width="240"
    >
      <template #channelType="{row}">
        <el-tag size="small" :type="channelTypeMetaRow.get(row.channelType) || 'info'" effect="plain">
          {{ channelTypeLabel(row.channelType) }}
        </el-tag>
      </template>
      <template #status="{row}">
        <StatusTag :entity="'notifyChannel'" :value="row.status" />
      </template>
      <template #lastTestResult="{row}">
        <el-tag v-if="row.lastTestResult" size="small" :type="row.lastTestResult === 'SUCCESS' ? 'success' : 'danger'" effect="plain">
          {{ row.lastTestResult === 'SUCCESS' ? '成功' : '失败' }}
        </el-tag>
        <span v-else class="muted">未测试</span>
      </template>
      <template #actions="{row}">
        <PermButton perm="sys:notify:update" type="text" size="mini" @click="onTest(row)">测试</PermButton>
        <PermButton perm="sys:notify:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="sys:notify:update" type="text" size="mini" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑通知渠道' : '新建通知渠道'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'600px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-form-item v-if="form.channelType === 'WEBHOOK'" label="Webhook URL" prop="webhookUrl">
          <el-input v-model="form['webhookUrl']" placeholder="https://..." />
        </el-form-item>
        <el-form-item v-else-if="form.channelType === 'EMAIL'" label="收件邮箱" prop="emailAddress">
          <el-input v-model="form['emailAddress']" placeholder="alert@example.com" />
        </el-form-item>
        <el-form-item v-else-if="form.channelType === 'WECOM' || form.channelType === 'DINGTALK'" label="机器人 Key" prop="botKey">
          <el-input v-model="form['botKey']" show-password />
        </el-form-item>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getNotifyChannelList,
  createNotifyChannel,
  updateNotifyChannel,
  deleteNotifyChannel,
  testNotifyChannel
} from '@/api/modules'
import { ENUM_OPTIONS } from '@/utils/enum'

const channelTypeOptions = ENUM_OPTIONS.channelType || []
const channelTypeMetaRow = new Map(channelTypeOptions.map((o) => [o.value, o.value === 'EMAIL' ? 'warning' : 'primary']))
const channelTypeLabel = (v) => (channelTypeOptions.find((o) => o.value === v) || {}).label || v

export default {
  name: 'SysNotify',
  data() {
    return {
      channelTypeOptions,
      channelTypeMetaRow,
      channelTypeLabel,
      query: { keyword: '', channelType: undefined, status: undefined },
      total: 0,
      columns: [
        { prop: 'channelName', label: '渠道名称', minWidth: 140 },
        { prop: 'channelType', label: '类型', width: 110, slot: 'channelType' },
        { prop: 'status', label: '状态', width: 80, slot: 'status' },
        { prop: 'lastTestTime', label: '最后测试时间', width: 170, formatter: (v) => v || '—' },
        { prop: 'lastTestResult', label: '测试结果', width: 90, slot: 'lastTestResult' },
        { prop: 'remark', label: '备注', minWidth: 160, showOverflowTooltip: true }
      ],
      dialog: {
        visible: false,
        loading: false,
        form: {},
        fields: [],
        rules: {}
      }
    }
  },
  methods: {
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, ...rest } = params
        const res = await getNotifyChannelList({
          pageNum: page,
          pageSize: size,
          ...rest
        })
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
      this.query.keyword = ''
      this.query.channelType = undefined
      this.query.status = undefined
      this.reload()
    },
    baseFields() {
      return [
        { prop: 'channelName', label: '渠道名称', type: 'input', required: true, span: 12, placeholder: '请输入渠道名称' },
        {
          prop: 'channelType', label: '渠道类型', type: 'select', required: true, span: 12,
          options: channelTypeOptions
        },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 },
        { prop: 'remark', label: '备注', type: 'textarea', span: 24, rows: 2 }
      ]
    },
    onCreate() {
      this.dialog.form = { channelName: '', channelType: 'WECOM', status: 1, remark: '' }
      this.dialog.fields = this.baseFields()
      this.dialog.rules = {}
      this.dialog.visible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.baseFields()
      this.dialog.rules = {}
      this.dialog.visible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateNotifyChannel(form.id, form)
          this.$message.success('渠道已更新')
        } else {
          await createNotifyChannel(form)
          this.$message.success('渠道已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {} finally {
        this.dialog.loading = false
      }
    },
    onDelete(row) {
      this.$confirm(`确认删除渠道「${row.channelName}」？`, '删除确认', { type: 'warning' })
        .then(async() => {
          try {
            await deleteNotifyChannel(row.id)
            this.$message.success('已删除')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    },
    async onTest(row) {
      try {
        this.$message.info(`已发起测试：${row.channelName}`)
        await testNotifyChannel(row.id)
        this.$message.success('测试请求已提交，请稍后查看结果')
        this.reload()
      } catch (e) {}
    }
  }
}
</script>

<style scoped>
.sys-notify .filter-card { margin-bottom: 12px; }
.sys-notify .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.sys-notify .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.sys-notify .toolbar .spacer { flex: 1; }
.sys-notify .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-notify .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-notify .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.muted { color: #9aa7bf; font-size: 12px; }
</style>