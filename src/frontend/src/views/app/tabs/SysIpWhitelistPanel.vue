<template>
  <div class="siw-panel">
    <!-- 语义说明：这是「系统级」配置，不是本应用专属 —— 必须先说清楚，
         否则用户会以为它只影响当前应用 -->
    <el-alert type="warning" :closable="false" show-icon class="siw-tip">
      <template #title>
        <b>系统访问白名单（全局生效）</b>：作用于<b>整个网关入口</b>，不区分应用。
        <b>列表为空时不限制</b>（保持现状）；一旦存在启用条目，
        <b>所有应用</b>的接口都只接受名单内来源 IP。
        网关校验优先级：系统级 → 应用级，两层是「且」的关系。
      </template>
    </el-alert>

    <div class="siw-toolbar">
      <PermButton
        perm="sys:security:update"
        type="primary"
        size="small"
        icon="el-icon-plus"
        @click="openCreate"
      >新增规则</PermButton>
      <span class="siw-hint">
        共 {{ list.length }} 条，其中启用 {{ enabledCount }} 条
      </span>
      <span class="spacer" />
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="reload">刷新</el-button>
    </div>

    <el-table :data="list" border stripe size="medium" v-loading="loading">
      <el-table-column prop="ipCidr" label="IP / CIDR" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="100" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.status === 1 ? 'success' : 'info'" effect="plain">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170" align="center">
        <template slot-scope="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template slot-scope="{ row }">
          <PermButton perm="sys:security:update" type="text" size="mini" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </PermButton>
          <PermButton perm="sys:security:update" type="text" size="mini" @click="openEdit(row)">编辑</PermButton>
          <PermButton perm="sys:security:update" type="text" size="mini" class="danger-link" @click="remove(row)">删除</PermButton>
        </template>
      </el-table-column>
      <template slot="empty">
        <EmptyState title="未配置系统访问白名单（当前不限制来源 IP）" />
      </template>
    </el-table>

    <!-- append-to-body：本面板可能被渲染在抽屉内，抽屉自成层叠上下文，
         弹窗若内联渲染会被 Element 的单例遮罩压住 -->
    <el-dialog
      :title="form.id ? '编辑访问白名单' : '新增访问白名单'"
      :visible.sync="dialogVisible"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="IP / CIDR" prop="ipCidr">
          <el-input v-model="form.ipCidr" placeholder="如 10.0.0.1 或 10.0.0.0/24" maxlength="64" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="siw-hint-inline">停用的条目不参与网关校验（便于临时摘除而不丢记录）</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="200" placeholder="说明为何放行，便于他人理解" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
/**
 * 系统级访问白名单面板（T15-4）
 * ------------------------------------------------------------------
 * 需求（2026-09-15 用户第 4 条）：「系统访问白名单在应用设置中可配置。」
 *
 * 与「应用级 IP 白名单」（IpWhitelistTab.vue）的区别 —— 这是本组件存在的理由：
 *   应用级：某个应用的调用方来源限制，随应用走，网关第 2 环校验；
 *   系统级：本平台的入口来源限制，**不区分应用**，网关最前（@Order(0)）校验。
 * 两层是「且」的关系。
 *
 * 生效规则（与 SysAccessWhitelistHandler 一致）：
 *   列表为空 / 无启用条目 ⇒ 不限制；查询异常 ⇒ fail-open 放行。
 *
 * 权限口径：写操作复用 sys:security:update（已在「系统设置 → 安全策略」语义范畴内，
 * 该页既有 CRUD 也用它）。刻意不新增权限点 —— 服务端无超管通配，
 * 新增码若忘了授权，连 SUPER_ADMIN 都拿不到（详见 T08 契约记录）。
 */
import {
  getSysIpWhitelist,
  addSysIpWhitelist,
  updateSysIpWhitelist,
  removeSysIpWhitelist
} from '@/api/modules'
import PermButton from '@/components/common/PermButton.vue'
import EmptyState from '@/components/EmptyState.vue'

export default {
  name: 'SysIpWhitelistPanel',
  components: { PermButton, EmptyState },
  data() {
    return {
      list: [],
      loading: false,
      dialogVisible: false,
      submitting: false,
      form: { id: null, ipCidr: '', remark: '', status: 1 },
      rules: {
        ipCidr: [{ required: true, message: '请填写 IP / CIDR', trigger: 'blur' }]
      }
    }
  },
  computed: {
    enabledCount() {
      return this.list.filter((r) => r.status === 1).length
    }
  },
  mounted() {
    this.reload()
  },
  methods: {
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async reload() {
      this.loading = true
      try {
        const res = await getSysIpWhitelist()
        this.list = (res && res.data) || []
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.loading = false
      }
    },
    openCreate() {
      this.form = { id: null, ipCidr: '', remark: '', status: 1 }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    openEdit(row) {
      this.form = {
        id: row.id,
        ipCidr: row.ipCidr || '',
        remark: row.remark || '',
        status: row.status === 0 ? 0 : 1
      }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.submitting = true
        try {
          const payload = { ipCidr: this.form.ipCidr, remark: this.form.remark, status: this.form.status }
          if (this.form.id) {
            await updateSysIpWhitelist(this.form.id, payload)
            this.$message.success('已更新')
          } else {
            await addSysIpWhitelist(payload)
            this.$message.success('已新增')
          }
          this.dialogVisible = false
          this.reload()
        } catch (e) { /* 拦截器已提示（含后端 IP/CIDR 格式与重复校验） */ } finally {
          this.submitting = false
        }
      })
    },
    /**
     * 启用 / 停用。
     * 停用前给出提示：停用会立刻把它从「生效名单」里摘出去 ——
     * 若这是最后一条启用条目，系统将回到「不限制」状态（放宽），
     * 这一点与直觉相反（很多人以为停用=更严格），必须说清楚。
     */
    toggle(row) {
      const toDisable = row.status === 1
      const willRelax = toDisable && this.enabledCount === 1
      const msg = willRelax
        ? '这是最后一条启用规则。停用后系统访问白名单将变为「空」⇒ 网关不再限制来源 IP（放宽）。确认继续？'
        : `确认${toDisable ? '停用' : '启用'}「${row.ipCidr}」？`
      this.$confirm(msg, `${toDisable ? '停用' : '启用'}确认`, {
        type: willRelax ? 'warning' : 'info'
      }).then(async () => {
        try {
          await updateSysIpWhitelist(row.id, {
            ipCidr: row.ipCidr,
            remark: row.remark,
            status: toDisable ? 0 : 1
          })
          this.$message.success(toDisable ? '已停用' : '已启用')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    remove(row) {
      const lastEnabled = row.status === 1 && this.enabledCount === 1
      const msg = lastEnabled
        ? `「${row.ipCidr}」是最后一条启用规则。删除后网关将不再限制来源 IP（放宽）。确认删除？`
        : `确认删除白名单「${row.ipCidr}」？`
      this.$confirm(msg, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await removeSysIpWhitelist(row.id)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.siw-panel { padding: 4px; }
.siw-tip { margin-bottom: 12px; }
.siw-tip b { color: #8a5a00; }
.siw-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.siw-toolbar .spacer { flex: 1; }
.siw-hint { font-size: 12px; color: #9aa7bf; }
.siw-hint-inline { margin-left: 10px; font-size: 12px; color: #9aa7bf; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
