<template>
  <div class="page-container perm-user">
    <div class="page-head">
      <h2>用户管理</h2>
      <span class="page-tag">管理后台登录账号 · 密码 BCrypt 加密落库</span>
    </div>

    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="query" size="small" @submit.native.prevent>
        <el-form-item label="账号">
          <el-input v-model="query.username" placeholder="账号模糊匹配" clearable style="width:200px" @keyup.enter.native="reload" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="reload">查询</el-button>
          <el-button icon="el-icon-refresh-left" @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 个账号</span>
      <span class="spacer" />
      <PermButton perm="sys:user:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建用户</PermButton>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :query="query" :actions-width="220">
      <template #status="{row}">
        <StatusTag :entity="'user'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton perm="sys:user:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="sys:user:update" type="text" size="mini" @click="onToggleStatus(row)">
          {{ row.status === 1 ? '停用' : '启用' }}
        </PermButton>
        <PermButton perm="sys:user:resetpwd" type="text" size="mini" @click="onResetPwd(row)">重置密码</PermButton>
        <PermButton perm="sys:user:delete" type="text" size="mini" class="danger-link" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑用户' : '新建用户'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'640px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    >
      <template #extra="{form}">
        <el-alert v-if="!form.id" type="info" :closable="false" show-icon>
          <template #title>新建用户需设置初始密码，密码将经 BCrypt 加密后落库。</template>
        </el-alert>
        <el-alert v-else type="warning" :closable="false" show-icon>
          <template #title>账号（username）创建后不可修改；如需改密请用列表「重置密码」。</template>
        </el-alert>
      </template>
    </CrudDialog>
  </div>
</template>

<script>
import {
  getUserList,
  createUser,
  updateUser,
  deleteUser,
  updateUserStatus,
  resetUserPassword
} from '@/api/modules'

export default {
  name: 'PermUser',
  data() {
    return {
      query: { username: '' },
      total: 0,
      columns: [
        { prop: 'username', label: '账号', width: 140, showOverflowTooltip: true },
        { prop: 'realName', label: '姓名', width: 120, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'email', label: '邮箱', minWidth: 180, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'phone', label: '手机号', width: 130, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'lastLoginAt', label: '最后登录', width: 170, formatter: (v) => v || '—' },
        { prop: 'lastLoginIp', label: '最后登录IP', width: 140, formatter: (v) => v || '—' }
      ],
      dialog: { visible: false, loading: false, form: {}, fields: [], rules: {} }
    }
  },
  methods: {
    fetchData: function() {
      const self = this
      return async(params) => {
        const { page, size, username } = params
        const res = await getUserList({ current: page, size, username })
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
      this.query.username = ''
      this.reload()
    },
    buildFields(editing) {
      const base = [
        {
          prop: 'username', label: '账号', type: 'input', required: true, span: 12,
          placeholder: editing ? '账号不可修改' : '登录账号（唯一）',
          ...(editing ? { disabled: true } : {})
        },
        { prop: 'realName', label: '姓名', type: 'input', span: 12, placeholder: '真实姓名' },
        { prop: 'email', label: '邮箱', type: 'input', span: 12, placeholder: 'name@example.com' },
        { prop: 'phone', label: '手机号', type: 'input', span: 12, placeholder: '11 位手机号' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
      if (!editing) {
        base.push({ prop: 'password', label: '初始密码', type: 'input', required: true, span: 12, placeholder: '登录初始密码' })
      }
      return base
    },
    buildRules(editing) {
      const rules = {
        username: [{ required: true, message: '账号不能为空', trigger: 'blur' }]
      }
      if (!editing) {
        rules.password = [{ required: true, message: '初始密码不能为空', trigger: 'blur' }]
      }
      return rules
    },
    onCreate() {
      this.dialog.form = { username: '', realName: '', email: '', phone: '', password: '', status: 1 }
      this.dialog.fields = this.buildFields(false)
      this.dialog.rules = this.buildRules(false)
      this.dialog.visible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.buildFields(true)
      this.dialog.rules = this.buildRules(true)
      this.dialog.visible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateUser(form.id, form)
          this.$message.success('用户已更新')
        } else {
          await createUser(form)
          this.$message.success('用户已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {
        // axios 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onToggleStatus(row) {
      const next = row.status === 1 ? 0 : 1
      this.$confirm(`确认${next === 1 ? '启用' : '停用'}账号「${row.username}」？`, '状态变更（高危）', { type: 'warning' })
        .then(async() => {
          try {
            await updateUserStatus(row.id, next)
            this.$message.success('状态已更新')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    },
    onResetPwd(row) {
      this.$prompt(`为用户「${row.username}」设置新密码`, '重置密码', {
        inputType: 'password',
        inputValidator: (v) => (v ? true : '新密码不能为空')
      }).then(async({ value }) => {
        try {
          await resetUserPassword(row.id, { password: value })
          this.$message.success('密码已重置')
        } catch (e) {}
      }).catch(() => {})
    },
    onDelete(row) {
      this.$confirm(`确认删除账号「${row.username}」？`, '删除确认', { type: 'warning' })
        .then(async() => {
          try {
            await deleteUser(row.id)
            this.$message.success('已删除')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.perm-user .filter-card { margin-bottom: 12px; }
.perm-user .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.perm-user .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.perm-user .toolbar .spacer { flex: 1; }
.perm-user .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.perm-user .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.perm-user .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
</style>
