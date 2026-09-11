<template>
  <el-header class="gk-header">
    <!-- 折叠/展开侧边栏 -->
    <i :class="collapse ? 'el-icon-s-unfold' : 'el-icon-s-fold'" class="gk-fold-btn" @click="$emit('toggle')"></i>
    <!-- 面包屑：当前路由页面标题 -->
    <div class="gk-crumb">GateKeeper 工作台 <span class="gk-sep">/</span> <b>{{ title }}</b></div>
    <div class="gk-tb-right">
      <!-- 全局搜索框（P2 再接真实搜索） -->
      <div class="gk-searchbox">
        <i class="el-icon-search"></i>
        <input placeholder="搜索应用、接口、日志…">
        <span class="gk-kbd">⌘K</span>
      </div>
      <!-- 告警通知铃铛：未读红点 + 下拉最近告警 -->
      <el-dropdown class="gk-bell-dropdown" trigger="click" @command="openAlert">
        <div class="gk-bell" :class="{ active: unread > 0 }">
          <i class="el-icon-bell"></i>
          <span v-if="unread > 0" class="gk-rd">{{ unread > 99 ? '99+' : unread }}</span>
        </div>
        <el-dropdown-menu slot="dropdown" class="gk-alert-menu">
          <div class="gk-am-head">
            <span>告警通知</span>
            <span class="gk-link" @click="goAlertCenter">查看全部</span>
          </div>
          <div v-if="recentAlerts.length === 0" class="gk-am-empty">暂无未读告警</div>
          <el-dropdown-item v-for="a in recentAlerts" :key="a.id" :command="a.id" class="gk-am-item">
            <span class="gk-dot" :class="'lv-' + (a.level || 1)"></span>
            <span class="gk-am-title" :title="a.content || a.title">{{ a.title }}</span>
            <span class="gk-am-time">{{ fmtTime(a.occurredAt) }}</span>
          </el-dropdown-item>
          <div class="gk-am-foot">
            <span class="gk-link" @click="markAllReadFromBell">全部已读</span>
          </div>
        </el-dropdown-menu>
      </el-dropdown>
      <!-- 用户信息下拉菜单 -->
      <el-dropdown class="gk-user-dropdown">
        <span class="gk-user-row">
          <span class="gk-avatar">{{ initial }}</span>
          <span class="gk-uname">{{ username }}</span>
          <i class="el-icon-arrow-down"></i>
        </span>
        <el-dropdown-menu slot="dropdown">
          <el-dropdown-item @click.native="logout">退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </el-dropdown>
    </div>
  </el-header>
</template>

<script>
import { getAlertUnread, getAlertList, markAlertRead, markAllAlertRead } from '@/api/modules'

export default {
  name: 'HeaderBar',
  props: {
    collapse: { type: Boolean, default: false }
  },
  data() {
    return {
      unread: 0,
      recentAlerts: [],
      alertTimer: null
    }
  },
  computed: {
    title() {
      return (this.$route.meta && this.$route.meta.title) || ''
    },
    user() {
      return this.$store.state.userInfo || {}
    },
    username() {
      return this.user.username || 'admin'
    },
    initial() {
      return (this.username || 'a').charAt(0).toUpperCase()
    }
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
    logout() {
      this.$store.commit('LOGOUT')
      this.$router.push('/login')
    },
    async loadAlerts() {
      try {
        this.unread = (await getAlertUnread()).data || 0
        this.$store.commit('SET_ALERT_UNREAD', this.unread)
        const res = await getAlertList({ current: 1, size: 6, status: 0 })
        this.recentAlerts = (res.data && res.data.records) || []
      } catch (e) { /* 告警接口异常不影响主界面 */ }
    },
    async openAlert(id) {
      try { await markAlertRead(id) } catch (e) { /* 忽略标记失败 */ }
      this.$router.push('/mon/mon-alarm')
      this.loadAlerts()
    },
    async markAllReadFromBell() {
      await markAllAlertRead()
      this.unread = 0
      this.recentAlerts = []
      this.$message.success('已全部标记为已读')
    },
    goAlertCenter() { this.$router.push('/mon/mon-alarm') },
    fmtTime(s) {
      if (!s) return ''
      const i = String(s).indexOf(' ')
      return i >= 0 ? s.slice(i + 1) : s
    }
  }
}
</script>

