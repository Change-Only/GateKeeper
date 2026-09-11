import Vue from 'vue'
import Vuex from 'vuex'
import { buildMenus } from '@/router/menu'
import { hasPerm } from '@/utils/perm'

Vue.use(Vuex)

const LS_TOKEN = 'gatekeeper_token'
const LS_USER = 'gatekeeper_user'
const LS_PERMS = 'gatekeeper_perms'

// 创建并导出全局状态仓库（T05 Phase 0：新增 perms / menus / alertUnread）
export default new Vuex.Store({
  state: {
    // 当前登录用户信息 {id, username, realName}
    userInfo: JSON.parse(localStorage.getItem(LS_USER) || 'null'),
    // 权限点编码数组（登录返回的 perms 闸门数据源）
    perms: JSON.parse(localStorage.getItem(LS_PERMS) || '[]'),
    // 登录后由 menu.js 经 perms 过滤得到的可访问菜单
    menus: [],
    // 顶栏告警铃铛未读数
    alertUnread: 0
  },
  getters: {
    // 权限点校验：this.$store.getters.hasPerm(code)
    hasPerm: (state) => (code) => hasPerm(code, state.perms),
    // 可访问菜单（已按 perms 过滤）
    menus: (state) => state.menus
  },
  mutations: {
    SET_USER(state, user) {
      state.userInfo = user
      localStorage.setItem(LS_USER, JSON.stringify(user))
    },
    SET_PERMS(state, perms) {
      state.perms = perms || []
      localStorage.setItem(LS_PERMS, JSON.stringify(state.perms))
    },
    SET_MENUS(state, menus) {
      state.menus = menus || []
    },
    SET_ALERT_UNREAD(state, n) {
      state.alertUnread = n || 0
    },
    LOGOUT(state) {
      state.userInfo = null
      state.perms = []
      state.menus = []
      state.alertUnread = 0
      localStorage.removeItem(LS_USER)
      localStorage.removeItem(LS_TOKEN)
      localStorage.removeItem(LS_PERMS)
    }
  },
  actions: {
    // 登录成功后统一写入 token / 用户 / 权限，并依据 perms 计算可访问菜单
    applyAuth({ commit }, { token, user, perms }) {
      if (token) localStorage.setItem(LS_TOKEN, token)
      commit('SET_USER', user)
      commit('SET_PERMS', perms)
      commit('SET_MENUS', buildMenus(perms || []))
    }
  }
})
