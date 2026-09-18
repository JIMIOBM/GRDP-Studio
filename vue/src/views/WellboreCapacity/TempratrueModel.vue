<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { wellboreTemperatureApi } from '@/api/wellboreTemperature'
import { loadTemperatureSources } from '@/api/temperatureSources'
import request from '@/utils/request'
import { selectedPvtProperties } from './pvtSource'
import {
  inferWellheadChannel,
  normalizeProductionDate,
  productionRecords as buildProductionRecords,
  productionValues
} from '@/utils/temperatureSources'
import {
  applyWellboreBoundaryDefaults,
  boundaryValuesForTemperature,
  getWellboreBoundaryState,
  setWellboreBoundaryValue,
  wellboreBoundaryLabels
} from '@/utils/wellboreBoundaryState'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const defaults = {
  tempModel: 'alves',
  boundaryPosition: 'wellhead',
  depth: 3100,
  step: 50,
  idTubing: 62,
  tGrad: 3,
  angle: 0,
  gammaG: null,
  rhoL: null,
  muL: null,
  pvtId: null,
  pvtSnapshot: null,
  roughness: 0.016,
  tSurf: 20,
  uTo: 8,
  wallMm: 6.35,
  muJt: 9,
  cpGas: 2200,
  formationK: 2.5,
  formationRhoCp: 2.3,
  referencePressure: 3.8,
  tWh: 30,
  qGas: 2.5,
  qLiq: 2
}
const form = reactive({ ...defaults })
const result = ref(null)
const calculatedInput = ref(null)
const payload = () => ({
  ...ctx(),
  ...form,
  ...boundaryValuesForTemperature(boundary),
  pvtId: boundary.values.pvtId,
  productionRecordKey: boundary.values.productionRecordKey,
  productionDate: boundary.values.productionDate,
  productionChannel: boundary.values.boundaryPosition === 'wellhead'
    ? boundary.values.wellheadChannel
    : 'manual-bottomhole'
})
const error = ref('')
const busy = ref(false)
const sourceLoading = ref(false)
const pvtLoading = ref(false)
const availableProductionRecords = ref([])
const productionFields = ref([])
const availablePvtRecords = ref([])
const chartEl = ref(null)
const chartAreaEl = ref(null)
const legendEl = ref(null)
const legendPosition = ref(null)
const draggingLegend = ref(false)
const legendItems = [
  { name: '流体温度', color: '#5470c6' },
  { name: '地层温度', color: '#91cc75' }
]
const legendSelected = reactive({ 流体温度: true, 地层温度: true })
const legendStyle = computed(() => legendPosition.value
  ? { left: legendPosition.value.x + 'px', top: legendPosition.value.y + 'px' }
  : { right: '38px', top: '52px' })
let legendDragOffset = { x: 0, y: 0 }

function clampLegendPosition (x, y) {
  const area = chartAreaEl.value
  const legend = legendEl.value
  if (!area || !legend) return
  legendPosition.value = {
    x: Math.max(0, Math.min(Math.max(0, area.clientWidth - legend.offsetWidth), x)),
    y: Math.max(0, Math.min(Math.max(0, area.clientHeight - legend.offsetHeight), y))
  }
}
function moveLegend (event) {
  if (!draggingLegend.value || !chartAreaEl.value) return
  const area = chartAreaEl.value.getBoundingClientRect()
  clampLegendPosition(event.clientX - area.left - legendDragOffset.x, event.clientY - area.top - legendDragOffset.y)
}
function stopLegendDrag () {
  draggingLegend.value = false
  window.removeEventListener('pointermove', moveLegend)
  window.removeEventListener('pointerup', stopLegendDrag)
  window.removeEventListener('pointercancel', stopLegendDrag)
}
function startLegendDrag (event) {
  if (event.button !== 0 || !legendEl.value) return
  event.preventDefault()
  const rect = legendEl.value.getBoundingClientRect()
  legendDragOffset = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  draggingLegend.value = true
  window.addEventListener('pointermove', moveLegend)
  window.addEventListener('pointerup', stopLegendDrag)
  window.addEventListener('pointercancel', stopLegendDrag)
}
function toggleLegend (name) {
  legendSelected[name] = !legendSelected[name]
  chart?.dispatchAction({ type: legendSelected[name] ? 'legendSelect' : 'legendUnSelect', name })
}
const panel = ref(null)
const panelWidth = ref(238)
const paramsCollapsed = ref(false)
const activeParamTab = ref('input')
const displayNumber = (value, digits = 2) => value == null || !Number.isFinite(Number(value)) ? '—' : Number(value).toFixed(digits)
const validBoundaryNumber = value => value !== null && value !== '' && Number.isFinite(Number(value))
const resultBoundaryPosition = computed(() =>
  result.value?.calculationPosition || boundary.values.boundaryPosition
)
const outputFields = computed(() => [
  ['温度模型', result.value
    ? (result.value.tempModel === 'linear'
        ? `${resultBoundaryPosition.value === 'wellhead' ? '井口' : '井底'}锚定线性模型`
        : 'Alves分段能量平衡')
    : '—'],
  [resultBoundaryPosition.value === 'wellhead' ? '井底流体温度（℃）' : '反算井口温度（℃）',
    displayNumber(resultBoundaryPosition.value === 'wellhead'
      ? result.value?.inferredBottomTemperature
      : result.value?.predictedWellheadTemperature)],
  ...(result.value?.tempModel === 'alves' ? [
    ['热松弛距离（m）', displayNumber(result.value?.thermal?.relaxationDistance, 1)],
    ['无量纲时间', displayNumber(result.value?.thermal?.dimensionlessTime, 3)]
  ] : [])
])
watch(paramsCollapsed, async () => {
  await nextTick()
  chart?.resize()
})

