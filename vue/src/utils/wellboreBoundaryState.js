import { reactive } from 'vue'

// 仅保存当前前端会话的共享输入；数据库计算结果仍由各页面原有接口保存。
const states = new Map()
const numericFields = ['pressure', 'temperature', 'qGas', 'qLiq']
const fields = ['boundaryPosition', ...numericFields]
const createState = () => reactive({
  values: { boundaryPosition: 'wellhead', pressure: null, temperature: null, qGas: null, qLiq: null },
  edited: {}
})

export function getWellboreBoundaryState(context = {}) {
  const projectId = Number(context.projectId)
  const gasReservoirId = Number(context.gasReservoirId)
  const wellName = String(context.wellName ?? '').trim()
  // 无完整归属时返回独立状态，不能让未选井页面共享同一个空键。
  if (!Number.isSafeInteger(projectId) || projectId <= 0
    || !Number.isSafeInteger(gasReservoirId) || gasReservoirId <= 0 || !wellName) return createState()
  const key = JSON.stringify([projectId, gasReservoirId, wellName])
  if (!states.has(key)) states.set(key, createState())
  return states.get(key)
}

const numberOrNull = value => {
  if (value == null || (typeof value === 'string' && !value.trim())) return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

export function setWellboreBoundaryValue(state, field, value) {
  if (!fields.includes(field)) return
  if (field === 'boundaryPosition') {
    if (!['wellhead', 'bottomhole'].includes(value)) return
    state.values[field] = value
  } else state.values[field] = numberOrNull(value)
  state.edited[field] = true
}

export function commitWellboreBoundaryValues(state, draft) {
  // 只同步这五个公共字段，不把模型选择或其他页面的私有参数带入共享状态。
  for (const field of fields) {
    if (Object.hasOwn(draft, field)) setWellboreBoundaryValue(state, field, draft[field])
  }
}

export function applyWellboreBoundaryDefaults(state, defaults = {}) {
  for (const field of numericFields) {
    // 异步生产数据只补空白；用户编辑（包括主动清空）后不再被默认值覆盖。
    if (state.edited[field] || state.values[field] != null) continue
    // 调用方读取的是井口生产数据，不能填作井底的压力、温度。
    if (state.values.boundaryPosition !== 'wellhead' && ['pressure', 'temperature'].includes(field)) continue
    const value = numberOrNull(defaults[field])
    if (value != null) state.values[field] = value
  }
}

export function wellboreBoundaryLabels(position) {
  const prefix = position === 'bottomhole' ? '井底' : '井口'
  return { pressure: `${prefix}压力 (MPa)`, temperature: `${prefix}温度 (℃)` }
}

// 仅适配后端字段名：MPa、℃、万m³/d及m³/d沿用页面单位，不在此换算。
export function boundaryValuesForPressure(state) {
  const { boundaryPosition, pressure, temperature, qGas, qLiq } = state.values
  return { boundaryPosition, boundaryPressure: pressure, tWh: temperature, qGas, qLiq }
}

export function boundaryValuesForTemperature(state) {
  const { boundaryPosition, pressure, temperature, qGas, qLiq } = state.values
  return { boundaryPosition, referencePressure: pressure, tWh: temperature, qGas, qLiq }
}
