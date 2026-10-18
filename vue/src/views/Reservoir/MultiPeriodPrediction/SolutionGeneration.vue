<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  PREDICTION_METRICS,
  resolvePredictionId,
  fetchPlatformWells,
  fetchDefaultGroupPredictionName,
  createGroupPrediction,
  fetchPredictionDetail,
  calculatePrediction,
  fetchGroupChart,
  parseForecastCharts,
  buildReservoirCalcInput
} from '@/api/reservoirPredictionApi'
import { getReservoirPredictionContext } from './reservoirPredictionContext'
import { reservoirConstraintRecordApi } from '@/api/reservoirConstraintRecordApi'
import { aggregateStorageBoundaries, validatePlanBoundaries } from './constraintWellRunner'
import { storagePredictionSchemeApi } from '@/api/storagePredictionSchemeApi'
import { storageCatalogApi } from '@/api/storageCatalog'
import { upsertSolutionGenerationRecordNode } from '@/utils/solutionGenerationTree'
import { workspaceTreeData } from '@/utils/workspaceTreeState'

// 列表、保存和详情共用同一响应解包，兼容 Axios 与后端统一响应包装。
function unwrapSavedSchemeResponse(raw, list = false) {
  let value = raw
  for (let depth = 0; depth < 7; depth++) {
    if (!value || typeof value !== 'object') break
    if (value.success === false || Number(value.code) >= 400) throw new Error(value.msg || value.message || '方案接口请求失败')
    if (list && Array.isArray(value)) return value
    if (!list && value.id != null) return value
    value = list
      ? value.records ?? value.content ?? value.list ?? value.data ?? value.result
      : value.record ?? value.data ?? value.result
  }
  throw new Error(list ? '方案列表接口返回格式不正确' : '方案接口未返回有效记录')
}

// 风格查询与总图构造在本组件内部，避免老版本 API 缺少命名导出导致 HMR 崩溃。
async function optionalChartConfigRequest(projectId, path) {
  const base = (import.meta.env?.VITE_ORIGINAL_PLATFORM_API_BASE || '/docker-api').replace(/\/+$/, '')
  const response = await fetch(`${base}${path}`, {
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'x-project-id': String(projectId),
      'Process-Env': String(import.meta.env?.VITE_PLATFORM_PROCESS_ENV ?? 'prod')
    }
  })
  if (!response.ok) throw new Error(`图表样式接口 HTTP ${response.status}`)
  return response.json()
}
async function fetchGroupChartAppearance(projectId, predictionId) {
  const pid = Number(projectId), id = Number(predictionId)
  if (!Number.isSafeInteger(pid) || pid <= 0 || !Number.isSafeInteger(id) || id <= 0) return null
  const [templatesResult, savedResult] = await Promise.allSettled([
    optionalChartConfigRequest(pid, '/common/charttemplates/ProductionPerformance'),
    optionalChartConfigRequest(pid, `/common/chartconfiguration/ProductionPerformance/${id}`)
  ])
  const templates = templatesResult.status === 'fulfilled' ? templatesResult.value?.templates : []
  const active = Array.isArray(templates)
    ? templates.find(item => item?.isActive) || templates[0] : null
  const saved = savedResult.status === 'fulfilled' ? savedResult.value?.chartConfiguration : null
  const raw = saved?.rawConfig ?? saved?.config ?? active?.rawConfig ?? null
  if (!raw) return null
  try {
    const config = typeof raw === 'string' ? JSON.parse(raw) : raw
    return config && typeof config === 'object' && !Array.isArray(config) ? config : null
  } catch { return null }
}

const ORIGINAL_GROUP_SERIES_COLORS = Object.freeze({
  gasProduction: '#F2D300',
  cumulativeGasProduction: '#FDC10A',
  formationPressure: '#D1AF4C',
  reservoirRecoveryDegree: '#ee6666'
})

/**
 * 一个坐标网格 + 四条曲线 + 独立物理单位的多 Y 轴。
 * 数值取自 result/wells/chart，样式取自 ProductionPerformance，绝不造点。
 * 参数：parseForecastCharts 结果 / 原始图表响应 / 原平台图表模板。
 */
