<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { completionFactorUnit } from './eclipseCompletionFactor'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { storeToRefs } from 'pinia'
import { useSoftwareIntegrationStore } from '@/stores/softwareIntegration'
import PipesimNetworkResult from './PipesimNetworkResult.vue'
import PipesimNetworkOptimizerResult from './PipesimNetworkOptimizerResult.vue'
import PipesimSystemAnalysisResult from './PipesimSystemAnalysisResult.vue'
import PipesimNetworkWellPerformanceResult from './PipesimNetworkWellPerformanceResult.vue'
import PipesimNodalResult from './PipesimNodalResult.vue'
import PipesimProfileResult from './PipesimProfileResult.vue'
import PipesimWellComparisonSummary from './PipesimWellComparisonSummary.vue'
import PipesimSensitivityResult from './PipesimSensitivityResult.vue'
import PipesimGasLiftPerformanceResult from './PipesimGasLiftPerformanceResult.vue'
import PipesimGasLiftDiagnosticsResult from './PipesimGasLiftDiagnosticsResult.vue'
import PipesimVfpTablesResult from './PipesimVfpTablesResult.vue'
import PipesimEspCurvesResult from './PipesimEspCurvesResult.vue'
import PipesimEspParameterResult from './PipesimEspParameterResult.vue'
import PipesimWellTrajectoryResult from './PipesimWellTrajectoryResult.vue'
import PipesimRunHistory from './PipesimRunHistory.vue'
import EclipseRunResult from './EclipseRunResult.vue'
import EclipseCompositionalResult from './EclipseCompositionalResult.vue'
import EclipseThermalResult from './EclipseThermalResult.vue'
import EclipseDataInspectionOverview from './EclipseDataInspectionOverview.vue'
import PipesimPackageOverview from './PipesimPackageOverview.vue'
import { sourceReservoirPressure, supportsPressureScenario } from './wellParameterPreview'
import { buildPressureScenarioParameters } from './pressureScenarioParameters'
import { buildSystemAnalysisParameters, validateSystemAnalysisRun } from './systemAnalysisParameters'
import { buildEclipseWellEvents, validateEclipseWellEvents, buildEclipseMultiDateEvents, validateEclipseMultiDateEvents, eclipseSurfaceRateUnit, eclipseDatedControl } from './eclipseWellEvents'
import { productionTargetRecords, buildProductionTarget } from './eclipseProductionTarget'
import { pressureControlRecords, buildPressureControl } from './eclipsePressureControl'
import { productionControlRecords, buildProductionControl } from './eclipseProductionControl'
import { thpControlRecords, buildThpControl } from './eclipseThpControl'
import { newDatesWindows, buildNewDates } from './eclipseNewDates'
import { tstepRecords, buildTstep } from './eclipseTstep'
import { groupProductionRecords, buildGroupProduction } from './eclipseGroupProduction'
import { groupRateRecords, buildGroupRate } from './eclipseGroupRate'
import { groupWaterProductionRecords, buildGroupWaterProduction } from './eclipseGroupWaterProduction'
import { controlPlanPeriods, buildControlPlan } from './eclipseControlPlan'
import { waterInjectionPlanPeriods, buildWaterInjectionPlan } from './eclipseWaterInjectionPlan'
import { validRestartSource, buildRestartForecast } from './eclipseRestartForecast'
import { groupStructureRecords, groupStructureKey, groupStructureLabel, buildGroupStructure } from './eclipseGroupStructure'
import { advancedInjectionRecords, buildAdvancedInjection } from './eclipseAdvancedInjection'
import { groupInjectionRecords, buildGroupInjection } from './eclipseGroupInjection'
import { groupGasInjectionRecords, buildGroupGasInjection } from './eclipseGroupGasInjection'
import { buildRunTypeOptions } from './runTypeOptions'
import { gasLiftEligibility, gasLiftReason, gasLiftRunSnapshot } from './gasLiftTask'
import { useRunFeedback } from './useRunFeedback'
import { buildRunNotice } from './runNotice'

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
  hasNetworkCapability,
  isNetworkCapable,
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

const { notice: operationNotice, begin: beginFeedback } = useRunFeedback([
  activeProjectId, activeVersionId, selectedStudy, runType
])

const activeTab = ref('nodal')
const resultTabForRunType = runType => ({
  eclipse: 'eclipse',
  'network-well-performance-curves': 'network-well-performance-curves',
  'network-optimizer': 'network-optimizer',
  'system-analysis': 'system-analysis',
  network: 'network',
  profile: 'profile',
  sensitivity: 'sensitivity',
  'gas-lift-performance': 'gas-lift-performance',
  'gas-lift-diagnostics': 'gas-lift-diagnostics',
  'vfp-tables': 'vfp-tables',
  'esp-curves': 'esp-curves',
  'esp-parameter': 'esp-parameter',
  trajectory: 'trajectory'
}[runType] || 'nodal')
const isNetworkWorkflow = computed(() => isNetworkModel.value || hasNetworkCapability.value)
const networkRunTypes = new Set(['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'])
const isNetworkTask = computed(() => networkRunTypes.has(runType.value))
const networkCapabilityStudies = computed(() => hasNetworkCapability.value
  ? activeVersion.value?.inspection?.networkCapability?.studies || []
  : [])
const networkInspectionDocument = computed(() => isNetworkModel.value
  ? activeVersion.value?.inspection
  : activeVersion.value?.inspection?.networkCapability?.networkInspection)
const networkPerformanceProducers = computed(() => ['pipesim-network-inspection/4', 'pipesim-network-inspection/5'].includes(networkInspectionDocument.value?.schemaVersion)
  ? networkInspectionDocument.value.wellContexts || [] : [])
const networkPerformanceBranches = computed(() => ['pipesim-network-inspection/4', 'pipesim-network-inspection/5'].includes(networkInspectionDocument.value?.schemaVersion)
  ? networkInspectionDocument.value.flowlineContexts || [] : [])
const networkPerformanceContextAvailable = computed(() => networkPerformanceProducers.value.length > 0 && networkPerformanceBranches.value.length > 0)
const networkPerformanceProducer = ref('')
const networkPerformanceBranch = ref('')
const networkPerformanceValuesText = ref('4, 5, 6')
watch([activeVersionId, networkPerformanceProducers, networkPerformanceBranches], () => {
  networkPerformanceProducer.value = networkPerformanceProducers.value.length === 1
    ? networkPerformanceProducers.value[0]
    : networkPerformanceProducers.value.includes(networkPerformanceProducer.value) ? networkPerformanceProducer.value : ''
  networkPerformanceBranch.value = networkPerformanceBranches.value.length === 1
    ? networkPerformanceBranches.value[0]
    : networkPerformanceBranches.value.includes(networkPerformanceBranch.value) ? networkPerformanceBranch.value : ''
}, { immediate: true })
const profileOutletPressure = ref(100)
const statusMeta = {
  CREATED: ['已创建', 'info'],
  QUEUED: ['排队中', 'info'],
  CLAIMED: ['已领取', 'primary'],
  PREPARING: ['准备模型', 'primary'],
  RUNNING_NODAL: ['节点分析', 'primary'],
  RUNNING_PROFILE: ['PT 剖面', 'primary'],
  APPLYING_TRAJECTORY: ['保存轨迹方案', 'primary'],
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
  { value: 'esp-parameter', label: 'ESP 参数方案' },
  { value: 'trajectory', label: '井轨迹' }
]
const selectedWellContext = ref('')
const availableWellContexts = computed(() => {
  const inspection = activeVersion.value?.inspection
  if (['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(inspection?.schemaVersion) && Array.isArray(inspection.wellContexts)) return inspection.wellContexts
  if (['pipesim-well-inspection/3', 'pipesim-well-inspection/4'].includes(inspection?.schemaVersion) && typeof inspection.well === 'string') {
    return [{ context: inspection.well, espContexts: inspection.schemaVersion === 'pipesim-well-inspection/4' && Array.isArray(inspection.espContexts) ? inspection.espContexts : [], completionContexts: [], tubingContexts: [] }]
  }
  return []
})
const availableEspContexts = computed(() => availableWellContexts.value.find(item => item.context === selectedWellContext.value)?.espContexts || [])
const allEspContexts = computed(() => [...new Set(availableWellContexts.value.flatMap(item => item.espContexts || []))])
watch([activeVersionId, availableWellContexts], () => {
  const contexts = availableWellContexts.value.map(item => item.context)
  selectedWellContext.value = contexts.length === 1 ? contexts[0] : contexts.includes(selectedWellContext.value) ? selectedWellContext.value : ''
}, { immediate: true })
const runTypeOptions = computed(() => buildRunTypeOptions({
  wellRunTypeOptions,
  isNetworkModel: isNetworkModel.value,
  isWellModel: isWellModel.value,
  modelKind: activeVersion.value?.modelKind,
  inspectionSchemaVersion: activeVersion.value?.inspection?.schemaVersion,
  gasLiftInspection: activeVersion.value?.inspection,
  wellContexts: availableWellContexts.value,
  selectedWellContext: selectedWellContext.value,
  hasNetworkCapability: hasNetworkCapability.value,
  networkCapabilityStudies: activeVersion.value?.inspection?.networkCapability?.studies,
  selectedStudy: selectedStudy.value,
  networkPerformanceContextAvailable: networkPerformanceContextAvailable.value,
  wellAvailableTasks: capabilities.value?.pipesimWell?.status === 'AVAILABLE' ? capabilities.value.pipesimWell.runTasks : [],
  networkAvailableTasks: capabilities.value?.pipesimNetwork?.status === 'AVAILABLE' ? capabilities.value.pipesimNetwork.runTasks : []
}))
watch(runTypeOptions, options => {
  if (options.length && !options.some(option => option.value === runType.value)) runType.value = options[0].value
})
const isEclipseRunPresentation = computed(() => props.eclipsePresentation && ['eclipse_100', 'eclipse_300'].includes(activeVersion.value?.modelKind))
const isEclipse100RunPresentation = computed(() => isEclipseRunPresentation.value && activeVersion.value?.modelKind === 'eclipse_100')
const eclipsePresentationAvailable = computed(() => isEclipseRunPresentation.value &&
  activeVersion.value?.status === 'READY' && Boolean(activeVersion.value?.inspection))
const eclipseRunRequest = computed(() => ({ study: null, runType: 'eclipse', parameters: null }))
const pipesimPackageAvailable = computed(() => (isWellModel.value || isNetworkModel.value) &&
  activeVersion.value?.status === 'READY' &&
  ['pipesim-well-inspection/2', 'pipesim-well-inspection/3', 'pipesim-well-inspection/4', 'pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9', 'pipesim-network-inspection/2', 'pipesim-network-inspection/3'].includes(activeVersion.value?.inspection?.schemaVersion) &&
  Array.isArray(activeVersion.value?.inspection?.packageFiles))
const displayRun = computed(() => activeRun.value || selectedRun.value)
const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const validateWellRun = run => {
  const result = run?.result
  const tubingEdit = result?.schemaVersion === 'pipesim-well-result/2'
  if (!result || (!tubingEdit && result.schemaVersion !== 'pipesim-well-result/1')) return null
  if (tubingEdit && (!hasExactFields(result, ['schemaVersion', 'model_kind', 'runTask', 'resultContract', 'units', 'ipr', 'vlp', 'profile', 'application']) ||
    run?.runType !== 'profile' || run?.status !== 'SUCCEEDED' || result.resultContract !== 'VALID_FULL')) return null
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
  if (tubingEdit) {
    const application = result.application
    const parameters = run?.parameters
    if (!application || !hasExactFields(application, ['applied', 'isolatedCopy', 'sourceModelUnchanged', 'artifactName', 'wellContext', 'tubingContext', 'unit', 'originalInnerDiameter', 'targetInnerDiameter', 'appliedInnerDiameter']) ||
      application.applied !== true || application.isolatedCopy !== true || application.sourceModelUnchanged !== true ||
      application.artifactName !== 'pipesim-well-tubing-applied.pips' || !isSafeTopologyText(application.wellContext) ||
      !isSafeTopologyText(application.tubingContext) || typeof application.unit !== 'string' || !application.unit.trim() || application.unit.length > 32 ||
      !parameters || !hasExactFields(parameters, ['schemaVersion', 'baselineRunId', 'wellContext', 'tubingContext', 'originalInnerDiameter', 'unit', 'targetInnerDiameter']) ||
      parameters.schemaVersion !== 'pipesim-well-tubing-diameter-parameters/1' || !Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 ||
      !isSafeTopologyText(parameters.wellContext) || !isSafeTopologyText(parameters.tubingContext) || typeof parameters.unit !== 'string' || !parameters.unit.trim() || parameters.unit.length > 32 ||
      parameters.wellContext !== application.wellContext || parameters.tubingContext !== application.tubingContext || parameters.unit !== application.unit ||
      !['originalInnerDiameter', 'targetInnerDiameter'].every(field => isFiniteNumber(parameters[field]) && parameters[field] > 0 && parameters[field] <= 1000) ||
      !['originalInnerDiameter', 'targetInnerDiameter', 'appliedInnerDiameter'].every(field => isFiniteNumber(application[field]) && application[field] > 0 && application[field] <= 1000)) return null
    const tolerance = value => Math.max(1e-9, Math.abs(value) * 1e-9)
    if (Math.abs(application.originalInnerDiameter - parameters.originalInnerDiameter) > tolerance(application.originalInnerDiameter) ||
      Math.abs(application.targetInnerDiameter - parameters.targetInnerDiameter) > tolerance(application.targetInnerDiameter) ||
      Math.abs(application.appliedInnerDiameter - application.targetInnerDiameter) > tolerance(application.targetInnerDiameter) ||
      Math.abs(application.originalInnerDiameter - application.targetInnerDiameter) <= Math.max(1e-12, Math.abs(application.originalInnerDiameter) * 1e-12)) return null
  }
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
  const rowKey = row => [row.thpIndex, row.waterCutIndex, row.gorIndex, row.artificialLiftIndex].join(':')
  const validTable = (table, valueName, unit, axes, allowEmpty = false) => {
    if (!table || !hasExactFields(table, ['valueName', 'unit', 'rows']) || table.valueName !== valueName || table.unit !== unit || !Array.isArray(table.rows)) return false
    if (allowEmpty && table.rows.length === 0) return true
    const expectedRows = axes.outletPressuresPsi.length * axes.waterCutFraction.length * axes.gorMscfPerStb.length * axes.artificialLiftInjectionDpPsi.length
    if (table.rows.length !== expectedRows) return false
    const keys = new Set()
    for (const row of table.rows) {
      if (!hasExactFields(row, ['thpIndex', 'waterCutIndex', 'gorIndex', 'artificialLiftIndex', 'values']) ||
        !Number.isInteger(row.thpIndex) || row.thpIndex < 1 || row.thpIndex > axes.outletPressuresPsi.length ||
        !Number.isInteger(row.waterCutIndex) || row.waterCutIndex < 1 || row.waterCutIndex > axes.waterCutFraction.length ||
        !Number.isInteger(row.gorIndex) || row.gorIndex < 1 || row.gorIndex > axes.gorMscfPerStb.length ||
        !Number.isInteger(row.artificialLiftIndex) || row.artificialLiftIndex < 1 || row.artificialLiftIndex > axes.artificialLiftInjectionDpPsi.length ||
        !Array.isArray(row.values) || row.values.length !== axes.liquidRatesStbPerDay.length || !row.values.every(isFiniteNumber)) return false
      const key = rowKey(row)
      if (keys.has(key)) return false
      keys.add(key)
    }
    return true
  }
  const parameters = run?.parameters
  if (!result || run?.runType !== 'vfp-tables' || run?.status !== 'SUCCEEDED' || result.schemaVersion !== 'pipesim-vfp-tables-result/2' || result.model_kind !== 'black_oil_liquid' || result.runTask !== 'vfp-tables' || result.resultContract !== 'VALID_FULL' || result.resultContract !== run.resultContract || !isSafeTopologyText(result.producer) || result.reservoirSimulator !== 'ECLIPSE' || !Number.isInteger(result.tableNumber) || result.tableNumber <= 0 || typeof result.includeTemperature !== 'boolean' || !isFiniteNumber(result.bottomHoleDatumDepth) || !result.axes || !increasing(result.axes.liquidRatesStbPerDay) || !increasing(result.axes.outletPressuresPsi) || !increasing(result.axes.waterCutFraction) || !increasing(result.axes.gorMscfPerStb) || !increasing(result.axes.artificialLiftInjectionDpPsi) ||
    parameters?.producer !== result.producer || parameters?.tableNumber !== result.tableNumber || parameters?.includeTemperature !== result.includeTemperature || parameters?.bottomHoleDatumDepth !== result.bottomHoleDatumDepth ||
    !validTable(result.table, 'BHP', 'psia', result.axes) || !validTable(result.temperatureTable, 'TEMP', 'F', result.axes, !result.includeTemperature) ||
    result.includeTemperature && result.table.rows.map(rowKey).some(key => !result.temperatureTable.rows.some(row => rowKey(row) === key)) ||
    typeof result.vfpTableContent !== 'string' || typeof result.vfpTableWithTemperatureContent !== 'string') return null
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
  const validPump = pump => isSafeTopologyText(pump?.pumpName) && pump.inputs && finite(pump.inputs.frequency) && typeof pump.inputs.frequencyUnit === 'string' &&
    typeof pump.inputs.manufacturer === 'string' && typeof pump.inputs.model === 'string' && finite(pump.inputs.minFlowRate) &&
    finite(pump.inputs.maxFlowRate) && pump.inputs.maxFlowRate > pump.inputs.minFlowRate && finite(pump.inputs.stages) &&
    Array.isArray(pump.frequencies) && pump.frequencies.length > 0 && pump.frequencies.every(item => finite(item.frequencyHz) && typeof item.frequencyLabel === 'string' && validCurve(item)) &&
    pump.operatingEnvelope && ['qMin', 'bep', 'qMax'].every(key => validCurve(pump.operatingEnvelope[key]))
  if (!result || run?.runType !== 'esp-curves' || run?.status !== 'SUCCEEDED' || !['pipesim-esp-curves-result/1', 'pipesim-esp-curves-result/2'].includes(result.schemaVersion) ||
     !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(result.model_kind) || result.runTask !== 'esp-curves' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || typeof result.producer !== 'string' || !validPump(result.pump) || !validPump(result.nodalPump)) return null
  if (result.schemaVersion === 'pipesim-esp-curves-result/2' && (!isSafeTopologyText(result.espContext) || result.espContext !== run.parameters?.espContext)) return null
  return result
}
const validEspCurvesResult = computed(() => isWellModel.value ? validateEspCurvesRun(selectedRun.value) : null)
const validateEspParameterRun = run => {
  const result = run?.result
  const finite = value => typeof value === 'number' && Number.isFinite(value)
  const curvePoint = point => hasExactFields(point, ['flow', 'pressure']) && finite(point.flow) && finite(point.pressure)
  const profilePoint = point => hasExactFields(point, ['depth', 'pressure', 'temperature']) && finite(point.depth) && point.depth >= 0 && finite(point.pressure) && finite(point.temperature)
  const frequencyV1 = result?.schemaVersion === 'pipesim-esp-parameter-result/1' && result.parameter === 'operatingFrequency'
  const headFactorV2 = result?.schemaVersion === 'pipesim-esp-parameter-result/2' && result.parameter === 'headFactor'
  const parameterField = frequencyV1 ? 'frequencyHz' : 'parameterValue'
  const parameterMax = headFactorV2 ? 2 : 200
  const validCase = (item, allowAbovePlatformGuard = false) => hasExactFields(item, [parameterField, 'ipr', 'vlp', 'profile']) && finite(item[parameterField]) && item[parameterField] > 0 && (allowAbovePlatformGuard || item[parameterField] <= parameterMax) &&
    Array.isArray(item.ipr) && item.ipr.length > 0 && item.ipr.length <= 4096 && item.ipr.every(curvePoint) &&
    Array.isArray(item.vlp) && item.vlp.length > 0 && item.vlp.length <= 4096 && item.vlp.every(curvePoint) &&
    Array.isArray(item.profile) && item.profile.length > 0 && item.profile.length <= 4096 && item.profile.every(profilePoint)
  if (!result || run?.runType !== 'esp-parameter' || run?.status !== 'SUCCEEDED' || !(frequencyV1 || headFactorV2) ||
    !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(result.model_kind) || result.runTask !== 'esp-parameter' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || !isSafeTopologyText(result.producer) || !isSafeTopologyText(result.espContext) || result.unit !== (frequencyV1 ? 'Hz' : '1') ||
    !finite(result.originalValue) || result.originalValue <= 0 || !finite(result.targetValue) || result.targetValue <= 0 || result.targetValue > parameterMax ||
    !finite(result.appliedValue) || result.appliedValue <= 0 || result.appliedValue > parameterMax || result.sourceModelUnchanged !== true || result.isolatedCopy !== true ||
    Math.abs(result.targetValue - result.appliedValue) > 1e-6 || Math.abs(result.originalValue - result.targetValue) <= 1e-6 || !validCase(result.baseline, headFactorV2) || !validCase(result.target) ||
    !hasExactFields(result.comparison, ['maxIprPressureDelta', 'maxVlpPressureDelta', 'maxProfilePressureDelta', 'maxProfileTemperatureDelta']) ||
    !Object.values(result.comparison).every(value => value === null || finite(value) && value >= 0 && value <= 100000000) ||
    Math.abs(result.baseline[parameterField] - result.originalValue) > 1e-6 || Math.abs(result.target[parameterField] - result.appliedValue) > 1e-6) return null
  return result
}
const validEspParameterResult = computed(() => isWellModel.value ? validateEspParameterRun(selectedRun.value) : null)
const validateTrajectoryRun = run => {
  const result = run?.result
  const finite = value => typeof value === 'number' && Number.isFinite(value)
  const nullable = value => value === null || finite(value)
  const pointValid = point => point && hasExactFields(point, ['measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity']) &&
    finite(point.measuredDepth) && point.measuredDepth >= 0 && finite(point.trueVerticalDepth) && point.trueVerticalDepth >= 0 &&
    finite(point.inclination) && point.inclination >= 0 && point.inclination <= 180 && nullable(point.azimuth) && nullable(point.maxDogLegSeverity)
  const edited = result?.schemaVersion === 'pipesim-well-trajectory-result/2'
  if (!result || run?.runType !== 'trajectory' || run?.status !== 'SUCCEEDED' || !['pipesim-well-trajectory-result/1', 'pipesim-well-trajectory-result/2'].includes(result.schemaVersion) ||
     !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(result.model_kind) || result.runTask !== 'trajectory' || result.resultContract !== 'VALID_FULL' ||
    result.resultContract !== run.resultContract || typeof result.producer !== 'string' || !result.producer.trim() ||
    !result.units || !hasExactFields(result.units, ['measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity']) ||
    !Object.values(result.units).every(value => typeof value === 'string' && value.length > 0) || !Array.isArray(result.points) || result.points.length < 2 ||
    result.points.length > 4096 || !result.points.every(pointValid)) return null
  for (let index = 1; index < result.points.length; index += 1) if (result.points[index].measuredDepth <= result.points[index - 1].measuredDepth) return null
  if (edited) {
    const application = result.application
    const comparison = result.profileComparison
    const profilePoint = point => point && hasExactFields(point, ['depth', 'pressure', 'temperature']) && ['depth', 'pressure', 'temperature'].every(field => finite(point[field]))
    const profileArray = values => Array.isArray(values) && values.length > 0 && values.length <= 4096 && values.every(profilePoint)
    const profileMap = values => {
      const groups = new Map()
      for (const point of values) {
        const key = Math.round(point.depth * 1e6)
        const group = groups.get(key) || []
        group.push(point)
        groups.set(key, group)
      }
      return new Map([...groups].filter(([, group]) => group.length === 1).map(([key, group]) => [key, group[0]]))
    }
    if (!application || !hasExactFields(application, ['applied', 'isolatedCopy', 'sourceModelUnchanged', 'artifactName', 'stationIndex', 'originalInclinationDeg', 'targetInclinationDeg', 'maxTvdBeforeFt', 'maxTvdAfterFt']) ||
      application.applied !== true || application.isolatedCopy !== true || application.sourceModelUnchanged !== true || application.artifactName !== 'pipesim-trajectory-applied.pips' ||
      !Number.isInteger(application.stationIndex) || application.stationIndex < 1 || application.stationIndex > 4094 || application.stationIndex >= result.points.length - 1 ||
      !finite(application.originalInclinationDeg) || application.originalInclinationDeg < 0 || application.originalInclinationDeg > 90 ||
      !finite(application.targetInclinationDeg) || application.targetInclinationDeg < 0 || application.targetInclinationDeg > 90 ||
      Math.abs(application.originalInclinationDeg - application.targetInclinationDeg) <= 1e-9 || !finite(application.maxTvdBeforeFt) || !finite(application.maxTvdAfterFt) ||
      Math.abs(result.points[application.stationIndex].inclination - application.targetInclinationDeg) > 1e-6 ||
      Math.abs(result.points.at(-1).trueVerticalDepth - application.maxTvdAfterFt) > 1e-6 ||
      !comparison || !hasExactFields(comparison, ['method', 'baselinePointCount', 'targetPointCount', 'matchedPointCount', 'baseline', 'target', 'matchedPoints', 'maxAbsPressureDelta', 'maxAbsTemperatureDelta']) ||
      comparison.method !== 'exact-depth-match-no-interpolation' || !profileArray(comparison.baseline) || !profileArray(comparison.target) ||
      comparison.baselinePointCount !== comparison.baseline.length || comparison.targetPointCount !== comparison.target.length ||
      !Array.isArray(comparison.matchedPoints) || comparison.matchedPointCount !== comparison.matchedPoints.length ||
      comparison.matchedPoints.some(point => !point || !hasExactFields(point, ['depth', 'baselinePressure', 'targetPressure', 'pressureDelta', 'baselineTemperature', 'targetTemperature', 'temperatureDelta']) ||
        ['depth', 'baselinePressure', 'targetPressure', 'pressureDelta', 'baselineTemperature', 'targetTemperature', 'temperatureDelta'].some(field => !finite(point[field])))) return null
    const baselineMap = profileMap(comparison.baseline)
    const targetMap = profileMap(comparison.target)
    const commonDepths = [...baselineMap.keys()].filter(depth => targetMap.has(depth)).sort((first, second) => first - second)
    if (commonDepths.length !== comparison.matchedPoints.length || comparison.matchedPoints.some((point, index) => {
      const depthKey = commonDepths[index]
      const baselinePoint = baselineMap.get(depthKey)
      const targetPoint = targetMap.get(depthKey)
      return !baselinePoint || !targetPoint || Math.abs(point.depth - depthKey / 1e6) > 1e-6 ||
        Math.abs(point.baselinePressure - baselinePoint.pressure) > 1e-6 || Math.abs(point.targetPressure - targetPoint.pressure) > 1e-6 ||
        Math.abs(point.baselineTemperature - baselinePoint.temperature) > 1e-6 || Math.abs(point.targetTemperature - targetPoint.temperature) > 1e-6 ||
        Math.abs(point.pressureDelta - (targetPoint.pressure - baselinePoint.pressure)) > 1e-6 ||
        Math.abs(point.temperatureDelta - (targetPoint.temperature - baselinePoint.temperature)) > 1e-6
    })) return null
    const maxPressure = comparison.matchedPoints.length ? Math.max(...comparison.matchedPoints.map(point => Math.abs(point.pressureDelta))) : null
    const maxTemperature = comparison.matchedPoints.length ? Math.max(...comparison.matchedPoints.map(point => Math.abs(point.temperatureDelta))) : null
    if ((maxPressure === null ? comparison.maxAbsPressureDelta !== null : !finite(comparison.maxAbsPressureDelta) || Math.abs(maxPressure - comparison.maxAbsPressureDelta) > 1e-6) ||
      (maxTemperature === null ? comparison.maxAbsTemperatureDelta !== null : !finite(comparison.maxAbsTemperatureDelta) || Math.abs(maxTemperature - comparison.maxAbsTemperatureDelta) > 1e-6)) return null
  }
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
const runLabel = run => run ? `运行 #${run.id} · v${run.versionNo || activeVersion.value?.versionNo} · ${run.study || '无 Study'}${run.parameters?.schemaVersion === 'pipesim-well-parameters/1' && Number.isFinite(run.parameters.reservoirPressurePsi) ? ` · 地层压力 ${run.parameters.reservoirPressurePsi} psia` : run.parameters?.schemaVersion === 'pipesim-esp-parameter-parameters/1' ? ` · ESP ${run.parameters.targetValueHz} Hz` : ['pipesim-esp-parameter-parameters/2', 'pipesim-esp-parameter-parameters/3'].includes(run.parameters?.schemaVersion) && run.parameters?.parameter === 'headFactor' ? ` · ESP Head Factor ${run.parameters.targetValue}` : run.parameters?.schemaVersion === 'pipesim-esp-parameter-parameters/3' && run.parameters?.parameter === 'operatingFrequency' ? ` · ESP ${run.parameters.targetValue} Hz` : ''}` : ''
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
  const run = selectedRun.value
  const baselineRunId = run?.parameters?.schemaVersion === 'pipesim-well-tubing-diameter-parameters/1'
    ? Number(run.parameters.baselineRunId) : null
  if (Number.isSafeInteger(baselineRunId) && comparisonOptions.value.some(item => Number(item.id) === baselineRunId)) {
    comparisonId.value = baselineRunId
  }
})
onBeforeUnmount(() => { comparisonGeneration++ })
const isNetworkVariable = entry => entry && isSafeTopologyText(entry.variable) &&
  (entry.unit === null || isSafeTopologyText(entry.unit, true)) && Array.isArray(entry.values)
const isNumericValue = value => value === null || isFiniteNumber(value) ||
  (Array.isArray(value) && value.length > 0 && value.every(isNumericValue)) ||
  (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0 &&
    Object.values(value).every(isNumericValue))
const isNetworkNativeScalar = value => value === null || isFiniteNumber(value) || typeof value === 'boolean' ||
  (typeof value === 'string' && isSafeTopologyText(value) && !Number.isFinite(Number(value.trim()))) ||
  (Array.isArray(value) && value.length > 0 && value.every(isNetworkNativeScalar)) ||
  (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0 &&
    Object.entries(value).every(([key, item]) => isSafeTopologyText(key) && isNetworkNativeScalar(item)))
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
    if (!isNetworkNativeScalar(value.value)) return null
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
  if (!isNetworkWorkflow.value) return null
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
const validSystemAnalysisResult = computed(() => isNetworkWorkflow.value
  ? validateSystemAnalysisRun(selectedRun.value, activeVersionId.value) : null)
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
const validNetworkOptimizerResult = computed(() => isNetworkWorkflow.value ? validateNetworkOptimizerRun(selectedRun.value) : null)
const validateNetworkWellPerformanceRun = run => {
  const result = run?.result
  const parameters = run?.parameters
  if (!result || run?.runType !== 'network-well-performance-curves' || run.status !== 'SUCCEEDED' ||
    !hasExactFields(parameters, ['schemaVersion', 'producerContext', 'branchTerminator', 'sensitivityUnit', 'sensitivityValues']) ||
    parameters.schemaVersion !== 'pipesim-network-well-performance-parameters/1' || parameters.sensitivityUnit !== 'in' ||
    result.schemaVersion !== 'pipesim-network-well-performance-result/1' || result.model_kind !== 'network' ||
    result.runTask !== 'network-well-performance-curves' || result.resultContract !== 'VALID_FULL' ||
    result.study !== run.study || result.simulationState !== 'Completed' ||
    result.producerContext !== parameters.producerContext || result.branchTerminator !== parameters.branchTerminator ||
    !isSafeTopologyText(result.producerContext) || !isSafeTopologyText(result.branchTerminator) || result.flowratePoints !== 50 ||
    !result.sensitivity || result.sensitivity.variable !== 'InnerDiameter' || result.sensitivity.unit !== 'in' ||
    !Array.isArray(result.sensitivity.values) || result.sensitivity.values.length < 2 || result.sensitivity.values.length > 8 ||
    !Array.isArray(parameters.sensitivityValues) || parameters.sensitivityValues.length !== result.sensitivity.values.length ||
    parameters.sensitivityValues.some((value, index) => value !== result.sensitivity.values[index]) ||
    !Array.isArray(result.system) || result.system.length === 0 || !Array.isArray(result.cases) ||
    result.cases.length !== result.sensitivity.values.length || !hasExactFields(result.summary, ['info', 'warnings', 'errors']) ||
    !['info', 'warnings', 'errors'].every(key => Array.isArray(result.summary[key])) || !Array.isArray(result.messages) ||
    !Array.isArray(result.quality) || result.pwiArtifact?.artifactName !== 'pipesim-network-well-performance.pwi' ||
    Object.hasOwn(result.pwiArtifact, 'content')) return null
  let previous = 0
  if (result.sensitivity.values.some(value => {
    if (!isFiniteNumber(value) || value <= previous || value > 1000) return true
    previous = value
    return false
  })) return null
  const validSeries = series => Array.isArray(series) && series.length > 0 && series.every(item =>
    item && isSafeTopologyText(item.variable) && typeof item.unit === 'string' && item.unit.length <= 128 && Array.isArray(item.values) &&
    item.values.length > 0 && item.values.every(value => value && isSafeTopologyText(value.name) &&
      (value.value === null || typeof value.value === 'string' || typeof value.value === 'boolean' || isFiniteNumber(value.value))))
  if (!validSeries(result.system) || result.cases.some((item, index) =>
    !item || !isSafeTopologyText(item.caseId) || item.caseIndex !== index ||
    item.sensitivityValueIn !== result.sensitivity.values[index] || !validSeries(item.node))) return null
  const pwiArtifact = run.artifacts?.find(artifact => artifact?.name === result.pwiArtifact.artifactName)
  if (!pwiArtifact || !Number.isSafeInteger(pwiArtifact.id) || pwiArtifact.id <= 0) return null
  return result
}
const validNetworkWellPerformanceResult = computed(() => isNetworkWorkflow.value
  ? validateNetworkWellPerformanceRun(selectedRun.value) : null)
const networkWellPerformanceArtifact = computed(() => {
  const artifact = selectedRun.value?.artifacts?.find(item => item?.name === 'pipesim-network-well-performance.pwi')
  if (!Number.isSafeInteger(artifact?.id) || artifact.id <= 0) return null
  return { ...artifact, contentType: typeof artifact.contentType === 'string' ? artifact.contentType : 'text/plain', downloadable: true }
})
const isPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validWellResult.value?.resultContract === 'VALID_PARTIAL')
const isNetworkPartial = computed(() => selectedRun.value?.status === 'PARTIAL_SUCCEEDED' &&
  validNetworkResult.value?.resultContract === 'VALID_PARTIAL')
