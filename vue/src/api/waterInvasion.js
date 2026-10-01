import request from '@/utils/request'

// 计算、导入和所有结果读取统一走新后端；这里不再直接调用旧平台。
export const waterInvasionApi = {
  start: data => request.post('/water-invasion/tasks', data, { silentError: true, timeout: 40000 }),
  reconcile: (id, scope) => request.post(`/water-invasion/tasks/${id}/reconcile`, scope, { silentError: true, timeout: 40000 }),
  importLegacy: data => request.post('/water-invasion/imports', data, { silentError: true, timeout: 40000 }),
  records: params => request.get('/water-invasion/records', { params, silentError: true, timeout: 40000 }),
  detail: (id, params) => request.get(`/water-invasion/records/${id}`, { params, silentError: true, timeout: 40000 }),
  remove: (id, params) => request.delete(`/water-invasion/records/${id}`, { params, silentError: true, timeout: 40000 })
}

export const isWaterInvasionTaskActive = record => ['RUNNING', 'SAVING'].includes(record?.taskStatus)
export const waterInvasionRecordLabel = record => {
  const source = record.sourceType === 'IMPORT' ? '导入' : '计算'
  const time = record.finishedAt || record.savedAt || record.createdAt
  return `${source} · ${time ? new Date(time).toLocaleString('zh-CN', { hour12: false }) : '时间未知'}`
}
