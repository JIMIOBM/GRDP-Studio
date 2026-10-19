import { validThermalInjectionContext } from './eclipseThermalInjection.js'

export const thermalRockSchema = 'eclipse-thermal-rock-parameters/1'
export const thermalRockLabels = { HEATCR: '岩石体积热容', THCONR: '岩石与流体综合导热率' }
export const thermalRockUnit = unit => ({ 'Btu/ft3/degR': 'Btu/ft³/°R', 'Btu/ft/day/degR': 'Btu/ft/day/°R' }[unit] || unit)
const shape = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k))
const integer = (n, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n > 0 && n <= max
const value = (field, n) => typeof n === 'number' && Number.isFinite(n) && n <= 1e9 && (field === 'HEATCR' ? n > 0 : field === 'THCONR' && n >= 0)
const unit = (field, u) => field === 'HEATCR' ? ['Btu/ft3/degR', 'kJ/m3/K'].includes(u) : field === 'THCONR' && ['Btu/ft/day/degR', 'kJ/m/day/K'].includes(u)
export function validThermalRockContext(c, i) {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'cellCount', 'activeCellCount', 'records']) ||
    c.schemaVersion !== 'eclipse-thermal-rock-context/1' || i?.schemaVersion !== 'eclipse-data-inspection/23' || !validThermalInjectionContext(i.thermalInjectionContext, i) ||
    !integer(c.cellCount, 250000) || !integer(c.activeCellCount, c.cellCount) || !Array.isArray(c.records) || c.records.length !== 2) return false
  const source = i.thermalInjectionContext, d = i.dimensions
  if (['sourceFile', 'sourceSha256', 'sourceSizeBytes'].some(k => c[k] !== source[k]) || !d || !['nx', 'ny', 'nz'].every(k => integer(d[k], 250000)) || d.nx * d.ny * d.nz !== c.cellCount) return false
  const keys = new Set(), lines = new Set(), field = i.unitSystem === 'FIELD'
  for (const r of c.records) {
    const expected = r.field === 'HEATCR' ? field ? 'Btu/ft3/degR' : 'kJ/m3/K' : field ? 'Btu/ft/day/degR' : 'kJ/m/day/K'
    if (!shape(r, ['field', 'lineNumber', 'unit', 'originalValue']) || !Object.hasOwn(thermalRockLabels, r.field) || keys.has(r.field) ||
      !integer(r.lineNumber, c.sourceSizeBytes) || lines.has(r.lineNumber) || r.unit !== expected || !value(r.field, r.originalValue)) return false
    keys.add(r.field); lines.add(r.lineNumber)
  }
  return keys.size === 2
}
export const thermalRockRecords = i => validThermalRockContext(i?.thermalRockContext, i) ? i.thermalRockContext.records : []
export const validThermalRock = p => shape(p, ['schemaVersion', 'baselineRunId', 'sourceFile', 'sourceSha256', 'field', 'lineNumber', 'cellCount', 'unit', 'originalValue', 'targetValue']) &&
  p.schemaVersion === thermalRockSchema && integer(p.baselineRunId) && integer(p.lineNumber, 2147483647) && integer(p.cellCount, 250000) &&
  typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile) && typeof p.sourceSha256 === 'string' && /^[a-f0-9]{64}$/.test(p.sourceSha256) &&
  unit(p.field, p.unit) && value(p.field, p.originalValue) && value(p.field, p.targetValue) && p.originalValue !== p.targetValue
export const matchesThermalRock = (p, i) => validThermalRock(p) && thermalRockRecords(i).some(r => r.field === p.field && r.lineNumber === p.lineNumber && r.unit === p.unit && r.originalValue === p.originalValue) &&
  ['sourceFile', 'sourceSha256', 'cellCount'].every(k => p[k] === i.thermalRockContext[k])