<style>
.gk-header {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fff;
  border-bottom: 1px solid #e3e8f2;
  padding: 0 20px;
  height: 56px;
}
.gk-fold-btn { font-size: 18px; cursor: pointer; color: #5c6b8a; }
.gk-fold-btn:hover { color: #2563eb; }
.gk-crumb { font-size: 13px; color: #9aa7bf; display: flex; align-items: center; gap: 8px; }
.gk-crumb .gk-sep { color: #d3dbe8; }
.gk-crumb b { color: #17233d; font-weight: 600; }
.gk-tb-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.gk-searchbox { position: relative; display: flex; align-items: center; }
.gk-searchbox i { position: absolute; left: 10px; color: #9aa7bf; font-size: 14px; }
.gk-searchbox input {
  width: 240px; height: 32px; border: 1px solid #d3dbe8; border-radius: 8px;
  padding: 0 40px 0 32px; font-size: 12px; color: #17233d; outline: none;
  transition: all .15s; font-family: inherit;
}
.gk-searchbox input:focus { border-color: #2563eb; box-shadow: 0 0 0 3px rgba(37, 99, 235, .12); }
.gk-searchbox input::placeholder { color: #9aa7bf; }
.gk-searchbox .gk-kbd {
  position: absolute; right: 8px; font-size: 11px; color: #9aa7bf;
  border: 1px solid #e3e8f2; border-radius: 4px; padding: 0 5px; background: #fafbfe;
}
.gk-bell { position: relative; width: 32px; height: 32px; border-radius: 8px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: #5c6b8a; }
.gk-bell:hover, .gk-bell.active { background: #f4f6fa; color: #2563eb; }
.gk-bell .gk-rd { position: absolute; top: 2px; right: 1px; min-width: 16px; height: 16px; padding: 0 4px; line-height: 16px; text-align: center; font-size: 11px; font-weight: 600; color: #fff; background: #c03337; border-radius: 9px; border: 2px solid #fff; }
.gk-alert-menu { width: 320px; padding: 0; }
.gk-alert-menu .gk-am-head { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; border-bottom: 1px solid #eef1f6; font-size: 13px; font-weight: 600; color: #17233d; }
.gk-alert-menu .gk-am-empty { padding: 22px 14px; text-align: center; font-size: 12px; color: #9aa7bf; }
.gk-alert-menu .gk-am-item { display: flex; align-items: center; gap: 8px; padding: 9px 14px; line-height: 1.3; white-space: normal; }
.gk-alert-menu .gk-am-item .gk-dot { flex: none; width: 8px; height: 8px; border-radius: 50%; }
.gk-alert-menu .gk-am-item .gk-dot.lv-1 { background: #2563eb; }
.gk-alert-menu .gk-am-item .gk-dot.lv-2 { background: #f59e0b; }
.gk-alert-menu .gk-am-item .gk-dot.lv-3 { background: #c03337; }
.gk-alert-menu .gk-am-title { flex: 1; font-size: 13px; color: #17233d; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gk-alert-menu .gk-am-time { flex: none; font-size: 11px; color: #9aa7bf; }
.gk-alert-menu .gk-am-foot { padding: 8px 14px; border-top: 1px solid #eef1f6; text-align: right; }
.gk-link { color: #2563eb; font-size: 12px; cursor: pointer; }
.gk-link:hover { text-decoration: underline; }
.gk-user-dropdown { cursor: pointer; }
.gk-user-row { display: flex; align-items: center; gap: 8px; color: #17233d; }
.gk-avatar {
  width: 28px; height: 28px; border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #4f8cff); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600;
}
.gk-uname { font-size: 13px; font-weight: 500; }
</style>
