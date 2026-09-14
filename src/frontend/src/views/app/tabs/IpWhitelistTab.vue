<template>
  <div class="api-tab">
    <div class="tab-toolbar">
      <PermButton perm="app:ipwhitelist:add" type="primary" size="small" icon="el-icon-plus" @click="openCreate">新增白名单</PermButton>
      <span class="tab-hint">仅允许白名单内的 IP / CIDR 访问该应用（外部合作方应用建议强制配置）</span>
    </div>

    <el-table :data="list" border stripe size="medium" v-loading="loading" class="wl-table">
      <el-table-column prop="ipCidr" label="IP / CIDR" min-width="180" />
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="创建时间" width="160" :formatter="createdAtCell" />
      <el-table-column label="操作" width="100" fixed="right">
        <template slot-scope="{ row }">
          <PermButton perm="app:ipwhitelist:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
        </template>
      </el-table-column>
      <template slot="empty">
        <EmptyState title="暂无 IP 白名单" />
      </template>
    </el-table>

    <!-- append-to-body 必加：本 Tab 位于「应用详情」抽屉内，抽屉的 .el-drawer__wrapper
         是 position:fixed + z-index 自成的层叠上下文；弹窗若内联渲染会被困在里面，
         被 Element 的单例遮罩 .v-modal（z 跟随顶层弹窗、挂在 body 上）整片压住。 -->
    <el-dialog title="新增 IP 白名单" :visible.sync="dialogVisible" width="480px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="IP / CIDR" prop="ipCidr">
          <el-input v-model="form.ipCidr" placeholder="如 10.0.0.1 或 10.0.0.0/24" maxlength="64" />
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
 * 应用 IP 白名单 Tab（T05 Phase 1 · app-list 详情）
 * 对接 /app/{id}/ip-whitelist（GET 列表 / POST 新增 / DELETE 删除）。
 * 字段对齐 AppIpWhitelist：ipCidr / remark。
 */
import { getIpWhitelist, addIpWhitelist, removeIpWhitelist } from '@/api/modules'
import EmptyState from '@/components/EmptyState.vue'

export default {
  name: 'IpWhitelistTab',
  components: { EmptyState },
  props: {
    appId: { type: [Number, String], default: null }
  },
  data() {
    return {
      list: [],
      loading: false,
      dialogVisible: false,
      saving: false,
      form: { ipCidr: '', remark: '' },
      rules: {
        ipCidr: [{ required: true, message: '请填写 IP / CIDR', trigger: 'blur' }]
      }
    }
  },
  watch: {
    appId: {
      immediate: true,
      handler(v) { if (v != null) this.reload() }
    }
  },
  methods: {
    /** 后端 createdAt 是 LocalDateTime，ISO 输出带 `T`（2026-09-10T14:11:04），统一转可读形式 */
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    /**
     * el-table-column 的 formatter 签名是 (row, column, cellValue, index)，与 CrudTable 的
     * formatter(value) 不同 —— 这里必须按行取值。
     * 原先该列写的是 `formatter=""`（空字符串），Element 的 prop 类型校验会持续报
     * `Invalid prop: type check failed for prop "formatter". Expected Function, got String`，
     * 且格式化静默失效（时间直接显示带 T 的 ISO 串）。
     */
    createdAtCell(row) {
      return this.fmtTime(row && row.createdAt)
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
      this.form = { ipCidr: '', remark: '' }
      this.dialogVisible = true
      this.$nextTick(() => { if (this.$refs.form) this.$refs.form.clearValidate() })
    },
    submit() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.saving = true
        try {
          await addIpWhitelist(this.appId, { ...this.form })
          this.$message.success('已新增')
          this.dialogVisible = false
          this.reload()
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.saving = false
        }
      })
    },
    remove(row) {
      this.$confirm(`确认删除白名单「${row.ipCidr}」？`, '删除确认', { type: 'warning' }).then(async () => {
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
.tab-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.tab-hint { font-size: 12px; color: #9aa7bf; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
