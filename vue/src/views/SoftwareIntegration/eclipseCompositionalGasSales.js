import { compositionalReinjectionRecords, compositionalGasAccountingResponse } from './eclipseCompositionalReinjection.js'
export const compositionalGasSalesSchema = 'eclipse-compositional-gas-sales-parameters/1'
const keys = ['schemaVersion', 'baselineRunId', 'group', 'phase', 'sourceFile', 'sourceSha256', 'lineNumber', 'unit', 'originalSalesTarget', 'targetSalesTarget']
const rate = n => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 1e8
export const validCompositionalGasSales = p => Boolean(p && typeof p === 'object' && !Array.isArray(p) && Object.keys(p).length === keys.length && keys.every(k => Object.hasOwn(p, k)) &&
  p.schemaVersion === compositionalGasSalesSchema && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && p.group === 'FIELD' && p.phase === 'GAS' && p.unit === 'Mscf/day' &&
  typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile) && typeof p.sourceSha256 === 'string' && /^[a-f0-9]{64}$/.test(p.sourceSha256) &&
  Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 && p.lineNumber <= 2147483647 && rate(p.originalSalesTarget) && rate(p.targetSalesTarget) && p.originalSalesTarget !== p.targetSalesTarget)
export const matchesCompositionalGasSales = (p, i) => validCompositionalGasSales(p) && compositionalReinjectionRecords(i).some(r => r.group === p.group && r.phase === p.phase &&
  r.salesLineNumber === p.lineNumber && r.salesTarget === p.originalSalesTarget) && p.sourceFile === i.compositionalReinjectionContext.sourceFile && p.sourceSha256 === i.compositionalReinjectionContext.sourceSha256
export const buildCompositionalGasSales = ({ inspection, baselineRunId, group, targetSalesTarget }) => {
  const r = compositionalReinjectionRecords(inspection).find(r => r.group === group), c = inspection?.compositionalReinjectionContext
  if (!r) return null
  const p = { schemaVersion: compositionalGasSalesSchema, baselineRunId, group, phase: r.phase, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.salesLineNumber, unit: r.rateUnit, originalSalesTarget: r.salesTarget, targetSalesTarget }
  return validCompositionalGasSales(p) ? p : null
}
export const compositionalGasSalesResponse = (s, b, p, i) => matchesCompositionalGasSales(p, i)
  ? compositionalGasAccountingResponse(s, b, i, i.compositionalReinjectionContext.records[0].originalFraction, p.targetSalesTarget) : null
