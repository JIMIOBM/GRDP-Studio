import request from '@/utils/request'

export const productivityStorageApi = {
  listIsochronal: (projectId, gasReservoirId, wellName) => request.get('/productivity-tests', {
    params: { projectId, gasReservoirId, ...(wellName ? { wellName } : {}) }
  }),
  getIsochronal: (testId, projectId, gasReservoirId) => request.get(`/productivity-tests/${testId}`, {
    params: { projectId, gasReservoirId }
  }),
  saveIsochronal: data => request.post('/productivity-tests/isochronal/save', data)
}
