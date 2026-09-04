<!--
  动态产能－稳定流页面。

  数据流分为两条：
  1. “计算”复用旧平台稳定流算法，三种结果先进入页面临时状态；
  2. “保存”才把当前注采方向的输入快照、三种输出和完整IPR曲线写入新六表。
  因此编辑输入框不会直接改变图表，只有计算成功后才更新 calculated* 状态。
-->
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { productivityEvaluationApi } from '@/api/docker'
import { loadStableFlowEvaluationResults } from '@/utils/stableFlowEvaluation'
import { pvtStorageApi } from '@/api/pvtStorage'
import { dynamicProductivityApi } from '@/api/dynamicProductivity'
import { theoreticalProductivityApi } from '@/api/theoreticalProductivity'

const props = defineProps({
  wellName: { type: String, default: '' },
  defaultWellType: { type: String, default: '' },
  projectId: { type: Number, required: true },
  gasReservoirId: { type: Number, required: true },
  pvtTableOptions: { type: Array, default: () => [] },
  pvtRecords: { type: Array, default: () => [] },
  stableId: { type: Number, default: null },
  resultData: { type: Object, default: null },
  storageMode: { type: String, default: 'dynamic' },
  // 只有顶部菜单首次进入时为 true；左侧已有记录永远只恢复数据库快照。
  autoCalculate: { type: Boolean, default: false }
})
const emit = defineEmits(['saved', 'record-missing', 'initial-calculated'])
const DEFAULT_PVT_TABLE_VALUE = '__default_pvt__'

const panelWidth = ref(300)
const paramsCollapsed = ref(false)
const activeDirection = ref('production')
const activePanelTab = ref('input')
const activePressureMethod = ref('pseudoPressure')
const selectedPvtTable = ref(DEFAULT_PVT_TABLE_VALUE)
const calculating = ref(false)
const saving = ref(false)
const restoringRecord = ref(false)
const resolvedWellType = ref('')
const lastDefaultSavedResult = ref(null)
const stableRecord = reactive({ stableId: null, stableNo: 1, stableName: '稳定流1' })
const chartElement = ref(null)
let chart = null
let chartResizeObserver = null
let chartResizeFrame = 0
let chartResizeTimer = 0

// PVT 控制的气体性质。选定一张 PVT 后采气、注气共同使用这组值。
const pvtFields = reactive({
  gasType: '干气',
  specificGravity: '0.7336',
  hydrogenSulfide: '14.62',
  carbonDioxide: '8.96',
  nitrogen: '0',
  correctionMethod: 'Wichert-Aziz 修正方法',
  deviationMethod: 'Dranchuk-Abu-Kassem 方法',
  viscosityMethod: 'Lee-Gonzalez-Eakin 方法'
})

// 非 PVT 参数按采气/注气分开保存，切换方向时互不覆盖。
const directionForms = reactive({
  production: { permeability: '7.23428', thickness: '96.47', skin: '0', drainageRadius: '631.12', wellboreRadius: '0.09', horizontalLength: '4034.8', reservoirPressure: '55', temperature: '120' },
  injection: { permeability: '7.23428', thickness: '96.47', skin: '0', drainageRadius: '631.12', wellboreRadius: '0.09', horizontalLength: '4034.8', reservoirPressure: '55', temperature: '120' }
})

const pressureMethods = [
  { key: 'pseudoPressure', resultKey: 'pseudo-pressure', evaluationForm: 3, label: '拟压力', unit: 'MPa²/(mPa·s)' },
  { key: 'pressureSquared', resultKey: 'pressure-squared', evaluationForm: 2, label: '压力平方', unit: 'MPa²' },
  { key: 'pressure', resultKey: 'pressure', evaluationForm: 1, label: '压力', unit: 'MPa' }
]

const methodResults = reactive({
  pseudoPressure: { a: '1.4676E+3', b: '2.4004E+0', aof: '5.00', r2: '0.9986' },
  pressureSquared: { a: '8.2361E-2', b: '1.7492E-3', aof: '4.86', r2: '0.9972' },
  pressure: { a: '2.5914E-2', b: '8.3620E-4', aof: '4.72', r2: '0.9959' }
})
// 采气和注气各自拥有三种结果；切换方向时不会拿到另一方向的计算数据。
const calculatedOutputs = reactive({ production: {}, injection: {} })

const calculatedRowsByMethod = reactive({ production: {}, injection: {} })
const calculatedPointsByMethod = reactive({ production: {}, injection: {} })
const calculatedSeriesByMethod = reactive({ production: {}, injection: {} })
// 每种结果最后一次成功计算所使用的地层压力；编辑输入框时不修改它。
const calculatedFormationPressureByMethod = reactive({ production: {}, injection: {} })
// 保留原平台返回的完整结果，用于重算时复用 evaluationId、input.id 等关联信息。
const calculatedDetailsByMethod = reactive({ production: {}, injection: {} })

const form = computed(() => directionForms[activeDirection.value])
const result = computed(() => calculatedOutputs[activeDirection.value][activePressureMethod.value]
  || methodResults[activePressureMethod.value])
const currentMethod = computed(() => pressureMethods.find(item => item.key === activePressureMethod.value))
const directionLabel = computed(() => activeDirection.value === 'production' ? '采气' : '注气')
const isTheoretical = computed(() => props.storageMode === 'theoretical')
const moduleLabel = computed(() => isTheoretical.value ? '理论计算' : '动态产能')
const storageApi = computed(() => isTheoretical.value ? theoreticalProductivityApi : dynamicProductivityApi)
const resultTitle = computed(() => `${moduleLabel.value}-${props.wellName || '当前井'}-${stableRecord.stableName}-${directionLabel.value}分析结果`)
const normalizedWellType = computed(() => {
  if (resolvedWellType.value === 'horizontal') return '水平井'
  if (resolvedWellType.value === 'vertical') return '直井'
  const type = String(props.defaultWellType || '')
  return type.includes('水平') || /H$/i.test(props.wellName) ? '水平井' : '直井'
})
const availablePvtOptions = computed(() => [
  { label: '默认PVT', value: DEFAULT_PVT_TABLE_VALUE },
  ...(props.pvtTableOptions.length
    ? props.pvtTableOptions.map(option => typeof option === 'object'
    ? { label: String(option.label ?? option.value ?? ''), value: String(option.value ?? option.label ?? '') }
    : { label: String(option), value: String(option) })
    : [{ label: 'PVT性质1', value: 'demo-1' }, { label: 'PVT性质2', value: 'demo-2' }])
])
const selectedPvtRecord = computed(() => props.pvtRecords.find(record =>
  String(record?.pvtId ?? record?.id ?? '') === String(selectedPvtTable.value)
) || null)

