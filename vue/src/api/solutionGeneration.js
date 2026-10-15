import client from '@/api/docker'

function ensureApiPayload(response, operation) {
  const contentType = String(response?.headers?.['content-type'] || '').toLowerCase()
  const body = response?.data
  if (contentType.includes('text/html') ||
      (typeof body === 'string' && /<\s*(?:!doctype\s+html|html\b)/i.test(body))) {
    throw new Error(`${operation} 返回的是HTML而不是JSON。请检查 docker.js 的 /docker-api 代理以及原平台会话。`)
  }
  if (isObject(body) && body.success === false) {
    throw new Error(`${operation} 失败：${body.message || body.msg || '原平台返回失败状态'}`)
  }
  return body
}
function checkId(value, name) {
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number < 0) throw new Error(`${name}必须是非负整数`)
  return number
}

export const dynamicPredictionApi = {
  // HAR 实证：点击“单井动态预测”打开新表单时先获取默认名称。
  // 服务端真实路径在 dynamicprediction 后有两个 /，此处按抓包原样保留。
  getDefaultPredictionName: (projectId, gasReservoirId, wellName) =>
    client.get(
      `/projectanalysis/dynamicprediction//${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/3/defaultname`,
      { params: { wellName }, silentError: true }
    ).then(response => ensureApiPayload(response, '获取动态预测默认名称')),

  // HAR 实证：POST 创建单井任务后直接返回 dynamicPredictionId / dynamicPredictionName。
  createPredictionTask: (scope, name, productivityEvaluationForm = 1) =>
    client.post('/projectanalysis/dynamicprediction', {
      gasReservoirId: checkId(scope.gasReservoirId, 'gasReservoirId'),
      projectId: checkId(scope.projectId, 'projectId'),
      wellNames: [String(scope.wellName)],
      dynamicPredictionType: 3,
      dynamicPredictionName: String(name),
      productivityEvaluationForm: positiveInteger(productivityEvaluationForm, 'productivityEvaluationForm')
    }, { silentError: true }).then(response => ensureApiPayload(response, '创建单井动态预测任务')),

  getPredictionTaskTree: (projectId, gasReservoirId) =>
    client.get(`/projectanalysis/node/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/60`,
      { silentError: true }).then(response => ensureApiPayload(response, '查询动态预测任务树')),

  // 新建和已有任务均通过这条详情接口获取各井本身的 reservoirInput / wellInputs / 井筒参数。
  getPredictionDetail: (projectId, gasReservoirId, dynamicPredictionId) =>
    client.get(`/projectanalysis/dynamicprediction/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/${checkId(dynamicPredictionId, 'dynamicPredictionId')}`,
      { silentError: true }).then(response => ensureApiPayload(response, '获取单井预测参数详情')),

  // Calc 200/空body表示请求已返回；绘图直接使用当前Calc请求里的动态预测任务ID。
  // 不要求Calc返回井结果编号，也不凭HTTP 200认定异步落库已完成。
  calculate: (wellName, payload) => {
    // chartWellName 是 /chart 查询专用的前端元数据，不属于原平台 Calc 抓包请求体。
    // Calc 使用当前井所有已映射的原生输入，不要求响应返回任何绘图结果编号。
    const calcPayload = cloneJson(payload)
    // 任务展示元数据只供前端选择 /chart；不能混进原平台原生 Calc 请求体。
    for (const field of ['chartWellName', 'predictionName', 'dynamicPredictionName',
      'taskName', 'physicalWellName', 'predictionTask', 'wellProfile']) {
      delete calcPayload[field]
    }
    return client.post(
      `/projectanalysis/dynamicprediction/${encodeURIComponent(wellName)}/Calc`, calcPayload,
      {
        timeout: 600000,
        silentError: true,
        // 与原平台 HAR 的 Calc 请求保持一致；仅补项目上下文头，不改计算数据。
        headers: { 'X-Project-Id': String(checkId(calcPayload.projectId, 'projectId')) }
      }
    ).then(response => ({
    data: ensureApiPayload(response, 'Calc'),
    headers: response.headers || {},
    status: response.status
    }))
  },

  // 已核实的原平台绘图接口：
  // GET /result/well/chart/6/1/20?wellName=X-1单井动态预测 1
  // GET /result/well/chart/6/1/24?wellName=X-1单井动态预测 2
  // 路径依次是项目ID / 气藏ID / 动态预测任务ID；查询wellName是任务名称，不是物理井名。
  getPredictionChart: (projectId, gasReservoirId, dynamicPredictionId, chartWellName) => {
    const name = String(chartWellName ?? '').trim()
    if (!name) throw new Error('绘图接口缺少当前预测任务名称（chartWellName）')
    return client.get(
      `/projectanalysis/dynamicprediction/result/well/chart/${checkId(projectId, 'projectId')}/${checkId(gasReservoirId, 'gasReservoirId')}/${checkId(dynamicPredictionId, 'dynamicPredictionId')}`,
      { params: { wellName: name }, timeout: 60000, silentError: true }
    ).then(response => ensureApiPayload(response, '原平台绘图数据查询'))
  },

  // 保留旧 /data 接口供其他模块兼容；方案生成已不再调用它。
  // 旧版接口仅保留兼容；本页不再用 /data 中的历史结果ID作为任务预测结果。
  getWellResults: (projectId, resultPredictionId, wellName, resultKind = 0) => client.get(
    `/projectanalysis/dynamicprediction/result/well/data/${checkId(projectId, 'projectId')}/${checkId(resultKind, 'resultKind')}/${checkId(resultPredictionId, 'resultPredictionId')}`,
    { params: { wellName, page: 1, size: -1 } }
  ).then(response => ensureApiPayload(response, '井结果查询'))
}

// 所有井共用同一套请求组装。不得将 X-1/X-2 的抓包模板作为其他井的兜底。
// 原平台任务创建、任务详情及绘图接口已通过 HAR 核实；仍只处理明确归属当前井的元数据。

export const isObject = value => Boolean(value && typeof value === 'object' && !Array.isArray(value))
export const cloneJson = value => JSON.parse(JSON.stringify(value))

export function numeric(value, label = '数值', { min = -Infinity, allowZero = true } = {}) {
  if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) {
    throw new Error(`${label}不能为空`)
  }
  const number = Number(value)
  if (!Number.isFinite(number) || number < min || (!allowZero && number === 0)) {
    throw new Error(`${label}不是有效值`)
  }
  return number
}
export function optionalNumber(value) {
  if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}
export function positiveInteger(value, label) {
  const number = numeric(value, label, { min: 1 })
  if (!Number.isSafeInteger(number)) throw new Error(`${label}必须是正整数`)
  return number
}
export function sameScope(item, scope) {
  if (!isObject(item)) return false
  return String(item.projectId) === String(scope.projectId) &&
    String(item.gasReservoirId) === String(scope.gasReservoirId) &&
    String(item.wellName) === String(scope.wellName)
}
export function scopeKey(scope) {
  return [scope.projectId, scope.gasReservoirId, encodeURIComponent(scope.wellName || '')].join(':')
}
/**
 * 极简方案页的约束选项适配层。数据库接通后把记录传入 records 即可；
 * 仅接受明确属于当前项目、气藏和物理井的记录，不跨井补值。
 * records: [{id,name,projectId,gasReservoirId,wellName,inputs}]
 * 或 records: [{id,name,snapshot:{projectId,gasReservoirId,wellName,inputs}}]
 */
export function listWellConstraintOptions(scope, currentSnapshot, records = []) {
  const options = []
  if (sameScope(currentSnapshot, scope) && isObject(currentSnapshot.inputs)) {
    options.push({
      id: 'current', label: '当前约束条件',
      inputs: currentSnapshot.inputs, snapshot: currentSnapshot
    })
  }
  const seen = new Set(['current'])
  if (!Array.isArray(records)) return options
  for (const record of records) {
    if (!isObject(record)) continue
    const snapshot = isObject(record.snapshot) ? record.snapshot : record
    if (!sameScope(snapshot, scope) || !isObject(snapshot.inputs)) continue
    const rawId = record.constraintId ?? record.id ?? record.value
    if (rawId === undefined || rawId === null || String(rawId).trim() === '') continue
    const id = `saved:${String(rawId)}`
    if (seen.has(id)) continue
    seen.add(id)
    const name = String(record.name ?? record.label ?? `约束条件${rawId}`).trim()
    options.push({ id, label: name || `约束条件${rawId}`, inputs: snapshot.inputs, snapshot })
  }
  return options
}

export function isCompleteCalcRequest(request) {
  return isObject(request) && isObject(request.reservoirInput) &&
    isObject(request.wellInput) && isObject(request.wellFlowTubeInput)
}

