<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { materialBalanceApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const emit = defineEmits(['refresh-tree'])

const loading = ref(false)
const resultData = ref(null)
const activePanelTab = ref('input')
const activeChartTab = ref(0)
const activeContentTab = ref('chart')
const chartEl = ref(null)
const chartAreaEl = ref(null)
const equationGraphicPosition = ref(null)
const legendPosition = ref({ x: null, y: null })
const draggingLegend = ref(false)
const legendDragOffset = ref({ x: 0, y: 0 })
const hiddenLegendNames = ref(new Set())

const paramsPanelEl = ref(null)
const paramsPanelWidth = ref(238)
const paramsCollapsed = ref(false)
const resizingParamsPanel = ref(false)
//计算方式参数-物质平衡方程
const reservoirType=ref('constant')
//水气比
const enableWaterGasRatioLimit = ref(false)
const waterGasRatioLimitValue = ref('0.0602')

let chart = null
let requestSeq = 0

const rawNode = computed(() => props.node?.raw || {})
const currentWellName = computed(() =>
    props.node?.wellName ||
    rawNode.value?.wellName ||
    rawNode.value?.name ||
    props.node?.name ||
    props.node?.label ||
    ''
)
const chartTabTitle = computed(() =>
    `物质平衡-${currentWellName.value || '当前井'}-分析结果`)

const findDynamicOriginalGasInPlaceId = (value) => {
  if (!value || typeof value !== 'object') return null

  const idKeys = [
    'DynamicOriginalGasInPlaceId',
    'dynamicOriginalGasInPlaceId',
    'dynamicOriginalGasInplaceId',
    'dynamicOriginalGasInplaceID',
    'resultId',
    'ResultId'
  ]

  for (const key of idKeys) {
    if (value[key] !== undefined && value[key] !== null && value[key] !== '') {
      return value[key]
    }
  }

  if (value.DynamicOriginalGasInPlaceId !== undefined && value.DynamicOriginalGasInPlaceId !== null) {
    return value.DynamicOriginalGasInPlaceId
  }

  if (value.dynamicOriginalGasInPlaceId !== undefined && value.dynamicOriginalGasInPlaceId !== null) {
    return value.dynamicOriginalGasInPlaceId
  }

  const children = Array.isArray(value) ? value : Object.values(value)
  for (const child of children) {
    const id = findDynamicOriginalGasInPlaceId(child)
    if (id !== null && id !== undefined && id !== '') return id
  }

  return null
}

const getDynamicOriginalGasInPlaceIdFromNode = () =>
    findDynamicOriginalGasInPlaceId({
      node: props.node,
      raw: rawNode.value,
      parentNode: rawNode.value?.parentNode,
      rootNode: rawNode.value?.rootNode
    })

const getAverageFormationPressureRows = (payload) => {
  const rows =
      payload?.data?.data ??
      payload?.data ??
      payload?.items ??
      payload?.rows ??
      payload

  if (Array.isArray(rows)) return rows
  return rows ? [rows] : []
}

const getMaterialBalanceResultId = (row) => {
  if (!row || typeof row !== 'object') return null
  return (
      row.DynamicOriginalGasInPlaceId ??
      row.dynamicOriginalGasInPlaceId ??
      row.dynamicOriginalGasInplaceId ??
      row.dynamicOriginalGasInplaceID ??
      row.id ??
      null
  )
}

const MATERIAL_BALANCE_TYPES = new Set([1, 2])

const isMaterialBalanceRow = (row) => {
  const type = Number(row?.dynamicOriginalGasInplaceType)

  // averageFormationPressure 接口会返回同一井的多种动态储量分析结果。
  // 物质平衡页面只接收 1（实测静压）和 2（计算静压），排除流动/动态物质平衡结果。
  if (Number.isFinite(type)) return MATERIAL_BALANCE_TYPES.has(type)

  // 兼容缺少类型字段的旧数据，避免历史物质平衡结果无法展示。
  const description = row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || ''
  return /物质平衡方程-根据(?:实测|计算)静压/.test(description)
}

const getMaterialBalanceTabLabel = (row, index) => {
  const description = row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || ''
  if (/实测静压/.test(description)) return '根据实测静压'
  if (/计算静压/.test(description)) return '根据计算静压'
  return description || `图表 ${index + 1}`
}

const mergeResultWithAverageRow = (result, averageRow, index) => {
  // 输出值必须以 result 接口为准；平均地层压力接口只用于确定结果页签及结果 ID，
  // 不能整行合并到 output，否则同名字段会覆盖 result 接口返回的真实计算结果。
  const output = result?.output || result?.result || {}
  const label = getMaterialBalanceTabLabel(averageRow || output, index)

  return {
    ...result,
    name: label,
    label,
    title: label,
    chartItems: getChartItems(result),
    output
  }
}

const input = computed(() => resultData.value?.input || {})

const getChartItems = (value) => {
  const items =
      value?.chartItems ??
      value?.charttItems ??
      value?.charttItem ??
      value?.chartItem ??
      value?.charts ??
      []
  return Array.isArray(items) ? items : []
}


const chartTabs = computed(() => {
  const outputs = resultData.value?.outputs
  if (Array.isArray(outputs) && outputs.length) {
    return outputs.map((item, index) => ({
      label: item.name || item.title || item.label || `图表 ${index + 1}`,
      chartItems: getChartItems(item),
      output: item.output || item.result || {}
    }))
  }

  return [{
    label: resultData.value?.name || resultData.value?.title || '根据计算静压',
    chartItems: getChartItems(resultData.value),
    output: resultData.value?.output || resultData.value?.result || {}
  }]
})

const output = computed(() => chartTabs.value[activeChartTab.value]?.output || {})
const activeChartItems = computed(() => chartTabs.value[activeChartTab.value]?.chartItems || [])

const toNumber = (value) => {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

const formatSci = (value) => {
  if (!Number.isFinite(value)) return ''
  if (value === 0) return '0'
  const sign = value < 0 ? '-' : ''
  const abs = Math.abs(value)
  const exponent = Math.floor(Math.log10(abs))
  const coefficient = (abs / 10 ** exponent).toFixed(4)
  return `${sign}${coefficient}E${exponent >= 0 ? '+' : ''}${exponent}`
}

const displayedOutputFields = computed(() => {
  const value = output.value || {}

  // 输出区不再依赖 resultFields 动态生成，始终按产品要求展示这五项结果。
  // gradient/rsquared 是当前接口字段，slope/r2 用于兼容旧结果数据。
  return [
    {
      key: 'originalGasVolume',
      label: '动态储量(10⁸m³)',
      value: value.originalGasVolume ?? ''
    },
    {
      key: 'intercept',
      label: '回归分析截距(MPa)',
      value: formatSci(toNumber(value.intercept))
    },
    {
      key: 'gradient',
      label: '回归分析斜率(MPa/10⁸m³)',
      value: formatSci(toNumber(value.gradient ?? value.slope))
    },
    {
      key: 'rsquared',
      label: 'R²(dless)',
      value: value.rsquared ?? value.r2 ?? ''
    },
    {
      key: 'reliability',
      label: '结果可靠性',
      // 接口的可靠性文字字段为 reliabilityDescription；其余字段用于兼容旧数据。
      value: value.reliabilityDescription ?? value.reliability ?? value.reliablity ?? ''
    }
  ]
})

const MODIFICATION_METHODS = {
  0: 'Wichert-Aziz 修正方法',
  1: 'Carr-Kobayashi-Burrous 修正方法'
}

const DEVIATION_METHODS = {
  0: 'Dranchuk-Abu-Kassem 方法',
  1: 'Dranchuk-Purvis-Robinson 方法',
  2: 'Hall-Yarborough 方法'
}

const GAS_TYPES = {
  0: '干气',
  1: '湿气'
}

const toSelectOptions = (map) => Object.values(map).map(value => ({ label: value, value }))

const getInputValue = (keys, fallback = '') => {
  for (const key of keys) {
    const value = input.value?.[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return fallback
}

const getMappedInputValue = (keys, map) => {
  const value = getInputValue(keys)
  if (value === '') return ''
  return map?.[value] ?? map?.[Number(value)] ?? value
}

const groupedInputSections = computed(() => [
  {
    title: '气体性质',
    fields: [
      {
        key: 'gasType',
        label: '天然气类型',
        value: getMappedInputValue(['gasType'], GAS_TYPES),
        options: toSelectOptions(GAS_TYPES)
      },
      { key: 'specificGravity', label: '天然气比重(dless)', value: getInputValue(['specificGravity']) },
      { key: 'hydrogenSulfide', label: 'H₂S摩尔百分含量(%)', value: getInputValue(['hydrogenSulfide', 'h2s', 'H2S']) },
      { key: 'carbonDioxide', label: 'CO₂摩尔百分含量(%)', value: getInputValue(['carbonDioxide', 'co2', 'CO2']) },
      { key: 'nitrogen', label: 'N₂摩尔百分含量(%)', value: getInputValue(['nitrogen', 'n2', 'N2']) }
    ]
  },
  {
    title: '计算方法',
    fields: [
      {
        key: 'modificationMethod',
        label: '非烃气体修正方法',
        value: getMappedInputValue(['modificationMethod'], MODIFICATION_METHODS),
        options: toSelectOptions(MODIFICATION_METHODS)
      },
      {
        key: 'deviationFactorMethod',
        label: '天然气偏差系数计算方法',
        value: getMappedInputValue(['deviationFactorMethod'], DEVIATION_METHODS),
        options: toSelectOptions(DEVIATION_METHODS)
      }
    ]
  },
  {
    title: '其他方法',
    fields: [
      { key: 'formationTemperature', label: '地层温度(℃)', value: getInputValue(['formationTemperature', 'temperature']) },
      { key: 'waterGasRatioLimit', label: '生产水气比上限(m³/10⁴m³)', value: getInputValue(['waterGasRatioLimit']) }
    ]
  }
])

const hasDisplayedInputFields = computed(() =>
    groupedInputSections.value.some(section =>
        section.fields.some(field => field.value !== undefined && field.value !== null && field.value !== '')
    )
)

const getPointFromRow = (row) => {
  if (Array.isArray(row) && row.length >= 2) {
    const x = toNumber(row[0])
    const y = toNumber(row[1])
    return x !== null && y !== null ? [x, y] : null
  }

  if (!row || typeof row !== 'object') return null

  const x = toNumber(
      row.xValue ??
      row.gp ??
      row.Gp ??
      row.cumulativeGasProduction ??
      row.cumulativeGas ??
      row.cumGas ??
      row.x
  )
  const y = toNumber(
      row.yValue ??
      row.pressure ??
      row.pp ??
      row.Pp ??
      row.averageFormationPressure ??
      row.formationPressure ??
      row.calculatedStaticPressure ??
      row.actualStaticPressure ??
      row.y
  )

  return x !== null && y !== null ? [x, y] : null
}

const isRegressionItem = (item, index) => {
  const text = `${item?.name || ''} ${item?.yAxisField || ''}`
  return index > 0 || /line|regression|linear|回归|线/i.test(text)
}

const pointChartItem = computed(() =>
    activeChartItems.value.find((item, index) => Array.isArray(item?.data) && item.data.length && !isRegressionItem(item, index)) ||
    activeChartItems.value.find(item => Array.isArray(item?.data) && item.data.length) ||
    null
)

const regressionChartItems = computed(() =>
    activeChartItems.value.filter((item, index) => Array.isArray(item?.data) && item.data.length && isRegressionItem(item, index))
)

const primaryPointRows = computed(() => Array.isArray(pointChartItem.value?.data) ? pointChartItem.value.data : [])

const chartPoints = computed(() =>
    primaryPointRows.value
        .map(row => getPointFromRow(row))
        .filter(Boolean)
)

const regression = computed(() => {
  const value = output.value || {}
  const slope = toNumber(value.gradient ?? value.slope)
  const intercept = toNumber(value.intercept)
  const r2 = toNumber(value.rsquared ?? value.r2)

  if (slope === null || intercept === null) return null
  return { slope, intercept, r2: r2 ?? 0 }
})

const chartXRange = computed(() => {
  if (!chartPoints.value.length) return { xMin: 0, xMax: 1 }

  const xs = chartPoints.value.map(([x]) => x)
  const xMax = Math.max(...xs)
  const xPadding = Math.max(xMax * 0.05, 1)

  return {
    xMin: 0,
    xMax: Math.ceil(xMax + xPadding)
  }
})

const calculatedRegressionLinePoints = computed(() => {
  const reg = regression.value
  if (!reg || !chartPoints.value.length) return []

  const { xMin, xMax } = chartXRange.value

  return [
    [xMin, reg.slope * xMin + reg.intercept],
    [xMax, reg.slope * xMax + reg.intercept]
  ]
})

const regressionLinePoints = computed(() => {
  const item = regressionChartItems.value[0]
  if (item?.data?.length) {
    return item.data
        .map(row => getPointFromRow(row))
        .filter(Boolean)
  }

  return calculatedRegressionLinePoints.value
})

const chartTitle = computed(() =>
    output.value?.dynamicOriginalGasInplaceMethodDescription ||
    chartTabs.value[activeChartTab.value]?.label ||
    '定容气藏物质平衡方程-根据计算静压'
)

const chartBounds = computed(() => {
  const points = [...chartPoints.value, ...regressionLinePoints.value]
  if (!points.length) return { xMin: 0, xMax: 1, yMin: 0, yMax: 1 }

  const ys = points.map(([, y]) => y)
  const yMin = Math.min(...ys)
  const yMax = Math.max(...ys)
  const yPadding = Math.max((yMax - yMin) * 0.08, 1)

  return {
    xMin: chartXRange.value.xMin,
    xMax: chartXRange.value.xMax,
    yMin: Math.floor(yMin - yPadding),
    yMax: Math.ceil(yMax + yPadding)
  }
})

const tableRows = computed(() =>
    primaryPointRows.value
        .map((row, index) => {
          const point = getPointFromRow(row)
          if (!point) return null
          return {
            index: index + 1,
            gp: point[0],
            pressure: point[1],
            selected: row?.isDeleted === true ? '否' : '是'
          }
        })
        .filter(Boolean)
)

const getMethodValue = (methods, key) => {
  const value = input.value?.[key]
  if (value === undefined || value === null || value === '') return ''
  if (typeof value === 'number') return methods[value] || String(value)
  return value
}

const getRegressionEquationText = (reg) => {
  if (!reg) return ''
  return `Y = ${formatSci(reg.slope)} * X + ${formatSci(reg.intercept)}\nR² = ${Number(reg.r2 || 0).toFixed(4)}`
}

const getEquationGraphicPosition = () => {
  if (equationGraphicPosition.value) return equationGraphicPosition.value
  const width = chart?.getWidth?.() || 900
  const height = chart?.getHeight?.() || 520
  return [Math.max(width - 330, 80), Math.max(height - 130, 60)]
}

const LEGEND_LINE_ICON = 'path://M0,0 L36,0 L36,3 L0,3 Z'

const createLegendData = (series) =>
    series.map(item => item.type === 'line'
        ? {
          // name: item.name,
          name: '回归线(Mpa)',
          icon: LEGEND_LINE_ICON,
          itemStyle: { color: '#000' }
        }
        : item.name
    )

const legendItems = computed(() => {
  const items = createLegendData(createChartSeries())
  return items.map(item => {
    if (typeof item === 'string') {
      return {
        name: item,
        color: item.includes('回归线') ? '#333' : '#0037b5'
      }
    }

    return {
      name: item.name,
      color: item.itemStyle?.color || '#333'
    }
  }).filter(item => item.name)
})

const legendStyle = computed(() => {
  if (legendPosition.value.x === null || legendPosition.value.y === null) {
    return { top: '42px', right: '22px' }
  }

  return {
    left: `${legendPosition.value.x}px`,
    top: `${legendPosition.value.y}px`
  }
})

function isLegendItemHidden(name) {
  return hiddenLegendNames.value.has(name)
}

function toggleLegendItem(name) {
  const next = new Set(hiddenLegendNames.value)
  if (next.has(name)) {
    next.delete(name)
  } else {
    next.add(name)
  }
  hiddenLegendNames.value = next
  renderChartSoon()
}

function filterLegendSeries(series) {
  return series.filter(item => !isLegendItemHidden(item.name))
}

function onLegendDrag(event) {
  if (!draggingLegend.value || !chartAreaEl.value) return

  const rect = chartAreaEl.value.getBoundingClientRect()
  const x = Math.max(0, Math.min(rect.width - 80, event.clientX - rect.left - legendDragOffset.value.x))
  const y = Math.max(0, Math.min(rect.height - 30, event.clientY - rect.top - legendDragOffset.value.y))
  legendPosition.value = { x, y }
}

function stopLegendDrag() {
  if (!draggingLegend.value) return

  draggingLegend.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onLegendDrag)
  window.removeEventListener('mouseup', stopLegendDrag)
}

function startLegendDrag(event) {
  if (!chartAreaEl.value) return

  event.preventDefault()
  const legendRect = event.currentTarget.getBoundingClientRect()
  const areaRect = chartAreaEl.value.getBoundingClientRect()
  const currentX = legendPosition.value.x ?? legendRect.left - areaRect.left
  const currentY = legendPosition.value.y ?? legendRect.top - areaRect.top
  legendPosition.value = { x: currentX, y: currentY }
  legendDragOffset.value = {
    x: event.clientX - legendRect.left,
    y: event.clientY - legendRect.top
  }
  draggingLegend.value = true
  document.body.style.cursor = 'move'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onLegendDrag)
  window.addEventListener('mouseup', stopLegendDrag)
}

const createChartSeries = () => {
  const explicitRegressionSeries = regressionChartItems.value.map((item, index) => ({
    // name: item.name || `回归线 ${index + 1}`,
    name: '回归线(Mpa)',
    type: 'line',
    data: item.data.map(row => getPointFromRow(row)).filter(Boolean),
    symbol: 'none',
    itemStyle: { color: '#000' },
    lineStyle: { color: index === 0 ? '#333' : '#666', width: 2 },
    tooltip: { show: false }
  }))

  const series = [
    {
      // name: pointChartItem.value?.name || '数据点(Mpa)',
      name: '数据点(Mpa)',
      type: 'scatter',
      data: chartPoints.value,
      symbolSize: 11,
      itemStyle: { color: '#0037b5', opacity: 0.85 },
      tooltip: { show: true }
    },
    {
      name: '回归线(Mpa)',
      type: 'line',
      data: regressionLinePoints.value,
      symbol: 'none',
      itemStyle: { color: '#000' },
      lineStyle: { color: '#333', width: 2 },
      tooltip: { show: false }
    }
  ]

  if (explicitRegressionSeries.length) {
    series.splice(1, 1, ...explicitRegressionSeries)
  }

  return series
      .filter((series, index, list) =>
          Array.isArray(series.data) &&
          series.data.length &&
          list.findIndex(item => item.name === series.name) === index
      )
}

function renderChart() {
  if (!chart) return

  const reg = regression.value
  const bounds = chartBounds.value
  const series = filterLegendSeries(createChartSeries())

  chart.clear()
  chart.setOption({
    animation: false,
    title: {
      text: chartTitle.value,
      left: 'center',
      top: 12,
      textStyle: { color: '#333', fontSize: 16, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'item',
      formatter: params => {
        const value = Array.isArray(params.value) ? params.value : params.value?.value
        if (!Array.isArray(value)) return params.seriesName
        return `${params.marker}${params.seriesName}<br/>Gp: ${Number(value[0]).toFixed(3)}<br/>Pp: ${Number(value[1]).toFixed(3)} MPa`
      }
    },
    legend: { show: false },
    grid: { left: 70, right: 28, top: 58, bottom: 58, containLabel: false },
    xAxis: {
      type: 'value',
      name: 'Gp(10⁸m³)',
      nameLocation: 'middle',
      nameGap: 34,
      min: bounds.xMin,
      max: bounds.xMax,
      interval: 1,
      axisLine: { lineStyle: { color: '#444' } },
      axisTick: { show: true },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } },
      nameTextStyle: { color: '#333', fontSize: 14 }
    },
    yAxis: {
      type: 'value',
      name: 'Pp(MPa)',
      nameLocation: 'middle',
      nameGap: 48,
      min: bounds.yMin,
      max: bounds.yMax,
      interval: 1,
      axisLine: { lineStyle: { color: '#444' } },
      axisTick: { show: true },
      minorTick: { show: true, splitNumber: 5 },
      splitLine: { show: true, lineStyle: { color: '#dfe7f2' } },
      minorSplitLine: { show: true, lineStyle: { color: '#f0f4fa' } },
      nameTextStyle: { color: '#333', fontSize: 14 }
    },
    graphic: reg ? [
      {
        id: 'regression-equation',
        type: 'group',
        z: 1000,
        zlevel: 10,
        draggable: true,
        cursor: 'move',
        position: getEquationGraphicPosition(),
        ondrag: function () {
          equationGraphicPosition.value = [
            Number.isFinite(this.x) ? this.x : this.position?.[0],
            Number.isFinite(this.y) ? this.y : this.position?.[1]
          ]
        },
        children: [
          {
            type: 'rect',
            z: 1000,
            zlevel: 10,
            shape: { x: 0, y: 0, width: 260, height: 58, r: 3 },
            style: {
              fill: 'rgba(255,255,255,0.9)',
              stroke: '#dcdfe6',
              lineWidth: 1
            }
          },
          {
            type: 'text',
            z: 1001,
            zlevel: 10,
            left: 10,
            top: 8,
            style: {
              text: getRegressionEquationText(reg),
              fill: '#333',
              font: '14px Arial',
              lineHeight: 22
            }
          }
        ]
      }
    ] : [],
    series
  }, true)
}

function renderChartSoon() {
  nextTick(() => {
    requestAnimationFrame(() => {
      chart?.resize()
      renderChart()
    })
  })
}

function toggleParamsPanel() {
  paramsCollapsed.value = !paramsCollapsed.value
  renderChartSoon()
}

function onParamsPanelResize(event) {
  if (!resizingParamsPanel.value) return

  const left = paramsPanelEl.value?.getBoundingClientRect().left || 0
  paramsPanelWidth.value = Math.max(238, Math.min(520, event.clientX - left))

  chart?.resize()
}

function stopParamsPanelResize() {
  if (!resizingParamsPanel.value) return

  resizingParamsPanel.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''

  window.removeEventListener('mousemove', onParamsPanelResize)
  window.removeEventListener('mouseup', stopParamsPanelResize)

  renderChartSoon()
}

function startParamsPanelResize(event) {
  if (paramsCollapsed.value) return

  event.preventDefault()

  resizingParamsPanel.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'

  window.addEventListener('mousemove', onParamsPanelResize)
  window.addEventListener('mouseup', stopParamsPanelResize)
}

async function fetchData() {
  const requestId = ++requestSeq
  const wellName = currentWellName.value
  resultData.value = null
  activeChartTab.value = 0
  activeContentTab.value = 'chart'
  equationGraphicPosition.value = null
  await nextTick()
  renderChart()

  if (!wellName || !props.projectId || !props.gasReservoirId) return

  loading.value = true
  try {
    let nextResultData = null
    let lastError = null
    for (let attempt = 0; attempt < 10; attempt++) {
      if (requestId !== requestSeq) return

      try {
        const pressureRes = await materialBalanceApi.getAverageFormationPressure(
            props.projectId,
            props.gasReservoirId,
            wellName,
            { silentError: true }
        )
        const averageRows = getAverageFormationPressureRows(pressureRes.data)
            .filter(isMaterialBalanceRow)
        const resultRequests = averageRows
            .map((row, index) => ({
              row,
              index,
              id: getMaterialBalanceResultId(row)
            }))
            .filter(item => item.id !== null && item.id !== undefined && item.id !== '')

        if (!resultRequests.length) {
          const dynamicOriginalGasInPlaceId = getDynamicOriginalGasInPlaceIdFromNode()

          if (dynamicOriginalGasInPlaceId === null || dynamicOriginalGasInPlaceId === undefined || dynamicOriginalGasInPlaceId === '') {
            throw new Error('averageFormationPressure 接口未返回可用的 DynamicOriginalGasInPlaceId')
          }

          const fallbackRes = await materialBalanceApi.getResult(
              props.projectId,
              props.gasReservoirId,
              dynamicOriginalGasInPlaceId,
              null,
              null,
              { silentError: true }
          )
          nextResultData = fallbackRes.data
          break
        }

        const resultResponses = await Promise.all(
            resultRequests.map(item =>
                materialBalanceApi.getResult(
                    props.projectId,
                    props.gasReservoirId,
                    item.id,
                    null,
                    null,
                    { silentError: true }
                ).then(res => ({ ...item, result: res.data }))
            )
        )

        const firstResult = resultResponses[0]?.result || {}
        nextResultData = {
          ...firstResult,
          input: firstResult.input || resultResponses.find(item => item.result?.input)?.result?.input || {},
          resultFields: firstResult.resultFields || resultResponses.find(item => item.result?.resultFields)?.result?.resultFields || [],
          outputs: resultResponses.map(item => mergeResultWithAverageRow(item.result, item.row, item.index))
        }
        break
      } catch (error) {
        lastError = error
        if (attempt === 9) break
        await sleep(1000)
      }
    }

    if (!nextResultData) throw lastError || new Error('物质平衡结果加载失败')
    if (requestId !== requestSeq) return

    resultData.value = nextResultData
    activeChartTab.value = 0
    activeContentTab.value = 'chart'
    activePanelTab.value = 'input'
    await nextTick()
    renderChart()
    // 物质平衡是批量计算，但本次页面只刷新当前井对应的左侧节点。
    emit('refresh-tree', wellName)
  } catch (error) {
    if (requestId !== requestSeq) return
    console.error('[MaterialBalanceContent] load failed', error)
    resultData.value = null
    await nextTick()
    renderChart()
  } finally {
    if (requestId === requestSeq) loading.value = false
  }
}

function handleRecalculate() {
  emit('recalculate', {
    gasReservoirType: reservoirType.value,
    waterGasRatioLimit: enableWaterGasRatioLimit.value ? Number(waterGasRatioLimitValue.value) : -1
  })
}

watch(() => [
  props.node?.id,
  currentWellName.value,
  props.projectId,
  props.gasReservoirId
], fetchData, { immediate: true })
watch(activeChartTab, () => nextTick(renderChart))
watch(activeContentTab, (tab) => { if (tab === 'chart') nextTick(renderChart)})
watch([chartPoints, regressionLinePoints], () => nextTick(renderChart), { deep: true })
//监听计算方式
watch(input,(value)=>{
  reservoirType.value=value?.gasReservoirType?'constant':'closed'
  const limit = value?.waterGasRatioLimit
  enableWaterGasRatioLimit.value = Number(limit) > 0
  waterGasRatioLimitValue.value = Number(limit) > 0 ? String(limit) : '0.0602'
})


onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', renderChartSoon)
  renderChartSoon()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', renderChartSoon)
  stopParamsPanelResize()
  stopLegendDrag()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-loading="loading" class="mb-wrap">
    <aside
        ref="paramsPanelEl"
        class="params-panel"
        :class="{
          collapsed: paramsCollapsed,
          resizing: resizingParamsPanel,
          narrow: !paramsCollapsed && paramsPanelWidth < 380
        }"
        :style="{
        width: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`,
        minWidth: paramsCollapsed ? '22px' : `${paramsPanelWidth}px`
      }"
    >
      <div
          v-if="paramsCollapsed"
          class="panel-collapsed-tab"
          @click="toggleParamsPanel"
      >
        参数设置
      </div>

      <template v-if="!paramsCollapsed">
        <div class="panel-head">
          <span>参数设置</span>
          <button
              class="panel-toggle"
              type="button"
              title="收起参数设置"
              @click="toggleParamsPanel"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
              <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
            </svg>
          </button>
        </div>

        <div v-show="activePanelTab === 'input'" class="panel-body">
          <div v-if="!hasDisplayedInputFields" class="empty">暂无接口输入结果</div>
          <template v-for="section in groupedInputSections" :key="section.title">
            <div class="section-title">{{ section.title }}</div>
            <div class="field-grid">
              <div
                  v-for="item in section.fields"
                  v-show="item.value !== undefined && item.value !== null && item.value !== ''"
                  :key="item.key"
                  class="field"
              >
                <label>{{ item.label }}</label>
                <el-select
                    v-if="item.options"
                    size="small"
                    :model-value="item.value"
                    style="width: 100%"
                >
                  <el-option
                      v-for="option in item.options"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                  />
                </el-select>
                <el-input v-else size="small" readonly :model-value="item.value" />
              </div>
            </div>
          </template>

          <!--计算条件-->
          <div class="section-title">计算条件</div>
          <div class="condition-panel">

            <div class="type-row">
              <span class="type-name-label">请选择物质平衡方程类型：</span>
              <el-radio-group v-model="reservoirType">
                <el-radio value="constant">定容气藏物质平衡</el-radio>
                <el-radio value="closed">封闭气藏物质平衡方程</el-radio>
              </el-radio-group>
            </div>
            <div class="condition-row condition-limit-row">
              <div class="condition-limit-label" :class="{ 'condition-muted': !enableWaterGasRatioLimit }">
                <el-checkbox v-model="enableWaterGasRatioLimit" class="condition-checkbox" />
                <span class="condition-text">生产水气比上限(m³/10⁴m³):</span>
              </div>
              <div class="condition-actions">
                <el-input
                    v-model="waterGasRatioLimitValue"
                    size="small"
                    :disabled="!enableWaterGasRatioLimit"
                />
                <el-button size="small" class="condition-recalculate" @click="handleRecalculate">
                  重新计算
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div class="field-grid">
            <div v-for="item in displayedOutputFields" :key="item.key" class="field">
              <label>{{ item.label }}</label>
              <el-input size="small" readonly :model-value="item.value" />
            </div>
          </div>
        </div>

        <div class="panel-tabs">
          <button :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>

        <div
            class="params-resizer"
            @mousedown="startParamsPanelResize"
        />
      </template>
    </aside>

    <!-- 右侧图表区域 -->
    <main ref="chartAreaEl" class="chart-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="chartTabTitle">
          <span class="dynamic-result-tab-text">{{ chartTabTitle }}</span>
        </button>
      </div>

      <div class="result-tabs">
        <button
            v-for="(tab, index) in chartTabs"
            :key="tab.label"
            :class="{ active: activeChartTab === index }"
            @click="activeChartTab = index"
        >
          {{ tab.label }}
        </button>
      </div>
      <div v-show="activeContentTab === 'chart'" ref="chartEl" class="chart"></div>
      <div
          v-if="activeContentTab === 'chart' && legendItems.length"
          class="floating-chart-legend"
          :class="{ dragging: draggingLegend }"
          :style="legendStyle"
          @mousedown="startLegendDrag"
      >
        <div
            v-for="item in legendItems"
            :key="item.name"
            class="floating-legend-item"
            :class="{ hidden: isLegendItemHidden(item.name) }"
            title="点击显示/隐藏"
            @mousedown.stop
            @click.stop="toggleLegendItem(item.name)"
        >
          <span :class="item.name.includes('回归线') ? 'legend-line' : 'legend-dot'" :style="{backgroundColor: isLegendItemHidden(item.name) ? 'transparent' : item.color,
      borderColor: item.color}"
          ></span>
          <span>{{ item.name }}</span>
        </div>
      </div>
      <div v-if="activeContentTab === 'table'" class="data-list-panel">
        <el-table :data="tableRows" size="small" height="100%" border stripe>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="gp" label="Gp(10⁸m³)" min-width="150" />
          <el-table-column prop="pressure" label="Pp(MPa)" min-width="130" />
          <el-table-column prop="selected" label="回归点" min-width="90" />
        </el-table>
      </div>

      <div class="chart-tabs">
        <button type="button" class="chart-tab" :class="{ active: activeContentTab === 'table' }" @click="activeContentTab = 'table'">
          数据列表
        </button>
        <button type="button" class="chart-tab" :class="{ active: activeContentTab === 'chart' }" @click="activeContentTab = 'chart'">
          结果分析图
        </button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.mb-wrap {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  width: 238px;
  min-width: 238px;
  display: flex;
  flex-direction: column;
  position: relative;
  border-right: 1px solid #e0e0e0;
  overflow: hidden;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
  }

  &.resizing {
    transition: none;
  }
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px 6px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  font-size: 13px;
  color: #333;
}

.panel-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 2px;

  &:hover {
    background: #eef4ff;
  }
}

.panel-collapsed-tab {
  width: 22px;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-left: 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);

  &:hover {
    background: #eef4ff;
    color: #1f6fd6;
  }
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.params-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 4;

  &:hover {
    background: rgba(64, 132, 217, 0.18);
  }
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.condition-panel {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  padding: 1px 0 9px;
  color: #303133;

  :deep(.el-checkbox) {
    height: 24px;
    margin-right: 0;
  }

  :deep(.el-checkbox__label) {
    font-size: 13px;
    color: #303133;
  }

  :deep(.el-checkbox__inner) {
    border-color: #c0c4cc;
  }

  :deep(.el-checkbox__input.is-checked .el-checkbox__inner),
  :deep(.el-checkbox__input.is-indeterminate .el-checkbox__inner) {
    background-color: #303133;
    border-color: #303133;
  }

  :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
    color: #303133;
  }
}

.type-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  width: 100%;

  .type-name-label {
    color: #303133;
    font-size: 13px;
    line-height: 24px;
  }

  :deep(.el-radio-group) {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
  }

  :deep(.el-radio) {
    height: 24px;
    margin-right: 0;
  }

  :deep(.el-radio__label) {
    color: #303133;
    font-size: 13px;
  }
}

.condition-row {
  display: flex;
  align-items: center;
  min-height: 24px;
}

.condition-limit-row {
  width: 100%;
  gap: 8px;
}

.condition-limit-label,
.condition-actions {
  display: flex;
  align-items: center;
}

.condition-limit-label {
  gap: 8px;
  flex-shrink: 0;

  .condition-text {
    font-size: 13px;
    color: #303133;
    white-space: nowrap;
  }
}

.condition-actions {
  gap: 4px;
  min-width: 0;

  .el-input {
    width: 135px;
  }
}

.condition-muted {
  :deep(.el-checkbox__label),
  .condition-text {
    color: #a8abb2;
  }
}

.condition-recalculate {
  flex-shrink: 0;
}

.params-panel.narrow {
  .condition-limit-row {
    flex-wrap: wrap;
    row-gap: 5px;
  }

  .condition-actions {
    width: 100%;
    padding-left: 22px;

    .el-input {
      flex: 1;
      width: auto;
      min-width: 0;
    }
  }
}

.section-title {
  font-size: 14px;
  color: #333;
  margin: 10px 0 7px;
  font-weight: 600;
}

.field {
  margin-bottom: 9px;

  label {
    display: block;
    margin-bottom: 3px;
    color: #555;
    font-size: 12px;
  }
}

.empty {
  color: #888;
  font-size: 13px;
  line-height: 1.5;
  padding: 4px 0 10px;
}

.panel-tabs {
  height: 36px;
  display: flex;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;

  button {
    flex: 1;
    border: 0;
    border-right: 1px solid #e0e0e0;
    background: #fff;
    color: #333;
    font-size: 14px;
    cursor: pointer;

    &:last-child {
      border-right: 0;
    }

    &.active {
      background: #f4d000;
      color: #111;
      font-weight: 600;
    }
  }
}

.chart-area {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  overflow: hidden;
}

.dynamic-result-tabs {
  height: 34px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  overflow-x: auto;
  overflow-y: hidden;
}

.dynamic-result-tab {
  height: 34px;
  max-width: 340px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: #409eff;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  padding: 0 12px;
  cursor: default;
  white-space: nowrap;

  &.active {
    border-bottom-color: #409eff;
    background: #fff;
  }
}

.dynamic-result-tab-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-tabs {
  height: 34px;
  display: flex;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  flex-shrink: 0;
  overflow-x: auto;
  overflow-y: hidden;

  button {
    border: 0;
    border-right: 1px solid #e4e7ed;
    border-bottom: 2px solid transparent;
    background: transparent;
    padding: 0 16px;
    color: #555;
    cursor: pointer;
    flex-shrink: 0;
    white-space: nowrap;

    &:hover {
      color: #409eff;
    }

    &.active {
      background: #fff;
      color: #409eff;
      font-weight: 600;
      border-bottom-color: #409eff;
    }
  }
}

.chart-tabs {
  display: flex;
  height: 34px;
  border-top: 1px solid #e4e7ed;
  flex-shrink: 0;
  background: #fafafa;
}

.chart-tab {
  border: 0;
  border-right: 1px solid #e4e7ed;
  background: transparent;
  padding: 0 16px;
  color: #555;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  white-space: nowrap;

  &:hover {
    color: #409eff;
  }

  &.active {
    color: #409eff;
    border-bottom-color: #409eff;
    background: #fff;
    font-weight: 600;
  }
}

.chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.floating-chart-legend {
  position: absolute;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-width: 280px;
  padding: 7px 10px;
  border: 1px solid #eeeeee;
  background: rgba(255, 255, 255, 0.9);
  color: #333;
  font-size: 12px;
  line-height: 1.2;
  cursor: move;
  user-select: none;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);

  &.dragging {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.14);
  }
}

.floating-legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  cursor: pointer;

  &.hidden {
    color: #999;
    opacity: 0.55;
  }
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1px solid transparent;
}

.data-list-panel {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow: hidden;
  background: #fff;
}

// 回归线在图例中显示为黑色横线，而不是数据点圆圈。
.legend-line {
  width: 18px;
  height: 2px;
  flex-shrink: 0;
  border: none;
}
</style>
