import test from 'node:test'
import assert from 'node:assert/strict'
import { thermalBrowserFixture } from '../tests/fixtures/eclipseThermal.js'
import { thermalResult, validThermalInspection, thermalObject, thermalCsv, thermalCategory } from '../src/views/SoftwareIntegration/eclipseThermal.js'
import { compositionalResult } from '../src/views/SoftwareIntegration/eclipseCompositional.js'

test('native thermal inspection recognizes oil components and separate water/steam phases', () => {
  const f = thermalBrowserFixture(); assert.equal(validThermalInspection(f.inspection), true)
  assert.equal(thermalResult(f.run, f.inspection), f.run.result)
  assert.equal(compositionalResult(f.run, f.inspection), null)
})
test('two cells five temperatures steam and heat keep their own units and raw identities', () => {
  const { inspection, run } = thermalBrowserFixture(), s = run.result.summary.series
  assert.equal(s.filter(v => thermalCategory(v.keyword) === 'temperature').length, 5)
  assert.equal(thermalCategory('WSTPR'), 'steam'); assert.equal(thermalCategory('FHLR'), 'heat')
  assert.equal(thermalObject(s[1], inspection.dimensions), '网格 (9, 1, 4)')
  assert.equal(thermalCsv(run.result).split('\r\n').length, 15)
  assert.equal(s[2].points[1].value, 514) // Input 450°F is not forced onto pressure-dependent actual well temperature.
})
test('wrong thermal mode solver component names and old schema cannot display', () => {
  for (const fault of ['type', 'program', 'schema', 'fluid', 'names', 'phases', 'extra']) {
    const { inspection, run } = thermalBrowserFixture()
    if (fault === 'type') run.result.simulation.modelType = 'COMPOSITIONAL'
    if (fault === 'program') run.result.simulation.summaryProgramIdentifier = 300
    if (fault === 'schema') run.result.schemaVersion = 'eclipse-summary-result/2'
    if (fault === 'fluid') run.result.simulation.fluidModel = 'PR'
    if (fault === 'names') run.result.simulation.componentNames[0] = 'OTHER'
    if (fault === 'phases') inspection.phases.pop()
    if (fault === 'extra') run.result.simulation.equationOfState = 'PR'
    assert.equal(thermalResult(run, inspection), null, fault)
  }
})
test('temperature units missing vectors wrong cell/well and invented clocks are rejected', () => {
  for (const fault of ['unit', 'missing', 'axis', 'index', 'cell', 'zero', 'well', 'clock', 'nan', 'date']) {
    const { inspection, run } = thermalBrowserFixture(), s = run.result.summary, v = s.series[0]
    if (fault === 'unit') v.unit = 'DEG C'
    if (fault === 'missing') s.series.splice(0, 1)
    if (fault === 'axis') s.timeVectorIndex = 1
    if (fault === 'index') s.series[1].vectorIndex = 1
    if (fault === 'cell') v.number = 181
    if (fault === 'zero') v.number = 0
    if (fault === 'well') s.series[2].objectName = 'UNKNOWN'
    if (fault === 'clock') v.points[1].timeDays = 3651
    if (fault === 'nan') v.points[1].value = Infinity
    if (fault === 'date') s.startDate = '1990-02-31'
    assert.equal(thermalResult(run, inspection), null, fault)
  }
})
test('metric temperature preserves Celsius labels without changing numbers', () => {
  const { inspection, run } = thermalBrowserFixture(); inspection.unitSystem = 'METRIC'
  for (const v of run.result.summary.series) if (thermalCategory(v.keyword) === 'temperature') v.unit = 'DEG C'
  assert.equal(thermalResult(run, inspection), run.result)
  assert.equal(run.result.summary.series[0].points[1].value, 510.5)
})
