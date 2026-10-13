const MAX_INPUT_BYTES = 64 * 1024 * 1024
const MAX_POINTS = 500_000
const NUMBER_PATTERN = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[Ee][+-]?\d+)?$/
const GRDECL_NUMBER_PATTERN = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[EeDd][+-]?\d+)?$/

const normalizeLengthUnit = value => {
  const unit = String(value || '').trim().toUpperCase()
  if (unit === 'FEET' || unit === 'FOOT' || unit === 'FT') return 'FEET'
  if (unit === 'METRES' || unit === 'METERS' || unit === 'METRE' || unit === 'METER' || unit === 'M') return 'METRES'
  return null
}

const lengthUnitInMetres = unit => unit === 'FEET' ? 0.3048 : unit === 'METRES' ? 1 : null

const keywordRows = text => String(text || '').split(/\r?\n/).map(row => row.replace(/--.*$/, ''))

const keywordBlock = (rows, keyword, { optional = false, inline = false } = {}) => {
  const matches = []
  rows.forEach((row, index) => {
    const match = row.match(new RegExp(`^\\s*${keyword}\\b(.*)$`, 'i'))
    if (match) matches.push({ index, tail: match[1] })
  })
  if (matches.length > 1) throw new Error(`GRDECL 中 ${keyword} 出现多次，不能安全配准。`)
  if (!matches.length) {
    if (optional) return null
    throw new Error(`GRDECL 缺少 ${keyword}，不能安全配准。`)
  }
  const { index, tail } = matches[0]
  let body = tail
  if (inline && body.trim() && !body.includes('/')) return body.trim()
  if (body.includes('/')) return body.slice(0, body.indexOf('/')).trim()
  for (let cursor = index + 1; cursor < rows.length; cursor++) {
    const row = rows[cursor]
    if (row.includes('/')) {
      body += ` ${row.slice(0, row.indexOf('/'))}`
      return body.trim()
    }
    body += ` ${row}`
    if (/^\s*[A-Z][A-Z0-9_]*\s*$/i.test(row) && row.trim()) {
      throw new Error(`${keyword} 内容缺少结束符 /。`)
    }
  }
  if (inline && body.trim()) return body.trim()
  throw new Error(`${keyword} 内容缺少结束符 /。`)
}

const strictNumbers = (body, keyword, expected) => {
  const tokens = body.trim().split(/\s+/).filter(Boolean)
  if (tokens.length !== expected || tokens.some(token => !GRDECL_NUMBER_PATTERN.test(token))) {
    throw new Error(`${keyword} 必须恰有 ${expected} 个有限数值。`)
  }
  const values = tokens.map(token => Number(token.replace(/[Dd]/g, 'E')))
  if (values.some(value => !Number.isFinite(value))) throw new Error(`${keyword} 包含非有限数值。`)
  return values
}

