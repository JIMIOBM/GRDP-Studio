import { gridRegionResponse } from './eclipseGridRegion.js'
export const newWellUnits=system=>({METRIC:{length:'m',rate:'SM3/DAY',pressure:'BARSA'},FIELD:{length:'ft',rate:'STB/DAY',pressure:'PSIA'},LAB:{length:'cm',rate:'SCC/HR',pressure:'ATMA'},'PVT-M':{length:'m',rate:'SM3/DAY',pressure:'ATMA'}}[system]||null)
const names=['schemaVersion','baselineRunId','sourceFile','sourceSha256','gridFile','gridArtifactSha256','nx','ny','nz','insertionLine','well','group','i','j','k','referenceDepth','lengthUnit','diameter','kh','skin','waterRate','rateUnit','bhpLimit','pressureUnit']
export function validNewWellParameters(p){
  const positive=(k,max)=>typeof p[k]==='number'&&Number.isFinite(p[k])&&p[k]>0&&p[k]<=max
  return !!p&&Object.keys(p).length===names.length&&names.every(k=>Object.hasOwn(p,k))&&p.schemaVersion==='eclipse-new-well-parameters/1'
    && Number.isSafeInteger(p.baselineRunId)&&p.baselineRunId>0&&typeof p.sourceFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.DATA$/i.test(p.sourceFile)
    && typeof p.gridFile==='string'&&/^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}\.EGRID$/i.test(p.gridFile)
    && ['sourceSha256','gridArtifactSha256'].every(k=>typeof p[k]==='string'&&/^[0-9a-f]{64}$/.test(p[k]))
    && ['well','group'].every(k=>typeof p[k]==='string'&&/^[A-Za-z][A-Za-z0-9_-]{0,7}$/.test(p[k]))&&p.group.toUpperCase()!=='FIELD'
    && ['nx','ny','nz','insertionLine','i','j','k'].every(k=>Number.isSafeInteger(p[k])&&p[k]>0&&p[k]<=2e6)&&p.nx*p.ny*p.nz<=2e6&&p.i<=p.nx&&p.j<=p.ny&&p.k<=p.nz
    && ['METRIC','FIELD','LAB','PVT-M'].some(s=>{const u=newWellUnits(s);return p.lengthUnit===u.length&&p.rateUnit===u.rate&&p.pressureUnit===u.pressure})
    && positive('referenceDepth',1e7)&&positive('diameter',2/(p.lengthUnit==='ft'?.3048:p.lengthUnit==='cm'?.01:1))&&positive('kh',1e9)&&positive('waterRate',1e7)&&positive('bhpLimit',1e6)
    && typeof p.skin==='number'&&Number.isFinite(p.skin)&&p.skin>=0&&p.skin<=100
}
export function newWellContext(inspection){
  const c=inspection?.newWellContext,m=inspection?.scheduleMetadata,files=inspection?.packageFiles
  if(c?.schemaVersion!=='eclipse-new-well-context/1'||!Array.isArray(files)||files.length!==1||files[0].relativePath!==c.sourceFile||!newWellUnits(inspection.unitSystem)
    ||inspection.phases?.length!==1||inspection.phases[0]!=='WATER'||!Array.isArray(m?.wells)||!m.wells.length||!Array.isArray(m.groups)||m.groups.length
    ||!Array.isArray(m.records)||!Array.isArray(m.completions)||!Number.isSafeInteger(c.insertionLine)||c.insertionLine<=0
    ||['maxWells','maxConnections','maxGroups','maxWellsPerGroup'].some(k=>!Number.isSafeInteger(c[k])||c[k]<=0)
    ||m.wells.some(w=>typeof w.group!=='string'||!w.group||w.sourceFile!==c.sourceFile||!Number.isSafeInteger(w.lineNumber)||w.lineNumber>=c.insertionLine)
    ||m.records.some(r=>r.sourceFile!==c.sourceFile||!Number.isSafeInteger(r.lineNumber)||r.lineNumber>=c.insertionLine||!r.values?.length||/[*?]/.test(r.values[0])))return null
  return c
}
export function buildNewWellParameters({run,inspection,grid,field,artifact,draft,activeValue}){
  const c=newWellContext(inspection),m=inspection?.scheduleMetadata,u=newWellUnits(inspection?.unitSystem)
  if(!c||run?.runType!=='eclipse'||run?.status!=='SUCCEEDED'||run?.resultContract!=='VALID_FULL'||run?.parameters!=null)throw new Error('需要当前纯水单DATA的完整无参数成功基线和新井检查信息；SUMMARY须请求全井WWPR/WBHP/WWPT/WSTAT。')
  const file=inspection.packageFiles[0]
  if(!grid||['nx','ny','nz'].some(k=>grid[k]!==inspection.dimensions?.[k])||field?.keyword!=='ACTNUM'||field.file!==grid.fileName||field.timeStep!==undefined||field.dataType!=='INTE'||field.elementSize!==4||field.count!==grid.nx*grid.ny*grid.nz
    ||artifact?.name!==`eclipse-output-${grid.fileName}`||artifact.contentType!=='application/octet-stream'||!/^[0-9a-f]{64}$/i.test(artifact.sha256||''))throw new Error('需要同次运行的完整ACTNUM和EGRID校验信息。')
  const p={schemaVersion:'eclipse-new-well-parameters/1',baselineRunId:run.id,sourceFile:c.sourceFile,sourceSha256:file.sha256,gridFile:grid.fileName,gridArtifactSha256:artifact.sha256.toLowerCase(),
    nx:grid.nx,ny:grid.ny,nz:grid.nz,insertionLine:c.insertionLine,...Object.fromEntries(['well','group','i','j','k','referenceDepth','diameter','kh','skin','waterRate','bhpLimit'].map(k=>[k,draft[k]])),lengthUnit:u.length,rateUnit:u.rate,pressureUnit:u.pressure}
  if(!validNewWellParameters(p))throw new Error('请核对新井名（最多8字符）、整数网格、原单位及正数深度/井径/KH/产水目标/压力下限；皮肤须0–100。')
  if(m.wells.some(w=>w.name.toUpperCase()===p.well.toUpperCase()))throw new Error('新井名与已有井冲突。')
  const members=m.wells.filter(w=>w.group===p.group)
  if(!members.length||m.wells.length+1>c.maxWells||members.length+1>c.maxWellsPerGroup||new Set(m.wells.map(w=>w.group)).size>c.maxGroups)throw new Error('已有直接井组或WELLDIMS容量不满足新增井。')
  if(m.completions.some(r=>!['i','j','k1','k2'].every(k=>typeof r[k]==='string'&&/^[1-9][0-9]*$/.test(r[k]))||Number(r.i)>p.nx||Number(r.j)>p.ny||Number(r.k2)>p.nz||Number(r.k1)>Number(r.k2)))throw new Error('已有连接坐标须明确有效，不能猜测默认坐标。')
  if(m.completions.some(r=>Number(r.i)===p.i&&Number(r.j)===p.j&&p.k>=Number(r.k1)&&p.k<=Number(r.k2)))throw new Error('本期不在已有井连接占用的单元中新建井。')
  if(!Number.isInteger(activeValue)||activeValue<=0)throw new Error('新连接必须位于实际EGRID的活动单元。')
  return p
}
export function newWellResponse(current,baseline){
  // E100 may reserve unassigned well slots. They have no well identity and
  // remain in raw outputs; only named wells belong in the business comparison.
  const named=s=>s&&{...s,series:Array.isArray(s.series)?s.series.filter(v=>typeof v.objectName==='string'&&v.objectName.trim()):s.series}
  return gridRegionResponse(named(current),named(baseline))
}
export function newWellOutcome(summary,p){
  const fail=message=>({available:false,rows:[],message})
  if(!validNewWellParameters(p)||!Array.isArray(summary?.series))return fail('缺少有效新井方案或实际Summary。')
  const required={WWPR:p.rateUnit,WBHP:p.pressureUnit,WWPT:p.rateUnit==='STB/DAY'?'STB':p.rateUnit==='SCC/HR'?'SCC':'SM3',WSTAT:null},vectors={}
  for(const [key,unit] of Object.entries(required)){
    const series=summary.series.filter(s=>s.keyword===key&&s.objectName===p.well)
    if(series.length!==1||unit!==null&&series[0].unit!==unit||unit===null&&![null,'','1'].includes(series[0].unit)||!Array.isArray(series[0].points))return fail('缺少唯一的新井实际曲线或单位不一致。')
    let previous=-Infinity;const map=new Map()
    for(const point of series[0].points){if(!Number.isFinite(point.timeDays)||point.timeDays<=previous||!Number.isFinite(point.value))return fail('新井报告时刻或数值无效。');map.set(point.timeDays,point.value);previous=point.timeDays}
    vectors[key]=map
  }
  const clocks=[...vectors.WWPR.keys()]
  if(!clocks.length||Object.values(vectors).some(v=>v.size!==clocks.length||clocks.some(t=>!v.has(t))))return fail('新井压力、流量、累计量和状态没有相同报告时刻。')
  const labels={1:'生产',2:'注入',3:'关井',4:'停井',5:'优先级关井',6:'优先级停井'}
  const rows=clocks.map(timeDays=>{const state=vectors.WSTAT.get(timeDays);return{timeDays,state,stateLabel:labels[state]||'未知状态',rate:vectors.WWPR.get(timeDays),bhp:vectors.WBHP.get(timeDays),total:vectors.WWPT.get(timeDays)}})
  if(rows.some(r=>!Object.hasOwn(labels,r.state)))return fail('实际WSTAT状态编码不受支持。')
  return{available:true,rows,cumulativeUnit:required.WWPT,message:'',limitedReports:rows.filter(r=>r.timeDays>0&&r.state===1&&r.rate<p.waterRate-1e-6&&Math.abs(r.bhp-p.bhpLimit)<Math.max(.01,p.bhpLimit*1e-6)).length,shutReports:rows.filter(r=>[3,4,5,6].includes(r.state)).length}
}
