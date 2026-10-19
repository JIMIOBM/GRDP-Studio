import { restartForecastSeries, compareRestartForecasts } from './eclipseRestartForecast.js'
import { compositionalResult } from './eclipseCompositional.js'
import { compositionalSaveMatchesInspection, compositionalSaveMatchesResult, compositionalForecastMatches, compositionalForecastEvidence, validCompositionalPortableContext } from './eclipseCompositionalForecast.js'

const keywords = new Set(['FOPT','FOPR','FPR','FGPT','FGIT','FWPT','FWIT','WOPT','WOPR','WGPR','WWIR','WBHP'])
const cumulative = new Set(['FOPT','FGPT','FGIT','FWPT','FWIT','WOPT'])
const full = run => run?.runType === 'eclipse' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'
const identity = s => JSON.stringify([s.vectorIndex ?? null, s.keyword, s.objectName ?? null, s.unit])
const sameModel = (one, two) => one?.modelId === two?.modelId && one?.modelVersionId === two?.modelVersionId
const invalid = reason => ({ valid: false, reason, metrics: [], reports: [], forecasts: [] })
export const forecastDate = (start, days) => new Date(Date.parse(start + 'T00:00:00Z') + days * 864e5).toISOString().slice(0, 10)

export function forecastPresentation({ kind, source, catalog, history, run, other, inspection, portable = false }) {
  let cutoff, report, startDate, endTimeDays, reports, futures, historical, historyId, sourceFile
  if (kind === 'e100') {
    if (!restartForecastSeries({ source, history, run, inspection }).length) return invalid('历史来源或预测实际时刻尚未核对，暂不显示接续看板。')
    if (other) { const c = compareRestartForecasts({ source, history, first: run, second: other, inspection }); if (!c.valid) return invalid(c.reason) }
    cutoff = run.parameters.restartTimeDays; report = run.parameters.restartReport; startDate = source.catalog.startDate
    reports = source.catalog.reports.map(r => ({ reportNumber: r.reportNumber, timeDays: r.timeDays, date: r.date, predictable: source.forecastInputs.some(f => f.restartReport === r.reportNumber) }))
    futures = [run, other].filter(Boolean).map(r => ({ run: r, label: r.parameters.forecastDataFile }))
    historical = history.result.summary.series; historyId = history.id; sourceFile = source.catalog.caseName
    endTimeDays = Math.max(...futures.map(f => f.run.result.summary.series[0].points.at(-1).timeDays))
  } else if (kind === 'e300') {
    if (portable) {
      const context = inspection?.compositionalRestartContext
      if (!full(run) || !validCompositionalPortableContext(context, inspection) || JSON.stringify(context.historyCatalog) !== JSON.stringify(catalog) || !compositionalResult(run, inspection) || !compositionalForecastEvidence(run.result, catalog, inspection.wellNames)?.complete) return invalid('导入模型的保存点或完整预测尚未核对。')
      historical = []; historyId = null
      futures = [{ run, label: context.targetGasRate == null ? '导入的原计划续算' : `导入的 ${context.targetGasRate} Mscf/day 方案` }]
    } else {
      if (!full(history) || history.parameters != null || !compositionalResult(history, inspection) || !compositionalSaveMatchesInspection(catalog, inspection) || !compositionalSaveMatchesResult(catalog, history.result)) return invalid('同版本完整历史与保存点来源尚未核对。')
      historical = history.result.summary.series; historyId = history.id
      if (run.id === history.id && run.parameters == null) futures = []
      else {
        futures = [run, other].filter(Boolean).map(r => ({ run: r, label: r.parameters?.targetGasRate == null ? '沿原计划续算' : `产气目标 ${r.parameters.targetGasRate} Mscf/day` }))
        if (futures.some(f => !full(f.run) || !sameModel(f.run, history) || f.run.parameters?.historyRunId !== history.id || !compositionalForecastMatches(f.run.parameters, catalog, inspection) || !compositionalResult(f.run, inspection) || !compositionalForecastEvidence(f.run.result, catalog, inspection.wellNames)?.complete)) return invalid('预测必须来自同版本、同一历史保存点及完整成功结果。')
        if (other && (other.id === run.id || other.parameters.saveCatalogSha256 !== run.parameters.saveCatalogSha256 || other.parameters.targetGasRate === run.parameters.targetGasRate)) return invalid('请选择同一保存点的另一个不同产气方案。')
      }
    }
    cutoff = catalog.timeDays; report = catalog.reportNumber; startDate = catalog.startDate; endTimeDays = catalog.endTimeDays; sourceFile = catalog.sourceFile
    reports = [{ reportNumber: report, timeDays: cutoff, date: catalog.date, predictable: true }]
  } else return invalid('尚未支持此保存点类型。')
  const baseSeries = historical.length ? historical : futures[0]?.run.result.summary.series || [], metrics = []
  for (const s of baseSeries.filter(s => keywords.has(s.keyword) && s.objectName !== ':+:+:+:+' && typeof s.unit === 'string' && s.unit.trim())) {
    const past = historical.length ? s.points.filter(p => p.timeDays <= cutoff) : [], saved = past.find(p => p.timeDays === cutoff)
    if (historical.length && !saved) return invalid('该原始列没有保存点实际值，不能计算新增量。')
    const branches = []
    for (const f of futures) {
      const matches = f.run.result.summary.series.filter(v => identity(v) === identity(s))
      if (matches.length !== 1) return invalid('预测与历史的原始列、对象或单位不一致，不能合并对比。')
      const v = matches[0]
      if (['FOPT','WOPT'].includes(s.keyword) && saved && v.points.some(p => p.value < saved.value)) return invalid('预测累计产油低于保存点累计值，请核对接续状态。')
      branches.push({ runId: f.run.id, label: f.label, points: v.points })
    }
    metrics.push({ key: identity(s), keyword: s.keyword, objectName: s.objectName, vectorIndex: s.vectorIndex ?? null, unit: s.unit, cumulative: cumulative.has(s.keyword), savedValue: saved?.value ?? null, historical: past, forecasts: branches })
  }
  if (!metrics.some(m => m.keyword === 'FOPT')) return invalid('缺少可核对的原生累计产油。')
  return { valid: true, reason: '', kind, sourceFile, startDate, cutoff, report, endTimeDays, reports, historyId, runId: run.id, modelVersionId: run.modelVersionId, portable, metrics, forecasts: futures.map(f => ({ id: f.run.id, label: f.label, firstDays: f.run.result.summary.series[0].points[0].timeDays, lastDays: f.run.result.summary.series[0].points.at(-1).timeDays, actualTimes: f.run.result.summary.series[0].points.length })) }
}

