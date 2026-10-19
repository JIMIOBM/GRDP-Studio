const shape = (v, names) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === names.length && names.every(k => Object.hasOwn(v, k))
const integer = (v, min = 1, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v >= min && v <= max
const positive = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 1e8
const well = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(v)
const source = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(v)
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
export const compositionalProductionSchema = 'eclipse-compositional-production-parameters/1'
export const validCompositionalProduction = p => shape(p, ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'sourceSha256', 'lineNumber', 'unit', 'originalGasRate', 'targetGasRate']) &&
  p.schemaVersion === compositionalProductionSchema && integer(p.baselineRunId) && well(p.well) && source(p.sourceFile) && hash(p.sourceSha256) && integer(p.lineNumber, 1, 2147483647) &&
  ['Mscf/day', 'sm3/day'].includes(p.unit) && positive(p.originalGasRate) && positive(p.targetGasRate) && p.originalGasRate !== p.targetGasRate
export const validCompositionalProductionContext = (c, i) => {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'solver', 'componentCount', 'componentNames', 'equationOfState', 'records']) ||
    c.schemaVersion !== 'eclipse-compositional-production-context/1' || !['eclipse-data-inspection/14', 'eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion) || i.simulation?.solver !== 300 || i.simulation?.modelType !== 'COMPOSITIONAL' ||
    i.simulation?.componentCount !== 9 || i.simulation?.equationOfState !== 'PR' || c.solver !== 300 || c.componentCount !== 9 || c.equationOfState !== 'PR' ||
    !Array.isArray(c.componentNames) || c.componentNames.length !== 9 || c.componentNames.some((n, j) => n !== i.simulation.componentNames?.[j]) ||
    !source(c.sourceFile) || c.sourceFile !== i.caseName || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) ||
    !Array.isArray(i.packageFiles) || i.packageFiles.length !== 1 || !Array.isArray(i.wellNames) || !['FIELD', 'METRIC'].includes(i.unitSystem) ||
    !Array.isArray(c.records) || !c.records.length || c.records.length > 1000) return false
  const f = i.packageFiles[0]
  if (f.relativePath !== c.sourceFile || f.sha256 !== c.sourceSha256 || f.sizeBytes !== c.sourceSizeBytes) return false
  const names = new Set(), rows = new Set(), unit = i.unitSystem === 'FIELD' ? 'Mscf/day' : 'sm3/day'
  for (const r of c.records) {
    if (!shape(r, ['well', 'lineNumber', 'controlMode', 'unit', 'originalGasRate', 'bhpLimit', 'bhpLineNumber']) || !well(r.well) || !i.wellNames.includes(r.well) || names.has(r.well) ||
      !integer(r.lineNumber, 1, c.sourceSizeBytes) || !integer(r.bhpLineNumber, 1, c.sourceSizeBytes) || r.lineNumber === r.bhpLineNumber || rows.has(r.lineNumber) || rows.has(r.bhpLineNumber) ||
      r.controlMode !== 'GRAT' || r.unit !== unit || !positive(r.originalGasRate) || !positive(r.bhpLimit)) return false
    names.add(r.well); rows.add(r.lineNumber); rows.add(r.bhpLineNumber)
  }
  return true
}
export const compositionalProductionRecords = i => validCompositionalProductionContext(i?.compositionalProductionContext, i) ? i.compositionalProductionContext.records : []
export const matchesCompositionalProduction = (p, i) => validCompositionalProduction(p) && compositionalProductionRecords(i).some(r => r.well === p.well && r.lineNumber === p.lineNumber && r.unit === p.unit && r.originalGasRate === p.originalGasRate) &&
  p.sourceFile === i.compositionalProductionContext.sourceFile && p.sourceSha256 === i.compositionalProductionContext.sourceSha256
export const buildCompositionalProduction = ({ inspection, baselineRunId, well: name, targetGasRate }) => {
  const r = compositionalProductionRecords(inspection).find(r => r.well === name), c = inspection?.compositionalProductionContext
  if (!r) return null
  const p = { schemaVersion: compositionalProductionSchema, baselineRunId, well: r.well, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.lineNumber, unit: r.unit, originalGasRate: r.originalGasRate, targetGasRate }
  return validCompositionalProduction(p) ? p : null
}
const unique = (summary, keyword, name, unit) => {
  const series = summary?.series?.filter(s => s.keyword === keyword && s.objectName === name && s.unit?.toUpperCase() === unit) || []
  return series.length === 1 ? series[0] : null
}
const points = s => s?.points?.length > 0 && s.points.every((p, j) => Number.isFinite(p.value) && Number.isFinite(p.timeDays) && p.timeDays >= 0 && (!j || p.timeDays > s.points[j - 1].timeDays))
export const compositionalProductionResponse = (summary, baseline, p, inspection) => {
  if (!matchesCompositionalProduction(p, inspection) || summary?.startDate !== baseline?.startDate) return null
  const record = compositionalProductionRecords(inspection).find(r => r.well === p.well), flowUnit = p.unit === 'Mscf/day' ? 'MSCF/DAY' : 'SM3/DAY', pressureUnit = p.unit === 'Mscf/day' ? 'PSIA' : 'BARSA'
  const gas = unique(summary, 'WGPR', p.well, flowUnit), pressure = unique(summary, 'WBHP', p.well, pressureUnit), bg = unique(baseline, 'WGPR', p.well, flowUnit), bp = unique(baseline, 'WBHP', p.well, pressureUnit)
  if (![gas, pressure, bg, bp].every(points) || gas.points.length !== pressure.points.length || gas.points.some((v, j) => v.timeDays !== pressure.points[j].timeDays)) return null
  const baselineGas = new Map(bg.points.map(p => [p.timeDays, p.value])), baselinePressure = new Map(bp.points.map(p => [p.timeDays, p.value]))
  const rows = gas.points.map((v, j) => ({ timeDays: v.timeDays, gasRate: v.value, bhp: pressure.points[j].value, baselineGasRate: baselineGas.get(v.timeDays) ?? null, baselineBhp: baselinePressure.get(v.timeDays) ?? null,
    attained: Math.abs(v.value - p.targetGasRate) <= .005, pressureLimited: v.value < p.targetGasRate - .005 && Math.abs(pressure.points[j].value - record.bhpLimit) <= .1 }))
  const changed = rows.filter(r => r.baselineGasRate !== null && (r.gasRate !== r.baselineGasRate || r.bhp !== r.baselineBhp)).length
  const limited = rows.filter(r => r.pressureLimited).length, attained = rows.filter(r => r.attained).length
  return { rows, changed, limited, attained, flowUnit, pressureUnit, bhpLimit: record.bhpLimit,
    consistent: rows.every(r => r.gasRate >= 0 && r.gasRate <= p.targetGasRate + .005 && r.bhp >= record.bhpLimit - .1),
    verified: changed > 0 && attained + limited === rows.length && rows.every(r => r.bhp >= record.bhpLimit - .1) }
}
