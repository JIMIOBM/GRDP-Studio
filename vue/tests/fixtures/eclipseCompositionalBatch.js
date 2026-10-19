import { compositionalEosPropertyFixture } from './eclipseCompositionalEosProperties.js'
import { compositionalAcentricFactorFixture } from './eclipseCompositionalAcentricFactor.js'
import { buildBatchCase,buildBatchRequest } from '../../src/views/SoftwareIntegration/eclipseBatch.js'
export const compositionalBatchFixture = () => {
 const f=compositionalEosPropertyFixture(),inspection=f.inspection,baseline={...structuredClone(f.baseline),id:10101}
 const fixtures=[compositionalAcentricFactorFixture(),compositionalEosPropertyFixture('MW'),compositionalEosPropertyFixture('OMEGAA'),compositionalEosPropertyFixture('OMEGAB')]
 const fields=['ACF','MW','OMEGAA','OMEGAB'],targets=[.03,17,.54,.078]
 const cases=fields.map((field,j)=>buildBatchCase({run:baseline,inspection,kind:'E300_'+field,componentIndex:3,value:targets[j],name:'C1 '+field}))
 const members=cases.map((c,j)=>({name:c.name,run:{...structuredClone(fixtures[j].property),id:10111+j,parameters:c.parameters,modelId:baseline.modelId,modelVersionId:baseline.modelVersionId}}))
 const request=buildBatchRequest({run:baseline,name:'组分物性敏感性',cases,requestKey:'10000000-0000-0000-0000-000000000001'})
 const batch={id:10201,modelVersionId:baseline.modelVersionId,baselineRunId:baseline.id,name:request.name,status:'SUCCEEDED',createdAt:'2026-10-17T10:00:00',baseline,members}
 return {inspection,baseline,cases,request,batch}
}
