<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { nodeApi, productivityEvaluationApi } from '@/api/docker'
import { NODETYPE } from '@/constants/nodeType'
import { pvtStorageApi } from '@/api/pvtStorage'
import { productivityTestsApi } from '@/api/productivityTests'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  wellName: { type: String, default: '' },
  testId: { type: [Number, String], default: null },
  evaluationId: { type: [Number, String], default: null }
})
const emit = defineEmits(['saved'])

const GAS_DEFAULTS = Object.freeze({ gasType: '', specificGravity: null, hydrogenSulfide: 0,
  carbonDioxide: 0, nitrogen: 0, condensateOilDensity: 0,
  modificationMethod: 0, deviationFactorMethod: 0, viscosityMethod: 0 })
const STATIC_DATE = '2015-10-12'
const staticRows = () => [[45, 35.61, 34.933472], [59.1, 35.605, 34.655762],
  [77.6, 35.6, 34.25293], [85, 35.68, 34.082031], [29.5, 35.68, 35.217285]]
  .map(([flowRate, recoveryPressure, flowingPressure], index) => ({
    sequence: index + 1, date: STATIC_DATE, flowRate, recoveryPressure, flowingPressure
  }))

const fileInput = ref(null)
const chartEl = ref(null)
const pvtOptions = ref([])
const pvtDetailCache = new Map()
const selectedPvtId = ref('')
const selectedGas = ref({ ...GAS_DEFAULTS })
const rows = ref(staticRows())
const importedFileName = ref('修正等时验证数据（静态）')
const maximumFormationPressure = ref(56.34)
const formationTemperature = ref(120)
const calculationMethod = ref('pseudo-pressure')
const calculationResultType = ref('binomial')
const operationType = ref('production')
const testDate = ref(STATIC_DATE)
const currentResult = ref(null)
const activePanel = ref('input')
const activeChart = ref('analysis')
const loading = ref(false)
const calculating = ref(false)
const saving = ref(false)
const importing = ref(false)
const inputDirty = ref(true)
const resultDirty = ref(false)
const evaluationIds = ref({})
let chart
let loadSequence = 0
const initializedForms = new Set()

const unwrap = response => response?.data ?? response ?? {}
const scientific = value => Number.isFinite(Number(value)) ? Number(value).toExponential(4).replace('e', 'E') : ''
const GAS_TYPE_NAMES = ['干气', '湿气', '凝析气']
const MODIFICATION_METHOD_NAMES = ['Wichert-Aziz 修正方法', 'Carr-Kobayashi-Burrous 修正方法']
const DEVIATION_METHOD_NAMES = ['Dranchuk-Abu-Kassem 方法', 'Dranchuk-Purvis-Robinson 方法', 'Hall-Yarborough 方法']
const VISCOSITY_METHOD_NAMES = ['Lee-Gonzalez-Eakin 方法', 'Carr-Kobayashi-Burrous 方法', 'Sutton 方法']
const isMissingValue = value => value === null || value === undefined ||
  ['null', 'undefined', 'nan'].includes(String(value).trim().toLowerCase()) || String(value).trim() === ''
const platformNumber = (value, fallback = 0) => {
  if (isMissingValue(value)) return fallback
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : fallback
}
const platformMethodIndex = (value, names, fallback = 0) => {
  if (isMissingValue(value)) return fallback
  const numeric = Number(value)
  if (Number.isInteger(numeric) && numeric >= 0 && numeric < names.length) return numeric
  const index = names.indexOf(String(value).trim())
  return index >= 0 ? index : fallback
}
const gasWithDefaults = value => Object.fromEntries(Object.entries(GAS_DEFAULTS).map(([key, fallback]) => {
  const current = value?.[key]
  return [key, isMissingValue(current) ? fallback : current]
}))
const normalizeGasType = value => {
  if (value === null || value === undefined || String(value).trim() === '') return null
  const numeric = Number(value)
  if (Number.isInteger(numeric) && numeric >= 0 && numeric <= 2) return numeric
  const text = String(value).trim().toLowerCase()
  if (text.includes('湿') || text.includes('wet')) return 1
  if (text.includes('凝析') || text.includes('condensate')) return 2
  if (text.includes('干') || text.includes('dry')) return 0
  return null
}
const platformGasType = value => {
  const index = normalizeGasType(value)
  return index === null ? '' : GAS_TYPE_NAMES[index]
}
const gasFromPvtDetail = detail => {
  const settings = typeof detail?.settings?.gas === 'string'
    ? JSON.parse(detail.settings.gas || '{}')
    : (detail?.settings?.gas || {})
  const source = { ...(detail?.gasInput || {}), ...settings }
  return gasWithDefaults({ ...source,
    modificationMethod: platformMethodIndex(
      isMissingValue(source.modificationMethod) ? source.gasCorrectionMethod : source.modificationMethod,
      MODIFICATION_METHOD_NAMES),
    deviationFactorMethod: platformMethodIndex(source.deviationFactorMethod, DEVIATION_METHOD_NAMES),
    viscosityMethod: platformMethodIndex(source.viscosityMethod, VISCOSITY_METHOD_NAMES)
  })
}
const isValidPvtGas = gas => normalizeGasType(gas?.gasType) !== null &&
  Number.isFinite(Number(gas?.specificGravity)) && Number(gas.specificGravity) > 0
const normalizeMethod = value => ({ 1: 'pressure', 2: 'pressure-squared', 3: 'pseudo-pressure',
  '压力形式': 'pressure', '压力平方形式': 'pressure-squared', '拟压力形式': 'pseudo-pressure' }[value] ||
  (['pressure', 'pressure-squared', 'pseudo-pressure'].includes(value) ? value : 'pseudo-pressure'))

const loadPvtOptions = async () => {
  pvtOptions.value = []
  pvtDetailCache.clear()
  if (!props.wellName) return void (selectedPvtId.value = '')
  try {
    const summaries = unwrap(await pvtStorageApi.list(
      props.projectId, props.gasReservoirId, props.wellName
    )) || []
    // 选项必须与当前井 project_well_pvt 主表完全一致；参数完整性在计算时校验，
    // 不能因为名称或明细缺项在修正等时页面静默隐藏数据库记录。
    pvtOptions.value = Array.isArray(summaries) ? summaries : []
    await Promise.all(pvtOptions.value.map(async record => {
      try {
        const detail = unwrap(await pvtStorageApi.getDetail(
          record.pvtId, props.projectId, props.gasReservoirId, props.wellName
        ))
        pvtDetailCache.set(String(record.pvtId), detail)
      } catch (error) {
        console.warn(`PVT性质${record.pvtNo}明细读取失败`, error)
      }
    }))
    if (!pvtOptions.value.some(item => String(item.pvtId) === String(selectedPvtId.value))) {
      const preferred = pvtOptions.value.find(item => isValidPvtGas(
        gasFromPvtDetail(pvtDetailCache.get(String(item.pvtId)))
      ))
      selectedPvtId.value = String((preferred || pvtOptions.value[0])?.pvtId || '')
    }
  } catch (error) {
    selectedPvtId.value = ''
    console.warn('PVT数据库记录读取失败', error)
  }
}

