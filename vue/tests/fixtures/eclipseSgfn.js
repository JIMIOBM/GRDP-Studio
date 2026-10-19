import {readFileSync} from 'node:fs'
const root=new URL('./eclipseSgfn/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
export function sgfnBrowserFixture(){
 const inspection=load('inspection'),baseline=load('baseline'),target=load('target'),native=load('native-acceptance')
 if(!native.nativeVerified||!native.onlySelectedRelpermColumnsChanged||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native.target.series))throw new Error('Gas relperm platform differs from independent native output')
 Object.assign(baseline,{id:9075,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]});const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9076,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]});return {inspection,baseline,target,parameters}
}
