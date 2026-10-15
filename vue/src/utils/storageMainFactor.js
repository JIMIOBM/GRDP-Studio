/**
 * 主控因素分析的展示层纯函数。
 *
 * 这里**不做单位换算以外的计算**，也**不重算差异**：序列的差异与百分比偏差都由后端算好
 * （backend/.../storagemainfactor/）。同一件事留第二处实现，就会出现前后端不一致的那类
 * 错值——本功能此前正是被这个坑绊了很久。
 *
 * 所以本文件只做三件事：输入文本转数值、算出来的数值格式化、
 * 导入文件的解析与校验。全部为纯函数，不依赖 Vue / echarts，便于用 node:test 直接测。
 */

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

// ---------------------------------------------------------------------------
// 口径换算：数据库口径（Pa / K / 小数 / 1/Pa）↔ 界面口径（MPa / ℃ / % / MPa⁻¹）
//
// 界面用 MPa/℃/% 是为了与「微观损耗」以及原平台自己的表单一致——
// 用户看到的量级应该是 50 MPa，而不是 50000000 Pa。
//
// 这里**不碰提交给原平台的载荷**：那份载荷由后端
// StorageMainFactorCalculator.toolboxPayload 一处组装，前端不参与，
// 这样"平台口径"就只有一个转换点。
//
// 累产气量**不在这里**：它逐点来自导入的时序数据，由后端逐点替换，
// 界面上既没有这一项，也就不该在这里留一个"填了但不一定用"的字段。
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
    // 累产气量逐点来自时序数据，这里没有、也不该有：后端按点替换后才发给原平台。
    cumulativeGasProduction: null,
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

// ---------------------------------------------------------------------------
// 双线折线图
// ---------------------------------------------------------------------------

/**
 * 理论值 vs 实际值的双线折线序列。两种视图：
 *   {@code absolute}  —— 两条线都是 MPa（主视图）
 *   {@code deviation} —— 一条偏差百分比线（看偏离随时间放大还是收敛）
 *
 * 两个视图**共用同一组日期与实测值**，切换视图不会改变 x 轴。
 *
 * 理论值缺失（原平台对某个点失败）时该点为 {@code null}，echarts 会把线断开：
 * 补成 0 会在图上多出一个假的压力谷底，比断开更容易误读。
 *
 * 差异与偏差**不由这里计算**，直接取后端算好的 `deviationPercent`——
 * 前端再算一遍就是第二个定义。
 */
export function buildPressureLines(points, mode) {
  const list = Array.isArray(points) ? points : []
  const deviationView = mode === 'deviation'
  return {
    mode: deviationView ? 'deviation' : 'absolute',
    dates: list.map(point => point?.date),
    theoretical: list.map(point => toNumberOrNull(point?.theoreticalPressure)),
    actual: list.map(point => toNumberOrNull(point?.measuredPressure)),
    deviation: list.map(point => toNumberOrNull(point?.deviationPercent)),
    yName: deviationView ? '偏差 (%)' : 'MPa'
  }
}

// ---------------------------------------------------------------------------
// 导入文件的解析
// ---------------------------------------------------------------------------

/** 实测地层压力按 MPa 解释；超出即认为单位或列选错了（后端有同一道校验）。 */
const MAX_MEASURED_MPA = 300

const pad2 = value => String(value).padStart(2, '0')

/**
 * 可信的日期范围：1950-01-01 ~ 2100-12-31。
 *
 * 这个窗口不是可有可无的：日期列里如果放的是**年份或编号**（比如 2004），它同样是个整数，
 * 会被当成 Excel 序列号算成 1905 年，然后一路顺利导入——图上每个点的年份都错了，
 * 却看不出哪里错。18264 以下的整数直接判为"不是日期"。
 */
const MIN_EXCEL_SERIAL = 18264
const MAX_EXCEL_SERIAL = 73415
const MIN_YEAR = 1950
const MAX_YEAR = 2100

/** 1904 日期系统比 1900 系统少 1462 天（Mac 版 Excel 的旧约定）。 */
const DATE1904_OFFSET = 1462

/**
 * 把单元格里的日期归一成 `yyyy-MM-dd`；认不出来返回 `null`（由调用方报错，不猜）。
 *
 * 三种形态都要认：Excel 里日期本来就是个数字（`raw: true` 读出来正是序列号）、
 * 单元格格式化成日期时是 {@link Date}、以及文本里写的 `2004/4/20`。
 *
 * @param {boolean} date1904 工作簿用的是 Mac 的 1904 日期系统
 */
