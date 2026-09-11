import Vue from 'vue'
import VueRouter from 'vue-router'
import store from '@/store'
import { buildMenus } from './menu'

Vue.use(VueRouter)

// 20 个业务页 + 保留页共用同一个占位 StubPage（Phase 0 骨架；真实页面在后续 Phase 实现）
const StubPage = () => import('@/views/StubPage.vue')

const routes = [
  // 根路径重定向到仪表盘页面
  { path: '/', redirect: '/dashboard' },
  // 登录页（独立于布局之外）
  { path: '/login', component: () => import('@/views/system/Login.vue') },
  // 接入文档页（公开可访问）
  {
    path: '/access-doc',
    name: 'AccessDoc',
    component: () => import('@/views/system/AccessDoc.vue'),
    meta: { title: '接入文档' }
  },
  // 数据大屏页面（独立全屏路由）
  {
    path: '/screen',
    name: 'DataScreen',
    component: () => import('@/views/screen/Index.vue')
  },
  // 布局容器：其下子路由渲染在 Layout 的 <router-view> 中
  {
    path: '/',
    component: () => import('@/components/layout/Layout.vue'),
    children: [
      // ===== 概览 =====
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/dashboard/Dashboard.vue'), meta: { title: '概览', perm: 'dashboard:view', module: '概览' } },
      // ===== 应用管理 =====
      { path: 'app', name: 'AppList', component: () => import('@/views/app/AppList.vue'), meta: { title: '应用列表', perm: 'app:list', module: '应用管理' } },
      // ===== 接口管理 =====
      { path: 'api/api-group', name: 'ApiGroup', component: () => import('@/views/api/ApiGroup.vue'), meta: { title: '接口分组', perm: 'api:list', module: '接口管理' } },
      { path: 'api/api-list', name: 'ApiList', component: () => import('@/views/api/ApiList.vue'), meta: { title: '接口列表', perm: 'api:list', module: '接口管理' } },
      // ===== 权限管理 =====
      { path: 'perm/perm-user', name: 'PermUser', component: StubPage, meta: { title: '用户管理', perm: 'sys:user:list', module: '权限管理' } },
      { path: 'perm/perm-role', name: 'PermRole', component: StubPage, meta: { title: '角色管理', perm: 'sys:role:list', module: '权限管理' } },
      { path: 'perm/perm-matrix', name: 'PermMatrix', component: StubPage, meta: { title: '接口授权总览', perm: 'grant:list', module: '权限管理' } },
      { path: 'perm/perm-datascope', name: 'PermDatascope', component: StubPage, meta: { title: '数据权限', perm: 'sys:datascope:view', module: '权限管理' } },
      { path: 'perm/perm-audit', name: 'PermAudit', component: StubPage, meta: { title: '操作审计', perm: 'audit:list', module: '权限管理' } },
      // ===== 系统设置 =====
      { path: 'sys/sys-env', name: 'SysEnv', component: StubPage, meta: { title: '环境与网关', perm: 'sys:env:list', module: '系统设置' } },
      { path: 'sys/sys-security', name: 'SysSecurity', component: StubPage, meta: { title: '安全策略', perm: 'sys:security:view', module: '系统设置' } },
      { path: 'sys/sys-bizline', name: 'SysBizline', component: StubPage, meta: { title: '业务线管理', perm: 'sys:bizline:list', module: '系统设置' } },
      { path: 'sys/sys-dict', name: 'SysDict', component: StubPage, meta: { title: '字典管理', perm: 'sys:dict:update', module: '系统设置' } },
      { path: 'sys/sys-alarm', name: 'SysAlarm', component: StubPage, meta: { title: '告警规则', perm: 'sys:alarm:update', module: '系统设置' } },
      { path: 'sys/sys-notify', name: 'SysNotify', component: StubPage, meta: { title: '通知渠道', perm: 'sys:notify:list', module: '系统设置' } },
      { path: 'sys/sys-config', name: 'SysConfig', component: StubPage, meta: { title: '参数配置', perm: 'sys:config:list', module: '系统设置' } },
      { path: 'sys/sys-log', name: 'SysLog', component: StubPage, meta: { title: '日志与审计', perm: 'sys:log:list', module: '系统设置' } },
      // ===== 监控与审计 =====
      { path: 'mon/mon-calllog', name: 'MonCalllog', component: StubPage, meta: { title: '调用日志', perm: 'log:call:list', module: '监控与审计' } },
      { path: 'mon/mon-alarm', name: 'MonAlarm', component: StubPage, meta: { title: '告警记录', perm: 'alarm:list', module: '监控与审计' } },
      { path: 'mon/mon-block', name: 'MonBlock', component: StubPage, meta: { title: '封禁管理', perm: 'block:list', module: '监控与审计' } },
      // 保留但不进侧边菜单的旧页面（后续并入 sys-security / 保留独立入口）
      { path: 'encryption', name: 'EncryptionConfig', component: () => import('@/views/encryption/Index.vue'), meta: { title: '加解密管理' } }
    ]
  }
]

const router = new VueRouter({ routes })

// 公开页面：无需登录即可访问（登录页 / 接入文档）
const PUBLIC_PATHS = ['/login', '/access-doc']

// 全局前置守卫：未登录跳登录；已登录访问登录页跳回首页；依据 perms 重建菜单；设置标题
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('gatekeeper_token')
  if (!PUBLIC_PATHS.includes(to.path) && !token) {
    next('/login')
    return
  }
  if (to.path === '/login' && token) {
    next('/dashboard')
    return
  }
  // 登录态下，若菜单尚未构建（如刷新直达内页），依据已持久化的 perms 重建
  if (token && store.state.menus.length === 0 && store.state.perms.length > 0) {
    store.commit('SET_MENUS', buildMenus(store.state.perms))
  }
  document.title = to.meta && to.meta.title ? to.meta.title + ' - GateKeeper' : 'GateKeeper'
  next()
})

export default router