const iprSeries = computed(() => calculatedSeriesByMethod[activeDirection.value][activePressureMethod.value] || [])
const formationPressure = computed(() => {
  const value = Number(calculatedFormationPressureByMethod[activeDirection.value][activePressureMethod.value])
  return Number.isFinite(value) && value > 0 ? value : 0
})
const pressureForCurve = curve => {
  // 原平台固定按 1/10～10/10 地层压力生成十条曲线。不能用当前已读取到的
  // 曲线条数作分母：旧错误数据只剩一条时，会把 Pᵣ1 错标成完整地层压力。
  const count = Math.max(10, ...iprSeries.value.map(item => Number(item.curveNumber) || 0))
  return formationPressure.value > 0
    ? formationPressure.value * curve.curveNumber / count
    : Math.max(...curve.points.map(point => point.y), 0)
}

// PVT 只负责回填其控制的气体性质；同一次稳定流的注气、采气共用该选择。
watch(selectedPvtRecord, record => {
  const row = record?.gasRows?.[0]
  if (!row) return
  const valueAt = (index, keys) => Array.isArray(row)
    ? row[index]
    : keys.map(key => row?.[key]).find(value => value !== undefined && value !== null)
  pvtFields.gasType = String(valueAt(0, ['gasType', 'gas_type']) || pvtFields.gasType)
  pvtFields.specificGravity = String(valueAt(1, ['specificGravity', 'specific_gravity']) ?? pvtFields.specificGravity)
  pvtFields.hydrogenSulfide = String(valueAt(2, ['hydrogenSulfide', 'hydrogen_sulfide']) ?? pvtFields.hydrogenSulfide)
  pvtFields.carbonDioxide = String(valueAt(3, ['carbonDioxide', 'carbon_dioxide']) ?? pvtFields.carbonDioxide)
  pvtFields.nitrogen = String(valueAt(4, ['nitrogen']) ?? pvtFields.nitrogen)
})

const loadDefaultParameters = async () => {
  const requestedWellName = props.wellName
  try {
    const response = await storageApi.value.getDefaultParameters(
      props.projectId, props.gasReservoirId, requestedWellName
    )
    // 快速连续切井时，较早请求的响应不能覆盖当前井。
    if (requestedWellName !== props.wellName) return
    const detail = response?.data ?? response
    resolvedWellType.value = detail?.wellType || resolvedWellType.value
    if (detail?.input) {
      applyCalculatedInput('production', { input: detail.input })
      applyCalculatedInput('injection', { input: detail.input })
    }
  } catch (error) {
    if (requestedWellName === props.wellName && error?.response?.status !== 404) {
      ElMessage.warning(error?.msg || error?.message || '默认PVT读取失败')
    }
  }
}

watch(selectedPvtTable, async pvtId => {
  if (restoringRecord.value) return
  if (pvtId === DEFAULT_PVT_TABLE_VALUE) {
    await loadDefaultParameters()
    return
  }
  const numericPvtId = Number(pvtId)
  if (!Number.isFinite(numericPvtId) || numericPvtId <= 0) return
  try {
    const response = await pvtStorageApi.getDetail(numericPvtId, props.projectId, props.gasReservoirId, props.wellName)
    const detail = response?.data ?? response ?? {}
    const input = detail.gasInput || {}
    let settings = detail.settings?.gas || {}
    if (typeof settings === 'string') {
      try { settings = JSON.parse(settings) } catch { settings = {} }
    }
    pvtFields.gasType = String(input.gasType || pvtFields.gasType)
    pvtFields.specificGravity = displayValue(input.specificGravity)
    pvtFields.hydrogenSulfide = displayValue(input.hydrogenSulfide)
    pvtFields.carbonDioxide = displayValue(input.carbonDioxide)
    pvtFields.nitrogen = displayValue(input.nitrogen)
    pvtFields.correctionMethod = settings.gasCorrectionMethod || pvtFields.correctionMethod
    pvtFields.deviationMethod = settings.deviationFactorMethod || pvtFields.deviationMethod
    pvtFields.viscosityMethod = settings.viscosityMethod || pvtFields.viscosityMethod
  } catch (error) {
    ElMessage.warning(error?.message || 'PVT性质明细读取失败')
  }
}, { immediate: true })
watch(() => props.wellName, async () => {
  const alreadyUsingDefault = selectedPvtTable.value === DEFAULT_PVT_TABLE_VALUE
  selectedPvtTable.value = DEFAULT_PVT_TABLE_VALUE
  resolvedWellType.value = ''
  // v-model 值本来就是默认PVT时不会触发 selectedPvtTable 的 watcher，
  // 因此切井必须主动读取新井的默认参数。
  if (alreadyUsingDefault) await loadDefaultParameters()
})

const clearCalculatedState = () => {
  for (const direction of ['production', 'injection']) {
    Object.keys(calculatedOutputs[direction]).forEach(key => delete calculatedOutputs[direction][key])
    Object.keys(calculatedRowsByMethod[direction]).forEach(key => delete calculatedRowsByMethod[direction][key])
    Object.keys(calculatedPointsByMethod[direction]).forEach(key => delete calculatedPointsByMethod[direction][key])
    Object.keys(calculatedSeriesByMethod[direction]).forEach(key => delete calculatedSeriesByMethod[direction][key])
    Object.keys(calculatedFormationPressureByMethod[direction]).forEach(key => delete calculatedFormationPressureByMethod[direction][key])
    Object.keys(calculatedDetailsByMethod[direction]).forEach(key => delete calculatedDetailsByMethod[direction][key])
  }
}

