// API 请求封装：基于 axios 创建统一的后端请求实例
import axios from 'axios'
// 引入 Element UI 消息提示组件，用于请求错误提示
import { Message } from 'element-ui'
// 引入路由与状态仓库：401 时用 SPA 路由跳转登录页（保留页面状态，避免整页刷新丢失表单）
import router from '@/router'
import store from '@/store'

// 创建 axios 实例，统一基础路径与超时时间
const service = axios.create({
  // 所有请求统一以 /api 为前缀（由 devServer 代理到后端）
  baseURL: '/api',
  // 请求超时时间：15 秒
  timeout: 15000
})

// 请求拦截器：在每次请求发出前附加登录令牌
service.interceptors.request.use(
  config => {
    // 从 localStorage 读取登录令牌
    const token = localStorage.getItem('gatekeeper_token')
    // 若令牌存在，则在请求头中携带 Bearer Token
    if (token) {
      config.headers['Authorization'] = 'Bearer ' + token
    }
    return config
  },
  error => Promise.reject(error)
)

// 响应拦截器：统一处理业务状态码与网络错误
service.interceptors.response.use(
  response => {
    const res = response.data
    // 若后端返回的业务码存在且非 200，视为业务失败并弹出错误提示
    if (res.code !== undefined && res.code !== 200) {
      Message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || 'Error'))
    }
    // 业务成功时直接返回响应数据
    return res
  },
  error => {
    // 401 未认证：清除本地凭证并以 SPA 路由跳转登录页（不整页刷新，避免表单/页面状态丢失）
    if (error.response && error.response.status === 401) {
      store.commit('LOGOUT')
      if (router.currentRoute.path !== '/login') {
        Message.error('登录已过期，请重新登录')
        router.push('/login')
      }
      return Promise.reject(error)
    }
    // 网络层异常统一弹出提示
    const msg = (error.response && error.response.data && error.response.data.message) || error.message || '网络异常'
    Message.error(msg)
    return Promise.reject(error)
  }
)

export default service
