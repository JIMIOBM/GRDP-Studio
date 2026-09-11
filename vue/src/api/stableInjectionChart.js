import request from '@/utils/request'

export const getInjectionPotentials = data => request.post('/stable-injection-chart/potentials', data, { timeout: 240000 })
