import test from 'node:test'
import assert from 'node:assert/strict'
import {
  outerInputColumns, overallInputColumns, externalHeatMethods,
  outerImportColumns, overallImportColumns,
  outerImportTemplateRows, overallImportTemplateRows,
  parseOuterImportRows, parseOverallImportRows
} from './pipelineThermalParameterImport.js'
import {
  assertTemperatureImportSheet, parseTemperatureImportCsv, temperatureImportCsv
} from './pipelineTemperatureImport.js'

const outerHeaders = ['序号', '管道名称', '管内径（mm）', '外部换热方法',
  '管中心埋深 h（m）', '土壤导热系数（W/(m·K)）', '地表综合放热系数（W/(m²·K)）']
const overallHeaders = ['序号', '管道名称', '管内径（mm）',
  '物性计算压力（MPa绝压）', '物性计算温度（℃）', '环境温度（℃）', '气体导热系数 λ（W/(m·K)）']
const methodLabels = ['第一类边界（不计地表热阻）', '第二类边界（浅埋，h/D外≤2）']

const graph = () => ({
  nodes: [{ id: 'well', name: '井口' }, { id: 'station', name: '站场' }],
  edges: [
    { id: 'pipe-b', name: '井口至阀室', source: 'well', target: 'valve', parameters: { diameterMm: 100, lengthM: 700, ambientC: 19 } },
    { id: 'pipe-a', name: '阀室至站场', source: 'valve', target: 'station', parameters: { diameterMm: 150, lengthM: 500, ambientC: 20 } }
  ]
})
const settings = () => ({
  gas: { cpJkgK: 2200 }, tolerance: 0.001,
  segments: [
    { edgeId: 'pipe-a', externalMethod: 'surface-fixed', burialDepthM: 2,
      soilConductivityWmK: 1.5, surfaceCoefficientWm2K: 12,
      propertyPressureMpa: 120, propertyTemperatureC: 0, ambientC: -1000,gasConductivityWmK:0.034,
      densityKgM3: 35, layers: [{ name: '钢管', thicknessMm: 8 }] },
    { edgeId: 'pipe-b', externalMethod: 'surface-resistance', burialDepthM: 0.2,
      soilConductivityWmK: 0.9, surfaceCoefficientWm2K: 20,
      propertyPressureMpa: 8, propertyTemperatureC: 25, ambientC: 18,gasConductivityWmK:0.031,
      densityKgM3: 45, layers: [{ name: '保温层', thicknessMm: 20 }] }
  ]
})
const outerRows = () => [
  [...outerHeaders],
  [1, '井口至阀室', 100, 'surface-resistance', 0.2, 0.9, 20],
  [2, '阀室至站场', 150, '', '', '', '']
]
const overallRows = () => [
  [...overallHeaders],
  [1, '井口至阀室', 100, 8, 25, 18,0.031],
  [2, '阀室至站场', 150, '', '', '',0.034]
]
const expectedOuter = () => ({ segments: [
  { edgeId: 'pipe-b', externalMethod: 'surface-resistance', burialDepthM: 0.2, soilConductivityWmK: 0.9, surfaceCoefficientWm2K: 20 },
  { edgeId: 'pipe-a', externalMethod: null, burialDepthM: null, soilConductivityWmK: null, surfaceCoefficientWm2K: null }
] })
const expectedOverall = () => ({ segments: [
  { edgeId: 'pipe-b', propertyPressureMpa: 8, propertyTemperatureC: 25, ambientC: 18,gasConductivityWmK:0.031 },
  { edgeId: 'pipe-a', propertyPressureMpa: null, propertyTemperatureC: null, ambientC: null,gasConductivityWmK:0.034 }
] })
const pages = [
  { name: 'outer', headers: outerHeaders, rows: outerRows, template: outerImportTemplateRows, parse: parseOuterImportRows, expected: expectedOuter,
    keys: ['edgeId', 'externalMethod', 'burialDepthM', 'soilConductivityWmK', 'surfaceCoefficientWm2K'], numberColumns: [4, 5, 6] },
  { name: 'overall', headers: overallHeaders, rows: overallRows, template: overallImportTemplateRows, parse: parseOverallImportRows, expected: expectedOverall,
    keys: ['edgeId', 'propertyPressureMpa', 'propertyTemperatureC', 'ambientC','gasConductivityWmK'], numberColumns: [3, 4, 5,6] }
]

