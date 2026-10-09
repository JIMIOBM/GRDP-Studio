import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPressureChartSeries,
  differenceDirection,
  formatComputedValue,
  sourceLabel,
  toAppInputs,
  toDisplayInputs,
  toNumberOrNull
} from './storageMainFactor.js'

test('空字符串与非法输入不能当成 0', () => {
  assert.equal(toNumberOrNull(''), null)
  assert.equal(toNumberOrNull('   '), null)
  assert.equal(toNumberOrNull('abc'), null)
  assert.equal(toNumberOrNull('0'), 0)
  assert.equal(toNumberOrNull('-1.5'), -1.5)
  assert.equal(toNumberOrNull(null), null)
  assert.equal(toNumberOrNull(undefined), null)
  assert.equal(toNumberOrNull(NaN), null)
  assert.equal(toNumberOrNull(0), 0)
})

test('差异方向只看正负，无法比较时为 null', () => {
  assert.equal(differenceDirection(-4.55), 'NEGATIVE')
  assert.equal(differenceDirection(2), 'POSITIVE')
  assert.equal(differenceDirection(0), 'ZERO')
  assert.equal(differenceDirection(null), null)
  assert.equal(differenceDirection(''), null)
})

test('来源标签', () => {
  assert.equal(sourceLabel('AUTO'), '自动读取')
  assert.equal(sourceLabel('MANUAL'), '手动填写')
  assert.equal(sourceLabel('MISSING'), '待填写')
  assert.equal(sourceLabel(undefined), '待填写')
})

test('算出来的数值缺值显示破折号，绝不显示 0', () => {
  // 平台没算出理论压力时，显示 0 会让它看起来像一个真实读数。
  assert.equal(formatComputedValue(null), '—')
  assert.equal(formatComputedValue(undefined), '—')
  assert.equal(formatComputedValue(''), '—')
  assert.equal(formatComputedValue(18.1045), '18.1')
  assert.equal(formatComputedValue(-4.55), '-4.55')
  assert.equal(formatComputedValue(0), '0')
})

// 本机实测的一组入参（数据库口径），用于下面两个方向互为逆运算的用例。
const APP_INPUTS = {
  gasReservoirType: 1,
  originalPressure: 50_000_000,
  formationTemperature: 353.15,
  originalGasInPlace: 23.398270898104453,
  cumulativeGasProduction: 12.306372768,
  rockCompressionCoefficient: 1.0e-10,
  waterCompressionCoefficient: 3.744512763331313e-10,
  waterSaturation: 0.26158040988077613,
  gasPvtParam: {
    gasType: 0,
    specificGravity: 0.58,
    modificationMethod: 0,
    h2SMoleFraction: 0.0462,
    co2MoleFraction: 0.0396,
    n2MoleFraction: 0,
    deviationFactorMethod: 0,
    viscosityMethod: 0
  }
}

test('数据库口径换算成界面口径：MPa / ℃ / % / MPa⁻¹', () => {
  const form = toDisplayInputs(APP_INPUTS)

  assert.equal(form.originalPressure, 50) // Pa → MPa
  assert.equal(form.formationTemperature, 80) // K → ℃，353.15−273.15 的浮点噪声必须清掉
  assert.equal(form.originalGasInPlace, 23.3983) // 10⁸m³，4 位小数（与原平台表单一致）
  assert.equal(form.cumulativeGasProduction, 12.3064)
  assert.equal(form.rockCompressionCoefficient, 1.0e-4) // 1/Pa → MPa⁻¹
  assert.equal(form.waterCompressionCoefficient, 3.74451e-4)
  assert.equal(form.waterSaturation, 26.16) // 小数 → %
  assert.equal(form.gasType, 0)
  assert.equal(form.specificGravity, 0.58)
  assert.equal(form.h2SMoleFraction, 4.62) // 小数 → %
  assert.equal(form.co2MoleFraction, 3.96)
  assert.equal(form.n2MoleFraction, 0)
})

