import request from '@/utils/request'

const base = '/storage-network'

export const storageNetworkApi = {
  topology: params => request.get(`${base}/topology`, { params }),
  saveTopology: data => request.put(`${base}/topology`, data),
  correlationSource: params => request.get(`${base}/correlation/source`, { params })
}
