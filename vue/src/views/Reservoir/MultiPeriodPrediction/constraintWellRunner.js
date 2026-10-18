/**
 * 库级多周期预测 - 井筒约束计算适配器。
 * 页面只接受原截图的库级输入。单井出砂/冲蚀/积液数据来自各井已保存的计算记录；
 * 不伪造 PVT ID、油管内径、含砂率、日产水量，也不把井组目标产量当作每井气量。
 *
 * 真实 API 签名：出砂/积液使用位置参数；冲蚀使用 context 对象。
 * 库级结果：积液为各井最低流量之和；出砂/冲蚀为各井最高流量之和。
 * 只有当前井组所有必要的单井结果均有效，才允许向方案生成发布约束。
 * 不拿井组目标产量充当单井实际产量，也不伪造缺失的井筒参数。
 */

const rateUnit = '10⁴m³/d'
const definitions = Object.freeze({
  erosion: { name: '冲蚀临界气量', symbol: 'qₑ' },
  sand: { name: '出砂临界气量', symbol: 'qₛ' },
  liquid: { name: '积液临界气量', symbol: 'qₘᵢₙ' }
})

export function wellNameList(raw) {
  const payload = raw?.data ?? raw
  const list = Array.isArray(payload) ? payload :
    [payload?.data, payload?.wells, payload?.records, payload?.rows, payload?.list, payload?.items,
      payload?.data?.wells, payload?.data?.records, payload?.data?.rows].find(Array.isArray)
  if (!Array.isArray(list)) throw new Error('当前库的井列表响应不是数组，请检查 storageCatalogApi.wells 的返回结构')
  return [...new Set(list
    .map(value => typeof value === 'string' ? value : value?.wellName)
    .filter(value => typeof value === 'string' && value.trim())
    .map(value => value.trim()))]
}

export async function loadExistingStorageWells({ catalogApi, projectId, gasReservoirId, storageId }) {
  if (typeof catalogApi?.wells !== 'function') {
    throw new Error('缺少当前库井列表接口 storageCatalogApi.wells')
  }
  if (![projectId, gasReservoirId, storageId].every(id => String(id ?? '').trim())) {
    throw new Error('缺少当前库的项目、气藏或储气库标识')
  }
  const result = await catalogApi.wells(storageId, projectId, gasReservoirId)
  const names = wellNameList(result)
  if (!names.length) throw new Error('当前库没有已归属的井，不能执行单井约束计算')
  return names
}

export function numeric(value, label, { allowZero = false, celsius = false } = {}) {
  if (value === null || value === undefined || String(value).trim() === '') {
    throw new Error(`缺少已保存的单井参数：${label}`)
  }
  const result = Number(value)
  if (!Number.isFinite(result) || (celsius ? result <= -273.15 : (allowZero ? result < 0 : result <= 0))) {
    throw new Error(`单井参数“${label}”不合法`)
  }
  return result
}

function getRecordArray(raw) {
  const payload = raw?.data ?? raw
  const rows = Array.isArray(payload) ? payload :
    [payload?.data, payload?.records, payload?.rows, payload?.list, payload?.items,
      payload?.page?.records, payload?.content, payload?.data?.records, payload?.data?.rows].find(Array.isArray)
  return Array.isArray(rows) ? rows : []
}

function recordOrder(row) {
  const date = Date.parse(row?.createdAt || row?.createTime || row?.calculatedAt || '')
  const count = Number(row?.calculationNo ?? row?.calculation_no ?? row?.id)
  return [Number.isFinite(date) ? date : 0, Number.isFinite(count) ? count : 0]
}

function inputObject(value) {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed
    } catch { /* 具体解析失败原因由调用者提示 */ }
  }
  return null
}

