import request from '@/utils/request'

/**
 * 库级主控因素分析。
 *
 * 时序数据（日期 / 累产气量 / 地层压力）由页面**导入**，存在本模块自己的表里；
 * 计算时后端逐点调原平台工具箱，只替换累产气量。
 *
 * 参数在界面上是**界面口径**（MPa / ℃ / 10⁸m³ / % / MPa⁻¹），提交前由
 * `utils/storageMainFactor` 的 `toAppInputs` 换回后端口径（Pa / K / 小数 / 1/Pa）。
 * 后端只在组装原平台载荷时（`toolboxPayload`）再换算成平台的提交口径，
 * 前端不参与那一步。
 */
export const storageMainFactorApi = {
  /** 本库已导入的时序数据（按日期升序）；没导入过返回空表。 */
  seriesData: (scope, signal) =>
    request.get('/storage-main-factor/series-data', { params: scope, signal, timeout: 60000, silentError: true }),

  /** 导入时序数据，**整体替换**本库已有的一份；返回写入的点数。 */
  importSeriesData: (payload, signal) =>
    request.post('/storage-main-factor/series-data', payload, { signal, timeout: 60000, silentError: true }),

  // 逐点调原平台工具箱：20 个点串行，单点几十秒都可能，超时要给足
  series: (payload, signal) =>
    request.post('/storage-main-factor/series', payload, { signal, timeout: 600000, silentError: true }),

  save: payload => request.post('/storage-main-factor/save', payload, { silentError: true }),

  saved: (scope, signal) =>
    request.get('/storage-main-factor/saved', { params: scope, signal, silentError: true })
}
