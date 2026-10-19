import { readFileSync } from 'node:fs'
import { compositionalFluidPropertyFixture } from './eclipseCompositionalFluidProperty.js'
import { buildCompositionalFluidProperty } from '../../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
export const compositionalAcentricFactorFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalAcentricFactor/${name}.json`, import.meta.url), 'utf8'))
  const original = compositionalFluidPropertyFixture(), inspection = read('inspection'), native = read('native')
  const baseline = { ...original.baseline, id: 9991 }
  const parameters = buildCompositionalFluidProperty({ inspection, baselineRunId: baseline.id, componentIndex: 3, targetValue: 0.03, field: 'ACF' })
  const property = { ...structuredClone(baseline), id: 9992, parameters,
    result: { ...structuredClone(baseline.result), summary: native.acf.summary, eclEnd: native.acf.eclEnd } }
  return { inspection, native, baseline, property, parameters }
}