const networkTopologyUnavailable = computed(() => isNetworkWorkflow.value &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(selectedRun.value?.status) &&
  ['VALID_FULL', 'VALID_PARTIAL'].includes(selectedRun.value?.result?.resultContract) &&
  !validNetworkResult.value)
const canRun = computed(() => !previewPending.value && activeVersion.value?.status === 'READY' &&
  (isNetworkWorkflow.value || isWellModel.value || isEclipseModel.value) &&
  (isEclipseModel.value ? eclipsePresentationAvailable.value : persistedStudies.value.includes(selectedStudy.value)) &&
  (isEclipseModel.value || runTypeOptions.value.some(option => option.value === runType.value)) &&
  (!['gas-lift-performance', 'gas-lift-diagnostics'].includes(runType.value) || gasLiftCapability.value.supported) &&
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
  if (isEclipseModel.value) return activeVersion.value?.modelKind === 'eclipse_300' ? (['eclipse-data-inspection/15', 'eclipse-data-inspection/23'].includes(activeVersion.value?.inspection?.schemaVersion) ? 'ECLIPSE 300 热采模型' : 'ECLIPSE 300 组分模型') : 'ECLIPSE 100 模型'
  if (activeVersion.value?.modelKind === 'legacy_well') return 'PIPESIM 通用井模型'
  return isWellModel.value ? 'PIPESIM 井筒模型' : '待识别 PIPESIM 模型'
})
const stages = computed(() => {
  const type = displayRun.value?.runType || runType.value
  const values = ['PREPARING']
  if (type === 'network' || type === 'system-analysis' || type === 'network-optimizer' || type === 'network-well-performance-curves') values.push('RUNNING_NETWORK')
  else if (type === 'eclipse') values.push('RUNNING_ECLIPSE')
  else {
  if (type === 'esp-curves' || type === 'esp-parameter') values.push('RUNNING_PROFILE', 'RUNNING_NODAL')
    else if (type === 'nodal' || type === 'combined' || type === 'sensitivity' || type === 'gas-lift-performance' || type === 'gas-lift-diagnostics' || type === 'vfp-tables') values.push('RUNNING_NODAL')
    if (type === 'trajectory' && displayRun.value?.parameters?.schemaVersion === 'pipesim-well-trajectory-parameters/3') values.push('RUNNING_PROFILE', 'APPLYING_TRAJECTORY')
    else if (type === 'trajectory') values.push('READING_TRAJECTORY')
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
  ['nodal', 'profile', 'combined', 'sensitivity', 'gas-lift-performance', 'gas-lift-diagnostics', 'vfp-tables', 'esp-curves', 'esp-parameter', 'trajectory', 'network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves', 'eclipse'].includes(displayRun.value.runType))
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
const safeDiagnosticText = value => typeof value === 'string' && value.length <= 500 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value) ? value : ''
const safeRunError = computed(() => selectedError.value ? {
  category: safeCode(selectedError.value.category) || 'EXECUTION',
  code: safeCode(selectedError.value.code) || 'RUN_NOT_ACCEPTED',
  retryable: selectedError.value.retryable === true,
  diagnostics: Array.isArray(selectedError.value.diagnostics) ? selectedError.value.diagnostics.slice(0, 40).flatMap(item => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return []
    const diagnostic = { path: safeDiagnosticText(item.path), property: safeDiagnosticText(item.property), message: safeDiagnosticText(item.message) }
    return diagnostic.message ? [diagnostic] : []
  }) : []
} : null)
const terminalRunGuidance = computed(() => {
  if (!displayRun.value || !['FAILED', 'CANCELLED', 'TIMED_OUT', 'WORKER_LOST'].includes(displayRun.value.status)) return ''
  if (displayRun.value.status === 'CANCELLED') return '任务已取消。确认 Study 后可重新提交运行。'
  if (displayRun.value.status === 'TIMED_OUT') return '运行已超时。请确认模型与运行环境后重新提交。'
  if (displayRun.value.status === 'WORKER_LOST') return 'Worker 状态已丢失。请确认 Worker 可用后重新提交。'
  if (gasLiftReason(safeRunError.value?.code)) return gasLiftReason(safeRunError.value.code)
  if (safeRunError.value?.code === 'PIPESIM_TASK_VALIDATION_FAILED') return '官方任务预检发现问题，求解器尚未启动。请先处理下方井/字段诊断，再重新运行。'
  if (['PIPESIM_TASK_VALIDATION_UNAVAILABLE', 'PIPESIM_TASK_VALIDATION_EMPTY'].includes(safeRunError.value?.code)) return '官方任务预检未能完成，求解器尚未启动。请重新验证模型与 Study，并检查 PIPESIM Toolkit 环境。'
  return safeRunError.value?.retryable ? '请确认运行环境后重新提交该版本。' : '请检查模型版本和 Study，必要时重新验证后再运行。'
})
const networkContractRejected = computed(() => isNetworkWorkflow.value && !validNetworkResult.value &&
  !networkTopologyUnavailable.value &&
  (Boolean(selectedRun.value?.result) || ['INVALID_NETWORK_RESULT_CONTRACT', 'RESULT_CONTRACT_INVALID'].includes(safeCode(selectedError.value?.code))) &&
  ['SUCCEEDED', 'PARTIAL_SUCCEEDED', 'FAILED'].includes(selectedRun.value?.status))
const networkWellPerformanceContractRejected = computed(() => isNetworkWorkflow.value &&
  selectedRun.value?.runType === 'network-well-performance-curves' && selectedRun.value?.status === 'SUCCEEDED' &&
  Boolean(selectedRun.value?.result) && !validNetworkWellPerformanceResult.value)