const loadPvtDetail = async () => {
  markInputDirty()
  if (!selectedPvtId.value) return void (selectedGas.value = { ...GAS_DEFAULTS })
  const detail = pvtDetailCache.get(String(selectedPvtId.value)) || unwrap(await pvtStorageApi.getDetail(
    selectedPvtId.value, props.projectId, props.gasReservoirId, props.wellName))
  selectedGas.value = gasFromPvtDetail(detail)
  const pvtTemperature = Number(detail.gasInput?.formationTemperature)
  if (Number.isFinite(pvtTemperature)) formationTemperature.value = pvtTemperature
}

const evaluationFormByMethod = { pressure: 1, 'pressure-squared': 2, 'pseudo-pressure': 3 }
const pressureNodeTypeByMethod = {
  pressure: NODETYPE.NodeType_ProductivityEvaluationByPressure,
  'pressure-squared': NODETYPE.NodeType_ProductivityEvaluationByPressureSquared,
  'pseudo-pressure': NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure
}
const analysisCurves = [
  { curveType: 'regularized', field: 'regularizedPressure', sourceName: '不稳定数据点', name: '不稳定点', color: '#5470c6' },
  { curveType: 'regression', field: 'linearRegressionPressure', sourceName: '线性回归分析线', name: '回归线', color: '#333' },
  { curveType: 'shifted-regression', field: 'shiftLinearRegressionPressure', sourceName: '线性回归分析平移线', name: '平移线', color: '#f5b642' },
  { curveType: 'stable', field: 'stableRegularizedPressure', sourceName: '稳定数据点', name: '稳定点', color: '#ee6666' }
]
const exponentialAnalysisCurves = [
  { curveType: 'analysis', name: '测试点', color: '#5470c6' },
  { curveType: 'regression', name: '稳定回归线', color: '#333' },
  { curveType: 'transient', name: '不稳定辅助线', color: '#f5b642' }
]
const curvesForResult = type => type === 'exponential' ? exponentialAnalysisCurves : analysisCurves

const chartData = item => (item?.data || []).map(point => ({
  x: Number(point.xValue), y: Number(point.yValue), deleted: Boolean(point.isDeleted),
  dataLabel: point.dataLabel || ''
})).filter(point => Number.isFinite(point.x) && Number.isFinite(point.y))

const parseResult = detail => {
  const output = detail.output || {}
  const charts = detail.chartItems || []
  const analysisSeries = analysisCurves.map(config => ({ ...config,
    data: chartData(charts.find(item => item.yAxisField === config.field) ||
      charts.find(item => String(item.name).trim() === config.sourceName))
  })).filter(series => series.data.length)
  const formationPressure = Number(detail.input?.originalFormationPressure)
  const iprSeries = (detail.iprChartItems || []).map((item, index) => {
    const curveNumber = Number(String(item.yAxisField || '').match(/(\d+)$/)?.[1]) || index + 1
    return { curveNumber, data: chartData(item) }
  }).filter(series => series.data.length).sort((a, b) => a.curveNumber - b.curveNumber)
  return { calculationMethod: normalizeMethod(detail.evaluation?.evaluationForm ?? detail.evaluationFormDesc),
    evaluationId: Number(detail.evaluation?.id || detail.input?.ProductivityEvaluationId),
    calculationResultType: 'binomial', formationPressure,
    darcyCoefficient: Number(output.darcySeepageCoefficient),
    nonDarcyCoefficient: Number(output.nonDarcySeepageCoefficient),
    aofRate: Number(output.openFlowCapacity), gradient: Number(output.gradient),
    intercept: Number(output.intercept), rSquared: Number(output.rSquared),
    reliabilityLevel: Number(output.reliability), reliability: output.reliabilityDesc || '',
    analysisSeries, iprSeries }
}

const completeResult = result =>
  curvesForResult(result?.calculationResultType).every(config => result?.analysisSeries?.some(series =>
    series.curveType === config.curveType && series.data.length)) &&
  (result?.iprSeries?.length || 0) > 1

const knownEvaluationId = method => {
  const normalized = normalizeMethod(method)
  const stored = Number(evaluationIds.value[normalized])
  if (Number.isFinite(stored) && stored > 0) return stored
  if (props.evaluationId !== null && props.evaluationId !== undefined && props.evaluationId !== '' &&
      Number.isFinite(Number(props.evaluationId)) && Number(props.evaluationId) > 0) {
    return Number(props.evaluationId)
  }
  return null
}

const nodeChildren = node => [node?.children, node?.subNodes, node?.nodes, node?.analysisNodes]
  .flatMap(value => Array.isArray(value) ? value : value ? [value] : [])
const nodeLabel = node => String(node?.wellName || node?.nodeTitle || node?.name || node?.title || node?.label || '')
const candidateNodeIds = node => ['evaluationId', 'ProductivityEvaluationId', 'resultId', 'result', 'nodeId', 'id']
  .map(field => Number(node?.[field])).filter(value => Number.isFinite(value) && value > 0)

const discoverEvaluationId = async method => {
  const normalized = normalizeMethod(method)
  const response = await nodeApi.getNode(props.projectId, props.gasReservoirId,
    pressureNodeTypeByMethod[normalized], { silentError: true })
  const candidates = new Set()
  const walk = (node, insideWell = false) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) return node.forEach(item => walk(item, insideWell))
    const label = nodeLabel(node)
    const inWell = insideWell || label === props.wellName || node.wellName === props.wellName
    if (inWell) candidateNodeIds(node).forEach(id => candidates.add(id))
    nodeChildren(node).forEach(child => walk(child, inWell))
  }
  const payload = unwrap(response)
  walk(payload.node ?? payload)
  const form = evaluationFormByMethod[normalized]
  for (const id of [...candidates].sort((a, b) => b - a)) {
    try {
      const result = await productivityEvaluationApi.getResult(
        props.projectId, props.gasReservoirId, id, { silentError: true })
      const detail = result?.data?.data ?? result?.data ?? result
      if (String(detail?.evaluation?.wellName) === String(props.wellName) &&
          Number(detail?.evaluation?.evaluationForm) === form &&
          Number(detail?.evaluation?.evaluationType) === 4) return id
    } catch { /* 节点编号不是评价主键时继续验证下一个候选值。 */ }
  }
  return null
}

