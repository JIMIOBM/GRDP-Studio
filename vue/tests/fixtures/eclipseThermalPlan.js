import { readFileSync } from 'node:fs'
import { thermalInjectionFixture } from './eclipseThermalInjection.js'
import { buildThermalPlan } from '../../src/views/SoftwareIntegration/eclipseThermalPlan.js'
export function thermalPlanFixture(kind = 'official') {
  const f = thermalInjectionFixture(kind), prefix = kind === 'official' ? 'official' : 'temperature-output'
  const native = JSON.parse(readFileSync(new URL('./eclipseThermalPlan/native.json', import.meta.url), 'utf8'))
  const baseline = { ...f.baseline, result: { ...f.baseline.result, summary: native.cases[prefix + '/baseline'].summary } }
  const plans = {}
  for (const [index, name] of ['two-stage', 'restore', 'pressure-limited'].entries()) {
    const stages = native.stages[name].map(([startTimeDays, surfaceInjectionRate, injectionTemperature, steamQuality, bhpUpperLimit]) => ({ startTimeDays, surfaceInjectionRate, injectionTemperature, steamQuality, bhpUpperLimit }))
    const parameters = buildThermalPlan({ inspection: f.inspection, baselineRunId: baseline.id, well: 'I', stages })
    const data = native.cases[prefix + '/' + name]
    plans[name] = { ...structuredClone(baseline), id: baseline.id + index + 20, parameters, result: { ...structuredClone(baseline.result), summary: data.summary, eclEnd: data.eclEnd } }
  }
  return { inspection: f.inspection, baseline, plans }
}
