import { validCompositionalFluidPropertyContext } from './eclipseCompositionalFluidProperty.js'
import { validCompositionalReinjectionContext } from './eclipseCompositionalReinjection.js'
const shape = (v, keys) => Boolean(v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k)))
const integer = (n, min, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= min && n <= max
const hash = s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s)
const source = s => typeof s === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(s)
const rate = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 1e8
const date = s => typeof s === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(s) && Number(s.slice(0, 4)) >= 1 &&
  Number.isFinite(Date.parse(s + 'T00:00:00Z')) && new Date(s + 'T00:00:00Z').toISOString().slice(0, 10) === s
export const compositionalForecastSchema = 'eclipse-compositional-forecast-parameters/1'
export const compositionalSaveCatalogName = 'eclipse-compositional-save-catalog.json'
export const validCompositionalForecast = p => shape(p, ['schemaVersion', 'historyRunId', 'sourceFile', 'sourceSha256', 'saveCatalogSha256', 'saveReport', 'targetGasRate']) &&
  p.schemaVersion === compositionalForecastSchema && integer(p.historyRunId, 1) && source(p.sourceFile) && hash(p.sourceSha256) && hash(p.saveCatalogSha256) &&
  integer(p.saveReport, 1, 9999) && (p.targetGasRate === null || rate(p.targetGasRate))
export const validCompositionalSaveCatalog = c => {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'solver', 'solverVersion', 'caseName', 'startDate', 'reportNumber', 'timeDays', 'date',
    'unitSystem', 'dimensions', 'activeCells', 'componentCount', 'componentNames', 'equationOfState', 'endTimeDays', 'files']) ||
    c.schemaVersion !== 'eclipse-compositional-save-catalog/1' || !source(c.sourceFile) || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) ||
    c.solver !== 300 || c.solverVersion !== '2024.1' || c.caseName !== c.sourceFile.slice(0, -5) || c.unitSystem !== 'FIELD' || c.equationOfState !== 'PR' ||
    c.componentCount !== 9 || !Array.isArray(c.componentNames) || c.componentNames.length !== 9 || new Set(c.componentNames).size !== 9 ||
    !c.componentNames.every(n => typeof n === 'string' && /^[A-Za-z0-9][A-Za-z0-9+_.-]{0,15}$/.test(n)) || !integer(c.reportNumber, 1, 9999) ||
    !integer(c.timeDays, 1, 1e6) || !integer(c.endTimeDays, c.timeDays + 1, 1e6) || !date(c.startDate) || !date(c.date) ||
    !shape(c.dimensions, ['nx', 'ny', 'nz']) || !['nx', 'ny', 'nz'].every(k => integer(c.dimensions[k], 1, 1e6))) return false
  const cells = c.dimensions.nx * c.dimensions.ny * c.dimensions.nz
  if (cells > 1e6 || !integer(c.activeCells, 1, cells)) return false
  const origin = Date.parse(c.startDate + 'T00:00:00Z')
  if (new Date(origin + c.timeDays * 864e5).toISOString().slice(0, 10) !== c.date || !date(new Date(origin + c.endTimeDays * 864e5).toISOString().slice(0, 10))) return false
  const report = String(c.reportNumber).padStart(4, '0'), names = [c.caseName + '.V0000', c.caseName + '.V' + report, c.caseName + '.X' + report]
  if (!Array.isArray(c.files) || c.files.length !== 3 || !c.files.every((f, j) => shape(f, ['name', 'artifactName', 'sizeBytes', 'sha256']) &&
    f.name === names[j] && f.artifactName === 'eclipse-output-' + names[j] && integer(f.sizeBytes, 24, 128 * 1024 * 1024) && hash(f.sha256))) return false
  return c.files.reduce((s, f) => s + f.sizeBytes, 0) + 2 * c.sourceSizeBytes + 64 * 1024 <= 64 * 1024 * 1024
}
export const compositionalSaveMatchesInspection = (c, i) => validCompositionalSaveCatalog(c) &&
  validCompositionalFluidPropertyContext(i?.compositionalFluidPropertyContext, i) && validCompositionalReinjectionContext(i?.compositionalReinjectionContext, i) &&
  ['sourceFile', 'sourceSha256', 'sourceSizeBytes'].every(k => c[k] === i.compositionalFluidPropertyContext[k]) &&
  Array.isArray(i?.simulation?.componentNames) && c.componentNames.every((v, j) => v === i.simulation.componentNames[j]) && ['nx', 'ny', 'nz'].every(k => c.dimensions[k] === i.dimensions[k])
export const compositionalForecastMatches = (p, c, i) => validCompositionalForecast(p) && compositionalSaveMatchesInspection(c, i) &&
  p.sourceFile === c.sourceFile && p.sourceSha256 === c.sourceSha256 && p.saveReport === c.reportNumber &&
  (p.targetGasRate === null || p.targetGasRate !== i.compositionalProductionContext.records[0].originalGasRate)
export const compositionalSaveMatchesResult = (c, r) => validCompositionalSaveCatalog(c) && r?.schemaVersion === 'eclipse-summary-result/2' && r.modelKind === 'eclipse_300' &&
  r.caseName === c.sourceFile && r.summary?.startDate === c.startDate && r.simulation?.solver === 300 && r.simulation.modelType === 'COMPOSITIONAL' &&
  r.simulation.componentCount === 9 && r.simulation.equationOfState === 'PR' && c.componentNames.every((n, j) => r.simulation.componentNames?.[j] === n) &&
  ['problems', 'errors', 'bugs'].every(k => r.eclEnd?.[k] === 0) && ['nx', 'ny', 'nz'].every(k => r.grid?.[k] === c.dimensions[k]) && r.grid.activeCells === c.activeCells &&
  Array.isArray(r.outputFiles) && c.files.every(f => r.outputFiles.filter(o => o.name === f.name && o.sizeBytes === f.sizeBytes && o.sha256 === f.sha256).length === 1)
