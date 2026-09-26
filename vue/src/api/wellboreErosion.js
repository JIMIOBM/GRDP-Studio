import request from '@/utils/request'

const base = '/wellbore/erosion'
const options = { timeout: 600000, headers: { 'Process-Env': 'prod' } }
const dataOf = response => response?.data ?? response
export const erosionApi = {
  properties: data => request.post(`${base}/properties`, data, options).then(dataOf),
  calculate: data => request.post(`${base}/calculate`, data, options).then(dataOf),
  save: data => request.post(`${base}/records/save`, data, options).then(dataOf),
  list: context => request.get(`${base}/records`, { params: context }).then(dataOf),
  detail: (id, context) => request.get(`${base}/records/${id}`, { params: context }).then(dataOf),
  delete: (id, context) => request.delete(`${base}/records/${id}`, { params: context })
}
