import { readFileSync } from 'node:fs'
import { compositionalFluidPropertyFixture } from './eclipseCompositionalFluidProperty.js'
import { buildCompositionalFluidProperty } from '../../src/views/SoftwareIntegration/eclipseCompositionalFluidProperty.js'
export const eosPropertyCases = [
 {field:'MW',name:'分子量',target:17,original:16.043,unit:'lb/lb-M',line:140,oil:2591593,difference:-2009.25},
 {field:'OMEGAA',name:'Ωa系数',target:.54,original:.534021,unit:'dimensionless',line:146,oil:2624349.5,difference:30747.25},
 {field:'OMEGAB',name:'Ωb系数',target:.078,original:.0777961,unit:'dimensionless',line:152,oil:2586853.75,difference:-6748.5}
]
export const compositionalEosPropertyFixture = (field='MW') => {
 const read=name=>JSON.parse(readFileSync(new URL(`./eclipseCompositionalEosProperties/${name}.json`,import.meta.url),'utf8'))
 const c=eosPropertyCases.find(c=>c.field===field),inspection=read('inspection'),native=read(field),original=compositionalFluidPropertyFixture()
 const baseline={...original.baseline,id:10001}
 const parameters=buildCompositionalFluidProperty({inspection,baselineRunId:baseline.id,componentIndex:3,targetValue:c.target,field})
 const property={...structuredClone(baseline),id:10002+eosPropertyCases.indexOf(c),parameters,result:{...structuredClone(baseline.result),summary:native.case.summary,eclEnd:native.case.eclEnd}}
 return {inspection,native,baseline,property,parameters,c}
}
