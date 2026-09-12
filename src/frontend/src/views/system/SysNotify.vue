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
      <PermButton perm="notify_channel:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建渠道</PermButton>
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
        <PermButton perm="notify_channel:test" type="text" size="mini" @click="onTest(row)">测试</PermButton>
        <PermButton perm="sys:notify:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="notify_channel:delete" type="text" size="mini" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <!--
      T10-N2 动态表单（契约：docs/T09-告警通知渠道扩展-技术方案.md §2.2 冻结 Schema v1 + §4）
      - channelType 从 CrudDialog 的 fields 移入本插槽自渲染：el-select 挂 @change 钩子，
        实现「类型切换清空动态散字段」（§4.3），共享组件 CrudDialog 零改动（方案 R7）。
      - channelType 与动态字段的校验经父级 rules 通道传入（CrudDialog 的 rules prop
        会按 prop 合并进 formRules，extra 插槽内带 prop 的 el-form-item 同样参与校验）。
    -->
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
      <template #extra="{ form }">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="渠道类型" prop="channelType">
              <el-select v-model="form.channelType" placeholder="请选择" style="width:100%" @change="onChannelTypeChange(form)">
                <el-option v-for="t in channelTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
              </el-select>
            </el-form-item>
          </el-col>

          <!-- WECOM / DINGTALK / WEBHOOK：完整 Webhook URL（Schema v1：{ "webhook": "https://..." }） -->
          <template v-if="isWebhookType(form.channelType)">
            <el-col :span="24">
              <el-form-item label="Webhook 地址" prop="webhook">
                <el-input v-model="form.webhook" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx" clearable />
              </el-form-item>
            </el-col>
          </template>

          <!-- EMAIL：SMTP 完整配置（Schema v1：smtpHost/smtpPort/ssl/username/password/from/to） -->
          <template v-else-if="form.channelType === 'EMAIL'">
            <el-col :span="12">
              <el-form-item label="SMTP 服务器" prop="smtpHost">
                <el-input v-model="form.smtpHost" placeholder="smtp.example.com" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="端口" prop="smtpPort" label-width="60px">
                <el-input-number v-model="form.smtpPort" :min="1" :max="65535" :step="1" controls-position="right" style="width:100%" />
              </el-form-item>
            </el-col>
            <el-col :span="6">
              <el-form-item label="SSL" prop="ssl" label-width="50px">
                <el-switch v-model="form.ssl" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="认证账号" prop="username">
                <el-input v-model="form.username" placeholder="alert@example.com（匿名可留空）" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="认证密码" prop="password">
                <el-input v-model="form.password" show-password placeholder="未修改请保持原样" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="发件人" prop="from">
                <el-input v-model="form.from" placeholder="alert@example.com" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="收件人" prop="to">
                <el-input v-model="form.to" placeholder="ops-a@example.com,ops-b@example.com" clearable />
              </el-form-item>
            </el-col>
          </template>
        </el-row>
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

/**
 * 动态散字段默认值（T09 §2.2 Schema v1 的全集；新建渠道时全部初始化，
 * 保证传给 CrudDialog 的 model 含全部 key —— onOpen 深拷贝后属性均为响应式）
 */