function buildGroupOverviewOption(charts, chartPayload, appearance, boundaries) {
  const fieldMap = new Map((Array.isArray(chartPayload?.fields) ? chartPayload.fields : [])
    .filter(field => typeof field?.name === 'string')
    .map(field => [field.name, field]))
  const items = PREDICTION_METRICS.filter(metric =>
    Array.isArray(charts?.[metric.key]) && charts[metric.key].some(row => row?.[1] !== null && row?.[1] !== undefined)
  )
  const counts = { left: 0, right: 0 }
  const usedAxes = []
  const series = []
  for (const metric of items) {
    const index = usedAxes.length
    const isLeft = index === 0 || index === 2
    const position = isLeft ? 'left' : 'right'
    const offset = counts[position]++ * 68
    const seriesStyle = appearance?.seriesList?.[metric.key] || {}
    const axisStyle = appearance?.yAxis?.[metric.key] || {}
    const unit = fieldMap.get(metric.key)?.unit_label || metric.unit
    const color = /^#[0-9a-fA-F]{6}$/.test(seriesStyle.color)
      ? seriesStyle.color : ORIGINAL_GROUP_SERIES_COLORS[metric.key]
    const name = String(seriesStyle.name || axisStyle.name || metric.name)
    usedAxes.push({
      type: 'value', position, offset, alignTicks: index !== 0,
      scale: true,
      name: `${axisStyle.name || metric.name} (${unit})`,
      nameLocation: 'middle', nameGap: index > 1 ? 57 : 49,
      nameTextStyle: { color: '#4d5662', fontSize: 11 },
      axisLine: { show: true, lineStyle: { color } },
      axisTick: { show: false },
      axisLabel: { color: '#596575', fontSize: 10, margin: 9 },
      splitLine: index === 0 ? {
        show: appearance?.gridConfig?.main?.isShowLine !== false,
        lineStyle: { color: appearance?.gridConfig?.main?.lineColor || '#e1e6f0' }
      } : { show: false }
    })
    const isGasProduction = metric.key === 'gasProduction'
    const isFormationPressure = metric.key === 'formationPressure'
    const makeBoundary = (value, name, color, position) => (value === null || value === undefined)
      ? null
      : {
        name, yAxis: value,
        lineStyle: { color, width: 1.8, type: 'dashed' },
        label: {
          show: true, position, fontSize: 10, color,
          formatter: `${name} ${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
        }
      }
    const bound = boundaries && boundaries.complete ? boundaries : null
    const gasLines = isGasProduction && bound
      ? [
        makeBoundary(bound.erosionMax, '冲蚀上限', '#e45a4f', 'insideEndTop'),
        makeBoundary(bound.sandMax, '出砂上限', '#a26ad1', 'insideStartTop'),
        makeBoundary(bound.liquidMin, '积液下限', '#269c71', 'insideEndBottom'),
        makeBoundary(bound.exportPressure, '外输压力', '#3c86d3', 'insideStartBottom')
      ].filter(Boolean)
      : []
    const pressureLines = isFormationPressure && bound
      ? [makeBoundary(bound.exportPressure, '外输压力', '#3c86d3', 'insideEndBottom')].filter(Boolean)
      : []
    series.push({
      name,
      type: 'line',
      yAxisIndex: index,
      xAxisIndex: 0,
      showSymbol: charts[metric.key].length < 80,
      symbol: 'circle', symbolSize: 4,
      connectNulls: false,
      smooth: false,
      animation: false,
      lineStyle: { color, width: Number(seriesStyle.lineWidth) || 2 },
      itemStyle: { color },
      emphasis: { focus: 'series' },
      ...(gasLines.length ? { markLine: { silent: true, symbol: 'none', animation: false, data: gasLines } } : {}),
      ...(isFormationPressure && pressureLines.length ? { markLine: { silent: true, symbol: 'none', animation: false, data: pressureLines } } : {}),
      ...(isGasProduction && bound && Math.max(bound.exportPressure, bound.liquidMin) <= Math.min(bound.erosionMax, bound.sandMax)
        ? {
          markArea: {
            silent: true,
            itemStyle: { color: 'rgba(38, 156, 113, 0.06)' },
            data: [[
              { yAxis: Math.max(bound.exportPressure, bound.liquidMin) },
              { yAxis: Math.min(bound.erosionMax, bound.sandMax) }
            ]]
          }
        }
        : {}),
      data: charts[metric.key]
    })
    if (isGasProduction && bound) {
      const lower = Math.max(bound.exportPressure, bound.liquidMin)
      const upper = Math.min(bound.erosionMax, bound.sandMax)
      const violating = (charts[metric.key] || [])
        .filter(row => row && row[1] !== null && row[1] !== undefined &&
          !(Number(row[1]) >= lower && Number(row[1]) <= upper))
        .map(row => [row[0], Number(row[1])])
      if (violating.length) {
        series.push({
          name: '气量越界点', type: 'scatter', yAxisIndex: index, z: 12,
          symbolSize: 9, animation: false,
          itemStyle: { color: '#d63f3f', borderColor: '#fff', borderWidth: 1 },
          data: violating
        })
      }
    }
  }
  const xAxisName = appearance?.xAxis?.name || '日期'
  const mainTitle = appearance?.name || '动态预测分析图'
  return {
    backgroundColor: '#ffffff',
    animation: false,
    useUTC: true,
    title: {
      text: mainTitle, left: 'center', top: 12,
      textStyle: { color: '#303743', fontFamily: 'Microsoft YaHei, sans-serif', fontSize: 16, fontWeight: 600 }
    },
    legend: {
      show: appearance?.legend?.isShow !== false,
      type: 'scroll', top: 52, left: 'center',
      textStyle: {
        color: appearance?.legend?.color || '#333',
        fontFamily: appearance?.legend?.font || 'Microsoft YaHei',
        fontSize: Number(appearance?.legend?.size) || 12
      },
      data: series.map(item => item.name)
    },
    tooltip: {
      trigger: 'axis', confine: true,
      axisPointer: { type: 'cross', label: { backgroundColor: '#596575' } },
      formatter(params) {
        const entries = Array.isArray(params) ? params : [params]
        if (!entries.length) return ''
        const stamp = Number(entries[0]?.axisValue ?? entries[0]?.value?.[0])
        const date = Number.isFinite(stamp)
          ? new Date(stamp).toISOString().slice(0, 10) : ''
        const escape = value => String(value).replace(/[&<>"']/g, character => ({
          '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        })[character])
        const rows = entries.map(item => {
          const data = Array.isArray(item.value) ? item.value[1] : null
          const value = data === null || data === undefined || !Number.isFinite(Number(data))
            ? '-' : Number(data).toLocaleString('zh-CN', { maximumFractionDigits: 4 })
          const field = item.seriesName === '气量越界点'
            ? PREDICTION_METRICS.find(metric => metric.key === 'gasProduction')
            : items.find(metric => String(appearance?.seriesList?.[metric.key]?.name ||
              appearance?.yAxis?.[metric.key]?.name || metric.name) === item.seriesName)
          const unit = field ? fieldMap.get(field.key)?.unit_label || field.unit : ''
          return `${item.marker || ''} ${escape(item.seriesName)}：${value} ${escape(unit)}`
        })
        return [date, ...rows].join('<br/>')
      }
    },
    grid: { left: 142, right: 142, top: 113, bottom: 88, containLabel: false },
    xAxis: {
      type: 'time', name: xAxisName, nameLocation: 'end',
      axisLabel: { formatter: '{yyyy}-{MM}', color: '#697586' },
      axisLine: { lineStyle: { color: '#b8c2d0' } },
      splitLine: { show: false }
    },
    yAxis: usedAxes,
    series,
    dataZoom: [
      { type: 'inside', xAxisIndex: 0, filterMode: 'none' }
    ]
  }
}


const props = defineProps({
  schemeId: { type: [Number, String], default: null },
  recordId: { type: [Number, String], default: null },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  storageId: { type: [Number, String], required: true },
  storageName: { type: String, default: '' },
  // 兼容上层已有任务，但不要求用户填写 ID。
  dynamicPredictionId: { type: [Number, String], default: null },
  dynamicPredictionRequest: { type: Object, default: null },
  // 上层如果已经选中了井，可用于初始勾选。
  wellNames: { type: Array, default: () => [] },
  // 仅允许当前库真实归属的井，不能退回整个气藏的井列表。
  storageWellNames: { type: Array, default: null },
  storageReservoir: { type: Object, default: null }
})
const emit = defineEmits(['solution-generated'])
const context = computed(() => getReservoirPredictionContext(
  props.projectId, props.gasReservoirId, props.storageId
))

const wells = ref([])
const selectedWellNames = ref([])
const searchText = ref('')
const wellsLoading = ref(false)
const busy = ref(false)
const loadingChart = ref(false)
const errorText = ref('')
const wellErrorText = ref('')
const statusText = ref('')
const schemeSaving = ref(false)
const schemeTitle = ref('')
const currentSchemeId = ref('')
const savedSchemes = ref([])           // 已保存方案列表
const selectedSavedId = ref('')        // 当前选中节点
const loadingSchemes = ref(false)      // 加载方案列表的 loading
const params = reactive({ targetProduction: '', exportPressure: '' })
if (context.value.objectiveQ != null && context.value.objectiveQ !== '') {
  params.targetProduction = String(context.value.objectiveQ)
}
// 方案只能选取数据库中已保存的约束，不能把共享草稿冒充保存记录。
const selectedConstraintId = ref('')
const savedConstraints = ref([])
const selectedConstraint = ref(null)
const constraintsLoading = ref(false)
const constraintDetailLoading = ref(false)
const constraintListError = ref('')
const constraintDetailError = ref('')
let schemeListVersion = 0
let constraintListVersion = 0
let constraintDetailVersion = 0
let settingConstraintSelection = false
const constraints = computed(() => {
  const records = [...savedConstraints.value]
  const selected = selectedConstraint.value
  if (selected && !records.some(item => item.id === selected.id)) records.push(selected)
  if (selectedConstraintId.value && !records.some(item => item.id === selectedConstraintId.value)) {
    records.push({ id: selectedConstraintId.value, label: `约束条件${selectedConstraintId.value}（详情待确认）` })
  }
  return records
})
const boundaryState = computed(() => {
  if (!selectedConstraintId.value) return { boundaries: null, error: '' }
  try {
    if (constraintDetailLoading.value) throw new Error('正在读取已保存约束…')
    if (constraintDetailError.value) throw new Error(constraintDetailError.value)
    const record = selectedConstraint.value
    if (!record || record.id !== selectedConstraintId.value) throw new Error('请重新选择已保存约束')
    if (!selectedWellNames.value.length) throw new Error('请选择参与计算的井')
    // 严格使用所选记录的逐井结果；部分井选择不能直接套用全库总边界。
    const available = new Set(record.wells.filter(well => well.complete).map(well => well.wellName))
    const missing = selectedWellNames.value.filter(name => !available.has(name))
    if (missing.length) throw new Error(`该约束缺少所选井的完整结果：${missing.join('、')}`)
    const boundaries = normalizeBoundaries(aggregateStorageBoundaries(
      record.wells, record.draft, selectedWellNames.value))
    return { boundaries, error: '' }
  } catch (error) { return { boundaries: null, error: error?.message || '约束边界无效' } }
})
const activeBoundaryError = computed(() => boundaryState.value.error)
const activeBoundaries = computed(() => boundaryState.value.boundaries)
const result = ref(null)
const chartRef = ref(null)
const taskCache = new Map()
let chart = null
let resizeObserver = null
let requestVersion = 0
let wellsVersion = 0
let lastWellScope = null

function normalizeNames(input) {
  if (!Array.isArray(input)) return []
  return [...new Set(input
    .map(item => typeof item === 'string' ? item : item?.wellName ?? item?.name)
    .filter(item => typeof item === 'string' && item.trim())
    .map(item => item.trim()))]
}
function signature(names) { return [...new Set(names)].sort().join('\u001f') }

/** 严格读取当前库节点的已有井集合，不从项目/气藏整体井列表推断归属。 */
function resolveStorageWellScope(explicit, reservoir) {
  const sources = [
    ['storageWellNames', explicit],
    ['reservoir.wellNames', reservoir?.wellNames],
    ['reservoir.wells', reservoir?.wells],
    ['reservoir.storageWellNames', reservoir?.storageWellNames],
    ['reservoir.storageWells', reservoir?.storageWells],
    ['reservoir.wellList', reservoir?.wellList],
    ['reservoir.data.wellNames', reservoir?.data?.wellNames],
    ['reservoir.data.wells', reservoir?.data?.wells]
  ]
  // 如果优先字段是空数组，但同一库节点其它权威字段有值，则使用非空名单。
  const arrays = sources.filter(([, values]) => Array.isArray(values))
  for (const [source, values] of arrays) {
    const names = normalizeNames(values)
    if (names.length) return { ready: true, source, names }
  }
  return arrays.length
    ? { ready: true, source: arrays[0][0], names: [] }
    : { ready: false, source: null, names: [] }
}
const storageWellScope = computed(() => resolveStorageWellScope(
  props.storageWellNames, props.storageReservoir
))
const storageScopeSignature = computed(() =>
  storageWellScope.value.ready
    ? `ready:${signature(storageWellScope.value.names)}` : 'unbound'
)
function makeScopeKey() {
  return [props.projectId, props.gasReservoirId, props.storageId].join(':')
}
const selectedSignature = computed(() => signature(selectedWellNames.value))
const filteredWells = computed(() => {
  const search = searchText.value.trim().toLocaleLowerCase()
  return search ? wells.value.filter(name => name.toLocaleLowerCase().includes(search)) : wells.value
})
const currentTaskId = computed(() => taskCache.get(selectedSignature.value) || null)
const hasChart = computed(() => !!result.value?.option?.series?.some(
  item => Array.isArray(item.data) && item.data.some(point => point[1] !== null)
))

function setWellChecked(name, checked) {
  if (busy.value || loadingChart.value || !wells.value.includes(name)) return
  const chosen = new Set(selectedWellNames.value)
  if (checked) chosen.add(name)
  else chosen.delete(name)
  selectedWellNames.value = [...chosen]
}
function selectFilteredWells() {
  if (busy.value || loadingChart.value) return
  selectedWellNames.value = [...new Set([...selectedWellNames.value, ...filteredWells.value])]
}
function clearSelectedWells() {
  if (busy.value || loadingChart.value) return
  selectedWellNames.value = []
}

function clearChart() {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (chart) { chart.dispose(); chart = null }
}
function resetChart() {
  result.value = null
  clearChart()
}
function constraintRecordId(value) {
  if (value == null || String(value).trim() === '') return ''
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('约束记录 ID 无效')
  return String(id)
}
function unwrapConstraintResponse(raw, isList = false) {
  let value = raw
  for (let depth = 0; depth < 7; depth++) {
    if (!value || typeof value !== 'object') break
    if (value.success === false || Number(value.code) >= 400) {
      throw new Error(value.msg || value.message || '读取约束失败')
    }
    if (isList && Array.isArray(value)) return value
    if (!isList && (value.formJson != null || value.form_json != null || value.draft != null)) return value
    value = isList
      ? value.records ?? value.content ?? value.list ?? value.data ?? value.result
      : value.record ?? value.data ?? value.result
  }
  throw new Error(isList ? '约束列表接口返回格式不正确' : '约束详情缺少已保存的表单')
}
function parseConstraintJson(raw, label, array = false) {
  let value = raw
  for (let depth = 0; depth < 3 && typeof value === 'string'; depth++) {
    try { value = JSON.parse(value) } catch { throw new Error(`${label}不是有效 JSON`) }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value) !== array) {
    throw new Error(`${label}格式不正确`)
  }
  return value
}
function normalizeBoundaries(raw) {
  if (raw?.complete === false) throw new Error('约束边界尚未计算完成')
  const boundaries = { ...raw }
  for (const key of ['erosionMax', 'sandMax', 'liquidMin', 'exportPressure']) {
    const value = boundaries[key]
    if (value == null || String(value).trim() === '' || !Number.isFinite(Number(value)) || Number(value) < 0) {
      throw new Error(`约束缺少有效边界：${key}`)
    }
    boundaries[key] = Number(value)
  }
  // 按业务规则，外输压力的数值继续作为气量下界参与比较。
  boundaries.complete = true
  return boundaries
}
function assertConstraintScope(record) {
  for (const [camel, snake, expected] of [
    ['projectId', 'project_id', props.projectId],
    ['gasReservoirId', 'gas_reservoir_id', props.gasReservoirId],
    ['storageId', 'storage_id', props.storageId]
  ]) {
    const actual = record[camel] ?? record[snake]
    if (actual != null && String(actual) !== String(expected)) throw new Error('约束记录不属于当前库')
  }
}
async function loadSavedConstraints() {
  const version = ++constraintListVersion
  constraintsLoading.value = true
  constraintListError.value = ''
  try {
    const raw = await reservoirConstraintRecordApi.list(props.projectId, props.gasReservoirId, props.storageId)
    if (version !== constraintListVersion) return
    savedConstraints.value = unwrapConstraintResponse(raw, true).map(record => {
      assertConstraintScope(record)
      const id = constraintRecordId(record.id)
      if (!id) throw new Error('约束列表存在缺少 ID 的记录')
      return { id, label: String(record.title || record.name || record.recordName || `约束条件${id}`) }
    })
  } catch (error) {
    if (version === constraintListVersion) constraintListError.value = error?.message || '加载已保存约束失败'
  } finally {
    if (version === constraintListVersion) constraintsLoading.value = false
  }
}
async function selectSavedConstraint(rawId, { syncParams = true } = {}) {
  const id = constraintRecordId(rawId)
  const version = ++constraintDetailVersion
  settingConstraintSelection = true
  selectedConstraintId.value = id
  settingConstraintSelection = false
  selectedConstraint.value = null
  constraintDetailError.value = ''
  constraintDetailLoading.value = !!id
  if (!id) {
    if (syncParams) {
      params.targetProduction = ''
      params.exportPressure = ''
    }
    return
  }
  try {
    const raw = await reservoirConstraintRecordApi.detail(id)
    if (version !== constraintDetailVersion) return
    const detail = unwrapConstraintResponse(raw)
    if (constraintRecordId(detail.id) !== id) throw new Error('接口返回了其它约束记录')
    assertConstraintScope(detail)
    const draft = parseConstraintJson(detail.draft ?? detail.formJson ?? detail.form_json, '约束表单')
    if (!draft.gasProperties || !draft.calcMethod || !draft.otherData) throw new Error('约束表单结构不完整')
    const boundaries = normalizeBoundaries(parseConstraintJson(
      detail.boundaries ?? detail.boundariesJson ?? detail.boundaries_json, '约束边界'))
    const wells = parseConstraintJson(
      detail.wellResults ?? detail.wellResultsJson ?? detail.well_results_json, '逐井约束结果', true)
    selectedConstraint.value = {
      id, label: String(detail.title || detail.name || detail.recordName || `约束条件${id}`),
      draft, wells, boundaries
    }
    if (syncParams) {
      params.targetProduction = draft.otherData.targetProduction == null ? '' : String(draft.otherData.targetProduction)
      params.exportPressure = String(boundaries.exportPressure)
    }
  } catch (error) {
    if (version === constraintDetailVersion) constraintDetailError.value = error?.message || '读取约束详情失败'
  } finally {
    if (version === constraintDetailVersion) constraintDetailLoading.value = false
  }
}
function onConstraintsSaved(event) {
  const detail = event.detail || {}
  if (['projectId', 'gasReservoirId', 'storageId'].some(key => String(detail[key]) !== String(props[key]))) return
  loadSavedConstraints()
  if (String(detail.id) === selectedConstraintId.value) {
    requestVersion++
    busy.value = false
    loadingChart.value = false
    resetChart()
    selectSavedConstraint(detail.id)
  }
}

async function loadWells() {
  const version = ++wellsVersion
  // 用户还未绑定库井名单时，不能调用整个气藏井列表替代库井。
  const scope = makeScopeKey()
  const priorSelection = lastWellScope === scope ? [...selectedWellNames.value] : []
  wells.value = []
  selectedWellNames.value = []
  wellErrorText.value = ''
  wellsLoading.value = false
  resetChart()
  const storage = storageWellScope.value
  if (!storage.ready) {
    wellsLoading.value = true
    try {
      const data = await storageCatalogApi.wells(props.storageId, props.projectId, props.gasReservoirId)
      const payload = data?.data ?? data
      if (version !== wellsVersion) return
      if (!Array.isArray(payload)) throw new Error('库级井列表接口返回格式不正确')
      const names = payload
        .map(well => String(well?.wellName || '').trim())
        .filter(Boolean)
      if (!names.length) {
        wellErrorText.value = '当前库的井名单为空，暂时没有可以参与计算的井。'
        return
      }
      storage.names = names
      storage.ready = true
    } catch (error) {
      if (version !== wellsVersion) return
      wellErrorText.value = error?.message || '获取当前库井列表失败'
      return
    } finally {
      if (version === wellsVersion && !storage.ready) wellsLoading.value = false
    }
  }
  if (!storage.names.length) {
    wellErrorText.value = '当前库的井名单为空，暂时没有可以参与计算的井。'
    return
  }

  wellsLoading.value = true
  try {
    // 原平台 /wells 只用来核验这些库级井真实存在，不能拿它定义库的井范围。
    const platformWells = await fetchPlatformWells(props.projectId, props.gasReservoirId)
    if (version !== wellsVersion) return
    const indexed = new Map(platformWells.map(name => [name.trim().toLocaleLowerCase(), name]))
    wells.value = [...new Set(storage.names
      .map(name => indexed.get(name.toLocaleLowerCase()))
      .filter(Boolean))]
    if (!wells.value.length) {
      wellErrorText.value = '库级井名单与原平台气藏井列表没有交集，请核对库—井映射及项目/气藏 ID。'
    } else if (wells.value.length !== storage.names.length) {
      wellErrorText.value = `当前库有 ${storage.names.length - wells.value.length} 口井未在原平台井列表中找到，已排除。`
    }
    const preselected = new Set([...normalizeNames(props.wellNames), ...priorSelection])
    selectedWellNames.value = wells.value.filter(name => preselected.has(name))
    lastWellScope = scope
  } catch (error) {
    if (version !== wellsVersion) return
    wells.value = []
    selectedWellNames.value = []
    wellErrorText.value = error?.message || '核验当前库井名单失败'
  } finally {
    if (version === wellsVersion) wellsLoading.value = false
  }
}

function validateOverrides() {
  for (const [field, label] of [
    ['targetProduction', '目标产量'],
    ['exportPressure', '外输压力']
  ]) {
    const raw = params[field]
    if (raw === '' || raw === null || raw === undefined) continue
    const numeric = Number(raw)
    if (!Number.isFinite(numeric) || numeric < 0) throw new Error(`${label}必须为非负有限数字`)
  }
}

function isAuthError(error) {
  return /HTTP (401|403)|会话无效|无权限/.test(String(error?.message || ''))
}
function isNotFoundError(error) {
  return Number(error?.status) === 404 || /HTTP\s+404\b/.test(String(error?.message || ''))
}

// 只重试 GET；POST 创建任务和 POST Calc 均只执行一次。
// 原平台 HAR 创建任务后才请求详情、Calc 后稍后读取总图，因此采用有限轮询。
const DETAIL_DELAYS = Object.freeze([0, 400, 900, 1600, 2500])
const CHART_DELAYS = Object.freeze([0, 700, 1200, 2200, 3200, 4000])
function hasActualPoints(charts) {
  return PREDICTION_METRICS.some(metric => Array.isArray(charts?.[metric.key]) &&
    charts[metric.key].some(point => point?.[1] !== null &&
      point?.[1] !== undefined && Number.isFinite(Number(point[1]))))
}
async function inCurrentRequest(delay, version) {
  if (delay) await new Promise(resolve => setTimeout(resolve, delay))
  return version === requestVersion
}
async function waitForTaskDetail(id, version) {
  let lastProblem = ''
  for (const [index, delay] of DETAIL_DELAYS.entries()) {
    if (!(await inCurrentRequest(delay, version))) return null
    try {
      const detail = await fetchPredictionDetail(props.projectId, props.gasReservoirId, id)
      if (version !== requestVersion) return null
      if (detail?.reservoirInput) return detail
      lastProblem = '服务器尚未返回 reservoirInput'
    } catch (error) {
      if (!isNotFoundError(error)) throw error
      lastProblem = '任务详情接口返回 HTTP 404'
    }
    if (index < DETAIL_DELAYS.length - 1) {
      statusText.value = `原平台任务 ${id} 已创建，等待任务详情就绪（${index + 1}/${DETAIL_DELAYS.length}）…`
    }
  }
  throw new Error(
    `原平台已返回预测任务 ID ${id}，但多次读取详情失败：${lastProblem}。` +
    '本次未继续调用 Calc。请在原平台确认同一项目/气藏下此任务是否存在；不要反复创建新任务。'
  )
}
async function waitForGroupOverview(id, version) {
  let lastProblem = '无可用响应'
  for (const [index, delay] of CHART_DELAYS.entries()) {
    if (!(await inCurrentRequest(delay, version))) return null
    try {
      const payload = await fetchGroupChart(props.projectId, props.gasReservoirId, id)
      if (version !== requestVersion) return null
      if (Array.isArray(payload?.chartItems)) {
        const charts = parseForecastCharts(payload)
        if (hasActualPoints(charts)) return { payload, charts }
        const fields = payload.chartItems.map(item => item?.yAxisField || '?').slice(0, 8)
        lastProblem = `chartItems=${payload.chartItems.length}，指标=${fields.join('、') || '无'}，有效数值点=0`
      } else {
        lastProblem = `缺少 chartItems；响应字段=${Object.keys(payload || {}).slice(0, 10).join('、')}`
      }
    } catch (error) {
      if (!isNotFoundError(error)) throw error
      lastProblem = '总图接口返回 HTTP 404'
    }
    if (index < CHART_DELAYS.length - 1) {
      statusText.value = `任务 ${id} 的 Calc 已返回，正在等待原平台总图数据就绪（${index + 1}/${CHART_DELAYS.length}）…`
    }
  }
  throw new Error(
    `原平台任务 ${id} 的总图仍不可用（最后响应：${lastProblem}）。` +
    '计算接口返回成功不代表总图已就绪；请核对原平台所显示的预测任务 ID 是否相同，' +
    '并检查 Network 中 result/wells/chart 的 Response。未使用模拟数据。'
  )
}
async function makeTaskName() {
  const provided = String(props.dynamicPredictionRequest?.dynamicPredictionName || '').trim()
  if (provided) return provided
  try {
    return await fetchDefaultGroupPredictionName(props.projectId, props.gasReservoirId)
  } catch (error) {
    if (isAuthError(error)) throw error
    return `井组动态预测-${Date.now()}`
  }
}

/** 一组井对应一个服务器任务。切换选井时不将旧任务的结果冒充当前结果。 */
async function ensureTask(names, version) {
  const key = signature(names)
  const cached = taskCache.get(key)
  if (cached) {
    statusText.value = '正在获取已有方案的计算参数…'
    const detail = await waitForTaskDetail(cached, version)
    if (!detail || version !== requestVersion) return null
    const taskWells = normalizeNames(detail?.wellInputs)
    if (taskWells.length && signature(taskWells) !== key) {
      taskCache.delete(key)
      throw new Error('原平台已有任务的井组已变化，请重新选择井后生成方案')
    }
    return { id: cached, detail }
  }

  // 若父级传入了已有任务，必须与用户实际选择的井完全一致才允许重算。
  const externalId = resolvePredictionId(props.projectId, props.gasReservoirId,
    props.dynamicPredictionId, props.dynamicPredictionRequest?.dynamicPredictionId)
  if (externalId) {
    statusText.value = '正在核验已有任务与所选井…'
    const detail = await waitForTaskDetail(externalId, version)
    if (!detail || version !== requestVersion) return null
    const taskWells = normalizeNames(detail?.wellInputs)
    if (taskWells.length && signature(taskWells) === key) {
      taskCache.set(key, externalId)
      return { id: externalId, detail }
    }
  }

  statusText.value = '正在原平台创建所选井组的预测任务…'
  const dynamicPredictionName = await makeTaskName()
  if (version !== requestVersion) return null
  const created = await createGroupPrediction(props.projectId, props.gasReservoirId, {
    wellNames: names,
    dynamicPredictionName,
    productivityEvaluationForm: props.dynamicPredictionRequest?.productivityEvaluationForm ?? 1
  })
  if (version !== requestVersion) return null
  // 成功创建后才记录任务，后续同井组重复计算复用该任务。
  taskCache.set(key, created.id)
  if (context.value) context.value.predictionId = created.id
  statusText.value = '正在读取原平台任务计算参数…'
  const detail = await waitForTaskDetail(created.id, version)
  if (!detail || version !== requestVersion) return null
  const taskWells = normalizeNames(detail?.wellInputs)
  if (!taskWells.length || signature(taskWells) !== key) {
    taskCache.delete(key)
    throw new Error('原平台创建的预测任务井组与当前选择不一致，已停止计算以免生成错误曲线')
  }
  return { id: created.id, detail }
}

function fillInputDefaults(input) {
  if (params.targetProduction === '' && input?.targetProduction !== undefined) {
    params.targetProduction = String(input.targetProduction)
  }
  if (params.exportPressure === '' && input?.externalWellheadPressure !== undefined) {
    params.exportPressure = String(input.externalWellheadPressure)
  }
}
async function readOverview(id, input, version, isNewCalculation, boundaries = null, constraintId = null) {
  statusText.value = '正在读取原平台井组动态预测总图…'
  const ready = await waitForGroupOverview(id, version)
  if (!ready || version !== requestVersion) return
  const { payload, charts } = ready
  // 图表模板仅影响样式。若该可选接口不可用，按 HAR 记录的模板默认值渲染。
  const appearance = await fetchGroupChartAppearance(props.projectId, id)
  if (version !== requestVersion) return
  const option = buildGroupOverviewOption(charts, payload, appearance, boundaries)
  if (!option.series.length) throw new Error('原平台返回的总图没有有效曲线，未使用模拟数据')
  const snapshot = {
    predictionId: id,
    constraintRecordId: constraintId ? Number(constraintId) : null,
    selectedWells: [...selectedWellNames.value],
    charts,
    input: { ...input },
    forecastStartDate: input.predictionStartTime,
    scope: 'group',
    retrievedAt: new Date().toISOString(),
    option,
    ...(boundaries ? { boundaries: { ...boundaries } } : {})
  }
  result.value = snapshot
  if (isNewCalculation && Array.isArray(context.value?.liveSolutions)) {
    const no = context.value.nextSolutionNo++
    context.value.liveSolutions.push({
      id: `preview-${no}`, name: `Calc ${no}`,
      createdAt: snapshot.retrievedAt,
      charts: JSON.parse(JSON.stringify(charts)),
      wellNames: [...selectedWellNames.value]
    })
    if (context.value.liveSolutions.length > 20) context.value.liveSolutions.shift()
  }
  await nextTick()
  if (version === requestVersion) {
    renderChart()
    emit('solution-generated', snapshot)
  }
}

async function generateSolution() {
  if (busy.value || loadingChart.value || wellsLoading.value) return
  errorText.value = ''
  statusText.value = ''
  const names = [...selectedWellNames.value]
  const constraintId = selectedConstraintId.value
  if (!storageWellScope.value.ready || !storageWellScope.value.names.length) {
    errorText.value = '没有可信的当前库井归属数据，已停止计算；请提供诊断曲线页面的取井数据来源。'
    return
  }
  const allowed = new Set(wells.value)
  if (!names.length || names.some(name => !allowed.has(name))) {
    errorText.value = '请从当前库已有井列表中勾选有效井，不能包含其他库的井。'
    return
  }
  busy.value = true
  resetChart()
  const version = ++requestVersion
  try {
    validateOverrides()
    if (selectedConstraintId.value) {
      // 创建服务器任务之前先验证已知的目标产量，避免明显超限仍写入预测任务。
      if (activeBoundaryError.value) throw new Error(activeBoundaryError.value)
      if (params.targetProduction !== '' && params.exportPressure !== '') {
        const warnings = validatePlanBoundaries({
          targetProduction: params.targetProduction,
          exportPressure: params.exportPressure
        }, activeBoundaries.value)
        warnings.forEach(text => ElMessage.warning(text))
      }
    }
    const task = await ensureTask(names, version)
    if (!task || version !== requestVersion) return
    if (!task.detail?.reservoirInput) throw new Error('原平台任务缺少 reservoirInput 计算参数')
    fillInputDefaults(task.detail.reservoirInput)
    const draft = selectedConstraint.value?.draft || null
    const input = buildReservoirCalcInput(task.detail.reservoirInput, draft, params)
    const boundaries = selectedConstraintId.value ? activeBoundaries.value : null
    if (selectedConstraintId.value) {
      if (!boundaries) throw new Error(activeBoundaryError.value || '选井边界数据未就绪')
      const warnings = validatePlanBoundaries({
        targetProduction: input.targetProduction,
        exportPressure: input.externalWellheadPressure
      }, boundaries)
      warnings.forEach(text => ElMessage.warning(text))
    }
    statusText.value = '正在执行原平台 Calc…'
    await calculatePrediction(props.projectId, props.gasReservoirId, task.id, input)
    if (version !== requestVersion) return
    // 保留原有的计算结果 GET 重试，并在发出事件之前保存边界。
    await readOverview(task.id, input, version, true, boundaries, constraintId)
    if (version === requestVersion) {
      statusText.value = '方案计算完成，已显示原平台井组预测总图。'
      ElMessage.success('已生成井组预测总图')
    }
  } catch (error) {
    if (version !== requestVersion) return
    errorText.value = error?.message || '方案生成失败'
    statusText.value = ''
    ElMessage.error(errorText.value)
  } finally {
    if (version === requestVersion) busy.value = false
  }
}

async function saveScheme() {
  if (schemeSaving.value || !result.value) {
    ElMessage.warning('请先生成方案再保存')
    return
  }
  const snapshot = result.value
  if (!snapshot.predictionId) {
    ElMessage.warning('缺少原平台预测任务ID，无法保存方案')
    return
  }
  const title = String(schemeTitle.value || '').trim()
  const saveScope = makeScopeKey()
  schemeSaving.value = true
  try {
    const constraintId = snapshot.constraintRecordId || null
    const payload = {
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      storageId: Number(props.storageId),
      title: title || null,
      constraintRecordId: constraintId ? Number(constraintId) : null,
      originalPredictionId: Number(snapshot.predictionId),
      // 保存完整原始结果及当时的边界；比选按该快照筛选，不覆盖原始曲线。
      wellNamesJson: [...snapshot.selectedWells],
      calcInputJson: JSON.parse(JSON.stringify(snapshot.input)),
      boundariesJson: snapshot.boundaries ? { ...snapshot.boundaries } : null,
      chartsJson: JSON.parse(JSON.stringify(snapshot.charts)),
      forecastStartTime: snapshot.forecastStartDate != null ? String(snapshot.forecastStartDate) : null
    }
    const idToUpdate = currentSchemeId.value || null
    const response = idToUpdate
      ? await storagePredictionSchemeApi.update(idToUpdate, payload)
      : await storagePredictionSchemeApi.save(payload)
    const saved = unwrapSavedSchemeResponse(response)
    if (!Number.isSafeInteger(Number(saved.id)) || Number(saved.id) <= 0) throw new Error('保存接口没有返回有效方案ID')
    if (idToUpdate && String(saved.id) !== String(idToUpdate)) throw new Error('更新接口返回了其它方案 ID，已停止同步比选列表')
    if (saveScope === makeScopeKey()) {
      currentSchemeId.value = String(saved.id)
      schemeTitle.value = saved.title || title || ''
      loadSavedSchemes()
    }
    window.dispatchEvent(new CustomEvent('grdp:reservoir-solution-saved', {
      detail: {
        scopeKey: saveScope,
        projectId: payload.projectId,
        gasReservoirId: payload.gasReservoirId,
        storageId: payload.storageId,
        id: saved.id,
        recordName: saved.title || `方案${saved.id}`,
        hasBoundarySnapshot: !!payload.boundariesJson,
        action: idToUpdate ? 'updated' : 'created'
      }
    }))
    // 把新方案即时插入左侧导航树"方案生成"目录下
    upsertSolutionGenerationRecordNode({
      treeData: workspaceTreeData,
      projectId: payload.projectId,
      gasReservoirId: payload.gasReservoirId,
      storageId: payload.storageId,
      record: { id: saved.id, title: saved.title }
    })
    ElMessage.success(`预测方案已保存：${saved.title || `方案${saved.id}`}。` +
      (payload.boundariesJson ? '可在方案比选中选择。' : '未保存约束边界，请选择约束后重新生成并保存，再进行界限内比选。'))
  } catch (error) {
    ElMessage.error('保存方案失败：' + (error?.message || '未知错误'))
  } finally {
    schemeSaving.value = false
  }
}
// 加载已保存的方案列表
async function loadSavedSchemes() {
  const version = ++schemeListVersion
  loadingSchemes.value = true
  try {
    const data = await storagePredictionSchemeApi.list(
      props.projectId, props.gasReservoirId, props.storageId
    )
    if (version !== schemeListVersion) return
    savedSchemes.value = unwrapSavedSchemeResponse(data, true)
  } catch (error) {
    if (version === schemeListVersion) console.error('加载方案列表失败：', error?.message || error)
  } finally {
    if (version === schemeListVersion) loadingSchemes.value = false
  }
}

// 点击节点，加载方案详情并回显
// 从左侧导航树节点加载已保存的方案
async function loadSchemeDetail(schemeId) {
  if (!schemeId || busy.value) return
  constraintDetailVersion++
  constraintDetailLoading.value = false
  resetChart()
  selectedSavedId.value = String(schemeId)
  loadingChart.value = true
  errorText.value = ''
  statusText.value = '正在加载已保存方案…'
  const version = ++requestVersion
  try {
    const scheme = await storagePredictionSchemeApi.detail(schemeId)
    const detail = unwrapSavedSchemeResponse(scheme)
    if (!detail) throw new Error('方案详情为空')
    assertConstraintScope(detail)
    if (version !== requestVersion) return

    // 恢复井选择
    const savedWells = Array.isArray(detail.wellNamesJson)
      ? detail.wellNamesJson
      : (typeof detail.wellNamesJson === 'string' ? JSON.parse(detail.wellNamesJson) : [])
    if (!savedWells.length) throw new Error('方案中无保存的井数据')
    if (savedWells.some(name => !wells.value.includes(name))) {
      throw new Error('历史方案包含当前库不可用的井，请重新选择井生成方案，避免将原井组曲线当作部分井的结果')
    }
    selectedWellNames.value = [...savedWells]

    // 恢复计算参数
    const calcInput = typeof detail.calcInputJson === 'string'
      ? JSON.parse(detail.calcInputJson) : (detail.calcInputJson || {})
    params.targetProduction = calcInput.targetProduction != null ? String(calcInput.targetProduction) : ''
    params.exportPressure = calcInput.externalWellheadPressure != null ? String(calcInput.externalWellheadPressure) : ''

    // 恢复约束条件选择状态
    await selectSavedConstraint(detail.constraintRecordId, { syncParams: false })
    if (version !== requestVersion) return

    currentSchemeId.value = String(detail.id)
    schemeTitle.value = detail.title || ''

    // 恢复边界条件
    const rawBoundaries = detail.boundariesJson ?? detail.boundaries_json
    const savedBoundaries = rawBoundaries == null ? null : normalizeBoundaries(
      parseConstraintJson(rawBoundaries, '方案边界快照'))
    if (detail.constraintRecordId && !savedBoundaries) {
      throw new Error('历史方案关联了约束但缺少边界快照，请重新选择约束生成方案')
    }

    // 恢复图表数据
    let charts = null
    try {
      charts = typeof detail.chartsJson === 'string'
        ? JSON.parse(detail.chartsJson) : (detail.chartsJson || null)
    } catch { charts = null }

    if (!charts || !PREDICTION_METRICS.some(
      m => Array.isArray(charts?.[m.key]) && charts[m.key].some(p => p?.[1] != null)
    )) {
      if (detail.originalPredictionId) {
        await readOverview(detail.originalPredictionId, calcInput, version, false, savedBoundaries, detail.constraintRecordId)
        if (version === requestVersion) statusText.value = '已从原平台重新加载方案总图。'
        return
      }
      throw new Error('方案未缓存图表数据且缺少原平台预测任务ID')
    }

    const appearance = detail.originalPredictionId
      ? await fetchGroupChartAppearance(props.projectId, detail.originalPredictionId) : null
    if (version !== requestVersion) return

    const option = buildGroupOverviewOption(charts, null, appearance, savedBoundaries)
    if (!option.series.length) throw new Error('方案缓存的图表数据无效')

    result.value = {
      predictionId: detail.originalPredictionId || null,
      constraintRecordId: detail.constraintRecordId ? Number(detail.constraintRecordId) : null,
      selectedWells: [...selectedWellNames.value],
      charts, input: calcInput,
      forecastStartDate: detail.forecastStartTime || null,
      scope: 'group', retrievedAt: detail.createdAt || new Date().toISOString(),
      option,
      ...(savedBoundaries ? { boundaries: { ...savedBoundaries } } : {})
    }
    await nextTick()
    if (version === requestVersion) {
      renderChart()
      statusText.value = `已加载方案：${detail.title || `方案${detail.id}`}`
      ElMessage.success(statusText.value)
    }
  } catch (error) {
    if (version !== requestVersion) return
    errorText.value = error?.message || '加载方案失败'
    statusText.value = ''
    ElMessage.error(errorText.value)
  } finally {
    if (version === requestVersion) loadingChart.value = false
  }
}
async function refreshOverview() {
  if (busy.value || loadingChart.value) return
  const snapshot = result.value
  const id = snapshot?.predictionId
  if (!id) { ElMessage.warning('请先生成或加载方案，再刷新总图'); return }
  loadingChart.value = true
  errorText.value = ''
  const version = ++requestVersion
  try {
    statusText.value = '正在刷新原平台总图数据…'
    // 刷新已有总图只需要服务端结果接口。
    // 原平台任务详情偶尔会返回 404，但这不应妨碍读取已经存在的总图。
    const lastInput = snapshot.input || {}
    await readOverview(id, lastInput, version, false, snapshot.boundaries || null, snapshot.constraintRecordId || null)
    if (version === requestVersion) statusText.value = '原平台井组总图已刷新（未重复计算）。'
  } catch (error) {
    if (version !== requestVersion) return
    errorText.value = error?.message || '刷新总图失败'
    statusText.value = ''
    ElMessage.error(errorText.value)
  } finally {
    if (version === requestVersion) loadingChart.value = false
  }
}

function renderChart() {
  if (!chartRef.value || !hasChart.value) { clearChart(); return }
  if (chart && chart.getDom() !== chartRef.value) clearChart()
  if (!chart) {
    chart = echarts.init(chartRef.value)
    if (typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(() => chart?.resize())
      resizeObserver.observe(chartRef.value)
    }
  }
  chart.setOption(result.value.option, true)
  chart.resize()
}
function exportChart() {
  if (!chart || !hasChart.value) return
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  const link = document.createElement('a')
  link.href = url
  const safeName = String(props.storageName || '井组').replace(/[\\/:*?"<>|]/g, '-')
  link.download = `${safeName}-井组动态预测总图.png`
  document.body.appendChild(link)
  link.click()
  link.remove()
}
const resizeChart = () => chart?.resize()

watch(selectedConstraintId, id => {
  if (settingConstraintSelection) return
  resetChart()
  statusText.value = ''
  selectSavedConstraint(id)
}, { flush: 'sync' })
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
  requestVersion++
  wellsVersion++
  taskCache.clear()
  wells.value = []
  selectedWellNames.value = []
  searchText.value = ''
  statusText.value = ''
  errorText.value = ''
  resetChart()
  schemeListVersion++
  constraintListVersion++
  constraintDetailVersion++
  savedConstraints.value = []
  selectSavedConstraint('')
  busy.value = false
  loadingChart.value = false
  currentSchemeId.value = ''
  schemeTitle.value = ''
  savedSchemes.value = []
  loadSavedConstraints()
  loadSavedSchemes()
  loadWells()
})
watch(storageScopeSignature, () => {
  // 库的井集合变化时取消前端旧结果（服务端已收到的 Calc 无法撤回）。
  requestVersion++
  taskCache.clear()
  busy.value = false
  loadingChart.value = false
  loadWells()
})
watch(() => selectedSignature.value, () => {
  resetChart()
  statusText.value = ''
})
watch(params, resetChart, { deep: true })

// 同一个组件实例切换历史方案节点时也重新读取；等当前库井名单就绪后再恢复。
watch(() => [props.schemeId || props.recordId, wells.value], ([id, available]) => {
  if (id && available.length) loadSchemeDetail(id)
})

onMounted(() => {
  loadSavedConstraints()
  loadWells()
  loadSavedSchemes()
  window.addEventListener('resize', resizeChart)
  window.addEventListener('grdp:reservoir-constraints-saved', onConstraintsSaved)
})
onBeforeUnmount(() => {
  requestVersion++
  wellsVersion++
  schemeListVersion++
  constraintListVersion++
  constraintDetailVersion++
  window.removeEventListener('resize', resizeChart)
  window.removeEventListener('grdp:reservoir-constraints-saved', onConstraintsSaved)
  clearChart()
})
</script>

<template>
  <div class="solution-generation-workspace">
    <aside class="sg-sidebar">
      <div class="section-title">方案参数</div>
      <div class="sg-sidebar-storage-name">{{ storageName || '当前库' }}</div>
      <div class="sg-content">
        <section class="sg-block">
                    <div class="sg-block-title">
            <span>选择参与计算的井</span>
            <button type="button" class="sg-text-button" :disabled="wellsLoading || busy" @click="loadWells">
              刷新井列表
            </button>
          </div>
          <p class="sg-caption">仅显示「{{ storageName || '当前库' }}」已经归属且在原平台可用的井，不显示同气藏的其他库井。</p>
          <input v-model="searchText" class="sg-input" type="search" placeholder="搜索井名" aria-label="搜索井名"
            :disabled="wellsLoading || busy" />
          <div class="sg-wells-actions">
            <span>已选 {{ selectedWellNames.length }} / {{ wells.length }} 口</span>
            <div>
              <button type="button" :disabled="wellsLoading || busy || !filteredWells.length"
                @click="selectFilteredWells">全选筛选结果</button>
              <button type="button" :disabled="wellsLoading || busy || !selectedWellNames.length"
                @click="clearSelectedWells">清空</button>
            </div>
          </div>
          <div v-if="wellsLoading" class="sg-well-placeholder">正在核验当前库已有井…</div>
          <div v-else-if="wellErrorText && !wells.length" class="sg-error">{{ wellErrorText }}</div>
          <div v-else-if="!filteredWells.length" class="sg-well-placeholder">没有匹配的井</div>
          <div v-else class="sg-wells-list" role="group" aria-label="井组勾选">
            <label v-for="name in filteredWells" :key="name" class="sg-well-item" :title="name">
              <input type="checkbox" :checked="selectedWellNames.includes(name)" :disabled="busy || loadingChart"
                @change="setWellChecked(name, $event.target.checked)" />
              <span>{{ name }}</span>
            </label>
          </div>
          <p v-if="wellErrorText && wells.length" class="sg-caption sg-partial-note">{{ wellErrorText }}</p>
        </section>

               <section class="sg-block">
          <div class="sg-block-title"><span>预测计算条件</span></div>
          <label class="field">
            <span>井组目标产量 <small>(10⁴m³/d)</small></span>
            <input v-model="params.targetProduction" type="number" min="0" step="any" placeholder="为空则取原平台任务参数"
              :disabled="busy || loadingChart" />
          </label>
          <label class="field">
            <span>井口外输压力 <small>(MPa)</small></span>
            <input v-model="params.exportPressure" type="number" min="0" step="any" placeholder="为空则取原平台任务参数"
              :disabled="busy || loadingChart" />
          </label>
          <label class="field">
            <span>已保存的约束条件</span>
            <select v-model="selectedConstraintId" :disabled="busy || loadingChart || constraintsLoading">
              <option value="">采用原平台默认参数</option>
              <option v-for="item in constraints" :key="item.id" :value="item.id">{{ item.label }}</option>
            </select>
          </label>
          <div class="sg-wells-actions">
            <span>{{ constraintsLoading ? '正在加载已保存约束…' : `已保存 ${savedConstraints.length} 项约束` }}</span>
            <button type="button" :disabled="constraintsLoading || busy || loadingChart" @click="loadSavedConstraints">刷新约束</button>
          </div>
          <p v-if="constraintListError" class="sg-error">{{ constraintListError }}</p>
          <p v-if="!constraintsLoading && !constraints.length && !constraintListError" class="sg-caption">请先在约束条件页面计算并保存，再到此处选择。</p>
          <div v-if="selectedConstraintId && activeBoundaries" class="sg-boundary-panel">
            <div class="sg-boundary-title">本方案边界条件</div>
            <div class="sg-boundary-row">
              <span>冲蚀上限</span><strong>{{ activeBoundaries.erosionMax.toFixed(4) }} <small>10⁴m³/d</small></strong>
            </div>
            <div class="sg-boundary-row">
              <span>出砂上限</span><strong>{{ activeBoundaries.sandMax.toFixed(4) }} <small>10⁴m³/d</small></strong>
            </div>
            <div class="sg-boundary-row">
              <span>积液下限</span><strong>{{ activeBoundaries.liquidMin.toFixed(4) }} <small>10⁴m³/d</small></strong>
            </div>
            <div class="sg-boundary-row">
              <span>井口外输压力</span><strong>{{ activeBoundaries.exportPressure.toFixed(4) }} <small>MPa</small></strong>
            </div>
            <div class="sg-boundary-row">
              <span>总图气量下界</span><strong>{{ Math.max(activeBoundaries.liquidMin, activeBoundaries.exportPressure).toFixed(4) }}</strong>
            </div>
            <div class="sg-boundary-row">
              <span>总图气量上界</span><strong>{{ Math.min(activeBoundaries.erosionMax, activeBoundaries.sandMax).toFixed(4) }}</strong>
            </div>
            <p class="sg-caption">气量下界取积液下限与外输压力的较大值，上界取冲蚀上限与出砂上限的较小值。</p>
            <p v-if="Math.max(activeBoundaries.liquidMin, activeBoundaries.exportPressure) > Math.min(activeBoundaries.erosionMax, activeBoundaries.sandMax)" class="sg-error">下界大于上界，当前选井不存在可行气量区间；图中气量点将标记为越界。</p>
          </div>
          <p v-else-if="selectedConstraintId" class="sg-error">{{ activeBoundaryError }}</p>
        </section>
        <div class="form-actions">
          <button type="button" class="sg-generate"
            :disabled="busy || loadingChart || wellsLoading || constraintDetailLoading || !!activeBoundaryError || !selectedWellNames.length" @click="generateSolution">
            {{ busy ? '生成中…' : '生成方案' }}
          </button>
          <button type="button" class="sg-generate" :disabled="schemeSaving || !hasChart" @click="saveScheme">
            {{ schemeSaving ? '保存中…' : (currentSchemeId ? '更新方案' : '保存方案') }}
          </button>
        </div>
        <p class="sg-caption">保存方案后可在“方案比选”中选择，比选仅显示本方案保存的边界内的预测时段。</p>
        <p v-if="statusText" class="sg-status" role="status">{{ statusText }}</p>
        <p v-if="errorText" class="sg-error" role="alert">{{ errorText }}</p>
      </div>
    </aside>

        <main class="sg-chart-area">
      <div class="sg-tab">{{ storageName || '当前库' }} · 井组动态预测分析图</div>
      <p v-if="currentTaskId" class="sg-task-indicator">原平台预测任务：{{ currentTaskId }}（后端分配，用于核对结果）</p>
      <div v-if="!hasChart" class="chart-placeholder">
          <span class="chart-placeholder-icon">⌁</span>
          <strong>井组预测总图</strong>
          <p>先从左侧选择井，再点击"生成方案"。</p>
          <p class="sg-hint">气产量、累产气量、地层压力和采出程度将显示在同一张图上。</p>
        </div>
        <div v-else class="sg-chart-body">
          <div ref="chartRef" class="prediction-chart" role="img"
            aria-label="原平台井组动态预测总图，包含气产量、累产气量、地层压力和采出程度曲线"></div>
        </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.sg-task-indicator {
  margin: 0 16px 4px;
  color: #909399;
  font-size: 11px;
}

.solution-generation-workspace {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  min-width: 0;
  background: #fff;
  color: #252525;
  font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

/* ---- 侧边栏标题（与井页面统一的金色左边框） ---- */
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 12px 20px 8px;
  color: #1f2937;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  letter-spacing: 0.2px;
}

.section-title::before {
  width: 4px;
  height: 15px;
  flex: 0 0 4px;
  border-radius: 2px;
  background: #d5b900;
  content: '';
}

.sg-sidebar-storage-name {
  padding: 0 20px 4px;
  color: #909399;
  font-size: 11px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sg-sidebar {
  width: 290px;
  flex: 0 0 290px;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-right: 1px solid #e4e7ed;
  background: #fff;
}

.sg-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 20px 20px;
}

/* ---- 井选择区块 ---- */
.sg-block {
  padding: 0;
  margin-bottom: 14px;
}

.sg-block-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.sg-hint,
.sg-caption {
  margin: 4px 0 10px;
  color: #909399;
  font-size: 11px;
  line-height: 1.6;
}

.sg-text-button {
  font: inherit;
  color: #8c771e;
  border: 0;
  background: none;
  padding: 3px;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
}

.sg-text-button:hover:not(:disabled) { color: #b89820; }
.sg-text-button:disabled { color: #c0c4cc; cursor: not-allowed; }

.sg-input {
  display: block;
  width: 100%;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #aaa;
  border-radius: 3px;
  color: #333;
  background: #fff;
  box-sizing: border-box;
  font: inherit;
  font-size: 12px;
}

.sg-input:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

/* ---- 井列表 ---- */
.sg-wells-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 5px;
  padding: 6px 0;
  color: #909399;
  font-size: 11px;
}

.sg-wells-actions div {
  display: flex;
  gap: 6px;
}

.sg-wells-actions button {
  border: 0;
  padding: 3px 0;
  background: none;
  color: #8c771e;
  font-size: 11px;
  cursor: pointer;
}

.sg-wells-actions button:disabled { color: #c0c4cc; cursor: not-allowed; }

.sg-wells-list {
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 6px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
  background: #fafbfc;
}

.sg-well-item {
  display: flex;
  gap: 5px;
  align-items: center;
  padding: 4px 5px;
  border-radius: 3px;
  cursor: pointer;
  min-width: 0;
  font-size: 12px;
}

.sg-well-item:hover { background: #fff8cf; }

.sg-well-item input { margin: 0; accent-color: #d2ae00; flex: 0 0 auto; }

.sg-well-item span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sg-well-placeholder {
  padding: 18px 8px;
  color: #909399;
  text-align: center;
  font-size: 12px;
}

.sg-partial-note { color: #e6a23c; }

/* ---- 输入字段（与井页面统一的 .field） ---- */
.field {
  display: block;
  min-width: 0;
  margin-bottom: 12px;
}

.field span {
  display: block;
  margin-bottom: 3px;
  color: #252525;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.field small { font-weight: 400; color: #909399; }

.field input,
.field select {
  display: block;
  width: 100%;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  box-sizing: border-box;
  border: 1px solid #aaa;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
  font-size: 12px;
}

.field input:focus-visible,
.field select:focus-visible {
  outline: 1px solid #d5b900;
  outline-offset: 1px;
}

.field input:disabled,
.field select:disabled {
  background: #f5f7fa;
  color: #c0c4cc;
}

/* ---- 边界条件面板（与井页面统一） ---- */
.sg-boundary-panel {
  margin: 4px 0 14px;
  padding: 9px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fafbfc;
}

.sg-boundary-title {
  margin-bottom: 7px;
  font-size: 12px;
  font-weight: 700;
}

.sg-boundary-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 6px;
  margin: 5px 0;
  font-size: 11px;
}

.sg-boundary-row strong {
  color: #2c3e50;
  text-align: right;
  white-space: nowrap;
  font-weight: 600;
}

.sg-boundary-row small { color: #909399; font-size: 10px; font-weight: 400; }

/* ---- 按钮（与井页面统一的网格布局） ---- */
.form-actions {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 10px;
  margin-top: 4px;
  width: 100%;
}

.form-actions > button {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  padding: 0 6px;
  white-space: nowrap;
  text-align: center;
}

.sg-generate {
  height: 30px;
  padding: 0 22px;
  border: 1px solid #d5b900;
  border-radius: 4px;
  background: #f4d000;
  color: #222;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  font-weight: 500;
}

.sg-generate:hover:not(:disabled) { background: #ffe033; }
.sg-generate:disabled { opacity: 0.6; cursor: wait; }

.sg-record-state {
  margin-top: 4px;
  color: #909399;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.sg-status,
.sg-error {
  margin: 10px 0 0;
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.sg-status { color: #606266; }
.sg-error { color: #f56c6c; }

/* ---- 图表区（与井页面统一的黄色标签页） ---- */
.sg-tab {
  height: 34px;
  line-height: 34px;
  max-width: min(430px, 100%);
  padding: 0 12px;
  border-right: 1px solid #e4e7ed;
  box-sizing: border-box;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}

.sg-chart-area {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.chart-placeholder {
  flex: 1;
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
  text-align: center;
  padding: 20px;
}

.chart-placeholder-icon {
  font-size: 48px;
  color: #d5b900;
  margin-bottom: 8px;
}

.chart-placeholder strong {
  color: #606266;
  font-size: 14px;
}

.chart-placeholder p {
  font-size: 12px;
  margin: 6px 0 0;
}

.sg-chart-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 8px 6px;
}

.prediction-chart {
  width: 100%;
  flex: 1;
  min-height: 400px;
}

@media (max-width: 900px) {
  .solution-generation-workspace {
    flex-direction: column;
    overflow: auto;
  }

  .sg-sidebar {
    width: 100%;
    flex: 0 0 auto;
    border-right: 0;
    border-bottom: 1px solid #e4e7ed;
  }

  .sg-content { padding: 14px 20px; }

  .sg-chart-area { flex: 0 0 auto; }

  .prediction-chart { min-height: 280px; }
}
</style>