import { readFileSync } from 'node:fs'
const root=new URL('./eclipseLiveOilPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
export function liveOilBrowserFixture(field='volume'){
  const inspection=load('platform-inspection'),baseline=load('baseline'),target=load(field),native=load('native-acceptance')
  if(!native.nativeVerified||!native.onlySelectedColumnChanged||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native[field].series))throw new Error('Live-oil platform and independent native output differ')
  Object.assign(baseline,{id:9060,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
  const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9063,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
  return {inspection,baseline,target,parameters}
}
