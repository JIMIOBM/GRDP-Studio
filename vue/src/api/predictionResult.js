/**
 * predictionResult.js
 * 井组动态预测结果留存的本地后端接口（本项目 Spring Boot，走 /api）。
 * 每次预测完成（确认新曲线）后保存一份到本项目数据库（project_prediction_result 表），
 * 与依赖原平台数据库结果表解耦；同一任务重算按覆盖处理。历史任务不做迁移。
 */
import request from '@/utils/request'

export const predictionResultApi = {
  /** 保存/覆盖当前任务的结果留存。 */
  save: data => request.post('/prediction-results', data, { timeout: 30000 }),

  /** 已留存记录摘要（最近保存在前），供后续查询/导出使用。 */
  list: (projectId, gasReservoirId, storageId) => {
    const params = { projectId, gasReservoirId }
    if (storageId !== undefined && storageId !== null && Number(storageId) > 0) params.storageId = storageId
    return request.get('/prediction-results', { params })
  },

  /** 删除留存记录（幂等；返回实际删除行数）。 */
  remove: (projectId, gasReservoirId, predictionId, storageId) => {
    const params = { projectId, gasReservoirId, predictionId }
    if (storageId !== undefined && storageId !== null && Number(storageId) > 0) params.storageId = storageId
    return request.delete('/prediction-results', { params, timeout: 30000 })
  }
}

export default predictionResultApi
