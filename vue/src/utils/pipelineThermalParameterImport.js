import {
  isTemperatureImportBlank, temperatureImportEdges, temperatureImportNumber, parseTemperatureSinglePipeRows
} from './pipelineTemperatureImport.js'
import { gasConductivityInputColumn } from './pipelineInnerImport.js'

const frozenColumns = columns => Object.freeze(columns.map(column => Object.freeze(column)))

export const externalHeatMethods = frozenColumns([
  { value: 'surface-fixed', label: '第一类边界（不计地表热阻）' },
  { value: 'surface-resistance', label: '第二类边界（浅埋，h/D外≤2）' }
])

export const outerInputColumns = frozenColumns([
  { key: 'externalMethod', label: '外部换热方法', unit: '', type: 'select' },
  { key: 'burialDepthM', label: '管中心埋深 h', unit: 'm', type: 'number' },
  { key: 'soilConductivityWmK', label: '土壤导热系数', unit: 'W/(m·K)', type: 'number' },
  { key: 'surfaceCoefficientWm2K', label: '地表综合放热系数', unit: 'W/(m²·K)', type: 'number' }
])

export const overallInputColumns = frozenColumns([
  { key: 'propertyPressureMpa', label: '物性计算压力', unit: 'MPa绝压', type: 'number' },
  { key: 'propertyTemperatureC', label: '物性计算温度', unit: '℃', type: 'number' },
  { key: 'ambientC', label: '环境温度', unit: '℃', type: 'number' },
  { ...gasConductivityInputColumn, type: 'number' }
])

function importColumns(inputColumns) {
  return frozenColumns([
    { key: 'sequence', label: '序号' },
    { key: 'name', label: '管道名称' },
    { key: 'diameterMm', label: '管内径（mm）' },
    ...inputColumns.map(({ key, label, unit }) => ({ key, label: unit ? `${label}（${unit}）` : label }))
  ])
}
export const outerImportColumns = importColumns(outerInputColumns)
export const overallImportColumns = importColumns(overallInputColumns)

const externalMethodAliases = new Map(externalHeatMethods.flatMap((method, index) => [
  [method.value, method.value], [method.label, method.value], [index === 0 ? '第一类边界' : '第二类边界', method.value]
]))
function parseExternalMethod(value, line) {
  if (isTemperatureImportBlank(value)) return null
  const method = typeof value === 'string' ? externalMethodAliases.get(value.trim()) : null
  if (!method) throw new Error(`第 ${line} 行：外部换热方法须填写第一类边界或第二类边界，未确定时可留空。`)
  return method
}

function parameterTemplateRows(graph, settings, columns, inputColumns) {
  return [columns.map(column => column.label), ...temperatureImportEdges(graph, true).map((edge, index) => {
    const config = settings?.segments?.find(segment => segment.edgeId === edge.id)
    return [index + 1, edge.name, edge.parameters.diameterMm, ...inputColumns.map(column => {
      const value = config?.[column.key]
      if (column.key === 'externalMethod') return externalHeatMethods.find(method => method.value === value)?.label ?? value ?? ''
      return value ?? ''
    })]
  })]
}

export function outerImportTemplateRows(graph, settings = {}) {
  return parameterTemplateRows(graph, settings, outerImportColumns, outerInputColumns)
}
export function overallImportTemplateRows(graph, settings = {}) {
  return parameterTemplateRows(graph, settings, overallImportColumns, overallInputColumns)
}

export function parseOuterImportRows(rows, graph) {
  return parseTemperatureSinglePipeRows(rows, graph, {
    columns: outerImportColumns, title: '外部放热系数', fixedDiameter: true,
    parseValues: (row, line) => Object.fromEntries(outerInputColumns.map((column, index) => [
      column.key, column.key === 'externalMethod' ? parseExternalMethod(row[index + 3], line)
        : temperatureImportNumber(row[index + 3], column.label, line, { optional: true, minExclusive: 0 })
    ]))
  })
}

export function parseOverallImportRows(rows, graph) {
  return parseTemperatureSinglePipeRows(rows, graph, {
    columns: overallImportColumns, title: '总传热系数', fixedDiameter: true,
    parseValues: (row, line) => Object.fromEntries(overallInputColumns.map((column, index) => [
      column.key, temperatureImportNumber(row[index + 3], column.label, line, {
        optional: column.key !== 'gasConductivityWmK', minExclusive: ['propertyPressureMpa','gasConductivityWmK'].includes(column.key) ? 0 : column.key === 'propertyTemperatureC' ? -273.15 : null
      })
    ]))
  })
}