const historicalSuccessfulNetworkRun = computed(() => runHistory.value.find(run => run.id !== selectedRun.value?.id &&
  run.runType === 'network' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'))
const primaryNotice = computed(() => buildRunNotice({
  operationNotice: operationNotice.value,
  pollingUnavailable: runPollingUnavailable.value,
  terminalGuidance: terminalRunGuidance.value,
  resultExpired: displayRun.value?.resultExpired,
  networkTopologyUnavailable: networkTopologyUnavailable.value,
  networkContractRejected: networkContractRejected.value,
  networkWellPerformanceContractRejected: networkWellPerformanceContractRejected.value,
  isPartial: isPartial.value,
  isNetworkPartial: isNetworkPartial.value
}))

const formatElapsed = value => {
  const total = Math.max(0, Math.floor(Number(value || 0) / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${minutes}:${String(seconds).padStart(2, '0')}`
}
const errorMessage = (error, fallback = '请求失败，请稍后重试') => {
  const candidate = typeof error?.msg === 'string'
    ? error.msg
    : (!error?.isAxiosError && !error?.config && !error?.request && typeof error?.message === 'string'
        ? error.message
        : '')
  const message = candidate.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 180)
  return message || fallback
}
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
  const selection = store.selectVersion(versionId)
  const feedback = beginFeedback()
  try { await selection } catch (error) {
    feedback.error(isEclipseVersion(versionId)
      ? eclipseRequestErrorMessage(error, '切换 ECLIPSE 模型版本失败，请稍后重试')
      : errorMessage(error))
  }
}
const scenarioEnabled = ref(false)
const scenarioPressure = ref(null)
const supportsCurrentScenario = computed(() => supportsPressureScenario(activeVersion.value?.modelKind, runType.value))
const pressureFieldTouched = ref(false)
const pressureScenarioDraft = computed(() => buildPressureScenarioParameters({
  enabled: scenarioEnabled.value,
  modelKind: activeVersion.value?.modelKind,
  runType: runType.value,
  pressure: scenarioPressure.value
}))
const pressureFieldError = computed(() => pressureFieldTouched.value ? pressureScenarioDraft.value.fieldError : null)
watch(scenarioEnabled, () => { pressureFieldTouched.value = false }, { flush: 'sync' })
const eclipseScheduleEnabled = ref(false)
const eclipseMultiScheduleEnabled = ref(false)
const eclipseWconHistEnabled = ref(false)
const eclipseWconInjeEnabled = ref(false)
const eclipseWconProdEnabled = ref(false)
const eclipsePressureEnabled = ref(false)
const eclipsePressureWell = ref('')
const eclipsePressureTarget = ref(null)
const eclipseThpEnabled = ref(false)
const eclipseNewDatesEnabled = ref(false)
const eclipseTstepEnabled = ref(false)
const eclipseControlPlanEnabled = ref(false)
const eclipseControlPlanDraft = ref([])
const eclipseWaterInjectionPlanEnabled = ref(false)
const eclipseWaterInjectionPlanDraft = ref([])
const eclipseGroupStructureEnabled = ref(false)
const eclipseGroupStructureControl = ref('')
const eclipseGroupStructureTarget = ref(null)
const eclipseAdvancedInjectionEnabled = ref(false)
const eclipseAdvancedInjectionGroup = ref('')
const eclipseAdvancedInjectionGroupTarget = ref(null)
const eclipseGroupWaterProductionEnabled = ref(false)
const eclipseWaterProductionGroup = ref('')
const eclipseWaterProductionGroupTarget = ref(null)
const eclipseGroupRateEnabled = ref(false)
const eclipseRateGroup = ref('')
const eclipseRateGroupTarget = ref(null)
const eclipseGroupProductionEnabled = ref(false)
const eclipseGroupInjectionEnabled = ref(false)
const eclipseInjectionGroup = ref('')
const eclipseInjectionGroupTarget = ref(null)
const eclipseGroupGasInjectionEnabled = ref(false)
const eclipseGasInjectionGroup = ref('')
const eclipseGasInjectionGroupTarget = ref(null)
const eclipseProductionGroup = ref('')
const eclipseGroupTarget = ref(null)
const eclipseTstepLine = ref(null)
const eclipseTstepText = ref('')
const eclipseNewDatesWindowKey = ref(null)
const eclipseNewDatesWell = ref('')
const eclipseNewShutDate = ref('')
const eclipseNewOpenDate = ref('')
const eclipseThpWell = ref('')
const eclipseThpTarget = ref(null)
const eclipseRateControlEnabled = ref(false)
const eclipseRateControlWell = ref('')
const eclipseRateControlTarget = ref(null)
const eclipseCompletionEnabled = ref(false)
const eclipseCompletionFactorEnabled = ref(false)
const eclipseForecastEnabled = ref(false)
const eclipseScheduleWell = ref('')
const eclipseScheduleDate = ref('')
const eclipseMultiScheduleSelectedWells = ref([])
const eclipseMultiScheduleShutDate = ref('')
const eclipseMultiScheduleOpenDate = ref('')
const eclipseMultiDateMode = ref(false)
const eclipseMultiDateEvents = ref([])
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
const eclipseForecastRestartReport = ref(null)
const eclipseForecastSources = ref({})
const eclipseForecastCatalogLoading = ref(false)
const eclipseForecastCatalogError = ref('')
let eclipseForecastHistoryGeneration = 0
onBeforeUnmount(() => { eclipseForecastHistoryGeneration++ })
const eclipseHistoryRunDetails = ref({})
const eclipseScheduleMetadata = computed(() => ['eclipse-data-inspection/4', 'eclipse-data-inspection/5', 'eclipse-data-inspection/6', 'eclipse-data-inspection/7', 'eclipse-data-inspection/8', 'eclipse-data-inspection/9', 'eclipse-data-inspection/10', 'eclipse-data-inspection/11', 'eclipse-data-inspection/12', 'eclipse-data-inspection/13'].includes(activeVersion.value?.inspection?.schemaVersion)
  ? activeVersion.value.inspection.scheduleMetadata : null)
const eclipseScheduleWells = computed(() => Array.isArray(eclipseScheduleMetadata.value?.wells)
  ? eclipseScheduleMetadata.value.wells.map(item => item?.name).filter(Boolean) : [])
const eclipseMultiScheduleWells = computed(() => [...new Set((eclipseScheduleMetadata.value?.records || [])
  .filter(record => ['WCONHIST', 'WCONPROD'].includes(record?.keyword) && Array.isArray(record.values) && typeof record.values[0] === 'string')
  .map(record => record.values[0]).filter(well => eclipseScheduleWells.value.includes(well)))])
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
const eclipseMultiScheduleDates = computed(() => eclipseScheduleDates.value.filter(date => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return false
  const parsed = new Date(`${date}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date
}).sort())
const eclipseMultiScheduleOpenDates = computed(() => eclipseMultiScheduleDates.value.filter(date => date > eclipseMultiScheduleShutDate.value))
const addEclipseDateEvent = () => {
  const date = eclipseMultiScheduleDates.value.find(d => d > (eclipseMultiDateEvents.value.at(-1)?.date || ''))
  if (date && eclipseMultiDateEvents.value.length < 24) eclipseMultiDateEvents.value.push({ date, status: 'SHUT' })
}
const toggleEclipseMultiDateMode = value => {
  if (value) eclipseMultiDateEvents.value = [
    { date: eclipseMultiScheduleShutDate.value, status: 'SHUT' }, { date: eclipseMultiScheduleOpenDate.value, status: 'OPEN' }
  ]
}
const eclipseBaselineOptions = computed(() => runHistory.value.filter(run =>
  run.modelVersionId === activeVersionId.value && run.runType === 'eclipse' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' &&
  (run.parameters === null || run.parameters === undefined)))
const eclipseBaselineRunId = ref(null)
const eclipseScheduleAvailable = computed(() => isEclipseModel.value && eclipseScheduleWells.value.length > 0 &&
  eclipseScheduleDates.value.length > 0 && eclipseBaselineOptions.value.length > 0)
const eclipseMultiScheduleAvailable = computed(() => isEclipseModel.value && eclipseMultiScheduleWells.value.length > 0 &&
  eclipseMultiScheduleDates.value.length >= 2 && eclipseBaselineOptions.value.length > 0)
const eclipseWconHistAvailable = computed(() => eclipseScheduleAvailable.value &&
  (activeVersion.value?.inspection?.scheduleMetadata?.records || []).some(record => record?.keyword === 'WCONHIST' &&
    Array.isArray(record.values) && record.values.length >= 4 && eclipseScheduleWells.value.includes(record.values[0])))
const eclipseWconHistRecord = computed(() => eclipseDatedControl(activeVersion.value?.inspection, 'WCONHIST', eclipseScheduleWell.value, eclipseScheduleDate.value))
const eclipseWconHistUnit = computed(() => eclipseSurfaceRateUnit(activeVersion.value?.inspection?.unitSystem))
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
const eclipseWconInjeDatedRecord = computed(() => eclipseDatedControl(activeVersion.value?.inspection, 'WCONINJE', eclipseWconInjeWell.value, eclipseWconInjeDate.value))
const eclipseWconInjeFluid = computed(() => (eclipseWconInjeDatedRecord.value || eclipseWconInjeRecord.value)?.values?.[1] || '')
const eclipseWconInjeUnit = computed(() => eclipseSurfaceRateUnit(activeVersion.value?.inspection?.unitSystem, eclipseWconInjeFluid.value))
const eclipseWconInjeDefaultTargetRate = computed(() => {
  const value = Number(eclipseWconInjeRecord.value?.values?.[4])
  return Number.isFinite(value) && value > 0 ? value : 1000
})
const eclipseWconInjeAvailable = computed(() => isEclipseModel.value && eclipseScheduleDates.value.length > 0 &&
  eclipseBaselineOptions.value.length > 0 && eclipseWconInjeRecords.value.length > 0)
const eclipseProductionTargetRows = computed(() => productionTargetRecords(activeVersion.value?.inspection))
const eclipseProductionTargetMode = computed(() => eclipseProductionTargetRows.value.length > 0)
const eclipseProductionTargetRecord = computed(() => eclipseProductionTargetRows.value.find(record => record.well === eclipseWconProdWell.value) || null)
const eclipseWconProdRecords = computed(() => eclipseProductionTargetMode.value ? eclipseProductionTargetRows.value.map(row => row.record) : (activeVersion.value?.inspection?.scheduleMetadata?.records || []).filter(record => {
  const values = record?.values
  return record?.keyword === 'WCONPROD' && Array.isArray(values) && values.length >= 4 &&
    typeof values[0] === 'string' && values[2] === 'ORAT' && Number.isFinite(Number(values[3]))
}))
const eclipseWconProdWells = computed(() => [...new Set(eclipseWconProdRecords.value.map(record => record.values[0]))])
const eclipseWconProdRecord = computed(() => eclipseWconProdRecords.value.find(record => record.values[0] === eclipseWconProdWell.value) || null)
const eclipseWconProdDefaultTargetRate = computed(() => {
  const value = Number(eclipseWconProdRecord.value?.values?.[3])
  return Number.isFinite(value) && value > 0 ? (eclipseProductionTargetMode.value ? Number((value * 0.9).toPrecision(12)) : value) : 5000
})
const eclipseWconProdAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseWconProdRecords.value.length > 0)
const eclipsePressureRecords = computed(() => pressureControlRecords(activeVersion.value?.inspection))
const eclipsePressureRecord = computed(() => eclipsePressureRecords.value.find(row => row.well === eclipsePressureWell.value) || null)
const eclipsePressureAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipsePressureRecords.value.length > 0)
const eclipseThpRecords = computed(() => thpControlRecords(activeVersion.value?.inspection))
const eclipseThpRecord = computed(() => eclipseThpRecords.value.find(row => row.well === eclipseThpWell.value) || null)
const eclipseThpAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseThpRecords.value.length > 0)
const eclipseNewDateWindows = computed(() => newDatesWindows(activeVersion.value?.inspection))
const eclipseNewDatesWindow = computed(() => eclipseNewDateWindows.value.find(w => w.beforeLineNumber === eclipseNewDatesWindowKey.value) || null)
const eclipseNewDatesAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseNewDateWindows.value.length > 0)
const eclipseTstepRecords = computed(() => tstepRecords(activeVersion.value?.inspection))
const eclipseControlPlanRecords = computed(() => controlPlanPeriods(activeVersion.value?.inspection))
const eclipseControlPlanAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseControlPlanRecords.value.length > 0)
watch(eclipseControlPlanRecords, rows => { eclipseControlPlanDraft.value = rows.map(c => ({ ...c, targetOilRate: c.originalOilRate })) }, { immediate: true })
const eclipseWaterInjectionPlanRecords = computed(() => waterInjectionPlanPeriods(activeVersion.value?.inspection))
const eclipseWaterInjectionPlanAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseWaterInjectionPlanRecords.value.length > 0)
watch(eclipseWaterInjectionPlanRecords, rows => { eclipseWaterInjectionPlanDraft.value = rows.map(c => ({ ...c, targetInjectionRate: c.originalInjectionRate })) }, { immediate: true })
const eclipseGroupStructureRecords = computed(() => groupStructureRecords(activeVersion.value?.inspection))
const eclipseGroupStructureRecord = computed(() => eclipseGroupStructureRecords.value.find(c => groupStructureKey(c) === eclipseGroupStructureControl.value) || null)
const eclipseGroupStructureAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupStructureRecords.value.length > 0)
watch(eclipseGroupStructureRecord, c => { eclipseGroupStructureTarget.value = c?.originalValue ?? null })
const eclipseAdvancedInjectionRecords = computed(() => advancedInjectionRecords(activeVersion.value?.inspection))
const eclipseAdvancedInjectionRecord = computed(() => eclipseAdvancedInjectionRecords.value.find(r => r.group === eclipseAdvancedInjectionGroup.value) || null)
const eclipseAdvancedInjectionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseAdvancedInjectionRecords.value.length > 0)
watch(eclipseAdvancedInjectionRecord, r => { eclipseAdvancedInjectionGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseGroupWaterProductionRecords = computed(() => groupWaterProductionRecords(activeVersion.value?.inspection))
const eclipseGroupWaterProductionRecord = computed(() => eclipseGroupWaterProductionRecords.value.find(r => r.group === eclipseWaterProductionGroup.value) || null)
const eclipseGroupWaterProductionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupWaterProductionRecords.value.length > 0)
watch(eclipseGroupWaterProductionRecord, r => { eclipseWaterProductionGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseGroupRateRecords = computed(() => groupRateRecords(activeVersion.value?.inspection))
const eclipseGroupRateRecord = computed(() => eclipseGroupRateRecords.value.find(r => r.group === eclipseRateGroup.value) || null)
const eclipseGroupRateAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupRateRecords.value.length > 0)
watch(eclipseGroupRateRecord, r => { eclipseRateGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseGroupProductionRecords = computed(() => groupProductionRecords(activeVersion.value?.inspection))
const eclipseGroupInjectionRecords = computed(() => groupInjectionRecords(activeVersion.value?.inspection))
const eclipseGroupInjectionRecord = computed(() => eclipseGroupInjectionRecords.value.find(r => r.group === eclipseInjectionGroup.value) || null)
const eclipseGroupInjectionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupInjectionRecords.value.length > 0)
watch(eclipseGroupInjectionRecord, r => { eclipseInjectionGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseGroupGasInjectionRecords = computed(() => groupGasInjectionRecords(activeVersion.value?.inspection))
const eclipseGroupGasInjectionRecord = computed(() => eclipseGroupGasInjectionRecords.value.find(r => r.group === eclipseGasInjectionGroup.value) || null)
const eclipseGroupGasInjectionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupGasInjectionRecords.value.length > 0)
watch(eclipseGroupGasInjectionRecord, r => { eclipseGasInjectionGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseGroupProductionRecord = computed(() => eclipseGroupProductionRecords.value.find(r => r.group === eclipseProductionGroup.value) || null)
const eclipseGroupProductionAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseGroupProductionRecords.value.length > 0)
watch(eclipseGroupProductionRecord, r => { eclipseGroupTarget.value = r?.originalTargetRate ?? null })
const eclipseTstepRecord = computed(() => eclipseTstepRecords.value.find(r => r.lineNumber === eclipseTstepLine.value) || null)
const eclipseTstepAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseTstepRecords.value.length > 0)
watch(eclipseTstepRecord, r => { eclipseTstepText.value = r?.originalSteps.join(' ') || '' })
watch(eclipseNewDatesWindow, window => {
  eclipseNewDatesWell.value = window?.wells[0] || ''
  const day = window ? Date.parse(window.beforeDate + 'T00:00:00Z') : null
  const span = window ? (Date.parse(window.afterDate) - day) / 86400000 : 0
  const offset = n => new Date(day + n * 86400000).toISOString().slice(0, 10)
  eclipseNewShutDate.value = window ? offset(Math.max(1, Math.floor(span / 3))) : ''
  eclipseNewOpenDate.value = window ? offset(Math.max(2, Math.floor(span * 2 / 3))) : ''
})
const eclipseRateControlRecords = computed(() => productionControlRecords(activeVersion.value?.inspection))
const eclipseRateControlRecord = computed(() => eclipseRateControlRecords.value.find(row => row.well === eclipseRateControlWell.value) || null)
const eclipseRateControlAvailable = computed(() => isEclipseModel.value && eclipseBaselineOptions.value.length > 0 && eclipseRateControlRecords.value.length > 0)
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
const eclipseCompletionFactorUnit = computed(() => completionFactorUnit(activeVersion.value?.inspection?.unitSystem))
const eclipseCompletionFactorAvailable = computed(() => isEclipseModel.value && eclipseCompletionFactorUnit.value && eclipseBaselineOptions.value.length > 0 && eclipseCompletionFactorRecords.value.length > 0)
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
  .filter(artifact => artifact?.name && (/^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$/i.test(artifact.name) ||
    /^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.UNRST$/i.test(artifact.name) && eclipseForecastHistoryDetail.value.artifacts.some(a => a.name === 'eclipse-restart-catalog.json')) && /^[a-f0-9]{64}$/i.test(artifact.sha256 || '')))
const eclipseForecastSource = computed(() => eclipseForecastSources.value[Number(eclipseForecastHistoryRunId.value)] || null)
const eclipseForecastCatalogPresent = computed(() => (eclipseForecastHistoryDetail.value?.artifacts || []).some(a => a.name === 'eclipse-restart-catalog.json'))
const eclipseForecastRequiresCatalog = computed(() => eclipseForecastCatalogPresent.value ||
  (eclipseForecastHistoryDetail.value?.artifacts || []).some(a => /^eclipse-output-.+\.UNRST$/i.test(a.name || '')))
const eclipseForecastSelectableDataFiles = computed(() => eclipseForecastCatalogPresent.value
  ? (eclipseForecastSource.value?.forecastInputs || []).filter(f => activeVersion.value?.inspection?.packageFiles?.some(p => p.relativePath === f.relativePath && p.sha256 === f.sha256)).map(f => f.relativePath)
  : eclipseForecastDataFiles.value)
const eclipseForecastReportLabel = report => {
  const record = eclipseForecastSource.value?.catalog?.reports?.find(r => r.reportNumber === report)
  return record ? `${record.date} · 第 ${record.timeDays} 天 · 报告 ${report}` : `报告 ${report}`
}
const eclipseForecastRestartReports = computed(() => {
  if (eclipseForecastSource.value) {
    const future = eclipseForecastSource.value.forecastInputs.find(f => f.relativePath === eclipseForecastDataFile.value)
    return eclipseForecastSource.value.catalog.reports.filter(r => r.reportNumber === future?.restartReport).map(r => r.reportNumber)
  }
  const artifact = eclipseForecastRestartArtifacts.value.find(item => item.name === eclipseForecastRestartArtifactName.value)
  const files = eclipseForecastHistoryDetail.value?.result?.fieldIndex?.files
  if (!artifact || !Array.isArray(files)) return []
  const outputName = artifact.name.slice('eclipse-output-'.length)
  const indexedFile = files.find(file => typeof file?.name === 'string' && file.name.toLowerCase() === outputName.toLowerCase())
  const reports = (Array.isArray(indexedFile?.fields) ? indexedFile.fields : [])
    .filter(field => typeof field?.keyword === 'string' && field.keyword.toUpperCase() === 'SEQNUM')
    .map(field => field?.timeStep)
    .filter(report => Number.isSafeInteger(report) && report > 0)
  return [...new Set(reports)].sort((left, right) => left - right)
})
const eclipseForecastAvailable = computed(() => isEclipseModel.value && eclipseForecastDataFiles.value.length > 0 && eclipseForecastHistoryOptions.value.length > 0)
const disableOtherEclipseScenarios = () => {
  eclipseForecastEnabled.value = false
  eclipseScheduleEnabled.value = false
  eclipseMultiScheduleEnabled.value = false
  eclipseWconHistEnabled.value = false
  eclipseWconInjeEnabled.value = false
  eclipseWconProdEnabled.value = false
  eclipsePressureEnabled.value = false
  eclipseRateControlEnabled.value = false
  eclipseThpEnabled.value = false
  eclipseNewDatesEnabled.value = false
  eclipseTstepEnabled.value = false
  eclipseGroupInjectionEnabled.value = false
  eclipseGroupGasInjectionEnabled.value = false
  eclipseControlPlanEnabled.value = false
  eclipseWaterInjectionPlanEnabled.value = false
  eclipseGroupStructureEnabled.value = false
  eclipseAdvancedInjectionEnabled.value = false
  eclipseGroupWaterProductionEnabled.value = false
  eclipseGroupRateEnabled.value = false
  eclipseGroupProductionEnabled.value = false
  eclipseCompletionEnabled.value = false
  eclipseCompletionFactorEnabled.value = false
}
const toggleEclipseSchedule = value => { if (value) { disableOtherEclipseScenarios(); eclipseScheduleEnabled.value = true } }
const toggleEclipseMultiSchedule = value => { if (value) { disableOtherEclipseScenarios(); eclipseMultiScheduleEnabled.value = true } }
const toggleEclipseWconHist = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconHistEnabled.value = true } }
const toggleEclipseWconInje = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconInjeEnabled.value = true } }
const toggleEclipseWconProd = value => { if (value) { disableOtherEclipseScenarios(); eclipseWconProdEnabled.value = true } }
const toggleEclipsePressure = value => { if (value) { disableOtherEclipseScenarios(); eclipsePressureEnabled.value = true } }
const toggleEclipseThp = value => { if (value) { disableOtherEclipseScenarios(); eclipseThpEnabled.value = true } }
const toggleEclipseNewDates = value => { if (value) { disableOtherEclipseScenarios(); eclipseNewDatesEnabled.value = true } }
const toggleEclipseTstep = value => { if (value) { disableOtherEclipseScenarios(); eclipseTstepEnabled.value = true } }
const toggleEclipseControlPlan = value => { if (value) { disableOtherEclipseScenarios(); eclipseControlPlanEnabled.value = true } }
const toggleEclipseWaterInjectionPlan = value => { if (value) { disableOtherEclipseScenarios(); eclipseWaterInjectionPlanEnabled.value = true } }
const toggleEclipseGroupStructure = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupStructureEnabled.value = true } }
const toggleEclipseAdvancedInjection = value => { if (value) { disableOtherEclipseScenarios(); eclipseAdvancedInjectionEnabled.value = true } }
const toggleEclipseGroupWaterProduction = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupWaterProductionEnabled.value = true } }
const toggleEclipseGroupRate = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupRateEnabled.value = true } }
const toggleEclipseGroupProduction = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupProductionEnabled.value = true } }
const toggleEclipseGroupInjection = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupInjectionEnabled.value = true } }
const toggleEclipseGroupGasInjection = value => { if (value) { disableOtherEclipseScenarios(); eclipseGroupGasInjectionEnabled.value = true } }
const toggleEclipseRateControl = value => { if (value) { disableOtherEclipseScenarios(); eclipseRateControlEnabled.value = true } }
const toggleEclipseCompletion = value => { if (value) { disableOtherEclipseScenarios(); eclipseCompletionEnabled.value = true } }
const toggleEclipseCompletionFactor = value => { if (value) { disableOtherEclipseScenarios(); eclipseCompletionFactorEnabled.value = true } }
const toggleEclipseForecast = value => { if (value) { disableOtherEclipseScenarios(); eclipseForecastEnabled.value = true } }
const eclipseScenarioSummary = computed(() => {
  if (eclipseTstepEnabled.value) return 'TSTEP 报告间隔方案'
  if (eclipseControlPlanEnabled.value) return '多井分阶段产油目标方案'
  if (eclipseWaterInjectionPlanEnabled.value) return '多井分阶段注水目标方案'
  if (eclipseGroupStructureEnabled.value) return '组树目标、井导向与开工比例方案'
  if (eclipseAdvancedInjectionEnabled.value) return '注入目标与回注比例方案'
  if (eclipseGroupWaterProductionEnabled.value) return '生产组总产水目标方案'
  if (eclipseGroupRateEnabled.value) return '生产组总产气/总产液目标方案'
  if (eclipseGroupProductionEnabled.value) return '生产组总产油目标方案'
  if (eclipseGroupInjectionEnabled.value) return '注入组总注水目标方案'
  if (eclipseGroupGasInjectionEnabled.value) return '注入组总注气目标方案'
  if (eclipseNewDatesEnabled.value) return '新增日期停井／复产方案'
  if (eclipseThpEnabled.value) return '井口压力 THP 方案'
  if (eclipseRateControlEnabled.value) return `${eclipseRateControlRecord.value?.controlMode || ''} 生产目标方案`
  if (eclipsePressureEnabled.value) return '井底压力 BHP 方案'
  if (eclipseForecastEnabled.value) return '历史重启预测'
  if (eclipseCompletionFactorEnabled.value) return 'COMPDAT 连接因子方案'
  if (eclipseCompletionEnabled.value) return 'COMPDAT 完井状态方案'
  if (eclipseWconProdEnabled.value) return 'WCONPROD ORAT 方案'
  if (eclipseWconInjeEnabled.value) return 'WCONINJE RATE 方案'
  if (eclipseWconHistEnabled.value) return 'WCONHIST ORAT 方案'
  if (eclipseMultiScheduleEnabled.value) return `WELOPEN 井组方案 · ${eclipseMultiScheduleSelectedWells.value.length} 口井`
  if (eclipseScheduleEnabled.value) return 'WELOPEN 方案'
  return '不覆盖'
})
const loadEclipseForecastHistory = async runId => {
  const id = Number(runId)
  const versionId = activeVersionId.value
  const generation = ++eclipseForecastHistoryGeneration
  eclipseForecastCatalogError.value = ''
  eclipseForecastCatalogLoading.value = false
  if (!id) return
  const feedback = beginFeedback()
  try {
    const existing = eclipseHistoryRunDetails.value[id]
    const payload = existing ? null : await softwareIntegrationApi.getRun(id)
    const detail = existing || payload?.data || payload
    if (generation !== eclipseForecastHistoryGeneration || versionId !== activeVersionId.value || id !== Number(eclipseForecastHistoryRunId.value)) return
    if (detail?.id !== id || detail.modelVersionId !== versionId) return
    eclipseHistoryRunDetails.value = { ...eclipseHistoryRunDetails.value, [id]: detail }
    const artifact = detail.artifacts?.find(a => a.name === 'eclipse-restart-catalog.json')
    if (artifact && !eclipseForecastSources.value[id]) {
      eclipseForecastCatalogLoading.value = true
      const blob = await softwareIntegrationApi.downloadArtifact(id, artifact.id)
      if (blob.size !== artifact.sizeBytes || blob.size > 8 * 1024 * 1024) throw new Error('Invalid catalog size')
      const bytes = await blob.arrayBuffer()
      const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
      const checksum = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('')
      const source = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
      if (checksum !== artifact.sha256 || !validRestartSource(source) || !detail.artifacts.some(a => a.name === source.catalog.artifactName && a.sha256 === source.catalog.artifactSha256 && a.sizeBytes === source.catalog.artifactSizeBytes)) throw new Error('Invalid catalog binding')
      if (generation !== eclipseForecastHistoryGeneration || versionId !== activeVersionId.value || id !== Number(eclipseForecastHistoryRunId.value)) return
      eclipseForecastSources.value = { ...eclipseForecastSources.value, [id]: source }
    }
  } catch {
    if (generation === eclipseForecastHistoryGeneration && versionId === activeVersionId.value) {
      eclipseForecastCatalogError.value = '历史报告目录读取或核对失败，请重新选择历史运行'
      feedback.error(eclipseForecastCatalogError.value)
    }
  } finally {
    if (generation === eclipseForecastHistoryGeneration) eclipseForecastCatalogLoading.value = false
  }
}
watch([activeVersionId, () => activeVersion.value?.inspection, () => runHistory.value.length], () => {
  eclipsePressureEnabled.value = false
  eclipseRateControlEnabled.value = false
  eclipseThpEnabled.value = false
  eclipseNewDatesEnabled.value = false
  eclipseTstepEnabled.value = false
  eclipseGroupInjectionEnabled.value = false
  eclipseGroupGasInjectionEnabled.value = false
  eclipseControlPlanEnabled.value = false
  eclipseWaterInjectionPlanEnabled.value = false
  eclipseGroupStructureEnabled.value = false
  eclipseAdvancedInjectionEnabled.value = false
  eclipseGroupWaterProductionEnabled.value = false
  eclipseGroupRateEnabled.value = false
  eclipseGroupProductionEnabled.value = false
  eclipseScheduleEnabled.value = false
  eclipseMultiScheduleEnabled.value = false
  eclipseWconHistEnabled.value = false
  eclipseWconInjeEnabled.value = false
  eclipseWconProdEnabled.value = false
  eclipseCompletionEnabled.value = false
  eclipseCompletionFactorEnabled.value = false
  eclipseForecastEnabled.value = false
  eclipseScheduleWell.value = eclipseScheduleWells.value[0] || ''
  eclipseScheduleDate.value = eclipseScheduleDates.value[0] || ''
  eclipseMultiScheduleSelectedWells.value = eclipseMultiScheduleWells.value.slice(0, 1)
  eclipseMultiScheduleShutDate.value = eclipseMultiScheduleDates.value[0] || ''
  eclipseMultiScheduleOpenDate.value = eclipseMultiScheduleDates.value[1] || ''
  eclipseMultiDateMode.value = false
  eclipseMultiDateEvents.value = []
  eclipseScheduleStatus.value = 'SHUT'
  eclipseWconHistTargetOilRate.value = 100
  eclipseWconInjeWell.value = eclipseWconInjeWells.value[0] || ''
  eclipseWconInjeDate.value = eclipseScheduleDates.value[0] || ''
  eclipseWconInjeTargetRate.value = eclipseWconInjeDefaultTargetRate.value
  eclipseWconProdWell.value = eclipseWconProdWells.value[0] || ''
  eclipseWconProdStatus.value = 'SHUT'
  eclipseWconProdTargetOilRate.value = eclipseWconProdDefaultTargetRate.value
  eclipsePressureWell.value = eclipsePressureRecords.value[0]?.well || ''
  eclipseThpWell.value = eclipseThpRecords.value[0]?.well || ''
  eclipseNewDatesWindowKey.value = eclipseNewDateWindows.value[0]?.beforeLineNumber || null
  eclipseTstepLine.value = eclipseTstepRecords.value[0]?.lineNumber || null
  eclipseGroupStructureControl.value = eclipseGroupStructureRecords.value[0] ? groupStructureKey(eclipseGroupStructureRecords.value[0]) : ''
  eclipseAdvancedInjectionGroup.value = eclipseAdvancedInjectionRecords.value[0]?.group || ''
  eclipseWaterProductionGroup.value = eclipseGroupWaterProductionRecords.value[0]?.group || ''
  eclipseRateGroup.value = eclipseGroupRateRecords.value[0]?.group || ''
  eclipseProductionGroup.value = eclipseGroupProductionRecords.value[0]?.group || ''
  eclipseInjectionGroup.value = eclipseGroupInjectionRecords.value[0]?.group || ''
  eclipseGasInjectionGroup.value = eclipseGroupGasInjectionRecords.value[0]?.group || ''
  eclipseRateControlWell.value = eclipseRateControlRecords.value[0]?.well || ''
  eclipseCompletionKey.value = eclipseCompletionRecords.value.length ? `${eclipseCompletionRecords.value[0].sourceFile}:${eclipseCompletionRecords.value[0].lineNumber}` : ''
  eclipseCompletionStatus.value = eclipseCompletionRecords.value[0]?.status === 'OPEN' ? 'SHUT' : 'OPEN'
  eclipseCompletionFactorTarget.value = eclipseCompletionFactorRecords.value[0]?.originalConnectionFactor
    ? Number((eclipseCompletionFactorRecords.value[0].originalConnectionFactor * 0.5).toPrecision(12)) : null
  eclipseBaselineRunId.value = eclipseBaselineOptions.value[0]?.id || null
  eclipseForecastDataFile.value = eclipseForecastDataFiles.value[0] || ''
  eclipseForecastHistoryRunId.value = eclipseForecastHistoryOptions.value[0]?.id || null
  eclipseForecastRestartArtifactName.value = ''
  eclipseForecastRestartReport.value = null
  eclipseHistoryRunDetails.value = {}
  eclipseForecastSources.value = {}
  eclipseForecastHistoryGeneration++
  eclipseForecastCatalogLoading.value = false
  eclipseForecastCatalogError.value = ''
}, { immediate: true })
watch(() => eclipseMultiScheduleShutDate.value, value => {
  if (eclipseMultiScheduleOpenDate.value <= value) {
    eclipseMultiScheduleOpenDate.value = eclipseMultiScheduleDates.value.find(date => date > value) || ''
  }
})
watch([() => eclipseMultiScheduleAvailable.value, () => eclipseMultiScheduleWells.value.join('|'), () => eclipseMultiScheduleDates.value.join('|')], () => {
  eclipseMultiScheduleSelectedWells.value = eclipseMultiScheduleSelectedWells.value.filter(well => eclipseMultiScheduleWells.value.includes(well))
  if (!eclipseMultiScheduleDates.value.includes(eclipseMultiScheduleShutDate.value)) eclipseMultiScheduleShutDate.value = eclipseMultiScheduleDates.value[0] || ''
  if (!eclipseMultiScheduleDates.value.includes(eclipseMultiScheduleOpenDate.value) || eclipseMultiScheduleOpenDate.value <= eclipseMultiScheduleShutDate.value) {
    eclipseMultiScheduleOpenDate.value = eclipseMultiScheduleDates.value.find(date => date > eclipseMultiScheduleShutDate.value) || ''
  }
})
watch([eclipseForecastHistoryRunId, activeVersionId, () => runHistory.value.length], async ([value]) => {
  await loadEclipseForecastHistory(value)
  if (Number(value) !== Number(eclipseForecastHistoryRunId.value)) return
  const artifact = eclipseForecastRestartArtifacts.value[0]
  if (artifact) eclipseForecastRestartArtifactName.value = artifact.name
}, { immediate: true })
watch(eclipseForecastRestartArtifacts, artifacts => {
  if (!artifacts.some(artifact => artifact.name === eclipseForecastRestartArtifactName.value)) {
    eclipseForecastRestartArtifactName.value = artifacts[0]?.name || ''
  }
})
watch(eclipseForecastRestartArtifactName, () => { eclipseForecastRestartReport.value = null })
watch(eclipseForecastRestartReports, reports => {
  if (reports.length && !reports.includes(Number(eclipseForecastRestartReport.value))) eclipseForecastRestartReport.value = null
})
watch([eclipseForecastSource, eclipseForecastSelectableDataFiles, eclipseForecastDataFile, eclipseForecastRestartArtifactName], () => {
  const source = eclipseForecastSource.value
  if (!source) return
  if (!eclipseForecastSelectableDataFiles.value.includes(eclipseForecastDataFile.value)) eclipseForecastDataFile.value = eclipseForecastSelectableDataFiles.value[0] || ''
  if (eclipseForecastRestartArtifactName.value !== source.catalog.artifactName) eclipseForecastRestartArtifactName.value = source.catalog.artifactName
  eclipseForecastRestartReport.value = source.forecastInputs.find(f => f.relativePath === eclipseForecastDataFile.value)?.restartReport || null
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
watch([eclipseWconProdWell, () => activeVersion.value?.id], () => {
  eclipseWconProdTargetOilRate.value = eclipseWconProdDefaultTargetRate.value
  if (eclipseProductionTargetMode.value) eclipseWconProdStatus.value = 'OPEN'
})
watch(eclipsePressureRecord, record => {
  eclipsePressureTarget.value = record ? Number((record.originalBhp * (record.keyword === 'WCONPROD' ? 1.1 : 0.9)).toPrecision(12)) : null
}, { immediate: true })
const sensitivityVariable = ref('reservoirPressure')
const sensitivityValuesText = ref('3000, 4000, 5000')
const isSensitivityRun = computed(() => isWellModel.value && runType.value === 'sensitivity')
const espContext = ref('')
const espParameter = ref('operatingFrequency')
const espTargetValue = ref(70)
const isEspParameterRun = computed(() => isWellModel.value && runType.value === 'esp-parameter')
const isEspCurvesRun = computed(() => isWellModel.value && runType.value === 'esp-curves')
const isTrajectoryRun = computed(() => isWellModel.value && runType.value === 'trajectory')
const trajectoryEditEnabled = ref(false)
const trajectoryStationIndex = ref(2)
const trajectoryTargetInclination = ref(26.92574034)
const trajectoryEditSupportedInspection = computed(() => ['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion.value?.inspection?.schemaVersion))
const wellTubingEditEnabled = ref(false)
const wellTubingContext = ref('')
const wellTubingTargetInnerDiameter = ref(null)
const wellTubingBaselineRunId = ref(null)
const selectedWellInspection = computed(() => availableWellContexts.value.find(item => item.context === selectedWellContext.value) || null)
const availableTubingComponents = computed(() => {
  const components = selectedWellInspection.value?.tubingComponents
  if (!['pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion.value?.inspection?.schemaVersion) || !Array.isArray(components) || components.length > 128) return []
  return components.filter(item => typeof item?.context === 'string' && isSafeTopologyText(item.context) &&
    typeof item?.unit === 'string' && item.unit.trim().length > 0 && item.unit.length <= 32 &&
    isFiniteNumber(item.innerDiameter) && item.innerDiameter > 0 && item.innerDiameter <= 1000)
})
watch(eclipseThpRecord, record => {
  eclipseThpTarget.value = record ? Math.min(record.table.thpAxis.at(-1), Number((record.originalThp * 1.4).toPrecision(12))) : null
}, { immediate: true })
watch(eclipseRateControlRecord, record => {
  eclipseRateControlTarget.value = record ? Number((record.originalTargetRate * 0.8).toPrecision(12)) : null
}, { immediate: true })
const wellTubingBaselineOptions = computed(() => runHistory.value.filter(run =>
  run.modelVersionId === activeVersionId.value && run.runType === 'profile' &&
  run.study === selectedStudy.value && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' &&
  (run.parameters === null || run.parameters === undefined)))
const wellTubingRecord = computed(() => availableTubingComponents.value.find(item => item.context === wellTubingContext.value) || null)
const wellTubingEditAvailable = computed(() => isWellModel.value && runType.value === 'profile' &&
  ['pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion.value?.inspection?.schemaVersion) && availableWellContexts.value.length === 1 &&
  Boolean(selectedWellInspection.value?.completionContexts?.length && availableTubingComponents.value.length) &&
  wellTubingBaselineOptions.value.length > 0)
watch([activeVersionId, selectedStudy, availableTubingComponents, () => wellTubingBaselineOptions.value], () => {
  wellTubingEditEnabled.value = false
  wellTubingContext.value = availableTubingComponents.value[0]?.context || ''
  wellTubingTargetInnerDiameter.value = wellTubingRecord.value
    ? Number((wellTubingRecord.value.innerDiameter * 0.95).toPrecision(12)) : null
  wellTubingBaselineRunId.value = wellTubingBaselineOptions.value[0]?.id || null
}, { immediate: true })
watch(wellTubingContext, () => {
  const component = wellTubingRecord.value
  wellTubingTargetInnerDiameter.value = component
    ? Number((component.innerDiameter * 0.95).toPrecision(12)) : null
})
watch(activeVersionId, () => { trajectoryEditEnabled.value = false })
watch(espParameter, value => { espTargetValue.value = value === 'headFactor' ? 0.9 : 70 })
watch([activeVersionId, availableEspContexts], () => {
  const contexts = availableEspContexts.value
  espContext.value = contexts.length === 1 ? contexts[0] : contexts.includes(espContext.value) ? espContext.value : ''
}, { immediate: true })
const validatedWellName = computed(() => {
  const well = activeVersion.value?.inspection?.well
  return typeof well === 'string' ? well.trim() : ''
})
const gasLiftProducer = ref('')
const gasLiftOutletPressure = ref(null)
const gasLiftSurfaceInjectionTemperature = ref(null)
const gasLiftTargetInjectionRate = ref(null)
const gasLiftReservoirPressure = ref(null)
const gasLiftGor = ref(null)
const gasLiftWaterCut = ref(null)
const gasLiftValuesText = ref('')
const gasLiftCapability = computed(() => gasLiftEligibility(activeVersion.value?.inspection, selectedWellContext.value, selectedStudy.value))
const gasLiftSnapshot = computed(() => gasLiftRunSnapshot(selectedRun.value))
const resetGasLiftDraft = () => {
  gasLiftProducer.value = selectedWellContext.value || ''
  gasLiftOutletPressure.value = gasLiftSurfaceInjectionTemperature.value = gasLiftTargetInjectionRate.value = null
  gasLiftReservoirPressure.value = gasLiftGor.value = gasLiftWaterCut.value = null
  gasLiftValuesText.value = ''
}
const loadGasLiftExample = () => {
  gasLiftOutletPressure.value = 151
  gasLiftSurfaceInjectionTemperature.value = 110
  gasLiftTargetInjectionRate.value = 1.1
  gasLiftReservoirPressure.value = 1700
  gasLiftGor.value = 400
  gasLiftWaterCut.value = 80
  gasLiftValuesText.value = '0.55, 0.65, 0.75, 0.85, 0.95, 1.1, 1.4, 1.7, 2.0, 2.4, 2.8'
}
watch([activeVersionId, selectedWellContext, selectedStudy], resetGasLiftDraft, { immediate: true })
const isGasLiftPerformanceRun = computed(() => isWellModel.value && runType.value === 'gas-lift-performance')
const isGasLiftDiagnosticsRun = computed(() => isWellModel.value && runType.value === 'gas-lift-diagnostics')
const vfpProducer = computed(() => validatedWellName.value)
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
  wellScopedInputVersionId.value = versionId
}
watch([activeVersionId, validatedWellName], syncWellScopedInputs, { immediate: true })
const buildGasLiftPerformanceParameters = () => {
  if (!gasLiftCapability.value.supported) return { error: gasLiftCapability.value.reason }
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
  if (!gasLiftCapability.value.supported) return { error: gasLiftCapability.value.reason }
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
const systemAnalysisProducer = ref('')
const systemAnalysisBranchTerminator = ref('')
const systemAnalysisOutletPressure = ref(600)
const systemAnalysisValuesText = ref('2400, 3000, 3600')
const systemAnalysisErrors = ref({})
const systemAnalysisDraft = computed(() => ({
  producer: systemAnalysisProducer.value, branchTerminator: systemAnalysisBranchTerminator.value,
  outletPressurePsi: systemAnalysisOutletPressure.value, valuesText: systemAnalysisValuesText.value,
  producers: networkPerformanceProducers.value, branches: networkPerformanceBranches.value,
  study: selectedStudy.value, studies: persistedStudies.value
}))
watch([activeVersionId, selectedStudy], () => {
  systemAnalysisProducer.value = networkPerformanceProducers.value.length === 1 ? networkPerformanceProducers.value[0] : ''
  systemAnalysisBranchTerminator.value = networkPerformanceBranches.value.length === 1 ? networkPerformanceBranches.value[0] : ''
  systemAnalysisErrors.value = {}
}, { immediate: true })
watch([networkPerformanceProducers, networkPerformanceBranches], () => {
  if (!networkPerformanceProducers.value.includes(systemAnalysisProducer.value)) {
    systemAnalysisProducer.value = networkPerformanceProducers.value.length === 1 ? networkPerformanceProducers.value[0] : ''
  }
  if (!networkPerformanceBranches.value.includes(systemAnalysisBranchTerminator.value)) {
    systemAnalysisBranchTerminator.value = networkPerformanceBranches.value.length === 1 ? networkPerformanceBranches.value[0] : ''
  }
})
watch(systemAnalysisDraft, draft => {
  if (Object.keys(systemAnalysisErrors.value).length) systemAnalysisErrors.value = buildSystemAnalysisParameters(draft).errors
})
const isSystemAnalysisRun = computed(() => isNetworkWorkflow.value && runType.value === 'system-analysis')
const isNetworkOptimizerRun = computed(() => isNetworkWorkflow.value && runType.value === 'network-optimizer')
const isNetworkWellPerformanceRun = computed(() => isNetworkWorkflow.value && runType.value === 'network-well-performance-curves')
const networkOptimizerApplyResults = ref(false)
const networkChokeEnabled = ref(false)
const networkChokeName = ref('')
const networkChokeTarget = ref(null)
const networkChokeBaselineRunId = ref(null)
const networkFlowlineDiameterEnabled = ref(false)
const networkFlowlineName = ref('')
const networkFlowlineTarget = ref(null)
const networkFlowlineBaselineRunId = ref(null)
const buildNetworkWellPerformanceParameters = () => {
  const producerContext = String(networkPerformanceProducer.value || '').trim()
  const branchTerminator = String(networkPerformanceBranch.value || '').trim()
  const values = parseStrictValues(networkPerformanceValuesText.value)
  const validContext = value => value.length > 0 && value.length <= 255 && !value.includes('..') && !/[\\/\u0000-\u001f\u007f]/.test(value)
  if (!networkPerformanceProducers.value.includes(producerContext) || !validContext(producerContext) ||
    !networkPerformanceBranches.value.includes(branchTerminator) || !validContext(branchTerminator) ||
    values.length < 2 || values.length > 8 || values.some(value => !Number.isFinite(value) || value <= 0 || value > 1000) ||
    values.some((value, index) => index > 0 && value <= values[index - 1])) {
    return { error: '请选择检查清单中的生产井和 Flowline，并输入 2–8 个严格递增、范围为 (0, 1000] in 的内径值' }
  }
  return {
    schemaVersion: 'pipesim-network-well-performance-parameters/1',
    producerContext,
    branchTerminator,
    sensitivityUnit: 'in',
    sensitivityValues: values
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
  if (!isNetworkWorkflow.value || activeVersion.value?.status !== 'READY' ||
    (isWellModel.value && !activeVersion.value?.inspection?.networkCapability?.studies?.includes(selectedStudy.value))) return null
  const studies = networkInspectionDocument.value?.studies
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
const networkInspectionUnavailable = computed(() => isNetworkTask.value && isNetworkWorkflow.value && activeVersion.value?.status === 'READY' && !networkInspection.value)
const networkChokes = computed(() => {
  if (!isNetworkWorkflow.value || !['pipesim-network-inspection/3', 'pipesim-network-inspection/4', 'pipesim-network-inspection/5'].includes(networkInspectionDocument.value?.schemaVersion)) return []
  const chokes = networkInspectionDocument.value?.chokes
  if (!Array.isArray(chokes) || chokes.length > 128) return []
  return chokes.filter(choke => typeof choke?.name === 'string' && isSafeTopologyText(choke.name) &&
    typeof choke?.unit === 'string' && isSafeTopologyText(choke.unit, true) && isFiniteNumber(choke.beanSize) &&
    choke.beanSize > 0 && choke.beanSize <= 1000000)
})
const networkChokeRecord = computed(() => networkChokes.value.find(choke => choke.name === networkChokeName.value) || networkChokes.value[0] || null)
const networkChokeBaselineOptions = computed(() => runHistory.value.filter(run =>
  run.modelVersionId === activeVersionId.value && run.runType === 'network' && run.study === selectedStudy.value &&
  run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL' && (run.parameters === null || run.parameters === undefined)))
const networkChokeAvailable = computed(() => isNetworkWorkflow.value && runType.value === 'network' &&
  networkChokes.value.length > 0 && networkChokeBaselineOptions.value.length > 0)
const networkFlowlines = computed(() => {
  if (!isNetworkWorkflow.value || networkInspectionDocument.value?.schemaVersion !== 'pipesim-network-inspection/5') return []
  const flowlines = networkInspectionDocument.value?.flowlines
  if (!Array.isArray(flowlines) || flowlines.length > 128) return []
  return flowlines.filter(flowline => typeof flowline?.name === 'string' && isSafeTopologyText(flowline.name) &&
    typeof flowline?.unit === 'string' && isSafeTopologyText(flowline.unit) && isFiniteNumber(flowline.innerDiameter) &&
    flowline.innerDiameter > 0 && flowline.innerDiameter <= 1000)
})
const networkFlowlineRecord = computed(() => networkFlowlines.value.find(flowline => flowline.name === networkFlowlineName.value) || networkFlowlines.value[0] || null)
const networkFlowlineDiameterAvailable = computed(() => isNetworkWorkflow.value && runType.value === 'network' &&
  networkFlowlines.value.length > 0 && networkChokeBaselineOptions.value.length > 0)
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
  networkFlowlineDiameterEnabled.value = false
  networkFlowlineName.value = networkFlowlines.value[0]?.name || ''
  networkFlowlineTarget.value = networkFlowlines.value[0]
    ? Number((networkFlowlines.value[0].innerDiameter * 1.25).toPrecision(12)) : null
  networkFlowlineBaselineRunId.value = networkChokeBaselineOptions.value[0]?.id || null
}, { immediate: true })
watch(networkChokeName, () => {
  const choke = networkChokeRecord.value
  networkChokeTarget.value = choke ? Number((choke.beanSize * 1.5).toPrecision(12)) : null
})
const networkFieldLabel = field => networkParameterFields.find(item => item.value === field)?.label || field
const networkParameterValueValid = (field, value) => Number.isFinite(value) && value > (field === 'temperature' ? -1000 : 0) && value <= (field.includes('FlowRate') ? 1000000000 : 100000)
const toggleNetworkBoundary = boundary => {
  networkChokeEnabled.value = false
  networkFlowlineDiameterEnabled.value = false
  boundary.enabled = !boundary.enabled
}
const toggleNetworkChoke = value => {
  if (!value) return
  networkFlowlineDiameterEnabled.value = false
  networkBoundaryDrafts.value.forEach(item => { item.enabled = false })
}
const toggleNetworkFlowlineDiameter = value => {
  if (!value) return
  networkChokeEnabled.value = false
  networkBoundaryDrafts.value.forEach(item => { item.enabled = false })
}
watch(networkFlowlineName, () => {
  const flowline = networkFlowlineRecord.value
  networkFlowlineTarget.value = flowline ? Number((flowline.innerDiameter * 1.25).toPrecision(12)) : null
})
const buildNetworkFlowlineDiameterParameters = () => {
  const flowline = networkFlowlineRecord.value
  if (!flowline || !Number.isSafeInteger(Number(networkFlowlineBaselineRunId.value)) || Number(networkFlowlineBaselineRunId.value) <= 0 ||
    !Number.isFinite(networkFlowlineTarget.value) || networkFlowlineTarget.value <= 0 || networkFlowlineTarget.value > 1000 ||
    Math.abs(networkFlowlineTarget.value - flowline.innerDiameter) <= Math.max(1e-12, Math.abs(flowline.innerDiameter) * 1e-12)) {
    return { error: 'Flowline 内径方案必须选择同 Study 的成功无参数基线，并输入与检查原值不同的正数目标值' }
  }
  return {
    schemaVersion: 'pipesim-network-flowline-diameter-parameters/1',
    baselineRunId: Number(networkFlowlineBaselineRunId.value),
    flowline: flowline.name,
    originalInnerDiameter: flowline.innerDiameter,
    unit: flowline.unit,
    targetInnerDiameter: Number(networkFlowlineTarget.value)
  }
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
const canRevalidateActiveModel = computed(() => (isWellModel.value || isNetworkModel.value || isEclipseModel.value) &&
  activeVersion.value?.status === 'READY' && !hasActiveRun.value && !submittingRun.value && !previewPending.value && !workerBusy.value)
const canUseSource = computed(() => isWellModel.value && !hasActiveRun.value && !submittingRun.value && !previewPending.value && sourcePressure.value > 0 && sourcePressure.value <= 100000)
const readSourceParameters = async () => {
  if (!canReadSource.value) return
  const feedback = beginFeedback()
  const projectId = activeProjectId.value
  const versionId = activeVersionId.value
  readingVersionId.value = versionId
  try {
    await store.revalidateModel(projectId, versionId)
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) feedback.success('已提交重新验证，完成后显示原模型参数')
  } catch {
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) feedback.error('原模型参数读取请求失败，请稍后重试')
  } finally {
    if (readingVersionId.value === versionId) readingVersionId.value = null
  }
}
const revalidateActiveModel = async () => {
  if (!canRevalidateActiveModel.value) return
  const feedback = beginFeedback()
  const projectId = activeProjectId.value
  const versionId = activeVersionId.value
  readingVersionId.value = versionId
  try {
    await store.revalidateModel(projectId, versionId)
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) feedback.success('已提交重新验证，完成后显示最新模型检查信息')
  } catch {
    if (activeProjectId.value === projectId && activeVersionId.value === versionId) feedback.error('模型重新验证请求失败，请稍后重试')
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
  pressureFieldTouched.value = false
  if (!isEclipseModel.value) {
    eclipsePressureEnabled.value = false
    eclipseRateControlEnabled.value = false
  eclipseThpEnabled.value = false
  eclipseNewDatesEnabled.value = false
  eclipseTstepEnabled.value = false
  eclipseGroupInjectionEnabled.value = false
  eclipseGroupGasInjectionEnabled.value = false
  eclipseControlPlanEnabled.value = false
  eclipseWaterInjectionPlanEnabled.value = false
  eclipseGroupStructureEnabled.value = false
  eclipseAdvancedInjectionEnabled.value = false
  eclipseGroupWaterProductionEnabled.value = false
  eclipseGroupRateEnabled.value = false
  eclipseGroupProductionEnabled.value = false
    eclipseScheduleEnabled.value = false
    eclipseWconHistEnabled.value = false
    eclipseWconInjeEnabled.value = false
    eclipseWconProdEnabled.value = false
    eclipseCompletionEnabled.value = false
    eclipseCompletionFactorEnabled.value = false
  }
  if (isNetworkTask.value && !hasActiveRun.value) {
    activeTab.value = resultTabForRunType(runType.value)
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
  const feedback = beginFeedback()
  feedback.success('已载入方案参数，可修改后点击运行；不会修改历史记录')
}
const buildWellTubingDiameterParameters = () => {
  const tubing = wellTubingRecord.value
  if (!wellTubingEditAvailable.value || !tubing || !Number.isSafeInteger(Number(wellTubingBaselineRunId.value)) ||
    Number(wellTubingBaselineRunId.value) <= 0 || !Number.isFinite(wellTubingTargetInnerDiameter.value) ||
    wellTubingTargetInnerDiameter.value <= 0 || wellTubingTargetInnerDiameter.value > 1000 ||
    Math.abs(wellTubingTargetInnerDiameter.value - tubing.innerDiameter) <= Math.max(1e-12, Math.abs(tubing.innerDiameter) * 1e-12)) {
    return { error: '油管内径方案必须选择同 Study 的成功无参数 Profile 基线，并输入与检查原值不同的正数目标值' }
  }
  return {
    schemaVersion: 'pipesim-well-tubing-diameter-parameters/1',
    baselineRunId: Number(wellTubingBaselineRunId.value),
    wellContext: selectedWellContext.value,
    tubingContext: tubing.context,
    originalInnerDiameter: tubing.innerDiameter,
    unit: tubing.unit,
    targetInnerDiameter: Number(wellTubingTargetInnerDiameter.value)
  }
}
const submitRun = async (scenarioOverride = null) => {
  const feedback = beginFeedback()
  const eclipseThermalScenario = ['eclipse-thermal-injection-parameters/1', 'eclipse-thermal-injection-parameters/2', 'eclipse-thermal-injection-parameters/3', 'eclipse-thermal-injection-plan-parameters/1', 'eclipse-thermal-rock-parameters/1'].includes(scenarioOverride?.schemaVersion)
  const eclipseCompositionalScenario = ['eclipse-compositional-production-parameters/1', 'eclipse-compositional-pressure-parameters/1', 'eclipse-compositional-reinjection-parameters/1', 'eclipse-compositional-gas-sales-parameters/1', 'eclipse-compositional-injection-pressure-parameters/1', 'eclipse-compositional-fluid-property-parameters/1', 'eclipse-compositional-temperature-parameters/1', 'eclipse-compositional-critical-z-parameters/1', 'eclipse-compositional-acentric-factor-parameters/1', 'eclipse-compositional-eos-property-parameters/1', 'eclipse-compositional-forecast-parameters/1'].includes(scenarioOverride?.schemaVersion)
  const eclipseScenarioSourceId = scenarioOverride?.schemaVersion === 'eclipse-compositional-forecast-parameters/1' ? scenarioOverride.historyRunId : scenarioOverride?.baselineRunId
  const eclipseGridScenario = ['eclipse-grid-field-parameters/1', 'eclipse-grid-region-parameters/1'].includes(scenarioOverride?.schemaVersion)
  const eclipseGeometryScenario = scenarioOverride?.schemaVersion === 'eclipse-completion-geometry-parameters/1'
  const eclipseNewWellScenario = scenarioOverride?.schemaVersion === 'eclipse-new-well-parameters/1'
  const eclipseGasPvtScenario = ['eclipse-multi-pvtg-parameters/1', 'eclipse-pvtg-parameters/1', 'eclipse-pvdg-parameters/1', 'eclipse-pvdg-parameters/2'].includes(scenarioOverride?.schemaVersion)
  const eclipseRelpermScenario = ['eclipse-relperm-parameters/1','eclipse-relperm-parameters/2','eclipse-coupled-relperm-parameters/1','eclipse-multi-sat-relperm-parameters/1','eclipse-multi-sat-relperm-parameters/2'].includes(scenarioOverride?.schemaVersion)
  const eclipseOilPvtScenario = ['eclipse-multi-pvto-parameters/1', 'eclipse-multi-pvt-parameters/1', 'eclipse-pvto-parameters/1', 'eclipse-pvdo-parameters/1', 'eclipse-pvdo-parameters/2'].includes(scenarioOverride?.schemaVersion)
  const eclipsePvtwScenario = ['eclipse-pvtw-parameters/1','eclipse-pvtw-parameters/2','eclipse-multi-pvtw-parameters/1','eclipse-multi-pvtw-parameters/2','eclipse-multi-pvtw-parameters/3'].includes(scenarioOverride?.schemaVersion)
  const eclipseNewCompletionScenario = scenarioOverride?.schemaVersion === 'eclipse-new-completion-parameters/1'
  const networkConnectionScenario = scenarioOverride?.schemaVersion === 'pipesim-network-connection-parameters/1'
  if (networkConnectionScenario && runType.value !== 'network') runType.value = 'network'
  if (!canRun.value) return
  if (scenarioOverride && !eclipseGridScenario && !eclipseGeometryScenario && !eclipseNewWellScenario && !eclipseNewCompletionScenario && !eclipsePvtwScenario && !eclipseOilPvtScenario && !eclipseGasPvtScenario && !eclipseRelpermScenario && !eclipseCompositionalScenario && !eclipseThermalScenario && !networkConnectionScenario) {
    feedback.error('当前结果页提交了不支持的受控方案类型。')
    return
  }
  if ((eclipseGridScenario || eclipseGeometryScenario || eclipseNewWellScenario || eclipseNewCompletionScenario || eclipsePvtwScenario || eclipseOilPvtScenario || eclipseGasPvtScenario || eclipseRelpermScenario || eclipseCompositionalScenario || eclipseThermalScenario) && (!isEclipseModel.value || Number(eclipseScenarioSourceId) !== Number(selectedRun.value?.id)
    || selectedRun.value?.status !== 'SUCCEEDED' || selectedRun.value?.resultContract !== 'VALID_FULL'
    || selectedRun.value?.parameters != null)) {
    feedback.error('受控重算仅能绑定当前选中的完整成功无参数 ECLIPSE 基线。')
    return
  }
  if (eclipseThermalScenario && (activeVersion.value?.modelKind !== 'eclipse_300' || !['eclipse-data-inspection/15', 'eclipse-data-inspection/23'].includes(activeVersion.value?.inspection?.schemaVersion))) { feedback.error('注汽参数仅适用于当前已核准的E300热采模型。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-temperature-parameters/1' && !['eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的TCRIT临界温度。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-eos-property-parameters/1' && activeVersion.value?.inspection?.schemaVersion !== 'eclipse-data-inspection/22') { feedback.error('请重新验证模型以启用已核准的分子量和状态方程系数。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-acentric-factor-parameters/1' && !['eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的ACF偏心因子。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-critical-z-parameters/1' && !['eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的ZCRIT临界Z因子。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-reinjection-parameters/1' && !['eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的可用气回注比例。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-gas-sales-parameters/1' && !['eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的固定销售目标。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-injection-pressure-parameters/1' && !['eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的注气井BHP上限。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-forecast-parameters/1' && !['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请从已核准原模型的完整历史保存点创建续算。'); return }
  if (scenarioOverride?.schemaVersion === 'eclipse-compositional-fluid-property-parameters/1' && !['eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion)) { feedback.error('请重新验证模型以启用已核准的PCRIT组分物性。'); return }
  if (eclipseCompositionalScenario && (activeVersion.value?.modelKind !== 'eclipse_300' || !['eclipse-data-inspection/14', 'eclipse-data-inspection/16', 'eclipse-data-inspection/17', 'eclipse-data-inspection/19', 'eclipse-data-inspection/20', 'eclipse-data-inspection/21', 'eclipse-data-inspection/22'].includes(activeVersion.value?.inspection?.schemaVersion))) {
    feedback.error('产气目标编辑仅适用于当前已核准的E300组分模型。'); return
  }
  if (networkConnectionScenario && (!isNetworkWorkflow.value || runType.value !== 'network' ||
    Number(scenarioOverride.baselineRunId) !== Number(selectedRun.value?.id) || selectedRun.value?.runType !== 'network' ||
    selectedRun.value?.status !== 'SUCCEEDED' || selectedRun.value?.resultContract !== 'VALID_FULL' ||
    selectedRun.value?.parameters != null || !validNetworkResult.value)) {
    feedback.error('连接重算仅能绑定当前选中的完整成功无参数 PIPESIM Network 基线。')
    return
  }
  if (eclipseGridScenario || eclipseGeometryScenario || eclipseNewWellScenario || eclipseNewCompletionScenario || eclipsePvtwScenario || eclipseOilPvtScenario || eclipseGasPvtScenario || eclipseRelpermScenario) disableOtherEclipseScenarios()
  if (pressureScenarioDraft.value.contextError) {
    feedback.error(pressureScenarioDraft.value.contextError)
    return
  }
  if (pressureScenarioDraft.value.fieldError) {
    pressureFieldTouched.value = true
    return
  }
  if (legacyProfileRun.value && (!Number.isFinite(profileOutletPressure.value) || profileOutletPressure.value <= 0 || profileOutletPressure.value > 100000)) {
    feedback.error('PT 剖面出口压力必须大于 0 且不超过 100000 psia')
    return
  }
  let parameters = scenarioOverride
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupInjectionEnabled.value) {
    parameters = buildGroupInjection({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseInjectionGroup.value, targetRate: Number(eclipseInjectionGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有注水组和同版本基线，新总注入目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupGasInjectionEnabled.value) {
    parameters = buildGroupGasInjection({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseGasInjectionGroup.value, targetRate: Number(eclipseGasInjectionGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有注气组和同版本基线，新总注入目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseControlPlanEnabled.value) {
    parameters = buildControlPlan({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), edits: eclipseControlPlanDraft.value })
    if (!parameters) { feedback.error('请选择同版本成功基线，并修改 1–24 条已有产油目标；新值必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseWaterInjectionPlanEnabled.value) {
    parameters = buildWaterInjectionPlan({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), edits: eclipseWaterInjectionPlanDraft.value })
    if (!parameters) { feedback.error('请选择同版本成功基线，并修改 1–24 条已有注水目标；新值必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupStructureEnabled.value) {
    parameters = buildGroupStructure({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), controlKey: eclipseGroupStructureControl.value, targetValue: Number(eclipseGroupStructureTarget.value) })
    if (!parameters) { feedback.error('请选择已有组树操作和同版本基线，新值必须为不同于原值的正数；开工比例不能超过 1'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseAdvancedInjectionEnabled.value) {
    parameters = buildAdvancedInjection({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseAdvancedInjectionGroup.value, targetRate: Number(eclipseAdvancedInjectionGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有生产组和同版本基线，新注入目标或比例目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupWaterProductionEnabled.value) {
    parameters = buildGroupWaterProduction({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseWaterProductionGroup.value, targetRate: Number(eclipseWaterProductionGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有生产组和同版本基线，新总产水目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupRateEnabled.value) {
    parameters = buildGroupRate({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseRateGroup.value, targetRate: Number(eclipseRateGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有生产组和同版本基线，新总产气/总产液目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseGroupProductionEnabled.value) {
    parameters = buildGroupProduction({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), group: eclipseProductionGroup.value, targetRate: Number(eclipseGroupTarget.value) })
    if (!parameters) { feedback.error('请选择已有生产组和同版本基线，新总产油目标必须为不同于原值的正数'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseTstepEnabled.value) {
    parameters = buildTstep({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), lineNumber: eclipseTstepLine.value, stepsText: eclipseTstepText.value })
    if (!parameters) { feedback.error('请填写不同于原值的正整数天数列表，总时长必须保持不变（最多1000项）'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseNewDatesEnabled.value) {
    parameters = buildNewDates({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseNewDatesWell.value, beforeLineNumber: eclipseNewDatesWindowKey.value, shutDate: eclipseNewShutDate.value, openDate: eclipseNewOpenDate.value })
    if (!parameters) { feedback.error('停井、复产日期必须递增，并严格位于所选两个已有日期之间'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseThpEnabled.value) {
    parameters = buildThpControl({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseThpWell.value, targetThp: Number(eclipseThpTarget.value) })
    if (!parameters) { feedback.error('请选择同版本基线和有效 THP 井；新压力必须不同于原目标并位于 VFP 表范围内'); return }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipseRateControlEnabled.value) {
    parameters = buildProductionControl({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseRateControlWell.value, targetRate: Number(eclipseRateControlTarget.value) })
    if (!parameters) {
      feedback.warning('请选择有效基线、初始开井记录，并填写不同于原值的正数生产目标')
      return
    }
  }
  if (!scenarioOverride && isEclipseModel.value && eclipsePressureEnabled.value) {
    parameters = buildPressureControl({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), well: eclipsePressureWell.value, targetBhp: Number(eclipsePressureTarget.value) })
    if (!parameters) { feedback.error('请选择有效开井记录和成功基线；BHP 必须在 0–100000 psia 内，已有 BHP 控制须修改原压力'); return }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseForecastEnabled.value) {
    if (eclipseForecastRequiresCatalog.value) {
      parameters = buildRestartForecast({ source: eclipseForecastSource.value, history: eclipseForecastHistoryDetail.value, inspection: activeVersion.value?.inspection,
        forecastDataFile: eclipseForecastDataFile.value, restartReport: Number(eclipseForecastRestartReport.value) })
      if (!parameters) { feedback.error('请等待历史目录核对完成，并选择同源物理模型及有效截止日期的预测方案'); return }
    } else {
      parameters = { schemaVersion: 'eclipse-history-forecast-parameters/1', historyRunId: Number(eclipseForecastHistoryRunId.value), forecastDataFile: eclipseForecastDataFile.value, restartArtifactName: eclipseForecastRestartArtifactName.value, restartArtifactSha256: eclipseForecastRestartArtifacts.value.find(artifact => artifact.name === eclipseForecastRestartArtifactName.value)?.sha256?.toLowerCase(), restartReport: Number(eclipseForecastRestartReport.value) }
    }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseCompletionFactorEnabled.value) {
    const completion = eclipseCompletionFactorRecord.value
    parameters = { schemaVersion: 'eclipse-completion-factor-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: completion?.well || '', sourceFile: completion?.sourceFile || '', lineNumber: Number(completion?.lineNumber), i: completion?.i || '', j: completion?.j || '', k1: completion?.k1 || '', k2: completion?.k2 || '', originalConnectionFactor: Number(completion?.originalConnectionFactor), targetConnectionFactor: Number(eclipseCompletionFactorTarget.value) }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseCompletionEnabled.value) {
    const completion = eclipseCompletionRecord.value
    parameters = { schemaVersion: 'eclipse-completion-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: completion?.well || '', sourceFile: completion?.sourceFile || '', lineNumber: Number(completion?.lineNumber), i: completion?.i || '', j: completion?.j || '', k1: completion?.k1 || '', k2: completion?.k2 || '', status: eclipseCompletionStatus.value }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseMultiScheduleEnabled.value) {
    const draft = (eclipseMultiDateMode.value ? buildEclipseMultiDateEvents : buildEclipseWellEvents)({
      baselineRunId: Number(eclipseBaselineRunId.value), wells: eclipseMultiScheduleSelectedWells.value,
      events: eclipseMultiDateEvents.value,
      shutDate: eclipseMultiScheduleShutDate.value, openDate: eclipseMultiScheduleOpenDate.value,
      knownWells: eclipseMultiScheduleWells.value, knownDates: eclipseMultiScheduleDates.value
    })
    if (draft.error) { feedback.error(draft.error); return }
    parameters = draft.parameters
  } else if (!scenarioOverride && isEclipseModel.value && eclipseWconProdEnabled.value) {
    if (eclipseProductionTargetMode.value) {
      parameters = buildProductionTarget({ inspection: activeVersion.value?.inspection, baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseWconProdWell.value, targetOilRate: Number(eclipseWconProdTargetOilRate.value) })
      if (!parameters) { feedback.error('请选择有效初始开井记录、成功基线和不同于原值的正数产油目标'); return }
    } else {
      parameters = { schemaVersion: 'eclipse-schedule-parameters/4', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseWconProdWell.value, phase: 'FORECAST_INITIAL', status: eclipseWconProdStatus.value, controlMode: 'ORAT', targetOilRate: Number(eclipseWconProdTargetOilRate.value) }
    }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseWconInjeEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/3', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseWconInjeWell.value, date: eclipseWconInjeDate.value, injectionType: eclipseWconInjeFluid.value, controlMode: 'RATE', targetInjectionRate: Number(eclipseWconInjeTargetRate.value) }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseWconHistEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/2', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseScheduleWell.value, date: eclipseScheduleDate.value, status: eclipseScheduleStatus.value, controlMode: 'ORAT', targetOilRate: Number(eclipseWconHistTargetOilRate.value) }
  } else if (!scenarioOverride && isEclipseModel.value && eclipseScheduleEnabled.value) {
    parameters = { schemaVersion: 'eclipse-schedule-parameters/1', baselineRunId: Number(eclipseBaselineRunId.value), well: eclipseScheduleWell.value, date: eclipseScheduleDate.value, status: eclipseScheduleStatus.value }
  } else if (isGasLiftPerformanceRun.value) {
    parameters = buildGasLiftPerformanceParameters()
  } else if (isGasLiftDiagnosticsRun.value) {
    parameters = buildGasLiftDiagnosticsParameters()
  } else if (isVfpTablesRun.value) {
    parameters = buildVfpTablesParameters()
  } else if (isEspParameterRun.value) {
    const context = String(espContext.value || '').trim()
    const maximum = espParameter.value === 'headFactor' ? 2 : 200
    const wellContext = String(selectedWellContext.value || '').trim()
    if (!availableWellContexts.value.some(item => item.context === wellContext && item.espContexts.includes(context)) || context.length > 255 || context.includes('..') || /[\\/\u0000]/.test(context) || !Number.isFinite(espTargetValue.value) || espTargetValue.value <= 0 || espTargetValue.value > maximum) {
      feedback.error(espParameter.value === 'headFactor' ? 'ESP Head Factor 目标值必须大于 0 且不超过平台保护上限 2.0' : 'ESP 运行频率必须大于 0 且不超过 200 Hz')
      return
    }
    if (['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion.value?.inspection?.schemaVersion)) {
      parameters = { schemaVersion: 'pipesim-esp-parameter-parameters/3', wellContext, espContext: context, parameter: espParameter.value, targetValue: espTargetValue.value }
    } else {
      parameters = espParameter.value === 'headFactor'
        ? { schemaVersion: 'pipesim-esp-parameter-parameters/2', espContext: context, parameter: 'headFactor', targetValue: espTargetValue.value }
        : { schemaVersion: 'pipesim-esp-parameter-parameters/1', espContext: context, parameter: 'operatingFrequency', targetValueHz: espTargetValue.value }
    }
  } else if (runType.value === 'esp-curves') {
    if (!selectedWellContext.value || !availableEspContexts.value.includes(espContext.value)) {
      feedback.error('ESP 曲线必须选择本模型检查清单中对应的一口井和一台 ESP')
      return
    }
    parameters = ['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion.value?.inspection?.schemaVersion)
      ? { schemaVersion: 'pipesim-esp-curves-parameters/2', wellContext: selectedWellContext.value, espContext: espContext.value }
      : { schemaVersion: 'pipesim-esp-curves-parameters/1' }
  } else if (runType.value === 'trajectory') {
    if (!availableWellContexts.value.some(item => item.context === selectedWellContext.value)) {
      feedback.error('井轨迹必须选择本模型检查清单中存在的井')
      return
    }
    if (trajectoryEditEnabled.value) {
      if (!trajectoryEditSupportedInspection.value || !Number.isInteger(trajectoryStationIndex.value) || trajectoryStationIndex.value < 1 || trajectoryStationIndex.value > 4094 ||
        !Number.isFinite(trajectoryTargetInclination.value) || trajectoryTargetInclination.value < 0 || trajectoryTargetInclination.value > 90) {
        feedback.error('轨迹方案仅支持已绑定井上下文的检查版本；请输入 1–4094 的内部站点序号和 0–90° 倾角')
        return
      }
      parameters = { schemaVersion: 'pipesim-well-trajectory-parameters/3', wellContext: selectedWellContext.value, stationIndex: trajectoryStationIndex.value, targetInclinationDeg: trajectoryTargetInclination.value }
    } else {
      parameters = trajectoryEditSupportedInspection.value
        ? { schemaVersion: 'pipesim-well-trajectory-parameters/2', wellContext: selectedWellContext.value }
        : { schemaVersion: 'pipesim-well-trajectory-parameters/1' }
    }
  } else if (!scenarioOverride && runType.value === 'profile' && wellTubingEditEnabled.value) {
    if (scenarioEnabled.value) {
      feedback.error('单根油管内径修改不能与地层压力方案叠加；请关闭压力方案后再运行')
      return
    }
    parameters = buildWellTubingDiameterParameters()
    if (parameters?.error) {
      feedback.error(parameters.error)
      return
    }
  } else if (legacyProfileRun.value) {
    parameters = { schemaVersion: 'pipesim-well-profile-parameters/1', outletPressurePsi: profileOutletPressure.value }
  } else if (scenarioEnabled.value) {
    parameters = { ...pressureScenarioDraft.value.parameters }
  }
  if ((isGasLiftPerformanceRun.value || isGasLiftDiagnosticsRun.value || isVfpTablesRun.value) && parameters?.error) {
    feedback.error(parameters.error)
    return
  }
  if (isEclipseModel.value && (eclipseScheduleEnabled.value || eclipseWconHistEnabled.value) &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 || !eclipseScheduleWells.value.includes(parameters.well) ||
      !eclipseScheduleDates.value.includes(parameters.date) || !['OPEN', 'SHUT'].includes(parameters.status) ||
      (eclipseWconHistEnabled.value && (!eclipseWconHistAvailable.value || parameters.controlMode !== 'ORAT' || !Number.isFinite(parameters.targetOilRate) || parameters.targetOilRate <= 0 || parameters.targetOilRate > 100000000)))) {
    feedback.error(eclipseWconHistEnabled.value ? 'WCONHIST 方案必须选择已有成功基线、井、DATES 日期、OPEN/SHUT 状态和正数 ORAT 目标值' : '调度方案必须选择已有成功基线、井、DATES 日期和 OPEN/SHUT 状态')
    return
  }
  if (isEclipseModel.value && eclipseWconInjeEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 ||
      !eclipseWconInjeWells.value.includes(parameters.well) || !eclipseScheduleDates.value.includes(parameters.date) ||
      parameters.injectionType !== eclipseWconInjeFluid.value || parameters.controlMode !== 'RATE' ||
      !Number.isFinite(parameters.targetInjectionRate) || parameters.targetInjectionRate <= 0 || parameters.targetInjectionRate > 100000000)) {
    feedback.error('WCONINJE 方案必须选择已有成功基线、已有注入井、DATES 日期和正数 RATE 注入速率')
    return
  }
  if (isEclipseModel.value && eclipseWconProdEnabled.value && !eclipseProductionTargetMode.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 ||
      !eclipseWconProdAvailable.value || !eclipseWconProdWells.value.includes(parameters.well) ||
      parameters.phase !== 'FORECAST_INITIAL' || !['OPEN', 'SHUT'].includes(parameters.status) || parameters.controlMode !== 'ORAT' ||
      !Number.isFinite(parameters.targetOilRate) || parameters.targetOilRate <= 0 || parameters.targetOilRate > 100000000)) {
    feedback.error('WCONPROD 方案必须选择已有成功基线、预测初始段已有 ORAT 井、OPEN/SHUT 状态和正数 ORAT 目标值')
    return
  }
  if (isEclipseModel.value && eclipseCompletionEnabled.value &&
    (!Number.isSafeInteger(parameters.baselineRunId) || parameters.baselineRunId <= 0 || !eclipseCompletionAvailable.value ||
      !eclipseCompletionRecord.value || parameters.well !== eclipseCompletionRecord.value.well ||
      parameters.sourceFile !== eclipseCompletionRecord.value.sourceFile || parameters.lineNumber !== Number(eclipseCompletionRecord.value.lineNumber) ||
      parameters.i !== eclipseCompletionRecord.value.i || parameters.j !== eclipseCompletionRecord.value.j ||
      parameters.k1 !== eclipseCompletionRecord.value.k1 || parameters.k2 !== eclipseCompletionRecord.value.k2 ||
      !['OPEN', 'SHUT'].includes(parameters.status) || parameters.status === eclipseCompletionRecord.value.status)) {
    feedback.error('COMPDAT 完井方案必须选择已有成功基线、已有 COMPDAT 完井段和与原状态不同的 OPEN/SHUT 状态')
    return
  }
  if (isEclipseModel.value && eclipseMultiScheduleEnabled.value &&
    (!eclipseMultiScheduleAvailable.value || !(eclipseMultiDateMode.value ? validateEclipseMultiDateEvents : validateEclipseWellEvents)(parameters, eclipseMultiScheduleWells.value, eclipseMultiScheduleDates.value))) {
    feedback.error('井组方案需选择同版本成功基线、1–8 口已有生产井，以及先关闭后重开的两个日期')
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
    feedback.error('COMPDAT 连接因子方案必须选择已有成功基线、已有数值连接因子完井段，并输入与原值不同的正数目标值')
    return
  }
  if (isEclipseModel.value && eclipseForecastEnabled.value &&
    (!Number.isSafeInteger(parameters.historyRunId) || parameters.historyRunId <= 0 ||
      !eclipseForecastDataFiles.value.includes(parameters.forecastDataFile) ||
      !eclipseForecastRestartArtifacts.value.some(artifact => artifact.name === parameters.restartArtifactName && artifact.sha256?.toLowerCase() === parameters.restartArtifactSha256) ||
      !Number.isSafeInteger(parameters.restartReport) || parameters.restartReport <= 0)) {
    feedback.error(eclipseForecastRequiresCatalog.value ? '预测运行必须选择当前版本的预测 DATA、成功历史运行、FUNRST / UNRST 状态文件和正整数重启报告' : '预测运行必须选择当前版本的预测 DATA、成功历史运行、FUNRST Artifact 和正整数重启报告')
    return
  }
  if (!scenarioOverride && isNetworkWellPerformanceRun.value) {
    const wellPerformanceParameters = buildNetworkWellPerformanceParameters()
    if (wellPerformanceParameters?.error) {
      feedback.error(wellPerformanceParameters.error)
      return
    }
    parameters = wellPerformanceParameters
  } else if (!scenarioOverride && isNetworkOptimizerRun.value) {
    parameters = {
      schemaVersion: networkOptimizerApplyResults.value
        ? 'pipesim-network-optimizer-parameters/2'
        : 'pipesim-network-optimizer-parameters/1',
      applyResults: networkOptimizerApplyResults.value
    }
  } else if (!scenarioOverride && isSystemAnalysisRun.value) {
    const systemAnalysis = buildSystemAnalysisParameters(systemAnalysisDraft.value)
    systemAnalysisErrors.value = systemAnalysis.errors
    if (!systemAnalysis.parameters) {
      feedback.error(systemAnalysis.summary)
      return
    }
    parameters = systemAnalysis.parameters
  } else if (!scenarioOverride && isNetworkTask.value && !isNetworkWellPerformanceRun.value) {
    if (networkFlowlineDiameterEnabled.value) {
      const flowlineParameters = buildNetworkFlowlineDiameterParameters()
      if (flowlineParameters?.error) {
        feedback.error(flowlineParameters.error)
        return
      }
      parameters = flowlineParameters
    } else if (networkChokeEnabled.value) {
      const networkChokeParameters = buildNetworkChokeParameters()
      if (networkChokeParameters?.error) {
        feedback.error(networkChokeParameters.error)
        return
      }
      parameters = networkChokeParameters
    } else {
      const networkParameters = buildNetworkParameters()
      if (networkParameters?.error) {
        feedback.error(networkParameters.error)
        return
      }
      parameters = networkParameters
    }
  }
  if (!scenarioOverride && isSensitivityRun.value) {
    const values = sensitivityValuesText.value.split(/[,，\s]+/).filter(Boolean).map(Number)
    if (!['reservoirPressure', 'waterCut', 'gor', 'tubingInnerDiameter'].includes(sensitivityVariable.value) || values.length < 2 || values.length > 12 ||
      values.some(value => !Number.isFinite(value) || value <= 0) || values.some((value, index) => index > 0 && value <= values[index - 1])) {
      feedback.error('敏感性分析需要 2 到 12 个严格递增的正数值')
      return
    }
    const max = sensitivityVariable.value === 'reservoirPressure' ? 100000 : (sensitivityVariable.value === 'waterCut' ? 100 : (sensitivityVariable.value === 'gor' ? 1000000 : 100))
    if (values.some(value => value > max) || (activeVersion.value?.modelKind === 'basic_gas' && ['waterCut', 'gor'].includes(sensitivityVariable.value))) {
      feedback.error('敏感性变量或取值范围不适用于当前模型')
      return
    }
    parameters = { schemaVersion: 'pipesim-well-sensitivity-parameters/1', targetVariable: sensitivityVariable.value, values }
  }
  try {
    const detail = await store.createRun(parameters)
    if (!detail) return
    activeTab.value = resultTabForRunType(runType.value)
    feedback.success('运行任务已创建')
  } catch (error) {
    feedback.error(isEclipseModel.value
      ? eclipseRequestErrorMessage(error, '创建 ECLIPSE 运行失败，请稍后重试')
      : errorMessage(error))
  }
}
const cancelRun = async () => {
  const feedback = beginFeedback()
  try {
    await store.cancelRun()
    feedback.success('取消请求已提交')
  } catch (error) {
    feedback.error(errorMessage(error))
  }
}
const canRetryRun = computed(() => Boolean(displayRun.value &&
  ['TIMED_OUT', 'WORKER_LOST'].includes(displayRun.value.status) ||
  (displayRun.value?.status === 'FAILED' && safeRunError.value?.retryable)))
const retryRun = async () => {
  if (!canRetryRun.value || retryingRun.value) return
  const feedback = beginFeedback()
  retryingRun.value = true
  try {
    const detail = await store.retryRun()
    if (detail) {
      activeTab.value = resultTabForRunType(detail.runType)
      feedback.success('已基于原运行参数重新提交，原运行记录保留不变')
    }
  } catch (error) {
    feedback.error(isEclipseModel.value
      ? eclipseRequestErrorMessage(error, '重新提交 ECLIPSE 运行失败，请稍后重试')
      : errorMessage(error))
  } finally {
    retryingRun.value = false
  }
}
const downloadArtifact = async artifact => {
  const runId = displayRun.value?.id
  if (!runId || !artifact?.downloadable || artifactDownloadPending.value) return
  const feedback = beginFeedback()
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
    feedback.error('Artifact 下载失败，请检查运行记录和有效期。')
  } finally {
    artifactDownloadPending.value = null
  }
}
const selectHistoryRun = async runId => {
  const feedback = beginFeedback()
  try {
    const detail = await store.selectRun(runId)
    if (!detail) return
    if (detail.runType === 'network-well-performance-curves') activeTab.value = 'network-well-performance-curves'
    else if (detail.runType === 'network-optimizer') activeTab.value = 'network-optimizer'
    else if (detail.runType === 'system-analysis') activeTab.value = 'system-analysis'
    else if (detail.runType === 'network') activeTab.value = 'network'
    else if (detail.runType === 'eclipse') activeTab.value = 'eclipse'
    else if (detail.runType === 'profile') activeTab.value = 'profile'
    else if (detail.runType === 'sensitivity') activeTab.value = 'sensitivity'
    else if (detail.runType === 'gas-lift-performance') activeTab.value = 'gas-lift-performance'
    else if (detail.runType === 'gas-lift-diagnostics') activeTab.value = 'gas-lift-diagnostics'
    else if (detail.runType === 'vfp-tables') activeTab.value = 'vfp-tables'
    else if (detail.runType === 'esp-curves') activeTab.value = 'esp-curves'
    else if (detail.runType === 'esp-parameter') activeTab.value = 'esp-parameter'
    else if (detail.runType === 'trajectory') activeTab.value = 'trajectory'
  } catch (error) {
    const historyRun = runHistory.value.find(run => run.id === runId)
    feedback.error(historyRun?.runType === 'eclipse'
      ? eclipseRequestErrorMessage(error, '加载 ECLIPSE 运行记录失败，请稍后重试')
      : errorMessage(error))
  }
}
const selectHistoricalSuccessfulNetworkRun = () => {
  if (historicalSuccessfulNetworkRun.value) selectHistoryRun(historicalSuccessfulNetworkRun.value.id)
  else activeTab.value = 'history'
}
const refreshRunManually = async () => {
  const runId = displayRun.value?.id
  if (!runId || manualRefreshing.value) return
  const feedback = beginFeedback()
  manualRefreshing.value = true
  try {
    await store.selectRun(runId)
    // Reload the persisted summary so a newly terminal run clears the stale active run.
    await store.loadRunHistory(activeVersionId.value, false)
    feedback.success('运行状态已刷新')
  } catch (error) {
    feedback.error(errorMessage(error))
  } finally {
    manualRefreshing.value = false
  }
}

watch(() => selectedRun.value?.id, () => {
  if (selectedRun.value?.runType === 'network-well-performance-curves') activeTab.value = 'network-well-performance-curves'
  else if (selectedRun.value?.runType === 'network-optimizer') activeTab.value = 'network-optimizer'
  else if (selectedRun.value?.runType === 'system-analysis') activeTab.value = 'system-analysis'
  else if (selectedRun.value?.runType === 'network') activeTab.value = 'network'
  else if (selectedRun.value?.runType === 'eclipse') activeTab.value = 'eclipse'
  else if (selectedRun.value?.runType === 'profile') activeTab.value = 'profile'
  else if (selectedRun.value?.runType === 'sensitivity') activeTab.value = 'sensitivity'
  else if (selectedRun.value?.runType === 'gas-lift-performance') activeTab.value = 'gas-lift-performance'
  else if (selectedRun.value?.runType === 'gas-lift-diagnostics') activeTab.value = 'gas-lift-diagnostics'
  else if (selectedRun.value?.runType === 'vfp-tables') activeTab.value = 'vfp-tables'
  else if (selectedRun.value?.runType === 'esp-curves') activeTab.value = 'esp-curves'
  else if (selectedRun.value?.runType === 'esp-parameter') activeTab.value = 'esp-parameter'
  else if (selectedRun.value?.runType === 'trajectory') activeTab.value = 'trajectory'
  else if (activeTab.value === 'profile' && selectedRun.value?.runType === 'nodal') activeTab.value = 'nodal'
})
watch([isNetworkModel, isWellModel, isEclipseModel, isNetworkCapable], ([networkModel, wellModel, eclipseModel, networkCapable]) => {
  if (networkModel) {
    if (!['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'].includes(runType.value)) runType.value = 'network'
    activeTab.value = resultTabForRunType(runType.value)
    return
  }
  if (eclipseModel) {
    runType.value = 'eclipse'
    selectedStudy.value = null
    activeTab.value = 'eclipse'
    return
  }
  if (wellModel && isNetworkTask.value && !networkCapable) runType.value = 'nodal'
  else if (!wellModel) runType.value = ''
  if (!networkCapable && !networkModel && ['network', 'system-analysis', 'network-optimizer', 'network-well-performance-curves'].includes(activeTab.value)) activeTab.value = selectedRun.value?.runType === 'profile' ? 'profile' : 'nodal'
}, { immediate: true })

defineExpose({ eclipseRunRequest })
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

    <section v-if="['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion)" class="well-scenario" aria-label="PIPESIM 井与 ESP 对象清单">
      <label>目标井
        <el-select v-model="selectedWellContext" :disabled="hasActiveRun || submittingRun" placeholder="多井模型请显式选择目标井" aria-label="目标井上下文">
          <el-option v-for="well in availableWellContexts" :key="well.context" :value="well.context" :label="well.context" />
        </el-select>
      </label>
      <details class="model-object-details">
        <summary>模型对象 · {{ availableWellContexts.length }} 口井</summary>
        <div v-for="well in availableWellContexts" :key="well.context" class="inline-notice">
          <span>{{ well.context }}</span>
          <span>{{ well.espContexts.length ? `ESP：${well.espContexts.join('、')}` : '未发现 ESP' }}</span>
          <span v-if="['pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion)">{{ well.completionContexts?.length ? `完井：${well.completionContexts.join('、')}` : '无完井（流动任务不可运行）' }}</span>
          <span v-if="['pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion)">{{ well.tubingContexts?.length ? `油管：${well.tubingContexts.join('、')}` : '无油管（流动任务不可运行）' }}</span>
        </div>
        <small>对象来自官方 PTK 按 Well 上下文过滤的只读检查；多井任务必须绑定清单中的目标井。井轨迹只需目标井，节点/剖面/ESP 等流动任务要求该井实际具有完井和油管，ESP 任务还要求该井下存在具体 ESP。</small>
      </details>
    </section>

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
        <small v-if="!runTypeOptions.length && activeVersion?.status === 'READY' && !runCapabilityMessage">当前 Study 或目标井没有可用任务，请检查模型对象或重新验证。</small>
      </div>

        <div v-if="!isEclipseRunPresentation || eclipsePresentationAvailable" class="control-actions">
          <el-button type="primary" :loading="submittingRun" :disabled="!canRun" @click="submitRun()">运行</el-button>
          <el-button type="danger" plain :loading="cancellingRun" :disabled="!activeRun?.cancellable" @click="cancelRun">取消</el-button>
          <el-button v-if="canRetryRun" type="warning" plain :loading="retryingRun" @click="retryRun">重新提交</el-button>
        </div>
      </div>

      <p v-if="primaryNotice" class="inline-notice run-feedback" :class="primaryNotice.type"
        :role="primaryNotice.type === 'danger' ? 'alert' : 'status'" aria-live="polite" aria-label="运行提示" data-testid="run-feedback">
        <span>{{ primaryNotice.message }}</span>
        <el-button v-if="primaryNotice.action === 'refresh'" link type="primary" :loading="manualRefreshing" @click="refreshRunManually">手动刷新</el-button>
        <el-button v-else-if="primaryNotice.action === 'history'" link type="primary" @click="selectHistoricalSuccessfulNetworkRun">{{ historicalSuccessfulNetworkRun ? '选择历史成功运行' : '查看运行记录' }}</el-button>
      </p>

      <p v-if="hasNetworkCapability" class="inline-notice success" data-testid="pipesim-network-capability-notice">
        此井模型还通过 PIPESIM Network Simulation 原生 Study 校验，已开放管网工作流：{{ networkCapabilityStudies.join('、') }}。这只是任务预检；是否收敛及结果有效性以实际运行结果为准。
      </p>

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
      <p v-if="isWellModel && activeVersion?.status === 'READY' && !['pipesim-well-inspection/4', 'pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion)" class="inline-notice warning">
        当前版本没有可验证的 ESP 上下文清单；ESP 专项任务已隐藏。请重新验证模型后再使用，不能默认选择模型中的第一台 ESP。
      </p>
      <p v-if="isWellModel && ['pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion) && availableWellContexts.length > 1" class="inline-notice warning">
        当前模型含 {{ availableWellContexts.length }} 口井。井轨迹、ESP 曲线和 ESP 参数方案可绑定目标对象；尚未绑定井上下文的节点/剖面等任务暂时拦截，不会默认计算第一口井。
      </p>
      <section v-if="supportsCurrentScenario" class="well-scenario" aria-label="井筒压力方案">
        <el-checkbox v-model="scenarioEnabled" :disabled="hasActiveRun || submittingRun">使用地层压力方案</el-checkbox>
        <template v-if="scenarioEnabled">
          <div class="pressure-field" role="group" aria-label="方案地层压力校验" :aria-invalid="Boolean(pressureFieldError)" :aria-describedby="pressureFieldError ? 'reservoir-pressure-error' : undefined">
            <label>地层压力 (psia) <el-input-number v-model="scenarioPressure" :min="0.000001" :max="100000" :disabled="hasActiveRun || submittingRun" aria-label="方案地层压力" @blur="pressureFieldTouched = true" /></label>
            <small v-if="pressureFieldError" id="reservoir-pressure-error" role="alert" data-testid="pressure-field-error">{{ pressureFieldError }}</small>
          </div>
          <span>仅修改本次计算副本；模型单位不匹配或设置失败时停止计算。</span>
          <span v-if="activeVersion?.modelKind === 'basic_gas' && runType !== 'nodal'">基础气井方案同时将该值用于 PT 入口压力，沿用桌面端计算方式。</span>
          <span v-if="sourcePressure !== null && Number.isFinite(scenarioPressure)" data-testid="pressure-scenario-comparison">原值 {{ sourcePressure }} → 方案值 {{ scenarioPressure }} psia</span>
        </template>
        <span v-else>使用上传模型的原始参数</span>
      </section>
      <section v-if="isWellModel && runType === 'profile' && ['pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(activeVersion?.inspection?.schemaVersion) && availableTubingComponents.length" class="well-scenario" aria-label="PIPESIM 单根油管内径持久方案" data-testid="well-tubing-diameter-scenario">
        <el-checkbox v-model="wellTubingEditEnabled" :disabled="hasActiveRun || submittingRun || !wellTubingBaselineOptions.length" aria-label="创建油管内径派生工程并重算 Profile">创建单根油管内径派生工程并重算 Profile</el-checkbox>
        <template v-if="wellTubingEditEnabled">
          <label>基线运行
            <el-select v-model="wellTubingBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="油管内径方案基线运行">
              <el-option v-for="run in wellTubingBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id} · ${run.study}`" />
            </el-select>
          </label>
          <label>目标井
            <el-select v-model="selectedWellContext" :disabled="hasActiveRun || submittingRun" aria-label="油管内径方案目标井">
              <el-option v-for="well in availableWellContexts" :key="well.context" :value="well.context" :label="well.context" />
            </el-select>
          </label>
          <label>油管组件
            <el-select v-model="wellTubingContext" :disabled="hasActiveRun || submittingRun" aria-label="目标油管组件">
              <el-option v-for="component in availableTubingComponents" :key="component.context" :value="component.context" :label="component.context" />
            </el-select>
          </label>
          <span>检查原值：{{ wellTubingRecord?.innerDiameter }} {{ wellTubingRecord?.unit }}</span>
          <label>目标内径
            <el-input-number v-model="wellTubingTargetInnerDiameter" :min="0.000001" :max="1000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="目标油管内径" />
            <span>{{ wellTubingRecord?.unit }}</span>
          </label>
          <small>使用 PIPESIM 官方 Model.set_value(Parameters.Tubing.INNERDIAMETER)。运行前绑定同一版本/Study 的成功无参数 Profile 基线；仅改隔离副本，保存、关闭重开并回读目标值，再运行原生 PT Profile。成功后提供派生 .pips 下载；上传原模型保持不变。0.95 倍仅作初始建议值，1000 为平台保护上限而非软件物理限值。</small>
        </template>
        <small v-if="!wellTubingBaselineOptions.length">请先运行一次同 Study 的无参数 PT 剖面，成功后才能建立对照基线。</small>
      </section>
      <section v-if="isSystemAnalysisRun" class="system-analysis-scenario system-analysis-inputs" aria-label="PIPESIM 系统分析工况">
        <strong>系统分析</strong>
        <label>井
          <el-select v-model="systemAnalysisProducer" :disabled="hasActiveRun || submittingRun" placeholder="选择实际井" aria-label="系统分析井">
            <el-option v-for="context in networkPerformanceProducers" :key="context" :value="context" :label="context" />
          </el-select>
          <small v-if="systemAnalysisErrors.producer" role="alert">{{ systemAnalysisErrors.producer }}</small>
        </label>
        <label>分支终点
          <el-select v-model="systemAnalysisBranchTerminator" :disabled="hasActiveRun || submittingRun" placeholder="选择 Flowline" aria-label="系统分析分支终点">
            <el-option v-for="context in networkPerformanceBranches" :key="context" :value="context" :label="context" />
          </el-select>
          <small v-if="systemAnalysisErrors.branchTerminator" role="alert">{{ systemAnalysisErrors.branchTerminator }}</small>
        </label>
        <label>出口压力 (psia)
          <el-input-number v-model="systemAnalysisOutletPressure" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="系统分析出口压力" />
          <small v-if="systemAnalysisErrors.outletPressurePsi" role="alert">{{ systemAnalysisErrors.outletPressurePsi }}</small>
        </label>
        <label>液量扫描 (STB/d)
          <el-input v-model="systemAnalysisValuesText" :disabled="hasActiveRun || submittingRun" placeholder="例如：2400, 3000, 3600" aria-label="系统分析液体流量扫描值" />
          <small v-if="systemAnalysisErrors.valuesText" role="alert">{{ systemAnalysisErrors.valuesText }}</small>
        </label>
        <small v-if="systemAnalysisErrors.study" role="alert">{{ systemAnalysisErrors.study }}</small>
        <small>按所选井和 Study 求解入口压力；输入 2–8 个液量。只运行隔离副本，原模型不变。示例轴是实验值，需按实际模型确认。</small>
      </section>
      <section v-if="isNetworkOptimizerRun" class="system-analysis-scenario" aria-label="PIPESIM 网络优化工况">
        <strong>Network Optimizer 工况</strong>
        <el-checkbox v-model="networkOptimizerApplyResults" :disabled="hasActiveRun || submittingRun">将优化结果应用到隔离方案副本</el-checkbox>
        <span>使用官方模型内置的优化目标、边界条件和控制变量执行一次真实优化计算。</span>
        <small v-if="networkOptimizerApplyResults">调用官方 apply_results()；只写入本次运行的隔离副本，并发布可下载的 pipesim-network-optimizer-applied.pips，不修改上传版本。</small>
        <small v-else>当前只读取并展示优化结果，不写回模型；结果中的 NaN 会按官方“不适用/无值”规范化为空值并标记。</small>
      </section>
      <section v-if="isNetworkWellPerformanceRun" class="system-analysis-scenario" aria-label="PIPESIM 井性能曲线工况" data-testid="network-well-performance-parameters">
        <strong>Well Performance Curves 工况</strong>
        <label>生产井
          <el-select v-model="networkPerformanceProducer" :disabled="hasActiveRun || submittingRun" placeholder="选择检查清单中的生产井" aria-label="井性能曲线生产井">
            <el-option v-for="context in networkPerformanceProducers" :key="context" :value="context" :label="context" />
          </el-select>
        </label>
        <label>BranchTerminator / Flowline
          <el-select v-model="networkPerformanceBranch" :disabled="hasActiveRun || submittingRun" placeholder="选择检查清单中的 Flowline" aria-label="井性能曲线 Flowline">
            <el-option v-for="context in networkPerformanceBranches" :key="context" :value="context" :label="context" />
          </el-select>
        </label>
        <label class="wide-field">Flowline 内径扫描值 (in)
          <el-input v-model="networkPerformanceValuesText" :disabled="hasActiveRun || submittingRun" placeholder="例如：4, 5, 6" aria-label="井性能曲线内径扫描值" />
        </label>
        <small>调用官方 Well Performance Curves Simulation；只接受模型实际单位为 in 的 Flowline，按 2–8 个严格递增内径计算。原工程不修改；结果包括 System/Node 数值和官方 PWI 文件。</small>
      </section>
      <section v-if="isNetworkWorkflow && isNetworkTask && !isSystemAnalysisRun && !isNetworkWellPerformanceRun" class="network-scenario" aria-label="管网边界条件方案">
        <strong>Network 边界条件方案</strong>
        <div v-if="networkFlowlineDiameterAvailable" class="network-choke-scenario" aria-label="管网 Flowline 内径工程方案">
          <el-checkbox v-model="networkFlowlineDiameterEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleNetworkFlowlineDiameter">创建 Flowline 内径派生工程并重算</el-checkbox>
          <template v-if="networkFlowlineDiameterEnabled">
            <label>基线运行
              <el-select v-model="networkFlowlineBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="Flowline 内径方案基线运行">
                <el-option v-for="run in networkChokeBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id} · ${run.study}`" />
              </el-select>
            </label>
            <label>Flowline
              <el-select v-model="networkFlowlineName" :disabled="hasActiveRun || submittingRun" aria-label="方案 Flowline">
                <el-option v-for="flowline in networkFlowlines" :key="flowline.name" :value="flowline.name" :label="flowline.name" />
              </el-select>
            </label>
            <span>检查原值：{{ networkFlowlineRecord?.innerDiameter }} {{ networkFlowlineRecord?.unit }}</span>
            <label>目标内径
              <el-input-number v-model="networkFlowlineTarget" :min="0.000001" :max="1000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="目标 Flowline 内径" />
              <span>{{ networkFlowlineRecord?.unit }}</span>
            </label>
            <small>仅对检查清单中已读取到内径和单位的对象开放。调用官方 Model.set_value(Flowline, InnerDiameter)，隔离副本保存、关闭重开和原值回读后，再验证并运行相同 Study；完整成功后提供派生 .pips 下载，上传版本不变。1000 为平台输入保护上限，不是 PIPESIM 物理限值。</small>
          </template>
        </div>
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
        <label>生产井<el-input :model-value="gasLiftProducer" disabled aria-label="气举生产井" /></label>
        <el-button :disabled="hasActiveRun || submittingRun || !gasLiftCapability.supported" @click="loadGasLiftExample">载入官方气举示例方案</el-button>
        <small>以下为本次参数方案，不是模型原值。示例数值对应 Gas Lift Performance 官方模型；其他模型请自行输入适用条件。</small>
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
        <label>生产井<el-input :model-value="vfpProducer" disabled aria-label="VFP 生产井" /></label>
        <label>表号<el-input-number v-model="vfpTableNumber" :min="1" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="VFP 表号" /></label>
        <label>井底基准深度<el-input-number v-model="vfpBottomHoleDatumDepth" :min="0" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="VFP 井底基准深度" /></label>
        <el-checkbox v-model="vfpIncludeTemperature" :disabled="hasActiveRun || submittingRun">返回温度表</el-checkbox>
        <label class="wide-field">液量轴 (STB/d)<el-input v-model="vfpLiquidRatesText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 液量轴" /></label>
        <label class="wide-field">出口压力轴 (psia)<el-input v-model="vfpOutletPressuresText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 出口压力轴" /></label>
        <label>含水率轴 (fraction)<el-input v-model="vfpWaterCutText" :disabled="hasActiveRun || submittingRun" aria-label="VFP 含水率轴" /></label>
        <label>GOR 轴 (MSCF/STB)<el-input v-model="vfpGorText" :disabled="hasActiveRun || submittingRun" aria-label="VFP GOR 轴" /></label>
        <label>ALQ 轴 (psia)<el-input v-model="vfpArtificialLiftText" :disabled="hasActiveRun || submittingRun" aria-label="VFP ALQ 轴" /></label>
        <small>生产井取自当前版本已验证的 PIPESIM 对象，不接受手工猜名；调用官方 VFP Tables Simulation 展示真实 BHP/温度表、维度轴和原始文本。当前只生成 ECLIPSE VFPPROD 表，不自动耦合进 ECLIPSE。</small>
      </section>
      <section v-if="isEspParameterRun" class="well-scenario" aria-label="PIPESIM ESP 单参数方案">
        <strong>ESP 单参数方案</strong>
        <label>目标井
          <el-select v-model="selectedWellContext" :disabled="hasActiveRun || submittingRun" placeholder="先选择目标井" aria-label="ESP 目标井">
            <el-option v-for="well in availableWellContexts" :key="well.context" :value="well.context" :label="well.context" />
          </el-select>
        </label>
        <label>ESP 上下文
          <el-select v-model="espContext" :disabled="hasActiveRun || submittingRun" placeholder="选择模型中已检查到的 ESP" aria-label="ESP 上下文">
            <el-option v-for="context in availableEspContexts" :key="context" :value="context" :label="context" />
          </el-select>
        </label>
        <label>方案参数
          <el-select v-model="espParameter" :disabled="hasActiveRun || submittingRun" aria-label="ESP 方案参数">
            <el-option label="运行频率 (Hz)" value="operatingFrequency" />
            <el-option label="扬程系数 Head Factor (无量纲)" value="headFactor" />
          </el-select>
        </label>
        <label>{{ espParameter === 'headFactor' ? '目标 Head Factor' : '目标运行频率 (Hz)' }}
          <el-input-number v-model="espTargetValue" :min="0.000001" :max="espParameter === 'headFactor' ? 2 : 200" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ESP 方案目标值" />
        </label>
        <small v-if="espParameter === 'headFactor'">调用官方 Model.set_value(Parameters.ESP.HEADFACTOR)；官方 PTK 未提供该参数的单位/范围元数据，按无量纲系数显示；输入上限 2.0 是平台保护值，不代表官方限值。默认目标 0.9，Worker 在隔离副本执行原值基线、写入读回和 PT Profile + Nodal 复算。</small>
        <small v-else>调用官方 Model.set_value(Parameters.ESP.OPERATINGFREQUENCY)，单位 Hz、平台上限 200。必须显式选择本版本检查清单中的 ESP；Worker 在隔离副本内先做原值基线，再读回目标值并执行真实 PT Profile + Nodal，原上传模型不修改。</small>
      </section>
      <section v-if="isEspCurvesRun" class="well-scenario" aria-label="PIPESIM ESP 曲线对象">
        <strong>ESP 曲线对象</strong>
        <label>目标井<el-select v-model="selectedWellContext" :disabled="hasActiveRun || submittingRun" aria-label="ESP 曲线目标井"><el-option v-for="well in availableWellContexts" :key="well.context" :value="well.context" :label="well.context" /></el-select></label>
        <label>ESP 上下文<el-select v-model="espContext" :disabled="hasActiveRun || submittingRun" aria-label="ESP 曲线上下文"><el-option v-for="context in availableEspContexts" :key="context" :value="context" :label="context" /></el-select></label>
        <small>对所选井执行官方 PT Profile + Nodal；泵曲线必须能唯一对应所选 ESP，否则返回可诊断错误，不取结果中的第一台泵。</small>
      </section>
      <section v-if="isTrajectoryRun" class="well-scenario" aria-label="PIPESIM 井轨迹对象">
        <strong>井轨迹对象</strong>
        <label>目标井<el-select v-model="selectedWellContext" :disabled="hasActiveRun || submittingRun" aria-label="井轨迹目标井"><el-option v-for="well in availableWellContexts" :key="well.context" :value="well.context" :label="well.context" /></el-select></label>
        <small>默认只读调用官方 Model.get_trajectory。受控方案只对已验证的 2D Angle/Tangential/OriginalRKB 轨迹启用；在 Worker 隔离副本修改一个内部站倾角、按验证过的几何关系更新下游 TVD、保存重开核对，再做基线/方案 PT Profile。上传源文件不回写。</small>
        <el-checkbox v-model="trajectoryEditEnabled" :disabled="hasActiveRun || submittingRun || !trajectoryEditSupportedInspection || !selectedWellContext" aria-label="创建倾角方案并重算 Profile">创建倾角方案并重算 Profile</el-checkbox>
        <small v-if="!trajectoryEditSupportedInspection">当前检查版本缺少逐井绑定信息，仅开放只读轨迹；请重新验证模型。</small>
        <div v-if="trajectoryEditEnabled" class="trajectory-edit-inputs">
          <label>站点索引（从 0 开始）<el-input-number v-model="trajectoryStationIndex" :min="1" :max="4094" :step="1" :precision="0" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="轨迹站点索引" /></label>
          <label>目标倾角（度）<el-input-number v-model="trajectoryTargetInclination" :min="0" :max="90" :step="0.1" :precision="6" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="目标倾角" /></label>
          <small>限制：只接受内部站点，且 Worker 必须确认测站几何与 P101 实测关系一致；方位、MD 和其他井参数均不编辑。不适用的 Survey 会安全拒绝。</small>
        </div>
      </section>
      <small v-if="isWellModel && activeVersion?.modelKind === 'black_oil_liquid' && !gasLiftCapability.supported">气举阀任务：{{ gasLiftCapability.reason }}</small>
      <div v-if="isWellModel && selectedRun" class="inline-notice"><span>当前结果 #{{ selectedRun.id }}：{{ gasLiftSnapshot ? '气举参数方案（运行时快照）' : scenarioSnapshot !== null ? `压力方案 · 地层压力 ${scenarioSnapshot} psia` : selectedRun.parameters ? '受控参数方案' : '原模型参数' }}</span>
        <el-button v-if="scenarioSnapshot !== null" link type="primary" :disabled="!canReuseScenario" @click="reuseScenario">载入此方案参数</el-button>
      </div>
      <p v-if="runCapabilityMessage" class="inline-notice warning" :title="`${runCapabilityMessage}；历史运行与已有结果仍可查看。`">
        新运行不可用：{{ runCapabilityMessage }}
      </p>
      <section v-if="isWellModel || isNetworkModel || isEclipseModel" class="model-revalidation" aria-label="模型检查信息">
        <el-button link type="primary" :disabled="!canRevalidateActiveModel" :loading="readingVersionId === activeVersionId" @click="revalidateActiveModel">重新验证并读取模型检查信息</el-button>
        <small>从上传版本的隔离副本重新读取；不修改原文件。重新验证期间暂不可计算。</small>
      </section>

      <p
        v-if="isEclipseRunPresentation && !eclipsePresentationAvailable"
        class="inline-notice warning"
        :title="activeVersion?.status !== 'READY' ? '请等待模型版本验证为 READY。' : '当前 READY 版本缺少 DATA 检查信息。'"
      >ECLIPSE 运行不可用：{{ activeVersion?.status !== 'READY' ? '模型尚未 READY' : '缺少有效 DATA 检查' }}</p>
      <div v-else-if="isEclipseRunPresentation" class="eclipse-request-summary">
        <span>运行类型：ECLIPSE</span><span>Study：不适用</span><span>参数：{{ eclipseScenarioSummary }}</span>
      </div>
      <details v-if="gasLiftSnapshot" :key="`gas-input-${selectedRun.id}`">
        <summary>本次气举输入 · {{ gasLiftSnapshot.producer }} · {{ selectedRun.study }}</summary>
        <p>出口压力 {{ gasLiftSnapshot.outletPressurePsi }} psia；注气温度 {{ gasLiftSnapshot.surfaceInjectionTemperatureF }} °F；目标注气量 {{ gasLiftSnapshot.targetInjectionRateMmscfd }} mmscf/d；地层压力 {{ gasLiftSnapshot.reservoirPressurePsi }} psia；GOR {{ gasLiftSnapshot.gorScfPerStb }} scf/STB；含水率 {{ gasLiftSnapshot.waterCutPercent }}%。</p>
        <p v-if="gasLiftSnapshot.valuesMmscfd.length">扫描值 (mmscf/d)：{{ gasLiftSnapshot.valuesMmscfd.join(', ') }}</p>
      </details>
      <section v-if="isEclipse100RunPresentation && eclipseMultiScheduleAvailable" class="eclipse-scenario" aria-label="ECLIPSE 井组双事件 Schedule 方案">
        <el-checkbox v-model="eclipseMultiScheduleEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseMultiSchedule">创建井组关井 → 重开方案</el-checkbox>
        <template v-if="eclipseMultiScheduleEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 双事件基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>生产井
            <el-select v-model="eclipseMultiScheduleSelectedWells" multiple collapse-tags collapse-tags-tooltip :multiple-limit="8" placeholder="选择 1–8 口井" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 双事件生产井">
              <el-option v-for="well in eclipseMultiScheduleWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <el-checkbox v-model="eclipseMultiDateMode" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseMultiDateMode">编辑多日期启停（2–24个事件）</el-checkbox>
          <template v-if="eclipseMultiDateMode">
            <div v-for="(event, index) in eclipseMultiDateEvents" :key="index">
              <label>事件 {{ index + 1 }} 日期
                <el-select v-model="event.date" :disabled="hasActiveRun || submittingRun" :aria-label="`ECLIPSE 事件 ${index + 1} 日期`">
                  <el-option v-for="date in eclipseMultiScheduleDates" :key="date" :value="date" :label="date" />
                </el-select>
              </label>
              <label>状态
                <el-select v-model="event.status" :disabled="hasActiveRun || submittingRun" :aria-label="`ECLIPSE 事件 ${index + 1} 状态`">
                  <el-option value="SHUT" label="SHUT（关闭）" /><el-option value="OPEN" label="OPEN（打开）" />
                </el-select>
              </label>
              <el-button :disabled="hasActiveRun || submittingRun || eclipseMultiDateEvents.length <= 2" @click="eclipseMultiDateEvents.splice(index, 1)" :aria-label="`删除 ECLIPSE 事件 ${index + 1}`">删除</el-button>
            </div>
            <el-button :disabled="hasActiveRun || submittingRun || eclipseMultiDateEvents.length >= 24 || !eclipseMultiScheduleDates.some(d => d > (eclipseMultiDateEvents.at(-1)?.date || ''))" @click="addEclipseDateEvent">添加启停事件</el-button>
            <small>日期必须递增且已存在于工程中；事件作用于全部所选井。后续日期的原始井控制可能再次改变状态。</small>
          </template>
          <label v-if="!eclipseMultiDateMode">关闭日期
            <el-select v-model="eclipseMultiScheduleShutDate" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 关井日期">
              <el-option v-for="date in eclipseMultiScheduleDates" :key="date" :value="date" :label="date" />
            </el-select><el-tag type="warning">SHUT</el-tag>
          </label>
          <label v-if="!eclipseMultiDateMode">重开日期
            <el-select v-model="eclipseMultiScheduleOpenDate" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重开日期">
              <el-option v-for="date in eclipseMultiScheduleOpenDates" :key="date" :value="date" :label="date" />
            </el-select><el-tag type="success">OPEN</el-tag>
          </label>
          <small>启停事件在所选日期的原始控制之后执行；计算后可下载完整工程。无法确认执行顺序的分拆调度会拒绝运行。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseScheduleAvailable" class="eclipse-scenario" aria-label="ECLIPSE 单井启停方案">
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
      <section v-if="isEclipse100RunPresentation && eclipseCompletionAvailable" class="eclipse-scenario" aria-label="ECLIPSE COMPDAT 完井状态方案">
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
      <section v-if="isEclipse100RunPresentation && eclipseCompletionFactorAvailable" class="eclipse-scenario" aria-label="ECLIPSE COMPDAT 连接因子方案">
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
          <label>原连接因子（{{ eclipseCompletionFactorUnit }}）
            <el-input :model-value="eclipseCompletionFactorRecord?.originalConnectionFactor ?? '—'" disabled aria-label="ECLIPSE COMPDAT 原连接因子" />
          </label>
          <label>目标连接因子（{{ eclipseCompletionFactorUnit }}）
            <el-input-number v-model="eclipseCompletionFactorTarget" :min="0.000001" :max="1000000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE COMPDAT 目标连接因子" />
          </label>
          <small>调用官方 ECLIPSE 求解器，只修改隔离副本中已检查的单条 COMPDAT 数值 CF；当前示例可选择 BASE.SCH:116 的 G1 I14/J2/K1-1，并把 3.028 改为 1.5，原工程不变。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupInjectionAvailable" class="eclipse-scenario" aria-label="ECLIPSE 注入组控方案">
        <el-checkbox v-model="eclipseGroupInjectionEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupInjection">调整注入组总注水目标</el-checkbox>
        <template v-if="eclipseGroupInjectionEnabled">
          <p>已有直接注水组按各井注入潜力分配表面 RATE 目标，保留各井流量上限和 BHP 上限。</p>
          <label>同版本无参数成功基线</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注入组控基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id}`" />
          </el-select>
          <label>注水组</label><el-select v-model="eclipseInjectionGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注水组">
            <el-option v-for="r in eclipseGroupInjectionRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseGroupInjectionRecord">原总注水目标 {{ eclipseGroupInjectionRecord.originalTargetRate }} {{ eclipseGroupInjectionRecord.unit }}；{{ eclipseGroupInjectionRecord.members.map(m => `${m.well} BHP≤${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <label>新总注水目标</label><el-input-number v-model="eclipseInjectionGroupTarget" :min="0.001" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组总注水目标" />
          <span>{{ eclipseGroupInjectionRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupGasInjectionAvailable" class="eclipse-scenario" aria-label="ECLIPSE 注气组控方案">
        <el-checkbox v-model="eclipseGroupGasInjectionEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupGasInjection">调整注入组总注气目标</el-checkbox>
        <template v-if="eclipseGroupGasInjectionEnabled">
          <p>已有直接注气组按各井注入潜力分配表面 RATE 目标，保留各井流量上限和 BHP 上限。</p>
          <label>同版本无参数成功基线</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注气组控基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id}`" />
          </el-select>
          <label>注气组</label><el-select v-model="eclipseGasInjectionGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注气组">
            <el-option v-for="r in eclipseGroupGasInjectionRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseGroupGasInjectionRecord">原总注气目标 {{ eclipseGroupGasInjectionRecord.originalTargetRate }} {{ eclipseGroupGasInjectionRecord.unit }}；{{ eclipseGroupGasInjectionRecord.members.map(m => `${m.well} BHP≤${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <label>新总注气目标</label><el-input-number v-model="eclipseGasInjectionGroupTarget" :min="0.001" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组总注气目标" />
          <span>{{ eclipseGroupGasInjectionRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseControlPlanAvailable" class="eclipse-scenario" aria-label="ECLIPSE 分阶段产油目标方案">
        <el-checkbox v-model="eclipseControlPlanEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseControlPlan">制定多井分阶段产油目标</el-checkbox>
        <template v-if="eclipseControlPlanEnabled">
          <label>基线运行</label><el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 阶段方案基线"><el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" /></el-select>
          <p>在已有日期上调整 OPEN/ORAT 目标，未修改的记录保持原值。每行目标延续至该井下一条控制；后续明确记录会覆盖前期。最多同时修改 24 条，保留原日期、其它井和注入控制及压力下限。</p>
          <el-table :data="eclipseControlPlanDraft" max-height="380" border size="small" aria-label="ECLIPSE 已有阶段目标编辑">
            <el-table-column v-if="activeVersion.inspection.packageProductionControlPlanContext" prop="sourceFile" label="源文件" min-width="170" />
            <el-table-column prop="well" label="井" min-width="110" /><el-table-column prop="date" label="生效日期" min-width="130" /><el-table-column prop="timeDays" label="生效天数" /><el-table-column label="下一覆盖"><template #default="{ row }">{{ row.nextDate || `计划结束第 ${row.nextTimeDays} 天` }}</template></el-table-column>
            <el-table-column prop="originalOilRate" label="原产油目标" /><el-table-column label="新产油目标" min-width="190"><template #default="{ row }"><el-input-number v-model="row.targetOilRate" :min="0.000001" :max="100000000" :precision="6" :disabled="hasActiveRun || submittingRun" :aria-label="`ECLIPSE 阶段目标 ${row.well} ${row.date}`" /></template></el-table-column>
            <el-table-column prop="unit" label="流量单位" /><el-table-column prop="bhpLimit" label="BHP 下限" /><el-table-column prop="lineNumber" label="源行" />
          </el-table>
          <p>{{ activeVersion.inspection.packageProductionControlPlanContext ? '主文件' : '原文件' }} {{ activeVersion.inspection.caseName }}；BHP 单位 {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。日期边界报告对应刚结束的时段，新目标从边界之后生效。</p>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseWaterInjectionPlanAvailable" class="eclipse-scenario" aria-label="ECLIPSE 分阶段注水目标方案">
        <el-checkbox v-model="eclipseWaterInjectionPlanEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseWaterInjectionPlan">制定多井分阶段注水目标</el-checkbox>
        <template v-if="eclipseWaterInjectionPlanEnabled">
          <label>基线运行</label><el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 调注阶段方案基线"><el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" /></el-select>
          <p>在已有日期上调整 OPEN/RATE 目标，未修改的记录保持原值。每行目标延续至该井下一条控制；后续明确记录会覆盖前期。最多同时修改 24 条，保留原日期、其它井和生产控制及压力上限。</p>
          <el-table :data="eclipseWaterInjectionPlanDraft" max-height="380" border size="small" aria-label="ECLIPSE 已有调注阶段目标编辑">
            <el-table-column v-if="activeVersion.inspection.waterInjectionControlPlanContext" prop="sourceFile" label="源文件" min-width="170" />
            <el-table-column prop="well" label="井" min-width="110" /><el-table-column prop="date" label="生效日期" min-width="130" /><el-table-column prop="timeDays" label="生效天数" /><el-table-column label="下一覆盖"><template #default="{ row }">{{ row.nextDate || `计划结束第 ${row.nextTimeDays} 天` }}</template></el-table-column>
            <el-table-column prop="originalInjectionRate" label="原注水目标" /><el-table-column label="新注水目标" min-width="190"><template #default="{ row }"><el-input-number v-model="row.targetInjectionRate" :min="0.000001" :max="100000000" :precision="6" :disabled="hasActiveRun || submittingRun" :aria-label="`ECLIPSE 调注阶段目标 ${row.well} ${row.date}`" /></template></el-table-column>
            <el-table-column prop="unit" label="流量单位" /><el-table-column prop="bhpLimit" label="BHP 上限" /><el-table-column prop="lineNumber" label="源行" />
          </el-table>
          <p>{{ activeVersion.inspection.waterInjectionControlPlanContext ? '主文件' : '原文件' }} {{ activeVersion.inspection.caseName }}；BHP 单位 {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。日期边界报告对应刚结束的时段，新目标从边界之后生效。</p>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupStructureAvailable" class="eclipse-scenario" aria-label="ECLIPSE 组树与分配方案">
        <el-checkbox v-model="eclipseGroupStructureEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupStructure">调整组树目标、井导向或开工比例</el-checkbox>
        <template v-if="eclipseGroupStructureEnabled">
          <label>基线运行</label><el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组树基线"><el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" /></el-select>
          <label>已有操作</label><el-select v-model="eclipseGroupStructureControl" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组树操作"><el-option v-for="c in eclipseGroupStructureRecords" :key="groupStructureKey(c)" :value="groupStructureKey(c)" :label="groupStructureLabel(c)" /></el-select>
          <template v-if="eclipseGroupStructureRecord">
            <p>父组 {{ eclipseGroupStructureRecord.parentGroup }}，产油目标 {{ eclipseGroupStructureRecord.parentTarget }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'STB/day' : 'sm3/day' }}。</p>
            <p v-for="leaf in eclipseGroupStructureRecord.leaves" :key="leaf.group">{{ eclipseGroupStructureRecord.parentGroup }} → {{ leaf.group }} → {{ leaf.members.map(m => m.well).join('、') }}；开工比例 {{ leaf.efficiency }}；原井产油上限/BHP 下限：{{ leaf.members.map(m => `${m.well} ${m.oilRateLimit}/${m.bhpLimit}`).join('、') }}。</p>
            <p>原值 {{ eclipseGroupStructureRecord.originalValue }} {{ eclipseGroupStructureRecord.unit }}；源文件 {{ eclipseGroupStructureRecord.sourceFile }} 第 {{ eclipseGroupStructureRecord.lineNumber }} 行。</p>
            <p v-if="eclipseGroupStructureRecord.keyword === 'WGRUPCON'">固定油导向是相对分配权重。例如四井均为 1 时均分；其中一井改为 3 时，其理论份额为 3/6。保留其余井权重与原有限值。</p>
            <p v-if="eclipseGroupStructureRecord.keyword === 'GEFAC'">0.5 表示子组开工比例 50%。井和子组显示开工时流量；父组计入折算后的贡献，累计量已含开工比例。</p>
            <label>新值</label><el-input-number v-model="eclipseGroupStructureTarget" :min="0.000001" :precision="6" :max="eclipseGroupStructureRecord.keyword === 'GEFAC' ? 1 : 100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组树新值" /><span>{{ eclipseGroupStructureRecord.unit }}</span>
          </template>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseAdvancedInjectionAvailable" class="eclipse-scenario" aria-label="ECLIPSE 注入目标与回注比例方案">
        <el-checkbox v-model="eclipseAdvancedInjectionEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseAdvancedInjection">调整注入目标或回注比例</el-checkbox>
        <template v-if="eclipseAdvancedInjectionEnabled">
          <p>保留模型已有注入相、控制方式和单井上限。RATE 为地面体积，RESV 为油藏体积；REIN 随来源组产气量回注，VREP 按来源组总采出油藏体积补偿。</p>
          <label>基线运行</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注入方案基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" />
          </el-select>
          <label>注入组</label><el-select v-model="eclipseAdvancedInjectionGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注入组">
            <el-option v-for="r in eclipseAdvancedInjectionRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.injectionPhase} ${r.controlMode} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseAdvancedInjectionRecord">已有 {{ eclipseAdvancedInjectionRecord.injectionPhase }} {{ eclipseAdvancedInjectionRecord.controlMode }}；原目标 {{ eclipseAdvancedInjectionRecord.originalTargetRate }} {{ eclipseAdvancedInjectionRecord.unit === 'fraction' ? '倍' : eclipseAdvancedInjectionRecord.unit }}。BHP 上限：{{ eclipseAdvancedInjectionRecord.members.map(m => `${m.well} ≤ ${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <p v-if="eclipseAdvancedInjectionRecord">保留单井地面注入上限：{{ eclipseAdvancedInjectionRecord.members.map(m => `${m.well} ≤ ${m.surfaceRateLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? eclipseAdvancedInjectionRecord.injectionPhase === 'OIL' ? 'STB/day' : 'Mscf/day' : 'sm3/day' }}。提高目标可能受压力或流量限制。</p>
          <p v-if="eclipseAdvancedInjectionRecord?.referenceGroup">来源生产组 {{ eclipseAdvancedInjectionRecord.referenceGroup }}：{{ eclipseAdvancedInjectionRecord.referenceMembers.join('、') }}。例如 0.1 表示其当时产量的 10%，实际比例允许绝对误差 0.0001。</p>
          <label>新注入目标或比例</label><el-input-number v-model="eclipseAdvancedInjectionGroupTarget" :min="0.000001" :precision="6" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 注入目标或比例" />
          <span>{{ eclipseAdvancedInjectionRecord?.unit === 'fraction' ? '倍' : eclipseAdvancedInjectionRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupWaterProductionAvailable" class="eclipse-scenario" aria-label="ECLIPSE 生产组产水目标方案">
        <el-checkbox v-model="eclipseGroupWaterProductionEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupWaterProduction">调整生产组总产水目标</el-checkbox>
        <template v-if="eclipseGroupWaterProductionEnabled">
          <p>已有直接生产组按井潜力分配目标；保留各井流量上限和 BHP 下限。结果同时核对组总量、各井分配与压力限制。</p>
          <label>基线运行</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组控基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" />
          </el-select>
          <label>生产组</label><el-select v-model="eclipseWaterProductionGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组">
            <el-option v-for="r in eclipseGroupWaterProductionRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.controlMode} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseGroupWaterProductionRecord">已有 {{ eclipseGroupWaterProductionRecord.controlMode }} 模式；原组目标 {{ eclipseGroupWaterProductionRecord.originalTargetRate }} {{ eclipseGroupWaterProductionRecord.unit }}；{{ eclipseGroupWaterProductionRecord.members.map(m => `${m.well} BHP≥${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <p v-if="eclipseGroupWaterProductionRecord">保留原产油上限：{{ eclipseGroupWaterProductionRecord.members.map(m => `${m.well} WOPR≤${m.oilRateLimit}`).join('、') }} {{ eclipseGroupWaterProductionRecord.unit }}。提高产水目标可能触及产油或压力限制。</p>
          <label>新总产水目标</label><el-input-number v-model="eclipseWaterProductionGroupTarget" :min="0.001" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组总产水目标" />
          <span>{{ eclipseGroupWaterProductionRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupRateAvailable" class="eclipse-scenario" aria-label="ECLIPSE 生产组气液目标方案">
        <el-checkbox v-model="eclipseGroupRateEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupRate">调整生产组总产气/总产液目标</el-checkbox>
        <template v-if="eclipseGroupRateEnabled">
          <p>已有直接生产组按井潜力分配目标；保留各井流量上限和 BHP 下限。结果同时核对组总量、各井分配与压力限制。</p>
          <label>基线运行</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组控基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" />
          </el-select>
          <label>生产组</label><el-select v-model="eclipseRateGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组">
            <el-option v-for="r in eclipseGroupRateRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.controlMode} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseGroupRateRecord">已有 {{ eclipseGroupRateRecord.controlMode }} 模式；原组目标 {{ eclipseGroupRateRecord.originalTargetRate }} {{ eclipseGroupRateRecord.unit }}；{{ eclipseGroupRateRecord.members.map(m => `${m.well} BHP≥${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <label>新总产气/总产液目标</label><el-input-number v-model="eclipseRateGroupTarget" :min="0.001" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组总产气/总产液目标" />
          <span>{{ eclipseGroupRateRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseGroupProductionAvailable" class="eclipse-scenario" aria-label="ECLIPSE 生产组控方案">
        <el-checkbox v-model="eclipseGroupProductionEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseGroupProduction">调整生产组总产油目标</el-checkbox>
        <template v-if="eclipseGroupProductionEnabled">
          <p>已有直接生产组按井潜力分配目标；保留各井流量上限和 BHP 下限。结果同时核对组总量、各井分配与压力限制。</p>
          <label>基线运行</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组控基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · 成功基线`" />
          </el-select>
          <label>生产组</label><el-select v-model="eclipseProductionGroup" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 生产组">
            <el-option v-for="r in eclipseGroupProductionRecords" :key="r.group" :value="r.group" :label="`${r.group} · ${r.members.length} 口井`" />
          </el-select>
          <p v-if="eclipseGroupProductionRecord">原总产油目标 {{ eclipseGroupProductionRecord.originalTargetRate }} {{ eclipseGroupProductionRecord.unit }}；{{ eclipseGroupProductionRecord.members.map(m => `${m.well} BHP≥${m.bhpLimit}`).join('、') }} {{ activeVersion.inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
          <label>新总产油目标</label><el-input-number v-model="eclipseGroupTarget" :min="0.001" :max="100000000" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 组总产油目标" />
          <span>{{ eclipseGroupProductionRecord?.unit }}</span>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseTstepAvailable" class="eclipse-scenario" aria-label="ECLIPSE TSTEP 方案">
        <el-checkbox v-model="eclipseTstepEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseTstep">调整 TSTEP 报告间隔</el-checkbox>
        <template v-if="eclipseTstepEnabled">
          <label>无参数成功基线</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE TSTEP 基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id}`" />
          </el-select>
          <label>报告时间段</label>
          <el-select v-model="eclipseTstepLine" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE TSTEP 时间段">
            <el-option v-for="r in eclipseTstepRecords" :key="r.lineNumber" :value="r.lineNumber" :label="`${r.startDays} → ${r.endDays} 天 · 第${r.lineNumber}行`" />
          </el-select>
          <p v-if="eclipseTstepRecord">原间隔：{{ eclipseTstepRecord.originalSteps.join('、') }} 天；合计 {{ eclipseTstepRecord.duration }} 天。</p>
          <label>新报告间隔（天）</label><el-input v-model="eclipseTstepText" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新 TSTEP 间隔" placeholder="例如：1 4 5 10 30" />
          <p>正整数列表，用空格或逗号分隔，总和必须等于原段时长。改变报告日期，不改变后续井控生效时刻；求解器内部自动步长仍由求解器决定。当前支持单 DATA、纯 TSTEP、天单位。</p>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseNewDatesAvailable" class="eclipse-scenario" aria-label="ECLIPSE 新增日期方案">
        <el-checkbox v-model="eclipseNewDatesEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseNewDates">新增日期停井／复产</el-checkbox>
        <template v-if="eclipseNewDatesEnabled">
          <label>无参数成功基线</label>
          <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新日期基线">
            <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id}`" />
          </el-select>
          <label>已有日期区间</label>
          <el-select v-model="eclipseNewDatesWindowKey" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新日期区间">
            <el-option v-for="w in eclipseNewDateWindows" :key="w.beforeLineNumber" :value="w.beforeLineNumber" :label="`${w.beforeDate} → ${w.afterDate}`" />
          </el-select>
          <label>生产井</label>
          <el-select v-model="eclipseNewDatesWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新日期井">
            <el-option v-for="well in eclipseNewDatesWindow?.wells || []" :key="well" :value="well" :label="well" />
          </el-select>
          <label>停井日期</label><el-input v-model="eclipseNewShutDate" placeholder="YYYY-MM-DD" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新停井日期" />
          <label>复产日期</label><el-input v-model="eclipseNewOpenDate" placeholder="YYYY-MM-DD" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新复产日期" />
          <p>两个新日期须严格位于所选区间内。适用于一份 DATA、单记录 DATES 和已有开井生产井。保留原控制目标与后续日期；混用 TSTEP、重启、INCLUDE 或动态控制须另行处理。</p>
          <p>报告对应刚结束的时段：复产日期报告可能仍为零，复产后的下一时段应出现非零产量。SUMMARY DATE 可提供日历核对。</p>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseThpAvailable" class="eclipse-scenario" aria-label="ECLIPSE 井口压力 THP 方案">
        <el-checkbox v-model="eclipseThpEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseThp">创建井口压力 THP 方案</el-checkbox>
        <template v-if="eclipseThpEnabled">
          <p>用于评估提高或降低井口背压后的产量。沿用模型已有 VFP 表，只调整初始开井 THP 目标，保留井底压力及其它流量约束。</p>
          <label>无参数成功基线
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE THP 方案基线">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`Run #${run.id}`" />
            </el-select>
          </label>
          <label>生产井
            <el-select v-model="eclipseThpWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE THP 方案井">
              <el-option v-for="record in eclipseThpRecords" :key="record.well" :value="record.well" :label="record.well" />
            </el-select>
          </label>
          <div v-if="eclipseThpRecord">
            原目标：{{ eclipseThpRecord.originalThp }} psia · VFP 表 {{ eclipseThpRecord.table.number }} · 井底基准深度 {{ eclipseThpRecord.table.datumDepth }} ft<br />
            THP 范围 {{ eclipseThpRecord.table.thpAxis[0] }}–{{ eclipseThpRecord.table.thpAxis.at(-1) }} psia；总液 {{ eclipseThpRecord.table.flowAxis[0] }}–{{ eclipseThpRecord.table.flowAxis.at(-1) }} STB/day<br />
            含水率 WCT {{ eclipseThpRecord.table.waterFractionAxis[0] }}–{{ eclipseThpRecord.table.waterFractionAxis.at(-1) }}；气油比 GOR {{ eclipseThpRecord.table.gasFractionAxis[0] }}–{{ eclipseThpRecord.table.gasFractionAxis.at(-1) }} Mscf/STB；ALQ {{ eclipseThpRecord.originalAlq }}
          </div>
          <label>新井口压力 psia
            <el-input-number v-model="eclipseThpTarget" :min="Math.max(0.000001, eclipseThpRecord?.table.thpAxis[0] || 0)" :max="eclipseThpRecord?.table.thpAxis.at(-1) || 100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新井口压力" />
          </label>
          <p>计算后核对 WTHP、WBHP、WLPR 和表范围提示；下载方案工程 ZIP 可直接重新上传复算。</p>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseRateControlAvailable" class="eclipse-scenario" aria-label="ECLIPSE 生产流量目标方案">
        <el-checkbox v-model="eclipseRateControlEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseRateControl">创建产水／产气／总液目标方案</el-checkbox>
        <template v-if="eclipseRateControlEnabled">
          <label>无参数基线
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 流量方案基线">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`#${run.id} · ${run.status}`" />
            </el-select>
          </label>
          <label>目标井与控制模式
            <el-select v-model="eclipseRateControlWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 流量方案井">
              <el-option v-for="record in eclipseRateControlRecords" :key="record.well" :value="record.well" :label="`${record.well} · ${record.controlMode}`" />
            </el-select>
          </label>
          <div v-if="eclipseRateControlRecord">初始阶段 · 保持 OPEN 和 {{ eclipseRateControlRecord.controlMode }}<br />原目标：{{ eclipseRateControlRecord.originalTargetRate }} {{ eclipseRateControlRecord.unit }}</div>
          <label>新目标 {{ eclipseRateControlRecord?.unit || '' }}
            <el-input-number v-model="eclipseRateControlTarget" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 新生产流量目标" />
          </label>
          <small>WRAT 对比产水量、GRAT 对比产气量、LRAT 对比总液量。保留其它压力和流量限制；计算后可下载方案工程并上传复算。支持无 INCLUDE 的 FIELD／METRIC 单文件模型。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipsePressureAvailable" class="eclipse-scenario" aria-label="ECLIPSE 井底压力 BHP 方案">
        <el-checkbox v-model="eclipsePressureEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipsePressure">创建井底压力 BHP 方案</el-checkbox>
        <template v-if="eclipsePressureEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE BHP 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>目标井
            <el-select v-model="eclipsePressureWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE BHP 方案井">
              <el-option v-for="record in eclipsePressureRecords" :key="record.well" :value="record.well" :label="`${record.well} · ${record.keyword === 'WCONPROD' ? '生产井' : '注入井'}`" />
            </el-select>
          </label>
          <div v-if="eclipsePressureRecord">初始阶段 · 保持 OPEN · 控制改为 BHP<br />原控制：{{ eclipsePressureRecord.originalControlMode }} · 原 BHP 设定／限值：{{ eclipsePressureRecord.originalBhp }} psia<span v-if="eclipsePressureRecord.keyword === 'WCONINJE'"> · {{ eclipsePressureRecord.injectionType }}</span></div>
          <label>目标井底压力（psia）
            <el-input-number v-model="eclipsePressureTarget" :min="0.000001" :max="100000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE BHP 目标压力" />
          </label>
          <small>支持单 DATA、FIELD 单位工程中的初始开井记录。压力应在模型 PVT 适用范围内；原流量等限制保留，可能使实际压力无法达到目标。计算后对照 WBHP 与产量／注入量，并下载方案工程复算。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseWconProdAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONPROD 预测初始段方案">
        <el-checkbox v-model="eclipseWconProdEnabled" :disabled="hasActiveRun || submittingRun || eclipseForecastEnabled" @change="toggleEclipseWconProd">{{ eclipseProductionTargetMode ? '创建开井产油目标方案（可下载工程）' : '创建 WCONPROD 预测初始段方案' }}</el-checkbox>
        <template v-if="eclipseWconProdEnabled">
          <label>基线运行
            <el-select v-model="eclipseBaselineRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 基线运行">
              <el-option v-for="run in eclipseBaselineOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>生产井
            <el-select v-model="eclipseWconProdWell" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 方案井">
              <el-option v-for="well in eclipseWconProdWells" :key="well" :value="well" :label="well" />
            </el-select>
          </label>
          <label v-if="!eclipseProductionTargetMode">状态
            <el-select v-model="eclipseWconProdStatus" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD 方案状态">
              <el-option value="OPEN" label="OPEN（打开）" />
              <el-option value="SHUT" label="SHUT（关闭）" />
            </el-select>
          </label>
          <div v-if="eclipseProductionTargetRecord">初始阶段 · 保持 OPEN<br />原产油目标：{{ eclipseProductionTargetRecord.originalOilRate }} {{ eclipseProductionTargetRecord.unit }}<br />来源：{{ eclipseProductionTargetRecord.sourceFile }}:{{ eclipseProductionTargetRecord.lineNumber }}</div>
          <label>控制模式
            <el-input model-value="ORAT" disabled aria-label="ECLIPSE WCONPROD 控制模式" />
          </label>
          <label>ORAT 目标值 {{ eclipseProductionTargetRecord?.unit || '' }}
            <el-input-number v-model="eclipseWconProdTargetOilRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONPROD ORAT 目标值" />
          </label>
          <small v-if="eclipseProductionTargetMode">修改初始产油目标并保留其它压力限制；实际产量可能受压力或油藏供给限制。计算后可下载完整方案工程，再上传复算。支持已核准执行顺序的 FIELD 单文件及 INCLUDE 工程；只修改所选源记录，其它文件保留。</small>
          <small v-else>此选项只修改预测段初始 WCONPROD 中已有 ORAT 行的状态和目标值；井底压力使用单独的 BHP 方案选项。WRAT/GRAT、批量多井或任意 Schedule 文本仍待开发。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseWconInjeAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONINJE 注入方案">
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
          <div v-if="eclipseWconInjeDatedRecord">所选日期原注入目标：{{ eclipseWconInjeDatedRecord.values[4] }} {{ eclipseWconInjeUnit || '工程原单位（未识别）' }}</div>
          <label>RATE 注入速率（{{ eclipseWconInjeUnit || '工程原单位（未识别）' }}）
            <el-input-number v-model="eclipseWconInjeTargetRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONINJE RATE 注入速率" />
          </label>
          <small>调整已有日期的注入目标；后续调度记录可能覆盖该目标。计算后比较目标井的注入量，并可下载完整工程复算。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseWconHistAvailable" class="eclipse-scenario" aria-label="ECLIPSE WCONHIST 产量方案">
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
          <div v-if="eclipseWconHistRecord">所选日期原产油目标：{{ eclipseWconHistRecord.values[3] }} {{ eclipseWconHistUnit || '工程原单位（未识别）' }}</div>
          <label>ORAT 目标值（{{ eclipseWconHistUnit || '工程原单位（未识别）' }}）
            <el-input-number v-model="eclipseWconHistTargetOilRate" :min="0.000001" :max="100000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE WCONHIST ORAT 目标值" />
          </label>
          <small>用于调整历史阶段的指定产油量；验收数值效果请选择 OPEN。后续日期的控制仍按原工程执行，计算后可下载完整工程复算。</small>
        </template>
      </section>
      <section v-if="isEclipse100RunPresentation && eclipseForecastAvailable" class="eclipse-scenario" aria-label="ECLIPSE 历史重启预测方案">
        <el-checkbox v-model="eclipseForecastEnabled" :disabled="hasActiveRun || submittingRun" @change="toggleEclipseForecast">创建历史重启预测方案</el-checkbox>
        <template v-if="eclipseForecastEnabled">
          <label>历史运行
            <el-select v-model="eclipseForecastHistoryRunId" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 历史运行">
              <el-option v-for="run in eclipseForecastHistoryOptions" :key="run.id" :value="run.id" :label="`运行 #${run.id}`" />
            </el-select>
          </label>
          <label>预测 DATA
            <el-select v-model="eclipseForecastDataFile" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 预测 DATA">
              <el-option v-for="file in eclipseForecastSelectableDataFiles" :key="file" :value="file" :label="file" />
            </el-select>
          </label>
          <label>重启 Artifact
            <el-select v-model="eclipseForecastRestartArtifactName" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重启 Artifact">
              <el-option v-for="artifact in eclipseForecastRestartArtifacts" :key="artifact.name" :value="artifact.name" :label="artifact.name" />
            </el-select>
          </label>
          <label>重启报告编号
            <el-select v-if="eclipseForecastRestartReports.length" v-model="eclipseForecastRestartReport" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重启报告编号">
              <el-option v-for="report in eclipseForecastRestartReports" :key="report" :value="report" :label="eclipseForecastReportLabel(report)" />
            </el-select>
            <el-input-number v-else-if="!eclipseForecastRequiresCatalog" v-model="eclipseForecastRestartReport" :min="1" :max="1000000" :controls="false" :disabled="hasActiveRun || submittingRun" aria-label="ECLIPSE 重启报告编号" />
          </label>
          <small v-if="eclipseForecastSource">已核对 {{ eclipseForecastSource.catalog.reports.length }} 个真实历史报告；{{ eclipseForecastSource.schemaVersion === 'eclipse-restart-source/2' ? '二进制 UNRST' : '格式化 FUNRST' }} 状态。仅显示与当前预测输入相符的截止日期。计算后可下载含完整重启状态的工程，重新上传复算。</small>
          <small v-else-if="eclipseForecastRequiresCatalog && !eclipseForecastCatalogPresent">二进制重启状态缺少已核准报告目录，请重新运行历史模型；不能手填报告号。</small>
          <small v-else-if="eclipseForecastCatalogLoading">正在核对历史报告目录…</small>
          <small v-else-if="eclipseForecastCatalogError" role="alert">{{ eclipseForecastCatalogError }}</small>
          <small v-else-if="eclipseForecastRestartReports.length">候选报告来自所选历史 Run 的 FUNRST SEQNUM 索引；仍须与预测 DATA 的 SOLUTION / RESTART 声明一致，Worker 会在启动求解器前复核。</small>
          <small v-else>该历史 Artifact 暂无可读 SEQNUM 索引；旧记录可手动输入，但须按预测 DATA 的 RESTART 声明填写，未知 report 是否存在由 ECLIPSE 求解器确认。可重跑历史 DATA 生成索引。</small>
        </template>
      </section>


    <div v-if="(!isEclipseRunPresentation || eclipsePresentationAvailable) && displayRun && hasActiveRun" class="stage-strip" aria-label="真实运行阶段">
      <div v-for="(stage, index) in stages" :key="stage.status" class="stage" :class="{ active: currentStageIndex === index, done: currentStageIndex > index }">
        <i />
        <span>{{ stage.label }}</span>
      </div>
      <span v-if="currentStageIndex < 0" class="queue-stage">{{ statusMeta[displayRun.status]?.[0] || displayRun.status }}</span>
    </div>

    <details v-if="safeRunError && !isEclipseModel" :key="selectedRun?.id" :open="Boolean(safeRunError.diagnostics?.length)" class="structured-error">
      <summary>运行诊断 · {{ safeRunError.code }}</summary>
      <dl>
        <div><dt>类别</dt><dd>{{ safeRunError.category }}</dd></div>
        <div><dt>代码</dt><dd>{{ safeRunError.code }}</dd></div>
        <div><dt>消息</dt><dd>{{ gasLiftReason(safeRunError.code) || (safeRunError.diagnostics.length ? '官方任务返回以下诊断。' : '运行失败详情已隐藏。') }}</dd></div>
        <div><dt>可重试</dt><dd>{{ safeRunError.retryable ? '是' : '否' }}</dd></div>
      </dl>
      <ul v-if="safeRunError.diagnostics.length" class="validation-diagnostics" aria-label="PIPESIM 官方预检诊断">
        <li v-for="(diagnostic, index) in safeRunError.diagnostics" :key="`${diagnostic.path}-${diagnostic.property}-${index}`">
          <span v-if="diagnostic.path">{{ diagnostic.path }}</span>
          <strong v-if="diagnostic.property">{{ diagnostic.property }}</strong>
          <span>{{ diagnostic.message }}</span>
        </li>
      </ul>
    </details>
    <section v-if="validWellResult?.schemaVersion === 'pipesim-well-result/2'" class="inline-notice success" data-testid="well-tubing-edit-result">
      <strong>油管参数已持久化并完成原生 PT Profile</strong>
      <span>{{ validWellResult.application.wellContext }} · {{ validWellResult.application.tubingContext }}：{{ validWellResult.application.originalInnerDiameter }} → {{ validWellResult.application.appliedInnerDiameter }} {{ validWellResult.application.unit }}</span>
      <span>基线运行 #{{ selectedRun.parameters.baselineRunId }}；已自动载入基线 PT 剖面对照。原上传模型未修改；派生工程可在本次运行的 Artifact 中下载。</span>
    </section>

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
         <PipesimVfpTablesResult v-if="activeTab === 'vfp-tables'" :result="validVfpTablesResult" :run="selectedRun" @download-artifact="downloadArtifact" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="ESP 曲线" name="esp-curves">
        <PipesimEspCurvesResult v-if="activeTab === 'esp-curves'" :result="validEspCurvesResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="ESP 参数方案" name="esp-parameter">
        <PipesimEspParameterResult v-if="activeTab === 'esp-parameter'" :result="validEspParameterResult" />
      </el-tab-pane>
      <el-tab-pane v-if="isWellModel" label="井轨迹" name="trajectory">
        <PipesimWellTrajectoryResult v-if="activeTab === 'trajectory'" :result="validTrajectoryResult" />
      </el-tab-pane>
       <el-tab-pane v-if="isNetworkWorkflow" label="管网结果" name="network">
         <PipesimNetworkResult :result="validNetworkResult" :partial="isNetworkPartial" :run-id="selectedRun?.id" :history="runHistory" :run="selectedRun" :submitting="submittingRun" @create-network-connection-run="submitRun" />
       </el-tab-pane>
       <el-tab-pane v-if="isNetworkWorkflow" label="系统分析" name="system-analysis">
         <PipesimSystemAnalysisResult v-if="activeTab === 'system-analysis'" :result="validSystemAnalysisResult" />
       </el-tab-pane>
       <el-tab-pane v-if="isNetworkWorkflow" label="网络优化" name="network-optimizer">
         <PipesimNetworkOptimizerResult v-if="activeTab === 'network-optimizer'" :result="validNetworkOptimizerResult" />
       </el-tab-pane>
       <el-tab-pane v-if="isNetworkWorkflow" label="井性能曲线" name="network-well-performance-curves">
         <PipesimNetworkWellPerformanceResult v-if="activeTab === 'network-well-performance-curves'" :result="validNetworkWellPerformanceResult" :artifact="networkWellPerformanceArtifact" @download-artifact="downloadArtifact" />
       </el-tab-pane>
       <el-tab-pane v-if="pipesimPackageAvailable" label="工程包" name="package">
         <PipesimPackageOverview :inspection="activeVersion.inspection" />
       </el-tab-pane>
      <el-tab-pane v-if="isEclipseModel" label="ECLIPSE 结果" name="eclipse">
        <EclipseThermalResult v-if="activeVersion?.modelKind === 'eclipse_300' && ['eclipse-data-inspection/15', 'eclipse-data-inspection/23'].includes(activeVersion?.inspection?.schemaVersion)" :run="selectedRun" :inspection="activeVersion?.inspection" :disabled="hasActiveRun || submittingRun" @run-scenario="submitRun" @select-run="selectHistoryRun" />
        <EclipseCompositionalResult v-else-if="activeVersion?.modelKind === 'eclipse_300'" :run="selectedRun" :inspection="activeVersion?.inspection" :history="runHistory" :disabled="hasActiveRun || submittingRun" @run-scenario="submitRun" @select-run="selectHistoryRun" />
        <EclipseRunResult v-else :run="selectedRun" :history="runHistory" :inspection="activeVersion?.inspection" @create-grid-field-run="submitRun" @create-completion-geometry-run="submitRun" @create-new-well-run="submitRun" @create-new-completion-run="submitRun" @create-pvtw-run="submitRun" @create-oil-pvt-run="submitRun" @create-gas-pvt-run="submitRun" @create-relperm-run="submitRun" @select-run="selectHistoryRun" />
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
.model-object-details { min-width: 0; flex: 1 1 220px; }
.model-object-details summary, .structured-error summary { cursor: pointer; font-size: 12px; }
.model-object-details small { display: block; color: #73777d; }
.run-feedback { align-items: center; gap: 8px; }
.combined-results { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.combined-results > * { min-width: 0; }
@media (max-width: 1200px) { .combined-results { grid-template-columns: 1fr; } }
.well-scenario { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 10px; border-bottom: 1px solid #deded9; font-size: 12px; }
.well-scenario > span { color: #73777d; }
.well-scenario > small { flex-basis: 100%; color: #73777d; }
.pressure-field { display: flex; flex-direction: column; gap: 4px; }
.pressure-field > small { color: #b23c3c; }
.trajectory-edit-inputs { display: flex; flex: 1 1 100%; flex-wrap: wrap; align-items: center; gap: 8px 16px; padding: 8px 0; }
.trajectory-edit-inputs > small { flex-basis: 100%; color: #73777d; }
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
.system-analysis-inputs label { display: grid; gap: 5px; }
.system-analysis-inputs .el-select { width: 180px; }
.system-analysis-inputs label .el-input, .system-analysis-inputs label .el-input-number { width: 200px; }
.system-analysis-inputs small[role="alert"] { color: #b42318; }
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
