<template>
  <div class="page-container perm-datascope">
    <div class="page-head">
      <h2>数据权限</h2>
      <span class="page-tag">按角色配置数据范围（业务线 / 环境 / 接口分组）</span>
    </div>

    <el-row :gutter="12">
      <el-col :span="9">
        <el-card shadow="never" class="role-card">
          <div class="card-toolbar">
            <el-input
              v-model="roleQuery.keyword"
              placeholder="搜索角色"
              size="small"
              clearable
              style="width:200px"
              @keyup.enter.native="loadRoles"
              @clear="loadRoles"
            />
            <span class="spacer" />
            <el-button size="mini" icon="el-icon-refresh" @click="loadRoles">刷新</el-button>
          </div>
          <el-table
            v-loading="roleLoading"
            :data="roleList"
            :row-key="(row) => row.roleId"
            :highlight-current-row="true"
            :height="'calc(100vh - 280px)'"
            size="small"
            :border="true"
            :stripe="true"
            @row-click="onSelectRole"
          >
            <el-table-column prop="roleName" label="角色名称" min-width="120" show-overflow-tooltip />
            <el-table-column prop="roleCode" label="角色编码" min-width="100" show-overflow-tooltip />
            <el-table-column label="范围" width="80">
              <template slot-scope="{row}">
                <StatusTag :entity="'dataScope'" :value="row.dataScope" />
              </template>
            </el-table-column>
            <template #empty>
              <EmptyState title="暂无角色" />
            </template>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" class="scope-card">
          <template v-if="!selectedRole">
            <el-empty description="请在左侧选择角色以配置数据范围" />
          </template>
          <template v-else>
            <div class="card-toolbar">
              <span class="title">
                数据范围 · {{ selectedRole.roleName }}
                <small>({{ selectedRole.roleCode }})</small>
                <el-tag size="mini" effect="plain" type="info" style="margin-left:8px">当前默认范围：{{ selectedRole.dataScope || '—' }}</el-tag>
              </span>
              <span class="spacer" />
              <PermButton
                perm="sys:datascope:update"
                type="primary"
                size="mini"
                icon="el-icon-check"
                :loading="saving"
                :disabled="saving"
                @click="onSave"
              >保存（高危）</PermButton>
            </div>

            <el-alert
              type="warning"
              :closable="false"
              show-icon
              style="margin-bottom: 12px"
            >
              <template #title>
                <strong>全量覆盖语义：</strong>保存即删除该角色旧范围，再写入新勾选项；全部不勾选 = 不限范围。
              </template>
            </el-alert>

            <div class="scope-section">
              <div class="scope-section-head">
                <span class="scope-section-title">业务线</span>
                <span class="scope-section-tip">已选 {{ counts.bizLines }} 个</span>
                <span class="spacer" />
                <el-button size="mini" :disabled="!options.bizLines || !options.bizLines.length" @click="toggleAll('bizLines')">
                  {{ isAllChecked('bizLines') ? '全不选' : '全选' }}
                </el-button>
              </div>
              <el-checkbox-group v-model="form.bizLines" class="scope-options">
                <el-checkbox
                  v-for="o in (options.bizLines || [])"
                  :key="'bl-' + o.id"
                  :label="String(o.id)"
                  border
                >{{ o.lineName || o.lineCode || o.id }}</el-checkbox>
              </el-checkbox-group>
            </div>

            <div class="scope-section">
              <div class="scope-section-head">
                <span class="scope-section-title">环境</span>
                <span class="scope-section-tip">已选 {{ counts.envs }} 个</span>
                <span class="spacer" />
                <el-button size="mini" :disabled="!options.envs || !options.envs.length" @click="toggleAll('envs')">
                  {{ isAllChecked('envs') ? '全不选' : '全选' }}
                </el-button>
              </div>
              <el-checkbox-group v-model="form.envs" class="scope-options">
                <el-checkbox
                  v-for="o in (options.envs || [])"
                  :key="'env-' + o.envCode"
                  :label="o.envCode"
                  border
                >{{ o.envName }} ({{ o.envCode }})</el-checkbox>
              </el-checkbox-group>
            </div>

            <div class="scope-section">
              <div class="scope-section-head">
                <span class="scope-section-title">接口分组</span>
                <span class="scope-section-tip">已选 {{ counts.apiGroups }} 个</span>
                <span class="spacer" />
                <el-button size="mini" :disabled="!options.apiGroups || !options.apiGroups.length" @click="toggleAll('apiGroups')">
                  {{ isAllChecked('apiGroups') ? '全不选' : '全选' }}
                </el-button>
              </div>
              <el-checkbox-group v-model="form.apiGroups" class="scope-options">
                <el-checkbox
                  v-for="o in (options.apiGroups || [])"
                  :key="'ag-' + o.id"
                  :label="String(o.id)"
                  border
                >{{ o.groupName }} ({{ o.groupCode || o.id }})</el-checkbox>
              </el-checkbox-group>
            </div>

            <el-divider content-position="left">效果预览</el-divider>
            <div class="preview">
              <div class="preview-row">
                <span class="preview-label">可见业务线：</span>
                <el-tag v-for="v in preview.bizLines" :key="'pb-' + v" size="small" type="warning" effect="plain" style="margin-right:6px">{{ v }}</el-tag>
                <span v-if="!preview.bizLines.length" class="muted">未限制</span>
              </div>
              <div class="preview-row">
                <span class="preview-label">可见环境：</span>
                <el-tag v-for="v in preview.envs" :key="'pe-' + v" size="small" type="primary" effect="plain" style="margin-right:6px">{{ v }}</el-tag>
                <span v-if="!preview.envs.length" class="muted">未限制</span>
              </div>
              <div class="preview-row">
                <span class="preview-label">可见接口分组：</span>
                <el-tag v-for="v in preview.apiGroups" :key="'pg-' + v" size="small" type="success" effect="plain" style="margin-right:6px">{{ v }}</el-tag>
                <span v-if="!preview.apiGroups.length" class="muted">未限制</span>
              </div>
            </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import {
  getDataScopeRoles,
  getDataScopeOptions,
  getDataScopeByRole,
  saveDataScope
} from '@/api/modules'

