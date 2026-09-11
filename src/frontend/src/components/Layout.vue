<template>
  <!-- 整体布局容器：左侧菜单栏 + 右侧内容区 -->
  <el-container class="layout-container">
    <!-- 左侧侧边栏，根据折叠状态动态调整宽度 -->
    <el-aside :width="isCollapse ? '64px' : '210px'">
      <!-- 顶部 Logo：盾牌标识 + 产品名，折叠时显示缩写 GK -->
      <div class="logo">
        <svg v-if="!isCollapse" class="shield" viewBox="0 0 48 48" fill="none">
          <path d="M24 4 L42 10 V22 C42 34 34 41 24 44 C14 41 6 34 6 22 V10 Z" fill="rgba(79,140,255,.15)" stroke="#4f8cff" stroke-width="2.5"/>
          <path d="M17 24 l5 5 9 -10" stroke="#6ea8ff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
        </svg>
        <span v-if="!isCollapse" class="logo-text">GateKeeper</span>
        <span v-else class="logo-text">GK</span>
      </div>
      <!-- 导航菜单，开启 router 模式后点击菜单项即跳转对应路由 -->
      <el-menu :default-active="$route.path" :collapse="isCollapse" router
               background-color="#0b1c33" text-color="#8ea4c6" active-text-color="#ffffff">
        <el-menu-item index="/dashboard"><i class="el-icon-data-line"></i><span>统计仪表盘</span></el-menu-item>
        <el-menu-item index="/app"><i class="el-icon-s-cooperation"></i><span>应用管理</span></el-menu-item>
        <el-menu-item index="/interface"><i class="el-icon-connection"></i><span>接口管理</span></el-menu-item>
        <el-menu-item index="/permission"><i class="el-icon-s-check"></i><span>权限管理</span></el-menu-item>
        <el-menu-item index="/encryption"><i class="el-icon-lock"></i><span>加解密管理</span></el-menu-item>
        <el-menu-item index="/log"><i class="el-icon-document"></i><span>调用日志</span></el-menu-item>
        <el-menu-item index="/alert"><i class="el-icon-bell"></i><span>告警中心</span></el-menu-item>
        <el-menu-item index="/access-doc"><i class="el-icon-reading"></i><span>接入文档</span></el-menu-item>
        <el-menu-item index="/system"><i class="el-icon-setting"></i><span>系统管理</span></el-menu-item>
        <!-- 安全防护子菜单，包含 IP 封禁、安全事件与检测规则 -->
        <el-submenu index="security">
          <template slot="title"><i class="el-icon-lock"></i><span>安全防护</span></template>
          <el-menu-item index="/security/ban">IP封禁</el-menu-item>
          <el-menu-item index="/security/event">安全事件</el-menu-item>
          <el-menu-item index="/security/rule">安全规则</el-menu-item>
        </el-submenu>
        <el-menu-item index="/screen"><i class="el-icon-monitor"></i><span>数据大屏</span></el-menu-item>
      </el-menu>
    </el-aside>
    <el-container class="main-wrap">
      <!-- 顶部页头：面包屑 + 折叠按钮 + 搜索 + 通知 + 用户下拉菜单 -->
      <el-header class="header">
        <!-- 折叠/展开侧边栏的切换图标 -->
        <i :class="isCollapse ? 'el-icon-s-unfold' : 'el-icon-s-fold'" class="fold-btn" @click="isCollapse = !isCollapse"></i>
        <!-- 面包屑：当前路由页面标题 -->
        <div class="crumb">工作台 <span class="sep">/</span> <b>{{ $route.meta.title }}</b></div>
        <div class="tb-right">
          <!-- 全局搜索框 -->
          <div class="searchbox">
            <i class="el-icon-search"></i>
            <input placeholder="搜索应用、接口、日志…">
            <span class="kbd">⌘K</span>
          </div>
          <!-- 告警通知铃铛：未读红点计数 + 下拉最近告警 -->
          <el-dropdown class="bell-dropdown" trigger="click" @command="openAlert">
            <div class="bell" :class="{ active: unreadCount > 0 }">
              <i class="el-icon-bell"></i>
              <span v-if="unreadCount > 0" class="rd">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
            </div>
            <el-dropdown-menu slot="dropdown" class="alert-menu">
              <div class="am-head">
                <span>告警通知</span>
                <span class="link" @click="goAlertCenter">查看全部</span>
              </div>
              <div v-if="recentAlerts.length === 0" class="am-empty">暂无未读告警</div>
              <el-dropdown-item v-for="a in recentAlerts" :key="a.id" :command="a.id" class="am-item">
                <span class="dot" :class="'lv-' + a.level.toLowerCase()"></span>
                <span class="am-title" :title="a.content">{{ a.title }}</span>
                <span class="am-time">{{ fmtTime(a.occurredAt) }}</span>
              </el-dropdown-item>
              <div class="am-foot">
                <span class="link" @click="markAllReadFromBell">全部已读</span>
              </div>
            </el-dropdown-menu>
          </el-dropdown>
          <!-- 用户信息下拉菜单 -->
          <el-dropdown class="user-dropdown">
            <span class="user-row"><span class="avatar">{{ (userInfo ? userInfo.username : 'admin').charAt(0) }}</span><span class="uname">{{ userInfo ? userInfo.username : 'admin' }}</span><i class="el-icon-arrow-down"></i></span>
            <el-dropdown-menu slot="dropdown">
              <el-dropdown-item @click.native="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </el-dropdown>
        </div>
      </el-header>
      <!-- 主内容区，渲染子路由对应页面 -->
      <el-main class="main-area">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script>
