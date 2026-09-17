export async function firstPvtSource (request, context) {
  const response = await request.get('/wellbore/pvt/source', { params: { ...context } })
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

export async function waterProperties (request, context, pressureMpa, temperatureC) {
  const response = await request.post('/wellbore/pvt/water-properties', {
    ...context, pressureMpa: Number(pressureMpa), temperatureC: Number(temperatureC)
  }, { timeout: 600000, headers: { 'Process-Env': 'prod' } })
  return response?.data?.data ?? response?.data ?? response
}
