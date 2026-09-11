<template>
  <div class="page-container sys-dict">
    <div class="page-head">
      <h2>字典管理</h2>
      <span class="page-tag">字典头与字典项分开维护，变更独立生效</span>
    </div>

    <el-row :gutter="12">
      <el-col :span="9">
        <el-card shadow="never" class="dict-card">
          <div class="card-toolbar">
            <el-input
              v-model="dictQuery.keyword"
              placeholder="搜索字典编码 / 名称"
              size="small"
              clearable
              style="width:200px"
              @keyup.enter.native="reloadDict"
              @clear="reloadDict"
            />
            <span class="spacer" />
            <PermButton perm="sys:dict:update" type="primary" size="mini" icon="el-icon-plus" @click="onCreateDict">新建字典</PermButton>
          </div>

          <el-table
            v-loading="dictLoading"
            :data="dictList"
            :row-key="(row) => row['dictCode']"
            :highlight-current-row="true"
            :height="'calc(100vh - 280px)'"
            size="small"
            :border="true"
            :stripe="true"
            @row-click="onSelectDict"
          >
            <el-table-column prop="dictCode" label="编码" min-width="100" show-overflow-tooltip />
            <el-table-column prop="dictName" label="名称" min-width="100" show-overflow-tooltip />
            <el-table-column label="状态" width="68">
              <template slot-scope="{row}">
                <StatusTag :entity="'dict'" :value="row.status" />
              </template>
            </el-table-column>
            <el-table-column prop="itemCount" label="项数" width="60" align="right" />
            <el-table-column label="操作" width="120">
              <template slot-scope="{row}">
                <PermButton perm="sys:dict:update" type="text" size="mini" @click.stop="onEditDict(row)">编辑</PermButton>
                <PermButton
                  perm="sys:dict:update"
                  type="text"
                  size="mini"
                  :disabled="row.builtIn === 1"
                  @click.stop="onDeleteDict(row)"
                >删除</PermButton>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState title="暂无字典" />
            </template>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" class="dict-card">
          <div class="card-toolbar">
            <span class="title">
              <template v-if="selectedDict">
                字典项 · {{ selectedDict.dictCode }} <small>({{ selectedDict.dictName }})</small>
              </template>
              <template v-else>字典项</template>
            </span>
            <span class="spacer" />
            <PermButton
              perm="sys:dict:update"
              type="primary"
              size="mini"
              icon="el-icon-plus"
              :disabled="!selectedDict"
              @click="onCreateItem"
            >新建字典项</PermButton>
          </div>

          <el-table
            v-loading="itemLoading"
            :data="itemList"
            :row-key="(row) => row.id"
            :height="'calc(100vh - 280px)'"
            size="small"
            :border="true"
            :stripe="true"
          >
            <el-table-column prop="itemValue" label="项值" min-width="120" show-overflow-tooltip />
            <el-table-column prop="itemLabel" label="项标签" min-width="140" show-overflow-tooltip />
            <el-table-column prop="sortOrder" label="排序" width="70" align="right" />
            <el-table-column label="状态" width="68">
              <template slot-scope="{row}">
                <StatusTag :entity="'dict'" :value="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template slot-scope="{row}">
                <PermButton perm="sys:dict:update" type="text" size="mini" @click="onEditItem(row)">编辑</PermButton>
                <PermButton perm="sys:dict:update" type="text" size="mini" @click="onDeleteItem(row)">删除</PermButton>
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState :title="selectedDict ? '该字典暂无项，点击右上角新建' : '请先选择左侧字典'" />
            </template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 字典头 dialog -->
    <CrudDialog
      :visible.sync="dictDialog.visible"
      :title="dictDialog.form.id ? '编辑字典' : '新建字典'"
      :model="dictDialog.form"
      :fields="dictDialog.fields"
      :rules="dictDialog.rules"
      :width="'520px'"
      :loading="dictDialog.loading"
      @submit="onSubmitDict"
    />

    <!-- 字典项 dialog -->
    <CrudDialog
      :visible.sync="itemDialog.visible"
      :title="itemDialog.form.id ? '编辑字典项' : '新建字典项'"
      :model="itemDialog.form"
      :fields="itemDialog.fields"
      :rules="itemDialog.rules"
      :width="'480px'"
      :loading="itemDialog.loading"
      @submit="onSubmitItem"
    />
  </div>
</template>

<script>
import {
  getDictList,
  createDict,
  updateDict,
  deleteDict,
  getDictItems,
  createDictItem,
  updateDictItem,
  deleteDictItem
} from '@/api/modules'

