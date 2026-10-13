<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { completionFactorUnit, completionFactorResponse } from './eclipseCompletionFactor'
import { validGridRegionParameters, gridRegionUnit, gridRegionResponse } from './eclipseGridRegion'
import { validCompletionGeometry, completionGeometryLabels, completionGeometryResponse } from './eclipseCompletionGeometry'
import EclipseCompletionGeometryEditor from './EclipseCompletionGeometryEditor.vue'
import EclipseHistoryPanel from './EclipseHistoryPanel.vue'
import EclipseBatchPanel from './EclipseBatchPanel.vue'
import EclipseRelpermEditor from './EclipseRelpermEditor.vue'
import { validRelperm, relpermResponse } from './eclipseRelperm'
import EclipseGasPvtEditor from './EclipseGasPvtEditor.vue'
import { validGasPvt, gasPvtResponse } from './eclipseGasPvt'
import EclipseOilPvtEditor from './EclipseOilPvtEditor.vue'
import { validOilPvt, oilPvtResponse } from './eclipseOilPvt'
import EclipsePvtwEditor from './EclipsePvtwEditor.vue'
import { validPvtw, pvtwResponse, pvtwLabels } from './eclipsePvtw'
import { validNewWellParameters, newWellOutcome, newWellResponse } from './eclipseNewWell'
import EclipseNewWellEditor from './EclipseNewWellEditor.vue'
import EclipseNewCompletionEditor from './EclipseNewCompletionEditor.vue'
import { validNewCompletionParameters, newCompletionResponse } from './eclipseNewCompletion'
import { ElMessage } from 'element-plus'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import EclipseRestartForecastCompare from './EclipseRestartForecastCompare.vue'
import { validRestartForecast } from './eclipseRestartForecast'
import EclipseGridSlice from './EclipseGridSlice.vue'
import EclipseGrid3D from './EclipseGrid3D.vue'
import { createFieldRequestGate, snapshotField } from './eclipseFieldRequest'
import { compareEclipseWellRates, validateEclipseWellEvents, validateEclipseMultiDateEvents } from './eclipseWellEvents'
import { validProductionTarget } from './eclipseProductionTarget'
import { validPressureControl } from './eclipsePressureControl'
import { validProductionControl, productionControlResultUnitMatches } from './eclipseProductionControl'
import { validThpControl, thpTableCoverage } from './eclipseThpControl'
import { validNewDates, newDatesResponse } from './eclipseNewDates'
import { validTstep, tstepResponse } from './eclipseTstep'
import { validGroupProduction, groupProductionResponse } from './eclipseGroupProduction'
import { validGroupRate, groupRateResponse } from './eclipseGroupRate'
import { validGroupWaterProduction, groupWaterProductionResponse } from './eclipseGroupWaterProduction'
import { validControlPlan, controlPlanResponse } from './eclipseControlPlan'
import { validGroupStructure, groupStructureResponse } from './eclipseGroupStructure'
import { validAdvancedInjection, advancedInjectionResponse } from './eclipseAdvancedInjection'
import { validGroupInjection, groupInjectionResponse } from './eclipseGroupInjection'
import { validGroupGasInjection, groupGasInjectionResponse } from './eclipseGroupGasInjection'

const emit = defineEmits(['create-grid-field-run', 'create-completion-geometry-run', 'create-new-well-run', 'create-new-completion-run', 'create-pvtw-run', 'create-oil-pvt-run', 'create-gas-pvt-run', 'create-relperm-run', 'select-run'])
const batchPanel = ref(null)
function addGridBatchCase(candidate) {
  try {
    if (!batchPanel.value) throw new Error('批量方案面板尚未就绪。')
    batchPanel.value.addGridCase(candidate)
    ElMessage.success('已加入批量方案清单；加入至少两项后点击“运行全部方案”。')
  } catch (error) { ElMessage.error(error?.message || '区域方案加入失败。') }
}

const props = defineProps({
  run: { type: Object, default: null },
  history: { type: Array, default: () => [] },
  inspection: { type: Object, default: null }
})


