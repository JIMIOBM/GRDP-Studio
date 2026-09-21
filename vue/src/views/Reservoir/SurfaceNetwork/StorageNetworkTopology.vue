<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { storageCatalogApi } from '@/api/storageCatalog'
import { storageNetworkApi } from '@/api/storageNetwork'
import { checkStorageNetwork, cloneStorageNetwork, createStorageNetworkEdge, createStorageNetworkNode,
  storageNetworkNodeTypes } from '@/utils/storageNetwork'

const props = defineProps({ reservoir: { type: Object, default: null } })
const projectId = computed(() => Number(props.reservoir?.projectId))
const gasReservoirId = computed(() => Number(props.reservoir?.gasReservoirId))
const storageId = computed(() => Number(props.reservoir?.storageId))
const storageName = computed(() => props.reservoir?.label || '未选择库')
const scope = computed(() => ({ projectId: projectId.value, gasReservoirId: gasReservoirId.value, storageId: storageId.value }))

const graph = ref({ nodes: [], edges: [], layout: { x: 0, y: 0, zoom: 1 } })
const wells = ref([])
const revision = ref(0)
const saved = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const selection = ref([])
const mode = ref('select')
const connecting = ref('')
const svg = ref(null)
const selectBox = ref(null)
let gesture = null
let requestVersion = 0

const dirty = computed(() => saved.value && saved.value !== JSON.stringify(graph.value))
const selectedNode = computed(() => selection.value.length === 1
  ? graph.value.nodes.find(node => node.id === selection.value[0]) : null)
const selectedEdge = computed(() => selection.value.length === 1
  ? graph.value.edges.find(edge => edge.id === selection.value[0]) : null)
const selected = computed(() => selectedNode.value || selectedEdge.value)
const transform = computed(() => `translate(${graph.value.layout.x} ${graph.value.layout.y}) scale(${graph.value.layout.zoom})`)
const segments = computed(() => graph.value.edges.map(edge => ({ ...edge,
  from: graph.value.nodes.find(node => node.id === edge.source),
  to: graph.value.nodes.find(node => node.id === edge.target) })).filter(edge => edge.from && edge.to))
const validation = computed(() => checkStorageNetwork(graph.value, wells.value.map(well => well.id)))
const descriptor = type => storageNetworkNodeTypes.find(item => item.type === type)
const needsLimits = node => ['gathering', 'compressor', 'metering', 'external'].includes(node?.type)
const statusText = computed(() => saving.value ? '正在保存…' : loading.value ? '正在加载…'
  : dirty.value ? '有未保存修改' : revision.value ? `已保存 · 版本 ${revision.value}` : '初始拓扑待保存')

const unwrap = response => response?.data ?? response
const errorText = value => value?.response?.data?.msg || value?.msg || value?.message || '请求失败'

async function load(force = false) {
  if (force && dirty.value) {
    try {
      await ElMessageBox.confirm('当前库级管网有未保存修改，重新加载后将丢失。', '重新加载',
        { confirmButtonText: '继续', cancelButtonText: '返回编辑' })
    } catch { return }
  }
  const version = ++requestVersion
  loading.value = true; error.value = ''; selection.value = []; connecting.value = ''
  try {
    const [topologyResponse, wellsResponse] = await Promise.all([
      storageNetworkApi.topology(scope.value),
      storageCatalogApi.wells(storageId.value, projectId.value, gasReservoirId.value)
    ])
    if (version !== requestVersion) return
    const detail = unwrap(topologyResponse)
    const wellRows = unwrap(wellsResponse)
    if (!detail?.graph || !Array.isArray(wellRows)) throw new Error('库级管网接口返回格式不正确')
    graph.value = cloneStorageNetwork(detail.graph)
    wells.value = wellRows.map(well => ({ id: Number(well.id), wellName: String(well.wellName || '') }))
    revision.value = Number(detail.revision) || 0
    saved.value = JSON.stringify(graph.value)
    await nextTick(); fit(false)
  } catch (value) { if (version === requestVersion) error.value = errorText(value) }
  finally { if (version === requestVersion) loading.value = false }
}

