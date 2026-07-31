import request from '@/utils/request'

export const rockPvtApi = {
  calculateCurveOne: (data) =>
    request.post('/pvt/rock/curve-one', data, {
      timeout: 600000,
      headers: { 'Process-Env': 'prod' }
    }),
  calculateCurveTwo: (data) =>
    request.post('/pvt/rock/curve-two', data, {
      timeout: 600000,
      headers: { 'Process-Env': 'prod' }
    })
}