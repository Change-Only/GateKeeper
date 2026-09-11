<template>
  <div class="api-tab">
    <el-card shadow="never" class="quota-card">
      <div slot="header" class="card-head">调用配额（全局）</div>
      <el-form ref="form" :model="form" :rules="rules" label-width="120px" class="quota-form">
        <el-form-item label="QPS 限制" prop="qpsLimit">
          <el-input-number v-model="form.qpsLimit" :min="0" :max="100000" :step="10" controls-position="right" />
          <span class="unit">次/秒</span>
        </el-form-item>
        <el-form-item label="并发限制" prop="concurrentLimit">
          <el-input-number v-model="form.concurrentLimit" :min="0" :max="100000" :step="10" controls-position="right" />
          <span class="unit">连接</span>
        </el-form-item>
        <el-form-item label="日调用配额" prop="dailyLimit">
          <el-input-number v-model="form.dailyLimit" :min="0" :max="100000000" :step="1000" controls-position="right" />
          <span class="unit">次/天</span>
        </el-form-item>
        <el-form-item>
          <PermButton perm="app:quota:update" type="primary" :loading="saving" @click="save">保存配额</PermButton>
          <PermButton perm="" type="text" @click="reload">重置</PermButton>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script>
/**
 * 应用配额 Tab（T05 Phase 1 · app-list 详情）
 * 对接 /app/{id}/rate-limit（GET 查询 / PUT 更新）。字段对齐 AppRateLimit：
 * qpsLimit / concurrentLimit / dailyLimit。
 */
import { getRateLimit, updateRateLimit } from '@/api/modules'

export default {
  name: 'QuotaTab',
  props: {
    appId: { type: [Number, String], default: null }
  },
  data() {
    return {
      form: { qpsLimit: 0, concurrentLimit: 0, dailyLimit: 0 },
      saving: false,
      rules: {
        qpsLimit: [{ required: true, message: '请填写 QPS 限制', trigger: 'blur' }],
        dailyLimit: [{ required: true, message: '请填写日配额', trigger: 'blur' }]
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
    async reload() {
      if (this.appId == null) return
      try {
        const res = await getRateLimit(this.appId)
        const d = res.data || {}
        this.form = {
          qpsLimit: d.qpsLimit || 0,
          concurrentLimit: d.concurrentLimit || 0,
          dailyLimit: d.dailyLimit || 0
        }
      } catch (e) { /* 拦截器已提示；保持默认值 */ }
    },
    save() {
      this.$refs.form.validate(async (valid) => {
        if (!valid) return
        this.saving = true
        try {
          await updateRateLimit(this.appId, { ...this.form })
          this.$message.success('配额已保存')
        } catch (e) { /* 拦截器已提示 */ } finally {
          this.saving = false
        }
      })
    }
  }
}
</script>

<style scoped>
.api-tab { padding: 4px; }
.quota-card { max-width: 560px; }
.card-head { font-size: 14px; font-weight: 600; color: #17233d; }
.quota-form { padding-top: 8px; }
.unit { margin-left: 8px; color: #9aa7bf; font-size: 12px; }
</style>
