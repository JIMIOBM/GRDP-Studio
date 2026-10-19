import { readFileSync } from 'node:fs'
import { compositionalProductionFixture } from './eclipseCompositionalProduction.js'
import { buildCompositionalPressure } from '../../src/views/SoftwareIntegration/eclipseCompositionalPressure.js'
export const compositionalPressureFixture = () => {
  const f = compositionalProductionFixture(), native = JSON.parse(readFileSync(new URL('./eclipseCompositionalPressure/native.json', import.meta.url), 'utf8'))
  const baseline = { ...f.baseline, result: { ...f.baseline.result, ...native.baseline } }
  const parameters = buildCompositionalPressure({ inspection: f.inspection, baselineRunId: baseline.id, well: 'P', targetBhpLowerLimit: 1500 })
  const pressure = { ...structuredClone(baseline), id: baseline.id + 4, parameters, result: { ...structuredClone(baseline.result), summary: native.pressure.summary, eclEnd: native.pressure.eclEnd } }
  return { inspection: f.inspection, native, baseline, parameters, pressure }
}
