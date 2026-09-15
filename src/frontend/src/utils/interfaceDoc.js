/**
 * 应用「接口文档」导出（T16-2）
 * ------------------------------------------------------------------
 * 需求：「应用管理中增加接口文档导出，**只导出有权限的接口**」。
 *
 * 为什么正文只在 JS 侧定义：
 *   与 T14 接入文档同一取舍 —— 后端只返回**结构化数据**
 *   （GET /app/{id}/interface-doc，见 AppInterfaceDocVo），
 *   Markdown 由这里渲染。若后端再做一套模板，两边口径迟早发散，
 *   最典型的后果是「文档里写的签名算法与页面上讲的不一致」。
 *
 * 「有权限」的判定在后端（授权已生效 + 在有效期内 + 接口已启用）；
 * 本模块**不做二次过滤**，只负责渲染，避免出现两套判定标准。
 *
 * 依赖：@/utils/accessDoc 的 downloadTextFile / safeFileName / formatNow / GK_HEADERS
 *       与网关认证头定义共用同一来源。
 */
import { downloadTextFile, safeFileName, formatNow, GK_HEADERS } from './accessDoc'

/** api_param.param_type 语义 → 文档章节名（顺序即章节顺序） */
const PARAM_SECTIONS = [
  { type: 1, key: 'header', title: '请求头参数' },
  { type: 2, key: 'query', title: 'Query 参数' },
  { type: 3, key: 'body', title: 'Body 参数' },
  { type: 4, key: 'response', title: '响应参数' },
  { type: 5, key: 'error', title: '错误码' }
]

/** null/空 → 占位符（文档里出现 "null" 比 "—" 难看得多） */
function nz(v) {
  if (v === null || v === undefined || v === '') return '—'
  return String(v)
}

/** Markdown 表格单元格转义：竖线与换行会破坏表格结构 */
function cell(v) {
  return nz(v).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ')
}

/** 必填列：api_param.required 1=必填 0=选填 */
function requiredText(v) {
  if (v === null || v === undefined) return '—'
  return Number(v) === 1 ? '是' : '否'
}

/** 鉴权要求：api_interface.auth_required 1=需鉴权 0=免鉴权 */
function authText(v) {
  if (v === null || v === undefined) return '—'
  return Number(v) === 1 ? '需鉴权' : '免鉴权'
}

/** 有效期：valid_from / valid_to 均可为空（空=不限制） */
function validText(from, to) {
  if (!from && !to) return '不限制'
  return `${from || '不限'} ~ ${to || '不限'}`
}

/** 配额：0 = 不限（后端约定） */
function quotaText(v) {
  if (v === null || v === undefined) return '不限'
  return Number(v) === 0 ? '不限' : String(v)
}

/** 参数表（列按 paramType 分化：错误码表用 errorCode/httpStatus） */
function renderParamTable(list, paramType) {
  const lines = []
  if (paramType === 5) {
    lines.push('| 错误码 | HTTP | 说明 |')
    lines.push('| --- | --- | --- |')
    list.forEach((p) => {
      lines.push(`| ${cell(p.errorCode || p.fieldName)} | ${cell(p.httpStatus)} | ${cell(p.description)} |`)
    })
    return lines
  }
  lines.push('| 字段 | 类型 | 必填 | 示例 | 加解密/脱敏 | 说明 |')
  lines.push('| --- | --- | --- | --- | --- | --- |')
  list.forEach((p) => {
    lines.push(
      `| ${cell(p.fieldName)} | ${cell(p.fieldType)} | ${requiredText(p.required)} ` +
        `| ${cell(p.example)} | ${cell(p.encryptRule)} | ${cell(p.description)} |`
    )
  })
  return lines
}

/**
 * 生成某个应用的接口文档（Markdown）。
 *
 * @param {object} doc  后端 GET /app/{id}/interface-doc 返回的 data
 * @param {object} [opt] { gatewayUrl, generatedAt }
 * @returns {string} Markdown 文本
 */