const resolveEvaluationId = async method => {
  const normalized = normalizeMethod(method)
  const known = knownEvaluationId(normalized)
  if (known) return known
  const form = evaluationFormByMethod[normalized]
  const formKey = `${props.projectId}/${props.gasReservoirId}/${props.wellName}/${form}`
  if (props.testId) {
    const existing = await discoverEvaluationId(normalized)
    if (existing) {
      evaluationIds.value = { ...evaluationIds.value, [normalized]: existing }
      return existing
    }
  }
  if (!initializedForms.has(formKey)) {
    await productivityEvaluationApi.initialize({
      gasReservoirId: Number(props.gasReservoirId), projectId: Number(props.projectId),
      wellNames: [props.wellName], evaluationForm: form
    }, { silentError: true })
    initializedForms.add(formKey)
  }
  for (let attempt = 0; attempt < 8; attempt++) {
    if (attempt) await new Promise(resolve => setTimeout(resolve, 500))
    const discovered = await discoverEvaluationId(normalized)
    if (discovered) {
      evaluationIds.value = { ...evaluationIds.value, [normalized]: discovered }
      return discovered
    }
  }
  throw new Error('当前井口无修正等时结果')
}

const fetchCompleteResult = async method => {
  const evaluationId = knownEvaluationId(method)
  if (!evaluationId) return null
  const response = await productivityEvaluationApi.getResult(
    props.projectId, props.gasReservoirId, evaluationId, { silentError: true }
  )
  const detail = response?.data?.data ?? response?.data ?? response
  return { ...parseResult(detail), calculationMethod: normalizeMethod(method) }
}

const calculatePlatformResult = async (minimumPoints = 2) => {
  const validRows = rows.value.filter(row =>
    [row.flowRate, row.recoveryPressure, row.flowingPressure].every(value => Number.isFinite(Number(value))))
  if (validRows.length < minimumPoints) throw new Error(
    minimumPoints === 3 ? '修正等时指数式至少需要3个有效测试点，最后一行为稳定点' : '至少需要两个有效测试点')
  if (validRows.some(row => Number(row.flowRate) <= 0 ||
      Number(row.recoveryPressure) <= Number(row.flowingPressure))) {
    throw new Error('测试气产量必须大于0，且地层/恢复压力必须大于测试流压')
  }
  const method = normalizeMethod(calculationMethod.value)
  const evaluationForm = evaluationFormByMethod[method]
  const evaluationId = await resolveEvaluationId(method)
  const gas = gasWithDefaults(selectedGas.value)
  const gasTypeIndex = normalizeGasType(gas.gasType)
  const specificGravity = platformNumber(gas.specificGravity, Number.NaN)
  if (gasTypeIndex === null || !Number.isFinite(specificGravity) || specificGravity <= 0) {
    throw new Error('所选PVT性质缺少有效的气体类型或天然气相对密度')
  }
  // 原平台的产能评价接口使用中文气体类型名称；PVT库中的历史记录则可能保存为 0/1/2。
  const gasType = GAS_TYPE_NAMES[gasTypeIndex]
  const input = {
    id: evaluationId, ProductivityEvaluationId: evaluationId,
    originalFormationPressure: Number(maximumFormationPressure.value),
    formationTemperature: Number(formationTemperature.value), horizontalSectionLength: 0,
    skinFactor: 0, permeability: 0, thickness: 0, gasDrainageRadius: 0, wellboreRadius: 0,
    gasType, specificGravity,
    hydrogenSulfide: platformNumber(gas.hydrogenSulfide),
    carbonDioxide: platformNumber(gas.carbonDioxide), nitrogen: platformNumber(gas.nitrogen),
    condensateOilDensityUnderStandardCondition: platformNumber(gas.condensateOilDensity),
    modificationMethod: platformMethodIndex(gas.modificationMethod, MODIFICATION_METHOD_NAMES),
    deviationFactorMethod: platformMethodIndex(gas.deviationFactorMethod, DEVIATION_METHOD_NAMES),
    viscosityMethod: platformMethodIndex(gas.viscosityMethod, VISCOSITY_METHOD_NAMES), edges: {},
    condensateOilDensity: platformNumber(gas.condensateOilDensity)
  }
  await productivityEvaluationApi.calculate(props.wellName, {
    // 原平台单井产能模块约定 calc 请求的 gasReservoirId 固定为 0；
    // 实际气藏 ID 仅用于初始化评价节点和读取计算结果。
    gasReservoirId: 0, projectId: Number(props.projectId),
    evaluationId, deletePointIds: [], input,
    inputItems: validRows.map((row, index) => ({ testPointNumber: index + 1,
      reserviorPressure: Number(row.recoveryPressure),
      testDailyGasProduction: Number(row.flowRate), testFlowPressure: Number(row.flowingPressure),
      testDailyOilProduction: 0 })),
    evaluationForm, evaluationType: 4, wellName: props.wellName
  }, { silentError: true })
  // calc 负责写入计算结果；随后始终按评价节点编号读取完整 output/chart/IPR 契约。
  const resultResponse = await productivityEvaluationApi.getResult(
    props.projectId, props.gasReservoirId, evaluationId, { silentError: true }
  )
  const detail = resultResponse?.data?.data ?? resultResponse?.data ?? resultResponse
  if (!detail?.output) throw new Error('原平台未返回完整的修正等时计算结果')
  evaluationIds.value = { ...evaluationIds.value, [method]: evaluationId }
  return { detail, validRows, method, evaluationId }
}

const calculateResult = async () => {
  const { detail, method, evaluationId } = await calculatePlatformResult()
  return { ...parseResult(detail), calculationMethod: method, evaluationId }
}

