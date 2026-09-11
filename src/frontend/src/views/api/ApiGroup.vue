<template>
  <div class="page-container">
    <div class="iface-layout">
      <!-- 左：分组树 -->
      <el-card class="group-card" shadow="never" :body-style="{ padding: '10px' }">
        <div class="group-card-head">
          <span class="gc-title">分组树</span>
          <el-button type="text" icon="el-icon-refresh" @click="loadTree">刷新</el-button>
        </div>
        <el-tree
          ref="tree"
          class="group-tree"
          :data="treeData"
          :props="{ label: 'groupName', children: 'children' }"
          node-key="id"
          highlight-current
          default-expand-all
          :expand-on-click-node="false"
          @node-click="onNodeClick"
        >
          <span slot-scope="{ node, data }" class="gt-node">
            <span class="gt-label">{{ node.label }}</span>
            <span class="gt-actions">
              <i class="el-icon-plus" title="在此分组下新建" @click.stop="openCreate(data)" />
            </span>
          </span>
        </el-tree>
        <div class="gc-all">
          <el-button type="text" icon="el-icon-menu" @click="onNodeClick(null)">全部分组</el-button>
        </div>
      </el-card>

      <!-- 右：分组列表 -->
      <div class="group-main">
        <CrudTable
          ref="table"
          :columns="columns"
          :fetch="fetchGroups"
          :query="query"
          row-key="id"
          :show-index="true"
          :page-size="20"
          @loaded="onLoaded"
        >
          <template #toolbar>
            <el-input
              v-model="query.kw"
              placeholder="搜索分组名称"
              clearable
              style="width: 200px"
              @keyup.enter.native="reload"
              @clear="reload"
            />
            <span class="spacer" />
            <PermButton perm="api_group:create" type="primary" icon="el-icon-plus" @click="openCreate(null)">新建分组</PermButton>
          </template>
          <template #parentName="{ row }">
            <span>{{ row.parentId ? (parentNameMap[row.parentId] || '—') : '顶级分组' }}</span>
          </template>
          <template #actions="{ row }">
            <PermButton perm="api_group:update" type="text" @click="openEdit(row)">编辑</PermButton>
            <PermButton perm="api_group:delete" type="text" class="danger-link" @click="remove(row)">删除</PermButton>
          </template>
        </CrudTable>
      </div>
    </div>

    <CrudDialog
      :visible.sync="dialogVisible"
      :title="dialogTitle"
      :model="form"
      :fields="fields"
      :loading="submitting"
      width="520px"
      @submit="submit"
    />
  </div>
</template>

<script>
/**
 * 接口分组管理页（T05 Phase 1 · 接口管理）
 * 左侧分组树（/group/tree）+ 右侧分组列表（/group/list），支持新建/编辑/删除。
 * 注意：后端 ApiGroup 实体字段为 groupName/parentId/description/sortOrder，
 *       与原型计划的 groupCode/lineId/ownerName 等字段不同，表单以真实后端字段为准。
 */
import { getGroupList, getGroupTree, createGroup, updateGroup, deleteGroup } from '@/api/modules'

