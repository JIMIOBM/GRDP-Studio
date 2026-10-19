import {buildLiveOilPvt,validLiveOilPvt,validLiveOilRecords,liveOilPvtResponse} from './eclipseLiveOilPvt.js'
import {validGasPvtRows} from './eclipseGasPvt.js'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export const multiLiveOilChoices={REGION_PVTO_MU:{field:'VISCOSITY',label:'分区含溶解气原油黏度乘数'},REGION_PVTO_BO:{field:'FORMATION_VOLUME_FACTOR',label:'分区含溶解气原油体积系数 Bo 乘数'}}
export function validRegionGasTable(t){return exact(t,['keywordLineNumber','rows'])&&Number.isSafeInteger(t.keywordLineNumber)&&t.keywordLineNumber>0&&t.keywordLineNumber<=2e6&&validGasPvtRows(t.rows,t.keywordLineNumber)}
export function validMultiLiveOilRegions(regions,count,cells){
  if(!Number.isSafeInteger(count)||count<2||count>8||!Array.isArray(regions)||regions.length!==count||!Number.isSafeInteger(cells)||cells<1||cells>2e6)return false
  let total=0,oilHeader=0,gasHeader=0,oilEnd=0,gasEnd=0
  for(const [i,r] of regions.entries()){
    if(!exact(r,['number','cellCount','keywordLineNumber','records','gasTable'])||r.number!==i+1||!Number.isSafeInteger(r.cellCount)||r.cellCount<1||!Number.isSafeInteger(r.keywordLineNumber)||r.keywordLineNumber<1||r.keywordLineNumber>2e6||!validLiveOilRecords(r.records,r.keywordLineNumber)||!validRegionGasTable(r.gasTable))return false
    total+=r.cellCount;if(total>cells)return false
    if(i===0){oilHeader=r.keywordLineNumber;gasHeader=r.gasTable.keywordLineNumber}
    else if(r.keywordLineNumber!==oilHeader||r.gasTable.keywordLineNumber!==gasHeader||r.records[0].rows[0].lineNumber<=oilEnd||r.gasTable.rows[0].lineNumber<=gasEnd)return false
    oilEnd=r.records.at(-1).rows.at(-1).lineNumber;gasEnd=r.gasTable.rows.at(-1).lineNumber
  }return total===cells&&gasEnd<oilHeader
}
export function multiLiveOilPvtContext(inspection){
  const c=inspection?.multiLiveOilPvtContext,d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  if(!axes.every(n=>Number.isSafeInteger(n)&&n>0)||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||c.schemaVersion!=='eclipse-multi-live-oil-pvt-context/1'||inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||inspection.packageFiles?.length!==1||c.sourceFile!==inspection.packageFiles[0].relativePath||c.sourceFile!==inspection.caseName||!Array.isArray(inspection.phases)||new Set(inspection.phases).size!==3||!['OIL','WATER','GAS'].every(p=>inspection.phases.includes(p))||!validMultiLiveOilRegions(c.regions,c.regionCount,cells))return null
  return c
}
export function validMultiLiveOilPvt(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','keywordLineNumber','unit','multiplier','records','field','regionNumber','gasTable'])||p.schemaVersion!=='eclipse-multi-pvto-parameters/1'||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8||!validRegionGasTable(p.gasTable)||p.gasTable.rows.at(-1).lineNumber>=p.keywordLineNumber)return false
  const {regionNumber,gasTable,...single}=p;return validLiveOilPvt({...single,schemaVersion:'eclipse-pvto-parameters/1'})
}
export function buildMultiLiveOilPvt({run,inspection,regionNumber,field,multiplier}){
  const c=multiLiveOilPvtContext(inspection),r=c?.regions.find(r=>r.number===regionNumber)
  if(!r)throw new Error('请选择当前模型的明确PVTO流体区；PVT区与平衡区须一一对应。')
  const single=buildLiveOilPvt({run,inspection:{...inspection,liveOilPvtContext:{schemaVersion:'eclipse-live-oil-pvt-context/1',sourceFile:c.sourceFile,keywordLineNumber:r.keywordLineNumber,records:r.records}},field,multiplier})
  const result={...single,schemaVersion:'eclipse-multi-pvto-parameters/1',regionNumber,gasTable:JSON.parse(JSON.stringify(r.gasTable))}
  if(!validMultiLiveOilPvt(result))throw new Error('分区PVTO方案无效，必须绑定完整油分支及原气表。')
  return result
}
export const multiLiveOilPvtResponse=liveOilPvtResponse
export const multiLiveOilPvtLabel=p=>p?.field==='FORMATION_VOLUME_FACTOR'?'含溶解气原油体积系数 Bo':'含溶解气原油黏度'
