import {readFileSync} from 'node:fs'
import {buildPvtw} from '../../src/views/SoftwareIntegration/eclipsePvtw.js'
const root=new URL('./eclipsePvtw/',import.meta.url),load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const native=load('native-acceptance');if(!native.nativeVerified||!native.onlyWaterViscosityTokenChanged)throw new Error('Native water PVT not verified')
const fieldsNative=load('fields-native-acceptance');if(!fieldsNative.nativeVerified||!fieldsNative.onlySelectedPvtwTokenChanged)throw new Error('Five native water fields not verified')
export function pvtwBrowserFixture(label='viscosity'){
  const inspection=load('inspection'),baseline=load('fields-baseline');Object.assign(baseline,{id:9050,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',artifacts:[]})
  const definition=fieldsNative.scenarios[label],parameters=buildPvtw({run:baseline,inspection,targetValue:definition.targetValue,field:definition.field}),target=load('fields-'+label)
  Object.assign(target,{id:9051,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',parameters,artifacts:[]})
  if(JSON.stringify(target.result.summary.series)!==JSON.stringify(fieldsNative[label].series))throw new Error('Platform differs from native')
  return{inspection,baseline,target,parameters}
}
