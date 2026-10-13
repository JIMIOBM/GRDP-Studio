const schema = 'eclipse-production-control-parameters/1'
const safeWell = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(value)
const safeFile = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.data$/i.test(value)
const rate = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 100000000
const modes = ['WRAT', 'GRAT', 'LRAT']
const unitFor = (mode, units) => units === 'METRIC' ? 'sm3/day' : mode === 'GRAT' ? 'Mscf/day' : 'STB/day'
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
export function productionControlRecords(i) {
  const file = i?.caseName
  if (!['eclipse-data-inspection/4', 'eclipse-data-inspection/5'].includes(i?.schemaVersion) || !['FIELD', 'METRIC'].includes(i.unitSystem) || !safeFile(file) || i.packageFiles?.length !== 1 || i.packageFiles[0].relativePath !== file || !Array.isArray(i.scheduleTimeline)) return []
  if (i.scheduleTimeline.some(e => e.sourceFile !== file || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= 0)) return []
  const end = Math.min(Infinity, ...i.scheduleTimeline.map(e => e.lineNumber))
  const controls = (i.scheduleMetadata?.records || []).filter(r => r.sourceFile === file && Number.isSafeInteger(r.lineNumber) && r.lineNumber > 0 && r.lineNumber < end && ['WCONPROD', 'WCONINJE', 'WCONHIST', 'WELOPEN'].includes(r.keyword))
    .map(r => ({ ...r, expanded: expanded(r.values) }))
  if (controls.some(r => !safeWell(r.expanded[0]))) return []
  return controls.flatMap(r => {
    const v = r.expanded, mode = v[2], target = v[mode === 'WRAT' ? 4 : mode === 'GRAT' ? 5 : 6]
    if (r.keyword !== 'WCONPROD' || v[1] !== 'OPEN' || !modes.includes(mode) || typeof target !== 'string' || !target.trim() || !rate(Number(target)) ||
      controls.filter(c => c.expanded[0] === v[0]).length !== 1 || !i.scheduleMetadata?.wells?.some(w => w.name === v[0])) return []
    return [{ well: v[0], sourceFile: file, lineNumber: r.lineNumber, controlMode: mode, originalTargetRate: Number(target), unit: unitFor(mode, i.unitSystem) }]
  })
}
export function buildProductionControl({ inspection, baselineRunId, well, targetRate }) {
  const record = productionControlRecords(inspection).find(r => r.well === well)
  if (!record) return null
  const p = { schemaVersion: schema, baselineRunId, ...record, phase: 'INITIAL', targetRate }
  return validProductionControl(p) ? p : null
}
export function validProductionControl(p) {
  const fields = ['schemaVersion', 'baselineRunId', 'well', 'phase', 'sourceFile', 'lineNumber', 'controlMode', 'originalTargetRate', 'targetRate', 'unit']
  return Boolean(p && typeof p === 'object' && Object.keys(p).length === fields.length && fields.every(f => Object.hasOwn(p, f)) && p.schemaVersion === schema &&
    Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && safeWell(p.well) && safeFile(p.sourceFile) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 &&
    p.phase === 'INITIAL' && modes.includes(p.controlMode) && ['sm3/day', unitFor(p.controlMode, 'FIELD')].includes(p.unit) &&
    rate(p.originalTargetRate) && rate(p.targetRate) && p.originalTargetRate !== p.targetRate)
}
export function productionControlResultUnitMatches(unit, expected) {
  const normalized = typeof unit === 'string' ? unit.trim().toUpperCase() : ''
  return normalized === { 'STB/day': 'STB/DAY', 'Mscf/day': 'MSCF/DAY', 'sm3/day': 'SM3/DAY' }[expected]
}
