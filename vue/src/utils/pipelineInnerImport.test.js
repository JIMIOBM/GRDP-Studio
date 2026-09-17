import test from 'node:test'
import assert from 'node:assert/strict'
import {
  assertInnerImportSheet,
  innerInputColumns,
  innerPropertyColumns,
  innerImportColumns,
  innerImportTemplateRows,
  parseInnerImportRows,
  parseInnerImportCsv,
  innerImportCsv
} from './pipelineInnerImport.js'

const headers = [
  '序号', '管道名称', '管内径 D（mm）', '工况体积流量 Q（m³/s）',
  '物性计算压力（MPa绝压）', '物性计算温度（℃）', '气体导热系数 λ（W/(m·K)）'
]

const graph = () => ({
  nodes: [{ id: 'well', name: '井口' }, { id: 'station', name: '站场' }],
  edges: [
    { id: 'pipe-b', name: '井口至阀室', source: 'well', target: 'valve', parameters: { diameterMm: 100, lengthM: 700 } },
    { id: 'pipe-a', name: '阀室至站场', source: 'valve', target: 'station', parameters: { diameterMm: 150, lengthM: 500 } }
  ]
})

const rows = () => [
  [...headers],
  [1, '井口至阀室', 100, 0.4, 8, 39, 0.031],
  [2, '阀室至站场', 150, '', '', '', 0.034]
]
const conductivitySettings = () => ({segments:[{edgeId:'pipe-b',gasConductivityWmK:0.031},{edgeId:'pipe-a',gasConductivityWmK:0.034}]})

test('import includes manual conductivity and four operating fields but excludes PVT density, viscosity and Cp', () => {
  assert.equal(innerInputColumns.length, 5)
  assert.deepEqual(innerInputColumns.map(column => column.key), [
    'innerDiameterMm', 'actualFlowM3s', 'propertyPressureMpa', 'propertyTemperatureC', 'gasConductivityWmK'
  ])
  assert.deepEqual(innerPropertyColumns.map(column => column.key), ['densityKgM3', 'viscosityMpaS', 'cpJkgK'])
  assert.ok(innerPropertyColumns.every(column => !innerImportColumns.some(input => input.key === column.key)))
  assert.deepEqual(innerInputColumns.map(column => `${column.label}（${column.unit}）`), headers.slice(2))
  assert.ok(Object.isFrozen(innerInputColumns))
  assert.ok(innerInputColumns.every(Object.isFrozen))
})

test('actual XLSX and XLS files round trip blank properties and formula-like pipe names as text', async () => {
  const XLSX = await import('xlsx')
  const topology = graph()
  topology.edges[0].name = '=SUM(1,2)'
  topology.edges[1].name = '管道,"A"\n第二行'
  const template = innerImportTemplateRows(topology,conductivitySettings())
  for (const bookType of ['xlsx', 'biff8']) {
    const workbook = XLSX.utils.book_new()
    const sheet = XLSX.utils.aoa_to_sheet(template)
    XLSX.utils.book_append_sheet(workbook, sheet, '内壁参数')
    const bytes = XLSX.write(workbook, { type: 'buffer', bookType })
    const restored = XLSX.read(bytes, { type: 'buffer', cellFormula: true, cellDates: true })
    const resultSheet = restored.Sheets[restored.SheetNames[0]]
    assert.equal(resultSheet.B2.t, 's')
    assert.equal(resultSheet.B2.f, undefined)
    assert.equal(resultSheet.B2.v, '=SUM(1,2)')
    const table = XLSX.utils.sheet_to_json(resultSheet, { header: 1, raw: true, defval: null, blankrows: true })
    const parsed = parseInnerImportRows(table, topology)
    assert.deepEqual(parsed.segments.map(segment => segment.edgeId), ['pipe-b', 'pipe-a'])
    assert.deepEqual(parsed.segments.map(segment => segment.innerDiameterMm), [100, 150])
    assert.ok(parsed.segments.every(segment => !Object.hasOwn(segment, 'cpJkgK') && segment.actualFlowM3s === null))
  }
})

test('actual XLSX error cells without formulas are rejected before worksheet conversion can erase properties', async () => {
  const XLSX = await import('xlsx')
  for (const [code, text] of [[0x07, '#DIV/0!'], [0x2a, '#N/A']]) {
    const workbook = XLSX.utils.book_new()
    const sheet = XLSX.utils.aoa_to_sheet(rows())
    sheet.D2 = { t: 'e', v: code, w: text }
    XLSX.utils.book_append_sheet(workbook, sheet, '内壁参数')
    const restored = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellFormula: true })
    const importedSheet = restored.Sheets[restored.SheetNames[0]]
    assert.equal(importedSheet.D2.t, 'e')
    assert.equal(importedSheet.D2.f, undefined)
    const unsafeRows = XLSX.utils.sheet_to_json(importedSheet, { header: 1, raw: true, defval: null })
    assert.equal(unsafeRows[1][3], null)
    assert.throws(() => assertInnerImportSheet(importedSheet), /D2.*Excel.*错误/)
  }
})

