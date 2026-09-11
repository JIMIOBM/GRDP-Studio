import test from 'node:test'
import assert from 'node:assert/strict'
import { wallImportColumns, wallImportTemplateRows, parseWallImportRows } from './pipelineWallImport.js'
import {
  assertTemperatureImportSheet,
  parseTemperatureImportCsv,
  temperatureImportCsv
} from './pipelineTemperatureImport.js'

const headers = [
  '管道序号', '管道名称', '管内径（mm）', '层序号',
  '材料层名称', '厚度（mm）', '导热系数（W/(m·K)）'
]

const graph = () => ({
  nodes: [{ id: 'well', name: '井口' }, { id: 'station', name: '站场' }],
  edges: [
    { id: 'pipe-b', name: '井口至阀室', source: 'well', target: 'valve', parameters: { diameterMm: 100, lengthM: 700 } },
    { id: 'pipe-a', name: '阀室至站场', source: 'valve', target: 'station', parameters: { diameterMm: 150, lengthM: 500 } }
  ]
})

const settings = () => ({
  gas: { cpJkgK: 2200 },
  segments: [
    { edgeId: 'pipe-a', densityKgM3: 35, externalMode: 'buried', layers: [] },
    { edgeId: 'pipe-b', densityKgM3: 45, externalMode: 'air', layers: [
      { name: '钢管', thicknessMm: 8, conductivityWmK: 45 },
      { name: '保温层', thicknessMm: 20, conductivityWmK: 0.04 }
    ] }
  ]
})

const rows = () => [
  [...headers],
  [1, '井口至阀室', 100, 1, '钢管', 8, 45],
  [1, '井口至阀室', 100, 2, '保温层', '', ''],
  [2, '阀室至站场', 150, 1, '钢管', 10, 48]
]

const expected = () => ({ segments: [
  { edgeId: 'pipe-b', layers: [
    { name: '钢管', thicknessMm: 8, conductivityWmK: 45 },
    { name: '保温层', thicknessMm: null, conductivityWmK: null }
  ] },
  { edgeId: 'pipe-a', layers: [
    { name: '钢管', thicknessMm: 10, conductivityWmK: 48 }
  ] }
] })

test('material template has seven labeled columns and resolves layer names by edge ID', () => {
  assert.deepEqual(wallImportColumns.map(column => column.label), headers)
  assert.equal(new Set(wallImportColumns.map(column => column.key)).size, 7)
  assert.ok(wallImportColumns.every(column => typeof column.key === 'string' && column.key.length > 0))
  assert.deepEqual(wallImportTemplateRows(graph(), settings()), [
    headers,
    [1, '井口至阀室', 100, 1, '钢管', '', ''],
    [1, '井口至阀室', 100, 2, '保温层', '', ''],
    [2, '阀室至站场', 150, 1, '钢管', '', '']
  ])
})

test('missing or empty layer settings give one blank steel layer per saved pipe', () => {
  const template = [
    headers,
    [1, '井口至阀室', 100, 1, '钢管', '', ''],
    [2, '阀室至站场', 150, 1, '钢管', '', '']
  ]
  assert.deepEqual(wallImportTemplateRows(graph()), template)
  assert.deepEqual(wallImportTemplateRows(graph(), { segments: [
    { edgeId: 'pipe-b', layers: [] }, { edgeId: 'pipe-a' }
  ] }), template)
})

test('import sorts pipes and layers into topology order and returns only material-layer updates', () => {
  const data = rows()
  assert.deepEqual(parseWallImportRows([data[0], data[3], data[2], data[1]], graph()), expected())
})

test('decimal and scientific quantities are accepted and equivalent numeric diameters match saved geometry', () => {
  const data = rows()
  data[1][2] = '100.0'
  data[1][3] = '1'
  data[1][5] = '8e0'
  data[1][6] = '4.5E1'
  data[2][2] = '1e2'
  data[2][5] = '.2'
  data[2][6] = '4e-2'
  const result = expected()
  result.segments[0].layers[1].thicknessMm = 0.2
  result.segments[0].layers[1].conductivityWmK = 0.04
  assert.deepEqual(parseWallImportRows(data, graph()), result)
})

test('blank thickness and conductivity remain missing values with no additional layer metadata', () => {
  const data = rows()
  data[1][5] = null
  data[1][6] = undefined
  assert.deepEqual(parseWallImportRows(data, graph()).segments[0].layers[0], {
    name: '钢管', thicknessMm: null, conductivityWmK: null
  })
})