const calculateExponentialResult = async () => {
  if (operationType.value !== 'production') throw new Error('修正等时指数式当前仅支持采气')
  // 与二项式完全共用 PVT 参数和原平台物性计算；这里只从返回值恢复 ΔΦ，随后替换指数式公式。
  const { detail, validRows, method, evaluationId } = await calculatePlatformResult(3)
  const regularized = chartData((detail.chartItems || []).find(item =>
    item.yAxisField === 'regularizedPressure' || String(item.name).trim() === '不稳定数据点'))
  if (regularized.length < validRows.length) {
    throw new Error('原平台计算结果缺少与测试点对应的压力函数数据')
  }
  const pressureFunctionDifferences = validRows.map((row, index) => {
    const point = regularized[index]
    const rate = Number(row.flowRate)
    if (!Number.isFinite(point?.y) || point.y <= 0 ||
        Math.abs(Number(point.x) - rate) > Math.max(1e-6, Math.abs(rate) * 1e-4)) {
      throw new Error(`原平台第${index + 1}个压力函数点与当前试井数据不一致`)
    }
    return { testPointNumber: index + 1, pressureFunctionDifference: rate * point.y }
  })
  const output = detail.output || {}
  const darcy = Number(output.darcySeepageCoefficient)
  const nonDarcy = Number(output.nonDarcySeepageCoefficient)
  if (![darcy, nonDarcy].every(Number.isFinite)) {
    throw new Error('原平台计算结果缺少生成IPR所需的二项式压力函数系数')
  }
  const platformIpr = parseResult(detail).iprSeries
  if (platformIpr.length < 2) throw new Error('原平台计算结果缺少IPR压力函数网格')
  const pressureFunctionCurves = platformIpr.map(series => ({
    formationPressure: Number(maximumFormationPressure.value) * series.curveNumber / 10,
    points: series.data.map(point => ({
      bottomHoleFlowingPressure: Number(point.y),
      pressureFunctionDifference: Math.max(0, darcy * Number(point.x) + nonDarcy * Number(point.x) ** 2)
    }))
  }))
  const response = unwrap(await productivityTestsApi.calculateModifiedIsochronalExponential({
    projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId),
    wellName: props.wellName, pvtId: Number(selectedPvtId.value), operationType: operationType.value,
    pressureMethod: method,
    maximumFormationPressure: Number(maximumFormationPressure.value),
    inputItems: validRows.map((row, index) => ({ testPointNumber: index + 1,
      testDailyGasProduction: Number(row.flowRate), reservoirPressure: Number(row.recoveryPressure),
      testFlowPressure: Number(row.flowingPressure) })),
    pressureFunctionDifferences, pressureFunctionCurves
  }))
  const analysisSeries = exponentialAnalysisCurves.map(config => ({ ...config,
    data: (config.curveType === 'analysis' ? response.analysisPoints
      : config.curveType === 'regression' ? response.regressionLine : response.transientLine)
      .map(point => ({ x: Number(point.x), y: Number(point.y), deleted: false,
        dataLabel: point.label || '' }))
  }))
  const iprSeries = (response.iprCurves || []).map((curve, index) => ({
    curveNumber: index + 1, formationPressure: Number(curve.formationPressure),
    data: (curve.points || []).map(point => ({ x: Number(point.gasProduction),
      y: Number(point.bottomHoleFlowingPressure), deleted: false, dataLabel: point.label || '' }))
  }))
  return { calculationResultType: 'exponential', calculationMethod: response.pressureMethod,
    evaluationId,
    formationPressure: Number(maximumFormationPressure.value),
    productivityCoefficient: Number(response.productivityCoefficient),
    productivityExponent: Number(response.productivityExponent),
    transientProductivityCoefficient: Number(response.transientProductivityCoefficient),
    aofRate: Number(response.openFlowCapacity), rSquared: Number(response.rSquared),
    reliability: response.reliabilityDescription || '', equation: response.equation || '',
    analysisSeries, iprSeries }
}

const saveResult = async (result, pvtId) => {
  const gas = selectedGas.value
  const chartPoints = result.analysisSeries.flatMap(series => series.data.map((point, index) => ({
    curveType: series.curveType, pointNumber: index + 1, xValue: point.x, yValue: point.y,
    deleted: point.deleted, dataLabel: point.dataLabel
  })))
  const iprPoints = result.iprSeries.flatMap(series => series.data.map((point, index) => ({
    curveNumber: series.curveNumber, pointNumber: index + 1, gasProduction: point.x,
    bottomHoleFlowingPressure: point.y, deleted: point.deleted, dataLabel: point.dataLabel,
    formationPressure: Number(series.formationPressure ??
      Number(maximumFormationPressure.value) * series.curveNumber / 10)
  })))
  const saved = unwrap(await productivityTestsApi.save({
    testId: props.testId ? Number(props.testId) : null, projectId: Number(props.projectId),
    gasReservoirId: Number(props.gasReservoirId), wellName: props.wellName, pvtId,
    operationType: operationType.value, testMethod: 'modified-isochronal',
    testDate: rows.value.find(row => row.date)?.date || testDate.value,
    wellType: null, replaceInput: !props.testId || inputDirty.value,
    input: { maximumFormationPressure: Number(maximumFormationPressure.value),
      formationTemperature: Number(formationTemperature.value), onePointAlpha: null,
      gasType: platformGasType(gas.gasType), specificGravity: Number(gas.specificGravity), hydrogenSulfide: Number(gas.hydrogenSulfide || 0),
      carbonDioxide: Number(gas.carbonDioxide || 0), nitrogen: Number(gas.nitrogen || 0),
      condensateOilDensity: gas.condensateOilDensity, modificationMethod: String(gas.modificationMethod ?? ''),
      deviationFactorMethod: String(gas.deviationFactorMethod ?? ''), viscosityMethod: String(gas.viscosityMethod ?? '') },
    inputItems: rows.value.filter(row =>
      [row.flowRate, row.recoveryPressure, row.flowingPressure].every(value => Number.isFinite(Number(value))))
      .map((row, index) => ({ testPointNumber: index + 1,
      testDailyGasProduction: Number(row.flowRate), reservoirPressure: Number(row.recoveryPressure),
      testFlowPressure: Number(row.flowingPressure) })),
    result: { calculationResultType: result.calculationResultType || 'binomial',
      pressureMethod: result.calculationMethod, evaluationId: result.evaluationId,
      darcySeepageCoefficient: result.darcyCoefficient,
      nonDarcySeepageCoefficient: result.nonDarcyCoefficient, openFlowCapacity: result.aofRate,
      productivityCoefficient: result.productivityCoefficient,
      productivityExponent: result.productivityExponent,
      gradient: result.gradient, intercept: result.intercept, rSquared: result.rSquared,
      reliabilityLevel: Number.isFinite(result.reliabilityLevel) ? result.reliabilityLevel :
        (result.rSquared >= .9 ? 2 : result.rSquared >= .7 ? 1 : 0),
      reliabilityDescription: result.reliability, chartPoints, iprPoints }
  }))
  inputDirty.value = false
  emit('saved', saved)
}

