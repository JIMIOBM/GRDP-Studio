import { validCompositionalFluidPropertyContext, validCompositionalTemperatureContext, validCompositionalCriticalZContext, validCompositionalAcentricFactorContext, validCompositionalEosPropertyContexts } from './eclipseCompositionalFluidProperty.js'
import { validCompositionalProductionContext } from './eclipseCompositionalProduction.js'
import { validCompositionalReinjectionContext } from './eclipseCompositionalReinjection.js'
import { validCompositionalPortableContext } from './eclipseCompositionalForecast.js'
const fields = (value, names) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === names.length && names.every(n => Object.hasOwn(value, n))
const integer = (n, low, high = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= low && n <= high
const safe = value => typeof value === 'string' && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f/\\]/.test(value)
export const validCompositionalMetadata = value => fields(value, ['solver', 'modelType', 'componentCount', 'componentNames', 'equationOfState']) &&
  value.solver === 300 && value.modelType === 'COMPOSITIONAL' && value.equationOfState === 'PR' && integer(value.componentCount, 1, 100) &&
  Array.isArray(value.componentNames) && value.componentNames.length === value.componentCount && new Set(value.componentNames).size === value.componentCount &&
  value.componentNames.every(n => safe(n) && n.length <= 8 && /^[\x20-\x7e]+$/.test(n))
