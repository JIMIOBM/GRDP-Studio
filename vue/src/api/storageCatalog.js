import request from '@/utils/request'

// 列表由两个原系统ID限定范围；返回的storageId才用于后续损耗记录的库级归属。
export const storageCatalogApi = {
  list: (projectId, gasReservoirId) => request.get('/reservoir-loss/storages', { params: { projectId, gasReservoirId } }),
  candidateWells: (projectId, gasReservoirId) => request.get('/reservoir-loss/storages/candidate-wells', { params: { projectId, gasReservoirId } }),
  wells: (storageId, projectId, gasReservoirId) => request.get(`/reservoir-loss/storages/${storageId}/wells`, { params: { projectId, gasReservoirId } }),
  create: data => request.post('/reservoir-loss/storages', data)
}
