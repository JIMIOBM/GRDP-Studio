import { thermalInjectionRecords, thermalStageDays } from './eclipseThermalInjection.js'

export const thermalPlanSchema = 'eclipse-thermal-injection-plan-parameters/1'
export const thermalPlanFields = ['surfaceInjectionRate', 'injectionTemperature', 'steamQuality', 'bhpUpperLimit']
const shape = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k))
const integer = (v, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v > 0 && v <= max
const same = (a, b) => thermalPlanFields.every(k => a[k] === b[k])
const values = (v, unit) => thermalPlanFields.every(k => typeof v[k] === 'number' && Number.isFinite(v[k])) &&
  v.surfaceInjectionRate > 0 && v.surfaceInjectionRate <= 1e8 && v.bhpUpperLimit > 0 && v.bhpUpperLimit <= 1e8 &&
  v.steamQuality >= 0 && v.steamQuality <= 1 && v.injectionTemperature > (unit === 'degF' ? -459.67 : -273.15) && v.injectionTemperature <= 1e6
export const thermalPlanOriginal = r => ({ surfaceInjectionRate: r.surfaceInjectionRate, injectionTemperature: r.originalTemperature,
  steamQuality: r.originalSteamQuality, bhpUpperLimit: r.bhpUpperLimit })
export function validThermalPlan(p) {
  if (!shape(p, ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'sourceSha256', 'lineNumber', 'controlLineNumber',
    'temperatureUnit', 'rateUnit', 'pressureUnit', 'original', 'stages']) || p.schemaVersion !== thermalPlanSchema || !integer(p.baselineRunId) ||
    typeof p.well !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(p.well) || typeof p.sourceFile !== 'string' ||
    !/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile) || typeof p.sourceSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(p.sourceSha256) ||
    !integer(p.lineNumber, 2147483647) || !integer(p.controlLineNumber, p.lineNumber - 1) ||
    !(p.temperatureUnit === 'degF' && p.rateUnit === 'STB/day' && p.pressureUnit === 'psia' || p.temperatureUnit === 'degC' && p.rateUnit === 'sm3/day' && p.pressureUnit === 'barsa') ||
    !shape(p.original, thermalPlanFields) || !values(p.original, p.temperatureUnit) || !Array.isArray(p.stages) || p.stages.length < 2 || p.stages.length > 8) return false
  let day = 0, previous = p.original
  for (const stage of p.stages) {
    if (!shape(stage, ['startTimeDays', ...thermalPlanFields]) || !integer(stage.startTimeDays, 1e9) || stage.startTimeDays <= day ||
      !values(stage, p.temperatureUnit) || same(stage, previous)) return false
    day = stage.startTimeDays; previous = stage
  }
  return true
}
export function matchesThermalPlan(p, i) {
  if (!validThermalPlan(p)) return false
  const records = thermalInjectionRecords(i), c = i?.thermalInjectionContext, days = thermalStageDays(i)
  return c?.sourceFile === p.sourceFile && c.sourceSha256 === p.sourceSha256 && p.stages.every(s => days.includes(s.startTimeDays)) &&
    records.filter(r => r.well === p.well && r.lineNumber === p.lineNumber && r.controlLineNumber === p.controlLineNumber &&
      r.temperatureUnit === p.temperatureUnit && r.rateUnit === p.rateUnit && same(thermalPlanOriginal(r), p.original)).length === 1
}
export function buildThermalPlan({ inspection: i, baselineRunId, well, stages }) {
  const r = thermalInjectionRecords(i).find(r => r.well === well), c = i?.thermalInjectionContext
  if (!r) return null
  const p = { schemaVersion: thermalPlanSchema, baselineRunId, well, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.lineNumber, controlLineNumber: r.controlLineNumber, temperatureUnit: r.temperatureUnit, rateUnit: r.rateUnit,
    pressureUnit: r.rateUnit === 'STB/day' ? 'psia' : 'barsa', original: thermalPlanOriginal(r), stages: Array.isArray(stages) ? stages.map(s => ({ ...s })) : stages }
  return matchesThermalPlan(p, i) ? p : null
}
export function thermalPlanPeriods(p, i) {
  if (!matchesThermalPlan(p, i)) return []
  const end = i.scheduleTimeline[0].steps.reduce((sum, n) => sum + Number(n), 0)
  return [{ ...p.original, startTimeDays: 0 }, ...p.stages].map((s, index, all) => ({ ...s, index,
    endTimeDays: all[index + 1]?.startTimeDays ?? end, label: index ? `阶段 ${index}` : '原方案' }))
}
export function thermalPlanEffective(p, timeDays) {
  let result = p.original
  for (const stage of p.stages) { if (stage.startTimeDays < timeDays) result = stage; else break }
  return result
}
const validPoints = s => Array.isArray(s?.points) && s.points.length && s.points.every((p, j) => typeof p.value === 'number' && Number.isFinite(p.value) &&
  typeof p.timeDays === 'number' && Number.isFinite(p.timeDays) && p.timeDays >= 0 && (!j || p.timeDays > s.points[j - 1].timeDays))
