export const branchOverview = profiles => (profiles || []).map(profile => {
  const pressure = profile.variables?.find(item => item.variable === 'Pressure')
  const values = Array.isArray(pressure?.values) ? pressure.values : []
  const valid = values.filter(Number.isFinite)
  const first = Number.isFinite(values[0]) ? values[0] : null
  const last = Number.isFinite(values.at(-1)) ? values.at(-1) : null
  const difference = first !== null && last !== null && pressure?.unit ? first - last : null
  return {
    branch: profile.branch,
    unit: pressure?.unit || null,
    pointCount: values.length,
    first, last,
    difference: Number.isFinite(difference) ? difference : null,
    minimum: valid.length ? valid.reduce((a, b) => Math.min(a, b)) : null,
    maximum: valid.length ? valid.reduce((a, b) => Math.max(a, b)) : null,
    missing: values.length - valid.length,
    unavailable: !values.length
  }
})

export const branchOverviewCsvRows = (rows, runId, study) => [
  ['Run', 'Study', '支路', '压力单位', '压力点数', '首点压力', '末点压力', '首点减末点', '最低压力', '最高压力', '缺失压力点数'],
  ...rows.map(row => [runId, study, row.branch, row.unit, row.pointCount, row.first, row.last, row.difference, row.minimum, row.maximum, row.missing])
]
