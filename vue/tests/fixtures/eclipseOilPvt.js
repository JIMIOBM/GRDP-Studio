import {readFileSync} from 'node:fs'
const root=new URL('./eclipseOilPvt/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const native=load('native-acceptance');if(!native.nativeVerified||!native.onlyOilViscosityColumnChanged)throw new Error('Oil PVT native proof missing')
export function oilPvtBrowserFixture(){
 const inspection=load('platform-inspection'),baseline=load('baseline'),target=load('target')
 if(JSON.stringify(baseline.result.summary.series)!==JSON.stringify(native.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(native.target.series))throw new Error('Oil PVT API/native output differs')
 Object.assign(baseline,{id:9060,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
 const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9061,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
 return{inspection,baseline,target,parameters}
}

export function oilVolumeBrowserFixture(){
 const baseline=load('volume/baseline'),target=load('volume/target'),volume=load('volume/native-acceptance'),inspection=load('platform-inspection')
 if(!volume.nativeVerified||!volume.onlyOilVolumeColumnChanged||JSON.stringify(baseline.result.summary.series)!==JSON.stringify(volume.baseline.series)||JSON.stringify(target.result.summary.series)!==JSON.stringify(volume.target.series))throw new Error('Oil volume API/native output differs')
 Object.assign(baseline,{id:9060,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
 const parameters={...target.parameters,baselineRunId:baseline.id};Object.assign(target,{id:9062,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
 return{inspection,baseline,target,parameters}
}
