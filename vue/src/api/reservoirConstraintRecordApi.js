// D:\shiyou\GRDP-Studio\vue\src\api\reservoir\reservoirConstraintRecordApi.js
/**
 * 库级(storageId 维度)多周期预测约束条件记录 API，对应数据表 storage_prediction_constraint。
 */
const BASE_PATH = '/api/solution-generation/reservoir-constraint-records'

async function request(method, suffix = '', { body, params } = {}) {
  const query = params ? `?${new URLSearchParams(
    Object.entries(params).map(([key, value]) => [key, String(value ?? '')])
  )}` : ''
  const path = `${BASE_PATH}${suffix}${query}`
  let response
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      cache: method === 'GET' ? 'no-store' : 'default',
      headers: { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) })
    })
  } catch (error) {
    throw new Error('连接 Spring Boot 失败：' + (error?.message || '网络错误'))
  }
  const raw = await response.text()
  let result = null
  if (raw) {
    try { result = JSON.parse(raw) } catch {
      throw new Error(`HTTP ${response.status} 返回非 JSON 内容`)
    }
  }
  if (!response.ok || result?.success === false || (typeof result?.code === 'number' && result.code >= 400)) {
    throw new Error(result?.message || result?.msg || result?.error || `HTTP ${response.status}`)
  }
  return result && Object.prototype.hasOwnProperty.call(result, 'data') ? result.data : result
}

export const reservoirConstraintRecordApi = {
  save: (payload) => request('POST', '/save', { body: payload }),
  update: (id, payload) => request('PUT', `/${encodeURIComponent(String(id))}`, { body: payload }),
  list: (projectId, gasReservoirId, storageId) =>
    request('GET', '', { params: { projectId, gasReservoirId, storageId } }),
  detail: (id) => request('GET', `/${encodeURIComponent(String(id))}`),
  delete: (id, projectId, gasReservoirId, storageId) =>
    request('DELETE', `/${encodeURIComponent(String(id))}`, { params: { projectId, gasReservoirId, storageId } })
}