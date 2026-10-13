const fields = ['schemaVersion', 'baselineRunId', 'well', 'sourceFile', 'beforeLineNumber', 'beforeDate', 'afterLineNumber', 'afterDate', 'shutDate', 'openDate']
const date = v => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v + 'T00:00:00Z')) && new Date(v + 'T00:00:00Z').toISOString().slice(0, 10) === v && !v.startsWith('0000')
const month = { JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, JLY: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12 }
const iso = r => `${r?.year}-${String(month[r?.month?.toUpperCase()]).padStart(2, '0')}-${String(r?.day).padStart(2, '0')}`
export function validNewDates(p) {
  return p && Object.keys(p).length === fields.length && fields.every(f => Object.hasOwn(p, f)) && p.schemaVersion === 'eclipse-new-dates-parameters/1'
    && Number.isSafeInteger(p.baselineRunId) && p.baselineRunId > 0 && typeof p.well === 'string' && /^[A-Za-z0-9][A-Za-z0-9:_ .-]{0,63}$/.test(p.well)
    && typeof p.sourceFile === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    && Number.isSafeInteger(p.beforeLineNumber) && p.beforeLineNumber > 0 && Number.isSafeInteger(p.afterLineNumber) && p.afterLineNumber > p.beforeLineNumber
    && ['beforeDate', 'afterDate', 'shutDate', 'openDate'].every(k => date(p[k])) && p.beforeDate < p.shutDate && p.shutDate < p.openDate && p.openDate < p.afterDate
}
export function newDatesWindows(i) {
  if (!['eclipse-data-inspection/4', 'eclipse-data-inspection/5'].includes(i?.schemaVersion) || i.packageFiles?.length !== 1 || i.packageFiles[0].relativePath !== i.caseName) return []
  const t = i.scheduleTimeline
  if (!Array.isArray(t) || t.length < 2 || t.some((e, n) => e.kind !== 'DATES' || e.sourceFile !== i.caseName || !Number.isSafeInteger(e.lineNumber) || e.lineNumber <= 0
    || e.records?.length !== 1 || e.records[0].time != null || !date(iso(e.records[0]))
    || n > 0 && (t[n-1].lineNumber >= e.lineNumber || iso(t[n-1].records[0]) >= iso(e.records[0])))) return []
  return t.slice(0, -1).flatMap((before, n) => {
    const after = t[n+1], beforeDate = iso(before.records[0]), afterDate = iso(after.records[0])
    if ((Date.parse(afterDate) - Date.parse(beforeDate)) / 86400000 < 3) return []
    const metadata = i.scheduleMetadata
    const wells = (metadata?.wells || []).filter(w => w.sourceFile === i.caseName && w.lineNumber < after.lineNumber).flatMap(w => {
      const records = (metadata.records || []).filter(r => ['WCONPROD', 'WCONHIST', 'WELOPEN', 'WCONINJE'].includes(r.keyword) && r.sourceFile === i.caseName && r.lineNumber < after.lineNumber && r.values?.[0] === w.name).sort((a,b) => a.lineNumber-b.lineNumber)
      return records.length && !records.some(r => ['WELOPEN', 'WCONINJE'].includes(r.keyword)) && records.at(-1).values[1] === 'OPEN'
        && metadata.completions?.some(c => c.well === w.name && c.sourceFile === i.caseName && c.lineNumber < after.lineNumber && c.status === 'OPEN') ? [w.name] : []
    })
    return wells.length ? [{ sourceFile: i.caseName, beforeLineNumber: before.lineNumber, beforeDate, afterLineNumber: after.lineNumber, afterDate, wells: [...new Set(wells)] }] : []
  })
}
export function buildNewDates({ inspection, baselineRunId, well, beforeLineNumber, shutDate, openDate }) {
  const w = newDatesWindows(inspection).find(w => w.beforeLineNumber === beforeLineNumber && w.wells.includes(well))
  if (!w) return null
  const { wells, ...window } = w
  const p = { schemaVersion: 'eclipse-new-dates-parameters/1', baselineRunId, well, ...window, shutDate, openDate }
  return validNewDates(p) ? p : null
}
// Calendar series come from real SUMMARY DATE outputs; never infer dates from array indices or interpolate rates.
export function newDatesResponse(current, baseline, p) {
  const unavailable = { available: false, verified: false, rows: [] }
  if (!validNewDates(p)) return unavailable
  function rows(summary) {
    const unique = (keyword, objectName) => {
      const items = (summary?.series || []).filter(s => s.keyword === keyword && (objectName ? s.objectName === objectName : !s.objectName))
      if (items.length !== 1 || !items[0].points?.length || new Set(items[0].points.map(x => x.timeDays)).size !== items[0].points.length
        || !items[0].points.every(x => Number.isFinite(x.timeDays) && Number.isFinite(x.value))) return null
      return items[0]
    }
    const rate = unique('WOPR', p.well), day = unique('DAY'), mon = unique('MONTH'), year = unique('YEAR')
    if (!rate || !day || !mon || !year || !['STB/DAY', 'SM3/DAY', 'SM³/DAY'].includes(rate.unit?.trim().toUpperCase())) return null
    const maps = [day, mon, year].map(s => new Map(s.points.map(x => [x.timeDays, x.value])))
    const result = rate.points.map(x => {
      const [d, m, y] = maps.map(v => v.get(x.timeDays))
      const reportDate = `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
      return [d,m,y].every(Number.isInteger) && date(reportDate) ? { timeDays: x.timeDays, date: reportDate, value: x.value, unit: rate.unit } : null
    })
    return result.some(r => !r) ? null : result.sort((a,b) => a.timeDays-b.timeDays)
  }
  const a = rows(current), b = rows(baseline)
  if (!a || !b || a[0].unit !== b[0].unit) return unavailable
  const start = b.find(r => r.timeDays === 0), derivedStart = a.find(r => r.timeDays === 0)
  if (!start || !derivedStart || start.date !== derivedStart.date) return unavailable
  const day = d => (Date.parse(d + 'T00:00:00Z') - Date.parse(start.date + 'T00:00:00Z')) / 86400000
  const consistent = r => new Date(Date.parse(start.date + 'T00:00:00Z') + Math.floor(r.timeDays) * 86400000).toISOString().slice(0,10) === r.date
  if (![...a, ...b].every(consistent)) return unavailable
  const shut = a.filter(r => r.timeDays > day(p.shutDate) && r.timeDays <= day(p.openDate))
  const open = a.filter(r => r.timeDays > day(p.openDate) && r.timeDays <= day(p.afterDate))
  const before = b.filter(r => r.timeDays >= day(p.beforeDate) && r.timeDays <= day(p.shutDate))
  const verified = before.some(r => r.value > 1e-9) && shut.length > 0 && shut.every(r => Math.abs(r.value) <= 1e-9) && open.some(r => r.value > 1e-9)
  return { available: true, verified, rows: [...shut.map(r => ({ ...r, phase: '停井时段' })), ...open.map(r => ({ ...r, phase: '复产时段' }))], baselineBefore: before }
}
