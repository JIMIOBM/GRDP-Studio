/**
 * 库-井筒折算-井间对比的绘图配置：多口井在同一时间节点的深度-压力剖面对比曲线。
 * 页面只负责传入各井折算结果与标题信息，本文件不参与计算与请求。
 */

export const wellboreComparisonPalette = [
  '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
  '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#2f4554'
]

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

const formatPressure = value => Number.isFinite(Number(value)) ? `${Number(value).toFixed(4)} MPa` : '—'

/**
 * @param {Array} wells 各井折算结果，每项 { wellName, profile: [{depth, pressure}], topPressure, bottomPressure }
 * @param {Object} context { methodLabel, date, emptyText }
 */
export function buildWellboreComparisonChart(wells, context = {}) {
  const { methodLabel = '', date = '', emptyText = '请选择井并计算' } = context
  const validWells = (wells || []).filter(well => Array.isArray(well.profile) && well.profile.length)
  const deepest = validWells.reduce((max, well) => Math.max(max, well.profile.at(-1)?.depth ?? 0), 0)

  return {
    animation: false,
    color: wellboreComparisonPalette,
    title: {
      text: '井间压力剖面对比图',
      left: 'center',
      top: 4,
      subtext: [methodLabel, date].filter(Boolean).join(' · '),
      itemGap: 10,
      textStyle: { fontSize: 14, color: '#333', fontWeight: 600 },
      subtextStyle: { fontSize: 12, color: '#777' }
    },
    // 标题（主标题+副标题）下方留出间距再放图例，避免两行文字挤在一起。
    legend: {
      top: 66,
      left: 'center',
      type: 'scroll',
      data: validWells.map(well => well.wellName),
      textStyle: { fontSize: 12, color: '#555' }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { axis: 'y' },
      formatter: points => {
        if (!points?.length) return ''
        const depth = Number(points[0].value?.[1])
        const depthText = Number.isFinite(depth)
          ? depth.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
          : '-'
        const wellRows = points.map(point => {
          const pressure = Number(point.value?.[0])
          const pressureText = Number.isFinite(pressure) ? `${pressure.toFixed(4)} MPa` : '-'
          return `${point.marker}${escapeHtml(point.seriesName)}<span style="float:right;margin-left:24px">${pressureText}</span>`
        }).join('<br/>')
        return `<strong>井深 ${depthText} m</strong><br/>${wellRows}`
      }
    },
    grid: { left: 64, right: 30, top: 108, bottom: 48 },
    xAxis: {
      type: 'value',
      name: '压力 (MPa)',
      scale: true,
      nameLocation: 'middle',
      nameGap: 34,
      axisLine: { show: true, lineStyle: { color: '#888' } },
      axisLabel: { color: '#555', fontSize: 12 },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    yAxis: {
      type: 'value',
      name: '井深 (m)',
      inverse: true,
      min: 0,
      max: deepest || 3100,
      nameLocation: 'middle',
      nameGap: 48,
      axisLine: { show: true, lineStyle: { color: '#888' } },
      axisLabel: { color: '#555', fontSize: 12 },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: validWells.map(well => ({
      name: well.wellName,
      type: 'line',
      showSymbol: false,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 1.6 },
      data: well.profile.map(point => [point.pressure, point.depth]),
      // 曲线两端标注井顶（井深0）与井底（最深处）的折算压力，便于井间数值对比。
      markPoint: {
        symbolSize: 42,
        label: { fontSize: 10, color: '#333', lineHeight: 13 },
        data: [
          { name: `井顶 ${formatPressure(well.topPressure)}`, coord: [well.topPressure, well.profile[0].depth] },
          { name: `井底 ${formatPressure(well.bottomPressure)}`, coord: [well.bottomPressure, well.profile.at(-1).depth] }
        ]
      }
    })),
    graphic: validWells.length ? [] : [{
      type: 'text',
      left: 'center',
      top: 'middle',
      silent: true,
      style: { text: emptyText, fill: '#999', font: '14px "Microsoft YaHei", sans-serif' }
    }]
  }
}
