import {readFileSync} from 'node:fs'
const root=new URL('./eclipseGasPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const native=load('native-acceptance');if(!native.nativeVerified||!native.onlyGasViscosityColumnChanged)throw new Error('Gas PVT native proof missing')
export function gasPvtBrowserFixture(){
 const inspection=load('platform-inspection'),baseline=load('baseline'),target=load('target')
 if(JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native.target.series))throw new Error('Gas PVT API/native output differs')
 Object.assign(baseline,{id:9062,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
 const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9063,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
 return{inspection,baseline,target,parameters}
}

export function gasVolumeBrowserFixture(){
 const baseline=load('volume/baseline'),target=load('volume/target'),volume=load('volume/native-acceptance'),inspection=load('platform-inspection')
 if(!volume.nativeVerified||!volume.onlyGasVolumeColumnChanged||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(volume.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(volume.target.series))throw new Error('Gas volume API/native output differs')
 Object.assign(baseline,{id:9062,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
 const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9064,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
 return{inspection,baseline,target,parameters}
}
