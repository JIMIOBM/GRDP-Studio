<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { softwareIntegrationApi } from '@/api/softwareIntegration'

const props = defineProps({
  runId: { type: [Number, String], default: null },
  grid: { type: Object, default: null },
  fields: { type: Array, default: () => [] },
  artifacts: { type: Array, default: () => [] },
  wellCompletions: { type: Array, default: () => [] }
})

const MAX_RANGE_BYTES = 4 * 1024 * 1024
const MAX_GEOMETRY_BYTES = 64 * 1024 * 1024
const MAX_CELL_COUNT = 2_000_000

const canvasHost = ref(null)
const loading = ref(false)
const error = ref('')
const geometryReady = ref(false)
const fieldLoading = ref(false)
const fieldError = ref('')
const selectedFieldKey = ref('')
const layer = ref(1)
const selectedCell = ref(null)
const editValue = ref(null)
const draftOverrides = ref({})
const showAllLayers = ref(true)
const geometrySummary = ref(null)
const fieldValues = ref([])

let scene
let camera
let renderer
let controls
let resizeObserver
let animationFrame
let raycaster
let pointer
let gridGroup
let selectedMarker
let initialCameraState
let cells = []
let coordinateValues = null
let zcornValues = null
let actnumValues = null
let activeOrdinalByIndex = null
let loadGeneration = 0

const totalCells = computed(() => {
  const { nx, ny, nz } = props.grid || {}
  return Number.isInteger(nx) && Number.isInteger(ny) && Number.isInteger(nz) ? nx * ny * nz : 0
})
const activeCellCount = computed(() => Number.isInteger(props.grid?.activeCells) ? props.grid.activeCells : null)
const geometryFields = computed(() => ({
  coord: props.fields.find(field => field.file?.toUpperCase().endsWith('.EGRID') && field.keyword === 'COORD' && field.dataType === 'REAL'),
  zcorn: props.fields.find(field => field.file?.toUpperCase().endsWith('.EGRID') && field.keyword === 'ZCORN' && field.dataType === 'REAL'),
  actnum: props.fields.find(field => field.file?.toUpperCase().endsWith('.EGRID') && field.keyword === 'ACTNUM' && field.dataType === 'INTE' && field.count === totalCells.value)
}))
const gridFields = computed(() => props.fields.filter(field =>
  ['REAL', 'DOUB', 'INTE', 'LOGI'].includes(field.dataType) &&
  field.keyword !== 'ACTNUM' &&
  (field.count === totalCells.value || (activeCellCount.value !== null && field.count === activeCellCount.value && geometryFields.value.actnum))
))
const fieldKey = field => `${field.file}:${field.keyword}:${field.dataType}:${field.timeStep ?? 'static'}:${field.segments?.[0]?.offset ?? 0}`
const selectedField = computed(() => gridFields.value.find(field => fieldKey(field) === selectedFieldKey.value) || gridFields.value[0] || null)
const selectedFieldLabel = computed(() => selectedField.value
  ? `${selectedField.value.keyword} · ${selectedField.value.dataType} · ${selectedField.value.file} · 时间步 ${selectedField.value.timeStep ?? '静态'}`
  : '-')
const currentLayerCells = computed(() => cells.filter(cell => cell.k === layer.value))
const draftEntries = computed(() => Object.entries(draftOverrides.value)
  .map(([key, value]) => {
    const [field, cellIndex] = key.split('|')
    const cell = cells.find(item => item.index === Number(cellIndex))
    if (!cell || field !== selectedFieldKey.value) return null
    return { ...cell, originalValue: cell.value, editedValue: value }
  })
  .filter(Boolean))
const completions = computed(() => props.wellCompletions.flatMap(item => {
  if (!item || typeof item !== 'object' || typeof item.well !== 'string') return []
  const i = Number(item.i)
  const j = Number(item.j)
  const k1 = Number(item.k1)
  const k2 = Number(item.k2)
  if (![i, j, k1, k2].every(Number.isInteger) || i < 1 || j < 1 || k1 < 1 || k2 < k1 ||
    i > (props.grid?.nx || 0) || j > (props.grid?.ny || 0) || k1 > (props.grid?.nz || 0)) return []
  return [{ ...item, i, j, k1, k2: Math.min(k2, props.grid.nz) }]
}))

