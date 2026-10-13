const schema = 'eclipse-thp-control-parameters/1'
const safeWell = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(v)
const safeFile = v => typeof v === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.data$/i.test(v)
const pressure = v => typeof v === 'number' && Number.isFinite(v) && v > 0 && v <= 1e8
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const axis = (v, min = 1) => Array.isArray(v) && v.length >= min && v.length <= 128 && v.every((n, i) => typeof n === 'number' && Number.isFinite(n) && n >= 0 && (!i || n > v[i - 1]))
const inside = (v, n) => axis(v) && Number.isFinite(n) && n >= v[0] && n <= v.at(-1)
export function validThpTable(t) {
  return Boolean(t && Number.isSafeInteger(t.number) && t.number > 0 && t.number <= 100000 && Number.isFinite(t.datumDepth) && t.datumDepth >= 0 &&
    t.flowType === 'LIQ' && t.waterFractionType === 'WCT' && t.gasFractionType === 'GOR' && t.alqType === '' && t.unitSystem === 'FIELD' &&
    safeFile(t.sourceFile) && Number.isSafeInteger(t.lineNumber) && t.lineNumber > 0 && hash(t.sha256) &&
    axis(t.flowAxis, 2) && [t.thpAxis, t.waterFractionAxis, t.gasFractionAxis, t.alqAxis].every(v => axis(v)))
}
function expanded(values) {
  if (!Array.isArray(values)) return []
  const result = []
  for (const item of values) {
    if (typeof item !== 'string') return []
    const repeat = result.length ? /^(\d+)\*(.*)$/.exec(item) : null
    const count = repeat ? Number(repeat[1]) : 1
    if (count < 1 || count > 64 || result.length + count > 64) return []
    result.push(...Array(count).fill(repeat ? repeat[2] ? 'REPEATED' : '' : item))
  }
  return result
}
export function thpControlRecords(i) {
  const file = i?.caseName
  if (i?.schemaVersion !== 'eclipse-data-inspection/5' || i.unitSystem !== 'FIELD' || !safeFile(file) || i.packageFiles?.length !== 1 || i.packageFiles[0].relativePath !== file || !Array.isArray(i.scheduleTimeline) || !Array.isArray(i.vfpTables)) return []
  if (i.scheduleTimeline.some(e => e.sourceFile !== file || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= 0)) return []
  const end = Math.min(Infinity, ...i.scheduleTimeline.map(e => e.lineNumber))
  const controls = (i.scheduleMetadata?.records || []).filter(r => r.sourceFile === file && Number.isSafeInteger(r.lineNumber) && r.lineNumber > 0 && r.lineNumber < end && ['WCONPROD', 'WCONINJE', 'WCONHIST', 'WELOPEN'].includes(r.keyword)).map(r => ({ ...r, expanded: expanded(r.values) }))
  if (controls.some(r => !safeWell(r.expanded[0]))) return []
  return controls.flatMap(r => {
    const v = r.expanded, thp = v[9], number = v[10], alq = !v[11] ? 0 : Number(v[11])
    const tables = i.vfpTables.filter(t => t.number === Number(number)), table = tables[0]
    if (r.keyword !== 'WCONPROD' || v[1] !== 'OPEN' || v[2] !== 'THP' || !thp || !number || !pressure(Number(thp)) || tables.length !== 1 ||
      !validThpTable(table) || table.sourceFile !== file || table.lineNumber >= end || !inside(table.thpAxis, Number(thp)) || !inside(table.alqAxis, alq) ||
      controls.filter(c => c.expanded[0] === v[0]).length !== 1 || !i.scheduleMetadata?.wells?.some(w => w.name === v[0])) return []
    return [{ well: v[0], sourceFile: file, lineNumber: r.lineNumber, originalThp: Number(thp), unit: 'psia', originalAlq: alq, vfpTableNumber: table.number, vfpTableSha256: table.sha256, table }]
  })
}
export function buildThpControl({ inspection, baselineRunId, well, targetThp }) {
  const record = thpControlRecords(inspection).find(r => r.well === well)
  if (!record || !inside(record.table.thpAxis, targetThp)) return null
  const { table, ...fields } = record
  const p = { schemaVersion: schema, baselineRunId, phase: 'INITIAL', ...fields, targetThp }
  return validThpControl(p) ? p : null
}
export function validThpControl(p) {
  const fields = ['schemaVersion', 'baselineRunId', 'well', 'phase', 'sourceFile', 'lineNumber', 'unit', 'originalThp', 'targetThp', 'vfpTableNumber', 'vfpTableSha256', 'originalAlq']
  return Boolean(p && typeof p === 'object' && Object.keys(p).length === fields.length && fields.every(f => Object.hasOwn(p, f)) && p.schemaVersion === schema && p.phase === 'INITIAL' && p.unit === 'psia' &&
    Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && safeWell(p.well) && safeFile(p.sourceFile) && Number.isSafeInteger(p.lineNumber) && p.lineNumber > 0 &&
    pressure(p.originalThp) && pressure(p.targetThp) && p.originalThp !== p.targetThp && Number.isSafeInteger(p.vfpTableNumber) && p.vfpTableNumber > 0 && p.vfpTableNumber <= 100000 && hash(p.vfpTableSha256) &&
    typeof p.originalAlq === 'number' && Number.isFinite(p.originalAlq) && p.originalAlq >= 0 && p.originalAlq <= 1e8)
}
// Linear extrapolation is a solver capability, but never qualifies as a verified in-table scenario.
export function thpTableCoverage(summary, p, tables) {
  const matches = (tables || []).filter(t => t.number === p?.vfpTableNumber && t.sha256 === p?.vfpTableSha256)
  const table = matches[0]
  if (matches.length !== 1 || !validThpTable(table) || !inside(table.alqAxis, p.originalAlq)) return { available: false, inRange: false, outsideCount: 0 }
  const series = summary?.series || [], points = {}
  for (const [keyword, unit] of [['WLPR', 'STB/DAY'], ['WOPR', 'STB/DAY'], ['WWPR', 'STB/DAY'], ['WGPR', 'MSCF/DAY'], ['WTHP', 'PSIA']]) {
    const selected = series.filter(s => s.keyword === keyword && s.objectName === p.well)
    if (selected.length !== 1 || selected[0].unit?.trim().toUpperCase() !== unit || !Array.isArray(selected[0].points)) return { available: false, inRange: false, outsideCount: 0 }
    const data = selected[0].points
    if (data.some(v => !Number.isFinite(v.timeDays) || !Number.isFinite(v.value)) || new Set(data.map(v => v.timeDays)).size !== data.length) return { available: false, inRange: false, outsideCount: 0 }
    points[keyword] = new Map(data.map(v => [v.timeDays, v.value]))
  }
  const rows = []
  for (const [timeDays, flow] of points.WLPR) {
    if (timeDays <= 0 || flow <= 0) continue
    const oil = points.WOPR.get(timeDays), water = points.WWPR.get(timeDays), gas = points.WGPR.get(timeDays), thp = points.WTHP.get(timeDays)
    if (!(oil > 0) || !(water >= 0) || !(gas >= 0) || !Number.isFinite(thp)) return { available: false, inRange: false, outsideCount: 0 }
    rows.push({ timeDays, flow, wct: water / flow, gor: gas / oil, thp, inRange: inside(table.flowAxis, flow) && inside(table.waterFractionAxis, water / flow) && inside(table.gasFractionAxis, gas / oil) && inside(table.thpAxis, thp) })
  }
  const outsideCount = rows.filter(r => !r.inRange).length
  return { available: rows.length > 0, inRange: rows.length > 0 && outsideCount === 0, outsideCount, rows }
}
