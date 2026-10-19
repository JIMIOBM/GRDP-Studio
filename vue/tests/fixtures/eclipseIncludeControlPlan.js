import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { controlPlanRecords, buildControlPlan } from '../../src/views/SoftwareIntegration/eclipseControlPlan.js'

export const includeControlPlanFixture = () => {
  const read = path => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'))
  const inspection = read('../../../backend/src/test/resources/eclipse-include-control-plan-inspection.json')
  const records = controlPlanRecords(inspection)
  const desired = new Map([['PRODUCR1:365', 250], ['PRODUCR2:730', 350], ['PRODUCR1:1095', 450]])
  const parameters = buildControlPlan({ inspection, baselineRunId: 9771, edits: records.map(c => ({ ...c, targetOilRate: desired.get(`${c.well}:${c.timeDays}`) ?? c.originalOilRate })) })
  const limitedParameters = buildControlPlan({ inspection, baselineRunId: 9771, edits: records.map(c => ({ ...c, targetOilRate: c.timeDays > 0 ? 100000 : c.originalOilRate })) })
  const envelope = label => ({ schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL', outputFiles: [],
    eclEnd: read(`./eclipseIncludeControlPlan/${label}.json`).eclEnd, summary: { series: read(`./eclipseIncludeControlPlan/${label}.json`).series } })
  const baseline = { id: 9771, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse',
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [], result: envelope('baseline') }
  const target = { ...structuredClone(baseline), id: 9772, parameters, result: envelope('target') }
  const limited = { ...structuredClone(baseline), id: 9773, parameters: limitedParameters, result: envelope('limited') }
  const partialParameters = structuredClone(parameters)
  for (const e of partialParameters.edits) e.targetOilRate = 100000
  const partial = { ...structuredClone(baseline), id: 9774, status: 'PARTIAL_SUCCEEDED', resultContract: 'VALID_PARTIAL', parameters: partialParameters,
    result: { ...envelope('partial'), resultContract: 'VALID_PARTIAL', messages: read('./eclipseIncludeControlPlan/partial.json').messages } }
  const packageBytes = readFileSync(new URL('./eclipseIncludeControlPlan/target-package.zip', import.meta.url))
  target.artifacts = [{ id: 97721, name: 'eclipse-schedule-applied-package.zip', contentType: 'application/zip', sizeBytes: packageBytes.length, sha256: createHash('sha256').update(packageBytes).digest('hex') }]
  return { inspection, records, parameters, limitedParameters, baseline, target, limited, partial, partialParameters, packageBytes, artifactPayloads: { '9772:97721': packageBytes } }
}
