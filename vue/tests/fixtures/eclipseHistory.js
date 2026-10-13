import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
const root=new URL('./eclipseHistory/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
export function historyBrowserFixture(gas=false) {
  const receipt=load('platform-acceptance');if(!receipt.apiVerified||!receipt.solverRunCountUnchanged)throw new Error('History API not verified')
  const baseline=load(gas?'gas-baseline':'baseline'),target=load(gas?'gas-target':'water'),dataset=load(gas?'dataset-gas':'dataset')
  const comparison=load(gas?'comparison-gas':'comparison-baseline'),scenario=gas?comparison:load('comparison-water')
  if(createHash('sha256').update(dataset.csv).digest('hex')!==dataset.metadata.sourceSha256)throw new Error('Original history CSV differs')
  for(const [run,evaluation] of (gas?[[target,comparison]]:[[baseline,comparison],[target,scenario]])) {
    for(const point of evaluation.comparison.points) {
      const series=run.result.summary.series.find(s=>s.keyword===point.keyword&&(s.objectName||'')===point.well&&s.unit===point.unit)
      const value=series?.points.find(p=>p.timeDays===point.timeDays)?.value??null
      if(point.simulated!==value||point.difference!==(value==null?null:value-point.observed))throw new Error('Comparison does not bind native report values')
    }
  }
  Object.assign(baseline,{id:9080,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
  Object.assign(target,{id:9081,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters:{...target.parameters,baselineRunId:9080},artifacts:[]})
  Object.assign(dataset.metadata,{modelVersionId:201,baselineRunId:9080})
  const normalize=(value,id)=>{value.runId=id;value.dataset={...dataset.metadata};return value}
  return{baseline,target,dataset,comparison:normalize(comparison,gas?9081:9080),scenario:normalize(scenario,9081)}
}