const applySavedOperation = operation => {
  if (!operation?.operationType || !operation?.input) return
  const direction = operation.operationType
  const input = operation.input
  pvtFields.gasType = String(input.gasType ?? pvtFields.gasType)
  pvtFields.specificGravity = displayValue(input.specificGravity)
  pvtFields.hydrogenSulfide = displayValue(input.hydrogenSulfide)
  pvtFields.carbonDioxide = displayValue(input.carbonDioxide)
  pvtFields.nitrogen = displayValue(input.nitrogen)
  pvtFields.correctionMethod = input.modificationMethod || pvtFields.correctionMethod
  pvtFields.deviationMethod = input.deviationFactorMethod || pvtFields.deviationMethod
  pvtFields.viscosityMethod = input.viscosityMethod || pvtFields.viscosityMethod
  directionForms[direction] = {
    permeability: displayValue(input.permeability),
    thickness: displayValue(input.formationThickness),
    skin: displayValue(input.skinFactor),
    drainageRadius: displayValue(input.drainageRadius),
    wellboreRadius: displayValue(input.wellboreRadius),
    horizontalLength: displayValue(input.horizontalSectionLength),
    reservoirPressure: displayValue(input.originalFormationPressure),
    temperature: displayValue(input.formationTemperature)
  }
  // 新库保存的是扁平曲线点；恢复时必须按 curveNumber 重新分成10条 ECharts 序列。
  ;(operation.outputs || []).forEach(output => {
    const key = output.pressureMethod === 'pseudo_pressure' ? 'pseudoPressure'
      : output.pressureMethod === 'pressure_squared' ? 'pressureSquared' : 'pressure'
    calculatedOutputs[direction][key] = {
      a: scientificValue(output.darcySeepageCoefficient),
      b: scientificValue(output.nonDarcySeepageCoefficient),
      aof: displayValue(output.openFlowCapacity),
      r2: displayValue(output.rSquared),
      gradient: output.gradient,
      intercept: output.intercept,
      reliabilityLevel: output.reliabilityLevel,
      reliabilityDescription: output.reliabilityDescription
    }
    const storedPoints = (output.iprPoints || [])
      .map(point => ({
        curveNumber: Number(point.curveNumber) || 1,
        x: Number(point.x),
        y: Number(point.y)
      }))
      .filter(point => Number.isFinite(point.x) && Number.isFinite(point.y))
    const seriesGroups = new Map()
    storedPoints.forEach(point => {
      if (!seriesGroups.has(point.curveNumber)) seriesGroups.set(point.curveNumber, [])
      seriesGroups.get(point.curveNumber).push({ x: point.x, y: point.y })
    })
    const series = [...seriesGroups.entries()]
      .map(([curveNumber, points]) => ({ curveNumber, points }))
      .sort((left, right) => left.curveNumber - right.curveNumber)
    const points = series[0]?.points || []
    calculatedPointsByMethod[direction][key] = points
    calculatedSeriesByMethod[direction][key] = series
    calculatedFormationPressureByMethod[direction][key] = Number(input.originalFormationPressure)
    calculatedRowsByMethod[direction][key] = points.map(point => [
      displayValue(input.originalFormationPressure), displayValue(point.y), displayValue(point.x)
    ])
  })
}

const loadSavedStable = async stableId => {
  const requestedWellName = props.wellName
  // stableId 为空表示顶部首次计算的新建工作区；有值才从新六表恢复历史快照。
  clearCalculatedState()
  if (!stableId) {
    stableRecord.stableId = null
    stableRecord.stableNo = 1
    stableRecord.stableName = '稳定流1'
    selectedPvtTable.value = DEFAULT_PVT_TABLE_VALUE
    activeDirection.value = 'production'
    return
  }
  try {
    restoringRecord.value = true
    const response = await storageApi.value.getStable(
      stableId, props.projectId, props.gasReservoirId, requestedWellName
    )
    if (requestedWellName !== props.wellName || Number(stableId) !== Number(props.stableId)) return
    const detail = response?.data ?? response
    stableRecord.stableId = Number(detail.record.stableId)
    stableRecord.stableNo = Number(detail.record.stableNo)
    stableRecord.stableName = detail.record.stableName
    resolvedWellType.value = detail.wellType
    selectedPvtTable.value = detail.record.pvtId
      ? String(detail.record.pvtId)
      : DEFAULT_PVT_TABLE_VALUE
    const operations = detail.operations || {}
    Object.values(operations).forEach(applySavedOperation)
    activeDirection.value = operations.production ? 'production' : 'injection'
    activePanelTab.value = 'output'
  } catch (error) {
    if (error?.response?.status === 404) {
      if (requestedWellName === props.wellName && Number(stableId) === Number(props.stableId)) {
        emit('record-missing', { stableId: Number(stableId), wellName: requestedWellName })
      }
      return
    }
    ElMessage.error(error?.msg || error?.message || `${moduleLabel.value}稳定流记录读取失败`)
  } finally {
    restoringRecord.value = false
  }
}

// 井名也是稳定流工作区身份的一部分。两口井都处于“新建记录”时 stableId 都为空，
// 只监听 stableId 会导致切井后继续沿用上一口井的计算状态和默认参数。
watch(
  [() => props.wellName, () => props.stableId],
  ([, stableId]) => loadSavedStable(stableId),
  { immediate: true }
)

const correctionMethods = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const deviationMethods = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const viscosityMethods = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const methodIndex = (value, options) => Math.max(0, options.indexOf(value))
const firstDefined = (...values) => values.find(value => value !== null && value !== undefined && value !== '')
const arrayValue = value => {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string') return []
  try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed : [] } catch { return [] }
}
const scientificValue = value => {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric === 0) return Number.isFinite(numeric) ? '0' : String(value ?? '0')
  return numeric.toExponential(4).replace('e', 'E')
}
const displayValue = value => value === null || value === undefined || value === '' ? '0' : String(value)
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))
const methodDisplayValue = (value, options, fallback) => {
  if (value === null || value === undefined || value === '') return fallback
  const numericIndex = Number(value)
  if (Number.isInteger(numericIndex) && String(value).trim() !== '') {
    return options[numericIndex] || fallback
  }
  return String(value)
}

