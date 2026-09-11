import { hasPerm } from '@/utils/perm'

/**
 * 静态菜单树（对应原型 MENU_TREE / T05 §7 路由—权限点映射）
 * ------------------------------------------------------------------
 * 每个叶节点带 perm 权限点；登录后由 buildMenus(perms) 过滤，侧边栏仅渲染
 * 有权限的菜单项。分组节点（无 perm）在其子节点任一有权限时保留。
 */
export const MENU_TREE = [
  { title: '概览', icon: 'el-icon-data-line', path: '/dashboard', perm: 'dashboard:view' },
  { title: '应用管理', icon: 'el-icon-s-cooperation', path: '/app', perm: 'app:list' },
  {
    title: '接口管理', icon: 'el-icon-connection', path: '/api',
    children: [
      { title: '接口分组', path: '/api/api-group', perm: 'api:list' },
      { title: '接口列表', path: '/api/api-list', perm: 'api:list' }
    ]
  },
  {
    title: '权限管理', icon: 'el-icon-s-check', path: '/perm',
    children: [
      { title: '用户管理', path: '/perm/perm-user', perm: 'sys:user:list' },
      { title: '角色管理', path: '/perm/perm-role', perm: 'sys:role:list' },
      { title: '接口授权总览', path: '/perm/perm-matrix', perm: 'grant:list' },
      { title: '数据权限', path: '/perm/perm-datascope', perm: 'sys:datascope:view' },
      { title: '操作审计', path: '/perm/perm-audit', perm: 'audit:list' }
    ]
  },
  {
    title: '系统设置', icon: 'el-icon-setting', path: '/sys',
    children: [
      { title: '环境与网关', path: '/sys/sys-env', perm: 'sys:env:list' },
      { title: '安全策略', path: '/sys/sys-security', perm: 'sys:security:view' },
      { title: '业务线管理', path: '/sys/sys-bizline', perm: 'sys:bizline:list' },
      { title: '字典管理', path: '/sys/sys-dict', perm: 'sys:dict:update' },
      { title: '告警规则', path: '/sys/sys-alarm', perm: 'sys:alarm:update' },
      { title: '通知渠道', path: '/sys/sys-notify', perm: 'sys:notify:list' },
      { title: '参数配置', path: '/sys/sys-config', perm: 'sys:config:list' },
      { title: '日志与审计', path: '/sys/sys-log', perm: 'sys:log:list' }
    ]
  },
  {
    title: '监控与审计', icon: 'el-icon-monitor', path: '/mon',
    children: [
      { title: '调用日志', path: '/mon/mon-calllog', perm: 'log:call:list' },
      { title: '告警记录', path: '/mon/mon-alarm', perm: 'alarm:list' },
      { title: '封禁管理', path: '/mon/mon-block', perm: 'block:list' }
    ]
  }
]

/**
 * 依据当前 perms 过滤菜单树
 * @param {string[]} perms 权限点数组
 * @returns {Array} 过滤后的菜单（分组节点保留其子节点中可见的）
 */
export function buildMenus(perms) {
  const list = MENU_TREE.filter((item) => {
    if (item.children && item.children.length) {
      return item.children.some((c) => hasPerm(c.perm, perms))
    }
    return hasPerm(item.perm, perms)
  })
  return list.map((item) => {
    if (item.children && item.children.length) {
      return { ...item, children: item.children.filter((c) => hasPerm(c.perm, perms)) }
    }
    return { ...item }
  })
}
