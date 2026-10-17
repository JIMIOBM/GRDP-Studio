/**
 * 库级/井组动态预测前端接口。接口路径和字段取自用户提供的 HAR。
 * 不访问会报 500 的 /node 任务树接口；直接按已知任务 ID 读取、计算、出图。
 */
const BASE = '/api/projectanalysis'
// HAR 中请求携带 Process-Env=prod 和 x-project-id。
// 部署到非 prod 环境时可通过 Vite 的 VITE_PROCESS_ENV 覆盖。
const PROCESS_ENV = import.meta.env?.VITE_PROCESS_ENV || 'prod'

export const PREDICTION_METRICS = Object.freeze([
  { key: 'gasProduction', name: '气产量', unit: '10⁴m³/d' },
  { key: 'cumulativeGasProduction', name: '累产气量', unit: '10⁸m³' },
  { key: 'formationPressure', name: '地层压力', unit: 'MPa' },
  { key: 'reservoirRecoveryDegree', name: '采出程度', unit: '%' }
])

function positiveId(value, label) {
  const id = Number(value)
  if (!Number.isSafeInteger(id) || id <= 0) {
    throw new Error(`${label}无效，请选择已有动态预测任务`)
  }
  return id
}

async function http(path, { method = 'GET', body, signal, projectId } = {}) {
  const headers = { 'Accept': 'application/json', 'Process-Env': PROCESS_ENV }
  if (projectId !== undefined) headers['x-project-id'] = String(projectId)
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const options = { method, credentials: 'same-origin', signal, headers }
  if (body !== undefined) options.body = JSON.stringify(body)
  const res = await fetch(`${BASE}${path}`, options)
  const raw = await res.text()
  let json
  if (raw) {
    try { json = JSON.parse(raw) } catch {
      throw new Error(`接口返回非 JSON 数据（HTTP ${res.status}）`)
    }
  }
  if (!res.ok) {
    const detail = json?.message || json?.msg || json?.error || res.statusText
    throw new Error(`${method} ${path} 返回 HTTP ${res.status}：${String(detail || '服务器错误')}`)
  }
  if (json?.success === false ||
      (typeof json?.code === 'number' && ![0, 200].includes(json.code))) {
    throw new Error(String(json.message || json.msg || '业务接口处理失败'))
  }
  return json && typeof json === 'object' && !Array.isArray(json) &&
    json.data != null && !('chartItems' in json) && !('node' in json) &&
    !('reservoirInput' in json)
    ? json.data : json
}

/**
 * 后端 Calc 必须有真实 dynamicPredictionId，不能从 storageId 猜测，也不允许乱造。
 * 优先使用父组件传入的 id，随后使用前端内存已确认 id / 环境变量。
 * 6/1/194 是用户 HAR 中成功读取、计算、出图的临时对接记录，严格限定此范围。
 * 如果该记录已在服务端删除，读取详情将明确失败，不自动创建新任务。
 */
export function resolvePredictionId(projectId, gasReservoirId, suppliedId, rememberedId) {
  const p = positiveId(projectId, '项目ID')
  const g = positiveId(gasReservoirId, '气藏ID')
  const configured = import.meta.env?.VITE_DYNAMIC_PREDICTION_ID
  for (const value of [suppliedId, rememberedId, configured]) {
    if (value === '' || value === null || value === undefined) continue
    const n = Number(value)
    if (Number.isSafeInteger(n) && n > 0) return n
    throw new Error('传入的动态预测任务ID不是有效正整数')
  }
  if (p === 6 && g === 1) return 194 // 用户 HAR 验证的当前范围；其它范围禁止套用
  throw new Error('缺少动态预测任务ID：请父组件传入 dynamicPredictionId，或在 .env 配置 VITE_DYNAMIC_PREDICTION_ID；本版本不创建数据库任务')
}

export function fetchPredictionDetail(projectId, gasReservoirId, predictionId, options) {
  return http(
    `/dynamicprediction/${positiveId(projectId, '项目ID')}/${positiveId(gasReservoirId, '气藏ID')}/${positiveId(predictionId, '预测任务ID')}`,
    { ...options, projectId: positiveId(projectId, '项目ID') }
  )
}

export function calculatePrediction(projectId, gasReservoirId, predictionId, reservoirInput, options) {
  if (!reservoirInput || typeof reservoirInput !== 'object') {
    throw new Error('缺少动态预测计算参数')
  }
  return http('/dynamicprediction/Calc', {
    ...options,
    projectId: positiveId(projectId, '项目ID'),
    method: 'POST',
    body: {
      projectId: positiveId(projectId, '项目ID'),
      gasReservoirId: positiveId(gasReservoirId, '气藏ID'),
      dynamicPredictionId: positiveId(predictionId, '预测任务ID'),
      reservoirInput
    }
  })
}

