import request from '@/utils/request'

export const storageMaterialBalanceApi = {
  source: (scope, signal) => request.get('/storage-material-balance/source', { params: scope, signal, timeout: 60000, silentError: true }),
  availability: (scope, signal) => request.get('/storage-material-balance/availability', { params: scope, signal, silentError: true }),
  aggregate: (scope, signal) => request.get('/storage-material-balance/aggregate', {
    params: scope, signal, timeout: 60000, silentError: true
  })
}
