export const temperatureCalculationKinds = Object.freeze({
  properties: 'inner',
  layers: 'wall',
  environment: 'outer',
  settings: 'overall',
})

function requireKind(kind) {
  if (!Object.values(temperatureCalculationKinds).includes(kind)) {
    throw new Error(`未知温度计算类型：${kind}`)
  }
}

export function temperatureResultTabs(kind) {
  requireKind(kind)
  return [['input', '数据列表'], ['table', '结果分析']]
}

function pick(source, keys) {
  return Object.fromEntries(keys.map(key => [key, source?.[key] ?? null]))
}

// Keep independent calculations fresh when another section's inputs change.
export function temperatureCoefficientStamp(kind, config, diameterMm, topologyRevision, pvtSourceRevision = null) {
  requireKind(kind)
  const effectiveDiameter = kind === 'inner' && Object.hasOwn(config || {}, 'innerDiameterMm') ? config.innerDiameterMm : diameterMm
  const stamp = { kind, diameterMm: effectiveDiameter ?? null, topologyRevision: topologyRevision ?? null }
  const layers = Array.isArray(config?.layers) ? config.layers : []

  if (kind === 'inner' || kind === 'overall') {
    stamp.inner = pick(config, ['actualFlowM3s', 'gasConductivityWmK'])
    stamp.properties = { source: 'pipeline-pvt-model-v1', ...pick(config, ['pvtId', 'propertyPressureMpa', 'propertyTemperatureC']), sourceRevision: pvtSourceRevision }
  }
  if (kind === 'wall' || kind === 'overall') {
    stamp.wall = layers.map(layer => pick(layer, ['name', 'thicknessMm', 'conductivityWmK']))
  }
  if (kind === 'outer' || kind === 'overall') {
    stamp.outer = {
      ...pick(config, ['externalMethod', 'burialDepthM', 'soilConductivityWmK']),
      layers: layers.map(layer => pick(layer, ['name', 'thicknessMm'])),
    }
    if (config?.externalMethod === 'surface-resistance') {
      stamp.outer.surfaceCoefficientWm2K = config.surfaceCoefficientWm2K ?? null
    }
  }
  return JSON.stringify(stamp)
}
