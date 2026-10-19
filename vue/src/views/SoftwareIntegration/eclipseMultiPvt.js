import {buildOilPvt,validOilPvt,validOilPvtRows,oilVolumeUnit} from './eclipseOilPvt.js'
import {buildGasPvt,validGasPvt,validGasPvtRows,gasVolumeUnit} from './eclipseGasPvt.js'
import {gridRegionResponse} from './eclipseGridRegion.js'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export const multiPvtChoices={PVT_OIL_MU:{keyword:'PVDO',field:'VISCOSITY',label:'油黏度 · PVDO'},PVT_OIL_BO:{keyword:'PVDO',field:'FORMATION_VOLUME_FACTOR',label:'油体积系数 Bo · PVDO'},PVT_GAS_MU:{keyword:'PVDG',field:'VISCOSITY',label:'气黏度 · PVDG'},PVT_GAS_BG:{keyword:'PVDG',field:'FORMATION_VOLUME_FACTOR',label:'气体积系数 Bg · PVDG'}}
export const multiPvtUnit=(system,keyword,field)=>field==='VISCOSITY'?'cP':keyword==='PVDO'?oilVolumeUnit(system):gasVolumeUnit(system)
export function validMultiPvtTables(tables){
  if(!Array.isArray(tables)||tables.length!==2)return false
  let end=0
  for(const [i,t] of tables.entries()){
    if(!exact(t,['keyword','keywordLineNumber','rows'])||t.keyword!==['PVDO','PVDG'][i]||!Number.isSafeInteger(t.keywordLineNumber)||t.keywordLineNumber<=end||t.keywordLineNumber>2e6||!(i?validGasPvtRows:validOilPvtRows)(t.rows,t.keywordLineNumber))return false
    end=t.rows.at(-1).lineNumber
  }return true
}
export function validMultiPvtRegions(regions,count,cells){
  if(!Number.isSafeInteger(count)||count<2||count>8||!Array.isArray(regions)||regions.length!==count||!Number.isSafeInteger(cells)||cells<1||cells>2e6)return false
  let total=0;const headers=[],ends=[]
  for(const [i,r] of regions.entries()){
    if(!exact(r,['number','cellCount','tables'])||r.number!==i+1||!Number.isSafeInteger(r.cellCount)||r.cellCount<1||!validMultiPvtTables(r.tables))return false
    total+=r.cellCount;if(total>cells)return false
    for(const [k,t] of r.tables.entries()){
      if(i===0)headers[k]=t.keywordLineNumber
      else if(t.keywordLineNumber!==headers[k]||t.rows[0].lineNumber<=ends[k])return false
      ends[k]=t.rows.at(-1).lineNumber
    }
  }return total===cells&&ends[0]<headers[1]
}
const adapted=(inspection,c,t)=>({...inspection,[t.keyword==='PVDO'?'oilPvtContext':'gasPvtContext']:{schemaVersion:t.keyword==='PVDO'?'eclipse-oil-pvt-context/1':'eclipse-gas-pvt-context/1',sourceFile:c.sourceFile,keywordLineNumber:t.keywordLineNumber,rows:t.rows}})
export function multiPvtContext(inspection){
  const c=inspection?.multiPvtContext,d=inspection?.dimensions,axes=[d?.nx,d?.ny,d?.nz],cells=axes.reduce((a,b)=>a*b,1)
  if(!axes.every(n=>Number.isSafeInteger(n)&&n>0)||!exact(c,['schemaVersion','sourceFile','regionCount','regions'])||c.schemaVersion!=='eclipse-multi-pvt-context/1'||inspection?.schemaVersion!=='eclipse-data-inspection/4'||!['METRIC','FIELD','LAB','PVT-M'].includes(inspection.unitSystem)||inspection.packageFiles?.length!==1||c.sourceFile!==inspection.packageFiles[0].relativePath||c.sourceFile!==inspection.caseName||!Array.isArray(inspection.phases)||new Set(inspection.phases).size!==3||!['OIL','WATER','GAS'].every(p=>inspection.phases.includes(p))||!validMultiPvtRegions(c.regions,c.regionCount,cells))return null
  return c
}
export function validMultiPvt(p){
  if(!exact(p,['schemaVersion','baselineRunId','sourceFile','sourceSha256','unit','multiplier','field','tables','regionNumber','keyword'])||p.schemaVersion!=='eclipse-multi-pvt-parameters/1'||!Number.isSafeInteger(p.regionNumber)||p.regionNumber<1||p.regionNumber>8||!['PVDO','PVDG'].includes(p.keyword)||!validMultiPvtTables(p.tables))return false
  const {regionNumber,keyword,tables,...rest}=p,t=tables.find(t=>t.keyword===keyword)
  return (keyword==='PVDO'?validOilPvt:validGasPvt)({...rest,schemaVersion:keyword==='PVDO'?'eclipse-pvdo-parameters/2':'eclipse-pvdg-parameters/2',keywordLineNumber:t.keywordLineNumber,rows:t.rows})
}
export function buildMultiPvt({run,inspection,regionNumber,keyword,field,multiplier}){
  const c=multiPvtContext(inspection),region=c?.regions.find(r=>r.number===regionNumber),t=region?.tables.find(t=>t.keyword===keyword)
  if(!t)throw new Error('请选择当前模型的明确PVT区及PVDO/PVDG调整项目。')
  if(run?.resultExpired)throw new Error('基线结果已过期。')
  const p=(keyword==='PVDO'?buildOilPvt:buildGasPvt)({run,inspection:adapted(inspection,c,t),field,multiplier})
  const {rows,keywordLineNumber,...rest}=p
  const result={...rest,schemaVersion:'eclipse-multi-pvt-parameters/1',field,keyword,regionNumber,tables:region.tables.map(t=>({...t,rows:t.rows.map(r=>({...r,values:[...r.values]}))}))}
  if(!validMultiPvt(result))throw new Error('分区PVT方案无效，请核对乘数、单位和目标物性范围。')
  return result
}
export const multiPvtLabel=p=>Object.values(multiPvtChoices).find(c=>c.keyword===p?.keyword&&c.field===p?.field)?.label||''
export const multiPvtResponse=(current,baseline)=>gridRegionResponse(current,baseline,['WBHP','WOPR','WWPR','WGPR','WLPR','WOIR','WWIR','WGIR','WOPT','WWPT','WGPT','WOIT','WWIT','WGIT'])
