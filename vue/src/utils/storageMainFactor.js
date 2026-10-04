/**
 * 主控因素分析的展示层纯函数。
 *
 * 这里**不做单位换算**：数据库口径与原平台口径之间的换算全部在后端
 * （backend/.../storagemainfactor/StorageMainFactorUnits.java）。换算只留一处，
 * 才不会出现前后端各转一次、结果差 10⁶ 或 10⁸ 倍的情况。
 *
 * 本文件只负责：缺值判定、差异方向、百分比偏差、格式化、来源中文标签、图表序列。
 * 全部为纯函数，不依赖 Vue / echarts，便于用 node:test 直接测。
 */

/** 键必须与后端 StorageMainFactorCalculator 的 KEYS 完全一致，顺序即表格行序。 */
export const FACTOR_LABELS = {
  formationPressure: '地层压力',
  poreVolume: '动用孔隙体积',
  gas: '天然气',
  gasSaturation: '气体饱和度'
}

export const FACTOR_UNITS = {
  formationPressure: 'MPa',
  poreVolume: '10⁸m³',
  gas: '10⁸m³',
  gasSaturation: '小数'
}

const SOURCE_LABELS = {
  AUTO: '自动读取',
  MANUAL: '手动填写',
  MISSING: '待填写'
}

/**
 * 输入框文本转数字。
 *
 * 必须把 `''`、空白串、非法字符返回 `null`：`Number('') === 0`，
 * 直接用 Number() 会把"没填"当成真实的 0，差异列就会显示一个看似正常的错值。
 */
export function toNumberOrNull(input) {
  if (input === null || input === undefined) return null
  if (typeof input === 'number') return Number.isFinite(input) ? input : null
  const text = String(input).trim()
  if (text === '') return null
  const value = Number(text)
  return Number.isFinite(value) ? value : null
}

/** 差异方向；无法比较时返回 null。 */
export function differenceDirection(difference) {
  const value = toNumberOrNull(difference)
  if (value === null) return null
  if (value > 0) return 'POSITIVE'
  if (value < 0) return 'NEGATIVE'
  return 'ZERO'
}

/** 百分比偏差 = (实际 − 理论) / 理论 × 100；理论值为 0 或缺值时返回 null（不除零）。 */
export function deviationPercent(actual, theoretical) {
  const a = toNumberOrNull(actual)
  const t = toNumberOrNull(theoretical)
  if (a === null || t === null || t === 0) return null
  const percent = ((a - t) / t) * 100
  return Number.isFinite(percent) ? percent : null
}

/** 来源中文标签。 */
export function sourceLabel(source) {
  return SOURCE_LABELS[source] || SOURCE_LABELS.MISSING
}

/** 数值 + 单位；缺值显示"待填写"，不要显示成 0。保留两位小数并去掉多余的 0。 */
export function formatFactorValue(value, unit) {
  const number = toNumberOrNull(value)
  if (number === null) return '待填写'
  const text = number.toFixed(2).replace(/\.?0+$/, '')
  return unit ? `${text} ${unit}` : text
}

const valueOf = side => {
  if (side === null || side === undefined) return null
  return toNumberOrNull(side.value)
}

/**
 * 相对理论值的序列：理论值一律为 100% 作为基准。
 *
 * 四个因素单位互不相同（MPa / 10⁸m³ / 小数），把绝对值画在同一张柱状图上没有可比性，
 * 所以主图用这个序列。理论值为 0 或缺值时该因素的柱子留空。
 */
export function buildRelativeSeries(factors = []) {
  return factors.map(factor => {
    const theoretical = valueOf(factor.theoretical)
    const actual = valueOf(factor.actual)
    return {
      key: factor.key,
      label: FACTOR_LABELS[factor.key] || factor.key,
      unit: '%',
      theoretical: theoretical === null ? null : 100,
      actual: theoretical === null || theoretical === 0 || actual === null
        ? null
        : (actual / theoretical) * 100
    }
  })
}

/** 绝对值的序列：按因素分面，每个因素自带单位，不与其他因素混在一张图里。 */
export function buildAbsoluteSeries(factors = []) {
  return factors.map(factor => ({
    key: factor.key,
    label: FACTOR_LABELS[factor.key] || factor.key,
    unit: FACTOR_UNITS[factor.key] || '',
    theoretical: valueOf(factor.theoretical),
    actual: valueOf(factor.actual)
  }))
}
