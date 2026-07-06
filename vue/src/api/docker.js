import axios from 'axios'
import { ElMessage } from 'element-plus'

const dockerRequest = axios.create({
  baseURL: '/docker-api',
  timeout: 30000,
  withCredentials: true
})

dockerRequest.interceptors.request.use(
  (config) => {
    const accountStr = localStorage.getItem('account')
    if (accountStr) {
      const account = JSON.parse(accountStr)
      if (account.token) {
        config.headers.token = account.token
      }
    }
    return config
  },
  (error) => Promise.reject(error)
)

dockerRequest.interceptors.response.use(
  (res) => res,
  (err) => {
    if (!err.config?.silent) {
      const msg = err.response?.data?.msg || err.response?.data?.message || err.message || '请求失败'
      ElMessage.error(`Docker 服务错误：${msg}`)
    }
    return Promise.reject(err)
  }
)

export const projectApi = {
  getProject: (projectId = 1) =>
    dockerRequest.get(`/projects/${projectId}`, {
      params: { projectId }
    })
}

export const waterInvasionApi = {
  analyze: (data) =>
    dockerRequest.post('/projectanalysis/waterinvasionanalysis', data)
}

export const materialBalanceApi = {
  calc: (data) =>
    dockerRequest.post('/projectanalysis/dynamicoriginalgasInplace/mb/calc', data),

  getAverageFormationPressure: (projectId, gasReservoirId, wellName, page = 1, size = -1) =>
    dockerRequest.get(
      `/projectanalysis/dynamicoriginalgasInplace/averageFormationPressure/${projectId}/${gasReservoirId}/${encodeURIComponent(wellName)}`,
      { params: { page, size } }
    ),

  getResult: (projectId, gasReservoirId, resultId) =>
    dockerRequest.get(`/projectanalysis/dynamicoriginalgasInplace/result/${projectId}/${gasReservoirId}/${resultId}`),

  getChartTemplate: () =>
    dockerRequest.get('/common/charttemplates/MaterialBalance')
}

export const analyticMethodApi = {
  createByWellName: (data) =>
    dockerRequest.post(`/projectanalysis/analysismethods/${encodeURIComponent(data.wellName)}`, data),

  fitHistory: (data) =>
    dockerRequest.post('/projectanalysis/analysismethods/historyfitting', data),

  refitHistory: (data) =>
    dockerRequest.post('/projectanalysis/analysismethods/historyrefitting', data),

  getResult: (projectId, gasReservoirId, resultId, config = {}) =>
    dockerRequest.get(`/projectanalysis/analysismethods/${projectId}/${gasReservoirId}/${resultId}`, config),

  getSummaryChart: (projectId, gasReservoirId, config = {}) =>
    dockerRequest.get(`/projectanalysis/analysismethods/summary/chart/${projectId}/${gasReservoirId}`, config)
}

export const wellApi = {
  getWells: (projectId, gasReservoirId) =>
    dockerRequest.get(`/projectanalysis/${projectId}/${gasReservoirId}/wells`)
}

export const parametersApi = {
  getMinWaterGasRatio: (projectId, gasReservoirId) =>
    dockerRequest.get(
      `/projectanalysis/${projectId}/${gasReservoirId}/parameters/minmumWaterGasRatio`
    )
}

export const nodeApi = {
  getNode: (projectId, gasReservoirId, nodeType) =>
    dockerRequest.get(`/projectanalysis/node/${projectId}/${gasReservoirId}/${nodeType}`)
}

export default dockerRequest
