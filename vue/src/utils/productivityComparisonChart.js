export const pressureForms = [
  { value: 'pressure', label: '压力法' },
  { value: 'pressure-squared', label: '压力平方法' },
  { value: 'pseudo-pressure', label: '拟压力法' }
]
export const comparisonMethods = [
  { value: 'back-pressure', label: '回压试井', color: '#32a1d2' },
  { value: 'isochronal', label: '等时试井', color: '#dc9740' },
  { value: 'modified-isochronal', label: '修正等时', color: '#5879c4' },
  { value: 'one-point', label: '一点法', color: '#5832d8' },
  { value: 'stable', label: '理论稳定流', color: '#46a789' },
  { value: 'unstable', label: '理论不稳定流', color: '#c6677b' }
]
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const number = value => Number.isFinite(value) ? Number(value).toLocaleString('en-US', { maximumFractionDigits: 4 }) : '—'
const operationOf = record => record.operationType || 'production'

// ECharts' built-in overlap handling does not reliably stagger labels across separate bar series.
// Share one placement pass between directions, and reset it after each synchronous chart layout (also on resize/zoom).
function visibleBarLabels() {
  let occupied = [], resetPending = false
  return ({ rect, labelRect }) => {
    if (!resetPending) {
      resetPending = true
      queueMicrotask(() => { occupied = []; resetPending = false })
    }
    const x = rect.x + rect.width / 2, width = labelRect.width, height = labelRect.height
    let bottom = rect.y - 6
    const left = x - width / 2, right = x + width / 2
    // Move upwards until this whole two-line label has a clear slot; never suppress a value.
    for (let pass = 0; pass <= occupied.length; pass++) {
      const collision = occupied.find(box => left < box.right + 4 && right + 4 > box.left
        && bottom > box.top - 4 && bottom - height < box.bottom + 4)
      if (!collision) break
      bottom = collision.top - 4
    }
    occupied.push({ left, right, top: bottom - height, bottom })
    return { x, y: bottom, align: 'center', verticalAlign: 'bottom', hideOverlap: false }
  }
}

export function buildDirectionComparisonChart(response, pressureMethod, emptyText = '请选择采气或注气记录并计算') {
  const option = buildComparisonChart(response, pressureMethod, emptyText)
  const groups = response?.directionGroups || []
  const results = response?.results || []
  const form = pressureForms.find(item => item.value === pressureMethod)?.label || ''
  option.title.text = `注采无阻流量对比图（${form}）`
  const directions = [
    { type: 'production', name: '采气', color: '#5879c4' },
    { type: 'injection', name: '注气', color: '#dc9740' }
  ].filter(direction => results.some(row => operationOf(row.record) === direction.type))
  // Only show directions actually selected; an absent side is not a zero-flow series.
  option.legend = { ...option.legend, top: 94, data: directions.map(direction => direction.name) }
  option.grid.top = 132
  const many = groups.length > 4
  option.grid.bottom = many ? 125 : 90
  option.xAxis.name = '计算日期'
  option.xAxis.data = groups.map(group => group.date)
  option.xAxis.axisLabel.formatter = date => date
  option.dataZoom = many ? [{ type: 'slider', xAxisIndex: 0, bottom: 12, height: 20,
    startValue: 0, endValue: 3, filterMode: 'filter', showDetail: false }] : []
  const tooltip = option.tooltip.formatter
  const indexes = new Map(results.map((row, index) => [row.record.key, index]))
  const labelLayout = visibleBarLabels()
  option.tooltip.formatter = point => point.data?.row
    ? `${operationOf(point.data.row.record) === 'injection' ? '注气' : '采气'}<br/>`
      + tooltip({ dataIndex: indexes.get(point.data.row.record.key) }) : ''
  option.series = directions.flatMap(direction => {
    const rows = groups.map(group => group.results.filter(row => operationOf(row.record) === direction.type))
    const slots = Math.max(1, ...rows.map(items => items.length))
    return Array.from({ length: slots }, (_, slot) => ({
      name: direction.name, type: 'bar', barMaxWidth: 44, barGap: '25%', barCategoryGap: '40%',
      itemStyle: { color: direction.color },
      // Equal-height neighbouring bars must keep both labels; stagger collisions vertically instead of hiding them.
      labelLayout,
      labelLine: { show: true, lineStyle: { color: '#999' } },
      label: { show: true, position: 'top', color: '#444', fontSize: 12, lineHeight: 18, width: 90, overflow: 'truncate',
        formatter: point => point.data?.row ? `${number(point.value)}\n${point.data.row.record.recordName}` : '' },
      // Missing sides are absent values, not zero flow. Multiple records remain independent bars.
      data: rows.map(items => items[slot] ? { value: items[slot].openFlowCapacity, row: items[slot] } : null)
    }))
  })
  return option
}

