import { readFileSync } from 'node:fs'
import { compositionalReinjectionFixture } from './eclipseCompositionalReinjection.js'
import { buildCompositionalFluidProperty } from '../../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
export const compositionalFluidPropertyFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalFluidProperty/${name}.json`, import.meta.url), 'utf8'))
  const original = compositionalReinjectionFixture(), inspection = read('inspection'), native = read('native')
  const baseline = { ...original.baseline, id: 9951 }
  const parameters = buildCompositionalFluidProperty({ inspection, baselineRunId: baseline.id, componentIndex: 3, targetValue: 700 })
  const property = { ...structuredClone(baseline), id: 9952, parameters,
    result: { ...structuredClone(baseline.result), summary: native.pcrit.summary, eclEnd: native.pcrit.eclEnd } }
  return { inspection, native, baseline, property, parameters }
}
