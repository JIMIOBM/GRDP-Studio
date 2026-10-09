/**
 * Fold a measured formation pressure to a common datum using a positive
 * pressure-gradient magnitude. Depth is positive downward; elevation is
 * positive upward, so the elevation form reverses the coordinate difference.
 */
const finiteInput = value => {
  if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function calculateDatumPressure({
  measuredPressure,
  measuredCoordinate,
  gradient,
  referenceCoordinate,
  coordinateMode = 'depth'
}) {
  const pressure = finiteInput(measuredPressure)
  const measured = finiteInput(measuredCoordinate)
  const slope = finiteInput(gradient)
  const reference = finiteInput(referenceCoordinate)
  if ([pressure, measured, slope, reference].some(value => value === null)
    || pressure < 0 || slope < 0) return null
  if (coordinateMode !== 'elevation' && (measured < 0 || reference < 0)) return null

  const delta = coordinateMode === 'elevation'
    ? measured - reference
    : reference - measured
  return pressure + slope * delta
}

/** Intercept at coordinate zero for P = P0 + G*H (depth) or P = P0 - G*E (elevation). */
export function calculatePressureIntercept({
  measuredPressure,
  measuredCoordinate,
  gradient,
  coordinateMode = 'depth'
}) {
  const pressure = finiteInput(measuredPressure)
  const coordinate = finiteInput(measuredCoordinate)
  const slope = finiteInput(gradient)
  if ([pressure, coordinate, slope].some(value => value === null) || pressure < 0 || slope < 0) return null
  if (coordinateMode !== 'elevation' && coordinate < 0) return null
  return coordinateMode === 'elevation'
    ? pressure + slope * coordinate
    : pressure - slope * coordinate
}

const dateFields = ['date', 'testDate', 'pressureDate', 'measuredDate', 'test_date', 'pressure_date', 'measured_date']
const pressureFields = ['reserviorPressure', 'reservoirPressure', 'formationPressure', 'pressure', 'reservior_pressure', 'reservoir_pressure', 'formation_pressure']

/** Keep persisted edits tied to a source record when the API provides its ID. */
export function pressureSourceKeys(row, wellName, index) {
  const rawDate = dateFields.map(field => row?.[field]).find(value => value !== null && value !== undefined && String(value).trim() !== '') ?? ''
  const legacySourceKey = `source:${String(wellName)}:${String(rawDate)}:${index}`
  const sourceId = [row?.id, row?.recordId, row?.record_id, row?.staticPressureId, row?.static_pressure_id]
    .find(value => value !== null && value !== undefined && String(value).trim() !== '')
  const sourceKey = sourceId === undefined
    ? legacySourceKey
    : `source:${encodeURIComponent(String(wellName))}:id:${encodeURIComponent(String(sourceId))}`
  return { sourceKey, legacySourceKey }
}

export function pressureRecordDate(row) {
  const raw = dateFields.map(field => row?.[field]).find(value => value !== null && value !== undefined && String(value).trim() !== '')
  if (raw === undefined) return ''
  const match = String(raw).trim().match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})/)
  if (!match) return ''
  const [, year, month, day] = match
  const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)))
  if (date.getUTCFullYear() !== Number(year) || date.getUTCMonth() !== Number(month) - 1 || date.getUTCDate() !== Number(day)) return ''
  return `${year}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`
}

export function isCalculablePressureRecord(row, referenceCoordinate, coordinateMode = 'depth') {
  if (!row || row.deleted === true || !String(row.wellName || '').trim()) return false
  const date = pressureRecordDate({ date: row.date })
  if (!date || date !== String(row.date)) return false
  const pressure = finiteInput(row.measuredPressure)
  const coordinate = finiteInput(row.measuredCoordinate)
  const gradient = finiteInput(row.gradient)
  const reference = finiteInput(referenceCoordinate)
  if ([pressure, coordinate, gradient, reference].some(value => value === null)) return false
  if (pressure < 0 || gradient < 0) return false
  if (coordinateMode !== 'elevation' && (coordinate < 0 || reference < 0)) return false
  const folded = calculateDatumPressure({
    measuredPressure: pressure,
    measuredCoordinate: coordinate,
    gradient,
    referenceCoordinate: reference,
    coordinateMode
  })
  return Number.isFinite(folded) && folded >= 0
}

/** Build the exact values plotted by the well/date comparison, leaving absent pairs blank. */
export function buildPressureGradientComparison({
  rows = [],
  selectedWells = [],
  referenceCoordinate,
  coordinateMode = 'depth',
  dateRange = 'recent3',
  chartAxis = 'well'
}) {
  const selected = new Set(selectedWells.map(String))
  const validRows = rows.filter(row => selected.has(String(row.wellName))
    && isCalculablePressureRecord(row, referenceCoordinate, coordinateMode))
  const allDates = [...new Set(validRows.map(row => row.date))].sort((a, b) => a.localeCompare(b))
  const dates = dateRange === 'all' ? allDates : allDates.slice(-3)
  const activeRows = validRows.filter(row => dates.includes(row.date))
  const byTime = chartAxis === 'time'
  const categories = byTime ? dates : selectedWells.map(String)
  const seriesKeys = byTime ? selectedWells.map(String) : dates
  const grouped = new Map()
  activeRows.forEach(row => {
    const key = JSON.stringify([String(row.wellName), row.date])
    const sum = grouped.get(key) || { total: 0, count: 0 }
    sum.total += calculateDatumPressure({
      measuredPressure: row.measuredPressure,
      measuredCoordinate: row.measuredCoordinate,
      gradient: row.gradient,
      referenceCoordinate,
      coordinateMode
    })
    sum.count++
    grouped.set(key, sum)
  })
  const series = seriesKeys.map(key => {
    const data = categories.map(category => {
      const wellName = byTime ? key : category
      const date = byTime ? category : key
      const values = grouped.get(JSON.stringify([wellName, date]))
      return values ? Number((values.total / values.count).toFixed(4)) : null
    })
    return { key, data }
  })
  const maxPressure = Math.max(0, ...series.flatMap(item => item.data.filter(Number.isFinite)))
  return { validRows, allDates, dates, categories, seriesKeys, series, maxPressure }
}

export function normalizePressureRecord(row, wellName, id) {
  const date = pressureRecordDate(row)
  const pressureValue = pressureFields.map(field => row?.[field]).find(value =>
    value !== null && value !== undefined && !(typeof value === 'string' && value.trim() === '')
  )
  const pressure = pressureValue === null || pressureValue === undefined || pressureValue === ''
    ? ''
    : String(pressureValue)
  return {
    id,
    sourceKey: id,
    wellName: String(row?.wellName || row?.well_name || wellName || ''),
    date,
    measuredPressure: pressure,
    measuredCoordinate: '',
    gradient: '',
    manual: false
  }
}
