import request from '@/utils/request'

export const storagePressureGradientApi = {
  list: scope => request.get('/storage-pressure-gradient/points', { params: scope, silentError: true }),
  save: (scope, dataset) => request.put('/storage-pressure-gradient/points', dataset, { params: scope, silentError: true })
}