const fieldArtifact = field => props.artifacts.find(artifact => artifact?.name === `eclipse-output-${field.file}` &&
  artifact?.contentType === 'application/octet-stream' && Number.isSafeInteger(artifact.id) && artifact.id > 0)

const decode = (bytes, field) => {
  const values = []
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const littleEndian = field.byteOrder === 'LITTLE'
  for (let offset = 0; offset + field.elementSize <= bytes.byteLength; offset += field.elementSize) {
    if (field.dataType === 'REAL') values.push(view.getFloat32(offset, littleEndian))
    else if (field.dataType === 'DOUB') values.push(view.getFloat64(offset, littleEndian))
    else values.push(view.getInt32(offset, littleEndian))
  }
  return values
}

const readValues = async field => {
  const artifact = fieldArtifact(field)
  if (!artifact) throw new Error(`字段 ${field.keyword} 对应的二进制 Artifact 不可用。`)
  if (!Number.isSafeInteger(field.count) || field.count < 0 || field.count * field.elementSize > MAX_GEOMETRY_BYTES) {
    throw new Error(`字段 ${field.keyword} 超出页面安全读取上限。`)
  }
  const values = []
  for (const segment of field.segments || []) {
    let segmentOffset = 0
    const segmentValues = Math.floor(segment.length / field.elementSize)
    while (segmentOffset < segmentValues) {
      const chunkValues = Math.min(segmentValues - segmentOffset, Math.max(1, Math.floor(MAX_RANGE_BYTES / field.elementSize)))
      const offset = segment.offset + segmentOffset * field.elementSize
      const length = chunkValues * field.elementSize
      const payload = await softwareIntegrationApi.downloadArtifactRange(props.runId, artifact.id, offset, length)
      const blob = payload instanceof Blob ? payload : new Blob([payload])
      values.push(...decode(new Uint8Array(await blob.arrayBuffer()), field))
      segmentOffset += chunkValues
    }
  }
  if (values.length !== field.count) throw new Error(`字段 ${field.keyword} 返回长度与索引不一致。`)
  return values
}

const disposeObject = object => object?.traverse(child => {
  child.geometry?.dispose()
  if (Array.isArray(child.material)) child.material.forEach(material => material.dispose())
  else child.material?.dispose()
})

const removeRenderedGrid = () => {
  if (gridGroup && scene) {
    scene.remove(gridGroup)
    disposeObject(gridGroup)
  }
  gridGroup = null
  selectedMarker = null
}

const clearGrid = () => {
  removeRenderedGrid()
  cells = []
}

const resetCamera = () => {
  if (!initialCameraState || !camera || !controls) return
  camera.position.copy(initialCameraState.position)
  controls.target.copy(initialCameraState.target)
  controls.update()
}

const normalizePoint = (x, y, z, center) => new THREE.Vector3(x - center.x, z - center.z, -(y - center.y))

const pillarPoint = (pillarIndex, z, coord, center) => {
  const offset = pillarIndex * 6
  const topX = coord[offset]
  const topY = coord[offset + 1]
  const topZ = coord[offset + 2]
  const bottomX = coord[offset + 3]
  const bottomY = coord[offset + 4]
  const bottomZ = coord[offset + 5]
  const denominator = bottomZ - topZ
  const ratio = Math.abs(denominator) > 1e-9 ? (z - topZ) / denominator : 0
  return normalizePoint(topX + (bottomX - topX) * ratio, topY + (bottomY - topY) * ratio, z, center)
}

const valueForCell = cell => {
  const key = `${selectedFieldKey.value}|${cell.index}`
  return Object.hasOwn(draftOverrides.value, key) ? draftOverrides.value[key] : cell.value
}

const valueColor = (value, min, max, active) => {
  if (!active) return new THREE.Color(0xcbd5e1)
  if (typeof value !== 'number' || !Number.isFinite(value)) return new THREE.Color(0x94a3b8)
  const ratio = max > min ? Math.max(0, Math.min(1, (value - min) / (max - min))) : 0.5
  return new THREE.Color().setHSL(0.62 - ratio * 0.62, 0.78, 0.5)
}

const addAxes = (group, span) => {
  const axes = new THREE.AxesHelper(Math.max(span * 0.28, 1))
  group.add(axes)
  const grid = new THREE.GridHelper(Math.max(span * 1.25, 10), 10, 0x94a3b8, 0xe2e8f0)
  grid.position.y = -span * 0.5
  group.add(grid)
}

