import {readFileSync} from 'node:fs'
const root=new URL('./eclipseRelperm/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
export function relpermBrowserFixture(keyword){
  if(!['SWFN','SOF3'].includes(keyword))throw new Error('Unsupported relperm browser scenario')
  const native=load('native-'+keyword),inspection=load('platform-inspection'),baseline=load('baseline'),target=load(keyword)
  if(!native.nativeVerified||!native.onlySelectedRelpermColumnsChanged||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native.target.series))throw new Error('Relative permeability API/native output differs')
  Object.assign(baseline,{id:9070,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
  const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:keyword==='SWFN'?9071:9072,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
  return{native,inspection,baseline,target,parameters}
}
