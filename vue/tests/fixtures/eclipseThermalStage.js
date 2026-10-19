import {readFileSync} from 'node:fs'
import {thermalInjectionFixture} from './eclipseThermalInjection.js'
import {buildThermalInjection} from '../../src/views/SoftwareIntegration/eclipseThermalInjection.js'
export function thermalStageFixture(kind='official'){
 const f=thermalInjectionFixture(kind),prefix=kind==='official'?'official':'temperature-output',all=JSON.parse(readFileSync(new URL('./eclipseThermalStage/native.json',import.meta.url),'utf8')),native=Object.fromEntries(['baseline','rate','temperature'].map(n=>[n,all[prefix+'/'+n]]))
 const baseline={...f.baseline,result:{...f.baseline.result,summary:native.baseline.summary}},cases={}
 for(const [name,field,target] of [['rate','SURFACE_INJECTION_RATE',25],['temperature','INJECTION_TEMPERATURE',400]]){const parameters=buildThermalInjection({inspection:f.inspection,baselineRunId:baseline.id,well:'I',field,targetValue:target,startTimeDays:1825});cases[name]={...structuredClone(baseline),id:baseline.id+(name==='rate'?5:6),parameters,result:{...structuredClone(baseline.result),summary:native[name].summary,eclEnd:native[name].eclEnd}}}
 return{inspection:f.inspection,baseline,native,...cases}
}