const setNumber = (target, key, source, label, opts) => {
  if (source !== undefined && source !== null && source !== '') target[key] = numeric(source, label, opts)
}

function mapKnownMethod(choice, expected, oldValue, label) {
  if (choice === undefined || choice === null || choice === '') return oldValue
  if (choice === expected) return 0 // 抓包中这三个方法的编码均为 0
  throw new Error(`${label}“${choice}”缺少原平台编码，不能沿用旧的编码`)
}

function mapConstraintInputs(request, inputs) {
  if (!isObject(inputs)) return
  const gas = inputs.gasProperties || {}
  const methods = inputs.calcMethod || {}
  const pipe = inputs.pipeData || {}
  const other = inputs.otherData || {}
  const well = request.wellInput
  const tube = request.wellFlowTubeInput
  const reservoir = request.reservoirInput

  if (gas.gasType) well.gasType = gas.gasType
  setNumber(well, 'specificGravity', gas.dless, '天然气比重', { min: 0, allowZero: false })
  setNumber(well, 'hydrogenSulfide', gas.h2sContent, 'H₂S含量', { min: 0 })
  setNumber(well, 'carbonDioxide', gas.co2Content, 'CO₂含量', { min: 0 })
  setNumber(well, 'nitrogen', gas.n2Content, 'N₂含量', { min: 0 })
  well.modificationMethod = mapKnownMethod(methods.correctionMethod, 'Wichert-Aziz 修正方法', well.modificationMethod, '非烃气体修正')
  well.deviationFactorMethod = mapKnownMethod(methods.deviationMethod, 'Dranchuk-Abu-Kassem 方法', well.deviationFactorMethod, '偏差系数计算')
  well.viscosityMethod = mapKnownMethod(methods.viscosityMethod, 'Lee-Gonzalez-Eakin 方法', well.viscosityMethod, '粘度计算')

  if (pipe.wellboreStructure) {
    const matched = String(pipe.wellboreStructure).match(/^情况([1-4])$/)
    if (!matched) throw new Error('井身结构与原平台编码不一致')
    tube.wellStructure = Number(matched[1])
  }
  setNumber(tube, 'middleDepthOfGasWellProducingFormation', pipe.mdDepth, '产层中深MD', { min: 0 })
  setNumber(tube, 'minDateTrueVerticalDepthOfGasWellProducingFormation', pipe.mdDepth, '当前产层中深MD', { min: 0 })
  setNumber(tube, 'trueVerticalDepthOfGasWellProducingFormation', pipe.tvdDepth, '产层中深TVD', { min: 0 })
  setNumber(tube, 'casingInnerDiameter', pipe.casingInnerDiameter, '套管内径', { min: 0, allowZero: false })
  setNumber(tube, 'tubingBottomMD', pipe.tubingMdDepth, '油管下深MD', { min: 0 })
  setNumber(tube, 'tubingBottomTVD', pipe.tubingTvdDepth, '油管下深TVD', { min: 0 })
  setNumber(tube, 'tubingInnerDiameter', pipe.tubingInnerDiameter, '油管内径', { min: 0, allowZero: false })

  setNumber(well, 'darcySeepageCoefficient', other.coefficientA, '产能系数A', { min: 0 })
  setNumber(well, 'nonDarcySeepageCoefficient', other.coefficientB, '产能系数B', { min: 0 })
  setNumber(well, 'originalPressure', other.initialPressure, '原始地层压力', { min: 0, allowZero: false })
  setNumber(well, 'formationTemperature', other.reservoirTemperature, '地层温度', { min: -273.149 })
  setNumber(well, 'wellheadTemperature', other.wellheadTemperature, '井口温度', { min: -273.149 })
  setNumber(well, 'originalGasVolume', other.dynamicReserve, '动态地质储量', { min: 0, allowZero: false })
  setNumber(well, 'dailyGasProduction', other.productionRate, '气产量', { min: 0 })
  setNumber(well, 'cumulativeGasProduction', other.cumulativeProduction, '累产气量', { min: 0 })

  if (other.materialBalanceType) {
    if (other.materialBalanceType !== '定容气藏') {
      throw new Error('物质平衡方程类型的原平台编码尚未抓取，不能擅自映射')
    }
    reservoir.mbEquationType = 1
  }
  setNumber(reservoir, 'targetProduction', other.targetProduction, '目标产量', { min: 0, allowZero: false })
  setNumber(reservoir, 'economicLimitGasProduction', other.abandonmentRate, '废弃产量', { min: 0 })
  setNumber(reservoir, 'externalWellheadPressure', other.wellheadExportPressure, '外输压力', { min: 0, allowZero: false })
  setNumber(reservoir, 'predictedTimeStep', other.forecastTimeStep, '预测时间步长', { min: 1 })
  setNumber(reservoir, 'predictedTimeStepNumber', other.forecastTotalDays, '预测时间步数', { min: 1 })

  if (other.forecastStartDate) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(other.forecastStartDate)) throw new Error('预测开始日期格式错误')
    const ms = Date.parse(`${other.forecastStartDate}T00:00:00.000Z`)
    if (!Number.isFinite(ms) || new Date(ms).toISOString().slice(0, 10) !== other.forecastStartDate) {
      throw new Error('预测开始日期无效')
    }
    reservoir.predictionStartTime = ms
  }
}

/**
 * HAR 实证：动态预测节点的 nodeType=80 叶子节点，nodeId 对应该任务的
 * dynamicPredictionId，nodeTitle 对应 chart 查询 wellName；AG 的 nodeId 不能套用。
 * 先筛选名称，再用 /dynamicprediction/{p}/{g}/{id} 详情确认井名和ID。
 */
export function extractTypedPredictionTaskNodes(response, scope) {
  const payload = unwrapResponse(response)
  const root = payload?.node || payload
  const prefix = `${String(scope.wellName)}单井动态预测`
  const matches = []
  const visited = new Set()
  function visit(node) {
    if (!node || typeof node !== 'object' || visited.has(node)) return
    visited.add(node)
    if (Array.isArray(node)) { node.forEach(visit); return }
    if (Number(node.nodeType) === 80 &&
        Number.isSafeInteger(Number(node.nodeId)) && Number(node.nodeId) > 0 &&
        typeof node.nodeTitle === 'string' && node.nodeTitle.startsWith(prefix)) {
      matches.push({
        projectId: scope.projectId, gasReservoirId: scope.gasReservoirId,
        wellName: String(scope.wellName), dynamicPredictionId: Number(node.nodeId),
        chartWellName: node.nodeTitle
      })
    }
    if (Array.isArray(node.subNodes)) node.subNodes.forEach(visit)
  }
  visit(root)
  const ids = new Map()
  for (const task of matches) {
    const existing = ids.get(task.dynamicPredictionId)
    if (existing && existing.chartWellName !== task.chartWellName) {
      throw new Error(`动态预测任务 ${task.dynamicPredictionId} 在节点树中出现不同名称`)
    }
    ids.set(task.dynamicPredictionId, task)
  }
  return [...ids.values()]
}

const RESERVOIR_CALC_FIELDS = [
  'mbEquationType', 'targetProduction', 'predictionStartTime',
  'predictedTimeStep', 'predictedTimeStepNumber', 'productivityEvaluationForm',
  'externalWellheadPressure', 'rockCompressionCoefficient',
  'waterCompressionCoefficient', 'economicLimitGasProduction'
]
const WELL_CALC_FIELDS = [
  'wellheadTemperature', 'originalGasVolume', 'darcySeepageCoefficient',
  'nonDarcySeepageCoefficient', 'dailyGasProduction', 'cumulativeGasProduction',
  'waterSaturation', 'originalPressure', 'formationTemperature',
  'gasType', 'specificGravity', 'hydrogenSulfide', 'carbonDioxide', 'nitrogen',
  'modificationMethod', 'deviationFactorMethod', 'viscosityMethod'
]
const TUBE_CALC_FIELDS = [
  'wellStructure', 'middleDepthOfGasWellProducingFormation',
  'minDateTrueVerticalDepthOfGasWellProducingFormation',
  'trueVerticalDepthOfGasWellProducingFormation',
  'casingBottomMD', 'casingBottomTVD', 'casingInnerDiameter',
  'tubingBottomMD', 'tubingBottomTVD', 'tubingInnerDiameter',
  'throttlerBottomMD', 'throttlerBottomTVD', 'throttlerInnerDiameter',
  'velocityStringBottomMD', 'velocityStringBottomTVD', 'velocityStringInnerDiameter'
]
function extractFields(source, keys) {
  const result = {}
  for (const key of keys) {
    const value = source?.[key]
    if (value !== undefined && value !== null && value !== '') result[key] = value
  }
  return result
}

