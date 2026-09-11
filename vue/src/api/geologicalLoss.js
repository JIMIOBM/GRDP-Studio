import request from '@/utils/request'

export const geologicalLossApi = {
  listRecords: (projectId, gasReservoirId) => request.get('/reservoir-loss/records', { params: { projectId, gasReservoirId } }),
  listMicroscopic: (projectId, gasReservoirId) => request.get('/reservoir-loss/microscopic', { params: { projectId, gasReservoirId } }),
  listEscape: (projectId, gasReservoirId) => request.get('/reservoir-loss/escape', { params: { projectId, gasReservoirId } }),
  // 微观损耗需要后端继续调用原平台PVT工具箱，显式保持与现有PVT模块一致的运行环境。
  calculateMicroscopic: data => request.post('/reservoir-loss/microscopic/calculate', data, {
    timeout: 60000,
    headers: { 'Process-Env': 'prod' }
  }),
  getMicroscopic: (id, projectId, gasReservoirId) => request.get(`/reservoir-loss/microscopic/${id}`, { params: { projectId, gasReservoirId } }),
  saveMicroscopic: data => request.post('/reservoir-loss/microscopic/save', data),
  calculateEscape: data => request.post('/reservoir-loss/escape/calculate', data),
  getEscape: (id, projectId, gasReservoirId) => request.get(`/reservoir-loss/escape/${id}`, { params: { projectId, gasReservoirId } }),
  saveEscape: data => request.post('/reservoir-loss/escape/save', data),
  renameRecord: (type, id, name) => request.patch(`/reservoir-loss/${type}/${id}/name`, { name }),
  deleteRecord: (type, id, projectId, gasReservoirId) => request.delete(`/reservoir-loss/${type}/${id}`, { params: { projectId, gasReservoirId } })
}
