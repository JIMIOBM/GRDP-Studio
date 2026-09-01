import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { baseApi } from '../../config/config.default'
import { disconnectNotifySocket } from '@/utils/notifySocket'

const request = axios.create({
  baseURL: baseApi,
  timeout: 15000,
  withCredentials: true
})

let lastNetworkErrorTip = ''
let lastNetworkErrorTipAt = 0

const showNetworkErrorOnce = message => {
  const now = Date.now()
  if (message === lastNetworkErrorTip && now - lastNetworkErrorTipAt < 5000) return
  lastNetworkErrorTip = message
  lastNetworkErrorTipAt = now
  ElMessage.error(message)
}

// 请求拦截：自动带上 token
request.interceptors.request.use(
  (config) => {
    const accountStr = localStorage.getItem('account')
    if (accountStr) {
      const account = JSON.parse(accountStr)
      if (account.token) {
        config.headers['token'] = account.token
      }
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：统一处理后端返回结构 { code, msg, data }
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 兼容直接返回数据 / 返回包裹结构两种情况
    if (res && typeof res.code !== 'undefined') {
      if (res.code === 200 || res.code === 0) {
        return res
      }
      // 未登录 / token 失效
      if (res.code === 401) {
        disconnectNotifySocket()
        localStorage.removeItem('account')
        router.push('/login')
      }
      ElMessage.error(res.msg || '请求失败')
      return Promise.reject(res)
    }
    return res
  },
  (error) => {
    const message = error.code === 'ECONNABORTED'
      ? '本地服务响应超时，请检查后端服务'
      : (error.response?.data?.msg || error.message || '网络异常')
    showNetworkErrorOnce(message)
    return Promise.reject(error)
  }
)

export default request
