<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import axios from 'axios'
import { ElMessage } from 'element-plus'

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
  externalTemperature: { type: Number, default: null }
})
const TEST_TYPES = [
  { value: 'back-pressure', label: '回压' },
  { value: 'one-point', label: '一点法（迁移）' },
  { value: 'isochronal', label: '等时' },
  { value: 'modified-isochronal', label: '修正等时' }
]
const ONE_POINT_ALPHA = 0.252

const selectorVisible = ref(false)
const selectedWellName = ref(props.initialWellName || props.wellNames[0] || '')
const activeTestType = ref(TEST_TYPES.some(item => item.value === props.initialTestType)
  ? props.initialTestType
  : 'back-pressure')
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
const calculationMethod = ref('pressure')
const calculationResultType = ref('binomial')
const loadingData = ref(false)
const calculating = ref(false)
const result = ref(null)
const activePanel = ref('input')
const hasMethodData = ref(false)
const chartEl = ref(null)
let chart = null

const methodName = computed(
  () => TEST_TYPES.find(item => item.value === activeTestType.value)?.label || '回压'
)
const taskTitle = computed(() => `产能试井-${methodName.value}试井`)
const productivityTableOptions = computed(() => TEST_TYPES.map(item => ({
  value: item.value,
  label: `${selectedWellName.value || '当前井'} ${item.label}试井数据`,
  hasData: sourceRows.value.some(row => row.testType === item.value)
})))
const showsStablePoint = computed(() => activeTestType.value === 'isochronal')
const resultEmptyText = computed(() => {
  const chartName = activePanel.value === 'ipr' ? 'IPR曲线' : '结果分析图'
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

const toBoolean = (value) => {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value !== 0
  return ['true', '1', 'yes', 'y', '是', '稳定'].includes(String(value || '').trim().toLowerCase())
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
  ])),
  stabilized: toBoolean(readField(row, ['stabilized', 'isStabilized', 'stable', '稳定点']))
})

const createBlankRow = (sequence) => ({
  sequence,
  date: '',
  testType: activeTestType.value,
  recoveryPressure: null,
  flowRate: null,
  equivalentFlowRate: null,
  flowingPressure: null,
  stabilized: false
})

const applySourceRows = () => {
  const matching = sourceRows.value.filter(row => row.testType === activeTestType.value)
  hasMethodData.value = matching.length > 0
  inputRows.value = matching.map((row, index) => ({
    ...row,
    sequence: row.sequence ?? index + 1,
    stabilized: Boolean(row.stabilized)
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
}

const clearWorkspace = () => {
  selectedPvtTable.value = ''
  selectedDataTable.value = ''
  inputRows.value = []
  hasMethodData.value = false
  result.value = null
  activePanel.value = 'input'
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
      : Number(row.recoveryPressure),
    stabilized: Boolean(row.stabilized)
  })),
  migrationPoints: activeTestType.value === 'one-point'
    ? sourceRows.value
      .filter(row => row.testType === 'back-pressure')
      .map((row, index) => ({
        sequence: Number(row.sequence || index + 1),
        flowRate: row.flowRate,
        flowingPressure: row.flowingPressure,
        recoveryPressure: row.recoveryPressure,
        stabilized: false
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
  ])),
  stabilized: toBoolean(readField(point, ['stabilized', 'isStabilized', 'stable']))
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

const createIprCurve = (pressure, darcy, nonDarcy) => {
  if (![pressure, darcy, nonDarcy].every(Number.isFinite)) return []
  return Array.from({ length: 41 }, (_, index) => {
    const drawdown = pressure * index / 40
    let flowRate = 0
    if (nonDarcy > 1e-12) {
      flowRate = (-darcy + Math.sqrt(darcy ** 2 + 4 * nonDarcy * drawdown)) / (2 * nonDarcy)
    } else if (darcy > 1e-12) {
      flowRate = drawdown / darcy
    }
    return { flowRate, flowingPressure: pressure - drawdown }
  })
}

const normalizeLocalPoints = (points, fallbackPressure, minimum) => {
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
      if (recoveryPressure <= flowingPressure) throw new Error('地层/恢复压力必须大于测试流压')
      return {
        sequence: Number(point.sequence || index + 1),
        flowRate,
        equivalentFlowRate: point.equivalentFlowRate === null || point.equivalentFlowRate === ''
          ? null
          : Number(point.equivalentFlowRate),
        transformedPressure: (recoveryPressure - flowingPressure) / flowRate,
        sourcePressure: recoveryPressure,
        stabilized: Boolean(point.stabilized)
      }
    })
    .sort((left, right) => left.sequence - right.sequence)
  if (normalized.length < minimum) throw new Error(`${methodName.value}至少需要 ${minimum} 个有效测试点`)
  return normalized
}