let chart
let observer
let disposed = false
let loadSequence = 0
let pvtLoadSequence = 0
let oldCursor = ''
let oldSelect = ''

const ctx = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: String(props.node?.wellName ?? '').trim()
})
const boundary = getWellboreBoundaryState(ctx())
const boundaryLabels = computed(() => wellboreBoundaryLabels(boundary.values.boundaryPosition))
const sharedField = key => ({
  referencePressure: 'pressure',
  tWh: 'temperature',
  qGas: 'qGas',
  qLiq: 'qLiq'
})[key]
const fieldValue = key => sharedField(key) ? boundary.values[sharedField(key)] : form[key]
const setFieldValue = (key, value) => {
  const field = sharedField(key)
  if (field) setWellboreBoundaryValue(boundary, field, value)
  else form[key] = value
}
const selectedProduction = computed(() => availableProductionRecords.value.find(
  item => item.key === boundary.values.productionRecordKey
))
// 日历中仅开放当前井实际存在注采记录的日期。
const availableProductionDates = computed(() => new Set(
  availableProductionRecords.value.map(item => item.date)
))
const channelLabel = computed(() => boundary.values.wellheadChannel === 'casing' ? '套管' : '油管')
// 下拉选项直接对应当前井 project_well_pvt 主记录，不隐藏参数尚不完整的记录。
const pvtOptions = computed(() => availablePvtRecords.value.map(record => ({
  value: Number(record.pvtId),
  label: record.pvtName || `PVT性质${record.pvtNo}`
})))

const isProductionDateDisabled = date => !availableProductionDates.value.has(normalizeProductionDate(date))

// 井口模式同步同一记录、同一通道的温压和气水量；井底模式只同步同日气水量。
function applySelectedProduction (explicit = false, selected = selectedProduction.value) {
  if (!selected) return
  const position = boundary.values.boundaryPosition
  const values = productionValues(
    selected.row,
    position,
    productionFields.value,
    boundary.values.wellheadChannel
  )
  const defaultsFromRow = {
    qGas: values.qGas,
    qLiq: values.qLiq ?? 0
  }
  if (position === 'wellhead') {
    defaultsFromRow.pressure = values.fWh
    defaultsFromRow.temperature = values.tWh
  }
  if (explicit) {
    for (const [field, value] of Object.entries(defaultsFromRow)) {
      setWellboreBoundaryValue(boundary, field, value)
    }
  } else {
    applyWellboreBoundaryDefaults(boundary, defaultsFromRow)
  }
}

function selectProductionRecord (key) {
  const selected = availableProductionRecords.value.find(item => item.key === key)
  if (!selected) return
  setWellboreBoundaryValue(boundary, 'productionRecordKey', selected.key)
  setWellboreBoundaryValue(boundary, 'productionDate', selected.date)
  applySelectedProduction(true, selected)
}

function selectProductionDate (value) {
  const date = normalizeProductionDate(value)
  const selected = availableProductionRecords.value.find(item => item.date === date)
  if (selected) selectProductionRecord(selected.key)
}

// 使用可写计算属性承接日期组件的即时更新，点击日期后立即加载对应生产记录。
const productionDateModel = computed({
  get: () => boundary.values.productionDate,
  set: selectProductionDate
})