const calculate = async () => {
  if (!props.wellName) return ElMessage.warning('请先选择井')
  if (!selectedPvtId.value) return ElMessage.warning('当前井没有可用的数据库PVT性质，请先创建PVT性质')
  calculating.value = true
  try {
    const pvtId = Number(selectedPvtId.value)
    if (!Number.isFinite(pvtId) || pvtId <= 0) throw new Error('请选择有效的数据库PVT性质')
    const result = calculationResultType.value === 'exponential'
      ? await calculateExponentialResult() : await calculateResult()
    currentResult.value = result; activePanel.value = 'analysis'; activeChart.value = 'analysis'
    resultDirty.value = true
    await nextTick(); renderChart()
    ElMessage.success('计算完成，请点击保存写入数据库')
  } catch (error) {
    const response = error?.response?.data
    const serverMessage = typeof response === 'string'
      ? response
      : response?.message || response?.msg || response?.error || response?.detail
    console.error('修正等时计算接口失败', {
      status: error?.response?.status,
      response,
      request: error?.config?.data
    })
    ElMessage.error(serverMessage || error?.msg || error?.message || '计算失败')
  } finally { calculating.value = false }
}

const save = async () => {
  if (!currentResult.value) return ElMessage.warning('请先完成计算')
  const pvtId = Number(selectedPvtId.value)
  if (!Number.isFinite(pvtId) || pvtId <= 0) return ElMessage.warning('请选择有效的数据库PVT性质')
  saving.value = true
  try {
    await saveResult(currentResult.value, pvtId)
    resultDirty.value = false
    ElMessage.success('修正等时记录已保存到数据库')
  } catch (error) {
    const response = error?.response?.data
    const serverMessage = typeof response === 'string'
      ? response
      : response?.message || response?.msg || response?.error || response?.detail
    ElMessage.error(serverMessage || error?.msg || error?.message || '保存失败')
  } finally { saving.value = false }
}

const normalizeRows = items => (items || []).map((item, index) => ({ sequence: item.testPointNumber ?? index + 1,
  date: item.date || item.testDate || testDate.value, flowRate: item.testDailyGasProduction ?? item.flowRate,
  recoveryPressure: item.reservoirPressure ?? item.reserviorPressure ?? item.recoveryPressure,
  flowingPressure: item.testFlowPressure ?? item.flowingPressure }))