/** 往返只在"界面显示精度"上损失，量级远小于页面显示的 2 位小数。 */
const closeTo = (actual, expected, relative = 1e-4) => {
  assert.ok(Math.abs(actual - expected) <= Math.abs(expected) * relative,
    `期望 ${expected}（±${relative} 相对），实际 ${actual}`)
}

test('界面口径换回数据库口径是上面那个换算的逆运算', () => {
  const back = toAppInputs(toDisplayInputs(APP_INPUTS))

  closeTo(back.originalPressure, 50_000_000, 1e-9)
  closeTo(back.formationTemperature, 353.15, 1e-9)
  closeTo(back.rockCompressionCoefficient, 1.0e-10, 1e-4)
  closeTo(back.waterCompressionCoefficient, 3.744512763331313e-10, 1e-4)
  closeTo(back.waterSaturation, 0.26158040988077613, 1e-4)
  closeTo(back.gasPvtParam.h2SMoleFraction, 0.0462, 1e-9)
  closeTo(back.gasPvtParam.co2MoleFraction, 0.0396, 1e-9)
  assert.equal(back.gasPvtParam.specificGravity, 0.58)
  assert.equal(back.gasPvtParam.gasType, 0)
})

test('换算遇到缺值给 null，不能编出 0', () => {
  // 0 是一个合法的读数：把"没填"变成 0 会算出一个看着正常的错压力。
  const form = toDisplayInputs({ ...APP_INPUTS, originalPressure: null, waterSaturation: null })
  assert.equal(form.originalPressure, null)
  assert.equal(form.waterSaturation, null)

  const back = toAppInputs({ originalPressure: '', waterSaturation: '  ', gasReservoirType: null })
  assert.equal(back.originalPressure, null)
  assert.equal(back.waterSaturation, null)
  assert.equal(back.gasReservoirType, null)
  // 下拉框与 PVT 组是原始类型，缺省按 0（对应第一项），不会伪装成读数
  assert.equal(back.gasPvtParam.gasType, 0)
  assert.equal(back.gasPvtParam.h2SMoleFraction, 0)
})

test('地层压力对比图：相对口径以理论值为 100% 基准', () => {
  const series = buildPressureChartSeries(18.1045, 13.5573, 'relative')

  assert.equal(series.yName, '相对理论值 (%)')
  assert.equal(series.theoretical, 100)
  assert.ok(Math.abs(series.actual - (13.5573 / 18.1045) * 100) < 1e-9)
})

test('地层压力对比图：绝对口径直接给 MPa', () => {
  const series = buildPressureChartSeries(18.1045, 13.5573, 'absolute')

  assert.equal(series.yName, 'MPa')
  assert.equal(series.theoretical, 18.1045)
  assert.equal(series.actual, 13.5573)
})

test('地层压力对比图：缺值时柱子留空，不画成 0', () => {
  // 平台不可用时理论值是空的：相对口径下 100% 这条基准也不能画出来，
  // 否则图上会凭空出现一根"理论值"的柱子。
  const relative = buildPressureChartSeries(null, 13.5573, 'relative')
  assert.equal(relative.theoretical, null)
  assert.equal(relative.actual, null)

  const absolute = buildPressureChartSeries(null, 13.5573, 'absolute')
  assert.equal(absolute.theoretical, null)
  assert.equal(absolute.actual, 13.5573)

  // 理论值为 0 时相对口径不能除零
  const zero = buildPressureChartSeries(0, 13.5573, 'relative')
  assert.equal(zero.actual, null)
})

test('黏度计算方法不再由界面提供，固定发 0', () => {
  // 参数表与原平台 MBE 表单对齐后删掉了「天然气黏度计算方法」：
  // 它既不在平台表单里，也不在导入 PVT 模板里；平台自己的界面同样固定发 0。
  const back = toAppInputs({ ...toDisplayInputs(APP_INPUTS), viscosityMethod: 2 })

  assert.equal(back.gasPvtParam.viscosityMethod, 0)
})
