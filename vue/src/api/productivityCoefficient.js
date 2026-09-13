import request from '@/utils/request'

export const productivityCoefficientApi = {
  list: scope => request.get('/productivity-coefficients/records', { params: scope }),
  detail: (id, scope) => request.get(`/productivity-coefficients/records/${id}`, { params: scope }),
  save: data => request.post('/productivity-coefficients/records/save', data)
}