/**
 * 用任务详情生成 Calc 原生参数；绝不把详情里的数据库表 id/edges 发送至 Calc。
 * HAR 中 predictionStartTime 使用「显示日期的 UTC 零点时间戳」，而非带时区 ISO 直接转换。
 */
export function buildCalcRequestFromPredictionDetail(response, scope, task) {
  const payload = unwrapResponse(response)
  const requestedId = positiveInteger(task.dynamicPredictionId, 'dynamicPredictionId')
  const wellName = String(scope.wellName || '').trim()
  if (!wellName || !isObject(payload?.reservoirInput) || !Array.isArray(payload?.wellInputs)) {
    throw new Error('原平台任务详情缺少 reservoirInput 或 wellInputs')
  }
  const reservoirTaskId = payload.reservoirInput.DynamicPredictionId ?? payload.reservoirInput.dynamicPredictionId
  if (String(reservoirTaskId) !== String(requestedId)) {
    throw new Error(`任务详情所属预测编号 ${reservoirTaskId ?? '空'} 与请求 ${requestedId} 不一致`)
  }
  const matches = payload.wellInputs.filter(well => String(well?.wellName) === wellName)
  if (matches.length !== 1) throw new Error(`任务 ${requestedId} 没有唯一匹配当前井 ${wellName} 的 wellInputs`)
  const well = matches[0]
  const wellTaskId = well.DynamicPredictionId ?? well.dynamicPredictionId
  if (String(wellTaskId) !== String(requestedId)) throw new Error('任务详情的井参数所属预测编号不一致')
  const sourceTube = well.edges?.dynamic_prediction_well_flowtube_input
  if (!isObject(sourceTube)) throw new Error(`当前井 ${wellName} 的任务详情缺少井筒参数`)
  const r = extractFields(payload.reservoirInput, RESERVOIR_CALC_FIELDS)
  if (typeof r.predictionStartTime === 'string') {
    const date = r.predictionStartTime.slice(0, 10)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('预测开始日期格式不正确')
    const dateMs = Date.parse(`${date}T00:00:00.000Z`)
    if (!Number.isFinite(dateMs) || new Date(dateMs).toISOString().slice(0, 10) !== date) {
      throw new Error('任务详情中的预测开始日期无效')
    }
    r.predictionStartTime = dateMs
  }
  const w = extractFields(well, WELL_CALC_FIELDS)
  const tube = extractFields(sourceTube, TUBE_CALC_FIELDS)
  // 原平台本次启动预测的实证规则：minDateTrue... 与当前产层中深 MD 相同。
  // 此字段在 GET 详情中可能留有旧值（HAR 中 X-3 从 3968.0556 变为 4095.385）。
  if (tube.middleDepthOfGasWellProducingFormation != null) {
    tube.minDateTrueVerticalDepthOfGasWellProducingFormation = tube.middleDepthOfGasWellProducingFormation
  }
  // HAR 中原平台 Calc 把井筒详情的空值明确发送为 0。包括生产套管 TVD。
  // 这里只补齐原平台抓包已确认的井筒字段；其他未证实字段不补值。
  for (const key of [
    'casingBottomTVD',
    'throttlerBottomMD', 'throttlerBottomTVD', 'throttlerInnerDiameter',
    'velocityStringBottomMD', 'velocityStringBottomTVD', 'velocityStringInnerDiameter'
  ]) {
    if (tube[key] == null) tube[key] = 0
  }
  const calcRequest = {
    wellName, projectId: positiveInteger(scope.projectId, 'projectId'),
    gasReservoirId: positiveInteger(scope.gasReservoirId, 'gasReservoirId'),
    dynamicPredictionId: requestedId,
    chartWellName: String(task.chartWellName ?? '').trim(),
    reservoirInput: r, wellInput: w, wellFlowTubeInput: tube
  }
  if (!calcRequest.chartWellName) throw new Error('任务详情关联信息缺少原平台真实名称')
  if (!isCompleteCalcRequest(calcRequest)) throw new Error('任务详情未能生成 Calc 原生数据结构')
  for (const [group, field] of [
    ['reservoirInput', 'rockCompressionCoefficient'],
    ['reservoirInput', 'waterCompressionCoefficient'],
    ['wellInput', 'waterSaturation'],
    ['wellFlowTubeInput', 'tubingInnerDiameter']
  ]) {
    if (calcRequest[group][field] === undefined) {
      throw new Error(`原平台当前井任务详情缺少 ${group}.${field}`)
    }
  }
  return calcRequest
}

/** 根据 HAR 验证过的 nodeType 80 节点查询任务；用任务详情核实井和编号。 */
export async function loadVerifiedPredictionTasks(scope, api = dynamicPredictionApi) {
  const tree = await api.getPredictionTaskTree(scope.projectId, scope.gasReservoirId)
  const candidates = extractTypedPredictionTaskNodes(tree, scope)
  const tasks = []
  const calcRequestsByTaskId = {}
  const warnings = []
  for (const task of candidates) {
    try {
      const detail = await api.getPredictionDetail(
        scope.projectId, scope.gasReservoirId, task.dynamicPredictionId
      )
      const request = buildCalcRequestFromPredictionDetail(detail, scope, task)
      tasks.push(task)
      calcRequestsByTaskId[String(task.dynamicPredictionId)] = request
    } catch (error) {
      warnings.push(`任务 ${task.dynamicPredictionId} 详情校验失败：${error.message}`)
    }
  }
  return { predictionTasks: tasks, calcRequestsByTaskId, warnings }
}

/** 自动创建当前井真实任务；与已有任务共用同一详情→Calc 组装逻辑。 */
export async function createWellPredictionTask(scope, productivityEvaluationForm = 1, api = dynamicPredictionApi) {
  const nameResponse = unwrapResponse(await api.getDefaultPredictionName(
    scope.projectId, scope.gasReservoirId, scope.wellName
  ))
  const requestedName = String(nameResponse?.predictionName || '').trim()
  if (!requestedName || !requestedName.startsWith(`${scope.wellName}单井动态预测`)) {
    throw new Error('原平台没有返回符合当前井的默认预测任务名称')
  }
  const created = unwrapResponse(await api.createPredictionTask(scope, requestedName, productivityEvaluationForm))
  const id = positiveInteger(created?.dynamicPredictionId, '创建任务的dynamicPredictionId')
  const chartWellName = String(created?.dynamicPredictionName || '').trim()
  if (!chartWellName || chartWellName !== requestedName) {
    throw new Error('创建任务返回的实际任务名称与请求名称不一致')
  }
  const task = {
    ...scope, dynamicPredictionId: id, chartWellName
  }
  let detail = null
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      detail = await api.getPredictionDetail(scope.projectId, scope.gasReservoirId, id)
      return { task, calcRequest: buildCalcRequestFromPredictionDetail(detail, scope, task) }
    } catch (error) {
      if (attempt === 3) throw new Error(`任务 ${id} 已创建，但获取本井参数失败：${error.message}`)
      await new Promise(resolve => setTimeout(resolve, 400 * (attempt + 1)))
    }
  }
  throw new Error('无法取得新建预测任务详情')
}

/**
 * 从任意已核实的原平台响应中抽取预测任务。不能把 nodeId/id/predictionId
 * 当作 dynamicPredictionId；不能按井名或排列顺序推测绘图查询名称。
 * 仅当物理井名明确，或服务端返回的任务名称明确包含该井前缀时接受。
 */