test('input metadata and import headers expose only their page fields and the two documented heat methods', () => {
  assert.deepEqual(outerImportColumns.map(column => column.label), outerHeaders)
  assert.deepEqual(overallImportColumns.map(column => column.label), overallHeaders)
  assert.deepEqual(outerInputColumns.map(column => column.key), pages[0].keys.slice(1))
  assert.deepEqual(overallInputColumns.map(column => column.key), pages[1].keys.slice(1))
  for (const columns of [outerInputColumns, overallInputColumns]) {
    assert.ok(columns.every(column => typeof column.label === 'string' && column.label.length > 0 && typeof column.unit === 'string'))
  }
  assert.deepEqual(externalHeatMethods, [
    { value: 'surface-fixed', label: methodLabels[0] },
    { value: 'surface-resistance', label: methodLabels[1] }
  ])
})

test('templates prefill the current page parameters by edge ID and display complete Chinese method labels', () => {
  assert.deepEqual(outerImportTemplateRows(graph(), settings()), [
    outerHeaders,
    [1, '井口至阀室', 100, methodLabels[1], 0.2, 0.9, 20],
    [2, '阀室至站场', 150, methodLabels[0], 2, 1.5, 12]
  ])
  assert.deepEqual(overallImportTemplateRows(graph(), settings()), [
    overallHeaders,
    [1, '井口至阀室', 100, 8, 25, 18,0.031],
    [2, '阀室至站场', 150, 120, 0, -1000,0.034]
  ])
})

test('missing settings create blank page inputs without inventing values or using topology ambient temperature', () => {
  for (const page of pages) {
    const template = page.template(graph(), { segments: [{ edgeId: 'pipe-b' }] })
    assert.deepEqual(template, [page.headers,
      [1, '井口至阀室', 100, ...Array(page.headers.length - 3).fill('')],
      [2, '阀室至站场', 150, ...Array(page.headers.length - 3).fill('')]
    ])
    if(page.name==='overall'){assert.throws(()=>page.parse(template,graph()),/气体导热系数.*不能为空/);continue}
    const imported = page.parse(template, graph())
    for (const segment of imported.segments) {
      for (const key of page.keys.slice(1)) assert.equal(segment[key], null)
    }
  }
})

test('imports accept arbitrary row order and return exactly the selected page fields in topology order', () => {
  for (const page of pages) {
    const data = page.rows()
    const result = page.parse([data[0], data[2], data[1]], graph())
    assert.deepEqual(result, page.expected())
    assert.deepEqual(Object.keys(result), ['segments'])
    assert.deepEqual(Object.keys(result.segments[0]).sort(), [...page.keys].sort())
  }
})

test('templates and parsed patches cannot mutate settings, geometry or worksheet inputs', () => {
  for (const page of pages) {
    const topology = graph(), saved = settings(), data = page.rows()
    const snapshots = [structuredClone(topology), structuredClone(saved), structuredClone(data)]
    const template = page.template(topology, saved)
    template[1][1] = '模板修改'
    template[1][3] = 999
    const result = page.parse(data, topology)
    result.segments[0][page.keys[1]] = 999
    assert.deepEqual(topology, snapshots[0])
    assert.deepEqual(saved, snapshots[1])
    assert.deepEqual(data, snapshots[2])
  }
})

