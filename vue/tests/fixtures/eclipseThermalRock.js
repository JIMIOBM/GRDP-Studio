import { readFileSync } from 'node:fs'
import { buildThermalRock } from '../../src/views/SoftwareIntegration/eclipseThermalRock.js'
export function thermalRockFixture(kind = 'official') {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseThermalRock/${name}.json`, import.meta.url), 'utf8'))
  const inspection = read(kind + '-inspection'), native = read('native'), prefix = kind === 'official' ? 'official' : 'temperature-output', baseId = kind === 'official' ? 9971 : 9981
  const result = name => ({ schemaVersion: 'eclipse-summary-result/3', modelKind: 'eclipse_300', runTask: 'eclipse', resultContract: 'VALID_FULL', caseName: inspection.caseName,
    simulation: structuredClone(inspection.simulation), summary: native[prefix + '/' + name].summary, eclEnd: native[prefix + '/' + name].eclEnd })
  const baseline = { id: baseId, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse', parameters: null,
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false, events: [], artifacts: [], result: result('baseline') }
  const cases = {}
  for (const [index, name, field, targetValue] of [[1, 'heat-capacity', 'HEATCR', 45], [2, 'conductivity', 'THCONR', 12]]) {
    const parameters = buildThermalRock({ inspection, baselineRunId: baseId, field, targetValue })
    cases[name] = { ...structuredClone(baseline), id: baseId + index, parameters, result: result(name) }
  }
  return { inspection, baseline, cases }
}
