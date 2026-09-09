import request from '@/utils/request'

export const productivityComparisonApi = {
  records: params => request.get('/productivity-comparison/records', {
    params: { ...params, methods: params.methods.join(','), operationTypes: (params.operationTypes || ['production']).join(',') }
  }),
  calculate: data => request.post('/productivity-comparison/calculate', data, { timeout: 180000 }),
  compareDirections: data => request.post('/productivity-comparison/injection-production/calculate', data, { timeout: 180000 })
}
