import { buildComparisonChart, comparisonMethods, pressureForms } from './productivityComparisonChart.js'

const number = value => Number(value).toLocaleString('en-US', { maximumFractionDigits: 4 })
const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

/** 库多方法：周期 → 井 → 方法。前端只排布后端均值，不再混合、计算或舍入原数据。 */
export function buildStorageMethodChart(response, pressureMethod, emptyText = '请选择井和方法并计算', width = 1000, selected = {}) {
  return buildStorageGroupedChart(response, pressureMethod, emptyText, width, selected, {
    items: (response?.methods || []).map(value => comparisonMethods.find(method => method.value === value)).filter(Boolean),
    field: 'methods', key: 'method', kind: '方法', title: '多方法', axisName: '周期 / 单井 / 对比方法',
    subtitle: response ? `${response.operationType === 'injection' ? '注气 · 注气压力' : '采气 · 地层压力'} ${number(response.operationType === 'injection' ? response.injectionPressure : response.formationPressure)} MPa` : ''
  })
}

/** 复用“周期—井—柱”排布，注采对比只替换最内层维度，不把方向伪装成计算方法。 */
export function buildStorageGroupedChart(response, pressureMethod, emptyText, width, selected, dimension) {
  const option = buildComparisonChart(null, pressureMethod, emptyText)
  const methods = dimension.items
  const periods = response?.periods || [], wells = response?.wells || []
  const slots = [], wellLabels = [], periodLabels = []
  const series = methods.map(method => ({
    name: method.label, type: 'custom', clip: false, encode: { x: 0, y: 1 },
    itemStyle: { color: method.color }, emphasis: { focus: 'series' }, data: [],
    renderItem: (params, api) => {
      const band = api.size([1, 0])[0], step = many ? band : Math.min(64, band)
      const center = (api.value(2) + api.value(3)) / 2
      const groupCenter = (api.coord([api.value(2), 0])[0] + api.coord([api.value(3), 0])[0]) / 2
      const x = groupCenter + (api.value(0) - center) * step
      const y = api.coord([api.value(0), api.value(1)])[1], bottom = api.coord([api.value(0), 0])[1]
      const barWidth = Math.min(36, step * .75)
      return { type: 'group', children: [
        { type: 'rect', shape: { x: x - barWidth / 2, y, width: barWidth, height: bottom - y }, style: { fill: api.visual('color') } },
        { type: 'text', silent: true, style: { x, y: y - 7, text: number(api.value(1)), align: 'center', verticalAlign: 'bottom',
          fill: '#444', font: '12px "Microsoft YaHei", sans-serif', width: Math.max(20, step - 4), overflow: 'truncate' } }
      ] }
    }
  }))
  periods.forEach((period, periodIndex) => {
    const periodStart = slots.length
    const byWell = new Map(period.wells.map(well => [String(well.wellId), well]))
    wells.forEach(well => {
      const byMethod = new Map((byWell.get(String(well.wellId))?.[dimension.field] || []).map(mean => [mean[dimension.key], mean]))
      const visible = methods.map((method, index) => ({ method, index, mean: byMethod.get(method.value) }))
        .filter(({ method, mean }) => selected[method.label] !== false && Number.isFinite(mean?.averageOpenFlow))
      if (!visible.length) return // 缺失方法不补零，整口井无柱时也不留一个空柱组。
      if (slots.length > periodStart) slots.push(null) // 井间留白大于同井的方法间距。
      const start = slots.length, end = start + visible.length - 1
      visible.forEach(({ method, index, mean }) => {
        series[index].data.push({ value: [slots.length, mean.averageOpenFlow, start, end], well, method, mean, period })
        slots.push({ well, method, period })
      })
      wellLabels.push({ value: [start, end], text: well.wellName })
    })
    const empty = slots.length === periodStart
    if (empty) slots.push(null) // 保留空周期的日期范围，不生成虚构柱子。
    periodLabels.push({ value: [periodStart, slots.length - 1], period, empty })
    if (periodIndex < periods.length - 1) slots.push(null, null)
  })
  const capacity = Math.max(3, Math.floor((width - 116) / 42)), many = slots.length > capacity
  option.title.text = `${dimension.title}平均无阻流量对比图（${pressureForms.find(form => form.value === pressureMethod)?.label || ''}）`
  option.title.subtext = dimension.subtitle
  option.legend = { ...option.legend, data: methods.map(method => method.label), selected }
  option.yAxis.name = '平均无阻流量（10⁴m³/d）'
  option.xAxis.data = slots.map((_, i) => String(i))
  option.xAxis.name = dimension.axisName
  option.xAxis.nameGap = 122
  option.xAxis.axisLabel.show = false
  option.grid.bottom = many ? 192 : 158
  option.dataZoom = many ? [
    { type: 'slider', xAxisIndex: 0, bottom: 12, height: 20, startValue: 0, endValue: capacity - 1,
      minValueSpan: capacity - 1, maxValueSpan: capacity - 1, zoomLock: true, filterMode: 'weakFilter', showDetail: false },
    { type: 'inside', xAxisIndex: 0, startValue: 0, endValue: capacity - 1, zoomLock: true,
      minValueSpan: capacity - 1, maxValueSpan: capacity - 1, filterMode: 'weakFilter', zoomOnMouseWheel: false,
      moveOnMouseWheel: true, moveOnMouseMove: true }
  ] : []
  const labels = (id, data, isPeriod) => ({
    id, type: 'custom', silent: true, clip: false, encode: { x: [0, 1], y: [] }, data, tooltip: { show: false },
    renderItem: (params, api) => {
      const item = data[params.dataIndex], grid = params.coordSys, band = api.size([1, 0])[0]
      const left = Math.max(grid.x, api.coord([api.value(0), 0])[0] - band / 2)
      const right = Math.min(grid.x + grid.width, api.coord([api.value(1), 0])[0] + band / 2)
      if (right - left < 35) return null
      const x = (left + right) / 2, y = grid.y + grid.height
      const text = (value, offset, color = '#555') => ({ type: 'text', style: { x, y: y + offset, text: value,
        align: 'center', verticalAlign: 'top', fill: color, font: '12px "Microsoft YaHei", sans-serif',
        width: Math.max(24, right - left - 8), overflow: 'truncate' } })
      if (!isPeriod) return text(item.text, 14)
      return { type: 'group', children: [
        { type: 'polyline', shape: { points: [[left + 6, y + 38], [left + 6, y + 44], [right - 6, y + 44], [right - 6, y + 38]] }, style: { stroke: '#aaa', fill: null, lineWidth: 1 } },
        text(`第${item.period.index}周期${item.empty ? '（无显示数据）' : ''}`, 52),
        ...(right - left >= 190 ? [text(`${item.period.startDate} 至 ${item.period.endDate}`, 74, '#888')] : [])
      ] }
    }
  })
  option.tooltip.formatter = point => {
    const { well, method, mean, period } = point.data || {}
    if (!mean) return ''
    return [`${escape(well.wellName)} · ${escape(method.label)}`,
      `第${period.index}周期：${escape(period.startDate)} 至 ${escape(period.endDate)}`,
      `平均无阻流量：${number(mean.averageOpenFlow)} ×10⁴m³/d`, `参与记录：${mean.recordCount} 条`].join('<br/>')
  }
  option.graphic = series.some(item => item.data.length) ? [] : [{ type: 'text', left: 'center', top: 'middle',
    style: { text: response ? (methods.every(method => selected[method.label] === false)
      ? `请在图例中选择要显示的${dimension.kind}` : `所选井、周期和${dimension.kind}下没有有效记录`) : emptyText, fill: '#888', fontSize: 13 } }]
  option.series = [...series, ...(periodLabels.length ? [labels('storage-method-well-labels', wellLabels, false), labels('storage-method-period-labels', periodLabels, true)] : [])]
  return option
}
