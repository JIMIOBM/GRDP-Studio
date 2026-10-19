import { validCompositionalProductionContext } from './eclipseCompositionalProduction.js'
const shape = (v, keys) => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const integer = (v, min, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v >= min && v <= max
const positive = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 1e8
const name = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}$/.test(v)
const source = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(v)
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
export const compositionalFluidPropertySchema = 'eclipse-compositional-fluid-property-parameters/1'
export const compositionalTemperatureSchema = 'eclipse-compositional-temperature-parameters/1'
const propertyValue = (field, v) => field === 'ACF' ? typeof v === 'number' && Number.isFinite(v) && Math.abs(v) <= 1e8 : positive(v)
export const compositionalEosFields = ['MW', 'OMEGAA', 'OMEGAB']
export const compositionalEosPropertySchema = 'eclipse-compositional-eos-property-parameters/1'
const propertyUnit = field => field === 'PCRIT' ? 'psia' : field === 'TCRIT' ? 'degR' : field === 'MW' ? 'lb/lb-M' : 'dimensionless'
export const compositionalAcentricFactorSchema = 'eclipse-compositional-acentric-factor-parameters/1'
export const compositionalCriticalZSchema = 'eclipse-compositional-critical-z-parameters/1'
export const validCompositionalFluidProperty = p => shape(p, ['schemaVersion', 'baselineRunId', 'sourceFile', 'sourceSha256', 'lineNumber', 'field', 'componentIndex', 'componentName', 'unit', 'originalValue', 'targetValue']) &&
  (p.schemaVersion === compositionalFluidPropertySchema && p.field === 'PCRIT' && p.unit === 'psia' || p.schemaVersion === compositionalTemperatureSchema && p.field === 'TCRIT' && p.unit === 'degR' ||
    p.schemaVersion === compositionalCriticalZSchema && p.field === 'ZCRIT' && p.unit === 'dimensionless' || p.schemaVersion === compositionalAcentricFactorSchema && p.field === 'ACF' && p.unit === 'dimensionless' || p.schemaVersion === compositionalEosPropertySchema && compositionalEosFields.includes(p.field) && p.unit === propertyUnit(p.field)) &&
  integer(p.baselineRunId, 1) && source(p.sourceFile) && hash(p.sourceSha256) && integer(p.lineNumber, 1, 2147483647) &&
  integer(p.componentIndex, 1, 9) && name(p.componentName) && propertyValue(p.field, p.originalValue) && propertyValue(p.field, p.targetValue) && p.originalValue !== p.targetValue
export const validCompositionalFluidPropertyContext = (c, i, field = 'PCRIT') => {
  if (!['PCRIT', 'TCRIT', 'ZCRIT', 'ACF', ...compositionalEosFields].includes(field)) return false
  const eos = compositionalEosFields.includes(field), acentric = field === 'ACF', temperature = field === 'TCRIT', criticalZ = field === 'ZCRIT', unit = propertyUnit(field)
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'solver', 'componentCount', 'componentNames', 'equationOfState', 'records']) ||
    !(eos ? i?.schemaVersion === 'eclipse-data-inspection/22' : acentric ? ['eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion) : criticalZ ? ['eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion) : temperature ? ['eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion) : ['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion)) ||
    c.schemaVersion !== (eos ? 'eclipse-compositional-eos-property-context/1' : acentric ? 'eclipse-compositional-acentric-factor-context/1' : criticalZ ? 'eclipse-compositional-critical-z-context/1' : temperature ? 'eclipse-compositional-temperature-context/1' : 'eclipse-compositional-fluid-property-context/1') || i.unitSystem !== 'FIELD' ||
    !validCompositionalProductionContext(i.compositionalProductionContext, i) || !source(c.sourceFile) || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) ||
    c.solver !== 300 || c.componentCount !== 9 || c.equationOfState !== 'PR' || !Array.isArray(c.componentNames) || c.componentNames.length !== 9 ||
    !c.componentNames.every(name) || new Set(c.componentNames).size !== 9 || !Array.isArray(c.records) || c.records.length !== 9) return false
  const producer = i.compositionalProductionContext
  if (c.sourceFile !== producer.sourceFile || c.sourceSha256 !== producer.sourceSha256 || c.sourceSizeBytes !== producer.sourceSizeBytes ||
    c.componentNames.some((v, j) => v !== producer.componentNames[j])) return false
  return c.records.every((r, j) => shape(r, ['componentIndex', 'componentName', 'field', 'lineNumber', 'unit', 'originalValue']) &&
    r.componentIndex === j + 1 && r.componentName === c.componentNames[j] && r.field === field && r.unit === unit &&
    integer(r.lineNumber, j ? c.records[j - 1].lineNumber : 1, c.sourceSizeBytes) && propertyValue(field, r.originalValue))
}
export const validCompositionalTemperatureContext = (c, i) => validCompositionalFluidPropertyContext(c, i, 'TCRIT')
export const validCompositionalCriticalZContext = (c, i) => validCompositionalFluidPropertyContext(c, i, 'ZCRIT')
export const validCompositionalAcentricFactorContext = (c, i) => validCompositionalFluidPropertyContext(c, i, 'ACF')
const contextKey = field => field === 'ACF' ? 'compositionalAcentricFactorContext' : field === 'ZCRIT' ? 'compositionalCriticalZContext' : field === 'TCRIT' ? 'compositionalTemperatureContext' : 'compositionalFluidPropertyContext'
export const compositionalEosPropertyContext = (i, field) => Array.isArray(i?.compositionalEosPropertyContexts) ? i.compositionalEosPropertyContexts.find(c => c?.records?.[0]?.field === field) : null
const sourceContext = (i, field) => compositionalEosFields.includes(field) ? compositionalEosPropertyContext(i, field) : i?.[contextKey(field)]
export const validCompositionalEosPropertyContexts = (c, i) => Array.isArray(c) && c.length === 3 && compositionalEosFields.every((field, j) => validCompositionalFluidPropertyContext(c[j], i, field))
export const compositionalFluidPropertyRecords = (i, field = 'PCRIT') => validCompositionalFluidPropertyContext(sourceContext(i, field), i, field) ? sourceContext(i, field).records : []
export const matchesCompositionalFluidProperty = (p, i) => validCompositionalFluidProperty(p) && compositionalFluidPropertyRecords(i, p.field).some(r => r.componentIndex === p.componentIndex &&
  r.componentName === p.componentName && r.field === p.field && r.unit === p.unit && r.lineNumber === p.lineNumber && r.originalValue === p.originalValue) &&
  p.sourceFile === sourceContext(i, p.field).sourceFile && p.sourceSha256 === sourceContext(i, p.field).sourceSha256
