import { pressureRecordDate } from './formationPressureGradient.js'

/** 按真实实测静压构建测试法对比，不插值、不模拟缺失值。 */
export function buildPressureTestComparison({ rows = [], selectedWells = [], dateRange = 'recent3', chartAxis = 'well' }) {
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
