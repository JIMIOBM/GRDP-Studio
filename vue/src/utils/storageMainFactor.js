/**
 * 主控因素分析的展示层纯函数。
 *
 * 这里**不做单位换算**，也**不重算差异**：换算与差异都由后端负责
 * （backend/.../storagemainfactor/）。同一件事留第二处实现，就会出现
 * 前后端不一致的那类错值——本功能此前正是被这个坑绊了很久。
 *
 * 所以本文件只做三件事：输入文本转数值、来源中文标签、算出来的数值格式化。
 * 全部为纯函数，不依赖 Vue / echarts，便于用 node:test 直接测。
 */

const SOURCE_LABELS = {
  AUTO: '自动读取',
  MANUAL: '手动填写',
  MISSING: '待填写'
}

/**
 * 输入框文本转数字。
 *
 * 必须把 `''`、空白串、非法字符返回 `null`：`Number('') === 0`，
 * 直接用 Number() 会把"没填"当成真实的 0，差异就会显示一个看似正常的错值。
 */
export function toNumberOrNull(input) {
  if (input === null || input === undefined) return null
  if (typeof input === 'number') return Number.isFinite(input) ? input : null
  const text = String(input).trim()
  if (text === '') return null
  const value = Number(text)
  return Number.isFinite(value) ? value : null
}

/** 差异的符号，只看正负用于着色；无法比较时返回 null。 */
export function differenceDirection(difference) {
  const value = toNumberOrNull(difference)
  if (value === null) return null
  if (value > 0) return 'POSITIVE'
  if (value < 0) return 'NEGATIVE'
  return 'ZERO'
}

/** 来源中文标签。 */
export function sourceLabel(source) {
  return SOURCE_LABELS[source] || SOURCE_LABELS.MISSING
}

/**
 * **算出来的**数值（理论值 / 实际值 / 差异 / 百分比偏差）的格式化：缺值显示 "—"。
 *
 * 缺值时绝不显示 0：0 是一个合法读数，会让"平台没算出理论压力"
 * 看起来像"理论压力就是 0"。
 */
export function formatComputedValue(value) {
  const number = toNumberOrNull(value)
  if (number === null) return '—'
  return number.toFixed(2).replace(/\.?0+$/, '')
}

// ---------------------------------------------------------------------------
// 口径换算：数据库口径（Pa / K / 小数 / 1/Pa）↔ 界面口径（MPa / ℃ / % / MPa⁻¹）
//
// 界面用 MPa/℃/% 是为了与「微观损耗」以及原平台自己的表单一致——
// 用户看到的量级应该是 50 MPa，而不是 50000000 Pa。
//
// 这里**不碰提交给原平台的载荷**：那份载荷由后端
// StorageMainFactorCalculator.toolboxPayload 一处组装，前端不参与，
// 这样"平台口径"就只有一个转换点。
// ---------------------------------------------------------------------------

const PER_MPA_PER_PA = 1e6
const KELVIN_OFFSET = 273.15

/**
 * 每个字段在界面上该有的精度，取自原平台自己返回的 {@code fields.displayDecimal}。
 *
 * <p>取整是**必要的**：353.15 − 273.15 在二进制浮点下得到 80.00000000000006，
 * 直接显示很难看。但用统一的有效位数取整又会把 1e-10 这类真实精度削掉——
 * 噪声出现在第 14~16 位有效数字，而真实数据可以有 17 位，两者无法用同一条
 * 有效位数规则分开。所以按字段在界面上该有的精度取，与原平台表单一致。
 */
const DISPLAY_DECIMALS = {
  originalPressure: 4,
  formationTemperature: 2,
  originalGasInPlace: 4,
  cumulativeGasProduction: 4,
  waterSaturation: 2,
  specificGravity: 4,
  h2SMoleFraction: 2,
  co2MoleFraction: 2,
  n2MoleFraction: 2
}

const round = (value, decimals) => {
  const number = toNumberOrNull(value)
  return number === null ? null : Number(number.toFixed(decimals))
}

/** 压缩系数只有 ~1e-4 量级，按小数位取整会被抹成 0，必须按有效位数。 */
const SMALL_VALUE_DIGITS = 6

const roundSmall = (value, digits) => {
  const number = toNumberOrNull(value)
  return number === null ? null : Number(number.toPrecision(digits))
}

const times = (value, factor, digits) => {
  const number = toNumberOrNull(value)
  if (number === null) return null
  return digits === undefined ? number * factor : roundSmall(number * factor, digits)
}

const plus = (value, delta, decimals) => {
  const number = toNumberOrNull(value)
  if (number === null) return null
  const sum = number + delta
  // decimals 省略时必须原样返回：toFixed(undefined) 等价于 toFixed(0)，
  // 会把 353.15 悄悄截成 353。
  return decimals === undefined ? sum : round(sum, decimals)
}

