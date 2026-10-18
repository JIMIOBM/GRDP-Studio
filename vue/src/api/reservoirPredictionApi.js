/**
 * 原平台动态预测接口，独立使用 /docker-api 代理。
 * vite.config.js 已将 /api 指向本项目 Spring Boot (8080)，
 * 而 /docker-api 才转发给原平台 (9920)，并按原平台规则传递身份 Cookie。
 * 不硬编码 Cookie、账号或任务 ID。
 *
 * 为避免旧的 VITE_RESERVOIR_API_BASE=/api 再次误转发，
 * 使用专属配置项 VITE_ORIGINAL_PLATFORM_API_BASE。
 */
const API_BASE = (import.meta.env?.VITE_ORIGINAL_PLATFORM_API_BASE || '/docker-api').replace(/\/+$/, '')
// HAR 中原平台成功请求携带 Process-Env: prod；可在 .env.local 中按实际环境覆盖。
const PROCESS_ENV = import.meta.env?.VITE_PLATFORM_PROCESS_ENV ?? 'prod'

export const PREDICTION_METRICS = Object.freeze([
  { key: 'gasProduction', name: '气产量', unit: '10⁴m³/d' },
  { key: 'cumulativeGasProduction', name: '累产气量', unit: '10⁸m³' },
  { key: 'formationPressure', name: '地层压力', unit: 'MPa' },
  { key: 'reservoirRecoveryDegree', name: '采出程度', unit: '%' }
])

function apiId(value, label) {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error(`${label}必须是正整数`)
  return id
}

export function resolvePredictionId(_projectId, _gasReservoirId, ...candidates) {
  for (const candidate of candidates) {
    const id = Number(candidate)
    if (candidate !== null && candidate !== undefined && candidate !== '' &&
        Number.isSafeInteger(id) && id > 0) return id
  }
  return null
}