async function selectPvt (pvtId) {
  const numericPvtId = Number(pvtId)
  if (!Number.isFinite(numericPvtId) || numericPvtId <= 0) return false
  setWellboreBoundaryValue(boundary, 'pvtId', numericPvtId)
  // 先清空上一条PVT物性，防止异步请求期间混用新ID和旧参数。
  Object.assign(form, { pvtId: numericPvtId, pvtSnapshot: null, gammaG: null, rhoL: null, muL: null })
  const sequence = ++pvtLoadSequence
  pvtLoading.value = true
  error.value = ''
  try {
    const properties = await selectedPvtProperties(
      request,
      ctx(),
      numericPvtId,
      boundary.values.pressure,
      boundary.values.temperature
    )
    if (disposed || sequence !== pvtLoadSequence || Number(boundary.values.pvtId) !== numericPvtId) return false
    Object.assign(form, properties)
    return true
  } catch (pvtError) {
    if (!disposed && sequence === pvtLoadSequence) {
      error.value = pvtError?.msg || pvtError?.response?.data?.msg || pvtError?.message || 'PVT性质加载失败'
    }
    return false
  } finally {
    if (sequence === pvtLoadSequence) pvtLoading.value = false
  }
}

function selectWellheadChannel (channel) {
  setWellboreBoundaryValue(boundary, 'wellheadChannel', channel)
  if (boundary.values.boundaryPosition === 'wellhead') applySelectedProduction(true)
}

function selectBoundaryPosition (position) {
  if (position === boundary.values.boundaryPosition) return
  setWellboreBoundaryValue(boundary, 'boundaryPosition', position)
  if (position === 'wellhead') {
    applySelectedProduction(true)
  } else {
    // 当前没有带日期的井底温度，切到井底边界后由用户手动填写井底温压。
    setWellboreBoundaryValue(boundary, 'pressure', null)
    setWellboreBoundaryValue(boundary, 'temperature', null)
    applySelectedProduction(true)
  }
}
const data = value => value?.data ?? value

const normalizeStoredResult = detail => {
  const stored = data(detail)
  if (!stored?.record || !Array.isArray(stored.profile)) return stored

  const record = stored.record
  return {
    record,
    tempModel: String(record.modelCode || 'alves').toLowerCase(),
    depth: stored.profile.map(point => point.depthM),
    temp: stored.profile.map(point => point.fluidTemperatureC),
    tempFormation: stored.profile.map(point => point.formationTemperatureC),
    inferredBottomTemperature: record.bottomFluidTemperatureC,
    predictedWellheadTemperature: record.predictedWellheadTemperatureC,
    calculationPosition: record.boundaryPosition,
    gravityGradient: record.gravityGradient,
    thermal: {
      relaxationDistance: record.relaxationDistanceM,
      dimensionlessTime: record.dimensionlessTime,
      timeFunction: record.timeFunction,
      mixtureHeatCapacity: record.mixtureHeatCapacityJKgk
    }
  }
}

async function loadLatestResult (context) {
  const history = data(await wellboreTemperatureApi.list(
    context.projectId,
    context.gasReservoirId,
    context.wellName
  )) || []
  const latest = history[0]
  if (!latest?.id) return

  result.value = normalizeStoredResult(await wellboreTemperatureApi.detail(
    latest.id,
    context.projectId,
    context.gasReservoirId,
    context.wellName
  ))
  await draw()
}
const groups = computed(() => [
  {
    title: '井身结构及物性',
    fields: [
      ['depth', '测井深度 (m)', 0.001, 100000],
      ['idTubing', '油管内径 (mm)', 0.001, 2000],
      ['tGrad', '地温梯度 (℃/100m)', 0, 100],
      ['angle', '井斜角 (°)', 0, 90],
      ['step', '计算步长 (m)', 0.001, 100000],
      ['gammaG', '气体相对密度', 0.001, 10],
      ['rhoL', '液体密度 (kg/m³)', 0.001, 10000],
      ['muL', '液体黏度 (mPa·s)', 0.000001, 100000],
      ['roughness', '管内壁粗糙度 (mm)', 0, 100]
    ]
  },
  {
    title: '温度模型参数',
    fields: [
      ['tSurf', '地表温度 (℃)', -273.14, 1000],
      ['uTo', '总传热系数 (W/m²·K)', 0.001, 100000],
      ['wallMm', '油管壁厚 (mm)', 0, 500],
      ['muJt', '焦耳–汤姆逊系数 (K/MPa)', -1000, 1000],
      ['cpGas', '气体定压比热 (J/kg·K)', 0.001, 100000],
      ['formationK', '地层导热系数 (W/m·K)', 0.001, 1000],
      ['formationRhoCp', '地层体积热容 (MJ/m³·K)', 0.001, 1000]
    ]
  },
  {
    title: '生产数据',
    fields: [
      [
        'referencePressure',
        boundaryLabels.value.pressure,
        0.000001,
        1000
      ],
      [
        'tWh',
        boundaryLabels.value.temperature,
        -273.14,
        1000
      ],
      ['qGas', '日产气量 (×10⁴ m³/d)', 0, 1000000],
      ['qLiq', '日产水量 (m³/d)', 0, 1000000]
    ]
  }
])