// 原平台结果中的 input 才是本次计算真正使用的参数；读取结果时必须逐项回填，
// 不能继续显示页面初始化时的演示默认值。
const applyCalculatedInput = (direction, detail) => {
  const input = detail?.input || detail?.inputs?.[0]
  if (!input || typeof input !== 'object') return

  pvtFields.gasType = displayValue(firstDefined(input.gasType, input.gas_type, pvtFields.gasType))
  pvtFields.specificGravity = displayValue(firstDefined(input.specificGravity, input.specific_gravity, pvtFields.specificGravity))
  pvtFields.hydrogenSulfide = displayValue(firstDefined(input.hydrogenSulfide, input.hydrogen_sulfide, pvtFields.hydrogenSulfide))
  pvtFields.carbonDioxide = displayValue(firstDefined(input.carbonDioxide, input.carbon_dioxide, pvtFields.carbonDioxide))
  pvtFields.nitrogen = displayValue(firstDefined(input.nitrogen, pvtFields.nitrogen))
  pvtFields.correctionMethod = methodDisplayValue(
    firstDefined(input.modificationMethod, input.correctionMethod),
    correctionMethods,
    pvtFields.correctionMethod
  )
  pvtFields.deviationMethod = methodDisplayValue(
    firstDefined(input.deviationFactorMethod, input.deviationMethod),
    deviationMethods,
    pvtFields.deviationMethod
  )
  pvtFields.viscosityMethod = methodDisplayValue(
    input.viscosityMethod,
    viscosityMethods,
    pvtFields.viscosityMethod
  )

  const previous = directionForms[direction]
  directionForms[direction] = {
    permeability: displayValue(firstDefined(input.permeability, previous.permeability)),
    thickness: displayValue(firstDefined(input.thickness, input.formationThickness, previous.thickness)),
    skin: displayValue(firstDefined(input.skinFactor, input.skin, previous.skin)),
    drainageRadius: displayValue(firstDefined(input.gasDrainageRadius, input.drainageRadius, previous.drainageRadius)),
    wellboreRadius: displayValue(firstDefined(input.wellboreRadius, previous.wellboreRadius)),
    horizontalLength: displayValue(firstDefined(input.horizontalSectionLength, input.horizontalLength, previous.horizontalLength)),
    reservoirPressure: displayValue(firstDefined(input.originalFormationPressure, input.formationPressure, previous.reservoirPressure)),
    temperature: displayValue(firstDefined(input.formationTemperature, input.temperature, previous.temperature))
  }
}

const extractChartPoints = detail => {
  const items = arrayValue(detail?.iprChartItems ?? detail?.output?.iprChartItems ?? detail?.iprCurveItems)
  const itemWithPoints = items.find(item => arrayValue(item?.data ?? item?.items ?? item?.points ?? item?.chartData).length)
  return arrayValue(itemWithPoints?.data ?? itemWithPoints?.items ?? itemWithPoints?.points ?? itemWithPoints?.chartData)
    .map(point => ({
      x: Number(point?.xValue ?? point?.x ?? point?.gasProduction),
      y: Number(point?.yValue ?? point?.y ?? point?.bottomHoleFlowingPressure)
    }))
    .filter(point => Number.isFinite(point.x) && Number.isFinite(point.y))
}

const extractChartSeries = detail => {
  const items = arrayValue(detail?.iprChartItems ?? detail?.output?.iprChartItems ?? detail?.iprCurveItems)
  return items.map((item, index) => ({
    curveNumber: Number(String(item?.yAxisField || '').match(/(\d+)$/)?.[1]) || index + 1,
    points: arrayValue(item?.data ?? item?.items ?? item?.points ?? item?.chartData)
      .map(point => ({
        x: Number(point?.xValue ?? point?.x ?? point?.gasProduction),
        y: Number(point?.yValue ?? point?.y ?? point?.bottomHoleFlowingPressure)
      }))
      .filter(point => Number.isFinite(point.x) && Number.isFinite(point.y))
  })).filter(item => item.points.length).sort((left, right) => left.curveNumber - right.curveNumber)
}

const applyCalculatedDetail = (direction, method, detail) => {
  calculatedDetailsByMethod[direction][method.key] = detail
  applyCalculatedInput(direction, detail)
  const output = detail?.output || detail?.outputs?.[0] || {}
  calculatedOutputs[direction][method.key] = {
    a: scientificValue(firstDefined(output.darcySeepageCoefficient, output.darcyFlowCoefficient, output.darcyCoefficient, output.coefficientA, output.A, output.a)),
    b: scientificValue(firstDefined(output.nonDarcySeepageCoefficient, output.nonDarcyFlowCoefficient, output.nonDarcyCoefficient, output.coefficientB, output.B, output.b)),
    aof: displayValue(firstDefined(output.openFlowCapacity, output.absoluteOpenFlow, output.aofRate, output.aof)),
    r2: displayValue(firstDefined(output.rSquared, output.r2, output.R2)),
    gradient: firstDefined(output.gradient, output.slope),
    intercept: output.intercept,
    reliabilityLevel: firstDefined(output.reliabilityLevel, output.reliability),
    reliabilityDescription: output.reliabilityDescription
  }
  const series = extractChartSeries(detail)
  const points = series[0]?.points || extractChartPoints(detail)
  if (!points.length) return
  calculatedSeriesByMethod[direction][method.key] = series.length
    ? series
    : [{ curveNumber: 1, points }]
  calculatedFormationPressureByMethod[direction][method.key] = Number(firstDefined(
    detail?.input?.originalFormationPressure,
    detail?.input?.formationPressure,
    directionForms[direction].reservoirPressure
  ))
  calculatedPointsByMethod[direction][method.key] = points
  calculatedRowsByMethod[direction][method.key] = points.map((point, index) => [
    displayValue(firstDefined(detail?.input?.originalFormationPressure, detail?.input?.formationPressure, directionForms[direction].reservoirPressure)),
    displayValue(point.y),
    displayValue(point.x),
    index + 1
  ])
}

const buildCalculationInput = (existingInput = {}, evaluationId) => {
  const numericPvtId = Number(selectedPvtTable.value)
  return {
    ...existingInput,
    // input.id 是输入参数记录主键，不能拿 evaluationId 替代。
    ...(existingInput.id !== undefined && existingInput.id !== null
      ? { id: Number(existingInput.id) }
      : {}),
    ...(evaluationId !== undefined && evaluationId !== null
      ? { ProductivityEvaluationId: Number(evaluationId) }
      : {}),
    gasType: pvtFields.gasType,
    specificGravity: Number(pvtFields.specificGravity),
    hydrogenSulfide: Number(pvtFields.hydrogenSulfide),
    carbonDioxide: Number(pvtFields.carbonDioxide),
    nitrogen: Number(pvtFields.nitrogen),
    modificationMethod: methodIndex(pvtFields.correctionMethod, correctionMethods),
    deviationFactorMethod: methodIndex(pvtFields.deviationMethod, deviationMethods),
    viscosityMethod: methodIndex(pvtFields.viscosityMethod, viscosityMethods),
    permeability: Number(form.value.permeability),
    thickness: Number(form.value.thickness),
    skinFactor: Number(form.value.skin),
    gasDrainageRadius: Number(form.value.drainageRadius),
    wellboreRadius: Number(form.value.wellboreRadius),
    horizontalSectionLength: Number(form.value.horizontalLength),
    originalFormationPressure: Number(form.value.reservoirPressure),
    formationTemperature: Number(form.value.temperature),
    ...(Number.isFinite(numericPvtId) && numericPvtId > 0 ? { pvtId: numericPvtId } : {})
  }
}

