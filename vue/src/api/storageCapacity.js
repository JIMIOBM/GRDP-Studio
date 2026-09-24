import request from '@/utils/request'

// 库容设计是储气库自身的属性：一个库一条，读写都必须带完整作用域三元组。
// projectId + gasReservoirId 限定原系统项目范围，storageId 才是本系统独立储气库。
const base = '/storage-capacity'

export const storageCapacityApi = {
  get: (projectId, gasReservoirId, storageId) =>
    request.get(base, { params: { projectId, gasReservoirId, storageId } }),
  save: data => request.post(`${base}/save`, data),
  remove: (projectId, gasReservoirId, storageId) =>
    request.delete(base, { params: { projectId, gasReservoirId, storageId } })
}
