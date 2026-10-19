import {readFileSync} from 'node:fs'
export function includeProductionBrowserFixture() {
  const root=new URL('./eclipseIncludeProduction/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
  const inspection=load('inspection'),baseline=load('baseline'),target=load('target'),native=load('native-acceptance')
  const curves=label=>native[label].series.map(s=>({...s,objectName:['','FIELD',':+:+:+:+',null].includes(s.objectName)?null:s.objectName}))
  if(!native.nativeVerified||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(curves('baseline'))||JSON.stringify(target.result.summary.series)!==JSON.stringify(curves('target')))throw new Error('INCLUDE browser results must match complete independent native curves')
  Object.assign(baseline,{id:10021,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
  const parameters={...target.parameters,baselineRunId:baseline.id}
  Object.assign(target,{id:10022,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
  return {inspection,baseline,target,parameters,native}
}
