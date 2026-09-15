import Vue from 'vue'
import VueRouter from 'vue-router'
import store from '@/store'
import { buildMenus } from './menu'

Vue.use(VueRouter)

// 说明：T05 Phase 1~4 已将所有业务路由替换为真实页面视图，StubPage 占位组件已不再被引用。
// 保留 @/views/StubPage.vue 文件本身，供后续新增未实现路由时临时占位复用。

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
      { path: 'perm/perm-user', name: 'PermUser', component: () => import('@/views/perm/PermUser.vue'), meta: { title: '用户管理', perm: 'sys:user:list', module: '权限管理' } },
      { path: 'perm/perm-role', name: 'PermRole', component: () => import('@/views/perm/PermRole.vue'), meta: { title: '角色管理', perm: 'sys:role:list', module: '权限管理' } },
      { path: 'perm/perm-matrix', name: 'PermMatrix', component: () => import('@/views/perm/PermMatrix.vue'), meta: { title: '接口授权总览', perm: 'grant:list', module: '权限管理' } },
      { path: 'perm/perm-datascope', name: 'PermDatascope', component: () => import('@/views/perm/DataScope.vue'), meta: { title: '数据权限', perm: 'sys:datascope:view', module: '权限管理' } },
      { path: 'perm/perm-audit', name: 'PermAudit', component: () => import('@/views/perm/PermAudit.vue'), meta: { title: '操作审计', perm: 'audit:list', module: '权限管理' } },
      // ===== 系统设置 =====
      { path: 'sys/sys-env', name: 'SysEnv', component: () => import('@/views/system/SysEnv.vue'), meta: { title: '环境与网关', perm: 'env:list', module: '系统设置' } },
      { path: 'sys/sys-security', name: 'SysSecurity', component: () => import('@/views/system/SysSecurity.vue'), meta: { title: '安全策略', perm: 'sys:security:view', module: '系统设置' } },
      { path: 'sys/sys-bizline', name: 'SysBizline', component: () => import('@/views/system/SysBizLine.vue'), meta: { title: '业务线管理', perm: 'biz_line:list', module: '系统设置' } },
      { path: 'sys/sys-dict', name: 'SysDict', component: () => import('@/views/system/SysDict.vue'), meta: { title: '字典管理', perm: 'sys:dict:update', module: '系统设置' } },
      { path: 'sys/sys-alarm', name: 'SysAlarm', component: () => import('@/views/system/SysAlarm.vue'), meta: { title: '告警规则', perm: 'sys:alarm:update', module: '系统设置' } },
      { path: 'sys/sys-notify', name: 'SysNotify', component: () => import('@/views/system/SysNotify.vue'), meta: { title: '通知渠道', perm: 'sys:notify:list', module: '系统设置' } },
      { path: 'sys/sys-config', name: 'SysConfig', component: () => import('@/views/system/SysConfig.vue'), meta: { title: '参数配置', perm: 'sys:config:list', module: '系统设置' } },
      { path: 'sys/sys-log', name: 'SysLog', component: () => import('@/views/system/SysLog.vue'), meta: { title: '日志与审计', perm: 'sys:log:list', module: '系统设置' } },
      // ===== 监控与审计 =====
      { path: 'mon/mon-calllog', name: 'MonCalllog', component: () => import('@/views/monitor/MonCalllog.vue'), meta: { title: '调用日志', perm: 'log:call:list', module: '监控与审计' } },
      { path: 'mon/mon-alarm', name: 'MonAlarm', component: () => import('@/views/monitor/MonAlarm.vue'), meta: { title: '告警记录', perm: 'alarm:list', module: '监控与审计' } },
      { path: 'mon/mon-block', name: 'MonBlock', component: () => import('@/views/monitor/MonBlock.vue'), meta: { title: '封禁管理', perm: 'block:list', module: '监控与审计' } },
      // 保留但不进侧边菜单的旧页面（后续并入 sys-security / 保留独立入口）
      { path: 'encryption', name: 'EncryptionConfig', component: () => import('@/views/encryption/Index.vue'), meta: { title: '加解密管理' } },
      // ===== 开发者中心（T14）=====
      // 左侧菜单「接入文档」的落点：复用公开页组件 + props.embedded=true，
      // 由 Layout 的右侧内容区承载（去掉整屏品牌条与「返回登录」按钮）。
      // ⚠️ 必须与顶层公开路由 /access-doc 用**不同** path，否则 vue-router 只认第一条。
      // 刻意不挂 meta.perm：这是只读说明页，登录即可见，不新增权限点（不触碰权限红线）。
      { path: 'sys/sys-access-doc', name: 'SysAccessDoc', component: () => import('@/views/system/AccessDoc.vue'), props: { embedded: true }, meta: { title: '接入文档', module: '开发者中心' } }
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
