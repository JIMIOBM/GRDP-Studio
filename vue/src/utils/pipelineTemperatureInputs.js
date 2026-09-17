const positive = value => typeof value === 'number' && Number.isFinite(value) && value > 0

/** Geometry is displayed from the saved pipe and the current material thicknesses, without modifying either. */
export function temperatureLayerInputRows(graph, settings) {
  return (graph?.edges || []).flatMap((edge, pipeIndex) => {
    const layers = settings?.segments?.find(config => config.edgeId === edge.id)?.layers || []
    let diameter = positive(edge.parameters?.diameterMm) ? edge.parameters.diameterMm : null
    return (layers.length ? layers : [null]).map((layer, layerIndex) => {
      const innerDiameterMm = diameter
      const next = diameter != null && positive(layer?.thicknessMm) ? diameter + 2 * layer.thicknessMm : null
      diameter = positive(next) ? next : null
      return { edgeId: edge.id, pipeName: edge.name, pipeIndex, layerIndex,
        name: layer?.name ?? '', thicknessMm: layer?.thicknessMm ?? null, conductivityWmK: layer?.conductivityWmK ?? null,
        innerDiameterMm, outerDiameterMm: diameter }
    })
  })
}

export function temperatureOuterGeometryRows(graph, settings) {
  const layers = temperatureLayerInputRows(graph, settings)
  return (graph?.edges || []).map(edge => {
    const last = layers.filter(row => row.edgeId === edge.id).at(-1)
    const config = settings?.segments?.find(config => config.edgeId === edge.id)
    const diameter = positive(last?.outerDiameterMm) ? last.outerDiameterMm / 1000 : null
    const depth = positive(config?.burialDepthM) ? config.burialDepthM : null
    const valid = diameter != null && depth != null && depth > diameter / 2
    return { pipeName: edge.name, outerDiameterMm: last?.outerDiameterMm ?? null,
      depthRatio: diameter != null && depth != null ? depth / diameter : null,
      y0M: valid ? Math.sqrt(depth * depth - diameter * diameter / 4) : null,
      acoshRatio: valid ? Math.acosh(2 * depth / diameter) : null }
  })
}

/** PVT-derived properties never fall back to manual values; conductivity is an explicitly given input. */
export function temperatureEffectiveInputs(config, entry, { preview = null, coupled = false, current = false } = {}) {
  const used = current && entry?.pvtProperties ? entry.usedInput : null
  const properties = used || (coupled ? null : preview)
  return {
    densityKgM3: properties?.densityKgM3 ?? null,
    cpJkgK: properties?.cpJkgK ?? null,
    actualFlowM3s: coupled ? used?.actualFlowM3s ?? null : config?.actualFlowM3s ?? null,
    viscosityMpaS: properties?.viscosityMpaS ?? null,
    gasConductivityWmK: used ? used.gasConductivityWmK ?? null : config?.gasConductivityWmK ?? null,
    propertyPressureMpa: coupled ? used?.propertyPressureMpa ?? null : config?.propertyPressureMpa ?? null,
    propertyTemperatureC: coupled ? used?.propertyTemperatureC ?? null : config?.propertyTemperatureC ?? null
  }
}

const viscosityInputs = [
  ['pressureMpa', '物性计算压力', 'MPa（绝压）', true],
  ['temperatureC', '物性计算温度', '℃', true],
  ['gasGravity', '气体相对密度 γg', '—'],
  ['pseudoCriticalTemperatureK', '拟临界温度 Tpc', 'K'],
  ['pseudoCriticalPressureMpa', '拟临界压力 Ppc', 'MPa'],
  ['reducedTemperature', '拟对比温度 Tpr', '—'],
  ['reducedPressure', '拟对比压力 Ppr', '—'],
  ['lowPressureViscosityMpaS', '低压黏度 μ₁', 'mPa·s'],
  ['nitrogenCorrectionMpaS', '氮气黏度修正量', 'mPa·s'],
  ['carbonDioxideCorrectionMpaS', '二氧化碳黏度修正量', 'mPa·s'],
  ['hydrogenSulfideCorrectionMpaS', '硫化氢黏度修正量', 'mPa·s'],
  ['correctedLowPressureViscosityMpaS', '修正后低压黏度', 'mPa·s'],
  ['pressurePolynomial', '压力修正多项式 F', '—'],
  ['viscosityMpaS', '动力黏度 μg', 'mPa·s']
]

/** Only actual provider snapshots or current previews contain Standing calculation details. */
export function temperatureViscosityInputRows(graph, detailsByEdge = {}) {
  return (graph?.edges || []).flatMap(edge => {
    const detail = detailsByEdge[edge.id]
    return viscosityInputs.map(([key, name, unit, point]) => ({
      pipeName: edge.name, pvtName: detail?.pvtName || '—', name,
      value: (point ? detail?.[key] : detail?.viscosityCalculation?.[key]) ?? null,
      unit, source: 'Standing（资料原式）'
    }))
  })
}