const evaluationIdOf = detail => {
  const evaluation = detail?.evaluation || {}
  const existingInput = detail?.input || detail?.inputs?.[0] || {}
  return firstDefined(
    evaluation.evaluationId,
    evaluation.id,
    detail?.evaluationId,
    existingInput.ProductivityEvaluationId,
    existingInput.productivityEvaluationId,
    // 与理论稳定流保持一致：部分旧结果只在 input.id 中返回关联编号。
    existingInput.id
  )
}

// 新数据库保存的是本次计算快照，不复制原平台的内部主键。重新打开快照后，
// 在调用旧平台 calc 前按井和计算方式重新取得 evaluationId、input.id、inputItems。
const hydrateOriginalCalculationMetadata = async direction => {
  const metadataMissing = pressureMethods.some(method =>
    evaluationIdOf(calculatedDetailsByMethod[direction][method.key]) === undefined
  )
  if (!metadataMissing) return

  const loaded = await loadStableFlowEvaluationResults({
    projectId: props.projectId,
    gasReservoirId: props.gasReservoirId,
    wellName: props.wellName,
    preferredWellType: normalizedWellType.value === '水平井' ? 'horizontal' : 'vertical'
  })
  resolvedWellType.value = loaded.wellType || resolvedWellType.value
  pressureMethods.forEach(method => {
    const originalDetail = loaded.resultsByMethod?.[method.resultKey]?.detail
    if (originalDetail) calculatedDetailsByMethod[direction][method.key] = originalDetail
  })
}

// 页面内重新计算同时发起三种压力处理请求，不再人为增加请求间隔。
const calculatePressureMethodsConcurrently = async () => {
  const direction = activeDirection.value
  await hydrateOriginalCalculationMetadata(direction)
  return Promise.allSettled(pressureMethods.map(method => {
    const currentDetail = calculatedDetailsByMethod[direction][method.key] || {}
    const existingInput = currentDetail.input || currentDetail.inputs?.[0] || {}
    const evaluationId = evaluationIdOf(currentDetail)
    // 原平台规定：5=水平井，6=直井/斜井。
    const evaluationType = normalizedWellType.value === '水平井' ? 5 : 6
    const input = buildCalculationInput(existingInput, evaluationId)
    return productivityEvaluationApi.calculate(props.wellName, {
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      wellName: props.wellName,
      evaluationForm: method.evaluationForm,
      evaluationType,
      ...(evaluationId !== undefined ? { evaluationId: Number(evaluationId) } : {}),
      deletePointIds: [],
      input,
      inputItems: currentDetail.inputItems || currentDetail.inputsItems || []
    }, { silentError: true })
  }))
}

// 与理论稳定流复用原平台计算接口；结果只保存在当前页面状态，未接新库保存接口。
const handleCalculate = async ({ useBackendDefaults = false } = {}) => {
  if (calculating.value) return
  calculating.value = true
  const calculatingMessage = ElMessage({ message: `${props.wellName} ${moduleLabel.value}稳定流计算中，请稍候...`, type: 'info', duration: 0 })
  try {
    const calculatedDirection = activeDirection.value
    let calculationResults
    if (useBackendDefaults) {
      // 顶部首次计算与理论稳定流完全一致：调用 flowequation/calc 三次，使用后端默认参数。
      calculationResults = await productivityEvaluationApi.calculateStableFlowEquations({
          projectId: Number(props.projectId),
          gasReservoirId: Number(props.gasReservoirId),
          wellNames: [props.wellName],
          parameterSource: 1
        }, { silentError: true })
    } else {
      // 页面参数修改后的再次计算，保留完整输入快照请求。
      calculationResults = await calculatePressureMethodsConcurrently()
    }
    if (calculationResults.every(item => item.status === 'rejected')) {
      throw calculationResults[0]?.reason || new Error('三种压力计算均未成功')
    }
    // 三个 calc 请求结束后固定等待 1 秒，再统一查询一次三种最新结果。
    await wait(1500)
    const loaded = await loadStableFlowEvaluationResults({
      projectId: props.projectId,
      gasReservoirId: props.gasReservoirId,
      wellName: props.wellName,
      preferredWellType: normalizedWellType.value === '水平井' ? 'horizontal' : 'vertical',
      calculationResults
    })
    resolvedWellType.value = loaded.wellType || resolvedWellType.value
    pressureMethods.forEach(method => {
      const detail = loaded.resultsByMethod?.[method.resultKey]?.detail
      if (detail) applyCalculatedDetail(calculatedDirection, method, detail)
    })
    activePanelTab.value = 'output'
    if (useBackendDefaults) {
      // 顶部首次计算只更新该井唯一默认参数，不创建数据库编号“稳定流N”。
      await storageApi.value.saveDefaultParameters({
        projectId: Number(props.projectId),
        gasReservoirId: Number(props.gasReservoirId),
        wellName: props.wellName,
        wellType: normalizedWellType.value === '水平井' ? 'horizontal' : 'vertical',
        input: buildStoredInput(calculatedDirection)
      })
      emit('initial-calculated', loaded)
    }
    const failedCount = calculationResults.filter(item => item.status === 'rejected').length
    if (failedCount) ElMessage.warning(`${3 - failedCount} 种计算成功，${failedCount} 种计算失败`)
    else ElMessage.success(`${props.wellName} ${moduleLabel.value}稳定流三种计算完成`)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.message || `${moduleLabel.value}稳定流计算失败`)
  } finally {
    calculatingMessage.close()
    calculating.value = false
  }
}

