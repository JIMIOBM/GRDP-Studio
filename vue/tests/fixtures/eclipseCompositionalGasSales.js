import { readFileSync } from 'node:fs'
import { compositionalReinjectionFixture } from './eclipseCompositionalReinjection.js'
import { buildCompositionalGasSales } from '../../src/views/SoftwareIntegration/eclipseCompositionalGasSales.js'
export const compositionalGasSalesFixture = (name = 'normal') => {
  const original = compositionalReinjectionFixture(), inspection = original.inspection
  const native = JSON.parse(readFileSync(new URL(`./eclipseCompositionalGasSales/${name}.json`, import.meta.url), 'utf8'))
  const baseline = { ...original.baseline, id: 9931 }
  const parameters = buildCompositionalGasSales({ inspection, baselineRunId: baseline.id, group: 'FIELD', targetSalesTarget: native.targetSales })
  const sales = { ...structuredClone(baseline), id: name === 'normal' ? 9932 : 9933, parameters,
    result: { ...structuredClone(baseline.result), summary: native.sales.summary, eclEnd: native.sales.eclEnd } }
  return { inspection, native, baseline, sales, parameters }
}
