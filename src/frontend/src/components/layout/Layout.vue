<template>
  <!-- 整体布局容器：左侧菜单栏 + 右侧内容区 -->
  <el-container class="gk-layout">
    <Sidebar :collapse="isCollapse" />
    <el-container class="gk-main-wrap">
      <HeaderBar :collapse="isCollapse" @toggle="isCollapse = !isCollapse" />
      <el-main class="gk-main-area">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script>
import Sidebar from './Sidebar.vue'
import HeaderBar from './HeaderBar.vue'

export default {
  name: 'Layout',
  components: { Sidebar, HeaderBar },
  data() {
    return { isCollapse: false }
  }
}
</script>

<style scoped>
.gk-layout { height: 100vh; }
.gk-main-wrap { min-width: 0; }
.gk-main-area { background: #f4f6fa; padding: 0; }
</style>
