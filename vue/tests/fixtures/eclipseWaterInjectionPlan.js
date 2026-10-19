import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { waterInjectionPlanRecords, buildWaterInjectionPlan } from '../../src/views/SoftwareIntegration/eclipseWaterInjectionPlan.js'

export const waterInjectionPlanFixture = () => {
  const read = name => JSON.parse(readFileSync(new URL(`./eclipseWaterInjectionPlan/${name}.json`, import.meta.url), 'utf8'))
  const inspection = read('inspection'), native = read('native'), records = waterInjectionPlanRecords(inspection)
  const desired = new Map([['INJECTR5:365', .5], ['INJECTR6:730', .7], ['INJECTR5:1095', .9]])
  const parameters = buildWaterInjectionPlan({ inspection, baselineRunId: 9881, edits: records.map(r => ({ ...r, targetInjectionRate: desired.get(`${r.well}:${r.timeDays}`) ?? r.originalInjectionRate })) })
  const limitedParameters = buildWaterInjectionPlan({ inspection, baselineRunId: 9881, edits: records.map(r => ({ ...r, targetInjectionRate: r.timeDays > 0 ? 10 : r.originalInjectionRate })) })
  const envelope = name => ({ schemaVersion: 'eclipse-summary-result/1', modelKind: 'eclipse_100', runTask: 'eclipse', resultContract: 'VALID_FULL', outputFiles: [], eclEnd: native[name].eclEnd,
    summary: { series: native[name].series.map(s => ({ ...s, objectName: ['', 'FIELD', ':+:+:+:+', null].includes(s.objectName) ? null : s.objectName })) } })
  const baseline = { id: 9881, projectId: 1, modelId: 22, modelVersionId: 201, modelName: 'ECLIPSE演示模型', versionNo: 1, study: null, runType: 'eclipse',
    status: 'SUCCEEDED', resultContract: 'VALID_FULL', parameters: null, events: [], artifacts: [], result: envelope('baseline') }
  const target = { ...structuredClone(baseline), id: 9882, parameters, result: envelope('target') }
  const limited = { ...structuredClone(baseline), id: 9883, parameters: limitedParameters, result: envelope('limited') }
  const packageBytes = readFileSync(new URL('./eclipseWaterInjectionPlan/target-package.zip', import.meta.url))
  target.artifacts = [{ id: 98821, name: 'eclipse-schedule-applied-package.zip', contentType: 'application/zip', sizeBytes: packageBytes.length, sha256: createHash('sha256').update(packageBytes).digest('hex') }]
  return { inspection, records, parameters, limitedParameters, baseline, target, limited, packageBytes, artifactPayloads: { '9882:98821': packageBytes } }
}