function toIsoDate(value, date1904 = false) {
  const fromParts = (year, month, day) => (year >= MIN_YEAR && year <= MAX_YEAR
    && month >= 1 && month <= 12 && day >= 1 && day <= 31
    ? `${year}-${pad2(month)}-${pad2(day)}`
    : null)

  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return fromParts(value.getFullYear(), value.getMonth() + 1, value.getDate())
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    const serial = date1904 ? value + DATE1904_OFFSET : value
    if (serial < MIN_EXCEL_SERIAL || serial > MAX_EXCEL_SERIAL) return null
    // 25569 = 1900-01-01 到 1970-01-01 的天数（Excel 自己那个 1900 闰年 bug 已经含在内）
    const date = new Date(Math.round((serial - 25569) * 86400 * 1000))
    return Number.isNaN(date.getTime())
      ? null
      : fromParts(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
  }
  const text = String(value ?? '').trim()
  if (text === '') return null
  const matched = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(text)
  if (matched) {
    // 月/日越界当场判死：'2004/13/45' 会拼成 '2004-13-45'，后端反序列化 LocalDate
    // 失败后只回一个看不懂的 400。错在文件里，就该在文件这一层说清楚。
    return fromParts(Number(matched[1]), Number(matched[2]), Number(matched[3]))
  }
  const parsed = new Date(text)
  return Number.isNaN(parsed.getTime())
    ? null
    : fromParts(parsed.getFullYear(), parsed.getMonth() + 1, parsed.getDate())
}

/**
 * 解析导入文件的二维数组（`XLSX.utils.sheet_to_json(sheet, { header: 1 })` 的结果）。
 *
 * **逐行校验并指出是第几行**：导入错了却只说"数据有问题"，用户面对几十行无从下手。
 * 行号是**文件里的真实行号**，跳过的空行也照样占一行——否则空行之后的所有提示都会指错行。
 *
 * 首行是表头；整行空白跳过，但**空白单元格不能当 0**——0 是合法读数。
 *
 * 只做格式与范围校验，不做业务校验（重复日期、累产气回退、点太少）——那几条后端还会
 * 再判一次；这里判是为了让用户在**上传前**就拿到带行号的提示，而不是等一次 400 往返。
 *
 * @param {Array<Array>} rows `sheet_to_json` 的二维数组，含表头行
 * @param {{ date1904?: boolean }} options 工作簿的日期系统（`WBProps.date1904`）
 * @returns {{ points: Array, error: string|null }} 有错时 points 为空
 */
export function parseSeriesFile(rows, options = {}) {
  if (!Array.isArray(rows) || rows.length === 0) {
    return { points: [], error: '文件里没有内容' }
  }
  const date1904 = options.date1904 === true
  const body = []
  rows.slice(1).forEach((row, index) => {
    if (!Array.isArray(row)) return
    if (!row.some(value => String(value ?? '').trim() !== '')) return
    body.push({ fileRow: index + 2, cells: row }) // 表头占第 1 行
  })

  const points = []
  const seen = new Set()
  for (const { fileRow, cells } of body) {
    if (cells.length < 3) {
      return { points: [], error: `第 ${fileRow} 行只有 ${cells.length} 列，需要「日期 / 累产气量 / 地层压力」三列` }
    }
    const date = toIsoDate(cells[0], date1904)
    if (date === null) {
      return { points: [], error: `第 ${fileRow} 行的日期无法识别：${cells[0]}` }
    }
    if (seen.has(date)) {
      return { points: [], error: `第 ${fileRow} 行的日期 ${date} 与前面的行重复了，同一个日期只能有一个点` }
    }
    const gas = toNumberOrNull(cells[1])
    if (gas === null || gas < 0) {
      return { points: [], error: `第 ${fileRow} 行的累产气量不是有效的非负数：${cells[1]}` }
    }
    const pressure = toNumberOrNull(cells[2])
    if (pressure === null || pressure <= 0 || pressure > MAX_MEASURED_MPA) {
      return { points: [], error: `第 ${fileRow} 行的地层压力 ${cells[2]} 不在 (0, ${MAX_MEASURED_MPA}] MPa 内` }
    }
    seen.add(date)
    points.push({ date, cumulativeGasProduction: gas, measuredPressure: pressure })
  }

  if (points.length < 2) {
    return { points: [], error: `只读到 ${points.length} 个有效数据点，折线图至少需要两个` }
  }
  // 折线图的横轴本来就按日期排，所以文件给成倒序也应该能用（与后端的做法一致）。
  // 真正要拦的是累产气回退——那说明文件选错了或列错位了。
  points.sort((left, right) => (left.date < right.date ? -1 : left.date > right.date ? 1 : 0))
  for (let index = 1; index < points.length; index++) {
    if (points[index].cumulativeGasProduction < points[index - 1].cumulativeGasProduction) {
      // 报**日期**而不是行号：排序之后行号已经不等于文件里的行号，报了反而误导。
      return {
        points: [],
        error: `累产气量在 ${points[index - 1].date} 与 ${points[index].date} 之间下降了`
          + `（${points[index - 1].cumulativeGasProduction} → ${points[index].cumulativeGasProduction}）；`
          + '累产气不可能回退，请核对导入的是不是同一个库的数据'
      }
    }
  }
  return { points, error: null }
}
