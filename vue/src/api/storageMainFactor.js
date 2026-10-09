import request from '@/utils/request'

/**
 * 库级主控因素分析。
 *
 * 参数在界面上是**界面口径**（MPa / ℃ / 10⁸m³ / % / MPa⁻¹），提交前由
 * `utils/storageMainFactor` 的 `toAppInputs` 换回后端口径（Pa / K / 小数 / 1/Pa）。
 * 后端只在组装原平台载荷时（`toolboxPayload`）再换算成平台的提交口径，
 * 前端不参与那一步。
 */
export const storageMainFactorApi = {
  context: (scope, signal) =>
    request.get('/storage-main-factor/context', { params: scope, signal, timeout: 60000, silentError: true }),

  // 原平台工具箱最长 60 秒，超时要给足
  calculate: (payload, signal) =>
    request.post('/storage-main-factor/calculate', payload, { signal, timeout: 600000, silentError: true }),

  save: payload => request.post('/storage-main-factor/save', payload, { silentError: true }),

  saved: (scope, signal) =>
    request.get('/storage-main-factor/saved', { params: scope, signal, silentError: true })
}
