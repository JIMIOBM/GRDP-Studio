import request from '@/utils/request'
const base = '/allocation/nodal'
const data = r => r?.data ?? r
const options = { timeout: 600000, headers: { 'Process-Env': 'prod' } }
export const nodalApi = {
  sources: params => request.get(`${base}/sources`, { params }).then(data),
  calculate: input => request.post(`${base}/calculate`, input, options).then(data),
  save: input => request.post(`${base}/records/save`, input, options).then(data),
  list: params => request.get(`${base}/records`, { params }).then(data),
  detail: (id, params) => request.get(`${base}/records/${id}`, { params }).then(data),
  delete: (id, params) => request.delete(`${base}/records/${id}`, { params })
}
