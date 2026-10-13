// COMPDAT item 8 units, Reference Manual PDF497; compare actual points without interpolating.
export const completionFactorUnit = unitSystem => ({ FIELD: 'cP.rb/day/psi', METRIC: 'cP.rm³/day/bar', LAB: 'cP.rcc/hr/atm', 'PVT-M': 'cP.rm³/day/atm' }[unitSystem] || null)
export const completionFactorResponse = (current, baseline, well) => {
  const unavailable = { available: false, rows: [], changedCount: 0, unmatchedCount: 0 }
  if (!Array.isArray(current?.series) || !Array.isArray(baseline?.series) || typeof well !== 'string') return unavailable
  const rows = [], missingIndicators = []
  let changedCount = 0, unmatchedCount = 0
  for (const keyword of ['WOPR', 'WWPR', 'WGPR', 'WBHP', 'WOPT']) {
    const a = current.series.filter(s => s.keyword === keyword && s.objectName === well)
    const b = baseline.series.filter(s => s.keyword === keyword && s.objectName === well)
    if (!a.length && !b.length) { missingIndicators.push(keyword); continue }
    if (a.length !== 1 || b.length !== 1 || !a[0].unit || a[0].unit !== b[0].unit) return unavailable
    const valid = series => Array.isArray(series.points) && series.points.length > 0 && series.points.every((p, i) =>
      Number.isFinite(p.timeDays) && p.timeDays >= 0 && Number.isFinite(p.value) && (!i || p.timeDays > series.points[i - 1].timeDays))
    if (!valid(a[0]) || !valid(b[0])) return unavailable
    const after = new Map(a[0].points.map(p => [p.timeDays, p.value])), before = new Map(b[0].points.map(p => [p.timeDays, p.value]))
    for (const timeDays of [...new Set([...after.keys(), ...before.keys()])].sort((x, y) => x - y)) {
      const original = before.get(timeDays) ?? null, value = after.get(timeDays) ?? null
      const difference = original === null || value === null ? null : value - original
      if (difference === null) unmatchedCount++
      else if (Math.abs(difference) > Math.max(1e-6, Math.abs(original) * 1e-6)) changedCount++
      rows.push({ keyword, well, timeDays, unit: a[0].unit, baseline: original, current: value, difference })
    }
  }
  return rows.length ? { available: true, rows, changedCount, unmatchedCount, missingIndicators } : unavailable
}