export function extractPredictionTasksFromNodeResponse(response, scope) {
  const targetWell = String(scope?.wellName ?? '').trim()
  if (!targetWell) return []
  const matches = []
  const visited = new Set()
  let visits = 0
  function visit(value, inherited = {}, depth = 0) {
    if (!value || typeof value !== 'object' || visited.has(value) || depth > 16 || visits++ > 20000) return
    visited.add(value)
    if (Array.isArray(value)) {
      value.forEach(item => visit(item, inherited, depth + 1))
      return
    }
    const projectId = value.projectId ?? inherited.projectId
    const gasReservoirId = value.gasReservoirId ?? inherited.gasReservoirId
    const rawWell = value.physicalWellName ?? value.wellName
    // 某些任务对象的 wellName 字段实际上存的是“X-1单井动态预测 1”。
    const physicalWell = String(value.physicalWellName ??
      (typeof rawWell === 'string' && rawWell.includes('单井动态预测') ? inherited.wellName : rawWell ?? inherited.wellName) ?? '').trim()
    const candidateName = [value.chartWellName, value.predictionName,
      value.dynamicPredictionName, value.taskName,
      value.name, value.nodeTitle, value.wellName]
      .find(name => typeof name === 'string' && name.trim() &&
        (['chartWellName', 'predictionName', 'dynamicPredictionName', 'taskName'].some(k => value[k] === name) ||
          name.trim().startsWith(`${targetWell}单井动态预测`)))
    const taskId = value.dynamicPredictionId ?? value.DynamicPredictionId ?? value.dynamicPredictionID
    const name = String(candidateName ?? '').trim()
    const marker = '单井动态预测'
    const ownerByTitle = name.startsWith(`${targetWell}${marker}`)
    const titleBelongsOtherWell = name.includes(marker) && !ownerByTitle
    if (taskId != null && name && !titleBelongsOtherWell &&
        (physicalWell === targetWell || (!physicalWell && ownerByTitle)) &&
        (projectId == null || String(projectId) === String(scope.projectId)) &&
        (gasReservoirId == null || String(gasReservoirId) === String(scope.gasReservoirId))) {
      const id = Number(taskId)
      if (Number.isSafeInteger(id) && id > 0) {
        matches.push({ projectId: scope.projectId, gasReservoirId: scope.gasReservoirId,
          wellName: targetWell, dynamicPredictionId: id, chartWellName: name })
      }
    }
    const next = { projectId, gasReservoirId, wellName: physicalWell || inherited.wellName }
    for (const [field, child] of Object.entries(value)) {
      // Axios config 会带请求参数，不能把它误认作“服务端返回的任务”。
      if (['config', 'headers', 'request', 'status', 'statusText'].includes(field)) continue
      if (child && typeof child === 'object') visit(child, next, depth + 1)
    }
  }
  visit(isObject(response) && Object.prototype.hasOwnProperty.call(response, 'data')
    ? response.data : response)
  const byId = new Map()
  for (const task of matches) {
    const previous = byId.get(String(task.dynamicPredictionId))
    if (previous && previous.chartWellName !== task.chartWellName) {
      throw new Error(`任务 ${task.dynamicPredictionId} 出现多个不同名称，无法安全对应当前井`)
    }
    byId.set(String(task.dynamicPredictionId), task)
  }
  return [...byId.values()]
}

/** 单任务自动选择；多任务只能选择真实存在的任务；快照冲突时停止。 */
export function selectPredictionTask(tasks, { selectedTaskId = '', snapshotTaskId = '' } = {}) {
  const items = Array.isArray(tasks) ? tasks : []
  const byId = id => items.find(t => String(t.dynamicPredictionId) === String(id))
  const has = id => id !== undefined && id !== null && String(id).trim() !== ''
  if (has(selectedTaskId) && has(snapshotTaskId) && String(selectedTaskId) !== String(snapshotTaskId)) {
    return { task: null, warning: '当前选择的任务与约束快照绑定的任务不一致，请重新同步约束参数。' }
  }
  if (has(snapshotTaskId)) {
    const task = byId(snapshotTaskId)
    return task ? { task, warning: '' } :
      { task: null, warning: `约束快照绑定的任务 ${snapshotTaskId} 不在当前井任务列表中，不能自动更换。` }
  }
  if (has(selectedTaskId)) {
    const task = byId(selectedTaskId)
    if (task) return { task, warning: '' }
    if (items.length === 1) return { task: items[0], warning: '旧任务已不存在，已切换到当前井唯一的真实任务。' }
    return { task: null, warning: '之前选择的任务已不在当前井列表中，请重新选择。' }
  }
  if (items.length === 1) return { task: items[0], warning: '' }
  if (items.length > 1) return { task: null, warning: '当前井存在多个预测任务，请先选择任务。' }
  return { task: null, warning: '没有取得当前井真实的预测任务。' }
}

/**
 * 从已有的“井列表/其他数据”接口响应中，只提取与当前井显式匹配的字段。
 * 只识别原平台 Calc 的同名键，避免把无关的井资料当作岩石压缩系数等参数。
 */
export function extractCurrentWellProfileFromResponses(responses, scope) {
  const result = {
    projectId: scope.projectId, gasReservoirId: scope.gasReservoirId,
    wellName: scope.wellName, reservoirInput: {}, wellInput: {}, wellFlowTubeInput: {}
  }
  const fields = {
    reservoirInput: [
      'mbEquationType', 'rockCompressionCoefficient', 'waterCompressionCoefficient',
      'predictionStartTime', 'predictedTimeStep', 'predictedTimeStepNumber'
    ],
    wellInput: [
      'waterSaturation', 'specificGravity', 'originalPressure', 'originalGasVolume',
      'formationTemperature', 'wellheadTemperature', 'dailyGasProduction',
      'cumulativeGasProduction', 'darcySeepageCoefficient', 'nonDarcySeepageCoefficient'
    ],
    wellFlowTubeInput: [
      'wellStructure', 'middleDepthOfGasWellProducingFormation',
      'minDateTrueVerticalDepthOfGasWellProducingFormation',
      'trueVerticalDepthOfGasWellProducingFormation', 'casingBottomMD', 'casingBottomTVD',
      'casingInnerDiameter', 'tubingBottomMD', 'tubingBottomTVD', 'tubingInnerDiameter',
      'throttlerBottomMD', 'throttlerBottomTVD', 'throttlerInnerDiameter',
      'velocityStringBottomMD', 'velocityStringBottomTVD', 'velocityStringInnerDiameter'
    ]
  }
  const seen = new Set()
  function visit(value, depth = 0) {
    if (!value || typeof value !== 'object' || seen.has(value) || depth > 12) return
    seen.add(value)
    if (Array.isArray(value)) {
      value.forEach(item => visit(item, depth + 1))
      return
    }
    if (String(value.wellName ?? value.physicalWellName ?? '') === String(scope.wellName) &&
      (value.projectId == null || String(value.projectId) === String(scope.projectId)) &&
      (value.gasReservoirId == null || String(value.gasReservoirId) === String(scope.gasReservoirId)) &&
      (value.projectGasReservoirId == null ||
        String(value.projectGasReservoirId) === String(scope.gasReservoirId))) {
      for (const [group, keys] of Object.entries(fields)) {
        const nested = isObject(value[group]) ? value[group] : {}
        for (const key of keys) {
          const raw = nested[key] ?? value[key]
          if (raw === undefined || raw === null || raw === '') continue
          const num = optionalNumber(raw)
          if (num !== null) result[group][key] = num
        }
      }
    }
    for (const [key, child] of Object.entries(value)) {
      if (['config', 'headers', 'request', 'statusText'].includes(key)) continue
      if (child && typeof child === 'object') visit(child, depth + 1)
    }
  }
  for (const response of (Array.isArray(responses) ? responses : [responses])) visit(response)
  return result
}

/**
 * 从“当前井约束快照”中提取/组装当前井的 Calc 请求。
 *
 * 这里只接受快照里实际出现的数值和预测任务元数据；绝不把 X-1 抓包
 * 模板或任务编号复制到别的井。若约束页没有输出真实任务ID/任务名称，
 * 返回明确的缺失项，由上游补齐，不能猜测任务号。
 *
 * 可接受：
 *  - 快照里的原平台完整请求（calcRequest/dynamicPredictionRequest）；
 *  - 快照输入里的 reservoirInput/wellInput/wellFlowTubeInput 原生字段；
 *  - 已存在的 gasProperties/pipeData/otherData/calcMethod 表单字段；
 *  - 附带 task / predictionTask 中的 dynamicPredictionId + chartWellName。
 */
