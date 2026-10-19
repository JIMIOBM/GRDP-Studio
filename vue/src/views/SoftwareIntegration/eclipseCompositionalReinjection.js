import { validCompositionalProductionContext } from './eclipseCompositionalProduction.js'
export const compositionalReinjectionSchema = 'eclipse-compositional-reinjection-parameters/1'
const shape = (v, keys) => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const integer = (v, min = 1, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(v) && v >= min && v <= max
const number = (v, min, max) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max
const positive = v => number(v, Number.MIN_VALUE, 1e8)
const fraction = v => number(v, 0, 1)
const source = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(v)
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
export const validCompositionalReinjection = p => shape(p, ['schemaVersion', 'baselineRunId', 'group', 'phase', 'sourceFile', 'sourceSha256', 'lineNumber', 'unit', 'originalFraction', 'targetFraction']) &&
  p.schemaVersion === compositionalReinjectionSchema && integer(p.baselineRunId) && p.group === 'FIELD' && p.phase === 'GAS' && p.unit === 'fraction' &&
  source(p.sourceFile) && hash(p.sourceSha256) && integer(p.lineNumber, 1, 2147483647) && fraction(p.originalFraction) && fraction(p.targetFraction) && p.originalFraction !== p.targetFraction
const timeline = (i, c, r, initial) => {
  if (!Array.isArray(i.scheduleTimeline) || !i.scheduleTimeline.length) return null
  let elapsed = 0, before = 0, previous = initial, after = false
  for (const e of i.scheduleTimeline) {
    if (!shape(e, ['kind', 'steps', 'sourceFile', 'lineNumber']) || e.kind !== 'TSTEP' || e.sourceFile !== c.sourceFile ||
      !integer(e.lineNumber, previous + 1, c.sourceSizeBytes) || !Array.isArray(e.steps) || !e.steps.length) return null
    previous = e.lineNumber
    for (const s of e.steps) {
      if (typeof s !== 'string' || !/^[+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?$/.test(s) || !positive(Number(s))) return null
      elapsed += Number(s)
    }
    if (previous < r.shutLineNumber) before = elapsed; else after = true
    if (!Number.isFinite(elapsed) || elapsed > 1e7) return null
  }
  return after && before === r.shutAfterDays && elapsed > before ? { endDays: elapsed } : null
}
export const validCompositionalReinjectionContext = (c, i) => {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'solver', 'componentCount', 'componentNames', 'equationOfState', 'records']) ||
    c.schemaVersion !== 'eclipse-compositional-reinjection-context/1' || !['eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(i?.schemaVersion) || i.unitSystem !== 'FIELD' ||
    !validCompositionalProductionContext(i.compositionalProductionContext, i) || i.compositionalProductionContext.records.length !== 1 ||
    !Array.isArray(i.wellNames) || i.wellNames.length !== 2 || !source(c.sourceFile) || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) ||
    c.solver !== 300 || c.componentCount !== 9 || c.equationOfState !== 'PR' || !Array.isArray(c.componentNames) || c.componentNames.length !== 9 ||
    c.componentNames.some((n, j) => n !== i.simulation.componentNames[j]) || !Array.isArray(c.records) || c.records.length !== 1) return false
  const pc = i.compositionalProductionContext, p = pc.records[0], r = c.records[0]
  if (c.sourceFile !== pc.sourceFile || c.sourceSha256 !== pc.sourceSha256 || c.sourceSizeBytes !== pc.sourceSizeBytes ||
    !shape(r, ['group', 'phase', 'controlMode', 'lineNumber', 'originalFraction', 'gasSourceKind', 'gasSourceGroup', 'gasSourceLineNumber', 'salesTarget', 'salesLineNumber',
      'rateUnit', 'pressureUnit', 'injectionWell', 'injectionBhpUpperLimit', 'injectionLineNumber', 'productionWell', 'productionGasTarget', 'productionBhpLowerLimit', 'shutAfterDays', 'shutLineNumber']) ||
    r.group !== 'FIELD' || r.phase !== 'GAS' || r.controlMode !== 'REIN' || !fraction(r.originalFraction) || r.gasSourceKind !== 'GV' || r.gasSourceGroup !== 'FIELD' ||
    !number(r.salesTarget, 0, 1e8) || r.rateUnit !== 'Mscf/day' || r.pressureUnit !== 'psia' || typeof r.injectionWell !== 'string' || !/^[A-Z0-9][A-Z0-9:_ .-]{0,7}$/.test(r.injectionWell) ||
    r.productionWell !== p.well || r.productionWell !== r.productionWell.toUpperCase() || r.injectionWell === r.productionWell ||
    !positive(r.injectionBhpUpperLimit) || !positive(r.productionGasTarget) || !positive(r.productionBhpLowerLimit) || r.productionGasTarget !== p.originalGasRate || r.productionBhpLowerLimit !== p.bhpLimit ||
    !positive(r.shutAfterDays) || r.shutAfterDays > 1e7 || new Set(i.wellNames).size !== 2 || !i.wellNames.includes(r.injectionWell) || !i.wellNames.includes(r.productionWell)) return false
  const lines = ['lineNumber', 'gasSourceLineNumber', 'salesLineNumber', 'injectionLineNumber'].map(k => r[k])
  if (lines.some(v => !integer(v, 1, c.sourceSizeBytes)) || new Set(lines).size !== 4) return false
  const initial = Math.max(...lines, p.lineNumber, p.bhpLineNumber)
  return integer(r.shutLineNumber, initial + 1, c.sourceSizeBytes) && timeline(i, c, r, initial) !== null
}
export const compositionalReinjectionRecords = i => validCompositionalReinjectionContext(i?.compositionalReinjectionContext, i) ? i.compositionalReinjectionContext.records : []
export const matchesCompositionalReinjection = (p, i) => validCompositionalReinjection(p) && compositionalReinjectionRecords(i).some(r => r.group === p.group && r.phase === p.phase &&
  r.lineNumber === p.lineNumber && r.originalFraction === p.originalFraction) && p.sourceFile === i.compositionalReinjectionContext.sourceFile && p.sourceSha256 === i.compositionalReinjectionContext.sourceSha256
