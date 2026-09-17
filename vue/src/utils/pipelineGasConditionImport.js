import { temperatureImportNumber, temperatureImportTableRows } from './pipelineTemperatureImport.js'

export const gasConditionImportColumns = Object.freeze([
  { key: 'sequence', label: '序号' },
  { key: 'pressureMpa', label: '压力（MPa绝压）' },
  { key: 'temperatureC', label: '温度（℃）' }
].map(column => Object.freeze(column)))

/** Use only the caller's manual point; no PVT result or topology data is required. */
export function gasConditionTemplateRows(point) {
  return [gasConditionImportColumns.map(column => column.label), [1, point?.pressureMpa ?? '', point?.temperatureC ?? '']]
}

export function parseGasConditionRows(rows) {
  const entries = temperatureImportTableRows(rows, gasConditionImportColumns, '温压参数')
  if (entries.length !== 1) throw new Error('当前 PVT 只支持一组温压，请保留一行数据并填写序号 1。')
  const { row, line } = entries[0]
  if (temperatureImportNumber(row[0], '序号', line, { minExclusive: 0 }) !== 1) {
    throw new Error(`第 ${line} 行：当前 PVT 只支持一组温压，序号须为 1。`)
  }
  return {
    pressureMpa: temperatureImportNumber(row[1], '压力（MPa绝压）', line, { optional: true, minExclusive: 0 }),
    temperatureC: temperatureImportNumber(row[2], '温度（℃）', line, { optional: true, minExclusive: -273.15 })
  }
}
