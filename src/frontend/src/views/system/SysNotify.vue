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

/** 复用 { webhook } 形状的渠道类型（单一来源：isWebhookType 与 schemaForType 共用，防止两处字面量漂移） */
const WEBHOOK_TYPES = ['WECOM', 'DINGTALK', 'WEBHOOK']

/**
 * 渠道配置 Schema 单一来源（T09 §2.2 冻结 v1 + 后端读侧别名）。
 *
 * - canonical：本页表单渲染、保存时**写出**的规范 key；
 * - aliases  ：后端读侧等价的别名 key（值类型合法但非本页规范形状）——
 *   WebhookSender.java:73 以 {@code webhook} 优先、回退 {@code url}；
 *   EmailSmtpSender.java:87-88 以 {@code smtpHost} 优先回退 {@code host}、{@code smtpPort} 回退 {@code port}。
 *   编辑回显时把这些别名**归一迁移**到 canonical key（值不丢）。
 *
 * analyzeConfig 的「已知 key 白名单」与 buildChannelConfig 的「写出 key」**均从本常量派生**，
 * 二者不各存一份清单 —— 漏 key ⇒ 对健康配置假报警；多 key ⇒ 未知 key 被静默丢弃。
 */
const CONFIG_SCHEMA = {
  WEBHOOK: {
    canonical: ['webhook'],
    aliases: { url: 'webhook' }
  },
  EMAIL: {
    canonical: ['smtpHost', 'smtpPort', 'ssl', 'username', 'password', 'from', 'to'],
    aliases: { host: 'smtpHost', port: 'smtpPort' }
  }
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
        rules: {},
        // 静默覆盖防线（T10-N2b，SE-5 设计、lead 复核采纳）：描述「库中原配置」相对当前表单的丢失风险，
        // 与用户当前所选 channelType 无关 —— 保存始终会整串重建该列，故类型切换不清此标记。
        configLossy: false,
        configLossyReason: ''
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
      return WEBHOOK_TYPES.indexOf(t) !== -1
    },
    /** 该类型对应的 Schema 描述符（未知类型返回 null）；canonical/aliases 见模块常量 CONFIG_SCHEMA */
    schemaForType(type) {
      if (this.isWebhookType(type)) return CONFIG_SCHEMA.WEBHOOK
      if (type === 'EMAIL') return CONFIG_SCHEMA.EMAIL
      return null
    },
    /** 该类型的「已知 key」全集（canonical ∪ aliases）—— analyzeConfig 判 lossy 的白名单 */
    knownKeysForType(type) {
      const schema = this.schemaForType(type)
      if (!schema) return []
      return schema.canonical.concat(Object.keys(schema.aliases))
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
    /**
     * 分析库中原始配置串（**输入必须是 row.channelConfig 原串**，不能先 parse —— 否则丢失
     * 「能否解析」这一信息，恰好漏掉主场景）。返回 { lossy, reason, cfg }：
     * - 空/null            → 无可丢失，非 lossy；
     * - 非对象/JSON.parse 失败 → lossy=true（保存会整串重建 ⇒ 静默覆盖）；
     * - 对象但含白名单外的 key → lossy=true（保存会丢弃这些 key，reason 列出它们）；
     * - 未知 channelType     → buildChannelConfig 原样保留原串，不构成丢失，非 lossy；
     * - 其余                → 非 lossy。
     */
    analyzeConfig(raw, type) {
      if (raw == null || raw === '') return { lossy: false, reason: '', cfg: {} }
      let cfg = null
      if (typeof raw === 'object') {
        cfg = Array.isArray(raw) ? null : raw
      } else {
        try {
          const parsed = JSON.parse(raw)
          cfg = (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) ? parsed : null
        } catch (e) {
          cfg = null
        }
      }
      if (!cfg) {
        return { lossy: true, reason: '原配置无法解析为 JSON 对象', cfg: {} }
      }
      if (!this.schemaForType(type)) {
        return { lossy: false, reason: '', cfg }
      }
      const known = this.knownKeysForType(type)
      const extra = Object.keys(cfg).filter((k) => known.indexOf(k) === -1)
      if (extra.length > 0) {
        return { lossy: true, reason: '原配置含本页未展示的字段：' + extra.join('、'), cfg }
      }
      return { lossy: false, reason: '', cfg }
    },
    /**
     * 提交前组装：散字段 → channelConfig JSON 字符串（T09 缺陷①③ 根因修复）。
     * key 名与冻结 Schema v1 一字不差（后端按这些 key 解析与加密）。
     * password 掩码策略：编辑回显的是后端脱敏掩码，未修改时**原样传回**，
     * 由后端 §3.3 掩码防线（识别掩码格式则跳过该字段更新）兜底 —— 与冻结契约一致。
     */
    buildChannelConfig(form) {
      const schema = this.schemaForType(form.channelType)
      if (!schema) {
        // 未知类型：原样保留库中已有配置串，避免误清
        return form.channelConfig != null ? form.channelConfig : null
      }
      // 写出 key = schema.canonical（与 analyzeConfig 白名单同一来源，杜绝两份清单漂移）
      const out = {}
      schema.canonical.forEach((k) => { out[k] = this.canonicalValue(k, form) })
      return JSON.stringify(out)
    },
    /** 规范 key 的取值归一：smtpPort 数值化（空回退 465）、ssl 布尔化、password 不 trim，其余字符串 trim */
    canonicalValue(key, form) {
      const v = form[key]
      if (key === 'smtpPort') return Number(v) || 465
      if (key === 'ssl') return v !== false
      if (key === 'password') return v || ''
      return (v == null ? '' : String(v)).trim()
    },
    /**
     * 类型切换：清空全部动态散字段 + 重建该类型的校验规则（防 SMTP 残留进 webhook 渠道）。
     * 注：dialog.configLossy 描述的是「库中原配置」的丢失风险、与当前所选类型无关，故在此**不重置**。
     */
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
      this.dialog.configLossy = false // 新建无既有配置，不存在覆盖丢失
      this.dialog.configLossyReason = ''
      this.dialog.fields = this.baseFields()
      this.dialog.rules = this.buildRules('WECOM')
      this.dialog.visible = true
    },
    onEdit(row) {
      // analyzeConfig 输入必须是库中原始串（row.channelConfig），以保留「能否解析」信息
      const analysis = this.analyzeConfig(row.channelConfig, row.channelType)
      const cfg = analysis.cfg || {}
      const schema = this.schemaForType(row.channelType)
      // 别名归一：规范 key 缺失而后端等价别名存在时，用别名值预填（保存写回规范 key ⇒ 值迁移、不丢）
      const canonical = {}
      if (schema) {
        schema.canonical.forEach((k) => { canonical[k] = cfg[k] })
        Object.keys(schema.aliases).forEach((alias) => {
          const target = schema.aliases[alias]
          const cur = canonical[target]
          if ((cur == null || cur === '') && cfg[alias] != null && cfg[alias] !== '') {
            canonical[target] = cfg[alias]
          }
        })
      }
      this.dialog.form = {
        id: row.id,
        channelName: row.channelName || '',
        channelType: row.channelType,
        status: row.status === 0 ? 0 : 1,
        channelConfig: row.channelConfig != null ? row.channelConfig : null, // 原始串兜底（未知类型不误清）
        ...JSON.parse(JSON.stringify(EMPTY_CONFIG_FIELDS)),
        // 回显反填（§4.2）：解析（含别名归一）后反填散字段；
        // password 为后端脱敏掩码，原样回显，未改则原样传回
        webhook: canonical.webhook != null ? String(canonical.webhook) : '',
        smtpHost: canonical.smtpHost != null ? String(canonical.smtpHost) : '',
        smtpPort: canonical.smtpPort != null ? Number(canonical.smtpPort) : 465,
        ssl: canonical.ssl !== undefined ? !!canonical.ssl : true,
        username: canonical.username != null ? String(canonical.username) : '',
        password: canonical.password != null ? String(canonical.password) : '',
        from: canonical.from != null ? String(canonical.from) : '',
        to: canonical.to != null ? String(canonical.to) : ''
      }
      // 静默覆盖防线标记（T10-N2b）：库中原配置无法解析 / 含未展示字段时置位，保存前弹确认
      this.dialog.configLossy = analysis.lossy
      this.dialog.configLossyReason = analysis.reason
      this.dialog.fields = this.baseFields()
      this.dialog.rules = this.buildRules(row.channelType)
      this.dialog.visible = true
    },
    async onSubmit(form) {
      // 静默覆盖防线（T10-N2b）：库中原配置无法解析 / 含本页未展示字段时，保存会整串重建，
      // 必须先让用户知情确认。**确认框在 loading=true 之前**弹出；用户取消则直接中止
      // （loading 未置位、表单不被清空、不发请求）。
      if (this.dialog.configLossy) {
        try {
          await this.$confirm(
            (this.dialog.configLossyReason || '库中原配置与当前表单不一致') +
              '，保存将用本页内容覆盖原配置并丢弃上述内容，是否继续？',
            '配置覆盖确认',
            { type: 'warning', confirmButtonText: '仍然覆盖', cancelButtonText: '取消' }
          )
        } catch (e) {
          return // 用户取消：中止提交
        }
      }
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
