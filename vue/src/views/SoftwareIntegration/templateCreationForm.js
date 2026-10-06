export const templateFields = [
  ['oilApi', '原油 API 度', 0, 100, false],
  ['gasSpecificGravity', '气体相对密度', 0, 10, false],
  ['waterSpecificGravity', '水相对密度', 0, 10, false],
  ['gorScfStb', '气油比 (scf/STB)', 0, 1000000, true],
  ['waterCutPercent', '含水率 (%)', 0, 100, true],
  ['reservoirPressurePsia', '地层压力 (psia)', 0, 100000, false],
  ['reservoirTemperatureDegF', '地层温度 (°F)', -459.67, 1000, false],
  ['outletPressurePsia', '出口压力 (psia)', 0, 100000, false],
  ['liquidRateStbDay', '液量 (STB/d)', 0, 1000000, false]
]
export const experimentInputs = {
  oilApi: 35, gasSpecificGravity: 0.65, waterSpecificGravity: 1.05,
  gorScfStb: 200, waterCutPercent: 20, reservoirPressurePsia: 4000,
  reservoirTemperatureDegF: 150, outletPressurePsia: 250, liquidRateStbDay: 1000
}
export const terminalCreation = state => ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED'].includes(state)
export function creationPayload(well, study, values, requestId) {
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(well)) throw new Error('井名须以字母开头，限 64 位字母、数字、下划线或短横线')
  if (!study.trim() || study.length > 64 || /[\u0000-\u001f\u007f-\u009f]/.test(study)) throw new Error('请填写有效 Study 名称')
  const inputs = { schemaVersion: 'pipesim-template-profile-inputs/1', unitsSystem: 'PIPESIM_FIELD', study }
  for (const [key, label, min, max, inclusive] of templateFields) {
    const value = values[key]
    if (typeof value !== 'number' || !Number.isFinite(value) || value > max || (inclusive ? value < min : value <= min)) {
      throw new Error(`请填写有效的${label}，范围${inclusive ? '[' : '('}${min}, ${max}]`)
    }
    inputs[key] = value
  }
  return { requestId, well, inputs }
}