test('legacy source metadata in existing settings is ignored by seven-column templates and layer updates', () => {
  const saved = settings()
  saved.segments[1].layers[0].source = '旧材料资料'
  const snapshot = structuredClone(saved)
  assert.deepEqual(wallImportTemplateRows(graph(), saved), wallImportTemplateRows(graph(), settings()))
  const result = parseWallImportRows(wallImportTemplateRows(graph(), saved), graph())
  for (const segment of result.segments) {
    for (const layer of segment.layers) {
      assert.deepEqual(Object.keys(layer).sort(), ['conductivityWmK', 'name', 'thicknessMm'])
    }
  }
  assert.deepEqual(saved, snapshot)
})

test('legacy eight-column source headers and populated eighth data columns are rejected explicitly', () => {
  const oldTemplate = rows()
  oldTemplate[0].push('参数来源')
  for (const row of oldTemplate.slice(1)) row.push('')
  assert.throws(() => parseWallImportRows(oldTemplate, graph()))
  for (const value of ['旧材料资料', 111, 0]) {
    const data = rows()
    data[1].push(value)
    assert.throws(() => parseWallImportRows(data, graph()))
  }
})

test('creating templates and parsing imports do not mutate saved geometry, unrelated settings or source rows', () => {
  const topology = graph(), saved = settings(), data = rows()
  const beforeTopology = structuredClone(topology), beforeSettings = structuredClone(saved), beforeRows = structuredClone(data)
  const template = wallImportTemplateRows(topology, saved)
  template[1][4] = '修改模板名称'
  const result = parseWallImportRows(data, topology)
  result.segments[0].layers[0].name = '修改导入结果'
  assert.deepEqual(topology, beforeTopology)
  assert.deepEqual(saved, beforeSettings)
  assert.deepEqual(data, beforeRows)
  assert.deepEqual(Object.keys(result), ['segments'])
  assert.deepEqual(Object.keys(result.segments[0]).sort(), ['edgeId', 'layers'])
})

test('the table must keep the material columns and units without adding populated columns', () => {
  for (const alter of [
    data => { data[0][2] = '管内径（m）' },
    data => { [data[0][5], data[0][6]] = [data[0][6], data[0][5]] },
    data => { data[0].push('多余列') },
    data => { data[1].push('多余内容') }
  ]) {
    const data = rows()
    alter(data)
    assert.throws(() => parseWallImportRows(data, graph()))
  }
  const data = rows()
  data[0][0] = '\uFEFF管道序号'
  assert.deepEqual(parseWallImportRows(data, graph()), expected())
})

test('all saved pipes must be present and each row must match both pipe sequence and exact name', () => {
  for (const alter of [
    data => { data[1][1] = '阀室至站场' },
    data => { data[1][1] = '井口至阀室 ' },
    data => { data[1][0] = 3 },
    data => { data[1][0] = 0 },
    data => { data[1][0] = 1.5 },
    data => { data.pop() }
  ]) {
    const data = rows()
    alter(data)
    const snapshot = structuredClone(data)
    assert.throws(() => parseWallImportRows(data, graph()))
    assert.deepEqual(data, snapshot)
  }
})

test('every imported diameter must be present, positive, finite and equal to the saved pipe diameter', () => {
  for (const invalid of ['', null, 0, -100, 101, 150, 'NaN', 'Infinity', '1e309', '0x64', true]) {
    const data = rows()
    data[2][2] = invalid
    assert.throws(() => parseWallImportRows(data, graph()), `accepted diameter ${String(invalid)}`)
  }
})

test('layer numbers must start at one with no duplicates, fractional numbers or gaps', () => {
  for (const alter of [
    data => { data[1][3] = 0 },
    data => { data[1][3] = -1 },
    data => { data[1][3] = 1.5 },
    data => { data[1][3] = '' },
    data => { data[2][3] = 1 },
    data => { data[2][3] = 3 },
    data => { data.splice(1, 1) },
    data => { data.push([...data[1]]) }
  ]) {
    const data = rows()
    alter(data)
    assert.throws(() => parseWallImportRows(data, graph()))
  }
})

