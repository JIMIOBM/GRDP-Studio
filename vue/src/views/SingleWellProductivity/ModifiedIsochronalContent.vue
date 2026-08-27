<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { productivityEvaluationApi } from '@/api/docker'
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

const STATIC_PVT_KEY = 'static-validation-pvt-1'
const STATIC_PVT_NAME = 'PVT表1（静态验证数据）'
const STATIC_GAS = Object.freeze({ gasType: '干气', specificGravity: 0.7336, hydrogenSulfide: 14.62,
  carbonDioxide: 8.96, nitrogen: 0, condensateOilDensity: 0,
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
const selectedPvtId = ref(STATIC_PVT_KEY)
const selectedGas = ref({ ...STATIC_GAS })
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
const importing = ref(false)
const inputDirty = ref(true)
const staticPvtId = ref(null)
const staticPvtNo = ref(null)
let chart
let loadSequence = 0

const unwrap = response => response?.data ?? response ?? {}
const scientific = value => Number.isFinite(Number(value)) ? Number(value).toExponential(4).replace('e', 'E') : ''
const gasWithDefaults = value => Object.fromEntries(Object.entries(STATIC_GAS).map(([key, fallback]) => {
  const current = value?.[key]
  return [key, current === null || current === undefined || current === '' ? fallback : current]
}))
const normalizeMethod = value => ({ 1: 'pressure', 2: 'pressure-squared', 3: 'pseudo-pressure',
  '压力形式': 'pressure', '压力平方形式': 'pressure-squared', '拟压力形式': 'pseudo-pressure' }[value] ||
  (['pressure', 'pressure-squared', 'pseudo-pressure'].includes(value) ? value : 'pseudo-pressure'))

const loadPvtOptions = async () => {
  pvtOptions.value = [{ pvtId: STATIC_PVT_KEY, pvtName: STATIC_PVT_NAME }]
  if (!props.wellName) return
  try {
    const records = unwrap(await pvtStorageApi.list(props.projectId, props.gasReservoirId, props.wellName)) || []
    const stored = records.find(item => item.pvtName === STATIC_PVT_NAME)
    staticPvtId.value = stored?.pvtId ?? null
    staticPvtNo.value = stored?.pvtNo ?? null
    pvtOptions.value.push(...records.filter(item => item.pvtName !== STATIC_PVT_NAME))
  } catch (error) { console.warn('PVT列表读取失败，使用静态验证数据', error) }
}

const loadPvtDetail = async () => {
  markInputDirty()
  if (selectedPvtId.value === STATIC_PVT_KEY) return void (selectedGas.value = { ...STATIC_GAS })
  const detail = unwrap(await pvtStorageApi.getDetail(selectedPvtId.value, props.projectId,
    props.gasReservoirId, props.wellName))
  const settings = typeof detail.settings?.gas === 'string'
    ? JSON.parse(detail.settings.gas || '{}') : (detail.settings?.gas || {})
  selectedGas.value = gasWithDefaults({ ...(detail.gasInput || {}), ...settings })
}

const ensureStaticPvt = async () => {
  if (selectedPvtId.value !== STATIC_PVT_KEY) return Number(selectedPvtId.value)
  if (staticPvtId.value) return Number(staticPvtId.value)
  const pvtNo = Math.max(0, ...pvtOptions.value.map(item => Number(item.pvtNo) || 0)) + 1
  const saved = unwrap(await pvtStorageApi.save({
    projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId),
    wellName: props.wellName, pvtNo, pvtName: STATIC_PVT_NAME,
    propertyKind: 'gas', section: 'input', sourceType: 'manual',
    gasInput: { gasType: STATIC_GAS.gasType, specificGravity: STATIC_GAS.specificGravity,
      hydrogenSulfide: STATIC_GAS.hydrogenSulfide, carbonDioxide: STATIC_GAS.carbonDioxide,
      nitrogen: STATIC_GAS.nitrogen, condensateOilDensity: STATIC_GAS.condensateOilDensity }
  }))
  staticPvtId.value = saved.pvtId
  staticPvtNo.value = pvtNo
  return Number(saved.pvtId)
}

const evaluationFormByMethod = { pressure: 1, 'pressure-squared': 2, 'pseudo-pressure': 3 }
const staticEvaluationIds = { 1: 270, 2: 220, 3: 170 }
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

const remoteEvaluationId = method => {
  if (props.evaluationId !== null && props.evaluationId !== undefined && props.evaluationId !== '' &&
      Number.isFinite(Number(props.evaluationId)) && Number(props.evaluationId) > 0) {
    return Number(props.evaluationId)
  }
  if (props.wellName !== 'A1-3') return null
  return staticEvaluationIds[evaluationFormByMethod[normalizeMethod(method)]] || null
}

const fetchCompleteResult = async method => {
  const evaluationId = remoteEvaluationId(method)
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
  const evaluationId = Number(props.evaluationId ||
    (props.wellName === 'A1-3' ? staticEvaluationIds[evaluationForm] : null))
  if (!Number.isFinite(evaluationId)) throw new Error('当前试井记录缺少 evaluationId')
  const gas = gasWithDefaults(selectedGas.value)
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
  return { ...parseResult(detail), calculationMethod: method }
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
    result: { pressureMethod: result.calculationMethod, darcySeepageCoefficient: result.darcyCoefficient,
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
  calculating.value = true
  try {
    const pvtId = await ensureStaticPvt()
    const result = await calculateResult()
    currentResult.value = result; activePanel.value = 'analysis'; activeChart.value = 'analysis'
    await saveResult(result, pvtId)
    await nextTick(); renderChart()
    ElMessage.success('计算完成，修正等时记录已保存到数据库')
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
    ElMessage.error(serverMessage || error?.msg || error?.message || '计算或保存失败')
  } finally { calculating.value = false }
}

const normalizeRows = items => (items || []).map((item, index) => ({ sequence: item.testPointNumber ?? index + 1,
  date: item.date || item.testDate || testDate.value, flowRate: item.testDailyGasProduction ?? item.flowRate,
  recoveryPressure: item.reservoirPressure ?? item.reserviorPressure ?? item.recoveryPressure,
  flowingPressure: item.testFlowPressure ?? item.flowingPressure }))

const loadTest = async () => {
  const sequence = ++loadSequence
  if (!props.testId) {
    selectedPvtId.value = STATIC_PVT_KEY; selectedGas.value = { ...STATIC_GAS }; rows.value = staticRows()
    importedFileName.value = '修正等时验证数据（静态）'; maximumFormationPressure.value = 56.34
    formationTemperature.value = 120; calculationMethod.value = 'pseudo-pressure'; testDate.value = STATIC_DATE
    operationType.value = 'production'; inputDirty.value = true
    currentResult.value = null; activePanel.value = 'input'; return
  }
  loading.value = true
  try {
    const detail = unwrap(await productivityTestsApi.detail(props.testId)); const input = detail.input || {}
    if (sequence !== loadSequence) return
    selectedPvtId.value = Number(detail.pvtId) === Number(staticPvtId.value) ? STATIC_PVT_KEY : String(detail.pvtId || '')
    selectedGas.value = gasWithDefaults(input); maximumFormationPressure.value = input.maximumFormationPressure
    formationTemperature.value = input.formationTemperature; calculationMethod.value = normalizeMethod(detail.result?.pressureMethod)
    operationType.value = detail.operationType || 'production'; testDate.value = detail.testDate
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
    let loadedResult = { calculationMethod: result.pressureMethod,
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
          await saveResult(complete, Number(detail.pvtId))
          ElMessage.success('已从结果接口补全该记录的分析曲线和IPR曲线')
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
const invalidateResult = () => { currentResult.value = null; activePanel.value = 'input' }
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
    graphic: isIpr ? [] : [{ type: 'text', left: '55%', top: '73%', silent: true,
      style: { text: equation, fill: '#333', font: '14px sans-serif', lineHeight: 22 } }] }, true)
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
        <button type="button" class="calculate" :disabled="calculating" @click="calculate">{{ calculating ? '计算并保存中…' : '计算' }}</button>
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
          <el-table-column label="产能试井日期" min-width="145"><template #default="scope"><el-input v-model="scope.row.date" size="small" @change="markInputDirty" /></template></el-table-column>
          <el-table-column label="地层/恢复压力（MPa）" min-width="175"><template #default="scope"><el-input-number v-model="scope.row.recoveryPressure" :controls="false" size="small" @change="markInputDirty" /></template></el-table-column>
          <el-table-column label="测试气产量（10⁴m³/d）" min-width="175"><template #default="scope"><el-input-number v-model="scope.row.flowRate" :controls="false" size="small" @change="markInputDirty" /></template></el-table-column>
          <el-table-column label="测试流压（MPa）" min-width="150"><template #default="scope"><el-input-number v-model="scope.row.flowingPressure" :controls="false" size="small" @change="markInputDirty" /></template></el-table-column>
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
.modified-workspace{display:flex;height:100%;min-height:0;background:#fff}.params-panel{width:360px;min-width:360px;display:flex;flex-direction:column;border-right:1px solid #ddd}.panel-head{height:34px;padding:0 12px;display:flex;align-items:center;background:#f2f2f2;border-bottom:1px solid #ddd;font-size:13px}.panel-body{flex:1;overflow:auto;padding:10px 14px}.field{display:block;margin-bottom:11px;font-size:12px}.field>span{display:block;margin-bottom:4px}.field select,.field input,.file-button,.inline-output input{width:100%;height:28px;box-sizing:border-box;border:1px solid #aaa;border-radius:3px;background:#fff;padding:0 8px}.file-button{text-align:left;cursor:pointer}.hidden-file{display:none}.field small{display:block;margin-top:4px;overflow:hidden;color:#777;text-overflow:ellipsis;white-space:nowrap}.section-title{display:flex;align-items:center;gap:8px;margin:5px 0 10px;font-size:13px}.section-title i{flex:1;height:1px;background:#999}.radios{margin:0 0 10px;padding:0;border:0;font-size:13px}.radios legend{margin-bottom:6px;padding:0}.radios label{margin-right:12px;white-space:nowrap}.calculate{height:30px;padding:0 24px;border:0;border-radius:3px;background:#111;color:#fff;cursor:pointer}.calculate:disabled{opacity:.6;cursor:wait}.inline-output{margin-top:14px}.inline-output label{display:block;margin-bottom:10px;color:#555;font-size:12px}.inline-output input{display:block;margin-top:4px;color:#333}.result-area{flex:1;min-width:0;min-height:0;display:flex;flex-direction:column}.editable-data-grid,.analysis-view{flex:1;min-height:0;display:flex;flex-direction:column}.data-toolbar,.chart-switch{height:38px;padding:0 12px;display:flex;align-items:center;gap:14px;flex-shrink:0;border-bottom:1px solid #ddd;color:#666;font-size:12px}.data-toolbar{justify-content:space-between}:deep(.el-input-number){width:100%}.chart{flex:1;min-height:0}.bottom-tabs{height:31px;display:flex;flex-shrink:0;border-top:1px solid #ddd}.bottom-tabs button{min-width:110px;border:0;border-right:1px solid #ddd;background:#fff2f4;color:#999;cursor:pointer}.bottom-tabs button.active{color:#222;box-shadow:inset 0 -2px #2b171a;font-weight:600}.bottom-tabs button:disabled{cursor:not-allowed;opacity:.5}
.disabled-option{color:#aaa}
</style>