const buildCells = () => {
  const grid = props.grid
  const coord = coordinateValues
  const zcorn = zcornValues
  const actnum = actnumValues
  if (!grid || !coord || !zcorn || !actnum) throw new Error('EGRID 几何字段不完整，无法生成真实三维网格。')
  if (totalCells.value <= 0 || totalCells.value > MAX_CELL_COUNT) throw new Error('网格规模超出页面三维渲染安全上限。')
  const nx = grid.nx
  const ny = grid.ny
  const nz = grid.nz
  const rawPoints = []
  for (let k = 0; k < nz; k++) {
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const base = (k * nx * ny + j * nx + i)
        if (actnum[base] <= 0) continue
        const pillar00 = j * (nx + 1) + i
        const pillar10 = pillar00 + 1
        const pillar01 = (j + 1) * (nx + 1) + i
        const pillar11 = pillar01 + 1
        const zbase = base * 8
        const pointValues = [
          [pillar00, zcorn[zbase]], [pillar10, zcorn[zbase + 1]], [pillar01, zcorn[zbase + 2]], [pillar11, zcorn[zbase + 3]],
          [pillar00, zcorn[zbase + 4]], [pillar10, zcorn[zbase + 5]], [pillar01, zcorn[zbase + 6]], [pillar11, zcorn[zbase + 7]]
        ]
        for (const [pillar, z] of pointValues) {
          const offset = pillar * 6
          rawPoints.push(new THREE.Vector3(
            coord[offset] + (coord[offset + 3] - coord[offset]) * 0.5,
            coord[offset + 1] + (coord[offset + 4] - coord[offset + 1]) * 0.5,
            z
          ))
        }
      }
    }
  }
  if (!rawPoints.length) throw new Error('EGRID ACTNUM 没有活动网格单元。')
  const rawBounds = new THREE.Box3().setFromPoints(rawPoints)
  const center = rawBounds.getCenter(new THREE.Vector3())
  const normalized = []
  let rawCursor = 0
  for (let k = 0; k < nz; k++) {
    for (let j = 0; j < ny; j++) {
      for (let i = 0; i < nx; i++) {
        const base = k * nx * ny + j * nx + i
        if (actnum[base] <= 0) continue
        const zbase = base * 8
        const pillar00 = j * (nx + 1) + i
        const pillar10 = pillar00 + 1
        const pillar01 = (j + 1) * (nx + 1) + i
        const pillar11 = pillar01 + 1
        const points = [
          pillarPoint(pillar00, zcorn[zbase], coord, center), pillarPoint(pillar10, zcorn[zbase + 1], coord, center),
          pillarPoint(pillar01, zcorn[zbase + 2], coord, center), pillarPoint(pillar11, zcorn[zbase + 3], coord, center),
          pillarPoint(pillar00, zcorn[zbase + 4], coord, center), pillarPoint(pillar10, zcorn[zbase + 5], coord, center),
          pillarPoint(pillar01, zcorn[zbase + 6], coord, center), pillarPoint(pillar11, zcorn[zbase + 7], coord, center)
        ]
        const centerPoint = points.reduce((sum, point) => sum.add(point), new THREE.Vector3()).multiplyScalar(1 / 8)
        normalized.push({ index: base, i: i + 1, j: j + 1, k: k + 1, points, center: centerPoint, value: null, rawCursor: rawCursor++ })
      }
    }
  }
  return { cells: normalized, center, span: Math.max(...rawBounds.getSize(new THREE.Vector3()).toArray(), 1) }
}

const addWellMarkers = group => {
  const markerGroup = new THREE.Group()
  const grouped = new Map()
  for (const completion of completions.value) {
    const points = []
    for (let k = completion.k1; k <= completion.k2; k++) {
      const cell = cells.find(item => item.i === completion.i && item.j === completion.j && item.k === k)
      if (!cell) continue
      points.push(cell.center)
      const marker = new THREE.Mesh(
        new THREE.SphereGeometry(Math.max((geometrySummary.value?.span || 1) / 120, 0.12), 12, 8),
        new THREE.MeshBasicMaterial({ color: 0xf97316 })
      )
      marker.position.copy(cell.center)
      markerGroup.add(marker)
    }
    if (points.length > 1) {
      const key = completion.well
      const previous = grouped.get(key) || []
      grouped.set(key, [...previous, ...points])
    }
  }
  for (const [well, points] of grouped) {
    const unique = [...new Map(points.map(point => [`${point.x}|${point.y}|${point.z}`, point])).values()]
    if (unique.length < 2) continue
    const line = new THREE.Line(
      new THREE.BufferGeometry().setFromPoints(unique),
      new THREE.LineBasicMaterial({ color: 0xf97316 })
    )
    line.userData.well = well
    markerGroup.add(line)
  }
  group.add(markerGroup)
}

