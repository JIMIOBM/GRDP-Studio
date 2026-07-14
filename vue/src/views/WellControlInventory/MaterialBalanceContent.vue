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

const getMaterialBalanceTabLabel = (row, index) => {
  const description = row?.dynamicOriginalGasInplaceMethodDescription || row?.dynamicOriginalGasInPlaceMethodDescription || ''
  if (/实测静压/.test(description)) return '根据实测静压'
  if (/计算静压/.test(description)) return '根据计算静压'
  return description || `图表 ${index + 1}`
}

const mergeResultWithAverageRow = (result, averageRow, index) => {
  const output = {
    ...(result?.output || result?.result || {}),
    ...averageRow
  }
  const label = getMaterialBalanceTabLabel(output, index)

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

const getFieldLabel = (field) => {
  const label = field?.name_cn || field?.name || ''
  return field?.unit_label ? `${label}(${field.unit_label})` : label
}

const formatFieldValue = (value, field = {}) => {
  if (value === undefined || value === null || value === '') return ''
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  if (field.isScientificNotation) return formatSci(number)
  if (typeof field.displayDecimal === 'number') return number.toFixed(field.displayDecimal)
  return value
}

const displayedOutputFields = computed(() => {
  const value = output.value || {}
  const fields = Array.isArray(resultData.value?.resultFields) ? resultData.value.resultFields : []
  const rows = fields
      .filter(field => field?.name && value[field.name] !== undefined && value[field.name] !== null && value[field.name] !== '')
      .map(field => ({
        key: field.name,
        label: getFieldLabel(field),
        value: formatFieldValue(value[field.name], field)
      }))

  if (rows.length) return rows

  return Object.entries(value)
      .filter(([, fieldValue]) => fieldValue !== undefined && fieldValue !== null && fieldValue !== '')
      .map(([key, fieldValue]) => ({ key, label: key, value: fieldValue }))
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

watch(() => [
  props.node?.id,
  currentWellName.value,
  props.projectId,
  props.gasReservoirId
], fetchData, { immediate: true })
watch(activeChartTab, () => nextTick(renderChart))
watch([chartPoints, regressionLinePoints], () => nextTick(renderChart), { deep: true })

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
        :class="{ collapsed: paramsCollapsed, resizing: resizingParamsPanel }"
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
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div v-if="!displayedOutputFields.length" class="empty">暂无接口输出结果</div>
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

    <main ref="chartAreaEl" class="chart-area">
      <div class="chart-tabs">
        <button
            v-for="(tab, index) in chartTabs"
            :key="tab.label"
            :class="{ active: activeChartTab === index }"
            @click="activeChartTab = index"
        >
          {{ tab.label }}
        </button>
      </div>
      <div ref="chartEl" class="chart"></div>
      <div
          v-if="legendItems.length"
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
      <div class="table-wrap">
        <el-table :data="tableRows" size="small" height="150" border>
          <el-table-column prop="index" label="序号" width="70" />
          <el-table-column prop="gp" label="Gp(10⁸m³)" min-width="150" />
          <el-table-column prop="pressure" label="Pp(MPa)" min-width="130" />
          <el-table-column prop="selected" label="回归点" min-width="90" />
        </el-table>
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

.chart-tabs {
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
    background: transparent;
    padding: 0 16px;
    color: #555;
    cursor: pointer;
    flex-shrink: 0;

    &.active {
      background: #fff;
      color: #409eff;
      font-weight: 600;
      border-bottom: 2px solid #409eff;
    }
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

.table-wrap {
  height: 170px;
  padding: 10px;
  border-top: 1px solid #eeeeee;
  flex-shrink: 0;
}

// 回归线在图例中显示为黑色横线，而不是数据点圆圈。
.legend-line {
  width: 18px;
  height: 2px;
  flex-shrink: 0;
  border: none;
}
</style>
