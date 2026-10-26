export const nodalHydrateComponents = Object.freeze([
  ['C1', 'CH₄'],
  ['C2', 'C₂H₆'],
  ['C3', 'C₃H₈'],
  ['IC4', 'i-C₄'],
  ['NC4', 'n-C₄'],
  ['N2', 'N₂'],
  ['O2', 'O₂'],
  ['CO2', 'CO₂'],
  ['H2S', 'H₂S']
])

export const nodalPvtCompositionFields = Object.freeze([
  ['H2S', 'hydrogenSulfide', 'H₂S'],
  ['CO2', 'carbonDioxide', 'CO₂'],
  ['N2', 'nitrogen', 'N₂']
])

export const nodalPvtCompositionKeys = Object.freeze(nodalPvtCompositionFields.map(([key]) => key))

export function nodalCompositionFromPvt(gasInput) {
  if (!gasInput) throw new Error('所选PVT缺少天然气组成')
  const composition = {}
  for (const [key, field, label] of nodalPvtCompositionFields) {
    const raw = gasInput[field]
    const value = raw == null || raw === '' ? Number.NaN : Number(raw)
    if (!Number.isFinite(value) || value < 0 || value > 100) throw new Error(`所选PVT的${label}摩尔百分数无效`)
    composition[key] = value
  }
  if (Object.values(composition).reduce((sum, value) => sum + value, 0) > 100) throw new Error('所选PVT的非烃组分合计不能超过100%')
  return composition
}

export function nodalCompositionTotal(composition = {}) {
  return nodalHydrateComponents.reduce((total, [key]) => {
    const value = composition[key]
    return total + (value == null || value === '' || !Number.isFinite(Number(value)) ? 0 : Number(value))
  }, 0)
}

export function normalizeNodalComposition(composition = {}) {
  const normalized = {}
  for (const [key, label] of nodalHydrateComponents) {
    const raw = composition[key]
    const value = raw == null || raw === '' ? 0 : Number(raw)
    if (!Number.isFinite(value) || value < 0 || value > 100) throw new Error(`${label}摩尔百分数须为0至100`)
    if (value > 0) normalized[key] = value
  }
  const total = nodalCompositionTotal(normalized)
  if (Math.abs(total - 100) > 0.01) throw new Error(`气体组成摩尔百分数合计须为100%，当前为${total.toFixed(4)}%`)
  return normalized
}

// 临时联调默认值集中在此；数据来源确定后可统一替换。
export function nodalDefaults(operationMode = 'production') {
  const injection = operationMode === 'injection'
  return {
    schemaVersion: 'nodal-input/v2',
    operationMode, coefficientId: null, pressureSourceId: null, coefficientSet: 'corrected',
    coefficientA: null, coefficientB: null,
    reservoirText: '30, 25, 20, 15', minimumPressure: 0.101325, maximumPressure: 40, samples: 80,
    wellbore: { pvtId: null, boundaryPressure: injection ? 25 : 5, depth: 2000, step: 20,
      idTubing: 100, roughness: 0.016, angle: 0, tWh: 30, tGrad: 2,
      qLiq: injection ? 0 : 1, models: [injection ? 'GAS' : 'HB'] },
    tubingCases: [{ id: 'tubing-1', name: '油管方案1', idTubing: 100 }],
    constraints: { liquidLoading: !injection, hydrate: false, erosion: true, sanding: false,
      liquidHoldupPercent: null, sandContentPercent: null, sandDensity: null, erosionLiquidDensity: null,
      surfaceTension: 30, hydrateMargin: 0, fugacityScale: 2,
      composition: { C1: 95, C2: 3, C3: 0, IC4: 0, NC4: 0, N2: 2, O2: 0, CO2: 0, H2S: 0 },
      sandProppantType: 'quartz-sand', sandMassT: null, sandDensityGCm3: 1.7,
      sandHalfLengthM: null, sandClosurePressureMpa: null, sandCriticalVelocityMS: null }
  }
}
