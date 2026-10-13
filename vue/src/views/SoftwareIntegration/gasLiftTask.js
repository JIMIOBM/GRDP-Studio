export const gasLiftReason = code => ({
  GAS_LIFT_VALVE_REQUIRED: '当前井为注气口模式，不适用此气举阀任务；GLR 扫描是另一种工作流。',
  GAS_LIFT_TARGET_AXIS_UNAVAILABLE: '此井和 Study 没有目标注气量扫描轴，不能运行当前气举阀任务。',
  GAS_LIFT_CAPABILITY_UNKNOWN: '气举任务能力尚未确认，请重新验证模型；求解尚未启动。',
  GAS_LIFT_PARAMETERS_REJECTED: '官方接口拒绝了气举参数，求解尚未启动；展开查看具体字段。',
  GAS_LIFT_VALIDATION_FAILED: '气举参数未通过官方预检，求解尚未启动；请处理下方诊断。',
  GAS_LIFT_VALIDATION_UNAVAILABLE: '气举官方预检未完成，求解尚未启动；请检查 Toolkit 环境。',
  GAS_LIFT_UNITS_UNSUPPORTED: '当前气举表单采用 Field 单位；此模型单位不适用，求解尚未启动。',
  GAS_LIFT_SOLVER_FAILED: '官方气举求解失败；展开查看诊断，不会展示旧成功结果。',
  GAS_LIFT_PERFORMANCE_FAILED: '官方气举计算未完成；旧记录可能未保留详细诊断，请使用适用模型重新运行。',
  GAS_LIFT_DIAGNOSTICS_FAILED: '官方气举诊断未完成；旧记录可能未保留详细诊断，请使用适用模型重新运行。'
})[code] || ''

export const gasLiftEligibility = (inspection, well, study) => {
  const unknown = { supported: false, reason: gasLiftReason('GAS_LIFT_CAPABILITY_UNKNOWN') }
  if (inspection?.schemaVersion !== 'pipesim-well-inspection/9' || !Array.isArray(inspection.gasLiftCapabilities)) return unknown
  const matches = inspection.gasLiftCapabilities.filter(item => item?.well === well && item?.study === study)
  if (matches.length !== 1) return unknown
  const item = matches[0]
  if (item.state === 'supported' && item.reason === 'GAS_LIFT_VALVE_SUPPORTED' &&
      item.useGasliftValve === true && item.targetInjectionRateAvailable === true) return { supported: true, reason: '' }
  return { supported: false, reason: gasLiftReason(item.reason) || unknown.reason }
}

// Snapshot only the selected Run, never the editable form or another success.
export const gasLiftRunSnapshot = run => {
  if (!['gas-lift-performance', 'gas-lift-diagnostics'].includes(run?.runType)) return null
  const p = run.parameters
  if (!p || typeof p.producer !== 'string') return null
  const keys = ['outletPressurePsi', 'surfaceInjectionTemperatureF', 'targetInjectionRateMmscfd',
    'reservoirPressurePsi', 'gorScfPerStb', 'waterCutPercent']
  if (!keys.every(key => Number.isFinite(p[key]))) return null
  return { producer: p.producer, ...Object.fromEntries(keys.map(key => [key, p[key]])),
    valuesMmscfd: Array.isArray(p.valuesMmscfd) && p.valuesMmscfd.every(Number.isFinite) ? [...p.valuesMmscfd] : [] }
}
