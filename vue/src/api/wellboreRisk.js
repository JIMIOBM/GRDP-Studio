import request from '@/utils/request'

const records = (base) => ({
  calculate: data => request.post(`${base}/calculate`, data, { timeout: 600000 }).then(r => r?.data ?? r),
  save: data => request.post(`${base}/records/save`, data, { timeout: 600000 }).then(r => r?.data ?? r),
  list: (projectId, gasReservoirId, wellName) => request.get(`${base}/records`, { params: { projectId, gasReservoirId, wellName } }).then(r => r?.data ?? r),
  detail: (id, projectId, gasReservoirId, wellName) => request.get(`${base}/records/${id}`, { params: { projectId, gasReservoirId, wellName } }).then(r => r?.data ?? r),
  delete: (id, projectId, gasReservoirId, wellName) => request.delete(`${base}/records/${id}`, { params: { projectId, gasReservoirId, wellName } })
})

export const liquidLoadingApi = records('/wellbore/liquid-loading')
export const hydrateApi = records('/wellbore/hydrate')