export function resolveCalcContextFromConstraints({ scope, snapshot, platformContext = null }) {
  const issues = []
  const warnings = []
  if (!snapshot || !isObject(snapshot)) {
    return { request: null, issues: ['当前井还没有约束参数快照，请先打开约束条件页并保存当前参数。'], warnings }
  }
  if (!sameScope(snapshot, scope)) {
    return { request: null, issues: ['约束参数快照不属于当前项目/气藏/井，已拒绝跨井复用。'], warnings }
  }
  const inputs = isObject(snapshot.inputs) ? snapshot.inputs : {}
  const external = isObject(platformContext) && sameScope(platformContext, scope) ? platformContext : null
  const snapshotTask = [snapshot.predictionTask, snapshot.task, snapshot.source?.predictionTask,
    inputs.predictionTask].find(isObject) || {}
  const allTasks = Array.isArray(external?.predictionTasks)
    ? external.predictionTasks.filter(item => isObject(item) && sameScope(item, scope)) : []
  const snapshotId = snapshotTask.dynamicPredictionId ?? snapshot.dynamicPredictionId ??
    snapshot.source?.dynamicPredictionId ?? inputs.dynamicPredictionId ??
    external?.selectedTask?.dynamicPredictionId
  const snapshotName = snapshotTask.chartWellName ?? snapshotTask.predictionName ??
    snapshotTask.dynamicPredictionName ?? snapshotTask.taskName ??
    snapshot.chartWellName ?? snapshot.predictionName ?? inputs.chartWellName ??
    external?.selectedTask?.chartWellName
  let matchedTasks = allTasks
  if (snapshotId != null && String(snapshotId).trim() !== '') {
    matchedTasks = matchedTasks.filter(item => String(item.dynamicPredictionId) === String(snapshotId))
  }
  if (snapshotName != null && String(snapshotName).trim() !== '') {
    matchedTasks = matchedTasks.filter(item => String(item.chartWellName).trim() === String(snapshotName).trim())
  }
  const task = matchedTasks.length === 1 ? matchedTasks[0] : snapshotTask
  if (allTasks.length > 1 && matchedTasks.length !== 1 &&
      !snapshotId && !snapshotName) {
    issues.push(`当前井发现 ${allTasks.length} 个真实预测任务，请在原平台确定当前使用的任务；不自动猜测任务ID。`)
  }
  if (allTasks.length && (snapshotId != null || snapshotName != null) &&
      matchedTasks.length === 0 &&
      !(snapshotTask.dynamicPredictionId &&
        (snapshotTask.chartWellName || snapshotTask.predictionName))) {
    warnings.push('约束快照中的预测任务未在当前分析节点返回值中找到，不能据此推断任务名称。')
  }
  const requestCandidates = [
    snapshot.dynamicPredictionRequest, snapshot.calcRequest, snapshot.platformCalcRequest,
    snapshot.source?.dynamicPredictionRequest, snapshot.source?.calcRequest,
    inputs.dynamicPredictionRequest, inputs.calcRequest,
    external?.selectedTask?.calcRequest, external?.calcRequest
  ].filter(isObject)
  const candidate = requestCandidates.find(value =>
    isCompleteCalcRequest(value) &&
    (value.wellName == null || String(value.wellName) === String(scope.wellName)) &&
    (value.projectId == null || String(value.projectId) === String(scope.projectId)) &&
    (value.gasReservoirId == null || String(value.gasReservoirId) === String(scope.gasReservoirId))
  ) || null
  const profileSource = [snapshot.wellProfile, snapshot.source?.wellProfile, inputs.wellProfile]
    .find(isObject) || {}
  const externalProfile = isObject(external?.wellProfile) ? external.wellProfile : {}
  const profile = {
    ...externalProfile, ...profileSource,
    reservoirInput: { ...(externalProfile.reservoirInput || {}), ...(profileSource.reservoirInput || {}) },
    wellInput: { ...(externalProfile.wellInput || {}), ...(profileSource.wellInput || {}) },
    wellFlowTubeInput: {
      ...(externalProfile.wellFlowTubeInput || {}), ...(profileSource.wellFlowTubeInput || {})
    }
  }

  const idCandidates = [
    candidate?.dynamicPredictionId, task.dynamicPredictionId,
    snapshot.dynamicPredictionId, snapshot.source?.dynamicPredictionId,
    inputs.dynamicPredictionId
  ].filter(value => value !== undefined && value !== null && String(value).trim() !== '')
  let dynamicPredictionId = null
  try {
    const ids = [...new Set(idCandidates.map(id => positiveInteger(id, 'dynamicPredictionId')))]
    if (ids.length > 1) issues.push('约束快照中的预测任务ID不一致；请确认当前选中的任务。')
    else dynamicPredictionId = ids[0] ?? null
  } catch (error) { issues.push(error.message) }
  if (dynamicPredictionId === null) issues.push('缺少当前井的 dynamicPredictionId（原平台预测任务ID）。')

  const taskNames = [
    candidate?.chartWellName, candidate?.predictionName, candidate?.dynamicPredictionName,
    task.chartWellName, task.predictionName, task.dynamicPredictionName, task.taskName,
    snapshot.chartWellName, snapshot.predictionName,
    snapshot.source?.chartWellName, inputs.chartWellName
  ].filter(v => v !== undefined && v !== null && String(v).trim() !== '')
  const names = [...new Set(taskNames.map(v => String(v).trim()))]
  if (names.length > 1) issues.push('约束快照里的绘图任务名称不一致。')
  const chartWellName = names[0] ?? null
  if (!chartWellName) issues.push('缺少当前井的 chartWellName（原平台 /chart 的 wellName 查询值）。')
  if (chartWellName?.includes('单井动态预测') &&
      !chartWellName.startsWith(String(scope.wellName) + '单井动态预测')) {
    issues.push(`绘图任务“${chartWellName}”不属于当前井 ${scope.wellName}。`)
  }

  const raw = candidate ? {
    ...candidate,
    reservoirInput: { ...profile.reservoirInput, ...candidate.reservoirInput },
    wellInput: { ...profile.wellInput, ...candidate.wellInput },
    wellFlowTubeInput: { ...profile.wellFlowTubeInput, ...candidate.wellFlowTubeInput }
  } : {
    reservoirInput: { ...profile.reservoirInput, ...inputs.reservoirInput },
    wellInput: { ...profile.wellInput, ...inputs.wellInput },
    wellFlowTubeInput: { ...profile.wellFlowTubeInput, ...inputs.wellFlowTubeInput }
  }
  // 候选完整请求中的物理井/项目/气藏必须与当前井一致。
  for (const [key, expected] of [
    ['wellName', scope.wellName], ['projectId', scope.projectId],
    ['gasReservoirId', scope.gasReservoirId]
  ]) {
    if (raw[key] !== undefined && raw[key] !== null &&
        String(raw[key]) !== String(expected)) issues.push(`Calc 请求的 ${key} 属于其他井或项目。`)
  }
  if (isObject(profile)) {
    for (const [key, expected] of [
      ['wellName', scope.wellName], ['projectId', scope.projectId],
      ['gasReservoirId', scope.gasReservoirId]
    ]) {
      if (profile[key] != null && String(profile[key]) !== String(expected)) {
        issues.push(`井基础数据的 ${key} 不属于当前井或项目，已拒绝复用。`)
      }
    }
  }
  for (const [key, expected] of [
    ['physicalWellName', scope.wellName], ['projectId', scope.projectId],
    ['gasReservoirId', scope.gasReservoirId]
  ]) {
    if (task[key] != null && String(task[key]) !== String(expected)) {
      issues.push(`预测任务的 ${key} 不属于当前井或项目，已拒绝复用。`)
    }
  }
  if (!isObject(inputs.reservoirInput) && !isObject(inputs.wellInput) &&
      !isObject(inputs.wellFlowTubeInput) && !candidate &&
      !(inputs.gasProperties || inputs.pipeData || inputs.otherData)) {
    issues.push('约束快照没有气体、井筒、生产参数，也没有原平台 Calc 原生输入。')
  }

  const request = {
    ...cloneJson(raw),
    projectId: scope.projectId,
    gasReservoirId: scope.gasReservoirId,
    wellName: scope.wellName,
    dynamicPredictionId,
    chartWellName,
    reservoirInput: cloneJson(raw.reservoirInput || {}),
    wellInput: cloneJson(raw.wellInput || {}),
    wellFlowTubeInput: cloneJson(raw.wellFlowTubeInput || {})
  }
  try { mapConstraintInputs(request, inputs) }
  catch (error) { issues.push(`约束输入映射失败：${error.message}`) }

  // 只要求当前 Calc 关键物性与预测字段真实存在；其他平台扩展字段不填0、
  // 不移植 X-1 的值。若原平台还要求专用字段，须由当前井快照/基础数据提供。
  const required = [
    ['reservoirInput', 'predictionStartTime', '预测开始日期'],
    ['reservoirInput', 'predictedTimeStep', '预测时间步长'],
    ['reservoirInput', 'predictedTimeStepNumber', '预测时间步数'],
    ['wellInput', 'specificGravity', '天然气比重'],
    ['wellInput', 'originalPressure', '原始地层压力'],
    ['wellInput', 'originalGasVolume', '动态地质储量'],
    ['wellFlowTubeInput', 'wellStructure', '井身结构'],
    ['wellFlowTubeInput', 'tubingInnerDiameter', '油管内径'],
    ['wellFlowTubeInput', 'casingInnerDiameter', '套管内径']
  ]
  // 无论是从约束页自动组装、还是快照内携带了原生 Calc 请求，都验证必要字段。
  // 只检查已知必需字段；未抓到的专用字段不私自填零或挪用 X-1 值。
  const absent = required.filter(([group, field]) =>
    request[group]?.[field] === undefined || request[group]?.[field] === null ||
    String(request[group]?.[field]).trim() === '').map(([, , label]) => label)
  if (absent.length) issues.push(`当前井 Calc 请求缺少必要字段：${absent.join('、')}。`)
  const platformSpecific = [
    ['reservoirInput', 'rockCompressionCoefficient', '岩石压缩系数'],
    ['reservoirInput', 'waterCompressionCoefficient', '水压缩系数'],
    ['wellInput', 'waterSaturation', '含水饱和度']
  ].filter(([group, field]) => request[group]?.[field] === undefined)
  if (platformSpecific.length) warnings.push(
    `未提供原平台专用参数：${platformSpecific.map(([, , label]) => label).join('、')}；不会用 X-1 数值补齐，后台如要求这些字段需接入当前井基础数据。`
  )
  return { request: issues.length ? null : request, issues, warnings }
}

