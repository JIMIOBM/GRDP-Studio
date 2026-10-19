import { compositionalReinjectionRecords, compositionalGasAccountingResponse } from './eclipseCompositionalReinjection.js'
export const compositionalInjectionPressureSchema = 'eclipse-compositional-injection-pressure-parameters/1'
const keys = ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'sourceSha256', 'lineNumber', 'unit', 'originalBhpUpperLimit', 'targetBhpUpperLimit']
const pressure = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 1e8
export const validCompositionalInjectionPressure = p => Boolean(p && typeof p === 'object' && !Array.isArray(p) && Object.keys(p).length === keys.length && keys.every(k => Object.hasOwn(p, k)) &&
  p.schemaVersion === compositionalInjectionPressureSchema && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && typeof p.well === 'string' && /^[A-Z0-9][A-Z0-9:_ .-]{0,7}$/.test(p.well) && p.unit === 'psia' &&
  typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile) && typeof p.sourceSha256 === 'string' && /^[a-f0-9]{64}$/.test(p.sourceSha256) &&
  Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 && p.lineNumber <= 2147483647 && pressure(p.originalBhpUpperLimit) && pressure(p.targetBhpUpperLimit) && p.originalBhpUpperLimit !== p.targetBhpUpperLimit)
export const matchesCompositionalInjectionPressure = (p, i) => validCompositionalInjectionPressure(p) && compositionalReinjectionRecords(i).some(r => r.injectionWell === p.well &&
  r.injectionLineNumber === p.lineNumber && r.injectionBhpUpperLimit === p.originalBhpUpperLimit) && p.sourceFile === i.compositionalReinjectionContext.sourceFile && p.sourceSha256 === i.compositionalReinjectionContext.sourceSha256
export const buildCompositionalInjectionPressure = ({ inspection, baselineRunId, well, targetBhpUpperLimit }) => {
  const r = compositionalReinjectionRecords(inspection).find(r => r.injectionWell === well), c = inspection?.compositionalReinjectionContext
  if (!r) return null
  const p = { schemaVersion: compositionalInjectionPressureSchema, baselineRunId, well, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.injectionLineNumber, unit: r.pressureUnit, originalBhpUpperLimit: r.injectionBhpUpperLimit, targetBhpUpperLimit }
  return validCompositionalInjectionPressure(p) ? p : null
}
const rawDifferences = (s, b) => {
  const metadata = v => JSON.stringify(Object.entries(v).filter(([k]) => k !== 'points').sort(([a], [b]) => a.localeCompare(b)))
  const validPoints = v => Array.isArray(v.points) && v.points.every(p => Number.isFinite(p.timeDays) && Number.isFinite(p.value))
  if (!Array.isArray(s?.series) || !Array.isArray(b?.series) || s.series.length !== b.series.length || !s.series.every(validPoints) || !b.series.every(validPoints)) return null
  const before = new Map(b.series.map(v => [v.vectorIndex, v]))
  if (before.size !== b.series.length || new Set(s.series.map(v => v.vectorIndex)).size !== s.series.length) return null
  const differences = []
  for (const v of s.series) {
    const original = before.get(v.vectorIndex)
    if (!original || metadata(v) !== metadata(original) || v.points.length !== original.points.length || v.points.some((p, j) => p.timeDays !== original.points[j].timeDays)) return null
    const changedPoints = v.points.filter((p, j) => p.value !== original.points[j].value).length
    if (changedPoints) differences.push({ vectorIndex: v.vectorIndex, keyword: v.keyword, objectName: v.objectName, changedPoints })
  }
  return differences
}
export const compositionalInjectionPressureResponse = (s, b, p, i) => {
  if (!matchesCompositionalInjectionPressure(p, i)) return null
  const r = i.compositionalReinjectionContext.records[0]
  const response = compositionalGasAccountingResponse(s, b, i, r.originalFraction, r.salesTarget, p.targetBhpUpperLimit)
  return response ? { ...response, rawVectorDifferences: rawDifferences(s, b) } : null
}
