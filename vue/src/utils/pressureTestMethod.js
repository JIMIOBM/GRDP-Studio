import { normalizePressureRecord, pressureRecordDate, pressureSourceKeys } from './formationPressureGradient.js'

/** Merge platform readings with persisted edits, preserving readings absent from a partial platform response. */
export function mergePressureTestRows(sourceRecords = [], savedRows = []) {
  const savedBySource = new Map(savedRows.filter(row => row.sourceKey).map(row => [row.sourceKey, row]))
  const matchedSavedRows = new Set()
  const fetchedRows = sourceRecords.map(({ item, wellName, index }) => {
    const keys = pressureSourceKeys(item, wellName, index)
    const normalized = normalizePressureRecord(item, wellName, keys.sourceKey)
    const saved = savedBySource.get(keys.sourceKey) || savedBySource.get(keys.legacySourceKey)
    if (saved) matchedSavedRows.add(saved)
    return { ...normalized, ...(saved || {}), id: normalized.id, sourceKey: normalized.sourceKey }
  })
  return [...fetchedRows, ...savedRows.filter(row => !matchedSavedRows.has(row))]
}

/** 按真实实测静压构建测试法对比，不插值、不模拟缺失值。 */
export function buildPressureTestComparison({ rows = [], selectedWells = [], dateRange = 'all', chartAxis = 'well' }) {
  const selected = new Set(selectedWells.map(String))
  const validRows = rows.filter(row => {
    if (!selected.has(String(row.wellName)) || row.deleted === true
      || pressureRecordDate({ date: row.date }) !== String(row.date)
      || row.measuredPressure === null || row.measuredPressure === undefined || String(row.measuredPressure).trim() === '') return false
    const pressure = Number(row.measuredPressure)
    return Number.isFinite(pressure) && pressure >= 0
  })
  const allDates = [...new Set(validRows.map(row => row.date))].sort((a, b) => a.localeCompare(b))
  const dates = dateRange === 'all' ? allDates : allDates.slice(-3)
  const activeRows = validRows.filter(row => dates.includes(row.date))
  const byTime = chartAxis === 'time'
  const categories = byTime ? dates : selectedWells.map(String)
  const seriesKeys = byTime ? selectedWells.map(String) : dates
  const grouped = new Map()
  for (const row of activeRows) {
    const key = JSON.stringify([String(row.wellName), row.date])
    const group = grouped.get(key) || { sum: 0, count: 0 }
    group.sum += Number(row.measuredPressure)
    group.count++
    grouped.set(key, group)
  }
  const series = seriesKeys.map(key => ({
    key,
    data: categories.map(category => {
      const wellName = byTime ? key : category
      const date = byTime ? category : key
      const group = grouped.get(JSON.stringify([wellName, date]))
      return group ? Number((group.sum / group.count).toFixed(4)) : null
    })
  }))
  const maxPressure = Math.max(0, ...series.flatMap(item => item.data.filter(Number.isFinite)))
  return { validRows, allDates, dates, categories, seriesKeys, series, maxPressure }
}
