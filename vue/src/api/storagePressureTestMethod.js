import request from '@/utils/request'

export const storagePressureTestMethodApi = {
  list: scope => request.get('/storage-pressure-test/points', { params: scope, silentError: true }),
  save: (scope, dataset) => request.put('/storage-pressure-test/points', dataset, { params: scope, silentError: true })
}
