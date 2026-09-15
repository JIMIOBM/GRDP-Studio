import request from '@/utils/request'

/** 储气库井筒损耗计算及记录接口。 */
export const wellboreLossApi = {
  calculate: data => request.post('/reservoir-loss/wellbore/calculate', data, {
    timeout: 120000,
    headers: { 'Process-Env': 'prod' }
  }),
  list: (projectId, gasReservoirId, storageId) => request.get('/reservoir-loss/wellbore/records', {
    params: { projectId, gasReservoirId, storageId }
  }),
  get: (id, projectId, gasReservoirId, storageId) => request.get(`/reservoir-loss/wellbore/${id}`, {
    params: { projectId, gasReservoirId, storageId }
  }),
  save: data => request.post('/reservoir-loss/wellbore/save', data, {
    timeout: 120000, headers: { 'Process-Env': 'prod' }
  }),
  rename: (id, name, projectId, gasReservoirId, storageId) => request.patch(`/reservoir-loss/wellbore/${id}/name`,
    { name }, { params: { projectId, gasReservoirId, storageId } }),
  delete: (id, projectId, gasReservoirId, storageId) => request.delete(`/reservoir-loss/wellbore/${id}`, {
    params: { projectId, gasReservoirId, storageId }
  })
}
