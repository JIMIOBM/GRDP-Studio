/**
 * wellGroupPrediction.js（纯函数层）
 * 库级「协同预测-一体化耦合优化」调用的原平台「动态预测-井组动态预测」请求组装与响应解析。
 * 网络调用见 src/api/wellGroupPrediction.js；本文件不依赖 axios，可直接用 node:test 覆盖。
 *
 * 链路依据（原平台 9920 前端与后端实证）：
 *  - 创建任务：POST /projectanalysis/dynamicprediction，dynamicPredictionType=2（井组），
 *    原平台新建弹框要求至少 2 口井；
 *  - 启动预测：POST /projectanalysis/dynamicprediction/Calc，
 *    请求体 { gasReservoirId, projectId, dynamicPredictionId, reservoirInput }；
 *  - 绘图：GET /projectanalysis/dynamicprediction/result/wells/chart/{p}/{g}/{id}
 *    （井组为 wells 且不传查询参数；单井为 well 且附 wellName），
 *    响应 chartItems 与单井一致：{ yAxisField|name, data: [{ xValue, yValue, isDeleted }] }。
 */

// 与 solutionGeneration.js 同款极简工具；本文件不引 axios，单独保留以免测试环境解析 @/api/docker。
const isObject = value => Boolean(value && typeof value === 'object' && !Array.isArray(value))
export function optionalNumber(value) {
  if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
export function unwrapResponse(response) {
  let value = response
  for (let i = 0; i < 4; i += 1) {
    if (!isObject(value) || !Object.prototype.hasOwnProperty.call(value, 'data')) break
    value = value.data
  }
  return value
}

function checkId(value, name) {
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number < 0) throw new Error(`${name}必须是非负整数`)
  return number
}

/** 井组任务的 reservoirInput 字段清单：与原平台参数页提交的字段一致，未在面板展示的字段原样带回。 */
export const WELL_GROUP_RESERVOIR_FIELDS = [
  'viscosityMethod', 'deviationFactorMethod', 'modificationMethod', 'nitrogen', 'carbonDioxide',
  'hydrogenSulfide', 'specificGravity', 'gasType', 'formationTemperature', 'originalFormationPressure',
  'waterSaturation', 'economicLimitGasProduction', 'waterCompressionCoefficient',
  'rockCompressionCoefficient', 'externalWellheadPressure', 'productivityEvaluationForm',
  'predictedTimeStepNumber', 'predictedTimeStep', 'predictionStartTime', 'targetProduction',
  'mbEquationType'
]

/** 原平台产能方程形式编码（新建井组弹框的下拉值）。 */
export const PRODUCTIVITY_EVALUATION_FORMS = [
  { value: 1, label: '压力形式' },
  { value: 2, label: '压力平方形式' },
  { value: 3, label: '拟压力形式' }
]

/**
 * 预测开始时间在界面与请求之间的转换。
 * 原平台参数页用 new Date('YYYY-MM-DD') 生成时间戳，即显示日期的 UTC 零点毫秒值。
 */
export function dateTextToPredictionStartTime(dateText) {
  const date = String(dateText || '').trim()
  if (!date) return ''
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('预测开始日期格式不正确')
  const milliseconds = Date.parse(`${date}T00:00:00.000Z`)
  if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 10) !== date) {
    throw new Error('预测开始日期无效')
  }
  return milliseconds
}

export function predictionStartTimeToDateText(value) {
  if (value === null || value === undefined || value === '') return ''
  const text = String(value)
  if (/^\d{4}-\d{2}-\d{2}$/.test(text)) return text
  const milliseconds = Number(value)
  if (Number.isFinite(milliseconds) && milliseconds > 0) {
    return new Date(milliseconds).toISOString().slice(0, 10)
  }
  const parsed = Date.parse(text)
  return Number.isFinite(parsed) ? new Date(parsed).toISOString().slice(0, 10) : ''
}

/**
 * 校验任务详情并组装 Calc 请求。
 * 详情必须属于当前任务（防止串任务），未在面板展示的字段原样带回，面板编辑值覆盖详情值。
 */
