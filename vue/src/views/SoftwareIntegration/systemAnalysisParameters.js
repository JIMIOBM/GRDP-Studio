const requestFields = ['schemaVersion', 'producer', 'branchTerminator', 'outletPressurePsi', 'scanVariable', 'values']
const resultFields = ['schemaVersion', 'model_kind', 'runTask', 'resultContract', 'study', 'producer', 'branchTerminator', 'outletPressurePsi', 'scanVariable', 'cases']
const exactFields = (value, fields) => value !== null && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field))
const localPath = value => {
  let decoded = value
  try { decoded = decodeURIComponent(value) } catch { /* Check malformed URI text as supplied. */ }
  return /(?:^|[^a-z0-9])(?:[a-z]:(?:[\\/]|[^\s])|\\\\[^\\/\s]+[\\/]|file:|net\.pipe:\/\/)|(?:^|[^a-z0-9:])\/\/[^/\s]+\//i.test(decoded)
}
const safeText = (value, empty = false) => typeof value === 'string' && value.length <= 1000 &&
  (empty && value.length === 0 || value.trim().length > 0) && !/[\u0000-\u001f\u007f-\u009f]/.test(value) && !localPath(value)
const contextName = value => safeText(value) && value.length <= 255 && value === value.trim() &&
  !value.includes('..') && !/[\\/\u0000-\u001f\u007f]/.test(value)
const finite = value => typeof value === 'number' && Number.isFinite(value)
const positive = (value, max) => finite(value) && value > 0 && value <= max
const axisValid = values => Array.isArray(values) && values.length >= 2 && values.length <= 8 &&
  values.every((value, index) => positive(value, 1e9) && (index === 0 || value > values[index - 1]))
const nativeNumber = value => finite(value) && value !== -1e31 && Math.abs(value - 1.2345e25) > 1.2345e25 * 1e-12
const fieldUnits = Object.freeze({ TotalDistance: 'ft', Pressure: 'psia', BottomHolePressure: 'psia',
  Temperature: 'degF', VolumeFlowrateLiquidStockTank: 'STB/d' })
const fieldUnit = variable => Object.hasOwn(fieldUnits, variable.variable) && variable.unit === fieldUnits[variable.variable]

export function buildSystemAnalysisParameters(draft) {
  const errors = {}
  const selected = (value, choices) => contextName(value) && Array.isArray(choices) && choices.includes(value)
  if (!selected(draft.producer, draft.producers)) errors.producer = '请选择当前模型中的井'
  if (!selected(draft.branchTerminator, draft.branches)) errors.branchTerminator = '请选择当前模型中的 Flowline 分支'
  if (!safeText(draft.study) || !Array.isArray(draft.studies) || !draft.studies.includes(draft.study)) errors.study = '请选择当前版本已有的 Study'
  if (!positive(draft.outletPressurePsi, 100000)) errors.outletPressurePsi = '请输入 (0, 100000] psia 的出口压力'
  const tokens = typeof draft.valuesText === 'string' ? draft.valuesText.trim().split(/[,，\s]+/).filter(Boolean) : []
  const numericToken = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i
  const values = tokens.map(token => numericToken.test(token) ? Number(token) : NaN)
  if (!axisValid(values)) errors.valuesText = '请输入 2–8 个严格递增的正液量（STB/d，最多 1e9）'
  if (Object.keys(errors).length) return { parameters: null, errors, summary: '请修正系统分析标记的字段后运行' }
  return { parameters: { schemaVersion: 'pipesim-system-analysis-parameters/2', producer: draft.producer,
    branchTerminator: draft.branchTerminator, outletPressurePsi: draft.outletPressurePsi,
    scanVariable: 'liquidFlowRate', values }, errors, summary: null }
}

function validRequest(parameters, version) {
  return exactFields(parameters, requestFields) && parameters.schemaVersion === `pipesim-system-analysis-parameters/${version}` &&
    (version === 1 ? parameters.producer === 'Well' : contextName(parameters.producer)) &&
    contextName(parameters.branchTerminator) && positive(parameters.outletPressurePsi, 100000) &&
    parameters.scanVariable === 'liquidFlowRate' && axisValid(parameters.values)
}