async function request(projectId, path, { method = 'GET', body, signal } = {}) {
  const pid = apiId(projectId, '项目ID')
  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json, text/plain, */*',
        'x-project-id': String(pid),
        ...(PROCESS_ENV ? { 'Process-Env': String(PROCESS_ENV) } : {}),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      ...(signal ? { signal } : {})
    })
  } catch (error) {
    if (error?.name === 'AbortError') throw error
    throw new Error(`请求 ${method} ${path} 失败：${error?.message || '网络异常'}。请检查 /docker-api 代理和原平台服务地址`)
  }
  const raw = await response.text()
  let data
  try { data = raw ? JSON.parse(raw) : {} }
  catch { throw new Error(`接口 ${method} ${path} 响应不是 JSON（HTTP ${response.status}），请检查 /docker-api 代理`) }
  const message = [data?.msg, data?.message, data?.error].find(
    value => typeof value === 'string' && value.trim()
  ) || raw.slice(0, 160) || '原平台未返回详细错误原因'
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new Error(
        `原平台身份会话无效或无权限（HTTP ${response.status}，${method} ${path}）。` +
        '请确认已登录本项目并拥有原平台访问权限；/docker-api 只转发当前浏览器会话。'
      )
    }
    throw new Error(`原平台接口错误（HTTP ${response.status}，${method} ${path}）：${message}`)
  }
  if (data?.success === false || (typeof data?.code === 'number' && data.code >= 400)) {
    throw new Error(`原平台业务错误（${method} ${path}）：${message}`)
  }
  return data
}

function ids(projectId, gasReservoirId, predictionId) {
  return [apiId(projectId, '项目ID'), apiId(gasReservoirId, '气藏ID'), apiId(predictionId, '预测任务ID')]
}

export async function fetchPredictionDetail(projectId, gasReservoirId, predictionId) {
  const [p, g, id] = ids(projectId, gasReservoirId, predictionId)
  return request(p, `/projectanalysis/dynamicprediction/${p}/${g}/${id}`)
}

export async function fetchGroupChart(projectId, gasReservoirId, predictionId) {
  const [p, g, id] = ids(projectId, gasReservoirId, predictionId)
  return request(p, `/projectanalysis/dynamicprediction/result/wells/chart/${p}/${g}/${id}`)
}

export async function fetchWellChart(projectId, gasReservoirId, predictionId, wellName) {
  const [p, g, id] = ids(projectId, gasReservoirId, predictionId)
  if (!wellName || !String(wellName).trim()) throw new Error('请选择单井')
  const query = new URLSearchParams({ wellName: String(wellName) }).toString()
  return request(p, `/projectanalysis/dynamicprediction/result/well/chart/${p}/${g}/${id}?${query}`)
}

/**
 * 保留旧页面 ConstraintConditions.vue 依赖的命名导出。
 * 注意：SolutionGeneration.vue 不调用此函数，生成方案流程不请求节点树。
 * 只有旧页面明确调用 fetchPredictionList() 时才加载原平台任务目录。
 */
export async function fetchPredictionList(projectId, gasReservoirId) {
  const p = apiId(projectId, '项目ID')
  const g = apiId(gasReservoirId, '气藏ID')
  const payload = await request(p, `/projectanalysis/node/${p}/${g}/60`)
  const predictions = new Map()

  function visit(node) {
    if (!node || typeof node !== 'object') return
    if (Number(node.nodeType) === 79) {
      const id = resolvePredictionId(null, null, node.nodeId)
      if (id) predictions.set(id, { id, name: node.nodeTitle || `动态预测 ${id}` })
    }
    for (const child of Array.isArray(node.subNodes) ? node.subNodes : []) {
      visit(child)
    }
  }

  visit(payload?.node)
  return [...predictions.values()]
}

/** Fetch the project wells; the UI must not silently assume all wells are in a group. */
export async function fetchPlatformWells(projectId, gasReservoirId) {
  const p = apiId(projectId, 'projectId')
  const g = apiId(gasReservoirId, 'gasReservoirId')
  const payload = await request(p, `/projectanalysis/${p}/${g}/wells`)
  if (!Array.isArray(payload?.wells)) throw new Error('Original platform returned no wells array')
  return [...new Set(payload.wells
    .map(item => typeof item === 'string' ? item : item?.wellName)
    .filter(name => typeof name === 'string' && name.trim())
    .map(name => name.trim()))]
}

/** Exactly the double slash found in the uploaded HAR. Not a node-tree request. */
export async function fetchDefaultGroupPredictionName(projectId, gasReservoirId) {
  const p = apiId(projectId, 'projectId')
  const g = apiId(gasReservoirId, 'gasReservoirId')
  const payload = await request(p,
    `/projectanalysis/dynamicprediction//${p}/${g}/2/defaultname`)
  if (typeof payload?.predictionName !== 'string' || !payload.predictionName.trim()) {
    throw new Error('Original platform did not return predictionName')
  }
  return payload.predictionName.trim()
}

/**
 * POST create -> response.dynamicPredictionId -> GET detail -> POST Calc.
 * This is required by the HAR. The user NEVER enters an ID or visits node/.
 */
export async function createGroupPrediction(projectId, gasReservoirId, {
  wellNames, dynamicPredictionName, productivityEvaluationForm = 1
} = {}) {
  const p = apiId(projectId, 'projectId')
  const g = apiId(gasReservoirId, 'gasReservoirId')
  const wells = [...new Set((Array.isArray(wellNames) ? wellNames : [])
    .filter(item => typeof item === 'string')
    .map(item => item.trim()).filter(Boolean))]
  if (!wells.length) throw new Error('Please select at least one well to calculate')
  const name = String(dynamicPredictionName || '').trim()
  if (!name) throw new Error('Prediction name is required')
  const form = apiId(productivityEvaluationForm, 'productivityEvaluationForm')
  const payload = await request(p, '/projectanalysis/dynamicprediction', {
    method: 'POST',
    body: {
      gasReservoirId: g,
      projectId: p,
      wellNames: wells,
      dynamicPredictionType: 2,
      dynamicPredictionName: name,
      productivityEvaluationForm: form
    }
  })
  const id = resolvePredictionId(null, null, payload?.dynamicPredictionId)
  if (!id) throw new Error('Original platform create response has no dynamicPredictionId')
  return { id, name: String(payload.dynamicPredictionName || name), wellNames: wells }
}

// 原平台的 Calc 请求体只包含 reservoirInput 中的这些字段；不发送响应里的 id/edges。
const NUMERIC_FIELDS = Object.freeze([
  'viscosityMethod', 'deviationFactorMethod', 'modificationMethod',
  'nitrogen', 'carbonDioxide', 'hydrogenSulfide', 'specificGravity',
  'formationTemperature', 'originalFormationPressure', 'waterSaturation',
  'economicLimitGasProduction', 'waterCompressionCoefficient',
  'rockCompressionCoefficient', 'externalWellheadPressure',
  'productivityEvaluationForm', 'predictedTimeStepNumber',
  'predictedTimeStep', 'targetProduction', 'mbEquationType'
])

function numeric(value, label) {
  if (value === '' || value === null || value === undefined) throw new Error(`${label}不能为空`)
  const num = Number(value)
  if (!Number.isFinite(num)) throw new Error(`${label}不是有效数字`)
  return num
}

/**
 * 抓包证实 Calc 把日期时间传成“日期对应的 UTC 零点毫秒数”，
 * 而非直接 Date.parse(+08:00) 的时刻。例：2014-01-15 -> 1389744000000。
 */
function calcDate(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string') throw new Error('预测开始日期缺失')
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
  if (!match) throw new Error('预测开始日期格式错误')
  const y = +match[1], month = +match[2], day = +match[3]
  const time = Date.UTC(y, month - 1, day)
  const date = new Date(time)
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== month - 1 ||
      date.getUTCDate() !== day) throw new Error('预测开始日期无效')
  return time
}