export const buildCompositionalForecast = ({ inspection, historyRun, catalog, artifact, targetGasRate }) => {
  if (!historyRun || historyRun.status !== 'SUCCEEDED' || historyRun.resultContract !== 'VALID_FULL' || historyRun.runType !== 'eclipse' || historyRun.parameters != null ||
    !integer(historyRun.id, 1) || !compositionalSaveMatchesResult(catalog, historyRun.result) || artifact?.name !== compositionalSaveCatalogName ||
    artifact.contentType !== 'application/json' || !integer(artifact.id, 1) || !integer(artifact.sizeBytes, 1, 64 * 1024) || !hash(artifact.sha256) ||
    !Array.isArray(historyRun.artifacts) || historyRun.artifacts.filter(a => a.id === artifact.id && a.sha256 === artifact.sha256 && a.name === artifact.name).length !== 1) return null
  const p = { schemaVersion: compositionalForecastSchema, historyRunId: historyRun.id, sourceFile: catalog.sourceFile, sourceSha256: catalog.sourceSha256,
    saveCatalogSha256: artifact.sha256, saveReport: catalog.reportNumber, targetGasRate }
  return compositionalForecastMatches(p, catalog, inspection) ? p : null
}
export const validCompositionalPortableContext = (c, i) => {
  if (!shape(c, ['schemaVersion', 'sourceFile', 'sourceSha256', 'sourceSizeBytes', 'historySourceFile', 'targetGasRate', 'historyCatalog']) ||
    i?.schemaVersion !== 'eclipse-data-inspection/18' || c.schemaVersion !== 'eclipse-compositional-portable-restart-context/1' || !source(c.sourceFile) ||
    c.sourceFile !== i.caseName || !hash(c.sourceSha256) || !integer(c.sourceSizeBytes, 1, 4 * 1024 * 1024) || !(c.targetGasRate === null || rate(c.targetGasRate)) ||
    !validCompositionalSaveCatalog(c.historyCatalog) || c.historySourceFile !== 'history/input/' + c.historyCatalog.sourceFile || i.unitSystem !== 'FIELD') return false
  const h = c.historyCatalog, s = i.simulation
  if (!shape(s, ['solver', 'modelType', 'componentCount', 'componentNames', 'equationOfState']) || s.solver !== 300 || s.modelType !== 'COMPOSITIONAL' || s.componentCount !== 9 ||
    s.equationOfState !== 'PR' || !Array.isArray(s.componentNames) || s.componentNames.length !== 9 || h.componentNames.some((n, j) => n !== s.componentNames[j]) ||
    !i.dimensions || ['nx', 'ny', 'nz'].some(k => i.dimensions[k] !== h.dimensions[k])) return false
  const expected = new Map(h.files.map(f => ['history/state/' + f.name, f]))
  expected.set(c.sourceFile, { sizeBytes: c.sourceSizeBytes, sha256: c.sourceSha256 })
  expected.set(c.historySourceFile, { sizeBytes: h.sourceSizeBytes, sha256: h.sourceSha256 })
  expected.set('eclipse-compositional-restart-binding.json', null)
  if (!Array.isArray(i.packageFiles) || i.packageFiles.length !== 6 || new Set(i.packageFiles.map(f => f.relativePath)).size !== 6) return false
  return i.packageFiles.every(f => shape(f, ['relativePath', 'sizeBytes', 'sha256']) && expected.has(f.relativePath) && hash(f.sha256) && integer(f.sizeBytes, 1, 64 * 1024 * 1024) &&
    (expected.get(f.relativePath) ? ['sizeBytes', 'sha256'].every(k => expected.get(f.relativePath)[k] === f[k]) : f.sizeBytes <= 64 * 1024)) &&
    i.packageFiles.reduce((total, f) => total + f.sizeBytes, 0) <= 64 * 1024 * 1024
}
export const compositionalForecastEvidence = (result, catalog, wellNames = []) => {
  const s = result?.summary
  if (!validCompositionalSaveCatalog(catalog) || s?.startDate !== catalog.startDate || !Array.isArray(s.series) || !s.series.length ||
    !Array.isArray(s.series[0].points) || !s.series[0].points.length) return null
  const times = s.series[0].points.map(p => p.timeDays), indices = new Set()
  if (!times.every((t, j) => typeof t === 'number' && Number.isFinite(t) && t > catalog.timeDays && t <= catalog.endTimeDays && (!j || t > times[j - 1]))) return null
  for (const v of s.series) {
    if (!integer(v.vectorIndex, 0, 65535) || indices.has(v.vectorIndex) || !Array.isArray(v.points) || v.points.length !== times.length ||
      v.points.some((p, j) => p.timeDays !== times[j] || typeof p.value !== 'number' || !Number.isFinite(p.value))) return null
    indices.add(v.vectorIndex)
  }
  const wells = new Set(wellNames), fields = new Set(['FOPT', 'FOPR', 'FPR', 'FGIT']), metrics = s.series.filter(v =>
    fields.has(v.keyword) && v.objectName === null || ['WGPR', 'WGIR', 'WBHP'].includes(v.keyword) && wells.has(v.objectName)).map(v => ({
    vectorIndex: v.vectorIndex, keyword: v.keyword, objectName: v.objectName, unit: v.unit, first: v.points[0], last: v.points.at(-1)
  }))
  return { complete: result.resultContract === 'VALID_FULL' && times.at(-1) === catalog.endTimeDays, startDays: times[0], endDays: times.at(-1),
    actualTimes: times.length, rawVectorCount: s.series.length, metrics }
}
