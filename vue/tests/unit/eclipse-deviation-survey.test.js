import test from 'node:test'
import assert from 'node:assert/strict'
import { mapSurveyPointToGrid, parseEclipseDeviationSurvey, parseEclipseGridReference, surveyPlanPoints } from '../../src/views/SoftwareIntegration/eclipseDeviationSurvey.js'

const cnt = `-- official Schedule GRID control format
FILETYPE MULTIWELL
START 2
RECLEN 4
WELLNAME HEADER LINE 1 ITEM 2
ENDPOINT MARKER -999
ATTRIBUTE XPOS POSITION 1 UNITS FEET
ATTRIBUTE YPOS POSITION 2 UNITS FEET
ATTRIBUTE TVD POSITION 3 UNITS FEET
ATTRIBUTE MD POSITION 4 UNITS FEET
FILE EX3.DEV
END
`
const dev = `WELLNAME: SLANTW1
6000 500 0 0
5500 500 583.09 300
4500 500 1000 1000
-999
WELLNAME: SLANTW2
9000 9500 0 0
8500 9500 1100 1100
6500 9500 3336.07 2100
-999
`

test('parses the documented EX3 CNT/DEV multiwell layout without changing its source values', () => {
  const survey = parseEclipseDeviationSurvey(cnt, dev, 'ex3.dev')
  assert.equal(survey.schemaVersion, 'eclipse-deviation-survey/1')
  assert.equal(survey.sourceType, 'GRID_CONTROL_DEV_READ_ONLY')
  assert.equal(survey.wellCount, 2)
  assert.equal(survey.totalPoints, 6)
  assert.equal(survey.xyUnits, 'FEET')
  assert.equal(survey.depthUnits, 'FEET')
  assert.deepEqual(survey.wells[0].points[1], { lineNumber: 3, x: 5500, y: 500, tvd: 583.09, md: 300 })
  assert.deepEqual(survey.wells[0].ranges.md, [0, 1000])
  assert.equal(survey.wells[0].mdIncreases, true)
  const projected = surveyPlanPoints(survey.wells[0])
  assert.equal(projected.length, 3)
  assert.ok(projected.every(point => point.x >= 0 && point.x <= 600 && point.y >= 0 && point.y <= 220))
})

test('rejects mismatched control/data, unsupported trajectory grammar and non-finite rows', () => {
  assert.throws(() => parseEclipseDeviationSurvey(cnt, dev, 'another.dev'), /与上传文件.*不匹配/)
  assert.throws(() => parseEclipseDeviationSurvey(cnt.replace('FILETYPE MULTIWELL', 'FILETYPE SINGLEWELL'), dev, 'EX3.DEV'), /MULTIWELL/)
  assert.throws(() => parseEclipseDeviationSurvey(cnt, dev.replace('5500 500 583.09 300', '5500 500 NaN 300'), 'EX3.DEV'), /字段数\/数值/)
  assert.throws(() => parseEclipseDeviationSurvey(cnt, dev.replaceAll('-999', '-998'), 'EX3.DEV'), /终止标记/)
})

test('flags non-monotonic measured depth and refuses duplicate well mappings', () => {
  const survey = parseEclipseDeviationSurvey(cnt, dev.replace('5500 500 583.09 300', '5500 500 583.09 0'), 'EX3.DEV')
  assert.equal(survey.wells[0].mdIncreases, false)
  assert.equal(survey.warnings.length, 1)
  assert.throws(() => parseEclipseDeviationSurvey(cnt, dev.replace('SLANTW2', 'SLANTW1'), 'EX3.DEV'), /重复/)
})

test('preserves each depth unit and rejects a distorted XY preview with mixed coordinate units', () => {
  const differentDepthUnits = cnt.replace('ATTRIBUTE MD POSITION 4 UNITS FEET', 'ATTRIBUTE MD POSITION 4 UNITS METRES')
  const survey = parseEclipseDeviationSurvey(differentDepthUnits, dev, 'EX3.DEV')
  assert.equal(survey.units.tvd, 'FEET')
  assert.equal(survey.units.md, 'METRES')
  assert.equal(survey.depthUnits, 'TVD FEET / MD METRES')
  const differentXYUnits = cnt.replace('ATTRIBUTE YPOS POSITION 2 UNITS FEET', 'ATTRIBUTE YPOS POSITION 2 UNITS METRES')
  assert.throws(() => parseEclipseDeviationSurvey(differentXYUnits, dev, 'EX3.DEV'), /XPOS 与 YPOS 使用相同单位/)
})

test('parses the official MAPAXES order and maps survey points into grid units only with explicit datum', () => {
  const reference = parseEclipseGridReference(`-- EX3-style grid geometry\nMAPAXES\n0D0 0D0\n0 1D4\n1D4 1D4 /\nGRIDUNIT\nFEET /\n`)
  assert.equal(reference.gridUnit, 'FEET')
  assert.equal(reference.mapUnitNeedsConfirmation, true)
  const mapped = mapSurveyPointToGrid({ x: 6000, y: 500, tvd: 1000 }, reference, { mapUnitOverride: 'FEET', gridZAtTvdZero: 1200, tvdDirection: 'POSITIVE', xyUnit: 'FEET', tvdUnit: 'FEET' })
  assert.deepEqual(mapped, { x: 6000, y: 9500, z: 2200 })
  assert.throws(() => mapSurveyPointToGrid({ x: 6000, y: 500, tvd: 1000 }, reference, { gridZAtTvdZero: 1200, tvdDirection: 'POSITIVE', xyUnit: 'FEET', tvdUnit: 'FEET' }), /MAPUNITS 未确定/)
  assert.throws(() => mapSurveyPointToGrid({ x: 6000, y: 500, tvd: 1000 }, reference, { mapUnitOverride: 'FEET', gridZAtTvdZero: '', tvdDirection: 'POSITIVE', xyUnit: 'FEET', tvdUnit: 'FEET' }), /必须明确输入/)
})

test('converts map/depth units and rejects malformed or ambiguous MAPAXES metadata', () => {
  const reference = parseEclipseGridReference(`MAPAXES\n0 0\n0 10000\n10000 10000 /\nGRIDUNIT\nMETRES /\nMAPUNITS METRES\n`)
  const mapped = mapSurveyPointToGrid({ x: 1000, y: 1000, tvd: 1000 }, reference, { gridZAtTvdZero: 50, tvdDirection: 'NEGATIVE', xyUnit: 'FEET', tvdUnit: 'FEET' })
  assert.equal(mapped.x, 304.8)
  assert.ok(Math.abs(mapped.y - 9695.2) < 1e-9)
  assert.ok(Math.abs(mapped.z + 254.8) < 1e-9)
  assert.throws(() => parseEclipseGridReference(`MAPAXES\n0 0\n1 1\n2 2 /\nGRIDUNIT\nFEET /`), /共线/)
  assert.throws(() => parseEclipseGridReference(`MAPAXES\n0 0\n0 10000\n10000 10000 /\nGRIDUNIT\nFEET /\nMAPAXES\n0 0\n0 1\n1 1 /`), /出现多次/)
})
