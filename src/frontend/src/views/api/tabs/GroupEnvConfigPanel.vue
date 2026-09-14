<template>
  <div class="gec-panel">
    <!-- 头部：分组名 + 刷新 -->
    <div class="gec-head">
      <div class="gec-head-left">
        <span class="gec-group">{{ groupName || '未命名分组' }}</span>
        <span class="gec-sub">环境配置 · 服务前缀 / 超时 / 重试 / Mock</span>
      </div>
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="gec-tip">
      <template #title>
        <b>分组树向上继承</b>：本分组在某环境没有配置时，网关自动取<b>最近的祖先分组</b>的配置，
        整条继承（服务前缀 / 连接超时 / 读取超时 / 重试 / Mock 一起来，不做逐字段合并）。
        接口详情里的「环境配置」只做只读生效预览 —— <b>维护入口就在这里</b>。
      </template>
    </el-alert>

    <!--
      刻意**不使用** fixed 固定列。
      教训（2026-09-14 用户反馈的「连接超时/读取超时/重试次数看不到输入的值」）：
      接口侧那张表有 10 列 + fixed="right" 操作列，容器放不下时固定列会一直悬在右侧，
      把中间的数字列挤出可视区，而横向滚动条又不一定会出现 ⇒ 用户以为「值没存进去」。
      本表控制在 6 列并且不固定，宁可窄屏时整表横向滚动，也不让任何一列"消失"。
    -->
    <el-table v-loading="loading" :data="rows" size="small" border class="gec-table">
      <el-table-column label="环境" width="100">
        <template #default="{ row }">
          <span class="gec-env">{{ row.envLabel }}</span>
          <code class="gec-env-code">{{ row.envCode }}</code>
        </template>
      </el-table-column>

      <el-table-column label="配置来源" min-width="180">
        <template #default="{ row }">
          <el-tag v-if="row.isOwn" type="success" size="mini">本分组维护</el-tag>
          <el-tag v-else-if="row.isInherited" type="warning" size="mini">继承自父级</el-tag>
          <el-tag v-else type="info" size="mini">未配置</el-tag>
          <div v-if="row.isInherited" class="gec-src">{{ row.sourcePath }}</div>
        </template>
      </el-table-column>

      <el-table-column label="服务前缀（生效值）" min-width="230">
        <template #default="{ row }">
          <code v-if="row.upstreamUrl" class="gec-mono">{{ row.upstreamUrl }}</code>
          <span v-else class="gec-empty">—</span>
          <div class="gec-hint">完整地址 = 该前缀 + 各接口自身 URI</div>
        </template>
      </el-table-column>

      <el-table-column label="超时 / 重试" min-width="180">
        <template #default="{ row }">
          <div class="gec-kv"><span class="gec-k">连接</span><span class="gec-v">{{ fmtNum(row.connectTimeout) }}</span></div>
          <div class="gec-kv"><span class="gec-k">读取</span><span class="gec-v">{{ fmtNum(row.readTimeout) }}</span></div>
          <div class="gec-kv"><span class="gec-k">重试</span><span class="gec-v">{{ fmtNum(row.retryCount) }}</span></div>
        </template>
      </el-table-column>

      <el-table-column label="Mock" width="110">
        <template #default="{ row }">
          <el-tag :type="row.mockEnabled === 1 ? 'warning' : 'info'" size="mini">
            {{ row.mockEnabled === 1 ? '开启' : '关闭' }}
          </el-tag>
          <div v-if="row.mockEnabled === 1" class="gec-src">HTTP {{ row.mockStatus || 200 }}</div>
        </template>
      </el-table-column>

      <el-table-column label="配置状态" width="100">
        <template #default="{ row }">
          <StatusTag v-if="row.configStatus != null" entity="apiEnvConfig" :value="row.configStatus" />
          <span v-else class="gec-empty">—</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="250">
        <template #default="{ row }">
          <PermButton
            v-if="row.isOwn"
            perm="api_group_env_config:update"
            type="text"
            @click="openForm(row)"
          >编辑</PermButton>
          <PermButton
            v-else
            perm="api_group_env_config:create"
            type="text"
            @click="openForm(row)"
          >{{ row.isInherited ? '在本分组覆盖' : '配置' }}</PermButton>

          <PermButton
            v-if="row.isOwn"
            perm="api_group_env_config:test"
            type="text"
            :loading="testingId === row.ownId"
            @click="testConn(row)"
          >测试连通</PermButton>

          <PermButton
            v-if="row.isOwn"
            perm="api_group_env_config:update"
            type="text"
            @click="toggleMock(row)"
          >切换 Mock</PermButton>

          <PermButton
            v-if="row.isOwn"
            perm="api_group_env_config:delete"
            type="text"
            class="danger-link"
            @click="remove(row)"
          >清除</PermButton>

          <span v-if="!row.isOwn" class="gec-hint-inline">
            {{ row.isInherited ? '（来自父级，需覆盖才能改）' : '' }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 编辑/新增弹窗：手写 el-dialog 而不是复用 CrudDialog ——
         本域需要「环境不可改（它是配置的身份键）」+ Mock 三件套（开关/状态码/返回体）的联动，
         CrudDialog 的字段声明不支持 disabled，硬套会出现「改环境=改身份」的错。 -->
    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="620px"
      :close-on-click-modal="false"
      append-to-body
      @open="onDialogOpen"
    >
      <el-form ref="form" :model="form" :rules="formRules" label-width="120px">
        <el-form-item label="环境">
          <el-input :value="envLabelOf(form.envCode)" disabled />
          <div class="gec-hint">环境是配置的身份键（一个分组 + 一个环境只有一条配置），创建后不可更改。</div>
        </el-form-item>

        <el-form-item label="服务前缀" prop="upstreamUrl">
          <el-input
            v-model="form.upstreamUrl"
            maxlength="512"
            placeholder="如 http://order-svc.dev:8080（不要带接口路径）"
          />
          <div class="gec-hint">
            只填到主机端口为止；接口路径由各接口自身的 URI 决定（完整地址 = 本前缀 + 接口 URI）。
          </div>
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="连接超时(ms)" label-width="120px" class="gec-num-item">
              <el-input-number v-model="form.connectTimeout" :min="0" :max="120000" :step="100" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="读取超时(ms)" label-width="120px" class="gec-num-item">
              <el-input-number v-model="form.readTimeout" :min="0" :max="120000" :step="100" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="重试次数" label-width="100px" class="gec-num-item">
              <el-input-number v-model="form.retryCount" :min="0" :max="10" controls-position="right" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <div class="gec-hint gec-num-hint">
          写操作接口（POST/PUT/DELETE）重试次数请保持 0，避免重复提交。
        </div>

        <el-divider content-position="left">Mock</el-divider>
        <el-form-item label="开启 Mock">
          <el-switch v-model="form.mockEnabled" :active-value="1" :inactive-value="0" />
          <span class="gec-hint-inline">开启后网关<b>短路、不转发后端</b>，直接返回下面的状态码与报文。</span>
        </el-form-item>
        <template v-if="form.mockEnabled === 1">
          <el-form-item label="Mock 状态码">
            <el-input-number v-model="form.mockStatus" :min="100" :max="599" controls-position="right" style="width: 160px" />
          </el-form-item>
          <el-form-item label="Mock 返回体">
            <el-input
              v-model="form.mockResponse"
              type="textarea"
              :rows="5"
              placeholder='留空则返回默认提示 JSON，例如 {"mock":true,"message":"..."}'
            />
          </el-form-item>
        </template>
      </el-form>

      <template slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 分组环境配置面板（T13）
 * ------------------------------------------------------------------
 * 用户需求（2026-09-14 第 4 条）：环境配置从接口侧下沉到**接口分组侧**，
 * 维护点唯一，并支持**沿分组树向上继承父级配置**。
 *
 * 数据来源（一个请求搞定）：
 *   GET /api-group-env-config/effective?groupId=  → 4 个环境的**生效配置**（含来源判定）
 *   后端用 EnvConfigResolver 计算，与网关实际转发用的是**同一份逻辑**，
 *   因此本页面显示什么，网关就按什么走 —— 不允许出现两套口径。
 *
 * 每行的「归属」判定（决定能点哪些按钮）：
 *   sourceType=GROUP 且 sourceGroupId=本分组  → isOwn       本分组维护（可编辑/测试/切 Mock/清除）
 *   sourceType=GROUP 且 sourceGroupId=其他    → isInherited 继承自父级（只能「在本分组覆盖」）
 *   sourceType=DEFAULT / INTERFACE / 其他     → 未配置       可以「配置」
 *
 * 写入语义：
 *   isOwn      → PUT  /api-group-env-config/{id}/update   （api_group_env_config:update）
 *   非 isOwn   → POST /api-group-env-config/upsert        （api_group_env_config:create）
 *   两个端点对应两个权限点，前端按行选择走哪一个，和后端注解**一一对齐**
 *   （前端有闸门而后端无注解 = 假保护；反之则表现为"点了 403"，两端必须成对）。
 */
import { getGroupEnvConfigEffective, upsertGroupEnvConfig, updateGroupEnvConfig, testGroupEnvConfig, toggleGroupEnvConfigMock, deleteGroupEnvConfig } from '@/api/modules'
import { ENV_LIST } from '@/utils/enum'
import StatusTag from '@/components/common/StatusTag.vue'
import PermButton from '@/components/common/PermButton.vue'

export default {
  name: 'GroupEnvConfigPanel',
  components: { StatusTag, PermButton },
  props: {
    groupId: { type: [Number, String], default: null },
    groupName: { type: String, default: '' }
  },
  data() {
    return {
      loading: false,
      testingId: null,
      effective: [],
      dialogVisible: false,
      dialogTitle: '配置环境',
      submitting: false,
      form: { envCode: '', upstreamUrl: '', connectTimeout: 3000, readTimeout: 5000, retryCount: 0, mockEnabled: 0, mockStatus: 200, mockResponse: '' },
      /** 正在编辑的「本分组配置行 ID」；null 表示走 upsert 新建（含"在继承基础上覆盖"） */
      editId: null,
      formRules: {
        upstreamUrl: [
          { required: true, message: '请填写服务前缀', trigger: 'blur' },
          {
            validator: (rule, value, cb) => {
              const v = String(value || '').trim().toLowerCase()
              if (!v) return cb()
              if (!v.startsWith('http://') && !v.startsWith('https://')) {
                return cb(new Error('服务前缀必须以 http:// 或 https:// 开头'))
              }
              cb()
            },
            trigger: 'blur'
          }
        ]
      }
    }
  },
  computed: {
    rows() {
      const gid = this.normalizeId(this.groupId)
      return ENV_LIST.map((e) => {
        const eff = (this.effective || []).find((x) => x.envCode === e.code) || {}
        const srcGid = this.normalizeId(eff.sourceGroupId)
        const isOwn = eff.sourceType === 'GROUP' && srcGid != null && srcGid === gid
        const isInherited = eff.sourceType === 'GROUP' && !isOwn
        return {
          envCode: e.code,
          envLabel: e.label,
          upstreamUrl: eff.upstreamUrl,
          connectTimeout: eff.connectTimeout,
          readTimeout: eff.readTimeout,
          retryCount: eff.retryCount,
          mockEnabled: eff.mockEnabled,
          mockStatus: eff.mockStatus,
          mockResponse: eff.mockResponse,
          configStatus: eff.configStatus,
          sourceType: eff.sourceType,
          sourcePath: eff.sourcePath || eff.sourceGroupName,
          isOwn,
          isInherited,
          ownId: isOwn ? eff.sourceConfigId : null
        }
      })
    }
  },
  watch: {
    groupId() { this.load() }
  },
  mounted() {
    this.load()
  },
  methods: {
    normalizeId(v) {
      if (v === null || v === undefined || v === '') return null
      const n = Number(v)
      return Number.isNaN(n) ? null : n
    },
    envLabelOf(code) {
      const e = ENV_LIST.find((x) => x.code === code)
      return e ? e.label : (code || '—')
    },
    fmtNum(v) {
      return v === null || v === undefined ? '—' : v
    },
    async load() {
      if (this.groupId === null || this.groupId === undefined || this.groupId === '') return
      this.loading = true
      try {
        const res = await getGroupEnvConfigEffective(this.groupId)
        this.effective = (res && res.data) || []
      } catch (e) {
        this.effective = []
      } finally {
        this.loading = false
      }
    },
    /**
     * 打开配置弹窗。
     * 「在本分组覆盖」时把继承来的值预填进去 —— 用户绝大多数情况只是想在父级基础上改一处，
     * 空白起始会逼他重新抄一遍前缀，抄错就是线上事故。
     */
    openForm(row) {
      this.editId = row.ownId
      this.dialogTitle = row.isOwn
        ? `编辑「${row.envLabel}」环境配置`
        : (row.isInherited ? `在「${row.envLabel}」覆盖父级配置` : `配置「${row.envLabel}」环境`)
      this.form = {
        envCode: row.envCode,
        upstreamUrl: row.upstreamUrl || '',
        connectTimeout: row.connectTimeout != null ? row.connectTimeout : 3000,
        readTimeout: row.readTimeout != null ? row.readTimeout : 5000,
        retryCount: row.retryCount != null ? row.retryCount : 0,
        mockEnabled: row.mockEnabled === 1 ? 1 : 0,
        mockStatus: row.mockStatus != null ? row.mockStatus : 200,
        mockResponse: row.mockResponse || ''
      }
      this.dialogVisible = true
    },
    onDialogOpen() {
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.submitting = true
        try {
          // Mock 关闭时把状态码/报文归一，避免留下"看不见但会影响开关重新打开后行为"的残值
          const payload = { ...this.form }
          if (payload.mockEnabled !== 1) {
            payload.mockStatus = payload.mockStatus || 200
          }
          if (this.editId) {
            await updateGroupEnvConfig(this.editId, payload)
            this.$message.success('配置已更新')
          } else {
            await upsertGroupEnvConfig({ ...payload, groupId: this.groupId })
            this.$message.success('配置已保存')
          }
          this.dialogVisible = false
          await this.load()
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.submitting = false
        }
      })
    },
    testConn(row) {
      this.$confirm(`对「${row.envLabel}」环境的服务前缀发起连通性测试？`, '测试连通', { type: 'info' })
        .then(async () => {
          this.testingId = row.ownId
          try {
            const res = await testGroupEnvConfig(row.ownId)
            const cfg = (res && res.data) || {}
            if (cfg.configStatus === 2) {
              this.$message.success('连通测试通过 · 已标记为「已验证」')
            } else {
              this.$message.warning('连通测试未通过 · 仍为「已配置」，请检查服务前缀')
            }
            await this.load()
          } catch (e) { /* 拦截器已提示 */ } finally {
            this.testingId = null
          }
        })
        .catch(() => {})
    },
    toggleMock(row) {
      const next = row.mockEnabled === 1 ? '关闭' : '开启'
      this.$confirm(`确认${next}「${row.envLabel}」环境的 Mock？${next === '开启' ? '开启后网关将短路返回 Mock 报文，不再转发后端。' : ''}`,
        `${next} Mock`, { type: 'warning' })
        .then(async () => {
          try {
            await toggleGroupEnvConfigMock(row.ownId)
            this.$message.success(`Mock 已${next}`)
            await this.load()
          } catch (e) { /* 拦截器已提示 */ }
        })
        .catch(() => {})
    },
    remove(row) {
      this.$confirm(`确认清除本分组在「${row.envLabel}」环境的配置？清除后将回落到父级继承（若有）。`,
        '清除确认', { type: 'warning' })
        .then(async () => {
          try {
            await deleteGroupEnvConfig(row.ownId)
            this.$message.success('已清除')
            await this.load()
          } catch (e) { /* 拦截器已提示 */ }
        })
        .catch(() => {})
    }
  }
}
</script>

