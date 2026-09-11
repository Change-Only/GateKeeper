<template>
  <div class="page-container perm-role">
    <div class="page-head">
      <h2>角色管理</h2>
      <span class="page-tag">角色定义 + 菜单/权限点授权 · 授权变更即时刷新权限缓存</span>
    </div>

    <div class="toolbar">
      <span class="toolbar-tip">共 {{ total }} 个角色</span>
      <span class="spacer" />
      <PermButton perm="sys:role:create" type="primary" size="small" icon="el-icon-plus" @click="onCreate">新建角色</PermButton>
    </div>

    <CrudTable ref="table" :columns="columns" :fetch="fetchData" :show-pagination="false" :actions-width="230">
      <template #status="{row}">
        <StatusTag :entity="'role'" :value="row.status" />
      </template>
      <template #actions="{row}">
        <PermButton perm="sys:role:update" type="text" size="mini" @click="onEdit(row)">编辑</PermButton>
        <PermButton perm="sys:role:grant" type="text" size="mini" @click="onGrant(row)">配置权限</PermButton>
        <PermButton perm="sys:role:delete" type="text" size="mini" class="danger-link" @click="onDelete(row)">删除</PermButton>
      </template>
    </CrudTable>

    <CrudDialog
      :visible.sync="dialog.visible"
      :title="dialog.form.id ? '编辑角色' : '新建角色'"
      :model="dialog.form"
      :fields="dialog.fields"
      :rules="dialog.rules"
      :width="'600px'"
      :loading="dialog.loading"
      @submit="onSubmit"
    />

    <!-- 配置角色权限：菜单树 + 权限点勾选 -->
    <el-dialog
      :title="`配置角色权限 · ${grant.roleName}`"
      :visible.sync="grant.visible"
      width="720px"
      :close-on-click-modal="false"
    >
      <div class="grant-tip">勾选该角色可访问的菜单与可调用的权限点（高危操作：保存后即时生效并刷新相关用户权限缓存）。</div>
      <el-tree
        ref="menuTree"
        v-loading="grant.loading"
        :data="grant.tree"
        node-key="id"
        show-checkbox
        default-expand-all
        :props="{ label: 'name', children: 'children' }"
        class="menu-tree"
      >
        <span slot-scope="{ data }" class="tree-node">
          <span>{{ data.name }}</span>
          <el-tag v-if="data.permCode" size="mini" effect="plain" :type="data.riskFlag === 1 ? 'danger' : 'info'" class="tree-tag">{{ data.permCode }}</el-tag>
        </span>
      </el-tree>
      <template slot="footer">
        <el-button @click="grant.visible = false">取消</el-button>
        <el-button type="primary" :loading="grant.saving" @click="saveGrant">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import {
  getRoleList,
  createRole,
  updateRole,
  deleteRole,
  getMenuList,
  getRoleMenuIds,
  replaceRoleMenus
} from '@/api/modules'

