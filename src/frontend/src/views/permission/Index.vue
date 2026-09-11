<template>
  <div class="page-container">
    <el-card>
      <!-- 搜索区：按应用筛选授权记录 + 授权操作 -->
      <div class="toolbar">
        <el-select v-model="query.appId" placeholder="全部应用" clearable filterable style="width:200px">
          <el-option v-for="a in appOptions" :key="a.id" :label="a.appName" :value="a.id" />
        </el-select>
        <el-button type="primary" @click="loadData">查询</el-button>
        <div class="spacer"></div>
        <el-button type="primary" @click="showGrant">＋ 授权操作</el-button>
      </div>
      <!-- 授权记录表格（应用-接口授权矩阵） -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="暂无授权记录，点击右上角「授权操作」为应用开放接口" /></template>
        <el-table-column prop="appId" label="应用ID" width="90" />
        <el-table-column prop="interfaceId" label="接口ID" width="90" />
        <el-table-column label="状态" width="90"><template slot-scope="{row}"><el-tag type="success">已授权</el-tag></template></el-table-column>
        <el-table-column prop="createdAt" label="授权时间" width="180" />
        <el-table-column label="操作" width="120"><template slot-scope="{row}"><el-button size="mini" type="danger" @click="revoke(row)">取消授权</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>

    <!-- 授权对话框：选择应用 + 分组树（可展开查看接口）+ 勾选授权 -->
    <el-dialog title="授权" :visible.sync="grantVisible" width="560px">
      <el-form label-width="80px">
        <el-form-item label="应用">
          <el-select v-model="grantForm.appId" placeholder="选择应用" filterable style="width:100%">
            <el-option v-for="a in appOptions" :key="a.id" :label="a.appName" :value="a.id" />
          </el-select>
        </el-form-item>
        <!-- 授权树：分组节点整组授权，接口节点单个授权 -->
        <el-form-item label="分组/接口">
          <div class="auth-tree-wrap">
            <el-tree
              ref="authTree"
              :data="authTree"
              :props="{ label: 'label', children: 'children' }"
              node-key="id"
              show-checkbox
              check-strictly
              default-expand-all>
              <!-- 自定义树节点：分组显示文件夹图标，接口显示链接图标 -->
              <span slot-scope="{ node, data }">
                <i v-if="data.type==='group'" class="el-icon-folder-opened" style="color:#409EFF;margin-right:4px"></i>
                <i v-else class="el-icon-link" style="color:#909399;margin-right:4px"></i>
                <span>{{ data.label }}</span>
              </span>
            </el-tree>
          </div>
        </el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="grantVisible=false">取消</el-button>
        <el-button type="primary" :loading="granting" @click="doGrant">授权（分组整组授权 / 接口单个授权）</el-button>
      </div>
    </el-dialog>
  </div>
</template>
<script>
import {
  getPermissionList, grantPermission, grantByGroup, revokePermission,
  getGroupTree, getGroupInterfaces, getAppList
} from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数 + 应用 ID 筛选
      query: { current: 1, size: 10, appId: null },
      // 授权记录列表与总条数
      list: [], total: 0, loading: false, granting: false,
      // 应用下拉选项
      appOptions: [],
      // 授权对话框显示状态
      grantVisible: false,
      // 授权表单：目标应用
      grantForm: { appId: null },
      // 授权树数据（分组 + 接口混合树）
      authTree: []
    }
  },
  mounted() { this.loadApps(); this.loadData() },
  methods: {
    // 加载应用列表作为下拉选项
    async loadApps() { const res = await getAppList({ current: 1, size: 200 }); this.appOptions = res.data.records },
    // 加载授权记录列表
    async loadData() {
      this.loading = true
      try {
        const res = await getPermissionList({ appId: this.query.appId })
        this.list = res.data; this.total = res.data.length
      } finally { this.loading = false }
    },
    // 打开授权对话框并加载授权树
    async showGrant() {
      this.grantForm = { appId: null }
      this.grantVisible = true
      await this.loadAuthTree()
    },
    // 加载分组树并构建授权树
    async loadAuthTree() {
      const res = await getGroupTree()
      const tree = res.data || []
      this.authTree = await this.buildAuthTree(tree)
    },
    // 递归构建授权树：每个分组下挂载子分组与所属接口节点
    async buildAuthTree(groups) {
      const result = []
      for (const g of groups) {
        // 获取当前分组下的接口列表
        const ifaces = await getGroupInterfaces(g.id)
        const children = []
        // 递归处理子分组
        if (g.children && g.children.length) {
          children.push(...await this.buildAuthTree(g.children))
        }
        // 将接口转换为接口类型节点（id 加前缀 i 避免与分组冲突）
        children.push(...(ifaces.data || []).map(i => ({
          id: 'i' + i.id,
          label: i.interfaceName + '  (' + i.interfacePath + ')',
          type: 'interface',
          interfaceId: i.id
        })))
        // 分组节点（id 加前缀 g 避免与接口冲突）
        result.push({ id: 'g' + g.id, label: g.groupName, type: 'group', groupId: g.id, children })
      }
      return result
    },
    // 执行授权：分组走整组授权，接口走单个授权
    async doGrant() {
      if (this.granting) return
      if (!this.grantForm.appId) { this.$message.warning('请选择应用'); return }
      // 获取勾选的节点
      const nodes = this.$refs.authTree.getCheckedNodes()
      // 按节点类型拆分分组与接口
      const groups = nodes.filter(n => n.type === 'group')
      const ifaces = nodes.filter(n => n.type === 'interface')
      if (!groups.length && !ifaces.length) { this.$message.warning('请勾选分组或接口'); return }
      this.granting = true
      try {
        // 分组整组授权
        for (const g of groups) { await grantByGroup({ appId: this.grantForm.appId, groupId: g.groupId }) }
        // 接口单个授权
        for (const i of ifaces) { await grantPermission({ appId: this.grantForm.appId, interfaceId: i.interfaceId }) }
        this.grantVisible = false
        this.loadData()
        this.$message.success('授权成功')
      } finally { this.granting = false }
    },
    // 取消授权
    async revoke(row) { await this.$confirm('确认取消授权?'); await revokePermission(row.appId, row.interfaceId); this.loadData() }
  }
}
</script>
<style scoped>
.pagination { margin-top: 16px; text-align: right; }
.auth-tree-wrap { max-height: 360px; overflow: auto; border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px; }
</style>