// Period boundaries and membership come from the backend's saved calculation dates.
// Each slot is an independent record, never a sum or average for its period.
export function buildPeriodComparisonChart(response, pressureMethod, emptyText = '请选择记录并计算') {
  const option = buildComparisonChart(response, pressureMethod, emptyText)
  const periods = response?.periods || []
  const results = response?.results || []
  const form = pressureForms.find(item => item.value === pressureMethod)?.label || ''
  option.title.text = `多周期无阻流量对比图（${form}）`
  const slots = Math.max(0, ...periods.map(period => period.results.length))
  const visiblePeriods = Math.max(1, Math.min(4, Math.floor(16 / Math.max(1, slots))))
  const many = periods.length > visiblePeriods
  option.grid.bottom = many ? 155 : 125
  option.xAxis.data = periods.map(period => String(period.index))
  option.xAxis.name = '周期 / 计算日期范围'
  option.xAxis.nameGap = 92
  option.xAxis.axisLabel.formatter = (_, index) => {
    const period = periods[index]
    return period ? `第${period.index}周期${period.results.length ? '' : '（无记录）'}\n${period.startDate}\n至 ${period.endDate}` : ''
  }
  option.dataZoom = many ? [{ type: 'slider', xAxisIndex: 0, bottom: 12, height: 20,
    startValue: 0, endValue: visiblePeriods - 1, filterMode: 'filter', showDetail: false }] : []
  const tooltip = option.tooltip.formatter
  const resultIndexes = new Map(results.map((row, index) => [row.record.key, index]))
  option.tooltip.formatter = point => {
    const row = point.data?.row
    if (!row) return ''
    const period = periods[point.dataIndex]
    return `第${period.index}周期：${escape(period.startDate)} 至 ${escape(period.endDate)}<br/>`
      + tooltip({ dataIndex: resultIndexes.get(row.record.key) })
  }
  option.series = Array.from({ length: slots }, (_, slot) => ({
    name: results[0]?.record.methodName || '', type: 'bar', barMaxWidth: 44,
    // No stack: records in a period stand side by side, with a wider category gap.
    barGap: '25%', barCategoryGap: '40%',
    label: { show: true, position: 'top', color: '#444', fontSize: 12, lineHeight: 18,
      width: 90, overflow: 'truncate', formatter: point => point.data?.row
        ? `${number(point.value)}\n${point.data.row.record.recordName}` : '' },
    data: periods.map(period => {
      const row = period.results[slot]
      if (!row) return null
      const method = comparisonMethods.find(item => item.value === row.record.method)
      return { value: row.openFlowCapacity, row, itemStyle: { color: method?.color || '#5879c4' } }
    })
  }))
  return option
}

