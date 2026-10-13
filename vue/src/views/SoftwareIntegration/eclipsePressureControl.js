const schema = 'eclipse-pressure-control-parameters/1'
const safeWell = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(value)
const safeFile = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.data$/i.test(value)
const pressure = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 100000
const modes = keyword => keyword === 'WCONPROD' ? ['ORAT', 'WRAT', 'GRAT', 'LRAT', 'BHP'] : ['RATE', 'RESV', 'BHP']

function expanded(values) {
  if (!Array.isArray(values)) return []
  const result = []
  for (const item of values) {
    if (typeof item !== 'string') return []
    const repeat = result.length ? /^(\d+)\*(.*)$/.exec(item) : null
    const count = repeat ? Number(repeat[1]) : 1
    if (count < 1 || count > 64 || result.length + count > 64) return []
    result.push(...Array(count).fill(repeat ? (repeat[2] ? 'REPEATED' : '') : item))
  }
  return result
}
export function pressureControlRecords(i) {
  const file = i?.caseName
  if (!['eclipse-data-inspection/4', 'eclipse-data-inspection/5'].includes(i?.schemaVersion) || i.unitSystem !== 'FIELD' || !safeFile(file) || i.packageFiles?.length !== 1 || i.packageFiles[0].relativePath !== file || !Array.isArray(i.scheduleTimeline)) return []
  if (i.scheduleTimeline.some(e => e.sourceFile !== file || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= 0)) return []
  const end = Math.min(Infinity, ...i.scheduleTimeline.map(e => e.lineNumber))
  const controls = (i.scheduleMetadata?.records || []).filter(r => r.sourceFile === file && Number.isSafeInteger(r.lineNumber) && r.lineNumber > 0 && r.lineNumber < end && ['WCONPROD', 'WCONINJE', 'WCONHIST', 'WELOPEN'].includes(r.keyword))
    .map(r => ({ ...r, expanded: expanded(r.values) }))
  if (controls.some(r => !safeWell(r.expanded[0]))) return []
  return controls.flatMap(r => {
    const v = r.expanded, injection = r.keyword === 'WCONINJE'
    const mode = v[injection ? 3 : 2], bhp = v[injection ? 6 : 8]
    if (!['WCONPROD', 'WCONINJE'].includes(r.keyword) || v[injection ? 2 : 1] !== 'OPEN' || !modes(r.keyword).includes(mode) ||
      typeof bhp !== 'string' || !bhp.trim() || !pressure(Number(bhp)) || controls.filter(c => c.expanded[0] === v[0]).length !== 1 ||
      !i.scheduleMetadata?.wells?.some(w => w.name === v[0]) || (injection && !['WATER', 'WAT', 'OIL', 'GAS'].includes(v[1]))) return []
    return [{ well: v[0], sourceFile: file, lineNumber: r.lineNumber, keyword: r.keyword, originalControlMode: mode,
      originalBhp: Number(bhp), unit: 'psia', injectionType: injection ? v[1] : 'NONE' }]
  })
}
export function buildPressureControl({ inspection, baselineRunId, well, targetBhp }) {
  const record = pressureControlRecords(inspection).find(r => r.well === well)
  if (!record) return null
  const p = { schemaVersion: schema, baselineRunId, ...record, phase: 'INITIAL', targetBhp }
  return validPressureControl(p) ? p : null
}
export function validPressureControl(p) {
  const fields = ['schemaVersion', 'baselineRunId', 'well', 'phase', 'sourceFile', 'lineNumber', 'keyword', 'originalControlMode', 'originalBhp', 'targetBhp', 'unit', 'injectionType']
  return p && typeof p === 'object' && Object.keys(p).length === fields.length && fields.every(f => Object.hasOwn(p, f)) && p.schemaVersion === schema &&
    Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && safeWell(p.well) && safeFile(p.sourceFile) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 &&
    p.phase === 'INITIAL' && p.unit === 'psia' && ['WCONPROD', 'WCONINJE'].includes(p.keyword) && modes(p.keyword).includes(p.originalControlMode) &&
    pressure(p.originalBhp) && pressure(p.targetBhp) && (p.originalControlMode !== 'BHP' || p.originalBhp !== p.targetBhp) &&
    (p.keyword === 'WCONPROD' ? p.injectionType === 'NONE' : ['WATER', 'WAT', 'OIL', 'GAS'].includes(p.injectionType))
}
