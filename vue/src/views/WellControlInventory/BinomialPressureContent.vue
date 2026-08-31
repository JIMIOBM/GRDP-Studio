<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import dockerRequest, { nodeApi, productivityEvaluationApi } from '@/api/docker'
import { NODETYPE } from '@/constants/nodeType'
import {
  fitBackPressureBinomial,
  fitBackPressureExponential,
  solveBackPressureBinomialRate,
  solveBackPressureExponentialRate
} from '@/utils/backPressureCalculation'

const getStoredToken = () => {
  try {
    return JSON.parse(localStorage.getItem('account') || '{}')?.token || ''
  } catch {
    return ''
  }
}

const getDeliverabilityTest = (projectId, gasReservoirId, wellName) =>
  axios.get(
    `/docker-api/projects/${projectId}/gasreservoirs/${gasReservoirId}/wells/${encodeURIComponent(wellName)}/deliverabilitytestdata`,
    {
      params: { page: 1, size: -1 },
      timeout: 30000,
      withCredentials: true,
      headers: {
        'Process-Env': 'prod',
        'X-Project-Id': String(projectId),
        ...(getStoredToken() ? { token: getStoredToken() } : {})
      }
    }
  )

const props = defineProps({
  wellNames: { type: Array, default: () => [] },
  initialWellName: { type: String, default: '' },
  projectId: { type: Number, required: true },
  gasReservoirId: { type: Number, required: true },
  viewKey: { type: [String, Number], default: '' },
  embedded: { type: Boolean, default: false },
  autoSelectData: { type: Boolean, default: false },
  initialTestType: { type: String, default: 'back-pressure' },
  externalFormationPressure: { type: Number, default: null },
  externalTemperature: { type: Number, default: null },
  externalOnePointAlpha: { type: Number, default: 0.25 },
  externalCalculationMethod: { type: String, default: 'pressure' },
  externalCalculationResult: { type: String, default: 'binomial' },
  externalOperationType: { type: String, default: 'production' },
  pvtResultRows: { type: Array, default: () => [] },
  pvtRecord: { type: Object, default: null },
  storedTest: { type: Object, default: null }
})
const emit = defineEmits(['result-change', 'source-input-sync'])
const TEST_TYPES = [
  { value: 'back-pressure', label: '回压' },
  { value: 'one-point', label: '一点法' },
  { value: 'isochronal', label: '等时' },
  { value: 'modified-isochronal', label: '修正等时' }
]
const ATMOSPHERIC_PRESSURE_MPA = 0.101325
const EVALUATION_FORM_BY_METHOD = Object.freeze({
  pressure: 1,
  'pressure-squared': 2,
  'pseudo-pressure': 3
})
const PRESSURE_NODE_TYPE_BY_METHOD = Object.freeze({
  pressure: NODETYPE.NodeType_ProductivityEvaluationByPressure,
  'pressure-squared': NODETYPE.NodeType_ProductivityEvaluationByPressureSquared,
  'pseudo-pressure': NODETYPE.NodeType_ProductivityEvaluationByPseudoPressure
})
const EVALUATION_TYPE_BY_TEST = Object.freeze({
  'one-point': 1,
  'back-pressure': 2
})
const TEST_NODE_TYPE_BY_TEST = Object.freeze({
  'one-point': NODETYPE.NodeType_ProductivityEvaluationOnePointWellTest,
  'back-pressure': NODETYPE.NodeType_ProductivityEvaluationBackPressureWellTest
})
const originalEvaluationCache = new Map()
const normalizeCalculationMethod = value => ({
  '拟压力': 'pseudo-pressure',
  '压力平方方法': 'pressure-squared',
  '压力平方法': 'pressure-squared',
  '压力法': 'pressure'
}[value] || (['pseudo-pressure', 'pressure-squared', 'pressure'].includes(value) ? value : 'pressure'))

const selectorVisible = ref(false)
const selectedWellName = ref(props.initialWellName || props.wellNames[0] || '')
const activeTestType = ref(TEST_TYPES.some(item => item.value === props.initialTestType)
  ? props.initialTestType
  : 'back-pressure')
const isOwnedTestType = () => ['back-pressure', 'one-point'].includes(activeTestType.value)
const selectedPvtTable = ref('')
const selectedDataTable = ref(props.autoSelectData ? activeTestType.value : '')
const sourceRows = ref([])
const inputRows = ref([])
const formationPressure = ref(Number.isFinite(props.externalFormationPressure)
  ? props.externalFormationPressure
  : 56.34)
const temperature = ref(Number.isFinite(props.externalTemperature)
  ? props.externalTemperature
  : 120)
const calculationMethod = ref(normalizeCalculationMethod(props.externalCalculationMethod))
const calculationResultType = ref(props.externalCalculationResult === 'exponential' ? 'exponential' : 'binomial')
const operationType = ref(props.externalOperationType === 'injection' ? 'injection' : 'production')
const loadingData = ref(false)
const calculating = ref(false)
const result = ref(null)
const activePanel = ref('input')
const activeChart = ref('analysis')
const hasMethodData = ref(false)
const chartEl = ref(null)
let chart = null
let originalInputSyncKey = ''

const methodName = computed(
  () => TEST_TYPES.find(item => item.value === activeTestType.value)?.label || '回压'
)
const taskTitle = computed(() => `产能试井-${methodName.value}试井`)
const productivityTableOptions = computed(() => TEST_TYPES.map(item => ({
  value: item.value,
  label: `${selectedWellName.value || '当前井'} ${item.label}试井数据`,
  hasData: sourceRows.value.some(row => row.testType === item.value)
})))
const resultEmptyText = computed(() => {
  const chartName = activeChart.value === 'ipr' ? 'IPR曲线' : '结果分析图'
  return hasMethodData.value
    ? `请先点击“计算”生成${chartName}`
    : `${selectedWellName.value || '当前井'}暂无${methodName.value}试井数据，无法生成${chartName}`
})

const getFieldKey = (field, index) =>
  typeof field === 'string'
    ? field
    : field?.name || field?.key || field?.field || field?.columnName || field?.name_en || field?.name_cn || `field${index}`

const normalizeRawRow = (row, fields = []) => {
  if (Array.isArray(row)) {
    return Object.fromEntries(row.map((value, index) => [getFieldKey(fields[index], index), value]))
  }
  if (!row || typeof row !== 'object') return {}

  const nested = ['values', 'attributes', 'record', 'item', 'row', 'data']
    .map(key => row[key])
    .find(value => value && typeof value === 'object' && !Array.isArray(value))
  return nested ? { ...row, ...nested } : row
}

const unwrapItems = (response) => {
  const queue = [{ value: response, fields: [] }]
  const seen = new Set()
  const listKeys = ['items', 'rows', 'records', 'list', 'content', 'values', 'data']

  while (queue.length) {
    const { value, fields } = queue.shift()
    if (Array.isArray(value)) return value.map(row => normalizeRawRow(row, fields))
    if (!value || typeof value !== 'object' || seen.has(value)) continue
    seen.add(value)

    const nextFields = Array.isArray(value.fields) ? value.fields : fields
    for (const key of listKeys) {
      if (Array.isArray(value[key])) {
        return value[key].map(row => normalizeRawRow(row, nextFields))
      }
    }
    for (const key of ['data', 'result', 'payload', 'page', 'body']) {
      if (value[key] && typeof value[key] === 'object') {
        queue.push({ value: value[key], fields: nextFields })
      }
    }
  }
  return []
}

const normalizeKey = (value) => String(value || '')
  .toLowerCase()
  .replace(/[\s_\-\/()（）·]/g, '')

const readField = (row, candidates) => {
  for (const candidate of candidates) {
    if (row?.[candidate] !== undefined && row?.[candidate] !== null && row?.[candidate] !== '') {
      return row[candidate]
    }
  }
  const normalized = new Map(
    Object.entries(row || {}).map(([key, value]) => [normalizeKey(key), value])
  )
  for (const candidate of candidates) {
    const value = normalized.get(normalizeKey(candidate))
    if (value !== undefined && value !== null && value !== '') return value
  }
  return null
}

const toNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const normalized = typeof value === 'string' ? value.replaceAll(',', '').trim() : value
  const parsed = Number(normalized)
  return Number.isFinite(parsed) ? parsed : null
}

const parseJsonValue = value => {
  if (typeof value !== 'string') return value
  const text = value.trim()
  if (!text || !['{', '['].includes(text[0])) return value
  try {
    return JSON.parse(text)
  } catch {
    return value
  }
}

const findNestedField = (source, fieldName, seen = new Set()) => {
  const value = parseJsonValue(source)
  if (!value || typeof value !== 'object' || seen.has(value)) return null
  seen.add(value)
  if (value[fieldName] !== undefined && value[fieldName] !== null) return value[fieldName]
  for (const child of Object.values(value)) {
    const found = findNestedField(child, fieldName, seen)
    if (found !== null) return found
  }
  return null
}

const normalizeGasType = value => {
  const numeric = toNumber(value)
  if (Number.isInteger(numeric) && numeric >= 0 && numeric <= 2) return numeric
  const text = String(value || '').toLowerCase()
  if (text.includes('湿') || text.includes('wet')) return 1
  if (text.includes('凝析') || text.includes('condensate')) return 2
  return 0
}

const normalizeMethodIndex = (value, methodNames, fallback = 0) => {
  const numeric = toNumber(value)
  if (Number.isInteger(numeric) && numeric >= 0 && numeric < methodNames.length) return numeric
  const text = String(value || '').toLowerCase()
  const index = methodNames.findIndex(name => text.includes(name.toLowerCase()))
  return index >= 0 ? index : fallback
}

const getExactPvtInput = pressure => {
  const record = props.pvtRecord
  const row = record?.gasRows?.[0]
  if (!row) {
    throw new Error('所选PVT表缺少天然气组成数据，无法调用智慧气藏拟压力算法')
  }
  const valueAt = (index, candidates) => Array.isArray(row) ? row[index] : readField(row, candidates)
  const specificGravity = toNumber(valueAt(1, ['specificGravity', 'gasSpecificGravity', '天然气比重']))
  const h2SMoleFraction = toNumber(valueAt(2, ['h2SMoleFraction', 'h2s', 'H2S摩尔百分含量(%)']))
  const co2MoleFraction = toNumber(valueAt(3, ['co2MoleFraction', 'co2', 'CO2摩尔百分含量(%)']))
  const n2MoleFraction = toNumber(valueAt(4, ['n2MoleFraction', 'n2', 'N2摩尔百分含量(%)']))
  if (![specificGravity, h2SMoleFraction, co2MoleFraction, n2MoleFraction].every(Number.isFinite)) {
    throw new Error('所选PVT表天然气组成数据不完整，无法调用智慧气藏拟压力算法')
  }

  const settings = record?.gasSettings || {}
  const modificationValue = readField(settings, [
    'modificationMethod', 'gasCorrectionMethod', 'correctionMethod'
  ])
  const deviationValue = readField(settings, [
    'deviationFactorMethod', 'deviationMethod', 'zFactorMethod'
  ])
  const viscosityValue = readField(settings, ['viscosityMethod', 'gasViscosityMethod'])
  return {
    gasType: normalizeGasType(valueAt(0, ['gasType', 'naturalGasType', '天然气类型'])),
    specificGravity,
    h2SMoleFraction,
    co2MoleFraction,
    n2MoleFraction,
    pressure,
    temperature: Number(temperature.value),
    originalPressure: 40,
    pseudoPressure: 4e-8,
    regularizedPseudoPressure: 40,
    apparentPressure: 40,
    modificationMethod: normalizeMethodIndex(modificationValue, ['Wichert-Aziz', 'Carr-Kobayashi-Burrous']),
    deviationFactorMethod: normalizeMethodIndex(deviationValue, [
      'Dranchuk-Abu-Kassem', 'Dranchuk-Purvis-Robinson', 'Hall-Yarborough'
    ]),
    viscosityMethod: normalizeMethodIndex(viscosityValue, [
      'Lee-Gonzalez-Eakin', 'Carr-Kobayashi-Burrous', 'Sutton'
    ])
  }
}

const extractExactPseudoPressure = response => {
  for (const field of ['outPressure', 'gasPseudoPressure', 'naturalGasPseudoPressure', 'pseudoPressureResult']) {
    const value = toNumber(findNestedField(response, field))
    if (Number.isFinite(value)) return value
  }
  const fallback = toNumber(findNestedField(response, 'pseudoPressure'))
  return Number.isFinite(fallback) && fallback > 1e-6 ? fallback : null
}

const loadExactPseudoPressureRows = async payload => {
  const pressures = new Set([Number(payload.formationPressure), ATMOSPHERIC_PRESSURE_MPA])
  payload.points.forEach(point => {
    pressures.add(Number.isFinite(point.recoveryPressure)
      ? point.recoveryPressure
      : Number(payload.formationPressure))
    if (Number.isFinite(point.flowingPressure)) pressures.add(point.flowingPressure)
  })
  if (payload.operationType === 'injection') {
    const maximumOverpressure = Math.max(0, ...payload.points.map(point => {
      const reservoirPressure = Number.isFinite(point.recoveryPressure)
        ? point.recoveryPressure
        : Number(payload.formationPressure)
      return Number(point.flowingPressure) - reservoirPressure
    }).filter(Number.isFinite))
    pressures.add(Number(payload.formationPressure) + maximumOverpressure)
  }
  const exactPressures = [...pressures].filter(value => Number.isFinite(value) && value > 0)
  const created = await dockerRequest.post('/toolbox', {
    algorithm: 'GasPVT_PseudoPressure',
    projectId: Number(props.projectId)
  }, { silentError: true })
  const toolboxId = toNumber(findNestedField(created?.data, 'id'))
  if (!Number.isFinite(toolboxId)) throw new Error('智慧气藏未返回拟压力工具箱编号')

  const rows = []
  for (const pressure of exactPressures) {
    await dockerRequest.post('/toolbox/calc', {
      id: toolboxId,
      input: JSON.stringify(getExactPvtInput(pressure))
    }, { silentError: true })
    const response = await dockerRequest.get(`/toolbox/${toolboxId}`, { silentError: true })
    const pseudoPressure = extractExactPseudoPressure(response?.data)
    if (!Number.isFinite(pseudoPressure)) {
      throw new Error(`智慧气藏未返回 ${pressure} MPa 对应的气体拟压力`)
    }
    rows.push({ pressure, pseudoPressure })
  }
  return rows
}

const normalizePressure = (value) => {
  const parsed = toNumber(value)
  if (parsed === null) return null
  return Math.abs(parsed) >= 1000 ? parsed / 1e6 : parsed
}

const normalizeGasRate = (value) => toNumber(value)

const normalizeTestType = (value) => {
  const text = String(value || '').trim().toLowerCase()
  if (!text) return ''
  if (text.includes('修正等时') || text.includes('modified')) return 'modified-isochronal'
  if (text.includes('等时') || text.includes('isochronal')) return 'isochronal'
  if (text.includes('一点') || text.includes('one')) return 'one-point'
  if (text.includes('回压') || text.includes('back')) return 'back-pressure'
  return ''
}

