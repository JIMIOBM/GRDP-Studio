// 仅格式化只读展示；不改写输入、权重或曲线坐标。极小非零值不能舍入成零。
export function formatSourceNumber(value) {
  if (value == null) return '—'
  if (typeof value !== 'number') return value
  if (!Number.isFinite(value)) return '—'
  if (value !== 0 && Math.abs(value) < 1e-8) return value.toExponential(4)
  return value.toLocaleString('zh-CN', { useGrouping: false, maximumFractionDigits: 8 })
}

// 将库级只读来源转换为共享物质平衡组件的数据结构，不复制图表与参数栏实现。
export function toSharedMaterialBalanceResult(detail) {
  if (!detail) return null
  const finite = value => typeof value === 'number' && Number.isFinite(value)
  const output = detail.output || {}
  const validRegression = Number(output.reliability) > 0 && finite(output.gasVolume) && output.gasVolume > 0
  const points = (detail.resultRows || []).filter(row => finite(row.pressure) && row.pressure > 0 && finite(row.gas) && row.gas >= 0)
    .map(row => ({ xValue: row.gas, yValue: row.pressure, isDeleted: row.deleted === true }))
  const line = validRegression ? (detail.regressionLine || [])
    .filter(row => finite(row.pressure) && row.pressure > 0 && finite(row.gas) && row.gas >= 0)
    .map(row => ({ xValue: row.gas, yValue: row.pressure })) : []
  return {
    name: '根据实测静压',
    input: { ...detail.parameters, gasReservoirType: 1 },
    inputItems: detail.inputRows || [],
    validRegression,
    output: {
      originalGasVolume: output.gasVolume,
      intercept: output.intercept,
      gradient: output.gradient,
      rsquared: output.rSquared,
      reliabilityDescription: output.reliabilityDescription,
      dynamicOriginalGasInplaceMethodDescription: '定容气藏物质平衡方程-根据实测静压'
    },
    chartItems: [{ name: '数据点(MPa)', data: points },
      ...(line.length >= 2 ? [{ name: '回归线(MPa)', data: line }] : [])]
  }
}