const renderGrid = () => {
  if (!scene || !geometryReady.value || !cells.length) return
  removeRenderedGrid()
  cells.forEach(cell => { cell.value = fieldValueByIndex(cell.index) })
  const values = cells.map(cell => valueForCell(cell)).filter(value => typeof value === 'number' && Number.isFinite(value))
  const min = values.length ? Math.min(...values) : 0
  const max = values.length ? Math.max(...values) : 1
  const positions = []
  const colors = []
  const faceOrder = [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]]
  const renderCells = showAllLayers.value ? cells : currentLayerCells.value
  const renderIndexByCell = new Map(renderCells.map((cell, index) => [cell.index, index]))
  for (const cell of renderCells) {
    const color = valueColor(valueForCell(cell), min, max, cell.k === layer.value || showAllLayers.value)
    for (const face of faceOrder) {
      for (const index of [face[0], face[1], face[2], face[0], face[2], face[3]]) {
        const point = cell.points[index]
        positions.push(point.x, point.y, point.z)
        colors.push(color.r, color.g, color.b)
      }
    }
  }
  const geometry = new THREE.BufferGeometry()
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
  geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3))
  const mesh = new THREE.Mesh(geometry, new THREE.MeshBasicMaterial({ vertexColors: true, transparent: true, opacity: showAllLayers.value ? 0.62 : 0.8, side: THREE.DoubleSide }))
  mesh.userData.renderCells = renderCells
  gridGroup = new THREE.Group()
  gridGroup.add(mesh)
  const edgePositions = []
  const edgeOrder = [[0, 1], [1, 3], [3, 2], [2, 0], [4, 5], [5, 7], [7, 6], [6, 4], [0, 4], [1, 5], [2, 6], [3, 7]]
  for (const cell of renderCells) for (const [from, to] of edgeOrder) edgePositions.push(...cell.points[from].toArray(), ...cell.points[to].toArray())
  const edgeGeometry = new THREE.BufferGeometry()
  edgeGeometry.setAttribute('position', new THREE.Float32BufferAttribute(edgePositions, 3))
  gridGroup.add(new THREE.LineSegments(edgeGeometry, new THREE.LineBasicMaterial({ color: 0x64748b, transparent: true, opacity: 0.32 })))
  addWellMarkers(gridGroup)
  scene.add(gridGroup)
  if (selectedCell.value && renderIndexByCell.has(selectedCell.value.index)) selectCell(selectedCell.value)
  else selectedMarker = null
}

const fieldValueByIndex = index => {
  if (!selectedField.value || !fieldValues.value.length) return null
  if (selectedField.value.count === totalCells.value) return fieldValues.value[index] ?? null
  const activeCursor = activeOrdinalByIndex?.[index]
  return activeCursor >= 0 ? fieldValues.value[activeCursor] ?? null : null
}

const selectCell = cell => {
  selectedCell.value = cell
  editValue.value = valueForCell(cell)
  if (!gridGroup) return
  if (selectedMarker) {
    gridGroup.remove(selectedMarker)
    disposeObject(selectedMarker)
  }
  const markerGeometry = new THREE.BufferGeometry().setFromPoints([...cell.points, cell.points[0]])
  selectedMarker = new THREE.Line(markerGeometry, new THREE.LineBasicMaterial({ color: 0x2563eb, linewidth: 2 }))
  gridGroup.add(selectedMarker)
}

const onCanvasClick = event => {
  if (!renderer || !camera || !gridGroup) return
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
  raycaster.setFromCamera(pointer, camera)
  const mesh = gridGroup.children.find(child => child.isMesh && child.userData.renderCells)
  const hit = mesh ? raycaster.intersectObject(mesh, false)[0] : null
  if (!hit || hit.faceIndex == null) return
  const index = Math.floor(hit.faceIndex / 12)
  const cell = mesh.userData.renderCells[index]
  if (cell) selectCell(cell)
}

