<template>
  <div class="gep-panel">
    <!-- 头部：分组名 + 刷新 -->
    <div class="gep-head">
      <div class="gep-head-left">
        <span class="gep-group">{{ groupName || '未命名分组' }}</span>
        <span class="gep-sub">加解密配置 · 沿分组树向上继承 · 可显式关闭</span>
      </div>
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon class="gep-tip">
      <template #title>
        <b>三态语义</b>：<b>继承上级</b>（本分组不定义，继续向上找）·
        <b>启用加解密</b>（本分组定义算法与密钥，下级默认继承）·
        <b>不需要加解密</b>（明确关闭，且<b>不再向上继承</b>）。
        网关生效优先级：<b>接口级 &gt; 分组级 &gt; 应用级</b>。
      </template>
    </el-alert>

    <!-- 生效配置卡片（只读；维护入口在下面的按钮） -->
    <el-card shadow="never" class="gep-card">
      <div class="gep-row">
        <span class="gep-label">生效来源</span>
        <span class="gep-value">
          <el-tag v-if="isOwn" type="success" size="mini">本分组维护</el-tag>
          <el-tag v-else-if="isInherited" type="warning" size="mini">继承自父级</el-tag>
          <el-tag v-else type="info" size="mini">未配置</el-tag>
          <span v-if="isInherited" class="gep-src">{{ effective.sourcePath }}</span>
          <span v-else-if="!effective" class="gep-hint-inline">
            分组链上没有配置 ⇒ 网关回落到「应用级加解密配置」，都没有则明文传输
          </span>
        </span>
      </div>

      <div class="gep-row">
        <span class="gep-label">生效模式</span>
        <span class="gep-value">
          <el-tag v-if="effective && effective.mode" :type="modeMeta.type" size="mini">{{ modeMeta.label }}</el-tag>
          <span v-else class="gep-empty">—</span>
        </span>
      </div>

      <div class="gep-row">
        <span class="gep-label">入参</span>
        <span class="gep-value">
          <template v-if="effective && effective.requestEncrypted">
            <code class="gep-mono">{{ effective.requestAlgorithm }}</code>
            <span class="gep-sep">/</span>{{ effective.requestMode || '—' }}
            <span class="gep-sep">/</span>{{ effective.requestPadding || '—' }}
            <span class="gep-sep">/</span>密钥 <code class="gep-mono">{{ maskKey(effective.requestKey) }}</code>
          </template>
          <span v-else class="gep-empty">明文</span>
        </span>
      </div>

      <div class="gep-row">
        <span class="gep-label">返参</span>
        <span class="gep-value">
          <template v-if="effective && effective.responseEncrypted">
            <code class="gep-mono">{{ effective.responseAlgorithm }}</code>
            <span class="gep-sep">/</span>{{ effective.responseMode || '—' }}
            <span class="gep-sep">/</span>{{ effective.responsePadding || '—' }}
            <span class="gep-sep">/</span>密钥 <code class="gep-mono">{{ maskKey(effective.responseKey) }}</code>
          </template>
          <span v-else class="gep-empty">明文</span>
        </span>
      </div>

      <div v-if="effective && effective.remark" class="gep-row">
        <span class="gep-label">备注</span>
        <span class="gep-value">{{ effective.remark }}</span>
      </div>
    </el-card>

    <div class="gep-actions">
      <PermButton
        v-if="isOwn"
        perm="api_group_encryption:update"
        type="primary"
        size="small"
        icon="el-icon-edit"
        @click="openForm"
      >编辑本分组配置</PermButton>
      <PermButton
        v-else
        perm="api_group_encryption:create"
        type="primary"
        size="small"
        icon="el-icon-plus"
        @click="openForm"
      >{{ isInherited ? '在本分组覆盖' : '配置加解密' }}</PermButton>

      <PermButton
        v-if="isOwn"
        perm="api_group_encryption:delete"
        type="text"
        class="danger-link"
        @click="remove"
      >清除（回落继承）</PermButton>

      <span v-if="isInherited" class="gep-hint-inline">（来自父级，需在本分组覆盖才能改）</span>
    </div>

    <!-- 编辑弹窗：手写 el-dialog 而非 CrudDialog —— 需要「mode 三态联动 + 按开关显隐密钥字段」，
         CrudDialog 的字段声明做不到条件显隐。必须 append-to-body（抽屉自成层叠上下文）。 -->
    <el-dialog
      :title="dialogTitle"
      :visible.sync="dialogVisible"
      width="680px"
      :close-on-click-modal="false"
      append-to-body
      @open="onDialogOpen"
    >
      <el-form ref="form" :model="form" :rules="formRules" label-width="130px">
        <el-form-item label="加解密模式" prop="mode">
          <el-radio-group v-model="form.mode">
            <el-radio label="INHERIT">继承上级</el-radio>
            <el-radio label="ENABLED">启用加解密</el-radio>
            <el-radio label="DISABLED">不需要加解密</el-radio>
          </el-radio-group>
          <div class="gep-hint">{{ modeHint }}</div>
        </el-form-item>

        <template v-if="form.mode === 'ENABLED'">
          <el-divider content-position="left">入参加密</el-divider>
          <el-form-item label="是否加密">
            <el-switch v-model="form.requestEncrypted" />
          </el-form-item>
          <template v-if="form.requestEncrypted">
            <el-form-item label="加密算法">
              <el-select v-model="form.requestAlgorithm" placeholder="请选择" style="width:100%">
                <el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" />
              </el-select>
            </el-form-item>
            <el-form-item label="加密模式">
              <el-select v-model="form.requestMode" placeholder="请选择" style="width:100%">
                <el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" />
                <el-option label="CFB" value="CFB" /><el-option label="OFB" value="OFB" />
                <el-option label="CTR" value="CTR" />
              </el-select>
            </el-form-item>
            <el-form-item label="填充方式">
              <el-select v-model="form.requestPadding" placeholder="请选择" style="width:100%">
                <el-option label="PKCS5Padding" value="PKCS5Padding" />
                <el-option label="PKCS7Padding" value="PKCS7Padding" />
                <el-option label="NoPadding" value="NoPadding" />
              </el-select>
            </el-form-item>
            <el-form-item label="密钥">
              <el-input v-model="form.requestKey" placeholder="Base64 编码的密钥" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="genKey('requestKey')">生成</el-button>
            </el-form-item>
            <el-form-item label="IV 向量">
              <el-input v-model="form.requestIv" placeholder="Base64 编码的 IV，ECB 模式可留空" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="genKey('requestIv')">生成</el-button>
            </el-form-item>
          </template>

          <el-divider content-position="left">返参加密</el-divider>
          <el-form-item label="是否加密">
            <el-switch v-model="form.responseEncrypted" />
          </el-form-item>
          <template v-if="form.responseEncrypted">
            <el-form-item label="加密算法">
              <el-select v-model="form.responseAlgorithm" placeholder="请选择" style="width:100%">
                <el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" />
              </el-select>
            </el-form-item>
            <el-form-item label="加密模式">
              <el-select v-model="form.responseMode" placeholder="请选择" style="width:100%">
                <el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" />
                <el-option label="CFB" value="CFB" /><el-option label="OFB" value="OFB" />
                <el-option label="CTR" value="CTR" />
              </el-select>
            </el-form-item>
            <el-form-item label="填充方式">
              <el-select v-model="form.responsePadding" placeholder="请选择" style="width:100%">
                <el-option label="PKCS5Padding" value="PKCS5Padding" />
                <el-option label="PKCS7Padding" value="PKCS7Padding" />
                <el-option label="NoPadding" value="NoPadding" />
              </el-select>
            </el-form-item>
            <el-form-item label="密钥">
              <el-input v-model="form.responseKey" placeholder="Base64 编码的密钥" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="genKey('responseKey')">生成</el-button>
            </el-form-item>
            <el-form-item label="IV 向量">
              <el-input v-model="form.responseIv" placeholder="Base64 编码的 IV，ECB 模式可留空" style="width:calc(100% - 80px)" />
              <el-button style="margin-left:8px" @click="genKey('responseIv')">生成</el-button>
            </el-form-item>
          </template>
        </template>

        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="256" placeholder="说明为何启用 / 为何关闭，便于他人理解" />
        </el-form-item>
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
 * 分组加解密配置面板（T15-1）
 * ------------------------------------------------------------------
 * 用户需求（2026-09-15 第 1 条）：加解密配置放到**接口分组侧**，下级继承上级，
 * 并且**可以选择不需要加解密**。
 *
 * 数据来源：
 *   GET /api-group-encryption/effective?groupId=   → 生效配置（含来源判定）
 *   后端用 EncryptionConfigResolver 计算，与网关 EncryptionHandler 走**同一份逻辑**，
 *   因此本页面显示什么，网关就按什么走 —— 不允许出现两套口径。
 *
 * 三态（本需求的关键，老表 boolean 表达不了）：
 *   INHERIT  继承上级（默认；本分组不定义，继续沿父链上找）
 *   ENABLED  本分组启用加解密（下级默认继承）
 *   DISABLED 不需要加解密（明确关闭，**且不再向上继承**，也不会回落应用级）
 *
 * 写入语义：
 *   isOwn    → PUT  /api-group-encryption/{id}   （api_group_encryption:update）
 *   非 isOwn → POST /api-group-encryption/upsert （api_group_encryption:create）
 *   两个端点各自对应一个权限点，前端按行选择走哪一个，与后端注解**一一对齐**
 *   （前端有闸门而后端无注解 = 假保护；反之则表现为「点了 403」）。
 */