test('worksheet validation rejects formulas but preserves genuine blanks and numeric data', () => {
  assert.throws(() => assertInnerImportSheet({ D2: { t: 'n', v: 3, f: '1+2' } }), /D2.*公式/)
  assert.throws(() => assertInnerImportSheet({ E3: { t: 'n', v: 3, F: 'E3:E4' } }), /E3.*公式/)
  assert.doesNotThrow(() => assertInnerImportSheet({ '!ref': 'A1:D2', A1: { t: 's', v: '=管道' }, D2: { t: 'z' } }))
})

test('template describes seven columns and leaves conductivity blank until supplied', () => {
  const topology = graph(), before = structuredClone(topology)
  assert.deepEqual(innerImportColumns.map(column => column.label), headers)
  assert.equal(new Set(innerImportColumns.map(column => column.key)).size, 7)
  assert.ok(innerImportColumns.every(column => typeof column.key === 'string' && column.key.length > 0))
  const template = innerImportTemplateRows(topology)
  assert.deepEqual(template, [
    headers,
    [1, '井口至阀室', 100, '', '', '', ''],
    [2, '阀室至站场', 150, '', '', '', '']
  ])
  assert.deepEqual(topology, before)
  assert.throws(()=>parseInnerImportRows(template,topology),/气体导热系数.*不能为空/)
  const populated=innerImportTemplateRows(topology,conductivitySettings())
  assert.deepEqual(parseInnerImportRows(parseInnerImportCsv(innerImportCsv(populated)), topology), {
    segments: [
      { edgeId: 'pipe-b', innerDiameterMm: 100, actualFlowM3s: null, propertyPressureMpa: null, propertyTemperatureC: null,gasConductivityWmK:0.031 },
      { edgeId: 'pipe-a', innerDiameterMm: 150, actualFlowM3s: null, propertyPressureMpa: null, propertyTemperatureC: null,gasConductivityWmK:0.034 }
    ]
  })
})

test('import matches both sequence and name, restores topology order and accepts decimal scientific notation', () => {
  const data = [headers, [2, '阀室至站场', '1.5e2', '', '', '',0.034],
    ['1', '井口至阀室', '100.5', '4E-1', '8e0', '-2.2e1','3.1e-2']]
  assert.deepEqual(parseInnerImportRows(data, graph()), {
    segments: [
      { edgeId: 'pipe-b', innerDiameterMm: 100.5, actualFlowM3s: 0.4, propertyPressureMpa: 8, propertyTemperatureC: -22,gasConductivityWmK:0.031 },
      { edgeId: 'pipe-a', innerDiameterMm: 150, actualFlowM3s: null, propertyPressureMpa: null, propertyTemperatureC: null,gasConductivityWmK:0.034 }
    ]
  })
})

test('template and import do not change the topology or the supplied worksheet', () => {
  const topology = graph(), data = rows()
  const beforeTopology = structuredClone(topology), beforeRows = structuredClone(data)
  const imported = parseInnerImportRows(data, topology)
  imported.segments[0].innerDiameterMm = 999
  const template = innerImportTemplateRows(topology)
  template[1][1] = '模板里的修改'
  assert.deepEqual(topology, beforeTopology)
  assert.deepEqual(data, beforeRows)
})

test('a UTF-8 BOM on the first header is accepted', () => {
  const data = rows()
  data[0][0] = '\uFEFF序号'
  assert.deepEqual(parseInnerImportRows(data, graph()), parseInnerImportRows(rows(), graph()))
})

test('headers, units and worksheet width are validated before importing', () => {
  const cases = [
    data => { data[0][2] = '管内径 D（m）' },
    data => { [data[0][3], data[0][4]] = [data[0][4], data[0][3]] },
    data => { data[0].push('额外列') },
    data => { data[1].push('额外数据') }
  ]
  for (const alter of cases) {
    const data = rows()
    alter(data)
    assert.throws(() => parseInnerImportRows(data, graph()))
  }
})