async function refresh () {
  const sequence = ++loadSequence
  const context = ctx()
  if (!context.wellName) {
    error.value = '请先在左侧目录选择井'
    return
  }
  sourceLoading.value = true
  error.value = ''
  result.value = null
  calculatedInput.value = null
  chart?.clear()
  try {
    const source = await loadTemperatureSources(
      context.projectId,
      context.gasReservoirId,
      context.wellName
    )
    if (disposed || sequence !== loadSequence) return
    availableProductionRecords.value = buildProductionRecords(source.productionRows)
    productionFields.value = source.productionFields
    availablePvtRecords.value = source.pvtRecords
    if (!availableProductionRecords.value.length) source.errors.push('注采数据缺少可选择的生产日期')
    const selectedPvt = availablePvtRecords.value.find(
      record => Number(record.pvtId) === Number(boundary.values.pvtId)
    ) || availablePvtRecords.value[0]
    if (selectedPvt) {
      boundary.values.pvtId = Number(selectedPvt.pvtId)
    } else {
      boundary.values.pvtId = null
      source.errors.push('当前井暂无可选择的PVT性质')
    }
    if (!boundary.modified.has('wellheadChannel')) {
      // 首次进入时依据“其他数据”中的生产通道给出默认值，用户手动选择后不再覆盖。
      boundary.values.wellheadChannel = inferWellheadChannel(source.flowPath)
    }
    const selected = availableProductionRecords.value.find(
      item => item.key === boundary.values.productionRecordKey
        || item.date === boundary.values.productionDate
    ) || availableProductionRecords.value[0]
    if (selected) {
      boundary.values.productionRecordKey = selected.key
      boundary.values.productionDate = selected.date
      applySelectedProduction(false, selected)
    }
    const initial = {
      ...defaults,
      ...source.input
    }
    if (selectedPvt) {
      try {
        const properties = await selectedPvtProperties(
          request,
          context,
          selectedPvt.pvtId,
          boundary.values.pressure,
          boundary.values.temperature
        )
        if (disposed || sequence !== loadSequence) return
        Object.assign(initial, properties)
      } catch (pvtError) {
        initial.gammaG = initial.rhoL = initial.muL = null
        source.errors.push(`PVT物性读取失败：${pvtError?.msg || pvtError?.message || '接口异常'}`)
      }
    }

    for (const key of Object.keys(defaults)) {
      if (['gammaG', 'rhoL', 'muL', 'pvtId', 'pvtSnapshot'].includes(key)) continue
      if (
        initial[key] == null
        || (typeof defaults[key] === 'number' && !Number.isFinite(Number(initial[key])))
      ) {
        initial[key] = defaults[key]
      }
    }
    if (disposed || sequence !== loadSequence) return
    Object.assign(form, initial)

    try {
      await loadLatestResult(context)
    } catch (historyError) {
      source.errors.push(`温度历史读取失败：${historyError?.msg || historyError?.message || '接口异常'}`)
    }
    if (source.errors.length) {
      error.value = source.errors.join('；')
    }
  } catch (sourceError) {
    if (!disposed && sequence === loadSequence) {
      error.value = sourceError?.msg || sourceError?.message || '源数据加载失败'
    }
  } finally {
    if (sequence === loadSequence) {
      sourceLoading.value = false
    }
  }
}

