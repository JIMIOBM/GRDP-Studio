import { readFileSync } from 'node:fs'
import { controlPlanRecords, buildControlPlan, controlPlanKey } from '../../src/views/SoftwareIntegration/eclipseControlPlan.js'

// Actual inspection service snapshot of the native-qualified model; solver values below are UI fixtures.
export function controlPlanFixture(caseName=null) {
 const inspection=JSON.parse(readFileSync(new URL('../../../backend/src/test/resources/eclipse-control-plan-inspection.json',import.meta.url),'utf8'))
 if(caseName){
  inspection.caseName=caseName;inspection.packageFiles[0].relativePath=caseName
  inspection.scheduleTimeline.forEach(e=>e.sourceFile=caseName)
  for(const key of ['wells','groups','records','completions'])inspection.scheduleMetadata[key].forEach(r=>r.sourceFile=caseName)
  inspection.productionControlPlan.records.forEach(r=>r.sourceFile=caseName)
 }
 const edits=controlPlanRecords(inspection).filter(c=>c.timeDays>0).map(c=>({...c,targetOilRate:c.timeDays===365?250:c.timeDays===730?350:450}))
 const parameters=buildControlPlan({inspection,baselineRunId:1,edits})
 function summary(p=null,fault=null){
  const times=[365,730,1095,1460,3650],records=controlPlanRecords(inspection),selected=new Map((p?.edits??[]).map(e=>[controlPlanKey(e),e]))
  return {series:['PRODUCR1','PRODUCR2'].flatMap(well=>{
   const values=times.map(timeDays=>{
    const c=records.filter(r=>r.well===well && r.timeDays<timeDays).at(-1),target=selected.get(controlPlanKey(c))?.targetOilRate??c.originalOilRate
    const constrained=fault==='limited' && timeDays>365 || fault==='partial' && timeDays===3650
    return {timeDays,rate:target*(constrained?.5:1),pressure:constrained?2500:3000}
   })
   return [{keyword:'WOPR',objectName:well,unit:'STB/DAY',points:values.map(v=>({timeDays:v.timeDays,value:v.rate}))},{keyword:'WBHP',objectName:well,unit:'PSIA',points:values.map(v=>({timeDays:v.timeDays,value:v.pressure}))}]
  })}
 }
 return {inspection,parameters,summary}
}
