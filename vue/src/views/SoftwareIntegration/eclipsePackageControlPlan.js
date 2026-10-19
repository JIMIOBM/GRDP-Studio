const editFields = ['well', 'sourceFile', 'sourceSha256', 'lineNumber', 'date', 'timeDays', 'unit', 'originalOilRate', 'targetOilRate']
const recordFields = [...editFields.filter(k => k !== 'targetOilRate'), 'bhpLimit']
const shape = (o, keys) => o && typeof o === 'object' && !Array.isArray(o) && Object.keys(o).length === keys.length && keys.every(k => Object.hasOwn(o, k))
const integer = (n, min = 0, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(n) && n >= min && n <= max
const positive = n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 100000000
const hash = s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s)
const well = s => typeof s === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,7}$/.test(s)
const path = s => typeof s === 'string' && s.length > 0 && s.length <= 512 && s.split('/').every(p => p !== '.' && p !== '..' && /^[A-Za-z0-9][A-Za-z0-9._ -]*$/.test(p))
const day = s => {
  if (typeof s !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return null
  const time = Date.parse(`${s}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === s ? time / 86400000 : null
}
const number = (text, value) => typeof text === 'string' && /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[Ee][+-]?\d+)?$/.test(text) && Number(text) === value
const expand = values => {
  if (!Array.isArray(values)) return null
  const result = []
  for (let text of values) {
    if (typeof text !== 'string') return null
    const repeated = /^(\d+)\*(.*)$/.exec(text), count = repeated ? Number(repeated[1]) : 1
    if (!integer(count, 1, 128) || result.length + count > 128) return null
    if (repeated) text = repeated[2]
    for (let i = 0; i < count; i++) result.push(text)
  }
  return result
}
export const validPackageControlPlan = p => {
  if (!shape(p, ['schemaVersion', 'baselineRunId', 'edits']) || p.schemaVersion !== 'eclipse-control-plan-parameters/2' || !integer(p.baselineRunId, 1) || !Array.isArray(p.edits) || !p.edits.length || p.edits.length > 24) return false
  const seen = new Set(), physical = new Set(); let previous = -1
  for (const e of p.edits) {
    if (!shape(e, editFields) || !well(e.well) || !path(e.sourceFile) || !hash(e.sourceSha256) || !integer(e.lineNumber, 1, 2147483647) || !integer(e.timeDays, 0, 3000000)
      || day(e.date) === null || !['STB/day', 'sm3/day'].includes(e.unit) || !positive(e.originalOilRate) || !positive(e.targetOilRate) || e.targetOilRate === e.originalOilRate
      || e.timeDays < previous || seen.has(`${e.well}:${e.timeDays}`) || physical.has(`${e.sourceFile}:${e.lineNumber}`)) return false
    seen.add(`${e.well}:${e.timeDays}`); physical.add(`${e.sourceFile}:${e.lineNumber}`); previous = e.timeDays
  }
  return true
}
export const packageControlPlanRecords = i => {
  const p = i?.packageProductionControlPlanContext
  if (i?.schemaVersion !== 'eclipse-data-inspection/4' || !['FIELD', 'METRIC'].includes(i.unitSystem) || !Array.isArray(i.packageFiles) || i.packageFiles.length < 2 ||
    !shape(p, ['schemaVersion', 'startDate', 'endTimeDays', 'records']) || p.schemaVersion !== 'eclipse-package-production-control-plan-context/1' || day(p.startDate) === null ||
    !integer(p.endTimeDays, 1, 3000000) || !Array.isArray(p.records) || !p.records.length || p.records.length > 1000 || !Array.isArray(i.scheduleTimeline) || i.scheduleTimeline.length < 2) return []
  const start = day(p.startDate), reports = new Set(), months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC']
  let previousDate = start
  for (const event of i.scheduleTimeline) {
    if (event.kind !== 'DATES' || !Array.isArray(event.records) || !event.records.length) return []
    for (const r of event.records) {
      if (typeof r.year !== 'string' || !/^\d{4}$/.test(r.year) || typeof r.day !== 'string' || !/^(?:[1-9]|[12][0-9]|3[01])$/.test(r.day) || !(r.time === null || r.time === '00:00:00')) return []
      const month = months.indexOf(r.month) + 1, date = day(`${r.year}-${String(month).padStart(2, '0')}-${r.day.padStart(2, '0')}`)
      if (!month || date === null || date <= previousDate) return []
      previousDate = date; reports.add(date - start)
    }
  }
  if (previousDate - start !== p.endTimeDays) return []
  const m = i.scheduleMetadata, unit = i.unitSystem === 'FIELD' ? 'STB/day' : 'sm3/day'
  if (!Array.isArray(m?.wells) || !Array.isArray(m?.records) || !Array.isArray(m?.completions) || !Array.isArray(m?.groups) || m.groups.length ||
    m.records.some(r => !['WCONPROD', 'WCONINJE', 'COMPDAT'].includes(r.keyword)) || m.records.filter(r => r.keyword === 'WCONPROD').length !== p.records.length) return []
  const seen = new Set(), physical = new Set(), initial = new Set(), phases = new Set(); let previous = -1
  for (const c of p.records) {
    if (!shape(c, recordFields) || !well(c.well) || !path(c.sourceFile) || !hash(c.sourceSha256) || !integer(c.lineNumber, 1, 2147483647) || !integer(c.timeDays, 0, p.endTimeDays - 1) ||
      day(c.date) === null || day(c.date) - start !== c.timeDays || c.timeDays < previous || c.timeDays > 0 && !reports.has(c.timeDays) || c.unit !== unit ||
      !positive(c.originalOilRate) || !positive(c.bhpLimit) || seen.has(`${c.well}:${c.timeDays}`) || physical.has(`${c.sourceFile}:${c.lineNumber}`)) return []
    if (c.timeDays === 0) initial.add(c.well)
    else { if (!initial.has(c.well)) return []; phases.add(c.timeDays) }
    if (i.packageFiles.filter(f => f.relativePath === c.sourceFile && f.sha256 === c.sourceSha256).length !== 1 || m.wells.filter(w => w.name === c.well).length !== 1 ||
      m.records.some(r => r.keyword === 'WCONINJE' && r.values?.[0] === c.well)) return []
    const perfs = m.completions.filter(cmp => cmp.well === c.well)
    if (!perfs.length || perfs.some(cmp => cmp.status !== 'OPEN')) return []
    const records = m.records.filter(r => r.keyword === 'WCONPROD' && r.sourceFile === c.sourceFile && r.lineNumber === c.lineNumber)
    if (records.length !== 1) return []
    const v = expand(records[0].values)
    if (!v || v.length < 9 || v.length > 12 || v[0] !== c.well || v[1] !== 'OPEN' || v[2] !== 'ORAT' || !number(v[3], c.originalOilRate) ||
      v.slice(4, 8).some(s => s !== '') || !number(v[8], c.bhpLimit) || v.slice(9).some(s => s !== '' && s !== '0')) return []
    seen.add(`${c.well}:${c.timeDays}`); physical.add(`${c.sourceFile}:${c.lineNumber}`); previous = c.timeDays
  }
  return initial.size && phases.size >= 2 ? p.records : []
}
export const packageControlPlanMatches = (p, i) => {
  if (!validPackageControlPlan(p)) return false
  const records = packageControlPlanRecords(i); let previous = -1
  if (!records.length) return false
  for (const e of p.edits) {
    const index = records.findIndex(c => editFields.filter(k => k !== 'targetOilRate').every(k => c[k] === e[k]))
    if (index <= previous) return false
    previous = index
  }
  return true
}
export const buildPackageControlPlan = ({ inspection, baselineRunId, edits }) => {
  if (!Array.isArray(edits) || edits.some(e => !positive(e.targetOilRate))) return null
  const p = { schemaVersion: 'eclipse-control-plan-parameters/2', baselineRunId,
    edits: edits.filter(e => e.targetOilRate !== e.originalOilRate).map(e => Object.fromEntries(editFields.map(k => [k, e[k]]))) }
  return packageControlPlanMatches(p, inspection) ? p : null
}
