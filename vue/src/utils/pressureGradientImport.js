export const pressureGradientImportColumns = [
  '井号', '日期', '实测静压 (MPa)', '测压坐标 (m)', '压力梯度 (MPa/m)'
]

const isBlankCell = value => value === null || value === undefined || String(value).trim() === ''
const normalizedHeaders = row => {
  const headers = row.map(value => String(value ?? '').trim())
  while (headers.length && isBlankCell(headers.at(-1))) headers.pop()
  if (headers.length) headers[0] = headers[0].replace(/^\uFEFF/, '')
  return headers
}

export function cleanPressureGradientImportRows(rows, options = {}) {
  if (!Array.isArray(rows)) return []
  let cleaned = rows.map(row => Array.isArray(row) ? [...row] : [])

  if (options.removeEmptyRows) {
    cleaned = cleaned.filter((row, index) => index === 0 || row.some(value => !isBlankCell(value)))
  }

  if (options.removeEmptyColumns && cleaned.length) {
    const columnCount = Math.max(...cleaned.map(row => row.length))
    const retainedColumns = Array.from({ length: columnCount }, (_, index) => index)
      .filter(index => cleaned.some(row => !isBlankCell(row[index])))
    cleaned = cleaned.map(row => retainedColumns.map(index => row[index] ?? ''))
  }

  if (options.removeZeroRows && cleaned.length > 1) {
    cleaned = [cleaned[0], ...cleaned.slice(1).filter(row => {
      const values = row.slice(2, 5).filter(value => !isBlankCell(value))
      return !values.length || !values.every(value => Number.isFinite(Number(value)) && Number(value) === 0)
    })]
  }

  return cleaned
}

const dateText = value => {
  if (value instanceof Date && Number.isFinite(value.getTime())) {
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const date = new Date(Date.UTC(1899, 11, 30) + Math.floor(value) * 86400000)
    if (!Number.isFinite(date.getTime())) return ''
    return date.toISOString().slice(0, 10)
  }
  const match = String(value ?? '').trim().match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[T\s].*)?$/)
  if (!match) return ''
  const [, y, m, d] = match
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)))
  if (date.getUTCFullYear() !== Number(y) || date.getUTCMonth() !== Number(m) - 1 || date.getUTCDate() !== Number(d)) return ''
  return `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
}
const optionalNumber = (value, line, label, minimum = null) => {
  if (value === null || value === undefined || String(value).trim() === '') return ''
  const number = Number(value)
  if (!Number.isFinite(number) || (minimum !== null && number < minimum)) {
    throw new Error(`第 ${line} 行：${label}必须是${minimum === 0 ? '非负' : '有效'}数字`)
  }
  return String(number)
}

export function parsePressureGradientRows(rows, allowedWellNames) {
  if (!Array.isArray(rows) || !rows.length) throw new Error('文件中没有可读取的表格')
  const headers = normalizedHeaders(rows[0])
  if (headers.length !== pressureGradientImportColumns.length
    || pressureGradientImportColumns.some((column, index) => headers[index] !== column)) {
    throw new Error(`表头必须依次为：${pressureGradientImportColumns.join('、')}`)
  }
  const wells = new Set(allowedWellNames.map(name => String(name).trim()))
  const output = []
  rows.slice(1).forEach((row, index) => {
    if (row.every(isBlankCell)) return
    const line = index + 2
    if (row.slice(pressureGradientImportColumns.length).some(value => !isBlankCell(value))) {
      throw new Error(`第 ${line} 行：存在表头之外的数据列`)
    }
    const wellName = String(row[0] ?? '').trim()
    if (!wells.has(wellName)) throw new Error(`第 ${line} 行：井号“${wellName}”不是当前储气库成员井`)
    const date = dateText(row[1])
    if (!date) throw new Error(`第 ${line} 行：日期无效，请使用 YYYY-MM-DD`)
    const measuredPressure = optionalNumber(row[2], line, '实测静压', 0)
    const measuredCoordinate = optionalNumber(row[3], line, '测压坐标')
    const gradient = optionalNumber(row[4], line, '压力梯度', 0)
    if ([measuredPressure, measuredCoordinate, gradient].every(value => value === '')) {
      throw new Error(`第 ${line} 行：至少填写实测静压、测压坐标或压力梯度中的一项`)
    }
    output.push({ wellName, date, measuredPressure, measuredCoordinate, gradient })
  })
  if (!output.length) throw new Error('模板中没有可导入的测点行')
  return output
}

/** Merge one import without collapsing multiple depth measurements from the same well/date. */
export function mergePressureGradientImportRows(existingRows, importedRows, createId) {
  const merged = [...existingRows]
  const claimed = new Set()
  const hasValue = value => value !== null && value !== undefined && String(value).trim() !== ''
  const sameCoordinate = (row, item) => {
    const rowHasCoordinate = hasValue(row.measuredCoordinate)
    const importHasCoordinate = hasValue(item.measuredCoordinate)
    if (!rowHasCoordinate || !importHasCoordinate) return rowHasCoordinate === importHasCoordinate
    return Number(row.measuredCoordinate) === Number(item.measuredCoordinate)
  }

  for (const item of importedRows) {
    const candidates = merged.filter(row => row.deleted !== true
      && row.wellName === item.wellName && row.date === item.date)
    let target = candidates.find(row => !claimed.has(row) && sameCoordinate(row, item))
    if (!target) {
      const sourceCandidates = candidates.filter(row => row.manual !== true && !claimed.has(row))
      if (sourceCandidates.length === 1) target = sourceCandidates[0]
    }
    if (target) {
      claimed.add(target)
      if (item.measuredPressure !== '') target.measuredPressure = item.measuredPressure
      if (item.measuredCoordinate !== '') target.measuredCoordinate = item.measuredCoordinate
      if (item.gradient !== '') target.gradient = item.gradient
    } else {
      merged.push({ id: createId(), sourceKey: null, ...item, manual: true })
    }
  }
  return merged
}
