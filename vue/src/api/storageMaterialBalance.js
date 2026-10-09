import request from '@/utils/request'

export const storageMaterialBalanceApi = {
  source: (scope, signal) => request.get('/storage-material-balance/source', { params: scope, signal, timeout: 60000, silentError: true }),
  availability: (scope, signal) => request.get('/storage-material-balance/availability', { params: scope, signal, silentError: true }),
  latest: (scope, signal) => request.get('/storage-material-balance/saved', { params: scope, signal, silentError: true }),
  save: (scope, signal) => request.post('/storage-material-balance/saved', scope, { signal, timeout: 60000, silentError: true }),
  aggregate: (scope, signal) => {
    const params = { ...scope }
    if (Array.isArray(params.wellIds)) {
      if (params.wellIds.length) params.wellIds = params.wellIds.join(',')
      else delete params.wellIds
    }
    return request.get('/storage-material-balance/aggregate', {
      params, signal, timeout: 60000, silentError: true
    })
  }
}
