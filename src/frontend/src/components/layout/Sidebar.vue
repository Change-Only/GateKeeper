<template>
  <el-aside :width="collapse ? '64px' : '210px'" class="gk-aside">
    <!-- 顶部 Logo：盾牌标识 + 产品名，折叠时显示缩写 GK -->
    <div class="gk-logo">
      <svg v-if="!collapse" class="gk-shield" viewBox="0 0 48 48" fill="none">
        <path d="M24 4 L42 10 V22 C42 34 34 41 24 44 C14 41 6 34 6 22 V10 Z" fill="rgba(79,140,255,.15)" stroke="#4f8cff" stroke-width="2.5"/>
        <path d="M17 24 l5 5 9 -10" stroke="#6ea8ff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <span class="gk-logo-text">{{ collapse ? 'GK' : 'GateKeeper' }}</span>
    </div>
    <!-- 导航菜单：开启 router 模式后点击即跳转；菜单项已按 perms 过滤 -->
    <el-menu
      :default-active="activePath"
      :collapse="collapse"
      router
      background-color="#0b1c33"
      text-color="#8ea4c6"
      active-text-color="#ffffff"
      class="gk-menu"
    >
      <template v-for="item in menus">
        <el-submenu v-if="item.children && item.children.length" :key="item.path" :index="item.path">
          <template slot="title">
            <i :class="item.icon"></i>
            <span>{{ item.title }}</span>
          </template>
          <el-menu-item
            v-for="child in item.children"
            :key="child.path"
            :index="child.path"
          >{{ child.title }}</el-menu-item>
        </el-submenu>
        <el-menu-item v-else :key="item.path" :index="item.path">
          <i :class="item.icon"></i>
          <span slot="title">{{ item.title }}</span>
        </el-menu-item>
      </template>
    </el-menu>
  </el-aside>
</template>

<script>
export default {
  name: 'Sidebar',
  // 使用全局（非 scoped）样式，确保 el-menu 内部元素（el-submenu__title 等）也能命中
  props: {
    collapse: { type: Boolean, default: false }
  },
  computed: {
    menus() {
      return this.$store.state.menus || []
    },
    activePath() {
      return this.$route.path
    }
  }
}
</script>

<style>
.gk-aside {
  background: linear-gradient(180deg, #0b1c33 0%, #0e2342 100%);
  transition: width 0.3s;
  overflow-x: hidden;
}
.gk-logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  border-bottom: 1px solid rgba(255, 255, 255, .06);
}
.gk-logo .gk-shield { width: 26px; height: 26px; }
.gk-menu { border-right: none; }
.gk-menu .el-menu-item,
.gk-menu .el-submenu__title {
  position: relative;
  border-radius: 8px;
  margin: 2px 10px;
  height: 40px;
  line-height: 40px;
}
.gk-menu .el-menu-item:hover,
.gk-menu .el-submenu__title:hover { background: rgba(255, 255, 255, .06) !important; }
.gk-menu .el-menu-item.is-active {
  background: #10264a !important;
  font-weight: 500;
}
.gk-menu .el-menu-item.is-active::before {
  content: "";
  position: absolute;
  left: -10px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background: #2563eb;
  border-radius: 0 3px 3px 0;
}
.gk-menu.el-menu--collapse .el-menu-item { margin: 2px 6px; }
.gk-menu.el-menu--collapse .el-menu-item.is-active::before { left: -6px; }
.gk-menu .el-submenu .el-menu-item { margin: 0 0 0 10px; height: 38px; line-height: 38px; }
</style>