const loadTest = async (requestedType = null, requestedMethod = null) => {
  const sequence = ++loadSequence
  resultDirty.value = false
  currentResult.value = null
  activePanel.value = 'input'
  if (!props.testId) {
    // loadPvtOptions 已优先选中气体类型和相对密度完整的记录，不要再用第一条覆盖它。
    if (!pvtOptions.value.some(item => String(item.pvtId) === String(selectedPvtId.value))) {
      selectedPvtId.value = pvtOptions.value.length ? String(pvtOptions.value[0].pvtId) : ''
    }
    selectedGas.value = { ...GAS_DEFAULTS }; rows.value = staticRows()
    importedFileName.value = '修正等时验证数据（静态）'; maximumFormationPressure.value = 56.34
    formationTemperature.value = 120; calculationMethod.value = 'pseudo-pressure'; testDate.value = STATIC_DATE
    operationType.value = 'production'; calculationResultType.value = 'binomial'; inputDirty.value = true
    evaluationIds.value = {}
    if (selectedPvtId.value) {
      try {
        const detail = pvtDetailCache.get(String(selectedPvtId.value)) || unwrap(await pvtStorageApi.getDetail(
          selectedPvtId.value, props.projectId, props.gasReservoirId, props.wellName))
        if (sequence === loadSequence) {
          selectedGas.value = gasFromPvtDetail(detail)
          const pvtTemperature = Number(detail.gasInput?.formationTemperature)
          if (Number.isFinite(pvtTemperature)) formationTemperature.value = pvtTemperature
        }
      } catch (error) { console.warn('默认PVT性质明细读取失败', error) }
    }
    return
  }
  loading.value = true
  try {
    const detail = unwrap(await productivityTestsApi.detail(
      props.testId, props.projectId, props.gasReservoirId, props.wellName
    )); const input = detail.input || {}
    if (sequence !== loadSequence) return
    const targetType = requestedType === 'exponential' ? 'exponential'
      : requestedType === 'binomial' ? 'binomial' : null
    const targetMethod = requestedMethod ? normalizeMethod(requestedMethod) : null
    const availableResults = Array.isArray(detail.results) ? detail.results : []
    const selectedResult = availableResults.find(item =>
      (!targetType || (item.calculationResultType || 'binomial') === targetType) &&
      (!targetMethod || normalizeMethod(item.pressureMethod) === targetMethod)
    ) || (!targetType && !targetMethod ? detail.result : null)
    selectedPvtId.value = pvtOptions.value.some(item => Number(item.pvtId) === Number(detail.pvtId))
      ? String(detail.pvtId) : ''
    selectedGas.value = gasWithDefaults(input); maximumFormationPressure.value = input.maximumFormationPressure
    formationTemperature.value = input.formationTemperature
    calculationMethod.value = normalizeMethod(selectedResult?.pressureMethod || requestedMethod)
    calculationResultType.value = selectedResult?.calculationResultType === 'exponential' || requestedType === 'exponential'
      ? 'exponential' : 'binomial'
    operationType.value = detail.operationType || 'production'; testDate.value = detail.testDate
    evaluationIds.value = Object.fromEntries((detail.evaluations || []).map(item =>
      [normalizeMethod(item.pressureMethod), Number(item.evaluationId)]))
    rows.value = normalizeRows(detail.inputItems)
    importedFileName.value = `${detail.testName}已保存数据`
    if (!selectedResult) {
      inputDirty.value = false
      currentResult.value = null
      activePanel.value = 'input'
      return
    }
    const result = selectedResult; const chartItems = result.chartPoints || []
    const selectedCurves = curvesForResult(calculationResultType.value)
    const analysisSeries = selectedCurves.map(config => ({ ...config,
      data: chartItems.filter(point => point.curveType === config.curveType).map(point => ({
        x: Number(point.xValue), y: Number(point.yValue), deleted: Boolean(point.deleted),
        dataLabel: point.dataLabel || ''
      }))
    })).filter(series => series.data.length)
    const iprByCurve = (result.iprPoints || []).reduce((groups, point) => {
      const curveNumber = Number(point.curveNumber)
      groups.set(curveNumber, [...(groups.get(curveNumber) || []), point])
      return groups
    }, new Map())
    const iprSeries = [...iprByCurve].map(([curveNumber, points]) => ({ curveNumber,
      formationPressure: Number(points[0]?.formationPressure ??
        Number(input.maximumFormationPressure) * curveNumber / 10),
      data: points.map(point => ({ x: Number(point.gasProduction),
        y: Number(point.bottomHoleFlowingPressure), deleted: Boolean(point.deleted),
        dataLabel: point.dataLabel || '' }))
    })).sort((a, b) => a.curveNumber - b.curveNumber)
    let loadedResult = { calculationResultType: calculationResultType.value,
      calculationMethod: result.pressureMethod, evaluationId: result.evaluationId,
      formationPressure: Number(input.maximumFormationPressure),
      darcyCoefficient: result.darcySeepageCoefficient,
      nonDarcyCoefficient: result.nonDarcySeepageCoefficient, aofRate: result.openFlowCapacity,
      productivityCoefficient: result.productivityCoefficient,
      productivityExponent: result.productivityExponent,
      gradient: result.gradient, intercept: result.intercept, rSquared: result.rSquared,
      reliabilityLevel: result.reliabilityLevel, reliability: result.reliabilityDescription,
      analysisSeries, iprSeries }
    inputDirty.value = false
    if (calculationResultType.value === 'binomial' && !completeResult(loadedResult)) {
      try {
        const complete = await fetchCompleteResult(result.pressureMethod)
        if (sequence !== loadSequence) return
        if (completeResult(complete)) {
          loadedResult = complete
          resultDirty.value = true
          ElMessage.success('已从结果接口补全曲线，点击保存后写入数据库')
        }
      } catch (error) {
        console.warn('旧修正等时记录曲线补全失败，继续显示数据库已有结果', error)
      }
    }
    currentResult.value = loadedResult
    activePanel.value = 'analysis'; activeChart.value = 'analysis'
    await nextTick(); renderChart()
  } catch (error) {
    if (sequence === loadSequence) ElMessage.error(error?.msg || error?.message || '试井记录加载失败')
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

const switchResultType = async () => {
  resultDirty.value = false
  if (props.testId) await loadTest(calculationResultType.value, calculationMethod.value)
  else invalidateResult()
}

const switchPressureMethod = async () => {
  resultDirty.value = false
  if (props.testId) await loadTest(calculationResultType.value, calculationMethod.value)
  else invalidateResult()
}

const chooseFile = () => fileInput.value?.click()
const handleFile = async event => {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  importing.value = true
  try { const data = unwrap(await productivityTestsApi.importFile(file)); rows.value = normalizeRows(data.rows)
    importedFileName.value = file.name; markInputDirty() }
  catch (error) { ElMessage.error(error?.msg || error?.message || '文件导入失败') }
  finally { importing.value = false }
}

const addRow = () => { rows.value.push({ sequence: rows.value.length + 1, date: testDate.value,
  flowRate: null, recoveryPressure: null, flowingPressure: null }); markInputDirty() }
const removeRow = index => { rows.value.splice(index, 1); rows.value.forEach((row, i) => { row.sequence = i + 1 }); markInputDirty() }
const commitCell = (row, field, event, numeric = false) => {
  const text = event.currentTarget.textContent.trim()
  row[field] = numeric && text !== '' ? Number(text) : text
  markInputDirty()
}
const finishCell = event => event.currentTarget.blur()
const invalidateResult = () => { currentResult.value = null; resultDirty.value = false; activePanel.value = 'input' }
const markInputDirty = () => { inputDirty.value = true; invalidateResult() }

const compact = value => Number(value).toFixed(3).replace(/\.?0+$/, '')
const analysisUnit = method => ({
  'pseudo-pressure': '(ψws - ψwf)/qsc\n[(MPa²/(mPa·s))/(10⁴m³/d)]',
  'pressure-squared': '(Pr² - Pwf²)/qsc\n[MPa²/(10⁴m³/d)]',
  pressure: '(Pr - Pwf)/qsc\n[MPa/(10⁴m³/d)]'
}[method] || '压力函数差 / qsc')
const legendUnit = method => analysisUnit(method).split('\n')[1] || ''
const equationLeft = method => ({
  'pseudo-pressure': 'ψws - ψwf',
  'pressure-squared': 'Pr² - Pwf²',
  pressure: 'Pr - Pwf'
}[method] || 'Δp')
const exponentialAnalysisUnit = method => ({
  'pseudo-pressure': 'm(Pr) - m(Pwf)\n[MPa²/(mPa·s)]',
  'pressure-squared': 'Pr² - Pwf²\n[MPa²]',
  pressure: 'Pr - Pwf\n[MPa]'
}[method] || '压力函数差')

const renderChart = () => {
  if (!chartEl.value || !currentResult.value || activePanel.value !== 'analysis') return
  if (chart && chart.getDom() !== chartEl.value) {
    chart.dispose()
    chart = null
  }
  chart ||= echarts.getInstanceByDom(chartEl.value) || echarts.init(chartEl.value)
  const result = currentResult.value; const isIpr = activeChart.value === 'ipr'
  const isExponential = result.calculationResultType === 'exponential'
  const formationPressure = Number(result.formationPressure || maximumFormationPressure.value)
  const iprYAxisMax = Number.isFinite(formationPressure) && formationPressure > 0
    ? Math.ceil(formationPressure / 10) * 10 : undefined
  const visible = points => points.filter(point => !point.deleted).map(point => [point.x, point.y])
  const series = isIpr
    ? result.iprSeries.map(item => ({ name: `Pr${item.curveNumber}=${compact(item.formationPressure ?? formationPressure * item.curveNumber / 10)} MPa`,
      type: 'line', smooth: true, showSymbol: false, lineStyle: { width: 2 }, data: visible(item.data) }))
    : result.analysisSeries.map(item => ({ name: isExponential ? item.name : `${item.name}${legendUnit(result.calculationMethod)}`,
      type: (isExponential ? item.curveType === 'analysis' : ['regularized', 'stable'].includes(item.curveType)) ? 'scatter' : 'line',
      z: (isExponential ? item.curveType === 'analysis' : ['regularized', 'stable'].includes(item.curveType)) ? 5 : 2,
      symbolSize: item.curveType === 'stable' ? 12 : 10,
      showSymbol: isExponential ? item.curveType === 'analysis' : ['regularized', 'stable'].includes(item.curveType),
      itemStyle: { color: item.color }, lineStyle: { color: item.color, width: 2,
        type: ['shifted-regression', 'transient'].includes(item.curveType) ? 'dotted' : 'solid' }, data: visible(item.data) }))
  const equation = isExponential
    ? (result.equation || `qsc = ${scientific(result.productivityCoefficient)} × [${equationLeft(result.calculationMethod)}]^${Number(result.productivityExponent).toFixed(4)}`) +
      `\nR² = ${Number(result.rSquared).toFixed(4)}`
    : `${equationLeft(result.calculationMethod)} = ${scientific(result.darcyCoefficient)} qsc + ${scientific(result.nonDarcyCoefficient)} qsc²\nR² = ${Number(result.rSquared).toFixed(4)}`
  chart.setOption({ animation: false, color: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#2ec7c9'],
    title: { text: isIpr ? 'IPR曲线' : '修正等时试井分析图', left: 'center', top: 8,
      textStyle: { fontSize: 17, fontWeight: 600, color: '#333' } },
    tooltip: { trigger: isIpr ? 'axis' : 'item' },
    legend: { type: 'scroll', orient: 'vertical', right: 22, top: 52,
      itemWidth: 17, itemHeight: 10, backgroundColor: 'rgba(255,255,255,.9)',
      borderColor: '#e5e9f0', borderWidth: 1, padding: 9 },
    grid: { left: 92, right: isIpr ? 205 : 245, top: 70, bottom: 70 },
    xAxis: { type: !isIpr && isExponential ? 'log' : 'value', scale: !isIpr, name: 'qsc(10⁴m³/d)', nameLocation: 'middle', nameGap: 42,
      min: isIpr ? 0 : undefined,
      minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } },
      splitLine: { lineStyle: { color: '#dfe6f1' } } },
    yAxis: { type: !isIpr && isExponential ? 'log' : 'value', scale: !isIpr, min: isIpr ? 0 : undefined,
      max: isIpr ? iprYAxisMax : undefined,
      name: isIpr ? 'Pwf (MPa)' : isExponential
        ? exponentialAnalysisUnit(result.calculationMethod) : analysisUnit(result.calculationMethod),
      nameLocation: 'middle', nameGap: 62, nameTextStyle: { lineHeight: 18 },
      minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } },
      splitLine: { lineStyle: { color: '#dfe6f1' } } }, series,
    graphic: isIpr ? [] : [{ type: 'text', left: '55%', top: '73%', z: 100, zlevel: 10, silent: true,
      style: { text: equation, fill: '#333', font: '14px sans-serif', lineHeight: 22,
        backgroundColor: 'rgba(255,255,255,.92)', padding: [5, 8] } }] }, true)
  chart.resize()
}
const switchPanel = async panel => { activePanel.value = panel; if (panel === 'analysis') { await nextTick(); renderChart() } }
const switchChart = async mode => { activeChart.value = mode; await nextTick(); renderChart() }
const resizeChart = () => chart?.resize()

