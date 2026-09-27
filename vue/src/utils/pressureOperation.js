import { read, productionValues } from './temperatureSources.js'

export const pressureMethodName = code => ({ HB: 'Hagedorn & Brown', MB: 'Mukherjee & Brill', GAS: '单相气体' })[code] || code

const injectionKeys = ['dailyGasInjection', 'daily_gas_injection']
// Only explicit injection records/fields may populate injection inputs. Legacy production
// rows have no operation flag; never silently reinterpret their gas production as injection.
export function isInjectionRecord (row) {
  const mode = String(read(row, 'operationMode', 'operation_mode', 'operationType', 'operation_type') ?? '').toLowerCase()
  if (mode) return ['injection', '注气', '注入'].includes(mode)
  return read(row, ...injectionKeys) !== null
}

export function injectionValues (row, position, fields = [], channel = 'tubing') {
  if (!isInjectionRecord(row)) return { fWh: null, tWh: null, qGas: null, qLiq: 0 }
  const explicitKey = injectionKeys.find(key => read(row, key) !== null)
  const field = explicitKey && fields.find(item => item.name === explicitKey)
  // Reuse the established unit and channel conversions with a normalized gas field.
  const normalized = explicitKey ? { ...row, dailyGasProduction: read(row, explicitKey) } : row
  const normalizedFields = explicitKey
    ? [{ name: 'dailyGasProduction', unit_label: field?.unit_label }]
    : fields
  return { ...productionValues(normalized, position, normalizedFields, channel), qLiq: 0 }
}

export function pressurePayload (form, boundary, context) {
  const injection = form.operationMode === 'injection'
  return {
    ...context, ...form,
    boundaryPosition: boundary.boundaryPosition,
    boundaryPressure: boundary.pressure,
    tWh: boundary.temperature,
    qGas: boundary.qGas,
    qLiq: injection ? 0 : boundary.qLiq,
    rhoL: injection ? 0 : form.rhoL,
    muL: injection ? 0 : form.muL,
    models: injection ? ['GAS'] : [...form.models],
    pvtId: boundary.pvtId,
    productionRecordKey: boundary.productionRecordKey,
    productionDate: boundary.productionDate,
    productionChannel: boundary.boundaryPosition === 'wellhead' ? boundary.wellheadChannel : 'manual-bottomhole'
  }
}
