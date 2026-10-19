import test from 'node:test'
import assert from 'node:assert/strict'
import { buildSpatialGeometry } from '../src/views/SoftwareIntegration/eclipseSpatialGeometry.js'
import { zcornCornerIndices } from '../src/views/SoftwareIntegration/eclipseCornerGeometry.js'
function fixture(){
  const grid={nx:2,ny:2,nz:2,activeCells:6},mask=[1,0,1,1,0,1,1,1],coord=[],zcorn=Array(64).fill(0),cells=[]
  for(let j=0;j<3;j++)for(let i=0;i<3;i++)coord.push(i*10,j*20,0,i*10+10,j*20+20,200)
  for(let k=0;k<2;k++)for(let j=0;j<2;j++)for(let i=0;i<2;i++){zcornCornerIndices(2,2,i,j,k).forEach((at,n)=>zcorn[at]=(k+(n<4?0:1))*100);const index=k*4+j*2+i;if(mask[index]>0)cells.push({index,i:i+1,j:j+1,k:k+1})}
  return {grid,coord,zcorn,mask,cells}
}
test('true corner ordering and sloping pillars preserve source coordinates without a Cartesian substitute',()=>{
  const f=fixture(),g=buildSpatialGeometry(f.grid,f.coord,f.zcorn,f.mask,f.cells)
  assert.equal(g.cells.length,6)
  assert.deepEqual(g.cells[0].points,[[0,0,0],[10,0,0],[0,20,0],[10,20,0],[5,10,100],[15,10,100],[5,30,100],[15,30,100]])
  assert.deepEqual(g.cells.at(-1).points,[[15,30,100],[25,30,100],[15,50,100],[25,50,100],[20,40,200],[30,40,200],[20,60,200],[30,60,200]])
  assert.deepEqual(g.min,[0,0,0]);assert.deepEqual(g.max,[30,60,200]);assert.deepEqual(g.center,[15,30,100])
})
test('mismatched active counts, duplicate cell indices and incorrect IJK are rejected',()=>{
  for(const mutate of [f=>f.mask[0]=0,f=>f.cells[1]={...f.cells[0]},f=>f.cells[0].i=2,f=>f.cells[0].index=1]){const f=fixture();mutate(f);assert.throws(()=>buildSpatialGeometry(f.grid,f.coord,f.zcorn,f.mask,f.cells))}
})
test('missing or nonfinite source geometry cannot be drawn as an invented regular grid',()=>{
  for(const mutate of [f=>f.coord.pop(),f=>f.zcorn.pop(),f=>f.zcorn[1]=NaN,f=>f.coord[0]=Infinity]){const f=fixture();mutate(f);assert.throws(()=>buildSpatialGeometry(f.grid,f.coord,f.zcorn,f.mask,f.cells))}
})
test('zero depth span with sloping endpoints is refused rather than guessed',()=>{
  const f=fixture();f.coord[5]=0;assert.throws(()=>buildSpatialGeometry(f.grid,f.coord,f.zcorn,f.mask,f.cells),/零深度跨度/)
})
