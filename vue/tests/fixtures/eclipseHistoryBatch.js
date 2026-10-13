import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { validHistoryEvaluation } from '../../src/views/SoftwareIntegration/eclipseHistory.js'
const root=new URL('./eclipseHistoryBatch/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
export function historyBatchBrowserFixture(isolated=false) {
 const provenance=load('api-provenance');if(!provenance.apiVerified || !provenance.solverRunCountUnchanged)throw new Error('Actual history batch API not verified')
 const label=isolated?'isolated':'main',batch=load(label+'-batch'),dataset=load(label+'-dataset'),evaluations=load(label+'-evaluations')
 if(createHash('sha256').update(dataset.csv).digest('hex')!==dataset.metadata.sourceSha256)throw new Error('Original observation CSV changed')
 const runs=[batch.baseline,...batch.members.map(m=>m.run)],normalized={}
 for(const [index,run] of runs.entries()) {
  const evaluation=evaluations[run.id]
  if(evaluation && !validHistoryEvaluation(evaluation,dataset,run))throw new Error('Saved evaluation not bound to actual native result')
  Object.assign(run,{id:9080+index,modelVersionId:201,projectId:1,modelId:22,modelName:'ECLIPSE演示模型',versionNo:1,artifacts:[]})
  if(run.parameters)run.parameters.baselineRunId=9080
  if(evaluation){evaluation.runId=run.id;normalized[run.id]=evaluation}
 }
 Object.assign(dataset.metadata,{modelVersionId:201,baselineRunId:9080})
 for(const evaluation of Object.values(normalized))evaluation.dataset={...dataset.metadata}
 Object.assign(batch,{id:9803,modelVersionId:201,baselineRunId:9080})
 for(const member of batch.members)if(member.parameters)member.parameters.baselineRunId=9080
 const summary={id:batch.id,modelVersionId:201,baselineRunId:9080,name:batch.name,status:batch.status,caseCount:2}
 return{batch,dataset,evaluations:normalized,summary,runs}
}