export function buildThermalRock({ inspection: i, baselineRunId, field, targetValue }) {
  const r = thermalRockRecords(i).find(r => r.field === field), c = i?.thermalRockContext
  if (!r) return null
  const p = { schemaVersion: thermalRockSchema, baselineRunId, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256, field,
    lineNumber: r.lineNumber, cellCount: c.cellCount, unit: r.unit, originalValue: r.originalValue, targetValue }
  return matchesThermalRock(p, i) ? p : null
}
const finite = n => typeof n === 'number' && Number.isFinite(n)
const points = s => Array.isArray(s?.points) && s.points.length && s.points.every((p, j) => finite(p.timeDays) && p.timeDays >= 0 && finite(p.value) && (!j || p.timeDays > s.points[j - 1].timeDays))
const identity = s => JSON.stringify([s.vectorIndex, s.keyword, s.objectName, s.number, s.unit])
const labels = { WTEMP: '实际井温', BTEMP: '网格温度', WOPT: '累计产油', WWIT: '实际累计注入', WBHP: '井底压力', FHLR: '热损失率', FHLT: '累计热损失' }
export function thermalRockResponse(run, baseline, i) {
  const p = run?.parameters
  if (!matchesThermalRock(p, i) || !baseline || baseline.id !== p.baselineRunId || baseline.parameters != null || run.modelId !== baseline.modelId || run.modelVersionId !== baseline.modelVersionId ||
    [run, baseline].some(r => r.runType !== 'eclipse' || r.status !== 'SUCCEEDED' || r.resultContract !== 'VALID_FULL' || r.resultExpired) ||
    !run.result?.summary || !baseline.result?.summary || run.result.summary.startDate !== baseline.result.summary.startDate) return null
  const a = run.result.summary.series, b = baseline.result.summary.series
  if (!Array.isArray(a) || !Array.isArray(b) || !a.length || a.length !== b.length || new Set(a.map(identity)).size !== a.length || new Set(b.map(identity)).size !== b.length) return null
  const source = new Map(b.map(s => [identity(s), s])), metrics = [], rows = []
  for (const s of a) {
    const original = source.get(identity(s))
    if (!original || !points(s) || !points(original)) return null
    if (!Object.hasOwn(labels, s.keyword) || s.objectName === ':+:+:+:+' || !integer(s.vectorIndex + 1) || typeof s.unit !== 'string' || !s.unit) continue
    const before = new Map(original.points.map(p => [p.timeDays, p.value])), common = s.points.filter(p => before.has(p.timeDays))
    if (!common.length) continue
    const last = common.at(-1), baselineValue = before.get(last.timeDays), key = identity(s)
    metrics.push({ key, keyword: s.keyword, objectName: s.objectName, number: s.number, unit: s.unit, vectorIndex: s.vectorIndex, label: labels[s.keyword],
      current: s, baseline: original, finalTime: last.timeDays, value: last.value, baselineValue, difference: last.value - baselineValue })
    rows.push(...s.points.map(v => ({ keyword: s.keyword, objectName: s.objectName, number: s.number, vectorIndex: s.vectorIndex, unit: s.unit, timeDays: v.timeDays,
      value: v.value, baselineValue: before.get(v.timeDays) ?? null, difference: before.has(v.timeDays) ? v.value - before.get(v.timeDays) : null })))
  }
  if (!metrics.length) return null
  const injector = i.thermalInjectionContext.records[0].well, producer = i.wellNames.find(n => !i.thermalInjectionContext.records.some(r => r.well === n))
  const cards = [['WOPT', producer], ['WTEMP', injector], ['FHLR', null], ['WWIT', injector]].flatMap(([keyword, object]) => {
    const m = metrics.find(m => m.keyword === keyword && m.objectName === object); return m ? [m] : []
  })
  return { metrics, rows, cards, activeCellCount: i.thermalRockContext.activeCellCount, cellCount: p.cellCount,
    changed: metrics.some(m => m.difference !== 0), temperatureAvailable: metrics.some(m => ['WTEMP', 'BTEMP'].includes(m.keyword)) }
}
export function thermalRockCsv(response, run) {
  if (!response) return ''
  const keys = ['keyword', 'objectName', 'number', 'vectorIndex', 'unit', 'timeDays', 'value', 'baselineValue', 'difference']
  const quote = v => '"' + String(v ?? '').replaceAll('"', '""') + '"'
  return [['runId', 'baselineRunId', ...keys].join(','), ...response.rows.map(row => [run.id, run.parameters.baselineRunId, ...keys.map(k => row[k])].map(quote).join(','))].join('\r\n')
}
