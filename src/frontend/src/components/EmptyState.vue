<template>
  <div class="empty-state">
    <!-- 品牌蓝插画：空文档盒 -->
    <svg class="empty-illus" width="120" height="100" viewBox="0 0 120 100" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <rect x="14" y="30" width="92" height="60" rx="8" fill="#eef2ff" />
      <path d="M34 30V22C34 17.58 37.58 14 42 14H78C82.42 14 86 17.58 86 22V30" fill="#e0e7ff" />
      <path d="M34 30V22C34 17.58 37.58 14 42 14H78C82.42 14 86 17.58 86 22V30" stroke="#2563eb" stroke-width="2.5" stroke-linecap="round" />
      <rect x="14" y="30" width="92" height="60" rx="8" stroke="#2563eb" stroke-width="2.5" />
      <line x1="30" y1="46" x2="90" y2="46" stroke="#c7d2fe" stroke-width="3" stroke-linecap="round" />
      <line x1="30" y1="58" x2="72" y2="58" stroke="#c7d2fe" stroke-width="3" stroke-linecap="round" />
      <line x1="30" y1="70" x2="80" y2="70" stroke="#c7d2fe" stroke-width="3" stroke-linecap="round" />
      <circle cx="96" cy="22" r="14" fill="#fff" stroke="#2563eb" stroke-width="2.5" />
      <line x1="103" y1="29" x2="112" y2="38" stroke="#2563eb" stroke-width="3" stroke-linecap="round" />
    </svg>
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div v-if="$slots.action" class="empty-action">
      <slot name="action" />
    </div>
  </div>
</template>

<script>
export default {
  name: 'EmptyState',
  props: {
    // 主文案，默认"暂无数据"
    title: { type: String, default: '暂无数据' },
    // 副文案（可选，如"当前筛选条件下没有匹配记录"）
    description: { type: String, default: '' }
  }
}
</script>

<style scoped>
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px 20px; }
.empty-illus { opacity: 0.9; margin-bottom: 8px; }
.empty-title { font-size: 14px; color: #606266; margin: 0; font-weight: 500; }
.empty-desc { font-size: 12px; color: #a0a6b3; margin: 6px 0 0; }
.empty-action { margin-top: 14px; }
</style>
