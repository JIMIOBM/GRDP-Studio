import request from '@/utils/request'

function requireWellName (wellName) {
  if (typeof wellName !== 'string' || !wellName.trim()) {
    return Promise.reject(new Error('请先选择井，再读取PVT方案'))
  }
  return null
}

export const pvtStorageApi = {
  list: (projectId, gasReservoirId, wellName) => request.get('/pvt/records', {
    params: { projectId, gasReservoirId, wellName }
  }),
  getDetail: (pvtId, projectId, gasReservoirId, wellName) => {
    const invalid = requireWellName(wellName)
    if (invalid) return invalid
    return request.get(`/pvt/records/${pvtId}`, {
      params: { projectId, gasReservoirId, wellName }
    })
  },
  delete: (pvtId, projectId, gasReservoirId, wellName) =>
    request.delete(`/pvt/records/${pvtId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),
  save: data => request.post('/pvt/records/save', data)
}
