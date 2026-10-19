import { readFileSync } from 'node:fs'
import { compositionalReinjectionFixture } from './eclipseCompositionalReinjection.js'
import { buildCompositionalInjectionPressure } from '../../src/views/SoftwareIntegration/eclipseCompositionalInjectionPressure.js'
export const compositionalInjectionPressureFixture = (name = 'limited') => {
  const original = compositionalReinjectionFixture(), inspection = original.inspection
  const native = JSON.parse(readFileSync(new URL(`./eclipseCompositionalInjectionPressure/${name}.json`, import.meta.url), 'utf8'))
  const baseline = { ...original.baseline, id: 9941 }
  const parameters = buildCompositionalInjectionPressure({ inspection, baselineRunId: baseline.id, well: 'I', targetBhpUpperLimit: native.targetBhpUpperLimit })
  const pressure = { ...structuredClone(baseline), id: name === 'limited' ? 9942 : 9943, parameters,
    result: { ...structuredClone(baseline.result), summary: native.pressure.summary, eclEnd: native.pressure.eclEnd } }
  return { inspection, native, baseline, pressure, parameters }
}
