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
const selectedPvtId = ref('')
const selectedGas = ref({ ...GAS_DEFAULTS })
const rows = ref(staticRows())
const importedFileName = ref('修正等时验证数据（静态）')
const maximumFormationPressure = ref(56.34)
const formationTemperature = ref(120)
const calculationMethod = ref('pseudo-pressure')
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
const gasWithDefaults = value => Object.fromEntries(Object.entries(GAS_DEFAULTS).map(([key, fallback]) => {
  const current = value?.[key]
  return [key, current === null || current === undefined || current === '' ? fallback : current]
}))
const normalizeMethod = value => ({ 1: 'pressure', 2: 'pressure-squared', 3: 'pseudo-pressure',
  '压力形式': 'pressure', '压力平方形式': 'pressure-squared', '拟压力形式': 'pseudo-pressure' }[value] ||
  (['pressure', 'pressure-squared', 'pseudo-pressure'].includes(value) ? value : 'pseudo-pressure'))

const loadPvtOptions = async () => {
  pvtOptions.value = []
  if (!props.wellName) return void (selectedPvtId.value = '')
  try {
    const records = (unwrap(await pvtStorageApi.list(props.projectId, props.gasReservoirId, props.wellName)) || [])
      .filter(item => item.pvtName !== 'PVT表1（静态验证数据）')
    pvtOptions.value = records
    if (!records.some(item => String(item.pvtId) === String(selectedPvtId.value))) {
      selectedPvtId.value = records.length ? String(records[0].pvtId) : ''
    }
  } catch (error) {
    selectedPvtId.value = ''
    console.warn('PVT数据库记录读取失败', error)
  }
}

