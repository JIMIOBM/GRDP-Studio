import request from '@/utils/request'

/** 理论计算稳定流专属新库接口；数值计算仍复用旧平台接口。 */
export const theoreticalProductivityApi = {
  // 读取一口井的井级默认参数；顶部首次计算会写这里，但不会生成“稳定流N”。
  getDefaultParameters: (projectId, gasReservoirId, wellName) =>
    request.get('/theoretical-productivity/stable/default-parameters', {
      params: { projectId, gasReservoirId, wellName }
    }),
  // 覆盖井级默认参数，供之后未选择PVT时继续计算。
  saveDefaultParameters: data =>
    request.post('/theoretical-productivity/stable/default-parameters', data),
  // 只读取稳定流摘要，供左侧目录生成“稳定流1、稳定流2……”节点。
  listStable: (projectId, gasReservoirId, wellName) =>
    request.get('/theoretical-productivity/stable', {
      params: { projectId, gasReservoirId, wellName }
    }),
  // 读取一次稳定流的注采输入、三种压力输出及各自IPR曲线快照。
  getStable: (stableId, projectId, gasReservoirId, wellName) =>
    request.get(`/theoretical-productivity/stable/${stableId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),
  // stableId为空时新建并分配编号；有stableId时覆盖当前注采方向的完整快照。
  saveStable: data => request.post('/theoretical-productivity/stable/save', data),
  // 只改左侧显示名称，不重新计算、不覆盖输入和结果。
  renameStable: (stableId, data) =>
    request.patch(`/theoretical-productivity/stable/${stableId}/name`, data),
  // 删除整次理论稳定流；数据库外键会级联清理方向、输入、输出和IPR。
  deleteStable: (stableId, projectId, gasReservoirId, wellName) =>
    request.delete(`/theoretical-productivity/stable/${stableId}`, {
      params: { projectId, gasReservoirId, wellName }
    })
}
