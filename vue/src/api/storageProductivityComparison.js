import request from '@/utils/request'

// 库级统计由后端校验成员井、读取记录并计算，前端只传选井和对比条件。
export const storageProductivityComparisonApi = {
  compareDirections: data => request.post('/storage-productivity-comparison/injection-production/calculate', data,
    { timeout: 180000, silentError: true }),
  calculateMethods: data => request.post('/storage-productivity-comparison/multi-method/calculate', data,
    { timeout: 180000, silentError: true }),
  calculate: data => request.post('/storage-productivity-comparison/multi-period/calculate', data,
    { timeout: 180000, silentError: true })
}
