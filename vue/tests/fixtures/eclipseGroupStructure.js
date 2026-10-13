import { groupStructureKey, buildGroupStructure } from '../../src/views/SoftwareIntegration/eclipseGroupStructure.js'
export function groupStructureFixture(keyword='GCONPROD') {
 const wells=['PRODUCR1','PRODUCR2','PRODUCR3','PRODUCR4']
 const leaves=['GA','GB'].map((group,n)=>({group,efficiency:keyword==='GEFAC' && n===0?.8:1,members:wells.slice(n*2,n*2+2).map(well=>({well,bhpLimit:2500,oilRateLimit:20000,guideRate:keyword==='WGRUPCON'?1:0}))}))
 const c={parentGroup:'P',objectName:'P',keyword:'GCONPROD',sourceFile:'CASE.DATA',lineNumber:40,unit:'STB/day',originalValue:2000,parentTarget:2000,leaves}
 const controls=[c]
 if(keyword==='WGRUPCON')wells.forEach((well,n)=>controls.push({...structuredClone(c),keyword,objectName:well,lineNumber:41+n,unit:'dimensionless',originalValue:1}))
 if(keyword==='GEFAC')controls.push({...structuredClone(c),keyword,objectName:'GA',lineNumber:50,unit:'dimensionless',originalValue:.8})
 const inspection={schemaVersion:'eclipse-data-inspection/12',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleMetadata:{groups:leaves.map(l=>({name:l.group,parent:'P'})),wells:leaves.flatMap(l=>l.members.map(m=>({name:m.well,group:l.group})))},scheduleTimeline:[{sourceFile:'CASE.DATA',lineNumber:100}],groupStructureControls:controls}
 const record=controls.find(c=>c.keyword===keyword && (keyword!=='WGRUPCON' || c.objectName==='PRODUCR2'))
 const p=buildGroupStructure({inspection,baselineRunId:1,controlKey:groupStructureKey(record),targetValue:keyword==='GCONPROD'?1000:keyword==='WGRUPCON'?3:.5})
 function summary(derived=false,constrained=false){
  const target=derived && keyword==='GCONPROD'?1000:2000,eff=keyword==='GEFAC'?(derived?.5:.8):1
  const weights=wells.map(w=>keyword==='WGRUPCON' && derived && w==='PRODUCR2'?3:1),sum=weights.reduce((a,b)=>a+b,0)
  const rates=keyword==='WGRUPCON'?weights.map(w=>target*w/sum):wells.map(()=>target/(2*(eff+1)))
  if(constrained)rates.forEach((v,n)=>rates[n]=v/2)
  const parent=eff*(rates[0]+rates[1])+rates[2]+rates[3]
  const totals=rates.map((v,n)=>v*10*(n<2?eff:1)),total=totals.reduce((a,b)=>a+b,0)
  const tuples=[['GOPR','P',parent,'STB/DAY'],['GOPT','P',total,'STB'],['FOPR',null,parent,'STB/DAY'],['FOPT',null,total,'STB'],...leaves.flatMap((l,n)=>[['GOPR',l.group,rates[n*2]+rates[n*2+1],'STB/DAY'],['GOPT',l.group,totals[n*2]+totals[n*2+1],'STB']]),...wells.flatMap((w,n)=>[['WOPR',w,rates[n],'STB/DAY'],['WOPT',w,totals[n],'STB'],['WBHP',w,constrained?2500:3000,'PSIA'],['WEFFG',w,n<2?eff:1,'']])]
  return {series:tuples.map(([keyword,objectName,value,unit])=>({keyword,objectName,unit,points:[{timeDays:10,value}]}))}
 }
 return {inspection,p,summary}
}