// 冲蚀 records API 接受单个 context 对象；出砂、积液 records API 接受位置参数。
// 这里按已上传的三个 API 源码做精确区分，不能用同一套参数去调所有接口。
export function historyRecordCalls(api, scope, moduleName) {
  if (typeof api?.list !== 'function' || typeof api?.detail !== 'function') {
    throw new Error(`${moduleName}模块缺少 list/detail 接口，无法取得单井已保存的计算资料`)
  }
  if (!scope || !Number.isSafeInteger(Number(scope.projectId)) || Number(scope.projectId) <= 0 ||
      !Number.isSafeInteger(Number(scope.gasReservoirId)) || Number(scope.gasReservoirId) <= 0 ||
      typeof scope.wellName !== 'string' || !scope.wellName.trim()) {
    throw new Error(`${moduleName}模块缺少有效的项目、气藏或井名，不能查询历史数据`)
  }
  const p = Number(scope.projectId)
  const g = Number(scope.gasReservoirId)
  const wellName = scope.wellName.trim()
  if (moduleName === '冲蚀') {
    const context = { projectId: p, gasReservoirId: g, wellName }
    return {
      list: () => api.list(context),
      detail: id => api.detail(id, context)
    }
  }
  return {
    list: () => api.list(p, g, wellName),
    detail: id => api.detail(id, p, g, wellName)
  }
}

/** 取当前井该模块已保存的最新输入；没有历史记录时，不猜测额外参数。 */
export async function loadSavedWellInput(api, scope, moduleName) {
  const recordsApi = historyRecordCalls(api, scope, moduleName)
  const records = getRecordArray(await recordsApi.list())
    .filter(row => !row?.wellName || String(row.wellName).trim() === scope.wellName)
  if (!records.length) throw new Error(`${moduleName}没有该井的已保存计算记录`)
  records.sort((a, b) => {
    const ka = recordOrder(a), kb = recordOrder(b)
    return kb[0] - ka[0] || kb[1] - ka[1]
  })
  const latest = records[0]
  const recordId = latest.id ?? latest.recordId ?? latest.calculationId
  if (recordId == null || String(recordId).trim() === '') {
    throw new Error(`${moduleName}最近一次计算记录没有 ID`)
  }
  const response = await recordsApi.detail(recordId)
  const payload = response?.data ?? response
  const record = payload?.record ?? payload
  if (record?.wellName && String(record.wellName).trim() !== scope.wellName) {
    throw new Error(`${moduleName}详情属于其它井，已拒绝跨井使用`)
  }
  const extracted = inputObject(record?.input_json ?? record?.inputJson ?? record?.input
    ?? record?.requestJson ?? record?.request ?? record?.calculationInput
    ?? record?.requestPayload ?? record?.inputParams)
  // 部分历史接口的详情可能直接是单井参数对象，而没有 input_json 包裹。
  // 只在详情本身明确含有已知单井参数时才允许使用，绝不把列表的摘要充当计算输入。
  const inputKeys = ['qg', 'qw', 'pressureMpa', 'temperatureC', 'tubingIdMm',
    'tubingInnerDiameterMm', 'pvtId', 'proppantType', 'actualRate1e4M3d']
  const old = extracted || (inputKeys.some(key => record?.[key] !== undefined) ? record : null)
  if (!old) throw new Error(`${moduleName}详情缺少可解析的已保存输入参数；请提供该井记录详情的脱敏响应以核对后端字段`)
  if (old.wellName && String(old.wellName).trim() !== scope.wellName) {
    throw new Error(`${moduleName}保存的输入属于其它井，已拒绝跨井使用`)
  }
  return old
}

function extractThreshold(raw, moduleKey) {
  const payload = raw?.data ?? raw
  const result = payload?.result ?? payload?.data?.result ?? payload
  if (!result || typeof result !== 'object' || result.success === false ||
      (Number.isInteger(result.code) && result.code >= 400)) {
    throw new Error(result?.reason || result?.msg || result?.message ||
      `${definitions[moduleKey].name}接口未返回成功结果`)
  }
  // 只接受 API 既有字段、以及明确注明为 10^4 m3/d 的字段，不猜单位转换。
  const rawRate = result.criticalGasRate ?? result.criticalGasRate1e4M3d ??
    // 出砂和积液接口均可能返回 criticalRate1e4M3d（单位：10⁴m³/d）。
    (moduleKey === 'sand' || moduleKey === 'liquid' ? result.criticalRate1e4M3d : undefined) ??
    (moduleKey === 'liquid' ? (result.qCritical ?? result.qCritical1e4M3d) : undefined)
  if (rawRate == null || rawRate === '') {
    const keys = Object.keys(result).filter(key => !/token|secret|password/i.test(key))
    throw new Error(`${definitions[moduleKey].name}响应缺少已识别的临界气量字段；返回字段：${keys.join(', ') || '(空)'}`)
  }
  return numeric(rawRate, `${definitions[moduleKey].name}（接口返回值）`,
    { allowZero: moduleKey === 'liquid' })
}

