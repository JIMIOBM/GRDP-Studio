import request from '@/utils/request'

/** 储气库井筒损耗计算及记录接口。 */
export const wellboreLossApi = {
  calculate: data => request.post('/reservoir-loss/wellbore/calculate', data, {
    timeout: 120000,
    headers: { 'Process-Env': 'prod' }
  }),
  list: (projectId, gasReservoirId) => request.get('/reservoir-loss/wellbore/records', {
    params: { projectId, gasReservoirId }
  }),
  get: (id, projectId, gasReservoirId) => request.get(`/reservoir-loss/wellbore/${id}`, {
    params: { projectId, gasReservoirId }
  }),
  save: data => request.post('/reservoir-loss/wellbore/save', data, {
    timeout: 120000, headers: { 'Process-Env': 'prod' }
  }),
  rename: (id, name, projectId, gasReservoirId) => request.patch(`/reservoir-loss/wellbore/${id}/name`,
    { name }, { params: { projectId, gasReservoirId } }),
  delete: (id, projectId, gasReservoirId) => request.delete(`/reservoir-loss/wellbore/${id}`, {
    params: { projectId, gasReservoirId }
  })
}