async function draw () {
  await nextTick()
  if (!result.value || disposed) return
  chart ??= echarts.init(chartEl.value)
  const current = result.value
  chart.setOption({
    animation: false,
    color: ['#5470c6', '#91cc75'],
    title: {
      text: '温度分布曲线',
      left: 'center',
      top: 2,
      textStyle: { color: '#333', fontSize: 14, fontWeight: 600 }
    },
    legend: { show: false, selected: { ...legendSelected } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { axis: 'y' },
      formatter: points => {
        if (!points?.length) return ''
        const depth = Number(points[0].value?.[1])
        const title = Number.isFinite(depth)
          ? `井深 ${depth.toLocaleString('zh-CN', {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2
            })} m`
          : ''
        const values = points.map(point => {
          const temperature = Number(point.value?.[0])
          const temperatureText = Number.isFinite(temperature)
            ? temperature.toFixed(2)
            : '-'
          return `${point.marker}${point.seriesName}　${temperatureText} ℃`
        }).join('<br/>')
        return `${title}<br/>${values}`
      }
    },
    grid: { left: 64, right: 26, top: 40, bottom: 46 },
    xAxis: {
      type: 'value',
      name: '温度 (℃)',
      scale: true,
      min: Math.min(current.temp[0], current.tempFormation[0]),
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
      max: current.depth.at(-1),
      nameLocation: 'middle',
      nameGap: 48,
      axisLine: { show: true, lineStyle: { color: '#888' } },
      axisLabel: { color: '#555', fontSize: 12 },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: [
      {
        name: '流体温度',
        type: 'line',
        showSymbol: false,
        data: current.depth.map((depth, index) => [current.temp[index], depth])
      },
      {
        name: '地层温度',
        type: 'line',
        showSymbol: false,
        lineStyle: { type: 'dashed' },
        data: current.depth.map((depth, index) => [current.tempFormation[index], depth])
      }
    ]
  }, true)
}

async function calculate () {
  error.value = ''
  if (pvtLoading.value) {
    error.value = '正在加载所选PVT性质，请稍候'
    return
  }
  if (!Number.isFinite(Number(boundary.values.pvtId)) || Number(boundary.values.pvtId) <= 0) {
    error.value = '请选择PVT性质'
    return
  }
  if (!validBoundaryNumber(boundary.values.pressure)
    || !validBoundaryNumber(boundary.values.temperature)) {
    error.value = boundary.values.boundaryPosition === 'bottomhole'
      ? '请输入有效的井底压力和井底温度'
      : '所选生产记录缺少当前通道的井口压力或井口温度'
    return
  }
  if (!validBoundaryNumber(boundary.values.qGas)
    || !validBoundaryNumber(boundary.values.qLiq)
    || Number(boundary.values.qGas) + Number(boundary.values.qLiq) <= 0) {
    error.value = '所选生产记录缺少有效的日产气量或日产水量'
    return
  }
  // 以当前边界温压重新评价所选PVT，确保参数栏与本次计算输入完全一致。
  if (!await selectPvt(boundary.values.pvtId)) return
  if (![form.gammaG, form.rhoL, form.muL].every(validBoundaryNumber)) {
    error.value = '所选PVT缺少可用的气体比重、地层水密度或地层水黏度'
    return
  }
  busy.value = true
  try {
    const calculation = payload()
    const currentResult = data(await wellboreTemperatureApi.calculate(calculation))
    await nextTick()
    if (JSON.stringify(calculation) !== JSON.stringify(payload())) return
    result.value = currentResult
    calculatedInput.value = calculation
    await draw()
  } catch (calculateError) {
    error.value = calculateError?.msg || calculateError?.response?.data?.msg || calculateError?.message || '温度计算失败'
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!calculatedInput.value) {
    error.value = '请先计算温度分布，再保存结果'
    return
  }

  error.value = ''
  busy.value = true
  try {
    result.value = normalizeStoredResult(await wellboreTemperatureApi.save({
      calculation: calculatedInput.value
    }))
    calculatedInput.value = null
    await draw()
    ElMessage.success('温度模型计算结果已保存')
  } catch (saveError) {
    error.value = saveError?.msg
      || saveError?.response?.data?.msg
      || saveError?.message
      || '温度模型保存失败'
  } finally {
    busy.value = false
  }
}

function move (event) {
  const left = panel.value?.getBoundingClientRect().left ?? 0
  panelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
}

function stop () {
  window.removeEventListener('pointermove', move)
  window.removeEventListener('pointerup', stop)
  document.body.style.cursor = oldCursor
  document.body.style.userSelect = oldSelect
}

function resize (event) {
  event.preventDefault()
  oldCursor = document.body.style.cursor
  oldSelect = document.body.style.userSelect
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop, { once: true })
}

watch(
  () => props.node?.wellName,
  (current, previous) => {
    if (current && current !== previous) refresh()
  }
)
watch(form, () => {
  calculatedInput.value = null
}, { deep: true })
watch(() => ({ ...boundary.values }), () => {
  calculatedInput.value = null
}, { deep: true })