function calculateErrorMessage(error) {
  if (!error) return ''
  // Axios 400/500 的业务原因通常在 response.data，而不在 Error.message。
  const remote = error.response?.data
  const detail = [remote?.msg, remote?.message, remote?.detail,
    remote?.error, error.message]
    .find(value => typeof value === 'string' && value.trim())
  const status = Number(error.response?.status)
  return `${Number.isFinite(status) && status >= 400 ? `HTTP ${status}：` : ''}` +
    (detail || String(error))
}

export function resultItem(moduleKey, value, error) {
  const kind = definitions[moduleKey]
  if (!kind) throw new Error(`未知约束类别 ${moduleKey}`)
  return {
    key: moduleKey,
    name: kind.name, formula: kind.symbol, unit: rateUnit,
    available: Number.isFinite(value) && (moduleKey === 'liquid' ? value >= 0 : value > 0) && !error,
    value: Number.isFinite(value) && (moduleKey === 'liquid' ? value >= 0 : value > 0) && !error
      ? value : null,
    reason: error ? calculateErrorMessage(error) : '',
    source: error ? '未计算成功' : '单井真实计算接口'
  }
}

export async function calculateErosionForWell(api, scope) {
  const old = await loadSavedWellInput(api, scope, '冲蚀')
  const pvtId = numeric(old.pvtId, 'PVT方案ID')
  const pressureMpa = numeric(old.pressureMpa, '井筒计算压力')
  const temperatureC = numeric(old.temperatureC, '井筒计算温度', { celsius: true })
  if (typeof api?.properties !== 'function' || typeof api?.calculate !== 'function') {
    throw new Error('冲蚀模块缺少 properties/calculate 接口')
  }
  const pvtResponse = await api.properties({ ...scope, pvtId, pressureMpa, temperatureC })
  const pvtPayload = pvtResponse?.data ?? pvtResponse
  const pvt = pvtPayload?.properties ?? pvtPayload
  if (!pvt || typeof pvt !== 'object') throw new Error('冲蚀 PVT 接口没有返回可用的物性参数')
  const input = {
    ...old, ...scope, pvtId, pvtSnapshot: pvt.pvtSnapshot ?? old.pvtSnapshot ?? null,
    pressureMpa, temperatureC,
    actualGasRate1e4M3d: numeric(old.actualGasRate1e4M3d, '该井实际气量', { allowZero: true }),
    tubingInnerDiameterMm: numeric(old.tubingInnerDiameterMm, '油管内径'),
    gasDensityKgM3: numeric(pvt.gasDensityKgM3, 'PVT 气体密度'),
    liquidDensityKgM3: numeric(pvt.liquidDensityKgM3, 'PVT 液体密度'),
    gasVolumeFactor: numeric(pvt.gasVolumeFactor, 'PVT 体积系数'),
    liquidHoldupPercent: numeric(old.liquidHoldupPercent, '持液率', { allowZero: true }),
    sandContentPercent: numeric(old.sandContentPercent, '含砂率', { allowZero: true }),
    sandDensityKgM3: numeric(old.sandDensityKgM3, '砂粒密度')
  }
  return extractThreshold(await api.calculate(input), 'erosion')
}