// 页面已经停留在稳定流时，用户再次从顶部菜单点击“稳定流”不会重新挂载组件；
// 此时由 autoCalculate 的变化显式触发一次与首次进入相同的计算流程。
watch(() => props.autoCalculate, shouldCalculate => {
  if (shouldCalculate && !props.stableId && !props.resultData) {
    handleCalculate({ useBackendDefaults: true })
  }
})

// 顶部首次计算由父页面复用理论稳定流入口执行；这里只接收并展示三种真实结果。
watch(() => props.resultData, value => {
  if (!value || props.stableId) return
  resolvedWellType.value = value.wellType || resolvedWellType.value
  pressureMethods.forEach(method => {
    const detail = value.resultsByMethod?.[method.resultKey]?.detail
    if (detail) applyCalculatedDetail('production', method, detail)
  })
  activeDirection.value = 'production'
  activePanelTab.value = 'output'
}, { immediate: true })

const numericOrZero = value => Number.isFinite(Number(value)) ? Number(value) : 0
const nullableNumber = value => value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
  ? null : Number(value)

const buildStoredInput = direction => {
  const directionInput = directionForms[direction]
  return {
    gasType: pvtFields.gasType,
    specificGravity: numericOrZero(pvtFields.specificGravity),
    hydrogenSulfide: numericOrZero(pvtFields.hydrogenSulfide),
    carbonDioxide: numericOrZero(pvtFields.carbonDioxide),
    nitrogen: numericOrZero(pvtFields.nitrogen),
    modificationMethod: pvtFields.correctionMethod,
    deviationFactorMethod: pvtFields.deviationMethod,
    viscosityMethod: pvtFields.viscosityMethod,
    permeability: numericOrZero(directionInput.permeability),
    formationThickness: numericOrZero(directionInput.thickness),
    skinFactor: numericOrZero(directionInput.skin),
    drainageRadius: numericOrZero(directionInput.drainageRadius),
    wellboreRadius: numericOrZero(directionInput.wellboreRadius),
    horizontalSectionLength: normalizedWellType.value === '水平井'
      ? numericOrZero(directionInput.horizontalLength) : null,
    originalFormationPressure: numericOrZero(directionInput.reservoirPressure),
    formationTemperature: numericOrZero(directionInput.temperature)
  }
}

// 只有明确点击“保存”才写新库；重复保存同一稳定流时覆盖当前方向，另一方向保持原样。
const handleSave = async () => {
  if (saving.value) return null
  const outputs = pressureMethods.map(method => {
    const value = calculatedOutputs[activeDirection.value][method.key]
    if (!value) return null
    return {
      pressureMethod: method.key === 'pseudoPressure' ? 'pseudo_pressure'
        : method.key === 'pressureSquared' ? 'pressure_squared' : 'pressure',
      darcySeepageCoefficient: numericOrZero(value.a),
      nonDarcySeepageCoefficient: numericOrZero(value.b),
      openFlowCapacity: numericOrZero(value.aof),
      gradient: nullableNumber(value.gradient),
      intercept: nullableNumber(value.intercept),
      rSquared: nullableNumber(value.r2),
      reliabilityLevel: value.reliabilityLevel || null,
      reliabilityDescription: value.reliabilityDescription || null,
      // 一次计算会返回十条不同地层压力的 IPR 曲线。必须连同曲线编号全部保存；
      // 不能只保存 calculatedPointsByMethod 中供数据列表显示的第一条曲线。
      iprPoints: (calculatedSeriesByMethod[activeDirection.value][method.key] || [])
        .flatMap(curve => curve.points.map(point => ({
          curveNumber: Number(curve.curveNumber),
          x: Number(point.x),
          y: Number(point.y)
        })))
    }
  })
  if (outputs.some(item => !item)) {
    ElMessage.warning(`请先完成${directionLabel.value}的三种压力计算，再保存`)
    return null
  }
  saving.value = true
  try {
    const selectedOption = availablePvtOptions.value.find(option => String(option.value) === String(selectedPvtTable.value))
    const response = await storageApi.value.saveStable({
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      wellName: props.wellName,
      stableId: stableRecord.stableId,
      // 首次创建由数据库按照 next_stable_no 生成“稳定流N”，避免已有记录时重复叫“稳定流1”。
      stableName: stableRecord.stableId ? stableRecord.stableName : null,
      wellType: normalizedWellType.value === '水平井' ? 'horizontal' : 'vertical',
      pvtId: Number(selectedPvtTable.value) > 0 ? Number(selectedPvtTable.value) : null,
      pvtName: selectedOption?.label || null,
      parameterSource: Number(selectedPvtTable.value) > 0 ? 'pvt' : 'default',
      // 动态模块保留原有算法快照值，避免本次理论模块改造改变既有动态记录语义。
      algorithmCode: isTheoretical.value ? 'theoretical_stable_flow' : 'original_stable_flow',
      algorithmName: isTheoretical.value ? '理论计算稳定流（原平台算法）' : '原平台稳定流算法',
      operation: {
        operationType: activeDirection.value,
        input: buildStoredInput(activeDirection.value),
        outputs
      }
    })
    const saved = response?.data ?? response
    stableRecord.stableId = Number(saved.stableId)
    stableRecord.stableNo = Number(saved.stableNo)
    stableRecord.stableName = saved.stableName
    emit('saved', saved)
    ElMessage.success(`${stableRecord.stableName}的${directionLabel.value}结果已保存到新数据库`)
    return saved
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.msg || error?.message || `${moduleLabel.value}稳定流保存失败`)
    return null
  } finally {
    saving.value = false
  }
}

// 顶部菜单首次计算只保存“该井唯一默认参数”，不创建稳定流N，也不改变稳定流编号。
watch(() => props.resultData, async value => {
  if (!value || props.stableId || lastDefaultSavedResult.value === value) return
  await nextTick()
  try {
    await storageApi.value.saveDefaultParameters({
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      wellName: props.wellName,
      wellType: normalizedWellType.value === '水平井' ? 'horizontal' : 'vertical',
      input: buildStoredInput('production')
    })
    lastDefaultSavedResult.value = value
    ElMessage.success(`${props.wellName}的${moduleLabel.value}默认参数已保存`)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || error?.msg || error?.message || `${moduleLabel.value}默认参数保存失败`)
  }
}, { flush: 'post', immediate: true })

