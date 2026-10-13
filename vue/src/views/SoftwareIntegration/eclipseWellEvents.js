const WELL = /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/
const exact = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
const validDate = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}

export function eclipseSurfaceRateUnit(unitSystem, phase = 'OIL') {
  if (!['OIL', 'WATER', 'WAT', 'GAS'].includes(phase)) return null
  return ({ METRIC: 'sm³/day', FIELD: phase === 'GAS' ? 'Mscf/day' : 'STB/day', LAB: 'scc/hr', 'PVT-M': 'sm³/day' })[unitSystem] || null
}

// Show an original value only when the date and control share an unambiguous source block.
export function eclipseDatedControl(inspection, keyword, well, date) {
  const timeline = inspection?.scheduleTimeline
  const records = inspection?.scheduleMetadata?.records
  if (!Array.isArray(timeline) || !Array.isArray(records)) return null
  const month = value => ({ JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12 })[String(value).toUpperCase()]
  const matches = timeline.filter(event => event.kind === 'DATES' && event.records?.length === 1 && event.records.some(r =>
    `${r.year}-${String(month(r.month)).padStart(2, '0')}-${String(r.day).padStart(2, '0')}` === date))
  if (matches.length !== 1) return null
  const start = matches[0]
  const end = Math.min(Infinity, ...timeline.filter(e => e.sourceFile === start.sourceFile && e.lineNumber > start.lineNumber).map(e => e.lineNumber))
  const candidates = records.filter(r => r.keyword === keyword && r.sourceFile === start.sourceFile && r.lineNumber > start.lineNumber && r.lineNumber < end && r.values?.[0] === well)
  return candidates.length === 1 ? candidates[0] : null
}

export function validateEclipseWellEvents(parameters, knownWells, knownDates) {
  const group = parameters?.schemaVersion === 'eclipse-schedule-parameters/6'
  if (!['eclipse-schedule-parameters/5', 'eclipse-schedule-parameters/6'].includes(parameters?.schemaVersion) ||
    !exact(parameters, ['schemaVersion', 'baselineRunId', group ? 'wells' : 'well', 'events']) ||
    !Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0) return false
  const wells = group ? parameters.wells : [parameters.well]
  if (!Array.isArray(wells) || wells.length < (group ? 2 : 1) || wells.length > 8 ||
    new Set(wells).size !== wells.length || !wells.every(well => typeof well === 'string' && WELL.test(well)) ||
    (knownWells && !wells.every(well => knownWells.includes(well)))) return false
  const events = parameters.events
  return Array.isArray(events) && events.length === 2 &&
    events.every(event => exact(event, ['date', 'status']) && validDate(event.date) &&
      (!knownDates || knownDates.includes(event.date))) &&
    events[0].status === 'SHUT' && events[1].status === 'OPEN' && events[0].date < events[1].date
}

export function buildEclipseWellEvents({ baselineRunId, wells, shutDate, openDate, knownWells, knownDates }) {
  const selected = Array.isArray(wells) ? [...wells] : []
  const parameters = {
    schemaVersion: selected.length === 1 ? 'eclipse-schedule-parameters/5' : 'eclipse-schedule-parameters/6',
    baselineRunId,
    ...(selected.length === 1 ? { well: selected[0] } : { wells: selected }),
    events: [{ date: shutDate, status: 'SHUT' }, { date: openDate, status: 'OPEN' }]
  }
  return validateEclipseWellEvents(parameters, knownWells, knownDates)
    ? { parameters, error: null }
    : { parameters: null, error: '请选择同版本成功基线、1–8口已有生产井和两个递增的 DATES 日期（关井→重开）' }
}

export function validateEclipseMultiDateEvents(p, knownWells, knownDates) {
  return p?.schemaVersion === 'eclipse-schedule-parameters/7' && exact(p, ['schemaVersion', 'baselineRunId', 'wells', 'events']) &&
    Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && Array.isArray(p.wells) && p.wells.length >= 1 && p.wells.length <= 8 &&
    new Set(p.wells).size === p.wells.length && p.wells.every(w => typeof w === 'string' && WELL.test(w) && (!knownWells || knownWells.includes(w))) &&
    Array.isArray(p.events) && p.events.length >= 2 && p.events.length <= 24 && p.events.every((e, i) =>
      exact(e, ['date', 'status']) && validDate(e.date) && ['OPEN', 'SHUT'].includes(e.status) &&
      (!knownDates || knownDates.includes(e.date)) && (i === 0 || p.events[i - 1].date < e.date))
}

export function buildEclipseMultiDateEvents({ baselineRunId, wells, events, knownWells, knownDates }) {
  const parameters = { schemaVersion: 'eclipse-schedule-parameters/7', baselineRunId,
    wells: Array.isArray(wells) ? [...wells] : [], events: Array.isArray(events) ? events.map(e => ({ ...e })) : [] }
  return validateEclipseMultiDateEvents(parameters, knownWells, knownDates)
    ? { parameters, error: null }
    : { parameters: null, error: '请选择成功基线、1–8口生产井及2–24个按日期递增的已有日期启停事件' }
}

function uniqueWellSeries(summary, well, keyword) {
  const series = Array.isArray(summary?.series)
    ? summary.series.filter(item => item?.keyword === keyword && item.objectName === well) : []
  return series.length === 1 ? series[0] : null
}

function pointsByTime(series) {
  if (!Array.isArray(series?.points) || !series.points.length) return null
  const points = new Map()
  for (const point of series.points) {
    if (!Number.isFinite(point?.timeDays) || !Number.isFinite(point?.value) || points.has(point.timeDays)) return null
    points.set(point.timeDays, point.value)
  }
  return points
}

export function compareEclipseWellRates(currentSummary, baselineSummary, wells, keyword = 'WOPR') {
  const comparisons = wells.map(well => {
    const current = uniqueWellSeries(currentSummary, well, keyword)
    const baseline = uniqueWellSeries(baselineSummary, well, keyword)
    const currentPoints = pointsByTime(current)
    const baselinePoints = pointsByTime(baseline)
    if (!currentPoints || !baselinePoints || typeof current?.unit !== 'string' || !current.unit.trim() || current.unit !== baseline?.unit) {
      return { well, available: false, unit: null, rows: [], changedCount: 0 }
    }
    const rows = [...currentPoints].flatMap(([timeDays, value]) => baselinePoints.has(timeDays)
      ? [{ well, unit: current.unit, timeDays, baseline: baselinePoints.get(timeDays), current: value,
        difference: value - baselinePoints.get(timeDays) }] : [])
    const changedCount = rows.filter(row => Math.abs(row.difference) > Math.max(1e-9, Math.abs(row.baseline) * 1e-8)).length
    return { well, available: rows.length > 0, unit: current.unit, rows, changedCount }
  })
  const units = new Set(comparisons.filter(item => item.available).map(item => item.unit))
  return { available: comparisons.length > 0 && comparisons.every(item => item.available), comparisons,
    unit: units.size === 1 ? [...units][0] : '各井原始单位', rows: comparisons.flatMap(item => item.rows),
    changedCount: comparisons.reduce((count, item) => count + item.changedCount, 0) }
}
