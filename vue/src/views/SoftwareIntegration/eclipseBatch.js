import { buildPvtw, pvtwRecord, pvtwLabels, pvtwUnit, validPvtw } from './eclipsePvtw.js'
import { buildOilPvt, oilPvtRecord, validOilPvt } from './eclipseOilPvt.js'
import { buildGasPvt, gasPvtRecord, validGasPvt } from './eclipseGasPvt.js'
import { buildRelperm, relpermContext, validRelperm } from './eclipseRelperm.js'
import { gridRegionResponse, validGridRegionParameters } from './eclipseGridRegion.js'

export const batchStatusLabels = { QUEUED: '排队中', RUNNING: '计算中', SUCCEEDED: '全部完成', PARTIAL_SUCCEEDED: '部分完成', FAILED: '失败', CANCELLED: '已取消' }
export const batchPending = batch => ['QUEUED', 'RUNNING'].includes(batch?.status)
export const batchBaselineAvailable = run => Number.isSafeInteger(run?.id) && Number.isSafeInteger(run?.modelVersionId)
  && run.runType === 'eclipse' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'
  && run.parameters == null && !run.resultExpired && run.result?.schemaVersion === 'eclipse-summary-result/1' && run.result.modelKind === 'eclipse_100'
  && ['problems', 'errors', 'bugs'].every(key => run.result.eclEnd?.[key] === 0)
export function batchChoices(inspection) {
  const options = []
  if (pvtwRecord(inspection)) for (const [field, label] of Object.entries(pvtwLabels)) options.push({ kind: field, label: `水物性 · ${label}`, unit: pvtwUnit(inspection.unitSystem, field) })
  if (oilPvtRecord(inspection)) options.push({ kind: 'PVDO', label: '死油黏度乘数', unit: '倍' })
  if (gasPvtRecord(inspection)) options.push({ kind: 'PVDG', label: '干气黏度乘数', unit: '倍' })
  if (relpermContext(inspection)) options.push({ kind: 'SWFN', label: '水相 Krw 乘数', unit: '倍' }, { kind: 'SOF3', label: '油相两列相渗乘数', unit: '倍' })
  return options
}
export function buildBatchCase({ run, inspection, kind, value, name }) {
  if (!batchBaselineAvailable(run)) throw new Error('请先选择当前版本、结果未过期的完整无参数 E100 基线。')
  const label = typeof name === 'string' ? name.trim() : ''
  if (!label || label.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(label)) throw new Error('方案名称须为1–80个字符。')
  if (!batchChoices(inspection).some(option => option.kind === kind)) throw new Error('原模型没有可编辑的对应物性表。')
  const args = { run, inspection, multiplier: value, targetValue: value, field: kind, keyword: kind }
  const parameters = kind === 'PVDO' ? buildOilPvt(args) : kind === 'PVDG' ? buildGasPvt(args)
    : ['SWFN', 'SOF3'].includes(kind) ? buildRelperm(args) : buildPvtw(args)
  return { name: label, parameters }
}
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
export const sameBatchCase = (a, b) => JSON.stringify(canonical(a.parameters)) === JSON.stringify(canonical(b.parameters))
export function buildGridBatchCase({ run, name, parameters }) {
  if (!batchBaselineAvailable(run) || parameters?.baselineRunId !== run.id || !validGridRegionParameters(parameters)) throw new Error('区域方案必须来自当前完整无参数 E100 基线。')
  const label = typeof name === 'string' ? name.trim() : ''
  if (!label || label.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(label)) throw new Error('方案名称须为1–80个字符。')
  return { name: label, parameters: JSON.parse(JSON.stringify(parameters)) }
}
export function buildBatchRequest({ run, name, cases, requestKey }) {
  if (!batchBaselineAvailable(run)) throw new Error('完整基线不可用。')
  const request = { requestKey, name: typeof name === 'string' ? name.trim() : '', baselineRunId: run.id, cases: JSON.parse(JSON.stringify(cases)) }
  if (!validBatchRequest(request)) throw new Error('需要2–8个名称和参数各不重复、使用同一基线的有效方案。')
  return request
}
export function validBatchRequest(request) {
  const keys = ['requestKey', 'name', 'baselineRunId', 'cases']
  if (!request || Object.keys(request).length !== 4 || !keys.every(key => Object.hasOwn(request, key))
    || typeof request.requestKey !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(request.requestKey)
    || typeof request.name !== 'string' || !request.name.trim() || request.name !== request.name.trim() || request.name.length > 100
    || /[\u0000-\u001f\u007f-\u009f]/.test(request.name) || !Number.isSafeInteger(request.baselineRunId) || request.baselineRunId <= 0
    || !Array.isArray(request.cases) || request.cases.length < 2 || request.cases.length > 8 || new TextEncoder().encode(JSON.stringify(request)).length > 262144) return false
  return request.cases.every((item, index) => item && Object.keys(item).length === 2 && typeof item.name === 'string'
    && item.name === item.name.trim() && item.name.length > 0 && item.name.length <= 80 && !/[\u0000-\u001f\u007f-\u009f]/.test(item.name)
    && item.parameters?.baselineRunId === request.baselineRunId && [validPvtw, validOilPvt, validGasPvt, validRelperm, validGridRegionParameters].some(validate => validate(item.parameters))
    && request.cases.slice(0, index).every(old => old.name.toLowerCase() !== item.name.toLowerCase() && !sameBatchCase(old, item)))
}
export function batchResponse(batch) {
  if (!batchBaselineAvailable(batch?.baseline) || batch.baseline.id !== batch.baselineRunId || batch.baseline.modelVersionId !== batch.modelVersionId) return { rows: [], message: '基线缺失或已过期，无法对比。' }
  const rows = [], messages = []
  for (const member of batch.members || []) {
    const run = member.run
    if (run?.modelVersionId !== batch.modelVersionId || run.status !== 'SUCCEEDED' || run.resultContract !== 'VALID_FULL' || run.resultExpired || !run.result) continue
    const response = gridRegionResponse(run.result.summary, batch.baseline.result.summary, ['WBHP','WOPR','WWPR','WGPR','WLPR','WOIR','WWIR','WGIR','WOPT','WWPT','WGPT','WOIT','WWIT','WGIT'])
    if (!response.available) messages.push(`${member.name}：${response.message}`)
    else rows.push(...response.rows.map(row => ({ ...row, name: member.name, runId: run.id })))
  }
  return { rows, message: messages.join('；') || (rows.length ? '' : '成功方案产生实际结果后显示对比。') }
}
