import { readFileSync } from 'node:fs'
import { buildCompositionalReinjection } from '../../src/views/SoftwareIntegration/eclipseCompositionalReinjection.js'
export const compositionalReinjectionFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalReinjection/${name}.json`, import.meta.url), 'utf8'))
  const inspection = read('inspection'), native = read('native')
  const parameters = buildCompositionalReinjection({ inspection, baselineRunId: 9921, group: 'FIELD', targetFraction: .5 })
  const result = name => ({ schemaVersion: 'eclipse-summary-result/2', modelKind: 'eclipse_300', runTask: 'eclipse', resultContract: 'VALID_FULL',
    caseName: inspection.caseName, simulation: structuredClone(inspection.simulation), summary: native[name].summary, eclEnd: native[name].eclEnd, outputFiles: [] })
  const baseline = { id: 9921, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse', parameters: null,
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false, events: [], artifacts: [], result: result('baseline') }
  const half = { ...structuredClone(baseline), id: 9922, parameters, result: result('half') }
  return { inspection, native, baseline, half, parameters }
}
