<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { createFieldRequestGate } from './eclipseFieldRequest.js'
import { spatialSnapshot, differenceColor } from './eclipseSpatialComparison.js'
import { loadSpatialGeometry } from './eclipseSpatialGeometry.js'
import { gridValueColor, formatGridValue } from './eclipseGridPresentation.js'
const props=defineProps({run:Object,baseline:Object,comparison:Object,unit:String,selectedIndex:Number})
const emit=defineEmits(['select-cell'])
const host=ref(null),geometry=ref(null),loading=ref(false),error=ref(''),ready=ref(false),mode=ref('difference'),layer=ref(0),scale=ref(1),wire=ref(true),locateI=ref(1),locateJ=ref(1),locateK=ref(1),locateError=ref(''),visibleCount=ref(0)
const selected=computed(()=>props.comparison?.cells.find(c=>c.index===props.selectedIndex))
const magnitude=computed(()=>Math.max(Math.abs(props.comparison.differenceStats.min),Math.abs(props.comparison.differenceStats.max)))
const bounds=computed(()=>mode.value==='difference'?{min:-magnitude.value,max:magnitude.value}:{min:Math.min(props.comparison.baselineStats.min,props.comparison.currentStats.min),max:Math.max(props.comparison.baselineStats.max,props.comparison.currentStats.max)})
const title=computed(()=>({difference:'方案 − 基线',baselineValue:`基线 #${props.baseline.id}`,value:`方案 #${props.run.id}`})[mode.value])
const gate=createFieldRequestGate();let renderer,scene,camera,controls,observer,group,mesh,marker,frame,generation=0,visible=[],pointerDown
const faces=[[0,1,3,2],[4,6,7,5],[0,4,5,1],[2,3,7,6],[0,2,6,4],[1,5,7,3]],edges=[[0,1],[1,3],[3,2],[2,0],[4,5],[5,7],[7,6],[6,4],[0,4],[1,5],[2,6],[3,7]]
const position=p=>new THREE.Vector3(p[0]-geometry.value.center[0],p[2]-geometry.value.center[2],-(p[1]-geometry.value.center[1]))
function release(object){object?.traverse(o=>{o.geometry?.dispose();if(Array.isArray(o.material))o.material.forEach(m=>m.dispose());else o.material?.dispose()})}
function dispose(){observer?.disconnect();cancelAnimationFrame(frame);controls?.dispose();if(renderer){renderer.domElement.removeEventListener('pointerdown',down);renderer.domElement.removeEventListener('pointerup',up);renderer.dispose();renderer.forceContextLoss();renderer.domElement.remove()}release(scene);renderer=null;scene=null;camera=null;group=null;mesh=null;marker=null;ready.value=false}
function fit(reset=false){
  if(!camera||!geometry.value)return
  const points=geometry.value.cells.filter(c=>layer.value===0||c.k===layer.value).flatMap(c=>c.points.map(p=>{const q=position(p);q.y*=scale.value;return q}))
  if(!points.length)return
  const box=new THREE.Box3().setFromPoints(points),center=box.getCenter(new THREE.Vector3()),dir=(reset?new THREE.Vector3(1,.85,1.2):camera.position.clone().sub(controls.target)).normalize(),right=new THREE.Vector3(0,1,0).cross(dir).normalize(),up=dir.clone().cross(right).normalize(),tanY=Math.tan(THREE.MathUtils.degToRad(camera.fov/2))*.88,tanX=tanY*camera.aspect
  let distance=1
  for(const p of points){const relative=p.clone().sub(center);distance=Math.max(distance,Math.max(Math.abs(relative.dot(right))/tanX,Math.abs(relative.dot(up))/tanY)+relative.dot(dir))}
  camera.position.copy(center).addScaledVector(dir,distance);camera.near=Math.max(.001,distance/10000);camera.far=distance*100;camera.updateProjectionMatrix();controls.target.copy(center);controls.update()
}
function highlight(){if(!group)return;if(marker){group.remove(marker);release(marker);marker=null}const cell=visible.find(c=>c.index===props.selectedIndex);if(!cell)return;const positions=[];for(const[a,b]of edges)positions.push(...position(cell.points[a]).toArray(),...position(cell.points[b]).toArray());const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));marker=new THREE.LineSegments(g,new THREE.LineBasicMaterial({color:'#eea52d',depthTest:false}));marker.renderOrder=20;group.add(marker)}
function paint(){
  if(!scene||!geometry.value)return
  if(group){scene.remove(group);release(group)}
  const byIndex=new Map(props.comparison.cells.map(c=>[c.index,c]));visible=geometry.value.cells.filter(c=>layer.value===0||c.k===layer.value);visibleCount.value=visible.length
  const positions=[],colors=[],lines=[]
  for(const c of visible){const values=byIndex.get(c.index);if(!values)throw new Error('三维单元缺少当前场值。');const color=new THREE.Color(mode.value==='difference'?differenceColor(values.difference,magnitude.value):gridValueColor(values[mode.value],bounds.value));for(const face of faces)for(const n of [face[0],face[1],face[2],face[0],face[2],face[3]]){positions.push(...position(c.points[n]).toArray());colors.push(color.r,color.g,color.b)}for(const[a,b]of edges)lines.push(...position(c.points[a]).toArray(),...position(c.points[b]).toArray())}
  const g=new THREE.BufferGeometry();g.setAttribute('position',new THREE.Float32BufferAttribute(positions,3));g.setAttribute('color',new THREE.Float32BufferAttribute(colors,3));mesh=new THREE.Mesh(g,new THREE.MeshBasicMaterial({vertexColors:true,side:THREE.DoubleSide}));group=new THREE.Group();group.scale.y=scale.value;group.add(mesh)
  if(wire.value){const e=new THREE.BufferGeometry();e.setAttribute('position',new THREE.Float32BufferAttribute(lines,3));group.add(new THREE.LineSegments(e,new THREE.LineBasicMaterial({color:'#536d87',transparent:true,opacity:.3})))}scene.add(group);highlight();renderer.render(scene,camera)
}
function down(e){pointerDown=[e.clientX,e.clientY]}
function up(e){if(!mesh||!pointerDown||Math.hypot(e.clientX-pointerDown[0],e.clientY-pointerDown[1])>5)return;const rect=renderer.domElement.getBoundingClientRect(),ray=new THREE.Raycaster();ray.setFromCamera(new THREE.Vector2(2*(e.clientX-rect.left)/rect.width-1,1-2*(e.clientY-rect.top)/rect.height),camera);const hit=ray.intersectObject(mesh)[0],cell=hit&&visible[Math.floor(hit.faceIndex/12)];if(cell)emit('select-cell',cell.index)}
async function load(){
  const current=gate.begin();geometry.value=null;error.value='';loading.value=true;dispose()
  try{const value=await loadSpatialGeometry({run:spatialSnapshot(props.run),baseline:spatialSnapshot(props.baseline),comparison:props.comparison,request:softwareIntegrationApi.downloadArtifactRange,isCurrent:current});if(current())geometry.value=value}
  catch(e){if(current())error.value=e.message||'三维角点读取失败。'}finally{if(current())loading.value=false}
}
async function mount(){const token=++generation;await nextTick();if(token!==generation||!host.value||!geometry.value)return;dispose();try{
  scene=new THREE.Scene();scene.background=new THREE.Color('#f4f8fc');camera=new THREE.PerspectiveCamera(40,Math.max(host.value.clientWidth,1)/Math.max(host.value.clientHeight,1),.01,1e7);renderer=new THREE.WebGLRenderer({antialias:true,preserveDrawingBuffer:true});renderer.setPixelRatio(Math.min(devicePixelRatio||1,2));renderer.setSize(host.value.clientWidth,host.value.clientHeight);host.value.appendChild(renderer.domElement);controls=new OrbitControls(camera,renderer.domElement);controls.enableDamping=true;renderer.domElement.addEventListener('pointerdown',down);renderer.domElement.addEventListener('pointerup',up);fit(true);paint();ready.value=true
  observer=new ResizeObserver(()=>{if(!renderer||!host.value)return;camera.aspect=Math.max(host.value.clientWidth,1)/Math.max(host.value.clientHeight,1);renderer.setSize(host.value.clientWidth,host.value.clientHeight);fit()});observer.observe(host.value)
  const animate=()=>{frame=requestAnimationFrame(animate);controls.update();renderer.render(scene,camera)};animate()
}catch(e){dispose();error.value='当前环境无法加载三维视图，请使用层剖面和CSV。'}}
watch([()=>props.run?.id,()=>props.baseline?.id,()=>props.comparison],load,{immediate:true})
watch(geometry,mount)
watch([mode,wire],()=>{if(ready.value)paint()})
watch(layer,()=>{if(ready.value){fit();paint()}})
watch(scale,value=>{if(!Number.isFinite(value)||value<1||value>20){scale.value=1;return}if(ready.value){fit();paint()}})
watch(()=>props.selectedIndex,()=>{highlight();if(selected.value){locateI.value=selected.value.i;locateJ.value=selected.value.j;locateK.value=selected.value.k}},{immediate:true})
onBeforeUnmount(()=>{generation++;gate.dispose();dispose()})
function locate(){locateError.value='';const cell=props.comparison.cells.find(c=>c.i===locateI.value&&c.j===locateJ.value&&c.k===locateK.value);if(!cell){locateError.value='该位置不是当前活动单元。';return}emit('select-cell',cell.index)}
function exportPng(){if(!ready.value)return;renderer.render(scene,camera);const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d'),image=renderer.domElement;canvas.width=image.width;canvas.height=image.height+140;ctx.fillStyle='#fff';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.fillStyle='#285774';ctx.font='bold 22px sans-serif';ctx.fillText(`${props.comparison.keyword} · ${title.value} · 第${props.comparison.timeDays}天`,24,34);ctx.font='15px sans-serif';ctx.fillText(`基线 #${props.baseline.id} / 方案 #${props.run.id} | ${props.unit} | K=${layer.value||'全部'} | 纵向×${scale.value}`,24,66);ctx.drawImage(image,0,90);ctx.fillText(`范围 ${bounds.value.min} — ${bounds.value.max} ${props.unit} | EGRID真实角点 | 方案−基线`,24,canvas.height-18);const a=document.createElement('a');a.href=canvas.toDataURL('image/png');a.download=`eclipse-${props.run.id}-${props.comparison.keyword}-3d-difference.png`;a.click()}
</script>
<template>
  <section class="spatial-3d" aria-label="ECLIPSE真实网格三维方案对比" :data-ready="ready?'true':'false'" :data-mode="mode" :data-visible-cells="visibleCount">
    <header><div><span>REAL EGRID / SCENARIO DIFFERENCE</span><h4>旋转网格，查看变化发生的位置</h4></div><el-button size="small" :disabled="!ready" @click="exportPng">导出三维差值PNG</el-button></header>
    <el-alert v-if="error" type="warning" :closable="false" :title="error"/><p v-if="loading">正在读取并核对真实EGRID角点…</p>
    <div class="spatial-3d-controls"><el-radio-group v-model="mode" aria-label="三维比较显示方式"><el-radio-button value="difference">方案差值</el-radio-button><el-radio-button value="baselineValue">原基线</el-radio-button><el-radio-button value="value">当前方案</el-radio-button></el-radio-group><el-select v-model="layer" aria-label="三维差值显示层"><el-option :value="0" label="全部层"/><el-option v-for="k in comparison.grid.nz" :key="k" :value="k" :label="`K = ${k}`"/></el-select><label>纵向倍数 <el-input-number v-model="scale" :min="1" :max="20" :step="1" aria-label="三维差值纵向倍数"/></label><el-checkbox v-model="wire">网格线</el-checkbox><el-button size="small" :disabled="!ready" @click="fit(true)">重置三维视角</el-button></div>
    <div class="spatial-3d-stage"><div ref="host" class="spatial-3d-canvas" role="img" aria-label="真实角点几何与方案差值三维网格"/><div class="spatial-3d-context">{{comparison.keyword}} · {{title}} · 报告{{comparison.report}}<small>第{{comparison.timeDays}}天 · {{visibleCount}} / {{comparison.cells.length}} 活动格 · 纵向×{{scale}}</small></div><div class="spatial-3d-legend"><div :class="{difference:mode==='difference'}"/><span>{{formatGridValue(bounds.min)}} — {{formatGridValue(bounds.max)}} {{unit}}</span></div></div>
    <div class="spatial-3d-locate"><label>I <el-input-number v-model="locateI" :min="1" :max="comparison.grid.nx" :step="1" aria-label="三维差值定位I"/></label><label>J <el-input-number v-model="locateJ" :min="1" :max="comparison.grid.ny" :step="1" aria-label="三维差值定位J"/></label><label>K <el-input-number v-model="locateK" :min="1" :max="comparison.grid.nz" :step="1" aria-label="三维差值定位K"/></label><el-button size="small" :disabled="!ready" @click="locate">定位并查看单格历史</el-button><span v-if="locateError" role="status">{{locateError}}</span></div>
    <p>拖动旋转、滚轮缩放，点选网格可查看下方同一单元的历史曲线。差值蓝色表示降低、红色表示升高、白色表示不变；两幅原值使用共享色标。角点来自同一全局EGRID；纵向×1为原比例。本视图首期最多2万活动格，大模型保留层剖面与CSV。</p>
  </section>
</template>
<style scoped>
.spatial-3d{margin-top:18px;padding:20px;border:1px solid #d7e4ef;border-radius:12px;background:linear-gradient(140deg,#f0f7ff,#fff 65%);min-width:0;color:#567a96}.spatial-3d header{display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;align-items:start}.spatial-3d header span{font-size:10px;letter-spacing:.1em;color:#4b89b6;font-weight:700}.spatial-3d h4{margin:8px 0 16px;color:#315777;font-size:18px}.spatial-3d-controls{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin-bottom:14px}.spatial-3d-controls>.el-select{width:130px}.spatial-3d-controls label{display:flex;align-items:center;gap:8px;font-size:11px}.spatial-3d-controls .el-input-number{width:110px}.spatial-3d-stage{position:relative;border:1px solid #dce7f1;border-radius:9px;overflow:hidden;background:#f4f8fc}.spatial-3d-canvas{height:490px;min-width:0}.spatial-3d-context{position:absolute;top:14px;left:16px;font-size:12px;color:#3b6b91;pointer-events:none}.spatial-3d-context small{display:block;font-size:10px;margin-top:7px;color:#7d99ae}.spatial-3d-legend{position:absolute;bottom:15px;left:16px;padding:10px;background:#ffffffdf;border-radius:7px;font-size:10px;color:#5d7e98;pointer-events:none}.spatial-3d-legend>div{width:220px;height:7px;margin-bottom:7px;border-radius:5px;background:linear-gradient(90deg,#1c50e3,#1ce4d1,#6ee51b,#e6da1c,#e51c1c)}.spatial-3d-legend>div.difference{background:linear-gradient(90deg,#266dd9,#f5f7fb,#e63d2a)}.spatial-3d-locate{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:14px;font-size:11px}.spatial-3d-locate label{display:flex;gap:5px;align-items:center}.spatial-3d-locate .el-input-number{width:105px}.spatial-3d p{font-size:11px;line-height:1.8;color:#829aab}@media(max-width:550px){.spatial-3d{padding:12px}.spatial-3d-canvas{height:360px}.spatial-3d-controls>.el-select{width:100%}.spatial-3d h4{font-size:16px}}
</style>