export async function calculateSandForWell(api, scope) {
  const old = await loadSavedWellInput(api, scope, '出砂')
  if (typeof api?.calculate !== 'function') throw new Error('出砂模块缺少 calculate 接口')
  if (!old.proppantType || !String(old.proppantType).trim()) throw new Error('出砂记录缺少支撑剂类型')
  const input = {
    ...old, ...scope, proppantType: old.proppantType,
    massT: numeric(old.massT, '支撑剂质量'),
    densityGCm3: old.densityGCm3 == null ? null : numeric(old.densityGCm3, '堆积密度'),
    halfLengthM: numeric(old.halfLengthM, '裂缝半长'),
    closurePressureMpa: numeric(old.closurePressureMpa, '闭合压力'),
    criticalVelocityOverrideMS: old.criticalVelocityOverrideMS == null || old.criticalVelocityOverrideMS === ''
      ? null : numeric(old.criticalVelocityOverrideMS, '人工临界流速'),
    actualRate1e4M3d: numeric(old.actualRate1e4M3d, '该井实际气量', { allowZero: true })
  }
  return extractThreshold(await api.calculate(input), 'sand')
}

/**
 * 积液沿用单井历史中真正保存的井筒参数，不允许用库级目标产量、
 * 原始地层压力或虚构的油管内径/日产水量代替单井数据。
 * 如果后端 DTO 与下述已上传字段不同，请用实际 Request Payload 校对映射。
 */
export async function calculateLiquidForWell(api, scope, form) {
  if (typeof api?.calculate !== 'function') throw new Error('积液模块缺少 calculate 接口')
  const old = await loadSavedWellInput(api, scope, '积液')
  const first = (...values) => values.find(value => value !== null && value !== undefined && value !== '')
  const input = {
    ...old,
    ...scope,
    qg: numeric(first(old.qg, old.actualGasRate1e4M3d, old.gasRate1e4M3d),
      '该井实际气量', { allowZero: true }),
    qw: numeric(first(old.qw, old.waterRateM3d), '该井日产水量', { allowZero: true }),
    pressureMpa: numeric(first(old.pressureMpa, old.wellheadPressureMpa), '该井井筒计算压力'),
    temperatureC: numeric(first(old.temperatureC, old.wellheadTemperatureC),
      '该井井筒计算温度', { celsius: true }),
    gasSpecificGravity: numeric(form?.gasProperties?.dless, '天然气比重'),
    liquidDensityKgM3: numeric(old.liquidDensityKgM3, '该井液体密度'),
    surfaceTensionMnM: numeric(old.surfaceTensionMnM, '该井界面张力'),
    tubingIdMm: numeric(first(old.tubingIdMm, old.tubingInnerDiameterMm), '该井油管内径')
  }
  return extractThreshold(await api.calculate(input), 'liquid')
}

export async function calculateOneWell({ scope, form, erosionApi, sandProductionApi, liquidLoadingApi }) {
  const calls = [
    ['erosion', () => calculateErosionForWell(erosionApi, scope)],
    ['sand', () => calculateSandForWell(sandProductionApi, scope)],
    ['liquid', () => calculateLiquidForWell(liquidLoadingApi, scope, form)]
  ]
  const settled = await Promise.allSettled(calls.map(([, run]) => run()))
  const summary = { wellName: scope.wellName }
  calls.forEach(([key], idx) => {
    const result = settled[idx]
    summary[key] = result.status === 'fulfilled'
      ? resultItem(key, result.value) : resultItem(key, null, result.reason)
  })
  summary.complete = calls.every(([key]) => summary[key].available)
  return summary
}

/** 有限并行防止大型储气库一次向单井服务发出过多请求。 */
export async function calculateStorageConstraints({
  wellNames, projectId, gasReservoirId, form,
  erosionApi, sandProductionApi, liquidLoadingApi,
  concurrency = 3, onProgress = () => {}, stillCurrent = () => true
}) {
  const names = [...new Set(wellNames)]
  if (!names.length) throw new Error('当前库没有可以参与约束计算的井')
  const output = new Array(names.length)
  let next = 0, completed = 0
  const workers = Math.min(concurrency, names.length)
  await Promise.all(Array.from({ length: workers }, async () => {
    while (stillCurrent() && next < names.length) {
      const index = next++
      const scope = { projectId: Number(projectId), gasReservoirId: Number(gasReservoirId), wellName: names[index] }
      output[index] = await calculateOneWell({
        scope, form, erosionApi, sandProductionApi, liquidLoadingApi
      })
      completed++
      if (stillCurrent()) onProgress(completed, names.length)
    }
  }))
  if (!stillCurrent()) return []
  return output
}