let dragStartX = 0
let dragStartWidth = 0
function startResize(event) {
  dragStartX = event.clientX
  dragStartWidth = panelWidth.value
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
}
function resizePanel(event) {
  panelWidth.value = Math.min(480, Math.max(280, dragStartWidth + event.clientX - dragStartX))
  scheduleChartResize()
}
function stopResize() { window.removeEventListener('pointermove', resizePanel) }

const compactNumber = value => Number(value).toFixed(3).replace(/\.?0+$/, '')
const scheduleChartResize = () => {
  if (chartResizeFrame) cancelAnimationFrame(chartResizeFrame)
  if (chartResizeTimer) clearTimeout(chartResizeTimer)
  chartResizeFrame = requestAnimationFrame(() => {
    chartResizeFrame = 0
    chart?.resize()
  })
  chartResizeTimer = window.setTimeout(() => {
    chartResizeTimer = 0
    chart?.resize()
  }, 220)
}
const renderChart = () => {
  if (!chartElement.value) return
  chart ||= echarts.getInstanceByDom(chartElement.value) || echarts.init(chartElement.value)
  const series = iprSeries.value.map(curve => ({
    name: `Pᵣ${curve.curveNumber}=${compactNumber(pressureForCurve(curve))} MPa`,
    type: 'line',
    smooth: true,
    showSymbol: false,
    lineStyle: { width: 2 },
    data: curve.points.map(point => [point.x, point.y])
  }))
  // 纵轴必须比最高地层压力多留一个刻度，否则最高压力曲线会贴顶并被裁切。
  const yMax = formationPressure.value > 0
    ? Math.ceil(formationPressure.value / 5) * 5 + 5
    : undefined
  chart.setOption({
    animation: false,
    color: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#2ec7c9'],
    title: { text: 'IPR曲线', left: 'center', top: 10, textStyle: { fontSize: 16, fontWeight: 600 } },
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', orient: 'vertical', right: 20, top: 52, backgroundColor: 'rgba(255,255,255,.9)', borderColor: '#e5e9f0', borderWidth: 1, padding: 8 },
    grid: { left: 82, right: 190, top: 64, bottom: 64 },
    xAxis: { type: 'value', min: 0, name: activeDirection.value === 'injection' ? '注气量 qsc(10⁴m³/d)' : 'qsc(10⁴m³/d)', nameLocation: 'middle', nameGap: 40, minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } }, splitLine: { lineStyle: { color: '#dfe6f1' } } },
    yAxis: { type: 'value', min: 0, max: yMax, name: 'Pwf (MPa)', nameLocation: 'middle', nameGap: 52, minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } }, splitLine: { lineStyle: { color: '#dfe6f1' } } },
    series,
    graphic: series.length ? [] : [{ type: 'text', left: 'center', top: 'middle', style: { text: '暂无IPR曲线数据', fill: '#999', font: '14px sans-serif' } }]
  }, true)
  scheduleChartResize()
}

watch([activeDirection, activePressureMethod, iprSeries, formationPressure], async () => {
  await nextTick()
  renderChart()
}, { deep: true })
watch(paramsCollapsed, async () => {
  await nextTick()
  scheduleChartResize()
})

