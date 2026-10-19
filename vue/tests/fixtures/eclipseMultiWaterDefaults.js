import {readFileSync} from 'node:fs'
export function waterDefaultsBrowserFixture(kind='three',label='viscosity'){
 const root=new URL('./eclipseMultiWaterDefaults/'+kind+'/',import.meta.url),load=n=>JSON.parse(readFileSync(new URL(n+'.json',root),'utf8'))
 const inspection=load('inspection'),baseline=load('baseline'),target=load(label),native=load('native-acceptance')
 if(!native.nativeVerified||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native[label].series))throw new Error('Default water PVT differs from independent native output')
 Object.assign(baseline,{id:9421,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]});const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9422,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]});return{inspection,baseline,target,parameters,native}
}