test('a page rejects another page template, wrong units, altered column order and extra populated columns', () => {
  assert.throws(() => parseOuterImportRows(overallRows(), graph()))
  assert.throws(() => parseOverallImportRows(outerRows(), graph()))
  for (const page of pages) {
    for (const alter of [
      data => { data[0][2] = '管内径（m）' },
      data => { [data[0][3], data[0][4]] = [data[0][4], data[0][3]] },
      data => { data[0].push('其他页参数') },
      data => { data[1].push('额外值') }
    ]) {
      const data = page.rows()
      alter(data)
      assert.throws(() => page.parse(data, graph()))
    }
    const data = page.rows()
    data[0][0] = '\uFEFF序号'
    assert.deepEqual(page.parse(data, graph()), page.expected())
  }
})

test('each topology pipe must appear exactly once with its saved sequence and exact name', () => {
  for (const page of pages) {
    for (const alter of [
      data => { data[1][1] = '阀室至站场' },
      data => { data[1][1] = '井口至阀室 ' },
      data => { data[1][0] = 3 },
      data => { data[1][0] = 0 },
      data => { data[1][0] = 1.5 },
      data => { data.push([...data[1]]) },
      data => { data.pop() }
    ]) {
      const data = page.rows()
      alter(data)
      const before = structuredClone(data)
      assert.throws(() => page.parse(data, graph()))
      assert.deepEqual(data, before)
    }
  }
})

test('diameter is mandatory saved geometry and cannot be edited through either parameter import', () => {
  for (const page of pages) {
    for (const invalid of ['', null, 0, -1, 101, '0x64', 'Infinity', '1e309', true]) {
      const data = page.rows()
      data[1][2] = invalid
      assert.throws(() => page.parse(data, graph()), `${page.name} accepted diameter ${String(invalid)}`)
    }
    const data = page.rows()
    data[1][2] = '100.0'
    data[2][2] = '1.5e2'
    assert.deepEqual(page.parse(data, graph()), page.expected())
  }
})

test('optional conditions allow blank values but overall conductivity is required', () => {
  for (const page of pages) {
    for (const blank of ['', null, undefined, '  ']) {
      const data = page.rows()
      for (let column = 3; column < page.headers.length; column++)if(!(page.name==='overall'&&column===6))data[1][column] = blank
      const segment = page.parse(data, graph()).segments[0]
      for (const key of page.keys.slice(1))assert.equal(segment[key],key==='gasConductivityWmK'?0.031:null)
      if(page.name==='overall'){data[1][6]=blank;assert.throws(()=>page.parse(data,graph()),/气体导热系数.*不能为空/)}
    }
  }
})

test('both external methods accept their enum, short Chinese name and complete Chinese label', () => {
  for (const [expected, aliases] of [
    ['surface-fixed', ['surface-fixed', '第一类边界', methodLabels[0]]],
    ['surface-resistance', ['surface-resistance', '第二类边界', methodLabels[1]]]
  ]) {
    for (const alias of aliases) {
      const data = outerRows()
      data[1][3] = alias
      assert.equal(parseOuterImportRows(data, graph()).segments[0].externalMethod, expected)
    }
  }
  for (const invalid of ['第三类边界', 'surface', '第一类', 1, true, {}, []]) {
    const data = outerRows()
    data[1][3] = invalid
    assert.throws(() => parseOuterImportRows(data, graph()))
  }
})

test('positive parameters reject zero and negatives while accepting finite scientific notation', () => {
  for (const [page, columns] of [[pages[0], [4, 5, 6]], [pages[1], [3,6]]]) {
    for (const column of columns) {
      for (const invalid of [0, '0', '-0', -1, '-1e-3']) {
        const data = page.rows()
        data[1][column] = invalid
        assert.throws(() => page.parse(data, graph()))
      }
      const data = page.rows()
      data[1][column] = '1.25e2'
      assert.equal(page.parse(data, graph()).segments[0][page.keys[column - 2]], 125)
    }
  }
})

test('property temperature accepts zero and stays above absolute zero without imposing an artificial upper bound', () => {
  for (const valid of [0, '0', -273.149, '400', 10000]) {
    const data = overallRows()
    data[1][4] = valid
    assert.equal(parseOverallImportRows(data, graph()).segments[0].propertyTemperatureC, Number(valid))
  }
  for (const invalid of [-273.15, '-273.15', -274, -1e9]) {
    const data = overallRows()
    data[1][4] = invalid
    assert.throws(() => parseOverallImportRows(data, graph()))
  }
})