const optionalContexts = value => value?.schemaVersion === 'eclipse-data-inspection/18' ? ['compositionalRestartContext'] : ['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value?.schemaVersion)
  ? ['compositionalProductionContext', 'compositionalFluidPropertyContext', ...(['eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) ? ['compositionalTemperatureContext'] : []), ...(['eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) ? ['compositionalCriticalZContext'] : []), ...(['eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) ? ['compositionalAcentricFactorContext'] : []), ...(value.schemaVersion === 'eclipse-data-inspection/22' ? ['compositionalEosPropertyContexts'] : []), ...(Object.hasOwn(value, 'compositionalReinjectionContext') ? ['compositionalReinjectionContext'] : [])]
  : value?.schemaVersion === 'eclipse-data-inspection/16' ? ['compositionalProductionContext', 'compositionalReinjectionContext']
    : Object.hasOwn(value || {}, 'compositionalProductionContext') ? ['compositionalProductionContext'] : []
export const validCompositionalInspection = value => fields(value, ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline', 'packageFiles', 'simulation', ...optionalContexts(value)]) &&
  (!Object.hasOwn(value, 'compositionalProductionContext') || validCompositionalProductionContext(value.compositionalProductionContext, value)) &&
  ['eclipse-data-inspection/14', 'eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/18', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) &&
  (value.schemaVersion !== 'eclipse-data-inspection/18' || validCompositionalPortableContext(value.compositionalRestartContext, value)) &&
  (value.schemaVersion !== 'eclipse-data-inspection/16' || validCompositionalReinjectionContext(value.compositionalReinjectionContext, value)) &&
  (value.schemaVersion !== 'eclipse-data-inspection/22' || validCompositionalEosPropertyContexts(value.compositionalEosPropertyContexts, value)) &&
  (!['eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) || validCompositionalAcentricFactorContext(value.compositionalAcentricFactorContext, value)) &&
  (!['eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) || validCompositionalCriticalZContext(value.compositionalCriticalZContext, value)) &&
  (!['eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) || validCompositionalTemperatureContext(value.compositionalTemperatureContext, value)) &&
  (!['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(value.schemaVersion) || validCompositionalFluidPropertyContext(value.compositionalFluidPropertyContext, value) &&
    (!Object.hasOwn(value, 'compositionalReinjectionContext') || validCompositionalReinjectionContext(value.compositionalReinjectionContext, value))) && validCompositionalMetadata(value.simulation) &&
  safe(value.caseName) && /\.DATA$/i.test(value.caseName) && Array.isArray(value.sections) && Array.isArray(value.phases) && Array.isArray(value.wellNames) && Array.isArray(value.scheduleTimeline) &&
  ['FIELD', 'METRIC'].includes(value.unitSystem) && value.dimensions && ['nx', 'ny', 'nz'].every(k => integer(value.dimensions[k], 1, 1000000)) &&
  Array.isArray(value.packageFiles) && value.packageFiles.length > 0 && value.packageFiles.length <= 4096 && value.packageFiles.every(f =>
    fields(f, ['relativePath', 'sizeBytes', 'sha256']) && typeof f.relativePath === 'string' && f.relativePath.length > 0 && !f.relativePath.includes('\\') &&
    !f.relativePath.startsWith('/') && !f.relativePath.split('/').some(s => !s || s === '.' || s === '..' || s.includes(':')) &&
    integer(f.sizeBytes, 0, 64 * 1024 * 1024) && typeof f.sha256 === 'string' && /^[0-9a-f]{64}$/.test(f.sha256))
export const componentOf = (series, simulation) => {
  const match = series?.keyword?.match(/^[BFGW][XYZ]MF_([0-9]+)$/)
  if (!match || !validCompositionalMetadata(simulation)) return null
  const index = Number(match[1])
  return integer(index, 1, simulation.componentCount) ? { index, name: simulation.componentNames[index - 1] } : null
}
export const compositionalResult = (run, inspection) => {
  const value = run?.result
  if (!validCompositionalInspection(inspection) || !value || value.schemaVersion !== 'eclipse-summary-result/2' || value.modelKind !== 'eclipse_300' || value.runTask !== 'eclipse' ||
    !['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status) || !['VALID_FULL', 'VALID_PARTIAL'].includes(run.resultContract) || value.resultContract !== run.resultContract ||
    !validCompositionalMetadata(value.simulation) || ['solver', 'modelType', 'componentCount', 'equationOfState'].some(k => value.simulation[k] !== inspection.simulation[k]) ||
    value.simulation.componentNames.some((name, i) => name !== inspection.simulation.componentNames[i]) || value.caseName !== inspection.caseName) return null
  const e = value.eclEnd
  if (!fields(e, ['comments', 'warnings', 'problems', 'errors', 'bugs']) || !['comments', 'warnings'].every(k => e[k] === null || integer(e[k], 0)) || !['problems', 'errors', 'bugs'].every(k => integer(e[k], 0)) || e.errors !== 0 || e.bugs !== 0 ||
    (run.resultContract === 'VALID_FULL' ? e.problems !== 0 : e.problems === 0)) return null
  const summary = value.summary
  if (!fields(summary, ['startDate', 'timeVectorIndex', 'series']) || typeof summary.startDate !== 'string' || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(summary.startDate) ||
    Number(summary.startDate.slice(0, 4)) < 1 || !Number.isFinite(Date.parse(summary.startDate + 'T00:00:00Z')) || new Date(summary.startDate + 'T00:00:00Z').toISOString().slice(0, 10) !== summary.startDate ||
    !Array.isArray(summary.series) || !summary.series.length || summary.series.length > 65535 || !integer(summary.timeVectorIndex, 0, summary.series.length)) return null
  const grid = summary.series[0]?.points?.map(p => p.timeDays)
  if (!grid?.length || !grid.every((t, i) => typeof t === 'number' && Number.isFinite(t) && t >= 0 && (!i || t > grid[i - 1]))) return null
  let previous = -1, total = 0
  const cells = inspection.dimensions.nx * inspection.dimensions.ny * inspection.dimensions.nz
  for (const s of summary.series) {
    if (!fields(s, ['vectorIndex', 'keyword', 'objectName', 'number', 'unit', 'points']) || !integer(s.vectorIndex, 0, summary.series.length) || s.vectorIndex === summary.timeVectorIndex || s.vectorIndex <= previous ||
      typeof s.keyword !== 'string' || !/^[A-Z0-9_]{1,8}$/.test(s.keyword) || ['TIME', 'DAYS'].includes(s.keyword) || !integer(s.number, -2147483648, 2147483647) ||
      !(s.objectName === null || safe(s.objectName)) || !(s.unit === null || typeof s.unit === 'string' && s.unit.length > 0 && s.unit.length <= 128 && !/[\u0000-\u001f\u007f\\]/.test(s.unit)) ||
      !Array.isArray(s.points) || s.points.length !== grid.length || s.points.some((p, i) => !fields(p, ['timeDays', 'value']) || p.timeDays !== grid[i] || typeof p.value !== 'number' || !Number.isFinite(p.value))) return null
    if (/^[BFGW][XYZ]MF_[0-9]+$/.test(s.keyword) && !componentOf(s, value.simulation) || s.keyword.startsWith('B') && s.number > cells) return null
    total += s.points.length
    if (total > 4000000) return null
    previous = s.vectorIndex
  }
  return value
}
export const compositionalObject = (s, dimensions) => {
  if (s.objectName === ':+:+:+:+') return `未定义${s.keyword.startsWith('W') ? '井' : s.keyword.startsWith('G') ? '组' : ''}槽位 ${s.number}`
  if (s.keyword.startsWith('B') && s.number > 0) {
    const cell = s.number - 1, nx = dimensions.nx, ny = dimensions.ny
    return `网格 (${cell % nx + 1}, ${Math.floor(cell / nx) % ny + 1}, ${Math.floor(cell / (nx * ny)) + 1})`
  }
  return s.objectName || (s.keyword.startsWith('F') ? '全场' : '模拟器')
}
const csv = value => '"' + String(value ?? '').replaceAll('"', '""') + '"'
export const compositionalCsv = result => ['vectorIndex,keyword,objectName,number,unit,timeDays,value', ...result.summary.series.flatMap(s =>
  s.points.map(p => [s.vectorIndex, s.keyword, s.objectName, s.number, s.unit, p.timeDays, p.value].map(csv).join(',')))].join('\r\n')
