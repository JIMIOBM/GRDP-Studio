<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { storeToRefs } from 'pinia'
import { ElMessage } from 'element-plus'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimNetworkResult from './PipesimNetworkResult.vue'
import PipesimNetworkOptimizerResult from './PipesimNetworkOptimizerResult.vue'
import PipesimSystemAnalysisResult from './PipesimSystemAnalysisResult.vue'
import PipesimNodalResult from './PipesimNodalResult.vue'
import PipesimProfileResult from './PipesimProfileResult.vue'
import PipesimWellComparisonSummary from './PipesimWellComparisonSummary.vue'
import PipesimSensitivityResult from './PipesimSensitivityResult.vue'
import PipesimGasLiftPerformanceResult from './PipesimGasLiftPerformanceResult.vue'
import PipesimGasLiftDiagnosticsResult from './PipesimGasLiftDiagnosticsResult.vue'
import PipesimVfpTablesResult from './PipesimVfpTablesResult.vue'
import PipesimEspCurvesResult from './PipesimEspCurvesResult.vue'
import PipesimWellTrajectoryResult from './PipesimWellTrajectoryResult.vue'
import PipesimRunHistory from './PipesimRunHistory.vue'
import EclipseRunResult from './EclipseRunResult.vue'
import EclipseDataInspectionOverview from './EclipseDataInspectionOverview.vue'
import PipesimPackageOverview from './PipesimPackageOverview.vue'
import { sourceReservoirPressure, supportsPressureScenario } from './wellParameterPreview'

const props = defineProps({
  eclipsePresentation: { type: Boolean, default: true }
})

const store = useSoftwareIntegrationStore()
const {
  activeProjectId,
  activeModel,
  activeVersion,
  activeVersionId,
  versions,
  isNetworkModel,
  isWellModel,
  isEclipseModel,
  persistedStudies,
  selectedStudy,
  runType,
  runHistory,
  selectedRun,
  activeRun,
  hasActiveRun,
  activeElapsedMillis,
  loadingHistory,
  submittingRun,
  cancellingRun,
  runPollingUnavailable,
  capabilities,
  activeSimulatorCapability,
  workerBusy,
  canCreateRunByCapability
} = storeToRefs(store)

const activeTab = ref('nodal')
const profileOutletPressure = ref(100)
const statusMeta = {
  CREATED: ['已创建', 'info'],
  QUEUED: ['排队中', 'info'],
  CLAIMED: ['已领取', 'primary'],
  PREPARING: ['准备模型', 'primary'],
  RUNNING_NODAL: ['节点分析', 'primary'],
  RUNNING_PROFILE: ['PT 剖面', 'primary'],
  READING_TRAJECTORY: ['读取井轨迹', 'primary'],
  RUNNING_NETWORK: ['管网模拟中', 'primary'],
  RUNNING_ECLIPSE: ['ECLIPSE 计算中', 'primary'],
  COLLECTING: ['收集结果', 'primary'],
  CANCEL_REQUESTED: ['正在取消', 'warning'],
  SUCCEEDED: ['运行成功', 'success'],
  PARTIAL_SUCCEEDED: ['部分成功', 'warning'],
  FAILED: ['运行失败', 'danger'],
  CANCELLED: ['已取消', 'info'],
  TIMED_OUT: ['运行超时', 'danger'],
  WORKER_LOST: ['Worker 失联', 'danger']
}
const wellRunTypeOptions = [
  { value: 'nodal', label: '节点分析' },
  { value: 'profile', label: 'PT 剖面' },
  { value: 'combined', label: '组合运行' },
  { value: 'sensitivity', label: '敏感性分析' },
  { value: 'trajectory', label: '井轨迹' }
]
const runTypeOptions = computed(() => {
  if (isNetworkModel.value) return [
    { value: 'network', label: '管网模拟' },
    { value: 'system-analysis', label: '系统分析' },
    { value: 'network-optimizer', label: '网络优化' }
  ]
  if (!isWellModel.value) return []
  if (activeVersion.value?.modelKind === 'legacy_well') return wellRunTypeOptions.filter(option => ['nodal', 'profile', 'combined', 'trajectory'].includes(option.value))
  if (activeVersion.value?.modelKind === 'black_oil_liquid') {
    return [...wellRunTypeOptions,
      { value: 'gas-lift-performance', label: '气举性能' },
      { value: 'gas-lift-diagnostics', label: '气举诊断' },
      { value: 'vfp-tables', label: 'VFP 表生成' },
      { value: 'esp-curves', label: 'ESP 曲线' }]
  }
  if (activeVersion.value?.modelKind === 'basic_gas') return [...wellRunTypeOptions, { value: 'esp-curves', label: 'ESP 曲线' }]
  return wellRunTypeOptions
})
const isEclipseRunPresentation = computed(() => props.eclipsePresentation && activeVersion.value?.modelKind === 'eclipse_100')
const eclipsePresentationAvailable = computed(() => isEclipseRunPresentation.value &&
  activeVersion.value?.status === 'READY' && Boolean(activeVersion.value?.inspection))
const eclipseRunRequest = computed(() => ({ study: null, runType: 'eclipse', parameters: null }))
const pipesimPackageAvailable = computed(() => (isWellModel.value || isNetworkModel.value) &&
  activeVersion.value?.status === 'READY' &&
  ['pipesim-well-inspection/2', 'pipesim-well-inspection/3', 'pipesim-network-inspection/2', 'pipesim-network-inspection/3'].includes(activeVersion.value?.inspection?.schemaVersion) &&
  Array.isArray(activeVersion.value?.inspection?.packageFiles))
const displayRun = computed(() => activeRun.value || selectedRun.value)
const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const validateWellRun = run => {
  const result = run?.result
  if (!result || result.schemaVersion !== 'pipesim-well-result/1') return null
  if (!['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract)) return null
  if (!['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status)) return null
  if (result.resultContract !== run.resultContract || result.runTask !== run.runType) return null
  if (!['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(result.model_kind)) return null
  if (!result.units || !['flow', 'pressure', 'depth', 'temperature'].every(field => result.units[field] &&
    (result.units[field].displayUnit === null || typeof result.units[field].displayUnit === 'string'))) return null
  if (!Array.isArray(result.ipr) || !Array.isArray(result.vlp) || !Array.isArray(result.profile)) return null
  if (!result.ipr.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.vlp.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure))) return null
  if (!result.profile.every(point => isFiniteNumber(point?.depth) && point.depth >= 0 &&
    isFiniteNumber(point?.pressure) && isFiniteNumber(point?.temperature))) return null
  return result
}
const validateSensitivityRun = run => {
  const result = run?.result
  if (!result || result.schemaVersion !== 'pipesim-well-sensitivity-result/1' || result.runTask !== 'sensitivity' ||
    result.resultContract !== 'VALID_FULL' || run?.runType !== 'sensitivity' || run?.status !== 'SUCCEEDED' ||
    result.resultContract !== run.resultContract || !['black_oil_liquid', 'basic_gas'].includes(result.model_kind) ||
    !result.units || !['flow', 'pressure', 'depth', 'temperature'].every(field => result.units[field] &&
      (result.units[field].displayUnit === null || typeof result.units[field].displayUnit === 'string')) ||
    !['reservoirPressure', 'waterCut', 'gor', 'tubingInnerDiameter'].includes(result.targetVariable) || !Array.isArray(result.cases) ||
    (result.model_kind === 'basic_gas' && !['reservoirPressure', 'tubingInnerDiameter'].includes(result.targetVariable))) return null
  if (!result.cases.length || !result.cases.every(item => isFiniteNumber(item?.value) && Array.isArray(item.ipr) && Array.isArray(item.vlp) &&
    item.ipr.length > 0 && item.vlp.length > 0 && item.ipr.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure)) &&
    item.vlp.every(point => isFiniteNumber(point?.flow) && isFiniteNumber(point?.pressure)))) return null
  for (let index = 1; index < result.cases.length; index += 1) if (result.cases[index].value <= result.cases[index - 1].value) return null
  return result
}
const validateGasLiftPerformanceRun = run => {
  const result = run?.result
  if (!result || run?.runType !== 'gas-lift-performance' || run?.status !== 'SUCCEEDED' ||
    result.schemaVersion !== 'pipesim-gas-lift-performance-result/1' || result.model_kind !== 'black_oil_liquid' ||
    result.runTask !== 'gas-lift-performance' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || !isSafeTopologyText(result.producer) ||
    !['scanVariable', 'scanUnit', 'productionUnit'].every(field => typeof result[field] === 'string' && result[field].length > 0) ||
    result.scanVariable !== 'gasLiftInjectionRate' || result.scanUnit !== 'mmscf/d' || result.productionUnit !== 'STB/d' ||
    !['outletPressurePsi', 'surfaceInjectionTemperatureF', 'targetInjectionRateMmscfd', 'reservoirPressurePsi', 'gorScfPerStb', 'waterCutPercent'].every(field => isFiniteNumber(result[field])) ||
    !Array.isArray(result.cases) || result.cases.length < 2 || result.cases.length > 16) return null
  let previous = -Infinity
  for (const item of result.cases) {
    if (!hasExactFields(item, ['caseName', 'injectionRateMmscfd', 'liquidRateStbPerDay']) || !isSafeTopologyText(item.caseName) ||
      !isFiniteNumber(item.injectionRateMmscfd) || !isFiniteNumber(item.liquidRateStbPerDay) || item.injectionRateMmscfd <= previous) return null
    previous = item.injectionRateMmscfd
  }
  return result
}
const validWellResult = computed(() => isWellModel.value ? validateWellRun(selectedRun.value) : null)
const validSensitivityResult = computed(() => isWellModel.value ? validateSensitivityRun(selectedRun.value) : null)
const validGasLiftPerformanceResult = computed(() => isWellModel.value ? validateGasLiftPerformanceRun(selectedRun.value) : null)
const validateGasLiftDiagnosticsRun = run => {
  const result = run?.result
  const nullableNumber = value => value === null || isFiniteNumber(value)
  const nullableText = value => value === null || isSafeTopologyText(value)
  if (!result || run?.runType !== 'gas-lift-diagnostics' || run?.status !== 'SUCCEEDED' ||
    result.schemaVersion !== 'pipesim-gas-lift-diagnostics-result/1' || result.model_kind !== 'black_oil_liquid' ||
    result.runTask !== 'gas-lift-diagnostics' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || !isSafeTopologyText(result.producer) ||
    !['diagnosticType', 'throttling', 'injectionUnit', 'liquidRateUnit'].every(field => typeof result[field] === 'string' && result[field].length > 0) ||
    result.diagnosticType !== 'FIXEDINJECTION' || result.throttling !== 'ON' || result.injectionUnit !== 'mmscf/d' || result.liquidRateUnit !== 'STB/d' ||
    result.usePhaseRatio !== true ||
    !['outletPressurePsi', 'surfaceInjectionTemperatureF', 'targetInjectionRateMmscfd', 'reservoirPressurePsi', 'gorScfPerStb', 'waterCutPercent'].every(field => isFiniteNumber(result[field])) ||
    !Array.isArray(result.cases) || result.cases.length < 1 || result.cases.length > 32) return null
  let previous = -Infinity
  let valveCount = null
  for (const item of result.cases) {
    if (!hasExactFields(item, ['caseName', 'injectionRateMmscfd', 'liquidRateStbPerDay', 'valves']) || !isSafeTopologyText(item.caseName) ||
      !isFiniteNumber(item.injectionRateMmscfd) || !isFiniteNumber(item.liquidRateStbPerDay) || item.injectionRateMmscfd <= previous ||
      !Array.isArray(item.valves) || item.valves.length < 1 || item.valves.length > 64) return null
    if (valveCount === null) valveCount = item.valves.length
    if (item.valves.length !== valveCount) return null
    for (const valve of item.valves) {
      if (!hasExactFields(valve, ['valveName', 'positionStatus', 'status', 'gasRateNoThrottlingMmscfd', 'portDiameterIn', 'domeTemperatureF', 'closingPressurePsi', 'openingPressurePsi', 'ptroPsi', 'dischargeCoefficient', 'portToBellowArea', 'operationMode', 'portType']) ||
        !isSafeTopologyText(valve.valveName) || !isSafeTopologyText(valve.positionStatus) || !nullableText(valve.status) ||
        !nullableText(valve.operationMode) || !nullableText(valve.portType) ||
        !nullableNumber(valve.gasRateNoThrottlingMmscfd) || !nullableNumber(valve.portDiameterIn) || !nullableNumber(valve.domeTemperatureF) ||
        !nullableNumber(valve.closingPressurePsi) || !nullableNumber(valve.openingPressurePsi) || !nullableNumber(valve.ptroPsi) ||
        !nullableNumber(valve.dischargeCoefficient) || !nullableNumber(valve.portToBellowArea)) return null
    }
    previous = item.injectionRateMmscfd
  }
  return result
}
const validGasLiftDiagnosticsResult = computed(() => isWellModel.value ? validateGasLiftDiagnosticsRun(selectedRun.value) : null)
const validateVfpTablesRun = run => {
  const result = run?.result
  const increasing = values => Array.isArray(values) && values.length >= 1 && values.every((value, index) => isFiniteNumber(value) && (index === 0 || value > values[index - 1]))
  const validTable = (table, valueName, unit, axes, allowEmpty = false) => table && hasExactFields(table, ['valueName', 'unit', 'rows']) && table.valueName === valueName && table.unit === unit && Array.isArray(table.rows) && (allowEmpty ? table.rows.length === 0 : table.rows.length > 0) && table.rows.every(row => hasExactFields(row, ['liquidRateIndex', 'waterCutIndex', 'gorIndex', 'artificialLiftIndex', 'values']) &&
    ['liquidRateIndex', 'waterCutIndex', 'gorIndex', 'artificialLiftIndex'].every(field => Number.isInteger(row[field]) && row[field] > 0) && Array.isArray(row.values) && row.values.length === axes.outletPressuresPsi.length && row.values.every(isFiniteNumber))
  if (!result || run?.runType !== 'vfp-tables' || run?.status !== 'SUCCEEDED' || result.schemaVersion !== 'pipesim-vfp-tables-result/1' || result.model_kind !== 'black_oil_liquid' || result.runTask !== 'vfp-tables' || result.resultContract !== 'VALID_FULL' || result.resultContract !== run.resultContract || !isSafeTopologyText(result.producer) || result.reservoirSimulator !== 'ECLIPSE' || !Number.isInteger(result.tableNumber) || result.tableNumber <= 0 || typeof result.includeTemperature !== 'boolean' || !isFiniteNumber(result.bottomHoleDatumDepth) || !result.axes || !increasing(result.axes.liquidRatesStbPerDay) || !increasing(result.axes.outletPressuresPsi) || !increasing(result.axes.waterCutFraction) || !increasing(result.axes.gorMscfPerStb) || !increasing(result.axes.artificialLiftInjectionDpPsi) || !validTable(result.table, 'BHP', 'psia', result.axes) || !validTable(result.temperatureTable, 'TEMP', 'F', result.axes, !result.includeTemperature) || typeof result.vfpTableContent !== 'string' || typeof result.vfpTableWithTemperatureContent !== 'string') return null
  return result
}
const validVfpTablesResult = computed(() => isWellModel.value ? validateVfpTablesRun(selectedRun.value) : null)
const validateEspCurvesRun = run => {
  const result = run?.result
  const finite = value => typeof value === 'number' && Number.isFinite(value)
  const validArray = value => Array.isArray(value) && value.length > 0 && value.length <= 512 && value.every(finite)
  const validCurve = curve => curve && typeof curve === 'object' && Array.isArray(curve.flowRate) && Array.isArray(curve.head) &&
    curve.flowRate.length === curve.head.length && validArray(curve.flowRate) && validArray(curve.head) &&
    typeof curve.flowRateUnit === 'string' && typeof curve.headUnit === 'string'
  const validPump = pump => typeof pump?.pumpName === 'string' && pump.pumpName.trim() && pump.inputs && finite(pump.inputs.frequency) && typeof pump.inputs.frequencyUnit === 'string' &&
    typeof pump.inputs.manufacturer === 'string' && typeof pump.inputs.model === 'string' && finite(pump.inputs.minFlowRate) &&
    finite(pump.inputs.maxFlowRate) && pump.inputs.maxFlowRate > pump.inputs.minFlowRate && finite(pump.inputs.stages) &&
    Array.isArray(pump.frequencies) && pump.frequencies.length > 0 && pump.frequencies.every(item => finite(item.frequencyHz) && typeof item.frequencyLabel === 'string' && validCurve(item)) &&
    pump.operatingEnvelope && ['qMin', 'bep', 'qMax'].every(key => validCurve(pump.operatingEnvelope[key]))
  if (!result || run?.runType !== 'esp-curves' || run?.status !== 'SUCCEEDED' || result.schemaVersion !== 'pipesim-esp-curves-result/1' ||
     !['black_oil_liquid', 'basic_gas'].includes(result.model_kind) || result.runTask !== 'esp-curves' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || typeof result.producer !== 'string' || !validPump(result.pump) || !validPump(result.nodalPump)) return null
  return result
}
const validEspCurvesResult = computed(() => isWellModel.value ? validateEspCurvesRun(selectedRun.value) : null)
const validateTrajectoryRun = run => {
  const result = run?.result
  const finite = value => typeof value === 'number' && Number.isFinite(value)
  const nullable = value => value === null || finite(value)
  const pointValid = point => point && hasExactFields(point, ['measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity']) &&
    finite(point.measuredDepth) && point.measuredDepth >= 0 && finite(point.trueVerticalDepth) && point.trueVerticalDepth >= 0 &&
    finite(point.inclination) && point.inclination >= 0 && point.inclination <= 180 && nullable(point.azimuth) && nullable(point.maxDogLegSeverity)
  if (!result || run?.runType !== 'trajectory' || run?.status !== 'SUCCEEDED' || result.schemaVersion !== 'pipesim-well-trajectory-result/1' ||
     !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(result.model_kind) || result.runTask !== 'trajectory' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || typeof result.producer !== 'string' || !result.producer.trim() ||
    !result.units || !hasExactFields(result.units, ['measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity']) ||
    !Object.values(result.units).every(value => typeof value === 'string' && value.length > 0) || !Array.isArray(result.points) || result.points.length < 2 ||
    result.points.length > 4096 || !result.points.every(pointValid)) return null
  for (let index = 1; index < result.points.length; index += 1) if (result.points[index].measuredDepth <= result.points[index - 1].measuredDepth) return null
  return result
}
const validTrajectoryResult = computed(() => isWellModel.value ? validateTrajectoryRun(selectedRun.value) : null)
const comparisonId = ref(null)
const comparisonRun = ref(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
let comparisonGeneration = 0
const comparisonOptions = computed(() => runHistory.value.filter(run => run.id !== selectedRun.value?.id &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status) && ['nodal', 'profile', 'combined'].includes(run.runType) &&
  run.modelVersionId === activeVersionId.value && run.modelId === activeModel.value?.id))
const comparisonResult = computed(() => validateWellRun(comparisonRun.value))
const runLabel = run => run ? `运行 #${run.id} · v${run.versionNo || activeVersion.value?.versionNo} · ${run.study || '无 Study'}${run.parameters?.schemaVersion === 'pipesim-well-parameters/1' && Number.isFinite(run.parameters.reservoirPressurePsi) ? ` · 地层压力 ${run.parameters.reservoirPressurePsi} psia` : ''}` : ''
watch(comparisonId, async id => {
  const generation = ++comparisonGeneration
  comparisonRun.value = null
  comparisonError.value = ''
  comparisonLoading.value = Boolean(id)
  if (!id) return
  const versionId = activeVersionId.value
  const modelId = activeModel.value?.id
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== comparisonGeneration) return
    const run = response?.data
    if (response?.code !== 200 || run?.id !== id || run?.modelVersionId !== versionId || run?.modelId !== modelId || !validateWellRun(run)) {
      comparisonError.value = '所选历史结果不可用于比较。'
      return
    }
    comparisonRun.value = run
  } catch { if (generation === comparisonGeneration) comparisonError.value = '历史结果读取失败，请重新选择。' }
  finally { if (generation === comparisonGeneration) comparisonLoading.value = false }
})
watch([activeVersionId, () => selectedRun.value?.id], () => {
  comparisonGeneration++
  comparisonId.value = null
  comparisonRun.value = null
  comparisonLoading.value = false
  comparisonError.value = ''
})
onBeforeUnmount(() => { comparisonGeneration++ })
const isNetworkVariable = entry => entry && isSafeTopologyText(entry.variable) &&
  (entry.unit === null || isSafeTopologyText(entry.unit, true)) && Array.isArray(entry.values)
