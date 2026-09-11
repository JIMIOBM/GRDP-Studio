import request from '@/utils/request'

function requireWellName(wellName) {
  if (typeof wellName !== 'string' || !wellName.trim()) {
    return Promise.reject(new Error('请先选择井，再读取诊断方案'))
  }
  return null
}

export const diagnosticCurveApi = {
  calculate: (data) =>
    request.post('/diagnostic-curve/calculate', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),

  listRecords: (projectId, gasReservoirId, wellName) =>
    request.get('/diagnostic-curve/records', {
      params: { projectId, gasReservoirId, wellName }
    }),

  getRecord: (diagnosticId, projectId, gasReservoirId, wellName) => {
    const invalid = requireWellName(wellName)
    if (invalid) return invalid
    return request.get(`/diagnostic-curve/records/${diagnosticId}`, {
      params: { projectId, gasReservoirId, wellName }
    })
  },

  saveRecord: (data) =>
    request.post('/diagnostic-curve/records/save', data),

  deleteRecord: (diagnosticId, projectId, gasReservoirId, wellName) =>
    request.delete(`/diagnostic-curve/records/${diagnosticId}`, {
      params: { projectId, gasReservoirId, wellName }
    })
}