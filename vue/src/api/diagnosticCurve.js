import request from '@/utils/request'

export const diagnosticCurveApi = {
  calculate: (data) =>
    request.post('/diagnostic-curve/calculate', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    })
}