import { getAlertUnread, getAlertList, markAlertRead, markAllAlertRead } from '@/api/modules'
export default {
  // 布局组件名称
  name: 'Layout',
  data() {
    return {
      // 侧边栏是否折叠
      isCollapse: false,
      // 未读告警数（顶栏铃铛红点）
      unreadCount: 0,
      // 最近未读告警（下拉预览）
      recentAlerts: [],
      // 轮询定时器
      alertTimer: null
    }
  },
  computed: {
    // 从 Vuex 中读取当前登录用户信息
    userInfo() { return this.$store.state.userInfo }
  },
  mounted() {
    // 初次加载 + 每 30 秒轮询未读告警（轻量级，供铃铛实时提示）
    this.loadAlerts()
    this.alertTimer = setInterval(this.loadAlerts, 30000)
  },
  beforeDestroy() {
    if (this.alertTimer) clearInterval(this.alertTimer)
  },
  methods: {
    // 退出登录：清空登录状态并跳转到登录页
    logout() {
      this.$store.commit('LOGOUT')
      this.$router.push('/login')
    },
    // 拉取未读数量与最近未读告警
    async loadAlerts() {
      try {
        this.unreadCount = (await getAlertUnread()).data || 0
        const res = await getAlertList({ current: 1, size: 6, status: 0 })
        this.recentAlerts = (res.data && res.data.records) || []
      } catch (e) { /* 告警接口异常不影响主界面 */ }
    },
    // 点击下拉中的某条告警：标记已读并进入告警中心
    async openAlert(id) {
      try { await markAlertRead(id) } catch (e) { /* 忽略标记失败 */ }
      this.$router.push('/alert')
      this.loadAlerts()
    },
    // 从铃铛一键全部已读
    async markAllReadFromBell() {
      await markAllAlertRead()
      this.unreadCount = 0
      this.recentAlerts = []
      this.$message.success('已全部标记为已读')
    },
    // 跳转告警中心
    goAlertCenter() { this.$router.push('/alert') },
    // 时间格式化：仅展示时分秒
    fmtTime(s) {
      if (!s) return ''
      const i = s.indexOf(' ')
      return i >= 0 ? s.slice(i + 1) : s
    }
  }
}
</script>

<style scoped>
.layout-container { height: 100vh; }

