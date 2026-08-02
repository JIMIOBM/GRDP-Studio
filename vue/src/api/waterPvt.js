import request from '@/utils/request'

const calculationOptions = {
  timeout: 600000,
  headers: {
    'Process-Env': 'prod'
  }
}

export const waterPvtApi = {
  calculateCurveOne: (data) =>
    request.post('/pvt/water/curve-one', data, calculationOptions),
  calculateCurveTwo: (data) =>
    request.post('/pvt/water/curve-two', data, calculationOptions),
  calculateCurveThree: (data) =>
    request.post('/pvt/water/curve-three', data, calculationOptions),
  calculateViscosityCurve: (data) =>
    request.post('/pvt/water/viscosity-curve', data, calculationOptions)
}