async function save() {
  if (validation.value.errors.length) {
    ElMessage.warning(validation.value.errors[0]); return
  }
  saving.value = true
  try {
    const detail = unwrap(await storageNetworkApi.saveTopology({ ...scope.value, revision: revision.value,
      graph: cloneStorageNetwork(graph.value) }))
    graph.value = cloneStorageNetwork(detail.graph)
    revision.value = Number(detail.revision) || 0
    saved.value = JSON.stringify(graph.value)
    ElMessage.success('库级管网拓扑已保存')
  } catch (value) { ElMessage.error(errorText(value)) }
  finally { saving.value = false }
}

function point(event) {
  const rect = svg.value.getBoundingClientRect(), layout = graph.value.layout
  return { x: (event.clientX - rect.left - layout.x) / layout.zoom,
    y: (event.clientY - rect.top - layout.y) / layout.zoom }
}

function add(type) {
  if (type === 'well') return
  if (type === 'external' && graph.value.nodes.some(node => node.type === 'external')) return ElMessage.warning('只能保留一个外输管网节点')
  const node = createStorageNetworkNode(type, graph.value.nodes.length)
  const rect = svg.value?.getBoundingClientRect()
  node.x = ((rect?.width || 800) / 2 - graph.value.layout.x) / graph.value.layout.zoom
  node.y = ((rect?.height || 500) / 2 - graph.value.layout.y) / graph.value.layout.zoom
  graph.value.nodes.push(node); selection.value = [node.id]
}

function nodeDown(event, node) {
  if (saving.value || event.button !== 0) return
  event.stopPropagation()
  if (mode.value === 'connect') {
    if (!connecting.value) {
      if (node.type === 'external') return ElMessage.warning('外输管网不能作为连接起点')
      connecting.value = node.id; selection.value = [node.id]; return
    }
    if (node.type === 'well') { connecting.value = ''; return ElMessage.warning('井节点只能作为管线起点') }
    if (connecting.value === node.id) { connecting.value = ''; return }
    if (graph.value.edges.some(edge => edge.source === connecting.value && edge.target === node.id))
      return ElMessage.warning('这两个节点已有同向管段')
    graph.value.edges.push(createStorageNetworkEdge(connecting.value, node.id, graph.value.edges.length))
    selection.value = [graph.value.edges.at(-1).id]; connecting.value = ''; return
  }
  if (mode.value === 'pan') return canvasDown(event)
  if (event.shiftKey) selection.value = selection.value.includes(node.id)
    ? selection.value.filter(id => id !== node.id) : [...selection.value, node.id]
  else if (!selection.value.includes(node.id)) selection.value = [node.id]
  const start = point(event)
  gesture = { type: 'nodes', start, originals: graph.value.nodes.filter(item => selection.value.includes(item.id))
    .map(item => ({ id: item.id, x: item.x, y: item.y })) }
  svg.value.setPointerCapture(event.pointerId)
}

function canvasDown(event) {
  if (saving.value || event.button !== 0) return
  const start = point(event)
  connecting.value = ''
  if (mode.value === 'pan') gesture = { type: 'pan', clientX: event.clientX, clientY: event.clientY,
    layout: { ...graph.value.layout } }
  else { selection.value = []; gesture = { type: 'box', start }; selectBox.value = { x: start.x, y: start.y, width: 0, height: 0 } }
  svg.value.setPointerCapture(event.pointerId)
}