const identity = s => JSON.stringify([s.vectorIndex, s.keyword, s.objectName, s.number, s.unit])
const timing = new Set(['TCPU', 'ELAPSED', 'TCPUTS', 'TCPUDAY'])
export function thermalPlanResponse(summary, baseline, p, i) {
  if (!matchesThermalPlan(p, i) || !summary || !baseline || summary.startDate !== baseline.startDate || !Array.isArray(summary.series) ||
    !Array.isArray(baseline.series) || summary.series.length !== baseline.series.length || !summary.series.length) return null
  const before = new Map(baseline.series.map(s => [identity(s), s]))
  if (before.size !== baseline.series.length || new Set(summary.series.map(identity)).size !== summary.series.length ||
    !summary.series.every(s => validPoints(s) && before.has(identity(s)) && validPoints(before.get(identity(s))))) return null
  const first = p.stages[0].startTimeDays
  const preStageVerified = summary.series.filter(s => !timing.has(s.keyword)).every(s => {
    const a = s.points.filter(v => v.timeDays <= first), b = before.get(identity(s)).points.filter(v => v.timeDays <= first)
    return a.length > 0 && a.length === b.length && a.every((v, j) => v.timeDays === b[j].timeDays && v.value === b[j].value)
  })
  const field = p.rateUnit === 'STB/day', metrics = [], rows = []
  const collect = (keyword, objectName, label, unit, required = true) => {
    const a = summary.series.filter(s => s.keyword === keyword && s.objectName === objectName)
    if (!a.length && !required) return true
    if (a.length !== 1 || !a[0].unit || unit && a[0].unit.toUpperCase() !== unit) return false
    const s = a[0], b = before.get(identity(s)), common = new Map(b.points.map(v => [v.timeDays, v.value]))
    const data = s.points.map(v => { const settings = thermalPlanEffective(p, v.timeDays); return { keyword, objectName, label, unit: s.unit,
      vectorIndex: s.vectorIndex, timeDays: v.timeDays, value: v.value, baselineValue: common.get(v.timeDays) ?? null,
      difference: common.has(v.timeDays) ? v.value - common.get(v.timeDays) : null,
      phase: settings === p.original ? 0 : p.stages.indexOf(settings) + 1, targetRateUnit: p.rateUnit, pressureLimitUnit: p.pressureUnit, surfaceInjectionTarget: settings.surfaceInjectionRate, bhpUpperLimit: settings.bhpUpperLimit } })
    rows.push(...data); metrics.push({ keyword, objectName, label, unit: s.unit, vectorIndex: s.vectorIndex,
      finalValue: data.at(-1).value, finalTime: data.at(-1).timeDays, changed: data.filter(v => v.difference !== null && v.difference !== 0).length })
    return true
  }
  if (!collect('WWIR', p.well, '实际注入量', field ? 'STB/DAY' : 'SM3/DAY') || !collect('WWIT', p.well, '实际累计注入', field ? 'STB' : 'SM3') ||
    !collect('WBHP', p.well, '实际井底压力', field ? 'PSIA' : 'BARSA') || !collect('FHLR', null, '全场热损失率', null) ||
    !collect('FHLT', null, '全场累计热损失', null, false) || !collect('WTEMP', p.well, '实际井温', field ? 'DEG F' : 'DEG C', false)) return null
  const rates = rows.filter(r => r.keyword === 'WWIR'), pressures = rows.filter(r => r.keyword === 'WBHP')
  const consistent = rates.every(r => r.value >= 0 && r.value <= r.surfaceInjectionTarget + .001) && pressures.every(r => r.value >= 0 && r.value <= r.bhpUpperLimit + .1)
  const periods = thermalPlanPeriods(p, i)
  const complete = metrics.every(m => m.finalTime === periods.at(-1).endTimeDays) && periods.slice(1).every(period => rates.some(r => r.timeDays > period.startTimeDays && r.timeDays <= period.endTimeDays))
  const changed = metrics.some(m => m.changed > 0)
  return { rows, metrics, periods, consistent, complete, preStageVerified, verified: consistent && complete && preStageVerified && changed,
    temperatureAvailable: metrics.some(m => m.keyword === 'WTEMP'), pressureLimited: rates.some(r => r.value < r.surfaceInjectionTarget - .001 &&
      pressures.some(b => b.timeDays === r.timeDays && Math.abs(b.value - r.bhpUpperLimit) <= .1)) }
}
export function thermalPlanCsv(response, runId, baselineRunId) {
  if (!response) return ''
  const quote = value => '"' + String(value ?? '').replaceAll('"', '""') + '"'
  const fields = ['keyword', 'objectName', 'vectorIndex', 'unit', 'timeDays', 'phase', 'value', 'baselineValue', 'difference', 'surfaceInjectionTarget', 'targetRateUnit', 'bhpUpperLimit', 'pressureLimitUnit']
  return [['runId', 'baselineRunId', ...fields].join(','), ...response.rows.map(r => [runId, baselineRunId, ...fields.map(k => r[k])].map(quote).join(','))].join('\r\n')
}