export function buildWellGroupCalcRequest(detail, scope, panelInput) {
  const payload = unwrapResponse(detail)
  if (!isObject(payload) || !isObject(payload.reservoirInput)) {
    throw new Error('原平台井组任务详情缺少 reservoirInput')
  }
  const reservoirTaskId = payload.reservoirInput.DynamicPredictionId ?? payload.reservoirInput.dynamicPredictionId
  if (String(reservoirTaskId) !== String(scope.dynamicPredictionId)) {
    throw new Error(`任务详情所属预测编号 ${reservoirTaskId ?? '空'} 与请求 ${scope.dynamicPredictionId} 不一致`)
  }
  const reservoirInput = {}
  for (const field of WELL_GROUP_RESERVOIR_FIELDS) {
    const value = payload.reservoirInput[field]
    if (value !== undefined) reservoirInput[field] = value
  }
  // 面板空值（未编辑）不覆盖详情值；仅显式编辑过的值参与覆盖。
  for (const [field, value] of Object.entries(panelInput || {})) {
    if (value === undefined || value === null || value === '') continue
    reservoirInput[field] = value
  }
  if (reservoirInput.predictionStartTime !== undefined && reservoirInput.predictionStartTime !== '') {
    const text = predictionStartTimeToDateText(reservoirInput.predictionStartTime)
    reservoirInput.predictionStartTime = dateTextToPredictionStartTime(text)
  }
  return {
    projectId: checkId(scope.projectId, 'projectId'),
    gasReservoirId: checkId(scope.gasReservoirId, 'gasReservoirId'),
    dynamicPredictionId: checkId(scope.dynamicPredictionId, 'dynamicPredictionId'),
    reservoirInput
  }
}

/**
 * 解析井组绘图响应。气产量与累产气量为必读序列；地层压力、采出程度（原平台井组预测的
 * 4 个输出指标，nodeType 72-75）存在时一并读取。0 是有意义的停产值必须保留，
 * null 表示数据缺失（不写入该点）。
 */
export function normalizeWellGroupChart(response, query = {}) {
  const payload = unwrapResponse(response)
  if (!isObject(payload) || !Array.isArray(payload.chartItems)) {
    throw new Error('井组绘图接口返回结构不正确：缺少 chartItems')
  }
  const spec = [
    { key: 'gas', field: 'gasProduction', name: '气产量', required: true },
    { key: 'cumulative', field: 'cumulativeGasProduction', name: '累产气量', required: true },
    { key: 'formation', field: 'formationPressure', name: '地层压力', required: false },
    { key: 'recovery', field: 'reservoirRecoveryDegree', name: '采出程度', required: false }
  ]
  const byDate = new Map()
  for (const { key, field, name, required } of spec) {
    const item = payload.chartItems.find(entry => entry?.yAxisField === field) ??
      payload.chartItems.find(entry => entry?.name === name)
    if (!item || !Array.isArray(item.data)) {
      if (required) throw new Error(`原平台井组绘图数据缺少“${name}”序列`)
      continue
    }
    let valid = 0
    for (const entry of item.data) {
      if (!isObject(entry) || entry.isDeleted === true) continue
      const date = String(entry.xValue ?? '').slice(0, 10)
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue
      const milliseconds = Date.parse(`${date}T00:00:00.000Z`)
      if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 10) !== date) continue
      const number = optionalNumber(entry.yValue)
      if (number === null) continue
      if (!byDate.has(date)) byDate.set(date, { date, gas: null, cumulative: null, formation: null, recovery: null })
      byDate.get(date)[key] = number
      valid += 1
    }
    if (required && !valid) throw new Error(`“${name}”序列没有有效时间点`)
  }
  const points = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date))
  if (!points.some(point => point.gas !== null)) throw new Error('井组绘图没有有效气产量数据')
  if (!points.some(point => point.cumulative !== null)) throw new Error('井组绘图没有有效累产气量数据')
  return {
    id: query.dynamicPredictionId ?? null,
    source: '/result/wells/chart',
    points,
    total: points.length
  }
}

export function fingerprintWellGroupPoints(points) {
  return JSON.stringify(points.map(point => [point.date, point.gas, point.cumulative]))
}

/**
 * 组装"预测结果留存"请求体（本项目后端 POST /prediction-results）。
 * 预测完成（确认新曲线）后调用；点数或日期缺失时返回 null，由调用方跳过留存。
 */
