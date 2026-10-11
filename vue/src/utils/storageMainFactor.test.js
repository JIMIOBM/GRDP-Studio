import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildPressureLines,
  parseSeriesFile,
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

// ---------------------------------------------------------------------------
// 双线折线图：日期对齐与逐点偏差
// ---------------------------------------------------------------------------

/** 库 5 的真实序列片段；中间那点故意没有理论值，模拟原平台对单个点失败。 */
const SERIES = [
  { date: '2004-04-20', cumulativeGasProduction: 0.0012918, measuredPressure: 50.0, theoreticalPressure: 49.9998, difference: 0.0002, deviationPercent: 0.0004 },
  { date: '2009-04-30', cumulativeGasProduction: 37.356667794, measuredPressure: 44.2505, theoreticalPressure: null, difference: null, deviationPercent: null },
  { date: '2013-10-31', cumulativeGasProduction: 57.43970823, measuredPressure: 41.4794921875, theoreticalPressure: 40.5345, difference: 0.9449921875, deviationPercent: 2.3313 }
]

test('折线：两条线共用同一组日期，缺理论值的那一点就是 null（不是 0）', () => {
  const lines = buildPressureLines(SERIES, 'absolute')

  assert.deepEqual(lines.dates, ['2004-04-20', '2009-04-30', '2013-10-31'])
  assert.deepEqual(lines.theoretical, [49.9998, null, 40.5345])
  assert.deepEqual(lines.actual, [50.0, 44.2505, 41.4794921875])
  assert.equal(lines.yName, 'MPa')
  // 缺值画成 0 会在图上多一个假的压力谷底
  assert.notEqual(lines.theoretical[1], 0)
})

test('折线：偏差视图给的是逐点偏差百分比，与点上的值一致', () => {
  const lines = buildPressureLines(SERIES, 'deviation')

  assert.equal(lines.yName, '偏差 (%)')
  assert.deepEqual(lines.deviation, [0.0004, null, 2.3313])
  // 两个视图的日期与实测值必须完全一致：切换视图不能改变 x 轴
  assert.deepEqual(lines.dates, buildPressureLines(SERIES, 'absolute').dates)
  assert.deepEqual(lines.actual, buildPressureLines(SERIES, 'absolute').actual)
})

test('折线：没有数据时给空序列而不是崩掉', () => {
  const lines = buildPressureLines(undefined, 'absolute')

  assert.deepEqual(lines.dates, [])
  assert.deepEqual(lines.theoretical, [])
  assert.deepEqual(lines.actual, [])
})

// ---------------------------------------------------------------------------
// 导入解析
// ---------------------------------------------------------------------------

const HEADER = ['日期', '累产气(10⁸m³)', '地层压力(MPa)']

test('导入解析：正常文件返回日期字符串与两个数值', () => {
  const { points, error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 50.0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.equal(error, null)
  assert.deepEqual(points, [
    { date: '2004-04-20', cumulativeGasProduction: 0.0012918, measuredPressure: 50.0 },
    { date: '2004-10-31', cumulativeGasProduction: 0.9164784, measuredPressure: 49.8535 }
  ])
})

test('导入解析：Excel 日期序列号也能识别', () => {
  // 38097 是 Excel 里 2004-04-20 的序列号（raw:true 读出来就是它）。
  const { points, error } = parseSeriesFile([
    HEADER,
    [38097, 0.0012918, 50.0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.equal(error, null)
  assert.equal(points[0].date, '2004-04-20')
})

test('导入解析：累产气下降要报错，并指出是哪两个日期', () => {
  const { points, error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 10.0, 50.0],
    ['2004-10-31', 9.0, 49.8]
  ])

  assert.deepEqual(points, [])
  // 报日期而不是行号：排序之后行号已经不等于文件里的行号，报了反而误导
  assert.match(error, /2004-04-20/)
  assert.match(error, /2004-10-31/)
  assert.match(error, /累产气/)
})

test('导入解析：倒序的文件按日期排序后照样能用', () => {
  // 折线图的横轴本来就按日期排；倒序导出是合法数据，
  // 不该被误判成"累产气回退"（后端也是先按日期排序再判）。
  const { points, error } = parseSeriesFile([
    HEADER,
    ['2013-10-31', 57.43970823, 41.4795],
    ['2004-10-31', 0.9164784, 49.8535],
    ['2004-04-20', 0.0012918, 50.0]
  ])

  assert.equal(error, null)
  assert.deepEqual(points.map(point => point.date), ['2004-04-20', '2004-10-31', '2013-10-31'])
})

test('导入解析：跳过的空行不能把后面的报错行号带偏', () => {
  // 第 3 行是空行、第 4 行才是坏数据：提示必须说第 4 行。
  // 按"过滤后的下标"编号会指向那个空行，用户照着去找只会更糊涂。
  const { error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 50.0],
    ['', '', ''],
    ['2004-10-31', '', 49.8535]
  ])

  assert.match(error, /第 4 行/)
})

