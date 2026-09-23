<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import EclipseSummaryExplorer from './EclipseSummaryExplorer.vue'
import EclipseGridSlice from './EclipseGridSlice.vue'
import EclipseGrid3D from './EclipseGrid3D.vue'

const props = defineProps({
  run: { type: Object, default: null },
  history: { type: Array, default: () => [] },
  inspection: { type: Object, default: null }
})


const isFiniteNumber = value => typeof value === 'number' && Number.isFinite(value)
const isNonNegativeInteger = value => Number.isSafeInteger(value) && value >= 0
const validateFieldIndex = fieldIndex => {
  if (fieldIndex === undefined || fieldIndex === null) return true
  if (fieldIndex?.schemaVersion !== 'eclipse-binary-field-index/1' || !Array.isArray(fieldIndex.files) || fieldIndex.files.length > 64) return false
  return fieldIndex.files.every(file => typeof file?.name === 'string' && /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.(EGRID|INIT|UNRST)$/i.test(file.name) &&
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
  if (props.inspection?.schemaVersion !== 'eclipse-data-inspection/4' || !Array.isArray(metadata?.completions)) return []
  return metadata.completions.filter(completion => completion && typeof completion.well === 'string' &&
    ['COMPDAT', 'COMPDATM'].includes(completion.keyword) &&
    ['i', 'j', 'k1', 'k2', 'status'].every(key => typeof completion[key] === 'string'))
})
const scheduleScenario = computed(() => {
  const parameters = props.run?.parameters
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
const scheduleBaseline = ref(null)
const scheduleBaselineLoading = ref(false)
const scheduleBaselineError = ref('')
let scheduleBaselineGeneration = 0
const loadScheduleBaseline = async () => {
  const generation = ++scheduleBaselineGeneration
  scheduleBaseline.value = null
  scheduleBaselineError.value = ''
  scheduleBaselineLoading.value = false
  const scenario = scheduleScenario.value
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
const scheduleCountsChanged = computed(() => scheduleCounts.value?.some(item => item.baseline !== item.current) === true)
const scheduleHasObservableDifference = computed(() => Boolean(scheduleOutputDiff.value && (scheduleOutputDiff.value.changedCount > 0 || scheduleCountsChanged.value)))
const scheduleBaselineIsClean = computed(() => scheduleBaseline.value?.parameters === null || scheduleBaseline.value?.parameters === undefined)
const scheduleAcceptanceType = computed(() => scheduleBaselineLoading.value ? 'info' :
  scheduleBaselineError.value || !scheduleBaselineResult.value ? 'warning' :
  !scheduleBaselineIsClean.value ? 'warning' : scheduleHasObservableDifference.value ? 'success' : 'warning')
const scheduleAcceptanceLabel = computed(() => scheduleBaselineLoading.value ? '读取基线中' :
  scheduleBaselineError.value || !scheduleBaselineResult.value ? '无法比较' :
  !scheduleBaselineIsClean.value ? '基线不合规' : scheduleHasObservableDifference.value ? '检测到真实差异' : '未检测到差异')
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
  if (!props.run?.id || fieldPreviewLoading.value) return
  if (resetPage) {
    selectedField.value = field
    fieldPreviewPage.value = 1
  }
  const artifact = fieldArtifact(field)
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
    const page = fieldPreviewPage.value
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
      const payload = await softwareIntegrationApi.downloadArtifactRange(props.run.id, artifact.id, segmentOffset, alignedLength)
      const blob = payload instanceof Blob ? payload : new Blob([payload])
      const bytes = new Uint8Array(await blob.arrayBuffer())
      values.push(...decodeFieldValues(bytes, field, field.byteOrder))
      skippedValues = 0
    }
    fieldPreview.value = {
      ...field,
      artifactName: artifact.name,
      page,
      startValue,
      values: values.slice(0, maxValues)
    }
  } catch {
    fieldPreviewError.value = '二进制场值读取失败，请检查 Artifact 有效期或重新加载运行记录。'
  } finally {
    fieldPreviewLoading.value = false
  }
}
const loadFieldPreviewPage = page => {
  fieldPreviewPage.value = page
  if (selectedField.value) previewField(selectedField.value, false)
}
watch(() => props.run, () => {
  fieldPreview.value = null
  selectedField.value = null
  fieldPreviewPage.value = 1
  fieldPreviewError.value = ''
  fieldFileFilter.value = 'all'
  fieldTimeStepFilter.value = 'all'
  fieldKeywordFilter.value = ''
  fieldIndexPage.value = 1
})
watch([fieldFileFilter, fieldTimeStepFilter, fieldKeywordFilter, fieldIndexPageSize], () => { fieldIndexPage.value = 1 })
watch(() => filteredFieldRows.value.length, total => {
  const lastPage = Math.max(1, Math.ceil(total / fieldIndexPageSize.value))
  if (fieldIndexPage.value > lastPage) fieldIndexPage.value = lastPage
})
</script>

