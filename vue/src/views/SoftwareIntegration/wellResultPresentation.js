export const wellComparisonIssue = (result, comparison, fields) => {
  if (!comparison) return ''
  if (!result?.model_kind || result.model_kind !== comparison.model_kind) return '模型类别不同，不能叠加比较。'
  if (fields.some(field => !result?.units?.[field]?.displayUnit || !comparison?.units?.[field]?.displayUnit)) {
    return '结果未提供完整单位，无法确认可比性。'
  }
  if (fields.some(field => result.units[field].displayUnit !== comparison.units[field].displayUnit)) {
    return '结果单位不同，不能叠加比较。'
  }
  return ''
}

const finite = value => typeof value === 'number' && Number.isFinite(value)

const exactDeltaStats = (currentPoints, comparisonPoints, coordinate, value) => {
  const grouped = points => {
    const result = new Map()
    for (const point of Array.isArray(points) ? points : []) {
      if (!finite(point?.[coordinate]) || !finite(point?.[value])) continue
      const entries = result.get(point[coordinate]) || []
      entries.push(point[value])
      result.set(point[coordinate], entries)
    }
    return result
  }
  const current = grouped(currentPoints)
  const comparison = grouped(comparisonPoints)
  const deltas = []
  current.forEach((values, coordinateValue) => {
    const comparisonValues = comparison.get(coordinateValue)
    if (values.length !== 1 || comparisonValues?.length !== 1) return
    deltas.push(values[0] - comparisonValues[0])
  })
  if (!deltas.length) return { count: 0, min: null, max: null }
  return { count: deltas.length, min: Math.min(...deltas), max: Math.max(...deltas) }
}

export const wellComparisonDeltaRows = (result, comparison) => [
  { key: 'ipr-pressure', label: 'IPR 压力', coordinateLabel: '流量', coordinateUnit: result?.units?.flow?.displayUnit || '', valueUnit: result?.units?.pressure?.displayUnit || '', ...exactDeltaStats(result?.ipr, comparison?.ipr, 'flow', 'pressure') },
  { key: 'vlp-pressure', label: 'VLP 压力', coordinateLabel: '流量', coordinateUnit: result?.units?.flow?.displayUnit || '', valueUnit: result?.units?.pressure?.displayUnit || '', ...exactDeltaStats(result?.vlp, comparison?.vlp, 'flow', 'pressure') },
  { key: 'profile-pressure', label: 'PT 压力', coordinateLabel: '深度', coordinateUnit: result?.units?.depth?.displayUnit || '', valueUnit: result?.units?.pressure?.displayUnit || '', ...exactDeltaStats(result?.profile, comparison?.profile, 'depth', 'pressure') },
  { key: 'profile-temperature', label: 'PT 温度', coordinateLabel: '深度', coordinateUnit: result?.units?.depth?.displayUnit || '', valueUnit: result?.units?.temperature?.displayUnit || '', ...exactDeltaStats(result?.profile, comparison?.profile, 'depth', 'temperature') }
]

export const wellScenarioLabel = run => {
  const parameters = run?.parameters
  if (!parameters) return '原模型参数（未覆盖）'
  if (parameters.schemaVersion === 'pipesim-well-parameters/1' && finite(parameters.reservoirPressurePsi)) {
    return `地层压力方案：${parameters.reservoirPressurePsi} psia`
  }
  return '参数快照已保存'
}

const csvCell = value => {
  const text = value == null ? '' : String(value)
  const safe = typeof value === 'string' && /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}

export const wellCsv = rows => '\uFEFF' + rows.map(row => row.map(csvCell).join(',')).join('\r\n')

const download = (url, filename) => {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
}

export const downloadWellCsv = (rows, filename) => {
  const url = URL.createObjectURL(new Blob([wellCsv(rows)], { type: 'text/csv;charset=utf-8' }))
  download(url, filename)
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

export const downloadWellChart = (chart, filename) => {
  if (chart) download(chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' }), filename)
}
