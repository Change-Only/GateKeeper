/**
 * 字典工具
 * ------------------------------------------------------------------
 * 后端 DictController 尚未就绪（见 T05 §8 风险 1），此处使用原型枚举字典的
 * 静态种子兜底。组件层（DictSelect）默认走静态种子；待后端 /sys/dict 就绪后
 * 由 DictSelect 的 remote 模式拉取真实数据。
 */
import { ENUM_OPTIONS } from './enum'

// 原型 6 个字典（docs/原型枚举字典.md §十七）
export const STATIC_DICTS = {
  app_type: ENUM_OPTIONS.appType,
  visibility: ENUM_OPTIONS.visibility,
  api_status: [
    { value: 0, label: '草稿' },
    { value: 1, label: '待审核' },
    { value: 2, label: '已发布' },
    { value: 3, label: '已弃用' },
    { value: 4, label: '已下线' }
  ],
  grant_status: [
    { value: 0, label: '待审批' },
    { value: 1, label: '已生效' },
    { value: 2, label: '已过期' },
    { value: 3, label: '已撤销' },
    { value: 4, label: '已驳回' }
  ],
  cred_status: [
    { value: 1, label: '启用中' },
    { value: 2, label: '已停用' },
    { value: 3, label: '已吊销' },
    { value: 4, label: '已过期' }
  ],
  alarm_level: ENUM_OPTIONS.alarmLevel
}

/**
 * 同步获取字典项（静态种子）
 * @param {string} dictCode
 * @returns {Array<{value,label}>}
 */
export function getStaticDictItems(dictCode) {
  return STATIC_DICTS[dictCode] || []
}

/** 获取字典项的标签文本 */
export function getDictLabel(dictCode, value) {
  const items = getStaticDictItems(dictCode)
  const it = items.find((x) => String(x.value) === String(value))
  return it ? it.label : value
}