/** 确保当前任务不覆盖约束快照中明确绑定的另一预测任务。 */
export function assertChartTaskMatchesSnapshot(snapshot, query) {
  if (!isObject(snapshot)) return
  const task = [snapshot.predictionTask, snapshot.task, snapshot.source?.predictionTask,
    snapshot.inputs?.predictionTask].find(isObject) || {}
  const ids = [task.dynamicPredictionId, snapshot.dynamicPredictionId,
    snapshot.source?.dynamicPredictionId, snapshot.inputs?.dynamicPredictionId]
    .filter(value => value !== undefined && value !== null && String(value).trim() !== '')
  for (const id of ids) {
    if (positiveInteger(id, '快照预测任务ID') !== Number(query.dynamicPredictionId)) {
      throw new Error(`当前约束快照选择的预测任务 ${id} 与即将执行的 Calc 任务 ${query.dynamicPredictionId} 不一致，已停止以防覆盖其他方案。`)
    }
  }
  const names = [task.chartWellName, task.predictionName, task.dynamicPredictionName,
    task.taskName, snapshot.chartWellName, snapshot.predictionName,
    snapshot.source?.chartWellName, snapshot.inputs?.chartWellName]
    .filter(value => value !== undefined && value !== null && String(value).trim() !== '')
  for (const name of names) {
    if (String(name).trim() !== query.wellName) {
      throw new Error(`当前约束快照的预测任务名称“${name}”与绘图任务“${query.wellName}”不一致，已停止 Calc。`)
    }
  }
}

/**
 * 约束页面常使用显示精度保存数值，例如任务详情原值为 24.455246754769277，
 * 但约束快照里只有 24.4552。不能把这种显示四舍五入当作用户主动修改，
 * 覆盖原平台新任务详情的完整精度。
 *
 * 只有「极简方案生成」启用本保护：其两个显式输入始终在随后覆盖。
 * 将来保存的约束记录若有明确修改记录，可以在 inputs.explicitCalcOverrideFields
 * 中写入完整字段名（如 'wellInput.originalGasVolume'）以强制应用修改值。
 * 对真正超出显示舍入范围的约束修改，仍然正常应用。
 */
function preserveDetailPrecisionWhenInputsAreRounded(request, original, inputs) {
  const forced = new Set(Array.isArray(inputs?.explicitCalcOverrideFields)
    ? inputs.explicitCalcOverrideFields.map(String) : [])
  const protectedGroups = ['reservoirInput', 'wellInput', 'wellFlowTubeInput']
  for (const group of protectedGroups) {
    const existing = original?.[group] || {}
    const mapped = request?.[group] || {}
    for (const [field, proposed] of Object.entries(mapped)) {
      const fullKey = `${group}.${field}`
      if (forced.has(fullKey) ||
          fullKey === 'reservoirInput.targetProduction' ||
          fullKey === 'reservoirInput.externalWellheadPressure') continue
      const baseline = existing[field]
      if (typeof baseline !== 'number' || typeof proposed !== 'number' ||
          !Number.isFinite(baseline) || !Number.isFinite(proposed) ||
          baseline === proposed || baseline === 0 || proposed === 0) continue
      // 仅保护至少两位小数的显示舍入值；不把真正的整数/大幅变更吞掉。
      const s = String(proposed)
      const fraction = s.match(/\.(\d+)(?:e[+-]?\d+)?$/i)?.[1]
      const exponent = Number(s.match(/e([+-]?\d+)$/i)?.[1] || 0)
      const decimals = Math.max(0, (fraction?.length || 0) - exponent)
      if (decimals < 2 || decimals > 13) continue
      const halfUnit = 0.5 * 10 ** -decimals
      const tolerance = Number.EPSILON * Math.max(1, Math.abs(baseline)) * 2
      if (Math.abs(baseline - proposed) <= halfUnit + tolerance) {
        mapped[field] = baseline
      }
    }
  }
}

export function createCalcRequest({ scope, baseRequest, inputs, targetProduction, exportPressure, preferFormInputs = false }) {
  const projectId = positiveInteger(scope?.projectId, 'projectId')
  const gasReservoirId = positiveInteger(scope?.gasReservoirId, 'gasReservoirId')
  const wellName = String(scope?.wellName ?? '').trim()
  if (!wellName) throw new Error('缺少井名')
  if (!isCompleteCalcRequest(baseRequest)) {
    throw new Error('当前井缺少可用的 Calc 请求参数；请检查约束输入快照、当前井任务编号及原平台专用字段。')
  }
  if (baseRequest.wellName && String(baseRequest.wellName) !== wellName) {
    throw new Error('Calc 请求模板属于另一口井，已阻止跨井复用')
  }
  if (baseRequest.projectId != null && String(baseRequest.projectId) !== String(projectId)) {
    throw new Error('Calc 请求模板属于另一项目')
  }
  if (baseRequest.gasReservoirId != null && String(baseRequest.gasReservoirId) !== String(gasReservoirId)) {
    throw new Error('Calc 请求模板属于另一气藏')
  }
  const request = cloneJson(baseRequest)
  request.projectId = projectId
  request.gasReservoirId = gasReservoirId
  request.wellName = wellName
  mapConstraintInputs(request, inputs)
  if (preferFormInputs) {
    preserveDetailPrecisionWhenInputsAreRounded(request, baseRequest, inputs)
  }
  // 默认兼容旧页面：约束页参数优先。
  // 极简“方案生成”页面指定 preferFormInputs=true 时，两个人工输入框优先，
  // 其余气体物性/井筒/储层参数仍使用选中约束快照或当前井原平台任务详情。
  const current = inputs?.otherData || {}
  const present = value => value !== undefined && value !== null && String(value).trim() !== ''
  const choose = (key, manual, fallback) => {
    if (preferFormInputs && present(manual)) return manual
    if (present(current[key])) return current[key]
    if (present(manual)) return manual
    return fallback
  }
  request.reservoirInput.targetProduction = numeric(
    choose('targetProduction', targetProduction, request.reservoirInput.targetProduction),
    '单井目标产量', { min: 0, allowZero: false }
  )
  request.reservoirInput.externalWellheadPressure = numeric(
    choose('wellheadExportPressure', exportPressure, request.reservoirInput.externalWellheadPressure),
    '井口外输压力', { min: 0, allowZero: false }
  )
  positiveInteger(request.dynamicPredictionId, 'dynamicPredictionId')
  positiveInteger(request.reservoirInput.predictedTimeStep, 'predictedTimeStep')
  positiveInteger(request.reservoirInput.predictedTimeStepNumber, 'predictedTimeStepNumber')
  if (request.wellInput.originalGasVolume <= 0) throw new Error('动态储量必须大于零')
  return request
}