export function buildInterfaceDocMarkdown(doc, opt) {
  const d = doc || {}
  const appName = nz(d.appName)
  const items = Array.isArray(d.items) ? d.items : []
  const o = opt || {}
  const generatedAt = o.generatedAt || formatNow()
  const L = []

  L.push(`# ${appName} 接口文档`)
  L.push('')
  L.push(`- 应用名称：${appName}`)
  L.push(`- AppKey：\`${nz(d.appKey)}\``)
  L.push(`- 授权接口数：**${items.length}**`)
  if (o.gatewayUrl) L.push(`- 网关入口：\`${o.gatewayUrl}\``)
  L.push(`- 生成时间：${generatedAt}`)
  L.push('')
  L.push('> 本文档由 GateKeeper 自动生成，**仅包含该应用已被授权且当前生效的接口**')
  L.push('> （授权状态为「已生效」且在有效期内，且接口处于启用状态）。')

  // 被剔除的授权单独说明：让「导出条数 < 授权条数」可解释
  const dangling = Number(d.danglingCount) || 0
  const disabled = Number(d.disabledCount) || 0
  if (dangling > 0 || disabled > 0) {
    const parts = []
    if (dangling > 0) parts.push(`${dangling} 条授权的接口已删除`)
    if (disabled > 0) parts.push(`${disabled} 条授权的接口已停用`)
    L.push('>')
    L.push(`> ⚠️ 另有 ${parts.join('、')}，未出现在本文档中。`)
  }
  L.push('')

  // ---------- 一、调用约定 ----------
  L.push('## 一、调用约定')
  L.push('')
  L.push('所有接口统一经网关访问，请求需携带以下认证头：')
  L.push('')
  L.push('| 请求头 | 说明 |')
  L.push('| --- | --- |')
  GK_HEADERS.forEach((h) => {
    L.push(`| \`${h.name}\` | ${h.desc} |`)
  })
  L.push('')
  L.push('签名口径：`sign = SM3(AppKey + AppSecret + Timestamp + Nonce)`（无分隔符拼接，十六进制小写）。')
  L.push('完整的工具类与多语言调用示例见「接入文档」（登录后左侧菜单）。')
  L.push('')

  if (items.length === 0) {
    L.push('## 二、接口清单')
    L.push('')
    L.push('_该应用当前没有已生效的接口授权。请先在「接口授权总览」为其申请授权。_')
    L.push('')
    return L.join('\n')
  }

  // ---------- 二、接口清单 ----------
  L.push('## 二、接口清单')
  L.push('')
  L.push('| # | 接口名称 | 方法 | 路径 | 分组 | 环境 | 说明 |')
  L.push('| --- | --- | --- | --- | --- | --- | --- |')
  items.forEach((it, idx) => {
    L.push(
      `| ${idx + 1} | ${cell(it.interfaceName)} | ${cell(it.requestMethod)} ` +
        `| \`${cell(it.interfacePath)}\` | ${cell(it.groupName)} | ${cell(it.envCode)} ` +
        `| ${cell(it.description)} |`
    )
  })
  L.push('')

  // ---------- 三、接口明细 ----------
  L.push('## 三、接口明细')
  L.push('')
  items.forEach((it, idx) => {
    L.push(`### ${idx + 1}. ${nz(it.interfaceName)}`)
    L.push('')
    L.push('| 项 | 值 |')
    L.push('| --- | --- |')
    L.push(`| 接口编码 | ${cell(it.apiCode)} |`)
    L.push(`| 请求地址 | \`${cell(it.requestMethod)} ${cell(it.interfacePath)}\` |`)
    L.push(`| 入参类型 | ${cell(it.requestParamType)} |`)
    L.push(`| 所属分组 | ${cell(it.groupName)} |`)
    L.push(`| 当前版本 | ${cell(it.currentVersion)} |`)
    L.push(`| 鉴权要求 | ${cell(authText(it.authRequired))} |`)
    L.push(`| SLA | ${cell(it.sla)} |`)
    L.push(`| 转发超时 | ${nz(it.timeoutMs)} ${it.timeoutMs ? 'ms' : ''} |`)
    L.push(`| 标签 | ${cell(it.tags)} |`)
    L.push(`| 授权环境 | ${cell(it.envCode)} |`)
    L.push(`| 授权 QPS | ${cell(quotaText(it.qpsLimit))} |`)
    L.push(`| 日配额 | ${cell(quotaText(it.dailyQuota))} |`)
    L.push(`| 有效期 | ${cell(validText(it.validFrom, it.validTo))} |`)
    L.push('')
    if (it.description) {
      L.push(`**说明**：${nz(it.description)}`)
      L.push('')
    }

    const params = Array.isArray(it.params) ? it.params : []
    PARAM_SECTIONS.forEach((sec) => {
      const list = params.filter((p) => Number(p.paramType) === sec.type)
      if (!list.length) return
      L.push(`#### ${idx + 1}.${PARAM_SECTIONS.indexOf(sec) + 1} ${sec.title}`)
      L.push('')
      renderParamTable(list, sec.type).forEach((line) => L.push(line))
      L.push('')
    })
    if (!params.length) {
      L.push('_该接口尚未维护参数定义（api_param）。_')
      L.push('')
    }
  })

  return L.join('\n')
}

/**
 * 一步到位：为指定应用导出接口文档（Markdown）。
 *
 * @param {object} doc  后端返回的 data
 * @param {object} [opt] { gatewayUrl }
 * @returns {string} 实际使用的文件名
 */
export function exportInterfaceDoc(doc, opt) {
  const d = doc || {}
  const appName = d.appName ? String(d.appName) : '应用'
  const content = buildInterfaceDocMarkdown(d, opt)
  const filename = safeFileName(`${appName}-接口文档`) + '.md'
  // 注意：downloadTextFile 内部会自己补 ';charset=utf-8'，这里只传基础 MIME，
  // 否则会拼成 'text/markdown;charset=utf-8;charset=utf-8'
  downloadTextFile(filename, content, 'text/markdown')
  return filename
}
