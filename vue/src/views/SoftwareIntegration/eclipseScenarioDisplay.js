const finite = value => typeof value === 'number' && Number.isFinite(value)
const identity = series => JSON.stringify([series.keyword, series.objectName || '', series.unit, series.vectorIndex])
const physical = new Set(['WWIT', 'WOPT', 'WTEMP', 'WBHP', 'FOPT', 'FGPT', 'WGPT', 'FPR', 'WOPR', 'WGPR'])
export const scenarioMetricLabels = { WWIT: '累计注水', WOPT: '累计产油', WTEMP: '实际井温', WBHP: '井底压力', FOPT: '全场累计产油', FGPT: '全场累计产气', WGPT: '井累计产气', FPR: '地层压力', WOPR: '产油速率', WGPR: '产气速率' }
export const formatScenarioValue = value => finite(value) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 }).format(value) : '—'
const valid = series => Number.isSafeInteger(series.vectorIndex) && series.vectorIndex >= 0 && Array.isArray(series.points) && series.points.length &&
  series.points.every((p, i, points) => finite(p.timeDays) && finite(p.value) && (!i || p.timeDays > points[i - 1].timeDays))
export function scenarioMetricPairs(run, baseline) {
  if (!run || !baseline || run.status !== 'SUCCEEDED' || run.resultContract !== 'VALID_FULL' || baseline.status !== 'SUCCEEDED' || baseline.resultContract !== 'VALID_FULL' ||
    run.resultExpired || baseline.resultExpired || run.modelId !== baseline.modelId || run.modelVersionId !== baseline.modelVersionId || run.parameters?.baselineRunId !== baseline.id || baseline.parameters != null ||
    run.result?.summary?.startDate !== baseline.result?.summary?.startDate) return []
  const current = run.result?.summary?.series || [], original = baseline.result?.summary?.series || []
  const result = []
  for (const series of current) {
    if (!physical.has(series.keyword) || series.objectName === ':+:+:+:+' || !valid(series)) continue
    const key = identity(series), matches = original.filter(s => identity(s) === key && valid(s))
    if (matches.length !== 1 || current.filter(s => identity(s) === key).length !== 1) continue
    const before = matches[0], lookup = new Map(before.points.map(p => [p.timeDays, p.value]))
    const common = series.points.filter(p => lookup.has(p.timeDays))
    if (!common.length) continue
    const last = common.at(-1), baselineValue = lookup.get(last.timeDays), difference = last.value - baselineValue
    result.push({ key, keyword: series.keyword, object: series.objectName || '全场', vectorIndex: series.vectorIndex,
      unit: series.unit, current: series, baseline: before, timeDays: last.timeDays, baselineValue, value: last.value,
      difference, percent: baselineValue === 0 ? null : difference / Math.abs(baselineValue) * 100, commonCount: common.length })
  }
  return result
}
export function scenarioMetricCards(pairs, kind) {
  const preferred = kind === 'thermal' ? ['WWIT', 'WOPT', 'WTEMP', 'WBHP'] : ['FOPT', 'WGPT', 'WBHP', 'FPR']
  return preferred.flatMap(keyword => {
    const options = pairs.filter(p => p.keyword === keyword)
    const best = options.find(p => p.object === (keyword === 'WOPT' ? 'PNEAR' : kind === 'thermal' ? 'I' : 'P')) || options[0]
    return best ? [best] : []
  })
}
