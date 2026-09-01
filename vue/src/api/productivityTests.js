import request from '@/utils/request'

export const productivityTestsApi = {
  list: (projectId, gasReservoirId, wellName, testMethod = 'modified-isochronal') =>
    request.get('/productivity-tests', {
      params: { projectId, gasReservoirId, wellName, testMethod }
    }),
  detail: (testId, resultType, pressureMethod) => request.get(`/productivity-tests/${testId}`, {
    params: { ...(resultType ? { resultType } : {}), ...(pressureMethod ? { pressureMethod } : {}) }
  }),
  calculateModifiedIsochronalExponential: data =>
    request.post('/productivity-tests/modified-isochronal/exponential/calculate', data),
  importFile: file => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/productivity-tests/import', formData, {
      timeout: 60000,
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  save: data => request.post('/productivity-tests/save', data)
}