<style scoped>
.gec-panel { padding: 4px; }
.gec-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.gec-head-left { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.gec-group { font-size: 15px; font-weight: 600; color: #17233d; }
.gec-sub { font-size: 12px; color: #9aa7bf; }
.gec-tip { margin-bottom: 12px; }
.gec-tip b { color: #1e40af; }

.gec-table { width: 100%; }
.gec-env { font-size: 13px; color: #17233d; font-weight: 500; }
.gec-env-code { display: block; font-size: 11px; color: #9aa7bf; }
.gec-src { font-size: 11px; color: #5c6b8a; margin-top: 2px; word-break: break-all; }
.gec-mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; color: #17233d; word-break: break-all; }
.gec-empty { color: #c0c6d4; }
.gec-hint { font-size: 11px; color: #9aa7bf; margin-top: 2px; }
.gec-hint-inline { font-size: 11px; color: #9aa7bf; margin-left: 4px; }
.gec-num-hint { margin: -6px 0 8px 120px; }

.gec-kv { display: flex; gap: 6px; font-size: 12px; line-height: 18px; }
.gec-k { color: #9aa7bf; flex: none; width: 32px; }
.gec-v { color: #17233d; }

.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }

/* 三个数字字段并排时标签会挤，缩小这一行的标签宽度 */
::v-deep .gec-num-item .el-form-item__label { padding-right: 6px; }
</style>
