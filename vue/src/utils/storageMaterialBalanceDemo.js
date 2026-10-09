const DEMO_WELLS = Object.freeze([
  {
    wellId: -101, wellName: '演示井 A1', gasVolume: 12.5,
    samples: [
      ['2024-01-01', 31.2, 0, 0], ['2024-04-01', 30.1, 0.52, 0.04],
      ['2024-07-01', 28.9, 1.28, 0.11], ['2024-10-01', 27.5, 2.15, 0.23],
      ['2025-01-01', 26.2, 3.10, 0.36]
    ]
  },
  {
    wellId: -102, wellName: '演示井 A2', gasVolume: 8.4,
    samples: [
      ['2024-04-01', 32.0, 0, 0], ['2024-07-01', 30.8, 0.68, 0.05],
      ['2024-10-01', 29.1, 1.55, 0.14], ['2025-01-01', 28.0, 2.4, 0.25],
      ['2025-04-01', 26.8, 3.35, 0.40]
    ]
  },
  {
    wellId: -103, wellName: '演示井 A3', gasVolume: 6.1,
    samples: [
      ['2024-01-01', 30.7, 0, 0], ['2024-07-01', 29.2, 0.43, 0.03],
      ['2025-01-01', 27.2, 1.23, 0.10], ['2025-04-01', 26.0, 1.9, 0.17]
    ]
  }
])

export function storageMaterialBalanceDemoWells() {
  return DEMO_WELLS.map(well => ({
    wellId: well.wellId,
    wellName: well.wellName,
    resultId: null,
    measuredCount: well.samples.length,
    inStorage: true
  }))
}

export function storageMaterialBalanceDemoResult(selectedWellIds) {
  const selectedIds = new Set(selectedWellIds || [])
  const sources = DEMO_WELLS.filter(well => selectedIds.has(well.wellId))
  const totalVolume = sources.reduce((sum, well) => sum + well.gasVolume, 0)
  const dates = [...new Set(sources.flatMap(well => well.samples.map(sample => sample[0])))].sort()
  const rows = []
  const partialDates = []
  for (const date of dates) {
    const available = sources.filter(well => well.samples.some(sample => sample[0] === date))
    const missingWells = sources.filter(well => !available.includes(well)).map(well => well.wellName)
    const dayVolume = available.reduce((sum, well) => sum + well.gasVolume, 0)
    const samples = available.map(well => ({ well, sample: well.samples.find(item => item[0] === date) }))
    rows.push({
      date,
      pressure: samples.reduce((sum, { well, sample }) => sum + well.gasVolume / dayVolume * sample[1], 0),
      gas: samples.reduce((sum, { sample }) => sum + sample[2], 0),
      water: samples.reduce((sum, { sample }) => sum + sample[3], 0)
    })
    if (missingWells.length) partialDates.push({ date, missingWells })
  }
  return {
    demo: true,
    wells: sources.map(well => ({
      wellId: well.wellId,
      wellName: well.wellName,
      resultId: null,
      gasVolume: well.gasVolume,
      rSquared: null,
      included: true,
      weight: totalVolume ? well.gasVolume / totalVolume : null,
      reason: '演示数据',
      warning: '',
      discardedRows: 0,
      samples: well.samples.map(([date, pressure, gas, water]) => ({ date, pressure, gas, water, deleted: false }))
    })),
    rows,
    partialDates,
    sourceGasVolume: sources.length ? totalVolume : null,
    includedWellCount: sources.length,
    message: '演示数据仅用于查看多井汇总效果，不是智慧气藏实测结果。'
  }
}