const isNumericValue = value => value === null || isFiniteNumber(value) ||
  (Array.isArray(value) && value.length > 0 && value.every(isNumericValue)) ||
  (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0 &&
    Object.values(value).every(isNumericValue))
const copyNumericValue = value => Array.isArray(value)
  ? value.map(copyNumericValue)
  : value && typeof value === 'object'
    ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, copyNumericValue(item)]))
    : value
const copyNetworkTableVariable = entry => {
  if (!isNetworkVariable(entry) || !entry.values.length) return null
  const values = []
  for (const value of entry.values) {
    if (!value || !isSafeTopologyText(value.name) || !Object.prototype.hasOwnProperty.call(value, 'value')) return null
    if (!isNumericValue(value.value)) return null
    values.push({ name: value.name, value: copyNumericValue(value.value) })
  }
  return { variable: entry.variable, unit: entry.unit, values }
}
const copyNetworkProfile = profile => {
  if (!profile || !isSafeTopologyText(profile.branch) || !Number.isInteger(profile.pointCount) ||
    profile.pointCount < 0 || !Array.isArray(profile.variables) ||
    !profile.variables.every(isNetworkVariable)) return null
  const variables = profile.variables.map(variable => {
    if (variable.variable === 'BranchEquipment') {
      return variable.values.length === profile.pointCount && variable.values.every(value =>
        value === null || isSafeTopologyText(value, true))
        ? { variable: variable.variable, unit: variable.unit, values: [...variable.values] }
        : null
    }
    return isNumericValue(variable.values)
      ? { variable: variable.variable, unit: variable.unit, values: copyNumericValue(variable.values) }
      : null
  })
  if (variables.some(variable => !variable)) return null
  const distance = variables.find(variable => variable.variable === 'TotalDistance')?.values
  const pressure = variables.find(variable => variable.variable === 'Pressure')?.values
  const finiteOrGap = value => value === null || isFiniteNumber(value)
  if (!Array.isArray(distance) || !distance.length || !Array.isArray(pressure) ||
    distance.length !== pressure.length || profile.pointCount < distance.length ||
    !distance.every(finiteOrGap) || !pressure.every(finiteOrGap)) return null
  return { branch: profile.branch, pointCount: profile.pointCount, variables }
}
const hasExactFields = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.prototype.hasOwnProperty.call(value, field))
const containsUnsafeLocalPath = value => {
  let decoded = value
  try { decoded = decodeURIComponent(value) } catch { /* Keep malformed URI text in its original form. */ }
  return /(?:^|[^a-z0-9])(?:[a-z]:(?:[\\/]|[^\s])|\\\\[^\\/\s]+[\\/]|file:|net\.pipe:\/\/)|(?:^|[^a-z0-9:])\/\/[^/\s]+\//i.test(decoded)
}
const isSafeTopologyText = (value, allowEmpty = false) => typeof value === 'string' && value.length <= 1000 &&
  (allowEmpty ? value.length === 0 || value.trim().length > 0 : value.trim().length > 0) &&
  !/[\u0000-\u001f\u007f-\u009f]/.test(value) &&
  !containsUnsafeLocalPath(value)
const safeNetworkTopology = topology => {
  const nodes = topology?.nodes
  const edges = topology?.edges
  const counts = topology?.counts
  if (!hasExactFields(topology, ['nodes', 'edges', 'counts']) || !Array.isArray(nodes) || !nodes.length || !nodes.every(node =>
    hasExactFields(node, ['id', 'componentType']) && isSafeTopologyText(node.id) && isSafeTopologyText(node.componentType))) return null
  const nodeIds = new Set(nodes.map(node => node.id))
  if (nodeIds.size !== nodes.length || !Array.isArray(edges) || !edges.length || !edges.every(edge =>
    hasExactFields(edge, ['source', 'destination', 'sourcePort']) && isSafeTopologyText(edge.source) &&
    isSafeTopologyText(edge.destination) && isSafeTopologyText(edge.sourcePort, true) &&
    nodeIds.has(edge.source) && nodeIds.has(edge.destination))) return null
  if (!hasExactFields(counts, ['nodes', 'edges', 'sources', 'sinks', 'flowlines']) || !Object.values(counts).every(value =>
    Number.isInteger(value) && value >= 0)) return null
  const componentCount = componentTypes => nodes.filter(node =>
    componentTypes.includes(node.componentType.toLowerCase())).length
  if (counts.nodes !== nodes.length || counts.edges !== edges.length ||
    counts.sources !== componentCount(['source', 'well']) || counts.sinks !== componentCount(['sink']) ||
    counts.flowlines !== componentCount(['flowline'])) return null
  return {
    nodes: nodes.map(({ id, componentType }) => ({ id, componentType })),
    edges: edges.map(({ source, destination, sourcePort }) => ({ source, destination, sourcePort })),
    counts: { ...counts }
  }
}
const copyPartialNetworkSections = (result, selectedStudy) => {
  const sections = {}
  if (isSafeTopologyText(result.study) && result.study === selectedStudy) sections.study = result.study

  for (const [field, copy] of [
    ['system', copyNetworkTableVariable],
    ['node', copyNetworkTableVariable],
    ['profiles', copyNetworkProfile]
  ]) {
    if (!Array.isArray(result[field])) continue
    const entries = result[field].map(copy)
    if (!entries.some(entry => !entry)) sections[field] = entries
  }
  return sections
}
const validNetworkResult = computed(() => {
  if (!isNetworkModel.value) return null
  const result = selectedRun.value?.result
  const topology = result?.topology
  if (!result || result.schemaVersion !== 'pipesim-network-result/1' || result.model_kind !== 'network' ||
    result.runTask !== 'network' || !['VALID_FULL', 'VALID_PARTIAL'].includes(result.resultContract) ||
    result.simulationState !== 'Completed') return null
  if (selectedRun.value?.runType !== 'network' || result.resultContract !== selectedRun.value?.resultContract) return null
  const safeTopology = safeNetworkTopology(topology)
  if (!safeTopology) return null
  if (result.resultContract === 'VALID_PARTIAL' && selectedRun.value?.status === 'PARTIAL_SUCCEEDED') {
    return {
      schemaVersion: 'pipesim-network-result/1',
      model_kind: 'network',
      runTask: 'network',
      resultContract: 'VALID_PARTIAL',
      simulationState: 'Completed',
      topology: safeTopology,
      ...copyPartialNetworkSections(result, selectedRun.value?.study)
    }
  }
  if (result.resultContract !== 'VALID_FULL') return null
  if (!isSafeTopologyText(result.study) || result.study !== selectedRun.value?.study) return null
  if (!Array.isArray(result.system) || !Array.isArray(result.node) || !Array.isArray(result.profiles)) return null
  const system = result.system.map(copyNetworkTableVariable)
  const node = result.node.map(copyNetworkTableVariable)
  const profiles = result.profiles.map(copyNetworkProfile)
  if (system.some(entry => !entry) || node.some(entry => !entry) || profiles.some(profile => !profile)) return null
  if (!result.summary || !['info', 'warnings', 'errors'].every(field => Array.isArray(result.summary[field])) ||
    !['info', 'warnings', 'errors'].every(field => result.summary[field].every(isSafeTopologyText)) ||
    !Array.isArray(result.messages) || !result.messages.every(isSafeTopologyText) ||
    !Array.isArray(result.quality) || !result.quality.every(item =>
       isSafeTopologyText(item?.path) && isSafeTopologyText(item.code))) return null
  return {
    schemaVersion: 'pipesim-network-result/1',
    model_kind: 'network',
    runTask: 'network',
    resultContract: 'VALID_FULL',
    study: result.study,
    simulationState: 'Completed',
    topology: safeTopology,
    system,
    node,
    profiles,
    summary: {
      info: [...result.summary.info],
      warnings: [...result.summary.warnings],
      errors: [...result.summary.errors]
    },
    messages: [...result.messages],
    quality: result.quality.map(({ path, code }) => ({ path, code }))
  }
})
const legacyProfileRun = computed(() => activeVersion.value?.modelKind === 'legacy_well' && ['profile', 'combined'].includes(runType.value))
const validSystemAnalysisResult = computed(() => {
  if (!isNetworkModel.value) return null
  const result = selectedRun.value?.result
  if (!result || !hasExactFields(result, [
    'schemaVersion', 'model_kind', 'runTask', 'resultContract', 'study',
    'producer', 'branchTerminator', 'outletPressurePsi', 'scanVariable', 'cases'
  ]) || selectedRun.value?.runType !== 'system-analysis' || selectedRun.value.status !== 'SUCCEEDED' ||
    result.schemaVersion !== 'pipesim-system-analysis-result/1' || result.model_kind !== 'network' ||
    result.runTask !== 'system-analysis' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== selectedRun.value.resultContract || !isSafeTopologyText(result.study) ||
    result.study !== selectedRun.value.study || result.producer !== 'Well' ||
    !isSafeTopologyText(result.branchTerminator) || result.scanVariable !== 'liquidFlowRate' ||
    !isFiniteNumber(result.outletPressurePsi) || result.outletPressurePsi <= 0 || result.outletPressurePsi > 100000 ||
    !Array.isArray(result.cases) || result.cases.length < 2 || result.cases.length > 8) return null
  let previous = -Infinity
  const cases = []
  for (const item of result.cases) {
    if (!hasExactFields(item, ['caseName', 'scanValue', 'system', 'node', 'profile']) ||
      !isSafeTopologyText(item.caseName) || !isFiniteNumber(item.scanValue) || item.scanValue <= 0 ||
      item.scanValue <= previous || !Array.isArray(item.system) || !item.system.length ||
      !Array.isArray(item.node) || !item.node.length || !item.profile || typeof item.profile !== 'object') return null
    previous = item.scanValue
    const system = item.system.map(value => hasExactFields(value, ['variable', 'unit', 'value']) &&
      isSafeTopologyText(value.variable) && (value.unit === null || isSafeTopologyText(value.unit, true)) &&
      isFiniteNumber(value.value) ? { ...value } : null)
    const node = item.node.map(node => {
      if (!hasExactFields(node, ['node', 'variables']) || !isSafeTopologyText(node.node) ||
        !Array.isArray(node.variables) || !node.variables.length) return null
      const variables = node.variables.map(value => hasExactFields(value, ['variable', 'unit', 'value']) &&
        isSafeTopologyText(value.variable) && (value.unit === null || isSafeTopologyText(value.unit, true)) &&
        isFiniteNumber(value.value) ? { ...value } : null)
      return variables.some(value => !value) ? null : { node: node.node, variables }
    })
    const profile = item.profile
    if (!hasExactFields(profile, ['pointCount', 'variables']) || !Number.isInteger(profile.pointCount) ||
      profile.pointCount <= 0 || !Array.isArray(profile.variables) || !profile.variables.length) return null
    const variables = profile.variables.map(variable => {
      if (!hasExactFields(variable, ['variable', 'unit', 'values']) || !isSafeTopologyText(variable.variable) ||
        (variable.unit !== null && !isSafeTopologyText(variable.unit, true)) || !Array.isArray(variable.values) ||
        variable.values.length !== profile.pointCount) return null
      const validValues = variable.variable === 'BranchEquipment'
        ? variable.values.every(value => value === null || isSafeTopologyText(value, true))
        : variable.values.every(isFiniteNumber)
      return validValues ? { variable: variable.variable, unit: variable.unit, values: [...variable.values] } : null
    })
    const distance = variables.find(variable => variable?.variable === 'TotalDistance')?.values
    const pressure = variables.find(variable => variable?.variable === 'Pressure')?.values
    if (system.some(value => !value) || node.some(value => !value) || variables.some(value => !value) ||
      !Array.isArray(distance) || !Array.isArray(pressure) || distance.length !== pressure.length) return null
    cases.push({
      caseName: item.caseName,
      scanValue: item.scanValue,
      system,
      node,
      profile: { pointCount: profile.pointCount, variables }
    })
  }
  return {
    schemaVersion: 'pipesim-system-analysis-result/1',
    model_kind: 'network',
    runTask: 'system-analysis',
    resultContract: 'VALID_FULL',
    study: result.study,
    producer: result.producer,
    branchTerminator: result.branchTerminator,
    outletPressurePsi: result.outletPressurePsi,
    scanVariable: result.scanVariable,
    cases
  }
})
const validateNetworkOptimizerRun = run => {
  const result = run?.result
  const groups = ['wells', 'flowlines', 'sinks']
  const appliedSchema = result?.schemaVersion === 'pipesim-network-optimizer-result/2'
  const rootFields = ['schemaVersion', 'model_kind', 'runTask', 'resultContract', 'simulationState', 'summary', 'messages', 'variables', 'wells', 'flowlines', 'sinks', 'quality', ...(appliedSchema ? ['application'] : [])]
  if (!result || run?.runType !== 'network-optimizer' || run?.status !== 'SUCCEEDED' ||
    !hasExactFields(result, rootFields) || !['pipesim-network-optimizer-result/1', 'pipesim-network-optimizer-result/2'].includes(result.schemaVersion) || result.model_kind !== 'network' ||
    result.runTask !== 'network-optimizer' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || result.simulationState !== 'Completed' ||
    !result.summary || !hasExactFields(result.summary, ['info', 'warnings', 'errors']) ||
    !['info', 'warnings', 'errors'].every(field => Array.isArray(result.summary[field]) && result.summary[field].every(value => isSafeTopologyText(value, true))) ||
    !Array.isArray(result.messages) || !result.messages.every(value => isSafeTopologyText(value, true)) ||
    !Array.isArray(result.variables) || result.variables.length < 1 || result.variables.length > 128 ||
    !Array.isArray(result.quality)) return null
  const variableKeys = new Set()
  const variables = result.variables.map(variable => {
    if (!hasExactFields(variable, ['key', 'label', 'unit']) || !isSafeTopologyText(variable.key) ||
      !isSafeTopologyText(variable.label) || !isSafeTopologyText(variable.unit, true) || variableKeys.has(variable.key)) return null
    variableKeys.add(variable.key)
    return { key: variable.key, label: variable.label, unit: variable.unit }
  })
  if (variables.some(value => !value)) return null
  const dataPaths = new Set()
  const missingPaths = new Set()
  const normalizedGroups = {}
  for (const groupName of groups) {
    if (!Array.isArray(result[groupName]) || !result[groupName].length || result[groupName].length > 256) return null
    const names = new Set()
    normalizedGroups[groupName] = result[groupName].map(group => {
      if (!hasExactFields(group, ['name', 'values']) || !isSafeTopologyText(group.name) || names.has(group.name) ||
        !Array.isArray(group.values) || group.values.length > variableKeys.size) return null
      names.add(group.name)
      const seen = new Set()
      const values = group.values.map(item => {
        const path = `${groupName}.${group.name}.${item?.key}`
        const scalarValid = item?.value === null || typeof item?.value === 'boolean' || isFiniteNumber(item?.value)
        if (!hasExactFields(item, ['key', 'value']) || !isSafeTopologyText(item.key) || !variableKeys.has(item.key) ||
          seen.has(item.key) || !scalarValid || dataPaths.has(path)) return null
        seen.add(item.key)
        dataPaths.add(path)
        if (item.value === null) missingPaths.add(path)
        return { key: item.key, value: item.value }
      })
      return values.some(value => !value) ? null : { name: group.name, values }
    })
    if (normalizedGroups[groupName].some(value => !value)) return null
  }
  const qualityPaths = new Set()
  if (result.quality.some(item => !hasExactFields(item, ['path', 'code']) || !isSafeTopologyText(item.path) ||
    item.code !== 'UNAVAILABLE' || qualityPaths.has(item.path) || !missingPaths.has(item.path) || !qualityPaths.add(item.path))) return null
  if (qualityPaths.size !== missingPaths.size) return null
  let application = null
  if (appliedSchema) {
    const candidate = result.application
    if (!hasExactFields(candidate, ['requested', 'applied', 'scope', 'sourceModelUnchanged', 'artifactName', 'changes']) ||
      candidate.requested !== true || candidate.applied !== true || candidate.scope !== 'isolated-model-copy' ||
      candidate.sourceModelUnchanged !== true || candidate.artifactName !== 'pipesim-network-optimizer-applied.pips' ||
      !Array.isArray(candidate.changes) || candidate.changes.length > 256) return null
    const changes = candidate.changes.map(change => {
      if (!hasExactFields(change, ['context', 'parameter', 'unit', 'before', 'after']) ||
        !isSafeTopologyText(change.context) || change.parameter !== 'GasRate' || !isSafeTopologyText(change.unit, true) ||
        !isFiniteNumber(change.before) || !isFiniteNumber(change.after)) return null
      return { ...change }
    })
    if (changes.some(change => !change)) return null
    application = { ...candidate, changes }
  }
  return {
    schemaVersion: result.schemaVersion,
    model_kind: result.model_kind,
    runTask: result.runTask,
    resultContract: result.resultContract,
    simulationState: result.simulationState,
    summary: { info: [...result.summary.info], warnings: [...result.summary.warnings], errors: [...result.summary.errors] },
    messages: [...result.messages],
    variables,
    ...normalizedGroups,
    quality: result.quality.map(({ path, code }) => ({ path, code })),
    ...(application ? { application } : {})
  }
}
const validNetworkOptimizerResult = computed(() => isNetworkModel.value ? validateNetworkOptimizerRun(selectedRun.value) : null)
const isPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validWellResult.value?.resultContract === 'VALID_PARTIAL')
const isNetworkPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validNetworkResult.value?.resultContract === 'VALID_PARTIAL')
const networkTopologyUnavailable = computed(() => isNetworkModel.value &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(selectedRun.value?.status) &&
  ['VALID_FULL', 'VALID_PARTIAL'].includes(selectedRun.value?.result?.resultContract) &&
  !validNetworkResult.value)