export const buildCompositionalFluidProperty = ({ inspection, baselineRunId, componentIndex, targetValue, field = 'PCRIT' }) => {
  const r = compositionalFluidPropertyRecords(inspection, field).find(r => r.componentIndex === componentIndex), c = sourceContext(inspection, field)
  if (!r) return null
  const p = { schemaVersion: compositionalEosFields.includes(field) ? compositionalEosPropertySchema : field === 'ACF' ? compositionalAcentricFactorSchema : field === 'ZCRIT' ? compositionalCriticalZSchema : field === 'TCRIT' ? compositionalTemperatureSchema : compositionalFluidPropertySchema, baselineRunId, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.lineNumber, field: r.field, componentIndex, componentName: r.componentName, unit: r.unit, originalValue: r.originalValue, targetValue }
  return validCompositionalFluidProperty(p) ? p : null
}
const seriesMap = s => {
  if (!Array.isArray(s?.series) || !s.series.length || s.series.length > 65535) return null
  const map = new Map()
  for (const v of s.series) {
    if (!shape(v, ['vectorIndex', 'keyword', 'objectName', 'number', 'unit', 'points']) || !integer(v.vectorIndex, 0) || map.has(v.vectorIndex) ||
      !Array.isArray(v.points) || !v.points.length || !v.points.every((p, j) => shape(p, ['timeDays', 'value']) && typeof p.timeDays === 'number' && Number.isFinite(p.timeDays) && p.timeDays >= 0 &&
        typeof p.value === 'number' && Number.isFinite(p.value) && (!j || p.timeDays > v.points[j - 1].timeDays))) return null
    map.set(v.vectorIndex, v)
  }
  return map
}
export const compositionalFluidPropertyResponse = (s, b, p, i) => {
  if (!matchesCompositionalFluidProperty(p, i) || s?.startDate !== b?.startDate) return null
  const current = seriesMap(s), before = seriesMap(b)
  if (!current || !before || current.size !== before.size) return null
  for (const [index, v] of current) {
    const original = before.get(index)
    if (!original || ['keyword', 'objectName', 'number', 'unit'].some(k => v[k] !== original[k])) return null
  }
  const producer = i.compositionalProductionContext.records[0].well
  const definitions = [['FOPT', null, 'STB'], ['FOPR', null, 'STB/DAY'], ['FPR', null, 'PSIA'], ['WBHP', producer, 'PSIA']]
  const metrics = []
  for (const [keyword, objectName, unit] of definitions) {
    const originals = b.series.filter(v => v.keyword === keyword && v.objectName === objectName)
    if (!originals.length) return null
    for (const original of originals) {
      const v = current.get(original.vectorIndex)
      if (typeof v.unit !== 'string' || v.unit.toUpperCase() !== unit) return null
      const grid = new Map(original.points.map(p => [p.timeDays, p.value]))
      const common = v.points.filter(p => grid.has(p.timeDays))
      const baselineFinal = original.points.at(-1), currentFinal = v.points.at(-1)
      metrics.push({ vectorIndex: v.vectorIndex, keyword, objectName, number: v.number, unit: v.unit,
        baselineFinal, currentFinal, finalDifference: currentFinal.timeDays === baselineFinal.timeDays ? currentFinal.value - baselineFinal.value : null,
        commonPoints: common.length, changedPoints: common.filter(p => p.value !== grid.get(p.timeDays)).length })
    }
  }
  const completePeriod = metrics.every(v => v.currentFinal.timeDays === v.baselineFinal.timeDays)
  return { metrics, completePeriod, verified: completePeriod && metrics.every(v => v.commonPoints > 0),
    changedMetrics: metrics.filter(v => v.changedPoints > 0).length, rawVectorCount: current.size, endDays: metrics[0].baselineFinal.timeDays }
}