watch(() => props.testId, () => loadTest())
watch(() => props.wellName, async () => { await loadPvtOptions(); await loadTest() })
onMounted(async () => { await loadPvtOptions(); await loadTest(); window.addEventListener('resize', resizeChart) })
onBeforeUnmount(() => { window.removeEventListener('resize', resizeChart); chart?.dispose(); chart = null })
</script>

<template>
  <section v-loading="loading" class="modified-workspace">
    <aside class="params-panel">
      <div class="panel-head">参数设置</div>
      <div class="panel-body">
        <label class="field"><span>选择PVT表</span><select v-model="selectedPvtId" @change="loadPvtDetail">
          <option value="" disabled>{{ pvtOptions.length ? '请选择PVT性质' : '当前井暂无PVT性质' }}</option>
          <option v-for="item in pvtOptions" :key="item.pvtId" :value="String(item.pvtId)">{{ item.pvtName || `PVT性质${item.pvtNo}` }}</option>
        </select></label>
        <label class="field"><span>选择数据表</span>
          <button type="button" class="file-button" :title="importedFileName" :disabled="importing" @click="chooseFile">{{ importing ? '正在解析…' : '导入表1' }}</button>
          <input ref="fileInput" class="hidden-file" type="file" accept=".xlsx,.xls,.csv" @change="handleFile" />
          <small>{{ importedFileName }} · {{ rows.length }} 行</small>
        </label>
        <div class="section-title"><span>其他数据</span><i /></div>
        <label class="field"><span>计算IPR曲线的最大地层压力（MPa）</span><input v-model.number="maximumFormationPressure" @change="markInputDirty" /></label>
        <label class="field"><span>地层温度（℃）</span><input v-model.number="formationTemperature" @change="markInputDirty" /></label>
        <fieldset class="radios"><legend>计算方法</legend>
          <label><input v-model="calculationMethod" type="radio" value="pseudo-pressure" @change="switchPressureMethod" />拟压力</label>
          <label><input v-model="calculationMethod" type="radio" value="pressure-squared" @change="switchPressureMethod" />压力平方法</label>
          <label><input v-model="calculationMethod" type="radio" value="pressure" @change="switchPressureMethod" />压力法</label>
        </fieldset>
        <fieldset class="radios"><legend>注采类型</legend>
          <label><input v-model="operationType" type="radio" value="production" />采气</label>
          <label class="disabled-option" title="注气计算暂未开放"><input type="radio" value="injection" disabled />注气</label>
        </fieldset>
        <fieldset class="radios"><legend>计算结果</legend>
          <label><input v-model="calculationResultType" type="radio" value="binomial" @change="switchResultType" />二项式</label>
          <label><input v-model="calculationResultType" type="radio" value="exponential" @change="switchResultType" />指数式</label>
        </fieldset>
        <div class="action-buttons">
          <button type="button" class="calculate" :disabled="calculating || saving" @click="calculate">{{ calculating ? '计算中…' : '计算' }}</button>
          <button type="button" class="save" :disabled="!currentResult || !resultDirty || calculating || saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
        <div v-if="currentResult" class="inline-output">
          <template v-if="currentResult.calculationResultType === 'exponential'">
            <label>指数式产能系数C<input :value="scientific(currentResult.productivityCoefficient)" readonly /></label>
            <label>产能指数n<input :value="currentResult.productivityExponent" readonly /></label>
            <label>拟合优度R²<input :value="currentResult.rSquared" readonly /></label>
            <label>可靠性说明<input :value="currentResult.reliability" readonly /></label>
          </template>
          <template v-else>
            <label>达西渗流项系数A<input :value="scientific(currentResult.darcyCoefficient)" readonly /></label>
            <label>非达西渗流项系数B<input :value="scientific(currentResult.nonDarcyCoefficient)" readonly /></label>
          </template>
          <label>无阻流量(10⁴m³/d)<input :value="currentResult.aofRate" readonly /></label>
        </div>
      </div>
    </aside>
    <main class="result-area">
      <div v-show="activePanel === 'input'" class="editable-data-grid">
        <div class="data-toolbar"><span>可直接编辑；计算时使用当前表格值</span><el-button size="small" @click="addRow">新增测点</el-button></div>
        <el-table :data="rows" border height="100%">
          <el-table-column label="序号" width="70" align="center"><template #default="scope">{{ String(scope.$index + 1).padStart(2, '0') }}</template></el-table-column>
          <el-table-column label="产能试井日期" min-width="145" align="center"><template #default="scope"><div class="grid-cell" contenteditable="true" spellcheck="false" @keydown.enter.prevent="finishCell" @blur="commitCell(scope.row, 'date', $event)">{{ scope.row.date }}</div></template></el-table-column>
          <el-table-column label="地层/恢复压力（MPa）" min-width="175" align="center"><template #default="scope"><div class="grid-cell" contenteditable="true" spellcheck="false" @keydown.enter.prevent="finishCell" @blur="commitCell(scope.row, 'recoveryPressure', $event, true)">{{ scope.row.recoveryPressure }}</div></template></el-table-column>
          <el-table-column label="测试气产量（10⁴m³/d）" min-width="175" align="center"><template #default="scope"><div class="grid-cell" contenteditable="true" spellcheck="false" @keydown.enter.prevent="finishCell" @blur="commitCell(scope.row, 'flowRate', $event, true)">{{ scope.row.flowRate }}</div></template></el-table-column>
          <el-table-column label="测试流压（MPa）" min-width="150" align="center"><template #default="scope"><div class="grid-cell" contenteditable="true" spellcheck="false" @keydown.enter.prevent="finishCell" @blur="commitCell(scope.row, 'flowingPressure', $event, true)">{{ scope.row.flowingPressure }}</div></template></el-table-column>
          <el-table-column label="操作" width="70" align="center"><template #default="scope"><el-button link type="danger" @click="removeRow(scope.$index)">删除</el-button></template></el-table-column>
        </el-table>
      </div>
      <div v-show="activePanel === 'analysis'" class="analysis-view">
        <div class="chart-switch"><label><input type="radio" :checked="activeChart === 'analysis'" @change="switchChart('analysis')" />结果分析图</label>
          <label><input type="radio" :checked="activeChart === 'ipr'" @change="switchChart('ipr')" />IPR曲线</label></div>
        <div ref="chartEl" class="chart" />
      </div>
      <div class="bottom-tabs"><button :class="{ active: activePanel === 'input' }" @click="switchPanel('input')">数据列表</button>
        <button :class="{ active: activePanel === 'analysis' }" :disabled="!currentResult" @click="switchPanel('analysis')">结果分析</button></div>
    </main>
  </section>
