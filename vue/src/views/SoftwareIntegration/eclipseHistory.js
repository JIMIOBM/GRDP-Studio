import { batchBaselineAvailable } from './eclipseBatch.js'
import { validCompositionalMetadata } from './eclipseCompositional.js'
export const historyRunAvailable = run => run?.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' && !run.resultExpired
  && run.runType === 'eclipse' && (run.result?.modelKind === 'eclipse_100' && run.result?.schemaVersion === 'eclipse-summary-result/1' || run.result?.modelKind === 'eclipse_300' && run.result?.schemaVersion === 'eclipse-summary-result/2' && validCompositionalMetadata(run.result.simulation))
  && run.result.resultContract === 'VALID_FULL' && ['problems','errors','bugs'].every(key => run.result.eclEnd?.[key] === 0) && Array.isArray(run.result?.summary?.series)
export const historyBaselineAvailable = batchBaselineAvailable
export const historyIdentity = row => JSON.stringify(row.vectorIndex == null ? [row.well,row.keyword,row.unit] : [row.vectorIndex,row.well,row.keyword,row.unit])
export function buildHistoryRequest({ run, name, sourceFile, csv }) {
  if (!historyBaselineAvailable(run)) throw new Error('请先选择当前版本、未过期的无参数 E100 或组分 E300 完整基线。')
  if (typeof name !== 'string' || !name.trim() || name.trim().length > 100 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) throw new Error('数据名称须为1–100个字符。')
  if (typeof sourceFile !== 'string' || !/\.csv$/i.test(sourceFile) || sourceFile.length > 128 || sourceFile !== sourceFile.trim() || /[/\\:\u0000-\u001f\u007f-\u009f]/.test(sourceFile)) throw new Error('请选择有效的 CSV 文件。')
  if (typeof csv !== 'string' || !csv || new TextEncoder().encode(csv).length > 1048576) throw new Error('CSV须为不超过1MiB的UTF-8文本。')
  return { name: name.trim(), baselineRunId: run.id, sourceFile, csv }
}
export const validHistoryMetadata = (metadata, version) => metadata && Number.isSafeInteger(metadata.id) && metadata.id > 0
  && metadata.modelVersionId === version && Number.isSafeInteger(metadata.baselineRunId) && metadata.baselineRunId > 0
  && Number.isSafeInteger(metadata.observationCount) && metadata.observationCount > 0 && metadata.observationCount <= 5000
  && typeof metadata.sourceFile === 'string' && typeof metadata.sourceSha256 === 'string' && /^[a-f0-9]{64}$/.test(metadata.sourceSha256)
export function validHistoryDataset(dataset, version) {
  return validHistoryMetadata(dataset?.metadata, version) && typeof dataset.csv === 'string'
    && Array.isArray(dataset.observations) && dataset.observations.length === dataset.metadata.observationCount
    && (dataset.observations.every(row=>row.vectorIndex==null) || dataset.observations.every(row=>Number.isSafeInteger(row.vectorIndex)&&row.vectorIndex>=0&&row.vectorIndex<=999999))
    && dataset.observations.every(row => typeof row.well === 'string' && typeof row.keyword === 'string' && typeof row.unit === 'string'
      && Number.isFinite(row.timeDays) && row.timeDays >= 0 && Number.isFinite(row.value) && Number.isSafeInteger(row.sourceLine) && row.sourceLine > 1)
}
export function validHistoryEvaluation(evaluation, dataset, run) {
  if (!historyRunAvailable(run) || !validHistoryDataset(dataset, run.modelVersionId) || evaluation?.runId !== run.id
    || evaluation.dataset?.id !== dataset.metadata.id || evaluation.dataset?.sourceSha256 !== dataset.metadata.sourceSha256
    || evaluation.dataset?.modelVersionId !== run.modelVersionId || !Array.isArray(evaluation.comparison?.points)
    || evaluation.comparison.points.length !== dataset.observations.length || !Array.isArray(evaluation.comparison.metrics)) return false
  if(run.result.modelKind==='eclipse_300' && dataset.observations.some(row=>!Number.isSafeInteger(row.vectorIndex)))return false
  const counts = new Map()
  for (const [i, point] of evaluation.comparison.points.entries()) {
    const observation = dataset.observations[i]
    if (historyIdentity(point) !== historyIdentity(observation) || point.timeDays !== observation.timeDays || point.observed !== observation.value || point.sourceLine !== observation.sourceLine) return false
    const series = run.result.summary.series.find(series => series.keyword === point.keyword && (series.objectName || '') === point.well && series.unit === point.unit && (point.vectorIndex == null || series.vectorIndex === point.vectorIndex))
    const value = (Array.isArray(series?.points) ? series.points.find(value => value.timeDays === point.timeDays)?.value : null) ?? null
    if (point.simulated !== value || point.difference !== (value == null ? null : value - point.observed)
      || typeof point.reason !== 'string' || (value == null ? !point.reason : point.reason !== '')) return false
    const key = historyIdentity(point), group = counts.get(key) || { observed: 0, matched: 0 }
    group.observed++; if (value != null) group.matched++; counts.set(key, group)
  }
  if (evaluation.comparison.metrics.length !== counts.size) return false
  const seen = new Set()
  return evaluation.comparison.metrics.every(metric => {
    const key = historyIdentity(metric), count = counts.get(key)
    if (!count || seen.has(key)) return false
    seen.add(key)
    return metric.observedCount === count.observed && metric.matchedCount === count.matched && metric.missingCount === count.observed - count.matched
      && metric.coverage === count.matched / count.observed && ['mae','rmse','bias','mape'].every(key => metric[key] === null || Number.isFinite(metric[key]))
      && (count.matched > 0 ? ['mae','rmse','bias'].every(key => Number.isFinite(metric[key])) : ['mae','rmse','bias','mape'].every(key => metric[key] === null))
      && ['mae','rmse','mape'].every(key => metric[key] === null || metric[key] >= 0)
      && (metric.mapeOverflowCount === 0 && metric.mapeCount > 0 || metric.mape === null)
      && Number.isSafeInteger(metric.mapeCount) && metric.mapeCount >= 0 && metric.mapeCount <= metric.matchedCount
      && Number.isSafeInteger(metric.mapeOverflowCount) && metric.mapeOverflowCount >= 0 && metric.mapeOverflowCount <= metric.mapeCount
  })
}
export function historyCsvExport(evaluation) {
  const cell = value => {
    const text = String(value ?? ''), safe = typeof value === 'string' && /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
    return `"${safe.replaceAll('"', '""')}"`
  }
  const indexed=evaluation.comparison.points.some(p=>p.vectorIndex!=null)
  return '\uFEFF' + [[...(indexed?['原始列（从1计）']:[]),'井或对象','指标','单位','时间(天)','观察值','计算值','误差(计算-观察)','源行','缺失原因'],
    ...evaluation.comparison.points.map(p => [...(indexed?[p.vectorIndex+1]:[]),p.well,p.keyword,p.unit,p.timeDays,p.observed,p.simulated,p.difference,p.sourceLine,p.reason])]
    .map(row => row.map(cell).join(',')).join('\r\n')
}
