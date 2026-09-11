<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pipelineCapacityApi as api } from '@/api/pipelineCapacity'
import { newSegment, newEquipment } from '@/utils/pipelineDefaults'
import { checkTopology, clone, createNode, fromInput, nodeTypes, topologyDrafts, uid, removeObjects, normalizeTopologyPvtReferences } from '@/utils/pipelineTopology'
import { networkOrder } from '@/utils/pipelineBatch'
import PipelineNumber from './PipelineNumber.vue'
const props = defineProps({ context: Object })
const emit = defineEmits(['saved', 'close'])
const cacheKey = JSON.stringify(props.context)
const oldCached = topologyDrafts.get(cacheKey)
const cached = oldCached ? {...oldCached,graph:normalizeTopologyPvtReferences(oldCached.graph),saved:oldCached.saved?JSON.stringify(normalizeTopologyPvtReferences(JSON.parse(oldCached.saved))):''}:null
const preserveDraft = cached && JSON.stringify(cached.graph) !== cached.saved
const emptyTopology = () => fromInput({segments:[], equipment:[]}, props.context.wellName)
const graph = ref(cached?.graph || emptyTopology())
const revision = ref(cached?.revision || 0), loaded = ref(false)
const saved = ref(cached?.saved || ''), busy = ref(false), loadError = ref('')
const selection = ref([]), mode = ref('select'), connecting = ref(''), svg = ref(null), box = ref(null)
const history = ref([JSON.stringify(graph.value)]), cursor = ref(0)
const checked = ref(false)
let gesture = null
const selectedNode = computed(() => selection.value.length === 1 ? graph.value.nodes.find(n => n.id === selection.value[0]) : null)
const selectedEdge = computed(() => selection.value.length === 1 ? graph.value.edges.find(n => n.id === selection.value[0]) : null)
const selected = computed(() => selectedNode.value || selectedEdge.value)
const check = computed(() => checkTopology(graph.value))
const networkCheck = computed(() => { try { networkOrder(graph.value); return { ok: true, error: '' } } catch (e) { return { ok: false, error: e.message } } })
const dirty = computed(() => saved.value !== JSON.stringify(graph.value))
const transform = computed(() => `translate(${graph.value.layout.x} ${graph.value.layout.y}) scale(${graph.value.layout.zoom})`)
const segments = computed(() => graph.value.edges.map(edge => ({ ...edge, from: graph.value.nodes.find(n => n.id === edge.source), to: graph.value.nodes.find(n => n.id === edge.target) })).filter(e => e.from && e.to))
function commit() {
  const value = JSON.stringify(graph.value)
  if (history.value[cursor.value] === value) return
  history.value.splice(cursor.value + 1); history.value.push(value)
  if (history.value.length > 60) history.value.shift()
  cursor.value = history.value.length - 1
}
function undo(delta) { const next = cursor.value + delta; if (next < 0 || next >= history.value.length) return; cursor.value = next; graph.value = JSON.parse(history.value[next]); selection.value = []; connecting.value = '' }
function resetHistory() { history.value = [JSON.stringify(graph.value)]; cursor.value = 0; selection.value = []; connecting.value = ''; checked.value = false }
function point(event) { const rect = svg.value.getBoundingClientRect(); return { x: (event.clientX - rect.left - graph.value.layout.x) / graph.value.layout.zoom, y: (event.clientY - rect.top - graph.value.layout.y) / graph.value.layout.zoom } }
function add(type, position) {
  if (type === 'well') return
  if (graph.value.nodes.length >= 200) return ElMessage.warning('最多支持 200 个节点')
  const rect = svg.value?.getBoundingClientRect()
  const p = position || { x: ((rect?.width || 600) / 2 - graph.value.layout.x) / graph.value.layout.zoom, y: ((rect?.height || 500) / 2 - graph.value.layout.y) / graph.value.layout.zoom }
  const node = createNode(type, p.x, p.y, graph.value.nodes.length)
  graph.value.nodes.push(node); selection.value = [node.id]; commit()
}
function drop(event) { const type = event.dataTransfer.getData('application/x-pipeline-node'); if (nodeTypes.some(n => n.type === type)) add(type, point(event)) }
function paletteDrag(event,type) { event.dataTransfer.setData('application/x-pipeline-node',type); event.dataTransfer.effectAllowed = 'copy' }
function nodeDown(event,node) {
  if (busy.value || event.button !== 0) return
  if (mode.value === 'pan') { canvasDown(event); return }
  event.stopPropagation()
  if (mode.value === 'connect') {
    if (!connecting.value) { connecting.value = node.id; selection.value = [node.id]; return }
    if (node.type === 'well') { connecting.value = ''; return ElMessage.warning('当前井井口只能作为入口') }
    if (connecting.value === node.id) { connecting.value = ''; return }
    if (graph.value.edges.some(e => e.source === connecting.value && e.target === node.id)) return ElMessage.warning('这两个节点之间已有同向管段')
    if (graph.value.edges.length >= 400) return ElMessage.warning('最多支持 400 个管段')
    const edge = { id:uid(), source:connecting.value, target:node.id, name:`管段${graph.value.edges.length + 1}`, parameters:newSegment(graph.value.edges.length) }
    graph.value.edges.push(edge); connecting.value = ''; selection.value = [edge.id]; commit(); return
  }
  if (event.shiftKey) selection.value = selection.value.includes(node.id) ? selection.value.filter(id => id !== node.id) : [...selection.value,node.id]
  else if (!selection.value.includes(node.id)) selection.value = [node.id]
  const p = point(event)
  gesture = { type:'nodes', start:p, originals:graph.value.nodes.filter(n => selection.value.includes(n.id)).map(n => ({id:n.id,x:n.x,y:n.y})) }
  svg.value.setPointerCapture(event.pointerId)
}
function canvasDown(event) {
  if (busy.value || event.button !== 0) return
  const p = point(event)
  connecting.value = ''
  if (mode.value === 'pan') gesture = {type:'pan',x:event.clientX,y:event.clientY,layout:{...graph.value.layout}}
  else { selection.value = []; gesture = {type:'box',start:p}; box.value = {x:p.x,y:p.y,width:0,height:0} }
  svg.value.setPointerCapture(event.pointerId)
}
function move(event) {
  if (!gesture) return
  const p = point(event)
  if (gesture.type === 'nodes') for (const origin of gesture.originals) { const n = graph.value.nodes.find(n => n.id === origin.id); n.x = Math.round(origin.x + p.x - gesture.start.x); n.y = Math.round(origin.y + p.y - gesture.start.y) }
  if (gesture.type === 'pan') { graph.value.layout.x = gesture.layout.x + event.clientX - gesture.x; graph.value.layout.y = gesture.layout.y + event.clientY - gesture.y }
  if (gesture.type === 'box') box.value = {x:Math.min(p.x,gesture.start.x),y:Math.min(p.y,gesture.start.y),width:Math.abs(p.x-gesture.start.x),height:Math.abs(p.y-gesture.start.y)}
}
function end() {
  if (!gesture) return
  if (gesture.type === 'box' && box.value) { const b = box.value; selection.value = graph.value.nodes.filter(n => n.x >= b.x && n.x <= b.x+b.width && n.y >= b.y && n.y <= b.y+b.height).map(n => n.id) }
  gesture = null; box.value = null; commit()
}
function zoom(factor,event) {
  const rect = svg.value.getBoundingClientRect(), l = graph.value.layout
  const x = event ? event.clientX-rect.left : rect.width/2, y = event ? event.clientY-rect.top : rect.height/2
  const next = Math.max(0.2,Math.min(2.5,l.zoom*factor)), scale = next/l.zoom
  l.x = x-(x-l.x)*scale; l.y = y-(y-l.y)*scale; l.zoom = next; commit()
}
function fit() {
  if (!graph.value.nodes.length || !svg.value) return
  const r = svg.value.getBoundingClientRect(), ns = graph.value.nodes
  const minX = Math.min(...ns.map(n=>n.x))-80, maxX = Math.max(...ns.map(n=>n.x))+80, minY = Math.min(...ns.map(n=>n.y))-70, maxY = Math.max(...ns.map(n=>n.y))+70
  const zoom = Math.max(0.2,Math.min(1.2,(r.width-50)/(maxX-minX),(r.height-50)/(maxY-minY)))
  graph.value.layout = {zoom,x:(r.width-(maxX+minX)*zoom)/2,y:(r.height-(maxY+minY)*zoom)/2}; commit()
}
function remove() {
  removeObjects(graph.value, selection.value)
  selection.value = []; connecting.value = ''; commit()
}
function keydown(event) {
  if (busy.value || ['INPUT','TEXTAREA','SELECT','BUTTON'].includes(event.target.tagName)) return
  if (event.key === 'Delete' || event.key === 'Backspace') {event.preventDefault();remove()}
  if (event.key === 'Escape') {connecting.value='';mode.value='select';selection.value=[]}
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {event.preventDefault();undo(event.shiftKey ? 1 : -1)}
}
async function discard() { if (!dirty.value) return true; try {await ElMessageBox.confirm('当前拓扑有未保存修改，继续将丢弃这些修改。','切换拓扑',{confirmButtonText:'继续',cancelButtonText:'返回编辑'});return true} catch{return false} }
async function load(manual = false) {
  if (manual && !(await discard())) return
  busy.value=true;loadError.value=''
  try {
    const data=(await api.topology(props.context)).data
    if (!preserveDraft || manual) {
      graph.value=normalizeTopologyPvtReferences(data?.graph) || emptyTopology()
      revision.value=data?.revision || 0; saved.value=JSON.stringify(graph.value);resetHistory()
    }
    loaded.value=true
  } catch(e) {loadError.value=e.msg || e.message || '当前井拓扑加载失败'} finally {busy.value=false}
}
async function save() {
  if (!loaded.value) return
  if (graph.value.nodes.some(n=>!n.name.trim()) || graph.value.edges.some(e=>!e.name.trim())) return ElMessage.warning('节点和管段名称不能为空')
  busy.value=true
  try {
    const data=(await api.saveTopology({...props.context,revision:revision.value,graph:normalizeTopologyPvtReferences(graph.value)})).data
    graph.value=normalizeTopologyPvtReferences(data.graph); revision.value=data.revision; saved.value=JSON.stringify(graph.value)
    commit(); emit('saved',clone(data)); ElMessage.success('拓扑已保存，管流计算将自动加载')
  } catch(e) {ElMessage.error(e.msg || e.message)} finally {busy.value=false}
}
function addExtraEquipment() {
  const node=selectedNode.value
  if (!node || !['valve','compressor'].includes(node.type)) return
  node.parameters.extraEquipment ||= []
  node.parameters.extraEquipment.push(newEquipment(node.parameters.extraEquipment.length + 1)); commit()
}
function removeExtraEquipment(index) {
  selectedNode.value.parameters.extraEquipment.splice(index,1); commit()
}
function protect(event) {if(dirty.value){event.preventDefault();event.returnValue=''}}
onMounted(()=>{load();window.addEventListener('beforeunload',protect)})
onBeforeUnmount(()=>{if(loaded.value) topologyDrafts.set(cacheKey,clone({graph:graph.value,revision:revision.value,saved:saved.value}));window.removeEventListener('beforeunload',protect)})
</script>