export default {
  name: 'PermDatascope',
  data() {
    return {
      roleQuery: { keyword: '' },
      roleLoading: false,
      roleList: [],
      selectedRole: null,
      options: { bizLines: [], envs: [], apiGroups: [] },
      form: { bizLines: [], envs: [], apiGroups: [] },
      saving: false
    }
  },
  computed: {
    counts() {
      return {
        bizLines: this.form.bizLines.length,
        envs: this.form.envs.length,
        apiGroups: this.form.apiGroups.length
      }
    },
    preview() {
      const lookup = (list, arr) => arr.map((v) => {
        const it = (list || []).find((x) => String(x.id === undefined ? x.envCode : x.id) === String(v) || x.envCode === v)
        return it ? (it.lineName || it.envName || it.groupName || v) : v
      })
      return {
        bizLines: lookup(this.options.bizLines, this.form.bizLines),
        envs: lookup(this.options.envs, this.form.envs),
        apiGroups: lookup(this.options.apiGroups, this.form.apiGroups)
      }
    }
  },
  async mounted() {
    await Promise.all([this.loadOptions(), this.loadRoles()])
    if (this.roleList.length) this.onSelectRole(this.roleList[0])
  },
  methods: {
    async loadRoles() {
      this.roleLoading = true
      try {
        const res = await getDataScopeRoles({ keyword: this.roleQuery.keyword || undefined })
        this.roleList = (res && res.data) || []
      } catch (e) {
        this.roleList = []
      } finally {
        this.roleLoading = false
      }
    },
    async loadOptions() {
      try {
        const res = await getDataScopeOptions()
        const data = (res && res.data) || {}
        this.options = {
          bizLines: data.bizLines || [],
          envs: data.envs || [],
          apiGroups: data.apiGroups || []
        }
      } catch (e) {
        this.options = { bizLines: [], envs: [], apiGroups: [] }
      }
    },
    async onSelectRole(row) {
      if (!row) return
      this.selectedRole = row
      this.form.bizLines = []
      this.form.envs = []
      this.form.apiGroups = []
      try {
        const res = await getDataScopeByRole(row.roleId)
        const list = (res && res.data) || []
        list.forEach((s) => {
          if (s.scopeType === 'BIZ_LINE') this.form.bizLines.push(String(s.scopeValue))
          else if (s.scopeType === 'ENV') this.form.envs.push(s.scopeValue)
          else if (s.scopeType === 'API_GROUP') this.form.apiGroups.push(String(s.scopeValue))
        })
      } catch (e) {
        // empty = unlimited
      }
    },
    isAllChecked(key) {
      const all = this.options[key === 'bizLines' ? 'bizLines' : key === 'envs' ? 'envs' : 'apiGroups'] || []
      if (!all.length) return false
      return this.form[key].length >= all.length
    },
    toggleAll(key) {
      const all = this.options[key === 'bizLines' ? 'bizLines' : key === 'envs' ? 'envs' : 'apiGroups'] || []
      if (!all.length) return
      if (this.isAllChecked(key)) {
        this.form[key] = []
      } else {
        this.form[key] = all.map((o) => String(o.envCode !== undefined ? o.envCode : o.id))
      }
    },
    async onSave() {
      if (!this.selectedRole) return
      const roleName = this.selectedRole.roleName
      const total = this.counts.bizLines + this.counts.envs + this.counts.apiGroups
      const msg = total === 0
        ? `确认将「${roleName}」的数据范围清空（不限）？`
        : `确认覆盖「${roleName}」的数据范围（业务线 ${this.counts.bizLines} / 环境 ${this.counts.envs} / 分组 ${this.counts.apiGroups}）？该操作为高危全量覆盖，旧范围将丢失。`
      try {
        await this.$confirm(msg, '保存确认（高危）', { type: 'warning', confirmButtonText: '确认保存' })
      } catch (e) { return }
      const scopes = []
      this.form.bizLines.forEach((v) => scopes.push({ scopeType: 'BIZ_LINE', scopeValue: String(v) }))
      this.form.envs.forEach((v) => scopes.push({ scopeType: 'ENV', scopeValue: String(v) }))
      this.form.apiGroups.forEach((v) => scopes.push({ scopeType: 'API_GROUP', scopeValue: String(v) }))
      this.saving = true
      try {
        await saveDataScope(this.selectedRole.roleId, { roleId: this.selectedRole.roleId, scopes })
        this.$message.success('保存成功')
        await this.onSelectRole(this.selectedRole)
      } catch (e) {} finally {
        this.saving = false
      }
    }
  }
}
</script>