/* 侧栏：深海军蓝渐变 */
.el-aside {
  background: linear-gradient(180deg, #0b1c33 0%, #0e2342 100%);
  transition: width 0.3s;
  overflow-x: hidden;
}
.logo {
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
.logo .shield { width: 26px; height: 26px; }
.el-menu { border-right: none; }

/* 菜单项：hover 浅提亮 + 激活态左侧 3px 品牌蓝指示条 */
.el-menu-item, .el-submenu__title {
  position: relative;
  border-radius: 8px;
  margin: 2px 10px;
  height: 40px;
  line-height: 40px;
}
.el-menu-item:hover, .el-submenu__title:hover { background: rgba(255, 255, 255, .06) !important; }
.el-menu-item.is-active {
  background: #10264a !important;
  font-weight: 500;
}
.el-menu-item.is-active::before {
  content: "";
  position: absolute;
  left: -10px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background: #2563eb;
  border-radius: 0 3px 3px 0;
}
/* 折叠态下移除圆角与指示条偏移 */
.el-menu--collapse .el-menu-item { margin: 2px 6px; }
.el-menu--collapse .el-menu-item.is-active::before { left: -6px; }
/* 子菜单项 */
.el-submenu .el-menu-item { margin: 0 0 0 10px; height: 38px; line-height: 38px; }

/* 顶栏 */
.main-wrap { min-width: 0; }
.header {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border-bottom: 1px solid #e3e8f2;
  padding: 0 20px;
  height: 56px;
}
.fold-btn { font-size: 18px; cursor: pointer; color: #5c6b8a; }
.fold-btn:hover { color: #2563eb; }
.crumb { font-size: 13px; color: #9aa7bf; display: flex; align-items: center; gap: 8px; }
.crumb .sep { color: #d3dbe8; }
.crumb b { color: #17233d; font-weight: 600; }
.tb-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.searchbox {
  position: relative;
  display: flex;
  align-items: center;
}
.searchbox i { position: absolute; left: 10px; color: #9aa7bf; font-size: 14px; }
.searchbox input {
  width: 240px;
  height: 32px;
  border: 1px solid #d3dbe8;
  border-radius: 8px;
  padding: 0 40px 0 32px;
  font-size: 12px;
  color: #17233d;
  outline: none;
  transition: all .15s;
  font-family: inherit;
}
.searchbox input:focus { border-color: #2563eb; box-shadow: 0 0 0 3px rgba(37, 99, 235, .12); }
.searchbox input::placeholder { color: #9aa7bf; }
.searchbox .kbd {
  position: absolute;
  right: 8px;
  font-size: 11px;
  color: #9aa7bf;
  border: 1px solid #e3e8f2;
  border-radius: 4px;
  padding: 0 5px;
  background: #fafbfe;
}
.bell { position: relative; width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #5c6b8a; }
.bell:hover, .bell.active { background: #f4f6fa; color: #2563eb; }
.bell .rd { position: absolute; top: 2px; right: 1px; min-width: 16px; height: 16px; padding: 0 4px; line-height: 16px; text-align: center; font-size: 11px; font-weight: 600; color: #fff; background: #c03337; border-radius: 9px; border: 2px solid #fff; }
/* 告警下拉菜单 */
.alert-menu { width: 320px; padding: 0; }
.alert-menu .am-head { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid #eef1f6; font-size: 13px; font-weight: 600; color: #17233d; }
.alert-menu .am-empty { padding: 22px 14px; text-align: center; font-size: 12px; color: #9aa7bf; }
.alert-menu .am-item { display: flex; align-items: center; gap: 8px; padding: 9px 14px; line-height: 1.3; white-space: normal; }
.alert-menu .am-item .dot { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.alert-menu .am-item .dot.lv-critical { background: #c03337; }
.alert-menu .am-item .dot.lv-warning { background: #f59e0b; }
.alert-menu .am-item .dot.lv-info { background: #2563eb; }
.alert-menu .am-title { flex: 1; font-size: 13px; color: #17233d; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.alert-menu .am-time { flex: none; font-size: 11px; color: #9aa7bf; }
.alert-menu .am-foot { padding: 8px 14px; border-top: 1px solid #eef1f6; text-align: right; }
.alert-menu .link { color: #2563eb; font-size: 12px; cursor: pointer; }
.alert-menu .link:hover { text-decoration: underline; }
.user-dropdown { cursor: pointer; }
.user-row { display: flex; align-items: center; gap: 8px; color: #17233d; }
.avatar {
  width: 28px; height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #4f8cff);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
}
.uname { font-size: 13px; font-weight: 500; }

/* 内容区 */
.main-area { background: #f4f6fa; padding: 0; }
</style>