const initializeScene = () => {
  const host = canvasHost.value
  if (!host) return
  try {
    scene = new THREE.Scene()
    scene.background = new THREE.Color(0xf8fafc)
    camera = new THREE.PerspectiveCamera(45, Math.max(host.clientWidth, 1) / Math.max(host.clientHeight, 1), 0.1, 1000000)
    renderer = new THREE.WebGLRenderer({ antialias: true })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.setSize(host.clientWidth, host.clientHeight)
    renderer.domElement.addEventListener('click', onCanvasClick)
    host.appendChild(renderer.domElement)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    scene.add(new THREE.HemisphereLight(0xffffff, 0x5f6874, 1.7))
    const directionalLight = new THREE.DirectionalLight(0xffffff, 1.8)
    directionalLight.position.set(1, 1, 1)
    scene.add(directionalLight)
    raycaster = new THREE.Raycaster()
    pointer = new THREE.Vector2()
    resizeObserver = new ResizeObserver(() => {
      if (!canvasHost.value || !camera || !renderer) return
      const width = Math.max(canvasHost.value.clientWidth, 1)
      const height = Math.max(canvasHost.value.clientHeight, 1)
      camera.aspect = width / height
      camera.updateProjectionMatrix()
      renderer.setSize(width, height)
    })
    resizeObserver.observe(host)
    const animate = () => {
      animationFrame = requestAnimationFrame(animate)
      controls.update()
      renderer.render(scene, camera)
    }
    animate()
  } catch (exception) {
    error.value = exception?.message || '当前浏览器不支持三维网格渲染。'
  }
}

const loadGeometry = async () => {
  const generation = ++loadGeneration
  geometryReady.value = false
  loading.value = false
  error.value = ''
  geometrySummary.value = null
    coordinateValues = null
    zcornValues = null
    actnumValues = null
  activeOrdinalByIndex = null
  clearGrid()
  if (!props.runId || !props.grid || !geometryFields.value.coord || !geometryFields.value.zcorn || !geometryFields.value.actnum) {
    error.value = '当前运行缺少 EGRID 的 COORD、ZCORN 或 ACTNUM 索引，无法定位真实三维几何。'
    return
  }
  loading.value = true
  try {
    const [coord, zcorn, actnum] = await Promise.all([
      readValues(geometryFields.value.coord),
      readValues(geometryFields.value.zcorn),
      readValues(geometryFields.value.actnum)
    ])
    if (generation !== loadGeneration) return
    coordinateValues = coord
    zcornValues = zcorn
    actnumValues = actnum
    activeOrdinalByIndex = new Int32Array(totalCells.value)
    activeOrdinalByIndex.fill(-1)
    let activeCursor = 0
    for (let index = 0; index < actnum.length; index++) {
      if (actnum[index] > 0) activeOrdinalByIndex[index] = activeCursor++
    }
    const result = buildCells()
    cells = result.cells
    geometrySummary.value = { activeCells: cells.length, span: result.span }
    geometryReady.value = true
    initialCameraState = {
      position: new THREE.Vector3(result.span * 1.45, result.span * 1.1, result.span * 1.45),
      target: new THREE.Vector3(0, 0, 0)
    }
    resetCamera()
    await loadSelectedField()
  } catch (exception) {
    if (generation === loadGeneration) error.value = exception?.message || 'EGRID 三维几何读取失败。'
  } finally {
    if (generation === loadGeneration) loading.value = false
  }
}

const loadSelectedField = async () => {
  const field = selectedField.value
  if (!geometryReady.value || !field) {
    fieldValues.value = []
    return
  }
  fieldLoading.value = true
  fieldError.value = ''
  try {
    fieldValues.value = await readValues(field)
    cells.forEach(cell => { cell.value = fieldValueByIndex(cell.index) })
    renderGrid()
  } catch (exception) {
    fieldValues.value = []
    fieldError.value = exception?.message || '场值读取失败。'
  } finally {
    fieldLoading.value = false
  }
}

const applyEdit = () => {
  if (!selectedCell.value || !selectedField.value || !Number.isFinite(Number(editValue.value))) return
  const value = Number(editValue.value)
  if (!Number.isFinite(value) || Math.abs(value) > 1e15) {
    ElMessage.warning('请输入有限的数值。')
    return
  }
  draftOverrides.value = { ...draftOverrides.value, [`${selectedFieldKey.value}|${selectedCell.value.index}`]: value }
  selectedCell.value.value = selectedCell.value.value
  renderGrid()
  ElMessage.success(`已生成 I=${selectedCell.value.i}, J=${selectedCell.value.j}, K=${selectedCell.value.k} 的派生编辑值。`)
}

