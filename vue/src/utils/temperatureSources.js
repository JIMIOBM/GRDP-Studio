// Source normalization is kept separate from the panel so field selection and units can be checked.
export const unpack = response => response?.data?.data ?? response?.data ?? response ?? {}
export const rowsOf = response => {
  const value = unpack(response)
  const rows = Array.isArray(value) ? value : value.items ?? value.rows ?? value.datas ?? []
  const fields = value.fields ?? []
  return rows.map(row => Array.isArray(row)
    ? Object.fromEntries(fields.map((field, index) => [field.name, row[index]])) : row)
}
export const read = (row, ...keys) => {
  for (const key of keys) if (row?.[key] !== null && row?.[key] !== undefined && row?.[key] !== '') return row[key]
  return null
}
export const numberOf = value => value !== null && value !== '' && value !== undefined && Number.isFinite(Number(value)) ? Number(value) : null
export const wellRows = (rows, wellName, scoped = false) => rows.filter(row => {
  const name = read(row, 'wellName', 'well_name', '井名')
  return name === null ? scoped : String(name).trim() === wellName.trim()
})
export const latestRow = rows => [...rows].sort((a, b) =>
  String(read(b, 'date', 'productionDate', 'production_date') ?? '').localeCompare(String(read(a, 'date', 'productionDate', 'production_date') ?? '')))[0]

export function deviationValues(response, wellName) {
  const rows = wellRows(rowsOf(response), wellName, true).map(row => ({
    depth: numberOf(read(row, 'measuredDepth', 'measured_depth')),
    angle: numberOf(read(row, 'inclination', '井斜角'))
  })).filter(row => row.depth !== null && row.depth > 0)
  const deepest = rows.sort((a, b) => b.depth - a.depth)[0]
  if (!deepest || deepest.angle === null) throw new Error('井斜数据缺少测深或井斜角')
  return deepest
}

export function tubingRows(response, wellName) {
  const rows = wellRows(rowsOf(response), wellName, true)
    .filter(row => /油管|tubing/i.test(String(read(row, 'type', 'casing_type') ?? '')))
  const latest = latestRow(rows)
  if (!latest) throw new Error('完井数据没有可识别的油管记录（type/casing_type），不能用套管内径替代')
  const date = read(latest, 'date')
  return rows.filter(row => read(row, 'date') === date).map((row, index) => ({
    key: String(read(row, 'id') ?? index),
    diameter: numberOf(read(row, 'innerDiameter', 'inner_diameter')),
    roughness: numberOf(read(row, 'innerRoughness', 'inner_roughness', '内壁粗糙度')),
    label: `${read(row, 'topMeasuredDepth', 'top_measured_depth') ?? '?'}–${read(row, 'bottomMeasuredDepth', 'bottom_measured_depth') ?? '?'} m · ${read(row, 'innerDiameter', 'inner_diameter') ?? '?'} mm`,
    date
  }))
}

export function productionValues(row, position, fields = []) {
  const bottom = position === 'bottomhole'
  const pressure = bottom
    ? read(row, 'measuredBottomHolePressure', 'measured_bottom_hole_pressure', 'bottomHolePressure', 'bottom_hole_pressure')
    : read(row, 'wellHeadTubingPressure', 'well_head_tubing_pressure')
  const temperature = bottom
    ? read(row, 'measuredBottomHoleTemperature', 'measured_bottom_hole_temperature', 'bottomHoleTemperature', 'bottom_hole_temperature')
    : read(row, 'wellHeadTubingTemperature', 'well_head_tubing_temperature')
  let gas = numberOf(read(row, 'dailyGasProduction', 'daily_gas_production'))
  const gasUnit = fields.find(f => ['dailyGasProduction', 'daily_gas_production'].includes(f.name))?.unit_label
  // The project's production table uses 10^4 m³/d. Convert only an explicit plain m³/d source.
  if (gasUnit && /^(m³|m3|m\^3)\/(d|天|日)$/.test(gasUnit.replace(/\s/g, ''))) gas = gas === null ? null : gas / 10000
  if (gasUnit && !/^(m³|m3|m\^3)\/(d|天|日)$/.test(gasUnit.replace(/\s/g, '')) && !/10[⁴^4]|万/.test(gasUnit)) throw new Error(`未识别日产气量单位：${gasUnit}`)
  return { fWh: numberOf(pressure), tWh: numberOf(temperature), qGas: gas, qLiq: numberOf(read(row, 'dailyWaterProduction', 'daily_water_production')) }
}
