import {buildWetGasPvt,validWetGasPvt,validWetGasRecords,wetGasPvtResponse} from './eclipseWetGasPvt.js'
import {validLiveOilRecords} from './eclipseLiveOilPvt.js'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export const multiWetGasChoices={REGION_PVTG_MU:{field:'VISCOSITY',label:'分区湿气黏度乘数'},REGION_PVTG_BG:{field:'FORMATION_VOLUME_FACTOR',label:'分区湿气体积系数 Bg 乘数'}}
export function validRegionOilTable(t){return exact(t,['keywordLineNumber','records'])&&Number.isSafeInteger(t.keywordLineNumber)&&t.keywordLineNumber>0&&t.keywordLineNumber<=2e6&&validLiveOilRecords(t.records,t.keywordLineNumber)}
export function validMultiWetGasRegions(regions,count,cells){
  if(!Number.isSafeInteger(count)||count<2||count>8||!Array.isArray(regions)||regions.length!==count||!Number.isSafeInteger(cells)||cells<1||cells>2e6)return false
  let total=0,gasHeader=0,oilHeader=0,gasEnd=0,oilEnd=0
  for(const [i,r] of regions.entries()){
    if(!exact(r,['number','cellCount','keywordLineNumber','records','oilTable'])||r.number!==i+1||!Number.isSafeInteger(r.cellCount)||r.cellCount<1||!Number.isSafeInteger(r.keywordLineNumber)||r.keywordLineNumber<1||r.keywordLineNumber>2e6||!validWetGasRecords(r.records,r.keywordLineNumber)||!validRegionOilTable(r.oilTable))return false
    total+=r.cellCount;if(total>cells)return false
    if(i===0){gasHeader=r.keywordLineNumber;oilHeader=r.oilTable.keywordLineNumber}
    else if(r.keywordLineNumber!==gasHeader||r.oilTable.keywordLineNumber!==oilHeader||r.records[0].rows[0].lineNumber<=gasEnd||r.oilTable.records[0].rows[0].lineNumber<=oilEnd)return false
    gasEnd=r.records.at(-1).rows.at(-1).lineNumber;oilEnd=r.oilTable.records.at(-1).rows.at(-1).lineNumber
  }return total===cells&&gasEnd<oilHeader
}
export function multiWetGasPvtContext(inspection){
  const c=inspection?.multiWetGasPvtContext,d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  if(!axes.every(n=>Number.isSafeInteger(n)&&n>0)||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||c.schemaVersion!=='eclipse-multi-wet-gas-pvt-context/1'||inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||inspection.packageFiles?.length!==1||c.sourceFile!==inspection.packageFiles[0].relativePath||c.sourceFile!==inspection.caseName||!Array.isArray(inspection.phases)||new Set(inspection.phases).size!==3||!['OIL','WATER','GAS'].every(p=>inspection.phases.includes(p))||!validMultiWetGasRegions(c.regions,c.regionCount,cells))return null
  return c
}
export function validMultiWetGasPvt(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','keywordLineNumber','unit','multiplier','records','field','regionNumber','oilTable'])||p.schemaVersion!=='eclipse-multi-pvtg-parameters/1'||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8||!validRegionOilTable(p.oilTable)||!validWetGasRecords(p.records,p.keywordLineNumber)||p.records.at(-1).rows.at(-1).lineNumber>=p.oilTable.keywordLineNumber)return false
  const {regionNumber,oilTable,...single}=p;return validWetGasPvt({...single,schemaVersion:'eclipse-pvtg-parameters/1'})
}
export function buildMultiWetGasPvt({run,inspection,regionNumber,field,multiplier}){
  const c=multiWetGasPvtContext(inspection),r=c?.regions.find(r=>r.number===regionNumber)
  if(!r)throw new Error('请选择当前模型的明确PVTG流体区；PVT区与平衡区须一一对应。')
  const single=buildWetGasPvt({run,inspection:{...inspection,wetGasPvtContext:{schemaVersion:'eclipse-wet-gas-pvt-context/1',sourceFile:c.sourceFile,keywordLineNumber:r.keywordLineNumber,records:r.records}},field,multiplier})
  const result={...single,schemaVersion:'eclipse-multi-pvtg-parameters/1',regionNumber,oilTable:JSON.parse(JSON.stringify(r.oilTable))}
  if(!validMultiWetGasPvt(result))throw new Error('分区PVTG方案无效，必须绑定完整气分支及原油表。')
  return result
}
export const multiWetGasPvtResponse=wetGasPvtResponse
export const multiWetGasPvtLabel=p=>p?.field==='FORMATION_VOLUME_FACTOR'?'湿气体积系数 Bg':'湿气黏度'