function optionalOverride(...values) {
  return values.find(v => v !== null && v !== undefined && v !== '')
}

/** 基于原平台的完整原始输入，仅覆盖用户明确修改的两个约束参数。 */
export function buildReservoirCalcInput(source, constraintDraft = null, params = {}) {
  if (!source || typeof source !== 'object') throw new Error('缺少原平台 reservoirInput')
  const input = {}
  for (const field of NUMERIC_FIELDS) {
    if (source[field] !== null && source[field] !== undefined && source[field] !== '') {
      input[field] = numeric(source[field], field)
    }
  }
  if (source.gasType !== undefined && source.gasType !== null) input.gasType = String(source.gasType)
  input.predictionStartTime = calcDate(source.predictionStartTime)
  const draft = constraintDraft?.otherData || {}
  const target = optionalOverride(params.targetProduction, draft.targetProduction)
  const pressure = optionalOverride(params.exportPressure, draft.wellheadExportPressure,
    draft.externalWellheadPressure)
  if (target !== undefined) {
    input.targetProduction = numeric(target, '目标产量')
    if (input.targetProduction < 0) throw new Error('目标产量不能为负数')
  }
  if (pressure !== undefined) {
    input.externalWellheadPressure = numeric(pressure, '外输压力')
    if (input.externalWellheadPressure < 0) throw new Error('外输压力不能为负数')
  }
  return input
}

export async function calculatePrediction(projectId, gasReservoirId, predictionId, reservoirInput) {
  const [p, g, id] = ids(projectId, gasReservoirId, predictionId)
  if (!reservoirInput || typeof reservoirInput !== 'object') throw new Error('缺少计算输入')
  return request(p, '/projectanalysis/dynamicprediction/Calc', {
    method: 'POST',
    body: { gasReservoirId: g, projectId: p, dynamicPredictionId: id, reservoirInput }
  })
}

/** 返回适合 ECharts time 轴的 [timestamp, value|null] 点集。 */
export function parseForecastCharts(payload, { forecastStartDate, onlyForecast = false } = {}) {
  if (!Array.isArray(payload?.chartItems)) throw new Error('图表响应缺少 chartItems')
  const cutoff = onlyForecast && forecastStartDate ? calcDate(forecastStartDate) : null
  if (onlyForecast && forecastStartDate && !Number.isFinite(cutoff)) throw new Error('预测起始日期无效')
  const charts = Object.fromEntries(PREDICTION_METRICS.map(m => [m.key, []]))
  for (const item of payload.chartItems) {
    const key = item?.yAxisField
    if (!Object.prototype.hasOwnProperty.call(charts, key) || !Array.isArray(item.data)) continue
    const points = []
    for (const row of item.data) {
      if (!row || row.isDeleted) continue
      let time
      try {
        time = typeof row.xValue === 'number' ? row.xValue
          : (typeof row.xValue === 'string' && /^\d{4}-\d{2}-\d{2}/.test(row.xValue)
            ? calcDate(row.xValue) : Date.parse(row.xValue))
      } catch { continue } // 跳过格式异常的单个点，不让整张真实图失败
      if (!Number.isFinite(time) || (cutoff !== null && time < cutoff)) continue
      const y = row.yValue === null || row.yValue === undefined || row.yValue === ''
        ? null : Number(row.yValue)
      points.push([time, y === null || !Number.isFinite(y) ? null : y])
    }
    points.sort((a, b) => a[0] - b[0])
    charts[key] = points
  }
  return charts
}