/** Parse the documented GRDECL map/grid reference needed to transform survey X/Y. */
export function parseEclipseGridReference(text) {
  if (typeof text !== 'string' || !text.trim()) throw new Error('请选择非空 GRDECL 网格几何文件。')
  if (new TextEncoder().encode(text).byteLength > 64 * 1024 * 1024) throw new Error('GRDECL 网格参考文件不得超过 64 MiB。')
  const rows = keywordRows(text)
  const axes = strictNumbers(keywordBlock(rows, 'MAPAXES'), 'MAPAXES', 6)
  const gridUnitBody = keywordBlock(rows, 'GRIDUNIT')
  const gridUnit = normalizeLengthUnit(gridUnitBody.replace(/[/'"]/g, '').trim().split(/\s+/)[0])
  if (!gridUnit) throw new Error('当前只支持明确声明 FEET 或 METRES 的 GRIDUNIT。')
  const mapUnitBody = keywordBlock(rows, 'MAPUNITS', { optional: true, inline: true })
  let mapUnit = null
  if (mapUnitBody) {
    mapUnit = normalizeLengthUnit(mapUnitBody.replace(/[/'"]/g, '').trim().split(/\s+/)[0])
    if (!mapUnit) throw new Error('MAPUNITS 必须明确为 FEET 或 METRES。')
  }
  const [x1, y1, x2, y2, x3, y3] = axes
  const xAxis = { x: x3 - x2, y: y3 - y2 }
  const yAxis = { x: x1 - x2, y: y1 - y2 }
  const xLength = Math.hypot(xAxis.x, xAxis.y)
  const yLength = Math.hypot(yAxis.x, yAxis.y)
  const determinant = xAxis.x * yAxis.y - yAxis.x * xAxis.y
  if (xLength <= 1e-9 || yLength <= 1e-9 || Math.abs(determinant) <= xLength * yLength * 1e-8) {
    throw new Error('MAPAXES 的原点与 X/Y 轴点重合或共线，不能计算坐标变换。')
  }
  return {
    mapAxes: { x1: { x: x1, y: y1 }, origin: { x: x2, y: y2 }, x3: { x: x3, y: y3 } },
    xAxis: { x: xAxis.x / xLength, y: xAxis.y / xLength },
    yAxis: { x: yAxis.x / yLength, y: yAxis.y / yLength },
    gridUnit,
    mapUnit,
    mapUnitNeedsConfirmation: mapUnit === null
  }
}

/** Convert one survey point from map XY/TVD units to the GRDECL grid coordinates. */
export function mapSurveyPointToGrid(point, reference, {
  mapUnitOverride = '', gridZAtTvdZero, tvdDirection, xyUnit, tvdUnit
} = {}) {
  const mapUnit = reference?.mapUnit || normalizeLengthUnit(mapUnitOverride)
  if (!reference?.mapAxes || !mapUnit) throw new Error('MAPUNITS 未确定；请从该模型的 Schedule 配置/来源确认后选择。')
  if (!point || [point.x, point.y, point.tvd].some(value => value == null || String(value).trim() === '') ||
      ![point.x, point.y, point.tvd].every(value => Number.isFinite(Number(value)))) {
    throw new Error('偏斜调查点缺少有效 X/Y/TVD。')
  }
  if (String(gridZAtTvdZero ?? '').trim() === '' || !Number.isFinite(Number(gridZAtTvdZero)) || !['POSITIVE', 'NEGATIVE'].includes(tvdDirection)) {
    throw new Error('必须明确输入 EGRID 中 TVD=0 对应的 Z 值及 TVD 增加方向。')
  }
  const xyMetres = lengthUnitInMetres(normalizeLengthUnit(xyUnit || point.xyUnit))
  const mapMetres = lengthUnitInMetres(mapUnit)
  const gridMetres = lengthUnitInMetres(reference.gridUnit)
  const depthMetres = lengthUnitInMetres(normalizeLengthUnit(tvdUnit || point.tvdUnit))
  if (xyMetres == null || mapMetres == null || gridMetres == null || depthMetres == null) throw new Error('XY/TVD 与网格长度单位不受支持。')
  const xyToMapScale = xyMetres / mapMetres
  const mapToGridScale = mapMetres / gridMetres
  const depthScale = depthMetres / gridMetres
  const deltaX = (Number(point.x) * xyToMapScale - reference.mapAxes.origin.x) * mapToGridScale
  const deltaY = (Number(point.y) * xyToMapScale - reference.mapAxes.origin.y) * mapToGridScale
  const { x: ex, y: ey } = reference.xAxis
  const { x: fx, y: fy } = reference.yAxis
  const determinant = ex * fy - fx * ey
  if (Math.abs(determinant) < 1e-8) throw new Error('MAPAXES 方向基底不可逆。')
  const gridX = (deltaX * fy - fx * deltaY) / determinant
  const gridY = (ex * deltaY - deltaX * ey) / determinant
  const gridZ = Number(gridZAtTvdZero) + (tvdDirection === 'POSITIVE' ? 1 : -1) * Number(point.tvd) * depthScale
  if (![gridX, gridY, gridZ].every(Number.isFinite)) throw new Error('坐标变换产生非有限网格位置。')
  return { x: gridX, y: gridY, z: gridZ }
}

const fail = message => { throw new Error(message) }
const numeric = value => NUMBER_PATTERN.test(value) && Number.isFinite(Number(value))
const basename = value => String(value || '').split(/[\\/]/).pop().toUpperCase()

function parseControl(text) {
  const directives = new Map()
  const attributes = new Map()
  let dataFile = ''
  let ended = false
  for (const rawLine of text.replace(/^\uFEFF/, '').split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line || line.startsWith('--')) continue
    const tokens = line.split(/\s+/)
    const keyword = tokens[0].toUpperCase()
    if (keyword === 'END') { ended = true; break }
    if (keyword === 'ATTRIBUTE') {
      if (tokens.length !== 6 || tokens[2].toUpperCase() !== 'POSITION' || tokens[4].toUpperCase() !== 'UNITS') {
        fail('首版只支持官方 EX3 风格的 ATTRIBUTE 名称 POSITION n UNITS FEET/METRES 定义。')
      }
      const name = tokens[1].toUpperCase()
      const position = Number(tokens[3])
      const units = tokens[5].toUpperCase()
      if (!name || !Number.isInteger(position) || position < 1 || !['FEET', 'METRES'].includes(units) || attributes.has(name)) {
        fail(`CNT 中 ATTRIBUTE ${tokens[1] || ''} 定义无效或重复。`)
      }
      attributes.set(name, { position, units })
      continue
    }
    if (keyword === 'FILE') {
      if (tokens.length !== 2 || dataFile) fail('CNT 必须且只能引用一个 DEV 数据文件。')
      dataFile = tokens[1]
      continue
    }
    if (directives.has(keyword)) fail(`CNT 指令 ${keyword} 重复，首版不接受有歧义的定义。`)
    directives.set(keyword, tokens.slice(1))
  }
  if (!ended) fail('CNT 缺少 END 结束标记。')
  const one = name => directives.get(name) || []
  const fileType = one('FILETYPE')[0]?.toUpperCase()
  if (fileType !== 'MULTIWELL') fail('首版只支持官方 EX3 使用的 FILETYPE MULTIWELL。')
  const start = Number(one('START')[0])
  const recordLength = Number(one('RECLEN')[0])
  const endpoint = one('ENDPOINT')
  const wellName = one('WELLNAME')
  if (!Number.isInteger(start) || start < 2 || start > 20 || !Number.isInteger(recordLength) || recordLength < 4 || recordLength > 32) {
    fail('CNT 的 START 或 RECLEN 不在首版支持范围。')
  }
  if (endpoint.length !== 2 || endpoint[0].toUpperCase() !== 'MARKER' || !numeric(endpoint[1])) {
    fail('CNT 的 ENDPOINT 必须是有限数值 MARKER。')
  }
  if (wellName.length !== 5 || wellName[0].toUpperCase() !== 'HEADER' ||
      wellName[1].toUpperCase() !== 'LINE' || wellName[3].toUpperCase() !== 'ITEM') {
    fail('CNT 的 WELLNAME 必须通过 HEADER LINE n ITEM m 指定井名列。')
  }
  const headerLine = Number(wellName[2])
  const nameItem = Number(wellName[4])
  if (!Number.isInteger(headerLine) || headerLine < 1 || headerLine >= start ||
      !Number.isInteger(nameItem) || nameItem < 1 || nameItem > recordLength) {
    fail('CNT 的 WELLNAME header 行/列与 START、RECLEN 不匹配。')
  }
  for (const name of ['XPOS', 'YPOS', 'TVD', 'MD']) {
    if (!attributes.has(name)) fail(`CNT 缺少必需轨迹属性 ${name}。`)
    if (attributes.get(name).position > recordLength) fail(`CNT 属性 ${name} 的列号超过 RECLEN。`)
  }
  if (attributes.get('XPOS').units !== attributes.get('YPOS').units) {
    fail('首版 X/Y 平面预览要求 XPOS 与 YPOS 使用相同单位；源文件不会被修改。')
  }
  const positions = [...attributes.values()].map(attribute => attribute.position)
  if (new Set(positions).size !== positions.length) fail('CNT 多个 ATTRIBUTE 指向同一数据列。')
  if (!dataFile || /[\\/]/.test(dataFile) || basename(dataFile) === '') fail('CNT 的 FILE 必须引用上传文件中的单个相对 DEV 文件名。')
  return { start, recordLength, marker: Number(endpoint[1]), headerLine, nameItem, attributes, dataFile }
}

function parseData(text, control) {
  const blocks = []
  let block = []
  let pointCount = 0
  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/)
  for (let index = 0; index < lines.length; index++) {
    const raw = lines[index]
    const trimmed = raw.trim()
    if (trimmed && !trimmed.startsWith('--')) {
      const tokens = trimmed.split(/\s+/)
      if (tokens.length === 1 && numeric(tokens[0]) && Number(tokens[0]) === control.marker) {
        if (!block.length) fail(`DEV 第 ${index + 1} 行出现了孤立终止标记。`)
        blocks.push(block)
        block = []
        continue
      }
    }
    block.push({ raw, lineNumber: index + 1 })
  }
  if (block.some(row => row.raw.trim() && !row.raw.trim().startsWith('--'))) fail('DEV 最后一口井缺少 CNT 定义的终止标记。')
  if (!blocks.length) fail('DEV 中没有按 CNT marker 分隔的井轨迹记录。')

  const names = new Set()
  const wells = blocks.map(rows => {
    const header = rows[control.headerLine - 1]?.raw?.trim()
    if (!header || header.startsWith('--')) fail(`DEV 第 ${rows[0]?.lineNumber || 1} 口井缺少 CNT 指定的井名 header 行。`)
    const headerTokens = header.split(/\s+/)
    const name = headerTokens[control.nameItem - 1]?.replace(/:$/, '')
    if (!name || name.length > 100 || /[\u0000-\u001f\u007f]/.test(name)) fail('DEV 井名为空或包含不安全字符。')
    if (names.has(name)) fail(`DEV 井名 ${name} 重复，无法安全建立井名映射。`)
    names.add(name)
    const points = []
    for (const row of rows.slice(control.start - 1)) {
      const trimmed = row.raw.trim()
      if (!trimmed || trimmed.startsWith('--')) continue
      const tokens = trimmed.split(/\s+/)
      if (tokens.length !== control.recordLength || tokens.some(token => !numeric(token))) {
        fail(`DEV 第 ${row.lineNumber} 行字段数/数值与 CNT RECLEN=${control.recordLength} 不匹配。`)
      }
      const value = name => Number(tokens[control.attributes.get(name).position - 1])
      points.push({ lineNumber: row.lineNumber, x: value('XPOS'), y: value('YPOS'), tvd: value('TVD'), md: value('MD') })
      pointCount++
      if (pointCount > MAX_POINTS) fail(`DEV 超过 ${MAX_POINTS} 个测点的安全上限。`)
    }
    if (points.length < 2) fail(`井 ${name} 少于 2 个有效测点，不能作为轨迹显示。`)
    const mdIncreases = points.every((point, index) => index === 0 || point.md > points[index - 1].md)
    const range = values => values.reduce((bounds, item) => [Math.min(bounds[0], item), Math.max(bounds[1], item)], [Infinity, -Infinity])
    return {
      name,
      points,
      mdIncreases,
      ranges: {
        x: range(points.map(point => point.x)),
        y: range(points.map(point => point.y)),
        tvd: range(points.map(point => point.tvd)),
        md: [points[0].md, points.at(-1).md]
      }
    }
  })
  const units = Object.fromEntries(['XPOS', 'YPOS', 'TVD', 'MD'].map(name => [name.toLowerCase(), control.attributes.get(name).units]))
  return {
    schemaVersion: 'eclipse-deviation-survey/1',
    sourceType: 'GRID_CONTROL_DEV_READ_ONLY',
    xyUnits: units.xpos,
    depthUnits: units.tvd === units.md ? units.tvd : `TVD ${units.tvd} / MD ${units.md}`,
    units,
    dataFile: control.dataFile,
    wells,
    wellCount: wells.length,
    totalPoints: pointCount,
    warnings: wells.filter(well => !well.mdIncreases).map(well => `井 ${well.name} 的 MD 未严格递增；原始记录已保留，不能直接用于轨迹定位。`)
  }
}

export function parseEclipseDeviationSurvey(controlText, dataText, uploadedDataName) {
  if (typeof controlText !== 'string' || typeof dataText !== 'string' ||
      controlText.length > MAX_INPUT_BYTES || dataText.length > MAX_INPUT_BYTES) {
    fail('CNT/DEV 必须是可读取的文本文件，且每个文件不得超过 64 MiB。')
  }
  const control = parseControl(controlText)
  if (basename(control.dataFile) !== basename(uploadedDataName)) {
    fail(`CNT 声明的数据文件 ${control.dataFile} 与上传文件 ${uploadedDataName || '(未选择)'} 不匹配。`)
  }
  return parseData(dataText, control)
}

export function surveyPlanPoints(well, width = 600, height = 220, padding = 16) {
  if (!well || !Array.isArray(well.points) || well.points.length < 2) return []
  const xBounds = well.points.reduce((bounds, point) => [Math.min(bounds[0], point.x), Math.max(bounds[1], point.x)], [Infinity, -Infinity])
  const yBounds = well.points.reduce((bounds, point) => [Math.min(bounds[0], point.y), Math.max(bounds[1], point.y)], [Infinity, -Infinity])
  const [xMin, xMax] = xBounds
  const [yMin, yMax] = yBounds
  const scale = Math.min((width - padding * 2) / Math.max(xMax - xMin, 1e-12), (height - padding * 2) / Math.max(yMax - yMin, 1e-12))
  const plottedWidth = (xMax - xMin) * scale
  const plottedHeight = (yMax - yMin) * scale
  const offsetX = (width - plottedWidth) / 2
  const offsetY = (height - plottedHeight) / 2
  return well.points.map(point => ({
    x: Number((offsetX + (point.x - xMin) * scale).toFixed(2)),
    y: Number((height - offsetY - (point.y - yMin) * scale).toFixed(2)),
    md: point.md,
    tvd: point.tvd
  }))
}