/**
 * 按当前参与井集合，生成单值边界。
 * 假设各井气量可独立分配：Σq_liquid,min ≤ Q ≤ Σmin(q_sand,max,q_erosion,max)。
 * 三类分项亦各自聚合成单值；外输压力为用户设置的固定值，不累加。
 * 注意：没有逐井分配方案时，该汇总仅代表流量的必要可行区间，
 * 不能声称后端 Calc 自带这些约束。
 */
export function aggregateStorageBoundaries(results, form, selectedWellNames = null) {
  if (!Array.isArray(results) || !results.length) throw new Error('当前库没有已计算的单井约束')
  const lookup = new Map()
  for (const row of results) {
    const name = String(row?.wellName || '').trim()
    if (!name || lookup.has(name)) throw new Error('单井约束列表存在重复或无效井名')
    lookup.set(name, row)
  }
  const selected = selectedWellNames === null
    ? [...lookup.keys()]
    : [...new Set((Array.isArray(selectedWellNames) ? selectedWellNames : [])
      .filter(item => typeof item === 'string').map(item => item.trim()).filter(Boolean))]
  if (!selected.length) throw new Error('没有选中的井，无法生成井组边界')
  const rows = selected.map(name => {
    const row = lookup.get(name)
    if (!row) throw new Error(`所选井 ${name} 没有已计算的约束，请先计算该井`)
    for (const key of ['liquid', 'erosion', 'sand']) {
      if (!row[key]?.available || !Number.isFinite(row[key].value) ||
          (key === 'liquid' ? row[key].value < 0 : row[key].value <= 0)) {
        throw new Error(`井 ${name} 的${definitions[key].name}尚未计算成功：${row[key]?.reason || '无有效值'}`)
      }
    }
    return row
  })
  const total = key => rows.reduce((acc, row) => acc + row[key].value, 0)
  const liquidMin = total('liquid')
  const erosionMax = total('erosion')
  const sandMax = total('sand')
  // 不能简单取 min(Σ出砂,Σ冲蚀)：不同井的最紧约束可能不同。
  const productionMax = rows.reduce((acc, row) => acc +
    Math.min(row.erosion.value, row.sand.value), 0)
  const exportPressure = numeric(form?.otherData?.wellheadExportPressure,
    '井口外输压力', { allowZero: true })
  if (![liquidMin, erosionMax, sandMax, productionMax].every(Number.isFinite)) {
    throw new Error('井组约束汇总溢出，请检查临界气量及单位')
  }
  if (liquidMin > productionMax + 1e-8) {
    throw new Error(`当前选井的产量边界不可行：积液下限 ${liquidMin.toFixed(4)} 大于出砂/冲蚀合并上限 ${productionMax.toFixed(4)}（10⁴m³/d）`)
  }
  return {
    liquidMin, erosionMax, sandMax, exportPressure, productionMax,
    wellNames: selected,
    wellCount: selected.length,
    gasRateUnit: '10⁴m³/d', pressureUnit: 'MPa',
    aggregation: 'sum-per-well', complete: true,
    source: '单井出砂、冲蚀、积液计算结果 + 约束条件表单'
  }
}

/** 在发送原平台 Calc 前作本地约束校验，不伪称 Calc 已支持单井限额 DTO。 */
export function validatePlanBoundaries({ targetProduction, exportPressure }, boundaries) {
  if (!boundaries?.complete) throw new Error('没有可应用的完整井组边界')
  const target = numeric(targetProduction, '井组目标产量', { allowZero: true })
  const pressure = numeric(exportPressure, '井口外输压力', { allowZero: true })
  const tol = 1e-8
  const warnings = []
  if (target < boundaries.liquidMin - tol) {
    warnings.push(`目标产量 ${target} 低于积液最低产量 ${boundaries.liquidMin.toFixed(4)}（10⁴m³/d），可能发生积液`)
  }
  if (Math.abs(pressure - boundaries.exportPressure) > tol) {
    warnings.push(`外输压力与约束条件设定不同（约束 ${boundaries.exportPressure} MPa，当前 ${pressure} MPa）`)
  }
  return warnings
}
