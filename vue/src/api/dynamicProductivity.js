import request from '@/utils/request'

/**
 * 动态产能稳定流的新库接口。
 *
 * 注意：这里不负责稳定流数值计算。计算仍调用旧平台 docker 接口；只有用户明确
 * 点击“保存”后，页面才通过这里把输入快照、三种输出和 IPR 曲线写入新六表。
 */
export const dynamicProductivityApi = {
  // 读取/更新井级唯一默认参数；这两个接口不会创建“稳定流N”。
  getDefaultParameters: (projectId, gasReservoirId, wellName) =>
    request.get('/dynamic-productivity/stable/default-parameters', {
      params: { projectId, gasReservoirId, wellName }
    }),
  saveDefaultParameters: data =>
    request.post('/dynamic-productivity/stable/default-parameters', data),
  // 不稳定流拥有独立的默认参数入口，额外保存孔隙度、总压缩系数和流动时间。
  getUnstableDefaultParameters: (projectId, gasReservoirId, wellName) =>
    request.get('/dynamic-productivity/unstable/default-parameters', {
      params: { projectId, gasReservoirId, wellName }
    }),
  saveUnstableDefaultParameters: data =>
    request.post('/dynamic-productivity/unstable/default-parameters', data),
  // 左侧目录只展示新库中已经保存的稳定流记录。
  listStable: (projectId, gasReservoirId, wellName) => request.get('/dynamic-productivity/stable', {
    params: { projectId, gasReservoirId, wellName }
  }),
  getStable: (stableId, projectId, gasReservoirId, wellName) =>
    request.get(`/dynamic-productivity/stable/${stableId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),
  // stableId 为空时创建新记录；不为空时覆盖该记录的当前注采方向。
  saveStable: data => request.post('/dynamic-productivity/stable/save', data),
  renameStable: (stableId, data) =>
    request.patch(`/dynamic-productivity/stable/${stableId}/name`, data),
  deleteStable: (stableId, projectId, gasReservoirId, wellName) =>
    request.delete(`/dynamic-productivity/stable/${stableId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),

  // 不稳定流的公式、物性工具箱调用和IPR离散均在后端执行。
  // 拟压力要按 IPR 压力点执行多次“calc后取值”，单次计算允许更长超时。
  calculateUnstable: data => request.post('/dynamic-productivity/unstable/calculate', data, { timeout: 180000 }),
  listUnstable: (projectId, gasReservoirId, wellName) =>
    request.get('/dynamic-productivity/unstable', { params: { projectId, gasReservoirId, wellName } }),
  getUnstable: (unstableId, projectId, gasReservoirId, wellName) =>
    request.get(`/dynamic-productivity/unstable/${unstableId}`, {
      params: { projectId, gasReservoirId, wellName }
    }),
  saveUnstable: data => request.post('/dynamic-productivity/unstable/save', data),
  renameUnstable: (unstableId, data) =>
    request.patch(`/dynamic-productivity/unstable/${unstableId}/name`, data),
  deleteUnstable: (unstableId, projectId, gasReservoirId, wellName) =>
    request.delete(`/dynamic-productivity/unstable/${unstableId}`, {
      params: { projectId, gasReservoirId, wellName }
    })
}
