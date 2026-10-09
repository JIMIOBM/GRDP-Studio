export const pressureTestImportColumns = ['井号', '日期', '实测静压 (MPa)']

const calendarDate = value => {
  if (value instanceof Date && Number.isFinite(value.getTime())) {
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const date = new Date(Date.UTC(1899, 11, 30) + Math.floor(value) * 86400000)
    return Number.isFinite(date.getTime()) ? date.toISOString().slice(0, 10) : ''
  }
  const match = String(value ?? '').trim().match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[T\s].*)?$/)
  if (!match) return ''
  const [, y, m, d] = match
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)))
  if (date.getUTCFullYear() !== Number(y) || date.getUTCMonth() !== Number(m) - 1 || date.getUTCDate() !== Number(d)) return ''
  return `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`
}
const isBlankCell = value => value === null || value === undefined || String(value).trim() === ''
const normalizedHeaders = row => {
  const headers = row.map(value => String(value ?? '').trim())
  while (headers.length && isBlankCell(headers.at(-1))) headers.pop()
  if (headers.length) headers[0] = headers[0].replace(/^\uFEFF/, '')
  return headers
}

export function parsePressureTestRows(rows, allowedWellNames) {
  if (!Array.isArray(rows) || !rows.length) throw new Error('文件中没有可读取的表格')
  const headers = normalizedHeaders(rows[0])
  if (headers.length !== pressureTestImportColumns.length
    || pressureTestImportColumns.some((column, index) => headers[index] !== column)) {
    throw new Error(`表头必须依次为：${pressureTestImportColumns.join('、')}`)
  }
  const wells = new Set(allowedWellNames.map(name => String(name).trim()))
  const output = []
  rows.slice(1).forEach((row, index) => {
    if (row.every(value => value === null || value === undefined || String(value).trim() === '')) return
    const line = index + 2
    if (row.slice(pressureTestImportColumns.length).some(value => value !== null && value !== undefined && String(value).trim() !== '')) {
      throw new Error(`第 ${line} 行：存在表头之外的数据列`)
    }
    const wellName = String(row[0] ?? '').trim()
    if (!wells.has(wellName)) throw new Error(`第 ${line} 行：井号“${wellName}”不是当前储气库成员井`)
    const date = calendarDate(row[1])
    if (!date) throw new Error(`第 ${line} 行：日期无效，请使用 YYYY-MM-DD`)
    if (row[2] === null || row[2] === undefined || String(row[2]).trim() === '') throw new Error(`第 ${line} 行：实测静压不能为空`)
    if (typeof row[2] === 'boolean' || row[2] instanceof Date) throw new Error(`第 ${line} 行：实测静压必须是数字单元格或数字文本`)
    const pressure = Number(row[2])
    if (!Number.isFinite(pressure) || pressure < 0) throw new Error(`第 ${line} 行：实测静压必须是非负数字`)
    output.push({ wellName, date, measuredPressure: String(pressure) })
  })
  if (!output.length) throw new Error('模板中没有可导入的测点行')
  return output
}

/** Make repeated imports idempotent without losing multiple same-day measurements. */
export function mergePressureTestImportRows(existingRows, importedRows, createId) {
  const merged = [...existingRows]
  const claimed = new Set()

  for (const item of importedRows) {
    const candidates = merged.filter(row => row.deleted !== true
      && row.wellName === item.wellName && row.date === item.date)
    const pressure = Number(item.measuredPressure)
    let target = candidates.find(row => !claimed.has(row)
      && Number.isFinite(Number(row.measuredPressure)) && Number(row.measuredPressure) === pressure)
    if (!target) {
      const sourceCandidates = candidates.filter(row => row.manual !== true && !claimed.has(row))
      if (sourceCandidates.length === 1) target = sourceCandidates[0]
    }
    if (!target) {
      const manualCandidates = candidates.filter(row => row.manual === true && !claimed.has(row))
      if (manualCandidates.length === 1) target = manualCandidates[0]
    }
    if (target) {
      target.measuredPressure = item.measuredPressure
      claimed.add(target)
    } else {
      const inserted = {
        id: createId(), sourceKey: null, measuredCoordinate: '', gradient: '', manual: true, ...item
      }
      merged.push(inserted)
      claimed.add(inserted)
    }
  }
  return merged
}