const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const isNonNegativeInteger = value => Number.isSafeInteger(value) && value >= 0
const isIsoCalendarDate = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
const validateFieldIndex = fieldIndex => {
  if (fieldIndex === undefined || fieldIndex === null) return true
  if (fieldIndex?.schemaVersion !== 'eclipse-binary-field-index/1' || !Array.isArray(fieldIndex.files) || fieldIndex.files.length > 64) return false
  return fieldIndex.files.every(file => typeof file?.name === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.(EGRID|INIT|UNRST|FUNRST)$/i.test(file.name) &&
    ['BIG', 'LITTLE'].includes(file.byteOrder) &&
    isNonNegativeInteger(file.sizeBytes) && Array.isArray(file.fields) && file.fields.length <= 8192 && file.fields.every(field => {
      const elementSize = field.dataType === 'DOUB' ? 8 : field.dataType === 'CHAR' ? 8 : 4
      return typeof field.keyword === 'string' && /^[A-Z0-9_]{1,8}$/.test(field.keyword) &&
        ['INTE', 'LOGI', 'REAL', 'DOUB', 'CHAR'].includes(field.dataType) && isNonNegativeInteger(field.count) &&
        (field.timeStep === undefined || (Number.isSafeInteger(field.timeStep) && field.timeStep > 0)) &&
        field.elementSize === elementSize && isNonNegativeInteger(field.dataBytes) &&
        field.dataBytes === field.count * field.elementSize && Array.isArray(field.segments) &&
        field.segments.every(segment => isNonNegativeInteger(segment?.offset) && Number.isSafeInteger(segment?.length) && segment.length > 0 &&
        segment.length % elementSize === 0 && segment.offset + segment.length <= file.sizeBytes)
    }))
}
const validateResult = run => {
  const value = run?.result
  if (!value || value.schemaVersion !== 'eclipse-summary-result/1' || value.modelKind !== 'eclipse_100' ||
    value.runTask !== 'eclipse' || !['VALID_FULL', 'VALID_PARTIAL'].includes(value.resultContract) ||
    value.resultContract !== run?.resultContract ||
    (value.resultContract === 'VALID_PARTIAL' && run?.status !== 'PARTIAL_SUCCEEDED') ||
    (value.resultContract === 'VALID_FULL' && run?.status !== 'SUCCEEDED')) return null
  const counts = value.eclEnd
  if (!counts || !['problems', 'errors', 'bugs'].every(key =>
    Number.isInteger(counts[key]) && counts[key] >= 0) ||
    !['comments', 'warnings'].every(key => counts[key] === undefined || counts[key] === null ||
      Number.isInteger(counts[key]) && counts[key] >= 0) ||
    counts.errors !== 0 || counts.bugs !== 0 ||
    value.resultContract === 'VALID_PARTIAL' && counts.problems <= 0 ||
    value.resultContract === 'VALID_FULL' && counts.problems !== 0) return null
  if (value.messages !== undefined && (!Array.isArray(value.messages) || value.messages.length > 512 ||
    !value.messages.every(message => typeof message?.category === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(message.category) &&
      typeof message?.severity === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(message.severity) &&
      typeof message?.code === 'string' && /^[A-Z][A-Z0-9_]{0,99}$/.test(message.code) &&
      typeof message?.message === 'string' && message.message.length <= 1000 && typeof message?.retryable === 'boolean'))) return null
  if (value.summary !== null && (!Array.isArray(value.summary?.series) || !value.summary.series.every(series =>
    typeof series?.keyword === 'string' && (series.objectName === null || typeof series.objectName === 'string') &&
    (series.unit === null || typeof series.unit === 'string') && Array.isArray(series.points) && series.points.every(point =>
      isFiniteNumber(point?.timeDays) && point.timeDays >= 0 && isFiniteNumber(point?.value))))) return null
  if (value.grid !== undefined && value.grid !== null && (!value.grid || typeof value.grid.fileName !== 'string' ||
    !Number.isInteger(value.grid.nx) || value.grid.nx <= 0 || !Number.isInteger(value.grid.ny) || value.grid.ny <= 0 ||
    !Number.isInteger(value.grid.nz) || value.grid.nz <= 0 ||
    !(value.grid.activeCells === null || (Number.isInteger(value.grid.activeCells) && value.grid.activeCells >= 0 &&
      value.grid.activeCells <= value.grid.nx * value.grid.ny * value.grid.nz)))) return null
  if (!validateFieldIndex(value.fieldIndex)) return null
  return value
}
const result = computed(() => validateResult(props.run))
const comparisonId = ref(null)
const comparison = ref(null)
const comparisonLoading = ref(false)
const comparisonError = ref('')
let comparisonGeneration = 0
const comparisonOptions = computed(() => props.history.filter(run => run.id !== props.run?.id && run.modelId === props.run?.modelId && run.modelVersionId === props.run?.modelVersionId && run.runType === 'eclipse' && run.status === 'SUCCEEDED'))
watch(() => props.run, () => {
  comparisonGeneration++
  comparisonId.value = null
  comparison.value = null
  comparisonLoading.value = false
  comparisonError.value = ''
})
watch(comparisonId, async id => {
  const generation = ++comparisonGeneration
  comparison.value = null
  comparisonError.value = ''
  comparisonLoading.value = Boolean(id)
  if (!id) return
  const current = props.run
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (generation !== comparisonGeneration) return
    const candidate = response?.data
    if (response?.code !== 200 || candidate?.id !== id || candidate?.modelId !== current?.modelId || candidate?.modelVersionId !== current?.modelVersionId || candidate?.runType !== 'eclipse' || candidate?.status !== 'SUCCEEDED' || !validateResult(candidate)) {
      comparisonError.value = '历史结果未通过校验，无法比较。'
      return
    }
    if (!candidate.result.summary?.series?.some(item => item.points.length)) {
      comparisonError.value = '历史运行没有可用的 RSM 序列。'
      return
    }
    comparison.value = candidate
  } catch { if (generation === comparisonGeneration) comparisonError.value = '历史结果读取失败，请重新选择。' }
  finally { if (generation === comparisonGeneration) comparisonLoading.value = false }
})
onBeforeUnmount(() => { comparisonGeneration++ })
const explorerSeries = computed(() => comparison.value ? [
  ...series.value.map(item => ({ ...item, sourceRunId: props.run.id, historical: false })),
  ...comparison.value.result.summary.series.map(item => ({ ...item, sourceRunId: comparison.value.id, historical: true }))
] : series.value)
const counts = computed(() => result.value?.eclEnd || null)
const countItems = computed(() => [
  ['comments', 'Comments'], ['warnings', 'Warnings'], ['problems', 'Problems'], ['errors', 'Errors'], ['bugs', 'Bugs']
].map(([key, label]) => ({ key, label, value: counts.value?.[key] ?? '-' })))
const series = computed(() => result.value?.summary?.series || [])
const hasSummary = computed(() => result.value?.summary !== null && Array.isArray(result.value?.summary?.series))
const hasUsableSummary = computed(() => series.value.some(item => item.points.length > 0))
const forecastScenario = computed(() => {
  const parameters = props.run?.parameters
  if (!(['eclipse-history-forecast-parameters/1', 'eclipse-history-forecast-parameters/2'].includes(parameters?.schemaVersion)) ||
    parameters?.schemaVersion === 'eclipse-history-forecast-parameters/2' && !validRestartForecast(parameters) ||
    !Number.isSafeInteger(Number(parameters.historyRunId)) || Number(parameters.historyRunId) <= 0 ||
    typeof parameters.forecastDataFile !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._ /-]{0,511}\.DATA$/i.test(parameters.forecastDataFile) ||
    parameters.forecastDataFile.includes('\\') || parameters.forecastDataFile.split('/').some(part => !part || part === '.' || part === '..') ||
    typeof parameters.restartArtifactName !== 'string' || !/^eclipse-output-[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.FUNRST$/i.test(parameters.restartArtifactName) ||
    typeof parameters.restartArtifactSha256 !== 'string' || !/^[a-f0-9]{64}$/i.test(parameters.restartArtifactSha256) ||
    !Number.isSafeInteger(Number(parameters.restartReport)) || Number(parameters.restartReport) <= 0) return null
  return {
    historyRunId: Number(parameters.historyRunId), forecastDataFile: parameters.forecastDataFile,
    restartArtifactName: parameters.restartArtifactName, restartArtifactSha256: parameters.restartArtifactSha256.toLowerCase(),
    restartReport: Number(parameters.restartReport)
  }
})
const forecastHistoryRun = ref(null)
const forecastHistoryLoading = ref(false)
const forecastHistoryError = ref('')
let forecastHistoryGeneration = 0
const loadForecastHistory = async () => {
  const generation = ++forecastHistoryGeneration
  forecastHistoryRun.value = null
  forecastHistoryError.value = ''
  forecastHistoryLoading.value = false
  const scenario = forecastScenario.value
  if (!scenario) return
  forecastHistoryLoading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(scenario.historyRunId)
    if (generation !== forecastHistoryGeneration) return
    const candidate = response?.data
    const artifactMatches = candidate?.artifacts?.some(artifact => artifact?.name === scenario.restartArtifactName &&
      typeof artifact.sha256 === 'string' && artifact.sha256.toLowerCase() === scenario.restartArtifactSha256)
    if (response?.code !== 200 || candidate?.id !== scenario.historyRunId || candidate.modelId !== props.run?.modelId ||
      candidate.modelVersionId !== props.run?.modelVersionId || candidate.runType !== 'eclipse' ||
      candidate.status !== 'SUCCEEDED' || candidate.resultContract !== 'VALID_FULL' ||
      (candidate.parameters !== null && candidate.parameters !== undefined) || !artifactMatches) {
      forecastHistoryError.value = '历史来源与当前模型版本不匹配，或成功历史 Run 未包含指定 SHA-256 的 FUNRST Artifact。'
      return
    }
    forecastHistoryRun.value = candidate
  } catch {
    if (generation === forecastHistoryGeneration) forecastHistoryError.value = '历史来源读取失败；请从同版本成功运行重新建立预测方案。'
  } finally {
    if (generation === forecastHistoryGeneration) forecastHistoryLoading.value = false
  }
}
watch([() => props.run?.id, () => props.run?.parameters?.historyRunId, () => props.run?.parameters?.restartArtifactSha256], loadForecastHistory, { immediate: true })
onBeforeUnmount(() => { forecastHistoryGeneration++ })
const forecastHistoryVerified = computed(() => Boolean(forecastScenario.value && forecastHistoryRun.value))
const isTerminalSuccess = computed(() => ['SUCCEEDED', 'PARTIAL_SUCCEEDED'].includes(props.run?.status))
const resultExpired = computed(() => props.run?.resultExpired === true)
const successfulResultUnavailable = computed(() => isTerminalSuccess.value && !result.value)
const isPartialResult = computed(() => result.value?.resultContract === 'VALID_PARTIAL' && props.run?.status === 'PARTIAL_SUCCEEDED')
const diagnosticMessages = computed(() => result.value?.messages || [])
const diagnosticCounts = computed(() => diagnosticMessages.value.reduce((counts, item) => {
  const key = item.severity === 'ERROR' ? 'errors' : item.severity === 'PROBLEM' ? 'problems' : item.severity === 'WARNING' ? 'warnings' : 'info'
  counts[key] += 1
  return counts
}, { problems: 0, warnings: 0, errors: 0, info: 0 }))
const diagnosticGroupDefinitions = [
  { key: 'well-convergence', label: '井势与井求解', pattern: /POTENTIAL IN WELL|WELL .*CONVERG|RESIDUAL ERROR IN FLOWS|RESIDUAL ERROR IN .*PRESSURE/i, review: '建议审阅涉及井的井底压力、流量残差、控制模式和井约束；平台不自动修改输入。' },
  { key: 'nonlinear-timestep', label: '非线性与时间步', pattern: /NON[- ]?LINEAR|TIME[- ]?STEP .*?(REDUC|SHORTEN|CUT)|TIMESTEP .*?(REDUC|SHORTEN|CUT)|CUTBACK|CHOP/i, review: '建议结合发生日期审阅时间步、非线性收敛设置和重启关系；平台只提供原文证据。' },
  { key: 'linear-solver', label: '线性方程收敛', pattern: /LINEAR EQUATION|LINEAR .*CONVERG|LITMAX|NRSTACK/i, review: '建议审阅线性求解迭代与相关求解器设置，并以官方手册确认适用范围。' },
  { key: 'pressure-pvt', label: '压力与 PVT 边界', pattern: /PVT|MAXIMUM PRESSURE|BOTTOM HOLE PRESSURE|PRESSURE .*LIMIT/i, review: '建议核对压力范围、PVT 表上限和井底压力限制的单位及来源；不补默认值。' },
  { key: 'other', label: '其他诊断', pattern: null, review: '建议打开下方原始诊断，依据官方手册和工程上下文人工判断。' }
]
const diagnosticGroupRows = computed(() => {
  const counts = new Map(diagnosticGroupDefinitions.map(definition => [definition.key, 0]))
  diagnosticMessages.value.forEach(item => {
    const text = `${item.code || ''} ${item.message || ''}`
    const definition = diagnosticGroupDefinitions.find(candidate => candidate.pattern?.test(text)) || diagnosticGroupDefinitions.at(-1)
    counts.set(definition.key, counts.get(definition.key) + 1)
  })
  return diagnosticGroupDefinitions.filter(definition => counts.get(definition.key) > 0).map(definition => ({
    ...definition,
    count: counts.get(definition.key)
  }))
})
const summaryNotice = computed(() => {
  if (!isTerminalSuccess.value || !result.value) return null
  if (isPartialResult.value) return `本次 ECLIPSE 已产出可读取结果，但 ECLEND 记录 ${counts.value?.problems || 0} 个 Problems；请先查看下方诊断后再用于工程决策。`
  if (hasUsableSummary.value) return null
  return result.value.summary === null
    ? '本次 ECLIPSE 运行已成功结束，但未提供 RSM Summary 数据；页面不会补充或推测曲线。'
    : '本次 ECLIPSE 运行已成功结束，但 RSM Summary 没有可用序列；页面不会补充或推测曲线。'
})
const scheduleTimelineSummary = computed(() => {
  const timeline = props.inspection?.scheduleTimeline
  if (!Array.isArray(timeline)) return null
  const dates = timeline.flatMap(event => event?.kind === 'DATES' && Array.isArray(event.records)
    ? event.records.filter(record => typeof record?.day === 'string' && typeof record?.month === 'string' && typeof record?.year === 'string')
    : [])
  const tstepCount = timeline.reduce((count, event) => count + (event?.kind === 'TSTEP' && Array.isArray(event.steps) ? event.steps.length : 0), 0)
  if (!dates.length && !tstepCount) return null
  const formatDate = record => `${record.day} ${record.month} ${record.year}${record.time ? ` ${record.time}` : ''}`
  return {
    dateCount: dates.length,
    firstDate: dates.length ? formatDate(dates[0]) : '-',
    lastDate: dates.length ? formatDate(dates[dates.length - 1]) : '-',
    tstepCount
  }
})
const scheduleCompletions = computed(() => {
  const metadata = props.inspection?.scheduleMetadata
  if (!['eclipse-data-inspection/4', 'eclipse-data-inspection/5', 'eclipse-data-inspection/6', 'eclipse-data-inspection/7', 'eclipse-data-inspection/8', 'eclipse-data-inspection/9', 'eclipse-data-inspection/10', 'eclipse-data-inspection/11', 'eclipse-data-inspection/12', 'eclipse-data-inspection/13'].includes(props.inspection?.schemaVersion) || !Array.isArray(metadata?.completions)) return []
  return metadata.completions.filter(completion => completion && typeof completion.well === 'string' &&
    ['COMPDAT', 'COMPDATM'].includes(completion.keyword) &&
    ['i', 'j', 'k1', 'k2', 'status'].every(key => typeof completion[key] === 'string'))
})
const scheduleScenario = computed(() => {
  const parameters = props.run?.parameters
  if (parameters?.schemaVersion === 'eclipse-group-gas-injection-parameters/1') {
    if (!validGroupGasInjection(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_GAS_INJECTION', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-group-injection-parameters/1') {
    if (!validGroupInjection(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_INJECTION', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-control-plan-parameters/1') {
    if (!validControlPlan(parameters)) return null
    return { ...parameters, scenarioType: 'CONTROL_PLAN', well: null, wells: [...new Set(parameters.edits.map(e => e.well))], date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-group-structure-parameters/1') {
    if (!validGroupStructure(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_STRUCTURE', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-advanced-injection-parameters/1') {
    if (!validAdvancedInjection(parameters)) return null
    return { ...parameters, scenarioType: 'ADVANCED_INJECTION', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-group-water-production-parameters/1') {
    if (!validGroupWaterProduction(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_WATER_PRODUCTION', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-group-rate-parameters/1') {
    if (!validGroupRate(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_RATE', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-group-production-parameters/1') {
    if (!validGroupProduction(parameters)) return null
    return { ...parameters, scenarioType: 'GROUP_PRODUCTION', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-tstep-parameters/1') {
    if (!validTstep(parameters)) return null
    return { ...parameters, scenarioType: 'TSTEP', well: null, date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-new-dates-parameters/1') {
    if (!validNewDates(parameters)) return null
    return { ...parameters, scenarioType: 'NEW_DATES', date: null, events: [{ date: parameters.shutDate, status: 'SHUT' }, { date: parameters.openDate, status: 'OPEN' }] }
  }
  if (parameters?.schemaVersion === 'eclipse-thp-control-parameters/1') {
    if (!validThpControl(parameters)) return null
    return { ...parameters, scenarioType: 'THP_CONTROL', status: 'OPEN', controlMode: 'THP', date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-production-control-parameters/1') {
    if (!validProductionControl(parameters)) return null
    return { ...parameters, scenarioType: 'PRODUCTION_CONTROL', status: 'OPEN', date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-pressure-control-parameters/1') {
    if (!validPressureControl(parameters)) return null
    return { ...parameters, scenarioType: 'PRESSURE_CONTROL', status: 'OPEN', controlMode: 'BHP', date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-production-target-parameters/1') {
    if (!validProductionTarget(parameters)) return null
    return { ...parameters, scenarioType: 'PRODUCTION_TARGET', status: 'OPEN', controlMode: 'ORAT', date: null }
  }
  if (parameters?.schemaVersion === 'eclipse-completion-factor-parameters/1') {
    if (!Number.isSafeInteger(Number(parameters.baselineRunId)) || Number(parameters.baselineRunId) <= 0 ||
      typeof parameters.well !== 'string' || typeof parameters.sourceFile !== 'string' ||
      !Number.isSafeInteger(Number(parameters.lineNumber)) || Number(parameters.lineNumber) <= 0 ||
      !['i', 'j', 'k1', 'k2'].every(key => typeof parameters[key] === 'string') ||
      !Number.isFinite(Number(parameters.originalConnectionFactor)) || Number(parameters.originalConnectionFactor) <= 0 ||
      !Number.isFinite(Number(parameters.targetConnectionFactor)) || Number(parameters.targetConnectionFactor) <= 0 ||
      Number(parameters.originalConnectionFactor) === Number(parameters.targetConnectionFactor)) return null
    return {
      baselineRunId: Number(parameters.baselineRunId), well: parameters.well, date: null, phase: null,
      status: null, scenarioType: 'COMPDAT_FACTOR', controlMode: null, targetOilRate: null,
      injectionType: null, targetInjectionRate: null, sourceFile: parameters.sourceFile,
      lineNumber: Number(parameters.lineNumber), i: parameters.i, j: parameters.j, k1: parameters.k1, k2: parameters.k2,
      originalConnectionFactor: Number(parameters.originalConnectionFactor), targetConnectionFactor: Number(parameters.targetConnectionFactor)
    }
  }
  if (parameters?.schemaVersion === 'eclipse-completion-parameters/1') {
    if (!Number.isSafeInteger(Number(parameters.baselineRunId)) || Number(parameters.baselineRunId) <= 0 ||
      typeof parameters.well !== 'string' || typeof parameters.sourceFile !== 'string' ||
      !Number.isSafeInteger(Number(parameters.lineNumber)) || Number(parameters.lineNumber) <= 0 ||
      !['i', 'j', 'k1', 'k2'].every(key => typeof parameters[key] === 'string') ||
      !['OPEN', 'SHUT'].includes(parameters.status)) return null
    return {
      baselineRunId: Number(parameters.baselineRunId), well: parameters.well, date: null, phase: null,
      status: parameters.status, scenarioType: 'COMPDAT', controlMode: null, targetOilRate: null,
      injectionType: null, targetInjectionRate: null, sourceFile: parameters.sourceFile,
      lineNumber: Number(parameters.lineNumber), i: parameters.i, j: parameters.j, k1: parameters.k1, k2: parameters.k2
    }
  }
  if (parameters?.schemaVersion === 'eclipse-schedule-parameters/5') {
    const events = parameters.events
    if (!Number.isSafeInteger(Number(parameters.baselineRunId)) || Number(parameters.baselineRunId) <= 0 ||
      typeof parameters.well !== 'string' || !Array.isArray(events) || events.length !== 2 ||
      !events.every(event => event && isIsoCalendarDate(event.date) && ['SHUT', 'OPEN'].includes(event.status)) ||
      events[0].status !== 'SHUT' || events[1].status !== 'OPEN' || events[0].date >= events[1].date) return null
    return {
      baselineRunId: Number(parameters.baselineRunId), well: parameters.well, date: null, phase: null,
      status: null, scenarioType: 'WELOPEN_EVENTS', events: events.map(event => ({ date: event.date, status: event.status })),
      controlMode: null, targetOilRate: null, injectionType: null, targetInjectionRate: null
    }
  }
  if (['eclipse-schedule-parameters/6', 'eclipse-schedule-parameters/7'].includes(parameters?.schemaVersion)) {
    if (!(parameters.schemaVersion.endsWith('/7') ? validateEclipseMultiDateEvents : validateEclipseWellEvents)(parameters)) return null
    return {
      baselineRunId: parameters.baselineRunId, well: parameters.wells.join('、'), wells: [...parameters.wells],
      date: null, phase: null, status: null, scenarioType: 'WELOPEN_EVENTS',
      events: parameters.events.map(event => ({ ...event })), controlMode: null,
      targetOilRate: null, injectionType: null, targetInjectionRate: null
    }
  }
  if (!['eclipse-schedule-parameters/1', 'eclipse-schedule-parameters/2', 'eclipse-schedule-parameters/3', 'eclipse-schedule-parameters/4'].includes(parameters?.schemaVersion) ||
    !Number.isSafeInteger(Number(parameters.baselineRunId)) || Number(parameters.baselineRunId) <= 0 ||
    typeof parameters.well !== 'string') return null
  const wconHist = parameters.schemaVersion === 'eclipse-schedule-parameters/2'
  const wconInje = parameters.schemaVersion === 'eclipse-schedule-parameters/3'
  const wconProd = parameters.schemaVersion === 'eclipse-schedule-parameters/4'
  if (!wconProd && typeof parameters.date !== 'string') return null
  if (!wconInje && !['OPEN', 'SHUT'].includes(parameters.status)) return null
  if (wconHist && (parameters.controlMode !== 'ORAT' || !Number.isFinite(Number(parameters.targetOilRate)) || Number(parameters.targetOilRate) <= 0)) return null
  if (wconInje && (typeof parameters.injectionType !== 'string' || !/^[A-Za-z][A-Za-z0-9_ -]{0,31}$/.test(parameters.injectionType) || parameters.controlMode !== 'RATE' || !Number.isFinite(Number(parameters.targetInjectionRate)) || Number(parameters.targetInjectionRate) <= 0)) return null
  if (wconProd && (parameters.phase !== 'FORECAST_INITIAL' || parameters.controlMode !== 'ORAT' || !Number.isFinite(Number(parameters.targetOilRate)) || Number(parameters.targetOilRate) <= 0)) return null
  return {
    baselineRunId: Number(parameters.baselineRunId),
    well: parameters.well,
    date: wconProd ? null : parameters.date,
    phase: wconProd ? parameters.phase : null,
    status: wconInje ? null : parameters.status,
    scenarioType: wconProd ? 'WCONPROD' : wconInje ? 'WCONINJE' : wconHist ? 'WCONHIST' : 'WELOPEN',
    controlMode: wconHist || wconInje || wconProd ? parameters.controlMode : null,
    targetOilRate: wconHist || wconProd ? Number(parameters.targetOilRate) : null,
    injectionType: wconInje ? parameters.injectionType : null,
    targetInjectionRate: wconInje ? Number(parameters.targetInjectionRate) : null
  }
})
const scheduleScenarioTitle = computed(() => ({
  CONTROL_PLAN: '多井分阶段产油目标验收',
  GROUP_STRUCTURE: '生产组树、井导向与开工比例验收',
  ADVANCED_INJECTION: 'GCONINJE 注入目标与回注比例验收',
  GROUP_WATER_PRODUCTION: 'GCONPROD 生产组产水方案验收',
  GROUP_RATE: 'GCONPROD 生产组气液方案验收',
  GROUP_PRODUCTION: 'GCONPROD 生产组控方案验收',
  GROUP_GAS_INJECTION: 'GCONINJE 注气组控方案验收',
  GROUP_INJECTION: 'GCONINJE 注入组控方案验收',
  TSTEP: 'TSTEP 报告间隔方案验收',
  THP_CONTROL: '井口压力 THP / VFP 方案验收',
  PRODUCTION_CONTROL: `${scheduleScenario.value?.controlMode || ''} 生产目标方案验收`,
  PRESSURE_CONTROL: '井底压力 BHP 方案验收',
  PRODUCTION_TARGET: '开井产油目标方案', COMPDAT_FACTOR: 'COMPDAT 连接因子方案验收', COMPDAT: 'COMPDAT 完井方案验收', WCONPROD: 'WCONPROD 方案验收',
  NEW_DATES: '新增日期停井／复产方案验收', WCONINJE: 'WCONINJE 方案验收', WCONHIST: 'WCONHIST 方案验收', WELOPEN_EVENTS: scheduleScenario.value?.events?.length > 2 ? 'WELOPEN 多日期调度方案验收' : 'WELOPEN 双事件调度方案验收', WELOPEN: 'WELOPEN 方案验收'
}[scheduleScenario.value?.scenarioType] || 'ECLIPSE Schedule 方案验收'))
const regionScenario = computed(() => validGridRegionParameters(props.run?.parameters) ? props.run.parameters : null)
const geometryScenario = computed(() => validCompletionGeometry(props.run?.parameters) ? props.run.parameters : null)
const newWellScenario = computed(() => validNewWellParameters(props.run?.parameters) ? props.run.parameters : null)
const newCompletionScenario = computed(() => validNewCompletionParameters(props.run?.parameters) ? props.run.parameters : null)
const relpermScenario = computed(() => validRelperm(props.run?.parameters) ? props.run.parameters : null)
const gasPvtScenario = computed(() => validGasPvt(props.run?.parameters) ? props.run.parameters : null)
const oilPvtScenario = computed(() => validOilPvt(props.run?.parameters) ? props.run.parameters : null)
const pvtwScenario = computed(() => validPvtw(props.run?.parameters) ? props.run.parameters : null)
const scheduleBaseline = ref(null)
const scheduleBaselineLoading = ref(false)
const scheduleBaselineError = ref('')
let scheduleBaselineGeneration = 0
const loadScheduleBaseline = async () => {
  const generation = ++scheduleBaselineGeneration
  scheduleBaseline.value = null
  scheduleBaselineError.value = ''
  scheduleBaselineLoading.value = false
  const scenario = scheduleScenario.value || regionScenario.value || geometryScenario.value || newWellScenario.value || newCompletionScenario.value || pvtwScenario.value || oilPvtScenario.value || gasPvtScenario.value || relpermScenario.value
  if (!scenario) return
  scheduleBaselineLoading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(scenario.baselineRunId)
    if (generation !== scheduleBaselineGeneration) return
    const candidate = response?.data
    if (response?.code !== 200 || candidate?.id !== scenario.baselineRunId ||
      candidate.modelId !== props.run?.modelId || candidate.modelVersionId !== props.run?.modelVersionId ||
      candidate.runType !== 'eclipse' || candidate.status !== 'SUCCEEDED' || !validateResult(candidate)) {
      scheduleBaselineError.value = '基线运行不存在、版本不一致或结果未通过校验。'
      return
    }
    scheduleBaseline.value = candidate
  } catch {
    if (generation === scheduleBaselineGeneration) scheduleBaselineError.value = '基线结果读取失败，请重新加载运行记录。'
  } finally {
    if (generation === scheduleBaselineGeneration) scheduleBaselineLoading.value = false
  }
}
watch([() => props.run?.id, () => props.run?.parameters?.baselineRunId], loadScheduleBaseline, { immediate: true })
const scheduleBaselineResult = computed(() => validateResult(scheduleBaseline.value))
const regionResponse = computed(() => regionScenario.value ? gridRegionResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const geometryResponse = computed(() => geometryScenario.value ? completionGeometryResponse(result.value?.summary, scheduleBaselineResult.value?.summary, geometryScenario.value.well) : null)
const newWellActual = computed(() => newWellScenario.value ? newWellOutcome(result.value?.summary, newWellScenario.value) : null)
const newWellDiff = computed(() => newWellScenario.value ? newWellResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const newCompletionDiff = computed(() => newCompletionScenario.value ? newCompletionResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const relpermDiff = computed(() => relpermScenario.value ? relpermResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const gasPvtDiff = computed(() => gasPvtScenario.value ? gasPvtResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const gasPvtDay = ref(null)
watch(() => props.run?.id, () => { gasPvtDay.value = null })
const gasPvtTimes = computed(() => [...new Set((gasPvtDiff.value?.rows || []).filter(row => row.difference != null).map(row => row.timeDays))].sort((a,b) => a-b))
const gasPvtRows = computed(() => (gasPvtDiff.value?.rows || []).filter(row => gasPvtDay.value == null || row.timeDays === gasPvtDay.value))
const oilPvtDiff = computed(() => oilPvtScenario.value ? oilPvtResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const pvtwDiff = computed(() => pvtwScenario.value ? pvtwResponse(result.value?.summary, scheduleBaselineResult.value?.summary) : null)
const factorUnit = computed(() => completionFactorUnit(props.inspection?.unitSystem))
const factorResponse = computed(() => scheduleScenario.value?.scenarioType === 'COMPDAT_FACTOR'
  ? completionFactorResponse(result.value?.summary, scheduleBaselineResult.value?.summary, scheduleScenario.value.well) : null)
const scheduleCounts = computed(() => {
  if (!result.value?.eclEnd || !scheduleBaselineResult.value?.eclEnd) return null
  return [['comments', 'Comments'], ['warnings', 'Warnings'], ['problems', 'Problems'], ['errors', 'Errors'], ['bugs', 'Bugs']]
    .map(([key, label]) => ({ key, label, baseline: scheduleBaselineResult.value.eclEnd[key] ?? 0, current: result.value.eclEnd[key] ?? 0 }))
})
const scheduleOutputDiff = computed(() => {
  if (!result.value?.outputFiles || !scheduleBaselineResult.value?.outputFiles) return null
  const current = new Map(result.value.outputFiles.map(file => [file.name, file]))
  const baseline = new Map(scheduleBaselineResult.value.outputFiles.map(file => [file.name, file]))
  const names = [...new Set([...baseline.keys(), ...current.keys()])].sort()
  const rows = names.map(name => {
    const before = baseline.get(name)
    const after = current.get(name)
    const same = Boolean(before && after && before.sizeBytes === after.sizeBytes && before.sha256 === after.sha256)
    return { name, status: same ? '相同' : before && after ? '已变化' : after ? '新增' : '缺失' }
  })
  return { rows, changedCount: rows.filter(row => row.status !== '相同').length }
})
const scheduleWellRateDiff = computed(() => {
  const scenario = scheduleScenario.value
  if (!['NEW_DATES', 'WELOPEN_EVENTS', 'PRODUCTION_TARGET', 'PRODUCTION_CONTROL', 'WCONHIST', 'WCONINJE', 'PRESSURE_CONTROL', 'THP_CONTROL'].includes(scenario?.scenarioType)) return null
  let keyword = scenario.scenarioType === 'WCONINJE' || scenario.keyword === 'WCONINJE'
    ? ({ WATER: 'WWIR', WAT: 'WWIR', GAS: 'WGIR', OIL: 'WOIR' }[scenario.injectionType?.toUpperCase()] || null) : 'WOPR'
  if (scenario.scenarioType === 'PRESSURE_CONTROL' && scenario.keyword === 'WCONPROD') {
    const controls = { ORAT: 'WOPR', WRAT: 'WWPR', GRAT: 'WGPR', LRAT: 'WLPR' }
    keyword = controls[scenario.originalControlMode] || ['WOPR', 'WGPR', 'WWPR', 'WLPR'].find(k => {
      const matches = (scheduleBaselineResult.value?.summary?.series || []).filter(s => s.keyword === k && s.objectName === scenario.well)
      return matches.length === 1 && matches[0].points?.some(p => Number.isFinite(p.value) && p.timeDays > 0 && p.value > 1e-9)
    }) || 'WOPR'
  }
  if (!keyword) return null
  if (scenario.scenarioType === 'PRODUCTION_CONTROL') keyword = { WRAT: 'WWPR', GRAT: 'WGPR', LRAT: 'WLPR' }[scenario.controlMode]
  if (scenario.scenarioType === 'THP_CONTROL') keyword = 'WLPR'
  const comparison = compareEclipseWellRates(result.value?.summary, scheduleBaselineResult.value?.summary, scenario.wells || [scenario.well], keyword)
  if (scenario.scenarioType === 'PRODUCTION_CONTROL' && !productionControlResultUnitMatches(comparison.unit, scenario.unit)) return { ...comparison, keyword, available: false, rows: [], changedCount: 0 }
  if (scenario.scenarioType === 'THP_CONTROL' && !productionControlResultUnitMatches(comparison.unit, 'STB/day')) return { ...comparison, keyword, available: false, rows: [], changedCount: 0 }
  return { ...comparison, keyword }
})
const schedulePressureKeyword = computed(() => scheduleScenario.value?.scenarioType === 'THP_CONTROL' ? 'WTHP' : 'WBHP')
const schedulePressureDiff = computed(() => {
  if (!['PRESSURE_CONTROL', 'THP_CONTROL'].includes(scheduleScenario.value?.scenarioType)) return null
  const diff = compareEclipseWellRates(result.value?.summary, scheduleBaselineResult.value?.summary, [scheduleScenario.value.well], schedulePressureKeyword.value)
  return scheduleScenario.value.scenarioType === 'THP_CONTROL' && diff.unit?.trim().toUpperCase() !== 'PSIA' ? { ...diff, available: false, rows: [], changedCount: 0 } : diff
})
const scheduleThpCoverage = computed(() => scheduleScenario.value?.scenarioType === 'THP_CONTROL'
  ? thpTableCoverage(result.value?.summary, scheduleScenario.value, props.inspection?.vfpTables) : null)
const scheduleThpBhpDiff = computed(() => scheduleScenario.value?.scenarioType === 'THP_CONTROL'
  ? compareEclipseWellRates(result.value?.summary, scheduleBaselineResult.value?.summary, [scheduleScenario.value.well], 'WBHP') : null)
const scheduleThpBaselineCoverage = computed(() => scheduleScenario.value?.scenarioType === 'THP_CONTROL'
  ? thpTableCoverage(scheduleBaselineResult.value?.summary, scheduleScenario.value, props.inspection?.vfpTables) : null)
const scheduleThpReached = computed(() => {
  if (!scheduleThpCoverage.value?.inRange || !scheduleThpBaselineCoverage.value?.inRange || !schedulePressureDiff.value?.available || !scheduleWellRateDiff.value?.available) return false
  const pressures = new Map(schedulePressureDiff.value.rows.filter(row => row.timeDays > 0 && Math.abs(row.difference) > 1e-9).map(row => [row.timeDays, row.current]))
  return scheduleWellRateDiff.value.rows.some(row => row.timeDays > 0 && row.current > 0 && Math.abs(row.difference) > 1e-9 && pressures.has(row.timeDays) &&
    Math.abs(pressures.get(row.timeDays) - scheduleScenario.value.targetThp) <= Math.max(0.1, scheduleScenario.value.targetThp * 1e-6))
})
const scheduleBhpResponse = computed(() => {
  if (!schedulePressureDiff.value?.available || !scheduleWellRateDiff.value?.available) return null
  const pressures = new Map(schedulePressureDiff.value.rows.filter(row => row.timeDays > 0).map(row => [row.timeDays, row.current]))
  const active = scheduleWellRateDiff.value.rows.filter(row => row.timeDays > 0 && row.current > 1e-9 && pressures.has(row.timeDays))
  return { active: active.length > 0, targetReached: active.some(row =>
    Math.abs(pressures.get(row.timeDays) - scheduleScenario.value.targetBhp) <= Math.max(0.1, scheduleScenario.value.targetBhp * 1e-6)) }
})
const scheduleRateComparisonRequired = computed(() => ['WELOPEN_EVENTS', 'PRODUCTION_TARGET', 'PRODUCTION_CONTROL', 'WCONHIST', 'WCONINJE', 'PRESSURE_CONTROL', 'THP_CONTROL'].includes(scheduleScenario.value?.scenarioType))
const scheduleProductionTargetReached = computed(() => scheduleWellRateDiff.value?.available && scheduleWellRateDiff.value.rows.some(row => row.timeDays > 0 && row.current > 0 && Math.abs(row.difference) > 1e-9 && Math.abs(row.current - scheduleScenario.value?.targetRate) <= Math.max(0.1, scheduleScenario.value.targetRate * 1e-6)))
const scheduleCountsChanged = computed(() => scheduleCounts.value?.some(item => item.baseline !== item.current) === true)
const scheduleNewDatesResponse = computed(() => scheduleScenario.value?.scenarioType === 'NEW_DATES' ? newDatesResponse(result.value?.summary, scheduleBaselineResult.value?.summary, scheduleScenario.value && props.run.parameters) : null)
const scheduleTstepResponse = computed(() => scheduleScenario.value?.scenarioType === 'TSTEP' ? tstepResponse(result.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleControlPlanResponse = computed(() => scheduleScenario.value?.scenarioType === 'CONTROL_PLAN' ? controlPlanResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupStructureResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_STRUCTURE' ? groupStructureResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleAdvancedInjectionResponse = computed(() => scheduleScenario.value?.scenarioType === 'ADVANCED_INJECTION' ? advancedInjectionResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupWaterProductionResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_WATER_PRODUCTION' ? groupWaterProductionResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupRateResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_RATE' ? groupRateResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupProductionResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_PRODUCTION' ? groupProductionResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupInjectionResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_INJECTION' ? groupInjectionResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleGroupGasInjectionResponse = computed(() => scheduleScenario.value?.scenarioType === 'GROUP_GAS_INJECTION' ? groupGasInjectionResponse(result.value?.summary, scheduleBaselineResult.value?.summary, props.run.parameters, props.inspection) : null)
const scheduleHasObservableDifference = computed(() => scheduleScenario.value?.scenarioType === 'CONTROL_PLAN' ? scheduleControlPlanResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_STRUCTURE' ? scheduleGroupStructureResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'ADVANCED_INJECTION' ? scheduleAdvancedInjectionResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_WATER_PRODUCTION' ? scheduleGroupWaterProductionResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_RATE' ? scheduleGroupRateResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_GAS_INJECTION' ? scheduleGroupGasInjectionResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_INJECTION' ? scheduleGroupInjectionResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'GROUP_PRODUCTION' ? scheduleGroupProductionResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'TSTEP' ? scheduleTstepResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'NEW_DATES' ? scheduleNewDatesResponse.value?.verified === true : scheduleScenario.value?.scenarioType === 'PRESSURE_CONTROL'
  ? schedulePressureDiff.value?.available === true && schedulePressureDiff.value.changedCount > 0 && scheduleWellRateDiff.value?.available === true && scheduleWellRateDiff.value.changedCount > 0 && scheduleBhpResponse.value?.targetReached === true
  : scheduleScenario.value?.scenarioType === 'THP_CONTROL' ? scheduleThpReached.value
  : scheduleScenario.value?.scenarioType === 'PRODUCTION_CONTROL' ? scheduleWellRateDiff.value?.available === true && scheduleWellRateDiff.value.changedCount > 0 && scheduleProductionTargetReached.value
  : scheduleRateComparisonRequired.value
  ? (scheduleWellRateDiff.value?.available === true && scheduleWellRateDiff.value.changedCount > 0)
  : Boolean(scheduleOutputDiff.value && (scheduleOutputDiff.value.changedCount > 0 || scheduleCountsChanged.value)))
const scheduleBaselineIsClean = computed(() => scheduleBaseline.value?.parameters === null || scheduleBaseline.value?.parameters === undefined)
const scheduleAcceptanceType = computed(() => scheduleBaselineLoading.value ? 'info' :
  scheduleBaselineError.value || !scheduleBaselineResult.value ? 'warning' :
  !scheduleBaselineIsClean.value || result.value?.resultContract !== 'VALID_FULL' ? 'warning' : scheduleHasObservableDifference.value ? 'success' : 'warning')
const scheduleAcceptanceLabel = computed(() => scheduleBaselineLoading.value ? '读取基线中' :
  scheduleBaselineError.value || !scheduleBaselineResult.value ? '无法比较' :
  !scheduleBaselineIsClean.value ? '基线不合规' : result.value?.resultContract !== 'VALID_FULL' ? '部分成功，需审阅诊断' :
    scheduleScenario.value?.scenarioType === 'TSTEP' ? scheduleTstepResponse.value?.verified ? '新报告时间点均已在真实结果中找到' : '报告时间点缺失或源绑定失效，需核对输出' :
    scheduleScenario.value?.scenarioType === 'CONTROL_PLAN' ? !scheduleControlPlanResponse.value?.available ? '缺少完整目标链或井油量、压力曲线，无法核对阶段' : !scheduleControlPlanResponse.value.consistent ? '阶段目标、生效链或井压力下限不一致，需核对' : scheduleControlPlanResponse.value.verified ? '分阶段目标达标，已核对后续覆盖与井压力' : scheduleControlPlanResponse.value.constrained ? '分阶段目标未达标：井已触及原 BHP 下限' : '阶段目标或逐项响应不足，需审阅后续控制' :
    scheduleScenario.value?.scenarioType === 'GROUP_STRUCTURE' ? !scheduleGroupStructureResponse.value?.available ? '缺少完整组树、累计量、井效率或压力曲线，无法验证' : !scheduleGroupStructureResponse.value.consistent ? '父子贡献、累计量或井限值不一致，需核对' : scheduleGroupStructureResponse.value.verified ? '组树目标达标，已验证井分配与开工比例影响' : scheduleGroupStructureResponse.value.constrained ? '目标或分配未达标：井已触及原压力或产油上限' : '目标或分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'ADVANCED_INJECTION' ? !scheduleAdvancedInjectionResponse.value?.available ? '缺少完整同时间注入、压力或来源生产曲线，无法验证' : !scheduleAdvancedInjectionResponse.value.consistent ? '组总量、来源产量或单井上限不一致，需核对' : scheduleAdvancedInjectionResponse.value.verified ? '注入目标达标，已核对各井分配与来源生产量' : scheduleAdvancedInjectionResponse.value.constrained ? scheduleAdvancedInjectionResponse.value.rows.some(r => r.targetReached) ? '部分时刻未达标：各井已触及原压力或流量上限' : '目标未达标：各井已触及原压力或流量上限' : '注入目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'GROUP_WATER_PRODUCTION' ? !scheduleGroupWaterProductionResponse.value?.available ? '缺少同时间的 GWPR、WWPR、WOPR 或 BHP，无法验证生产分配' : !scheduleGroupWaterProductionResponse.value.consistent ? '生产组总量、井归属或压力下限不一致，需核对' : scheduleGroupWaterProductionResponse.value.verified ? '生产组目标达标，已验证各井分配与压力下限' : scheduleGroupWaterProductionResponse.value.constrained ? scheduleGroupWaterProductionResponse.value.rows.some(r => r.targetReached) ? '生产组部分时刻未达标：各井已触及原压力或产油上限' : '生产组目标未达标：各井已触及原压力或产油上限' : '生产组目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'GROUP_RATE' ? !scheduleGroupRateResponse.value?.available ? '缺少同时间的 GGPR/GLPR、WGPR/WLPR 或 BHP，无法验证生产分配' : !scheduleGroupRateResponse.value.consistent ? '生产组总量、井归属或压力下限不一致，需核对' : scheduleGroupRateResponse.value.verified ? '生产组目标达标，已验证各井分配与压力下限' : scheduleGroupRateResponse.value.constrained ? scheduleGroupRateResponse.value.rows.some(r => r.targetReached) ? '生产组部分时刻未达标：各井已触及 BHP 下限' : '生产组目标未达标：各井已触及 BHP 下限' : '生产组目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'GROUP_GAS_INJECTION' ? !scheduleGroupGasInjectionResponse.value?.available ? '缺少同时间的 GGIR、WGIR 或 BHP，无法验证注入分配' : !scheduleGroupGasInjectionResponse.value.consistent ? '注气组总量、井归属或压力上限不一致，需核对' : scheduleGroupGasInjectionResponse.value.verified ? '组总注气达标，已验证各井分配与压力上限' : scheduleGroupGasInjectionResponse.value.constrained ? scheduleGroupGasInjectionResponse.value.rows.some(r => r.targetReached) ? '注气组部分时刻未达标：各井已触及 BHP 上限' : '注气组目标未达标：各井已触及 BHP 上限' : '注气组目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'GROUP_INJECTION' ? !scheduleGroupInjectionResponse.value?.available ? '缺少同时间的 GWIR、WWIR 或 BHP，无法验证注入分配' : !scheduleGroupInjectionResponse.value.consistent ? '注水组总量、井归属或压力上限不一致，需核对' : scheduleGroupInjectionResponse.value.verified ? '组总注水达标，已验证各井分配与压力上限' : scheduleGroupInjectionResponse.value.constrained ? '注水组目标未达标：各井已触及 BHP 上限' : '注水组目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'GROUP_PRODUCTION' ? !scheduleGroupProductionResponse.value?.available ? '缺少同时间的组、井流量或 BHP，无法验证分配' : !scheduleGroupProductionResponse.value.consistent ? '组总量、井归属或压力边界不一致，需核对' : scheduleGroupProductionResponse.value.verified ? '组总产油达标，已验证各井分配与压力下限' : scheduleGroupProductionResponse.value.constrained ? '组目标未达标：各井已触及 BHP 下限' : '组目标或井分配响应不足，需审阅约束' :
    scheduleScenario.value?.scenarioType === 'NEW_DATES' ? !scheduleNewDatesResponse.value?.available ? '缺少真实日历与 WOPR，需核对报告日期' : scheduleNewDatesResponse.value.verified ? '检测到停井零产量与复产非零响应' : '停井或复产响应不足，需审阅控制约束' :
    scheduleRateComparisonRequired.value && !scheduleWellRateDiff.value?.available ? '缺少可对齐目标井流量' :
      scheduleScenario.value?.scenarioType === 'PRESSURE_CONTROL' ? (!schedulePressureDiff.value?.available ? '缺少可对齐井底压力' : !scheduleBhpResponse.value?.active ? '目标井对照流量为零，需审阅压力约束' : scheduleHasObservableDifference.value ? '检测到井底压力与流量差异' : schedulePressureDiff.value.changedCount > 0 && scheduleWellRateDiff.value.changedCount > 0 ? 'BHP未达到目标，需审阅其它约束' : '压力或流量未发生差异，需审阅约束') :
      scheduleScenario.value?.scenarioType === 'THP_CONTROL' ? !schedulePressureDiff.value?.available ? '缺少可对齐井口压力 WTHP' : !scheduleThpCoverage.value?.available || !scheduleThpBaselineCoverage.value?.available ? '缺少核对 VFP 范围的完整流量与压力' : !scheduleThpCoverage.value.inRange || !scheduleThpBaselineCoverage.value.inRange ? '工况超出 VFP 表范围，需审阅外推' : scheduleThpReached.value ? '井口压力达到新目标且总液产量发生变化' : 'THP 未达到目标或产量无变化，需审阅其它约束' :
      scheduleScenario.value?.scenarioType === 'PRODUCTION_CONTROL' ? scheduleHasObservableDifference.value ? '检测到目标流量差异并达到新目标' : '目标流量未达到或无差异，需审阅其它约束' :
      scheduleHasObservableDifference.value ? (scheduleScenario.value?.scenarioType === 'WCONINJE' ? '检测到目标井注入量差异' : '检测到目标井产油率差异') : '未检测到目标井流量差异')
const safeCode = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(value) ? value : null
const cleanupFields = new Map([
  ['processTreeExitConfirmed', '进程树已退出'],
  ['inputDeleted', '输入副本已删除'],
  ['workDirectoryDeleted', '工作目录已删除'],
  ['killUsed', '已执行终止清理']
])
const cleanupEntries = computed(() => {
  const cleanup = props.run?.cleanup
  if (!cleanup || typeof cleanup !== 'object' || Array.isArray(cleanup)) return []
  return [...cleanupFields].flatMap(([key, label]) => typeof cleanup[key] === 'boolean'
    ? [{ key: label, value: cleanup[key] ? '是' : '否' }]
    : [])
})
const allowedErrors = new Map([
  ['ECLIPSE_UNAVAILABLE', 'ENVIRONMENT'],
  ['ECLIPSE_VERSION_MISMATCH', 'ENVIRONMENT'],
  ['ECLIPSE_INCLUDE_UNSUPPORTED', 'MODEL'],
  ['ECLIPSE_MAIN_OUTSIDE_PACKAGE', 'MODEL'],
  ['ECLIPSE_INCLUDE_MISSING', 'MODEL'],
  ['ECLIPSE_INCLUDE_OUTSIDE_PACKAGE', 'MODEL'],
  ['ECLIPSE_INCLUDE_PATH_INVALID', 'MODEL'],
  ['ECLIPSE_INCLUDE_CYCLE', 'MODEL'],
  ['ECLIPSE_INCLUDE_COUNT', 'MODEL'],
  ['ECLIPSE_INCLUDE_DEPTH', 'MODEL'],
  ['ECLIPSE_INCLUDE_TOO_LARGE', 'MODEL'],
  ['ECLIPSE_INCLUDE_REPARSE_POINT', 'MODEL'],
  ['ECLIPSE_INCLUDE_INVALID_UTF8', 'MODEL'],
  ['ECLIPSE_INCLUDE_READ_FAILED', 'MODEL'],
  ['ECLIPSE_INCLUDE_SYNTAX', 'MODEL'],
  ['ECLIPSE_PACKAGE_INTEGRITY_MISMATCH', 'MODEL'],
  ['ECLIPSE_CLEANUP_FAILED', 'CLEANUP'],
  ['PROCESS_TREE_EXIT_UNCONFIRMED', 'CLEANUP'],
  ['ECLIPSE_RUN_FAILED', 'EXECUTION'],
  ['ECLIPSE_SOLVER_FAILED', 'SOLVER'],
  ['LICENSE_UNAVAILABLE', 'LICENSE']
])
const error = computed(() => {
  const value = props.run?.error
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const category = safeCode(value.category)
  const code = safeCode(value.code)
  if (!code || allowedErrors.get(code) !== category) return null
  return {
    category,
    code,
    retryable: value.retryable === true
  }
})
const failedRunGuidance = computed(() => error.value?.retryable
  ? '请确认运行环境后，从运行页面重新提交该版本。'
  : '请检查模型版本的验证状态或选择其他已验证版本。')
const outputFiles = computed(() => result.value?.outputFiles || [])
const gridMetadata = computed(() => result.value?.grid || null)
const fieldIndex = computed(() => result.value?.fieldIndex || null)
const fieldRows = computed(() => (fieldIndex.value?.files || []).flatMap(file => (file.fields || []).map(field => ({
  file: file.name,
  byteOrder: file.byteOrder,
  ...field
}))))
const fieldFileFilter = ref('all')
const fieldTimeStepFilter = ref('all')
const fieldKeywordFilter = ref('')
const fieldIndexPage = ref(1)
const fieldIndexPageSize = ref(25)
const fieldFileOptions = computed(() => [...new Set(fieldRows.value.map(field => field.file))])
const fieldTimeStepOptions = computed(() => [...new Set(fieldRows.value
  .map(field => Number.isSafeInteger(field.timeStep) && field.timeStep > 0 ? field.timeStep : null)
  .filter(value => value !== null))].sort((left, right) => left - right))
const filteredFieldRows = computed(() => {
  const keyword = fieldKeywordFilter.value.trim().toUpperCase()
  return fieldRows.value.filter(field =>
    (fieldFileFilter.value === 'all' || field.file === fieldFileFilter.value) &&
    (fieldTimeStepFilter.value === 'all' ||
      fieldTimeStepFilter.value === 'static' && field.timeStep === undefined ||
      Number(fieldTimeStepFilter.value) === field.timeStep) &&
    (!keyword || field.keyword.includes(keyword)))
})
const pagedFieldRows = computed(() => {
  const start = (fieldIndexPage.value - 1) * fieldIndexPageSize.value
  return filteredFieldRows.value.slice(start, start + fieldIndexPageSize.value)
})
const artifacts = computed(() => props.run?.artifacts || [])
const hasArtifactForField = field => artifacts.value.some(artifact => artifact?.name === `eclipse-output-${field.file}` && artifact?.contentType === 'application/octet-stream' && Number.isSafeInteger(artifact.id) && artifact.id > 0)
const egridGeometryFields = computed(() => fieldRows.value.filter(field => field.file?.toUpperCase().endsWith('.EGRID') && ['COORD', 'ZCORN', 'ACTNUM'].includes(field.keyword)))
const hasGridGeometryFields = computed(() => {
  return ['COORD', 'ZCORN', 'ACTNUM'].every(keyword => egridGeometryFields.value.some(field => field.keyword === keyword))
})
const hasGridGeometryArtifact = computed(() => hasGridGeometryFields.value && egridGeometryFields.value.every(hasArtifactForField))
const hasRenderableGridField = computed(() => fieldRows.value.some(field =>
  ['REAL', 'DOUB', 'INTE', 'LOGI'].includes(field.dataType) && !['COORD', 'ZCORN', 'ACTNUM'].includes(field.keyword) &&
  (field.file?.toUpperCase().endsWith('.EGRID') || field.file?.toUpperCase().endsWith('.INIT') || field.file?.toUpperCase().endsWith('.UNRST')) && hasArtifactForField(field)))
const threeDReadiness = computed(() => {
  const missing = []
  if (!gridMetadata.value) missing.push('EGRID 网格元数据')
  if (!hasGridGeometryArtifact.value) missing.push('EGRID Artifact')
  if (!hasGridGeometryFields.value) missing.push('EGRID 的 COORD/ZCORN/ACTNUM 字段')
  if (!hasRenderableGridField.value) missing.push('EGRID/INIT/UNRST 可渲染场值字段')
  return { ready: missing.length === 0, missing }
})
const threeDReadinessLabel = computed(() => threeDReadiness.value.ready ? '可进入三维' : '三维数据未齐')
const threeDReadinessDescription = computed(() => threeDReadiness.value.ready
  ? `真实 EGRID ${gridMetadata.value.nx} × ${gridMetadata.value.ny} × ${gridMetadata.value.nz}，可读取场值并进入三维。`
  : `本次运行不能显示三维：缺少 ${threeDReadiness.value.missing.join('、')}。请使用 BRILLIG.DATA 验收三维。`)
const scrollToResult = id => {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const formatSize = value => Number.isFinite(Number(value)) ? `${Number(value).toLocaleString()} B` : '-'
const fieldPreview = ref(null)
const selectedField = ref(null)
const fieldPreviewPage = ref(1)
const fieldPreviewPageSize = 200
const fieldPreviewLoading = ref(false)
const fieldPreviewError = ref('')
const previewRequests = createFieldRequestGate()
const artifactDownloadPending = ref(null)
const downloadableArtifacts = computed(() => artifacts.value.map(artifact => ({
  ...artifact,
  downloadable: Number.isSafeInteger(artifact?.id) && artifact.id > 0 && typeof artifact?.name === 'string' && artifact.name.length > 0
})))
const downloadArtifact = async artifact => {
  if (!props.run?.id || !artifact?.downloadable || artifactDownloadPending.value) return
  artifactDownloadPending.value = artifact.id
  try {
    const payload = await softwareIntegrationApi.downloadArtifact(props.run.id, artifact.id)
    const blob = payload instanceof Blob ? payload : new Blob([payload], { type: artifact.contentType || 'application/octet-stream' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = artifact.name || 'artifact.bin'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('Artifact 下载失败，请检查运行记录和有效期。')
  } finally {
    artifactDownloadPending.value = null
  }
}
const fieldArtifact = field => artifacts.value.find(artifact => artifact?.name === `eclipse-output-${field.file}` && artifact?.contentType === 'application/octet-stream')
const decodeFieldValues = (bytes, field, byteOrder) => {
  const values = []
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const littleEndian = byteOrder === 'LITTLE'
  const elementSize = field.elementSize
  for (let offset = 0; offset + elementSize <= bytes.byteLength; offset += elementSize) {
    if (field.dataType === 'REAL') values.push(view.getFloat32(offset, littleEndian))
    else if (field.dataType === 'DOUB') values.push(view.getFloat64(offset, littleEndian))
    else if (field.dataType === 'INTE' || field.dataType === 'LOGI') values.push(view.getInt32(offset, littleEndian))
    else if (field.dataType === 'CHAR') values.push(new TextDecoder('ascii').decode(bytes.slice(offset, offset + elementSize)).replace(/\0/g, '').trim())
  }
  return values
}
const previewField = async (field, resetPage = true) => {
  const isCurrent = previewRequests.begin()
  const runId = props.run?.id
  field = snapshotField(field)
  fieldPreviewLoading.value = false
  fieldPreview.value = null
  fieldPreviewError.value = ''
  if (!runId || !field) return
  if (resetPage) {
    selectedField.value = snapshotField(field)
    fieldPreviewPage.value = 1
  }
  const sourceArtifact = fieldArtifact(field)
  const artifact = sourceArtifact ? { ...sourceArtifact } : null
  const page = fieldPreviewPage.value
  if (!artifact?.id) {
    fieldPreview.value = null
    fieldPreviewError.value = '对应的二进制 Artifact 不可用或已过期。'
    return
  }
  fieldPreviewLoading.value = true
  fieldPreviewError.value = ''
  fieldPreview.value = null
  try {
    const values = []
    const startValue = (page - 1) * fieldPreviewPageSize
    const maxValues = Math.min(field.count - startValue, fieldPreviewPageSize)
    let skippedValues = startValue
    for (const segment of field.segments || []) {
      if (values.length >= maxValues) break
      const segmentValues = Math.floor(segment.length / field.elementSize)
      if (skippedValues >= segmentValues) {
        skippedValues -= segmentValues
        continue
      }
      const segmentOffset = segment.offset + skippedValues * field.elementSize
      const remainingBytes = (maxValues - values.length) * field.elementSize
      const availableBytes = (segmentValues - skippedValues) * field.elementSize
      const requested = Math.min(availableBytes, remainingBytes)
      const alignedLength = requested - (requested % field.elementSize)
      if (alignedLength <= 0) continue
      if (!isCurrent()) return
      const payload = await softwareIntegrationApi.downloadArtifactRange(runId, artifact.id, segmentOffset, alignedLength)
      const blob = payload instanceof Blob ? payload : new Blob([payload])
      const bytes = new Uint8Array(await blob.arrayBuffer())
      if (!isCurrent()) return
      values.push(...decodeFieldValues(bytes, field, field.byteOrder))
      skippedValues = 0
    }
    if (!isCurrent()) return
    fieldPreview.value = {
      ...field,
      artifactName: artifact.name,
      page,
      startValue,
      values: values.slice(0, maxValues)
    }
  } catch {
    if (isCurrent()) fieldPreviewError.value = '二进制场值读取失败，请检查 Artifact 有效期或重新加载运行记录。'
  } finally {
    if (isCurrent()) fieldPreviewLoading.value = false
  }
}
const loadFieldPreviewPage = page => {
  fieldPreviewPage.value = page
  if (selectedField.value) previewField(selectedField.value, false)
}
watch(() => props.run, () => {
  previewRequests.invalidate()
  fieldPreviewLoading.value = false
  fieldPreview.value = null
  selectedField.value = null
  fieldPreviewPage.value = 1
  fieldPreviewError.value = ''
  fieldFileFilter.value = 'all'
  fieldTimeStepFilter.value = 'all'
  fieldKeywordFilter.value = ''
  fieldIndexPage.value = 1
}, { deep: true, flush: 'sync' })
watch([fieldFileFilter, fieldTimeStepFilter, fieldKeywordFilter], () => {
  previewRequests.invalidate()
  fieldPreview.value = null
  selectedField.value = null
  fieldPreviewPage.value = 1
  fieldPreviewLoading.value = false
  fieldPreviewError.value = ''
}, { flush: 'sync' })
onBeforeUnmount(() => { previewRequests.dispose() })
watch([fieldFileFilter, fieldTimeStepFilter, fieldKeywordFilter, fieldIndexPageSize], () => { fieldIndexPage.value = 1 })
watch(() => filteredFieldRows.value.length, total => {
  const lastPage = Math.max(1, Math.ceil(total / fieldIndexPageSize.value))
  if (fieldIndexPage.value > lastPage) fieldIndexPage.value = lastPage
})
</script>

<template>
  <section class="eclipse-result">
    <EclipseHistoryPanel :run="run" @select-run="emit('select-run', $event)" />
    <EclipseBatchPanel ref="batchPanel" :run="run" :inspection="inspection" @select-run="emit('select-run', $event)" />
    <el-result v-if="!result" icon="info" :title="resultExpired ? '本次 ECLIPSE 结果已超过 30 天保留期限' : (successfulResultUnavailable ? '本次 ECLIPSE 运行已成功结束，但可验证运行结果不可用' : '尚无已验证真实计算结果')" :sub-title="resultExpired ? '运行状态、ECLEND 审计和事件仍保留；解析结果和原始结果包按保留策略不可再展示。' : (successfulResultUnavailable ? '未接收到符合结果契约的运行结果，因此无法确认是否存在 RSM Summary，也不会展示推测数据。' : (error ? failedRunGuidance : '尚未选择成功运行；仅在真实运行通过结果契约后展示 ECLEND、Summary 和 Artifact。'))">
      <template v-if="error" #extra>
        <el-tag type="danger">{{ error.category }} / {{ error.code }}</el-tag>
      </template>
    </el-result>
    <template v-else>
    <el-alert v-if="summaryNotice" :type="isPartialResult ? 'warning' : 'info'" :closable="false" :title="summaryNotice" />
    <EclipseCompletionGeometryEditor :run="run" :inspection="inspection" @create-completion-geometry-run="emit('create-completion-geometry-run', $event)" />
    <EclipseNewWellEditor :run="run" :inspection="inspection" :grid="gridMetadata" :fields="fieldRows" :artifacts="artifacts" @create-new-well-run="emit('create-new-well-run', $event)" />
    <EclipseNewCompletionEditor :run="run" :inspection="inspection" :grid="gridMetadata" :fields="fieldRows" :artifacts="artifacts" @create-new-completion-run="emit('create-new-completion-run', $event)" />
    <EclipseRelpermEditor :run="run" :inspection="inspection" @create-relperm-run="emit('create-relperm-run', $event)" />
    <section v-if="relpermScenario" class="result-panel" aria-label="ECLIPSE 相渗方案验收">
      <h2>相渗方案验收</h2>
      <p>基线 #{{relpermScenario.baselineRunId}} · {{relpermScenario.keyword}} · {{relpermScenario.rows.length}} 行相渗 × {{relpermScenario.multiplier}} · 无量纲。饱和度、毛管压力、PVT和原井控保留；SOF3两列联动。</p>
      <el-alert v-if="scheduleBaselineError" type="error" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!relpermDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取基线中' : relpermDiff?.message || '缺少实际可比曲线'" />
      <template v-else>
        <p>{{relpermDiff.rows.length}} 项实际井级数值，{{relpermDiff.changedCount}} 项变化，{{relpermDiff.unmatchedCount}} 项缺少同时间值。仅同时间、同单位给出差值，不推算产量。</p>
        <el-table :data="relpermDiff.rows" max-height="320" border size="small" aria-label="ECLIPSE 相渗井级响应">
          <el-table-column prop="well" label="井"/><el-table-column prop="keyword" label="指标"/><el-table-column prop="timeDays" label="时间（天）"/><el-table-column prop="unit" label="单位"/>
          <el-table-column prop="baseline" label="原基线"/><el-table-column prop="current" label="方案"/><el-table-column prop="difference" label="实际差值"/>
        </el-table>
      </template>
    </section>
    <EclipseGasPvtEditor :run="run" :inspection="inspection" @create-gas-pvt-run="emit('create-gas-pvt-run', $event)" />
    <section v-if="gasPvtScenario" class="result-panel" aria-label="ECLIPSE 气物性方案验收">
      <h2>气黏度方案验收</h2>
      <p>基线 #{{ gasPvtScenario.baselineRunId }} · 全部 {{ gasPvtScenario.rows.length }} 行气黏度 × {{ gasPvtScenario.multiplier }} · cP。压力与气体积系数原列、原井控及地质网格保留。</p>
      <el-alert v-if="scheduleBaselineError" type="error" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!gasPvtDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取基线中' : gasPvtDiff?.message || '缺少实际可比曲线'" />
      <template v-else>
        <p>{{ gasPvtDiff.rows.length }} 项实际井级数值，{{ gasPvtDiff.changedCount }} 项变化，{{ gasPvtDiff.unmatchedCount }} 项缺少同时间值。内部时间步可不同，仅同时间、同单位给出差值；缺少对应点保留空值。只显示工程实际请求的井压力、注采气量、累计量及水量。</p>
        <el-select v-model="gasPvtDay" clearable placeholder="全部报告时刻" aria-label="气物性对比时间">
          <el-option v-for="day in gasPvtTimes" :key="day" :value="day" :label="`${day}天`" />
        </el-select>
        <el-table :data="gasPvtRows" max-height="320" border size="small" aria-label="ECLIPSE 气物性井级响应">
          <el-table-column prop="well" label="井" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="unit" label="单位" />
          <el-table-column prop="timeDays" label="天" /><el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="差值" />
        </el-table>
      </template>
    </section>
    <EclipseOilPvtEditor :run="run" :inspection="inspection" @create-oil-pvt-run="emit('create-oil-pvt-run', $event)" />
    <section v-if="oilPvtScenario" class="result-panel" aria-label="ECLIPSE 油物性方案验收">
      <h2>油黏度方案验收</h2>
      <p>基线 #{{ oilPvtScenario.baselineRunId }} · 全部 {{ oilPvtScenario.rows.length }} 行油黏度 × {{ oilPvtScenario.multiplier }} · cP。压力与油体积系数原列、原井控及地质网格保留。</p>
      <el-alert v-if="scheduleBaselineError" type="error" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!oilPvtDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取基线中' : oilPvtDiff?.message || '缺少实际可比曲线'" />
      <template v-else>
        <p>{{ oilPvtDiff.rows.length }} 项实际井级数值，{{ oilPvtDiff.changedCount }} 项变化，{{ oilPvtDiff.unmatchedCount }} 项缺少同时间值。受压力下限约束时，请比较实际产油、产水及总液量。</p>
        <el-table :data="oilPvtDiff.rows" max-height="320" border size="small" aria-label="ECLIPSE 油物性井级响应">
          <el-table-column prop="well" label="井" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="unit" label="单位" />
          <el-table-column prop="timeDays" label="天" /><el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="差值" />
        </el-table>
      </template>
    </section>
    <EclipsePvtwEditor :run="run" :inspection="inspection" @create-pvtw-run="emit('create-pvtw-run', $event)" />
    <section v-if="pvtwScenario" class="result-panel" aria-label="ECLIPSE 水物性方案验收">
      <h2>{{ pvtwLabels[pvtwScenario.field] }}方案验收</h2>
      <p>基线 #{{ pvtwScenario.baselineRunId }} · {{ pvtwScenario.originalValue }} → {{ pvtwScenario.targetValue }} {{ pvtwScenario.unit }}。作用于全模型唯一水PVT区；原井控及地质网格保持。</p>
      <el-alert v-if="scheduleBaselineError" type="error" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!pvtwDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取基线中' : pvtwDiff?.message || '缺少实际可比曲线'" />
      <template v-else>
        <p>{{ pvtwDiff.rows.length }} 项实际井级数值，{{ pvtwDiff.changedCount }} 项变化，{{ pvtwDiff.unmatchedCount }} 项缺少同时间值。定产量井请比较井底压力；井状态请查看原始Summary。</p>
        <el-table :data="pvtwDiff.rows" max-height="320" border size="small" aria-label="ECLIPSE 水物性井级响应">
          <el-table-column prop="well" label="井" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="unit" label="单位" />
          <el-table-column prop="timeDays" label="天" /><el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="差值" />
        </el-table>
      </template>
      <p>下载输入工程ZIP，在另一项目选择包内DATA作为主文件并直接计算，可复核物性修改与结果。</p>
    </section>
    <section v-if="newCompletionScenario" class="result-panel" aria-label="ECLIPSE 新完井方案验收">
      <h2>已有井 {{ newCompletionScenario.well }} · 新增完井连接</h2>
      <p>基线 #{{ newCompletionScenario.baselineRunId }} · 新连接 I{{ newCompletionScenario.i }}/J{{ newCompletionScenario.j }}/K{{ newCompletionScenario.k }}。原井口、压力基准、井组和井控保留。</p>
      <p>井径{{ newCompletionScenario.diameter }} {{ newCompletionScenario.lengthUnit }}、KH{{ newCompletionScenario.kh }} mD.{{ newCompletionScenario.lengthUnit }}、皮肤{{ newCompletionScenario.skin }}，CF自动计算；原地质输入和网格几何保持。</p>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!newCompletionDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取原井基线中' : newCompletionDiff?.message || '缺少可对比实际结果'" />
      <template v-else>
        <p>{{ newCompletionDiff.rows.length }} 项实际报告数值，{{ newCompletionDiff.changedCount }} 项共有时刻变化，{{ newCompletionDiff.unmatchedCount }} 项缺少同时间值。控量井可能表现为压力变化，不能把新增连接直接算作增产。</p>
        <el-table :data="newCompletionDiff.rows" max-height="320" border size="small" aria-label="ECLIPSE 新完井井级响应">
          <el-table-column prop="keyword" label="曲线" /><el-table-column prop="well" label="井" /><el-table-column prop="unit" label="单位" /><el-table-column prop="timeDays" label="天数" />
          <el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="同时间差值" />
        </el-table>
      </template>
      <p>下载eclipse-schedule-applied-package.zip，另一项目上传完整工程无参数复算；检查原连接保留且新增连接坐标正确。多连接轨迹、后期射孔和复杂工程继续扩展。</p>
    </section>
    <section v-if="newWellScenario" class="result-panel" aria-label="ECLIPSE 新井方案验收">
      <h2>新井 {{ newWellScenario.well }} · 组 {{ newWellScenario.group }}</h2>
      <p>基线 #{{ newWellScenario.baselineRunId }} · I{{ newWellScenario.i }}/J{{ newWellScenario.j }}/K{{ newWellScenario.k }} · 基准深度{{ newWellScenario.referenceDepth }} {{ newWellScenario.lengthUnit }}。</p>
      <p>井径{{ newWellScenario.diameter }} {{ newWellScenario.lengthUnit }}、KH{{ newWellScenario.kh }} mD.{{ newWellScenario.lengthUnit }}、皮肤{{ newWellScenario.skin }}，CF自动计算；产水目标{{ newWellScenario.waterRate }} {{ newWellScenario.rateUnit }}、BHP下限{{ newWellScenario.bhpLimit }} {{ newWellScenario.pressureUnit }}。</p>
      <el-alert v-if="!newWellActual?.available" type="warning" :closable="false" :title="newWellActual?.message || '缺少新井实际结果'" />
      <template v-else>
        <p>实际{{ newWellActual.rows.length }}个报告，{{ newWellActual.limitedReports }}项压力约束下减产，{{ newWellActual.shutReports }}项关井/停井报告。状态取自原生WSTAT；关井压力可能输出0，不套用开井压力下限。新井在原基线不存在，不把其基线填为0。</p>
        <el-table :data="newWellActual.rows" max-height="320" border size="small" aria-label="ECLIPSE 新井实际生产与状态">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="stateLabel" label="实际井状态" /><el-table-column prop="state" label="WSTAT" />
          <el-table-column prop="rate" :label="`WWPR ${newWellScenario.rateUnit}`" /><el-table-column prop="bhp" :label="`WBHP ${newWellScenario.pressureUnit}`" /><el-table-column prop="total" :label="`WWPT ${newWellActual.cumulativeUnit}`" />
        </el-table>
      </template>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!newWellDiff?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取原井基线中' : newWellDiff?.message || '缺少原井可对比结果'" />
      <el-table v-if="newWellDiff?.available && !scheduleBaselineError" :data="newWellDiff.rows" max-height="280" border size="small" aria-label="ECLIPSE 新井对原井影响">
        <el-table-column prop="keyword" label="曲线" /><el-table-column prop="well" label="井" /><el-table-column prop="unit" label="单位" /><el-table-column prop="timeDays" label="天数" />
        <el-table-column prop="baseline" label="基线（新井为空）" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="共有时刻差值" />
      </el-table>
      <p>审阅求解警告与原井影响，下载eclipse-schedule-applied-package.zip，另一项目上传完整工程无参数复算。原地质与已有井控保持，新增井会改变油藏压力和实际供给分配。</p>
    </section>
    <section v-if="geometryScenario" class="result-panel" aria-label="ECLIPSE 完井数值方案验收">
      <h2>{{ completionGeometryLabels[geometryScenario.field] }}方案验收</h2>
      <p>井 {{ geometryScenario.well.trim() }} · {{ geometryScenario.sourceFile }}:{{ geometryScenario.lineNumber }} · I{{ geometryScenario.i }}/J{{ geometryScenario.j }}/K{{ geometryScenario.k1 }}</p>
      <p>基线 #{{ geometryScenario.baselineRunId }} · {{ geometryScenario.originalValue }} → {{ geometryScenario.targetValue }} {{ geometryScenario.unit === '1' ? '（无量纲）' : geometryScenario.unit }} · CF保持自动计算。</p>
      <p>核对井底压力、产量与累计量；原控制可能维持原流量。下载 eclipse-schedule-applied-package.zip，另一项目上传完整工程、不启用方案复算。</p>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!geometryResponse?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取同源基线中' : geometryResponse?.message || '缺少可对比结果'" />
      <template v-else>
        <p>{{ geometryResponse.rows.length }} 项实际报告数值，{{ geometryResponse.changedCount }} 项共有时刻变化，{{ geometryResponse.unmatchedCount }} 项缺少同时间值；空差值不插值。</p>
        <el-table :data="geometryResponse.rows" max-height="320" border size="small" aria-label="ECLIPSE 完井数值井级响应">
          <el-table-column prop="keyword" label="曲线" /><el-table-column prop="well" label="井" /><el-table-column prop="unit" label="单位" />
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="实际差值" />
        </el-table>
      </template>
    </section>
    <section v-if="regionScenario" class="result-panel" aria-label="ECLIPSE 区域物性方案验收">
      <div class="panel-heading"><div><h2>{{ regionScenario.keyword }} 区域物性方案验收</h2><p>选区物性以实际INIT核对；下面比较原生井压力、产量和累计量，不按输入倍率推算流量。</p></div></div>
      <dl class="scenario-metadata">
        <div><dt>基线</dt><dd>#{{ regionScenario.baselineRunId }}</dd></div>
        <div><dt>I / J / K范围</dt><dd>{{ regionScenario.i1 }}–{{ regionScenario.i2 }} / {{ regionScenario.j1 }}–{{ regionScenario.j2 }} / {{ regionScenario.k1 }}–{{ regionScenario.k2 }}</dd></div>
        <div><dt>选区单元</dt><dd>{{ regionScenario.originalValues.length }}</dd></div>
        <div><dt>单位 / 乘数</dt><dd>{{ gridRegionUnit(regionScenario.keyword) }} / {{ regionScenario.multiplier }}</dd></div>
      </dl>
      <p>下载 eclipse-grid-field-applied-package.zip，另一项目上传完整工程、不启用方案复算。修改范围外的场值仍需在INIT中核对。</p>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!regionResponse?.available" type="info" :closable="false" :title="scheduleBaselineLoading ? '读取同源基线中' : regionResponse?.message || '缺少可对比结果'" />
      <template v-else>
        <p>{{ regionResponse.rows.length }} 项实际报告数值，{{ regionResponse.changedCount }} 项共有时刻变化，{{ regionResponse.unmatchedCount }} 项缺少同时间值。差值为空时不插值；控制或压力限制可能保持原流量。</p>
        <el-table :data="regionResponse.rows" max-height="320" border size="small" aria-label="ECLIPSE 区域物性井级响应">
          <el-table-column prop="keyword" label="曲线" /><el-table-column prop="well" label="井" /><el-table-column prop="unit" label="单位" />
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baseline" label="基线" /><el-table-column prop="current" label="方案" /><el-table-column prop="difference" label="实际差值" />
        </el-table>
      </template>
    </section>
    <section v-if="forecastScenario" class="result-panel forecast-provenance" aria-label="ECLIPSE 历史预测来源链">
      <div class="panel-heading">
        <div><span class="kicker">ECLIPSE HISTORY → FORECAST</span><h2>历史—预测来源链</h2><p>当前 Run 的参数合同明确绑定历史来源、预测 DATA 与 Restart；此处不把两段 Summary 按未验证的时间原点自动拼接。</p></div>
        <el-tag :type="forecastHistoryLoading ? 'info' : forecastHistoryVerified ? 'success' : 'warning'">{{ forecastHistoryLoading ? '核对来源中' : forecastHistoryVerified ? '来源已核对' : '来源待核对' }}</el-tag>
      </div>
      <dl class="scenario-metadata">
        <div><dt>历史 Run</dt><dd>#{{ forecastScenario.historyRunId }}</dd></div>
        <div><dt>预测 DATA</dt><dd>{{ forecastScenario.forecastDataFile }}</dd></div>
        <div><dt>Restart Artifact</dt><dd>{{ forecastScenario.restartArtifactName }}</dd></div>
        <div><dt>Restart report</dt><dd>{{ forecastScenario.restartReport }}</dd></div>
        <div><dt>Artifact SHA-256</dt><dd>{{ forecastScenario.restartArtifactSha256 }}</dd></div>
      </dl>
      <el-alert v-if="forecastHistoryError" type="warning" :closable="false" :title="forecastHistoryError" />
      <el-alert v-else-if="forecastHistoryVerified" type="success" :closable="false" :title="`已确认历史 Run #${forecastHistoryRun.id} 属于同一模型版本、为无参数完整成功运行，且包含匹配哈希的 Restart Artifact。`" />
      <el-alert v-else-if="forecastHistoryLoading" type="info" :closable="false" title="正在读取历史 Run 并核对 Artifact 来源；核对结束前不将来源链标为通过。" />
      <el-alert type="info" :closable="false" title="本页 Summary 只代表当前预测 Run。历史与预测阶段分别保留各自的模拟时间原点；如需跨阶段曲线比较，须先确认 ECLIPSE restart 时间语义，平台不会按索引或日期猜测拼接。" />
    </section>
    <section v-if="scheduleTimelineSummary" class="result-panel schedule-timeline" aria-label="ECLIPSE Schedule 时间轴">
      <div class="panel-heading">
        <div><span class="kicker">ECLIPSE SCHEDULE</span><h2>Schedule 时间轴</h2><p>来自已验证 DATA 检查的 DATES/TSTEP 原始顺序；页面不根据时间或井名臆测历史/预测分界。</p></div>
        <el-tag type="info">已回读</el-tag>
      </div>
      <dl class="scenario-metadata">
        <div><dt>DATES 记录</dt><dd>{{ scheduleTimelineSummary.dateCount }}</dd></div>
        <div><dt>首个日期</dt><dd>{{ scheduleTimelineSummary.firstDate }}</dd></div>
        <div><dt>末个日期</dt><dd>{{ scheduleTimelineSummary.lastDate }}</dd></div>
        <div><dt>TSTEP 步数</dt><dd>{{ scheduleTimelineSummary.tstepCount }}</dd></div>
      </dl>
      <p class="schedule-timeline-note">如需历史—预测或重启验收，必须绑定明确的预测 DATA、成功历史 Run 和重启文件 Artifact；仅有时间轴不能替代重启关系。</p>
    </section>
    <section v-if="scheduleScenario" class="result-panel scenario-acceptance" aria-label="ECLIPSE Schedule 方案验收">
      <div class="panel-heading">
        <div><span class="kicker">ECLIPSE SCHEDULE</span><h2>{{ scheduleScenarioTitle }}</h2><p v-if="scheduleScenario.scenarioType === 'PRESSURE_CONTROL'">按相同模拟时间对照目标井的 WBHP 和产量／注入量，核对压力调整的实际影响。</p><p v-else-if="scheduleScenario.scenarioType === 'WELOPEN_EVENTS'">显示派生方案中的井状态事件，并按相同时间点对照真实 WOPR；只读，不把日志或文件变化当作计算效果。</p><p v-else>当前运行与无参数基线运行的真实 ECLEND 计数和输出文件 SHA-256 对比。</p></div>
        <el-tag :type="scheduleAcceptanceType">{{ scheduleAcceptanceLabel }}</el-tag>
      </div>
      <dl class="scenario-metadata">
        <div><dt>当前运行</dt><dd>#{{ run?.id || '-' }}</dd></div>
        <div><dt>基线运行</dt><dd>#{{ scheduleScenario.baselineRunId }}</dd></div>
        <div><dt>目标井</dt><dd>{{ scheduleScenario.well }}</dd></div>
        <div v-if="scheduleScenario.date"><dt>生效日期</dt><dd>{{ scheduleScenario.date }}</dd></div>
        <div v-if="scheduleScenario.phase"><dt>计划段</dt><dd>{{ scheduleScenario.phase === 'INITIAL' ? '初始调度阶段' : scheduleScenario.phase === 'FORECAST_INITIAL' ? '预测初始段' : scheduleScenario.phase }}</dd></div>
        <div v-if="scheduleScenario.status"><dt>目标状态</dt><dd>{{ scheduleScenario.status }}</dd></div>
        <div v-if="scheduleScenario.controlMode"><dt>控制关键字</dt><dd>{{ scheduleScenario.controlMode }}</dd></div>
        <template v-if="scheduleScenario.scenarioType === 'PRESSURE_CONTROL'">
          <div><dt>原控制模式</dt><dd>{{ scheduleScenario.originalControlMode }}</dd></div>
          <div><dt>原 BHP 设定／限值</dt><dd>{{ scheduleScenario.originalBhp }} psia</dd></div>
          <div><dt>目标井底压力</dt><dd>{{ scheduleScenario.targetBhp }} psia</dd></div>
          <div v-if="scheduleScenario.keyword === 'WCONINJE'"><dt>注入流体</dt><dd>{{ scheduleScenario.injectionType }}</dd></div>
        </template>
        <div v-if="['COMPDAT', 'COMPDAT_FACTOR'].includes(scheduleScenario.scenarioType)"><dt>完井坐标</dt><dd>I{{ scheduleScenario.i }} / J{{ scheduleScenario.j }} / K{{ scheduleScenario.k1 }}-{{ scheduleScenario.k2 }}</dd></div>
        <div v-if="['COMPDAT', 'COMPDAT_FACTOR'].includes(scheduleScenario.scenarioType)"><dt>源定位</dt><dd>{{ scheduleScenario.sourceFile }}:{{ scheduleScenario.lineNumber }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'COMPDAT_FACTOR'"><dt>原连接因子</dt><dd>{{ scheduleScenario.originalConnectionFactor }} {{ factorUnit || '单位未核准' }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'COMPDAT_FACTOR'"><dt>目标连接因子</dt><dd>{{ scheduleScenario.targetConnectionFactor }} {{ factorUnit || '单位未核准' }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONHIST'"><dt>目标 ORAT 原值</dt><dd>{{ scheduleScenario.targetOilRate }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONPROD'"><dt>目标 ORAT 原值</dt><dd>{{ scheduleScenario.targetOilRate }}</dd></div>
        <template v-if="scheduleScenario.scenarioType === 'PRODUCTION_TARGET'">
          <div><dt>原产油目标</dt><dd>{{ scheduleScenario.originalOilRate }} {{ scheduleScenario.unit }}</dd></div>
          <div><dt>新产油目标</dt><dd>{{ scheduleScenario.targetOilRate }} {{ scheduleScenario.unit }}</dd></div>
        </template>
        <template v-if="scheduleScenario.scenarioType === 'PRODUCTION_CONTROL'">
          <div><dt>原生产目标</dt><dd>{{ scheduleScenario.originalTargetRate }} {{ scheduleScenario.unit }}</dd></div>
          <div><dt>新生产目标</dt><dd>{{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</dd></div>
          <div><dt>源记录</dt><dd>{{ scheduleScenario.sourceFile }}:{{ scheduleScenario.lineNumber }}</dd></div>
        </template>
        <template v-if="scheduleScenario.scenarioType === 'THP_CONTROL'">
          <div><dt>原井口压力</dt><dd>{{ scheduleScenario.originalThp }} psia</dd></div>
          <div><dt>新井口压力</dt><dd>{{ scheduleScenario.targetThp }} psia</dd></div>
          <div><dt>VFP 表 / ALQ</dt><dd>{{ scheduleScenario.vfpTableNumber }} / {{ scheduleScenario.originalAlq }}</dd></div>
        </template>
        <div v-if="scheduleScenario.scenarioType === 'WCONINJE'"><dt>注入流体</dt><dd>{{ scheduleScenario.injectionType }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONINJE'"><dt>目标 RATE 原值</dt><dd>{{ scheduleScenario.targetInjectionRate }}</dd></div>
      </dl>
      <section v-if="scheduleScenario.scenarioType === 'COMPDAT_FACTOR'" aria-label="连接因子井级实际响应">
        <h3>目标井产量、压力与累计量</h3>
        <p>CF是完井连接导流能力，不是产油目标。保留原井控及其它完井，实际响应可能体现在压力或产量；不能按CF比例直接推算产量。</p>
        <p v-if="factorResponse?.available">共有时刻有{{ factorResponse.changedCount }}项数值变化；{{ factorResponse.unmatchedCount }}项只有单次报告，保持空值，不插值。</p>
        <p v-if="factorResponse?.missingIndicators?.length">工程没有请求{{ factorResponse.missingIndicators.join('、') }}，不补算；如需累计量对比，请在原工程SUMMARY请求WOPT后重新计算基线。</p>
        <el-table v-if="factorResponse?.available" :data="factorResponse.rows" border size="small" max-height="360" aria-label="CF目标井真实对比">
          <el-table-column prop="keyword" label="指标" /><el-table-column prop="timeDays" label="实际累计天数" />
          <el-table-column prop="unit" label="原生单位" /><el-table-column label="基线"><template #default="{ row }">{{ row.baseline ?? '—' }}</template></el-table-column>
          <el-table-column label="方案"><template #default="{ row }">{{ row.current ?? '—' }}</template></el-table-column>
          <el-table-column label="方案减基线"><template #default="{ row }">{{ row.difference ?? '—' }}</template></el-table-column>
        </el-table>
        <p v-else>同源基线或目标井指标尚不可核对；请审阅诊断，不能据此判为无影响。</p>
        <p>在“结果文件”下载eclipse-schedule-applied-package.zip，另一项目上传完整工程后不启用方案复算。</p>
      </section>
      <div v-if="['WELOPEN_EVENTS', 'NEW_DATES'].includes(scheduleScenario.scenarioType)" class="scenario-output-diff">
        <h3>计划井事件</h3>
        <p v-if="scheduleScenario.scenarioType === 'NEW_DATES'">在 {{ scheduleScenario.beforeDate }} 与 {{ scheduleScenario.afterDate }} 之间新增两个日期。日历和流量取自真实 SUMMARY；报告反映刚结束的时段。自动步长可能改变，只比较真实共有时间点。</p>
        <el-table :data="scheduleScenario.events" border size="small" :aria-label="scheduleScenario.events.length > 2 ? 'ECLIPSE 多日期时间轴' : 'ECLIPSE 双事件时间轴'">
          <el-table-column prop="date" label="DATES 日期" min-width="160" />
          <el-table-column label="WELOPEN 状态" min-width="160"><template #default="{ row }">{{ row.status === 'SHUT' ? 'SHUT（关井）' : 'OPEN（开井）' }}</template></el-table-column>
          <el-table-column label="应用位置" min-width="240"><template #default>{{ scheduleScenario.scenarioType === 'NEW_DATES' ? '所选区间已有控制之后，下一原日期之前' : '对应日期现有控制记录之后；源行保留' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_INJECTION'" class="scenario-output-diff">
        <h3>注水组 {{ scheduleScenario.group }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</h3>
        <p>只比较真实共有时刻。GWIR 应等于组内各井 WWIR 合计，BHP 不得超过原上限；按潜力分配，各井不一定均分。</p>
        <p v-if="scheduleGroupInjectionResponse?.available">{{ scheduleGroupInjectionResponse.rows.length }} 个共有时刻，其中 {{ scheduleGroupInjectionResponse.rows.filter(r => r.targetReached).length }} 个达标。分配验证通过不代表整个开发期始终达标。</p>
        <el-table :data="scheduleGroupInjectionResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 注水组总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineRate" label="基线 GWIR" />
          <el-table-column prop="target" label="新目标" /><el-table-column prop="rate" label="实际 GWIR" /><el-table-column prop="wellTotal" label="各井 WWIR 合计" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <p>注入量单位 {{ scheduleGroupInjectionResponse?.unit || scheduleScenario.unit }}；压力单位 {{ scheduleGroupInjectionResponse?.pressureUnit || '见模型单位' }}。</p>
        <el-table :data="scheduleGroupInjectionResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 注水组各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" label="基线 WWIR" />
          <el-table-column prop="rate" label="方案 WWIR" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="BHP 上限" />
          <el-table-column label="压力限制"><template #default="{ row }">{{ row.atBhpLimit ? '已触及上限' : '低于上限' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'CONTROL_PLAN'" class="scenario-output-diff">
        <p>{{ scheduleScenario.edits.length }} 条目标修改，{{ scheduleScenario.wells.length }} 口井；已有日期和未选井控保留。日期边界报告反映刚结束的时段；后续已有记录覆盖前期。基线空白表示该时刻没有同时间报告，不插值。</p>
        <p v-if="scheduleControlPlanResponse?.available">全部 {{ scheduleControlPlanResponse.rows.length }} 个井/时间输出均参与核对；流量 {{ scheduleControlPlanResponse.flowUnit }}，BHP {{ scheduleControlPlanResponse.pressureUnit }}。</p>
        <el-table :data="scheduleControlPlanResponse?.edits || []" max-height="260" border size="small" aria-label="ECLIPSE 阶段修改逐项核对"><el-table-column prop="well" label="井" /><el-table-column prop="date" label="生效日期" /><el-table-column prop="originalOilRate" label="原目标" /><el-table-column prop="targetOilRate" label="新目标" /><el-table-column prop="pointCount" label="生效期间输出点" /><el-table-column prop="targetCount" label="达标点" /><el-table-column prop="changedCount" label="共有时刻响应点" /><el-table-column prop="lineNumber" label="源行" /></el-table>
        <p v-if="scheduleControlPlanResponse?.rows.length > 500">下表预览前 500 条；全部输出已完成核对，完整数据可从运行产物下载。</p>
        <el-table :data="(scheduleControlPlanResponse?.rows || []).slice(0, 500)" max-height="320" border size="small" aria-label="ECLIPSE 阶段目标与实际井响应"><el-table-column prop="timeDays" label="报告天数" /><el-table-column prop="well" label="井" /><el-table-column prop="date" label="当前控制日期" /><el-table-column prop="nextDate" label="下一覆盖日期" /><el-table-column prop="target" label="当期目标" /><el-table-column prop="baselineRate" label="基线产油量" /><el-table-column prop="rate" label="实际产油量" /><el-table-column prop="bhp" label="BHP" /><el-table-column prop="bhpLimit" label="原 BHP 下限" /><el-table-column label="目标"><template #default="{ row }">{{ row.targetReached ? '达标' : '未达标' }}</template></el-table-column></el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_STRUCTURE'" class="scenario-output-diff">
        <p>{{ scheduleScenario.keyword }} · {{ scheduleScenario.objectName }}：{{ scheduleScenario.originalValue }} → {{ scheduleScenario.targetValue }} {{ scheduleScenario.unit }}；父组 {{ scheduleScenario.parentGroup }}。</p>
        <p v-if="scheduleGroupStructureResponse?.available">{{ scheduleGroupStructureResponse.rows.length }} 个时刻，{{ scheduleGroupStructureResponse.rows.filter(r => r.targetReached).length }} 个目标与分配达标。产油量 {{ scheduleGroupStructureResponse.flowUnit }}；累计量 {{ scheduleGroupStructureResponse.volumeUnit }}；BHP {{ scheduleGroupStructureResponse.pressureUnit }}。基线为空表示该时刻没有同时间输出。</p>
        <p>父组流量按子组开工比例折算；井和子组流量为开工时流量。累计量已计入开工比例，父组累计量直接求子组累计量之和。</p>
        <el-table :data="scheduleGroupStructureResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 父组目标与累计量核对"><el-table-column prop="timeDays" label="天数" /><el-table-column prop="target" label="父组目标" /><el-table-column prop="baselineRate" label="基线产油量" /><el-table-column prop="rate" label="实际产油量" /><el-table-column prop="contribution" label="子组折算贡献合计" /><el-table-column prop="total" label="父组累计量" /><el-table-column label="目标与分配"><template #default="{ row }">{{ row.targetReached ? '达标' : '未达标' }}</template></el-table-column></el-table>
        <el-table :data="scheduleGroupStructureResponse?.leaves || []" max-height="280" border size="small" aria-label="ECLIPSE 子组开工贡献与累计量"><el-table-column prop="timeDays" label="天数" /><el-table-column prop="group" label="子组" /><el-table-column prop="baselineRate" label="基线流量" /><el-table-column prop="rate" label="开工时流量" /><el-table-column prop="efficiency" label="开工比例" /><el-table-column prop="contribution" label="计入父组流量" /><el-table-column prop="baselineTotal" label="基线累计量" /><el-table-column prop="total" label="当前累计量" /></el-table>
        <el-table :data="scheduleGroupStructureResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 组树井分配与限值"><el-table-column prop="timeDays" label="天数" /><el-table-column prop="group" label="子组" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" label="基线产油量" /><el-table-column prop="rate" label="实际产油量" /><el-table-column prop="guideRate" label="固定导向权重" /><el-table-column prop="guideTarget" label="权重理论分配" /><el-table-column prop="efficiency" label="实际井效率" /><el-table-column prop="bhp" label="BHP" /><el-table-column prop="bhpLimit" label="BHP 下限" /><el-table-column prop="oilRateLimit" label="产油上限" /></el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'ADVANCED_INJECTION'" class="scenario-output-diff">
        <h3>注入组 {{ scheduleScenario.group }} · {{ scheduleScenario.injectionPhase }} {{ scheduleScenario.controlMode }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit === 'fraction' ? '倍' : scheduleScenario.unit }}</h3>
        <p>组注入量应等于全部注入井合计；BHP 与地面注入量不得超过模型原上限。显示方案全部真实输出时刻；基线只在共有时刻显示，不插值。</p>
        <p v-if="scheduleScenario.referenceGroup">来源生产组 {{ scheduleScenario.referenceGroup }}（{{ scheduleScenario.referenceMembers.join('、') }}）；REIN 使用地面产气量，VREP 使用总油藏采出体积。每个时刻分别按当时产量折算目标；实际比例绝对误差允许 {{ scheduleAdvancedInjectionResponse?.fractionTolerance || 0.0001 }}。</p>
        <p v-if="scheduleAdvancedInjectionResponse?.available">{{ scheduleAdvancedInjectionResponse.rows.length }} 个时刻，{{ scheduleAdvancedInjectionResponse.rows.filter(r => r.targetReached).length }} 个达标。注入量单位 {{ scheduleAdvancedInjectionResponse.flowUnit }}；地面注入量 {{ scheduleAdvancedInjectionResponse.surfaceUnit }}；BHP {{ scheduleAdvancedInjectionResponse.pressureUnit }}。</p>
        <el-table :data="scheduleAdvancedInjectionResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 注入方案总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineRate" label="基线注入量" />
          <el-table-column v-if="scheduleScenario.referenceGroup" prop="referenceRate" label="方案来源组产量" />
          <el-table-column v-if="scheduleScenario.referenceGroup" prop="baselineReferenceRate" label="基线来源组产量" />
          <el-table-column prop="target" label="方案折算目标" /><el-table-column prop="rate" label="实际注入量" /><el-table-column prop="wellTotal" label="各注入井合计" />
          <el-table-column v-if="scheduleScenario.referenceGroup" prop="actualRatio" label="实际比例" /><el-table-column v-if="scheduleScenario.referenceGroup" prop="ratioError" label="比例误差" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <el-table :data="scheduleAdvancedInjectionResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 注入方案各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" label="基线注入量" /><el-table-column prop="rate" label="方案注入量" />
          <el-table-column prop="surfaceRate" label="地面注入量" /><el-table-column prop="surfaceRateLimit" label="原地面上限" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="原 BHP 上限" />
          <el-table-column label="压力或流量限制"><template #default="{ row }">{{ row.atBhpLimit || row.atSurfaceLimit ? '已触及上限' : '低于上限' }}</template></el-table-column>
        </el-table>
        <el-table v-if="scheduleScenario.referenceGroup" :data="scheduleAdvancedInjectionResponse?.referenceRows || []" max-height="280" border size="small" aria-label="ECLIPSE 回注来源生产井">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="来源生产井" /><el-table-column prop="baselineRate" label="基线产量" /><el-table-column prop="rate" label="方案产量" />
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_WATER_PRODUCTION'" class="scenario-output-diff">
        <h3>生产组 {{ scheduleScenario.group }} · {{ scheduleScenario.controlMode }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</h3>
        <p>只比较真实共有时刻。GWPR 应等于全部成员井 WWPR 合计，BHP 不得低于原下限，WOPR 不得超过原产油上限；按潜力分配，各井不一定均分。</p>
        <p v-if="scheduleGroupWaterProductionResponse?.available">{{ scheduleGroupWaterProductionResponse.rows.length }} 个共有时刻，其中 {{ scheduleGroupWaterProductionResponse.rows.filter(r => r.targetReached).length }} 个达标。分配验证通过不代表整个开发期始终达标。</p>
        <el-table :data="scheduleGroupWaterProductionResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 生产组产水总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineRate" :label="`基线 ${'GWPR'}`" />
          <el-table-column prop="target" label="新目标" /><el-table-column prop="rate" :label="`实际 ${'GWPR'}`" /><el-table-column prop="wellTotal" :label="`各井 ${'WWPR'} 合计`" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <p>产量单位 {{ scheduleGroupWaterProductionResponse?.unit || scheduleScenario.unit }}；压力单位 {{ scheduleGroupWaterProductionResponse?.pressureUnit || '见模型单位' }}。</p>
        <el-table :data="scheduleGroupWaterProductionResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 生产组产水各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" :label="`基线 ${'WWPR'}`" />
          <el-table-column prop="rate" :label="`方案 ${'WWPR'}`" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="BHP 下限" />
          <el-table-column prop="oilRate" label="实际 WOPR" /><el-table-column prop="oilRateLimit" label="原产油上限" />
          <el-table-column label="产油限制"><template #default="{ row }">{{ row.atOilRateLimit ? '已触及上限' : '低于上限' }}</template></el-table-column>
          <el-table-column label="压力限制"><template #default="{ row }">{{ row.atBhpLimit ? '已触及下限' : '高于下限' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_RATE'" class="scenario-output-diff">
        <h3>生产组 {{ scheduleScenario.group }} · {{ scheduleScenario.controlMode }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</h3>
        <p>只比较真实共有时刻。GRAT 的 GGPR/WGPR、LRAT 的 GLPR/WLPR 按模式核对组总量与各井合计，BHP 不得低于原下限；按潜力分配，各井不一定均分。</p>
        <p v-if="scheduleGroupRateResponse?.available">{{ scheduleGroupRateResponse.rows.length }} 个共有时刻，其中 {{ scheduleGroupRateResponse.rows.filter(r => r.targetReached).length }} 个达标。分配验证通过不代表整个开发期始终达标。</p>
        <el-table :data="scheduleGroupRateResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 生产组气液总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineRate" :label="`基线 ${scheduleScenario.controlMode === 'GRAT' ? 'GGPR' : 'GLPR'}`" />
          <el-table-column prop="target" label="新目标" /><el-table-column prop="rate" :label="`实际 ${scheduleScenario.controlMode === 'GRAT' ? 'GGPR' : 'GLPR'}`" /><el-table-column prop="wellTotal" :label="`各井 ${scheduleScenario.controlMode === 'GRAT' ? 'WGPR' : 'WLPR'} 合计`" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <p>产量单位 {{ scheduleGroupRateResponse?.unit || scheduleScenario.unit }}；压力单位 {{ scheduleGroupRateResponse?.pressureUnit || '见模型单位' }}。</p>
        <el-table :data="scheduleGroupRateResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 生产组气液各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" :label="`基线 ${scheduleScenario.controlMode === 'GRAT' ? 'WGPR' : 'WLPR'}`" />
          <el-table-column prop="rate" :label="`方案 ${scheduleScenario.controlMode === 'GRAT' ? 'WGPR' : 'WLPR'}`" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="BHP 下限" />
          <el-table-column label="压力限制"><template #default="{ row }">{{ row.atBhpLimit ? '已触及下限' : '高于下限' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_GAS_INJECTION'" class="scenario-output-diff">
        <h3>注气组 {{ scheduleScenario.group }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</h3>
        <p>只比较真实共有时刻。GGIR 应等于组内各井 WGIR 合计，BHP 不得超过原上限；按潜力分配，各井不一定均分。</p>
        <p v-if="scheduleGroupGasInjectionResponse?.available">{{ scheduleGroupGasInjectionResponse.rows.length }} 个共有时刻，其中 {{ scheduleGroupGasInjectionResponse.rows.filter(r => r.targetReached).length }} 个达标。分配验证通过不代表整个开发期始终达标。</p>
        <el-table :data="scheduleGroupGasInjectionResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 注气组总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineRate" label="基线 GGIR" />
          <el-table-column prop="target" label="新目标" /><el-table-column prop="rate" label="实际 GGIR" /><el-table-column prop="wellTotal" label="各井 WGIR 合计" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <p>注入量单位 {{ scheduleGroupGasInjectionResponse?.unit || scheduleScenario.unit }}；压力单位 {{ scheduleGroupGasInjectionResponse?.pressureUnit || '见模型单位' }}。</p>
        <el-table :data="scheduleGroupGasInjectionResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 注气组各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineRate" label="基线 WGIR" />
          <el-table-column prop="rate" label="方案 WGIR" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="BHP 上限" />
          <el-table-column label="压力限制"><template #default="{ row }">{{ row.atBhpLimit ? '已触及上限' : '低于上限' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'GROUP_PRODUCTION'" class="scenario-output-diff">
        <h3>生产组 {{ scheduleScenario.group }}：{{ scheduleScenario.originalTargetRate }} → {{ scheduleScenario.targetRate }} {{ scheduleScenario.unit }}</h3>
        <p>仅比较基线和方案真实共有时间点。按潜力分配，不能预设各井必然均分；后期若触及压力或流量限制，实际总量可低于目标。下表保留全部共有时刻。</p>
        <p v-if="scheduleGroupProductionResponse?.available">共有 {{ scheduleGroupProductionResponse.rows.length }} 个真实时刻，其中 {{ scheduleGroupProductionResponse.rows.filter(r => r.targetReached).length }} 个达到新组目标。分配验证通过不代表整个开发期始终达标。</p>
        <el-table :data="scheduleGroupProductionResponse?.rows || []" max-height="280" border size="small" aria-label="ECLIPSE 组总量核对">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="baselineOil" label="基线 GOPR" />
          <el-table-column prop="target" label="新目标" /><el-table-column prop="oil" label="实际 GOPR" /><el-table-column prop="wellTotal" label="各井 WOPR 合计" />
          <el-table-column label="该时刻达标"><template #default="{ row }">{{ row.targetReached ? '是' : '否' }}</template></el-table-column>
          <el-table-column label="总量一致"><template #default="{ row }">{{ row.consistent ? '是' : '否' }}</template></el-table-column>
        </el-table>
        <p>产量单位 {{ scheduleGroupProductionResponse?.unit || scheduleScenario.unit }}；压力单位 {{ scheduleGroupProductionResponse?.pressureUnit || '见模型单位' }}。</p>
        <el-table :data="scheduleGroupProductionResponse?.members || []" max-height="280" border size="small" aria-label="ECLIPSE 组内各井分配">
          <el-table-column prop="timeDays" label="天数" /><el-table-column prop="well" label="井" /><el-table-column prop="baselineOil" label="基线 WOPR" />
          <el-table-column prop="oil" label="方案 WOPR" /><el-table-column prop="bhp" label="实际 BHP" /><el-table-column prop="bhpLimit" label="BHP 下限" />
          <el-table-column label="压力限制"><template #default="{ row }">{{ row.atBhpLimit ? '已触及下限' : '高于下限' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleScenario.scenarioType === 'TSTEP'" class="scenario-output-diff">
        <p>原报告间隔 {{ scheduleScenario.originalSteps.join('、') }} 天 → 新间隔 {{ scheduleScenario.targetSteps.join('、') }} 天；此段合计时长保持不变。后续控制仍在原时刻生效，曲线内部点可能因自动步长改变。</p>
        <el-table :data="scheduleTstepResponse?.rows || []" border size="small" aria-label="ECLIPSE TSTEP 真实报告时间">
          <el-table-column prop="interval" label="间隔（天）" />
          <el-table-column prop="timeDays" label="累计模拟天数" />
          <el-table-column label="实际输出"><template #default="{ row }">{{ row.present ? '已找到' : '缺失' }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleNewDatesResponse?.available" class="scenario-output-diff">
        <h3>按真实日历核对停井与复产</h3>
        <el-table :data="scheduleNewDatesResponse.rows" border size="small" max-height="260" aria-label="ECLIPSE 新日期真实响应">
          <el-table-column prop="date" label="报告日期" />
          <el-table-column prop="timeDays" label="模拟天数" />
          <el-table-column prop="phase" label="刚结束的时段" />
          <el-table-column prop="value" label="实际 WOPR" />
          <el-table-column prop="unit" label="单位" />
        </el-table>
      </div>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && !scheduleBaselineIsClean" type="warning" :closable="false" title="当前基线本身带有参数方案，不是干净基线；请重新运行并选择不覆盖参数的 ECLIPSE 运行作为基线。" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleRateComparisonRequired && !scheduleWellRateDiff?.available" type="warning" :closable="false" title="缺少同单位、同时间点的目标井流量，暂不能确认业务影响。" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleScenario.scenarioType === 'PRESSURE_CONTROL' && !schedulePressureDiff?.available" type="warning" :closable="false" title="缺少同单位、同时间点的目标井 WBHP，暂不能确认压力影响。" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && ['NEW_DATES', 'TSTEP'].includes(scheduleScenario.scenarioType)" :type="scheduleHasObservableDifference ? 'success' : 'warning'" :closable="false" :title="scheduleAcceptanceLabel" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleHasObservableDifference" type="success" :closable="false" :title="scheduleRateComparisonRequired ? '已检测到目标井流量的实际差异；请结合完整曲线和求解诊断审阅。' : '已检测到真实计算差异：ECLEND 计数或至少一个 ECLIPSE 输出文件发生变化。'" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleScenario.scenarioType === 'PRESSURE_CONTROL'" type="warning" :closable="false" :title="scheduleAcceptanceLabel" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleScenario.scenarioType === 'PRODUCTION_CONTROL'" type="warning" :closable="false" :title="scheduleAcceptanceLabel" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleScenario.scenarioType === 'THP_CONTROL'" type="warning" :closable="false" :title="scheduleAcceptanceLabel" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult" type="warning" :closable="false" title="本次方案与基线的可展示输出没有差异；不能仅凭运行成功判定方案生效。" />
      <el-alert v-if="scheduleScenario.scenarioType === 'PRODUCTION_TARGET'" type="info" :closable="false" title="产油目标保留其它压力与供给限制；实际产量可能低于目标。方案工程可从下方文件列表下载并重新上传计算。" />
      <el-alert v-if="scheduleScenario.scenarioType === 'PRESSURE_CONTROL'" type="info" :closable="false" title="保留原流量等限制；实际压力可能无法达到设定目标。请核对 WBHP、流量和求解诊断，再下载方案工程复算。" />
      <el-alert v-if="scheduleScenario.scenarioType === 'PRODUCTION_CONTROL'" type="info" :closable="false" title="保留其它压力和流量限制；核对 WWPR／WGPR／WLPR 是否达到新目标，审阅求解诊断，再下载方案工程复算。" />
      <el-alert v-if="scheduleScenario.scenarioType === 'THP_CONTROL'" type="info" :closable="false" title="沿用原 VFP 表和其它约束；检查 WTHP、WLPR 和 Summary 中的 WBHP。若流量、含水率或气油比超出表范围，线性外推结果需要审阅。方案 ZIP 可下载后重新上传复算。" />
      <div v-if="schedulePressureDiff?.available" class="scenario-output-diff">
        <h3>目标井 {{ schedulePressureKeyword }} · {{ schedulePressureDiff.unit }}</h3>
        <p>差异点 {{ schedulePressureDiff.changedCount }} 个，共 {{ schedulePressureDiff.rows.length }} 个对齐点。</p>
        <el-table :data="schedulePressureDiff.rows" border size="small" max-height="260" :aria-label="`ECLIPSE 目标井 ${schedulePressureKeyword} 基线差异`">
          <el-table-column prop="well" label="井" min-width="100" />
          <el-table-column prop="timeDays" label="模拟时间（天）" min-width="150" />
          <el-table-column :label="`基线 ${schedulePressureKeyword}`" min-width="140"><template #default="{ row }">{{ Number(row.baseline).toPrecision(7) }}</template></el-table-column>
          <el-table-column :label="`方案 ${schedulePressureKeyword}`" min-width="140"><template #default="{ row }">{{ Number(row.current).toPrecision(7) }}</template></el-table-column>
          <el-table-column label="差值" min-width="140"><template #default="{ row }">{{ Number(row.difference).toPrecision(7) }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleRateComparisonRequired && scheduleWellRateDiff?.available" class="scenario-output-diff">
        <h3>目标井 {{ scheduleWellRateDiff.keyword }} · {{ scheduleWellRateDiff.unit || '单位未提供' }}</h3>
        <p>仅比较基线与方案都提供的相同模拟时间点；差异点 {{ scheduleWellRateDiff.changedCount }} 个，共 {{ scheduleWellRateDiff.rows.length }} 个对齐点。</p>
        <el-table :data="scheduleWellRateDiff.rows" border size="small" max-height="260" :aria-label="`ECLIPSE 目标井 ${scheduleWellRateDiff.keyword} 基线差异`">
          <el-table-column prop="well" label="井" min-width="100" />
          <el-table-column prop="unit" label="单位" min-width="110" />
          <el-table-column prop="timeDays" label="模拟时间（天）" min-width="150" />
          <el-table-column :label="`基线 ${scheduleWellRateDiff.keyword}`" min-width="140"><template #default="{ row }">{{ Number(row.baseline).toPrecision(7) }}</template></el-table-column>
          <el-table-column :label="`方案 ${scheduleWellRateDiff.keyword}`" min-width="140"><template #default="{ row }">{{ Number(row.current).toPrecision(7) }}</template></el-table-column>
          <el-table-column label="差值" min-width="140"><template #default="{ row }">{{ Number(row.difference).toPrecision(7) }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleThpBhpDiff?.available && scheduleThpBhpDiff.unit?.trim().toUpperCase() === 'PSIA'" class="scenario-output-diff">
        <h3>目标井 WBHP · psia</h3>
        <el-table :data="scheduleThpBhpDiff.rows" border size="small" max-height="260" aria-label="ECLIPSE THP 方案 WBHP 基线差异">
          <el-table-column prop="timeDays" label="模拟时间（天）" min-width="150" />
          <el-table-column label="基线 WBHP" min-width="140"><template #default="{ row }">{{ Number(row.baseline).toPrecision(7) }}</template></el-table-column>
          <el-table-column label="方案 WBHP" min-width="140"><template #default="{ row }">{{ Number(row.current).toPrecision(7) }}</template></el-table-column>
        </el-table>
      </div>
      <div v-if="scheduleCounts" class="scenario-count-strip">
        <div v-for="item in scheduleCounts" :key="item.key"><span>{{ item.label }}</span><strong>{{ item.current }}</strong><small>基线 {{ item.baseline }} · {{ item.current - item.baseline >= 0 ? '+' : '' }}{{ item.current - item.baseline }}</small></div>
      </div>
      <div v-if="scheduleOutputDiff" class="scenario-output-diff">
        <span>输出文件 SHA-256：{{ scheduleOutputDiff.changedCount }} / {{ scheduleOutputDiff.rows.length }} 个文件发生变化</span>
        <el-table :data="scheduleOutputDiff.rows" border size="small" max-height="220"><el-table-column prop="name" label="文件" min-width="180" /><el-table-column prop="status" label="对比结果" width="120" /></el-table>
      </div>
    </section>
    <section class="result-panel result-navigation" aria-label="ECLIPSE 结果导航">
      <div class="panel-heading">
        <div><span class="kicker">ECLIPSE RESULTS</span><h2>结果导航</h2><p>按本次 Run 已发布的真实结果能力进入曲线、二维、三维或诊断；不会为缺失文件补造结果。</p></div>
        <el-tag :type="threeDReadiness.ready ? 'success' : 'warning'">{{ threeDReadinessLabel }}</el-tag>
      </div>
      <nav class="result-navigation-grid" aria-label="ECLIPSE 结果区域">
        <button type="button" class="result-navigation-card" @click="scrollToResult('eclipse-summary-result')">
          <span class="result-navigation-title">曲线 / Summary</span>
          <strong>{{ hasUsableSummary ? `${series.length} 个序列` : '暂无可用序列' }}</strong>
          <small>查看真实 Summary 曲线和同版本历史对比</small>
        </button>
        <button type="button" class="result-navigation-card" @click="scrollToResult('eclipse-2d-result')">
          <span class="result-navigation-title">二维场切片</span>
          <strong>{{ gridMetadata && fieldRows.length ? '可进入' : '暂无网格场' }}</strong>
          <small>按文件、时间步和层读取真实场值</small>
        </button>
        <button type="button" class="result-navigation-card" :class="{ ready: threeDReadiness.ready }" @click="scrollToResult('eclipse-3d-result')">
          <span class="result-navigation-title">三维几何 / 井定位</span>
          <strong>{{ threeDReadinessLabel }}</strong>
          <small>{{ threeDReadinessDescription }}</small>
        </button>
        <button type="button" class="result-navigation-card" @click="scrollToResult('eclipse-diagnostics-result')">
          <span class="result-navigation-title">求解诊断</span>
          <strong>{{ diagnosticMessages.length ? `${diagnosticMessages.length} 条消息` : '暂无诊断' }}</strong>
          <small>{{ isPartialResult ? '部分成功，请先审阅 Problems' : '查看 ECLEND/MSG 原始诊断' }}</small>
        </button>
      </nav>
    </section>
    <section id="eclipse-summary-result" class="result-anchor">
    <EclipseRestartForecastCompare v-if="run?.parameters?.schemaVersion === 'eclipse-history-forecast-parameters/2'" :run="run" :history="history" :inspection="inspection" />
    <div v-if="hasUsableSummary" class="eclipse-comparison">
      <span>当前运行 #{{ run.id }}</span>
      <el-select v-if="!forecastScenario" v-model="comparisonId" filterable clearable :loading="comparisonLoading" placeholder="选择同版本历史计算" aria-label="ECLIPSE 历史结果对比">
        <el-option v-for="item in comparisonOptions" :key="item.id" :value="item.id" :label="`运行 #${item.id} · ${item.createdAt || ''}`" />
      </el-select>
      <span v-else>预测段独立显示；待确认 Restart 时间原点后再开放跨阶段对比</span>
      <span v-if="comparisonError" role="status">{{ comparisonError }}</span>
      <span v-else-if="comparison">实线：当前运行；虚线：历史运行。仅同单位序列可叠加。</span>
    </div>
    <EclipseSummaryExplorer v-if="hasSummary" :series="explorerSeries" :run-id="run?.id" />
    </section>
    <section id="eclipse-diagnostics-result" class="result-anchor">
    <section v-if="diagnosticMessages.length" class="result-panel eclipse-diagnostics">
      <div class="panel-heading"><div><span class="kicker">ECLIPSE DIAGNOSTICS</span><h2>求解诊断</h2><p>以下内容来自本次运行生成的 MSG 诊断文件；Problems 不会被隐藏或改写。</p></div><el-tag :type="isPartialResult ? 'warning' : 'info'">{{ isPartialResult ? '带问题完成' : '含诊断' }}</el-tag></div>
      <div class="diagnostic-counts"><span>Problems {{ diagnosticCounts.problems }}</span><span>Warnings {{ diagnosticCounts.warnings }}</span><span>Errors {{ diagnosticCounts.errors }}</span></div>
      <details :key="run?.id" class="diagnostic-details">
        <summary>查看原始诊断 · {{ diagnosticMessages.length }} 条</summary>
        <div v-if="diagnosticGroupRows.length" class="diagnostic-groups" aria-label="ECLIPSE 诊断分类">
          <article v-for="group in diagnosticGroupRows" :key="group.key" class="diagnostic-group-card">
            <strong>{{ group.label }} · {{ group.count }} 条</strong>
            <small>{{ group.review }}</small>
          </article>
        </div>
        <p class="diagnostic-note">分类仅供审阅；不会自动修改模型、求解器参数或诊断原文。</p>
        <el-table :data="diagnosticMessages" border size="small" max-height="360">
          <el-table-column prop="severity" label="级别" width="110" />
          <el-table-column prop="category" label="类别" width="110" />
          <el-table-column prop="code" label="代码" width="170" />
          <el-table-column prop="message" label="ECLIPSE 原始诊断" min-width="520" show-overflow-tooltip />
        </el-table>
      </details>
    </section>
    <el-empty v-else class="result-panel result-empty" description="本次运行没有结构化 ECLIPSE 诊断消息" :image-size="56" />
    </section>
    <section id="eclipse-3d-result" class="result-anchor">
      <EclipseGrid3D v-if="gridMetadata" :run="run" :run-id="run?.id" :grid="gridMetadata" :fields="fieldRows" :artifacts="artifacts" :well-completions="scheduleCompletions" @create-grid-field-run="emit('create-grid-field-run', $event)" @add-grid-batch-case="addGridBatchCase" />
      <section v-else class="result-panel result-capability-empty" aria-label="ECLIPSE 三维结果不可用">
        <div class="panel-heading"><div><span class="kicker">ECLIPSE 3D</span><h2>三维几何、井定位与场值编辑</h2><p>三维入口已保留，但本次 Run 没有发布可读取的 EGRID 网格。</p></div><el-tag type="warning">不可用</el-tag></div>
        <el-alert type="warning" :closable="false" :title="threeDReadinessDescription" />
      </section>
    </section>
    <section id="eclipse-2d-result" class="result-anchor">
      <EclipseGridSlice v-if="gridMetadata" :run-id="run?.id" :grid="gridMetadata" :fields="fieldRows" :artifacts="artifacts" v-model:time-step="fieldTimeStepFilter" />
      <section v-else class="result-panel result-capability-empty" aria-label="ECLIPSE 二维结果不可用">
        <div class="panel-heading"><div><span class="kicker">ECLIPSE 2D</span><h2>二维场切片</h2><p>本次 Run 没有 EGRID 网格元数据，无法读取二维层场值。</p></div><el-tag type="info">不可用</el-tag></div>
      </section>
    </section>
    <details class="eclipse-audit"><summary>计算信息与输出文件</summary>
    <section class="result-panel">
      <div class="panel-heading"><div><span class="kicker">ECLIPSE 100</span><h2>ECLEND 计数</h2></div><el-tag :type="run?.status === 'SUCCEEDED' ? 'success' : run?.status === 'PARTIAL_SUCCEEDED' ? 'warning' : 'info'">{{ run?.status || '-' }}</el-tag></div>
      <div v-if="counts" class="count-strip"><div v-for="item in countItems" :key="item.key"><span>{{ item.label }}</span><strong :class="{ failure: ['problems', 'errors', 'bugs'].includes(item.key) && item.value > 0 }">{{ item.value }}</strong></div></div>
      <el-empty v-else description="当前运行没有可用的 ECLEND 计数" :image-size="56" />
    </section>

    <section v-if="error || cleanupEntries.length" class="result-panel state-panel">
      <div class="panel-heading"><div><h2>错误与清理状态</h2></div></div>
      <dl v-if="error" class="error-grid"><div><dt>类别</dt><dd>{{ error.category || '-' }}</dd></div><div><dt>代码</dt><dd>{{ error.code || '-' }}</dd></div><div><dt>消息</dt><dd>运行失败详情已隐藏。</dd></div><div><dt>可重试</dt><dd>{{ error.retryable ? '是' : '否' }}</dd></div></dl>
      <el-table v-if="cleanupEntries.length" :data="cleanupEntries" border size="small"><el-table-column prop="key" label="清理项" min-width="200" /><el-table-column prop="value" label="状态" min-width="280" show-overflow-tooltip /></el-table>
    </section>


    <section class="result-panel">
      <div class="panel-heading"><div><h2>ECLIPSE 输出文件</h2><p>ECLIPSE 结果契约中的本次输出文件元数据。</p></div><span>{{ outputFiles.length }} 个文件</span></div>
      <el-table v-if="outputFiles.length" :data="outputFiles" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /></el-table>
      <el-empty v-else description="当前运行没有 ECLIPSE 输出文件元数据" :image-size="56" />
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>网格结果索引</h2><p>仅展示 EGRID 的真实网格元数据；网格场数据通过后续分页接口读取，不在运行 JSON 中展开。</p></div></div>
      <dl v-if="gridMetadata" class="grid-metadata"><div><dt>文件</dt><dd>{{ gridMetadata.fileName }}</dd></div><div><dt>维度</dt><dd>{{ gridMetadata.nx }} × {{ gridMetadata.ny }} × {{ gridMetadata.nz }}</dd></div><div><dt>活动网格</dt><dd>{{ gridMetadata.activeCells ?? '未提供' }}</dd></div></dl>
      <el-empty v-else description="本次运行没有可解析的 EGRID 网格元数据" :image-size="56" />
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>二进制场索引</h2><p>列出真实 EGRID、INIT、UNRST、FUNRST 中可读取的关键字和数据范围；当前运行 JSON 不展开网格场值。</p></div><span>{{ fieldRows.length }} 个字段</span></div>
      <div v-if="fieldRows.length" class="field-index-toolbar">
        <el-select v-model="fieldFileFilter" filterable aria-label="筛选 ECLIPSE 二进制文件" placeholder="全部文件">
          <el-option value="all" label="全部文件" />
          <el-option v-for="file in fieldFileOptions" :key="file" :value="file" :label="file" />
        </el-select>
        <el-select v-model="fieldTimeStepFilter" aria-label="筛选 ECLIPSE 二进制时间步" placeholder="全部时间步">
          <el-option value="all" label="全部时间步" />
          <el-option value="static" label="静态字段" />
          <el-option v-for="timeStep in fieldTimeStepOptions" :key="timeStep" :value="String(timeStep)" :label="`时间步 ${timeStep}`" />
        </el-select>
        <el-input v-model="fieldKeywordFilter" clearable aria-label="搜索 ECLIPSE 二进制关键字" placeholder="搜索关键字" />
        <span>显示 {{ filteredFieldRows.length }} / {{ fieldRows.length }} 个字段</span>
      </div>
      <el-table v-if="fieldRows.length" :data="pagedFieldRows" border size="small" max-height="320"><el-table-column prop="file" label="文件" min-width="180" show-overflow-tooltip /><el-table-column prop="byteOrder" label="字节序" width="90" /><el-table-column prop="timeStep" label="时间步" width="90"><template #default="{ row }">{{ row.timeStep ?? '静态' }}</template></el-table-column><el-table-column prop="keyword" label="关键字" width="120" /><el-table-column prop="dataType" label="类型" width="90" /><el-table-column prop="count" label="数量" width="110" /><el-table-column prop="dataBytes" label="数据字节" width="120" /><el-table-column label="分段" width="80"><template #default="{ row }">{{ row.segments?.length || 0 }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" :loading="fieldPreviewLoading && fieldPreview?.keyword === row.keyword && fieldPreview?.file === row.file" :disabled="!fieldArtifact(row)" @click="previewField(row)">预览前 200 个值</el-button></template></el-table-column></el-table>
      <el-pagination v-if="filteredFieldRows.length > fieldIndexPageSize" v-model:current-page="fieldIndexPage" v-model:page-size="fieldIndexPageSize" class="field-index-pagination" background layout="total, sizes, prev, pager, next" :page-sizes="[25, 50, 100]" :total="filteredFieldRows.length" />
      <el-empty v-if="fieldRows.length && !filteredFieldRows.length" description="没有匹配的二进制字段" :image-size="56" />
      <el-empty v-if="!fieldRows.length" description="本次运行没有可解析的二进制场索引" :image-size="56" />
      <el-alert v-if="fieldPreviewError" class="field-preview-notice" type="warning" :closable="false" :title="fieldPreviewError" />
      <div v-if="fieldPreview" class="field-preview">
        <div class="panel-heading"><div><h3>{{ fieldPreview.file }} · {{ fieldPreview.keyword }}</h3><p>从受控 Artifact 按索引分段读取，展示 {{ fieldPreview.values.length }} 个真实值（第 {{ fieldPreview.startValue + 1 }}-{{ fieldPreview.startValue + fieldPreview.values.length }} 个）。</p></div><span>{{ fieldPreview.dataType }}</span></div>
        <el-table :data="fieldPreview.values.map((value, index) => ({ index: fieldPreview.startValue + index + 1, value }))" border size="small" max-height="240"><el-table-column prop="index" label="#" width="70" /><el-table-column prop="value" label="值" min-width="180" /></el-table>
        <el-pagination v-if="fieldPreview.count > fieldPreviewPageSize" class="field-preview-pagination" small background layout="prev, pager, next" :current-page="fieldPreviewPage" :page-size="fieldPreviewPageSize" :total="fieldPreview.count" :disabled="fieldPreviewLoading" @current-change="loadFieldPreviewPage" />
      </div>
    </section>

    <section class="result-panel">
      <div class="panel-heading"><div><h2>Artifact</h2><p>平台持久化的通用运行 Artifact。</p></div><span>{{ artifacts.length }} 个文件</span></div>
      <el-table v-if="artifacts.length" :data="downloadableArtifacts" border size="small" max-height="320"><el-table-column prop="name" label="文件名" min-width="210" show-overflow-tooltip /><el-table-column prop="type" label="类型" min-width="120" /><el-table-column label="大小" width="120"><template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template></el-table-column><el-table-column prop="sha256" label="SHA-256" min-width="280" show-overflow-tooltip /><el-table-column prop="expiresAt" label="到期时间" min-width="170" /><el-table-column label="操作" width="88"><template #default="{ row }"><el-button link type="primary" :loading="artifactDownloadPending === row.id" :disabled="!row.downloadable" @click="downloadArtifact(row)">下载</el-button></template></el-table-column></el-table>
      <el-empty v-else description="当前运行没有已发布的 Artifact" :image-size="56" />
    </section>
    </details>
    </template>
  </section>
</template>

<style lang="scss" scoped>
.diagnostic-details summary { cursor: pointer; padding: 8px 0; font-size: 12px; color: #2b6cb3; }
.diagnostic-note { color: #737a84; font-size: 12px; }
.eclipse-comparison { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 8px 10px; background: #f3f3f0; font-size: 12px; }
.schedule-timeline-note { margin: 12px 0 0; color: #737a84; font-size: 12px; line-height: 1.6; }
.forecast-provenance .scenario-metadata dd { overflow-wrap: anywhere; }
.scenario-acceptance { padding: 14px; }.scenario-metadata { display: grid; grid-template-columns: repeat(5, minmax(110px, 1fr)); gap: 8px; margin: 0 0 12px; }.scenario-metadata div { padding: 8px 10px; background: #f5f7fa; }.scenario-metadata dt { color: #909399; font-size: 12px; }.scenario-metadata dd { margin: 3px 0 0; color: #303133; font-size: 13px; font-weight: 600; }.scenario-count-strip { display: grid; grid-template-columns: repeat(5, minmax(90px, 1fr)); margin-top: 12px; border: 1px solid #e8edf3; }.scenario-count-strip div { padding: 9px 11px; border-right: 1px solid #e8edf3; }.scenario-count-strip div:last-child { border-right: 0; }.scenario-count-strip span, .scenario-count-strip small { display: block; color: #737a84; font-size: 12px; }.scenario-count-strip strong { display: block; margin: 2px 0; color: #2b3d52; font-size: 18px; }.scenario-output-diff { margin-top: 12px; color: #606266; font-size: 12px; }.scenario-output-diff .el-table { margin-top: 7px; } @media (max-width: 900px) { .scenario-metadata { grid-template-columns: repeat(3, minmax(110px, 1fr)); } } @media (max-width: 600px) { .scenario-metadata, .scenario-count-strip { grid-template-columns: repeat(2, minmax(90px, 1fr)); }.scenario-count-strip div:nth-child(2n) { border-right: 0; } }
.field-preview-notice { margin-top: 12px; }.field-preview { margin-top: 12px; padding-top: 12px; border-top: 1px solid #e5e7eb; }.field-preview-pagination, .field-index-pagination { justify-content: flex-end; margin-top: 10px; }.field-index-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 10px; }.field-index-toolbar .el-select { width: min(190px, 100%); }.field-index-toolbar .el-input { width: min(190px, 100%); }.field-index-toolbar > span { color: #737a84; font-size: 12px; }
.eclipse-comparison .el-select { width: min(320px, 100%); }
.diagnostic-counts { display: flex; flex-wrap: wrap; gap: 8px 18px; margin: 0 0 10px; color: #737a84; font-size: 12px; }
.diagnostic-groups { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin: 0 0 10px; }.diagnostic-group-card { min-width: 0; padding: 9px 10px; border: 1px solid #e5eaf1; background: #f8fafc; }.diagnostic-group-card strong, .diagnostic-group-card small { display: block; }.diagnostic-group-card strong { color: #334155; font-size: 12px; }.diagnostic-group-card small { margin-top: 4px; color: #7b8794; line-height: 1.45; } @media (max-width: 1000px) { .diagnostic-groups { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 600px) { .diagnostic-groups { grid-template-columns: 1fr; } }
.eclipse-audit { border: 1px solid #dcdfe6; }.eclipse-audit > summary { padding: 9px 12px; font-size: 13px; cursor: pointer; background: #f3f3f0; }.eclipse-audit .result-panel { border: 0; border-top: 1px solid #e5e7eb; padding: 12px; }
.eclipse-result { display: flex; flex-direction: column; gap: 16px; }.result-panel { min-width: 0; padding: 16px; border: 1px solid #e1e7ef; background: #fff; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.panel-heading h2 { margin: 3px 0 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }.count-strip { display: grid; grid-template-columns: repeat(5, minmax(90px, 1fr)); border: 1px solid #e8edf3; }.count-strip div { padding: 12px 14px; border-right: 1px solid #e8edf3; }.count-strip div:last-child { border-right: 0; }.count-strip span { display: block; color: #737a84; font-size: 12px; }.count-strip strong { display: block; margin-top: 3px; color: #2b3d52; font-size: 20px; }.count-strip .failure { color: #c45656; }.error-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px 22px; margin: 0 0 14px; }.error-grid div { display: flex; gap: 7px; min-width: 0; font-size: 12px; }.error-grid dt { color: #909399; }.error-grid dd { margin: 0; overflow-wrap: anywhere; }.summary-controls { margin-bottom: 12px; }.summary-controls .el-select { width: min(420px, 100%); }.summary-chart { height: 390px; min-height: 280px; margin-bottom: 14px; border: 1px solid #e5eaf1; } @media (max-width: 760px) { .panel-heading { flex-direction: column; }.count-strip { grid-template-columns: repeat(2, 1fr); }.count-strip div { border-bottom: 1px solid #e8edf3; }.count-strip div:nth-child(2n) { border-right: 0; }.error-grid { grid-template-columns: 1fr; }.summary-chart { height: 320px; } }
.grid-metadata { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin: 0; }.grid-metadata div { padding: 10px; background: #f5f7fa; }.grid-metadata dt { color: #909399; font-size: 12px; }.grid-metadata dd { margin: 4px 0 0; color: #303133; font-size: 14px; } @media (max-width: 760px) { .grid-metadata { grid-template-columns: 1fr; } }
.result-navigation { scroll-margin-top: 12px; }.result-navigation-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }.result-navigation-card { min-width: 0; padding: 12px; border: 1px solid #dfe7ef; border-radius: 4px; color: #334155; background: #f8fafc; text-align: left; cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease, background .15s ease; }.result-navigation-card:hover, .result-navigation-card:focus-visible { border-color: #409eff; box-shadow: 0 0 0 2px rgba(64,158,255,.12); outline: 0; }.result-navigation-card.ready { border-color: #b3e19d; background: #f0f9eb; }.result-navigation-title, .result-navigation-card strong, .result-navigation-card small { display: block; }.result-navigation-title { color: #64748b; font-size: 12px; }.result-navigation-card strong { margin: 5px 0; color: #1f2937; font-size: 15px; }.result-navigation-card small { color: #7b8794; line-height: 1.5; }.result-anchor { scroll-margin-top: 12px; }.result-capability-empty { margin: 0; }.result-empty { min-height: 160px; } @media (max-width: 1000px) { .result-navigation-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 600px) { .result-navigation-grid { grid-template-columns: 1fr; } }
</style>