test('ambient temperature accepts any finite signed number including zero and large magnitudes', () => {
  for (const valid of [0, -273.15, -1000, '-1e100', '1e100']) {
    const data = overallRows()
    data[1][5] = valid
    assert.equal(parseOverallImportRows(data, graph()).segments[0].ambientC, Number(valid))
  }
})

test('numeric inputs reject nonfinite values, nondecimal strings and nonnumeric cell types', () => {
  for (const page of pages) {
    for (const column of page.numberColumns) {
      for (const invalid of ['NaN', 'Infinity', '1e309', '0x10', '0b10', '1_000', '1,000', '20 ℃', NaN, Infinity, true, {}, [], new Date('2026-09-09T00:00:00Z')]) {
        const data = page.rows()
        data[1][column] = invalid
        assert.throws(() => page.parse(data, graph()), `${page.name} column ${column} accepted ${String(invalid)}`)
      }
    }
  }
})

test('CSV round trips both pages including blanks, special pipe names and current template parameters', () => {
  for (const page of pages) {
    const topology = graph(), data = page.rows()
    topology.edges[0].name = '=井口,"一号"\n管道'
    topology.edges[1].name = '阀室\r\n至站场'
    data[1][1] = topology.edges[0].name
    data[2][1] = topology.edges[1].name
    for (const sourceRows of [data, page.template(topology, settings())]) {
      const csv = temperatureImportCsv(sourceRows)
      assert.deepEqual(page.parse(parseTemperatureImportCsv(csv), topology), page.parse(sourceRows, topology))
    }
  }
})

test('actual XLSX and XLS files round trip both pages as numeric and text cells without changing formulas-like names', async () => {
  const XLSX = await import('xlsx')
  for (const bookType of ['xlsx', 'biff8']) {
    for (const page of pages) {
      const topology = graph(), data = page.rows()
      topology.edges[0].name = '=井口,"一号"\n管道'
      data[1][1] = topology.edges[0].name
      for (const sourceRows of [data, page.template(topology, settings())]) {
        const workbook = XLSX.utils.book_new()
        XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet(sourceRows), '传热参数')
        const bytes = XLSX.write(workbook, { type: 'buffer', bookType })
        const restored = XLSX.read(bytes, { type: 'buffer', cellFormula: true, cellDates: true })
        const sheet = restored.Sheets[restored.SheetNames[0]]
        assertTemperatureImportSheet(sheet)
        assert.equal(sheet.B2.t, 's')
        assert.equal(sheet.B2.v, topology.edges[0].name)
        assert.equal(sheet.B2.f, undefined)
        const table = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: null, blankrows: true })
        assert.deepEqual(page.parse(table, topology), page.parse(sourceRows, topology))
      }
    }
  }
})

test('Excel error cells and formulas are rejected before they can become blank thermal parameters', async () => {
  const XLSX = await import('xlsx')
  for (const page of pages) {
    for (const badCell of [{ t: 'e', v: 7 }, { t: 'n', v: 2, f: '1+1' }]) {
      const workbook = XLSX.utils.book_new()
      const original = XLSX.utils.aoa_to_sheet(page.rows())
      original.E2 = badCell
      XLSX.utils.book_append_sheet(workbook, original, '传热参数')
      const restored = XLSX.read(XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }), { type: 'buffer', cellFormula: true })
      assert.throws(() => assertTemperatureImportSheet(restored.Sheets[restored.SheetNames[0]]))
    }
  }
  assert.throws(() => assertTemperatureImportSheet({ E2: { t: 'n', v: 2, F: 'E2:E3' } }))
  assert.doesNotThrow(() => assertTemperatureImportSheet({ A1: { t: 's', v: '=管道名称' } }))
})
