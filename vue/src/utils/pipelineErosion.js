import { conditionTime } from './pipelineBatch.js'
import { formatOperatingTime } from './pipelineTime.js'

export const EROSION_INPUT_FIELDS = Object.freeze([
  Object.freeze({ key: 'liquidHoldupPercent', label: '持液率 Hₗ', unit: '%', min: 0, max: 100 }),
  Object.freeze({ key: 'sandContentPercent', label: '含砂率 Hₛ', unit: '%', min: 0, max: 100 }),
  Object.freeze({ key: 'sandDensityKgM3', label: '砂粒密度', unit: 'kg/m³', min: 0 })
])
const finite = value => typeof value === 'number' && Number.isFinite(value)
export function erosionSegment(settings, edgeId) {
  return settings?.segments?.find(row => row.edgeId === edgeId) || {}
}
export function erosionCase(settings, caseId, edgeId) {
  return settings?.cases?.find(row => row.caseId === caseId && row.edgeId === edgeId) || {}
}
export function erosionParameters(settings, caseId, edgeId) {
  const base = erosionSegment(settings, edgeId), override = erosionCase(settings, caseId, edgeId)
  return Object.fromEntries(EROSION_INPUT_FIELDS.map(({ key }) => [key, override[key] ?? base[key] ?? null]))
}
export function setErosionParameter(settings, edgeId, key, value, caseId = null) {
  if (!EROSION_INPUT_FIELDS.some(field => field.key === key)) return
  const collection = caseId == null ? (settings.segments ||= []) : (settings.cases ||= [])
  let record = collection.find(row => row.edgeId === edgeId && (caseId == null || row.caseId === caseId))
  if (!record) { record = caseId == null ? { edgeId } : { caseId, edgeId }; collection.push(record) }
  record[key] = value
  if (caseId != null && EROSION_INPUT_FIELDS.every(field => record[field.key] == null)) {
    settings.cases = collection.filter(row => row !== record)
  }
}

/** Draft rows remain editable even when the saved calculation is no longer current. */
export function erosionDataRows(graph, cases, settings, result, current) {
  const resultCases = new Map((current ? result?.cases || [] : []).map(row => [row.caseId, row]))
  const defaults = new Map((settings?.segments || []).map(row => [row.edgeId, row]))
  const overrides = new Map((settings?.cases || []).map(row => [`${row.caseId}/${row.edgeId}`, row]))
  return [...(cases || [])].sort((a, b) => (conditionTime(a.operatingAt) ?? Infinity) - (conditionTime(b.operatingAt) ?? Infinity))
    .flatMap(condition => {
      const calculated = resultCases.get(condition.id)
      const evaluations = new Map((calculated?.status === 'success' ? calculated.erosion || [] : []).map(row => [row.edgeId, row]))
      return (graph?.edges || []).map(edge => {
        const evaluation = evaluations.get(edge.id), override = overrides.get(`${condition.id}/${edge.id}`) || {}, base = defaults.get(edge.id) || {}
        const parameters = Object.fromEntries(EROSION_INPUT_FIELDS.map(({ key }) => [key, override[key] ?? base[key] ?? null]))
        return { ...evaluation, rowKey: `${condition.id}/${edge.id}`, caseId: condition.id, edgeId: edge.id,
          operatingAt: condition.operatingAt, name: edge.name, ...parameters,
          overrideKeys: EROSION_INPUT_FIELDS.filter(field => override[field.key] != null).map(field => field.key),
          overridden: EROSION_INPUT_FIELDS.some(field => override[field.key] != null),
          status: evaluation?.status || 'not_evaluated',
          reason: calculated?.error || evaluation?.reason || (current ? '本工况尚无冲蚀评价结果' : '请计算全部工况后查看结果') }
      })
    })
}

/** Validate the values actually used for every pipe/time, including user-entered shared values. */
export function erosionInputIssue(graph, cases, settings) {
  for (const row of erosionDataRows(graph, cases, settings, null, false)) {
    const location = `工况 ${formatOperatingTime(row.operatingAt)}，管道“${row.name}”`
    const missing = EROSION_INPUT_FIELDS.filter(({ key }) => row[key] == null || row[key] === '')
    if (missing.length) return `${location}：请填写${missing.map(field => field.label).join('、')}。计算需要全部工况、全部管道的三个参数。`
    const invalid = EROSION_INPUT_FIELDS.filter(({ key }) => !finite(row[key]))
    if (invalid.length) return `${location}：${invalid.map(field => field.label).join('、')}须为有效数值。`
    if (row.liquidHoldupPercent < 0 || row.sandContentPercent < 0 || row.liquidHoldupPercent + row.sandContentPercent >= 100)
      return `${location}：持液率、含砂率须为非负数，且两者之和须小于 100%。`
    if (row.sandDensityKgM3 <= 0) return `${location}：砂粒密度须大于 0。`
  }
  return ''
}

export function erosionStatusLabel(status) {
  return ({ reference_below: '低于临界值（参考）', reference_at: '达到临界值（参考）',
    reference_above: '超过临界值（参考）', not_applicable: '模型不适用', not_evaluated: '未评价' })[status] || '未评价'
}

/** Missing/failed times stay as gaps, and both velocities always come from the same sampled point. */
export function erosionChartSeries(rows, edgeId, metric) {
  const selected = rows.filter(row => (!edgeId || row.edgeId === edgeId) && conditionTime(row.operatingAt) != null)
    .sort((a, b) => conditionTime(a.operatingAt) - conditionTime(b.operatingAt))
  const ids = [...new Set(selected.map(row => row.edgeId))], series = []
  for (const id of ids) {
    const values = selected.filter(row => row.edgeId === id), prefix = edgeId ? '' : `${values[0].name} · `
    const fields = metric === 'ratio' ? [['velocityRatio', '流速利用率']] : [['actualVelocityMs', '实际流速'], ['criticalVelocityMs', '临界冲蚀流速']]
    for (const [key, label] of fields) series.push({ id: `${id}:${key}`, name: `${prefix}${label}`,
      data: values.map(row => [conditionTime(row.operatingAt),
        ['reference_below', 'reference_at', 'reference_above'].includes(row.status) && finite(row[key]) ? row[key] : null]) })
  }
  if (metric === 'ratio' && series.some(item => item.data.some(([, value]) => finite(value)))) {
    series.push({ id: 'erosion:threshold', name: '临界值 1', data: [...new Set(selected.map(row => conditionTime(row.operatingAt)))].map(time => [time, 1]) })
  }
  return series
}