function move(event) {
  if (!gesture) return
  const current = point(event)
  if (gesture.type === 'nodes') gesture.originals.forEach(origin => {
    const node = graph.value.nodes.find(item => item.id === origin.id)
    node.x = Math.round(origin.x + current.x - gesture.start.x)
    node.y = Math.round(origin.y + current.y - gesture.start.y)
  })
  if (gesture.type === 'pan') {
    graph.value.layout.x = gesture.layout.x + event.clientX - gesture.clientX
    graph.value.layout.y = gesture.layout.y + event.clientY - gesture.clientY
  }
  if (gesture.type === 'box') selectBox.value = { x: Math.min(current.x, gesture.start.x),
    y: Math.min(current.y, gesture.start.y), width: Math.abs(current.x - gesture.start.x),
    height: Math.abs(current.y - gesture.start.y) }
}

function end() {
  if (gesture?.type === 'box' && selectBox.value) {
    const box = selectBox.value
    selection.value = graph.value.nodes.filter(node => node.x >= box.x && node.x <= box.x + box.width
      && node.y >= box.y && node.y <= box.y + box.height).map(node => node.id)
  }
  gesture = null; selectBox.value = null
}

function zoom(factor, event) {
  const rect = svg.value.getBoundingClientRect(), layout = graph.value.layout
  const x = event ? event.clientX - rect.left : rect.width / 2
  const y = event ? event.clientY - rect.top : rect.height / 2
  const next = Math.max(.25, Math.min(2.2, layout.zoom * factor)), scale = next / layout.zoom
  layout.x = x - (x - layout.x) * scale; layout.y = y - (y - layout.y) * scale; layout.zoom = next
}

function fit(update = true) {
  if (!svg.value || !graph.value.nodes.length) return
  const rect = svg.value.getBoundingClientRect(), nodes = graph.value.nodes
  const minX = Math.min(...nodes.map(node => node.x)) - 70, maxX = Math.max(...nodes.map(node => node.x)) + 70
  const minY = Math.min(...nodes.map(node => node.y)) - 70, maxY = Math.max(...nodes.map(node => node.y)) + 70
  const scale = Math.max(.25, Math.min(1.15, (rect.width - 40) / Math.max(1, maxX - minX),
    (rect.height - 40) / Math.max(1, maxY - minY)))
  graph.value.layout = { zoom: scale, x: (rect.width - (maxX + minX) * scale) / 2,
    y: (rect.height - (maxY + minY) * scale) / 2 }
  if (!update) saved.value = JSON.stringify(graph.value)
}

function removeSelection() {
  const removable = new Set(selection.value.filter(id => !graph.value.nodes.some(node => node.id === id && node.type === 'well')))
  graph.value.edges = graph.value.edges.filter(edge => !removable.has(edge.id)
    && !removable.has(edge.source) && !removable.has(edge.target))
  graph.value.nodes = graph.value.nodes.filter(node => !removable.has(node.id))
  selection.value = []; connecting.value = ''
}

