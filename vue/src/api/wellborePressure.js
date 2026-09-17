import request from '@/utils/request'

export const wellborePressureApi = {
  calculate: data => request.post('/wellbore/pressure/calculate', data, {
    timeout: 600000
  }).then(response => response?.data?.result ?? response),

  list: (projectId, gasReservoirId, wellName) => request.get(
    '/wellbore/pressure/records',
    { params: { projectId, gasReservoirId, wellName } }
  ),

  detail: (id, projectId, gasReservoirId, wellName) => request.get(
    `/wellbore/pressure/records/${id}`,
    { params: { projectId, gasReservoirId, wellName } }
  ),

  save: data => request.post('/wellbore/pressure/records/save', data, {
    timeout: 600000,
    headers: { 'Process-Env': 'prod' }
  }),

  delete: (id, projectId, gasReservoirId, wellName) => request.delete(
    `/wellbore/pressure/records/${id}`,
    { params: { projectId, gasReservoirId, wellName } }
  )
}