test('imports may add up to twelve layers and templates reject saved settings beyond that limit', () => {
  const twelve = Array.from({ length: 12 }, (_, index) => [
    1, '井口至阀室', 100, index + 1, `材料 ${index + 1}`, '', ''
  ])
  const data = [headers, ...twelve.toReversed(), rows()[3]]
  const result = parseWallImportRows(data, graph())
  assert.equal(result.segments[0].layers.length, 12)
  assert.equal(result.segments[0].layers[0].name, '材料 1')
  assert.equal(result.segments[0].layers[11].name, '材料 12')
  const layers = twelve.map(row => ({ name: row[4] }))
  assert.equal(wallImportTemplateRows(graph(), { segments: [{ edgeId: 'pipe-b', layers }] }).length, 14)
  assert.throws(() => parseWallImportRows([...data, [1, '井口至阀室', 100, 13, '第十三层', 1, 1]], graph()))
  assert.throws(() => wallImportTemplateRows(graph(), {
    segments: [{ edgeId: 'pipe-b', layers: [...layers, { name: '第十三层' }] }]
  }))
})

test('each material needs a name while optional properties still reject invalid nonblank values', () => {
  for (const invalid of ['', '  ', null, undefined]) {
    const data = rows()
    data[1][4] = invalid
    assert.throws(() => parseWallImportRows(data, graph()))
  }
  for (const column of [5, 6]) {
    for (const invalid of [0, -1, '0x10', '0b10', '1_000', '1,000', '20 mm', 'NaN', 'Infinity', '1e309', NaN, Infinity, true, [], {}]) {
      const data = rows()
      data[1][column] = invalid
      assert.throws(() => parseWallImportRows(data, graph()), `column ${column} accepted ${String(invalid)}`)
    }
  }
})

test('material CSV round trip preserves several layers, quoted text, line breaks and formula-like names', () => {
  const topology = graph(), data = rows()
  topology.edges[0].name = '=管道,"一号"\n井口段'
  data[1][1] = topology.edges[0].name
  data[2][1] = topology.edges[0].name
  data[1][4] = '=钢管,"A"\n内层'
  data[2][4] = '保温层,"B"\r\n外层'
  const result = parseWallImportRows(data, topology)
  assert.deepEqual(parseWallImportRows(parseTemperatureImportCsv(temperatureImportCsv(data)), topology), result)
  const template = wallImportTemplateRows(topology, settings())
  assert.deepEqual(parseWallImportRows(parseTemperatureImportCsv(temperatureImportCsv(template)), topology), parseWallImportRows(template, topology))
})

test('real XLSX and XLS workbooks preserve seven-column material data, numeric cells, blanks and special names', async () => {
  const XLSX = await import('xlsx')
  const topology = graph(), data = rows()
  topology.edges[0].name = '=井口,"管道"\n一号'
  data[1][1] = topology.edges[0].name
  data[2][1] = topology.edges[0].name
  data[1][4] = '=钢管,"A"\n内层'
  for (const bookType of ['xlsx', 'biff8']) {
    for (const sourceRows of [data, wallImportTemplateRows(topology, settings())]) {
      const workbook = XLSX.utils.book_new()
      XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(sourceRows), '管道材料层')
      const bytes = XLSX.write(workbook, { type: 'buffer', bookType })
      const restored = XLSX.read(bytes, { type: 'buffer', cellFormula: true })
      const sheet = restored.Sheets[restored.SheetNames[0]]
      assertTemperatureImportSheet(sheet)
      assert.equal(sheet.B2.t, 's')
      assert.equal(sheet.B2.v, topology.edges[0].name)
      assert.equal(sheet.B2.f, undefined)
      const table = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true })
      assert.equal(table[0].length, 7)
      assert.deepEqual(parseWallImportRows(table, topology), parseWallImportRows(sourceRows, topology))
    }
  }
})

test('Excel formulas and error cells are rejected before conversion can treat them as blank material data', async () => {
  const XLSX = await import('xlsx')
  for (const badCell of [{ t: 'e', v: 7 }, { t: 'n', v: 2, f: '1+1' }]) {
    const workbook = XLSX.utils.book_new()
    const original = XLSX.utils.aoa_to_sheet(rows())
    original.F2 = badCell
    XLSX.utils.book_append_sheet(workbook, original, '管道材料层')
    const restored = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellFormula: true })
    assert.throws(() => assertTemperatureImportSheet(restored.Sheets[restored.SheetNames[0]]))
  }
  assert.throws(() => assertTemperatureImportSheet({ F2: { t: 'n', v: 2, F: 'F2:F3' } }))
  assert.doesNotThrow(() => assertTemperatureImportSheet({ A1: { t: 's', v: '=材料名称' } }))
})
