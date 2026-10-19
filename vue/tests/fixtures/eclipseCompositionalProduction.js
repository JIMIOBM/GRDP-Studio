import { readFileSync } from 'node:fs'
import { buildCompositionalProduction } from '../../src/views/SoftwareIntegration/eclipseCompositionalProduction.js'
export const compositionalProductionFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseCompositionalProduction/${name}.json`, import.meta.url), 'utf8'))
  const inspection = read('inspection'), native = read('native')
  const parameters = buildCompositionalProduction({ inspection, baselineRunId: 9891, well: 'P', targetGasRate: 4500 })
  const highParameters = { ...parameters, targetGasRate: 12000 }
  const result = name => ({ schemaVersion: 'eclipse-summary-result/2', modelKind: 'eclipse_300', runTask: 'eclipse', resultContract: 'VALID_FULL',
    caseName: inspection.caseName, simulation: structuredClone(inspection.simulation), ...native[name] })
  const baseline = { id: 9891, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse',
    parameters: null, status: 'SUCCEEDED', resultContract: 'VALID_FULL', cancellable: false, events: [], artifacts: [], result: result('baseline') }
  const target = { ...structuredClone(baseline), id: 9892, parameters, result: result('target') }
  const high = { ...structuredClone(baseline), id: 9893, parameters: highParameters, result: result('high') }
  return { inspection, native, parameters, highParameters, baseline, target, high }
}
