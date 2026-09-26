export const erosionFields = {
  pressureMpa: '当前压力（MPa，绝压）', temperatureC: '当前温度（℃）',
  actualGasRate1e4M3d: '标况日产气量（万m³/d）', tubingInnerDiameterMm: '管柱内径（mm）',
  gasDensityKgM3: '气体密度（kg/m³）', liquidDensityKgM3: '液体密度（kg/m³）',
  gasVolumeFactor: 'Bg（工况m³/标况m³）', liquidHoldupPercent: '持液率 Hₗ（%）',
  sandContentPercent: '含砂率 Hₛ（%）', sandDensityKgM3: '砂粒密度（kg/m³）'
}
export const emptyErosionInput = () => ({
  ...Object.fromEntries(Object.keys(erosionFields).map(key => [key, null])), pvtId: null, pvtSnapshot: null
})
const valid = value => value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value))
export function validateErosionInput(form, requireProperties = true) {
  const propertyKeys = ['gasDensityKgM3', 'liquidDensityKgM3', 'gasVolumeFactor']
  if (!valid(form.pvtId) || Number(form.pvtId) <= 0) return '请选择当前井PVT方案'
  for (const [key, label] of Object.entries(erosionFields)) {
    if (!requireProperties && propertyKeys.includes(key)) continue
    if (!valid(form[key])) return `请填写有效的${label}`
  }
  for (const key of ['pressureMpa', 'tubingInnerDiameterMm', 'gasDensityKgM3', 'liquidDensityKgM3', 'gasVolumeFactor', 'sandContentPercent', 'sandDensityKgM3']) {
    if (!requireProperties && propertyKeys.includes(key)) continue
    if (Number(form[key]) <= 0) return `${erosionFields[key]}必须大于0`
  }
  if (Number(form.actualGasRate1e4M3d) < 0) return '日产气量不能小于0'
  if (Number(form.liquidHoldupPercent) < 0) return '持液率不能小于0'
  if (Number(form.temperatureC) <= -273.15) return '温度必须高于绝对零度'
  if (Number(form.liquidHoldupPercent) + Number(form.sandContentPercent) >= 100) return '持液率与含砂率之和必须小于100%'
  return ''
}
export function erosionRangeHint(form) {
  const messages = []
  if (valid(form.liquidHoldupPercent) && (form.liquidHoldupPercent < 0.001 || form.liquidHoldupPercent > 0.01)) messages.push('持液率超出0.001%～0.01%')
  if (valid(form.sandContentPercent) && (form.sandContentPercent < 0.0001 || form.sandContentPercent > 0.02)) messages.push('含砂率超出0.0001%～0.02%')
  return messages.length ? `${messages.join('；')}。超出标定范围。` : ''
}
export function displayErosionValue(value, digits = 6) {
  if (!valid(value)) return '—'
  return Number(value).toPrecision(digits)
}
export const erosionStatus = status => ({ BELOW_LIMIT: '低于公式临界值', AT_LIMIT: '达到公式临界值', ABOVE_LIMIT: '高于公式临界值', NOT_APPLICABLE: '模型输入不适用', CALCULATION_ERROR: '计算失败' }[status] || '尚未计算')

// A revision token rejects delayed responses even if the user changes A→B→A while a request is running.
export function createErosionResultState() {
  return {
    revision: 0, result: null, calculatedInput: null,
    invalidate() { this.revision++; this.result = null; this.calculatedInput = null },
    accept(revision, input, result) {
      if (revision !== this.revision) return false
      this.result = result
      this.calculatedInput = ['NOT_APPLICABLE', 'CALCULATION_ERROR'].includes(result?.status) ? null : input
      return true
    }
  }
}