export function forecastMetricSummary(metric) {
  if (!metric) return { entries: [], common: null }
  const entries = metric.forecasts.map(f => ({ ...f, last: f.points.at(-1), increment: metric.cumulative && metric.savedValue != null ? f.points.at(-1).value - metric.savedValue : null }))
  let common = null
  if (entries.length === 2) {
    const second = new Map(entries[1].points.map(p => [p.timeDays, p.value]))
    const last = entries[0].points.filter(p => second.has(p.timeDays)).at(-1)
    if (last) common = { timeDays: last.timeDays, first: last.value, second: second.get(last.timeDays), difference: last.value - second.get(last.timeDays) }
  }
  return { entries, common }
}

export function forecastPresentationCsv(model) {
  if (!model?.valid) return ''
  const rows = [['stage','run_id','model_version_id','keyword','object','vector_index','unit','time_days','date','original_value','savepoint_value','increment_after_savepoint']]
  for (const m of model.metrics) {
    for (const p of m.historical) rows.push(['history',model.historyId,model.modelVersionId,m.keyword,m.objectName,m.vectorIndex,m.unit,p.timeDays,forecastDate(model.startDate,p.timeDays),p.value,m.savedValue,null])
    for (const f of m.forecasts) for (const p of f.points) rows.push(['forecast',f.runId,model.modelVersionId,m.keyword,m.objectName,m.vectorIndex,m.unit,p.timeDays,forecastDate(model.startDate,p.timeDays),p.value,m.savedValue,m.cumulative && m.savedValue != null ? p.value-m.savedValue : null])
  }
  return '\uFEFF' + rows.map(row => row.map(value => { const s = String(value ?? ''); return `"${(typeof value === 'string' && /^[=+\-@\t\r]/.test(s) ? "'"+s : s).replace(/"/g,'""')}"` }).join(',')).join('\r\n')
}
