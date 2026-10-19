import { compositionalObject, compositionalCsv } from './eclipseCompositional.js'
import { validThermalRockContext } from './eclipseThermalRock.js'
import { validThermalInjectionContext } from './eclipseThermalInjection.js'

const fields = (value, names) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === names.length && names.every(n => Object.hasOwn(value, n))
const integer = (n, low, high = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= low && n <= high
const safe = value => typeof value === 'string' && value.length > 0 && value.length <= 128 && !/[\u0000-\u001f\u007f/\\]/.test(value)
export const validThermalMetadata = value => fields(value, ['solver', 'modelType', 'componentCount', 'componentNames', 'fluidModel', 'summaryProgramIdentifier']) &&
  value.solver === 300 && value.modelType === 'THERMAL' && value.fluidModel === 'LIVEOIL' && value.summaryProgramIdentifier === 500 &&
  integer(value.componentCount, 1, 100) && Array.isArray(value.componentNames) && value.componentNames.length === value.componentCount &&
  new Set(value.componentNames).size === value.componentCount && value.componentNames.every(n => safe(n) && n.length <= 8 && /^[\x20-\x7e]+$/.test(n))
export const validThermalInspection = value => fields(value, ['schemaVersion', 'caseName', 'sections', 'unitSystem', 'phases', 'dimensions', 'wellNames', 'scheduleTimeline', 'packageFiles', 'simulation', ...(Object.hasOwn(value || {}, 'thermalInjectionContext') ? ['thermalInjectionContext'] : []), ...(value?.schemaVersion === 'eclipse-data-inspection/23' ? ['thermalRockContext'] : [])]) && (!Object.hasOwn(value, 'thermalInjectionContext') || validThermalInjectionContext(value.thermalInjectionContext, value)) &&
  ['eclipse-data-inspection/15', 'eclipse-data-inspection/23'].includes(value.schemaVersion) && (value.schemaVersion !== 'eclipse-data-inspection/23' || validThermalRockContext(value.thermalRockContext, value)) && validThermalMetadata(value.simulation) && safe(value.caseName) && /\.DATA$/i.test(value.caseName) &&
  ['FIELD', 'METRIC'].includes(value.unitSystem) && fields(value.dimensions, ['nx', 'ny', 'nz']) && ['nx', 'ny', 'nz'].every(k => integer(value.dimensions[k], 1, 1000000)) &&
  Array.isArray(value.phases) && value.phases.join(',') === 'OIL,WATER,GAS' && Array.isArray(value.sections) && Array.isArray(value.wellNames) && value.wellNames.every(safe) &&
  Array.isArray(value.scheduleTimeline) && Array.isArray(value.packageFiles) && value.packageFiles.length > 0 && value.packageFiles.length <= 4096 &&
  value.packageFiles.every(f => fields(f, ['relativePath', 'sizeBytes', 'sha256']) && typeof f.relativePath === 'string' && f.relativePath.length > 0 &&
    !/[\u0000-\u001f\u007f\\]/.test(f.relativePath) && !f.relativePath.startsWith('/') && !f.relativePath.split('/').some(s => !s || s === '.' || s === '..' || s.includes(':')) &&
    integer(f.sizeBytes, 0, 64 * 1024 * 1024) && typeof f.sha256 === 'string' && /^[0-9a-f]{64}$/.test(f.sha256))

export const thermalResult = (run, inspection) => {
  const r = run?.result
  if (!validThermalInspection(inspection) || !r || r.schemaVersion !== 'eclipse-summary-result/3' || r.modelKind !== 'eclipse_300' || r.runTask !== 'eclipse' ||
    !['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status) || !['VALID_FULL', 'VALID_PARTIAL'].includes(run.resultContract) || r.resultContract !== run.resultContract ||
    r.caseName !== inspection.caseName || !validThermalMetadata(r.simulation) || ['solver', 'modelType', 'componentCount', 'fluidModel', 'summaryProgramIdentifier'].some(k => r.simulation[k] !== inspection.simulation[k]) ||
    r.simulation.componentNames.some((n, i) => n !== inspection.simulation.componentNames[i])) return null
  const e = r.eclEnd
  if (!fields(e, ['comments', 'warnings', 'problems', 'errors', 'bugs']) || !['comments', 'warnings'].every(k => e[k] === null || integer(e[k], 0)) ||
    !['problems', 'errors', 'bugs'].every(k => integer(e[k], 0)) || e.errors !== 0 || e.bugs !== 0 || (run.resultContract === 'VALID_FULL' ? e.problems !== 0 : e.problems === 0)) return null
  const s = r.summary
  if (!fields(s, ['startDate', 'timeVectorIndex', 'series']) || !/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(s.startDate) ||
    Number(s.startDate.slice(0, 4)) < 1 || !Number.isFinite(Date.parse(s.startDate + 'T00:00:00Z')) || new Date(s.startDate + 'T00:00:00Z').toISOString().slice(0, 10) !== s.startDate ||
    !Array.isArray(s.series) || !s.series.length || s.series.length > 65535 || !integer(s.timeVectorIndex, 0, s.series.length)) return null
  const clock = s.series[0]?.points?.map(p => p.timeDays)
  if (!clock?.length || !clock.every((t, i) => typeof t === 'number' && Number.isFinite(t) && t >= 0 && (!i || t > clock[i - 1]))) return null
  let previous = -1, values = 0
  const cells = inspection.dimensions.nx * inspection.dimensions.ny * inspection.dimensions.nz
  for (const v of s.series) {
    if (!fields(v, ['vectorIndex', 'keyword', 'objectName', 'number', 'unit', 'points']) || !integer(v.vectorIndex, 0, s.series.length) || v.vectorIndex === s.timeVectorIndex || v.vectorIndex <= previous ||
      !/^[A-Z0-9_]{1,8}$/.test(v.keyword) || ['TIME', 'DAYS'].includes(v.keyword) || !integer(v.number, -2147483648, 2147483647) ||
      !(v.objectName === null || safe(v.objectName)) || !(v.unit === null || typeof v.unit === 'string' && v.unit.length > 0 && v.unit.length <= 128 && !/[\u0000-\u001f\u007f\\]/.test(v.unit)) ||
      !Array.isArray(v.points) || v.points.length !== clock.length || v.points.some((p, i) => !fields(p, ['timeDays', 'value']) || p.timeDays !== clock[i] || typeof p.value !== 'number' || !Number.isFinite(p.value)) ||
      v.keyword.startsWith('B') && v.number > cells) return null
    if (['BTEMP', 'WTEMP'].includes(v.keyword) && (v.unit !== (inspection.unitSystem === 'FIELD' ? 'DEG F' : 'DEG C') ||
      v.keyword === 'BTEMP' && v.number < 1 || v.keyword === 'WTEMP' && v.objectName !== ':+:+:+:+' && !inspection.wellNames.includes(v.objectName))) return null
    values += v.points.length
    if (values > 4000000) return null
    previous = v.vectorIndex
  }
  return r
}
export const thermalObject = compositionalObject
export const thermalCsv = compositionalCsv
export const thermalCategory = keyword => ['WTEMP', 'BTEMP'].includes(keyword) ? 'temperature' : /^[FGW]STP[RT]$/.test(keyword) ? 'steam' : /^FHL[RT]$/.test(keyword) ? 'heat' : 'other'