</template>

<style lang="scss" scoped>
.modified-workspace{display:flex;height:100%;min-height:0;background:#fff}.params-panel{width:360px;min-width:360px;display:flex;flex-direction:column;border-right:1px solid #ddd}.panel-head{height:34px;padding:0 12px;display:flex;align-items:center;background:#f2f2f2;border-bottom:1px solid #ddd;font-size:13px}.panel-body{flex:1;overflow:auto;padding:10px 14px}.field{display:block;margin-bottom:11px;font-size:12px}.field>span{display:block;margin-bottom:4px}.field select,.field input,.file-button,.inline-output input{width:100%;height:28px;box-sizing:border-box;border:1px solid #aaa;border-radius:3px;background:#fff;padding:0 8px}.file-button{text-align:left;cursor:pointer}.hidden-file{display:none}.field small{display:block;margin-top:4px;overflow:hidden;color:#777;text-overflow:ellipsis;white-space:nowrap}.section-title{display:flex;align-items:center;gap:8px;margin:5px 0 10px;font-size:13px}.section-title i{flex:1;height:1px;background:#999}.radios{margin:0 0 10px;padding:0;border:0;font-size:13px}.radios legend{margin-bottom:6px;padding:0}.radios label{margin-right:12px;white-space:nowrap}.action-buttons{display:flex;gap:8px}.calculate,.save{height:30px;padding:0 24px;border:0;border-radius:3px;color:#fff;cursor:pointer}.calculate{background:#111}.save{background:#409eff}.calculate:disabled,.save:disabled{opacity:.6;cursor:not-allowed}.inline-output{margin-top:14px}.inline-output label{display:block;margin-bottom:10px;color:#555;font-size:12px}.inline-output input{display:block;margin-top:4px;color:#333}.result-area{flex:1;min-width:0;min-height:0;display:flex;flex-direction:column}.editable-data-grid,.analysis-view{flex:1;min-height:0;display:flex;flex-direction:column}.data-toolbar,.chart-switch{height:38px;padding:0 12px;display:flex;align-items:center;gap:14px;flex-shrink:0;border-bottom:1px solid #ddd;color:#666;font-size:12px}.data-toolbar{justify-content:space-between}.chart{flex:1;min-height:0}.grid-cell{min-height:34px;padding:8px 10px;box-sizing:border-box;line-height:18px;text-align:center;outline:none;white-space:nowrap}.grid-cell:focus{padding:7px 9px;border:1px solid #409eff;background:#fff}:deep(.el-table .cell){padding:0;text-align:center}:deep(.el-table th.el-table__cell>.cell){padding:0 10px}:deep(.el-table td.el-table__cell){padding:0;background:#fff}:deep(.el-table__row:hover>td.el-table__cell){background:#fff!important}.bottom-tabs{height:31px;display:flex;flex-shrink:0;border-top:1px solid #ddd}.bottom-tabs button{min-width:110px;border:0;border-right:1px solid #ddd;background:#fff2f4;color:#999;cursor:pointer}.bottom-tabs button.active{color:#222;box-shadow:inset 0 -2px #2b171a;font-weight:600}.bottom-tabs button:disabled{cursor:not-allowed;opacity:.5}
.disabled-option{color:#aaa}
</style>