const clearEdits = () => {
  draftOverrides.value = {}
  renderGrid()
}

const exportDraft = () => {
  if (!draftEntries.value.length || !selectedField.value) return
  const payload = {
    schemaVersion: 'eclipse-field-edit-draft/1',
    runId: props.runId,
    source: { file: selectedField.value.file, keyword: selectedField.value.keyword, dataType: selectedField.value.dataType, timeStep: selectedField.value.timeStep ?? null },
    semantics: 'derived-postprocess-only',
    note: '该草案只用于受控后处理展示和复核，不覆盖原始 Artifact，也不会自动作为下一次 ECLIPSE 输入。',
    edits: draftEntries.value.map(item => ({ i: item.i, j: item.j, k: item.k, cellIndex: item.index, originalValue: item.originalValue, editedValue: item.editedValue }))
  }
  const url = URL.createObjectURL(new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `eclipse-${selectedField.value.keyword}-field-edit-draft.json`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

watch(gridFields, fields => {
  const pressure = fields.slice().reverse().find(field => field.keyword === 'PRESSURE')
  if (!fields.some(field => fieldKey(field) === selectedFieldKey.value)) selectedFieldKey.value = pressure ? fieldKey(pressure) : fields[0] ? fieldKey(fields[0]) : ''
}, { immediate: true })
watch(() => [props.runId, props.grid?.fileName, props.grid?.nx, props.grid?.ny, props.grid?.nz], loadGeometry, { immediate: true })
watch(selectedFieldKey, loadSelectedField)
watch(layer, renderGrid)
watch(showAllLayers, renderGrid)
watch(() => props.wellCompletions, renderGrid, { deep: true })

onMounted(() => {
  initializeScene()
  if (geometryReady.value) {
    resetCamera()
    renderGrid()
  }
})
onBeforeUnmount(() => {
  loadGeneration++
  cancelAnimationFrame(animationFrame)
  resizeObserver?.disconnect()
  renderer?.domElement.removeEventListener('click', onCanvasClick)
  controls?.dispose()
  clearGrid()
  renderer?.dispose()
  renderer?.domElement?.remove()
})
</script>

<template>
  <section v-if="grid" class="eclipse-grid-3d-panel" aria-label="ECLIPSE 三维网格与井定位" :data-render-ready="geometryReady && !fieldLoading ? 'true' : 'false'">
    <div class="panel-heading">
      <div><span class="kicker">ECLIPSE EGRID</span><h3>三维几何、井轨迹定位与场值编辑</h3><p>COORD/ZCORN/ACTNUM 来自本次真实 EGRID Artifact；点击网格单元可生成受控的派生场值编辑草案。</p></div>
      <span>{{ grid.nx }} × {{ grid.ny }} × {{ grid.nz }}</span>
    </div>
    <div class="grid-3d-controls">
      <el-select v-model="selectedFieldKey" aria-label="ECLIPSE 三维场值字段" placeholder="选择三维场值字段" :disabled="!gridFields.length">
        <el-option v-for="field in gridFields" :key="fieldKey(field)" :value="fieldKey(field)" :label="`${field.keyword} · ${field.file} · 时间步 ${field.timeStep ?? '静态'}`" />
      </el-select>
      <el-input-number v-model="layer" :min="1" :max="grid.nz" :step="1" controls-position="right" aria-label="ECLIPSE 三维当前层" />
      <el-checkbox v-model="showAllLayers">显示全部层</el-checkbox>
      <el-button @click="resetCamera">重置视角</el-button>
      <el-tag v-if="geometryReady" type="success">真实几何已加载 {{ geometrySummary?.activeCells ?? 0 }} 个活动单元</el-tag>
      <el-tag v-else-if="loading" type="info">读取 EGRID 几何中</el-tag>
    </div>
    <el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <el-alert v-else-if="fieldError" type="warning" :closable="false" :title="fieldError" />
    <div class="grid-3d-layout">
      <div ref="canvasHost" class="grid-3d-canvas" v-loading="loading || fieldLoading">
        <div v-if="!loading && !error && !geometryReady" class="scene-empty">暂无可绘制的 EGRID 三维几何</div>
        <span class="axis-caption">X：红　Y：蓝　Z：绿　橙色：COMPDAT 完井定位</span>
      </div>
      <aside class="grid-3d-inspector">
        <div class="inspector-block"><strong>当前场值</strong><span>{{ selectedFieldLabel }}</span><small>蓝 → 红表示当前场值由低到高；编辑只作用于派生草案。</small></div>
        <div v-if="selectedCell" class="inspector-block selected-cell">
          <strong>选中网格单元</strong>
          <dl><div><dt>I</dt><dd>{{ selectedCell.i }}</dd></div><div><dt>J</dt><dd>{{ selectedCell.j }}</dd></div><div><dt>K</dt><dd>{{ selectedCell.k }}</dd></div><div><dt>原值</dt><dd>{{ selectedCell.value ?? '-' }}</dd></div><div><dt>当前值</dt><dd>{{ valueForCell(selectedCell) ?? '-' }}</dd></div></dl>
          <el-input-number v-model="editValue" controls-position="right" :disabled="!selectedField" aria-label="编辑选中网格场值" />
          <div class="inspector-actions"><el-button type="primary" size="small" :disabled="!selectedField" @click="applyEdit">加入编辑草案</el-button><el-button size="small" @click="clearEdits">清空草案</el-button></div>
        </div>
        <div class="inspector-block"><strong>编辑草案</strong><span>{{ draftEntries.length }} 个当前字段单元</span><small>不会修改原始运行结果；可导出给后续审批或输入转换流程。</small><el-button v-if="draftEntries.length" size="small" @click="exportDraft">导出 JSON</el-button></div>
        <div class="inspector-block"><strong>井轨迹定位</strong><span>{{ completions.length }} 条 COMPDAT/COMPDATM 完井记录</span><small>当前使用真实 I/J/K 完井坐标定位；没有 COMPDAT 时不会补画虚构轨迹。</small></div>
      </aside>
    </div>
    <div v-if="completions.length" class="completion-table">
      <div class="subheading"><strong>完井定位清单</strong><span>橙色标记对应下列 I/J/K 区间</span></div>
      <el-table :data="completions" border size="small" max-height="220"><el-table-column prop="well" label="井" min-width="150" /><el-table-column prop="i" label="I" width="70" /><el-table-column prop="j" label="J" width="70" /><el-table-column prop="k1" label="K1" width="70" /><el-table-column prop="k2" label="K2" width="70" /><el-table-column prop="status" label="状态" width="100" /></el-table>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.eclipse-grid-3d-panel { padding: 16px; border: 1px solid #e1e7ef; background: #fff; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.panel-heading h3 { margin: 3px 0 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }.kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.grid-3d-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }.grid-3d-controls .el-select { width: min(360px, 100%); }.grid-3d-layout { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: 12px; }.grid-3d-canvas { position: relative; min-height: 440px; border: 1px solid #e5eaf1; background: #f8fafc; overflow: hidden; }.grid-3d-canvas canvas { display: block; width: 100%; height: 100%; }.axis-caption { position: absolute; top: 8px; left: 10px; padding: 4px 7px; color: #475569; background: rgba(255,255,255,.82); font-size: 11px; pointer-events: none; }.scene-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #909399; }.grid-3d-inspector { display: flex; flex-direction: column; gap: 8px; }.inspector-block { display: flex; flex-direction: column; gap: 6px; padding: 10px; border: 1px solid #e5eaf1; background: #f8fafc; color: #475569; font-size: 12px; }.inspector-block strong { color: #1e293b; }.inspector-block small { color: #94a3b8; line-height: 1.5; }.selected-cell dl { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; margin: 0; }.selected-cell dl div { padding: 5px 6px; background: #fff; }.selected-cell dt { color: #94a3b8; font-size: 11px; }.selected-cell dd { margin: 2px 0 0; color: #1e293b; font-weight: 600; }.inspector-actions { display: flex; flex-wrap: wrap; gap: 6px; }.completion-table { margin-top: 12px; border-top: 1px solid #e5eaf1; padding-top: 12px; }.subheading { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #475569; font-size: 12px; }.subheading span { color: #94a3b8; }
@media (max-width: 900px) { .grid-3d-layout { grid-template-columns: 1fr; }.grid-3d-inspector { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }.grid-3d-canvas { min-height: 360px; } }
@media (max-width: 600px) { .grid-3d-inspector { grid-template-columns: 1fr; } }
</style>
