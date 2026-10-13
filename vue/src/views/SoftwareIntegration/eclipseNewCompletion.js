import { newWellContext, newWellUnits, newWellResponse } from './eclipseNewWell.js'
const fields=['schemaVersion','baselineRunId','sourceFile','sourceSha256','gridFile','gridArtifactSha256','nx','ny','nz','insertionLine','well','i','j','k','diameter','kh','skin','lengthUnit']
export function validNewCompletionParameters(p){
  return !!p&&Object.keys(p).length===fields.length&&fields.every(k=>Object.hasOwn(p,k))&&p.schemaVersion==='eclipse-new-completion-parameters/1'
    &&Number.isSafeInteger(p.baselineRunId)&&p.baselineRunId>0&&typeof p.sourceFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    &&typeof p.gridFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.EGRID$/i.test(p.gridFile)
    &&['sourceSha256','gridArtifactSha256'].every(k=>typeof p[k]==='string'&&/^[0-9a-f]{64}$/.test(p[k]))
    &&typeof p.well==='string'&&/^[A-Za-z][A-Za-z0-9_-]{0,7}$/.test(p.well)
    &&['nx','ny','nz','insertionLine','i','j','k'].every(k=>Number.isSafeInteger(p[k])&&p[k]>0&&p[k]<=2e6)&&p.nx*p.ny*p.nz<=2e6&&p.i<=p.nx&&p.j<=p.ny&&p.k<=p.nz
    &&['m','ft','cm'].includes(p.lengthUnit)&&['diameter','kh','skin'].every(k=>typeof p[k]==='number'&&Number.isFinite(p[k]))
    &&p.diameter>0&&p.diameter*(p.lengthUnit==='ft'?.3048:p.lengthUnit==='cm'?.01:1)<=2&&p.kh>0&&p.kh<=1e9&&p.skin>=0&&p.skin<=100
}
export function completionProducerWells(inspection){
  if(!newWellContext(inspection))return []
  const m=inspection.scheduleMetadata
  return m.wells.filter(w=>{
    const rows=m.records.filter(r=>r.keyword==='WCONPROD'&&r.values[0]?.trim()===w.name)
    return rows.length===1&&rows[0].values.length>=5&&rows[0].values[1]==='OPEN'&&rows[0].values[2]==='WRAT'&&Number.isFinite(Number(rows[0].values[4]))&&Number(rows[0].values[4])>0
  }).map(w=>w.name)
}
export function buildNewCompletionParameters({run,inspection,grid,field,artifact,draft,activeValue}){
  const c=newWellContext(inspection),m=inspection?.scheduleMetadata,u=newWellUnits(inspection?.unitSystem)
  if(!c||run?.runType!=='eclipse'||run?.status!=='SUCCEEDED'||run?.resultContract!=='VALID_FULL'||run?.parameters!=null)throw new Error('需要纯水单DATA初始完整无参数成功基线。')
  if(!grid||['nx','ny','nz'].some(k=>grid[k]!==inspection.dimensions?.[k])||field?.keyword!=='ACTNUM'||field.file!==grid.fileName||field.timeStep!==undefined||field.dataType!=='INTE'||field.elementSize!==4||field.count!==grid.nx*grid.ny*grid.nz
    ||artifact?.name!==`eclipse-output-${grid.fileName}`||artifact.contentType!=='application/octet-stream'||!/^[0-9a-f]{64}$/i.test(artifact.sha256||''))throw new Error('需要同次运行完整ACTNUM和EGRID校验信息。')
  const p={schemaVersion:'eclipse-new-completion-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:inspection.packageFiles[0].sha256,gridFile:grid.fileName,gridArtifactSha256:artifact.sha256.toLowerCase(),
    nx:grid.nx,ny:grid.ny,nz:grid.nz,insertionLine:c.insertionLine,...Object.fromEntries(['well','i','j','k','diameter','kh','skin'].map(k=>[k,draft[k]])),lengthUnit:u.length}
  if(!validNewCompletionParameters(p))throw new Error('请核对已有井、整数连接坐标、原单位井径/KH及0–100皮肤。')
  if(!completionProducerWells(inspection).includes(p.well))throw new Error('只能给已有OPEN/WRAT产水井新增连接。')
  let connections=0
  for(const r of m.completions){
    if(r.keyword!=='COMPDAT'||r.sourceFile!==p.sourceFile||!Number.isSafeInteger(r.lineNumber)||r.lineNumber<=0||r.lineNumber>=p.insertionLine
      ||!['i','j','k1','k2'].every(k=>typeof r[k]==='string'&&/^[1-9][0-9]*$/.test(r[k]))||Number(r.i)>p.nx||Number(r.j)>p.ny||Number(r.k2)>p.nz||Number(r.k1)>Number(r.k2))throw new Error('原连接必须有明确有效坐标和初始来源。')
    if(Number(r.i)===p.i&&Number(r.j)===p.j&&p.k>=Number(r.k1)&&p.k<=Number(r.k2))throw new Error('新连接与已有井连接重复或占用同一单元。')
    if(r.well.trim()===p.well)connections+=Number(r.k2)-Number(r.k1)+1
  }
  if(!connections||connections+1>c.maxConnections)throw new Error('WELLDIMS每井连接容量不足。')
  if(!Number.isInteger(activeValue)||activeValue<=0)throw new Error('新连接必须位于实际EGRID的活动单元。')
  return p
}
export const newCompletionResponse=newWellResponse