export default {
  name: 'SysDict',
  data() {
    return {
      dictQuery: { keyword: '' },
      dictLoading: false,
      dictList: [],
      selectedDict: null,
      itemLoading: false,
      itemList: [],
      dictDialog: {
        visible: false,
        loading: false,
        form: {},
        fields: [],
        rules: {}
      },
      itemDialog: {
        visible: false,
        loading: false,
        form: {},
        fields: [],
        rules: {}
      }
    }
  },
  mounted() {
    this.loadDictList()
  },
  methods: {
    async loadDictList() {
      this.dictLoading = true
      try {
        const res = await getDictList({ pageNum: 1, pageSize: 200, keyword: this.dictQuery.keyword || undefined })
        const data = (res && res.data) || {}
        this.dictList = data.records || []
        if (this.dictList.length && !this.selectedDict) {
          this.onSelectDict(this.dictList[0])
        } else if (!this.dictList.length) {
          this.selectedDict = null
          this.itemList = []
        }
      } catch (e) {
        this.dictList = []
      } finally {
        this.dictLoading = false
      }
    },
    reloadDict() {
      this.loadDictList()
    },
    async onSelectDict(row) {
      if (!row) return
      this.selectedDict = row
      await this.loadItems(row.dictCode)
    },
    async loadItems(dictCode) {
      this.itemLoading = true
      try {
        const res = await getDictItems(dictCode)
        this.itemList = (res && res.data) || []
      } catch (e) {
        this.itemList = []
      } finally {
        this.itemLoading = false
      }
    },
    dictFields() {
      return [
        { prop: 'dictCode', label: '字典编码', type: 'input', required: true, placeholder: '请输入字典编码（英文）', span: 12 },
        { prop: 'dictName', label: '字典名称', type: 'input', required: true, placeholder: '请输入字典名称', span: 12 },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 },
        { prop: 'remark', label: '备注', type: 'textarea', span: 24, rows: 2 }
      ]
    },
    itemFields() {
      return [
        { prop: 'itemValue', label: '项值', type: 'input', required: true, placeholder: '请输入项值', span: 12 },
        { prop: 'itemLabel', label: '项标签', type: 'input', required: true, placeholder: '请输入项标签', span: 12 },
        { prop: 'sortOrder', label: '排序', type: 'number', span: 12, min: 0, step: 1, placeholder: '数字越小越靠前' },
        { prop: 'status', label: '状态', type: 'switch', span: 12, activeValue: 1, inactiveValue: 0 }
      ]
    },
    onCreateDict() {
      this.dictDialog.form = { dictCode: '', dictName: '', status: 1, remark: '' }
      this.dictDialog.fields = this.dictFields()
      this.dictDialog.rules = {
        dictCode: [{ required: true, message: '字典编码不能为空', trigger: 'blur' }],
        dictName: [{ required: true, message: '字典名称不能为空', trigger: 'blur' }]
      }
      this.dictDialog.visible = true
    },
    onEditDict(row) {
      this.dictDialog.form = { ...row }
      this.dictDialog.fields = this.dictFields()
      this.dictDialog.rules = {}
      this.dictDialog.visible = true
    },
    async onSubmitDict(form) {
      this.dictDialog.loading = true
      try {
        if (form.id) {
          await updateDict(form)
          this.$message.success('字典已更新')
        } else {
          await createDict(form)
          this.$message.success('字典已创建')
        }
        this.dictDialog.visible = false
        this.loadDictList()
      } catch (e) {} finally {
        this.dictDialog.loading = false
      }
    },
    onDeleteDict(row) {
      this.$confirm(`确认删除字典「${row.dictName}」？已有字典项将一并失效。`, '删除确认', {
        type: 'warning'
      }).then(async() => {
        try {
          await deleteDict(row.id)
          this.$message.success('已删除')
          if (this.selectedDict && this.selectedDict.id === row.id) {
            this.selectedDict = null
            this.itemList = []
          }
          this.loadDictList()
        } catch (e) {}
      }).catch(() => {})
    },
    onCreateItem() {
      if (!this.selectedDict) return
      this.itemDialog.form = {
        dictCode: this.selectedDict.dictCode,
        itemValue: '',
        itemLabel: '',
        sortOrder: 0,
        status: 1
      }
      this.itemDialog.fields = this.itemFields()
      this.itemDialog.rules = {
        itemValue: [{ required: true, message: '项值不能为空', trigger: 'blur' }],
        itemLabel: [{ required: true, message: '项标签不能为空', trigger: 'blur' }]
      }
      this.itemDialog.visible = true
    },
    onEditItem(row) {
      this.itemDialog.form = { ...row }
      this.itemDialog.fields = this.itemFields()
      this.itemDialog.rules = {}
      this.itemDialog.visible = true
    },
    async onSubmitItem(form) {
      this.itemDialog.loading = true
      try {
        if (form.id) {
          await updateDictItem(form.id, form)
          this.$message.success('字典项已更新')
        } else {
          await createDictItem(form.dictCode, form)
          this.$message.success('字典项已创建')
        }
        this.itemDialog.visible = false
        if (this.selectedDict) await this.loadItems(this.selectedDict.dictCode)
        this.loadDictList()
      } catch (e) {} finally {
        this.itemDialog.loading = false
      }
    },
    onDeleteItem(row) {
      this.$confirm(`确认删除字典项「${row.itemLabel}」？`, '删除确认', { type: 'warning' })
        .then(async() => {
          try {
            await deleteDictItem(row.id)
            this.$message.success('已删除')
            if (this.selectedDict) await this.loadItems(this.selectedDict.dictCode)
          } catch (e) {}
        }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.sys-dict .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.sys-dict .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.sys-dict .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.sys-dict .dict-card { height: calc(100vh - 160px); display: flex; flex-direction: column; }
.sys-dict .dict-card >>> .el-card__body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.sys-dict .card-toolbar { display: flex; align-items: center; gap: 8px; padding-bottom: 12px; }
.sys-dict .card-toolbar .title { font-weight: 600; color: #17233d; }
.sys-dict .card-toolbar .title small { color: #9aa7bf; font-weight: 400; margin-left: 4px; }
.sys-dict .card-toolbar .spacer { flex: 1; }
.sys-dict .el-table { flex: 1; min-height: 0; }
</style>