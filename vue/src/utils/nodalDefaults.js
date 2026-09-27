// 临时联调默认值集中在此；数据来源确定后可统一替换。
export function nodalDefaults(operationMode = 'production') {
  const injection = operationMode === 'injection'
  return {
    operationMode, coefficientId: null, pressureSourceId: null, coefficientSet: 'corrected',
    coefficientA: null, coefficientB: null,
    reservoirText: '30, 25, 20, 15', minimumPressure: 0.101325, maximumPressure: 40, samples: 80,
    wellbore: { pvtId: null, boundaryPressure: injection ? 25 : 5, depth: 2000, step: 20,
      idTubing: 100, roughness: 0.016, angle: 0, tWh: 30, tGrad: 2,
      qLiq: injection ? 0 : 1, models: [injection ? 'GAS' : 'HB'] },
    constraints: { liquidLoading: !injection, hydrate: false, erosion: true, sanding: false,
      liquidHoldupPercent: null, sandContentPercent: null, sandDensity: null, erosionLiquidDensity: null,
      surfaceTension: 30, hydrateMargin: 0, fugacityScale: 2 },
    compositionText: '{"C1": 95, "C2": 3, "N2": 2}'
  }
}