export function lastValidValue(points) {
  if (!Array.isArray(points)) return null
  for (let i = points.length - 1; i >= 0; i--) {
    const v = points[i]?.[1]
    if (v !== null && v !== undefined && Number.isFinite(Number(v))) return Number(v)
  }
  return null
}

export function validateConstraintForm(form) {
  const other = form?.otherData || {}
  const gas = form?.gasProperties || {}
  const num = (label, value, { min = 0, positive = false, integer = false } = {}) => {
    if (value === undefined || value === null || String(value).trim() === '') throw new Error(`请填写${label}`)
    const n = Number(value)
    if (!Number.isFinite(n) || n < min || (positive && n <= 0) || (integer && !Number.isInteger(n))) {
      throw new Error(`${label}数值不合法`)
    }
    return n
  }
  num('天然气比重', gas.dless, { positive: true })
  const a = num('H₂S含量', gas.h2sContent), b = num('CO₂含量', gas.co2Content), c = num('N₂含量', gas.n2Content)
  if (a + b + c > 100) throw new Error('非烃气体摩尔百分含量之和不能超过100%')
  num('井组目标产量', other.targetProduction, { positive: true })
  num('井口外输压力', other.wellheadExportPressure)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(other.forecastStartDate || '')) throw new Error('预测开始日期格式错误')
}

/**
 * 原平台“井组动态预测分析图”的显示配置：
 * HAR 在读取井组图表后请求 ProductionPerformance 模板与图表个性化设置。
 * 配置失败不影响 Calc 结果，回退到 HAR 中四条曲线的默认名称/颜色。
 */
export async function fetchGroupChartAppearance(projectId, predictionId) {
  const p = apiId(projectId, '项目ID')
  const id = apiId(predictionId, '预测任务ID')
  const [templatesResult, savedResult] = await Promise.allSettled([
    request(p, '/common/charttemplates/ProductionPerformance'),
    request(p, `/common/chartconfiguration/ProductionPerformance/${id}`)
  ])
  const templates = templatesResult.status === 'fulfilled' ? templatesResult.value?.templates : []
  const active = Array.isArray(templates)
    ? templates.find(item => item?.isActive) || templates[0] : null
  const saved = savedResult.status === 'fulfilled' ? savedResult.value?.chartConfiguration : null
  const raw = saved?.rawConfig ?? saved?.config ?? active?.rawConfig ?? null
  if (!raw) return null
  try {
    const data = typeof raw === 'string' ? JSON.parse(raw) : raw
    return data && typeof data === 'object' && !Array.isArray(data) ? data : null
  } catch {
    return null
  }
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
export function buildGroupOverviewOption(charts, chartPayload, appearance) {
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
      data: charts[metric.key]
    })
  }
  const xAxisName = appearance?.xAxis?.name || '日期'
  const mainTitle = appearance?.name || '动态预测分析图'
  return {
    backgroundColor: '#ffffff',
    animation: false,
    useUTC: true,
    title: { text: mainTitle, left: 'center', top: 12,
      textStyle: { color: '#303743', fontFamily: 'Microsoft YaHei, sans-serif', fontSize: 16, fontWeight: 600 } },
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
          const field = items[item.seriesIndex]
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
      { type: 'inside', xAxisIndex: 0, filterMode: 'none' },
      { type: 'slider', xAxisIndex: 0, filterMode: 'none', height: 17, bottom: 30, borderColor: '#dfe4ed', brushSelect: false }
    ]
  }
}