export function unwrapResponse(response) {
  let value = response
  for (let i = 0; i < 4; i += 1) {
    if (!isObject(value) || !Object.prototype.hasOwnProperty.call(value, 'data')) break
    value = value.data
  }
  return value
}
export function findPredictionId(response) {
  // 如果平台确实返回结果ID，优先使用它；不再把请求体中的ID当作结果ID。
  const headers = isObject(response?.headers) ? response.headers : {}
  for (const header of ['x-result-prediction-id', 'x-prediction-result-id']) {
    const candidate = headers[header] ?? headers[header.toUpperCase()]
    if (candidate !== undefined && candidate !== null && /^\d+$/.test(String(candidate))) {
      return positiveInteger(candidate, 'Calc响应头结果ID')
    }
  }
  const location = headers.location ?? headers.Location
  if (location) {
    const match = String(location).match(/\/(?:result|results)\/(?:[^/?]+\/)*([0-9]+)(?:[/?#]|$)/i)
    if (match) return positiveInteger(match[1], 'Calc Location结果ID')
  }
  const payload = unwrapResponse(response)
  if (payload == null || payload === '') return null
  const seen = new Set()
  const queue = [payload]
  let node
  while (queue.length) {
    node = queue.shift()
    if (!isObject(node) || seen.has(node)) continue
    seen.add(node)
    for (const key of ['resultPredictionId', 'ResultPredictionId', 'predictionResultId', 'resultId', 'ResultId']) {
      if (node[key] !== null && node[key] !== undefined && node[key] !== '') return positiveInteger(node[key], key)
    }
    for (const item of Object.values(node)) if (isObject(item)) queue.push(item)
  }
  return null
}

export function fingerprintWellResults(points) {
  return JSON.stringify(points.map(p => [p.date, p.gas, p.cumulative, p.formation, p.wellhead]))
}

// 任务名称必须由当前井原平台任务列表明确返回，不从 X-1 历史任务或井名拼造。
export function resolveChartQuery({ scope, request, chartWellName }) {
  const projectId = positiveInteger(scope?.projectId, 'projectId')
  const gasReservoirId = positiveInteger(scope?.gasReservoirId, 'gasReservoirId')
  const wellName = String(scope?.wellName ?? '').trim()
  const dynamicPredictionId = positiveInteger(request?.dynamicPredictionId, 'dynamicPredictionId')
  if (!wellName) throw new Error('缺少当前井名')
  // Calc请求已独立校验井归属，这里再校验一次，避免从不匹配的任务读取另一口井的曲线。
  if (request.wellName && String(request.wellName) !== wellName) {
    throw new Error('绘图任务与当前物理井不一致')
  }
  if (request.projectId != null && String(request.projectId) !== String(projectId)) {
    throw new Error('绘图任务与当前项目不一致')
  }
  if (request.gasReservoirId != null && String(request.gasReservoirId) !== String(gasReservoirId)) {
    throw new Error('绘图任务与当前气藏不一致')
  }
  const declaredNames = [request.chartWellName, request.predictionName,
    request.dynamicPredictionName, request.taskName].filter(value =>
    value !== null && value !== undefined && String(value).trim() !== '')
  if (chartWellName && declaredNames.length && declaredNames.some(value => String(value).trim() !== String(chartWellName).trim())) {
    throw new Error('父组件传入的绘图任务名称与当前井 Calc 请求声明的任务名称不一致')
  }
  const explicit = [chartWellName, ...declaredNames]
    .find(value => value !== null && value !== undefined && String(value).trim() !== '')
  const name = String(explicit ?? '').trim()
  if (!name) {
    throw new Error(`当前井 ${wellName} 的动态预测任务 ${dynamicPredictionId} 缺少绘图查询名称。请从原平台当前任务列表传入 chartWellName（原平台绘图GET的wellName参数），不能只传物理井名。`)
  }
  // 原平台常见格式为“X-1单井动态预测 1”；如能看出前缀所属井，禁止跨井名字。
  const marker = '单井动态预测'
  if (name.includes(marker) && name.slice(0, name.indexOf(marker)) !== wellName) {
    throw new Error(`绘图名称“${name}”不属于当前井 ${wellName}`)
  }
  return { projectId, gasReservoirId, dynamicPredictionId, wellName: name, physicalWellName: wellName }
}

// 专门解析 /result/well/chart 的 chartItems；严格区分0值与缺失值。
// 数据同时包含历史（高频）和未来（低频）日期，不筛 outputType，不按数组序号强行配对。
export function normalizePlatformChart(response, query = {}) {
  const payload = unwrapResponse(response)
  if (!isObject(payload) || !Array.isArray(payload.chartItems)) {
    throw new Error('绘图接口返回结构不正确：缺少 chartItems（请确认调用的是 /result/well/chart 而非 /data）')
  }
  const spec = [
    { key: 'gas', field: 'gasProduction', name: '气产量' },
    { key: 'cumulative', field: 'cumulativeGasProduction', name: '累产气量' },
    { key: 'formation', field: 'formationPressure', name: '地层压力' }
  ]
  const byDate = new Map()
  for (const { key, field, name } of spec) {
    const item = payload.chartItems.find(entry => entry?.yAxisField === field) ??
      payload.chartItems.find(entry => entry?.name === name)
    if (!item || !Array.isArray(item.data) || item.data.length === 0) {
      throw new Error(`原平台绘图数据缺少“${name}”序列`)
    }
    let valid = 0
    for (const entry of item.data) {
      if (!isObject(entry) || entry.isDeleted === true) continue
      const date = String(entry.xValue ?? '').slice(0, 10)
      if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue
      const milliseconds = Date.parse(`${date}T00:00:00.000Z`)
      if (!Number.isFinite(milliseconds) || new Date(milliseconds).toISOString().slice(0, 10) !== date) continue
      const number = optionalNumber(entry.yValue)
      // null 表示数据缺失，用 ECharts 断线处理；0 是有意义的停产/零值，不得移除。
      if (number === null) continue
      if (!byDate.has(date)) byDate.set(date, {
        date, gas: null, cumulative: null, formation: null, wellhead: null
      })
      byDate.get(date)[key] = number
      valid += 1
    }
    if (!valid) throw new Error(`“${name}”序列没有有效时间点`)
  }
  // /chart 部分版本还包含井口压力序列。仅当明确是“井口预测压力”时读取，
  // 绝不能把外输压力输入值或地层压力冒充井口压力。
  const wellheadFields = new Set([
    'wellheadPressure', 'wellHeadPressure', 'wellheadPressureMpa',
    'wellHeadPressureMpa', 'tubingHeadPressure'
  ])
  const wellheadNames = new Set(['井口压力', '井口预测压力', '预测井口压力', '井口油压'])
  const wellheadItem = payload.chartItems.find(entry =>
    wellheadFields.has(String(entry?.yAxisField ?? '')) ||
    wellheadNames.has(String(entry?.name ?? '').trim()))
  if (Array.isArray(wellheadItem?.data)) {
    for (const entry of wellheadItem.data) {
      if (!isObject(entry) || entry.isDeleted === true) continue
      const date = String(entry.xValue ?? '').slice(0, 10)
      if (!byDate.has(date)) continue
      const pressure = optionalNumber(entry.yValue)
      if (pressure !== null && pressure >= 0) byDate.get(date).wellhead = pressure
    }
  }
  const points = [...byDate.values()].sort((a, b) => a.date.localeCompare(b.date))
  if (!points.some(point => point.gas !== null)) throw new Error('绘图没有有效气产量数据')
  if (!points.some(point => point.cumulative !== null)) throw new Error('绘图没有有效累产气量数据')
  if (!points.some(point => point.formation !== null)) throw new Error('绘图没有有效地层压力数据')
  return {
    id: query.dynamicPredictionId ?? null,
    chartWellName: query.wellName || null,
    source: '/result/well/chart',
    hasWellheadPressure: points.some(point => point.wellhead !== null),
    points,
    total: points.length
  }
}

export function normalizeWellResults(response, scope, expectedPredictionId) {
  const payload = unwrapResponse(response)
  const source = Array.isArray(payload) ? payload : payload?.items
  if (!Array.isArray(source) || source.length === 0) throw new Error('结果查询为空，可能尚未计算完成或查询了错误的预测 ID')
  const expectedId = positiveInteger(expectedPredictionId, '查询预测ID')
  const points = []
  for (const entry of source) {
    if (!isObject(entry)) continue
    if (entry.wellName != null && String(entry.wellName) !== String(scope.wellName)) {
      throw new Error(`返回结果属于井 ${entry.wellName}，与当前井 ${scope.wellName} 不一致`)
    }
    const resultId = entry.DynamicPredictionId ?? entry.dynamicPredictionId
    if (resultId === null || resultId === undefined) throw new Error('结果记录没有 DynamicPredictionId，不能确认属于本次查询')
    if (positiveInteger(resultId, '结果预测ID') !== expectedId) {
      throw new Error(`结果预测ID ${resultId} 与查询ID ${expectedId} 不一致，已拒绝混用历史结果`)
    }
    if (entry.outputType != null && Number(entry.outputType) !== 2) continue
    const date = String(entry.predictionDate || '').slice(0, 10)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) continue
    const gas = optionalNumber(entry.gasProduction)
    if (gas === null || gas < 0) continue
    points.push({
      date,
      gas,
      cumulative: optionalNumber(entry.cumulativeGasProduction),
      formation: optionalNumber(entry.formationPressure),
      wellhead: optionalNumber(entry.wellheadPressure ?? entry.wellHeadPressure ?? entry.wellheadPressureMpa)
    })
  }
  points.sort((a, b) => a.date.localeCompare(b.date))
  if (!points.length) throw new Error('没有有效井预测点（日期或产气量缺失）')
  return { id: expectedId, points, total: Number(payload?.total) || points.length }
}

export const CONSTRAINT_DEFS = [
  { key: 'erosionLimit', label: '冲蚀上限', direction: 'upper', color: '#d07d5f' },
  { key: 'sandProductionLimit', label: '出砂上限', direction: 'upper', color: '#9574bf' },
  { key: 'liquidLoadingLimit', label: '积液下限', direction: 'lower', color: '#299785' }
]
export function boundState(snapshot, selected) {
  const entries = CONSTRAINT_DEFS.filter(def => selected[def.key]).map(def => {
    const source = snapshot?.constraintResults?.[def.key]
    const value = optionalNumber(source?.value)
    return {
      ...def,
      value: source?.available === false || value === null || value < 0 ? null : value,
      verified: source?.verified === true,
      reason: source?.reason || ''
    }
  })
  const lowerValues = entries.filter(item => item.direction === 'lower' && item.value !== null).map(item => item.value)
  const upperValues = entries.filter(item => item.direction === 'upper' && item.value !== null).map(item => item.value)
  const lower = lowerValues.length ? Math.max(...lowerValues) : null
  const upper = upperValues.length ? Math.min(...upperValues) : null
  return {
    entries,
    missing: entries.filter(item => item.value === null),
    unverified: entries.filter(item => item.value !== null && !item.verified),
    lower, upper,
    feasible: lower === null || upper === null || lower <= upper
  }
}

export function assessWellResults(points, snapshot, selected, exportPressure) {
  const bound = boundState(snapshot, selected)
  const pOut = optionalNumber(exportPressure)
  const counts = { passed: 0, failed: 0, unknown: 0, shutIn: 0 }
  const assessments = points.map(point => {
    if (point.gas === 0) { counts.shutIn++; return 'shutIn' }
    const knownFail = bound.entries.some(item =>
      item.value !== null && (item.direction === 'upper' ? point.gas > item.value : point.gas < item.value))
    if (knownFail || !bound.feasible ||
      (selected.wellheadPressure && point.wellhead !== null && pOut !== null && point.wellhead < pOut)) {
      counts.failed++; return 'failed'
    }
    if (bound.missing.length || bound.unverified.length ||
      (selected.wellheadPressure && (pOut === null || point.wellhead === null))) {
      counts.unknown++; return 'unknown'
    }
    counts.passed++
    return 'passed'
  })
  return { ...counts, assessments, bound }
}

/**
 * 校验选中的上下界。三个产量约束使用 10⁴m³/d，外输压力使用 MPa。
 * 不把 MPa 的压力限值混进产量上下界。
 * 未取得或未经核实的边界默认不能作为硬约束；可由用户显式选择参考值模式。
 */
export function validateConstrainedBounds(snapshot, selected = {}, exportPressure, { allowUnverified = false } = {}) {
  if (!isObject(snapshot?.constraintResults)) {
    throw new Error('所选约束条件没有计算结果；请先到约束条件页计算冲蚀、出砂和积液上下界')
  }
  const bound = boundState(snapshot, selected)
  if (!bound.entries.length && !selected.wellheadPressure) {
    throw new Error('至少选择一个约束条件，才能生成约束后的图像')
  }
  if (bound.missing.length) {
    throw new Error(`约束值缺失或算法计算失败：${bound.missing.map(item => item.label).join('、')}，无法生成约束图像`)
  }
  if (!allowUnverified && bound.unverified.length) {
    throw new Error(`以下约束尚未验证：${bound.unverified.map(item => item.label).join('、')}。请重新计算或明确勾选“采用未核实参考值”`)
  }
  if (!bound.feasible) {
    throw new Error(`上下界冲突：积液下限 ${bound.lower} 大于冲蚀/出砂上限 ${bound.upper}，没有可行产量区间`)
  }
  const pOut = selected.wellheadPressure
    ? numeric(exportPressure, '井口外输压力', { min: 0, allowZero: false })
    : null
  return { ...bound, exportPressureMpa: pOut, usesReferenceValue: allowUnverified && bound.unverified.length > 0 }
}

/**
 * 最终曲线：逐日期同时检验选中的约束；不合格日期的全部曲线值置 null。
 * 保留日期以让 ECharts 断线，不拼接跨越违规时间的假曲线；绝不裁改产量或压力。
 * 累产/地层压力只保留与合格日期对应的原值，不是重新求解后的结果。
 */
export function buildConstrainedCurve(points, snapshot, selected = {}, exportPressure, options = {}) {
  const bound = validateConstrainedBounds(snapshot, selected, exportPressure, options)
  if (!Array.isArray(points) || !points.length) throw new Error('原平台没有可供约束筛选的预测点')
  if (selected.wellheadPressure && !points.some(p => optionalNumber(p?.wellhead) !== null)) {
    throw new Error('原平台 /chart 未提供井口压力预测序列，不能验证“井口压力 ≥ 外输压力”。请获取同一任务的井口压力数据，或明确取消外输压力约束；不会用地层压力替代。')
  }
  const excludedBy = {
    missingGas: 0, erosion: 0, sand: 0, liquid: 0,
    pressure: 0, missingPressure: 0
  }
  let passed = 0
  const resultPoints = points.map(point => {
    const gas = optionalNumber(point?.gas)
    const wellhead = optionalNumber(point?.wellhead)
    const reasons = []
    if (gas === null || gas < 0) reasons.push('missingGas')
    if (gas !== null) {
      for (const entry of bound.entries) {
        const failed = entry.direction === 'upper' ? gas > entry.value : gas < entry.value
        if (failed) {
          reasons.push(entry.key === 'erosionLimit' ? 'erosion'
            : entry.key === 'sandProductionLimit' ? 'sand' : 'liquid')
        }
      }
    }
    if (selected.wellheadPressure) {
      if (wellhead === null || wellhead < 0) reasons.push('missingPressure')
      else if (wellhead < bound.exportPressureMpa) reasons.push('pressure')
    }
    const keep = reasons.length === 0
    for (const reason of reasons) excludedBy[reason] += 1
    if (keep) passed += 1
    return {
      date: point?.date ?? null,
      gas: keep ? gas : null,
      cumulative: keep ? optionalNumber(point?.cumulative) : null,
      formation: keep ? optionalNumber(point?.formation) : null,
      wellhead: keep ? wellhead : null
    }
  })
  return {
    points: resultPoints,
    bounds: bound,
    summary: {
      total: points.length,
      passed,
      excluded: points.length - passed,
      excludedBy,
      usesReferenceValue: bound.usesReferenceValue,
      exportPressureMpa: bound.exportPressureMpa
    }
  }
}

/**
 * 原平台若在任务详情中携带了 Calc 原始请求，提取当前井当前任务的输入。
 * 允许父任务节点携带任务编号/物理井名，子级 request 携带三组输入；
 * 不将 AG 的 nodeId 或其他对象的 id 当预测任务编号。
 */
export function extractCalcRequestFromNodeResponse(response, scope, expectedTaskId = null) {
  const targetWell = String(scope?.wellName ?? '').trim()
  if (!targetWell) return null
  const seen = new Set()
  function visit(value, context = {}, depth = 0) {
    if (!value || typeof value !== 'object' || seen.has(value) || depth > 16) return null
    seen.add(value)
    if (Array.isArray(value)) {
      for (const item of value) {
        const found = visit(item, context, depth + 1)
        if (found) return found
      }
      return null
    }
    const rawWell = value.physicalWellName ?? value.wellName
    const physicalWell = rawWell && !String(rawWell).includes('单井动态预测')
      ? String(rawWell) : String(context.wellName ?? '')
    const projectId = value.projectId ?? context.projectId
    const gasReservoirId = value.gasReservoirId ?? context.gasReservoirId
    const taskId = value.dynamicPredictionId ?? value.DynamicPredictionId ?? context.dynamicPredictionId
    const taskName = value.chartWellName ?? value.predictionName ?? context.chartWellName
    if (isObject(value.reservoirInput) && isObject(value.wellInput) &&
        isObject(value.wellFlowTubeInput) &&
        physicalWell === targetWell &&
        taskId != null &&
        (expectedTaskId == null || String(taskId) === String(expectedTaskId)) &&
        (projectId == null || String(projectId) === String(scope.projectId)) &&
        (gasReservoirId == null || String(gasReservoirId) === String(scope.gasReservoirId))) {
      return {
        projectId: scope.projectId, gasReservoirId: scope.gasReservoirId,
        wellName: targetWell, dynamicPredictionId: taskId,
        ...(taskName ? { chartWellName: taskName } : {}),
        reservoirInput: cloneJson(value.reservoirInput),
        wellInput: cloneJson(value.wellInput),
        wellFlowTubeInput: cloneJson(value.wellFlowTubeInput)
      }
    }
    const next = { wellName: physicalWell, projectId, gasReservoirId,
      dynamicPredictionId: taskId, chartWellName: taskName }
    for (const [field, child] of Object.entries(value)) {
      if (['config', 'headers', 'statusText'].includes(field)) continue
      if (child && typeof child === 'object') {
        const result = visit(child, next, depth + 1)
        if (result) return result
      }
    }
    return null
  }
  return visit(isObject(response) && Object.prototype.hasOwnProperty.call(response, 'data')
    ? response.data : response)
}
