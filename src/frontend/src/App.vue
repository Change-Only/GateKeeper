<template>
  <!-- 应用根节点，根据路由渲染对应页面组件 -->
  <div id="app">
    <router-view />
  </div>
</template>

<script>
export default {
  // 根组件名称
  name: 'App'
}
</script>
