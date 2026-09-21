export async function firstPvtSource (request, context, pvtId = null) {
  const response = await request.get('/wellbore/pvt/source', {
    params: { ...context, ...(pvtId ? { pvtId: Number(pvtId) } : {}) }
  })
  return response?.data?.data ?? response?.data ?? response
}

export function pvtComposition (gas) {
  if (!gas) throw new Error('当前井第一条PVT缺少天然气输入')
  const values = { H2S: gas.hydrogenSulfide, CO2: gas.carbonDioxide, N2: gas.nitrogen }
  for (const value of Object.values(values)) {
    if (value == null || value === '' || !Number.isFinite(Number(value)) || Number(value) < 0 || Number(value) > 100) {
      throw new Error('第一条PVT的非烃含量无效，请先补齐PVT性质')
    }
  }
  if (Object.values(values).reduce((sum, value) => sum + Number(value), 0) > 100) {
    throw new Error('第一条PVT的非烃含量总和不能超过100%')
  }
  // 未提供的烃组成保持空缺，由用户补充真实组成。
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [key, Number(value)]))
}

export async function waterProperties (request, context, pressureMpa, temperatureC, pvtId = null) {
  const response = await request.post('/wellbore/pvt/water-properties', {
    ...context,
    ...(pvtId ? { pvtId: Number(pvtId) } : {}),
    pressureMpa: Number(pressureMpa),
    temperatureC: Number(temperatureC)
  }, { timeout: 600000, headers: { 'Process-Env': 'prod' } })
  return response?.data?.data ?? response?.data ?? response
}

// 有有效边界温压时同步评价水密度和黏度；井底温压尚未填写时先加载PVT身份及气体比重。
export async function selectedPvtProperties (request, context, pvtId, pressureMpa, temperatureC) {
  const validState = pressureMpa !== null && pressureMpa !== '' && Number.isFinite(Number(pressureMpa)) &&
    temperatureC !== null && temperatureC !== '' && Number.isFinite(Number(temperatureC))
  if (validState) {
    return waterProperties(request, context, pressureMpa, temperatureC, pvtId)
  }
  const source = await firstPvtSource(request, context, pvtId)
  return {
    pvtId: source.pvtId,
    pvtSnapshot: source.pvtSnapshot,
    gammaG: source.gasInput?.specificGravity ?? null,
    rhoL: null,
    muL: null
  }
}