const EMPTY_CONFIG_FIELDS = {
  webhook: '',
  smtpHost: '',
  smtpPort: 465,
  ssl: true,
  username: '',
  password: '',
  from: '',
  to: ''
}

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
        { prop: 'updatedAt', label: '更新时间', width: 170, formatter: (v) => v || '—' }
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
        // 后端 GET /notify-channel/list 返回 Result<List<NotifyChannel>>：data 是**裸数组**，
        // 无 {records,total} 分页信封（该端点也不分页）。若按信封解析，data.records 恒 undefined
        // ⇒ 列表恒空且不报错。此处与 SysAlarm/MonBlock 的既有写法保持一致。
        const { page, size, ...rest } = params
        const res = await getNotifyChannelList(rest)
        const list = (res && res.data) || []
        self.total = list.length
        return { list, total: list.length }
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
    /** WECOM / DINGTALK / WEBHOOK 共用 { webhook } 单字段配置（NotifySender 读 webhook/url key） */
    isWebhookType(t) {
      return t === 'WECOM' || t === 'DINGTALK' || t === 'WEBHOOK'
    },
    baseFields() {
      // channelType 移入 #extra 插槽自渲染（挂 @change 钩子）；remark 已移除（表无此列，伪字段）
      return [
        { prop: 'channelName', label: '渠道名称', type: 'input', required: true, span: 12, placeholder: '请输入渠道名称' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    /** 按当前渠道类型生成校验规则（经 CrudDialog 的 rules prop 合并生效，含 extra 插槽内字段） */
    buildRules(type) {
      const rules = {
        channelType: [{ required: true, message: '请选择渠道类型', trigger: 'change' }]
      }
      if (this.isWebhookType(type)) {
        rules.webhook = [{ required: true, message: '请填写 Webhook 地址', trigger: 'blur' }]
      } else if (type === 'EMAIL') {
        rules.smtpHost = [{ required: true, message: '请填写 SMTP 服务器', trigger: 'blur' }]
        rules.from = [{ required: true, message: '请填写发件人地址', trigger: 'blur' }]
        rules.to = [{ required: true, message: '请填写收件人地址', trigger: 'blur' }]
      }
      return rules
    },
    /** 解析 channelConfig JSON（容错：空/非法 JSON/已是对象 均安全返回 {}） */
    parseChannelConfig(raw) {
      if (!raw) return {}
      if (typeof raw === 'object') return raw
      try {
        const parsed = JSON.parse(raw)
        return (parsed && typeof parsed === 'object') ? parsed : {}
      } catch (e) {
        return {}
      }
    },
    /**
     * 提交前组装：散字段 → channelConfig JSON 字符串（T09 缺陷①③ 根因修复）。
     * key 名与冻结 Schema v1 一字不差（后端按这些 key 解析与加密）。
     * password 掩码策略：编辑回显的是后端脱敏掩码，未修改时**原样传回**，
     * 由后端 §3.3 掩码防线（识别掩码格式则跳过该字段更新）兜底 —— 与冻结契约一致。
     */
    buildChannelConfig(form) {
      if (this.isWebhookType(form.channelType)) {
        return JSON.stringify({ webhook: (form.webhook || '').trim() })
      }
      if (form.channelType === 'EMAIL') {
        return JSON.stringify({
          smtpHost: (form.smtpHost || '').trim(),
          smtpPort: Number(form.smtpPort) || 465,
          ssl: form.ssl !== false,
          username: (form.username || '').trim(),
          password: form.password || '',
          from: (form.from || '').trim(),
          to: (form.to || '').trim()
        })
      }
      // 未知类型：原样保留库中已有配置串，避免误清
      return form.channelConfig != null ? form.channelConfig : null
    },
    /** 类型切换：清空全部动态散字段 + 重建该类型的校验规则（防 SMTP 残留进 webhook 渠道） */
    onChannelTypeChange(form) {
      Object.keys(EMPTY_CONFIG_FIELDS).forEach((k) => {
        form[k] = JSON.parse(JSON.stringify(EMPTY_CONFIG_FIELDS[k]))
      })
      this.dialog.rules = this.buildRules(form.channelType)
    },
    onCreate() {
      this.dialog.form = {
        channelName: '',
        channelType: 'WECOM',
        status: 1,
        channelConfig: null,
        ...JSON.parse(JSON.stringify(EMPTY_CONFIG_FIELDS))
      }
      this.dialog.fields = this.baseFields()
      this.dialog.rules = this.buildRules('WECOM')
      this.dialog.visible = true
    },
    onEdit(row) {
      const cfg = this.parseChannelConfig(row.channelConfig)
      this.dialog.form = {
        id: row.id,
        channelName: row.channelName || '',
        channelType: row.channelType,
        status: row.status === 0 ? 0 : 1,
        channelConfig: row.channelConfig != null ? row.channelConfig : null, // 原始串兜底（未知类型不误清）
        ...JSON.parse(JSON.stringify(EMPTY_CONFIG_FIELDS)),
        // 回显反填（§4.2）：解析 channelConfig JSON 反填散字段；
        // password 为后端脱敏掩码，原样回显，未改则原样传回
        webhook: cfg.webhook || '',
        smtpHost: cfg.smtpHost || '',
        smtpPort: cfg.smtpPort != null ? Number(cfg.smtpPort) : 465,
        ssl: cfg.ssl !== undefined ? !!cfg.ssl : true,
        username: cfg.username || '',
        password: cfg.password || '',
        from: cfg.from || '',
        to: cfg.to || ''
      }
      this.dialog.fields = this.baseFields()
      this.dialog.rules = this.buildRules(row.channelType)
      this.dialog.visible = true
    },
    async onSubmit(form) {
      // 只提交实体真实字段（channelName/channelType/status/channelConfig），
      // 散字段（webhook/smtpHost…）已序列化进 channelConfig，不再随 form 平铺
      const payload = {
        channelName: (form.channelName || '').trim(),
        channelType: form.channelType,
        status: form.status ? 1 : 0,
        channelConfig: this.buildChannelConfig(form)
      }
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateNotifyChannel(form.id, payload)
          this.$message.success('渠道已更新')
        } else {
          await createNotifyChannel(payload)
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
