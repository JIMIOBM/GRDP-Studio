import {readFileSync} from 'node:fs'
const root=new URL('./eclipseMultiWaterPvt/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8'))
export function multiWaterPvtBrowserFixture(label='viscosity'){
 const inspection=load('inspection'),baseline=load('baseline'),target=load(label),native=load('native-acceptance')
 if(!native.nativeVerified||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native[label].series))throw new Error('Water PVT platform differs from independent native output')
 Object.assign(baseline,{id:9221,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]});const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9222,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]});return{inspection,baseline,target,parameters,native}
}
