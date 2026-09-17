import request from '@/utils/request'

export const geologicalLossApi = {
  listRecords: (projectId, gasReservoirId, storageId) => request.get('/reservoir-loss/records', { params: { projectId, gasReservoirId, storageId } }),
  listMicroscopic: (projectId, gasReservoirId, storageId) => request.get('/reservoir-loss/microscopic', { params: { projectId, gasReservoirId, storageId } }),
  listEscape: (projectId, gasReservoirId, storageId) => request.get('/reservoir-loss/escape', { params: { projectId, gasReservoirId, storageId } }),
  // 微观损耗需要后端继续调用原平台PVT工具箱，显式保持与现有PVT模块一致的运行环境。
  calculateMicroscopic: data => request.post('/reservoir-loss/microscopic/calculate', data, {
    timeout: 60000,
    headers: { 'Process-Env': 'prod' }
  }),
  getMicroscopic: (id, projectId, gasReservoirId, storageId) => request.get(`/reservoir-loss/microscopic/${id}`, { params: { projectId, gasReservoirId, storageId } }),
  saveMicroscopic: data => request.post('/reservoir-loss/microscopic/save', data),
  calculateEscape: data => request.post('/reservoir-loss/escape/calculate', data),
  getEscape: (id, projectId, gasReservoirId, storageId) => request.get(`/reservoir-loss/escape/${id}`, { params: { projectId, gasReservoirId, storageId } }),
  saveEscape: data => request.post('/reservoir-loss/escape/save', data),
  renameRecord: (type, id, name, projectId, gasReservoirId, storageId) => request.patch(`/reservoir-loss/${type}/${id}/name`, { name }, { params: { projectId, gasReservoirId, storageId } }),
  deleteRecord: (type, id, projectId, gasReservoirId, storageId) => request.delete(`/reservoir-loss/${type}/${id}`, { params: { projectId, gasReservoirId, storageId } })
}
