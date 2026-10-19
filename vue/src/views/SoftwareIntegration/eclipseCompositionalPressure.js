import { compositionalProductionRecords } from './eclipseCompositionalProduction.js'
export const compositionalPressureSchema = 'eclipse-compositional-pressure-parameters/1'
const keys = ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'sourceSha256', 'lineNumber', 'unit', 'originalBhpLowerLimit', 'targetBhpLowerLimit']
const positive = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 1e8
export const validCompositionalPressure = p => p && typeof p === 'object' && !Array.isArray(p) && Object.keys(p).length === keys.length && keys.every(k => Object.hasOwn(p, k)) &&
  p.schemaVersion === compositionalPressureSchema && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 &&
  typeof p.well === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(p.well) && typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile) &&
  typeof p.sourceSha256 === 'string' && /^[a-f0-9]{64}$/.test(p.sourceSha256) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 && p.lineNumber <= 2147483647 &&
  ['psia', 'barsa'].includes(p.unit) && positive(p.originalBhpLowerLimit) && positive(p.targetBhpLowerLimit) && p.originalBhpLowerLimit !== p.targetBhpLowerLimit
export const matchesCompositionalPressure = (p, i) => validCompositionalPressure(p) && compositionalProductionRecords(i).some(r =>
  r.well === p.well && r.bhpLineNumber > r.lineNumber && r.bhpLineNumber === p.lineNumber && r.bhpLimit === p.originalBhpLowerLimit && p.unit === (i.unitSystem === 'FIELD' ? 'psia' : 'barsa')) &&
  p.sourceFile === i.compositionalProductionContext.sourceFile && p.sourceSha256 === i.compositionalProductionContext.sourceSha256
export const buildCompositionalPressure = ({ inspection, baselineRunId, well, targetBhpLowerLimit }) => {
  const r = compositionalProductionRecords(inspection).find(r => r.well === well), c = inspection?.compositionalProductionContext
  if (!r || r.bhpLineNumber <= r.lineNumber) return null
  const p = { schemaVersion: compositionalPressureSchema, baselineRunId, well, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.bhpLineNumber, unit: inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa', originalBhpLowerLimit: r.bhpLimit, targetBhpLowerLimit }
  return validCompositionalPressure(p) ? p : null
}
const points = s => Array.isArray(s?.points) && s.points.length > 0 && s.points.every((p, j) => typeof p.value === 'number' && Number.isFinite(p.value) &&
  typeof p.timeDays === 'number' && Number.isFinite(p.timeDays) && p.timeDays >= 0 && (!j || p.timeDays > s.points[j - 1].timeDays))
export const compositionalPressureResponse = (s, b, p, i) => {
  if (!matchesCompositionalPressure(p, i) || s?.startDate !== b?.startDate || !Array.isArray(s?.series) || !Array.isArray(b?.series)) return null
  const r = compositionalProductionRecords(i).find(r => r.well === p.well), field = i.unitSystem === 'FIELD', flowUnit = field ? 'MSCF/DAY' : 'SM3/DAY', pressureUnit = field ? 'PSIA' : 'BARSA'
  const pair = (keyword, unit, optional = false) => {
    const a = s.series.filter(v => v.keyword === keyword && v.objectName === p.well), before = b.series.filter(v => v.keyword === keyword && v.objectName === p.well)
    if (optional && !a.length && !before.length) return []
    if (a.length !== 1 || before.length !== 1 || !points(a[0]) || !points(before[0]) || a[0].unit?.toUpperCase() !== unit || before[0].unit !== a[0].unit ||
      a[0].vectorIndex !== before[0].vectorIndex || a[0].number !== before[0].number) return null
    return [a[0], before[0]]
  }
  const gas = pair('WGPR', flowUnit), pressure = pair('WBHP', pressureUnit), total = pair('WGPT', field ? 'MSCF' : 'SM3', true)
  if (!gas || !pressure || !total || gas[0].points.length !== pressure[0].points.length || gas[0].points.some((v, j) => v.timeDays !== pressure[0].points[j].timeDays)) return null
  const bg = new Map(gas[1].points.map(v => [v.timeDays, v.value])), bp = new Map(pressure[1].points.map(v => [v.timeDays, v.value]))
  const tolerance = Math.max(.01, r.originalGasRate * 1e-6)
  const rows = gas[0].points.map((g, j) => ({ timeDays: g.timeDays, gasRate: g.value, bhp: pressure[0].points[j].value,
    baselineGasRate: bg.get(g.timeDays) ?? null, baselineBhp: bp.get(g.timeDays) ?? null,
    pressureLimited: Math.abs(pressure[0].points[j].value - p.targetBhpLowerLimit) <= .1 && g.value < r.originalGasRate - tolerance }))
  const consistent = rows.every(v => v.bhp >= p.targetBhpLowerLimit - .1 && v.gasRate >= 0 && v.gasRate <= r.originalGasRate + tolerance)
  const changed = rows.filter(v => v.baselineGasRate !== null && v.baselineBhp !== null && (v.gasRate !== v.baselineGasRate || v.bhp !== v.baselineBhp)).length
  const limited = rows.filter(v => v.pressureLimited).length
  const inactive = consistent && !limited && rows.every(v => Math.abs(v.gasRate - r.originalGasRate) <= tolerance && v.bhp > p.targetBhpLowerLimit + .1)
  return { rows, consistent, changed, limited, inactive, verified: consistent && (changed > 0 || inactive), originalGasTarget: r.originalGasRate, flowUnit, pressureUnit,
    bhpLowerLimit: p.targetBhpLowerLimit, finalGasRate: rows.at(-1).gasRate, finalBhp: rows.at(-1).bhp, finalTime: rows.at(-1).timeDays,
    cumulative: total.length ? { value: total[0].points.at(-1).value, timeDays: total[0].points.at(-1).timeDays, unit: total[0].unit } : null }
}
