<template>
  <div class="page-container">
    <el-card>
      <!-- 操作区：右侧新增规则入口 -->
      <div class="toolbar">
        <div class="spacer"></div>
        <el-button type="primary" @click="showAdd">＋ 新增规则</el-button>
      </div>
      <!-- 安全检测规则列表 -->
      <el-table :data="list" border v-loading="loading">
        <template slot="empty"><EmptyState description="暂无安全检测规则，点击右上角「新增规则」开始" /></template>
        <el-table-column prop="ruleName" label="规则名称" min-width="140" show-overflow-tooltip />
        <!-- 规则类型：用不同颜色标签区分 -->
        <el-table-column prop="ruleType" label="类型" width="120">
          <template slot-scope="{row}">
            <el-tag :type="typeMeta[row.ruleType] ? typeMeta[row.ruleType].type : 'info'">
              {{ typeMeta[row.ruleType] ? typeMeta[row.ruleType].label : row.ruleType }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 命中动作：封禁 / 告警 / 仅记录 -->
        <el-table-column prop="triggerAction" label="命中动作" width="110">
          <template slot-scope="{row}">
            <el-tag :type="row.triggerAction === 'BAN_IP' ? 'danger' : (row.triggerAction === 'ALERT' ? 'warning' : 'info')">
              {{ actionMeta[row.triggerAction] || row.triggerAction }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="banDurationMin" label="封禁时长(分)" width="110" align="center">
          <template slot-scope="{row}">{{ row.triggerAction === 'BAN_IP' ? (row.banDurationMin || '—') : '—' }}</template>
        </el-table-column>
        <!-- 启用开关：可即时启停规则 -->
        <el-table-column label="启用" width="90" align="center">
          <template slot-scope="{row}">
            <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
        <!-- 操作列：编辑 / 删除 -->
        <el-table-column label="操作" width="120" fixed="right">
          <template slot-scope="{row}">
            <el-button size="mini" type="text" @click="showEdit(row)">编辑</el-button>
            <el-button size="mini" type="text" class="danger-link" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" @current-change="v=>{query.current=v;loadData()}" :current-page="query.current" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </el-card>

    <!-- 新增 / 编辑 规则对话框 -->
    <el-dialog :title="dialogTitle" :visible.sync="visible" width="560px" @close="resetForm">
      <el-form :model="form" label-width="110px">
        <el-form-item label="规则名称"><el-input v-model="form.ruleName" placeholder="如：登录接口频率限制" /></el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="form.ruleType" placeholder="请选择" style="width:100%">
            <el-option v-for="(m,k) in typeMeta" :key="k" :label="m.label" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="命中动作">
          <el-select v-model="form.triggerAction" placeholder="请选择" style="width:100%">
            <el-option v-for="(label,k) in actionMeta" :key="k" :label="label" :value="k" />
          </el-select>
        </el-form-item>
        <!-- 仅封禁动作需要填写封禁时长 -->
        <el-form-item v-if="form.triggerAction==='BAN_IP'" label="封禁时长(分)">
          <el-input-number v-model="form.banDurationMin" :min="1" :max="100000" />
        </el-form-item>
        <el-form-item label="规则配置(JSON)">
          <el-input v-model="form.ruleConfig" type="textarea" :rows="3" placeholder='如 {"threshold":100,"window":"1m"}' />
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <div slot="footer">
        <el-button @click="visible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </el-dialog>
  </div>
</template>
<script>
import { getRuleList, createRule, updateRule, deleteRule } from '@/api/modules'
export default {
  data() {
    return {
      // 查询条件：分页参数
      query: { current: 1, size: 10 },
      // 规则列表与总条数
      list: [], total: 0, loading: false,
      // 对话框状态
      visible: false, saving: false,
      // 编辑态标记（true=编辑，false=新增）
      editing: false,
      // 规则类型元数据：key=后端枚举，label/type=前端展示
      typeMeta: {
        RATE_LIMIT: { label: '频率限制', type: 'warning' },
        IP_ABNORMAL: { label: 'IP异常', type: 'danger' },
        SIGN_INVALID: { label: '签名异常', type: 'danger' },
        SQL_INJECT: { label: 'SQL注入', type: 'danger' },
        XSS: { label: 'XSS攻击', type: 'danger' },
        BLACKLIST: { label: '黑名单', type: 'info' }
      },
      // 命中动作元数据
      actionMeta: { BAN_IP: '封禁IP', ALERT: '告警', LOG: '仅记录' },
      // 表单数据
      form: this.emptyForm()
    }
  },
  computed: {
    // 对话框标题（新增/编辑切换）
    dialogTitle() { return this.editing ? '编辑规则' : '新增规则' }
  },
  mounted() { this.loadData() },
  methods: {
    // 重置表单为空白态
    emptyForm() {
      return { id: null, ruleName: '', ruleType: 'RATE_LIMIT', triggerAction: 'BAN_IP', banDurationMin: 60, ruleConfig: '', description: '', enabled: true }
    },
    // 加载安全检测规则列表
    async loadData() {
      this.loading = true
      try {
        const res = await getRuleList()
        this.list = res.data || []
        this.total = this.list.length
      } finally { this.loading = false }
    },
    // 打开新增对话框
    showAdd() { this.editing = false; this.form = this.emptyForm(); this.visible = true },
    // 打开编辑对话框并回填
    showEdit(row) { this.editing = true; this.form = { ...row }; this.visible = true },
    // 关闭时重置表单
    resetForm() { this.form = this.emptyForm() },
    // 保存：新增走 POST，编辑走 PUT
    async save() {
      if (!this.form.ruleName) { this.$message.warning('请填写规则名称'); return }
      this.saving = true
      try {
        if (this.editing) {
          await updateRule(this.form.id, this.form)
        } else {
          await createRule(this.form)
        }
        this.visible = false
        this.$message.success('保存成功')
        this.loadData()
      } finally { this.saving = false }
    },
    // 即时启停规则（仅向部分字段提交更新）
    async toggleEnabled(row) {
      try {
        await updateRule(row.id, row)
        this.$message.success(row.enabled ? '已启用' : '已停用')
      } catch (e) {
        row.enabled = !row.enabled // 失败回滚
      }
    },
    // 删除规则（二次确认）
    async remove(row) {
      await this.$confirm('确认删除该规则?')
      await deleteRule(row.id)
      this.$message.success('已删除')
      this.loadData()
    }
  }
}
</script>