export function buildComparisonChart(response, pressureMethod, emptyText = '请选择记录并计算') {
  const results = response?.results || []
  const methodName = pressureForms.find(item => item.value === pressureMethod)?.label || ''
  const methods = comparisonMethods.flatMap(method => ['production', 'injection']
    .filter(operationType => results.some(item => item.record.method === method.value && operationOf(item.record) === operationType))
    .map(operationType => ({ ...method, operationType, seriesName: method.label })))
  const conditions = []
  if (results.some(item => operationOf(item.record) === 'production')) conditions.push(`采气：地层压力 ${number(response.formationPressure)} MPa，井底流压 0.1 MPa`)
  if (results.some(item => operationOf(item.record) === 'injection')) conditions.push(`注气：注气压力 ${number(response.injectionPressure)} MPa，地层压力 0.1 MPa`)
  const many = results.length > 8
  return {
    animation: false,
    textStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 12 },
    title: { text: `多方法无阻流量对比图（${methodName}）`, left: 'center', top: 12,
      subtext: conditions.join('\n'),
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }, subtextStyle: { color: '#777', fontSize: 12 } },
    legend: { type: 'scroll', right: 24, top: 76, data: methods.map(item => item.seriesName), icon: 'rect', itemWidth: 10, itemHeight: 10 },
    grid: { left: 82, right: 34, top: 110, bottom: many ? 125 : 90 },
    xAxis: { type: 'category', data: results.map(item => item.record.key), name: '计算方法 / 计算日期',
      nameLocation: 'middle', nameGap: 60, axisLine: { lineStyle: { color: '#777' } }, axisTick: { show: false },
      axisLabel: { color: '#555', fontSize: 12, hideOverlap: true, interval: 0, lineHeight: 20,
        formatter: (_, index) => {
          const record = results[index]?.record
          return record ? `${record.methodName}\n${record.date || '日期未记录'}` : ''
        } } },
    yAxis: { type: 'value', min: 0, name: '无阻流量（10⁴m³/d）', nameLocation: 'middle', nameGap: 58,
      axisLine: { show: true, lineStyle: { color: '#777' } }, axisLabel: { color: '#555' },
      splitLine: { lineStyle: { color: '#dfe6f1' } }, minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } } },
    tooltip: { trigger: 'item', confine: true, formatter: point => {
      const row = results[point.dataIndex]
      if (!row) return ''
      const lines = [escape(row.record.recordName), `${escape(row.record.dateKind)}：${escape(row.record.date || '未记录')}`,
        `${escape(row.record.methodName)} · ${escape(methodName)} · 二项式`,
        `A：${escape(row.a)}　B：${escape(row.b)}`,
        `地层压力：${number(row.formationPressure ?? (operationOf(row.record) === 'injection' ? .1 : response.formationPressure))} MPa　井底流压：${number(row.flowingPressure ?? (operationOf(row.record) === 'injection' ? response.injectionPressure : .1))} MPa`,
        `无阻流量：${number(row.openFlowCapacity)} ×10⁴m³/d`]
      if (pressureMethod === 'pseudo-pressure') lines.push(`m(pᵣ)：${number(row.reservoirPseudoPressure)}　m(p_wf)：${number(row.flowingPseudoPressure)}`)
      return lines.join('<br/>')
    } },
    dataZoom: many ? [{ type: 'slider', xAxisIndex: 0, bottom: 12, height: 20, startValue: 0, endValue: 7, filterMode: 'filter', showDetail: false }] : [],
    graphic: results.length ? [] : [{ type: 'text', left: 'center', top: 'middle', style: { text: emptyText, fill: '#888', fontSize: 13 } }],
    series: methods.map(method => ({ name: method.seriesName, type: 'bar', stack: 'records', barMaxWidth: 44,
      itemStyle: { color: method.color, ...(method.operationType === 'injection' ? { decal: { symbol: 'rect', dashArrayX: [1, 0], dashArrayY: [3, 4], rotation: -Math.PI / 4, color: 'rgba(255,255,255,0.6)' } } : {}) }, label: { show: true, position: 'top', color: '#444', fontSize: 12,
        formatter: item => number(item.value) },
      data: results.map(row => row.record.method === method.value && operationOf(row.record) === method.operationType ? row.openFlowCapacity : null) }))
  }
}