export function toDisplayInputs(inputs) {
  if (!inputs) return null
  const pvt = inputs.gasPvtParam || {}
  return {
    gasReservoirType: inputs.gasReservoirType ?? null,
    originalPressure: round(times(inputs.originalPressure, 1 / PER_MPA_PER_PA), DISPLAY_DECIMALS.originalPressure),
    formationTemperature: plus(inputs.formationTemperature, -KELVIN_OFFSET, DISPLAY_DECIMALS.formationTemperature),
    originalGasInPlace: round(inputs.originalGasInPlace, DISPLAY_DECIMALS.originalGasInPlace),
    cumulativeGasProduction: round(inputs.cumulativeGasProduction, DISPLAY_DECIMALS.cumulativeGasProduction),
    rockCompressionCoefficient: times(inputs.rockCompressionCoefficient, PER_MPA_PER_PA, SMALL_VALUE_DIGITS),
    waterCompressionCoefficient: times(inputs.waterCompressionCoefficient, PER_MPA_PER_PA, SMALL_VALUE_DIGITS),
    waterSaturation: round(times(inputs.waterSaturation, 100), DISPLAY_DECIMALS.waterSaturation),
    gasType: pvt.gasType ?? 0,
    specificGravity: round(pvt.specificGravity, DISPLAY_DECIMALS.specificGravity),
    h2SMoleFraction: round(times(pvt.h2SMoleFraction, 100), DISPLAY_DECIMALS.h2SMoleFraction),
    co2MoleFraction: round(times(pvt.co2MoleFraction, 100), DISPLAY_DECIMALS.co2MoleFraction),
    n2MoleFraction: round(times(pvt.n2MoleFraction, 100), DISPLAY_DECIMALS.n2MoleFraction),
    modificationMethod: pvt.modificationMethod ?? 0,
    deviationFactorMethod: pvt.deviationFactorMethod ?? 0
  }
}

/**
 * 界面口径 → 后端口径（逆运算）。
 *
 * 这里**不做任何取整**：传回去的就是用户看到、或后端预填的那个数，
 * 只有算术本身（×1e6、+273.15、÷100）。取整只发生在展示方向。
 *
 * 可缺省的数值一律给 {@code null}，交给后端报"缺少哪个必填入参"；
 * 只有下拉框与 PVT 组给 0——它们的后端类型是原始类型，0 就是第一项，
 * 不会伪装成一个读数。
 */
export function toAppInputs(form) {
  if (!form) return null
  return {
    gasReservoirType: toNumberOrNull(form.gasReservoirType),
    originalPressure: times(form.originalPressure, PER_MPA_PER_PA),
    formationTemperature: plus(form.formationTemperature, KELVIN_OFFSET),
    originalGasInPlace: toNumberOrNull(form.originalGasInPlace),
    cumulativeGasProduction: toNumberOrNull(form.cumulativeGasProduction),
    rockCompressionCoefficient: times(form.rockCompressionCoefficient, 1 / PER_MPA_PER_PA),
    waterCompressionCoefficient: times(form.waterCompressionCoefficient, 1 / PER_MPA_PER_PA),
    waterSaturation: times(form.waterSaturation, 1 / 100),
    gasPvtParam: {
      gasType: toNumberOrNull(form.gasType) ?? 0,
      specificGravity: toNumberOrNull(form.specificGravity) ?? 0,
      modificationMethod: toNumberOrNull(form.modificationMethod) ?? 0,
      h2SMoleFraction: times(form.h2SMoleFraction, 1 / 100) ?? 0,
      co2MoleFraction: times(form.co2MoleFraction, 1 / 100) ?? 0,
      n2MoleFraction: times(form.n2MoleFraction, 1 / 100) ?? 0,
      deviationFactorMethod: toNumberOrNull(form.deviationFactorMethod) ?? 0,
      // 黏度计算方法界面上没有这一项：它既不在原平台 MBE 表单里，也不在导入 PVT 模板里。
      // 与原平台自己的界面一致，固定发 0（后端字段是原始类型，0 就是 Lee-Gonzalez-Eakin）。
      viscosityMethod: 0
    }
  }
}

/**
 * 地层压力对比图的序列。两种口径：
 *   {@code relative} —— 以理论值为 100% 基准（一眼看出"实际只有理论的多少"）
 *   {@code absolute} —— 直接是 MPa（单因素看绝对量）
 *
 * 任一侧缺值就给 {@code null} 让柱子留空：平台算不出理论值时，
 * 画一根"理论值 0"的柱子等于把"没算出来"说成"理论压力是 0"。
 * 理论值为 0 时相对口径不除零。
 */
export function buildPressureChartSeries(theoretical, actual, mode) {
  const t = toNumberOrNull(theoretical)
  const a = toNumberOrNull(actual)
  if (mode === 'relative') {
    return {
      yName: '相对理论值 (%)',
      theoretical: t === null ? null : 100,
      actual: t === null || t === 0 || a === null ? null : (a / t) * 100
    }
  }
  return { yName: 'MPa', theoretical: t, actual: a }
}
