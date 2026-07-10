/**
 * docker.js
 * 专门用于调用 Docker 服务（http://127.0.0.1:9919）的 axios 实例。
 * 开发时通过 vite.config.js 中的 /docker-api 代理转发，避免跨域。
 *
 * 路径：src/api/docker.js
 */
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

// 响应拦截：统一报错
dockerRequest.interceptors.response.use(
  (res) => res,
  (err) => {
    const msg = err.response?.data?.message || err.message || '请求失败'
    if (!err.config?.silentError) {
      ElMessage.error(`Docker 服务错误：${msg}`)
    }
    return Promise.reject(err)
  }
)

/* ===== 项目详情 ===== */
export const projectApi = {
    /**
     * GET /projects/{id}?projectId={id}
     * 返回项目、气藏、井以及已有分析节点等工程数据。
     */
    getProject: (projectId = 1) =>
        dockerRequest.get(`/projects/${projectId}`, {
            params: { projectId }
        })
}

/* ===== 水侵动态分析 ===== */
export const waterInvasionApi = {
  /**
   * POST /projectanalysis/waterinvasionanalysis
   * @param {Object} data
   * @param {number} data.gasReservoirId   - 气藏 ID
   * @param {number} data.projectId        - 项目 ID
   * @param {number} data.analysisType     - 分析类型（1=…）
   * @param {string[]} data.wellNames      - 井名列表
   * @param {number} data.waterGasRatioLimit - 气水比限值（-1 表示不限）
   */
  analyze: (data) =>
    dockerRequest.post('/projectanalysis/waterinvasionanalysis', data)
}

/* ===== 产量不稳定分析 - 解析法 ===== */
export const analyticMethodApi = {
  historyFitting: (data) =>
    dockerRequest.post('/projectanalysis/analysismethods/historyfitting', data),

  historyFittingByWell: (wellName, data) =>
    dockerRequest.post(`/projectanalysis/analysismethods/${encodeURIComponent(wellName)}/historyfitting`, data),

  historyRefitting: (data) =>
    dockerRequest.post('/projectanalysis/analysismethods/historyrefitting', data),

  getResult: (projectId, gasReservoirId, resultId) =>
    dockerRequest.get(`/projectanalysis/analysismethods/${projectId}/${gasReservoirId}/${resultId}`),

  getSummaryChart: (projectId, gasReservoirId) =>
    dockerRequest.get(`/projectanalysis/analysismethods/summary/chart/${projectId}/${gasReservoirId}`)
}

/* ===== 动态储量 - 物质平衡 ===== */
export const materialBalanceApi = {
  calc: (data) =>
    dockerRequest.post('/projectanalysis/dynamicoriginalgasInplace/mb/calc', data),

// /projectanalysis/dynamicoriginalgasInplace/averageFormationPressure/2/1/X-1
    getAverageFormationPressure: (projectId, gasReservoirId, wellName, options = {}) => {
        const params = {}
        return dockerRequest.get(
            `/projectanalysis/dynamicoriginalgasInplace/averageFormationPressure/${projectId}/${gasReservoirId}/${encodeURIComponent(wellName)}`,
            { ...(Object.keys(params).length ? { params } : {}), ...options }
        )
    },
    getResult: (projectId, gasReservoirId, dynamicOriginalGasInPlaceId, page = null, size = null, options = {}) => {
        const params = {}
        if (page !== null && page !== undefined) params.page = page
        if (size !== null && size !== undefined) params.size = size
        return dockerRequest.get(
            `/projectanalysis/dynamicoriginalgasInplace/result/${projectId}/${gasReservoirId}/${encodeURIComponent(dynamicOriginalGasInPlaceId)}`,
            { ...(Object.keys(params).length ? { params } : {}), ...options }
        )
    },
}

/* ===== 产量不稳定分析 - 典型曲线 ===== */
export const typicalCurveApi = {
  fitting: (data) =>
    dockerRequest.post('/projectanalysis/typicalcurve/fitting', data),

  getResult: (projectId, gasReservoirId, nodeId) =>
    dockerRequest.get(`/projectanalysis/typicalcurve/${projectId}/${gasReservoirId}/${nodeId}`)
}

/* ===== 井列表 ===== */
export const wellApi = {
    /**
     * GET /projectanalysis/{projectId}/{gasReservoirId}/wells
     * 返回 { wells: [...], fields: [...] }
     */
    getWells: (projectId, gasReservoirId) =>
        dockerRequest.get(`/projectanalysis/${projectId}/${gasReservoirId}/wells`)
}

/* ===== 分析参数 ===== */
export const parametersApi = {
    /**
     * GET /projectanalysis/{projectId}/{gasReservoirId}/parameters/minmumWaterGasRatio
     * 返回 { vaule: number, unitLabel: string }
     * 注意：后端字段名拼写为 vaule（原样保留，非笔误）
     *
     * @param {number|string} projectId
     * @param {number|string} gasReservoirId
     */
    getMinWaterGasRatio: (projectId, gasReservoirId) =>
        dockerRequest.get(
            `/projectanalysis/${projectId}/${gasReservoirId}/parameters/minmumWaterGasRatio`
        )
}

/* ===== 节点树 ===== */
export const nodeApi = {
    /**
     * GET /projectanalysis/node/{projectId}/{gasReservoirId}/{nodeType}
     */
    getNode: (projectId, gasReservoirId, nodeType) =>
        dockerRequest.get(`/projectanalysis/node/${projectId}/${gasReservoirId}/${nodeType}`)
}



export default dockerRequest
