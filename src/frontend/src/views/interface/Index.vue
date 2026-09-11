<template>
  <div class="page-container">
    <!-- 左右两栏布局：左侧分组树，右侧接口列表 -->
    <div class="iface-layout">
      <!-- 左侧：接口分组树 -->
      <el-card class="group-card">
        <div slot="header" class="card-header">
          <span>接口分组</span>
          <el-button size="mini" type="primary" @click="addGroup(null)">新增分组</el-button>
        </div>
        <!-- 多层级分组树，点击节点按分组筛选接口 -->
        <el-tree
          :data="groupTree"
          :props="{ label: 'groupName', children: 'children' }"
          node-key="id"
          highlight-current
          :expand-on-click-node="false"
          @node-click="onGroupClick">
          <!-- 自定义树节点：展示分组名，悬停时显示增/改/删操作按钮 -->
          <span slot-scope="{ node, data }" class="tree-node">
            <span>{{ node.label }}</span>
            <span class="tree-actions">
              <el-button type="text" size="mini" @click.stop="addGroup(data)">加子级</el-button>
              <el-button type="text" size="mini" @click.stop="editGroup(data)">改</el-button>
              <el-button type="text" size="mini" class="danger" @click.stop="removeGroup(data)">删</el-button>
            </span>
          </span>
        </el-tree>
      </el-card>

      <!-- 右侧：接口列表 -->
      <el-card class="list-card">
        <div class="toolbar">
          <el-input v-model="query.interfaceName" placeholder="接口名称" clearable style="width:200px" />
          <el-button type="primary" @click="loadData">查询</el-button>
          <div class="spacer"></div>
          <el-button type="primary" @click="showDialog()">＋ 新建接口</el-button>
        </div>
        <el-table :data="list" border v-loading="loading">
          <template slot="empty"><EmptyState description="当前分组下暂无接口，点击右上角「新建接口」开始" /></template>
          <el-table-column prop="interfaceName" label="接口名称" />
          <el-table-column prop="interfacePath" label="接口路径" width="180" />
          <!-- 方法列：彩色方法标签 -->
          <el-table-column prop="requestMethod" label="方法" width="80">
            <template slot-scope="{row}"><span class="method" :class="row.requestMethod.toLowerCase()">{{ row.requestMethod }}</span></template>
          </el-table-column>
          <el-table-column prop="requestParamType" label="入参类型" width="90" />
          <el-table-column prop="backendUrl" label="后端地址" width="220" show-overflow-tooltip />
          <!-- 状态列：启用/停用 -->
          <el-table-column label="状态" width="70"><template slot-scope="{row}"><el-tag :type="row.status===1?'success':'info'">{{ row.status===1?'启用':'停用' }}</el-tag></template></el-table-column>
          <!-- 操作列：编辑 / 加密配置 / 启停 / 删除 -->
          <el-table-column label="操作" width="250" fixed="right">
            <template slot-scope="{row}">
              <el-button size="mini" @click="showDialog(row)">编辑</el-button>
              <el-button size="mini" @click="showEncryption(row)">加密</el-button>
              <el-button size="mini" :type="row.status===1?'warning':'success'" @click="toggleStatus(row)">{{ row.status===1?'停用':'启用' }}</el-button>
              <el-button size="mini" type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
      </el-card>
    </div>

    <!-- 接口编辑/新增对话框 -->
    <el-dialog :title="form.id?'编辑接口':'新增接口'" :visible.sync="dialogVisible" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="接口名称"><el-input v-model="form.interfaceName" /></el-form-item>
        <el-form-item label="接口路径"><el-input v-model="form.interfacePath" placeholder="/gateway/xxx/yyy" /></el-form-item>
        <el-form-item label="请求类型">
          <el-select v-model="form.requestMethod" style="width:100%">
            <el-option label="GET" value="GET" /><el-option label="POST" value="POST" /><el-option label="PUT" value="PUT" /><el-option label="DELETE" value="DELETE" />
          </el-select>
        </el-form-item>
        <el-form-item label="入参类型">
          <el-select v-model="form.requestParamType" style="width:100%">
            <el-option label="JSON" value="JSON" /><el-option label="FORM 表单" value="FORM" /><el-option label="QUERY 查询串" value="QUERY" />
          </el-select>
        </el-form-item>
        <!-- 所属分组：级联选择器支持任意层级选择 -->
        <el-form-item label="所属分组">
          <el-cascader v-model="form.groupId" :options="groupCascader" :props="{ checkStrictly: true, value: 'id', label: 'groupName' }" clearable style="width:100%" />
        </el-form-item>
        <el-form-item label="后端服务地址"><el-input v-model="form.backendUrl" placeholder="http://backend-service/api/xxx" /></el-form-item>
        <el-form-item label="超时(ms)"><el-input-number v-model="form.timeoutMs" :min="1000" :max="60000" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></div>
    </el-dialog>

    <!-- 分组编辑/新增对话框 -->
    <el-dialog :title="groupForm.id?'编辑分组':'新增分组'" :visible.sync="groupVisible" width="420px">
      <el-form :model="groupForm" label-width="80px">
        <el-form-item label="分组名称"><el-input v-model="groupForm.groupName" /></el-form-item>
        <el-form-item label="父分组"><el-cascader v-model="groupForm.parentId" :options="groupCascader" :props="{ checkStrictly: true, value: 'id', label: 'groupName' }" clearable style="width:100%" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="groupForm.description" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="groupVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveGroup">保存</el-button></div>
    </el-dialog>

    <!-- 加密配置对话框：分入参加密与返参加密两部分 -->
    <el-dialog :title="'接口加密配置 - ' + encInterfaceName" :visible.sync="encVisible" width="620px">
      <el-form :model="encForm" label-width="130px">
        <!-- 入参加密配置 -->
        <el-divider content-position="left">入参加密</el-divider>
        <el-form-item label="是否加密"><el-switch v-model="encForm.requestEncrypted" /></el-form-item>
        <el-form-item label="加密类型"><el-select v-model="encForm.requestAlgorithm" style="width:100%"><el-option label="无" value="" /><el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" /></el-select></el-form-item>
        <el-form-item label="加密模式"><el-select v-model="encForm.requestMode" style="width:100%"><el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" /><el-option label="CFB" value="CFB" /><el-option label="CTR" value="CTR" /></el-select></el-form-item>
        <el-form-item label="密钥(Base64)"><el-input v-model="encForm.requestKey" placeholder="Base64 编码的密钥" /></el-form-item>
        <el-form-item label="IV 向量(Base64)"><el-input v-model="encForm.requestIv" placeholder="ECB 模式可留空" /></el-form-item>
        <!-- 返参加密配置 -->
        <el-divider content-position="left">返参加密</el-divider>
        <el-form-item label="是否加密"><el-switch v-model="encForm.responseEncrypted" /></el-form-item>
        <el-form-item label="加密类型"><el-select v-model="encForm.responseAlgorithm" style="width:100%"><el-option label="无" value="" /><el-option label="SM4" value="SM4" /><el-option label="AES" value="AES" /></el-select></el-form-item>
        <el-form-item label="加密模式"><el-select v-model="encForm.responseMode" style="width:100%"><el-option label="ECB" value="ECB" /><el-option label="CBC" value="CBC" /><el-option label="CFB" value="CFB" /><el-option label="CTR" value="CTR" /></el-select></el-form-item>
        <el-form-item label="密钥(Base64)"><el-input v-model="encForm.responseKey" placeholder="Base64 编码的密钥" /></el-form-item>
        <el-form-item label="IV 向量(Base64)"><el-input v-model="encForm.responseIv" placeholder="ECB 模式可留空" /></el-form-item>
      </el-form>
      <div slot="footer"><el-button @click="encVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEncryption">保存</el-button></div>
    </el-dialog>
  </div>
