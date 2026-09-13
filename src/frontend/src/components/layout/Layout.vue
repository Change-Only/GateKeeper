<template>
  <!-- 整体布局容器：左侧菜单栏 + 右侧内容区 -->
  <el-container class="gk-layout">
    <Sidebar :collapse="isCollapse" />
    <!--
      🔴 direction="vertical" 必须显式声明，不可删：
      el-container 的竖向判定逻辑是「扫描 $slots.default 里 tag === 'el-header' / 'el-footer' 的
      直接子节点」；本处的头栏是包装组件 <HeaderBar>（其 tag 为 gk-header），判定不到 el-header
      ⇒ 容器退化为 flex-direction: row ⇒ 头栏与主内容区【左右并排】，主内容被挤成右侧窄栏
      （实测：main 落在 x=947 / 宽 653，页面表格出现横向滚动条）。
      2026-09-11 把布局拆成 Sidebar/HeaderBar 子组件时引入此回归（旧写法直接内联 <el-header> 故未暴露）。
    -->
    <el-container class="gk-main-wrap" direction="vertical">
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
