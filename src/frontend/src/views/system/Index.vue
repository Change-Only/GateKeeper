<template>
  <div class="page-container">
    <el-card>
      <!-- 系统管理入口：用户管理 / 角色管理 / 操作审计三个标签页 -->
      <el-tabs v-model="activeTab" @tab-click="handleTabClick">
        <!-- ============ 用户管理 ============ -->
        <el-tab-pane label="用户管理" name="user">
          <div class="toolbar">
            <el-input v-model="userQuery.username" placeholder="用户名" clearable style="width:200px" @keyup.enter.native="loadUsers" />
            <el-button type="primary" @click="loadUsers">查询</el-button>
            <div class="spacer"></div>
            <el-button type="primary" @click="showUserDialog()">＋ 新增用户</el-button>
          </div>
          <el-table :data="userList" border v-loading="loading">
            <template slot="empty"><EmptyState description="暂无用户，点击右上角「新增用户」开始" /></template>
            <!-- 用户名：主行用户名，副标题展示真实姓名 -->
            <el-table-column label="用户名" width="180">
              <template slot-scope="{row}">
                <div>{{ row.username }}</div>
                <div class="sub-text">{{ row.realName || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="phone" label="手机" width="140" />
            <el-table-column prop="email" label="邮箱" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template slot-scope="{row}"><el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag></template>
            </el-table-column>
            <!-- 最近登录：时间与登录 IP 分行展示 -->
            <el-table-column label="最近登录" width="230">
              <template slot-scope="{row}">
                <div>{{ row.lastLoginAt || '-' }}</div>
                <div class="sub-text">{{ row.lastLoginIp || '-' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="260" fixed="right">
              <template slot-scope="{row}">
                <el-button size="mini" @click="showUserDialog(row)">编辑</el-button>
                <el-button size="mini" :type="row.status===1?'warning':'success'" @click="toggleUserStatus(row)">{{ row.status===1?'停用':'启用' }}</el-button>
                <el-button size="mini" @click="resetPassword(row)">重置密码</el-button>
                <el-button size="mini" type="danger" @click="removeUser(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" @current-change="v=>{userQuery.current=v;loadUsers()}" :current-page="userQuery.current"
            :page-size="userQuery.size" :total="userTotal" layout="total, prev, pager, next" />
        </el-tab-pane>

        <!-- ============ 角色管理 ============ -->
        <el-tab-pane label="角色管理" name="role">
          <div class="toolbar">
            <div class="spacer"></div>
            <el-button type="primary" @click="showRoleDialog()">＋ 新增角色</el-button>
          </div>
          <el-table :data="roleList" border v-loading="loading">
            <template slot="empty"><EmptyState description="暂无角色，点击右上角「新增角色」开始" /></template>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column prop="roleName" label="角色名称" width="160" />
            <el-table-column prop="roleCode" label="角色编码" width="160" />
            <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template slot-scope="{row}"><el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template slot-scope="{row}">
                <el-button size="mini" @click="showRoleDialog(row)">编辑</el-button>
                <el-button size="mini" type="danger" @click="removeRole(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ============ 操作审计 ============ -->
        <el-tab-pane label="操作审计" name="log">
          <div class="toolbar">
            <el-input v-model="logQuery.operatorName" placeholder="操作人" clearable style="width:200px" @keyup.enter.native="loadLogs" />
            <el-button type="primary" @click="loadLogs">查询</el-button>
          </div>
          <el-table :data="logList" border v-loading="loading">
            <template slot="empty"><EmptyState description="暂无操作审计记录" /></template>
            <el-table-column prop="createdAt" label="操作时间" width="170" />
            <el-table-column prop="operatorName" label="操作人" width="110" />
            <el-table-column label="操作类型" width="100">
              <template slot-scope="{row}"><el-tag :type="operationTag(row.operationType)">{{ row.operationType }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="operationModule" label="模块" width="120" />
            <el-table-column prop="operationDesc" label="操作描述" min-width="180" show-overflow-tooltip />
            <el-table-column prop="requestMethod" label="请求方法" width="90" />
            <el-table-column prop="requestUrl" label="请求 URL" min-width="200" show-overflow-tooltip />
            <el-table-column prop="clientIp" label="IP" width="130" />
            <el-table-column prop="costTime" label="耗时(ms)" width="100" />
          </el-table>
          <el-pagination class="pagination" @current-change="v=>{logQuery.current=v;loadLogs()}" :current-page="logQuery.current"
            :page-size="logQuery.size" :total="logTotal" layout="total, prev, pager, next" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增/编辑用户对话框 -->
    <el-dialog :title="userForm.id?'编辑用户':'新增用户'" :visible.sync="userDialogVisible" width="500px">
      <el-form :model="userForm" label-width="80px">
        <el-form-item label="用户名"><el-input v-model="userForm.username" :disabled="!!userForm.id" /></el-form-item>
        <el-form-item label="真实姓名"><el-input v-model="userForm.realName" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="userForm.phone" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="userForm.email" /></el-form-item>
        <!-- 新增时设置初始密码，编辑时不可修改（修改走重置密码） -->
        <el-form-item label="密码" v-if="!userForm.id"><el-input v-model="userForm.password" placeholder="默认 123456" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="userDialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveUser">保存</el-button></div>
    </el-dialog>

    <!-- 新增/编辑角色对话框 -->
    <el-dialog :title="roleForm.id?'编辑角色':'新增角色'" :visible.sync="roleDialogVisible" width="500px">
      <el-form :model="roleForm" label-width="80px">
        <el-form-item label="角色名称"><el-input v-model="roleForm.roleName" /></el-form-item>
        <el-form-item label="角色编码"><el-input v-model="roleForm.roleCode" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="roleForm.description" type="textarea" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="roleForm.status" style="width:100%">
            <el-option label="启用" :value="1" /><el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="roleDialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveRole">保存</el-button></div>
    </el-dialog>
  </div>
</template>

<script>
import {
  getUserList, createUser, updateUser, updateUserStatus, deleteUser, resetUserPassword,
  getRoleList, createRole, updateRole, deleteRole, getOperationLogList
} from '@/api/modules'

export default {
  data() {
    return {
      // 当前激活的标签页
      activeTab: 'user',
      // 用户查询条件：分页参数 + 用户名模糊匹配
      userQuery: { current: 1, size: 10, username: '' },
      // 用户列表数据与总条数
      userList: [], userTotal: 0, loading: false, saving: false,
      // 用户对话框显示状态与表单数据
      userDialogVisible: false, userForm: {},
      // 角色列表数据（不分页）
      roleList: [],
      // 角色对话框显示状态与表单数据
      roleDialogVisible: false, roleForm: {},
      // 操作日志查询条件：分页参数 + 操作人
      logQuery: { current: 1, size: 10, operatorName: '' },
      // 操作日志列表数据与总条数
      logList: [], logTotal: 0
    }
  },
  mounted() { this.loadUsers() },
  methods: {
    // 标签页切换：按需加载对应数据
    handleTabClick(tab) {
      if (tab.name === 'role') this.loadRoles()
      if (tab.name === 'log') this.loadLogs()
    },
    // 加载用户列表（带分页）
    async loadUsers() {
      this.loading = true
      try {
        const res = await getUserList(this.userQuery)
        this.userList = (res.data && res.data.records) || []
        this.userTotal = (res.data && res.data.total) || 0
      } catch (e) {
        this.$message.error('加载用户列表失败')
      } finally { this.loading = false }
    },
    // 打开新增/编辑用户对话框，传行数据为编辑，否则为新增
    showUserDialog(row) {
      this.userForm = row ? { ...row } : { password: '123456' }
      this.userDialogVisible = true
    },
    // 保存用户：有 id 走更新，否则走新增
    async saveUser() {
      if (this.saving) return
      if (!this.userForm.username) { this.$message.warning('请输入用户名'); return }
      this.saving = true
      try {
        if (this.userForm.id) {
          await updateUser(this.userForm.id, this.userForm)
        } else {
          await createUser(this.userForm)
        }
        this.userDialogVisible = false
        this.loadUsers()
        this.$message.success('保存成功')
      } catch (e) {
        this.$message.error('保存用户失败')
      } finally { this.saving = false }
    },
    // 启停用户：启用与停用互相切换
    async toggleUserStatus(row) {
      try {
        await updateUserStatus(row.id, row.status === 1 ? 0 : 1)
        this.loadUsers()
      } catch (e) {
        this.$message.error('更新用户状态失败')
      }
    },
    // 重置用户密码：弹窗输入新密码，默认 123456
    async resetPassword(row) {
      const { value } = await this.$prompt('请输入新密码', '重置密码', {
        inputValue: '123456',
        inputPattern: /.{6,}/,
        inputErrorMessage: '密码至少 6 位'
      }).catch(() => ({}))
      if (!value) return
      try {
        await resetUserPassword(row.id, { password: value })
        this.$message.success('密码已重置')
      } catch (e) {
        this.$message.error('重置密码失败')
      }
    },
    // 删除用户（二次确认）
    async removeUser(row) {
      await this.$confirm('确认删除?')
      try {
        await deleteUser(row.id)
        this.loadUsers()
        this.$message.success('删除成功')
      } catch (e) {
        this.$message.error('删除用户失败')
      }
    },
    // 加载角色列表（不分页）
    async loadRoles() {
      this.loading = true
      try {
        const res = await getRoleList()
        this.roleList = res.data || []
      } catch (e) {
        this.$message.error('加载角色列表失败')
      } finally { this.loading = false }
    },
    // 打开新增/编辑角色对话框
    showRoleDialog(row) {
      this.roleForm = row ? { ...row } : { status: 1 }
      this.roleDialogVisible = true
    },
    // 保存角色：有 id 走更新，否则走新增
    async saveRole() {
      if (this.saving) return
      if (!this.roleForm.roleName) { this.$message.warning('请输入角色名称'); return }
      if (!this.roleForm.roleCode) { this.$message.warning('请输入角色编码'); return }
      this.saving = true
      try {
        if (this.roleForm.id) {
          await updateRole(this.roleForm.id, this.roleForm)
        } else {
          await createRole(this.roleForm)
        }
        this.roleDialogVisible = false
        this.loadRoles()
        this.$message.success('保存成功')
      } catch (e) {
        this.$message.error('保存角色失败')
      } finally { this.saving = false }
    },
    // 删除角色（二次确认）
    async removeRole(row) {
      await this.$confirm('确认删除?')
      try {
        await deleteRole(row.id)
        this.loadRoles()
        this.$message.success('删除成功')
      } catch (e) {
        this.$message.error('删除角色失败')
      }
    },
    // 加载操作审计日志（带分页）
    async loadLogs() {
      this.loading = true
      try {
        const res = await getOperationLogList(this.logQuery)
        this.logList = (res.data && res.data.records) || []
        this.logTotal = (res.data && res.data.total) || 0
      } catch (e) {
        this.$message.error('加载操作日志失败')
      } finally { this.loading = false }
    },
    // 状态 → 标签类型：1 启用(success) / 0 停用(danger)
    statusTag(status) { return status === 1 ? 'success' : status === 0 ? 'danger' : 'info' },
    // 状态 → 中文文案
    statusText(status) { return status === 1 ? '正常' : status === 0 ? '已停用' : '未知' },
    // 操作类型 → 标签类型：新增/查询/更新/删除分别对应不同语义色
    operationTag(type) {
      if (type === 'CREATE' || type === '新增') return 'success'
      if (type === 'UPDATE' || type === '修改') return 'warning'
      if (type === 'DELETE' || type === '删除') return 'danger'
      return 'info'
    }
  }
}
</script>

<style scoped>
.pagination { margin-top: 16px; text-align: right; }
/* 表格副标题：真实姓名、登录 IP 等次要信息 */
.sub-text { color: #909399; font-size: 12px; line-height: 1.4; }
</style>
