/**
 * 权限点校验工具
 * ------------------------------------------------------------------
 * 权限点编码来自登录接口返回的 perms 数组（唯一闸门数据源；JWT 不塞权限，
 * 服务端 PermissionInterceptor 实校验）。本模块提供前端菜单 / 按钮显隐判断。
 */

const LS_PERMS = 'gatekeeper_perms'

// 惰性读取 localStorage 兜底（组件未注入 store 时）
function resolvePerms() {
  try {
    return JSON.parse(localStorage.getItem(LS_PERMS) || '[]')
  } catch (e) {
    return []
  }
}

/**
 * 是否拥有某权限点
 * @param {string} code 权限点编码；为空表示无需权限，默认可见
 * @param {string[]} [perms] 权限点数组；省略时从 localStorage 兜底读取
 * @returns {boolean}
 */
export function hasPerm(code, perms) {
  if (!code) return true
  const list = Array.isArray(perms) ? perms : resolvePerms()
  if (!Array.isArray(list) || list.length === 0) return false
  if (list.indexOf('*') >= 0) return true // 超管通配
  return list.indexOf(code) >= 0
}

/** 拥有任一权限点即可见 */
export function hasAnyPerm(codes, perms) {
  if (!Array.isArray(codes) || codes.length === 0) return true
  return codes.some((c) => hasPerm(c, perms))
}

/** 需同时拥有全部权限点 */
export function hasAllPerm(codes, perms) {
  if (!Array.isArray(codes) || codes.length === 0) return true
  return codes.every((c) => hasPerm(c, perms))
}
