import { reactive } from 'vue'

const VALUE_FIELDS = ['pressure', 'temperature', 'qGas', 'qLiq']
const EDITABLE_FIELDS = [...VALUE_FIELDS, 'boundaryPosition']
const states = new Map()

const contextKey = context => [
  Number(context?.projectId),
  Number(context?.gasReservoirId),
  String(context?.wellName ?? '').trim()
].join(':')

export function getWellboreBoundaryState (context) {
  const key = contextKey(context)
  if (!states.has(key)) {
    states.set(key, reactive({
      values: {
        pressure: null,
        temperature: null,
        qGas: null,
        qLiq: null,
        boundaryPosition: 'wellhead'
      },
      modified: new Set()
    }))
  }
  return states.get(key)
}

// Source values remain the default. Only fields explicitly changed in one of the
// three boundary editors are retained when another page reloads the source row.
export function applyWellboreBoundaryDefaults (state, defaults) {
  for (const field of VALUE_FIELDS) {
    if (!state.modified.has(field)) state.values[field] = defaults?.[field] ?? null
  }
}

export function setWellboreBoundaryValue (state, field, value) {
  if (!EDITABLE_FIELDS.includes(field)) throw new Error(`未知井筒边界字段：${field}`)
  state.modified.add(field)
  state.values[field] = value
}

export function commitWellboreBoundaryValues (state, values) {
  for (const field of EDITABLE_FIELDS) {
    if (
      Object.prototype.hasOwnProperty.call(values ?? {}, field)
      && !Object.is(state.values[field], values[field])
    ) {
      setWellboreBoundaryValue(state, field, values[field])
    }
  }
}

export function wellboreBoundaryLabels (boundaryPosition) {
  const inputAtWellhead = boundaryPosition === 'bottomhole'
  return {
    pressure: inputAtWellhead ? '井口压力 (MPa)' : '井底压力 (MPa)',
    temperature: inputAtWellhead ? '井口温度 (℃)' : '井底温度 (℃)'
  }
}

export function boundaryValuesForTemperature (state) {
  return {
    boundaryPosition: state.values.boundaryPosition,
    referencePressure: state.values.pressure,
    tWh: state.values.temperature,
    qGas: state.values.qGas,
    qLiq: state.values.qLiq
  }
}

export function boundaryValuesForPressure (state) {
  return {
    boundaryPosition: state.values.boundaryPosition,
    boundaryPressure: state.values.pressure,
    tWh: state.values.temperature,
    qGas: state.values.qGas,
    qLiq: state.values.qLiq
  }
}

export function resetWellboreBoundaryStatesForTest () {
  states.clear()
}
