export const unpack = response => response?.data?.data ?? response?.data ?? response ?? {}

export const numberOf = value => {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

export const sourceCollection = response => {
  const payload = unpack(response)
  return {
    items: Array.isArray(payload)
      ? payload
      : Array.isArray(payload.items)
        ? payload.items
        : Array.isArray(payload.rows)
          ? payload.rows
          : Array.isArray(payload.records)
            ? payload.records
            : [],
    fields: Array.isArray(payload.fields) ? payload.fields : []
  }
}

const normalizedKey = value => String(value ?? '').replace(/[\s_()（）/·-]/g, '').toLowerCase()

const fieldValue = (row, aliases, fields = []) => {
  if (!row || typeof row !== 'object') return null
  const expected = new Set(aliases.map(normalizedKey))
  for (const [key, value] of Object.entries(row)) {
    if (expected.has(normalizedKey(key))) return numberOf(value)
  }

  // Some original-platform responses use field metadata as the display-name map.
  for (const field of fields) {
    const names = [field?.name, field?.name_cn, field?.field, field?.label]
    if (!names.some(name => expected.has(normalizedKey(name)))) continue
    const key = field?.name ?? field?.field
    if (key && Object.hasOwn(row, key)) return numberOf(row[key])
  }
  return null
}

const dateValue = row => {
  const value = row?.date ?? row?.productionDate ?? row?.recordDate ?? row?.日期
  const timestamp = Date.parse(value)
  return Number.isFinite(timestamp) ? timestamp : Number.NEGATIVE_INFINITY
}

const newestFirst = rows => rows
  .map((row, index) => ({ row, index, timestamp: dateValue(row) }))
  .sort((left, right) => right.timestamp - left.timestamp || right.index - left.index)
  .map(item => item.row)

const firstNumber = (rows, aliases, fields) => {
  for (const row of rows) {
    const value = fieldValue(row, aliases, fields)
    if (value !== null) return value
  }
  return null
}

const COMMON_RATE_FIELDS = {
  qGas: ['dailyGasProduction', 'daily_gas_production', 'gasProduction', 'gasRate', '日产气量', '气产量'],
  qLiq: ['dailyWaterProduction', 'daily_water_production', 'waterProduction', 'liquidProduction', '日产水量', '水产量']
}

const POSITION_FIELDS = {
  wellhead: {
    fWh: ['wellHeadTubingPressure', 'well_head_tubing_pressure', 'wellheadPressure', 'tubingPressure', '井口油压'],
    tWh: ['wellHeadTubingTemperature', 'well_head_tubing_temperature', 'wellheadTemperature', 'tubingTemperature', '井口油温']
  },
  bottomhole: {
    fWh: ['measuredBottomHolePressure', 'measured_bottom_hole_pressure', 'calculatedBottomHolePressure', 'calculated_bottom_hole_pressure', 'bottomHolePressure', 'bottomPressure', '井底压力', '井底流压'],
    tWh: ['measuredBottomHoleTemperature', 'measured_bottom_hole_temperature', 'calculatedBottomHoleTemperature', 'calculated_bottom_hole_temperature', 'bottomHoleTemperature', 'bottomTemperature', '井底温度']
  }
}

export const productionValues = (production, position = 'wellhead', fields = []) => {
  const collection = sourceCollection(production)
  const rows = newestFirst(collection.items)
  const metadata = fields.length ? fields : collection.fields
  const positionFields = POSITION_FIELDS[position] ?? POSITION_FIELDS.wellhead
  return {
    fWh: firstNumber(rows, positionFields.fWh, metadata),
    tWh: firstNumber(rows, positionFields.tWh, metadata),
    qGas: firstNumber(rows, COMMON_RATE_FIELDS.qGas, metadata),
    qLiq: firstNumber(rows, COMMON_RATE_FIELDS.qLiq, metadata)
  }
}

export const readSourceNumber = (row, aliases, fields = []) => fieldValue(row, aliases, fields)
