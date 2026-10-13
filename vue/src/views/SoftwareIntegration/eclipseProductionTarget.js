const schema = 'eclipse-production-target-parameters/1'
const safeWell = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(value)
const safeFile = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.data$/i.test(value)
const rate = value => typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= 100000000

export function productionTargetRecords(inspection) {
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
  return { schemaVersion: schema, baselineRunId, well, phase: 'INITIAL', sourceFile: record.sourceFile,
    lineNumber: record.lineNumber, unit: record.unit, originalOilRate: record.originalOilRate, targetOilRate }
}

export function validProductionTarget(p) {
  const fields = ['schemaVersion', 'baselineRunId', 'well', 'phase', 'sourceFile', 'lineNumber', 'unit', 'originalOilRate', 'targetOilRate']
  return p && typeof p === 'object' && Object.keys(p).length === fields.length && fields.every(k => Object.hasOwn(p, k)) &&
    p.schemaVersion === schema && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && safeWell(p.well) &&
    p.phase === 'INITIAL' && safeFile(p.sourceFile) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 && p.unit === 'STB/day' &&
    rate(p.originalOilRate) && rate(p.targetOilRate) && p.originalOilRate !== p.targetOilRate
}