function validNumericVariable(variable, dynamic) {
  return exactFields(variable, ['variable', 'unit', 'value']) && safeText(variable.variable) &&
    (variable.unit === null || safeText(variable.unit, true)) &&
    (dynamic ? nativeNumber(variable.value) && fieldUnit(variable) : finite(variable.value))
}

function validCase(item, dynamic) {
  if (!exactFields(item, ['caseName', 'scanValue', 'system', 'node', 'profile']) || !safeText(item.caseName) ||
    !finite(item.scanValue) || item.scanValue <= 0 || !Array.isArray(item.system) || !item.system.length ||
    !item.system.every(variable => validNumericVariable(variable, dynamic)) ||
    !Array.isArray(item.node) || !item.node.length || !item.node.every(node =>
      exactFields(node, ['node', 'variables']) && safeText(node.node) && Array.isArray(node.variables) &&
      node.variables.length && node.variables.every(variable => validNumericVariable(variable, dynamic)))) return false
  const profile = item.profile
  if (!exactFields(profile, ['pointCount', 'variables']) || !Number.isInteger(profile.pointCount) || profile.pointCount <= 0 ||
    !Array.isArray(profile.variables) || !profile.variables.length) return false
  const names = new Set()
  for (const variable of profile.variables) {
    if (!exactFields(variable, ['variable', 'unit', 'values']) || !safeText(variable.variable) ||
      (variable.unit !== null && !safeText(variable.unit, true)) || !Array.isArray(variable.values) ||
      variable.values.length !== profile.pointCount || (dynamic && names.has(variable.variable))) return false
    names.add(variable.variable)
    if (variable.variable === 'BranchEquipment') {
      if (!variable.values.every(value => value === null || safeText(value, true))) return false
    } else if (!variable.values.every(dynamic ? nativeNumber : finite) || (dynamic && !fieldUnit(variable))) return false
  }
  return names.has('TotalDistance') && names.has('Pressure') && (!dynamic || names.has('Temperature'))
}

export function validateSystemAnalysisRun(run, expectedVersionId) {
  const result = run?.result
  if (!exactFields(result, resultFields) || run.runType !== 'system-analysis' || run.status !== 'SUCCEEDED' ||
    run.resultContract !== 'VALID_FULL' || result.resultContract !== run.resultContract ||
    (expectedVersionId !== undefined && run.modelVersionId !== expectedVersionId) ||
    result.model_kind !== 'network' || result.runTask !== 'system-analysis' || !safeText(result.study) ||
    result.study !== run.study || !safeText(result.branchTerminator) || result.scanVariable !== 'liquidFlowRate' ||
    !positive(result.outletPressurePsi, 100000) || !Array.isArray(result.cases) ||
    result.cases.length < 2 || result.cases.length > 8) return null
  const version = result.schemaVersion === 'pipesim-system-analysis-result/2' ? 2 :
    result.schemaVersion === 'pipesim-system-analysis-result/1' ? 1 : null
  if (!version || (version === 1 && result.producer !== 'Well') || (version === 2 && !contextName(result.producer))) return null
  if (version === 2 || run.parameters != null) {
    if (!validRequest(run.parameters, version) || ['producer', 'branchTerminator', 'outletPressurePsi', 'scanVariable']
      .some(field => result[field] !== run.parameters[field]) || result.cases.length !== run.parameters.values.length ||
      result.cases.some((item, index) => item?.scanValue !== run.parameters.values[index])) return null
  }
  if (!result.cases.every((item, index) => validCase(item, version === 2) &&
    (index === 0 || item.scanValue > result.cases[index - 1].scanValue))) return null
  // The validated contract is JSON data; serialization also unwraps Vue reactive proxies.
  return JSON.parse(JSON.stringify(result))
}
