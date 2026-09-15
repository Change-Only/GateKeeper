<template>
  <div class="ifp-panel">
    <!-- 语义说明：必须先讲清楚「这是平台自身的保护」而不是「对外报文的加解密」，
         否则用户会把它和「加解密管理」页搞混 -->
    <el-alert type="info" :closable="false" show-icon class="ifp-tip">
      <template #title>
        <b>这是 GateKeeper 自身数据的保护，不是对外报文的加解密。</b>
      </template>
      <template #default>
        <div class="ifp-desc">
          <p>
            <b>启用</b>时，平台的<b>接口路径</b>与<b>参数契约内容</b>（字段名 / 示例值 / 说明）
            在数据库里以密文存储；控制台里<b>只有下面白名单内的人</b>、
            以及<b>超级管理员</b>和该接口的<b>负责人</b>能看到明文，其他人只看到
            <code>****</code>。
          </p>
          <p>
            <b>关闭</b>时，接口信息以明文存储，且<b>所有登录用户都能看到</b> ——
            属高危操作，务必二次确认。
          </p>
          <p class="ifp-note">
            与「加解密管理」页无关：那一页配置的是第三方调用我方接口时请求体 / 响应体的加解密。
          </p>
        </div>
      </template>
    </el-alert>

    <!-- ============ ① 保护开关 ============ -->
    <div v-loading="cfgLoading" class="ifp-block">
      <div class="ifp-block-title">① 保护开关</div>
      <div class="ifp-row">
        <span class="ifp-label">接口信息保护</span>
        <el-switch
          v-model="enabled"
          :active-value="1"
          :inactive-value="0"
          active-text="启用（密文落库 + 按白名单展示）"
          inactive-text="关闭（明文落库 + 全员可见）"
          :disabled="cfgLoading"
        />
        <el-tag v-if="Number(enabled) === 0" type="danger" size="small" effect="plain">当前：明文落库，全员可见</el-tag>
        <el-tag v-else type="success" size="small" effect="plain">当前：密文落库，按白名单展示</el-tag>
      </div>
      <div class="ifp-row">
        <span class="ifp-label">变更备注</span>
        <el-input
          v-model="remark"
          maxlength="255"
          show-word-limit
          placeholder="建议写明原因，便于审计（可留空）"
          style="max-width:560px"
        />
      </div>
      <div class="ifp-row">
        <span class="ifp-label" />
        <PermButton
          perm="sys:security:update"
          type="primary"
          :loading="cfgSaving"
          @click="saveConfig"
        >保存开关</PermButton>
        <span class="ifp-meta">写后立即生效；多实例部署最长 10 秒收敛</span>
      </div>
      <div v-if="updatedAt" class="ifp-meta ifp-meta-block">
        最后更新：{{ fmtTime(updatedAt) }}{{ updatedBy ? '（操作人 ID ' + updatedBy + '）' : '' }}
      </div>
    </div>

    <!-- ============ ② 可见性白名单 ============ -->
    <div class="ifp-block">
      <div class="ifp-block-title">
        ② 可见性白名单
        <span class="ifp-meta">谁能看到接口明文</span>
      </div>

      <el-alert type="warning" :closable="false" show-icon class="ifp-subtip">
        <template #title>
          白名单为<b>空</b>时，<b>只有超级管理员与接口负责人</b>能看到明文（<b>不是</b>「不限制」）。
          这与「系统访问白名单」的语义<b>相反</b>：那边空表 = 不限制（准入闸门），
          这里是保护闸门，空表必须收紧。
        </template>
      </el-alert>

      <div class="ifp-toolbar">
        <PermButton
          perm="sys:security:update"
          type="primary"
          size="small"
          icon="el-icon-plus"
          @click="openCreate"
        >新增白名单</PermButton>
        <span class="ifp-meta">共 {{ list.length }} 条，其中启用 {{ enabledCount }} 条</span>
        <span class="spacer" />
        <el-button size="small" icon="el-icon-refresh" :loading="listLoading" @click="reloadList">刷新</el-button>
      </div>

      <el-table :data="list" border stripe size="medium" v-loading="listLoading">
        <el-table-column label="主体类型" width="110" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.subjectType === 'ROLE' ? 'warning' : 'primary'" effect="plain">
              {{ row.subjectType === 'ROLE' ? '角色' : '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主体" min-width="200" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <template v-if="row.subjectMissing">
              <el-tag size="mini" type="danger" effect="plain">主体已不存在</el-tag>
              <span class="ifp-meta">（id={{ row.subjectId }}，该条已失效，可删除）</span>
            </template>
            <template v-else>
              <span>{{ row.subjectLabel || '—' }}</span>
              <span class="ifp-meta">{{ row.subjectCode ? '（' + row.subjectCode + '）' : '' }}</span>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="row.status === 1 ? 'success' : 'info'" effect="plain">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip>
          <template slot-scope="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="165" align="center">
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
          <EmptyState title="未配置白名单：当前仅超级管理员与接口负责人可见接口明文" />
        </template>
      </el-table>
    </div>

    <el-dialog
      :title="form.id ? '编辑可见性白名单' : '新增可见性白名单'"
      :visible.sync="dialogVisible"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="form" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="主体类型" prop="subjectType">
          <el-radio-group v-model="form.subjectType" @change="onTypeChange">
            <el-radio label="USER">用户</el-radio>
            <el-radio label="ROLE">角色</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="主体" prop="subjectId">
          <el-select v-model="form.subjectId" filterable placeholder="请选择用户 / 角色" style="width:100%">
            <el-option
              v-for="o in currentOptions"
              :key="o.id"
              :value="o.id"
              :label="o.label ? o.label + '（' + o.code + '）' : String(o.id)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
          <span class="ifp-meta">停用的条目完全不参与可见性判定</span>
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
 * 接口信息保护面板（T17）
 * ------------------------------------------------------------------
 * 需求（2026-09-14 用户纠正后口径）：
 *   「在 GateKeeper 层面做接口加密，并设置开关 —— 不是第三方调用我接口时的加解密，
 *     是系统本身的加解密，别人在使用这个系统的时候从控制台看不到我的接口信息，
 *     再加上白名单功能。」
 *
 * 本面板 = 两件事：
 *   ① 保护开关：关 = 明文落库 + 全员可见（高危）；开 = 密文落库 + 按白名单展示。
 *   ② 可见性白名单：指定「哪些用户 / 角色能看到接口明文」。
 *
 * 可见性判定（与后端 InterfaceViewer.canSee 一致）：
 *   开关关闭 → 全可见；SUPER_ADMIN → 可见；接口 owner → 可见；
 *   白名单为空 → 仅上面两类可见；命中 (USER,uid) 或 (ROLE,任一角色) → 可见。
 *
 * 权限口径：写操作复用 sys:security:update（与「系统访问白名单」面板同一码）——
 * 零新增权限点，避免"新增码忘了授权 ⇒ 连 SUPER_ADMIN 都拿不到"的历史坑。
 */
import {
  getInterfaceProtectionConfig,
  updateInterfaceProtectionConfig,
  getInterfaceVisibilityList,
  addInterfaceVisibility,
  updateInterfaceVisibility,
  removeInterfaceVisibility,
  getInterfaceVisibilitySubjectOptions
} from '@/api/modules'
import PermButton from '@/components/common/PermButton.vue'
import EmptyState from '@/components/EmptyState.vue'

export default {
  name: 'InterfaceProtectionPanel',
  components: { PermButton, EmptyState },
  data() {
    return {
      // 开关
      cfgLoading: false,
      cfgSaving: false,
      enabled: 1,
      remark: '',
      updatedAt: null,
      updatedBy: null,
      // 白名单
      list: [],
      listLoading: false,
      subjectOptions: { users: [], roles: [] },
      dialogVisible: false,
      submitting: false,
      form: { id: null, subjectType: 'USER', subjectId: null, remark: '', status: 1 },
      rules: {
        subjectType: [{ required: true, message: '请选择主体类型', trigger: 'change' }],
        subjectId: [{ required: true, message: '请选择主体', trigger: 'change' }]
      }
    }
  },
  computed: {
    enabledCount() {
      return this.list.filter((r) => r.status === 1).length
    },
    currentOptions() {
      return this.form.subjectType === 'ROLE' ? this.subjectOptions.roles : this.subjectOptions.users
    }
  },
  mounted() {
    this.loadConfig()
    this.reloadList()
    this.loadOptions()
  },
  methods: {
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async loadConfig() {
      this.cfgLoading = true
      try {
        const res = await getInterfaceProtectionConfig()
        this.applyConfig((res && res.data) || {})
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.cfgLoading = false
      }
    },
    /** 回填：enabled 缺失一律按 1（启用）—— 与后端「缺行 = 启用」口径一致 */
    applyConfig(d) {
      this.enabled = Number(d.enabled) === 0 ? 0 : 1
      this.remark = d.remark || ''
      this.updatedAt = d.updatedAt || null
      this.updatedBy = d.updatedBy || null
    },
    /**
     * 保存开关。
     *
     * 关闭 = 明文落库 + 全员可见 —— 影响面覆盖全平台所有接口，属高危，
     * 必须二次确认（后端该端点同样 risk=true 强制审计）。
     */
    async saveConfig() {
      const turningOff = Number(this.enabled) === 0
      const doSave = async () => {
        this.cfgSaving = true
        try {
          const res = await updateInterfaceProtectionConfig({
            enabled: this.enabled,
            remark: this.remark
          })
          this.applyConfig((res && res.data) || {})
          this.$message.success('接口信息保护开关已保存')
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.cfgSaving = false
        }
      }
      if (turningOff) {
        this.$confirm(
          '关闭后接口路径与参数契约内容将以【明文】存储，且所有登录用户都能看到，'
            + '白名单与负责人限制全部失效。确认关闭？',
          '高危操作确认',
          { type: 'warning', confirmButtonText: '确认关闭', confirmButtonClass: 'el-button--danger' }
        ).then(doSave).catch(() => {})
      } else {
        doSave()
      }
    },
    async reloadList() {
      this.listLoading = true
      try {
        const res = await getInterfaceVisibilityList()
        this.list = (res && res.data) || []
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.listLoading = false
      }
    },
    async loadOptions() {
      try {
        const res = await getInterfaceVisibilitySubjectOptions()
        const d = (res && res.data) || {}
        this.subjectOptions = { users: d.users || [], roles: d.roles || [] }
      } catch (e) { /* 拦截器已提示 */ }
    },
    onTypeChange() {
      // 换类型后原选中 id 一定不在新候选里，清掉避免提交错主体
      this.form.subjectId = null
    },
    openCreate() {
      this.form = { id: null, subjectType: 'USER', subjectId: null, remark: '', status: 1 }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    openEdit(row) {
      this.form = {
        id: row.id,
        subjectType: row.subjectType || 'USER',
        subjectId: row.subjectId,
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
          const payload = {
            subjectType: this.form.subjectType,
            subjectId: this.form.subjectId,
            remark: this.form.remark,
            status: this.form.status
          }
          if (this.form.id) {
            await updateInterfaceVisibility(this.form.id, payload)
            this.$message.success('已更新')
          } else {
            await addInterfaceVisibility(payload)
            this.$message.success('已新增')
          }
          this.dialogVisible = false
          this.reloadList()
        } catch (e) { /* 拦截器已提示（后端会拦重复主体 / 主体不存在） */ } finally {
          this.submitting = false
        }
      })
    },
    /**
     * 启用 / 停用。
     * 停用最后一条启用条目会让白名单变成「空」⇒ 可见范围收窄到
     * 仅超管与负责人 —— 与「系统访问白名单」停用最后一条会放宽恰好相反，故提示措辞不同。
     */
    toggle(row) {
      const toDisable = row.status === 1
      const willTighten = toDisable && this.enabledCount === 1
      const msg = willTighten
        ? '这是最后一条启用条目。停用后白名单为空 ⇒ 仅超级管理员与接口负责人可见接口明文（范围收窄）。确认继续？'
        : `确认${toDisable ? '停用' : '启用'}「${row.subjectLabel || row.subjectCode || row.subjectId}」？`
      this.$confirm(msg, `${toDisable ? '停用' : '启用'}确认`, {
        type: willTighten ? 'warning' : 'info'
      }).then(async () => {
        try {
          await updateInterfaceVisibility(row.id, {
            subjectType: row.subjectType,
            subjectId: row.subjectId,
            remark: row.remark,
            status: toDisable ? 0 : 1
          })
          this.$message.success(toDisable ? '已停用' : '已启用')
          this.reloadList()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    remove(row) {
      const name = row.subjectLabel || row.subjectCode || row.subjectId
      this.$confirm(`确认删除白名单「${name}」？`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await removeInterfaceVisibility(row.id)
          this.$message.success('已删除')
          this.reloadList()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.ifp-panel { padding: 4px; }
.ifp-tip { margin-bottom: 14px; }
.ifp-desc { line-height: 1.8; font-size: 13px; }
.ifp-desc p { margin: 4px 0; }
.ifp-desc code { background: #f0f3f9; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
.ifp-note { color: #7d93b8; }
.ifp-block { margin-bottom: 22px; }
.ifp-block-title { font-size: 14px; font-weight: 600; color: #17233d; margin-bottom: 10px; }
.ifp-subtip { margin-bottom: 12px; }
.ifp-row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.ifp-label { width: 92px; flex: none; color: #17233d; font-size: 13px; }
.ifp-meta { color: #7d93b8; font-size: 12px; }
.ifp-meta-block { margin-top: -6px; }
.ifp-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.ifp-toolbar .spacer { flex: 1; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
