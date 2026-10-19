import { readFileSync } from 'node:fs'
import { thermalInjectionFixture } from './eclipseThermalInjection.js'
import { buildThermalInjection } from '../../src/views/SoftwareIntegration/eclipseThermalInjection.js'
export const thermalControlFixture = (kind = 'official') => {
  const f = thermalInjectionFixture(kind), prefix = kind === 'official' ? 'official' : 'temperature-output'
  const all = JSON.parse(readFileSync(new URL('./eclipseThermalControl/native.json', import.meta.url), 'utf8'))
  const native = Object.fromEntries(['baseline', 'rate', 'pressure'].map(name => [name, all[prefix + '/' + name]]))
  const result = name => ({ ...structuredClone(f.baseline.result), summary: native[name].summary, eclEnd: native[name].eclEnd })
  const baseline = { ...f.baseline, result: result('baseline') }
  const rateParameters = buildThermalInjection({ inspection: f.inspection, baselineRunId: baseline.id, well: 'I', field: 'SURFACE_INJECTION_RATE', targetValue: 25 })
  const pressureParameters = buildThermalInjection({ inspection: f.inspection, baselineRunId: baseline.id, well: 'I', field: 'BHP_UPPER_LIMIT', targetValue: 700 })
  return { inspection: f.inspection, native, baseline, rateParameters, pressureParameters,
    rate: { ...structuredClone(baseline), id: baseline.id + 3, parameters: rateParameters, result: result('rate') },
    pressure: { ...structuredClone(baseline), id: baseline.id + 4, parameters: pressureParameters, result: result('pressure') } }
}
