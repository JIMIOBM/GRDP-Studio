import { readFileSync } from 'node:fs'
import { compositionalFluidPropertyFixture } from './eclipseCompositionalFluidProperty.js'
import { buildCompositionalFluidProperty } from '../../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
export const compositionalCriticalZFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalCriticalZ/${name}.json`, import.meta.url), 'utf8'))
  const original = compositionalFluidPropertyFixture(), inspection = read('inspection'), native = read('native')
  const baseline = { ...original.baseline, id: 9981 }
  const parameters = buildCompositionalFluidProperty({ inspection, baselineRunId: baseline.id, componentIndex: 3, targetValue: 0.3, field: 'ZCRIT' })
  const property = { ...structuredClone(baseline), id: 9982, parameters,
    result: { ...structuredClone(baseline.result), summary: native.zcrit.summary, eclEnd: native.zcrit.eclEnd } }
  return { inspection, native, baseline, property, parameters }
}
