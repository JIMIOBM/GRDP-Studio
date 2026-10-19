import { readFileSync } from 'node:fs'
import { compositionalFluidPropertyFixture } from './eclipseCompositionalFluidProperty.js'
import { buildCompositionalFluidProperty } from '../../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
export const compositionalTemperatureFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalTemperature/${name}.json`, import.meta.url), 'utf8'))
  const original = compositionalFluidPropertyFixture(), inspection = read('inspection'), native = read('native')
  const baseline = { ...original.baseline, id: 9971 }
  const parameters = buildCompositionalFluidProperty({ inspection, baselineRunId: baseline.id, componentIndex: 3, targetValue: 350, field: 'TCRIT' })
  const property = { ...structuredClone(baseline), id: 9972, parameters,
    result: { ...structuredClone(baseline.result), summary: native.tcrit.summary, eclEnd: native.tcrit.eclEnd } }
  return { inspection, native, baseline, property, parameters }
}