export function fetchGroupChart(projectId, gasReservoirId, predictionId, options) {
  return http(
    `/dynamicprediction/result/wells/chart/${positiveId(projectId, '项目ID')}/${positiveId(gasReservoirId, '气藏ID')}/${positiveId(predictionId, '预测任务ID')}`,
    { ...options, projectId: positiveId(projectId, '项目ID') }
  )
}

export function fetchWellChart(projectId, gasReservoirId, predictionId, wellName, options) {
  if (!String(wellName || '').trim()) throw new Error('缺少井名')
  return http(
    `/dynamicprediction/result/well/chart/${positiveId(projectId, '项目ID')}/${positiveId(gasReservoirId, '气藏ID')}/${positiveId(predictionId, '预测任务ID')}?wellName=${encodeURIComponent(wellName)}`,
    { ...options, projectId: positiveId(projectId, '项目ID') }
  )
}

function dateOnly(value) {
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)) {
    return value.slice(0, 10)
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const dt = new Date(value)
    return Number.isNaN(dt.valueOf()) ? null : dt.toISOString().slice(0, 10)
  }
  return null
}

/**
 * HAR 的每个 chartItem 包含 { name, yAxisField, data:[{id,xValue,yValue,isDeleted}] }。
 * id=0 为历史段，id>0 为预测段；如果服务端未提供区分，则以预测开始日筛选。
 * 仅用确有数值的点，不把 null 变成 0，不填充/编造数据。
 */
export function parseForecastCharts(payload, { forecastStartDate = '', onlyForecast = true } = {}) {
  const items = payload?.chartItems
  if (!Array.isArray(items)) throw new Error('接口未返回 chartItems，无法绘制预测曲线')
  const startDate = dateOnly(forecastStartDate)
  const result = {}
  for (const metric of PREDICTION_METRICS) {
    const item = items.find(entry =>
      entry?.yAxisField === metric.key || entry?.name === metric.name
    )
    const raw = Array.isArray(item?.data) ? item.data : []
    const marked = raw.some(p => Number(p?.id) > 0)
    const points = []
    for (const p of raw) {
      if (!p || p.isDeleted === true) continue
      if (onlyForecast && marked && !(Number(p.id) > 0)) continue
      const date = dateOnly(p.xValue)
      if (!date || (onlyForecast && !marked && startDate && date < startDate)) continue
      const value = p.yValue === null || p.yValue === '' || p.yValue === undefined
        ? null : Number(p.yValue)
      points.push([date, Number.isFinite(value) ? value : null])
    }
    points.sort((a, b) => a[0].localeCompare(b[0]))
    result[metric.key] = points
  }
  return result
}

export function lastValidValue(points) {
  if (!Array.isArray(points)) return null
  for (let i = points.length - 1; i >= 0; i--) {
    if (Number.isFinite(points[i]?.[1])) return points[i][1]
  }
  return null
}

/**
 * 后端编码只确认了本次 HAR 中出现的：
 * 0 = 第一项计算方法；1 = 压力形式、定容气藏。
 * 其他选项没有可验证的编码时主动报错，避免错传算法参数。
 */