function keydown(event) {
  if (['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(event.target.tagName)) return
  if (['Delete', 'Backspace'].includes(event.key)) { event.preventDefault(); removeSelection() }
  if (event.key === 'Escape') { connecting.value = ''; selection.value = []; mode.value = 'select' }
}

const beforeUnload = event => { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
watch(() => [projectId.value, gasReservoirId.value, storageId.value], () => load(), { immediate: true })
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => { requestVersion++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <section class="network-workspace" tabindex="0" aria-label="库级管网拓扑结构" @keydown="keydown">
    <header class="module-tabs"><div class="module-tab">地面管网-管网拓扑结构</div></header>
    <div class="network-toolbar">
      <strong>{{ storageName }}</strong><span class="status">{{ statusText }}</span><i />
      <button :class="{ active: mode === 'select' }" @click="mode = 'select'; connecting = ''">选择</button>
      <button :class="{ active: mode === 'connect' }" @click="mode = 'connect'; connecting = ''">连接管线</button>
      <button :class="{ active: mode === 'pan' }" @click="mode = 'pan'; connecting = ''">移动画布</button>
      <button @click="fit()">适应画布</button><button :disabled="!selection.length" @click="removeSelection">删除</button>
      <button :disabled="loading || saving" @click="load(true)">重新加载</button>
      <button class="primary" :disabled="loading || saving" @click="save">保存</button>
    </div>
    <div v-if="error" class="error-strip" role="alert">{{ error }} <button @click="load()">重试</button></div>
    <div class="network-body" :class="{ busy: loading || saving }">
      <aside class="palette">
        <h3>地面设施</h3>
        <p>库内井口由井目录自动生成</p>
        <button v-for="item in storageNetworkNodeTypes.filter(item => item.type !== 'well')" :key="item.type"
          :disabled="item.type === 'external' && graph.nodes.some(node => node.type === 'external')" @click="add(item.type)">
          <b :class="item.type">{{ item.symbol }}</b><span>{{ item.label }}</span>
        </button>
        <div class="palette-note">连接方向表示气体流向。<br />选择起点，再选择终点。<br /><br />滚轮缩放 · 拖动框选<br />Shift 多选 · Delete 删除</div>
      </aside>
      <div class="canvas-wrap">
        <div class="canvas-hint">{{ mode === 'connect' ? connecting ? '请选择下游节点 · Esc取消' : '请选择上游节点' : mode === 'pan' ? '拖动画布调整视野' : '拖动节点调整布局，点击管段编辑参数' }}</div>
        <svg ref="svg" class="network-canvas" :class="mode" @pointerdown="canvasDown" @pointermove="move"
          @pointerup="end" @pointercancel="end" @wheel.prevent="zoom($event.deltaY < 0 ? 1.1 : 1 / 1.1, $event)">
          <defs>
            <pattern id="storage-network-grid" width="20" height="20" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" fill="#d7dfe8" /></pattern>
            <marker id="storage-network-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z" fill="#6388a8" /></marker>
          </defs>
          <rect width="100%" height="100%" fill="url(#storage-network-grid)" />
          <g :transform="transform">
            <g v-for="edge in segments" :key="edge.id" class="edge" :class="{ selected: selection.includes(edge.id) }" @pointerdown.stop="selection = [edge.id]">
              <path class="edge-hit" :d="`M ${edge.from.x} ${edge.from.y} L ${edge.to.x} ${edge.to.y}`" />
              <path class="edge-line" marker-end="url(#storage-network-arrow)" :d="`M ${edge.from.x} ${edge.from.y} L ${edge.to.x} ${edge.to.y}`" />
              <text :x="(edge.from.x + edge.to.x) / 2" :y="(edge.from.y + edge.to.y) / 2 - 9" text-anchor="middle">{{ edge.name }}</text>
              <text class="edge-detail" :x="(edge.from.x + edge.to.x) / 2" :y="(edge.from.y + edge.to.y) / 2 + 14" text-anchor="middle">DN{{ edge.parameters.diameterMm }} · {{ edge.parameters.maxFlow10k }}×10⁴m³/d</text>
            </g>
            <g v-for="node in graph.nodes" :key="node.id" class="graph-node" :class="[node.type, { selected: selection.includes(node.id), connecting: connecting === node.id }]"
              :transform="`translate(${node.x} ${node.y})`" @pointerdown="nodeDown($event, node)">
              <rect x="-31" y="-27" width="62" height="54" :rx="['well', 'junction'].includes(node.type) ? 27 : 8" />
              <text class="symbol" text-anchor="middle" y="7">{{ descriptor(node.type)?.symbol }}</text>
              <text class="node-name" text-anchor="middle" y="47">{{ node.name }}</text>
            </g>
            <rect v-if="selectBox" v-bind="selectBox" fill="#409eff22" stroke="#409eff" stroke-dasharray="4 3" />
          </g>
        </svg>
        <div class="zoom-tools"><button @click="zoom(1 / 1.2)">−</button><span>{{ Math.round(graph.layout.zoom * 100) }}%</span><button @click="zoom(1.2)">＋</button></div>
      </div>
      <aside class="properties">
        <h3>{{ selectedNode ? '节点属性' : selectedEdge ? '管段属性' : '属性设置' }}</h3>
        <template v-if="selected">
          <label>名称<input v-model="selected.name" :readonly="selectedNode?.type === 'well'" maxlength="100" /></label>
        </template>
        <template v-if="selectedNode">
          <label>类型<span class="readonly">{{ descriptor(selectedNode.type)?.label }}</span></label>
          <label>节点高程（m）<input v-model.number="selectedNode.parameters.elevationM" type="number" step="0.1" /></label>
          <template v-if="needsLimits(selectedNode)">
            <label>处理能力（10⁴m³/d）<input v-model.number="selectedNode.parameters.capacity10k" type="number" min="0.0001" step="1" /></label>
            <label>压力上限（MPa）<input v-model.number="selectedNode.parameters.maxPressureMpa" type="number" min="0.0001" step="0.1" /></label>
          </template>
          <p v-if="selectedNode.type === 'well'" class="note">井节点来自当前储气库目录，不可删除或改名。</p>
        </template>
        <template v-else-if="selectedEdge">
          <label>起点<span class="readonly">{{ graph.nodes.find(node => node.id === selectedEdge.source)?.name }}</span></label>
          <label>终点<span class="readonly">{{ graph.nodes.find(node => node.id === selectedEdge.target)?.name }}</span></label>
          <label>管长（m）<input v-model.number="selectedEdge.parameters.lengthM" type="number" min="0.0001" step="1" /></label>
          <label>内径（mm）<input v-model.number="selectedEdge.parameters.diameterMm" type="number" min="0.0001" step="1" /></label>
          <label>粗糙度（mm）<input v-model.number="selectedEdge.parameters.roughnessMm" type="number" min="0" step="0.001" /></label>
          <label>输量上限（10⁴m³/d）<input v-model.number="selectedEdge.parameters.maxFlow10k" type="number" min="0.0001" step="1" /></label>
        </template>
        <p v-else class="empty-properties">选择一个节点或管段查看工程参数。</p>
      </aside>
    </div>
    <footer class="network-status">
      <span>{{ graph.nodes.length }} 个节点 · {{ graph.edges.length }} 条管段 · {{ wells.length }} 口井</span>
      <span v-if="validation.errors.length" class="invalid">{{ validation.errors[0] }}</span>
      <span v-else-if="validation.warnings.length" class="warning">{{ validation.warnings[0] }}</span>
      <span v-else class="valid">连接和工程参数检查通过</span>
    </footer>
  </section>
</template>

<style lang="scss" scoped>
.network-workspace { width: 100%; height: 100%; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; color: #252525; font: 13px/1.45 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.module-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.module-tab { height: 34px; line-height: 34px; padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; color: #202020; font-weight: 600; }
.network-toolbar { min-height: 36px; display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-bottom: 1px solid #d8d8d8; background: #f5f5f5; box-sizing: border-box; }
.network-toolbar strong { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.network-toolbar .status { color: #777; font-size: 12px; }.network-toolbar i { flex: 1; }
button { height: 26px; padding: 0 10px; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; cursor: pointer; }
button:hover:not(:disabled), button.active { border-color: #c3a900; background: #fff8d8; } button:disabled { color: #aaa; cursor: not-allowed; }
button.primary { border-color: #d5b900; background: #f4d000; color: #222; }.error-strip { padding: 7px 12px; border-bottom: 1px solid #e8c7c0; background: #fff4f1; color: #a6412e; }
.network-body { flex: 1; min-height: 0; display: flex; overflow: hidden; }.network-body.busy { pointer-events: none; opacity: .65; }
.palette, .properties { width: 220px; min-width: 220px; padding: 10px; overflow: auto; box-sizing: border-box; background: #fafafa; }
.palette { border-right: 1px solid #ddd; }.properties { border-left: 1px solid #ddd; }.palette h3, .properties h3 { margin: 0 -10px 10px; padding: 0 10px 9px; border-bottom: 1px solid #ddd; font-size: 13px; font-weight: 600; }
.palette > p { margin: 0 0 8px; color: #777; font-size: 11px; }.palette > button { width: 100%; height: 36px; margin-bottom: 6px; display: flex; align-items: center; gap: 9px; text-align: left; }
.palette b { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid #758aa0; border-radius: 50%; background: #eef4fa; color: #35546d; font-size: 11px; }.palette b.gathering, .palette b.external { border-radius: 4px; background: #fff7d5; color: #7d6500; }
.palette-note { margin-top: 12px; color: #777; font-size: 11px; line-height: 1.75; }.canvas-wrap { position: relative; flex: 1; min-width: 0; overflow: hidden; background: #fff; }
.canvas-hint { position: absolute; z-index: 2; top: 8px; left: 50%; transform: translateX(-50%); padding: 3px 10px; border: 1px solid #ddd; border-radius: 12px; background: rgba(255,255,255,.92); color: #777; font-size: 11px; pointer-events: none; }
.network-canvas { width: 100%; height: 100%; min-height: 420px; user-select: none; cursor: default; }.network-canvas.pan { cursor: grab; }.network-canvas.connect { cursor: crosshair; }
.edge-line { fill: none; stroke: #6388a8; stroke-width: 2; }.edge-hit { fill: none; stroke: transparent; stroke-width: 16; cursor: pointer; }.edge.selected .edge-line { stroke: #e0b900; stroke-width: 4; }.edge text { fill: #44505c; font-size: 11px; pointer-events: none; paint-order: stroke; stroke: #fff; stroke-width: 4px; }.edge .edge-detail { fill: #7b8792; font-size: 10px; }
.graph-node { cursor: move; }.graph-node rect { fill: #fff; stroke: #627d95; stroke-width: 2; }.graph-node.well rect { fill: #eaf4ff; }.graph-node.gathering rect, .graph-node.external rect { fill: #fff7d5; stroke: #a48600; }.graph-node.compressor rect { fill: #fff0e7; stroke: #b6602f; }.graph-node.valve rect, .graph-node.metering rect { fill: #edf9f3; stroke: #43846a; }.graph-node.selected rect, .graph-node.connecting rect { stroke: #f4c900; stroke-width: 4; }.graph-node .symbol { fill: #263e52; font-size: 14px; font-weight: 700; pointer-events: none; }.graph-node .node-name { fill: #313b45; font-size: 11px; pointer-events: none; paint-order: stroke; stroke: #fff; stroke-width: 4px; }
.zoom-tools { position: absolute; right: 10px; bottom: 10px; display: flex; align-items: center; border: 1px solid #bbb; background: #fff; }.zoom-tools button { width: 28px; border: 0; border-radius: 0; }.zoom-tools span { width: 52px; text-align: center; font-size: 11px; }
.properties label { display: block; margin-bottom: 9px; color: #555; font-size: 12px; }.properties input, .properties .readonly { width: 100%; height: 27px; margin-top: 3px; padding: 0 7px; display: flex; align-items: center; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }.properties input[readonly], .properties .readonly { border-color: #ddd; background: #f3f3f3; }.properties .note, .empty-properties { color: #777; font-size: 11px; line-height: 1.7; }
.network-status { min-height: 30px; padding: 5px 10px; display: flex; align-items: center; gap: 18px; border-top: 1px solid #ddd; box-sizing: border-box; background: #f5f5f5; font-size: 11px; }.network-status .invalid { color: #b04431; }.network-status .warning { color: #9a6a00; }.network-status .valid { color: #347451; }
@media (max-width: 900px) { .palette { width: 160px; min-width: 160px; }.properties { width: 190px; min-width: 190px; } }
</style>