const formatDate = (value) => {
  if (value === null || value === undefined || value === '') return ''
  const pad = part => String(part).padStart(2, '0')

  if (Array.isArray(value) && value.length >= 3) {
    return `${value[0]}-${pad(value[1])}-${pad(value[2])}`
  }

  const numeric = Number(value)
  if (Number.isFinite(numeric) && String(value).trim() !== '') {
    if (numeric >= 20000 && numeric <= 80000) {
      const excelDate = new Date(Date.UTC(1899, 11, 30) + numeric * 86400000)
      return `${excelDate.getUTCFullYear()}-${pad(excelDate.getUTCMonth() + 1)}-${pad(excelDate.getUTCDate())}`
    }
    if (numeric > 1000000000) {
      const timestamp = numeric < 100000000000 ? numeric * 1000 : numeric
      const date = new Date(timestamp)
      if (!Number.isNaN(date.getTime())) {
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
      }
    }
  }

  const text = String(value).trim()
  const matched = text.match(/^(\d{4})[-/.\u5e74](\d{1,2})[-/.\u6708](\d{1,2})/)
  if (matched) return `${matched[1]}-${pad(matched[2])}-${pad(matched[3])}`
  const compact = text.match(/^(\d{4})(\d{2})(\d{2})$/)
  if (compact) return `${compact[1]}-${compact[2]}-${compact[3]}`

  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const year = date.getFullYear()
  return `${year}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const normalizeSourceRow = (row, index) => ({
  sequence: toNumber(readField(row, [
    'testPointNumber', 'test_point_number', 'pointNumber', 'sequence', 'seq', '测点序号', '序号'
  ])) ?? index + 1,
  wellName: String(readField(row, ['wellName', 'well_name', '井名']) || selectedWellName.value),
  date: formatDate(readField(row, [
    'productivityWellTestDate', 'productivity_well_test_date', 'wellTestDate', 'testDate', 'date', '产能试井日期', '日期'
  ])),
  testType: normalizeTestType(readField(row, [
    'productivityWellTestType', 'productivity_well_test_type', 'deliverabilityTestType', 'testType', 'testMethod', '产能试井类型', '试井类型'
  ])),
  recoveryPressure: normalizePressure(readField(row, [
    'reserviorPressure', 'reservior_pressure', 'reservoirPressure', 'reservoir_pressure',
    'recoveryPressure', 'recovery_pressure', 'formationPressure', 'originalFormationPressure',
    '地层/恢复压力', '地层压力', '恢复压力'
  ])),
  flowRate: normalizeGasRate(readField(row, [
    'testDailyGasProduction', 'test_daily_gas_production', 'dailyGasProduction',
    'gasProduction', 'gasRate', 'flowRate', 'qsc', '测试气产量', '气产量'
  ])),
  equivalentFlowRate: normalizeGasRate(readField(row, [
    'equivalentTestDailyGasProduction', 'equivalent_test_daily_gas_production',
    'equivalentDailyGasProduction', 'equivalentGasRate', 'equivalentFlowRate',
    '折算测试气产量', '折算气产量'
  ])),
  flowingPressure: normalizePressure(readField(row, [
    'testBottomHoleFlowingPressure', 'test_bottom_hole_flowing_pressure',
    'bottomHoleFlowingPressure', 'bottomholeFlowingPressure', 'flowingPressure',
    'flowPressure', 'pwf', '测试流压', '井底流压'
  ])),
  temperature: toNumber(readField(row, [
    'formationTemperature', 'reservoirTemperature', 'gasReservoirTemperature',
    'temperature', '地层温度', '气藏温度'
  ]))
})

const createBlankRow = (sequence) => ({
  sequence,
  date: '',
  testType: activeTestType.value,
  recoveryPressure: null,
  flowRate: null,
  equivalentFlowRate: null,
  flowingPressure: null
})

const applySourceRows = () => {
  originalInputSyncKey = ''
  const matching = sourceRows.value.filter(row => row.testType === activeTestType.value)
  hasMethodData.value = matching.length > 0
  inputRows.value = matching.map((row, index) => ({
    ...row,
    sequence: row.sequence ?? index + 1
  }))
  if (!inputRows.value.length) {
    const count = activeTestType.value === 'one-point' ? 1 : 4
    inputRows.value = Array.from({ length: count }, (_, index) => createBlankRow(index + 1))
  }

  const pressure = inputRows.value
    .map(row => row.recoveryPressure)
    .filter(value => Number.isFinite(value) && value > 0)
  if (pressure.length && !(props.embedded && Number.isFinite(props.externalFormationPressure))) {
    formationPressure.value = Math.max(...pressure)
  }
  const rowTemperature = inputRows.value.find(row => Number.isFinite(row.temperature))?.temperature
  if (Number.isFinite(rowTemperature) && !(props.embedded && Number.isFinite(props.externalTemperature))) {
    temperature.value = rowTemperature
  }
  result.value = null
  activePanel.value = 'input'
  activeChart.value = 'analysis'
}

const clearWorkspace = () => {
  selectedPvtTable.value = ''
  selectedDataTable.value = ''
  inputRows.value = []
  hasMethodData.value = false
  result.value = null
  activePanel.value = 'input'
  activeChart.value = 'analysis'
  formationPressure.value = Number.isFinite(props.externalFormationPressure)
    ? props.externalFormationPressure
    : 56.34
  temperature.value = Number.isFinite(props.externalTemperature)
    ? props.externalTemperature
    : 120
}

const loadWellData = async () => {
  if (!selectedWellName.value) return
  loadingData.value = true
  try {
    const response = await getDeliverabilityTest(
      props.projectId,
      props.gasReservoirId,
      selectedWellName.value
    )
    sourceRows.value = unwrapItems(response).map(normalizeSourceRow)
    if (selectedDataTable.value) applySourceRows()
    if (!sourceRows.value.length) {
      ElMessage.info(`${selectedWellName.value}暂无产能测试数据`)
    }
  } catch (error) {
    sourceRows.value = []
    if (selectedDataTable.value) applySourceRows()
    ElMessage.warning('产能测试数据读取失败')
    console.warn('读取产能测试数据失败', error)
  } finally {
    loadingData.value = false
  }
}

const confirmSelection = async () => {
  if (!selectedWellName.value) {
    ElMessage.warning('请选择一口井')
    return
  }
  selectorVisible.value = false
  await loadWellData()
}

const addRow = () => {
  inputRows.value.push(createBlankRow(inputRows.value.length + 1))
}

const replaceInputRows = rows => {
  inputRows.value = (rows || []).map((row, index) => ({
    sequence: Number(row.testPointNumber ?? row.sequence ?? index + 1),
    date: formatDate(row.testDate ?? row.date),
    testType: activeTestType.value,
    recoveryPressure: normalizePressure(row.reservoirPressure ?? row.reserviorPressure ?? row.recoveryPressure),
    flowRate: normalizeGasRate(row.testDailyGasProduction ?? row.gasProduction ?? row.flowRate),
    equivalentFlowRate: normalizeGasRate(row.equivalentTestDailyGasProduction ?? row.equivalentFlowRate),
    flowingPressure: normalizePressure(row.testFlowPressure ?? row.flowingPressure ?? row.flowPressure)
  }))
  hasMethodData.value = inputRows.value.length > 0
  result.value = null
  activePanel.value = 'input'
  activeChart.value = 'analysis'
}

const removeRow = (index) => {
  inputRows.value.splice(index, 1)
  inputRows.value.forEach((row, rowIndex) => {
    row.sequence = rowIndex + 1
  })
}

const reloadSourceRows = () => {
  if (!selectedDataTable.value) {
    ElMessage.warning('请先选择产能表')
    return
  }
  if (!sourceRows.value.length) {
    loadWellData()
    return
  }
  applySourceRows()
  ElMessage.success('已恢复该井的产能测试数据')
}

const buildPayload = () => ({
  wellName: selectedWellName.value,
  testType: activeTestType.value,
  formationPressure: Number(formationPressure.value),
  temperature: Number(temperature.value),
  calculationMethod: calculationMethod.value,
  calculationResultType: calculationResultType.value,
  operationType: operationType.value,
  onePointAlpha: Number(props.externalOnePointAlpha),
  pvtResultRows: props.pvtResultRows,
  migrationNonDarcyCoefficient: null,
  points: inputRows.value.map((row, index) => ({
    sequence: Number(row.sequence || index + 1),
    flowRate: row.flowRate === '' || row.flowRate === null ? null : Number(row.flowRate),
    equivalentFlowRate: row.equivalentFlowRate === '' || row.equivalentFlowRate === null
      ? null
      : Number(row.equivalentFlowRate),
    flowingPressure: row.flowingPressure === '' || row.flowingPressure === null
      ? null
      : Number(row.flowingPressure),
    recoveryPressure: row.recoveryPressure === '' || row.recoveryPressure === null
      ? null
      : Number(row.recoveryPressure)
  })),
  migrationPoints: activeTestType.value === 'one-point'
    ? sourceRows.value
      .filter(row => row.testType === 'back-pressure')
      .map((row, index) => ({
        sequence: Number(row.sequence || index + 1),
        flowRate: row.flowRate,
        flowingPressure: row.flowingPressure,
        recoveryPressure: row.recoveryPressure
      }))
    : null
})

const getCalculationPayload = (response) => {
  const queue = [response]
  const seen = new Set()
  const resultFields = [
    'darcyCoefficient', 'darcy_coefficient', 'coefficientA', 'coefficient_a', 'A',
    'nonDarcyCoefficient', 'non_darcy_coefficient', 'coefficientB', 'coefficient_b', 'B',
    'analysisPoints', 'analysis_points', 'iprCurve', 'ipr_curve', 'iprCurves', 'ipr_curves'
  ]
  let fallback = null

  while (queue.length) {
    const value = queue.shift()
    if (!value || typeof value !== 'object' || Array.isArray(value) || seen.has(value)) continue
    seen.add(value)
    fallback ||= value
    if (readField(value, resultFields) !== null) return value

    for (const key of ['data', 'result', 'payload', 'resultData', 'analysisResult', 'body']) {
      if (value[key] && typeof value[key] === 'object') queue.push(value[key])
    }
  }
  return fallback || {}
}

const normalizeAnalysisPoint = (point, index) => ({
  sequence: toNumber(readField(point, ['sequence', 'seq', 'index', 'testPointNumber'])) ?? index + 1,
  flowRate: toNumber(readField(point, ['flowRate', 'flow_rate', 'gasRate', 'qsc', 'x', 'xValue'])),
  transformedPressure: toNumber(readField(point, [
    'transformedPressure', 'transformed_pressure', 'pressureDifferencePerRate',
    'deltaPressurePerFlowRate', 'y', 'yValue', 'value'
  ])),
  sourcePressure: normalizePressure(readField(point, [
    'sourcePressure', 'source_pressure', 'recoveryPressure', 'formationPressure', 'reservoirPressure'
  ]))
})

const normalizeLinePoint = point => ({
  flowRate: toNumber(readField(point, ['flowRate', 'flow_rate', 'gasRate', 'qsc', 'x', 'xValue'])),
  transformedPressure: toNumber(readField(point, [
    'transformedPressure', 'transformed_pressure', 'pressureDifferencePerRate',
    'deltaPressurePerFlowRate', 'y', 'yValue', 'value'
  ]))
})

const normalizeIprPoint = point => ({
  flowRate: toNumber(readField(point, ['flowRate', 'flow_rate', 'gasRate', 'qsc', 'x', 'xValue'])),
  flowingPressure: normalizePressure(readField(point, [
    'flowingPressure', 'flowing_pressure', 'bottomHoleFlowingPressure', 'pwf', 'y', 'yValue', 'value'
  ]))
})

const validPointList = (value, normalizer) =>
  (Array.isArray(value) ? value : [])
    .map(normalizer)
    .filter(point => Number.isFinite(point.flowRate))

const normalizePvtCurve = rows => {
  const byPressure = new Map()
  ;(Array.isArray(rows) ? rows : []).forEach(row => {
    const pressure = toNumber(Array.isArray(row)
      ? row[0]
      : readField(row, ['pressure', 'formationPressure', 'reservoirPressure', '压力', '压力(MPa)']))
    const pseudoPressure = toNumber(Array.isArray(row)
      ? row[3]
      : readField(row, [
          'pseudoPressure', 'pseudo_pressure', 'gasPseudoPressure', 'mP', 'mp',
          '气体拟压力', '气体拟压力(MPa²/(mPa·s))'
        ]))
    if (Number.isFinite(pressure) && pressure >= 0 && Number.isFinite(pseudoPressure)) {
      byPressure.set(pressure, { pressure, pseudoPressure })
    }
  })
  if (!byPressure.has(0)) byPressure.set(0, { pressure: 0, pseudoPressure: 0 })
  return [...byPressure.values()].sort((left, right) => left.pressure - right.pressure)
}

const interpolatePseudoPressure = (pressure, curve) => {
  if (!Number.isFinite(pressure) || pressure < 0) throw new Error('压力数据必须是非负有效数值')
  if (!Array.isArray(curve) || curve.length < 2) {
    throw new Error('所选PVT表暂无气体拟压力数据，请先完成天然气PVT计算或导入结果')
  }
  const last = curve[curve.length - 1]
  if (pressure > last.pressure + 1e-9) {
    throw new Error(`所选PVT表拟压力范围仅到 ${last.pressure} MPa，不能计算 ${pressure} MPa`)
  }
  const exact = curve.find(point => Math.abs(point.pressure - pressure) <= 1e-9)
  if (exact) return exact.pseudoPressure
  const upperIndex = curve.findIndex(point => point.pressure > pressure)
  const lower = curve[Math.max(0, upperIndex - 1)]
  const upper = curve[upperIndex]
  if (!lower || !upper || upper.pressure === lower.pressure) {
    throw new Error(`PVT拟压力数据无法覆盖 ${pressure} MPa`)
  }

  // PVT结果是按压力间隔输出的离散点；使用局部平滑插值还原任意井底流压处的拟压力。
  const sampleCount = Math.min(5, curve.length)
  const startIndex = Math.max(0, Math.min(upperIndex - 2, curve.length - sampleCount))
  const samples = curve.slice(startIndex, startIndex + sampleCount)
  const interpolated = samples.reduce((sum, point, pointIndex) => {
    const weight = samples.reduce((value, other, otherIndex) => {
      if (pointIndex === otherIndex) return value
      return value * (pressure - other.pressure) / (point.pressure - other.pressure)
    }, 1)
    return sum + point.pseudoPressure * weight
  }, 0)
  const segmentMinimum = Math.min(lower.pseudoPressure, upper.pseudoPressure)
  const segmentMaximum = Math.max(lower.pseudoPressure, upper.pseudoPressure)
  if (Number.isFinite(interpolated) &&
      interpolated >= segmentMinimum - 1e-6 && interpolated <= segmentMaximum + 1e-6) {
    return interpolated
  }

  const ratio = (pressure - lower.pressure) / (upper.pressure - lower.pressure)
  return lower.pseudoPressure + ratio * (upper.pseudoPressure - lower.pseudoPressure)
}

const pressurePotential = (pressure, method, pvtCurve = []) => {
  if (method === 'pressure-squared') return pressure ** 2
  if (method === 'pseudo-pressure') return interpolatePseudoPressure(pressure, pvtCurve)
  return pressure
}

const pressureExpression = (method, selectedOperationType = 'production') => {
  const injection = selectedOperationType === 'injection'
  return ({
    'pseudo-pressure': injection ? 'm(Pwf) - m(Pr)' : 'm(Pr) - m(Pwf)',
    'pressure-squared': injection ? 'Pwf² - Pr²' : 'Pr² - Pwf²',
    pressure: injection ? 'Pwf - Pr' : 'Pr - Pwf'
  }[method] || (injection ? 'Pwf - Pr' : 'Pr - Pwf'))
}

const analysisAxisName = (method, selectedOperationType = 'production') =>
    `[${pressureExpression(method, selectedOperationType)}] / qsc ${
        isOwnedTestType() ? `(${({
          'pseudo-pressure': '(MPa²/(mPa·s))/(10⁴m³/d)',
          'pressure-squared': 'MPa²/(10⁴m³/d)',
          pressure: 'MPa/(10⁴m³/d)'
        }[method] || 'MPa/(10⁴m³/d)')})` : `[${({
          'pseudo-pressure': '(MPa²/(mPa·s))/(10⁴m³/d)',
          'pressure-squared': 'MPa²/(10⁴m³/d)',
          pressure: 'MPa/(10⁴m³/d)'
        }[method] || 'MPa/(10⁴m³/d)')}]`
    }`

const exponentialAnalysisAxisName = (method, selectedOperationType = 'production') =>
  `ln(${pressureExpression(method, selectedOperationType)})\n${({
    'pseudo-pressure': '(MPa²/(mPa·s))',
    'pressure-squared': '(MPa²)',
    pressure: '(MPa)'
  }[method] || '(MPa)')}`

const equationCoefficient = value => Number(value).toExponential(4).replace('e', 'E')
const coefficientEquation = (method, darcy, nonDarcy, selectedOperationType = 'production') =>
  isOwnedTestType()
    ? `${pressureExpression(method)} = ${equationCoefficient(darcy)} qsc + ${equationCoefficient(nonDarcy)} qsc²`
    : `${pressureExpression(method, selectedOperationType)} = ${darcy.toPrecision(6)} × qsc + ${nonDarcy.toPrecision(6)} × qsc²`

const exponentialEquation = (method, coefficient, exponent, selectedOperationType = 'production') =>
  `qsc = ${coefficient.toPrecision(6)} × [${pressureExpression(method, selectedOperationType)}]^${exponent.toPrecision(6)}`

const solveBinomialFlowRate = (drawdown, darcy, nonDarcy) => {
  return solveBackPressureBinomialRate(drawdown, darcy, nonDarcy)
}

const createIprCurve = (
  pressure,
  darcy,
  nonDarcy,
  method = 'pressure',
  pvtCurve = [],
  selectedOperationType = 'production',
  maximumInjectionPressure = pressure
) => {
  if (![pressure, darcy, nonDarcy].every(Number.isFinite)) return []
  const injection = selectedOperationType === 'injection'
  return Array.from({ length: 41 }, (_, index) => {
    const minimumPressure = Math.min(pressure, ATMOSPHERIC_PRESSURE_MPA)
    const flowingPressure = injection
      ? pressure + (Math.max(pressure, maximumInjectionPressure) - pressure) * index / 40
      : pressure - (pressure - minimumPressure) * index / 40
    const reservoirPotential = pressurePotential(pressure, method, pvtCurve)
    const flowingPotential = pressurePotential(flowingPressure, method, pvtCurve)
    const drawdown = injection
      ? flowingPotential - reservoirPotential
      : reservoirPotential - flowingPotential
    const flowRate = solveBinomialFlowRate(drawdown, darcy, nonDarcy)
    return { flowRate, flowingPressure }
  })
}

const createExponentialIprCurve = (
  pressure,
  coefficient,
  exponent,
  method = 'pressure',
  pvtCurve = [],
  selectedOperationType = 'production',
  maximumInjectionPressure = pressure
) => {
  if (![pressure, coefficient, exponent].every(Number.isFinite)) return []
  const injection = selectedOperationType === 'injection'
  return Array.from({ length: 41 }, (_, index) => {
    const minimumPressure = Math.min(pressure, ATMOSPHERIC_PRESSURE_MPA)
    const flowingPressure = injection
      ? pressure + (Math.max(pressure, maximumInjectionPressure) - pressure) * index / 40
      : pressure - (pressure - minimumPressure) * index / 40
    const reservoirPotential = pressurePotential(pressure, method, pvtCurve)
    const flowingPotential = pressurePotential(flowingPressure, method, pvtCurve)
    const drawdown = injection
      ? flowingPotential - reservoirPotential
      : reservoirPotential - flowingPotential
    return {
      flowRate: solveBackPressureExponentialRate(drawdown, coefficient, exponent),
      flowingPressure
    }
  })
}

const createFormationPressureSeries = maximumPressure =>
  Array.from({ length: 10 }, (_, index) => maximumPressure - maximumPressure / 10 * index)
    .filter(pressure => Number.isFinite(pressure) && pressure > 0)

const normalizeLocalPoints = (
  points,
  fallbackPressure,
  minimum,
  method = 'pressure',
  pvtCurve = [],
  selectedOperationType = 'production'
) => {
  const injection = selectedOperationType === 'injection'
  const normalized = (Array.isArray(points) ? points : [])
    .filter(point => point && point.flowRate !== null && point.flowRate !== '' &&
      point.flowingPressure !== null && point.flowingPressure !== '')
    .map((point, index) => {
      const flowRate = Number(point.flowRate)
      const flowingPressure = Number(point.flowingPressure)
      const recoveryPressure = point.recoveryPressure === null || point.recoveryPressure === ''
        ? Number(fallbackPressure)
        : Number(point.recoveryPressure)
      if (!Number.isFinite(flowRate) || flowRate <= 0) throw new Error('测试气产量必须大于 0')
      if (![flowingPressure, recoveryPressure].every(Number.isFinite)) throw new Error('压力数据必须是有效数值')
      if (!injection && recoveryPressure <= flowingPressure) {
        throw new Error('采气时地层/恢复压力必须大于测试流压')
      }
      if (injection && flowingPressure <= recoveryPressure) {
        throw new Error('注气时测试流压必须大于地层/恢复压力')
      }
      const reservoirPotential = pressurePotential(recoveryPressure, method, pvtCurve)
      const flowingPotential = pressurePotential(flowingPressure, method, pvtCurve)
      const potentialDifference = injection
        ? flowingPotential - reservoirPotential
        : reservoirPotential - flowingPotential
      return {
        sequence: Number(point.sequence || index + 1),
        flowRate,
        flowingPressure,
        recoveryPressure,
        equivalentFlowRate: point.equivalentFlowRate === null || point.equivalentFlowRate === ''
          ? null
          : Number(point.equivalentFlowRate),
        potentialDifference,
        transformedPressure: potentialDifference / flowRate,
        sourcePressure: recoveryPressure
      }
    })
    .sort((left, right) => left.sequence - right.sequence)
  if (normalized.length < minimum) throw new Error(`${methodName.value}至少需要 ${minimum} 个有效测试点`)
  return normalized
}

const regressLocalPoints = (points) => {
  if (activeTestType.value === 'back-pressure') {
    const fitted = fitBackPressureBinomial(points)
    return {
      darcyCoefficient: fitted.darcyCoefficient,
      nonDarcyCoefficient: fitted.nonDarcyCoefficient,
      rSquared: fitted.rSquared,
      transientDarcyCoefficient: null
    }
  }
  const meanX = points.reduce((sum, point) => sum + point.flowRate, 0) / points.length
  const meanY = points.reduce((sum, point) => sum + point.transformedPressure, 0) / points.length
  const sxx = points.reduce((sum, point) => sum + (point.flowRate - meanX) ** 2, 0)
  if (sxx <= 1e-12) throw new Error('测试气产量不能全部相同')
  const sxy = points.reduce(
    (sum, point) => sum + (point.flowRate - meanX) * (point.transformedPressure - meanY),
    0
  )
  const nonDarcyCoefficient = sxy / sxx
  const darcyCoefficient = meanY - nonDarcyCoefficient * meanX
  if (darcyCoefficient < 0 || nonDarcyCoefficient < 0) {
    throw new Error('测试点不能得到有效的二项式系数，请检查压力与产量数据')
  }
  const total = points.reduce((sum, point) => sum + (point.transformedPressure - meanY) ** 2, 0)
  const residual = points.reduce((sum, point) => {
    const predicted = darcyCoefficient + nonDarcyCoefficient * point.flowRate
    return sum + (point.transformedPressure - predicted) ** 2
  }, 0)
  const rSquared = total <= 1e-12 ? 1 : Math.max(0, Math.min(1, 1 - residual / total))
  return { darcyCoefficient, nonDarcyCoefficient, rSquared, transientDarcyCoefficient: null }
}

const regressExponentialPoints = points => {
  if (activeTestType.value === 'back-pressure') {
    return fitBackPressureExponential(points)
  }
  const logarithmicPoints = points.map(point => {
    if (!Number.isFinite(point.potentialDifference) || point.potentialDifference <= 0) {
      throw new Error('压力函数差必须大于 0')
    }
    return {
      ...point,
      logDrawdown: Math.log(point.potentialDifference),
      logFlowRate: Math.log(point.flowRate)
    }
  })
  const meanX = logarithmicPoints.reduce((sum, point) => sum + point.logDrawdown, 0) /
    logarithmicPoints.length
  const meanY = logarithmicPoints.reduce((sum, point) => sum + point.logFlowRate, 0) /
    logarithmicPoints.length
  const sxx = logarithmicPoints.reduce(
    (sum, point) => sum + (point.logDrawdown - meanX) ** 2,
    0
  )
  if (sxx <= 1e-12) throw new Error('测试点的压力函数差不能全部相同')
  const sxy = logarithmicPoints.reduce(
    (sum, point) => sum + (point.logDrawdown - meanX) * (point.logFlowRate - meanY),
    0
  )
  const productivityExponent = sxy / sxx
  const productivityCoefficient = Math.exp(meanY - productivityExponent * meanX)
  if (![productivityCoefficient, productivityExponent].every(Number.isFinite) ||
      productivityCoefficient <= 0 || productivityExponent <= 0) {
    throw new Error('测试点不能得到有效的指数式系数，请检查压力与产量数据')
  }
  const total = logarithmicPoints.reduce(
    (sum, point) => sum + (point.logFlowRate - meanY) ** 2,
    0
  )
  const residual = logarithmicPoints.reduce((sum, point) => {
    const predicted = Math.log(productivityCoefficient) +
      productivityExponent * point.logDrawdown
    return sum + (point.logFlowRate - predicted) ** 2
  }, 0)
  const rSquared = total <= 1e-12 ? 1 : Math.max(0, Math.min(1, 1 - residual / total))
  return { productivityCoefficient, productivityExponent, rSquared }
}

const solveOnePointExponent = (alpha, potentialRatio) => {
  let exponent = 0.75
  for (let index = 0; index < 100; index += 1) {
    const rateRatio = potentialRatio ** exponent
    const ratio = alpha / ((1 - alpha) * rateRatio)
    const nextExponent = (ratio + 1) / (ratio + 2)
    if (Math.abs(nextExponent - exponent) < 1e-10) return nextExponent
    exponent = nextExponent
  }
  return exponent
}

const calculateLocally = (payload) => {
  const maximumPressure = Number(payload.formationPressure)
  if (!Number.isFinite(maximumPressure) || maximumPressure <= 0) {
    throw new Error('计算IPR曲线的最大地层压力必须大于 0')
  }
  const selectedCalculationMethod = normalizeCalculationMethod(payload.calculationMethod)
  const selectedOperationType = payload.operationType === 'injection' ? 'injection' : 'production'
  if (selectedOperationType === 'injection' && payload.testType !== 'isochronal') {
    throw new Error('当前注气计算仅支持等时试井')
  }
  const pvtCurve = normalizePvtCurve(payload.pvtResultRows)
  const minimum = payload.testType === 'one-point' ? 1 : 2
  const points = normalizeLocalPoints(
    payload.points,
    payload.formationPressure,
    minimum,
    selectedCalculationMethod,
    pvtCurve,
    selectedOperationType
  )
  const maximumObservedInjectionOverpressure = Math.max(
    0,
    ...points.map(point => point.flowingPressure - point.recoveryPressure).filter(Number.isFinite)
  )
  const maximumInjectionPressure = maximumPressure + maximumObservedInjectionOverpressure
  let coefficients
  let analysisPoints = points
  let aofRate = null

  if (payload.testType === 'one-point' && payload.calculationResultType === 'exponential') {
    const point = points[0]
    const alpha = Number(payload.onePointAlpha)
    if (!Number.isFinite(alpha) || alpha <= 0 || alpha >= 1) {
      throw new Error('一点法产能系数 α 必须大于 0 且小于 1')
    }
    const maximumPotential = pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve) -
      pressurePotential(0, selectedCalculationMethod, pvtCurve)
    if (!Number.isFinite(maximumPotential) || maximumPotential <= 0 || point.potentialDifference <= 0) {
      throw new Error('计算指数式所需的压力函数值无效')
    }
    const potentialRatio = point.potentialDifference / maximumPotential
    if (potentialRatio <= 0 || potentialRatio >= 1) {
      throw new Error('一点法实测压力函数差必须小于最大压力函数差')
    }
    const productivityExponent = solveOnePointExponent(alpha, potentialRatio)
    const productivityCoefficient = point.flowRate / point.potentialDifference ** productivityExponent
    aofRate = productivityCoefficient * maximumPotential ** productivityExponent
    if (![productivityCoefficient, productivityExponent, aofRate].every(Number.isFinite) ||
        productivityCoefficient <= 0 || productivityExponent <= 0 || aofRate <= 0) {
      throw new Error('一点法迁移计算未得到有效的指数式系数')
    }

    analysisPoints = points.map(item => ({
      ...item,
      transformedPressure: item.potentialDifference
    }))
    const lineStart = Math.max(aofRate / 100, point.flowRate / 10)
    const lineEnd = aofRate * 1.02
    const regressionLine = Array.from({ length: 41 }, (_, index) => {
      const flowRate = lineStart + (lineEnd - lineStart) * index / 40
      return {
        flowRate,
        transformedPressure: (flowRate / productivityCoefficient) ** (1 / productivityExponent)
      }
    })
    const makeIprCurve = pressure => createExponentialIprCurve(
      pressure,
      productivityCoefficient,
      productivityExponent,
      selectedCalculationMethod,
      pvtCurve,
      selectedOperationType,
      maximumInjectionPressure
    )
    const iprCurve = makeIprCurve(maximumPressure)
    const iprCurves = createFormationPressureSeries(maximumPressure)
      .map(pressure => ({ formationPressure: pressure, points: makeIprCurve(pressure) }))

    return {
      wellName: payload.wellName,
      testType: payload.testType,
      methodName: methodName.value,
      calculationMethod: selectedCalculationMethod,
      calculationResultType: 'exponential',
      operationType: selectedOperationType,
      formationPressure: maximumPressure,
      productivityCoefficient,
      productivityExponent,
      aofRate,
      rSquared: null,
      reliability: '',
      equation: exponentialEquation(
        selectedCalculationMethod,
        productivityCoefficient,
        productivityExponent,
        selectedOperationType
      ),
      analysisPoints,
      regressionLine,
      transientLine: [],
      iprCurve,
      iprCurves
    }
  }

  if (payload.calculationResultType === 'exponential') {
    let regressionPoints = points
    let stablePoint = null
    if (payload.testType === 'isochronal') {
      // 与原系统一致：等时试井最后一个测试点为延长稳定点，其余点用于确定指数 n。
      stablePoint = points[points.length - 1]
      regressionPoints = points.slice(0, -1)
      if (regressionPoints.length < 2) throw new Error('等时试井至少需要 3 个测试点')
    }
    const exponentialCoefficients = regressExponentialPoints(regressionPoints)
    const productivityExponent = exponentialCoefficients.productivityExponent
    const transientProductivityCoefficient = stablePoint
      ? exponentialCoefficients.productivityCoefficient
      : null
    const productivityCoefficient = stablePoint
      ? stablePoint.flowRate / stablePoint.potentialDifference ** productivityExponent
      : exponentialCoefficients.productivityCoefficient
    const rSquared = exponentialCoefficients.rSquared
    if (!Number.isFinite(productivityCoefficient) || productivityCoefficient <= 0) {
      throw new Error('稳定点不能得到有效的指数式产能系数，请检查最后一个测试点')
    }
    const atmosphericPressure = Math.min(maximumPressure, ATMOSPHERIC_PRESSURE_MPA)
    const maximumPotential = selectedOperationType === 'injection'
      ? pressurePotential(maximumInjectionPressure, selectedCalculationMethod, pvtCurve) -
        pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve)
      : pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve) -
        pressurePotential(atmosphericPressure, selectedCalculationMethod, pvtCurve)
    aofRate = productivityCoefficient * maximumPotential ** productivityExponent
    if (!Number.isFinite(aofRate) || aofRate <= 0) {
      throw new Error(selectedOperationType === 'injection'
        ? '指数式计算未得到有效的最大注气量'
        : '指数式计算未得到有效的无阻流量')
    }
    analysisPoints = points.map(point => ({
      ...point,
      transformedPressure: point.potentialDifference
    }))
    const minimumRate = Math.min(...points.map(point => point.flowRate))
    const maximumRate = Math.max(...points.map(point => point.flowRate), aofRate)
    const lineStart = Math.max(minimumRate / 1.25, Number.MIN_VALUE)
    const lineEnd = maximumRate * 1.02
    const rateRatio = lineEnd / lineStart
    const makeRegressionLine = coefficient => Array.from({ length: 41 }, (_, index) => {
      const flowRate = lineStart * rateRatio ** (index / 40)
      return {
        flowRate,
        transformedPressure: (flowRate / coefficient) ** (1 / productivityExponent)
      }
    })
    const regressionLine = makeRegressionLine(productivityCoefficient)
    const transientLine = transientProductivityCoefficient === null
      ? []
      : makeRegressionLine(transientProductivityCoefficient)
    const makeIprCurve = pressure => createExponentialIprCurve(
      pressure,
      productivityCoefficient,
      productivityExponent,
      selectedCalculationMethod,
      pvtCurve,
      selectedOperationType,
      maximumInjectionPressure
    )
    const iprCurve = makeIprCurve(maximumPressure)
    const iprCurves = createFormationPressureSeries(maximumPressure)
      .map(pressure => ({ formationPressure: pressure, points: makeIprCurve(pressure) }))
    return {
      wellName: payload.wellName,
      testType: payload.testType,
      methodName: methodName.value,
      calculationMethod: selectedCalculationMethod,
      calculationResultType: 'exponential',
      operationType: selectedOperationType,
      formationPressure: maximumPressure,
      productivityCoefficient,
      productivityExponent,
      aofRate,
      rSquared,
      reliability: rSquared >= 0.9
        ? '分析结果可靠性较高'
        : rSquared >= 0.7
          ? '分析结果可靠性一般'
          : '分析结果可靠性偏低',
      equation: exponentialEquation(
        selectedCalculationMethod,
        productivityCoefficient,
        productivityExponent,
        selectedOperationType
      ),
      analysisPoints,
      regressionLine,
      transientLine,
      iprCurve,
      iprCurves
    }
  }

  if (payload.testType === 'one-point') {
    const point = points[0]
    const alpha = Number(payload.onePointAlpha)
    if (!Number.isFinite(alpha) || alpha <= 0 || alpha >= 1) {
      throw new Error('一点法产能系数 α 必须大于 0 且小于 1')
    }
    const rawMaximumPotential = pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve) -
      pressurePotential(0, selectedCalculationMethod, pvtCurve)
    // 智慧气藏的一点法在拟压力形式下先取整最大拟压力，再将 AOF 保留 3 位。
    // 压力、压力平方形式计算 AOF 时以 0 MPa 为终点，但迁移回归点以标准大气压为终点。
    const maximumPotential = selectedCalculationMethod === 'pseudo-pressure'
      ? Math.trunc(rawMaximumPotential)
      : rawMaximumPotential
    if (!Number.isFinite(maximumPotential) || maximumPotential <= 0) {
      throw new Error('计算 AOF 所需的压力函数值无效')
    }
    const darcyRatio = alpha / (1 - alpha)
    const potentialRatio = maximumPotential / point.transformedPressure
    const discriminant = (potentialRatio * darcyRatio) ** 2 +
      4 * (darcyRatio + 1) * potentialRatio * point.flowRate
    const rawAofRate = (potentialRatio * darcyRatio + Math.sqrt(discriminant)) /
      (2 * (darcyRatio + 1))
    aofRate = Number(rawAofRate.toFixed(
      selectedCalculationMethod === 'pseudo-pressure' ? 3 : 4
    ))
    const migratedAofRate = selectedCalculationMethod === 'pseudo-pressure'
      ? aofRate
      : rawAofRate
    const migratedPotential = selectedCalculationMethod === 'pseudo-pressure'
      ? maximumPotential
      : pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve) -
        pressurePotential(0.101325, selectedCalculationMethod, pvtCurve)
    const migratedPoint = {
      ...point,
      flowRate: migratedAofRate,
      transformedPressure: migratedPotential / migratedAofRate,
      potentialDifference: migratedPotential
    }
    analysisPoints = [point, migratedPoint]
    coefficients = regressLocalPoints(analysisPoints)
    if (![aofRate, coefficients.darcyCoefficient, coefficients.nonDarcyCoefficient].every(Number.isFinite) ||
        aofRate <= 0 || coefficients.darcyCoefficient < 0 || coefficients.nonDarcyCoefficient < 0) {
      throw new Error('一点法迁移计算未得到有效的二项式系数')
    }
    coefficients.transientDarcyCoefficient = null
  } else if (payload.testType === 'isochronal') {
    const stablePoint = points[points.length - 1]
    const transientPoints = points.slice(0, -1)
    if (transientPoints.length < 2) throw new Error('等时试井至少需要 3 个测试点')
    const transientCoefficients = regressLocalPoints(transientPoints)
    const stableDarcy = stablePoint.transformedPressure -
      transientCoefficients.nonDarcyCoefficient * stablePoint.flowRate
    if (stableDarcy < 0) throw new Error('等时试井测试点不匹配，计算得到的达西系数 A 小于 0')
    coefficients = {
      ...transientCoefficients,
      darcyCoefficient: stableDarcy,
      transientDarcyCoefficient: transientCoefficients.darcyCoefficient
    }
  } else {
    coefficients = regressLocalPoints(points)
  }

  const { darcyCoefficient, nonDarcyCoefficient, rSquared, transientDarcyCoefficient } = coefficients
  if (!Number.isFinite(aofRate)) {
    const atmosphericPressure = Math.min(maximumPressure, ATMOSPHERIC_PRESSURE_MPA)
    const maximumPotential = selectedOperationType === 'injection'
      ? pressurePotential(maximumInjectionPressure, selectedCalculationMethod, pvtCurve) -
        pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve)
      : pressurePotential(maximumPressure, selectedCalculationMethod, pvtCurve) -
        pressurePotential(atmosphericPressure, selectedCalculationMethod, pvtCurve)
    aofRate = solveBinomialFlowRate(maximumPotential, darcyCoefficient, nonDarcyCoefficient)
  }
  const maximumRate = payload.testType === 'one-point' && Number.isFinite(aofRate)
    ? aofRate
    : Math.max(...analysisPoints.map(point => point.flowRate), 1)
  const lineEnd = maximumRate * (payload.testType === 'one-point' ? 1.02 : 1.08)
  const lineStart = payload.testType === 'one-point' ? analysisPoints[0].flowRate : 0
  const makeLine = darcy => [
    { flowRate: lineStart, transformedPressure: darcy + nonDarcyCoefficient * lineStart },
    { flowRate: lineEnd, transformedPressure: darcy + nonDarcyCoefficient * lineEnd }
  ]
  const iprCurve = createIprCurve(
    maximumPressure,
    darcyCoefficient,
    nonDarcyCoefficient,
    selectedCalculationMethod,
    pvtCurve,
    selectedOperationType,
    maximumInjectionPressure
  )
  const iprCurves = createFormationPressureSeries(maximumPressure).map(pressure => ({
    formationPressure: pressure,
    points: createIprCurve(
      pressure,
      darcyCoefficient,
      nonDarcyCoefficient,
      selectedCalculationMethod,
      pvtCurve,
      selectedOperationType,
      maximumInjectionPressure
    )
  }))

  return {
    wellName: payload.wellName,
    testType: payload.testType,
    methodName: methodName.value,
    calculationMethod: selectedCalculationMethod,
    calculationResultType: 'binomial',
    operationType: selectedOperationType,
    formationPressure: maximumPressure,
    darcyCoefficient,
    nonDarcyCoefficient,
    rSquared,
    reliability: rSquared === null
      ? ''
      : rSquared >= 0.9
        ? '分析结果可靠性较高'
        : rSquared >= 0.7
          ? '分析结果可靠性一般'
          : '分析结果可靠性偏低',
    equation: coefficientEquation(
      selectedCalculationMethod,
      darcyCoefficient,
      nonDarcyCoefficient,
      selectedOperationType
    ),
    aofRate,
    analysisPoints,
    regressionLine: makeLine(darcyCoefficient),
    transientLine: transientDarcyCoefficient === null ? [] : makeLine(transientDarcyCoefficient),
    iprCurve,
    iprCurves
  }
}

const normalizeCalculationResult = (response, pvtResultRows = props.pvtResultRows) => {
  const payload = getCalculationPayload(response)
  const selectedCalculationMethod = normalizeCalculationMethod(
    readField(payload, ['calculationMethod', 'calculation_method', 'pressureMethod']) || calculationMethod.value
  )
  const selectedOperationType = readField(payload, ['operationType', 'operation_type']) === 'injection'
    ? 'injection'
    : operationType.value
  let pvtCurve = normalizePvtCurve(pvtResultRows)
  const darcyCoefficient = toNumber(readField(payload, [
    'darcyCoefficient', 'darcy_coefficient', 'darcySeepageCoefficient',
    'coefficientA', 'coefficient_a', 'darcyFlowCoefficient', 'A', 'a'
  ]))
  const nonDarcyCoefficient = toNumber(readField(payload, [
    'nonDarcyCoefficient', 'non_darcy_coefficient', 'nonDarcySeepageCoefficient',
    'coefficientB', 'coefficient_b',
    'nonDarcyFlowCoefficient', 'B', 'b'
  ]))
  const resultPressure = normalizePressure(readField(payload, [
    'formationPressure', 'formation_pressure', 'reservoirPressure', 'reservoir_pressure', 'pr'
  ])) ?? Number(formationPressure.value)
  const rSquared = toNumber(readField(payload, [
    'rSquared', 'r_squared', 'coefficientOfDetermination', 'determinationCoefficient', 'r2', 'R2'
  ]))

  const pseudoPressureRows = readField(payload, [
    'wellTestPseudoPressures', 'well_test_pseudo_pressures'
  ])
  if (selectedCalculationMethod === 'pseudo-pressure' && Array.isArray(pseudoPressureRows)) {
    const calculatedRows = pseudoPressureRows.flatMap((point, index) => {
      const sequence = toNumber(readField(point, ['index', 'sequence', 'testPointNumber'])) ?? index + 1
      const sourceRow = inputRows.value.find(row => Number(row.sequence) === Number(sequence)) || inputRows.value[index]
      return [
        {
          pressure: toNumber(sourceRow?.recoveryPressure) ?? resultPressure,
          pseudoPressure: toNumber(readField(point, [
            'reserviorPressure', 'reservoirPressure', 'recoveryPseudoPressure'
          ]))
        },
        {
          pressure: toNumber(sourceRow?.flowingPressure),
          pseudoPressure: toNumber(readField(point, [
            'testFlowPressure', 'flowingPressure', 'flowingPseudoPressure'
          ]))
        }
      ]
    }).filter(row => Number.isFinite(row.pressure) && Number.isFinite(row.pseudoPressure))
    if (calculatedRows.length) pvtCurve = normalizePvtCurve([...pvtResultRows, ...calculatedRows])
  }
  let analysisPoints = (Array.isArray(pseudoPressureRows) ? pseudoPressureRows : [])
    .map((point, index) => {
      const sequence = toNumber(readField(point, ['index', 'sequence', 'testPointNumber'])) ?? index + 1
      const sourceRow = inputRows.value.find(row => Number(row.sequence) === Number(sequence)) || inputRows.value[index]
      return {
        sequence,
        flowRate: toNumber(readField(point, [
          'dailyGasProduction', 'flowRate', 'gasRate', 'qsc'
        ])) ?? toNumber(sourceRow?.flowRate),
        transformedPressure: toNumber(readField(point, [
          'regularizedPressure', 'regularized_pressure', 'transformedPressure'
        ])),
        sourcePressure: normalizePressure(readField(point, [
          'reserviorPressure', 'reservoirPressure', 'sourcePressure'
        ])) ?? toNumber(sourceRow?.recoveryPressure)
      }
    })
    .filter(point => Number.isFinite(point.flowRate) && Number.isFinite(point.transformedPressure))
  if (!analysisPoints.length) analysisPoints = validPointList(
    readField(payload, ['analysisPoints', 'analysis_points', 'dataPoints', 'testPoints', 'points']),
    normalizeAnalysisPoint
  ).filter(point => Number.isFinite(point.transformedPressure))
  if (!analysisPoints.length) {
    analysisPoints = inputRows.value
      .map((row, index) => {
        const flowRate = toNumber(row.flowRate)
        const flowingPressure = toNumber(row.flowingPressure)
        const sourcePressure = toNumber(row.recoveryPressure) ?? resultPressure
        return {
          sequence: Number(row.sequence || index + 1),
          flowRate,
          transformedPressure: Number.isFinite(flowRate) && flowRate > 0 &&
            Number.isFinite(flowingPressure) && Number.isFinite(sourcePressure)
            ? (selectedOperationType === 'injection'
                ? pressurePotential(flowingPressure, selectedCalculationMethod, pvtCurve) -
                  pressurePotential(sourcePressure, selectedCalculationMethod, pvtCurve)
                : pressurePotential(sourcePressure, selectedCalculationMethod, pvtCurve) -
                  pressurePotential(flowingPressure, selectedCalculationMethod, pvtCurve)
              ) / flowRate
            : null,
          sourcePressure
        }
      })
      .filter(point => Number.isFinite(point.flowRate) && Number.isFinite(point.transformedPressure))
  }

  let regressionLine = validPointList(
    readField(payload, ['regressionLine', 'regression_line', 'fittingLine', 'fitLine']),
    normalizeLinePoint
  ).filter(point => Number.isFinite(point.transformedPressure))
  if (!regressionLine.length && [darcyCoefficient, nonDarcyCoefficient].every(Number.isFinite)) {
    const maximumRate = Math.max(1, ...analysisPoints.map(point => point.flowRate)) * 1.08
    regressionLine = [
      { flowRate: 0, transformedPressure: darcyCoefficient },
      { flowRate: maximumRate, transformedPressure: darcyCoefficient + nonDarcyCoefficient * maximumRate }
    ]
  }

  let transientLine = validPointList(
    readField(payload, ['transientLine', 'transient_line', 'isochronalLine', 'unstableLine']),
    normalizeLinePoint
  ).filter(point => Number.isFinite(point.transformedPressure))
  const transientIntercept = toNumber(readField(payload, [
    'intercept', 'isochronalIntercept', 'transientIntercept'
  ]))
  if (
    !transientLine.length &&
    activeTestType.value === 'isochronal' &&
    [transientIntercept, nonDarcyCoefficient].every(Number.isFinite)
  ) {
    const maximumRate = Math.max(1, ...analysisPoints.map(point => point.flowRate)) * 1.08
    transientLine = [
      { flowRate: 0, transformedPressure: transientIntercept },
      {
        flowRate: maximumRate,
        transformedPressure: transientIntercept + nonDarcyCoefficient * maximumRate
      }
    ]
  }

  let iprCurve = validPointList(
    readField(payload, ['iprCurve', 'ipr_curve', 'curve', 'iprPoints']),
    normalizeIprPoint
  ).filter(point => Number.isFinite(point.flowingPressure))
  if (!iprCurve.length) {
    iprCurve = createIprCurve(
      resultPressure,
      darcyCoefficient,
      nonDarcyCoefficient,
      selectedCalculationMethod,
      pvtCurve
    )
  }
  let aofRate = toNumber(readField(payload, [
    'aofRate', 'aof_rate', 'openFlowCapacity', 'open_flow_capacity'
  ]))
  if (!Number.isFinite(aofRate) && [darcyCoefficient, nonDarcyCoefficient].every(Number.isFinite)) {
    const atmosphericPressure = Math.min(resultPressure, ATMOSPHERIC_PRESSURE_MPA)
    const maximumPotential = pressurePotential(resultPressure, selectedCalculationMethod, pvtCurve) -
      pressurePotential(atmosphericPressure, selectedCalculationMethod, pvtCurve)
    aofRate = solveBinomialFlowRate(maximumPotential, darcyCoefficient, nonDarcyCoefficient)
  }

  const rawIprCurves = readField(payload, ['iprCurves', 'ipr_curves', 'curveList'])
  let iprCurves = (Array.isArray(rawIprCurves) ? rawIprCurves : []).map(curve => ({
    formationPressure: normalizePressure(readField(curve, [
      'formationPressure', 'formation_pressure', 'reservoirPressure', 'pressure', 'pr'
    ])) ?? resultPressure,
    points: validPointList(readField(curve, ['points', 'data', 'curve', 'iprCurve']), normalizeIprPoint)
      .filter(point => Number.isFinite(point.flowingPressure))
  })).filter(curve => curve.points.length)
  if (!iprCurves.length && iprCurve.length) iprCurves = [{ formationPressure: resultPressure, points: iprCurve }]

  const reliability = readField(payload, ['reliability', 'result', 'conclusion', 'evaluation']) || (
    rSquared === null
      ? ''
      : rSquared >= 0.9
        ? '分析结果可靠性较高'
        : rSquared >= 0.7
          ? '分析结果可靠性一般'
          : '分析结果可靠性偏低'
  )
  const equation = readField(payload, ['equation', 'formula', 'regressionEquation']) || (
    [darcyCoefficient, nonDarcyCoefficient].every(Number.isFinite)
      ? coefficientEquation(
          selectedCalculationMethod,
          darcyCoefficient,
          nonDarcyCoefficient,
          selectedOperationType
        )
      : ''
  )

  return {
    ...payload,
    wellName: readField(payload, ['wellName', 'well_name']) || selectedWellName.value,
    testType: normalizeTestType(readField(payload, ['testType', 'test_type'])) || activeTestType.value,
    methodName: readField(payload, ['methodName', 'method_name', 'method', 'evaluationMethod']) || methodName.value,
    calculationMethod: selectedCalculationMethod,
    operationType: selectedOperationType,
    formationPressure: resultPressure,
    darcyCoefficient,
    nonDarcyCoefficient,
    aofRate,
    rSquared,
    reliability,
    equation,
    analysisPoints,
    regressionLine,
    transientLine,
    iprCurve,
    iprCurves
  }
}

const unwrapDockerPayload = response => response?.data?.data ?? response?.data ?? response ?? {}
const originalNodeChildren = node => [node?.children, node?.subNodes, node?.nodes, node?.analysisNodes]
  .flatMap(value => Array.isArray(value) ? value : value ? [value] : [])
const originalNodeLabel = node => String(
  node?.wellName || node?.nodeTitle || node?.name || node?.title || node?.label || ''
).trim()

const discoverOriginalEvaluation = async (payload) => {
  const method = normalizeCalculationMethod(payload.calculationMethod)
  const evaluationForm = EVALUATION_FORM_BY_METHOD[method]
  const evaluationType = EVALUATION_TYPE_BY_TEST[payload.testType]
  const testNodeType = TEST_NODE_TYPE_BY_TEST[payload.testType]
  const pressureNodeType = PRESSURE_NODE_TYPE_BY_METHOD[method]
  if (![evaluationForm, evaluationType, testNodeType, pressureNodeType].every(Number.isFinite)) {
    throw new Error('当前试井类型或压力处理方法无法对应智慧气藏评价记录')
  }

  const requestedDate = String(inputRows.value.find(row => row.date)?.date || '').slice(0, 10)
  const cacheKey = [props.projectId, props.gasReservoirId, selectedWellName.value,
    evaluationForm, evaluationType, requestedDate].join('/')
  const cached = originalEvaluationCache.get(cacheKey)
  if (cached) return cached

  const response = await nodeApi.getNode(
    props.projectId,
    props.gasReservoirId,
    pressureNodeType,
    { silentError: true }
  )
  const payloadData = unwrapDockerPayload(response)
  const root = payloadData?.node ?? payloadData
  const candidates = []
  const walk = (node, insideWell = false) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) return node.forEach(item => walk(item, insideWell))
    const label = originalNodeLabel(node)
    const inWell = insideWell || label === selectedWellName.value || node.wellName === selectedWellName.value
    const nodeId = Number(node.nodeId ?? node.evaluationId ?? node.resultId ?? node.id)
    if (inWell && Number(node.nodeType) === Number(testNodeType) && Number.isFinite(nodeId) && nodeId > 0) {
      candidates.push({ id: nodeId, dateMatched: Boolean(requestedDate && label.includes(requestedDate)) })
    }
    originalNodeChildren(node).forEach(child => walk(child, inWell))
  }
  walk(root)

  for (const candidate of candidates.sort((left, right) =>
    Number(right.dateMatched) - Number(left.dateMatched) || right.id - left.id)) {
    try {
      const detailResponse = await productivityEvaluationApi.getResult(
        props.projectId,
        props.gasReservoirId,
        candidate.id,
        { silentError: true }
      )
      const detail = unwrapDockerPayload(detailResponse)
      if (String(detail?.evaluation?.wellName) !== String(selectedWellName.value) ||
          Number(detail?.evaluation?.evaluationForm) !== evaluationForm ||
          Number(detail?.evaluation?.evaluationType) !== evaluationType) continue
      originalEvaluationCache.set(cacheKey, detail)
      return detail
    } catch { /* 节点编号不是评价结果主键时继续查找。 */ }
  }
  throw new Error(`智慧气藏中未找到${selectedWellName.value}${methodName.value}试井的对应结果`)
}

const originalChartData = item => (item?.data || []).map((point, index) => ({
  sequence: index + 1,
  flowRate: Number(point.xValue),
  transformedPressure: Number(point.yValue),
  deleted: Boolean(point.isDeleted),
  dataLabel: point.dataLabel || ''
})).filter(point => [point.flowRate, point.transformedPressure].every(Number.isFinite))

const parseOriginalEvaluation = (detail, payload) => {
  const output = detail?.output || {}
  const charts = Array.isArray(detail?.chartItems) ? detail.chartItems : []
  const findChart = (field, name) => charts.find(item => item.yAxisField === field) ||
    charts.find(item => String(item.name).trim() === name)
  const analysisPoints = originalChartData(findChart('regularizedPressure', '不稳定数据点'))
  const regressionLine = originalChartData(findChart('linearRegressionPressure', '线性回归分析线'))
  const transientLine = originalChartData(findChart(
    'shiftLinearRegressionPressure',
    '线性回归分析平移线'
  ))
  const iprCurves = (detail?.iprChartItems || []).map((item, curveIndex) => {
    const points = (item?.data || []).map(point => ({
      flowRate: Number(point.xValue),
      flowingPressure: Number(point.yValue),
      deleted: Boolean(point.isDeleted),
      dataLabel: point.dataLabel || ''
    })).filter(point => [point.flowRate, point.flowingPressure].every(Number.isFinite))
    return {
      curveNumber: Number(String(item?.yAxisField || '').match(/(\d+)$/)?.[1]) || curveIndex + 1,
      formationPressure: Math.max(0, ...points.map(point => point.flowingPressure)),
      points
    }
  }).filter(curve => curve.points.length).sort((left, right) => left.curveNumber - right.curveNumber)
  const method = normalizeCalculationMethod(payload.calculationMethod)
  const darcyCoefficient = Number(output.darcySeepageCoefficient)
  const nonDarcyCoefficient = Number(output.nonDarcySeepageCoefficient)
  return {
    wellName: detail?.evaluation?.wellName || selectedWellName.value,
    testType: payload.testType,
    methodName: methodName.value,
    calculationMethod: method,
    calculationResultType: 'binomial',
    evaluationId: Number(detail?.evaluation?.id),
    formationPressure: Number(detail?.input?.originalFormationPressure ?? payload.formationPressure),
    darcyCoefficient,
    nonDarcyCoefficient,
    aofRate: Number(output.openFlowCapacity),
    rSquared: output.rSquared == null ? null : Number(output.rSquared),
    reliabilityLevel: output.reliability == null ? null : Number(output.reliability),
    reliability: output.reliabilityDesc || '',
    equation: coefficientEquation(method, darcyCoefficient, nonDarcyCoefficient),
    analysisPoints,
    regressionLine,
    transientLine,
    iprCurve: iprCurves.at(-1)?.points || [],
    iprCurves
  }
}

const nearlyEqual = (left, right, tolerance = 0.002) =>
  Number.isFinite(Number(left)) && Number.isFinite(Number(right)) &&
  Math.abs(Number(left) - Number(right)) <= tolerance

const matchesOriginalInput = (payload, detail) => {
  const originalPoints = [...(detail?.inputItems || [])].sort((left, right) =>
    Number(left.testPointNumber) - Number(right.testPointNumber))
  const currentPoints = [...(payload.points || [])].sort((left, right) =>
    Number(left.sequence) - Number(right.sequence))
  return originalPoints.length === currentPoints.length &&
    nearlyEqual(payload.formationPressure, detail?.input?.originalFormationPressure) &&
    nearlyEqual(payload.temperature, detail?.input?.formationTemperature) &&
    originalPoints.every((point, index) =>
      nearlyEqual(currentPoints[index]?.flowRate, point.testDailyGasProduction) &&
      nearlyEqual(currentPoints[index]?.recoveryPressure, point.reserviorPressure) &&
      nearlyEqual(currentPoints[index]?.flowingPressure, point.testFlowPressure))
}

const matchesOriginalRows = (detail) => {
  const originalPoints = [...(detail?.inputItems || [])].sort((left, right) =>
    Number(left.testPointNumber) - Number(right.testPointNumber))
  const currentPoints = [...inputRows.value].sort((left, right) =>
    Number(left.sequence) - Number(right.sequence))
  return originalPoints.length === currentPoints.length && originalPoints.every((point, index) =>
    nearlyEqual(currentPoints[index]?.flowRate, point.testDailyGasProduction) &&
    nearlyEqual(currentPoints[index]?.recoveryPressure, point.reserviorPressure) &&
    nearlyEqual(currentPoints[index]?.flowingPressure, point.testFlowPressure))
}

const applyOriginalInputRows = detail => {
  inputRows.value = (detail?.inputItems || []).map((item, index) => ({
    sequence: Number(item.testPointNumber ?? index + 1),
    date: String(detail?.evaluation?.wellTestDate || '').slice(0, 10),
    testType: activeTestType.value,
    recoveryPressure: Number(item.reserviorPressure),
    flowRate: Number(item.testDailyGasProduction),
    equivalentFlowRate: Number(item.equivalentTestDailyGasProduction),
    flowingPressure: Number(item.testFlowPressure)
  }))
}

const syncOriginalInputDefaults = async () => {
  if (!isOwnedTestType()) return
  if (props.storedTest || !selectedWellName.value || !inputRows.value.length) return
  const syncKey = [selectedWellName.value, activeTestType.value,
    normalizeCalculationMethod(calculationMethod.value)].join('/')
  if (originalInputSyncKey === syncKey) return
  const detail = await discoverOriginalEvaluation(buildPayload())
  originalInputSyncKey = syncKey
  if (!matchesOriginalRows(detail)) return
  applyOriginalInputRows(detail)
  formationPressure.value = Number(detail?.input?.originalFormationPressure ?? formationPressure.value)
  temperature.value = Number(detail?.input?.formationTemperature ?? temperature.value)
  emit('source-input-sync', {
    maximumFormationPressure: formationPressure.value,
    formationTemperature: temperature.value
  })
}

const originalGasInput = detail => {
  const gas = { ...(props.pvtRecord?.gasInput || {}), ...(props.pvtRecord?.gasSettings || {}) }
  const fallback = detail?.input || {}
  const methodNumber = (value, names, defaultValue) => {
    if (Number.isInteger(Number(value))) return Number(value)
    return normalizeMethodIndex(value, names, Number(defaultValue) || 0)
  }
  return {
    gasType: gas.gasType ?? fallback.gasType,
    specificGravity: Number(gas.specificGravity ?? fallback.specificGravity),
    hydrogenSulfide: Number(gas.hydrogenSulfide ?? fallback.hydrogenSulfide ?? 0),
    carbonDioxide: Number(gas.carbonDioxide ?? fallback.carbonDioxide ?? 0),
    nitrogen: Number(gas.nitrogen ?? fallback.nitrogen ?? 0),
    condensateOilDensityUnderStandardCondition: Number(
      gas.condensateOilDensity ?? fallback.condensateOilDensityUnderStandardCondition ?? 0
    ),
    modificationMethod: methodNumber(
      gas.modificationMethod,
      ['Wichert-Aziz', 'Carr-Kobayashi-Burrous'],
      fallback.modificationMethod
    ),
    deviationFactorMethod: methodNumber(
      gas.deviationFactorMethod,
      ['Dranchuk-Abu-Kassem', 'Dranchuk-Purvis-Robinson', 'Hall-Yarborough'],
      fallback.deviationFactorMethod
    ),
    viscosityMethod: methodNumber(
      gas.viscosityMethod,
      ['Lee-Gonzalez-Eakin', 'Carr-Kobayashi-Burrous', 'Sutton'],
      fallback.viscosityMethod
    )
  }
}

const calculateWithOriginalPlatform = async payload => {
  let detail = await discoverOriginalEvaluation(payload)
  if (!matchesOriginalInput(payload, detail)) {
    const evaluationId = Number(detail?.evaluation?.id)
    const evaluationForm = EVALUATION_FORM_BY_METHOD[normalizeCalculationMethod(payload.calculationMethod)]
    const evaluationType = EVALUATION_TYPE_BY_TEST[payload.testType]
    const originalInput = detail?.input || {}
    const response = await productivityEvaluationApi.calculate(selectedWellName.value, {
      gasReservoirId: Number(props.gasReservoirId),
      projectId: Number(props.projectId),
      evaluationId,
      deletePointIds: [],
      input: {
        ...originalInput,
        id: Number(originalInput.id),
        ProductivityEvaluationId: evaluationId,
        originalFormationPressure: Number(payload.formationPressure),
        formationTemperature: Number(payload.temperature),
        horizontalSectionLength: Number(originalInput.horizontalSectionLength || 0),
        skinFactor: Number(originalInput.skinFactor || 0),
        permeability: Number(originalInput.permeability || 0),
        thickness: Number(originalInput.thickness || 0),
        gasDrainageRadius: Number(originalInput.gasDrainageRadius || 0),
        wellboreRadius: Number(originalInput.wellboreRadius || 0),
        ...originalGasInput(detail),
        edges: {}
      },
      inputItems: payload.points.map((point, index) => ({
        testPointNumber: Number(point.sequence ?? index + 1),
        reserviorPressure: Number(point.recoveryPressure),
        testDailyGasProduction: Number(point.flowRate),
        testFlowPressure: Number(point.flowingPressure),
        testDailyOilProduction: 0
      })),
      evaluationForm,
      evaluationType,
      wellName: selectedWellName.value
    }, { silentError: true })
    detail = unwrapDockerPayload(response)
    if (!detail?.output) {
      const refreshed = await productivityEvaluationApi.getResult(
        props.projectId,
        props.gasReservoirId,
        evaluationId,
        { silentError: true }
      )
      detail = unwrapDockerPayload(refreshed)
    }
  }
  applyOriginalInputRows(detail)
  formationPressure.value = Number(detail?.input?.originalFormationPressure ?? payload.formationPressure)
  temperature.value = Number(detail?.input?.formationTemperature ?? payload.temperature)
  return parseOriginalEvaluation(detail, payload)
}

const analyze = async () => {
  if (!selectedWellName.value) {
    selectorVisible.value = true
    return
  }
  if (!selectedDataTable.value) {
    ElMessage.warning('请选择产能表')
    return
  }
  calculating.value = true
  try {
    if (activeTestType.value === 'one-point') {
      await syncOriginalInputDefaults()
    }
    const payload = buildPayload()
    if (normalizeCalculationMethod(payload.calculationMethod) === 'pseudo-pressure') {
      const exactRows = await loadExactPseudoPressureRows(payload)
      payload.pvtResultRows = [...payload.pvtResultRows, ...exactRows]
    }
    if (payload.calculationResultType === 'binomial' && payload.testType === 'one-point') {
      result.value = await calculateWithOriginalPlatform(payload)
    } else {
      const response = { data: calculateLocally(payload) }
      result.value = normalizeCalculationResult(response, payload.pvtResultRows)
    }
    emit('result-change', result.value, { stored: false })
    activePanel.value = 'analysis'
    activeChart.value = 'analysis'
    await nextTick()
    renderChart()
    const calculationMethodName = {
      'pseudo-pressure': '拟压力',
      'pressure-squared': '压力平方方法',
      pressure: '压力法'
    }[calculationMethod.value]
    ElMessage.success(
      `${selectedWellName.value} ${operationType.value === 'injection' ? '注气' : ''}${methodName.value}${calculationMethodName}计算完成`
    )
  } catch (error) {
    console.error('产能试井压力形式计算失败', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '产能试井压力形式计算失败')
  } finally {
    calculating.value = false
  }
}

const scientific = (value) => {
  const number = Number(value)
  if (!Number.isFinite(number)) return ''
  return number === 0 ? '0.0000' : number.toExponential(4).replace('e', 'E')
}

const ensureChart = () => {
  if (!chartEl.value) return null
  if (chart?.isDisposed?.()) chart = null
  if (chart && chart.getDom() !== chartEl.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartEl.value)
  return chart
}

const renderChart = () => {
  if (!result.value) return
  const chartInstance = ensureChart()
  if (!chartInstance) return

  const analysisPoints = result.value.analysisPoints || []
  const regressionLine = result.value.regressionLine || []
  const transientLine = result.value.transientLine || []
  const isIsochronalResult = activeTestType.value === 'isochronal' && transientLine.length > 0
  const isExponentialResult = result.value.calculationResultType === 'exponential'
  // 指数式分析采用双对数坐标。先对数据取自然对数，再使用普通 value 轴，
  // 既保持幂函数在线性坐标中的回归直线，也能与二项式共用完整的主、次刻度网格。
  const toAnalysisChartPoint = point => {
    const flowRate = Number(point.flowRate)
    const transformedPressure = Number(point.transformedPressure)
    if (![flowRate, transformedPressure].every(Number.isFinite)) return null
    if (!isExponentialResult) return [flowRate, transformedPressure]
    if (flowRate <= 0 || transformedPressure <= 0) return null
    return [Math.log(flowRate), Math.log(transformedPressure)]
  }
  const toAnalysisChartData = points => points
    .map(toAnalysisChartPoint)
    .filter(Boolean)
  const unstablePoints = isIsochronalResult ? analysisPoints.slice(0, -1) : analysisPoints
  const stablePoints = isIsochronalResult ? analysisPoints.slice(-1) : []
  const rateValues = analysisPoints.map(point => Number(point.flowRate)).filter(Number.isFinite)
  const minimumRate = Math.min(...rateValues)
  const maximumRate = Math.max(...rateValues)
  const clipLine = line => {
    if (line.length < 2 || !Number.isFinite(minimumRate) || !Number.isFinite(maximumRate)) return line
    const sorted = [...line]
      .filter(point => [point.flowRate, point.transformedPressure].every(value => Number.isFinite(Number(value))))
      .sort((left, right) => Number(left.flowRate) - Number(right.flowRate))
    if (sorted.length < 2) return sorted
    const interpolate = x => {
      let upperIndex = sorted.findIndex(point => Number(point.flowRate) >= x)
      if (upperIndex <= 0) upperIndex = 1
      if (upperIndex < 0) upperIndex = sorted.length - 1
      const lower = sorted[upperIndex - 1]
      const upper = sorted[upperIndex]
      const x1 = Number(lower.flowRate)
      const y1 = Number(lower.transformedPressure)
      const x2 = Number(upper.flowRate)
      const y2 = Number(upper.transformedPressure)
      if (Math.abs(x2 - x1) < 1e-12) return y1
      if (isExponentialResult && [x, x1, x2, y1, y2].every(value => value > 0)) {
        const ratio = (Math.log(x) - Math.log(x1)) / (Math.log(x2) - Math.log(x1))
        return Math.exp(Math.log(y1) + ratio * (Math.log(y2) - Math.log(y1)))
      }
      return y1 + (y2 - y1) * (x - x1) / (x2 - x1)
    }
    const middle = sorted.filter(point => {
      const rate = Number(point.flowRate)
      return rate > minimumRate && rate < maximumRate
    })
    return [
      { flowRate: minimumRate, transformedPressure: interpolate(minimumRate) },
      ...middle,
      { flowRate: maximumRate, transformedPressure: interpolate(maximumRate) }
    ]
  }
  const method = result.value.calculationMethod || calculationMethod.value
  const resultOperationType = result.value.operationType === 'injection' ? 'injection' : 'production'
  const ownedPresentation = isOwnedTestType()
  const analysisUnit = isExponentialResult
    ? ({
        'pseudo-pressure': '[MPa²/(mPa·s)]',
        'pressure-squared': '[MPa²]',
        pressure: '[MPa]'
      })[method] || '[MPa]'
    : ownedPresentation
      ? ({
          'pseudo-pressure': '((MPa²/(mPa·s))/(10⁴m³/d))',
          'pressure-squared': '(MPa²/(10⁴m³/d))',
          pressure: '(MPa/(10⁴m³/d))'
        })[method] || '(MPa/(10⁴m³/d))'
      : ({
          'pseudo-pressure': '[(MPa²/(mPa·s))/(10⁴m³/d)]',
          'pressure-squared': '[MPa²/(10⁴m³/d)]',
          pressure: '[MPa/(10⁴m³/d)]'
        })[method] || '[MPa/(10⁴m³/d)]'
  const blackLine = isIsochronalResult ? transientLine : regressionLine
  const orangeLine = isIsochronalResult ? regressionLine : transientLine
  const series = [
    {
      name: isIsochronalResult
        ? `不稳定点${analysisUnit}`
        : (ownedPresentation ? `数据点${analysisUnit}` : '测试点'),
      type: 'scatter',
      symbolSize: 10,
      z: 4,
      data: toAnalysisChartData(unstablePoints),
      itemStyle: { color: '#5478c9' }
    },
    {
      name: isIsochronalResult || ownedPresentation ? `回归线${analysisUnit}` : '回归线',
      type: 'line',
      showSymbol: false,
      symbol: 'none',
      z: 2,
      data: toAnalysisChartData(clipLine(blackLine)),
      lineStyle: { color: '#303030', width: 2 },
      itemStyle: { color: '#303030' }
    }
  ]

  if (orangeLine.length) {
    series.push({
      name: isIsochronalResult ? `平移线${analysisUnit}` : '等时线',
      type: 'line',
      showSymbol: false,
      symbol: 'none',
      z: 2,
      data: toAnalysisChartData(clipLine(orangeLine)),
      lineStyle: { color: '#f5a000', width: 2, type: 'dotted' },
      itemStyle: { color: '#f5a000' }
    })
  }

  if (stablePoints.length) {
    series.push({
      name: `稳定点${analysisUnit}`,
      type: 'scatter',
      symbolSize: 10,
      z: 5,
      data: toAnalysisChartData(stablePoints),
      itemStyle: { color: '#e75b62' }
    })
  }

  const legendItems = series.map(item => ({
    name: item.name,
    type: item.type,
    color: item.itemStyle?.color || item.lineStyle?.color || '#333',
    dotted: item.lineStyle?.type === 'dotted'
  }))
  const legendMeasureContext = document.createElement('canvas').getContext('2d')
  if (legendMeasureContext) legendMeasureContext.font = '12px "Microsoft YaHei", sans-serif'
  const widestLegendText = Math.max(
    0,
    ...legendItems.map(item => legendMeasureContext?.measureText(item.name).width || item.name.length * 7)
  )
  const legendPanelWidth = Math.min(330, Math.max(190, Math.ceil(widestLegendText) + 49))
  const legendRowHeight = 21
  const legendPanelHeight = legendItems.length * legendRowHeight + 12
  const legendChildren = [{
    type: 'rect',
    z: 1000,
    zlevel: 20,
    shape: { x: 0, y: 0, width: legendPanelWidth, height: legendPanelHeight, r: 2 },
    style: {
      fill: '#fff',
      stroke: '#cfd5dc',
      lineWidth: 1,
      shadowBlur: 7,
      shadowColor: 'rgba(0,0,0,0.14)',
      shadowOffsetY: 2
    }
  }]
  legendItems.forEach((item, index) => {
    const centerY = 6 + legendRowHeight * index + legendRowHeight / 2
    legendChildren.push(item.type === 'scatter'
      ? {
          type: 'circle',
          z: 1001,
          zlevel: 20,
          shape: { cx: 17, cy: centerY, r: 5.5 },
          style: { fill: item.color }
        }
      : {
          type: 'line',
          z: 1001,
          zlevel: 20,
          shape: { x1: 8, y1: centerY, x2: 28, y2: centerY },
          style: {
            stroke: item.color,
            lineWidth: 2,
            lineDash: item.dotted ? [2, 2] : null
          }
        })
    legendChildren.push({
      type: 'text',
      z: 1001,
      zlevel: 20,
      style: {
        x: 35,
        y: centerY,
        text: item.name,
        font: '12px "Microsoft YaHei", sans-serif',
        fill: '#303030',
        verticalAlign: 'middle'
      }
    })
  })
  const formulaText = result.value.rSquared === null || result.value.rSquared === undefined
    ? result.value.equation
    : `${result.value.equation}\nR² = ${Number(result.value.rSquared).toFixed(4)}`
  const formulaPanelWidth = 350
  const formulaPanelHeight = result.value.rSquared === null || result.value.rSquared === undefined ? 42 : 60

  chartInstance.setOption({
    animation: false,
    backgroundColor: '#fff',
    title: {
      text: `${methodName.value}${resultOperationType === 'injection' ? '注气' : ''}试井分析图`,
      left: 'center',
      top: 8,
      textStyle: { color: '#3f3f3f', fontSize: 14, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#cfd5dc',
      borderWidth: 1,
      textStyle: { color: '#333', fontSize: 12 },
      axisPointer: {
        type: 'line',
        axis: 'x',
        snap: false,
        lineStyle: { color: '#5f6f82', width: 1, type: 'dashed' },
        label: {
          show: true,
          backgroundColor: '#5f6f82',
          color: '#fff',
          precision: 3
        }
      }
    },
    legend: { show: false },
    grid: {
      left: 74,
      right: 30,
      top: 40,
      bottom: 58,
      show: true,
      borderColor: '#d7dfeb',
      borderWidth: 1
    },
    xAxis: {
      type: 'value',
      scale: true,
      name: isExponentialResult ? 'ln(qsc(10⁴m³/d))' : 'qsc(10⁴m³/d)',
      nameLocation: 'middle',
      nameGap: 32,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12 },
      splitNumber: 12,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      minorTick: { show: true, splitNumber: 5 },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } }
    },
    yAxis: {
      type: 'value',
      scale: true,
      name: result.value.calculationResultType === 'exponential'
        ? exponentialAnalysisAxisName(
            result.value.calculationMethod || calculationMethod.value,
            resultOperationType
          )
        : analysisAxisName(
            result.value.calculationMethod || calculationMethod.value,
            resultOperationType
          ),
      nameLocation: 'middle',
      nameGap: 48,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12 },
      splitNumber: 10,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      minorTick: { show: true, splitNumber: 5 },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } }
    },
    series,
    graphic: [
      {
        id: 'analysis-legend-panel',
        type: 'group',
        right: 16,
        top: 62,
        z: 100,
        zlevel: 20,
        draggable: true,
        cursor: 'move',
        children: legendChildren
      },
      {
        id: 'analysis-formula-panel',
        type: 'group',
        right: 46,
        bottom: 72,
        z: 100,
        zlevel: 20,
        draggable: true,
        cursor: 'move',
        children: [
          {
            type: 'rect',
            z: 1000,
            zlevel: 20,
            shape: { x: 0, y: 0, width: formulaPanelWidth, height: formulaPanelHeight, r: 2 },
            style: {
              fill: '#fff',
              stroke: '#cfd5dc',
              lineWidth: 1,
              shadowBlur: 7,
              shadowColor: 'rgba(0,0,0,0.14)',
              shadowOffsetY: 2
            }
          },
          {
            type: 'text',
            z: 1001,
            zlevel: 20,
            style: {
              x: 12,
              y: 9,
              text: formulaText,
              font: '13px "Microsoft YaHei", sans-serif',
              fill: '#444',
              lineHeight: 21,
              textAlign: 'left'
            }
          }
        ]
      }
    ]
  }, true)
  chartInstance.resize()
}

const renderIprChart = () => {
  if (!result.value) return
  const chartInstance = ensureChart()
  if (!chartInstance) return
  const injection = result.value.operationType === 'injection'
  const iprCurves = Array.isArray(result.value.iprCurves) && result.value.iprCurves.length
    ? result.value.iprCurves
    : [{ formationPressure: result.value.formationPressure, points: result.value.iprCurve || [] }]
  const iprColors = [
    '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
    '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#00b7c7',
    '#6f7ad3', '#c98bd4'
  ]
  const useOwnedPresentation = isOwnedTestType()
  const formatIprPressure = value => useOwnedPresentation
    ? Number.parseFloat(Number(value).toFixed(3)).toString()
    : Number(value).toFixed(0)
  const iprFlowValues = iprCurves.flatMap(curve =>
    (curve.points || []).map(point => Number(point.flowRate)).filter(Number.isFinite))
  const iprPressureValues = iprCurves.flatMap(curve =>
    (curve.points || []).map(point => Number(point.flowingPressure)).filter(Number.isFinite))
  const maximumIprFlow = Math.max(0, ...iprFlowValues)
  const xStepBasis = maximumIprFlow / 11
  const xStepMagnitude = xStepBasis > 0 ? 10 ** Math.floor(Math.log10(xStepBasis)) : 1
  const xInterval = Math.max(xStepMagnitude,
    Math.ceil(xStepBasis / xStepMagnitude - 1e-12) * xStepMagnitude)
  const xMaximum = xInterval * 11
  const maximumIprPressure = Math.max(0, ...iprPressureValues)
  const yMaximum = Math.ceil((maximumIprPressure + 1e-9) / 5) * 5
  const iprSeries = iprCurves.map((curve, index) => ({
    name: `${useOwnedPresentation ? 'Pᵣ' : 'Pr'}${index + 1}=${formatIprPressure(curve.formationPressure)} MPa`,
    type: 'line',
    showSymbol: false,
    symbol: 'none',
    smooth: true,
    data: (curve.points || []).map(point => [point.flowRate, point.flowingPressure]),
    lineStyle: { width: 1.7, color: iprColors[index % iprColors.length] },
    itemStyle: { color: iprColors[index % iprColors.length] }
  }))
  const iprMeasureContext = document.createElement('canvas').getContext('2d')
  if (iprMeasureContext) iprMeasureContext.font = '11px "Microsoft YaHei", sans-serif'
  const iprLegendTextWidth = Math.max(
    0,
    ...iprSeries.map(item => iprMeasureContext?.measureText(item.name).width || item.name.length * 6.5)
  )
  const iprLegendWidth = Math.min(190, Math.max(115, Math.ceil(iprLegendTextWidth) + 43))
  const iprLegendRowHeight = 18
  const iprLegendHeight = iprSeries.length * iprLegendRowHeight + 12
  const iprLegendChildren = [{
    type: 'rect',
    z: 1000,
    zlevel: 20,
    shape: { x: 0, y: 0, width: iprLegendWidth, height: iprLegendHeight, r: 2 },
    style: {
      fill: '#fff',
      stroke: '#cfd5dc',
      lineWidth: 1,
      shadowBlur: 7,
      shadowColor: 'rgba(0,0,0,0.14)',
      shadowOffsetY: 2
    }
  }]
  iprSeries.forEach((item, index) => {
    const centerY = 6 + iprLegendRowHeight * index + iprLegendRowHeight / 2
    iprLegendChildren.push(
      {
        type: 'line',
        z: 1001,
        zlevel: 20,
        shape: { x1: 8, y1: centerY, x2: 25, y2: centerY },
        style: { stroke: item.lineStyle.color, lineWidth: 2 }
      },
      {
        type: 'text',
        z: 1001,
        zlevel: 20,
        style: {
          x: 31,
          y: centerY,
          text: item.name,
          font: '11px "Microsoft YaHei", sans-serif',
          fill: '#303030',
          verticalAlign: 'middle'
        }
      }
    )
  })
  chartInstance.setOption({
    animation: false,
    backgroundColor: '#fff',
    title: {
      text: injection ? '注气IPR曲线' : 'IPR曲线',
      left: 'center',
      top: 8,
      textStyle: { color: '#3f3f3f', fontSize: 14, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'axis',
      confine: true,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#cfd5dc',
      borderWidth: 1,
      textStyle: { color: '#333', fontSize: 12 },
      axisPointer: {
        type: 'line',
        axis: 'x',
        snap: false,
        lineStyle: { color: '#5f6f82', width: 1, type: 'dashed' },
        label: {
          show: true,
          backgroundColor: '#5f6f82',
          color: '#fff',
          precision: 3
        }
      }
    },
    legend: { show: false },
    grid: {
      left: 68,
      right: 28,
      top: 40,
      bottom: 58,
      show: true,
      borderColor: '#d7dfeb',
      borderWidth: 1
    },
    xAxis: {
      type: 'value',
      min: 0,
      ...(useOwnedPresentation ? { max: xMaximum, interval: xInterval } : {}),
      name: 'qsc(10⁴m³/d)',
      nameLocation: 'middle',
      nameGap: 32,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12 },
      splitNumber: 15,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      minorTick: { show: true, splitNumber: 5 },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } }
    },
    yAxis: {
      type: 'value',
      min: injection ? undefined : 0,
      scale: injection,
      ...(useOwnedPresentation ? { max: yMaximum, interval: 5 } : {}),
      name: 'Pwf(MPa)',
      nameLocation: 'middle',
      nameGap: 43,
      nameTextStyle: { color: '#333', fontSize: 14 },
      axisLine: { show: true, lineStyle: { color: '#444', width: 1 } },
      axisTick: { show: true, lineStyle: { color: '#555' } },
      axisLabel: { color: '#444', fontSize: 12 },
      splitNumber: 12,
      splitLine: { show: true, lineStyle: { color: '#dbe4f1', width: 1 } },
      minorTick: { show: true, splitNumber: 5 },
      minorSplitLine: { show: true, lineStyle: { color: '#edf2f8', width: 1 } }
    },
    series: iprSeries,
    graphic: [{
      id: 'ipr-legend-panel',
      type: 'group',
      right: 16,
      top: 54,
      z: 100,
      zlevel: 20,
      draggable: true,
      cursor: 'move',
      children: iprLegendChildren
    }]
  }, true)
  chartInstance.resize()
}

const switchPanel = async (panel) => {
  activePanel.value = panel
  if (panel === 'input' || !result.value) return
  await nextTick()
  if (activeChart.value === 'ipr') renderIprChart()
  else renderChart()
}

const getPersistenceSnapshot = () => {
  if (!result.value) return null
  const validRows = inputRows.value.filter(row =>
    [row.flowRate, row.flowingPressure, row.recoveryPressure].every(value => Number.isFinite(Number(value)))
  )
  return {
    operationType: result.value.operationType === 'injection' ? 'injection' : 'production',
    testDate: validRows.find(row => row.date)?.date || new Date().toISOString().slice(0, 10),
    input: {
      maximumFormationPressure: Number(formationPressure.value),
      formationTemperature: Number(temperature.value),
      onePointAlpha: Number(props.externalOnePointAlpha),
      gasType: props.pvtRecord?.gasSettings?.gasType || null,
      specificGravity: Number(props.pvtRecord?.gasSettings?.specificGravity) || null,
      hydrogenSulfide: Number(props.pvtRecord?.gasSettings?.hydrogenSulfide) || null,
      carbonDioxide: Number(props.pvtRecord?.gasSettings?.carbonDioxide) || null,
      nitrogen: Number(props.pvtRecord?.gasSettings?.nitrogen) || null,
      condensateOilDensity: Number(props.pvtRecord?.gasSettings?.condensateOilDensity) || null,
      modificationMethod: props.pvtRecord?.gasSettings?.modificationMethod || null,
      deviationFactorMethod: props.pvtRecord?.gasSettings?.deviationFactorMethod || null,
      viscosityMethod: props.pvtRecord?.gasSettings?.viscosityMethod || null,
      points: validRows.map((row, index) => ({
        pointNumber: Number(row.sequence || index + 1),
        gasProduction: Number(row.flowRate),
        reservoirPressure: Number(row.recoveryPressure),
        flowPressure: Number(row.flowingPressure)
      }))
    },
    pressureMethod: calculationMethod.value,
    result: {
      calculationResultType: result.value.calculationResultType,
      evaluationId: result.value.evaluationId !== null
        && result.value.evaluationId !== undefined
        && Number.isFinite(Number(result.value.evaluationId))
        ? Number(result.value.evaluationId)
        : null,
      darcyCoefficient: result.value.calculationResultType === 'binomial'
        ? Number(result.value.darcyCoefficient)
        : null,
      nonDarcyCoefficient: result.value.calculationResultType === 'binomial'
        ? Number(result.value.nonDarcyCoefficient)
        : null,
      productivityCoefficient: result.value.calculationResultType === 'exponential'
        ? Number(result.value.productivityCoefficient)
        : null,
      productivityExponent: result.value.calculationResultType === 'exponential'
        ? Number(result.value.productivityExponent)
        : null,
      openFlowCapacity: Number(result.value.aofRate),
      gradient: Number.isFinite(Number(result.value.nonDarcyCoefficient))
        ? Number(result.value.nonDarcyCoefficient)
        : null,
      intercept: Number.isFinite(Number(result.value.darcyCoefficient))
        ? Number(result.value.darcyCoefficient)
        : null,
      rSquared: Number.isFinite(Number(result.value.rSquared)) ? Number(result.value.rSquared) : null,
      reliabilityLevel: Number.isFinite(Number(result.value.reliabilityLevel))
        ? Number(result.value.reliabilityLevel)
        : null,
      reliabilityDescription: result.value.reliability || null,
      analysisPoints: (result.value.analysisPoints || []).map(point => ({
        x: Number(point.flowRate), y: Number(point.transformedPressure), label: null
      })),
      regressionLine: (result.value.regressionLine || []).map(point => ({
        x: Number(point.flowRate), y: Number(point.transformedPressure), label: null
      })),
      transientLine: (result.value.transientLine || []).map(point => ({
        x: Number(point.flowRate), y: Number(point.transformedPressure), label: null
      })),
      iprCurves: (result.value.iprCurves || []).map(curve => ({
        formationPressure: Number(curve.formationPressure),
        points: (curve.points || []).map(point => ({
          gasProduction: Number(point.flowRate),
          bottomHoleFlowingPressure: Number(point.flowingPressure),
          label: null
        }))
      }))
    }
  }
}

const storedResult = () => {
  const detail = props.storedTest
  if (!detail) return null
  const candidates = Array.isArray(detail.results) && detail.results.length
    ? detail.results
    : (detail.result ? [detail.result] : [])
  const saved = candidates.find(item =>
    (item.calculationResultType || 'binomial') === calculationResultType.value &&
    normalizeCalculationMethod(item.pressureMethod) === calculationMethod.value
  )
  if (!saved) return null

  const chartPoints = Array.isArray(saved.chartPoints) ? saved.chartPoints : []
  const curve = types => chartPoints.filter(point => types.includes(point.curveType)).map(point => ({
    sequence: point.sourcePointNumber ?? point.pointNumber,
    flowRate: Number(point.xValue),
    transformedPressure: Number(point.yValue),
    deleted: Boolean(point.deleted),
    dataLabel: point.dataLabel || ''
  }))
  const iprGroups = (saved.iprPoints || []).reduce((groups, point) => {
    const curveNumber = Number(point.curveNumber)
    const flowingPressure = Number(point.bottomHoleFlowingPressure)
    const hasExplicitFormationPressure = point.formationPressure !== null &&
      point.formationPressure !== undefined && Number.isFinite(Number(point.formationPressure))
    const explicitFormationPressure = hasExplicitFormationPressure
      ? Number(point.formationPressure)
      : null
    const current = groups.get(curveNumber) || {
      formationPressure: hasExplicitFormationPressure
        ? explicitFormationPressure
        : flowingPressure,
      points: []
    }
    if (!hasExplicitFormationPressure && Number.isFinite(flowingPressure)) {
      current.formationPressure = Math.max(Number(current.formationPressure) || 0, flowingPressure)
    }
    current.points.push({
      flowRate: Number(point.gasProduction),
      flowingPressure,
      deleted: Boolean(point.deleted),
      dataLabel: point.dataLabel || ''
    })
    groups.set(curveNumber, current)
    return groups
  }, new Map())
  const iprCurves = [...iprGroups.values()]
  const isExponential = (saved.calculationResultType || 'binomial') === 'exponential'
  const coefficient = Number(saved.productivityCoefficient)
  const exponent = Number(saved.productivityExponent)
  const darcy = Number(saved.darcySeepageCoefficient)
  const nonDarcy = Number(saved.nonDarcySeepageCoefficient)
  return {
    wellName: detail.wellName,
    testType: detail.testMethod,
    methodName: methodName.value,
    calculationMethod: normalizeCalculationMethod(saved.pressureMethod),
    calculationResultType: isExponential ? 'exponential' : 'binomial',
    evaluationId: saved.evaluationId == null ? null : Number(saved.evaluationId),
    formationPressure: Number(detail.input?.maximumFormationPressure),
    productivityCoefficient: isExponential ? coefficient : null,
    productivityExponent: isExponential ? exponent : null,
    darcyCoefficient: isExponential ? null : darcy,
    nonDarcyCoefficient: isExponential ? null : nonDarcy,
    aofRate: Number(saved.openFlowCapacity),
    rSquared: saved.rSquared == null ? null : Number(saved.rSquared),
    reliability: saved.reliabilityDescription || '',
    equation: isExponential
      ? exponentialEquation(saved.pressureMethod, coefficient, exponent)
      : coefficientEquation(saved.pressureMethod, darcy, nonDarcy),
    analysisPoints: curve(['analysis', 'regularized', 'stable']),
    regressionLine: curve(['regression']),
    transientLine: curve(['transient', 'shifted-regression']),
    iprCurve: iprCurves[0]?.points || [],
    iprCurves
  }
}

const applyStoredTest = async () => {
  const detail = props.storedTest
  if (!detail || !['back-pressure', 'one-point'].includes(detail.testMethod)) return false
  const input = detail.input || {}
  selectedWellName.value = detail.wellName || props.initialWellName || selectedWellName.value
  activeTestType.value = detail.testMethod || props.initialTestType
  selectedDataTable.value = activeTestType.value
  await nextTick()
  inputRows.value = (detail.inputItems || []).map((item, index) => ({
    sequence: item.testPointNumber ?? index + 1,
    date: detail.testDate || '',
    testType: activeTestType.value,
    recoveryPressure: Number(item.reservoirPressure),
    flowRate: Number(item.testDailyGasProduction),
    equivalentFlowRate: null,
    flowingPressure: Number(item.testFlowPressure)
  }))
  formationPressure.value = Number(input.maximumFormationPressure)
  temperature.value = Number(input.formationTemperature)
  hasMethodData.value = inputRows.value.length > 0
  result.value = storedResult()
  activePanel.value = result.value ? 'analysis' : 'input'
  activeChart.value = 'analysis'
  if (result.value) emit('result-change', result.value, { stored: true })
  await nextTick()
  if (result.value) renderChart()
  return true
}

const restorePersisted = detail => {
  if (!detail?.input || !detail?.result) return
  activeTestType.value = 'isochronal'
  selectedDataTable.value = 'isochronal'
  formationPressure.value = Number(detail.input.maximumFormationPressure)
  temperature.value = Number(detail.input.formationTemperature)
  calculationMethod.value = normalizeCalculationMethod(detail.pressureMethod)
  calculationResultType.value = detail.result.calculationResultType === 'exponential'
    ? 'exponential'
    : 'binomial'
  operationType.value = detail.record?.operationType === 'injection' ? 'injection' : 'production'
  inputRows.value = (detail.input.points || []).map(point => ({
    sequence: point.pointNumber,
    flowRate: point.gasProduction,
    equivalentFlowRate: '',
    flowingPressure: point.flowPressure,
    recoveryPressure: point.reservoirPressure
  }))
  hasMethodData.value = inputRows.value.length > 0
  const restored = {
    wellName: detail.record?.wellName || selectedWellName.value,
    testType: 'isochronal',
    methodName: '等时',
    calculationMethod: calculationMethod.value,
    calculationResultType: calculationResultType.value,
    operationType: operationType.value,
    formationPressure: Number(detail.input.maximumFormationPressure),
    darcyCoefficient: detail.result.darcyCoefficient,
    nonDarcyCoefficient: detail.result.nonDarcyCoefficient,
    productivityCoefficient: detail.result.productivityCoefficient,
    productivityExponent: detail.result.productivityExponent,
    equation: calculationResultType.value === 'exponential'
      ? exponentialEquation(
          calculationMethod.value,
          Number(detail.result.productivityCoefficient),
          Number(detail.result.productivityExponent),
          operationType.value
        )
      : coefficientEquation(
          calculationMethod.value,
          Number(detail.result.darcyCoefficient),
          Number(detail.result.nonDarcyCoefficient),
          operationType.value
        ),
    aofRate: detail.result.openFlowCapacity,
    rSquared: detail.result.rSquared,
    reliability: detail.result.reliabilityDescription || '',
    analysisPoints: (detail.result.analysisPoints || []).map(point => ({
      flowRate: point.x, transformedPressure: point.y
    })),
    regressionLine: (detail.result.regressionLine || []).map(point => ({
      flowRate: point.x, transformedPressure: point.y
    })),
    transientLine: (detail.result.transientLine || []).map(point => ({
      flowRate: point.x, transformedPressure: point.y
    })),
    iprCurves: (detail.result.iprCurves || []).map(curve => ({
      formationPressure: Number(curve.formationPressure) || Number(detail.input.maximumFormationPressure),
      points: (curve.points || []).map(point => ({
        flowRate: point.gasProduction,
        flowingPressure: point.bottomHoleFlowingPressure
      }))
    }))
  }
  restored.iprCurve = restored.iprCurves.at(-1)?.points || []
  result.value = restored
  emit('result-change', restored)
  activePanel.value = 'analysis'
  activeChart.value = 'analysis'
  nextTick(() => {
    if (activeChart.value === 'ipr') renderIprChart()
    else renderChart()
  })
}

const switchChart = async (chartType) => {
  activeChart.value = chartType
  if (activePanel.value !== 'analysis' || !result.value) return
  await nextTick()
  if (chartType === 'ipr') renderIprChart()
  else renderChart()
}

const handleResize = () => chart?.resize()

watch(selectedDataTable, value => {
  if (!value) {
    inputRows.value = []
    hasMethodData.value = false
    result.value = null
    activePanel.value = 'input'
    return
  }
  activeTestType.value = value
  applySourceRows()
})
watch(() => props.initialTestType, value => {
  if (!TEST_TYPES.some(item => item.value === value)) return
  activeTestType.value = value
  if (props.autoSelectData) selectedDataTable.value = value
})
watch(() => props.externalFormationPressure, value => {
  if (Number.isFinite(value)) formationPressure.value = value
})
watch(() => props.externalTemperature, value => {
  if (Number.isFinite(value)) temperature.value = value
})
watch(() => props.externalCalculationMethod, value => {
  const normalized = normalizeCalculationMethod(value)
  if (calculationMethod.value === normalized) return
  calculationMethod.value = normalized
  if (props.storedTest) return void applyStoredTest()
  originalInputSyncKey = ''
  void syncOriginalInputDefaults().catch(error =>
    console.warn('智慧气藏原始试井参数同步失败', error))
  result.value = null
  activePanel.value = 'input'
})
watch(() => props.externalCalculationResult, value => {
  const normalized = value === 'exponential' ? 'exponential' : 'binomial'
  if (calculationResultType.value === normalized) return
  calculationResultType.value = normalized
  if (props.storedTest) return void applyStoredTest()
  result.value = null
  activePanel.value = 'input'
})
watch(() => props.externalOperationType, value => {
  const normalized = value === 'injection' ? 'injection' : 'production'
  if (operationType.value === normalized) return
  operationType.value = normalized
  result.value = null
  activePanel.value = 'input'
})
watch(() => props.pvtResultRows, () => {
  if (calculationMethod.value !== 'pseudo-pressure') return
  result.value = null
  activePanel.value = 'input'
})
watch(() => props.viewKey, async () => {
  selectedWellName.value = props.initialWellName || props.wellNames[0] || ''
  clearWorkspace()
  if (!await applyStoredTest() && selectedWellName.value) {
    await loadWellData()
    await syncOriginalInputDefaults().catch(error =>
      console.warn('智慧气藏原始试井参数同步失败', error))
  }
})
watch(() => props.storedTest, () => { void applyStoredTest() }, { deep: true })

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  if (!await applyStoredTest() && selectedWellName.value) {
    await loadWellData()
    await syncOriginalInputDefaults().catch(error =>
      console.warn('智慧气藏原始试井参数同步失败', error))
  }
})

defineExpose({ analyze, loadWellData, replaceInputRows, switchPanel, getPersistenceSnapshot, restorePersisted, applyStoredTest })

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="binomial-page" :class="{ embedded }">
    <div v-if="!embedded" class="workspace-tabs">
      <div class="workspace-tab">
        <span>{{ taskTitle }}</span>
        <button type="button" title="关闭" @click="clearWorkspace">×</button>
      </div>
    </div>

    <div class="page-body">
      <section v-if="!embedded" class="parameter-panel">
        <div class="panel-title">参数设置</div>
        <el-form label-position="top" size="small" class="parameter-form">
          <el-form-item label="选择PVT表">
            <el-select v-model="selectedPvtTable" placeholder="请选择">
              <el-option label="暂无可用PVT表" value="unavailable" disabled />
            </el-select>
          </el-form-item>
          <el-form-item label="选择数据表">
            <el-select v-model="selectedDataTable" placeholder="请选择">
              <el-option
                v-for="item in productivityTableOptions"
                :key="item.value"
                :label="item.hasData ? item.label : `${item.label}（无数据）`"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
          <div class="form-section">其它数据</div>
          <el-form-item label="计算IPR曲线的最大地层压力（MPa）">
            <el-input-number v-model="formationPressure" :min="0.0001" :controls="false" />
          </el-form-item>
          <el-form-item label="地层温度 (℃)">
            <el-input-number v-model="temperature" :controls="false" />
          </el-form-item>
          <el-form-item label="计算方法">
            <el-radio-group v-model="calculationMethod">
              <el-radio label="pseudo-pressure">拟压力</el-radio>
              <el-radio label="pressure-squared">压力平方法</el-radio>
              <el-radio label="pressure">压力法</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="计算结果">
            <el-radio-group v-model="calculationResultType">
              <el-radio label="binomial">二项式</el-radio>
              <el-radio label="exponential">指数式</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="注采类型">
            <el-radio-group v-model="operationType">
              <el-radio label="production">采气</el-radio>
              <el-radio label="injection" :disabled="activeTestType !== 'isochronal'">注气</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-form>
        <div class="parameter-actions">
          <el-button :loading="calculating" @click="analyze">计算</el-button>
        </div>
        <div v-if="result" class="left-result-fields">
          <template v-if="isOwnedTestType()">
            <label>产能评价方法</label>
            <el-input :model-value="`${methodName}试井`" readonly />
          </template>
          <template v-if="result.calculationResultType === 'exponential'">
            <label>{{ operationType === 'injection' ? '注气能力系数 C' : '产能系数 C' }}</label>
            <el-input :model-value="scientific(result.productivityCoefficient)" readonly />
            <label>{{ operationType === 'injection' ? '注气指数 n' : '产能指数 n' }}</label>
            <el-input :model-value="Number(result.productivityExponent).toFixed(4)" readonly />
          </template>
          <template v-else>
            <label>达西渗流系数 A</label>
            <el-input :model-value="scientific(result.darcyCoefficient)" readonly />
            <label>非达西高速流系数 B</label>
            <el-input :model-value="scientific(result.nonDarcyCoefficient)" readonly />
            <template v-if="activeTestType === 'back-pressure'">
              <label>R²(dless)</label>
              <el-input
                :model-value="result.rSquared == null ? '' : Number(result.rSquared).toFixed(4)"
                readonly
              />
              <label>结果可靠性</label>
              <el-input :model-value="result.reliability" readonly />
            </template>
          </template>
        </div>
      </section>

      <section class="workspace-panel">
        <div v-if="!selectedDataTable" class="blank-grid" aria-label="空白工作区"></div>

        <template v-else-if="activePanel === 'input'">
          <div class="content-title">
            <span>
              {{ selectedWellName || '未选择井' }} - {{ methodName }}试井数据
              <template v-if="!hasMethodData">（数据库暂无该方法数据，可手动填写）</template>
            </span>
            <div>
              <el-button size="small" @click="addRow">增加测点</el-button>
            </div>
          </div>
          <el-table v-loading="loadingData" :data="inputRows" border height="100%">
            <el-table-column type="index" label="序号" width="60" />
            <el-table-column label="日期" min-width="130">
              <template #default="scope">
                <el-input v-model="scope.row.date" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="地层/恢复压力(MPa)" min-width="170">
              <template #default="scope">
                <el-input-number v-model="scope.row.recoveryPressure" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column
              :label="operationType === 'injection' ? '测试注气量(10⁴m³/d)' : '测试气产量(10⁴m³/d)'"
              min-width="175"
            >
              <template #default="scope">
                <el-input-number v-model="scope.row.flowRate" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column v-if="activeTestType === 'one-point'" label="折算测试气产量(10⁴m³/d)" min-width="190">
              <template #default="scope">
                <el-input-number v-model="scope.row.equivalentFlowRate" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column
              :label="operationType === 'injection' ? '井底注入压力(MPa)' : '测试流压(MPa)'"
              min-width="145"
            >
              <template #default="scope">
                <el-input-number v-model="scope.row.flowingPressure" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column v-if="activeTestType === 'isochronal'" label="测点类型" width="90" align="center">
              <template #default="scope">
                <el-tag v-if="scope.$index === inputRows.length - 1" type="danger" size="small">稳定点</el-tag>
                <span v-else>等时点</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="75" align="center">
              <template #default="scope">
                <el-button link type="danger" @click="removeRow(scope.$index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <template v-else-if="selectedDataTable">
          <div class="result-view">
            <div class="chart-mode-switch">
              <label>
                <input
                  type="radio"
                  name="chart-mode"
                  :checked="activeChart === 'analysis'"
                  @change="switchChart('analysis')"
                />
                结果分析图
              </label>
              <label>
                <input
                  type="radio"
                  name="chart-mode"
                  :checked="activeChart === 'ipr'"
                  @change="switchChart('ipr')"
                />
                {{ operationType === 'injection' ? '注气IPR曲线' : 'IPR曲线' }}
              </label>
            </div>
            <el-empty v-if="!result" :description="resultEmptyText" />
            <div v-else class="chart-and-output">
              <div ref="chartEl" class="chart"></div>
            </div>
          </div>
        </template>
      </section>
    </div>

    <div v-if="selectedDataTable" class="bottom-tabs">
      <button :class="{ active: activePanel === 'input' }" @click="switchPanel('input')">数据列表</button>
      <button :class="{ active: activePanel === 'analysis' }" @click="switchPanel('analysis')">
        结果分析
      </button>
    </div>

    <el-dialog v-model="selectorVisible" title="选择井" width="460px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="选择井">
          <el-select v-model="selectedWellName" placeholder="请选择井" style="width: 100%">
            <el-option v-for="wellName in wellNames" :key="wellName" :label="wellName" :value="wellName" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="selectorVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSelection">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
$yellow: #f4d000;
$border: #dcdfe6;

.binomial-page {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  color: #303133;
}

.workspace-tabs {
  height: 32px;
  display: flex;
  align-items: stretch;
  border-bottom: 1px solid $border;
  background: #f3f3f3;
  flex-shrink: 0;
}

.workspace-tab {
  min-width: 160px;
  padding: 0 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: $yellow;
  color: #111;
  font-size: 13px;

  button {
    padding: 0;
    border: 0;
    background: transparent;
    cursor: pointer;
    color: #555;
    font-size: 18px;
    line-height: 1;
  }
}

.page-body {
  flex: 1;
  min-height: 0;
  display: flex;
}

.parameter-panel {
  width: 252px;
  flex-shrink: 0;
  border-right: 1px solid $border;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

.content-title,
.output-title {
  height: 42px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid $border;
  font-weight: 600;
  flex-shrink: 0;
}

.panel-title {
  height: 30px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid $border;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.parameter-form {
  padding: 10px;
  flex-shrink: 0;

  :deep(.el-input-number),
  :deep(.el-select) {
    width: 100%;
  }

  :deep(.el-form-item) {
    margin-bottom: 10px;
  }

  :deep(.el-form-item__label) {
    padding-bottom: 3px;
    color: #333;
    font-size: 12px;
    line-height: 18px;
  }

  :deep(.el-radio) {
    height: 22px;
    margin-right: 10px;
  }
}

.inline-field {
  display: flex;
  width: 100%;
  gap: 6px;
}

.form-section {
  margin: 2px 0 8px;
  padding-bottom: 5px;
  border-bottom: 1px solid #999;
  font-size: 12px;
  font-weight: 600;
}

.parameter-actions {
  padding: 0 10px 12px;
  display: flex;

  .el-button {
    width: 60px;
    border-color: #111;
    background: #050505;
    color: #fff;

    &:hover,
    &:focus {
      border-color: #333;
      background: #333;
      color: #fff;
    }
  }
}

.left-result-fields {
  padding: 0 10px 12px;

  label {
    display: block;
    margin: 0 0 5px;

    &:not(:first-child) {
      margin-top: 10px;
    }
  }
}

.workspace-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;

  :deep(.el-table) {
    flex: 1;
  }
}

.blank-grid {
  width: 100%;
  height: 100%;
  background-color: #fff;
  background-image:
    linear-gradient(to right, #e7e7e7 1px, transparent 1px),
    linear-gradient(to bottom, #e7e7e7 1px, transparent 1px);
  background-size: 92px 46px;
}

.result-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chart-mode-switch {
  min-height: 34px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 12px;
  flex-shrink: 0;

  label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    cursor: pointer;
  }
}

.chart-and-output {
  flex: 1;
  display: flex;
  min-height: 0;
}

.chart {
  flex: 1;
  min-width: 0;
  height: 100%;
}

.output-panel {
  width: 285px;
  padding: 0 14px 14px;
  border-left: 1px solid $border;
  overflow-y: auto;

  .output-title {
    margin: 0 -14px 12px;
  }

  label {
    display: block;
    margin: 12px 0 5px;
  }
}

.bottom-tabs {
  height: 38px;
  display: flex;
  justify-content: center;
  border-top: 1px solid $border;
  flex-shrink: 0;

  button {
    min-width: 150px;
    border: 0;
    border-right: 1px solid $border;
    background: #fff;
    cursor: pointer;

    &:disabled {
      color: #c0c4cc;
      cursor: not-allowed;
    }

    &.active {
      background: $yellow;
      color: #111;
      font-weight: 600;
    }
  }
}
</style>
