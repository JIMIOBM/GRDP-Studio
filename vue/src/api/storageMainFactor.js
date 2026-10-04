import request from '@/utils/request'

/**
 * 库级主控因素分析。
 *
 * 后端返回原平台口径的物质平衡入参（Pa / K / 10⁸m³ / 小数 / 1/Pa），
 * 四因素的理论值与实际值则已经是工程单位（MPa / 10⁸m³ / 小数）。
 * 单位换算全部在后端完成，前端不再换算一次。
 */
export const storageMainFactorApi = {
  context: (scope, signal) =>
    request.get('/storage-main-factor/context', { params: scope, signal, timeout: 60000, silentError: true }),

  calculate: (payload, signal) =>
    request.post('/storage-main-factor/calculate', payload, { signal, timeout: 600000, silentError: true })
}