<template>
  <section class="topology-editor" :class="{busy}" tabindex="0" @keydown="keydown">
    <div class="topology-heading"><strong>管网拓扑结构</strong><span>{{ context.wellName }}</span><small>{{ dirty ? '有未保存修改' : revision ? '已保存' : '初始拓扑待确认' }}</small><span class="spacer"/><button @click="emit('close')">返回管流计算</button><button @click="load(true)">重新加载</button><button class="primary" :disabled="!loaded" @click="save">保存</button></div>
    <div class="topology-tools"><button :disabled="cursor===0" @click="undo(-1)">撤销</button><button :disabled="cursor===history.length-1" @click="undo(1)">重做</button><button :disabled="!selection.some(id=>!graph.nodes.some(n=>n.id===id&&n.type==='well'))" @click="remove">删除</button><i/><button :class="{active:mode==='select'}" @click="mode='select';connecting=''">选择</button><button :class="{active:mode==='connect'}" @click="mode='connect';connecting=''">连接管线</button><button :class="{active:mode==='pan'}" @click="mode='pan'">移动画布</button><button @click="fit">适应画布</button><span class="spacer"/><button @click="checked=true">连接检查</button></div>
    <div class="topology-source">保存后，管流计算自动加载当前井拓扑。边界条件、气体物性在管流计算中设置。</div>
    <div v-if="loadError" class="load-error">{{ loadError }} <button @click="load()">重试</button></div>
    <div v-if="loaded" class="editor-body">
      <aside class="palette"><h4>元件库</h4><p>当前井井口固定保留</p><button v-for="item in nodeTypes.filter(n=>n.type!=='well')" :key="item.type" draggable="true" @dragstart="paletteDrag($event,item.type)" @click="add(item.type)"><b :class="item.type">{{ item.symbol }}</b>{{ item.label }}</button><div class="palette-note">管线由节点连接生成。<br/>点击起点，再点击终点。<br/><br/>滚轮缩放 · 拖动框选<br/>Shift 多选 · Delete 删除</div></aside>
      <div class="canvas-wrap"><div class="canvas-hint">{{ mode==='connect' ? connecting ? '请选择终点节点 · Esc 取消' : '请选择起点节点' : mode==='pan' ? '拖动画布调整视野' : '拖动节点调整布局，点击管线编辑参数' }}</div>
        <svg ref="svg" class="topology-canvas" :class="mode" aria-label="管网拓扑编辑画布" @pointerdown="canvasDown" @pointermove="move" @pointerup="end" @pointercancel="end" @dragover.prevent @drop.prevent="drop" @wheel.prevent="zoom($event.deltaY<0 ? 1.1 : 1/1.1,$event)">
          <defs><pattern id="topology-grid" width="20" height="20" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#d7dfe8"/></pattern><marker id="topology-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#6388a8"/></marker></defs>
          <rect width="100%" height="100%" fill="url(#topology-grid)"/>
          <g :transform="transform">
            <g v-for="edge in segments" :key="edge.id" class="edge" :class="{selected:selection.includes(edge.id)}" @pointerdown.stop="selection=[edge.id]">
              <path class="edge-hit" :d="`M ${edge.from.x} ${edge.from.y} L ${edge.to.x} ${edge.to.y}`"/>
              <path class="edge-line" marker-end="url(#topology-arrow)" :d="`M ${edge.from.x} ${edge.from.y} L ${edge.to.x-(edge.to.x-edge.from.x)/Math.max(1,Math.hypot(edge.to.x-edge.from.x,edge.to.y-edge.from.y))*32} ${edge.to.y-(edge.to.y-edge.from.y)/Math.max(1,Math.hypot(edge.to.x-edge.from.x,edge.to.y-edge.from.y))*32}`"/>
              <text :x="(edge.from.x+edge.to.x)/2" :y="(edge.from.y+edge.to.y)/2-12" text-anchor="middle">{{ edge.name }}</text><text class="edge-detail" :x="(edge.from.x+edge.to.x)/2" :y="(edge.from.y+edge.to.y)/2+18" text-anchor="middle">{{ edge.parameters.lengthM }} m / DN {{ edge.parameters.diameterMm }}</text>
            </g>
            <g v-for="node in graph.nodes" :key="node.id" class="graph-node" :class="[node.type,{selected:selection.includes(node.id),connecting:connecting===node.id}]" :transform="`translate(${node.x} ${node.y})`" @pointerdown="nodeDown($event,node)">
              <rect x="-30" y="-27" width="60" height="54" :rx="node.type==='junction'||node.type==='split'?27:8"/>
              <text text-anchor="middle" y="7" class="symbol">{{ nodeTypes.find(t=>t.type===node.type)?.symbol }}</text><text text-anchor="middle" y="48" class="node-name">{{ node.name }}</text><circle cx="30" cy="0" r="4" class="port"/>
            </g>
            <rect v-if="box" v-bind="box" fill="#409eff22" stroke="#409eff" stroke-dasharray="4 3"/>
          </g>
          <text v-if="!graph.nodes.length" x="50%" y="48%" text-anchor="middle" fill="#8b98a7" font-size="15">从左侧拖入设备和集气站，开始绘制管网</text>
        </svg>
        <div class="canvas-zoom"><button @click="zoom(1/1.2)">−</button><span>{{ Math.round(graph.layout.zoom*100) }}%</span><button @click="zoom(1.2)">＋</button></div>
      </div>
      <aside class="properties" @change="commit"><h4>{{ selectedNode ? '节点属性' : selectedEdge ? '管段属性' : '属性设置' }}</h4>
        <template v-if="selected"><label>名称<input v-model="selected.name" :readonly="selectedNode?.type==='well'" maxlength="100"/></label></template>
        <template v-if="selectedNode"><label>类型<span>{{ nodeTypes.find(t=>t.type===selectedNode.type)?.label }}</span></label><PipelineNumber v-model="selectedNode.parameters.elevationM" label="节点高程（m）"/>
          <template v-if="['valve','compressor'].includes(selectedNode.type)">
            <PipelineNumber v-if="selectedNode.type==='valve'" v-model="selectedNode.parameters.lossK" label="局部阻力系数 K" :min="0"/>
            <template v-else><PipelineNumber v-model="selectedNode.parameters.pressureRatio" label="压缩比" :min="1" :max="5"/><PipelineNumber v-model="selectedNode.parameters.efficiency" label="等熵效率" :min="0.01" :max="1"/><PipelineNumber v-model="selectedNode.parameters.maxPowerKw" label="最大功率（kW）" :min="1"/></template>
            <PipelineNumber v-model="selectedNode.parameters.maxPressureMpa" label="最大允许压力（MPa绝压）" :min="0.02"/>
            <div class="equipment-heading"><span>同位置串联设备</span><button @click="addExtraEquipment">新增</button></div>
            <p class="note">附加设备按下列顺序接在当前设备之后，随拓扑保存。</p>
            <div v-for="(equipment,index) in selectedNode.parameters.extraEquipment || []" :key="index" class="extra-equipment">
              <div class="equipment-heading"><span>设备 {{ index+2 }}</span><button @click="removeExtraEquipment(index)">删除</button></div>
              <label>名称<input v-model="equipment.name" maxlength="100"/></label>
              <label>类型<select v-model="equipment.type"><option value="valve">阀门</option><option value="compressor">压缩机</option></select></label>
              <PipelineNumber v-if="equipment.type==='valve'" v-model="equipment.lossK" label="局部阻力系数 K" :min="0"/>
              <template v-else><PipelineNumber v-model="equipment.pressureRatio" label="压缩比" :min="1" :max="5"/><PipelineNumber v-model="equipment.efficiency" label="等熵效率" :min="0.01" :max="1"/><PipelineNumber v-model="equipment.maxPowerKw" label="最大功率（kW）" :min="1"/></template>
              <PipelineNumber v-model="equipment.maxPressureMpa" label="最大允许压力（MPa绝压）" :min="0.02"/>
            </div>
          </template>
        </template>
        <template v-else-if="selectedEdge"><label>起点<select v-model="selectedEdge.source"><option v-for="n in graph.nodes" :key="n.id" :value="n.id">{{ n.name }}</option></select></label><label>终点<select v-model="selectedEdge.target"><option v-for="n in graph.nodes.filter(n=>n.type!=='well')" :key="n.id" :value="n.id">{{ n.name }}</option></select></label><PipelineNumber v-model="selectedEdge.parameters.lengthM" label="管长（m）" :min="0.01"/><PipelineNumber v-model="selectedEdge.parameters.diameterMm" label="内径（mm）" :min="1"/><PipelineNumber v-model="selectedEdge.parameters.roughnessMm" label="粗糙度（mm）" :min="0"/><p class="note">高差由两端节点高程计算。环境温度、材料及传热参数在温度模型中维护，计算时自动读取。</p></template>
        <p v-else class="empty-properties">{{ selection.length>1 ? `已选择 ${selection.length} 个对象，可整体移动或删除。` : '选择节点或管段，编辑工程参数。' }}</p>
      </aside>
    </div>
    <div class="checks"><div><strong>连接检查</strong><span>{{ graph.nodes.length }} 个节点 · {{ graph.edges.length }} 个管段</span><span v-if="checked" :class="networkCheck.ok?'ok':'warning'">{{ networkCheck.ok ? '管网结构可计算' : networkCheck.error }}</span></div><template v-if="checked"><p v-for="(message,i) in check.errors" :key="'e'+i" class="error">{{ message }}</p><p v-for="(message,i) in check.warnings" :key="'w'+i" class="warning">{{ message }}</p><p v-if="!check.errors.length&&!check.warnings.length" class="ok">连接完整，管段与设备参数检查通过。</p></template><p v-else>编辑完成后点击“连接检查”。树状分支管网支持全部工况计算；汇合或环网可保存，但尚需对应求解方法。</p></div>
  </section>
