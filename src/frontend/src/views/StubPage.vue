<template>
  <div class="stub-page page-container">
    <div class="stub-head">
      <h2>{{ title }}</h2>
      <span class="stub-badge">Phase 0 · 路由骨架占位</span>
    </div>
    <el-card shadow="never" class="stub-card">
      <div class="stub-body">
        <i class="el-icon-loading"></i>
        <p>该页面将在后续 Phase 实现，当前为路由骨架占位。</p>
        <p class="stub-route">
          route: <code>{{ $route.path }}</code> · perm: <code>{{ $route.meta.perm || '—' }}</code>
        </p>
      </div>
    </el-card>
  </div>
</template>

<script>
export default {
  name: 'StubPage',
  computed: {
    title() {
      return (this.$route.meta && this.$route.meta.title) || '页面'
    }
  }
}
</script>

<style scoped>
.stub-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.stub-head h2 { margin: 0; font-size: 18px; color: #17233d; font-weight: 600; }
.stub-badge { font-size: 12px; color: #2563eb; background: #eaf1ff; border-radius: 6px; padding: 2px 8px; }
.stub-card { min-height: 260px; display: flex; align-items: center; justify-content: center; }
.stub-body { text-align: center; color: #5c6b8a; }
.stub-body i { font-size: 28px; color: #2563eb; }
.stub-body p { margin: 12px 0 0; }
.stub-route { font-size: 12px; color: #9aa7bf; }
.stub-route code { background: #f0f3f9; padding: 1px 6px; border-radius: 4px; }
</style>