const loadPvtDetail = async () => {
  markInputDirty()
  if (!selectedPvtId.value) return void (selectedGas.value = { ...GAS_DEFAULTS })
  const detail = unwrap(await pvtStorageApi.getDetail(selectedPvtId.value, props.projectId,
    props.gasReservoirId, props.wellName))
  const settings = typeof detail.settings?.gas === 'string'
    ? JSON.parse(detail.settings.gas || '{}') : (detail.settings?.gas || {})
  selectedGas.value = gasWithDefaults({ ...(detail.gasInput || {}), ...settings })
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
  analysisCurves.every(config => result?.analysisSeries?.some(series =>
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
  const walk = (node, insideWell = false, insideModified = false) => {
    if (!node || typeof node !== 'object') return
    if (Array.isArray(node)) return node.forEach(item => walk(item, insideWell, insideModified))
    const label = nodeLabel(node)
    const inWell = insideWell || label === props.wellName || node.wellName === props.wellName
    const inModified = insideModified || Number(node.nodeType ?? node.type) ===
      NODETYPE.NodeType_ProductivityEvaluationModifiedIsochronalWellTest || label.includes('修正等时')
    if (inWell && inModified) candidateNodeIds(node).forEach(id => candidates.add(id))
    nodeChildren(node).forEach(child => walk(child, inWell, inModified))
  }
  walk(unwrap(response))
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
  throw new Error('原平台已完成初始化，但未找到对应修正等时评价主键')
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

const calculateResult = async () => {
  const validRows = rows.value.filter(row =>
    [row.flowRate, row.recoveryPressure, row.flowingPressure].every(value => Number.isFinite(Number(value))))
  if (validRows.length < 2) throw new Error('至少需要两个有效测试点')
  if (validRows.some(row => Number(row.flowRate) <= 0 ||
      Number(row.recoveryPressure) <= Number(row.flowingPressure))) {
    throw new Error('测试气产量必须大于0，且地层/恢复压力必须大于测试流压')
  }
  const method = normalizeMethod(calculationMethod.value)
  const evaluationForm = evaluationFormByMethod[method]
  const evaluationId = await resolveEvaluationId(method)
  const gas = gasWithDefaults(selectedGas.value)
  if (!gas.gasType || !Number.isFinite(Number(gas.specificGravity)) || Number(gas.specificGravity) <= 0) {
    throw new Error('所选PVT性质缺少有效的气体类型或天然气相对密度')
  }
  const input = {
    id: evaluationId, ProductivityEvaluationId: evaluationId,
    originalFormationPressure: Number(maximumFormationPressure.value),
    formationTemperature: Number(formationTemperature.value), horizontalSectionLength: 0,
    skinFactor: 0, permeability: 0, thickness: 0, gasDrainageRadius: 0, wellboreRadius: 0,
    gasType: gas.gasType, specificGravity: Number(gas.specificGravity),
    hydrogenSulfide: Number(gas.hydrogenSulfide || 0), carbonDioxide: Number(gas.carbonDioxide || 0),
    nitrogen: Number(gas.nitrogen || 0),
    condensateOilDensityUnderStandardCondition: Number(gas.condensateOilDensity || 0),
    modificationMethod: Number(gas.modificationMethod || 0),
    deviationFactorMethod: Number(gas.deviationFactorMethod || 0),
    viscosityMethod: Number(gas.viscosityMethod || 0), edges: {},
    condensateOilDensity: Number(gas.condensateOilDensity || 0)
  }
  const response = await productivityEvaluationApi.calculate(props.wellName, {
    gasReservoirId: Number(props.gasReservoirId), projectId: Number(props.projectId),
    evaluationId, deletePointIds: [], input,
    inputItems: validRows.map((row, index) => ({ testPointNumber: index + 1,
      reserviorPressure: Number(row.recoveryPressure),
      testDailyGasProduction: Number(row.flowRate), testFlowPressure: Number(row.flowingPressure),
      testDailyOilProduction: 0 })),
    evaluationForm, evaluationType: 4, wellName: props.wellName
  }, { silentError: true })
  let detail = response?.data?.data ?? response?.data ?? response
  if (!detail?.output) {
    const resultResponse = await productivityEvaluationApi.getResult(
      props.projectId, props.gasReservoirId, evaluationId, { silentError: true }
    )
    detail = resultResponse?.data?.data ?? resultResponse?.data ?? resultResponse
  }
  evaluationIds.value = { ...evaluationIds.value, [method]: evaluationId }
  return { ...parseResult(detail), calculationMethod: method, evaluationId }
}

const saveResult = async (result, pvtId) => {
  const gas = selectedGas.value
  const chartPoints = result.analysisSeries.flatMap(series => series.data.map((point, index) => ({
    curveType: series.curveType, pointNumber: index + 1, xValue: point.x, yValue: point.y,
    deleted: point.deleted, dataLabel: point.dataLabel
  })))
  const iprPoints = result.iprSeries.flatMap(series => series.data.map((point, index) => ({
    curveNumber: series.curveNumber, pointNumber: index + 1, gasProduction: point.x,
    bottomHoleFlowingPressure: point.y, deleted: point.deleted, dataLabel: point.dataLabel
  })))
  const saved = unwrap(await productivityTestsApi.save({
    testId: props.testId ? Number(props.testId) : null, projectId: Number(props.projectId),
    gasReservoirId: Number(props.gasReservoirId), wellName: props.wellName, pvtId,
    operationType: operationType.value, testMethod: 'modified-isochronal',
    testDate: rows.value.find(row => row.date)?.date || testDate.value,
    wellType: null, replaceInput: !props.testId || inputDirty.value,
    input: { maximumFormationPressure: Number(maximumFormationPressure.value),
      formationTemperature: Number(formationTemperature.value), onePointAlpha: null,
      gasType: gas.gasType, specificGravity: Number(gas.specificGravity), hydrogenSulfide: Number(gas.hydrogenSulfide || 0),
      carbonDioxide: Number(gas.carbonDioxide || 0), nitrogen: Number(gas.nitrogen || 0),
      condensateOilDensity: gas.condensateOilDensity, modificationMethod: String(gas.modificationMethod ?? ''),
      deviationFactorMethod: String(gas.deviationFactorMethod ?? ''), viscosityMethod: String(gas.viscosityMethod ?? '') },
    inputItems: rows.value.filter(row =>
      [row.flowRate, row.recoveryPressure, row.flowingPressure].every(value => Number.isFinite(Number(value))))
      .map((row, index) => ({ testPointNumber: index + 1,
      testDailyGasProduction: Number(row.flowRate), reservoirPressure: Number(row.recoveryPressure),
      testFlowPressure: Number(row.flowingPressure) })),
    result: { pressureMethod: result.calculationMethod, evaluationId: result.evaluationId,
      darcySeepageCoefficient: result.darcyCoefficient,
      nonDarcySeepageCoefficient: result.nonDarcyCoefficient, openFlowCapacity: result.aofRate,
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
    const result = await calculateResult()
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

const loadTest = async () => {
  const sequence = ++loadSequence
  resultDirty.value = false
  currentResult.value = null
  activePanel.value = 'input'
  if (!props.testId) {
    selectedPvtId.value = pvtOptions.value.length ? String(pvtOptions.value[0].pvtId) : ''
    selectedGas.value = { ...GAS_DEFAULTS }; rows.value = staticRows()
    importedFileName.value = '修正等时验证数据（静态）'; maximumFormationPressure.value = 56.34
    formationTemperature.value = 120; calculationMethod.value = 'pseudo-pressure'; testDate.value = STATIC_DATE
    operationType.value = 'production'; inputDirty.value = true
    evaluationIds.value = {}
    if (selectedPvtId.value) {
      try {
        const detail = unwrap(await pvtStorageApi.getDetail(selectedPvtId.value, props.projectId,
          props.gasReservoirId, props.wellName))
        const settings = typeof detail.settings?.gas === 'string'
          ? JSON.parse(detail.settings.gas || '{}') : (detail.settings?.gas || {})
        if (sequence === loadSequence) selectedGas.value = gasWithDefaults({ ...(detail.gasInput || {}), ...settings })
      } catch (error) { console.warn('默认PVT性质明细读取失败', error) }
    }
    return
  }
  loading.value = true
  try {
    const detail = unwrap(await productivityTestsApi.detail(props.testId)); const input = detail.input || {}
    if (sequence !== loadSequence) return
    selectedPvtId.value = pvtOptions.value.some(item => Number(item.pvtId) === Number(detail.pvtId))
      ? String(detail.pvtId) : ''
    selectedGas.value = gasWithDefaults(input); maximumFormationPressure.value = input.maximumFormationPressure
    formationTemperature.value = input.formationTemperature; calculationMethod.value = normalizeMethod(detail.result?.pressureMethod)
    operationType.value = detail.operationType || 'production'; testDate.value = detail.testDate
    evaluationIds.value = Object.fromEntries((detail.evaluations || []).map(item =>
      [normalizeMethod(item.pressureMethod), Number(item.evaluationId)]))
    rows.value = normalizeRows(detail.inputItems)
    importedFileName.value = `${detail.testName}已保存数据`
    const result = detail.result || {}; const chartItems = result.chartPoints || []
    const analysisSeries = analysisCurves.map(config => ({ ...config,
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
      data: points.map(point => ({ x: Number(point.gasProduction),
        y: Number(point.bottomHoleFlowingPressure), deleted: Boolean(point.deleted),
        dataLabel: point.dataLabel || '' }))
    })).sort((a, b) => a.curveNumber - b.curveNumber)
    let loadedResult = { calculationMethod: result.pressureMethod, evaluationId: result.evaluationId,
      formationPressure: Number(input.maximumFormationPressure),
      darcyCoefficient: result.darcySeepageCoefficient,
      nonDarcyCoefficient: result.nonDarcySeepageCoefficient, aofRate: result.openFlowCapacity,
      gradient: result.gradient, intercept: result.intercept, rSquared: result.rSquared,
      reliabilityLevel: result.reliabilityLevel, reliability: result.reliabilityDescription,
      analysisSeries, iprSeries }
    inputDirty.value = false
    if (!completeResult(loadedResult)) {
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

const renderChart = () => {
  if (!chartEl.value || !currentResult.value || activePanel.value !== 'analysis') return
  if (chart && chart.getDom() !== chartEl.value) {
    chart.dispose()
    chart = null
  }
  chart ||= echarts.getInstanceByDom(chartEl.value) || echarts.init(chartEl.value)
  const result = currentResult.value; const isIpr = activeChart.value === 'ipr'
  const formationPressure = Number(result.formationPressure || maximumFormationPressure.value)
  const iprYAxisMax = Number.isFinite(formationPressure) && formationPressure > 0
    ? Math.ceil(formationPressure / 10) * 10 : undefined
  const visible = points => points.filter(point => !point.deleted).map(point => [point.x, point.y])
  const series = isIpr
    ? result.iprSeries.map(item => ({ name: `Pr${item.curveNumber}=${compact(formationPressure * item.curveNumber / 10)} MPa`,
      type: 'line', smooth: true, showSymbol: false, lineStyle: { width: 2 }, data: visible(item.data) }))
    : result.analysisSeries.map(item => ({ name: `${item.name}${legendUnit(result.calculationMethod)}`,
      type: ['regularized', 'stable'].includes(item.curveType) ? 'scatter' : 'line',
      z: ['regularized', 'stable'].includes(item.curveType) ? 5 : 2,
      symbolSize: item.curveType === 'stable' ? 12 : 10,
      showSymbol: ['regularized', 'stable'].includes(item.curveType),
      itemStyle: { color: item.color }, lineStyle: { color: item.color, width: 2,
        type: item.curveType === 'shifted-regression' ? 'dotted' : 'solid' }, data: visible(item.data) }))
  const equation = `${equationLeft(result.calculationMethod)} = ${scientific(result.darcyCoefficient)} qsc + ${scientific(result.nonDarcyCoefficient)} qsc²\nR² = ${Number(result.rSquared).toFixed(4)}`
  chart.setOption({ animation: false, color: ['#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de', '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#2ec7c9'],
    title: { text: isIpr ? 'IPR曲线' : '修正等时试井分析图', left: 'center', top: 8,
      textStyle: { fontSize: 17, fontWeight: 600, color: '#333' } },
    tooltip: { trigger: isIpr ? 'axis' : 'item' },
    legend: { type: 'scroll', orient: 'vertical', right: 22, top: 52,
      itemWidth: 17, itemHeight: 10, backgroundColor: 'rgba(255,255,255,.9)',
      borderColor: '#e5e9f0', borderWidth: 1, padding: 9 },
    grid: { left: 92, right: isIpr ? 205 : 245, top: 70, bottom: 70 },
    xAxis: { type: 'value', scale: !isIpr, name: 'qsc(10⁴m³/d)', nameLocation: 'middle', nameGap: 42,
      min: isIpr ? 0 : undefined,
      minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } },
      splitLine: { lineStyle: { color: '#dfe6f1' } } },
    yAxis: { type: 'value', scale: !isIpr, min: isIpr ? 0 : undefined,
      max: isIpr ? iprYAxisMax : undefined,
      name: isIpr ? 'Pwf (MPa)' : analysisUnit(result.calculationMethod),
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

watch(() => props.testId, loadTest)
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
          <label><input v-model="calculationMethod" type="radio" value="pseudo-pressure" @change="invalidateResult" />拟压力</label>
          <label><input v-model="calculationMethod" type="radio" value="pressure-squared" @change="invalidateResult" />压力平方法</label>
          <label><input v-model="calculationMethod" type="radio" value="pressure" @change="invalidateResult" />压力法</label>
        </fieldset>
        <fieldset class="radios"><legend>注采类型</legend>
          <label><input v-model="operationType" type="radio" value="production" />采气</label>
          <label class="disabled-option" title="注气计算暂未开放"><input type="radio" value="injection" disabled />注气</label>
        </fieldset>
        <fieldset class="radios"><legend>计算结果</legend><label><input checked disabled type="radio" />二项式</label></fieldset>
        <div class="action-buttons">
          <button type="button" class="calculate" :disabled="calculating || saving" @click="calculate">{{ calculating ? '计算中…' : '计算' }}</button>
          <button type="button" class="save" :disabled="!currentResult || !resultDirty || calculating || saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
        <div v-if="currentResult" class="inline-output">
          <label>达西渗流项系数A<input :value="scientific(currentResult.darcyCoefficient)" readonly /></label>
          <label>非达西渗流项系数B<input :value="scientific(currentResult.nonDarcyCoefficient)" readonly /></label>
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
