import request from '@/utils/request'

export const gasPvtApi = {
  calculateCurveOne: (data) =>
    request.post('/pvt/gas/curve-one', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  calculateCurveTwo: (data) =>
    request.post('/pvt/gas/curve-two', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  calculateCurveThree: (data) =>
    request.post('/pvt/gas/curve-three', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    }),
  calculateViscosityCurve: (data) =>
    request.post('/pvt/gas/viscosity-curve', data, {
      timeout: 600000,
      headers: {
        'Process-Env': 'prod'
      }
    })
}
