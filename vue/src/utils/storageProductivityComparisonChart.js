import { buildComparisonChart, comparisonMethods, pressureForms, comparisonPeriodBar } from './productivityComparisonChart.js'

const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const number = value => value == null ? '—' : Number(value).toLocaleString('en-US', { maximumFractionDigits: 4 })

// 颜色只由井主键决定，取消选井、换周期或换库不会导致同一口井变色。
export function storageWellColor(id) {
  const palette = ['#32a1d2', '#dc9740', '#5879c4', '#46a789', '#8964b3', '#c6677b', '#8a9550', '#657886']
  let hash = 0
  for (const character of String(id)) hash = (hash * 31 + character.charCodeAt(0)) >>> 0
  return palette[hash % palette.length]
}

/** 沿用单井图表基础样式；只改变库级数据组织，不在前端重新求平均。 */
export function buildStoragePeriodChart(response, pressureMethod, emptyText = '请选择井并计算', width = 1000, selected = {}) {
  const option = buildComparisonChart(null, pressureMethod, emptyText)
  const wells = response?.wells || [], periods = response?.periods || []
  const form = pressureForms.find(item => item.value === pressureMethod)?.label || ''
  const method = comparisonMethods.find(item => item.value === response?.method)?.label || ''
  option.title.text = `多周期平均无阻流量对比图（${form}）`
  option.title.subtext = response ? `${method} · ${response.operationType === 'injection' ? '注气' : '采气'} · ${response.operationType === 'injection' ? '注气压力' : '地层压力'} ${number(response.operationType === 'injection' ? response.injectionPressure : response.formationPressure)} MPa` : ''
  option.yAxis.name = '平均无阻流量（10⁴m³/d）'
  option.legend = { ...option.legend, data: wells.map(well => well.wellName), selectedMode: 'multiple', selected }
  const slots = [], periodLabels = []
  const series = wells.map(well => ({ name: well.wellName, type: 'custom', clip: false,
    // 井颜色与图例独立保留，横轴仅为有数据且未隐藏的井分配柱位。
    emphasis: { focus: 'series' }, itemStyle: { color: storageWellColor(well.wellId) },
    encode: { x: 0, y: 1 },
    renderItem: (params, api) => {
      const band = api.size([1, 0])[0], center = (api.value(2) + api.value(3)) / 2
      const step = many ? band : Math.min(80, band)
      const groupCenter = (api.coord([api.value(2), 0])[0] + api.coord([api.value(3), 0])[0]) / 2
      const x = groupCenter + (api.value(0) - center) * step
      return comparisonPeriodBar(api, x, step, api.value(1), well.wellName)
    }, data: [] }))
  periods.forEach((period, periodIndex) => {
    const start = slots.length
    const byWell = new Map(period.wells.map(mean => [String(mean.wellId), mean]))
    const points = []
    wells.forEach((well, wellIndex) => {
      const index = slots.length, mean = byWell.get(String(well.wellId))
      // 无数据和图例隐藏的井不占空位；合法的0值仍保留，不能当作缺失值丢弃。
      if (!Number.isFinite(mean?.averageOpenFlow) || selected[well.wellName] === false) return
      slots.push({ period, well })
      const point = { value: [index, mean.averageOpenFlow], mean, period }
      points.push(point)
      series[wellIndex].data.push(point)
    })
    // 空周期仍保留日期范围，但不画虚构的0流量柱。
    if (wells.length && slots.length === start) slots.push({ period })
    points.forEach(point => point.value.push(start, slots.length - 1))
    if (wells.length) periodLabels.push({ value: [start, slots.length - 1], period })
    if (periodIndex < periods.length - 1) slots.push(null) // 周期间额外留白。
  })
  option.xAxis.data = slots.map((_, index) => String(index))
  option.xAxis.name = '周期 / 计算日期范围'
  option.xAxis.nameGap = 92
  const capacity = Math.max(2, Math.floor((width - 116) / 72))
  // 周期属于整组井，不属于某一根柱子；关闭柱位标签，另绘组级居中标题。
  option.xAxis.axisLabel = { ...option.xAxis.axisLabel, show: false }
  // 按柱位横向滚动，单个周期即使包含几十口井，也不会把所有柱子挤成细线。
  const many = slots.length > capacity
  option.grid.bottom = many ? 155 : 125
  option.dataZoom = many ? [
    { type: 'slider', xAxisIndex: 0, bottom: 12, height: 20, startValue: 0, endValue: capacity - 1,
      minValueSpan: capacity - 1, maxValueSpan: capacity - 1, zoomLock: true, filterMode: 'weakFilter', showDetail: false },
    { type: 'inside', xAxisIndex: 0, startValue: 0, endValue: capacity - 1, zoomLock: true,
      minValueSpan: capacity - 1, maxValueSpan: capacity - 1, filterMode: 'weakFilter', zoomOnMouseWheel: false,
      moveOnMouseWheel: true, moveOnMouseMove: true }
  ] : []
  option.tooltip.formatter = point => {
    const { mean, period } = point.data || {}
    if (!mean) return ''
    return [escape(mean.wellName), `第${period.index}周期：${escape(period.startDate)} 至 ${escape(period.endDate)}`,
      `平均无阻流量：${number(mean.averageOpenFlow)} ×10⁴m³/d`, `参与记录：${mean.recordCount} 条`].join('<br/>')
  }
  option.graphic = series.some(item => item.data.length) ? [] : [{ type: 'text', left: 'center', top: 'middle',
    style: { text: response ? (wells.length && wells.every(well => selected[well.wellName] === false)
      ? '请在图例中选择要显示的井' : '所选井在当前周期内没有有效记录') : emptyText, fill: '#888', fontSize: 13 } }]
  option.series = [...series, ...(periodLabels.length ? [{
    id: 'storage-period-labels', type: 'custom', silent: true, clip: false,
    encode: { x: [0, 1], y: [] }, data: periodLabels, tooltip: { show: false },
    renderItem: (params, api) => {
      const period = periodLabels[params.dataIndex]?.period
      if (!period) return null
      const grid = params.coordSys, band = api.size([1, 0])[0]
      // 滚动时按周期在当前窗口内的可见范围居中，避免组中点滚出屏幕后丢失周期信息。
      const left = Math.max(grid.x, api.coord([api.value(0), 0])[0] - band / 2)
      const right = Math.min(grid.x + grid.width, api.coord([api.value(1), 0])[0] + band / 2)
      if (right - left < 64) return null
      const x = (left + right) / 2, y = grid.y + grid.height + 18
      return { type: 'group', children: [
        { type: 'text', style: { x, y, text: `第${period.index}周期`, align: 'center', verticalAlign: 'top',
          fill: '#555', font: '12px "Microsoft YaHei", sans-serif' } },
        ...(right - left >= 190 ? [{ type: 'text', style: { x, y: y + 22,
          text: `${period.startDate} 至 ${period.endDate}`, align: 'center', verticalAlign: 'top',
          fill: '#888', font: '12px "Microsoft YaHei", sans-serif' } }] : [])
      ] }
    }
  }] : [])]
  return option
}