export function buildPredictionResultPayload({ scope, task, mode, wellNames, points, request }) {
  const list = Array.isArray(points) ? points : []
  const dated = list.filter(point => /^\d{4}-\d{2}-\d{2}$/.test(String(point?.date ?? '')))
  if (!list.length || dated.length !== list.length) return null
  const predictionId = Number(task?.dynamicPredictionId)
  if (!Number.isSafeInteger(predictionId) || predictionId <= 0) return null
  const projectId = Number(scope?.projectId)
  const gasReservoirId = Number(scope?.gasReservoirId)
  if (!Number.isSafeInteger(projectId) || projectId <= 0 ||
      !Number.isSafeInteger(gasReservoirId) || gasReservoirId <= 0) return null
  const storageId = Number(scope?.storageId)
  return {
    projectId,
    gasReservoirId,
    storageId: Number.isSafeInteger(storageId) && storageId > 0 ? storageId : null,
    predictionId,
    predictionName: String(task?.name || '').trim() || `预测任务${predictionId}`,
    mode: String(mode || '').trim() || '单目标',
    wellNames: (Array.isArray(wellNames) ? wellNames : []).map(String),
    startDate: String(dated[0].date),
    endDate: String(dated[dated.length - 1].date),
    points: list.map(point => ({
      date: String(point.date),
      gas: point.gas ?? null,
      cumulative: point.cumulative ?? null,
      formation: point.formation ?? null,
      recovery: point.recovery ?? null
    })),
    request: request ?? null
  }
}

/**
 * 目标量判定：计算量 = 预测期结束点累计气产量 − 起始点累计气产量（井组数据自预测开始时间起，
 * 首尾即预测期），与用户输入的目标需求量比较，累计差值是否满足需求。
 * 任一端点累计值缺失时不判定；返回 null 表示数据或输入不足。
 */
export function evaluateTargetDemand(points, targetValue) {
  const list = Array.isArray(points) ? points : []
  const withCumulative = list.filter(point => optionalNumber(point?.cumulative) !== null)
  if (withCumulative.length < 2) return null
  const first = withCumulative[0]
  const last = withCumulative[withCumulative.length - 1]
  const computedAmount = last.cumulative - first.cumulative
  const target = optionalNumber(targetValue)
  // 首尾累计相减有浮点误差（如 23.4−8=15.399…9），与输入恰好相等时不能用严格 >= 判负。
  const tolerance = Math.max(1e-9, Math.abs(computedAmount) * 1e-12)
  return {
    computedAmount,
    startDate: first.date ?? null,
    endDate: last.date ?? null,
    target,
    difference: target === null ? null : computedAmount - target,
    // null = 尚未输入目标需求量，不做判定。
    satisfied: target === null ? null : computedAmount + tolerance >= target
  }
}

/**
 * 从动态预测目录树（GET /projectanalysis/node/{p}/{g}/60）中提取井组动态预测任务节点。
 * 与原平台前端一致：井组任务的叶子节点 nodeType=79（NodeType_DynamicPredictionWellsInput），
 * nodeId 即 dynamicPredictionId，nodeTitle 即任务名称。
 * 只接受明确属于井组的节点；返回按 dynamicPredictionId 倒序（最新在前）。
 */
export function extractWellGroupTaskNodes(response) {
  const payload = unwrapResponse(response)
  const root = payload?.node || payload
  const tasks = new Map()
  const visited = new Set()
  function visit(node) {
    if (!node || typeof node !== 'object' || visited.has(node)) return
    visited.add(node)
    if (Array.isArray(node)) { node.forEach(visit); return }
    if (Number(node.nodeType) === 79 &&
        Number.isSafeInteger(Number(node.nodeId)) && Number(node.nodeId) > 0 &&
        typeof node.nodeTitle === 'string' && node.nodeTitle.trim()) {
      const id = Number(node.nodeId)
      const name = node.nodeTitle.trim()
      const existing = tasks.get(id)
      if (existing && existing.name !== name) {
        throw new Error(`井组任务 ${id} 在目录树中出现不同名称`)
      }
      tasks.set(id, { dynamicPredictionId: id, name })
    }
    if (Array.isArray(node.subNodes)) node.subNodes.forEach(visit)
    // 部分版本用 children 承载子节点。
    if (Array.isArray(node.children)) node.children.forEach(visit)
  }
  visit(root)
  return [...tasks.values()].sort((a, b) => b.dynamicPredictionId - a.dynamicPredictionId)
}