test('wrong names, duplicate records, unknown sequences and missing pipes cannot silently overwrite a pipe', () => {
  const cases = [
    data => { data[1][1] = '阀室至站场' },
    data => { data[1][1] = '已改名管道' },
    data => { data.push([...data[1]]) },
    data => { data[2][0] = 3 },
    data => { data[2][0] = 0 },
    data => { data[2][0] = 1.5 },
    data => { data.pop() }
  ]
  for (const alter of cases) {
    const data = rows(), topology = graph()
    alter(data)
    const before = structuredClone(data)
    assert.throws(() => parseInnerImportRows(data, topology))
    assert.deepEqual(data, before)
  }
})

test('diameter, flow and absolute pressure must be strictly positive and finite', () => {
  for (let column = 2; column < 5; column++) {
    for (const invalid of [0, -1, '-0', '1e309', Number.NaN, Number.POSITIVE_INFINITY]) {
      const data = rows()
      data[1][column] = invalid
      assert.throws(() => parseInnerImportRows(data, graph()), `column ${column} accepted ${String(invalid)}`)
    }
  }
})

test('nondecimal and nonnumeric values are rejected rather than coerced into engineering quantities', () => {
  for (const invalid of ['0x10', '0b10', '1_000', '1,000', '20 mm', 'NaN', 'Infinity', true, [], {}]) {
    const data = rows()
    data[1][3] = invalid
    assert.throws(() => parseInnerImportRows(data, graph()), `accepted ${JSON.stringify(invalid)}`)
  }
})

test('diameter is required even though blank operating conditions are allowed', () => {
  for (const missing of ['', null, undefined]) {
    const data = rows()
    data[1][2] = missing
    assert.throws(() => parseInnerImportRows(data, graph()))
  }
  const data = rows()
  data[1].splice(3, 3, null, '', null)
  assert.deepEqual(parseInnerImportRows(data, graph()).segments[0], {
    edgeId: 'pipe-b', innerDiameterMm: 100, actualFlowM3s: null, propertyPressureMpa: null, propertyTemperatureC: null,gasConductivityWmK:0.031
  })
})

test('temperatures can be zero or negative Celsius but must exceed absolute zero', () => {
  for(const value of [-273.14, -73.15, -1, 0, 39, 600]) {
    const data=rows();data[1][5]=value
    assert.equal(parseInnerImportRows(data, graph()).segments[0].propertyTemperatureC,value)
  }
  for(const value of [-273.15,-274,NaN,Infinity]) {
    const data=rows();data[1][5]=value
    assert.throws(()=>parseInnerImportRows(data, graph()))
  }
})

test('old manual gas-property templates cannot bypass the PVT selection', () => {
  const old=[['序号','管道名称','管内径 D（mm）','气体密度 ρ（kg/m³）','工况体积流量 Q（m³/s）',
    '动力黏度 μ（mPa·s）','定压比热容 Cp（J/(kg·K)）','气体导热系数 λ（W/(m·K)）'],
    [1,'井口至阀室',100,45.2,0.4,0.012,2200,0.031],[2,'阀室至站场',150,45.2,0.4,0.012,2200,0.031]]
  assert.throws(()=>parseInnerImportRows(old, graph()), /PVT.*重新下载/)
})

test('conductivity is mandatory for every imported pipe and cannot be zero, negative, nonfinite or formula text',()=>{
 for(const value of ['',null,undefined,0,-1,NaN,Infinity,'=1+2','0x10']) {
  const data=rows();data[2][6]=value
  assert.throws(()=>parseInnerImportRows(data,graph()),/气体导热系数/)
 }
})

test('CSV supports quoted commas, doubled quotes, embedded newlines and CRLF row endings', () => {
  const csv = 'one,"name,with,commas","say ""yes"""\r\n"two\nlines","three\r\nlines",tail\r\n'
  assert.deepEqual(parseInnerImportCsv(csv), [
    ['one', 'name,with,commas', 'say "yes"'],
    ['two\nlines', 'three\r\nlines', 'tail']
  ])
})

test('CSV export and reimport retain special pipe names including a leading equals sign', () => {
  const topology = graph()
  topology.edges[0].name = '=井口,"一号"\n至阀室'
  topology.edges[1].name = '阀室\r\n至站场'
  const template = innerImportTemplateRows(topology,conductivitySettings())
  const parsedRows = parseInnerImportCsv(innerImportCsv(template))
  assert.equal(parsedRows[1][1], topology.edges[0].name)
  assert.equal(parsedRows[2][1], topology.edges[1].name)
  assert.deepEqual(parseInnerImportRows(parsedRows, topology), parseInnerImportRows(template, topology))
})

test('malformed CSV quoting is rejected instead of shifting columns or accepting truncated records', () => {
  for (const csv of ['plain"quote,next', '"unclosed,next', '"closed"junk,next', '"first" "second",next']) {
    assert.throws(() => parseInnerImportCsv(csv), `accepted ${JSON.stringify(csv)}`)
  }
})
