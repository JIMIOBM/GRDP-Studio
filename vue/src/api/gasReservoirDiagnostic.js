import request from '@/utils/request'

export const gasReservoirDiagnosticApi = {
  getContext: (projectId, gasReservoirId, storageId) =>
    request.get('/gas-reservoir-diagnostic/context', {
      params: {
        projectId,
        gasReservoirId,
        storageId
      }
    }),

  calculate: data =>
    request.post('/gas-reservoir-diagnostic/calculate', data, {
      timeout: 600000
    })
}