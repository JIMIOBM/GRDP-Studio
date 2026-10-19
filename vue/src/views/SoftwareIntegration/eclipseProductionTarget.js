const schema = 'eclipse-production-target-parameters/1'
const packageSchema = 'eclipse-production-target-parameters/2'
const safeWell = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(value)
const safeFile = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.data$/i.test(value)
const rate = value => typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= 100000000
const safePath = value => typeof value === 'string' && value.length > 0 && value.length <= 512 && value.split('/').every(p => !['.', '..'].includes(p) && /^[A-Za-z0-9][A-Za-z0-9._ -]*$/.test(p))
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)

function packageRecords(inspection) {
  const context = inspection?.packageProductionTargetContext, files = inspection?.packageFiles
  if (inspection?.schemaVersion !== 'eclipse-data-inspection/4' || inspection?.unitSystem !== 'FIELD' ||
    context?.schemaVersion !== 'eclipse-package-production-target-context/1' || Object.keys(context).length !== 2 ||
    !Array.isArray(files) || files.length < 2 || !Array.isArray(context.targets) || !context.targets.length || context.targets.length > 1000) return []
  const names = new Set(), paths = new Set()
  for (const f of files) { if (!safePath(f?.relativePath) || !hash(f?.sha256) || paths.has(f.relativePath.toLowerCase())) return []; paths.add(f.relativePath.toLowerCase()) }
  const result = []
  for (const t of context.targets) {
    if (!t || Object.keys(t).length !== 5 || !safeWell(t.well) || !safePath(t.sourceFile) || !hash(t.sourceSha256) ||
      !Number.isSafeInteger(t.lineNumber) || t.lineNumber <= 0 || !rate(t.originalOilRate) || names.has(t.well) ||
      files.filter(f => f.relativePath === t.sourceFile && f.sha256 === t.sourceSha256).length !== 1 ||
      inspection.scheduleMetadata?.wells?.filter(w => w.name === t.well).length !== 1) return []
    const records = inspection.scheduleMetadata?.records?.filter(r => r.keyword === 'WCONPROD' && r.sourceFile === t.sourceFile && r.lineNumber === t.lineNumber &&
      Array.isArray(r.values) && r.values[0] === t.well && r.values[1] === 'OPEN' && r.values[2] === 'ORAT' && Number(r.values[3]) === t.originalOilRate)
    if (records?.length !== 1) return []
    names.add(t.well); result.push({ ...t, record: records[0], unit: 'STB/day' })
  }
  return result
}

export function productionTargetRecords(inspection) {
  if (inspection?.packageProductionTargetContext !== undefined) return packageRecords(inspection)
  if (['eclipse-data-inspection/6','eclipse-data-inspection/7', 'eclipse-data-inspection/8', 'eclipse-data-inspection/9', 'eclipse-data-inspection/10', 'eclipse-data-inspection/11', 'eclipse-data-inspection/12', 'eclipse-data-inspection/13'].includes(inspection?.schemaVersion)) return []
  const file = inspection?.caseName
  const files = inspection?.packageFiles
  if (inspection?.unitSystem !== 'FIELD' || !safeFile(file) || !Array.isArray(files) || files.length !== 1 || files[0]?.relativePath !== file) return []
  const events = inspection?.scheduleTimeline
  if (!Array.isArray(events) || events.some(e => e?.sourceFile !== file || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= 0)) return []
  const end = Math.min(Infinity, ...events.map(e => e.lineNumber))
  const records = inspection?.scheduleMetadata?.records
  if (!Array.isArray(records)) return []
  const controls = records.filter(r => r?.sourceFile === file && Number.isSafeInteger(r.lineNumber) && r.lineNumber > 0 && r.lineNumber < end &&
    ['WCONPROD', 'WCONINJE', 'WCONHIST', 'WELOPEN'].includes(r.keyword))
  if (controls.some(r => !Array.isArray(r.values) || !r.values.length || !safeWell(r.values[0]))) return []
  const wells = inspection?.scheduleMetadata?.wells || []
  return controls.filter(r => r.keyword === 'WCONPROD' && r.values[1] === 'OPEN' && r.values[2] === 'ORAT' && rate(Number(r.values[3])) &&
    wells.some(w => w.name === r.values[0]) && controls.filter(other => other.values[0] === r.values[0]).length === 1)
    .map(r => ({ record: r, well: r.values[0], sourceFile: file, lineNumber: r.lineNumber, originalOilRate: Number(r.values[3]), unit: 'STB/day' }))
}

export function buildProductionTarget({ inspection, baselineRunId, well, targetOilRate }) {
  const record = productionTargetRecords(inspection).find(r => r.well === well)
  if (!record || !Number.isSafeInteger(baselineRunId) || baselineRunId <= 0 || !rate(targetOilRate) || targetOilRate === record.originalOilRate) return null
  return { schemaVersion: record.sourceSha256 ? packageSchema : schema, baselineRunId, well, phase: 'INITIAL', sourceFile: record.sourceFile,
    lineNumber: record.lineNumber, unit: record.unit, originalOilRate: record.originalOilRate, targetOilRate,
    ...(record.sourceSha256 ? { sourceSha256: record.sourceSha256 } : {}) }
}

export function validProductionTarget(p) {
  const fields = ['schemaVersion', 'baselineRunId', 'well', 'phase', 'sourceFile', 'lineNumber', 'unit', 'originalOilRate', 'targetOilRate']
  const packageTarget = p?.schemaVersion === packageSchema
  if (packageTarget) fields.push('sourceSha256')
  return p && typeof p === 'object' && Object.keys(p).length === fields.length && fields.every(k => Object.hasOwn(p, k)) &&
    p.schemaVersion === (packageTarget ? packageSchema : schema) && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && safeWell(p.well) &&
    p.phase === 'INITIAL' && (packageTarget ? safePath(p.sourceFile) && hash(p.sourceSha256) : safeFile(p.sourceFile)) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 && p.unit === 'STB/day' &&
    rate(p.originalOilRate) && rate(p.targetOilRate) && p.originalOilRate !== p.targetOilRate
}