export function buildReservoirCalcInput(serverInput, form, directParams) {
  const r = serverInput || {}
  const gas = form?.gasProperties || {}
  const method = form?.calcMethod || {}
  const other = form?.otherData || {}
  const has = value => value !== '' && value !== undefined && value !== null
  const pick = (value, fallback) => has(value) ? value : fallback
  const requiredNumber = (label, value, { min = -Infinity, max = Infinity, integer = false } = {}) => {
    if (!has(value)) throw new Error(`请填写${label}`)
    const n = Number(value)
    if (!Number.isFinite(n) || n < min || n > max || (integer && !Number.isInteger(n))) {
      throw new Error(`${label}数值不合法`)
    }
    return n
  }
  const onlyVerifiedEnum = (label, selected, defaultLabel, code, fallback) => {
    if (!has(selected)) return requiredNumber(label, fallback, { min: 0, integer: true })
    if (selected !== defaultLabel) {
      throw new Error(`${label}所选算法的接口编码尚未在 HAR 中核实，请先使用“${defaultLabel}”`)
    }
    return code
  }
  const productionForm = pick(other.productionEquationType, undefined)
  const materialBalance = pick(other.materialBalanceType, undefined)
  const startDate = pick(other.forecastStartDate, r.predictionStartTime)
  const start = dateOnly(startDate)
  if (!start || Number.isNaN(Date.parse(start))) throw new Error('预测开始日期不合法')
  const input = {
    viscosityMethod: onlyVerifiedEnum(
      '天然气粘度方法', method.viscosityMethod, 'Lee-Gonzalez-Eakin 方法', 0, r.viscosityMethod
    ),
    deviationFactorMethod: onlyVerifiedEnum(
      '偏差系数方法', method.deviationMethod, 'Dranchuk-Abu-Kassem 方法', 0, r.deviationFactorMethod
    ),
    modificationMethod: onlyVerifiedEnum(
      '非烃气体修正方法', method.correctionMethod, 'Wichert-Aziz 修正方法', 0, r.modificationMethod
    ),
    nitrogen: requiredNumber('N₂含量', pick(gas.n2Content, r.nitrogen), { min: 0, max: 100 }),
    carbonDioxide: requiredNumber('CO₂含量', pick(gas.co2Content, r.carbonDioxide), { min: 0, max: 100 }),
    hydrogenSulfide: requiredNumber('H₂S含量', pick(gas.h2sContent, r.hydrogenSulfide), { min: 0, max: 100 }),
    specificGravity: requiredNumber('天然气比重', pick(gas.dless, r.specificGravity), { min: 0.00001 }),
    gasType: String(pick(gas.gasType, r.gasType) || ''),
    formationTemperature: requiredNumber('地层温度', pick(other.reservoirTemperature, r.formationTemperature)),
    originalFormationPressure: requiredNumber('原始地层压力', pick(other.initialPressure, r.originalFormationPressure), { min: 0 }),
    waterSaturation: requiredNumber('含水饱和度', pick(other.waterSaturation, r.waterSaturation), { min: 0, max: 100 }),
    economicLimitGasProduction: requiredNumber('单井废弃产量', pick(other.abandonmentRate, r.economicLimitGasProduction), { min: 0 }),
    waterCompressionCoefficient: requiredNumber('水压缩系数', pick(other.waterCompressionCoefficient, r.waterCompressionCoefficient), { min: 0 }),
    rockCompressionCoefficient: requiredNumber('岩石压缩系数', pick(other.rockCompressionCoefficient, r.rockCompressionCoefficient), { min: 0 }),
    externalWellheadPressure: requiredNumber('外输压力',
      pick(directParams?.exportPressure, pick(other.wellheadExportPressure, r.externalWellheadPressure)), { min: 0 }),
    productivityEvaluationForm: productionForm === undefined
      ? requiredNumber('产能方程形式', r.productivityEvaluationForm, { min: 0, integer: true })
      : onlyVerifiedEnum('产能方程形式', productionForm, '压力形式', 1, r.productivityEvaluationForm),
    predictedTimeStepNumber: requiredNumber('预测时间步数',
      pick(other.forecastTotalDays, r.predictedTimeStepNumber), { min: 1, integer: true }),
    predictedTimeStep: requiredNumber('预测时间步长',
      pick(other.forecastTimeStep, r.predictedTimeStep), { min: 1, integer: true }),
    predictionStartTime: Date.parse(start),
    targetProduction: requiredNumber('目标产量',
      pick(directParams?.targetProduction, pick(other.targetProduction, r.targetProduction)), { min: 0.000001 }),
    mbEquationType: materialBalance === undefined
      ? requiredNumber('物质平衡类型', r.mbEquationType, { min: 0, integer: true })
      : onlyVerifiedEnum('物质平衡类型', materialBalance, '定容气藏', 1, r.mbEquationType)
  }
  if (!input.gasType) throw new Error('请填写天然气类型')
  if (input.nitrogen + input.carbonDioxide + input.hydrogenSulfide > 100) {
    throw new Error('非烃气体摩尔百分含量之和不能超过100%')
  }
  return input
}

/** 约束条件页只负责验证原始参数并传递，不虚构不存在的四项约束计算结果。 */
export function validateOriginalConstraintForm(form) {
  // 利用真实 Calc 参数约束进行同一套基本校验；未向界面增加任何输入。
  const other = form?.otherData || {}
  const gas = form?.gasProperties || {}
  const numeric = (name, value, { min = 0, strictlyPositive = false, integer = false } = {}) => {
    if (value === undefined || value === null || String(value).trim() === '') throw new Error(`请填写${name}`)
    const n = Number(value)
    if (!Number.isFinite(n) || n < min || (strictlyPositive && n === 0) || (integer && !Number.isInteger(n))) {
      throw new Error(`${name}数值不合法`)
    }
    return n
  }
  numeric('天然气比重', gas.dless, { strictlyPositive: true })
  const a = numeric('H₂S含量', gas.h2sContent)
  const b = numeric('CO₂含量', gas.co2Content)
  const c = numeric('N₂含量', gas.n2Content)
  if (a + b + c > 100) throw new Error('非烃气体摩尔百分含量之和不能超过100%')
  numeric('原始地层压力', other.initialPressure, { strictlyPositive: true })
  numeric('地层温度', other.reservoirTemperature, { min: -273.15 })
  numeric('井组目标产量', other.targetProduction, { strictlyPositive: true })
  numeric('井口外输压力', other.wellheadExportPressure)
  numeric('单井废弃产量', other.abandonmentRate)
  numeric('预测时间步长', other.forecastTimeStep, { strictlyPositive: true, integer: true })
  numeric('预测时间步数', other.forecastTotalDays, { strictlyPositive: true, integer: true })
  if (!/^\d{4}-\d{2}-\d{2}$/.test(other.forecastStartDate || '') ||
      Number.isNaN(Date.parse(other.forecastStartDate))) {
    throw new Error('预测开始时间不合法')
  }
  return true
}