onMounted(() => {
  refresh()
  observer = new ResizeObserver(() => {
    chart?.resize()
    if (legendPosition.value) clampLegendPosition(legendPosition.value.x, legendPosition.value.y)
  })
  observer.observe(chartEl.value)
})

onBeforeUnmount(() => {
  disposed = true
  loadSequence++
  stop()
  stopLegendDrag()
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <section class="temperature-workspace">
    <div class="layout">
      <aside
        ref="panel"
        class="params-panel water-parameter-theme"
        :class="{ collapsed: paramsCollapsed }"
        :style="{ width: paramsCollapsed ? '22px' : panelWidth + 'px', minWidth: paramsCollapsed ? '22px' : panelWidth + 'px' }"
      >
        <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" @click="paramsCollapsed = false">参数设置</button>
        <div v-show="!paramsCollapsed" class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/></svg>
          </button>
        </div>

        <div v-show="!paramsCollapsed && activeParamTab === 'input'" class="panel-body">
          <div class="field model-field">
            <label for="temperature-model">温度模型</label>
            <el-select
              id="temperature-model"
              v-model="form.tempModel"
              :disabled="busy || sourceLoading"
              size="small"
            >
              <el-option value="alves" label="Alves分段能量平衡" />
              <el-option value="linear" label="井口锚定线性模型" />
            </el-select>
          </div>

          <section
            v-for="group in groups"
            :key="group.title"
            class="parameter-section"
          >
            <div class="section-title">{{ group.title }}</div>
            <div v-if="group.title === '井身结构及物性'" class="parameter-grid source-grid">
              <div class="field">
                <label for="temperature-pvt-source">PVT性质</label>
                <el-select
                  id="temperature-pvt-source"
                  :model-value="boundary.values.pvtId"
                  :disabled="busy || sourceLoading || pvtLoading"
                  size="small"
                  placeholder="请选择PVT性质"
                  @update:model-value="selectPvt"
                >
                  <el-option v-for="option in pvtOptions" :key="option.value" :value="option.value" :label="option.label" />
                </el-select>
              </div>
            </div>
            <div v-if="group.title === '生产数据'" class="parameter-grid source-grid">
              <div class="field">
                <label for="temperature-production-date">生产日期</label>
                <el-date-picker
                  id="temperature-production-date"
                  v-model="productionDateModel"
                  type="date"
                  format="YYYY/MM/DD"
                  value-format="YYYY-MM-DD"
                  placeholder="年/月/日"
                  :clearable="false"
                  :disabled-date="isProductionDateDisabled"
                  :disabled="busy || sourceLoading || pvtLoading"
                  size="small"
                />
              </div>
              <div v-if="boundary.values.boundaryPosition === 'wellhead'" class="field">
                <label for="temperature-production-channel">井口生产通道</label>
                <el-select
                  id="temperature-production-channel"
                  :model-value="boundary.values.wellheadChannel"
                  :disabled="busy || sourceLoading"
                  size="small"
                  @update:model-value="selectWellheadChannel"
                >
                  <el-option value="tubing" label="油管" />
                  <el-option value="casing" label="套管" />
                </el-select>
              </div>
            </div>
            <div class="parameter-grid">
              <div
                v-for="[key, label, min, max] in group.fields"
                :key="key"
                class="field"
              >
                <label :for="`temperature-${key}`">{{ label }}</label>
                <el-input-number
                  :id="`temperature-${key}`"
                  :model-value="fieldValue(key)"
                  :min="min"
                  :max="max"
                  :controls="false"
                  :disabled="busy || sourceLoading"
                  size="small"
                  @update:model-value="setFieldValue(key, $event)"
                />
              </div>
            </div>
          </section>

          <div class="boundary-selector">
            <span class="boundary-label">压力／温度位置</span>
            <el-radio-group
              :model-value="boundary.values.boundaryPosition"
              :disabled="busy || sourceLoading"
              size="small"
              @update:model-value="selectBoundaryPosition"
            >
              <el-radio-button value="wellhead">井口</el-radio-button>
              <el-radio-button value="bottomhole">井底</el-radio-button>
            </el-radio-group>
          </div>

          <div class="boundary-source-tip">
            <template v-if="boundary.values.boundaryPosition === 'wellhead'">
              使用 {{ boundary.values.productionDate || '所选日期' }} 的{{ channelLabel }}温压和同日气水量，计算井底温压。
            </template>
            <template v-else>
              井底压力、井底温度由用户手动输入；生产日期仅提供同日气水量，计算结果为井口温压。
            </template>
          </div>

          <el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
          />

          <div class="actions">
            <el-button
              :loading="busy"
              :disabled="sourceLoading || pvtLoading"
              size="small"
              class="calculate-button"
              @click="calculate"
            >
              计算温度分布
            </el-button>
            <el-button
              :disabled="busy || sourceLoading || pvtLoading || !calculatedInput"
              size="small"
              @click="save"
            >
              保存
            </el-button>
          </div>
        </div>

        <div v-show="!paramsCollapsed && activeParamTab === 'output'" class="panel-body">
          <div class="section-title">输出结果</div>
          <div v-for="[label, value] in outputFields" :key="label" class="field">
            <label>{{ label }}</label>
            <el-input :model-value="value" readonly size="small" />
          </div>
        </div>
        <div v-show="!paramsCollapsed" class="param-tabs" role="tablist" aria-label="参数面板">
          <button v-for="tab in [['input', '输入'], ['output', '输出']]" :key="tab[0]" type="button" role="tab" class="param-tab" :class="{ active: activeParamTab === tab[0] }" :aria-selected="activeParamTab === tab[0]" @click="activeParamTab = tab[0]">{{ tab[1] }}</button>
        </div>
        <div v-if="!paramsCollapsed" class="resizer" @pointerdown="resize" />
      </aside>

      <main v-loading="busy">
        <div class="dynamic-result-tabs">
          <div class="dynamic-result-tab active" :title="`${node.wellName}-温度模型-分析结果`">{{ node.wellName }}-温度模型-分析结果</div>
        </div>
        <div v-if="result" class="summary" aria-live="polite">
          <span>{{ result.tempModel === 'linear' ? `${resultBoundaryPosition === 'wellhead' ? '井口' : '井底'}锚定线性模型` : 'Alves分段能量平衡' }}</span>
          <span>{{ resultBoundaryPosition === 'wellhead' ? '井底流体温度' : '反算井口温度' }} <b>{{ displayNumber(resultBoundaryPosition === 'wellhead' ? result.inferredBottomTemperature : result.predictedWellheadTemperature) }} ℃</b></span>
          <template v-if="result.tempModel === 'alves'">
            <span>热松弛距离 <b>{{ displayNumber(result.thermal?.relaxationDistance, 1) }} m</b></span>
            <span>无量纲时间 <b>{{ displayNumber(result.thermal?.dimensionlessTime, 3) }}</b></span>
          </template>
        </div>
        <div ref="chartAreaEl" class="chart-container">
          <div ref="chartEl" class="chart" />
          <div v-if="result" ref="legendEl" class="floating-chart-legend" :class="{ dragging: draggingLegend }" :style="legendStyle" title="拖动调整图例位置" @pointerdown="startLegendDrag">
            <button v-for="item in legendItems" :key="item.name" type="button" class="floating-legend-item" :class="{ hidden: !legendSelected[item.name] }" :aria-pressed="legendSelected[item.name]" :title="legendSelected[item.name] ? '点击隐藏曲线' : '点击显示曲线'" @pointerdown.stop @click="toggleLegend(item.name)">
              <span class="legend-dot" :style="{ backgroundColor: legendSelected[item.name] ? item.color : 'transparent', borderColor: item.color }"></span>
              <span>{{ item.name }}</span>
            </button>
          </div>
          <div v-if="!result" class="chart-empty">请输入参数并计算温度分布</div>
        </div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.temperature-workspace {
  height: 100%;
  min-height: 0;
  background: #fff;
  color: #333;
  font-family: Arial, sans-serif;
  font-size: 13px;
  overflow: hidden;
}

.layout {
  display: flex;
  height: 100%;
  min-height: 0;
}

.params-panel {
  position: relative;
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #e0e0e0;
  transition: width 0.16s ease, min-width 0.16s ease;
}

.panel-head {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  padding: 7px 12px 6px;
  justify-content: space-between;
  border-bottom: 1px solid #f0f0f0;
  color: #333;
  font-size: 13px;
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 12px 14px;
}

.model-field {
  margin-top: 4px;
}

.section-title {
  margin: 10px 0 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.parameter-section { margin: 0; padding: 0; border: 0; }
.section-title:first-child { margin-top: 4px; }

.parameter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.field {
  margin-bottom: 9px;
}

.field label {
  display: block;
  margin-bottom: 3px;
  color: #555;
  font-size: 12px;
}

.el-select,
.el-date-editor.el-input,
.el-input-number {
  width: 100%;
}

:deep(.el-input-number .el-input__inner) {
  text-align: left !important;
}

/* 数字框、普通输出框和下拉框统一基线，覆盖数字控件默认的 15px 留白。 */
.params-panel :deep(.el-input__wrapper),
.params-panel :deep(.el-input-number.is-without-controls .el-input__wrapper),
.params-panel :deep(.el-select__wrapper) {
  box-sizing: border-box;
  min-height: 24px;
  height: 24px;
  padding: 0 6px;
  font-family: Arial, sans-serif;
  font-size: 12px;
}
.params-panel :deep(.el-input__inner),
.params-panel :deep(.el-select__selected-item),
.params-panel :deep(.el-radio-button__inner),
.params-panel :deep(.el-button) {
  font-family: Arial, sans-serif;
  font-size: 12px;
  font-weight: 400;
}
.params-panel :deep(.el-input__inner) {
  height: 22px;
  line-height: 22px;
  text-align: left;
}
.params-panel :deep(.el-select__selected-item) { line-height: 22px; }

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.boundary-selector {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.boundary-label {
  flex-shrink: 0;
  color: #555;
  font-size: 12px;
}

.source-grid { margin-bottom: 2px; }
.boundary-source-tip { margin: 8px 0 2px; color: #777; font-size: 12px; line-height: 1.5; }

.actions .el-button + .el-button {
  margin-left: 0;
}

.resizer {
  position: absolute;
  z-index: 2;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
}

.resizer:hover {
  background: rgba(64, 132, 217, 0.18);
}

main {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.chart {
  flex: 1;
  min-height: 0;
  height: 100%;
  width: 100%;
}

main :deep(.el-alert) {
  border-radius: 0;
}

/* 参数栏沿用水侵分析的分组间距与控件默认样式，右侧仅保留原有摘要和曲线。 */
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.panel-collapsed-tab { width: 22px; height: 76px; padding: 0; writing-mode: vertical-rl; border: 1px solid #e0e0e0; border-left: 0; background: #fff; color: #333; font: inherit; cursor: pointer; }
.params-panel.collapsed { border-right: 0; }
.panel-toggle:hover, .panel-collapsed-tab:hover { background: #fff8d8; }
.param-tabs { display: flex; height: 30px; flex-shrink: 0; border-top: 1px solid #e0e0e0; }
.param-tab { flex: 1; border: 0; border-right: 1px solid #e0e0e0; background: #fff; color: #555; font: inherit; cursor: pointer; }
.param-tab.active { background: #f4d000; color: #1a1a1a; font-weight: 600; }
.dynamic-result-tabs { display: flex; height: 34px; flex-shrink: 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.dynamic-result-tab { display: flex; align-items: center; max-width: 340px; padding: 0 12px; background: #f4d000; color: #202020; font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-container { position: relative; flex: 1; min-height: 0; overflow: hidden; }
.floating-chart-legend { position: absolute; z-index: 5; display: flex; flex-direction: column; gap: 5px; max-width: 280px; padding: 7px 10px; border: 1px solid #eee; background: rgba(255,255,255,.9); color: #333; font-size: 12px; line-height: 1.2; cursor: move; touch-action: none; user-select: none; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.floating-chart-legend.dragging { box-shadow: 0 4px 12px rgba(0,0,0,.14); }
.floating-legend-item { display: flex; align-items: center; gap: 5px; padding: 0; border: 0; background: transparent; color: inherit; font: inherit; white-space: nowrap; cursor: pointer; }
.floating-legend-item.hidden { color: #999; opacity: .55; }
.legend-dot { width: 10px; height: 10px; border: 1px solid transparent; border-radius: 50%; flex-shrink: 0; box-sizing: border-box; }
.chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: #999; pointer-events: none; }
.summary { display: flex; flex-shrink: 0; flex-wrap: wrap; align-items: center; gap: 4px 12px; padding: 5px 12px; font-size: 12px; line-height: 18px; color: #333; background: #fafafa; border-bottom: 1px solid #eef0f3; }
.summary > span + span { padding-left: 12px; border-left: 1px solid #dfe3e8; }
.actions :deep(.el-button) { margin-left: 0; border-radius: 4px; color: #202020; background: #fff; border-color: #c9cdd3; }
.actions :deep(.calculate-button) { background: #f4d000; border-color: #d5b900; }
.boundary-selector :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: #f4d000; border-color: #d5b900; color: #202020; box-shadow: -1px 0 0 #d5b900; }
</style>