import {
  getGroupEncryptionEffective,
  getGroupEncryptionOwn,
  upsertGroupEncryption,
  updateGroupEncryption,
  deleteGroupEncryption
} from '@/api/modules'
import PermButton from '@/components/common/PermButton.vue'

const MODE_META = {
  INHERIT: { label: '继承上级', type: 'info', hint: '本分组不定义加解密，网关继续沿分组树向上找最近一层配置；若整条链都没有，则回落到「应用级加解密配置」。' },
  ENABLED: { label: '启用加解密', type: 'success', hint: '本分组定义算法与密钥，组内接口与子分组**默认继承**这条配置（除非它们自己另有配置）。' },
  DISABLED: { label: '不需要加解密', type: 'warning', hint: '明确本分组链路不需要加解密：网关到此为止，**不再向上继承，也不会回落应用级**。' }
}

function emptyForm() {
  return {
    mode: 'INHERIT',
    remark: '',
    requestEncrypted: false, requestAlgorithm: '', requestMode: '', requestKey: '', requestIv: '', requestPadding: '',
    responseEncrypted: false, responseAlgorithm: '', responseMode: '', responseKey: '', responseIv: '', responsePadding: ''
  }
}

export default {
  name: 'GroupEncryptionPanel',
  components: { PermButton },
  props: {
    groupId: { type: [Number, String], default: null },
    groupName: { type: String, default: '' }
  },
  data() {
    return {
      loading: false,
      /** 生效配置（null = 整条分组链无配置） */
      effective: null,
      /** 本分组自己的行（null = 本分组未配置） */
      own: null,
      dialogVisible: false,
      dialogTitle: '配置加解密',
      submitting: false,
      form: emptyForm(),
      formRules: {
        mode: [{ required: true, message: '请选择加解密模式', trigger: 'change' }]
      }
    }
  },
  computed: {
    isOwn() {
      return !!(this.own && this.own.id)
    },
    isInherited() {
      const eff = this.effective
      if (!eff || !eff.mode) return false
      return this.normalizeId(eff.sourceGroupId) !== this.normalizeId(this.groupId)
    },
    modeMeta() {
      const m = this.effective && this.effective.mode
      return MODE_META[m] || { label: m || '—', type: 'info' }
    },
    modeHint() {
      const m = (this.form && this.form.mode) || 'INHERIT'
      return (MODE_META[m] || {}).hint || ''
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
    /** 密钥打码：首4 + **** + 末4（短密钥全打码）。展示区打码，编辑表单里是完整值。 */
    maskKey(k) {
      if (!k) return '—'
      const s = String(k)
      if (s.length <= 8) return '****'
      return s.slice(0, 4) + '****' + s.slice(-4)
    },
    genKey(field) {
      const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256))
      this.form[field] = btoa(String.fromCharCode.apply(null, bytes))
      this.$message.success('已生成随机密钥')
    },
    async load() {
      if (this.groupId === null || this.groupId === undefined || this.groupId === '') return
      this.loading = true
      try {
        const [effRes, ownRes] = await Promise.all([
          getGroupEncryptionEffective(this.groupId),
          getGroupEncryptionOwn(this.groupId)
        ])
        this.effective = (effRes && effRes.data) || null
        this.own = (ownRes && ownRes.data) || null
      } catch (e) {
        this.effective = null
        this.own = null
      } finally {
        this.loading = false
      }
    },
    /**
     * 打开表单。
     * 编辑本分组配置 → 回填自己的行；
     * 在父级基础上覆盖 → 以**继承来的值**预填（用户多半只想改一处，空白起始会逼他重抄一遍密钥，抄错就是线上事故）。
     */
    openForm() {
      if (this.isOwn) {
        this.dialogTitle = '编辑本分组加解密配置'
        this.form = { ...emptyForm(), ...this.own }
      } else {
        this.dialogTitle = this.isInherited ? '在本分组覆盖父级加解密配置' : '配置本分组加解密'
        const eff = this.effective || {}
        this.form = {
          ...emptyForm(),
          mode: eff.mode === 'ENABLED' ? 'ENABLED' : 'INHERIT',
          requestEncrypted: !!eff.requestEncrypted,
          requestAlgorithm: eff.requestAlgorithm || '',
          requestMode: eff.requestMode || '',
          requestKey: eff.requestKey || '',
          requestIv: eff.requestIv || '',
          requestPadding: eff.requestPadding || '',
          responseEncrypted: !!eff.responseEncrypted,
          responseAlgorithm: eff.responseAlgorithm || '',
          responseMode: eff.responseMode || '',
          responseKey: eff.responseKey || '',
          responseIv: eff.responseIv || '',
          responsePadding: eff.responsePadding || ''
        }
      }
      this.dialogVisible = true
    },
    onDialogOpen() {
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        // 前端先做一次同后端一致的校验：ENABLED 时至少开一侧，且开启侧必须有算法与密钥。
        // 后端同样会拦（双保险），但前端拦下能少一次无谓请求。
        const f = this.form
        if (f.mode === 'ENABLED') {
          if (!f.requestEncrypted && !f.responseEncrypted) {
            return this.$message.warning('选择「启用加解密」时，入参与返参至少要开启一项；若不需要加解密请改选「不需要加解密」')
          }
          if (f.requestEncrypted && (!f.requestAlgorithm || !f.requestKey)) {
            return this.$message.warning('已开启入参加密，请填写入参算法与密钥')
          }
          if (f.responseEncrypted && (!f.responseAlgorithm || !f.responseKey)) {
            return this.$message.warning('已开启返参加密，请填写返参算法与密钥')
          }
        }
        this.submitting = true
        try {
          const payload = { ...f }
          if (this.isOwn) {
            await updateGroupEncryption(this.own.id, payload)
            this.$message.success('配置已更新')
          } else {
            await upsertGroupEncryption(this.groupId, payload)
            this.$message.success('配置已保存')
          }
          this.dialogVisible = false
          await this.load()
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.submitting = false
        }
      })
    },
    remove() {
      if (!this.isOwn) return
      this.$confirm(
        '确认清除本分组的加解密配置？清除后该分组将回落到父级继承（若父级有配置）。',
        '清除确认',
        { type: 'warning' }
      ).then(async () => {
        try {
          await deleteGroupEncryption(this.own.id)
          this.$message.success('已清除')
          await this.load()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.gep-panel { padding: 4px; }
.gep-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.gep-head-left { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.gep-group { font-size: 15px; font-weight: 600; color: #17233d; }
.gep-sub { font-size: 12px; color: #9aa7bf; }
.gep-tip { margin-bottom: 12px; }
.gep-tip b { color: #1e40af; }

.gep-card { margin-bottom: 14px; }
.gep-row { display: flex; align-items: flex-start; gap: 10px; padding: 7px 0; border-bottom: 1px dashed #eef1f7; }
.gep-row:last-child { border-bottom: none; }
.gep-label { flex: none; width: 78px; color: #5c6b8a; font-size: 13px; }
.gep-value { flex: 1; min-width: 0; font-size: 13px; color: #17233d; word-break: break-all; }
.gep-mono { font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12px; }
.gep-sep { color: #c0c6d4; margin: 0 4px; }
.gep-src { margin-left: 8px; font-size: 12px; color: #5c6b8a; }
.gep-empty { color: #c0c6d4; }
.gep-hint { font-size: 11px; color: #9aa7bf; margin-top: 2px; line-height: 1.6; }
.gep-hint-inline { font-size: 11px; color: #9aa7bf; margin-left: 6px; }
.gep-actions { display: flex; align-items: center; gap: 10px; }

.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