<template>
  <section class="eclipse-result">
    <el-result v-if="!result" icon="info" :title="resultExpired ? '本次 ECLIPSE 结果已超过 30 天保留期限' : (successfulResultUnavailable ? '本次 ECLIPSE 运行已成功结束，但可验证运行结果不可用' : '尚无已验证真实计算结果')" :sub-title="resultExpired ? '运行状态、ECLEND 审计和事件仍保留；解析结果和原始结果包按保留策略不可再展示。' : (successfulResultUnavailable ? '未接收到符合结果契约的运行结果，因此无法确认是否存在 RSM Summary，也不会展示推测数据。' : (error ? failedRunGuidance : '尚未选择成功运行；仅在真实运行通过结果契约后展示 ECLEND、Summary 和 Artifact。'))">
      <template v-if="error" #extra>
        <el-tag type="danger">{{ error.category }} / {{ error.code }}</el-tag>
      </template>
    </el-result>
    <template v-else>
    <el-alert v-if="summaryNotice" :type="isPartialResult ? 'warning' : 'info'" :closable="false" :title="summaryNotice" />
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
        <div><span class="kicker">ECLIPSE SCHEDULE</span><h2>{{ scheduleScenario.scenarioType === 'COMPDAT_FACTOR' ? 'COMPDAT 连接因子方案验收' : scheduleScenario.scenarioType === 'COMPDAT' ? 'COMPDAT 完井方案验收' : scheduleScenario.scenarioType === 'WCONPROD' ? 'WCONPROD 方案验收' : scheduleScenario.scenarioType === 'WCONINJE' ? 'WCONINJE 方案验收' : scheduleScenario.scenarioType === 'WCONHIST' ? 'WCONHIST 方案验收' : 'WELOPEN 方案验收' }}</h2><p>当前运行与无参数基线运行的真实 ECLEND 计数和输出文件 SHA-256 对比。</p></div>
        <el-tag :type="scheduleAcceptanceType">{{ scheduleAcceptanceLabel }}</el-tag>
      </div>
      <dl class="scenario-metadata">
        <div><dt>当前运行</dt><dd>#{{ run?.id || '-' }}</dd></div>
        <div><dt>基线运行</dt><dd>#{{ scheduleScenario.baselineRunId }}</dd></div>
        <div><dt>目标井</dt><dd>{{ scheduleScenario.well }}</dd></div>
        <div v-if="scheduleScenario.date"><dt>生效日期</dt><dd>{{ scheduleScenario.date }}</dd></div>
        <div v-if="scheduleScenario.phase"><dt>计划段</dt><dd>{{ scheduleScenario.phase === 'FORECAST_INITIAL' ? '预测初始段' : scheduleScenario.phase }}</dd></div>
        <div v-if="scheduleScenario.status"><dt>目标状态</dt><dd>{{ scheduleScenario.status }}</dd></div>
        <div v-if="scheduleScenario.controlMode"><dt>控制关键字</dt><dd>{{ scheduleScenario.controlMode }}</dd></div>
        <div v-if="['COMPDAT', 'COMPDAT_FACTOR'].includes(scheduleScenario.scenarioType)"><dt>完井坐标</dt><dd>I{{ scheduleScenario.i }} / J{{ scheduleScenario.j }} / K{{ scheduleScenario.k1 }}-{{ scheduleScenario.k2 }}</dd></div>
        <div v-if="['COMPDAT', 'COMPDAT_FACTOR'].includes(scheduleScenario.scenarioType)"><dt>源定位</dt><dd>{{ scheduleScenario.sourceFile }}:{{ scheduleScenario.lineNumber }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'COMPDAT_FACTOR'"><dt>原连接因子</dt><dd>{{ scheduleScenario.originalConnectionFactor }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'COMPDAT_FACTOR'"><dt>目标连接因子</dt><dd>{{ scheduleScenario.targetConnectionFactor }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONHIST'"><dt>目标 ORAT 原值</dt><dd>{{ scheduleScenario.targetOilRate }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONPROD'"><dt>目标 ORAT 原值</dt><dd>{{ scheduleScenario.targetOilRate }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONINJE'"><dt>注入流体</dt><dd>{{ scheduleScenario.injectionType }}</dd></div>
        <div v-if="scheduleScenario.scenarioType === 'WCONINJE'"><dt>目标 RATE 原值</dt><dd>{{ scheduleScenario.targetInjectionRate }}</dd></div>
      </dl>
      <el-alert v-if="scheduleBaselineError" type="warning" :closable="false" :title="scheduleBaselineError" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && !scheduleBaselineIsClean" type="warning" :closable="false" title="当前基线本身带有参数方案，不是干净基线；请重新运行并选择不覆盖参数的 ECLIPSE 运行作为基线。" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult && scheduleHasObservableDifference" type="success" :closable="false" title="已检测到真实计算差异：ECLEND 计数或至少一个 ECLIPSE 输出文件发生变化。" />
      <el-alert v-else-if="!scheduleBaselineLoading && scheduleBaselineResult" type="warning" :closable="false" title="本次方案与基线的可展示输出没有差异；不能仅凭运行成功判定方案生效。" />
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
    <div v-if="hasUsableSummary" class="eclipse-comparison">
      <span>当前运行 #{{ run.id }}</span>
      <el-select v-model="comparisonId" filterable clearable :loading="comparisonLoading" placeholder="选择同版本历史计算" aria-label="ECLIPSE 历史结果对比">
        <el-option v-for="item in comparisonOptions" :key="item.id" :value="item.id" :label="`运行 #${item.id} · ${item.createdAt || ''}`" />
      </el-select>
      <span v-if="comparisonError" role="status">{{ comparisonError }}</span>
      <span v-else-if="comparison">实线：当前运行；虚线：历史运行。仅同单位序列可叠加。</span>
    </div>
    <EclipseSummaryExplorer v-if="hasSummary" :series="explorerSeries" :run-id="run?.id" />
    </section>
    <section id="eclipse-diagnostics-result" class="result-anchor">
    <section v-if="diagnosticMessages.length" class="result-panel eclipse-diagnostics">
      <div class="panel-heading"><div><span class="kicker">ECLIPSE DIAGNOSTICS</span><h2>求解诊断</h2><p>以下内容来自本次运行生成的 MSG 诊断文件；Problems 不会被隐藏或改写。</p></div><el-tag :type="isPartialResult ? 'warning' : 'info'">{{ isPartialResult ? '带问题完成' : '含诊断' }}</el-tag></div>
      <div class="diagnostic-counts"><span>Problems {{ diagnosticCounts.problems }}</span><span>Warnings {{ diagnosticCounts.warnings }}</span><span>Errors {{ diagnosticCounts.errors }}</span></div>
      <div v-if="diagnosticGroupRows.length" class="diagnostic-groups" aria-label="ECLIPSE 诊断分类">
        <article v-for="group in diagnosticGroupRows" :key="group.key" class="diagnostic-group-card">
          <strong>{{ group.label }} · {{ group.count }} 条</strong>
          <small>{{ group.review }}</small>
        </article>
      </div>
      <el-alert type="info" :closable="false" title="以上分类依据本次 MSG 原始文本的可追溯模式，仅用于审阅导航；不会自动修改模型、求解器参数或诊断原文。" />
      <el-table :data="diagnosticMessages" border size="small" max-height="360">
        <el-table-column prop="severity" label="级别" width="110" />
        <el-table-column prop="category" label="类别" width="110" />
        <el-table-column prop="code" label="代码" width="170" />
        <el-table-column prop="message" label="ECLIPSE 原始诊断" min-width="520" show-overflow-tooltip />
      </el-table>
    </section>
    <el-empty v-else class="result-panel result-empty" description="本次运行没有结构化 ECLIPSE 诊断消息" :image-size="56" />
    </section>
    <section id="eclipse-3d-result" class="result-anchor">
      <EclipseGrid3D v-if="gridMetadata" :run-id="run?.id" :grid="gridMetadata" :fields="fieldRows" :artifacts="artifacts" :well-completions="scheduleCompletions" />
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
      <div class="panel-heading"><div><h2>二进制场索引</h2><p>列出真实 EGRID、INIT、UNRST 中可读取的关键字和数据范围；当前运行 JSON 不展开网格场值。</p></div><span>{{ fieldRows.length }} 个字段</span></div>
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
.eclipse-comparison { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 8px 10px; background: #f3f3f0; font-size: 12px; }
.schedule-timeline-note { margin: 12px 0 0; color: #737a84; font-size: 12px; line-height: 1.6; }
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