</template>

<style scoped>
.topology-source{padding:6px 14px;border-bottom:1px solid #e3e8ef;color:#8a96a4;font-size:12px;background:#fafbfd}.equipment-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:12px}.extra-equipment{border-top:1px solid #e3e8ef;padding-top:12px;margin-top:12px}.extra-equipment .equipment-heading{color:#7c8a97}
.topology-editor{height:100%;min-height:0;display:flex;flex-direction:column;background:#fff;color:#374151;font-size:13px;outline:none}.busy{pointer-events:none;opacity:.65}button,input,select{font:inherit;border:1px solid #d8dfe7;border-radius:3px;background:#fff;color:#374151;min-height:28px}button{padding:4px 10px;cursor:pointer;white-space:nowrap}button:hover{border-color:#409eff;color:#1687e0}button:disabled{opacity:.45;cursor:default}button.primary{background:#409eff;border-color:#409eff;color:white}input,select{padding:3px 7px;min-width:0}button.active{background:#eaf4ff;border-color:#80bcf5;color:#1687e0}.topology-heading{display:flex;align-items:center;gap:12px;padding:10px 14px;border-bottom:1px solid #dde3ea}.topology-heading strong{color:#276394;font-size:14px}.topology-heading>span,.topology-heading small{color:#8a939f}.topology-tools{display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:8px 12px;background:#f8fafc;border-bottom:1px solid #dfe5ec}.topology-tools i{height:20px;border-right:1px solid #d8dfe7;margin:0 3px}.spacer{flex:1}.editor-body{flex:1;min-height:300px;display:flex;overflow:hidden}.palette{width:136px;flex-shrink:0;border-right:1px solid #dfe5ec;background:#fafbfd;padding:0 10px}.palette h4,.properties h4{font-size:13px;margin:0 -10px 12px;padding:11px 12px;border-bottom:1px solid #e3e8ef;font-weight:500;background:#f5f7fa}.palette p{font-size:11px;color:#8c97a4}.palette button{display:flex;align-items:center;width:100%;gap:12px;margin:10px 0;padding:7px 9px;text-align:left}.palette b{width:25px;height:25px;line-height:25px;text-align:center;background:#e8f3fd;color:#3b86bf;border-radius:4px;font-weight:500}.palette-note{font-size:11px;line-height:1.9;color:#94a0ae;margin-top:30px}.canvas-wrap{flex:1;min-width:100px;position:relative;background:#fcfdff;overflow:hidden}.topology-canvas{width:100%;height:100%;display:block;touch-action:none;user-select:none}.topology-canvas.pan{cursor:grab}.topology-canvas.connect{cursor:crosshair}.canvas-hint{position:absolute;top:12px;left:14px;color:#8a99aa;font-size:12px;pointer-events:none}.canvas-zoom{position:absolute;bottom:14px;right:14px;display:flex;align-items:center;background:white;border:1px solid #dfe5ec;border-radius:4px}.canvas-zoom button{border:0}.canvas-zoom span{width:48px;text-align:center;font-size:12px}.edge{cursor:pointer}.edge-hit{stroke:transparent;stroke-width:18;fill:none}.edge-line{stroke:#6388a8;stroke-width:2;fill:none}.edge.selected .edge-line{stroke:#218cef;stroke-width:3}.edge text{fill:#526a80;font-size:12px;paint-order:stroke;stroke:#fcfdff;stroke-width:4px;stroke-linejoin:round}.edge text.edge-detail{font-size:10px;fill:#90a0af}.graph-node{cursor:move}.graph-node rect{fill:#edf6ff;stroke:#87b5da;stroke-width:1.5}.graph-node.valve rect{fill:#f0f8f5;stroke:#81bba0}.graph-node.compressor rect{fill:#fff7e8;stroke:#d5b56e}.graph-node.station rect{fill:#edf0ff;stroke:#969fcf}.graph-node.selected rect,.graph-node.connecting rect{stroke:#168bf0;stroke-width:3}.symbol{font-size:21px;fill:#437397;pointer-events:none}.node-name{font-size:12px;fill:#455668;paint-order:stroke;stroke:#fcfdff;stroke-width:3px}.port{fill:white;stroke:#6f9cbf}.properties{width:254px;flex-shrink:0;border-left:1px solid #dfe5ec;padding:0 12px 14px;overflow:auto}.properties h4{margin-left:-12px;margin-right:-12px}.properties label{display:flex;flex-direction:column;gap:6px;margin:12px 0;font-size:12px}.properties label span{color:#7c8a97}.properties :deep(.field){display:flex;flex-direction:column;gap:6px;margin:12px 0}.properties :deep(input){width:100%;box-sizing:border-box;border:1px solid #d8dfe7;border-radius:3px;min-height:28px;padding:4px 7px}.properties :deep(.number-unit){display:none}.note,.empty-properties{font-size:12px;color:#8a96a4;line-height:1.8}.checks{border-top:1px solid #dfe5ec;padding:9px 14px;max-height:130px;overflow:auto;background:#fafbfd;font-size:12px}.checks>div{display:flex;gap:20px;align-items:center}.checks strong{font-weight:500}.checks span,.checks p{color:#8a97a6}.checks p{margin:6px 0}.checks .error{color:#d45656}.checks .warning{color:#b98736}.checks .ok{color:#438765}.load-error{padding:5px 12px;background:#fff4ed;color:#bd773f;font-size:12px}@media(max-width:1100px){.palette{width:112px}.properties{width:220px}.topology-heading{gap:7px}.topology-heading>span{display:none}}
</style>