<style scoped>
.perm-datascope .page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.perm-datascope .page-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.perm-datascope .page-tag { font-size: 12px; color: #5c6b8a; background: #f0f3f9; padding: 2px 8px; border-radius: 6px; }
.perm-datascope .role-card,
.perm-datascope .scope-card { height: calc(100vh - 160px); display: flex; flex-direction: column; }
.perm-datascope .role-card >>> .el-card__body,
.perm-datascope .scope-card >>> .el-card__body { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.perm-datascope .card-toolbar { display: flex; align-items: center; gap: 8px; padding-bottom: 12px; }
.perm-datascope .card-toolbar .title { font-weight: 600; color: #17233d; }
.perm-datascope .card-toolbar .title small { color: #9aa7bf; font-weight: 400; margin-left: 4px; }
.perm-datascope .card-toolbar .spacer { flex: 1; }
.perm-datascope .el-table { flex: 1; min-height: 0; }
.perm-datascope .scope-section { margin-bottom: 18px; }
.perm-datascope .scope-section-head { display: flex; align-items: center; gap: 8px; padding: 4px 0 8px; }
.perm-datascope .scope-section-title { font-weight: 600; color: #17233d; }
.perm-datascope .scope-section-tip { color: #9aa7bf; font-size: 12px; }
.perm-datascope .scope-section-head .spacer { flex: 1; }
.perm-datascope .scope-options { display: flex; flex-wrap: wrap; gap: 8px; }
.perm-datascope .scope-options >>> .el-checkbox { margin-right: 8px; margin-bottom: 0; }
.perm-datascope .preview { background: #f7f9fc; border-radius: 6px; padding: 12px 16px; }
.perm-datascope .preview-row { display: flex; align-items: flex-start; gap: 8px; margin-bottom: 8px; }
.perm-datascope .preview-row:last-child { margin-bottom: 0; }
.perm-datascope .preview-label { color: #5c6b8a; min-width: 100px; }
.perm-datascope .muted { color: #9aa7bf; font-size: 12px; }
</style>