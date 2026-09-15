<template>
  <div class="api-tab">
    <el-alert type="info" :closable="false" show-icon class="wl-tip">
      <template #title>
        仅允许白名单内（且<b>状态为启用</b>）的 IP / CIDR 访问<b>本应用</b>。
        另有一层<b>系统访问白名单</b>（全局、对全部应用生效），在应用管理页顶部「系统访问白名单」中维护；
        网关校验顺序为 <b>系统级 → 应用级</b>，两层是「且」的关系。
      </template>
    </el-alert>

    <div class="tab-toolbar">
      <PermButton perm="app:ipwhitelist:add" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新增白名单</PermButton>
      <span class="tab-hint">共 {{ list.length }} 条，启用 {{ enabledCount }} 条（停用条目不再拦截，但记录保留）</span>
    </div>

    <el-table :data="list" border stripe size="medium" v-loading="loading" class="wl-table">
      <el-table-column prop="ipCidr" label="IP / CIDR" min-width="180" show-overflow-tooltip />
      <el-table-column label="环境" width="110" align="center">
        <template slot-scope="{ row }">
          <el-tag v-if="row.envCode" size="mini" effect="plain">{{ row.envCode }}</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="140" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="isEnabled(row) ? 'success' : 'info'" effect="plain">
            {{ isEnabled(row) ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="160" align="center">
        <template slot-scope="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="{ row }">
          <PermButton perm="app:ipwhitelist:update" type="text" size="mini" @click="toggle(row)">
            {{ isEnabled(row) ? '停用' : '启用' }}
          </PermButton>
          <PermButton perm="app:ipwhitelist:update" type="text" size="mini" @click="openEdit(row)">编辑</PermButton>
          <PermButton perm="app:ipwhitelist:delete" type="text" size="mini" class="danger-link" @click="remove(row)">删除</PermButton>
        </template>
      </el-table-column>
      <template slot="empty">
        <EmptyState title="暂无 IP 白名单（当前不限制来源）" />
      </template>
    </el-table>

    <!-- append-to-body 必加：本 Tab 位于「应用详情」抽屉内，抽屉的 .el-drawer__wrapper
         是 position:fixed + z-index 自成的层叠上下文；弹窗若内联渲染会被困在里面，
         被 Element 的单例遮罩 .v-modal（z 跟随顶层弹窗、挂在 body 上）整片压住。 -->
    <el-dialog
      :title="form.id ? '编辑 IP 白名单' : '新增 IP 白名单'"
      :visible.sync="dialogVisible"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="IP / CIDR" prop="ipCidr">
          <el-input v-model="form.ipCidr" placeholder="如 10.0.0.1 或 10.0.0.0/24" maxlength="64" />
        </el-form-item>
        <el-form-item label="环境">
          <!-- 环境是「归类标注」维度，不参与网关校验（白名单是强语义，
               按环境过滤会让 A 环境配的行误拦 B 环境），故允许自由输入 -->
          <el-select
            v-model="form.envCode"
            filterable
            allow-create
            default-first-option
            placeholder="归类标注用，不参与校验"
            style="width:100%"
          >
            <el-option v-for="e in envOptions" :key="e" :label="e" :value="e" />
          </el-select>
          <div class="field-hint">仅用于按环境归类查看；网关校验只看「状态」</div>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="field-hint-inline">停用后本条不再参与校验（临时放行某段 IP 而不丢记录）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 应用 IP 白名单 Tab（T05 Phase 1 · app-list 详情；T15-4 补全）
 * ------------------------------------------------------------------
 * 对接 /app/{id}/ip-whitelist（GET 列表 / POST 新增 / PUT 更新 / DELETE 删除）。
 *
 * T15-4 补了什么、为什么：
 *   ① 环境（envCode）、状态（status）两个字段：这两列**线上表里早就有**
 *      （DDL 默认 'prod' / 1），只是实体没映射、页面也看不到；
 *   ② 「启用 / 停用」开关：此前只有"新增 / 删除"，想临时放行某段 IP 只能删了再加
 *      （丢备注、易写错）。补上 status 之后，**网关侧才按 status=1 过滤**
 *      —— 在此之前 status 列形同虚设，停用的条目照样拦人（已修复）。
 *
 * 与系统级白名单的区别：本 Tab 只作用于当前应用；系统级在应用管理页顶部维护。
 */
import { getIpWhitelist, addIpWhitelist, updateIpWhitelist, removeIpWhitelist, getEnvAll } from '@/api/modules'
import PermButton from '@/components/common/PermButton.vue'
import EmptyState from '@/components/EmptyState.vue'

export default {
  name: 'IpWhitelistTab',
  components: { PermButton, EmptyState },
  props: {
    appId: { type: [Number, String], default: null }
  },
  data() {
    return {
      list: [],
      loading: false,
      dialogVisible: false,
      saving: false,
      envOptions: ['prod'],
      form: { id: null, ipCidr: '', remark: '', envCode: 'prod', status: 1 },
      rules: {
        ipCidr: [{ required: true, message: '请填写 IP / CIDR', trigger: 'blur' }]
      }
    }
  },
  computed: {
    enabledCount() {
      return this.list.filter((r) => this.isEnabled(r)).length
    }
  },
  watch: {
    appId: {
      immediate: true,
      handler(v) { if (v != null) this.reload() }
    }
  },
  mounted() {
    this.loadEnvs()
  },
  methods: {
    /**
     * 后端 status 为 null 时按「启用」处理 —— 与 DDL 默认值 1 以及
     * IpWhitelistHandler 的 status=1 过滤保持一致，避免历史行显示成"停用"。
     */
    isEnabled(row) {
      return !row || row.status === null || row.status === undefined || row.status === 1
    },
    /** 后端 createdAt 是 LocalDateTime，ISO 输出带 `T`（2026-09-10T14:11:04），统一转可读形式 */
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async loadEnvs() {
      try {
        const res = await getEnvAll()
        const rows = (res && res.data) || []
        const codes = rows.map((e) => e.envCode || e.code).filter(Boolean)
        this.envOptions = Array.from(new Set(['prod'].concat(codes)))
      } catch (e) {
        // 环境列表拿不到不影响白名单维护：环境只是标注维度
      }
    },
    async reload() {
      if (this.appId == null) return
      this.loading = true
      try {
        const res = await getIpWhitelist(this.appId)
        this.list = res.data || []
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.loading = false
      }
    },
    openCreate() {
      this.form = { id: null, ipCidr: '', remark: '', envCode: 'prod', status: 1 }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    openEdit(row) {
      this.form = {
        id: row.id,
        ipCidr: row.ipCidr || '',
        remark: row.remark || '',
        envCode: row.envCode || 'prod',
        status: this.isEnabled(row) ? 1 : 0
      }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.saving = true
        try {
          const payload = {
            ipCidr: this.form.ipCidr,
            remark: this.form.remark,
            envCode: this.form.envCode,
            status: this.form.status
          }
          if (this.form.id) {
            await updateIpWhitelist(this.form.id, payload)
            this.$message.success('已更新')
          } else {
            await addIpWhitelist(this.appId, payload)
            this.$message.success('已新增')
          }
          this.dialogVisible = false
          this.reload()
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.saving = false
        }
      })
    },
    /**
     * 启用 / 停用。
     * 停用最后一条启用规则会立刻让本应用回到「不限制」状态（放宽），
     * 与"停用=更严格"的直觉相反，故单独提示。
     */
    toggle(row) {
      const toDisable = this.isEnabled(row)
      const willRelax = toDisable && this.enabledCount === 1
      const msg = willRelax
        ? '这是最后一条启用规则。停用后本应用的 IP 白名单将为空 ⇒ 网关不再限制来源 IP（放宽）。确认继续？'
        : `确认${toDisable ? '停用' : '启用'}「${row.ipCidr}」？`
      this.$confirm(msg, `${toDisable ? '停用' : '启用'}确认`, {
        type: willRelax ? 'warning' : 'info'
      }).then(async () => {
        try {
          await updateIpWhitelist(row.id, {
            ipCidr: row.ipCidr,
            remark: row.remark,
            envCode: row.envCode,
            status: toDisable ? 0 : 1
          })
          this.$message.success(toDisable ? '已停用' : '已启用')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    remove(row) {
      const lastEnabled = this.isEnabled(row) && this.enabledCount === 1
      const msg = lastEnabled
        ? `「${row.ipCidr}」是最后一条启用规则。删除后本应用将不再限制来源 IP（放宽）。确认删除？`
        : `确认删除白名单「${row.ipCidr}」？`
      this.$confirm(msg, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await removeIpWhitelist(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }
.wl-tip { margin-bottom: 12px; }
.wl-tip b { color: #1e40af; }
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.field-hint { font-size: 11px; color: #9aa7bf; margin-top: 2px; }
.field-hint-inline { margin-left: 10px; font-size: 12px; color: #9aa7bf; }
.muted { color: #c0c6d4; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
