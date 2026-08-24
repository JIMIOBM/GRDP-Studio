import request from '@/utils/request'

export const pvtStorageApi = {
  list: (projectId, gasReservoirId, wellName) => request.get('/pvt/records', {
    params: { projectId, gasReservoirId, wellName }
  }),
  getDetail: (pvtId, projectId, gasReservoirId, wellName) =>
    request.get(`/pvt/records/${pvtId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),
  save: data => request.post('/pvt/records/save', data)
}