test('导入解析：年份这类整数不能被当成 Excel 日期序列号', () => {
  // 日期列里放错列（比如放成年份 2004）时它也是个数字：当成序列号会算成 1905 年，
  // 然后一路导入成功——每个点的年份都是错的，却看不出哪里错。
  const { points, error } = parseSeriesFile([
    HEADER,
    [2004, 0.0012918, 50.0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.deepEqual(points, [])
  assert.match(error, /第 2 行/)
  assert.match(error, /日期/)
})

test('导入解析：1904 日期系统的工作簿要按 1904 换算', () => {
  // 2004-04-20 在 1904 系统里是 36635、2004-10-31 是 36829。
  // 不认这个标志会按 1900 系统解释，每个日期静默偏 1462 天，而且照样导入成功。
  const rows = [
    HEADER,
    [36635, 0.0012918, 50.0],
    [36829, 0.9164784, 49.8535]
  ]

  const shifted = parseSeriesFile(rows)
  assert.equal(shifted.error, null, '按 1900 系统解释也能解析出来——所以这个偏移是静默的')
  assert.notEqual(shifted.points[0].date, '2004-04-20')

  const { points, error } = parseSeriesFile(rows, { date1904: true })
  assert.equal(error, null)
  assert.deepEqual(points.map(point => point.date), ['2004-04-20', '2004-10-31'])
})

test('导入解析：月或日越界要当场判死', () => {
  // '2004/13/45' 会被拼成 '2004-13-45'，后端反序列化 LocalDate 失败后只回一个看不懂的 400
  const { error } = parseSeriesFile([
    HEADER,
    ['2004/13/45', 0.0012918, 50.0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.match(error, /第 2 行/)
  assert.match(error, /日期/)
})

test('导入解析：同一天出现两次要报错', () => {
  const { error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 50.0],
    ['2004-04-20', 0.9164784, 49.8535]
  ])

  assert.match(error, /第 3 行/)
  assert.match(error, /重复/)
})

test('导入解析：空单元格不能当成 0', () => {
  const { points, error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', '', 50.0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.deepEqual(points, [])
  assert.match(error, /第 2 行/)
  assert.match(error, /累产气/)

  // 全空行要直接跳过，而不是报一个"第 N 行没有数据"
  const skipped = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 50.0],
    ['', '', ''],
    ['2004-10-31', 0.9164784, 49.8535]
  ])
  assert.equal(skipped.error, null)
  assert.equal(skipped.points.length, 2)
})

test('导入解析：地层压力越界要报错', () => {
  const { error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 0],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.match(error, /第 2 行/)
  assert.match(error, /地层压力/)
})

test('导入解析：只有 1 个点要报错', () => {
  const { error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918, 50.0],
    ['', '', '']
  ])

  assert.match(error, /两个/)
})

test('导入解析：列数不足要报错', () => {
  const { error } = parseSeriesFile([
    HEADER,
    ['2004-04-20', 0.0012918],
    ['2004-10-31', 0.9164784, 49.8535]
  ])

  assert.match(error, /第 2 行/)
  assert.match(error, /三列/)
})

test('导入解析：没有数据行要报错', () => {
  assert.match(parseSeriesFile([HEADER]).error, /没有|两个/)
  assert.match(parseSeriesFile([]).error, /没有/)
})

// ---------------------------------------------------------------------------
// 口径换算
// ---------------------------------------------------------------------------

// 本机实测的一组入参（数据库口径），用于下面两个方向互为逆运算的用例。
const APP_INPUTS = {
  gasReservoirType: 1,
  originalPressure: 50_000_000,
  formationTemperature: 353.15,
  originalGasInPlace: 23.398270898104453,
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

test('累产气量不再是界面参数：它逐点来自导入的时序数据', () => {
  // 表单里删掉了这一项。若换算函数还带着它，就会出现"界面填的值到底用没用"的歧义。
  const form = toDisplayInputs(APP_INPUTS)
  assert.equal('cumulativeGasProduction' in form, false)

  const back = toAppInputs(toDisplayInputs(APP_INPUTS))
  assert.equal(back.cumulativeGasProduction, null)
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

test('黏度计算方法不再由界面提供，固定发 0', () => {
  // 参数表与原平台 MBE 表单对齐后删掉了「天然气黏度计算方法」：
  // 它既不在平台表单里，也不在导入 PVT 模板里；平台自己的界面同样固定发 0。
  const back = toAppInputs({ ...toDisplayInputs(APP_INPUTS), viscosityMethod: 2 })

  assert.equal(back.gasPvtParam.viscosityMethod, 0)
})
