import request from '@/utils/request'

// 地面损耗使用独立接口和记录，不与井筒损耗混存。
const base = '/reservoir-loss/surface'
const calculationOptions = { timeout: 120000, headers: { 'Process-Env': 'prod' } }
export const surfaceLossApi = {
  calculate: data => request.post(`${base}/calculate`, data, calculationOptions),
  save: data => request.post(`${base}/save`, data, calculationOptions),
  list: (projectId, gasReservoirId, storageId) => request.get(`${base}/records`, { params: { projectId, gasReservoirId, storageId } }),
  get: (id, projectId, gasReservoirId, storageId) => request.get(`${base}/${id}`, { params: { projectId, gasReservoirId, storageId } }),
  rename: (id, name, projectId, gasReservoirId, storageId) => request.patch(`${base}/${id}/name`, { name }, { params: { projectId, gasReservoirId, storageId } }),
  delete: (id, projectId, gasReservoirId, storageId) => request.delete(`${base}/${id}`, { params: { projectId, gasReservoirId, storageId } })
}
