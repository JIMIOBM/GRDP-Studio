// Only chart coordinates: coefficient values and the original platform AOF remain untouched.
export function injectionRate(a, b, delta) {
  if (![a, b, delta].every(Number.isFinite) || delta < 0 || (a === 0 && b === 0)) return null
  if (b === 0) return a > 0 ? delta / a : null
  const discriminant = a * a + 4 * b * delta
  if (discriminant < 0) return null
  const root = Math.sqrt(discriminant)
  // Positive slope branch. A<0,B>0 has a separate zero-flow equilibrium;
  // do not connect it to the finite-rate branch or silently replace A with |A|.
  const q = a >= 0 ? (delta === 0 ? 0 : 2 * delta / (a + root)) : (root - a) / (2 * b)
  return Number.isFinite(q) && q >= 0 && a + 2 * b * q >= 0 ? q : null
}

export function buildStableInjectionChart({ a, b, maximumPressure, method, potentials }) {
  if (![a, b, maximumPressure].every(Number.isFinite) || maximumPressure <= 0)
    throw new Error('注气曲线缺少有效的系数或原始地层压力')
  if (injectionRate(a, b, 1) === null && !(a > 0 && b < 0))
    throw new Error('当前系数无法生成正压差下的注气曲线')
  let grid
  if (method === 'pseudoPressure') {
    if (!Array.isArray(potentials) || potentials.length !== 37)
      throw new Error('拟压力曲线数据不完整')
    grid = potentials.map((point, index) => {
      const pressure = maximumPressure * ((index + 4) / 40)
      if (!Number.isFinite(point.value) || Math.abs(point.pressure - pressure) > 1e-7
        || (index > 0 && point.value <= potentials[index - 1].value))
        throw new Error('拟压力曲线数据无效')
      return { pressure, value: point.value }
    })
  } else {
    if (!['pressure', 'pressureSquared'].includes(method)) throw new Error('未知压力方法')
    grid = Array.from({ length: 37 }, (_, index) => {
      const pressure = maximumPressure * ((index + 4) / 40)
      return { pressure, value: method === 'pressure' ? pressure : pressure ** 2 }
    })
  }
  return Array.from({ length: 10 }, (_, index) => {
    const start = index * 4
    const reservoir = grid[start]
    const points = index === 9 ? [{ x: 0, y: maximumPressure }] : grid.slice(start)
      .map(point => ({ x: injectionRate(a, b, point.value - reservoir.value), y: point.pressure }))
      .filter(point => point.x !== null)
    return { curveNumber: index + 1, points,
      // Render as a separate point, never a fictitious connecting segment.
      isolatedOrigin: a < 0 && b > 0 && index < 9 ? { x: 0, y: reservoir.pressure } : null }
  })
}