</template>
<script>
import {
  getInterfaceList, createInterface, updateInterface, updateInterfaceStatus, deleteInterface,
  getGroupTree, getGroupList, getGroupInterfaces, createGroup, updateGroup, deleteGroup,
  getInterfaceEncryptionConfig, saveInterfaceEncryptionConfig
} from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数 + 接口名称 + 所属分组
      query: { current: 1, size: 10, interfaceName: '', groupId: null },
      // 接口列表数据与总条数
      list: [], total: 0, loading: false, saving: false,
      // 分组树数据（用于左侧树）与级联选择器数据
      groupTree: [], groupCascader: [],
      // 接口编辑对话框状态与表单
      dialogVisible: false, form: {},
      // 分组编辑对话框状态与表单
      groupVisible: false, groupForm: {},
      // 加密配置对话框状态、表单及当前接口信息
      encVisible: false, encForm: {}, encInterfaceName: '', encInterfaceId: null
    }
  },
  mounted() { this.loadGroups(); this.loadData() },
  methods: {
    // 加载分组树，并转换为级联选择器所需结构
    async loadGroups() {
      const res = await getGroupTree()
      this.groupTree = res.data
      this.groupCascader = this.toCascader(res.data)
    },
    // 递归将分组树转换为级联选择器结构（id/groupName/children）
    toCascader(tree) {
      return (tree || []).map(g => ({ id: g.id, groupName: g.groupName, children: g.children ? this.toCascader(g.children) : undefined }))
    },
    // 点击分组节点：按该分组筛选接口列表
    onGroupClick(data) {
      this.query.groupId = data.id
      this.query.current = 1
      this.loadData()
    },
    // 加载接口列表（带分页）
    async loadData() {
      this.loading = true
      try {
        const res = await getInterfaceList(this.query)
        this.list = res.data.records; this.total = res.data.total
      } finally { this.loading = false }
    },
    // 打开接口编辑对话框；新增时提供默认值
    showDialog(row) { this.form = row ? { ...row } : { requestMethod: 'POST', requestParamType: 'JSON', timeoutMs: 5000 }; this.dialogVisible = true },
    // 保存接口：先处理级联分组值为叶子节点，再更新或创建
    async save() {
      if (this.saving) return
      this.saving = true
      try {
        const payload = { ...this.form }
        // 级联选择器返回数组，取最后一级作为分组 id
        if (Array.isArray(payload.groupId)) payload.groupId = payload.groupId[payload.groupId.length - 1]
        if (payload.id) { await updateInterface(payload.id, payload) } else { await createInterface(payload) }
        this.dialogVisible = false; this.loadData(); this.$message.success('保存成功')
      } finally { this.saving = false }
    },
    // 切换接口启用/停用状态
    async toggleStatus(row) { await updateInterfaceStatus(row.id, row.status === 1 ? 0 : 1); this.loadData() },
    // 删除接口
    async remove(row) { await this.$confirm('确认删除?'); await deleteInterface(row.id); this.loadData() },
    // 新增分组：有父分组则预置 parentId
    addGroup(parent) {
      this.groupForm = parent ? { parentId: parent.id } : {}
      this.groupVisible = true
    },
    // 编辑分组
    editGroup(data) { this.groupForm = { ...data }; this.groupVisible = true },
    // 保存分组：处理级联父分组值后更新或创建
    async saveGroup() {
      if (this.saving) return
      this.saving = true
      try {
        const payload = { ...this.groupForm }
        if (Array.isArray(payload.parentId)) payload.parentId = payload.parentId[payload.parentId.length - 1]
        if (payload.id) { await updateGroup(payload.id, payload) } else { await createGroup(payload) }
        this.groupVisible = false; this.loadGroups(); this.$message.success('保存成功')
      } finally { this.saving = false }
    },
    // 删除分组
    async removeGroup(data) {
      await this.$confirm('确认删除该分组?')
      await deleteGroup(data.id); this.loadGroups(); this.loadData()
    },
    // 打开加密配置对话框，并加载已有配置
    async showEncryption(row) {
      this.encInterfaceId = row.id
      this.encInterfaceName = row.interfaceName
      const res = await getInterfaceEncryptionConfig(row.id)
      this.encForm = res.data || { interfaceId: row.id }
      this.encVisible = true
    },
    // 保存加密配置
    async saveEncryption() {
      if (this.saving) return
      this.saving = true
      try {
        await saveInterfaceEncryptionConfig({ ...this.encForm, interfaceId: this.encInterfaceId })
        this.encVisible = false; this.$message.success('加密配置已保存')
      } finally { this.saving = false }
    }
  }
}
</script>
<style scoped>
.iface-layout { display: flex; gap: 16px; align-items: flex-start; }
.group-card { width: 260px; flex-shrink: 0; }
.list-card { flex: 1; min-width: 0; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.tree-node { flex: 1; display: flex; justify-content: space-between; align-items: center; }
.tree-actions { display: none; }
.tree-node:hover .tree-actions { display: inline; }
.danger { color: #f56c6c; }
.pagination { margin-top: 16px; text-align: right; }
</style>