const canRun = computed(() => !previewPending.value && activeVersion.value?.status === 'READY' &&
  (isNetworkModel.value || isWellModel.value || isEclipseModel.value) &&
  (isEclipseModel.value ? eclipsePresentationAvailable.value : persistedStudies.value.includes(selectedStudy.value)) &&
  !hasActiveRun.value && !submittingRun.value && canCreateRunByCapability.value)
const capabilityReasonLabels = Object.freeze({
  WORKER_UNREACHABLE: 'Worker 无法连接',
  WORKER_BUSY: 'Worker 正在执行其他任务',
  PIPESIM_UNAVAILABLE: 'PIPESIM 执行环境不可用',
  PIPESIM_VERSION_MISMATCH: 'PIPESIM 版本不匹配',
  ECLIPSE_UNAVAILABLE: 'ECLIPSE 执行环境不可用',
  ECLIPSE_VERSION_MISMATCH: 'ECLIPSE 版本不匹配'
})
const runCapabilityMessage = computed(() => {
  if (!capabilities.value) return '正在检测执行环境；检测完成前不能创建新运行。'
  if (capabilities.value.worker?.status !== 'AVAILABLE') return capabilityReasonLabels.WORKER_UNREACHABLE
  if (workerBusy.value) return capabilityReasonLabels.WORKER_BUSY
  if (activeSimulatorCapability.value?.status !== 'AVAILABLE') {
    return capabilityReasonLabels[activeSimulatorCapability.value?.reasonCode] || '当前模拟器执行环境不可用'
  }
  return ''
})
const modelTypeLabel = computed(() => {
  if (isNetworkModel.value) return 'PIPESIM 管网模型'
  if (isEclipseModel.value) return 'ECLIPSE 100 模型'
  if (activeVersion.value?.modelKind === 'legacy_well') return 'PIPESIM 通用井模型'
  return isWellModel.value ? 'PIPESIM 井筒模型' : '待识别 PIPESIM 模型'
})
const stages = computed(() => {
  const type = displayRun.value?.runType || runType.value
  const values = ['PREPARING']
  if (type === 'network' || type === 'system-analysis' || type === 'network-optimizer') values.push('RUNNING_NETWORK')
  else if (type === 'eclipse') values.push('RUNNING_ECLIPSE')
  else {
    if (type === 'esp-curves') values.push('RUNNING_PROFILE', 'RUNNING_NODAL')
    else if (type === 'nodal' || type === 'combined' || type === 'sensitivity' || type === 'gas-lift-performance' || type === 'gas-lift-diagnostics' || type === 'vfp-tables') values.push('RUNNING_NODAL')
    if (type === 'trajectory') values.push('READING_TRAJECTORY')
    if (type === 'profile' || type === 'combined') values.push('RUNNING_PROFILE')
  }
  values.push('COLLECTING')
  return values.map(status => ({ status, label: statusMeta[status][0] }))
})
const currentStageIndex = computed(() => stages.value.findIndex(stage => stage.status === displayRun.value?.status))
const selectedError = computed(() => selectedRun.value?.error || null)
const safeCode = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : null
const executionEventTypes = new Set(['STATE', 'REQUEUED', 'WORKER'])
const executionArtifactTypes = new Set(['manifest', 'normalized-result', 'raw-response', 'request', 'log', 'output'])
const executionCleanupFields = new Map([
  ['processTreeExitConfirmed', '进程树已退出'],
  ['inputDeleted', '输入副本已删除'],
  ['workDirectoryDeleted', '工作目录已删除'],
  ['killUsed', '已执行终止清理']
])
const safeExecutionText = value => isSafeTopologyText(value) && value.length <= 240 ? value : null
const safeTimestamp = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?$/.test(value) ? value : null
const safeArtifactName = value => {
  if (typeof value !== 'string' || value.length > 256) return null
  const name = value.split(/[\\/]/).pop()
  return /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$/.test(name) ? name : null
}
const safeSha256 = value => typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value) ? value.toLowerCase() : null
const isExecutionPanelRun = computed(() => displayRun.value && statusMeta[displayRun.value.status] &&
  ['nodal', 'profile', 'combined', 'sensitivity', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves', 'trajectory', 'network', 'system-analysis', 'network-optimizer', 'eclipse'].includes(displayRun.value.runType))
const executionEvents = computed(() => {
  if (!isExecutionPanelRun.value || !Array.isArray(displayRun.value.events)) return []
  return displayRun.value.events.map(event => ({
    type: executionEventTypes.has(event?.type) ? event.type : '受控事件',
    status: statusMeta[event?.status]?.[0] || '状态不可用',
    message: safeExecutionText(event?.message) || '受控事件消息不可用',
    occurredAt: safeTimestamp(event?.occurredAt) || '时间不可用'
  }))
})
const executionCleanup = computed(() => {
  const cleanup = displayRun.value?.cleanup
  if (!isExecutionPanelRun.value || !cleanup || typeof cleanup !== 'object' || Array.isArray(cleanup)) return []
  return [...executionCleanupFields].flatMap(([key, label]) => typeof cleanup[key] === 'boolean'
    ? [{ key: label, value: cleanup[key] ? '是' : '否' }]
    : [])
})
const executionArtifacts = computed(() => {
  if (!isExecutionPanelRun.value || !Array.isArray(displayRun.value.artifacts)) return []
  return displayRun.value.artifacts.map(artifact => ({
    id: Number.isSafeInteger(artifact?.id) && artifact.id > 0 ? artifact.id : null,
    name: safeArtifactName(artifact?.name) || '文件名不可用',
    type: executionArtifactTypes.has(artifact?.type) ? artifact.type : '类型不可用',
    size: Number.isSafeInteger(artifact?.sizeBytes) && artifact.sizeBytes >= 0 ? `${artifact.sizeBytes.toLocaleString()} B` : '大小不可用',
    sha256: safeSha256(artifact?.sha256) || '校验值不可用',
    expiresAt: artifact?.expiresAt === null || artifact?.expiresAt === undefined ? '-' : (safeTimestamp(artifact.expiresAt) || '到期时间不可用'),
    contentType: typeof artifact?.contentType === 'string' ? artifact.contentType : 'application/octet-stream',
    downloadable: Number.isSafeInteger(artifact?.id) && artifact.id > 0
  }))
})
const safeRunError = computed(() => selectedError.value ? {
  category: safeCode(selectedError.value.category) || 'EXECUTION',
  code: safeCode(selectedError.value.code) || 'RUN_NOT_ACCEPTED',
  retryable: selectedError.value.retryable === true
} : null)
const terminalRunGuidance = computed(() => {
  if (!displayRun.value || !['FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST'].includes(displayRun.value.status)) return ''
  if (displayRun.value.status === 'CANCELLED') return '任务已取消。确认 Study 后可重新提交运行。'
  if (displayRun.value.status === 'TIMED_OUT') return '运行已超时。请确认模型与运行环境后重新提交。'
  if (displayRun.value.status === 'WORKER_LOST') return 'Worker 状态已丢失。请确认 Worker 可用后重新提交。'
  return safeRunError.value?.retryable ? '请确认运行环境后重新提交该版本。' : '请检查模型版本和 Study，必要时重新验证后再运行。'
})
const networkContractRejected = computed(() => isNetworkModel.value && !validNetworkResult.value &&
  !networkTopologyUnavailable.value &&
  (Boolean(selectedRun.value?.result) || ['INVALID_NETWORK_RESULT_CONTRACT', 'RESULT_CONTRACT_INVALID'].includes(safeCode(selectedError.value?.code))) &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED'].includes(selectedRun.value?.status))
const historicalSuccessfulNetworkRun = computed(() => runHistory.value.find(run => run.id !== selectedRun.value?.id &&
  run.runType === 'network' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'))

const formatElapsed = value => {
  const total = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${minutes}:${String(seconds).padStart(2, '0')}`
}
const errorMessage = () => '请求失败，请稍后重试'
const manualRefreshing = ref(false)
const retryingRun = ref(false)
const artifactDownloadPending = ref(null)
const eclipseErrorCategories = new Set(['MODEL', 'ENVIRONMENT', 'EXECUTION', 'SOLVER', 'CLEANUP', 'LICENSE'])
const eclipseErrorCodes = new Set([
  'ECLIPSE_UNAVAILABLE',
  'ECLIPSE_VERSION_MISMATCH',
  'ECLIPSE_INCLUDE_UNSUPPORTED',
  'ECLIPSE_MAIN_OUTSIDE_PACKAGE',
  'ECLIPSE_INCLUDE_MISSING',
  'ECLIPSE_INCLUDE_OUTSIDE_PACKAGE',
  'ECLIPSE_INCLUDE_PATH_INVALID',
  'ECLIPSE_INCLUDE_CYCLE',
  'ECLIPSE_INCLUDE_COUNT',
  'ECLIPSE_INCLUDE_DEPTH',
  'ECLIPSE_INCLUDE_TOO_LARGE',
  'ECLIPSE_INCLUDE_REPARSE_POINT',
  'ECLIPSE_INCLUDE_INVALID_UTF8',
  'ECLIPSE_INCLUDE_READ_FAILED',
  'ECLIPSE_INCLUDE_SYNTAX',
  'INVALID_PACKAGE_MANIFEST',
  'ECLIPSE_PACKAGE_INTEGRITY_MISMATCH',
  'LICENSE_UNAVAILABLE',
  'ECLIPSE_CLEANUP_FAILED',
  'PROCESS_TREE_EXIT_UNCONFIRMED',
  'ECLIPSE_RUN_FAILED',
  'ECLIPSE_SOLVER_FAILED',
  'INVALID_SCHEDULE_PARAMETERS',
  'ECLIPSE_SCHEDULE_TARGET_MISSING',
  'ECLIPSE_COMPLETION_FACTOR_TARGET_MISSING'
])
const eclipseRequestErrorMessage = (error, message) => {
  const tokens = [
    eclipseErrorCategories.has(error?.category) ? error.category : null,
    eclipseErrorCodes.has(error?.code) ? error.code : null
  ].filter(Boolean)
  return tokens.length ? `${message}（${tokens.join(' / ')}）` : message
}
const isEclipseVersion = versionId => {
  const version = versions.value.find(item => item.id === versionId)
  return version?.modelKind === 'eclipse_100' || /\.data$/i.test(version?.originalName || '')
}

const changeVersion = async versionId => {
  try { await store.selectVersion(versionId) } catch (error) {
    ElMessage.error(isEclipseVersion(versionId)
      ? eclipseRequestErrorMessage(error, '切换 ECLIPSE 模型版本失败，请稍后重试')
      : errorMessage(error))
  }
}
const scenarioEnabled = ref(false)
const scenarioPressure = ref(null)
const supportsCurrentScenario = computed(() => supportsPressureScenario(activeVersion.value?.modelKind, runType.value))
const eclipseScheduleEnabled = ref(false)
const eclipseWconHistEnabled = ref(false)
const eclipseWconInjeEnabled = ref(false)
const eclipseWconProdEnabled = ref(false)
const eclipseCompletionEnabled = ref(false)
const eclipseCompletionFactorEnabled = ref(false)
const eclipseForecastEnabled = ref(false)
const eclipseScheduleWell = ref('')
const eclipseScheduleDate = ref('')
const eclipseScheduleStatus = ref('SHUT')
const eclipseWconHistTargetOilRate = ref(100)
const eclipseWconInjeWell = ref('')
const eclipseWconInjeDate = ref('')
const eclipseWconInjeTargetRate = ref(1000)
const eclipseWconProdWell = ref('')
const eclipseWconProdStatus = ref('SHUT')
const eclipseWconProdTargetOilRate = ref(5000)
const eclipseCompletionKey = ref('')
const eclipseCompletionStatus = ref('SHUT')
const eclipseCompletionFactorTarget = ref(null)
const eclipseForecastDataFile = ref('')
const eclipseForecastHistoryRunId = ref(null)
const eclipseForecastRestartArtifactName = ref('')
const eclipseForecastRestartReport = ref(41)
const eclipseHistoryRunDetails = ref({})
const eclipseScheduleMetadata = computed(() => activeVersion.value?.inspection?.schemaVersion === 'eclipse-data-inspection/4'
  ? activeVersion.value.inspection.scheduleMetadata : null)
const eclipseScheduleWells = computed(() => Array.isArray(eclipseScheduleMetadata.value?.wells)
  ? eclipseScheduleMetadata.value.wells.map(item => item?.name).filter(Boolean) : [])
const scheduleMonth = value => ({ JAN: 1, FEB: 2, MAR: 3, APR: 4, MAY: 5, JUN: 6, JUL: 7, AUG: 8, SEP: 9, OCT: 10, NOV: 11, DEC: 12 })[String(value || '').toUpperCase()] || 0
const eclipseScheduleDates = computed(() => {
  const dates = []
  for (const event of activeVersion.value?.inspection?.scheduleTimeline || []) {
    if (event?.kind !== 'DATES' || !Array.isArray(event.records)) continue
    for (const record of event.records) {
      const month = scheduleMonth(record.month)
      const day = Number(record.day)
      const year = Number(record.year)
      const candidate = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
      if (month && Number.isInteger(day) && day > 0 && Number.isInteger(year) && /^\d{4}-\d{2}-\d{2}$/.test(candidate) && !dates.includes(candidate)) dates.push(candidate)
    }
  }
  return dates
})
const eclipseBaselineOptions = computed(() => runHistory.value.filter(run => run?.id !== selectedRun.value?.id &&
  run.modelVersionId === activeVersionId.value && run.runType === 'eclipse' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' &&
  (run.parameters === null || run.parameters === undefined)))
const eclipseBaselineRunId = ref(null)
const eclipseScheduleAvailable = computed(() => isEclipseModel.value && eclipseScheduleWells.value.length > 0 &&
  eclipseScheduleDates.value.length > 0 && eclipseBaselineOptions.value.length > 0)
const eclipseWconHistAvailable = computed(() => eclipseScheduleAvailable.value &&
  (activeVersion.value?.inspection?.scheduleMetadata?.records || []).some(record => record?.keyword === 'WCONHIST' &&
    Array.isArray(record.values) && record.values.length >= 4 && eclipseScheduleWells.value.includes(record.values[0])))
const eclipseWconHistDefaultTarget = computed(() => {
  const well = eclipseScheduleWell.value
  const record = (activeVersion.value?.inspection?.scheduleMetadata?.records || []).find(item => item?.keyword === 'WCONHIST' &&
    Array.isArray(item.values) && item.values[0] === well && item.values[2] === 'ORAT')
  const value = Number(record?.values?.[3])
  return Number.isFinite(value) && value > 0 ? value : 100
})
const eclipseWconInjeRecords = computed(() => (activeVersion.value?.inspection?.scheduleMetadata?.records || []).filter(record => {
  const values = record?.values
  return record?.keyword === 'WCONINJE' && Array.isArray(values) && values.length >= 5 &&
    typeof values[0] === 'string' && typeof values[1] === 'string' && values[3] === 'RATE' &&
    Number.isFinite(Number(values[4]))
}))
const eclipseWconInjeWells = computed(() => [...new Set(eclipseWconInjeRecords.value.map(record => record.values[0]))])
const eclipseWconInjeRecord = computed(() => eclipseWconInjeRecords.value.find(record => record.values[0] === eclipseWconInjeWell.value) || null)
const eclipseWconInjeFluid = computed(() => eclipseWconInjeRecord.value?.values?.[1] || '')
const eclipseWconInjeDefaultTargetRate = computed(() => {
  const value = Number(eclipseWconInjeRecord.value?.values?.[4])
  return Number.isFinite(value) && value > 0 ? value : 1000
})
const eclipseWconInjeAvailable = computed(() => isEclipseModel.value && eclipseScheduleDates.value.length > 0 &&
  eclipseBaselineOptions.value.length > 0 && eclipseWconInjeRecords.value.length > 0)
const eclipseWconProdRecords = computed(() => (activeVersion.value?.inspection?.scheduleMetadata?.records || []).filter(record => {
  const values = record?.values
  return record?.keyword === 'WCONPROD' && Array.isArray(values) && values.length >= 4 &&
    typeof values[0] === 'string' && values[2] === 'ORAT' && Number.isFinite(Number(values[3]))
}))
const eclipseWconProdWells = computed(() => [...new Set(eclipseWconProdRecords.value.map(record => record.values[0]))])
const eclipseWconProdRecord = computed(() => eclipseWconProdRecords.value.find(record => record.values[0] === eclipseWconProdWell.value) || null)
const eclipseWconProdDefaultTargetRate = computed(() => {
  const value = Number(eclipseWconProdRecord.value?.values?.[3])
  return Number.isFinite(value) && value > 0 ? value : 5000
})
const eclipseWconProdAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseWconProdRecords.value.length > 0)
const eclipseCompletionRecords = computed(() => (eclipseScheduleMetadata.value?.completions || []).filter(record =>
  record?.keyword === 'COMPDAT' && typeof record.well === 'string' && typeof record.sourceFile === 'string' &&
  Number.isInteger(Number(record.lineNumber)) && Number(record.lineNumber) > 0 &&
  ['i', 'j', 'k1', 'k2', 'status'].every(key => typeof record[key] === 'string') && ['OPEN', 'SHUT'].includes(record.status)))
const eclipseCompletionRecord = computed(() => eclipseCompletionRecords.value.find(record =>
  `${record.sourceFile}:${record.lineNumber}` === eclipseCompletionKey.value) || eclipseCompletionRecords.value[0] || null)
const eclipseCompletionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseCompletionRecords.value.length > 0)
const eclipseCompletionFactorRecords = computed(() => eclipseCompletionRecords.value.flatMap(completion => {
  const record = (eclipseScheduleMetadata.value?.records || []).find(item => item?.keyword === 'COMPDAT' &&
    item.sourceFile === completion.sourceFile && Number(item.lineNumber) === Number(completion.lineNumber) &&
    Array.isArray(item.values) && item.values.length > 7 && item.values[0] === completion.well &&
    item.values[1] === completion.i && item.values[2] === completion.j && item.values[3] === completion.k1 && item.values[4] === completion.k2)
  const original = Number(record?.values?.[7])
  return Number.isFinite(original) && original > 0 ? [{ ...completion, originalConnectionFactor: original }] : []
}))
const eclipseCompletionFactorRecord = computed(() => eclipseCompletionFactorRecords.value.find(record =>
  `${record.sourceFile}:${record.lineNumber}` === eclipseCompletionKey.value) || eclipseCompletionFactorRecords.value[0] || null)
const eclipseCompletionFactorAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseCompletionFactorRecords.value.length > 0)
const eclipseForecastDataFiles = computed(() => {
  const main = activeVersion.value?.inspection?.caseName
  return (Array.isArray(activeVersion.value?.inspection?.packageFiles) ? activeVersion.value.inspection.packageFiles : [])
    .map(file => file?.relativePath)
    .filter(path => typeof path === 'string' && /\.data$/i.test(path) && path !== main)
})
const eclipseForecastHistoryOptions = computed(() => runHistory.value.filter(run => run?.runType === 'eclipse' &&
  run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' && (run.parameters === null || run.parameters === undefined)))
const eclipseForecastHistoryDetail = computed(() => {
  const id = Number(eclipseForecastHistoryRunId.value)
  return id > 0 ? (eclipseHistoryRunDetails.value[id] || (selectedRun.value?.id === id ? selectedRun.value : null)) : null
})
const eclipseForecastRestartArtifacts = computed(() => (eclipseForecastHistoryDetail.value?.artifacts || [])
  .filter(artifact => artifact?.name && /^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$/i.test(artifact.name) && /^[a-f0-9]{64}$/i.test(artifact.sha256 || '')))
const eclipseForecastAvailable = computed(() => isEclipseModel.value && eclipseForecastDataFiles.value.length > 0 && eclipseForecastHistoryOptions.value.length > 0)
const disableOtherEclipseScenarios = () => {
  eclipseForecastEnabled.value = false
  eclipseScheduleEnabled.value = false
  eclipseWconHistEnabled.value = false
  eclipseWconInjeEnabled.value = false
  eclipseWconProdEnabled.value = false
  eclipseCompletionEnabled.value = false
  eclipseCompletionFactorEnabled.value = false
}
const toggleEclipseSchedule = value => { if (value) { disableOtherEclipseScenarios(); eclipseScheduleEnabled.value = true } }
const toggleEclipseWconHist = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconHistEnabled.value = true } }
const toggleEclipseWconInje = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconInjeEnabled.value = true } }
const toggleEclipseWconProd = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconProdEnabled.value = true } }
const toggleEclipseCompletion = value => { if (value) { disableOtherEclipseScenarios(); eclipseCompletionEnabled.value = true } }
const toggleEclipseCompletionFactor = value => { if (value) { disableOtherEclipseScenarios(); eclipseCompletionFactorEnabled.value = true } }
const toggleEclipseForecast = value => { if (value) { disableOtherEclipseScenarios(); eclipseForecastEnabled.value = true } }
const loadEclipseForecastHistory = async runId => {
  const id = Number(runId)
  if (!id || eclipseHistoryRunDetails.value[id]) return
  try {
    const payload = await softwareIntegrationApi.getRun(id)
    const detail = payload?.data || payload
    if (detail?.id === id && detail.modelVersionId === activeVersionId.value) eclipseHistoryRunDetails.value = { ...eclipseHistoryRunDetails.value, [id]: detail }
  } catch {
    ElMessage.error('历史运行 Artifact 读取失败，请重新选择历史运行')
  }
}
watch([activeVersionId, () => activeVersion.value?.inspection, () => runHistory.value.length], () => {
  eclipseScheduleEnabled.value = false
  eclipseWconHistEnabled.value = false
  eclipseWconInjeEnabled.value = false
  eclipseWconProdEnabled.value = false
  eclipseCompletionEnabled.value = false
  eclipseCompletionFactorEnabled.value = false
  eclipseForecastEnabled.value = false
  eclipseScheduleWell.value = eclipseScheduleWells.value[0] || ''
  eclipseScheduleDate.value = eclipseScheduleDates.value[0] || ''
  eclipseScheduleStatus.value = 'SHUT'
  eclipseWconHistTargetOilRate.value = 100
  eclipseWconInjeWell.value = eclipseWconInjeWells.value[0] || ''
  eclipseWconInjeDate.value = eclipseScheduleDates.value[0] || ''
  eclipseWconInjeTargetRate.value = eclipseWconInjeDefaultTargetRate.value
  eclipseWconProdWell.value = eclipseWconProdWells.value[0] || ''
  eclipseWconProdStatus.value = 'SHUT'
  eclipseWconProdTargetOilRate.value = eclipseWconProdDefaultTargetRate.value
  eclipseCompletionKey.value = eclipseCompletionRecords.value.length ? `${eclipseCompletionRecords.value[0].sourceFile}:${eclipseCompletionRecords.value[0].lineNumber}` : ''
  eclipseCompletionStatus.value = eclipseCompletionRecords.value[0]?.status === 'OPEN' ? 'SHUT' : 'OPEN'
  eclipseCompletionFactorTarget.value = eclipseCompletionFactorRecords.value[0]?.originalConnectionFactor
    ? Number((eclipseCompletionFactorRecords.value[0].originalConnectionFactor * 0.5).toPrecision(12)) : null
  eclipseBaselineRunId.value = eclipseBaselineOptions.value[0]?.id || null
  eclipseForecastDataFile.value = eclipseForecastDataFiles.value[0] || ''
  eclipseForecastHistoryRunId.value = eclipseForecastHistoryOptions.value[0]?.id || null
  eclipseForecastRestartArtifactName.value = ''
  eclipseForecastRestartReport.value = 41
  eclipseHistoryRunDetails.value = {}
}, { immediate: true })
watch(eclipseForecastHistoryRunId, async value => {
  await loadEclipseForecastHistory(value)
  const artifact = eclipseForecastRestartArtifacts.value[0]
  if (artifact) eclipseForecastRestartArtifactName.value = artifact.name
}, { immediate: true })
watch(eclipseForecastRestartArtifacts, artifacts => {
  if (!artifacts.some(artifact => artifact.name === eclipseForecastRestartArtifactName.value)) {
    eclipseForecastRestartArtifactName.value = artifacts[0]?.name || ''
  }
})
watch([eclipseScheduleWell, eclipseWconHistAvailable], () => {
  if (!eclipseWconHistTargetOilRate.value || eclipseWconHistTargetOilRate.value === 100) {
    eclipseWconHistTargetOilRate.value = eclipseWconHistDefaultTarget.value
  }
})
watch([eclipseWconInjeWell, eclipseWconInjeAvailable], () => {
  if (!Number.isFinite(eclipseWconInjeTargetRate.value) || eclipseWconInjeTargetRate.value <= 0 || eclipseWconInjeTargetRate.value === 1000) {
    eclipseWconInjeTargetRate.value = eclipseWconInjeDefaultTargetRate.value
  }
})
const sensitivityVariable = ref('reservoirPressure')
const sensitivityValuesText = ref('3000, 4000, 5000')
const isSensitivityRun = computed(() => isWellModel.value && runType.value === 'sensitivity')
const validatedWellName = computed(() => {
  const well = activeVersion.value?.inspection?.well
  return typeof well === 'string' ? well.trim() : ''
})
const gasLiftProducer = ref('')
const gasLiftOutletPressure = ref(151)
const gasLiftSurfaceInjectionTemperature = ref(110)
const gasLiftTargetInjectionRate = ref(1.1)
const gasLiftReservoirPressure = ref(1700)
const gasLiftGor = ref(400)
const gasLiftWaterCut = ref(80)
const gasLiftValuesText = ref('0.55, 0.65, 0.75, 0.85, 0.95, 1.1, 1.4, 1.7, 2.0, 2.4, 2.8')
const isGasLiftPerformanceRun = computed(() => isWellModel.value && runType.value === 'gas-lift-performance')
const isGasLiftDiagnosticsRun = computed(() => isWellModel.value && runType.value === 'gas-lift-diagnostics')
const vfpProducer = ref('')
const vfpTableNumber = ref(2)
const vfpIncludeTemperature = ref(true)
const vfpBottomHoleDatumDepth = ref(30)
const vfpLiquidRatesText = ref('200, 300')
const vfpOutletPressuresText = ref('250, 350')
const vfpWaterCutText = ref('0.4')
const vfpGorText = ref('0.265')
const vfpArtificialLiftText = ref('40, 50')
const isVfpTablesRun = computed(() => isWellModel.value && runType.value === 'vfp-tables')
const wellScopedInputVersionId = ref(null)
const syncWellScopedInputs = () => {
  const well = validatedWellName.value
  const versionId = activeVersionId.value
  if (!well) return
  if (versionId !== wellScopedInputVersionId.value || !gasLiftProducer.value.trim()) gasLiftProducer.value = well
  if (versionId !== wellScopedInputVersionId.value || !vfpProducer.value.trim()) vfpProducer.value = well
  wellScopedInputVersionId.value = versionId
}
watch([activeVersionId, validatedWellName], syncWellScopedInputs, { immediate: true })
const buildGasLiftPerformanceParameters = () => {
  const valuesMmscfd = gasLiftValuesText.value.split(/[,，\s]+/).filter(Boolean).map(Number)
  const producer = gasLiftProducer.value
  if (typeof producer !== 'string' || !producer.trim() || valuesMmscfd.length < 2 || valuesMmscfd.length > 16 ||
    valuesMmscfd.some(value => !Number.isFinite(value) || value < 0 || value > 100000) ||
    valuesMmscfd.some((value, index) => index > 0 && value <= valuesMmscfd[index - 1]) ||
    ![gasLiftOutletPressure.value, gasLiftSurfaceInjectionTemperature.value, gasLiftTargetInjectionRate.value,
      gasLiftReservoirPressure.value, gasLiftGor.value, gasLiftWaterCut.value].every(Number.isFinite) ||
    gasLiftOutletPressure.value <= 0 || gasLiftOutletPressure.value > 100000 ||
    gasLiftSurfaceInjectionTemperature.value < -1000 || gasLiftSurfaceInjectionTemperature.value > 100000 ||
    gasLiftTargetInjectionRate.value < 0 || gasLiftTargetInjectionRate.value > 100000 ||
    gasLiftReservoirPressure.value <= 0 || gasLiftReservoirPressure.value > 100000 ||
    gasLiftGor.value < 0 || gasLiftGor.value > 1000000 || gasLiftWaterCut.value < 0 || gasLiftWaterCut.value > 100) {
    return { error: '气举性能需要已验证的井、有效边界条件，以及 2 到 16 个严格递增的注气量值' }
  }
  return {
    schemaVersion: 'pipesim-gas-lift-performance-parameters/1',
    producer: producer.trim(),
    outletPressurePsi: gasLiftOutletPressure.value,
    surfaceInjectionTemperatureF: gasLiftSurfaceInjectionTemperature.value,
    targetInjectionRateMmscfd: gasLiftTargetInjectionRate.value,
    reservoirPressurePsi: gasLiftReservoirPressure.value,
    gorScfPerStb: gasLiftGor.value,
    waterCutPercent: gasLiftWaterCut.value,
    valuesMmscfd
  }
}
const buildGasLiftDiagnosticsParameters = () => {
  const producer = gasLiftProducer.value
  if (typeof producer !== 'string' || !producer.trim() ||
    ![gasLiftOutletPressure.value, gasLiftSurfaceInjectionTemperature.value, gasLiftTargetInjectionRate.value,
      gasLiftReservoirPressure.value, gasLiftGor.value, gasLiftWaterCut.value].every(Number.isFinite) ||
    gasLiftOutletPressure.value <= 0 || gasLiftOutletPressure.value > 100000 ||
    gasLiftSurfaceInjectionTemperature.value < -1000 || gasLiftSurfaceInjectionTemperature.value > 100000 ||
    gasLiftTargetInjectionRate.value < 0 || gasLiftTargetInjectionRate.value > 100000 ||
    gasLiftReservoirPressure.value <= 0 || gasLiftReservoirPressure.value > 100000 ||
    gasLiftGor.value < 0 || gasLiftGor.value > 1000000 || gasLiftWaterCut.value < 0 || gasLiftWaterCut.value > 100) {
    return { error: '气举诊断需要已验证的井和有效的官方边界条件' }
  }
  return {
    schemaVersion: 'pipesim-gas-lift-diagnostics-parameters/1',
    producer: producer.trim(),
    outletPressurePsi: gasLiftOutletPressure.value,
    surfaceInjectionTemperatureF: gasLiftSurfaceInjectionTemperature.value,
    targetInjectionRateMmscfd: gasLiftTargetInjectionRate.value,
    reservoirPressurePsi: gasLiftReservoirPressure.value,
    gorScfPerStb: gasLiftGor.value,
    waterCutPercent: gasLiftWaterCut.value
  }
}
const parseStrictValues = text => String(text || '').split(/[,，\s]+/).filter(Boolean).map(Number)
const buildVfpTablesParameters = () => {
  const values = {
    liquidRatesStbPerDay: parseStrictValues(vfpLiquidRatesText.value),
    outletPressuresPsi: parseStrictValues(vfpOutletPressuresText.value),
    waterCutFraction: parseStrictValues(vfpWaterCutText.value),
    gorMscfPerStb: parseStrictValues(vfpGorText.value),
    artificialLiftInjectionDpPsi: parseStrictValues(vfpArtificialLiftText.value)
  }
  const validAxis = (items, lower, upper) => items.length >= 1 && items.length <= 16 && items.every((value, index) => Number.isFinite(value) && value >= lower && value <= upper && (index === 0 || value > items[index - 1]))
  if (typeof vfpProducer.value !== 'string' || !vfpProducer.value.trim() || !Number.isInteger(vfpTableNumber.value) || vfpTableNumber.value < 1 || vfpTableNumber.value > 100000 || !Number.isFinite(vfpBottomHoleDatumDepth.value) || vfpBottomHoleDatumDepth.value < 0 || vfpBottomHoleDatumDepth.value > 100000 || !validAxis(values.liquidRatesStbPerDay, 0, 1000000) || !validAxis(values.outletPressuresPsi, 0, 1000000) || !validAxis(values.waterCutFraction, 0, 1) || !validAxis(values.gorMscfPerStb, 0, 1000000) || !validAxis(values.artificialLiftInjectionDpPsi, 0, 1000000)) {
    return { error: 'VFP 表需要已验证的生产井、有效表号、深度，以及各轴 1 到 16 个严格递增值' }
  }
  return { schemaVersion: 'pipesim-vfp-tables-parameters/1', producer: vfpProducer.value.trim(), reservoirSimulator: 'ECLIPSE', tableNumber: vfpTableNumber.value, includeTemperature: vfpIncludeTemperature.value, bottomHoleDatumDepth: vfpBottomHoleDatumDepth.value, ...values }
}
const systemAnalysisBranchTerminator = ref('FL-2')
const systemAnalysisOutletPressure = ref(600)
const systemAnalysisValuesText = ref('2400, 3000, 3600')
const isSystemAnalysisRun = computed(() => isNetworkModel.value && runType.value === 'system-analysis')
const isNetworkOptimizerRun = computed(() => isNetworkModel.value && runType.value === 'network-optimizer')
const networkOptimizerApplyResults = ref(false)
const networkChokeEnabled = ref(false)
const networkChokeName = ref('')
const networkChokeTarget = ref(null)
const networkChokeBaselineRunId = ref(null)
const buildSystemAnalysisParameters = () => {
  const values = systemAnalysisValuesText.value.split(/[,，\s]+/).filter(Boolean).map(Number)
  if (!systemAnalysisBranchTerminator.value?.trim() || systemAnalysisBranchTerminator.value.length > 255 ||
    /[\/\\\\\u0000]|\.\./.test(systemAnalysisBranchTerminator.value) ||
    !Number.isFinite(systemAnalysisOutletPressure.value) || systemAnalysisOutletPressure.value <= 0 ||
    systemAnalysisOutletPressure.value > 100000 || values.length < 2 || values.length > 8 ||
    values.some(value => !Number.isFinite(value) || value <= 0 || value > 1000000000) ||
    values.some((value, index) => index > 0 && value <= values[index - 1])) {
    return { error: '系统分析需要有效的 BranchTerminator、出口压力，以及 2 到 8 个严格递增的液体流量值' }
  }
  return {
    schemaVersion: 'pipesim-system-analysis-parameters/1',
    producer: 'Well',
    branchTerminator: systemAnalysisBranchTerminator.value.trim(),
    outletPressurePsi: systemAnalysisOutletPressure.value,
    scanVariable: 'liquidFlowRate',
    values
  }
}
const networkParameterFields = [
  { value: 'pressure', label: '压力' },
  { value: 'temperature', label: '温度' },
  { value: 'gasFlowRate', label: '气体流量' },
  { value: 'liquidFlowRate', label: '液体流量' },
  { value: 'massFlowRate', label: '质量流量' }
]
const networkInspection = computed(() => {
  if (!isNetworkModel.value || activeVersion.value?.status !== 'READY') return null
  const studies = activeVersion.value?.inspection?.studies
  if (!Array.isArray(studies)) return null
  const item = studies.find(study => study?.study === selectedStudy.value)
  if (!item || !Array.isArray(item.boundaries) || !item.boundaries.length || item.boundaries.length > 256) return null
  const boundaries = item.boundaries.filter(boundary => {
    const fields = ['node', 'boundaryNodeType', 'isActive', 'isSurfaceCondition', 'flowRateType', 'pressure', 'temperature', 'gasFlowRate', 'liquidFlowRate', 'massFlowRate']
    const values = fields.map(field => boundary?.[field])
    return boundary && Object.keys(boundary).length === fields.length &&
      fields.every((field, index) => field === 'isActive' || field === 'isSurfaceCondition'
        ? typeof values[index] === 'boolean'
        : true) && isSafeTopologyText(boundary.node) && isSafeTopologyText(boundary.boundaryNodeType) &&
      (boundary.flowRateType === null || ['GasFlowRate', 'LiquidFlowRate', 'MassFlowRate'].includes(boundary.flowRateType)) &&
      ['pressure', 'temperature', 'gasFlowRate', 'liquidFlowRate', 'massFlowRate'].every(field =>
        boundary[field] === null || (isFiniteNumber(boundary[field]) && boundary[field] > (field === 'temperature' ? -1000 : 0) && boundary[field] <= (field.includes('FlowRate') ? 1000000000 : 100000)))
  })
  return boundaries.length === item.boundaries.length ? { study: item.study, boundaries } : null
})
const networkInspectionUnavailable = computed(() => isNetworkModel.value && activeVersion.value?.status === 'READY' && !networkInspection.value)
const networkChokes = computed(() => {
  if (!isNetworkModel.value || activeVersion.value?.inspection?.schemaVersion !== 'pipesim-network-inspection/3') return []
  const chokes = activeVersion.value?.inspection?.chokes
  if (!Array.isArray(chokes) || chokes.length > 128) return []
  return chokes.filter(choke => typeof choke?.name === 'string' && isSafeTopologyText(choke.name) &&
    typeof choke?.unit === 'string' && isSafeTopologyText(choke.unit, true) && isFiniteNumber(choke.beanSize) &&
    choke.beanSize > 0 && choke.beanSize <= 1000000)
})
const networkChokeRecord = computed(() => networkChokes.value.find(choke => choke.name === networkChokeName.value) || networkChokes.value[0] || null)
const networkChokeBaselineOptions = computed(() => runHistory.value.filter(run => run?.id !== selectedRun.value?.id &&
  run.modelVersionId === activeVersionId.value && run.runType === 'network' && run.study === selectedStudy.value &&
  run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' && (run.parameters === null || run.parameters === undefined)))
const networkChokeAvailable = computed(() => isNetworkModel.value && runType.value === 'network' &&
  networkChokes.value.length > 0 && networkChokeBaselineOptions.value.length > 0)
const networkBoundaryDrafts = ref([])
const defaultNetworkField = boundary => {
  if (isFiniteNumber(boundary.pressure)) return 'pressure'
  if (isFiniteNumber(boundary.temperature)) return 'temperature'
  if (boundary.flowRateType === 'GasFlowRate' && isFiniteNumber(boundary.gasFlowRate)) return 'gasFlowRate'
  if (boundary.flowRateType === 'LiquidFlowRate' && isFiniteNumber(boundary.liquidFlowRate)) return 'liquidFlowRate'
  if (boundary.flowRateType === 'MassFlowRate' && isFiniteNumber(boundary.massFlowRate)) return 'massFlowRate'
  return 'pressure'
}
const resetNetworkBoundaryDrafts = () => {
  networkBoundaryDrafts.value = networkInspection.value?.boundaries.map(boundary => {
    const field = defaultNetworkField(boundary)
    return { node: boundary.node, boundaryNodeType: boundary.boundaryNodeType, enabled: false, field, value: boundary[field] ?? null }
  }) || []
}
watch([activeVersionId, selectedStudy, () => activeVersion.value?.inspection], resetNetworkBoundaryDrafts, { immediate: true })
watch([activeVersionId, selectedStudy, () => activeVersion.value?.inspection, () => runHistory.value.length], () => {
  networkChokeEnabled.value = false
  networkChokeName.value = networkChokes.value[0]?.name || ''
  networkChokeTarget.value = networkChokes.value[0]
    ? Number((networkChokes.value[0].beanSize * 1.5).toPrecision(12)) : null
  networkChokeBaselineRunId.value = networkChokeBaselineOptions.value[0]?.id || null
}, { immediate: true })
watch(networkChokeName, () => {
  const choke = networkChokeRecord.value
  networkChokeTarget.value = choke ? Number((choke.beanSize * 1.5).toPrecision(12)) : null
})
const networkFieldLabel = field => networkParameterFields.find(item => item.value === field)?.label || field
const networkParameterValueValid = (field, value) => Number.isFinite(value) && value > (field === 'temperature' ? -1000 : 0) && value <= (field.includes('FlowRate') ? 1000000000 : 100000)
const toggleNetworkBoundary = boundary => {
  networkChokeEnabled.value = false
  boundary.enabled = !boundary.enabled
}
const toggleNetworkChoke = value => {
  if (!value) return
  networkBoundaryDrafts.value.forEach(item => { item.enabled = false })
}
const buildNetworkChokeParameters = () => {
  const choke = networkChokeRecord.value
  if (!choke || !Number.isSafeInteger(Number(networkChokeBaselineRunId.value)) || Number(networkChokeBaselineRunId.value) <= 0 ||
    !Number.isFinite(networkChokeTarget.value) || networkChokeTarget.value <= 0 || networkChokeTarget.value > 1000000 ||
    Math.abs(networkChokeTarget.value - choke.beanSize) <= Math.max(1e-12, Math.abs(choke.beanSize) * 1e-12)) {
    return { error: 'Choke Bean Size 方案必须选择同 Study 的成功无参数基线，并输入与原值不同的正数目标值' }
  }
  return {
    schemaVersion: 'pipesim-network-choke-bean-size-parameters/1',
    baselineRunId: Number(networkChokeBaselineRunId.value),
    choke: choke.name,
    originalBeanSize: choke.beanSize,
    targetBeanSize: Number(networkChokeTarget.value)
  }
}
const buildNetworkParameters = () => {
  const enabled = networkBoundaryDrafts.value.filter(item => item.enabled)
  if (!enabled.length) return null
  const boundaries = []
  for (const item of enabled) {
    if (!networkParameterValueValid(item.field, item.value)) return { error: `节点 ${item.node} 的${networkFieldLabel(item.field)}不在有效范围内` }
    const boundary = { node: item.node, [item.field]: item.value }
    if (item.field.includes('FlowRate')) boundary.flowRateType = item.field === 'gasFlowRate' ? 'GasFlowRate' : (item.field === 'liquidFlowRate' ? 'LiquidFlowRate' : 'MassFlowRate')
    boundaries.push(boundary)
  }
  return { schemaVersion: 'pipesim-network-parameters/1', boundaries }
}
const sourcePressure = computed(() => sourceReservoirPressure(activeVersion.value))
const readingVersionId = ref(null)
const previewPending = computed(() => readingVersionId.value === activeVersionId.value || ['UPLOADED', 'VALIDATING'].includes(activeVersion.value?.status))
const canReadSource = computed(() => isWellModel.value && !hasActiveRun.value && !submittingRun.value && !previewPending.value && !workerBusy.value)
const canRevalidateActiveModel = computed(() => (isNetworkModel.value || isEclipseModel.value) &&
  activeVersion.value?.status === 'READY' && !hasActiveRun.value && !submittingRun.value && !previewPending.value && !workerBusy.value)
const canUseSource = computed(() => isWellModel.value && !hasActiveRun.value && !submittingRun.value && !previewPending.value && sourcePressure.value > 0 && sourcePressure.value <= 100000)
const readSourceParameters = async () => {
  if (!canReadSource.value) return
  const projectId = activeProjectId.value
  const versionId = activeVersionId.value
  readingVersionId.value = versionId
  try {
    await store.revalidateModel(projectId, versionId)
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.success('已提交重新验证，完成后显示原模型参数')
  } catch {
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.error('原模型参数读取请求失败，请稍后重试')
  } finally {
    if (readingVersionId.value === versionId) readingVersionId.value = null
  }
}
const revalidateActiveModel = async () => {
  if (!canRevalidateActiveModel.value) return
  const projectId = activeProjectId.value
  const versionId = activeVersionId.value
  readingVersionId.value = versionId
  try {
    await store.revalidateModel(projectId, versionId)
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.success('已提交重新验证，完成后显示最新模型检查信息')
  } catch {
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) ElMessage.error('模型重新验证请求失败，请稍后重试')
  } finally {
    if (readingVersionId.value === versionId) readingVersionId.value = null
  }
}
const useSourcePressure = () => {
  if (!canUseSource.value) return
  const pressure = sourcePressure.value
  if (!supportsCurrentScenario.value) runType.value = 'nodal'
  scenarioPressure.value = pressure
  scenarioEnabled.value = true
}
watch([activeVersionId, runType], () => {
  scenarioEnabled.value = false
  scenarioPressure.value = null
  if (!isEclipseModel.value) {
    eclipseScheduleEnabled.value = false
    eclipseWconHistEnabled.value = false
    eclipseWconInjeEnabled.value = false
    eclipseWconProdEnabled.value = false
    eclipseCompletionEnabled.value = false
    eclipseCompletionFactorEnabled.value = false
  }
  if (isNetworkModel.value && !hasActiveRun.value) {
    activeTab.value = runType.value === 'network-optimizer' ? 'network-optimizer' : (runType.value === 'system-analysis' ? 'system-analysis' : 'network')
  }
}, { flush: 'sync' })
const scenarioSnapshot = computed(() => selectedRun.value?.parameters?.schemaVersion === 'pipesim-well-parameters/1'
  && Number.isFinite(selectedRun.value.parameters.reservoirPressurePsi) ? selectedRun.value.parameters.reservoirPressurePsi : null)
const canReuseScenario = computed(() => isWellModel.value && activeVersion.value?.status === 'READY' && !hasActiveRun.value && !submittingRun.value &&
  selectedRun.value?.modelVersionId === activeVersionId.value && supportsPressureScenario(activeVersion.value?.modelKind, selectedRun.value?.runType) && scenarioSnapshot.value > 0 && scenarioSnapshot.value <= 100000)
const reuseScenario = () => {
  if (!canReuseScenario.value) return
  const pressure = scenarioSnapshot.value
  runType.value = selectedRun.value.runType
  scenarioPressure.value = pressure
  scenarioEnabled.value = true
  ElMessage.success('已载入方案参数，可修改后点击运行；不会修改历史记录')
}
const submitRun = async () => {
  if (!canRun.value) return
  if (scenarioEnabled.value && (!supportsCurrentScenario.value || !Number.isFinite(scenarioPressure.value) || scenarioPressure.value <= 0 || scenarioPressure.value > 100000)) {
    ElMessage.error('当前模型或运行类型不支持压力方案，或压力不在大于 0 且不超过 100000 psia 的范围内')
    return
  }
  if (legacyProfileRun.value && (!Number.isFinite(profileOutletPressure.value) || profileOutletPressure.value <= 0 || profileOutletPressure.value > 100000)) {
    ElMessage.error('PT 剖面出口压力必须大于 0 且不超过 100000 psia')
    return
  }
  let parameters = null
  if (isEclipseModel.value && eclipseForecastEnabled.value) {
    parameters = { schemaVersion: 'eclipse-history-forecast-parameters/1', historyRunId: Number(eclipseForecastHistoryRunId.value), forecastDataFile: eclipseForecastDataFile.value, restartArtifactName: eclipseForecastRestartArtifactName.value, restartArtifactSha256: eclipseForecastRestartArtifacts.value.find(artifact => artifact.name === eclipseForecastRestartArtifactName.value)?.sha256?.toLowerCase(), restartReport: Number(eclipseForecastRestartReport.value) }
  } else if (isEclipseModel.value && eclipseCompletionFactorEnabled.value) {
    const completion = eclipseCompletionFactorRecord.value
    parameters = { schemaVersion: 'eclipse-completion-factor-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: completion?.well || '', sourceFile: completion?.sourceFile || '', lineNumber: Number(completion?.lineNumber), i: completion?.i || '', j: completion?.j || '', k1: completion?.k1 || '', k2: completion?.k2 || '', originalConnectionFactor: Number(completion?.originalConnectionFactor), targetConnectionFactor: Number(eclipseCompletionFactorTarget.value) }
  } else if (isEclipseModel.value && eclipseCompletionEnabled.value) {
    const completion = eclipseCompletionRecord.value
    parameters = { schemaVersion: 'eclipse-completion-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: completion?.well || '', sourceFile: completion?.sourceFile || '', lineNumber: Number(completion?.lineNumber), i: completion?.i || '', j: completion?.j || '', k1: completion?.k1 || '', k2: completion?.k2 || '', status: eclipseCompletionStatus.value }
  } else if (isEclipseModel.value && eclipseWconProdEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/4', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseWconProdWell.value, phase: 'FORECAST_INITIAL', status: eclipseWconProdStatus.value, controlMode: 'ORAT', targetOilRate: Number(eclipseWconProdTargetOilRate.value) }
  } else if (isEclipseModel.value && eclipseWconInjeEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/3', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseWconInjeWell.value, date: eclipseWconInjeDate.value, injectionType: eclipseWconInjeFluid.value, controlMode: 'RATE', targetInjectionRate: Number(eclipseWconInjeTargetRate.value) }
  } else if (isEclipseModel.value && eclipseWconHistEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/2', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseScheduleWell.value, date: eclipseScheduleDate.value, status: eclipseScheduleStatus.value, controlMode: 'ORAT', targetOilRate: Number(eclipseWconHistTargetOilRate.value) }
  } else if (isEclipseModel.value && eclipseScheduleEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseScheduleWell.value, date: eclipseScheduleDate.value, status: eclipseScheduleStatus.value }
  } else if (isGasLiftPerformanceRun.value) {
    parameters = buildGasLiftPerformanceParameters()
  } else if (isGasLiftDiagnosticsRun.value) {
    parameters = buildGasLiftDiagnosticsParameters()
  } else if (isVfpTablesRun.value) {
    parameters = buildVfpTablesParameters()
  } else if (runType.value === 'esp-curves') {
    parameters = { schemaVersion: 'pipesim-esp-curves-parameters/1' }
  } else if (runType.value === 'trajectory') {
    parameters = { schemaVersion: 'pipesim-well-trajectory-parameters/1' }
  } else if (legacyProfileRun.value) {
    parameters = { schemaVersion: 'pipesim-well-profile-parameters/1', outletPressurePsi: profileOutletPressure.value }
  } else if (scenarioEnabled.value) {
    parameters = { schemaVersion: 'pipesim-well-parameters/1', reservoirPressurePsi: scenarioPressure.value }
  }
  if ((isGasLiftPerformanceRun.value || isGasLiftDiagnosticsRun.value || isVfpTablesRun.value) && parameters?.error) {
    ElMessage.error(parameters.error)
    return
  }
  if (isEclipseModel.value && (eclipseScheduleEnabled.value || eclipseWconHistEnabled.value) &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 || !eclipseScheduleWells.value.includes(parameters.well) ||
      !eclipseScheduleDates.value.includes(parameters.date) || !['OPEN', 'SHUT'].includes(parameters.status) ||
      (eclipseWconHistEnabled.value && (!eclipseWconHistAvailable.value || parameters.controlMode !== 'ORAT' || !Number.isFinite(parameters.targetOilRate) || parameters.targetOilRate <= 0 || parameters.targetOilRate > 100000000)))) {
    ElMessage.error(eclipseWconHistEnabled.value ? 'WCONHIST 方案必须选择已有成功基线、井、DATES 日期、OPEN/SHUT 状态和正数 ORAT 目标值' : '调度方案必须选择已有成功基线、井、DATES 日期和 OPEN/SHUT 状态')
    return
  }
  if (isEclipseModel.value && eclipseWconInjeEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 ||
      !eclipseWconInjeWells.value.includes(parameters.well) || !eclipseScheduleDates.value.includes(parameters.date) ||
      parameters.injectionType !== eclipseWconInjeFluid.value || parameters.controlMode !== 'RATE' ||
      !Number.isFinite(parameters.targetInjectionRate) || parameters.targetInjectionRate <= 0 || parameters.targetInjectionRate > 100000000)) {
    ElMessage.error('WCONINJE 方案必须选择已有成功基线、已有注入井、DATES 日期和正数 RATE 注入速率')
    return
  }
  if (isEclipseModel.value && eclipseWconProdEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 ||
      !eclipseWconProdAvailable.value || !eclipseWconProdWells.value.includes(parameters.well) ||
      parameters.phase !== 'FORECAST_INITIAL' || !['OPEN', 'SHUT'].includes(parameters.status) || parameters.controlMode !== 'ORAT' ||
      !Number.isFinite(parameters.targetOilRate) || parameters.targetOilRate <= 0 || parameters.targetOilRate > 100000000)) {
    ElMessage.error('WCONPROD 方案必须选择已有成功基线、预测初始段已有 ORAT 井、OPEN/SHUT 状态和正数 ORAT 目标值')
    return
  }
  if (isEclipseModel.value && eclipseCompletionEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 || !eclipseCompletionAvailable.value ||
      !eclipseCompletionRecord.value || parameters.well !== eclipseCompletionRecord.value.well ||
      parameters.sourceFile !== eclipseCompletionRecord.value.sourceFile || parameters.lineNumber !== Number(eclipseCompletionRecord.value.lineNumber) ||
      parameters.i !== eclipseCompletionRecord.value.i || parameters.j !== eclipseCompletionRecord.value.j ||
      parameters.k1 !== eclipseCompletionRecord.value.k1 || parameters.k2 !== eclipseCompletionRecord.value.k2 ||
      !['OPEN', 'SHUT'].includes(parameters.status) || parameters.status === eclipseCompletionRecord.value.status)) {
    ElMessage.error('COMPDAT 完井方案必须选择已有成功基线、已有 COMPDAT 完井段和与原状态不同的 OPEN/SHUT 状态')
    return
  }
  if (isEclipseModel.value && eclipseCompletionFactorEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 || !eclipseCompletionFactorAvailable.value ||
      !eclipseCompletionFactorRecord.value || parameters.well !== eclipseCompletionFactorRecord.value.well ||
      parameters.sourceFile !== eclipseCompletionFactorRecord.value.sourceFile || parameters.lineNumber !== Number(eclipseCompletionFactorRecord.value.lineNumber) ||
      parameters.i !== eclipseCompletionFactorRecord.value.i || parameters.j !== eclipseCompletionFactorRecord.value.j ||
      parameters.k1 !== eclipseCompletionFactorRecord.value.k1 || parameters.k2 !== eclipseCompletionFactorRecord.value.k2 ||
      !Number.isFinite(parameters.originalConnectionFactor) || parameters.originalConnectionFactor <= 0 ||
      Math.abs(parameters.originalConnectionFactor - eclipseCompletionFactorRecord.value.originalConnectionFactor) > Math.max(1e-12, Math.abs(parameters.originalConnectionFactor) * 1e-12) ||
      !Number.isFinite(parameters.targetConnectionFactor) || parameters.targetConnectionFactor <= 0 || parameters.targetConnectionFactor > 1000000000 ||
      parameters.targetConnectionFactor === parameters.originalConnectionFactor)) {
    ElMessage.error('COMPDAT 连接因子方案必须选择已有成功基线、已有数值连接因子完井段，并输入与原值不同的正数目标值')
    return
  }
  if (isEclipseModel.value && eclipseForecastEnabled.value &&
    (!Number.isSafeInteger(parameters.historyRunId) || parameters.historyRunId <= 0 ||
      !eclipseForecastDataFiles.value.includes(parameters.forecastDataFile) ||
      !eclipseForecastRestartArtifacts.value.some(artifact => artifact.name === parameters.restartArtifactName && artifact.sha256?.toLowerCase() === parameters.restartArtifactSha256) ||
      !Number.isSafeInteger(parameters.restartReport) || parameters.restartReport <= 0)) {
    ElMessage.error('预测运行必须选择当前版本的预测 DATA、成功历史运行、FUNRST Artifact 和正整数重启报告')
    return
  }
  if (isNetworkOptimizerRun.value) {
    parameters = {
      schemaVersion: networkOptimizerApplyResults.value
        ? 'pipesim-network-optimizer-parameters/2'
        : 'pipesim-network-optimizer-parameters/1',
      applyResults: networkOptimizerApplyResults.value
    }
  } else if (isSystemAnalysisRun.value) {
    const systemAnalysisParameters = buildSystemAnalysisParameters()
    if (systemAnalysisParameters?.error) {
      ElMessage.error(systemAnalysisParameters.error)
      return
    }
    parameters = systemAnalysisParameters
  } else if (isNetworkModel.value) {
    if (networkChokeEnabled.value) {
      const networkChokeParameters = buildNetworkChokeParameters()
      if (networkChokeParameters?.error) {
        ElMessage.error(networkChokeParameters.error)
        return
      }
      parameters = networkChokeParameters
    } else {
      const networkParameters = buildNetworkParameters()
      if (networkParameters?.error) {
        ElMessage.error(networkParameters.error)
        return
      }
      parameters = networkParameters
    }
  }
  if (isSensitivityRun.value) {
    const values = sensitivityValuesText.value.split(/[,，\s]+/).filter(Boolean).map(Number)
    if (!['reservoirPressure', 'waterCut', 'gor', 'tubingInnerDiameter'].includes(sensitivityVariable.value) || values.length < 2 || values.length > 12 ||
      values.some(value => !Number.isFinite(value) || value <= 0) || values.some((value, index) => index > 0 && value <= values[index - 1])) {
      ElMessage.error('敏感性分析需要 2 到 12 个严格递增的正数值')
      return
    }
    const max = sensitivityVariable.value === 'reservoirPressure' ? 100000 : (sensitivityVariable.value === 'waterCut' ? 100 : (sensitivityVariable.value === 'gor' ? 1000000 : 100))
    if (values.some(value => value > max) || (activeVersion.value?.modelKind === 'basic_gas' && ['waterCut', 'gor'].includes(sensitivityVariable.value))) {
      ElMessage.error('敏感性变量或取值范围不适用于当前模型')
      return
    }
    parameters = { schemaVersion: 'pipesim-well-sensitivity-parameters/1', targetVariable: sensitivityVariable.value, values }
  }
  try {
    const detail = await store.createRun(parameters)
    if (!detail) return
    activeTab.value = isEclipseModel.value ? 'eclipse' : (isNetworkOptimizerRun.value ? 'network-optimizer' : (isSystemAnalysisRun.value ? 'system-analysis' : (isNetworkModel.value ? 'network' : (runType.value === 'profile' ? 'profile' : (runType.value === 'sensitivity' ? 'sensitivity' : (runType.value === 'gas-lift-performance' ? 'gas-lift-performance' : (runType.value === 'gas-lift-diagnostics' ? 'gas-lift-diagnostics' : (runType.value === 'vfp-tables' ? 'vfp-tables' : (runType.value === 'esp-curves' ? 'esp-curves' : (runType.value === 'trajectory' ? 'trajectory' : 'nodal'))))))))))
    ElMessage.success('运行任务已创建')
  } catch (error) {
    ElMessage.error(isEclipseModel.value
      ? eclipseRequestErrorMessage(error, '创建 ECLIPSE 运行失败，请稍后重试')
      : errorMessage(error))
  }
}
const cancelRun = async () => {
  try {
    await store.cancelRun()
    ElMessage.success('取消请求已提交')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}
const canRetryRun = computed(() => Boolean(displayRun.value &&
  ['TIMED_OUT', 'WORKER_LOST'].includes(displayRun.value.status) ||
  (displayRun.value?.status === 'FAILED' && safeRunError.value?.retryable)))
const retryRun = async () => {
  if (!canRetryRun.value || retryingRun.value) return
  retryingRun.value = true
  try {
    const detail = await store.retryRun()
    if (detail) {
      activeTab.value = detail.runType === 'eclipse' ? 'eclipse' : (detail.runType === 'network-optimizer' ? 'network-optimizer' : (detail.runType === 'system-analysis' ? 'system-analysis' : (detail.runType === 'network' ? 'network' : (detail.runType === 'profile' ? 'profile' : (detail.runType === 'sensitivity' ? 'sensitivity' : (detail.runType === 'gas-lift-performance' ? 'gas-lift-performance' : (detail.runType === 'gas-lift-diagnostics' ? 'gas-lift-diagnostics' : (detail.runType === 'vfp-tables' ? 'vfp-tables' : (detail.runType === 'esp-curves' ? 'esp-curves' : (detail.runType === 'trajectory' ? 'trajectory' : 'nodal'))))))))))
      ElMessage.success('已基于原运行参数重新提交，原运行记录保留不变')
    }
  } catch (error) {
    ElMessage.error(isEclipseModel.value
      ? eclipseRequestErrorMessage(error, '重新提交 ECLIPSE 运行失败，请稍后重试')
      : errorMessage(error))
  } finally {
    retryingRun.value = false
  }
}
const downloadArtifact = async artifact => {
  const runId = displayRun.value?.id
  if (!runId || !artifact?.downloadable || artifactDownloadPending.value) return
  artifactDownloadPending.value = artifact.id
  try {
    const payload = await softwareIntegrationApi.downloadArtifact(runId, artifact.id)
    const blob = payload instanceof Blob ? payload : new Blob([payload], { type: artifact.contentType })
    const href = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = href
    anchor.download = artifact.name || 'artifact.bin'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(href), 0)
  } catch {
    ElMessage.error('Artifact 下载失败，请检查运行记录和有效期。')
  } finally {
    artifactDownloadPending.value = null
  }
}
const selectHistoryRun = async runId => {
  try {
    const detail = await store.selectRun(runId)
    if (!detail) return
    if (detail.runType === 'network-optimizer') activeTab.value = 'network-optimizer'
    else if (detail.runType === 'system-analysis') activeTab.value = 'system-analysis'
    else if (detail.runType === 'network') activeTab.value = 'network'
    else if (detail.runType === 'eclipse') activeTab.value = 'eclipse'
    else if (detail.runType === 'profile') activeTab.value = 'profile'
    else if (detail.runType === 'sensitivity') activeTab.value = 'sensitivity'
    else if (detail.runType === 'gas-lift-performance') activeTab.value = 'gas-lift-performance'
    else if (detail.runType === 'gas-lift-diagnostics') activeTab.value = 'gas-lift-diagnostics'
    else if (detail.runType === 'vfp-tables') activeTab.value = 'vfp-tables'
    else if (detail.runType === 'esp-curves') activeTab.value = 'esp-curves'
    else if (detail.runType === 'trajectory') activeTab.value = 'trajectory'
  } catch (error) {
    const historyRun = runHistory.value.find(run => run.id === runId)
    ElMessage.error(historyRun?.runType === 'eclipse'
      ? eclipseRequestErrorMessage(error, '加载 ECLIPSE 运行记录失败，请稍后重试')
      : errorMessage(error))
  }
}
const resultTabRunTypes = {
  nodal: ['nodal', 'combined'],
  profile: ['profile', 'combined'],
  sensitivity: ['sensitivity'],
  'gas-lift-performance': ['gas-lift-performance'],
  'gas-lift-diagnostics': ['gas-lift-diagnostics'],
  'vfp-tables': ['vfp-tables'],
  'esp-curves': ['esp-curves'],
  trajectory: ['trajectory']
}
watch(activeTab, async name => {
  const types = resultTabRunTypes[name]
  if (!types) return
  if (types.includes(selectedRun.value?.runType) && ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(selectedRun.value?.status)) return
  const match = runHistory.value.find(run => types.includes(run.runType) && ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(run.status))
  if (match) await selectHistoryRun(match.id)
})
const selectHistoricalSuccessfulNetworkRun = () => {
  if (historicalSuccessfulNetworkRun.value) selectHistoryRun(historicalSuccessfulNetworkRun.value.id)
  else activeTab.value = 'history'
}
const refreshRunManually = async () => {
  const runId = displayRun.value?.id
  if (!runId || manualRefreshing.value) return
  manualRefreshing.value = true
  try {
    await store.selectRun(runId)
    // Reload the persisted summary so a newly terminal run clears the stale active run.
    await store.loadRunHistory(activeVersionId.value, false)
    ElMessage.success('运行状态已刷新')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    manualRefreshing.value = false
  }
}

watch(() => selectedRun.value?.id, () => {
  if (selectedRun.value?.runType === 'network-optimizer') activeTab.value = 'network-optimizer'
  else if (selectedRun.value?.runType === 'system-analysis') activeTab.value = 'system-analysis'
  else if (selectedRun.value?.runType === 'network') activeTab.value = 'network'
  else if (selectedRun.value?.runType === 'eclipse') activeTab.value = 'eclipse'
  else if (selectedRun.value?.runType === 'profile') activeTab.value = 'profile'
  else if (selectedRun.value?.runType === 'sensitivity') activeTab.value = 'sensitivity'
  else if (selectedRun.value?.runType === 'gas-lift-performance') activeTab.value = 'gas-lift-performance'
  else if (selectedRun.value?.runType === 'gas-lift-diagnostics') activeTab.value = 'gas-lift-diagnostics'
  else if (selectedRun.value?.runType === 'vfp-tables') activeTab.value = 'vfp-tables'
  else if (selectedRun.value?.runType === 'esp-curves') activeTab.value = 'esp-curves'
  else if (selectedRun.value?.runType === 'trajectory') activeTab.value = 'trajectory'
  else if (activeTab.value === 'profile' && selectedRun.value?.runType === 'nodal') activeTab.value = 'nodal'
})
watch([isNetworkModel, isWellModel, isEclipseModel], ([networkModel, wellModel, eclipseModel]) => {
  if (networkModel) {
    if (!['network', 'system-analysis', 'network-optimizer'].includes(runType.value)) runType.value = 'network'
    activeTab.value = runType.value === 'network-optimizer' ? 'network-optimizer' : (runType.value === 'system-analysis' ? 'system-analysis' : 'network')
    return
  }
  if (eclipseModel) {
    runType.value = 'eclipse'
    selectedStudy.value = null
    activeTab.value = 'eclipse'
    return
  }
  if (wellModel && (runType.value === 'network' || runType.value === 'system-analysis' || runType.value === 'network-optimizer')) runType.value = 'nodal'
  else if (!wellModel) runType.value = ''
  if (activeTab.value === 'network' || activeTab.value === 'system-analysis' || activeTab.value === 'network-optimizer') activeTab.value = selectedRun.value?.runType === 'profile' ? 'profile' : 'nodal'
}, { immediate: true })

const focusView = async view => {
  if (view === 'monitor') {
    activeTab.value = 'history'
    await nextTick()
    document.querySelector('.result-tabs')?.scrollIntoView({ block: 'start' })
    return
  }
  if (view === '3d') {
    if (isEclipseModel.value) {
      activeTab.value = 'eclipse'
      await nextTick()
      document.getElementById('eclipse-3d-result')?.scrollIntoView({ block: 'center' })
      return
    }
    if (isWellModel.value) {
      activeTab.value = 'trajectory'
      ElMessage.info('井筒模型打开井轨迹。网格三维需要已完成的 ECLIPSE 结果。')
      return
    }
    activeTab.value = 'network'
    ElMessage.info('管网结果是二维拓扑，没有网格三维。')
    return
  }
  if (isEclipseModel.value) {
    activeTab.value = 'eclipse'
    await nextTick()
    document.getElementById(view === '2d' ? 'eclipse-2d-result' : 'eclipse-summary-result')?.scrollIntoView({ block: 'center' })
    return
  }
  if (isNetworkModel.value) {
    activeTab.value = selectedRun.value?.runType === 'system-analysis' ? 'system-analysis' : 'network'
  } else if (selectedRun.value?.runType === 'profile') {
    activeTab.value = 'profile'
  } else {
    activeTab.value = 'nodal'
  }
  await nextTick()
  document.querySelector('.result-chart, .result-tabs')?.scrollIntoView({ block: 'center' })
}

defineExpose({ eclipseRunRequest, focusView })
</script>

<template>
  <section v-if="activeModel" class="model-run-page">
    <header class="model-header">
      <div>
        <div class="title-line">
          <h1>{{ activeModel.name }}</h1>
          <el-tag :type="activeVersion?.status === 'READY' ? 'success' : 'warning'">{{ activeVersion?.status || '无版本' }}</el-tag>
        </div>
        <p>{{ modelTypeLabel }} · v{{ activeVersion?.versionNo || '-' }}</p>
      </div>
      <div v-if="displayRun" class="run-summary">
        <el-tag :type="statusMeta[displayRun.status]?.[1] || 'info'">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</el-tag>
        <span>已用时间 {{ formatElapsed(activeElapsedMillis) }}</span>
      </div>
    </header>

      <div class="run-controls" :class="{ 'eclipse-run-controls': isEclipseRunPresentation }">
        <label>
        <span>模型版本</span>
        <el-select :model-value="activeVersionId" :disabled="hasActiveRun" @change="changeVersion">
          <el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`v${version.versionNo} · ${version.status}`" />
        </el-select>
      </label>
        <label v-if="!isEclipseRunPresentation">
        <span>Study</span>
        <el-select v-model="selectedStudy" :disabled="hasActiveRun || activeVersion?.status !== 'READY'" placeholder="请选择已有 Study">
          <el-option v-for="study in persistedStudies" :key="study" :value="study" :label="study" />
        </el-select>
      </label>
        <div v-if="!isEclipseRunPresentation" class="run-type-control">
        <span>运行类型</span>
        <el-radio-group v-model="runType" :disabled="hasActiveRun">
          <el-radio-button v-for="option in runTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</el-radio-button>
        </el-radio-group>
      </div>

        <div v-if="!isEclipseRunPresentation || eclipsePresentationAvailable" class="control-actions">
          <el-button type="primary" :loading="submittingRun" :disabled="!canRun" @click="submitRun">运行</el-button>
          <el-button type="danger" plain :loading="cancellingRun" :disabled="!activeRun?.cancellable" @click="cancelRun">取消</el-button>
          <el-button v-if="canRetryRun" type="warning" plain :loading="retryingRun" @click="retryRun">重新提交</el-button>
        </div>
      </div>

      <section v-if="isWellModel" class="well-scenario" aria-label="原模型参数">
        <strong>原模型参数 · v{{ activeVersion?.versionNo }}</strong>
        <span v-if="sourcePressure !== null" data-testid="source-reservoir-pressure">地层压力：{{ sourcePressure }} psia</span>
        <span v-else>{{ previewPending ? '正在验证并读取原模型参数…' : '地层压力：暂无可用原值（尚未读取、读取失败或单位不支持）' }}</span>
        <el-button link type="primary" :disabled="!canReadSource" :loading="previewPending" @click="readSourceParameters">重新验证并读取</el-button>
        <el-button v-if="sourcePressure !== null" link type="primary" :disabled="!canUseSource" @click="useSourcePressure">以原值创建方案</el-button>
        <small>从上传版本的隔离副本读取；重新验证期间暂不可计算，不修改原文件。</small>
        <small v-if="sourcePressure > 100000">原值超出当前方案编辑范围，仅供查看。</small>
      </section>
      <section v-if="activeVersion?.modelKind === 'legacy_well' && activeVersion?.status === 'READY'" class="well-scenario" aria-label="通用 PIPESIM 井模型提示">
        <strong>官方井模型已识别</strong>
        <span>该工程包含复杂完井、多个油管、注入/气举/泵或特殊流体结构，已归入通用井模型。</span>
        <span>当前开放节点分析、PT 剖面、组合运行和井轨迹；复杂完井/多油管不套用简化压力方案，气举、VFP、ESP 和敏感性按结构单独校验。</span>
        <label v-if="legacyProfileRun">PT 剖面出口压力 (psia)
          <el-input-number v-model="profileOutletPressure" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="PT 剖面出口压力" />
        </label>
        <small v-if="legacyProfileRun">该参数写入本次隔离计算副本；官方模型没有可用出口压力时，默认 100 psia 会明确传给 PIPESIM。</small>
      </section>
      <section v-if="supportsCurrentScenario" class="well-scenario" aria-label="井筒压力方案">
        <el-checkbox v-model="scenarioEnabled" :disabled="hasActiveRun || submittingRun">使用地层压力方案</el-checkbox>
        <template v-if="scenarioEnabled">
          <label>地层压力 (psia) <el-input-number v-model="scenarioPressure" :min="0.000001" :max="100000" :disabled="hasActiveRun || submittingRun" aria-label="方案地层压力" /></label>
          <span>仅修改本次计算副本；模型单位不匹配或设置失败时停止计算。</span>
          <span v-if="activeVersion?.modelKind === 'basic_gas' && runType !== 'nodal'">基础气井方案同时将该值用于 PT 入口压力，沿用桌面端计算方式。</span>
          <span v-if="sourcePressure !== null && Number.isFinite(scenarioPressure)" data-testid="pressure-scenario-comparison">原值 {{ sourcePressure }} → 方案值 {{ scenarioPressure }} psia</span>
        </template>
        <span v-else>使用上传模型的原始参数</span>
      </section>
      <section v-if="isSystemAnalysisRun" class="system-analysis-scenario" aria-label="PIPESIM 系统分析工况">
        <strong>System Analysis 工况</strong>
        <label>BranchTerminator
          <el-input v-model="systemAnalysisBranchTerminator" :disabled="hasActiveRun || submittingRun" aria-label="系统分析分支终点" />
        </label>
        <label>出口压力 (psia)
          <el-input-number v-model="systemAnalysisOutletPressure" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="系统分析出口压力" />
        </label>
        <label>液体流量扫描值
          <el-input v-model="systemAnalysisValuesText" :disabled="hasActiveRun || submittingRun" placeholder="例如：2400, 3000, 3600" aria-label="系统分析液体流量扫描值" />
        </label>
        <small>调用官方 PIPESIM System Analysis；固定 Well、Study 和边界条件，仅扫描 LiquidFlowRate，单次最多 8 个工况。每个工况返回系统量、节点量和压力/温度/距离剖面。</small>
      </section>
      <section v-if="isNetworkOptimizerRun" class="system-analysis-scenario" aria-label="PIPESIM 网络优化工况">
        <strong>Network Optimizer 工况</strong>
        <el-checkbox v-model="networkOptimizerApplyResults" :disabled="hasActiveRun || submittingRun">将优化结果应用到隔离方案副本</el-checkbox>
        <span>使用官方模型内置的优化目标、边界条件和控制变量执行一次真实优化计算。</span>
        <small v-if="networkOptimizerApplyResults">调用官方 apply_results()；只写入本次运行的隔离副本，并发布可下载的 pipesim-network-optimizer-applied.pips，不修改上传版本。</small>
        <small v-else>当前只读取并展示优化结果，不写回模型；结果中的 NaN 会按官方“不适用/无值”规范化为空值并标记。</small>
      </section>
      <section v-if="isNetworkModel && !isSystemAnalysisRun" class="network-scenario" aria-label="管网边界条件方案">
        <strong>Network 边界条件方案</strong>
        <div v-if="networkChokeAvailable" class="network-choke-scenario" aria-label="管网 Choke Bean Size 方案">
          <el-checkbox v-model="networkChokeEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleNetworkChoke">创建 Choke Bean Size 方案</el-checkbox>
          <template v-if="networkChokeEnabled">
            <label>基线运行
              <el-select v-model="networkChokeBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="Choke 方案基线运行">
                <el-option v-for="run in networkChokeBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id} · ${run.study}`" />
              </el-select>
            </label>
            <label>Choke
              <el-select v-model="networkChokeName" :disabled="hasActiveRun || submittingRun" aria-label="方案 Choke">
                <el-option v-for="choke in networkChokes" :key="choke.name" :value="choke.name" :label="choke.name" />
              </el-select>
            </label>
            <span>原 Bean Size：{{ networkChokeRecord?.beanSize }} {{ networkChokeRecord?.unit }}</span>
            <label>目标 Bean Size
              <el-input-number v-model="networkChokeTarget" :min="0.000001" :max="1000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="目标 Choke Bean Size" />
              <span>{{ networkChokeRecord?.unit }}</span>
            </label>
            <small>依据官方 get_set_value.py；只在本次隔离副本调用 Model.set_value(Choke, BeanSize)，回读成功后执行 Network Simulation。原上传工程不修改。</small>
          </template>
        </div>
        <template v-if="networkInspection">
          <p class="network-scenario-hint">只覆盖本次计算副本；未选择的节点沿用 Study 原条件。单位沿用 PIPESIM Study 设置。</p>
          <div v-for="boundary in networkBoundaryDrafts" :key="boundary.node" class="network-boundary-row">
            <el-button class="network-boundary-toggle" size="small" plain :type="boundary.enabled ? 'primary' : 'default'" :disabled="hasActiveRun || submittingRun" :aria-pressed="boundary.enabled" @click="toggleNetworkBoundary(boundary)">{{ boundary.enabled ? '取消覆盖' : '覆盖' }}：{{ boundary.node }}（{{ boundary.boundaryNodeType }}）</el-button>
            <el-select v-model="boundary.field" :disabled="hasActiveRun || submittingRun" @change="boundary.enabled = true" aria-label="边界覆盖字段">
              <el-option v-for="field in networkParameterFields" :key="field.value" :value="field.value" :label="field.label" />
            </el-select>
            <el-input-number v-model="boundary.value" :disabled="hasActiveRun || submittingRun" @change="boundary.enabled = true" :controls="false" aria-label="边界覆盖值" />
          </div>
        </template>
        <span v-else>当前 Study 未返回可编辑的边界条件；仍可直接运行原始 Network Study。</span>
      </section>
      <section v-if="isSensitivityRun" class="well-scenario" aria-label="井筒敏感性分析参数">
        <label>敏感性变量
          <el-select v-model="sensitivityVariable" :disabled="hasActiveRun || submittingRun" aria-label="敏感性变量">
            <el-option value="reservoirPressure" label="地层压力 (psia)" />
            <el-option v-if="activeVersion?.modelKind === 'black_oil_liquid'" value="waterCut" label="含水率 (%)" />
            <el-option v-if="activeVersion?.modelKind === 'black_oil_liquid'" value="gor" label="GOR" />
            <el-option value="tubingInnerDiameter" label="油管内径" />
          </el-select>
        </label>
        <label>扫描值
          <el-input v-model="sensitivityValuesText" :disabled="hasActiveRun || submittingRun" placeholder="例如：3000, 4000, 5000" aria-label="敏感性扫描值" />
        </label>
        <span>将对每个值执行一次真实节点分析，值需严格递增，最多 12 个。</span>
      </section>
      <section v-if="isGasLiftPerformanceRun || isGasLiftDiagnosticsRun" class="gas-lift-scenario" aria-label="PIPESIM 气举参数">
        <strong>{{ isGasLiftDiagnosticsRun ? 'Gas Lift Diagnostics' : 'Gas Lift Performance' }}</strong>
        <label>生产井<el-input v-model="gasLiftProducer" :disabled="hasActiveRun || submittingRun" aria-label="气举生产井" /></label>
        <span>生产井名称将由 Worker 对 PIPESIM 模型中的真实井名再次校验。</span>
        <label>出口压力 (psia)<el-input-number v-model="gasLiftOutletPressure" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举出口压力" /></label>
        <label>注气温度 (°F)<el-input-number v-model="gasLiftSurfaceInjectionTemperature" :min="-1000" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举注气温度" /></label>
        <label>目标注气量 (mmscf/d)<el-input-number v-model="gasLiftTargetInjectionRate" :min="0" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举目标注气量" /></label>
        <label>地层压力 (psia)<el-input-number v-model="gasLiftReservoirPressure" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举地层压力" /></label>
        <label>GOR (scf/STB)<el-input-number v-model="gasLiftGor" :min="0" :max="1000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举 GOR" /></label>
        <label>含水率 (%)<el-input-number v-model="gasLiftWaterCut" :min="0" :max="100" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="气举含水率" /></label>
        <label v-if="isGasLiftPerformanceRun" class="wide-field">注气量扫描值 (mmscf/d)<el-input v-model="gasLiftValuesText" :disabled="hasActiveRun || submittingRun" aria-label="气举注气量扫描值" /></label>
        <small v-if="isGasLiftPerformanceRun">调用官方 Gas Lift Diagnostics sensitivity 接口生成气举性能曲线；固定注气诊断模式和相态比，扫描值严格递增，最多 16 个工况。只修改隔离副本。</small>
        <small v-else>调用官方 PIPESIM Gas Lift Diagnostics，展示每个注气工况的气举阀状态、阀参数和未节流气量；只修改隔离副本。</small>
      </section>
      <section v-if="isVfpTablesRun" class="vfp-scenario" aria-label="PIPESIM VFP 表参数">
        <strong>VFP Tables Simulation</strong>
        <label>生产井<el-input v-model="vfpProducer" :disabled="hasActiveRun || submittingRun" aria-label="VFP 生产井" /></label>
        <label>表号<el-input-number v-model="vfpTableNumber" :min="1" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="VFP 表号" /></label>
        <label>井底基准深度<el-input-number v-model="vfpBottomHoleDatumDepth" :min="0" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="VFP 井底基准深度" /></label>
        <el-checkbox v-model="vfpIncludeTemperature" :disabled="hasActiveRun || submittingRun">返回温度表</el-checkbox>
        <label class="wide-field">液量轴 (STB/d)<el-input v-model="vfpLiquidRatesText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 液量轴" /></label>
        <label class="wide-field">出口压力轴 (psia)<el-input v-model="vfpOutletPressuresText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 出口压力轴" /></label>
        <label>含水率轴 (fraction)<el-input v-model="vfpWaterCutText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 含水率轴" /></label>
        <label>GOR 轴 (MSCF/STB)<el-input v-model="vfpGorText" :disabled="hasActiveRun || submittingRun" aria-label="VFP GOR 轴" /></label>
        <label>ALQ 轴 (psia)<el-input v-model="vfpArtificialLiftText" :disabled="hasActiveRun || submittingRun" aria-label="VFP ALQ 轴" /></label>
        <small>调用官方 VFP Tables Simulation，展示真实 BHP/温度表、维度轴和官方原始文本；当前只生成 ECLIPSE VFPPROD 表，不自动耦合进 ECLIPSE。</small>
      </section>
      <div v-if="isWellModel && selectedRun" class="inline-notice"><span>当前结果 #{{ selectedRun.id }}：{{ scenarioSnapshot !== null ? `压力方案 · 地层压力 ${scenarioSnapshot} psia` : '原模型参数' }}</span>
        <el-button v-if="scenarioSnapshot !== null" link type="primary" :disabled="!canReuseScenario" @click="reuseScenario">载入此方案参数</el-button>
      </div>
      <p v-if="runCapabilityMessage" class="inline-notice warning" :title="`${runCapabilityMessage}；历史运行与已有结果仍可查看。`">
        新运行不可用：{{ runCapabilityMessage }}
      </p>
      <section v-if="isNetworkModel || isEclipseModel" class="model-revalidation" aria-label="模型检查信息">
        <el-button link type="primary" :disabled="!canRevalidateActiveModel" :loading="readingVersionId === activeVersionId" @click="revalidateActiveModel">重新验证并读取模型检查信息</el-button>
        <small>从上传版本的隔离副本重新读取；不修改原文件。重新验证期间暂不可计算。</small>
      </section>

      <p
        v-if="isEclipseRunPresentation && !eclipsePresentationAvailable"
        class="inline-notice warning"
        :title="activeVersion?.status !== 'READY' ? '请等待模型版本验证为 READY。' : '当前 READY 版本缺少 DATA 检查信息。'"
      >ECLIPSE 运行不可用：{{ activeVersion?.status !== 'READY' ? '模型尚未 READY' : '缺少有效 DATA 检查' }}</p>
      <div v-else-if="isEclipseRunPresentation" class="eclipse-request-summary">
        <span>运行类型：ECLIPSE</span><span>Study：不适用</span><span>参数：{{ eclipseForecastEnabled ? '历史重启预测' : (eclipseCompletionFactorEnabled ? 'COMPDAT 连接因子方案' : (eclipseCompletionEnabled ? 'COMPDAT 完井状态方案' : (eclipseWconProdEnabled ? 'WCONPROD ORAT 方案' : (eclipseWconInjeEnabled ? 'WCONINJE RATE 方案' : (eclipseWconHistEnabled ? 'WCONHIST ORAT 方案' : (eclipseScheduleEnabled ? 'WELOPEN 方案' : '不覆盖')))))) }}</span>
      </div>
      <section v-if="isEclipseRunPresentation && eclipseScheduleAvailable" class="eclipse-scenario" aria-label="ECLIPSE 单井启停方案">
        <el-checkbox v-model="eclipseScheduleEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseSchedule">创建单井 WELOPEN 方案</el-checkbox>
        <template v-if="eclipseScheduleEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 调度基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>井
            <el-select v-model="eclipseScheduleWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 调度方案井">
              <el-option v-for="well in eclipseScheduleWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <label>日期
            <el-select v-model="eclipseScheduleDate" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 调度方案日期">
              <el-option v-for="date in eclipseScheduleDates" :key="date" :value="date" :label="date" />
            </el-select>
          </label>
          <label>状态
            <el-select v-model="eclipseScheduleStatus" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 调度方案状态">
              <el-option value="OPEN" label="OPEN（打开）" />
              <el-option value="SHUT" label="SHUT（关闭）" />
            </el-select>
          </label>
          <small>只修改本次隔离副本；方案绑定当前版本的成功基线运行，原工程不变。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseCompletionAvailable" class="eclipse-scenario" aria-label="ECLIPSE COMPDAT 完井状态方案">
        <el-checkbox v-model="eclipseCompletionEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseCompletion">创建 COMPDAT 完井状态方案</el-checkbox>
        <template v-if="eclipseCompletionEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>完井段
            <el-select v-model="eclipseCompletionKey" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 完井段">
              <el-option v-for="completion in eclipseCompletionRecords" :key="`${completion.sourceFile}:${completion.lineNumber}`" :value="`${completion.sourceFile}:${completion.lineNumber}`" :label="`${completion.well} · I${completion.i}/J${completion.j}/K${completion.k1}-${completion.k2} · ${completion.sourceFile}:${completion.lineNumber}`" />
            </el-select>
          </label>
          <label>目标状态
            <el-select v-model="eclipseCompletionStatus" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 目标状态">
              <el-option value="OPEN" label="OPEN（打开）" />
              <el-option value="SHUT" label="SHUT（关闭）" />
            </el-select>
          </label>
          <small>只修改隔离副本中已检查的单条 COMPDAT 行的 OPEN/SHUT；井名、I/J/K、源文件和行号绑定原始检查结果，源工程不变。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseCompletionFactorAvailable" class="eclipse-scenario" aria-label="ECLIPSE COMPDAT 连接因子方案">
        <el-checkbox v-model="eclipseCompletionFactorEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseCompletionFactor">创建 COMPDAT 连接因子方案</el-checkbox>
        <template v-if="eclipseCompletionFactorEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 连接因子基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>完井段
            <el-select v-model="eclipseCompletionKey" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 连接因子完井段">
              <el-option v-for="completion in eclipseCompletionFactorRecords" :key="`${completion.sourceFile}:${completion.lineNumber}`" :value="`${completion.sourceFile}:${completion.lineNumber}`" :label="`${completion.well} · I${completion.i}/J${completion.j}/K${completion.k1}-${completion.k2} · ${completion.sourceFile}:${completion.lineNumber}`" />
            </el-select>
          </label>
          <label>原连接因子
            <el-input :model-value="eclipseCompletionFactorRecord?.originalConnectionFactor ?? '—'" disabled aria-label="ECLIPSE COMPDAT 原连接因子" />
          </label>
          <label>目标连接因子
            <el-input-number v-model="eclipseCompletionFactorTarget" :min="0.000001" :max="1000000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 目标连接因子" />
          </label>
          <small>调用官方 ECLIPSE 求解器，只修改隔离副本中已检查的单条 COMPDAT 数值 CF；当前示例可选择 BASE.SCH:116 的 G1 I14/J2/K1-1，并把 3.028 改为 1.5，原工程不变。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseWconProdAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONPROD 预测初始段方案">
        <el-checkbox v-model="eclipseWconProdEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseWconProd">创建 WCONPROD 预测初始段方案</el-checkbox>
        <template v-if="eclipseWconProdEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>预测生产井
            <el-select v-model="eclipseWconProdWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 方案井">
              <el-option v-for="well in eclipseWconProdWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <label>状态
            <el-select v-model="eclipseWconProdStatus" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 方案状态">
              <el-option value="OPEN" label="OPEN（打开）" />
              <el-option value="SHUT" label="SHUT（关闭）" />
            </el-select>
          </label>
          <label>控制模式
            <el-input model-value="ORAT" disabled aria-label="ECLIPSE WCONPROD 控制模式" />
          </label>
          <label>ORAT 目标值
            <el-input-number v-model="eclipseWconProdTargetOilRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD ORAT 目标值" />
          </label>
          <small>只修改预测段初始 WCONPROD 中已有 ORAT 行的状态和目标值；本轮不开放 WRAT/GRAT、压力控制、批量多井或任意 Schedule 文本。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseWconInjeAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONINJE 注入方案">
        <el-checkbox v-model="eclipseWconInjeEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseWconInje">创建 WCONINJE RATE 方案</el-checkbox>
        <template v-if="eclipseWconInjeEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONINJE 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>注入井
            <el-select v-model="eclipseWconInjeWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONINJE 方案井">
              <el-option v-for="well in eclipseWconInjeWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <label>日期
            <el-select v-model="eclipseWconInjeDate" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONINJE 方案日期">
              <el-option v-for="date in eclipseScheduleDates" :key="date" :value="date" :label="date" />
            </el-select>
          </label>
          <label>注入流体
            <el-input :model-value="eclipseWconInjeFluid || '—'" disabled aria-label="ECLIPSE WCONINJE 注入流体" />
          </label>
          <label>控制模式
            <el-input model-value="RATE" disabled aria-label="ECLIPSE WCONINJE 控制模式" />
          </label>
          <label>RATE 注入速率
            <el-input-number v-model="eclipseWconInjeTargetRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONINJE RATE 注入速率" />
          </label>
          <small>只修改隔离副本中目标日期的已有 WCONINJE RATE 行；流体和控制模式来自已验证原始记录，单位遵循当前 ECLIPSE 工程关键字约定。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseWconHistAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONHIST 产量方案">
        <el-checkbox v-model="eclipseWconHistEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseWconHist">创建 WCONHIST ORAT 方案</el-checkbox>
        <template v-if="eclipseWconHistEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>井
            <el-select v-model="eclipseScheduleWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST 方案井">
              <el-option v-for="well in eclipseScheduleWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <label>日期
            <el-select v-model="eclipseScheduleDate" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST 方案日期">
              <el-option v-for="date in eclipseScheduleDates" :key="date" :value="date" :label="date" />
            </el-select>
          </label>
          <label>状态
            <el-select v-model="eclipseScheduleStatus" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST 方案状态">
              <el-option value="OPEN" label="OPEN（打开）" />
              <el-option value="SHUT" label="SHUT（关闭）" />
            </el-select>
          </label>
          <label>ORAT 目标值
            <el-input-number v-model="eclipseWconHistTargetOilRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST ORAT 目标值" />
          </label>
          <small>只修改隔离副本中目标日期的已有 WCONHIST ORAT 行；目标值保留 ECLIPSE 关键字原值，不在前端猜测单位。</small>
        </template>
      </section>
      <section v-if="isEclipseRunPresentation && eclipseForecastAvailable" class="eclipse-scenario" aria-label="ECLIPSE 历史重启预测方案">
        <el-checkbox v-model="eclipseForecastEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseForecast">创建历史重启预测方案</el-checkbox>
        <template v-if="eclipseForecastEnabled">
          <label>历史运行
            <el-select v-model="eclipseForecastHistoryRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 历史运行">
              <el-option v-for="run in eclipseForecastHistoryOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>预测 DATA
            <el-select v-model="eclipseForecastDataFile" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 预测 DATA">
              <el-option v-for="file in eclipseForecastDataFiles" :key="file" :value="file" :label="file" />
            </el-select>
          </label>
          <label>重启 Artifact
            <el-select v-model="eclipseForecastRestartArtifactName" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重启 Artifact">
              <el-option v-for="artifact in eclipseForecastRestartArtifacts" :key="artifact.name" :value="artifact.name" :label="artifact.name" />
            </el-select>
          </label>
          <label>重启报告编号
            <el-input-number v-model="eclipseForecastRestartReport" :min="1" :max="1000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重启报告编号" />
          </label>
          <small>预测运行会在隔离副本中使用所选 DATA，并从历史运行的已发布 FUNRST Artifact 恢复；不读取任意本机路径。</small>
        </template>
      </section>

    <p v-if="runPollingUnavailable" class="inline-notice warning" title="自动刷新已停止，可手动读取一次持久状态。">
      运行状态暂时无法刷新 <el-button link type="primary" :loading="manualRefreshing" @click="refreshRunManually">手动刷新</el-button>
    </p>
    <p v-if="terminalRunGuidance" class="inline-notice warning" :title="terminalRunGuidance">
      {{ statusMeta[displayRun.status]?.[0] || displayRun.status }}：{{ terminalRunGuidance }}
    </p>
    <p v-if="displayRun?.resultExpired" class="inline-notice warning" title="运行审计记录仍保留，但解析结果已超过 30 天保留期限。">
      本次运行的解析结果已过期，保留运行状态、事件和审计信息；Artifact 需按各自到期时间判断是否仍可下载。
    </p>

    <div v-if="(!isEclipseRunPresentation || eclipsePresentationAvailable) && displayRun && hasActiveRun" class="stage-strip" aria-label="真实运行阶段">
      <div v-for="(stage, index) in stages" :key="stage.status" class="stage" :class="{ active: currentStageIndex === index, done: currentStageIndex > index }">
        <i />
        <span>{{ stage.label }}</span>
      </div>
      <span v-if="currentStageIndex < 0" class="queue-stage">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</span>
    </div>

    <p v-if="isPartial" class="inline-notice warning">组合运行部分成功：节点分析结果可用，PT 剖面失败。</p>
    <p v-if="isNetworkPartial" class="inline-notice warning" title="仅展示实际返回并通过安全校验的数据。">部分真实计算结果：部分管网结果不可用。</p>
    <p v-if="networkTopologyUnavailable" class="inline-notice danger" title="返回的拓扑或统计信息未通过安全展示校验。">管网结果不可用：已隐藏未通过校验的数据。</p>

    <div v-if="safeRunError && !isEclipseModel" class="structured-error">
      <dl>
        <div><dt>类别</dt><dd>{{ safeRunError.category }}</dd></div>
        <div><dt>代码</dt><dd>{{ safeRunError.code }}</dd></div>
        <div><dt>消息</dt><dd>运行失败详情已隐藏。</dd></div>
        <div><dt>可重试</dt><dd>{{ safeRunError.retryable ? '是' : '否' }}</dd></div>
      </dl>
    </div>
    <p v-if="networkContractRejected" class="inline-notice warning" title="返回数据未通过展示契约，未绘制图表或结果表。">
      结果未通过展示契约 <el-button link type="primary" @click="selectHistoricalSuccessfulNetworkRun">{{ historicalSuccessfulNetworkRun ? '选择历史成功运行' : '查看运行记录' }}</el-button>
    </p>

    <div v-if="isWellModel && validWellResult" class="comparison-controls">
      <span>{{ runLabel(selectedRun) }}</span>
      <el-select v-model="comparisonId" filterable clearable :loading="comparisonLoading" placeholder="选择同版本历史结果对比" aria-label="井筒历史结果对比">
        <el-option v-for="run in comparisonOptions" :key="run.id" :value="run.id" :label="`${runLabel(run)} · ${run.runType}`" />
      </el-select>
      <span v-if="comparisonError" role="status">{{ comparisonError }}</span>
    </div>
    <PipesimWellComparisonSummary
      v-if="isWellModel && validWellResult && comparisonResult"
      :result="validWellResult"
      :comparison-result="comparisonResult"
      :current-run="selectedRun"
      :comparison-run="comparisonRun"
      :current-label="runLabel(selectedRun)"
      :comparison-label="runLabel(comparisonRun)"
    />
    <el-tabs v-model="activeTab" class="result-tabs">
       <el-tab-pane v-if="isWellModel" label="节点分析" name="nodal">
        <PipesimNodalResult v-if="activeTab === 'nodal'" :result="validWellResult" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
      </el-tab-pane>
       <el-tab-pane v-if="isWellModel" label="PT 剖面" name="profile">
        <PipesimProfileResult v-if="activeTab === 'profile'" :result="validWellResult" :partial="isPartial" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel && validWellResult?.runTask === 'combined'" label="综合结果" name="combined">
        <section v-if="activeTab === 'combined'" class="combined-results" aria-label="井筒综合结果">
          <PipesimNodalResult :result="validWellResult" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
          <PipesimProfileResult :result="validWellResult" :partial="isPartial" :comparison-result="comparisonResult" :source-label="runLabel(selectedRun)" :comparison-label="runLabel(comparisonRun)" />
        </section>
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="敏感性结果" name="sensitivity">
        <PipesimSensitivityResult v-if="activeTab === 'sensitivity'" :result="validSensitivityResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="气举性能" name="gas-lift-performance">
        <PipesimGasLiftPerformanceResult v-if="activeTab === 'gas-lift-performance'" :result="validGasLiftPerformanceResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="气举诊断" name="gas-lift-diagnostics">
        <PipesimGasLiftDiagnosticsResult v-if="activeTab === 'gas-lift-diagnostics'" :result="validGasLiftDiagnosticsResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="VFP 表" name="vfp-tables">
        <PipesimVfpTablesResult v-if="activeTab === 'vfp-tables'" :result="validVfpTablesResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="ESP 曲线" name="esp-curves">
        <PipesimEspCurvesResult v-if="activeTab === 'esp-curves'" :result="validEspCurvesResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="井轨迹" name="trajectory">
        <PipesimWellTrajectoryResult v-if="activeTab === 'trajectory'" :result="validTrajectoryResult" />
      </el-tab-pane>
       <el-tab-pane v-if="isNetworkModel" label="管网结果" name="network">
         <PipesimNetworkResult :result="validNetworkResult" :partial="isNetworkPartial" :run-id="selectedRun?.id" :history="runHistory" :run="selectedRun" />
       </el-tab-pane>
       <el-tab-pane v-if="isNetworkModel" label="系统分析" name="system-analysis">
         <PipesimSystemAnalysisResult v-if="activeTab === 'system-analysis'" :result="validSystemAnalysisResult" />
       </el-tab-pane>
       <el-tab-pane v-if="isNetworkModel" label="网络优化" name="network-optimizer">
         <PipesimNetworkOptimizerResult v-if="activeTab === 'network-optimizer'" :result="validNetworkOptimizerResult" />
       </el-tab-pane>
       <el-tab-pane v-if="pipesimPackageAvailable" label="工程包" name="package">
         <PipesimPackageOverview :inspection="activeVersion.inspection" />
       </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="ECLIPSE 结果" name="eclipse">
        <EclipseRunResult :run="selectedRun" :history="runHistory" :inspection="activeVersion?.inspection" />
      </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="DATA 检查" name="inspection">
        <EclipseDataInspectionOverview :embedded="true" />
      </el-tab-pane>
      <el-tab-pane label="运行记录" name="history">
        <PipesimRunHistory
          :runs="runHistory"
          :selected-run-id="selectedRun?.id"
          :loading="loadingHistory"
          @select="selectHistoryRun"
        />
      </el-tab-pane>
    </el-tabs>
    <details v-if="isExecutionPanelRun" class="execution-panel" aria-label="持久化执行详情">
      <summary>
        <span><strong>执行详情</strong><small>持久化事件、清理结果与 Artifact 元数据</small></span>
        <span class="execution-count">事件 {{ executionEvents.length }} · Artifact {{ executionArtifacts.length }}</span>
      </summary>
      <div class="execution-panel-content">
        <dl class="execution-provenance">
          <div><dt>模型</dt><dd>{{ displayRun.modelName || activeModel.name }}</dd></div>
          <div><dt>版本</dt><dd>v{{ displayRun.versionNo || activeVersion?.versionNo || '-' }}</dd></div>
          <div><dt>Study</dt><dd>{{ displayRun.study || '不适用' }}</dd></div>
          <div><dt>运行 ID</dt><dd>{{ displayRun.id }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ displayRun.createdAt || '-' }}</dd></div>
          <div><dt>用时</dt><dd>{{ formatElapsed(displayRun.elapsedMillis) }}</dd></div>
        </dl>
        <div class="execution-section">
          <h3>事件 <span>{{ executionEvents.length }}</span></h3>
          <div v-if="executionEvents.length" class="technical-table-scroll">
            <el-table :data="executionEvents" border size="small" max-height="220" class="technical-table">
              <el-table-column prop="type" label="类型" min-width="100" />
              <el-table-column prop="status" label="状态" min-width="110" />
              <el-table-column prop="message" label="消息" min-width="220" show-overflow-tooltip />
              <el-table-column prop="occurredAt" label="时间" min-width="180" />
            </el-table>
          </div>
          <p v-else class="execution-empty">当前运行没有持久化事件。</p>
        </div>
        <div v-if="executionCleanup.length" class="execution-section">
          <h3>清理结果</h3>
          <dl class="cleanup-grid"><div v-for="entry in executionCleanup" :key="entry.key"><dt>{{ entry.key }}</dt><dd>{{ entry.value }}</dd></div></dl>
        </div>
        <div class="execution-section">
          <h3>Artifact <span>{{ executionArtifacts.length }}</span></h3>
          <div v-if="executionArtifacts.length" class="technical-table-scroll">
            <el-table :data="executionArtifacts" border size="small" max-height="220" class="technical-table">
              <el-table-column prop="name" label="文件名" min-width="150" show-overflow-tooltip />
              <el-table-column prop="type" label="类型" min-width="120" />
              <el-table-column prop="size" label="大小" width="120" />
              <el-table-column prop="sha256" label="SHA-256" min-width="260" show-overflow-tooltip />
              <el-table-column prop="expiresAt" label="到期时间" min-width="180" />
              <el-table-column label="操作" width="88">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="artifactDownloadPending === row.id" :disabled="!row.downloadable" @click="downloadArtifact(row)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <p v-else class="execution-empty">当前运行没有已发布的 Artifact。</p>
        </div>
      </div>
    </details>
  </section>
</template>

<style lang="scss" scoped>
.combined-results { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.combined-results > * { min-width: 0; }
@media (max-width: 1200px) { .combined-results { grid-template-columns: 1fr; } }
.well-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 10px; border-bottom: 1px solid #deded9; font-size: 12px; }
.well-scenario > span { color: #73777d; }
.well-scenario > small { flex-basis: 100%; color: #73777d; }
.network-scenario { padding: 10px; border-bottom: 1px solid #deded9; font-size: 12px; }
.network-scenario-hint { margin: 6px 0; color: #73777d; }
.network-boundary-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-top: 6px; }
.network-boundary-row .el-select { width: 132px; }
.network-boundary-row .el-input-number { width: 150px; }
.network-choke-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; margin: 8px 0; padding: 9px 10px; border: 1px solid #d8e7f5; background: #f7fbff; }
.network-choke-scenario label { display: flex; align-items: center; gap: 5px; color: #606266; }
.network-choke-scenario label .el-select { width: 170px; }
.network-choke-scenario label .el-input-number { width: 150px; }
.network-choke-scenario small { flex-basis: 100%; color: #73777d; line-height: 1.5; }
.system-analysis-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; padding: 10px; border-bottom: 1px solid #deded9; background: #f7fbff; font-size: 12px; }
.system-analysis-scenario label { display: flex; align-items: center; gap: 5px; color: #606266; }
.system-analysis-scenario label:first-of-type .el-input { width: 140px; }
.system-analysis-scenario label:nth-of-type(2) .el-input-number { width: 145px; }
.system-analysis-scenario label:nth-of-type(3) .el-input { width: 230px; }
.system-analysis-scenario small { flex-basis: 100%; color: #73777d; }
.comparison-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 7px 10px; background: #f3f3f0; font-size: 12px; }.comparison-controls .el-select { width: 340px; max-width: 100%; }
.model-run-page { min-width: 0; min-height: 0; padding: 14px 18px 22px; color: #303133; overflow: auto; background: #fff; }
.model-header { min-height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 0 0 8px; border-bottom: 1px solid #dcdfe6; }
.title-line { display: flex; align-items: center; gap: 10px; }
h1 { margin: 0; font-size: 19px; font-weight: 600; }
.model-header p { margin: 2px 0 0; color: #909399; font-size: 12px; }
.run-summary { display: flex; align-items: center; gap: 12px; color: #606266; font-size: 13px; }
.execution-panel { min-width: 0; margin-top: 14px; border: 1px solid #dcdfe6; background: #fff; }.execution-panel summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 12px; cursor: pointer; list-style: none; color: #303133; font-size: 13px; }.execution-panel summary::-webkit-details-marker { display: none; }.execution-panel summary::before { content: '+'; margin-right: 8px; color: #606266; font-weight: 600; }.execution-panel[open] summary { border-bottom: 1px solid #e4e7ed; background: #f5f5f4; }.execution-panel[open] summary::before { content: '-'; }.execution-panel summary > span:first-of-type { display: flex; align-items: baseline; gap: 9px; min-width: 0; }.execution-panel summary small, .execution-count { color: #909399; font-size: 12px; font-weight: 400; }.execution-count { white-space: nowrap; }.execution-panel-content { padding: 12px; }.execution-section + .execution-section { margin-top: 14px; }.execution-section h3 { margin: 0 0 7px; color: #303133; font-size: 13px; }.execution-section h3 span { color: #909399; font-weight: 400; }.technical-table-scroll { overflow-x: auto; }.technical-table { min-width: 680px; }.execution-empty { margin: 0; padding: 8px 10px; color: #909399; font-size: 12px; background: #f5f5f4; }.cleanup-grid { display: flex; flex-wrap: wrap; gap: 6px 18px; margin: 0; padding: 9px 10px; background: #f5f5f4; font-size: 12px; }.cleanup-grid div { display: flex; gap: 6px; }.cleanup-grid dt { color: #737a84; }.cleanup-grid dd { margin: 0; color: #303133; }
.execution-provenance { display: flex; flex-wrap: wrap; gap: 6px 20px; margin: 0 0 12px; padding: 0 0 10px; border-bottom: 1px solid #e4e7ed; font-size: 12px; }.execution-provenance div { display: flex; gap: 5px; }.execution-provenance dt { color: #909399; }.execution-provenance dd { margin: 0; overflow-wrap: anywhere; }
.run-controls { display: grid; grid-template-columns: minmax(150px, 210px) minmax(170px, 240px) auto auto; align-items: end; gap: 8px 12px; margin: 8px 0; padding: 7px 10px; border-bottom: 1px solid #deded9; background: #f3f3f0; }
.run-controls.eclipse-run-controls { grid-template-columns: minmax(150px, 210px) auto; }
.run-controls label, .run-type-control { min-width: 0; }
.run-controls label > span, .run-type-control > span { display: block; margin-bottom: 6px; color: #606266; font-size: 12px; }
.run-controls .el-select { width: 100%; }
.control-actions { display: flex; gap: 8px; }
.eclipse-request-summary { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 0 0 8px; padding: 4px 8px; color: #606266; font-size: 12px; }
.eclipse-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; margin: 0 0 8px; padding: 8px 10px; border-bottom: 1px solid #deded9; background: #fffdf0; font-size: 12px; }.eclipse-scenario label { display: flex; align-items: center; gap: 5px; color: #606266; }.eclipse-scenario .el-select { width: 155px; }.eclipse-scenario small { flex-basis: 100%; color: #73777d; }
.inline-notice { min-height: 26px; display: flex; align-items: center; gap: 6px; margin: 0 0 6px; padding: 0 8px; overflow: hidden; border-left: 2px solid #c99b00; background: #fff9dd; color: #695600; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }.inline-notice.danger { border-left-color: #c45656; background: #fff2f2; color: #8b3030; }.inline-notice .el-button { flex: 0 0 auto; }
.stage-strip { display: flex; align-items: center; gap: 0; min-height: 44px; margin-bottom: 12px; padding: 0 14px; border: 1px solid #dcdfe6; background: #f5f5f4; }
.stage { position: relative; min-width: 120px; display: flex; align-items: center; gap: 7px; color: #909399; font-size: 12px; }
.stage:not(:last-of-type)::after { content: ''; width: 48px; height: 1px; margin: 0 10px; background: #d7dee8; }
.stage i { width: 8px; height: 8px; border: 2px solid #c0c4cc; border-radius: 50%; background: #fff; }
.stage.active { color: #303133; font-weight: 600; }.stage.active i { border-color: #d9a300; background: #f4d000; box-shadow: 0 0 0 3px #fff3bf; }
.stage.done { color: #606266; }.stage.done i { border-color: #606266; background: #606266; }
.queue-stage { margin-left: auto; color: #606266; }
.structured-error { margin-bottom: 14px; padding: 12px 14px; border-left: 3px solid #d94b4b; background: #fff3f3; color: #8b2525; }
.structured-error dl { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 0; font-size: 12px; }
.structured-error dl div { display: flex; gap: 5px; }.structured-error dt { color: #a85b5b; }.structured-error dd { margin: 0; }
.result-tabs { min-height: 0; margin-top: 2px; }.result-tabs :deep(.el-tabs__header) { margin: 0 0 8px; border-bottom: 1px solid #dcdfe6; }.result-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; background: #dcdfe6; }.result-tabs :deep(.el-tabs__active-bar) { height: 2px; background: #f4d000; }.result-tabs :deep(.el-tabs__item) { height: 34px; color: #606266; font-size: 13px; }.result-tabs :deep(.el-tabs__item.is-active) { color: #303133; font-weight: 600; }
@media (max-width: 1120px) {
  .run-controls { grid-template-columns: 1fr 1fr; }
  .control-actions { align-self: end; }
}
@media (max-width: 760px) {
  .model-run-page { padding: 14px; }
  .model-header { align-items: flex-start; flex-direction: column; }
  .run-controls { grid-template-columns: 1fr; }
  .stage-strip { overflow-x: auto; }
  .inline-notice { overflow-x: auto; text-overflow: clip; }.execution-panel summary { align-items: flex-start; }.execution-panel summary > span:first-of-type { flex-direction: column; gap: 2px; }.execution-count { display: none; }.execution-panel-content { padding: 10px; }.result-tabs :deep(.el-tabs__header) { overflow-x: auto; }
}
</style>
