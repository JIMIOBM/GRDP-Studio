import { zcornCornerIndices } from './eclipseCornerGeometry.js'
import { validateSpatialPair, spatialFields, readSpatialValues } from './eclipseSpatialComparison.js'

export function buildSpatialGeometry(grid, coord, zcorn, mask, comparisonCells) {
  const total=grid.nx*grid.ny*grid.nz
  if(coord.length!==6*(grid.nx+1)*(grid.ny+1)||zcorn.length!==8*total||mask.length!==total||coord.some(v=>!Number.isFinite(v))||zcorn.some(v=>!Number.isFinite(v))||mask.some(v=>!Number.isInteger(v))||mask.filter(v=>v>0).length!==grid.activeCells)throw new Error('真实COORD/ZCORN/ACTNUM与全局网格不一致。')
  if(!Array.isArray(comparisonCells)||comparisonCells.length!==grid.activeCells||new Set(comparisonCells.map(c=>c.index)).size!==grid.activeCells)throw new Error('比较场与活动单元集合不一致。')
  const min=[Infinity,Infinity,Infinity],max=[-Infinity,-Infinity,-Infinity]
  const cells=comparisonCells.map(cell=>{
    const index=cell.index,i=index%grid.nx,j=Math.floor(index/grid.nx)%grid.ny,k=Math.floor(index/(grid.nx*grid.ny))
    if(!Number.isSafeInteger(index)||index<0||index>=total||mask[index]<=0||cell.i!==i+1||cell.j!==j+1||cell.k!==k+1)throw new Error('场值单元索引没有与真实EGRID定位一致。')
    const pillars=[j*(grid.nx+1)+i,j*(grid.nx+1)+i+1,(j+1)*(grid.nx+1)+i,(j+1)*(grid.nx+1)+i+1]
    const points=zcornCornerIndices(grid.nx,grid.ny,i,j,k).map((zIndex,n)=>{
      const z=zcorn[zIndex],at=pillars[n%4]*6,dz=coord[at+5]-coord[at+2],ratio=Math.abs(dz)>1e-9?(z-coord[at+2])/dz:0
      if(Math.abs(dz)<=1e-9&&(coord[at]!==coord[at+3]||coord[at+1]!==coord[at+4]))throw new Error('零深度跨度的倾斜支柱无法可靠插值。')
      const p=[coord[at]+(coord[at+3]-coord[at])*ratio,coord[at+1]+(coord[at+4]-coord[at+1])*ratio,z]
      if(p.some(v=>!Number.isFinite(v)))throw new Error('真实角点不是有限坐标。')
      p.forEach((v,a)=>{min[a]=Math.min(min[a],v);max[a]=Math.max(max[a],v)});return p
    })
    return {index,i:i+1,j:j+1,k:k+1,points}
  })
  return {cells,min,max,center:min.map((v,a)=>(v+max[a])/2),size:min.map((v,a)=>max[a]-v)}
}

export async function loadSpatialGeometry({run,baseline,comparison,request,isCurrent=()=>true}) {
  const grid=validateSpatialPair(run,baseline)
  if(grid.activeCells>20000)throw new Error('三维差值首期支持2万活动格；当前模型请使用层剖面、单格历史与CSV。')
  if(!comparison?.grid||['nx','ny','nz','activeCells','fileName'].some(k=>comparison.grid[k]!==grid[k]))throw new Error('比较场网格与三维来源不一致。')
  const fields=spatialFields(baseline),selected=['COORD','ZCORN','ACTNUM'].map(keyword=>{
    const list=fields.filter(f=>f.file===grid.fileName&&f.keyword===keyword&&f.timeStep===undefined)
    if(list.length!==1||list[0].dataType!==(keyword==='ACTNUM'?'INTE':'REAL'))throw new Error('缺少唯一的全局EGRID真实角点字段。')
    return list[0]
  })
  const values=await Promise.all(selected.map(f=>readSpatialValues(baseline,f,request,isCurrent)))
  if(!isCurrent()||values.some(v=>!v))return null
  return buildSpatialGeometry(grid,...values,comparison.cells)
}