export const buildCompositionalReinjection = ({ inspection, baselineRunId, group, targetFraction }) => {
  const r = compositionalReinjectionRecords(inspection).find(r => r.group === group), c = inspection?.compositionalReinjectionContext
  if (!r) return null
  const p = { schemaVersion: compositionalReinjectionSchema, baselineRunId, group, phase: r.phase, sourceFile: c.sourceFile, sourceSha256: c.sourceSha256,
    lineNumber: r.lineNumber, unit: 'fraction', originalFraction: r.originalFraction, targetFraction }
  return validCompositionalReinjection(p) ? p : null
}
const points = s => Array.isArray(s?.points) && s.points.length > 0 && s.points.every((p, j) => shape(p, ['timeDays', 'value']) && typeof p.value === 'number' && Number.isFinite(p.value) &&
  typeof p.timeDays === 'number' && Number.isFinite(p.timeDays) && p.timeDays >= 0 && (!j || p.timeDays > s.points[j - 1].timeDays))
export const compositionalReinjectionResponse = (s, b, p, i) => {
  if (!matchesCompositionalReinjection(p, i)) return null
  return compositionalGasAccountingResponse(s, b, i, p.targetFraction, i.compositionalReinjectionContext.records[0].salesTarget)
}
export const compositionalGasAccountingResponse = (s, b, i, targetFraction, salesTarget, injectionBhpUpperLimit = null) => {
  if (!fraction(targetFraction) || !number(salesTarget, 0, 1e8) || !compositionalReinjectionRecords(i).length || s?.startDate !== b?.startDate || !Array.isArray(s?.series) || !Array.isArray(b?.series)) return null
  const r = compositionalReinjectionRecords(i)[0], c = i.compositionalReinjectionContext
  const cap = injectionBhpUpperLimit ?? r.injectionBhpUpperLimit
  if (!positive(cap)) return null
  const pair = (key, name, unit) => {
    const a = s.series.filter(v => v.keyword === key && v.objectName === name), before = b.series.filter(v => v.keyword === key && v.objectName === name)
    if (a.length !== 1 || before.length !== 1 || !points(a[0]) || !points(before[0]) || typeof a[0].unit !== 'string' || a[0].unit.toUpperCase() !== unit ||
      a[0].unit !== before[0].unit || a[0].vectorIndex !== before[0].vectorIndex || a[0].number !== before[0].number) return null
    return [a[0], before[0]]
  }
  const definitions = { produced: ['FGPR', null, 'MSCF/DAY'], injected: ['FGIR', null, 'MSCF/DAY'], total: ['FGIT', null, 'MSCF'],
    wellInjected: ['WGIR', r.injectionWell, 'MSCF/DAY'], wellTotal: ['WGIT', r.injectionWell, 'MSCF'],
    injectionBhp: ['WBHP', r.injectionWell, 'PSIA'], productionBhp: ['WBHP', r.productionWell, 'PSIA'], wellProduced: ['WGPR', r.productionWell, 'MSCF/DAY'] }
  const data = Object.fromEntries(Object.entries(definitions).map(([key, values]) => [key, pair(...values)]))
  if (Object.values(data).some(v => !v)) return null
  if (data.total.some(v => v.points.some((p, j) => p.value < 0 || j > 0 && p.value < v.points[j - 1].value))) return null
  const currentGrid = data.produced[0].points.map(v => v.timeDays), beforeGrid = data.produced[1].points.map(v => v.timeDays)
  if (Object.values(data).some(pair => pair.some((v, j) => v.points.length !== (j ? beforeGrid : currentGrid).length || v.points.some((p, k) => p.timeDays !== (j ? beforeGrid : currentGrid)[k])))) return null
  const before = Object.fromEntries(Object.entries(data).map(([key, v]) => [key, new Map(v[1].points.map(p => [p.timeDays, p.value]))]))
  const initial = Math.max(r.lineNumber, r.gasSourceLineNumber, r.salesLineNumber, r.injectionLineNumber, i.compositionalProductionContext.records[0].lineNumber, i.compositionalProductionContext.records[0].bhpLineNumber)
  const endDays = timeline(i, c, r, initial).endDays, timeTolerance = Math.max(1e-6, endDays * 1e-7)
  const rows = currentGrid.map((timeDays, j) => {
    const v = Object.fromEntries(Object.entries(data).map(([key, values]) => [key, values[0].points[j].value]))
    const availableGas = Math.max(0, v.produced - salesTarget), target = targetFraction * availableGas, planShut = timeDays > r.shutAfterDays + timeTolerance
    const rateTolerance = Math.max(.01, Math.abs(target) * 1e-6), pressureTolerance = Math.max(.1, cap * 1e-6, r.productionBhpLowerLimit * 1e-6)
    const attained = !planShut && Math.abs(v.injected - target) <= rateTolerance
    const pressureLimited = !planShut && v.injected < target - rateTolerance && Math.abs(v.injectionBhp - cap) <= pressureTolerance
    const consistent = v.produced >= 0 && v.produced <= r.productionGasTarget + Math.max(.01, r.productionGasTarget * 1e-6) && v.injected >= 0 &&
      Math.abs(v.injected - v.wellInjected) <= rateTolerance && Math.abs(v.produced - v.wellProduced) <= rateTolerance && Math.abs(v.total - v.wellTotal) <= 1e-6 &&
      v.injectionBhp >= 0 && v.injectionBhp <= cap + pressureTolerance && v.productionBhp >= 0 &&
      (v.produced <= .000001 || v.productionBhp >= r.productionBhpLowerLimit - pressureTolerance) && (planShut ? v.injected <= rateTolerance : v.injected <= target + rateTolerance)
    return { timeDays, producedGas: v.produced, availableGas, plannedTarget: target, actualInjection: v.injected, injectionBhp: v.injectionBhp, productionBhp: v.productionBhp,
      cumulativeInjection: v.total, baselineInjection: before.injected.get(timeDays) ?? null, baselineProduction: before.produced.get(timeDays) ?? null,
      baselineInjectionBhp: before.injectionBhp.get(timeDays) ?? null, baselineProductionBhp: before.productionBhp.get(timeDays) ?? null,
      difference: before.injected.has(timeDays) ? v.injected - before.injected.get(timeDays) : null,
      attained, pressureLimited, planShut, consistent, state: planShut ? '原计划关井' : attained ? availableGas === 0 ? '没有可用气' : '达到可用气回注目标' : pressureLimited ? '注入压力受限' : '目标与实际需核查' }
  })
  const consistent = rows.every(v => v.consistent), closed = rows.filter(v => v.planShut).length, active = rows.length - closed
  const completePeriod = Math.abs(currentGrid.at(-1) - endDays) <= timeTolerance && Math.abs(beforeGrid.at(-1) - endDays) <= timeTolerance
  return { rows, consistent, completePeriod, verified: consistent && completePeriod && active > 0 && rows.every(v => v.planShut || v.attained || v.pressureLimited),
    attained: rows.filter(v => v.attained).length, limited: rows.filter(v => v.pressureLimited).length, active, closed,
    changed: rows.filter(v => v.baselineInjection !== null && (v.actualInjection !== v.baselineInjection || v.producedGas !== v.baselineProduction || v.productionBhp !== v.baselineProductionBhp)).length,
    common: rows.filter(v => v.baselineInjection !== null).length, final: rows.at(-1), baselineCumulativeInjection: data.total[1].points.at(-1).value,
    salesTarget, injectionBhpUpperLimit: cap, productionBhpLowerLimit: r.productionBhpLowerLimit,
    productionGasTarget: r.productionGasTarget, shutAfterDays: r.shutAfterDays, endDays, flowUnit: 'MSCF/DAY', pressureUnit: 'PSIA', cumulativeUnit: 'MSCF' }
}
