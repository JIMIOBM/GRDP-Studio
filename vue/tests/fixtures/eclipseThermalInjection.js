import { readFileSync } from 'node:fs'
import { buildThermalInjection } from '../../src/views/SoftwareIntegration/eclipseThermalInjection.js'
export const thermalInjectionFixture = (kind = 'official') => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseThermalInjection/${name}.json`, import.meta.url), 'utf8'))
  const inspection = read(kind + '-inspection'), all = read('native'), prefix = kind === 'official' ? 'official' : 'temperature-output', baseId = kind === 'official' ? 9901 : 9911
  const qualityParameters = buildThermalInjection({ inspection, baselineRunId: baseId, well: 'I', field: 'STEAM_QUALITY', targetValue: .5 })
  const temperatureParameters = buildThermalInjection({ inspection, baselineRunId: baseId, well: 'I', field: 'INJECTION_TEMPERATURE', targetValue: 400 })
  const native = Object.fromEntries(['baseline', 'quality', 'temperature'].map(name => [name, all[prefix + '/' + name]]))
  const envelope = name => ({ schemaVersion: 'eclipse-summary-result/3', modelKind: 'eclipse_300', runTask: 'eclipse', resultContract: 'VALID_FULL', caseName: inspection.caseName,
    simulation: structuredClone(inspection.simulation), summary: native[name].summary, eclEnd: native[name].eclEnd })
  const baseline = { id: baseId, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse', parameters: null,
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false, events: [], artifacts: [], result: envelope('baseline') }
  const quality = { ...structuredClone(baseline), id: baseId + 1, parameters: qualityParameters, result: envelope('quality') }
  const temperature = { ...structuredClone(baseline), id: baseId + 2, parameters: temperatureParameters, result: envelope('temperature') }
  return { inspection, native, baseline, quality, temperature, qualityParameters, temperatureParameters }
}
