import request from '@/utils/request'

export const calculateWellboreTemperature = data => request.post('/wellbore/temperature/calculate', data, { timeout: 600000, headers: { 'Process-Env': 'prod' } }).then(response => response?.data?.result ?? response)
export const wellboreTemperatureApi = {
  calculate: calculateWellboreTemperature,
  list: (projectId, gasReservoirId, wellName) => request.get('/wellbore/temperature/records', { params: { projectId, gasReservoirId, wellName } }),
  detail: (id, projectId, gasReservoirId, wellName) => request.get(`/wellbore/temperature/records/${id}`, { params: { projectId, gasReservoirId, wellName } }),
  save: data => request.post('/wellbore/temperature/records/save', data),
  delete: (id, projectId, gasReservoirId, wellName) => request.delete(`/wellbore/temperature/records/${id}`, { params: { projectId, gasReservoirId, wellName } })
}