const resizeChart = () => scheduleChartResize()
onMounted(async () => {
  window.addEventListener('resize', resizeChart)
  await nextTick()
  renderChart()
  if (typeof ResizeObserver !== 'undefined' && chartElement.value) {
    chartResizeObserver = new ResizeObserver(() => scheduleChartResize())
    chartResizeObserver.observe(chartElement.value)
  }
  if (props.autoCalculate && !props.stableId && !props.resultData) {
    await handleCalculate({ useBackendDefaults: true })
  }
})
onBeforeUnmount(() => {
  stopResize()
  window.removeEventListener('resize', resizeChart)
  chartResizeObserver?.disconnect()
  chartResizeObserver = null
  if (chartResizeFrame) cancelAnimationFrame(chartResizeFrame)
  if (chartResizeTimer) clearTimeout(chartResizeTimer)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="dynamic-stable-productivity-wrap">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }" :style="paramsCollapsed ? undefined : { width: `${panelWidth}px`, minWidth: `${panelWidth}px`, flexBasis: `${panelWidth}px` }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" @click="paramsCollapsed = false">参数设置</button>

      <template v-else>
        <div class="direction-tabs">
          <button type="button" :class="{ active: activeDirection === 'production' }" @click="activeDirection = 'production'">采气</button>
          <button type="button" :class="{ active: activeDirection === 'injection' }" @click="activeDirection = 'injection'">注气</button>
        </div>

        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>

        <div v-show="activePanelTab === 'input'" class="panel-body">
          <div class="field">
            <label>选择PVT表</label>
            <el-select v-model="selectedPvtTable" clearable size="small" placeholder="不选择时使用后端默认值" style="width: 100%">
              <el-option v-for="option in availablePvtOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </div>

          <div class="sec-label">气体性质</div>
          <div class="field-grid">
            <div class="field"><label>天然气类型</label><el-input v-model="pvtFields.gasType" size="small" readonly /></div>
            <div class="field"><label>天然气比重(dless)</label><el-input v-model="pvtFields.specificGravity" size="small" readonly /></div>
            <div class="field"><label>H₂S摩尔百分含量(%)</label><el-input v-model="pvtFields.hydrogenSulfide" size="small" readonly /></div>
            <div class="field"><label>CO₂摩尔百分含量(%)</label><el-input v-model="pvtFields.carbonDioxide" size="small" readonly /></div>
            <div class="field"><label>N₂摩尔百分含量(%)</label><el-input v-model="pvtFields.nitrogen" size="small" readonly /></div>
          </div>

          <div class="sec-label">计算方法</div>
          <div class="field-grid">
            <div class="field"><label>非烃气体修正方法</label><el-input v-model="pvtFields.correctionMethod" size="small" readonly /></div>
            <div class="field"><label>天然气偏差系数计算方法</label><el-input v-model="pvtFields.deviationMethod" size="small" readonly /></div>
            <div class="field"><label>天然气粘度计算方法</label><el-input v-model="pvtFields.viscosityMethod" size="small" readonly /></div>
          </div>

          <div class="sec-label">物性数据</div>
          <div class="field-grid">
            <div class="field"><label>产层渗透率(mD)</label><el-input v-model="form.permeability" size="small" /></div>
            <div class="field"><label>产层厚度(m)</label><el-input v-model="form.thickness" size="small" /></div>
            <div class="field"><label>表皮系数(dless)</label><el-input v-model="form.skin" size="small" /></div>
          </div>

          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <div class="field"><label>井型</label><el-input :model-value="normalizedWellType" size="small" readonly /></div>
            <div class="field"><label>泄气半径(m)</label><el-input v-model="form.drainageRadius" size="small" /></div>
            <div class="field"><label>井筒半径(m)</label><el-input v-model="form.wellboreRadius" size="small" /></div>
            <div v-if="normalizedWellType === '水平井'" class="field"><label>水平段长度(m)</label><el-input v-model="form.horizontalLength" size="small" /></div>
            <div class="field"><label>原始地层压力(MPa)</label><el-input v-model="form.reservoirPressure" size="small" /></div>
            <div class="field"><label>地层温度(℃)</label><el-input v-model="form.temperature" size="small" /></div>
          </div>

          <div class="form-actions">
            <button type="button" class="calculate-button" :disabled="calculating" @click="handleCalculate">{{ calculating ? '计算中...' : '计算' }}</button>
            <button type="button" class="save-button" :disabled="saving" @click="handleSave">{{ saving ? '保存中...' : '保存' }}</button>
          </div>
        </div>

        <div v-show="activePanelTab === 'output'" class="panel-body">
          <div class="field calculation-method-field">
            <label>计算方法</label>
            <div class="calculation-method-options">
              <label v-for="method in pressureMethods" :key="method.key"><input v-model="activePressureMethod" type="radio" :value="method.key" />{{ method.label }}</label>
            </div>
          </div>
          <div class="sec-label">输出结果</div>
          <div class="field-grid">
            <div class="field"><label>达西渗流项系数A</label><el-input :model-value="result.a" size="small" readonly /></div>
            <div class="field"><label>非达西渗流项系数B</label><el-input :model-value="result.b" size="small" readonly /></div>
            <div class="field"><label>无阻流量(10⁴m³/d)</label><el-input :model-value="result.aof" size="small" readonly /></div>
            <div class="field"><label>拟合优度R²</label><el-input :model-value="result.r2" size="small" readonly /></div>
          </div>
        </div>

        <div class="param-tabs">
          <button type="button" :class="{ active: activePanelTab === 'input' }" @click="activePanelTab = 'input'">输入</button>
          <button type="button" :class="{ active: activePanelTab === 'output' }" @click="activePanelTab = 'output'">输出</button>
        </div>
        <div class="params-resizer" @pointerdown="startResize"></div>
      </template>
    </aside>

    <main class="result-area">
      <div class="dynamic-result-tabs">
        <button type="button" class="dynamic-result-tab active" :title="resultTitle"><span>{{ resultTitle }}</span></button>
      </div>

      <div class="chart-placeholder" :aria-label="`${moduleLabel}稳定流结果分析图`">
        <div ref="chartElement" class="theoretical-ipr-chart" />
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 样式尺寸直接沿用现有单井产能页面的参数栏与结果区规范。
.dynamic-stable-productivity-wrap { height: 100%; min-height: 0; display: flex; overflow: hidden; background: #fff; color: #333; font-size: 13px; }
.params-panel { position: relative; width: 300px; min-width: 300px; flex: 0 0 300px; min-height: 0; display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #d7d7d7; background: #fff; }
.params-panel.collapsed { width: 34px; min-width: 34px; flex-basis: 34px; }
.panel-collapsed-tab { width: 100%; height: 76px; padding: 8px 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #333; writing-mode: vertical-rl; cursor: pointer; }
.direction-tabs { height: 44px; padding: 7px 12px 0; display: flex; align-items: flex-start; flex-shrink: 0; box-sizing: border-box; }
.direction-tabs button { min-width: 94px; height: 30px; padding: 0 12px; border: 1px solid #222; border-right: 0; background: #fff; color: #222; font: inherit; cursor: pointer; }
.direction-tabs button:last-child { border-right: 1px solid #222; }
.direction-tabs button.active { background: #f4d000; color: #111; font-weight: 700; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; display: flex; align-items: center; justify-content: center; background: transparent; cursor: pointer; }
.panel-body { flex: 1; min-height: 0; padding: 10px 12px 16px; overflow-y: auto; }
.sec-label { height: 22px; margin: 8px 0 7px; display: flex; align-items: center; gap: 8px; font-weight: 500; }
.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }
.field { margin-bottom: 9px; }
.field label { display: block; margin-bottom: 3px; color: #555; font-size: 12px; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); column-gap: 24px; }
:deep(.el-input__wrapper), :deep(.el-select__wrapper) { min-height: 24px; border-radius: 3px; box-shadow: 0 0 0 1px #aaa inset; font-size: 13px; }
:deep(.el-input__wrapper:has(.el-input__inner[readonly])) { background: #f5f6f7; }
.calculate-button { min-width: 86px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #222; cursor: pointer; }
.form-actions { display: flex; align-items: center; gap: 10px; }
.save-button { min-width: 86px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #888; border-radius: 4px; background: #fff; color: #222; cursor: pointer; }
.calculate-button:disabled, .save-button:disabled { opacity: .6; cursor: not-allowed; }
.calculation-method-field { margin-bottom: 12px; }
.calculation-method-options { min-height: 28px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
.calculation-method-options label { display: inline-flex; align-items: center; gap: 5px; white-space: nowrap; cursor: pointer; }
.calculation-method-options input { width: 13px; height: 13px; margin: 0; accent-color: #333; }
.param-tabs { height: 30px; display: flex; flex-shrink: 0; border-top: 1px solid #e0e0e0; }
.param-tabs button { flex: 1; border: 0; border-right: 1px solid #e0e0e0; background: #fff; color: #333; font: inherit; cursor: pointer; }
.param-tabs button:last-child { border-right: 0; }
.param-tabs button.active { background: #f4d000; color: #111; font-weight: 600; }
.params-resizer { position: absolute; z-index: 4; top: 0; right: -3px; width: 6px; height: 100%; cursor: col-resize; }

.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.dynamic-result-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.dynamic-result-tab { height: 34px; max-width: 430px; padding: 0 12px; border: 0; border-right: 1px solid #e4e7ed; background: #f4d000; color: #202020; font: inherit; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-placeholder { flex: 1; min-height: 0; position: relative; overflow: hidden; }
.theoretical-ipr-chart { width: 100%; height: 100%; }
</style>