export default {
  name: 'PermRole',
  data() {
    return {
      total: 0,
      columns: [
        { prop: 'roleName', label: '角色名称', minWidth: 140, showOverflowTooltip: true },
        { prop: 'roleCode', label: '角色编码', width: 180, showOverflowTooltip: true },
        { prop: 'description', label: '说明', minWidth: 200, showOverflowTooltip: true, formatter: (v) => v || '—' },
        { prop: 'status', label: '状态', width: 90, slot: 'status' },
        { prop: 'createdAt', label: '创建时间', width: 170, formatter: (v) => v || '—' }
      ],
      dialog: { visible: false, loading: false, form: {}, fields: [], rules: {} },
      grant: { visible: false, loading: false, saving: false, roleId: null, roleName: '', tree: [] }
    }
  },
  methods: {
    fetchData: function() {
      const self = this
      return async() => {
        const res = await getRoleList()
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
    buildFields() {
      return [
        { prop: 'roleName', label: '角色名称', type: 'input', required: true, span: 12, placeholder: '如：平台管理员' },
        { prop: 'roleCode', label: '角色编码', type: 'input', required: true, span: 12, placeholder: '如：ADMIN' },
        { prop: 'description', label: '说明', type: 'textarea', span: 24, rows: 3, placeholder: '角色职责说明' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    buildRules() {
      return {
        roleName: [{ required: true, message: '角色名称不能为空', trigger: 'blur' }],
        roleCode: [{ required: true, message: '角色编码不能为空', trigger: 'blur' }]
      }
    },
    onCreate() {
      this.dialog.form = { roleName: '', roleCode: '', description: '', status: 1 }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.dialog.visible = true
    },
    onEdit(row) {
      this.dialog.form = { ...row }
      this.dialog.fields = this.buildFields()
      this.dialog.rules = this.buildRules()
      this.dialog.visible = true
    },
    async onSubmit(form) {
      this.dialog.loading = true
      try {
        if (form.id) {
          await updateRole(form.id, form)
          this.$message.success('角色已更新')
        } else {
          await createRole(form)
          this.$message.success('角色已创建')
        }
        this.dialog.visible = false
        this.reload()
      } catch (e) {
        // axios 拦截器已弹错
      } finally {
        this.dialog.loading = false
      }
    },
    onDelete(row) {
      this.$confirm(`确认删除角色「${row.roleName}」？`, '删除确认', { type: 'warning' })
        .then(async() => {
          try {
            await deleteRole(row.id)
            this.$message.success('已删除')
            this.reload()
          } catch (e) {}
        }).catch(() => {})
    },
    // 把扁平菜单构造成树（pid=0 为顶级）
    buildTree(menus) {
      const map = {}
      const roots = []
      menus.forEach((m) => { map[m.id] = { ...m, children: [] } })
      menus.forEach((m) => {
        const node = map[m.id]
        if (m.pid && map[m.pid]) map[m.pid].children.push(node)
        else roots.push(node)
      })
      return roots
    },
    async onGrant(row) {
      this.grant.roleId = row.id
      this.grant.roleName = row.roleName
      this.grant.visible = true
      this.grant.loading = true
      this.grant.tree = []
      try {
        const [menuRes, idsRes] = await Promise.all([getMenuList(), getRoleMenuIds(row.id)])
        const menus = (menuRes && menuRes.data) || []
        this.grant.tree = this.buildTree(menus)
        const checkedIds = (idsRes && idsRes.data) || []
        this.$nextTick(() => {
          if (this.$refs.menuTree) this.$refs.menuTree.setCheckedKeys(checkedIds)
        })
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.grant.loading = false
      }
    },
    async saveGrant() {
      if (!this.$refs.menuTree) return
      const checked = this.$refs.menuTree.getCheckedKeys()
      const half = this.$refs.menuTree.getHalfCheckedKeys()
      const menuIds = [...checked, ...half]
      this.grant.saving = true
      try {
        await replaceRoleMenus(this.grant.roleId, menuIds)
        this.$message.success('授权已保存')
        this.grant.visible = false
      } catch (e) {
        // 拦截器已弹错
      } finally {
        this.grant.saving = false
      }
    }
  }
}
</script>

<style scoped>
.perm-role .toolbar { display: flex; align-items: center; gap: 8px; padding: 0 0 12px; }
.perm-role .toolbar-tip { color: #5c6b8a; font-size: 13px; }
.perm-role .toolbar .spacer { flex: 1; }
.perm-role .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.perm-role .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.perm-role .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.perm-role .grant-tip { color: #5c6b8a; font-size: 13px; margin-bottom: 12px; }
.perm-role .menu-tree { max-height: 460px; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; }
.perm-role .tree-node { display: inline-flex; align-items: center; gap: 8px; }
.perm-role .tree-tag { transform: scale(.9); }
</style>