const regressLocalPoints = (points) => {
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

const calculateLocally = (payload) => {
  const maximumPressure = Number(payload.formationPressure)
  if (!Number.isFinite(maximumPressure) || maximumPressure <= 0) {
    throw new Error('计算IPR曲线的最大地层压力必须大于 0')
  }
  const minimum = payload.testType === 'one-point' ? 1 : 2
  const points = normalizeLocalPoints(payload.points, payload.formationPressure, minimum)
  let coefficients
  let analysisPoints = points

  if (payload.testType === 'one-point') {
    const point = points[0]
    let migratedB
    let darcyCoefficient
    if (Number.isFinite(point.equivalentFlowRate) && point.equivalentFlowRate > 0) {
      const equivalentDarcyRate = ONE_POINT_ALPHA / (1 - ONE_POINT_ALPHA) * point.equivalentFlowRate
      migratedB = point.transformedPressure / (point.flowRate + equivalentDarcyRate)
      darcyCoefficient = migratedB * equivalentDarcyRate
      analysisPoints = [
        point,
        {
          ...point,
          sequence: point.sequence + 1,
          flowRate: point.equivalentFlowRate,
          transformedPressure: darcyCoefficient + migratedB * point.equivalentFlowRate,
          equivalent: true
        }
      ]
    } else {
      migratedB = payload.migrationNonDarcyCoefficient === null || payload.migrationNonDarcyCoefficient === ''
        ? NaN
        : Number(payload.migrationNonDarcyCoefficient)
      if (!Number.isFinite(migratedB) || migratedB < 0) {
        const migrationPoints = normalizeLocalPoints(payload.migrationPoints, payload.formationPressure, 2)
        migratedB = regressLocalPoints(migrationPoints).nonDarcyCoefficient
      }
      darcyCoefficient = point.transformedPressure - migratedB * point.flowRate
    }
    if (darcyCoefficient < 0) throw new Error('迁移系数 B 过大，计算得到的达西系数 A 小于 0')
    coefficients = { darcyCoefficient, nonDarcyCoefficient: migratedB, rSquared: null, transientDarcyCoefficient: null }
  } else {
    coefficients = regressLocalPoints(points)
    if (payload.testType === 'isochronal') {
      const stabilizedPoint = [...points].reverse().find(point => point.stabilized)
      if (stabilizedPoint) {
        const stableDarcy = stabilizedPoint.transformedPressure -
          coefficients.nonDarcyCoefficient * stabilizedPoint.flowRate
        if (stableDarcy < 0) throw new Error('稳定点与等时测试点不匹配，计算得到的达西系数 A 小于 0')
        coefficients = {
          ...coefficients,
          darcyCoefficient: stableDarcy,
          transientDarcyCoefficient: coefficients.darcyCoefficient
        }
      }
    }
  }

  const { darcyCoefficient, nonDarcyCoefficient, rSquared, transientDarcyCoefficient } = coefficients
  const maximumRate = Math.max(...analysisPoints.map(point => point.flowRate), 1)
  const lineEnd = maximumRate * (payload.testType === 'one-point' ? 1.02 : 1.08)
  const makeLine = darcy => [
    { flowRate: 0, transformedPressure: darcy },
    { flowRate: lineEnd, transformedPressure: darcy + nonDarcyCoefficient * lineEnd }
  ]
  const iprCurve = createIprCurve(maximumPressure, darcyCoefficient, nonDarcyCoefficient)
  const iprCurves = []
  for (let pressure = 5; pressure <= maximumPressure + 1e-12; pressure += 5) {
    iprCurves.push({ formationPressure: pressure, points: createIprCurve(pressure, darcyCoefficient, nonDarcyCoefficient) })
  }
  if (!iprCurves.length || Math.abs(iprCurves[iprCurves.length - 1].formationPressure - maximumPressure) > 1e-12) {
    iprCurves.push({ formationPressure: maximumPressure, points: iprCurve })
  }

  return {
    wellName: payload.wellName,
    testType: payload.testType,
    methodName: methodName.value,
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
    equation: `Pr - Pwf = ${darcyCoefficient.toPrecision(6)} × qsc + ${nonDarcyCoefficient.toPrecision(6)} × qsc²`,
    analysisPoints,
    regressionLine: makeLine(darcyCoefficient),
    transientLine: transientDarcyCoefficient === null ? [] : makeLine(transientDarcyCoefficient),
    iprCurve,
    iprCurves
  }
}

const normalizeCalculationResult = (response) => {
  const payload = getCalculationPayload(response)
  const darcyCoefficient = toNumber(readField(payload, [
    'darcyCoefficient', 'darcy_coefficient', 'coefficientA', 'coefficient_a', 'darcyFlowCoefficient', 'A', 'a'
  ]))
  const nonDarcyCoefficient = toNumber(readField(payload, [
    'nonDarcyCoefficient', 'non_darcy_coefficient', 'coefficientB', 'coefficient_b',
    'nonDarcyFlowCoefficient', 'B', 'b'
  ]))
  const resultPressure = normalizePressure(readField(payload, [
    'formationPressure', 'formation_pressure', 'reservoirPressure', 'reservoir_pressure', 'pr'
  ])) ?? Number(formationPressure.value)
  const rSquared = toNumber(readField(payload, [
    'rSquared', 'r_squared', 'coefficientOfDetermination', 'determinationCoefficient', 'r2', 'R2'
  ]))

  let analysisPoints = validPointList(
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
          transformedPressure: Number.isFinite(flowRate) && flowRate > 0 && Number.isFinite(flowingPressure)
            ? (sourcePressure - flowingPressure) / flowRate
            : null,
          sourcePressure,
          stabilized: Boolean(row.stabilized)
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

  const transientLine = validPointList(
    readField(payload, ['transientLine', 'transient_line', 'isochronalLine', 'unstableLine']),
    normalizeLinePoint
  ).filter(point => Number.isFinite(point.transformedPressure))

  let iprCurve = validPointList(
    readField(payload, ['iprCurve', 'ipr_curve', 'curve', 'iprPoints']),
    normalizeIprPoint
  ).filter(point => Number.isFinite(point.flowingPressure))
  if (!iprCurve.length) iprCurve = createIprCurve(resultPressure, darcyCoefficient, nonDarcyCoefficient)

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
      ? `Pr - Pwf = ${darcyCoefficient.toPrecision(6)} × qsc + ${nonDarcyCoefficient.toPrecision(6)} × qsc²`
      : ''
  )

  return {
    ...payload,
    wellName: readField(payload, ['wellName', 'well_name']) || selectedWellName.value,
    testType: normalizeTestType(readField(payload, ['testType', 'test_type'])) || activeTestType.value,
    methodName: readField(payload, ['methodName', 'method_name', 'method', 'evaluationMethod']) || methodName.value,
    formationPressure: resultPressure,
    darcyCoefficient,
    nonDarcyCoefficient,
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

const analyze = async () => {
  if (!selectedWellName.value) {
    selectorVisible.value = true
    return
  }
  if (!selectedDataTable.value) {
    ElMessage.warning('请选择产能表')
    return
  }
  if (calculationMethod.value !== 'pressure') {
    ElMessage.info('当前仅实现压力法')
    return
  }
  if (calculationResultType.value !== 'binomial') {
    ElMessage.info('当前仅实现二项式计算')
    return
  }
  calculating.value = true
  try {
    const response = { data: calculateLocally(buildPayload()) }
    result.value = normalizeCalculationResult(response)
    activePanel.value = 'analysis'
    await nextTick()
    renderChart()
    ElMessage.success(`${selectedWellName.value} ${methodName.value}压力形式计算完成`)
  } catch (error) {
    console.error('二项式压力形式计算失败', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '二项式压力形式计算失败')
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
  const series = [
    {
      name: '测试点',
      type: 'scatter',
      symbolSize: 10,
      data: analysisPoints.map(point => [point.flowRate, point.transformedPressure]),
      itemStyle: { color: '#4d78c9' }
    },
    {
      name: showsStablePoint.value ? '稳定线' : '回归线',
      type: 'line',
      showSymbol: false,
      data: regressionLine.map(point => [point.flowRate, point.transformedPressure]),
      lineStyle: { color: '#222', width: 2 }
    }
  ]

  if (transientLine.length) {
    series.push({
      name: '等时线',
      type: 'line',
      showSymbol: false,
      data: transientLine.map(point => [point.flowRate, point.transformedPressure]),
      lineStyle: { color: '#f2a900', width: 2, type: 'dashed' }
    })
  }

  chartInstance.setOption({
    animation: false,
    title: {
      text: `${methodName.value}试井分析图`,
      left: 'center',
      textStyle: { fontSize: 16, fontWeight: 600 }
    },
    tooltip: { trigger: 'axis' },
    legend: { right: 18, top: 12 },
    grid: { left: 82, right: 34, top: 60, bottom: 62 },
    xAxis: {
      type: 'value',
      scale: true,
      name: 'qsc(10⁴m³/d)',
      nameLocation: 'middle',
      nameGap: 38,
      splitLine: { lineStyle: { color: '#e7edf6' } }
    },
    yAxis: {
      type: 'value',
      scale: true,
      name: '(Pr - Pwf) / qsc\n(MPa/(10⁴m³/d))',
      nameLocation: 'middle',
      nameGap: 58,
      splitLine: { lineStyle: { color: '#e7edf6' } }
    },
    series,
    graphic: [{
      type: 'group',
      right: 46,
      bottom: 70,
      children: [
        {
          type: 'rect',
          shape: { x: 0, y: 0, width: 310, height: 62 },
          style: { fill: 'rgba(255,255,255,0.88)', stroke: '#d8dee8' }
        },
        {
          type: 'text',
          style: {
            x: 12,
            y: 12,
            text: result.value.rSquared === null || result.value.rSquared === undefined
              ? result.value.equation
              : `${result.value.equation}\nR² = ${Number(result.value.rSquared).toFixed(4)}`,
            font: '14px sans-serif',
            fill: '#444',
            lineHeight: 24
          }
        }
      ]
    }]
  }, true)
  chartInstance.resize()
}

const renderIprChart = () => {
  if (!result.value) return
  const chartInstance = ensureChart()
  if (!chartInstance) return
  const iprCurves = Array.isArray(result.value.iprCurves) && result.value.iprCurves.length
    ? result.value.iprCurves
    : [{ formationPressure: result.value.formationPressure, points: result.value.iprCurve || [] }]
  chartInstance.setOption({
    animation: false,
    title: {
      text: 'IPR曲线',
      left: 'center',
      textStyle: { fontSize: 16, fontWeight: 600 }
    },
    tooltip: { trigger: 'axis' },
    legend: { right: 28, top: 20, orient: 'vertical' },
    grid: { left: 75, right: 145, top: 58, bottom: 60 },
    xAxis: {
      type: 'value',
      name: 'qsc(10⁴m³/d)',
      nameLocation: 'middle',
      nameGap: 38,
      splitLine: { lineStyle: { color: '#e7edf6' } }
    },
    yAxis: {
      type: 'value',
      name: 'Pwf(MPa)',
      nameLocation: 'middle',
      nameGap: 48,
      splitLine: { lineStyle: { color: '#e7edf6' } }
    },
    series: iprCurves.map((curve, index) => ({
      name: `Pr${index + 1}=${Number(curve.formationPressure).toFixed(0)} MPa`,
      type: 'line',
      showSymbol: false,
      smooth: true,
      data: (curve.points || []).map(point => [point.flowRate, point.flowingPressure]),
      lineStyle: { width: 2 }
    }))
  }, true)
  chartInstance.resize()
}

const switchPanel = async (panel) => {
  activePanel.value = panel
  if (panel === 'input' || !result.value) return
  await nextTick()
  if (panel === 'ipr') renderIprChart()
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
watch(() => props.viewKey, async () => {
  selectedWellName.value = props.initialWellName || props.wellNames[0] || ''
  clearWorkspace()
  if (selectedWellName.value) await loadWellData()
})

onMounted(async () => {
  window.addEventListener('resize', handleResize)
  if (selectedWellName.value) await loadWellData()
})

defineExpose({ analyze, loadWellData, switchPanel })

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
        </el-form>
        <div class="parameter-actions">
          <el-button :loading="calculating" @click="analyze">计算</el-button>
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
              <el-button size="small" :loading="loadingData" @click="loadWellData">读取数据库</el-button>
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
            <el-table-column label="测试气产量(10⁴m³/d)" min-width="175">
              <template #default="scope">
                <el-input-number v-model="scope.row.flowRate" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column v-if="activeTestType === 'one-point'" label="折算测试气产量(10⁴m³/d)" min-width="190">
              <template #default="scope">
                <el-input-number v-model="scope.row.equivalentFlowRate" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="测试流压(MPa)" min-width="145">
              <template #default="scope">
                <el-input-number v-model="scope.row.flowingPressure" :controls="false" size="small" />
              </template>
            </el-table-column>
            <el-table-column v-if="showsStablePoint" label="稳定点" width="90" align="center">
              <template #default="scope">
                <el-checkbox v-model="scope.row.stabilized" />
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
          <el-empty v-if="!result" :description="resultEmptyText" />
          <div v-else class="chart-and-output">
            <div ref="chartEl" class="chart"></div>
            <aside class="output-panel">
              <div class="output-title">输出结果</div>
              <label>产能评价方法</label>
              <el-input :model-value="result?.methodName" readonly />
              <label>达西渗流系数 A</label>
              <el-input :model-value="scientific(result?.darcyCoefficient)" readonly />
              <label>非达西高速流系数 B</label>
              <el-input :model-value="scientific(result?.nonDarcyCoefficient)" readonly />
              <template v-if="result?.rSquared !== null && result?.rSquared !== undefined">
                <label>R²(dless)</label>
                <el-input :model-value="Number(result.rSquared).toFixed(4)" readonly />
                <label>结果</label>
                <el-input :model-value="result?.reliability" readonly />
              </template>
            </aside>
          </div>
        </template>
      </section>
    </div>

    <div v-if="selectedDataTable" class="bottom-tabs">
      <button :class="{ active: activePanel === 'input' }" @click="switchPanel('input')">输入参数</button>
      <button :class="{ active: activePanel === 'analysis' }" @click="switchPanel('analysis')">
        结果分析图
      </button>
      <button :class="{ active: activePanel === 'ipr' }" @click="switchPanel('ipr')">
        IPR曲线
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
  overflow-y: auto;

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

.chart-and-output {
  height: 100%;
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
