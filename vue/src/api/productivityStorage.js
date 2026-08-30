import request from '@/utils/request'

export const productivityStorageApi = {
  listIsochronal: (projectId, gasReservoirId, wellName) => request.get('/isochronal-productivity-tests', {
    params: { projectId, gasReservoirId, ...(wellName ? { wellName } : {}) }
  }),
  getIsochronal: (testId, projectId, gasReservoirId) => request.get(`/isochronal-productivity-tests/${testId}`, {
    params: { projectId, gasReservoirId }
  }),
  saveIsochronal: data => request.post('/isochronal-productivity-tests/save', data)
}