export default {
  name: 'ApiGroup',
  data() {
    return {
      treeData: [],
      parentNameMap: {},
      flatGroups: [],
      query: { kw: '', parentId: null },
      columns: [
        { prop: 'groupName', label: '分组名称', minWidth: 160 },
        { prop: 'parentId', label: '父分组', minWidth: 140, slot: 'parentName' },
        { prop: 'description', label: '描述', minWidth: 200, showOverflowTooltip: true },
        { prop: 'sortOrder', label: '排序', width: 80, align: 'center' },
        { prop: 'createdAt', label: '创建时间', width: 160, formatter: (v) => this.fmtTime(v) }
      ],
      dialogVisible: false,
      dialogTitle: '新建分组',
      submitting: false,
      form: {},
      fields: [
        { prop: 'groupName', label: '分组名称', type: 'input', required: true, maxlength: 50, span: 24 },
        { prop: 'parentId', label: '父分组', type: 'select', options: [], span: 24, placeholder: '不选则为顶级分组' },
        { prop: 'sortOrder', label: '排序', type: 'number', min: 0, max: 9999, span: 12 },
        { prop: 'description', label: '描述', type: 'textarea', span: 24, maxlength: 200 }
      ]
    }
  },
  mounted() {
    this.loadTree()
  },
  methods: {
    fmtTime(v) {
      if (!v) return '—'
      return String(v).replace('T', ' ')
    },
    async loadTree() {
      try {
        const [treeRes, listRes] = await Promise.all([getGroupTree(), getGroupList()])
        this.treeData = treeRes.data || []
        this.flatGroups = listRes.data || []
        this.parentNameMap = {}
        this.flatGroups.forEach((g) => { this.parentNameMap[g.id] = g.groupName })
      } catch (e) { /* 拦截器已提示 */ }
    },
    onNodeClick(node) {
      this.query.parentId = node && node.id != null ? node.id : null
      this.reload()
    },
    reload() {
      if (this.$refs.table) this.$refs.table.reload()
    },
    onLoaded() {},
    async fetchGroups(params) {
      let list = this.flatGroups.length ? this.flatGroups.slice() : (await getGroupList()).data || []
      this.flatGroups = list
      this.parentNameMap = {}
      list.forEach((g) => { this.parentNameMap[g.id] = g.groupName })
      if (this.query.kw) list = list.filter((g) => (g.groupName || '').includes(this.query.kw))
      if (this.query.parentId != null) list = list.filter((g) => g.parentId === this.query.parentId)
      list = list.slice().sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
      const total = list.length
      const start = (params.page - 1) * params.size
      return { list: list.slice(start, start + params.size), total }
    },
    buildParentOptions(excludeId) {
      const opts = [{ value: 0, label: '顶级分组' }]
      this.flatGroups.forEach((g) => {
        if (excludeId != null && g.id === excludeId) return
        opts.push({ value: g.id, label: g.groupName })
      })
      return opts
    },
    openCreate(parent) {
      this.dialogTitle = '新建分组'
      this.form = {
        groupName: '',
        parentId: parent && parent.id ? parent.id : 0,
        sortOrder: 0,
        description: ''
      }
      this.fields = this.fields.map((f) => f.prop === 'parentId' ? { ...f, options: this.buildParentOptions(null) } : f)
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogTitle = '编辑分组'
      this.form = { ...row, parentId: row.parentId || 0 }
      this.fields = this.fields.map((f) => f.prop === 'parentId' ? { ...f, options: this.buildParentOptions(row.id) } : f)
      this.dialogVisible = true
    },
    async submit(form) {
      this.submitting = true
      try {
        const payload = { ...form }
        if (payload.parentId === 0) payload.parentId = null
        if (payload.id) {
          await updateGroup(payload.id, payload)
          this.$message.success('分组已更新')
        } else {
          await createGroup(payload)
          this.$message.success('分组已创建')
        }
        this.dialogVisible = false
        await this.loadTree()
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally {
        this.submitting = false
      }
    },
    remove(row) {
      this.$confirm(`确认删除分组「${row.groupName}」？删除后其子分组将变为顶级分组。`, '删除确认', { type: 'warning' }).then(async () => {
        try {
          await deleteGroup(row.id)
          this.$message.success('已删除')
          await this.loadTree()
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.iface-layout { display: flex; gap: 16px; align-items: flex-start; }
.group-card { width: 280px; flex: none; }
.group-card-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.gc-title { font-size: 14px; font-weight: 600; color: #17233d; }
.group-tree { max-height: 60vh; overflow: auto; }
.gt-node { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.gt-actions { display: none; }
.gt-node:hover .gt-actions { display: inline-flex; }
.gt-actions i { color: #2563eb; padding: 0 4px; }
.gc-all { margin-top: 8px; border-top: 1px solid #eef1f7; padding-top: 8px; }
.group-main { flex: 1; min-width: 0; }
.danger-link { color: #c03337; }
.danger-link:hover { color: #e05559; }
</style>
