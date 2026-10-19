<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { zcornCornerIndices } from './eclipseCornerGeometry'
import { buildGridRegionParameters, gridRegionUnit } from './eclipseGridRegion'
import { batchBaselineAvailable } from './eclipseBatch.js'
import { createFieldRequestGate, snapshotField, snapshotRangeContext } from './eclipseFieldRequest'
import { mapSurveyPointToGrid, parseEclipseDeviationSurvey, parseEclipseGridReference, surveyPlanPoints } from './eclipseDeviationSurvey'
import { gridReportFrames, gridSeriesKey, gridValueStats, gridValueHistogram, gridValueColor, gridLegendTicks, formatGridValue, gridFieldUnit } from './eclipseGridPresentation.js'

const props = defineProps({
  run: { type: Object, default: null },
  runId: { type: [Number, String], default: null },
  grid: { type: Object, default: null },
  fields: { type: Array, default: () => [] },
  artifacts: { type: Array, default: () => [] },
  unitSystem: { type: String, default: '' },
  wellCompletions: { type: Array, default: () => [] }
})
const emit = defineEmits(['create-grid-field-run', 'add-grid-batch-case'])

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
const locateI = ref(1)
const locateJ = ref(1)
const locateK = ref(1)
const locateError = ref('')
const editValue = ref(null)
const draftOverrides = ref({})
const showAllLayers = ref(true)
const geometrySummary = ref(null)
const fieldValues = ref([])
const deviationSurvey = ref(null)
const deviationSurveyError = ref('')
const deviationGridReference = ref(null)
const deviationGridReferenceError = ref('')
const deviationMapUnits = ref('')
const tvdZeroGridZ = ref('')
const tvdDirection = ref('')
const deviationOverlayRuntimeError = ref('')
const selectedSurveyWell = ref('')
const deviationMappings = ref({})
const displayStats = ref(null), displayBounds = ref(null), distribution = ref([]), lockedBounds = ref(null)
const cutAxis = ref('all'), cutPosition = ref(1), wireframe = ref(true), exaggeration = ref(1), playing = ref(false)
const visibleCells = ref(0)
let playbackTimer

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
const fieldRequests = createFieldRequestGate()
let geometryCenter = null
let geometryBounds = null

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
const reportFrames = computed(() => gridReportFrames(gridFields.value, selectedField.value))
const frameIndex = computed(() => reportFrames.value.findIndex(field => fieldKey(field) === fieldKey(selectedField.value || {})))
const displayUnit = computed(() => gridFieldUnit(selectedField.value, props.unitSystem))
const cutLimit = computed(() => ({ i: props.grid?.nx, j: props.grid?.ny, k: props.grid?.nz }[cutAxis.value] || 1))
const pausePlayback = () => { playing.value = false; clearTimeout(playbackTimer) }
const setFrame = index => { if (reportFrames.value[index]) selectedFieldKey.value = fieldKey(reportFrames.value[index]) }
const selectFrame = index => { pausePlayback(); setFrame(index) }
const schedulePlayback = () => {
  clearTimeout(playbackTimer)
  if (!playing.value) return
  if (frameIndex.value >= reportFrames.value.length - 1) { pausePlayback(); return }
  playbackTimer = setTimeout(() => { if (playing.value && !fieldLoading.value && !loading.value) setFrame(frameIndex.value + 1) }, 1100)
}
const togglePlayback = () => {
  if (playing.value) { pausePlayback(); return }
  if (reportFrames.value.length < 2 || fieldLoading.value || loading.value) return
  playing.value = true
  if (frameIndex.value >= reportFrames.value.length - 1) setFrame(0)
  else schedulePlayback()
}
const selectedFieldLabel = computed(() => selectedField.value
  ? `${selectedField.value.keyword} · ${selectedField.value.dataType} · ${selectedField.value.file} · 时间步 ${selectedField.value.timeStep ?? '静态'}`
  : '-')
const editablePermxField = computed(() => selectedField.value?.keyword === 'PERMX'
  && selectedField.value?.file?.toUpperCase().endsWith('.INIT')
  && selectedField.value?.timeStep === undefined
  && ['REAL', 'DOUB'].includes(selectedField.value?.dataType)
  ? selectedField.value : null)
const baselineFieldArtifact = computed(() => editablePermxField.value
  ? fieldArtifact(editablePermxField.value) : null)
const canCreatePermxRun = computed(() => props.run?.status === 'SUCCEEDED'
  && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null
  && editablePermxField.value?.count === totalCells.value
  && typeof baselineFieldArtifact.value?.sha256 === 'string'
  && /^[0-9a-f]{64}$/i.test(baselineFieldArtifact.value.sha256))
const permxMultiplier = ref(0.5)
const regionBounds = ref({ i1: 1, i2: 1, j1: 1, j2: 1, k1: 1, k2: 1 })
const regionMultiplier = ref(0.5)
const regionError = ref('')
const regionBatchName = ref('')
const editableRegionField = computed(() => ['PERMX', 'PERMY', 'PERMZ', 'PORO'].includes(selectedField.value?.keyword)
  && selectedField.value?.file?.toUpperCase().endsWith('.INIT') && selectedField.value?.timeStep === undefined
  && ['REAL', 'DOUB'].includes(selectedField.value?.dataType) ? selectedField.value : null)
const regionCount = computed(() => ['i', 'j', 'k'].reduce((count, axis) => count * Math.max(0, regionBounds.value[axis + '2'] - regionBounds.value[axis + '1'] + 1), 1))
const canCreateRegionRun = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL'
  && props.run?.parameters == null && editableRegionField.value?.count === totalCells.value && !fieldLoading.value && fieldValues.value.length === totalCells.value)
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
const completionWellNames = computed(() => [...new Set(completions.value.map(item => item.well))].sort())
const selectedSurveyTrack = computed(() => deviationSurvey.value?.wells.find(well => well.name === selectedSurveyWell.value) || null)
const selectedSurveyPlot = computed(() => surveyPlanPoints(selectedSurveyTrack.value))
const selectedSurveyPath = computed(() => selectedSurveyPlot.value.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '))
const mappedSurveyTrack = computed(() => {
  if (!selectedSurveyTrack.value || !deviationGridReference.value) return { points: [], error: '' }
  try {
    return {
      points: selectedSurveyTrack.value.points.map(point => mapSurveyPointToGrid(point, deviationGridReference.value, {
        mapUnitOverride: deviationMapUnits.value,
        gridZAtTvdZero: tvdZeroGridZ.value,
        tvdDirection: tvdDirection.value,
        xyUnit: deviationSurvey.value.units.xpos,
        tvdUnit: deviationSurvey.value.units.tvd
      })),
      error: ''
    }
  } catch (exception) {
    return { points: [], error: exception?.message || '轨迹坐标变换参数无效。' }
  }
})
const deviationOverlayNeeds = computed(() => {
  if (!deviationSurvey.value) return ''
  if (!deviationGridReference.value) return '需选择与本次 EGRID 完全对应的源 GRDECL（包含 MAPAXES 和 GRIDUNIT）。'
  if (deviationGridReference.value.mapUnitNeedsConfirmation && !deviationMapUnits.value) return '源 GRDECL 未声明 MAPUNITS；请从该 Schedule 工程实际配置确认地图单位。'
  if (String(tvdZeroGridZ.value).trim() === '' || !tvdDirection.value) return '需提供 EGRID 中 survey TVD=0 对应的 Z 和 TVD 增加方向；不会默认假设深度基准。'
  return mappedSurveyTrack.value.error
})
const surveyMappingOptions = importedWell => completionWellNames.value.map(name => ({
  name,
  disabled: Object.entries(deviationMappings.value).some(([other, target]) => other !== importedWell && target === name)
}))

const importDeviationSurvey = async event => {
  const files = Array.from(event?.target?.files || [])
  deviationSurveyError.value = ''
  deviationSurvey.value = null
  deviationGridReference.value = null
  deviationGridReferenceError.value = ''
  deviationMapUnits.value = ''
  tvdZeroGridZ.value = ''
  tvdDirection.value = ''
  deviationOverlayRuntimeError.value = ''
  deviationMappings.value = {}
  selectedSurveyWell.value = ''
  try {
    const controls = files.filter(file => /\.cnt$/i.test(file.name))
    const dataFiles = files.filter(file => /\.dev$/i.test(file.name))
    if (files.length !== 2 || controls.length !== 1 || dataFiles.length !== 1) {
      throw new Error('请选择一组官方 deviation 数据：一个 .CNT 控制文件和其引用的一个 .DEV 文件。')
    }
    if (files.some(file => file.size <= 0 || file.size > 64 * 1024 * 1024)) {
      throw new Error('CNT/DEV 文件必须非空且每个不超过 64 MiB。')
    }
    const [controlText, dataText] = await Promise.all([controls[0].text(), dataFiles[0].text()])
    const survey = parseEclipseDeviationSurvey(controlText, dataText, dataFiles[0].name)
    deviationSurvey.value = survey
    selectedSurveyWell.value = survey.wells[0]?.name || ''
    deviationMappings.value = Object.fromEntries(survey.wells
      .filter(well => completionWellNames.value.includes(well.name))
      .map(well => [well.name, well.name]))
  } catch (exception) {
    deviationSurveyError.value = exception?.message || 'Deviation survey 导入失败。'
  } finally {
    if (event?.target) event.target.value = ''
  }
}

const importDeviationGridReference = async event => {
  const files = Array.from(event?.target?.files || [])
  deviationGridReference.value = null
  deviationGridReferenceError.value = ''
  deviationOverlayRuntimeError.value = ''
  deviationMapUnits.value = ''
  try {
    if (files.length !== 1 || !/\.grdecl$/i.test(files[0].name) || files[0].size <= 0 || files[0].size > 64 * 1024 * 1024) {
      throw new Error('请选择一个非空且不超过 64 MiB 的同模型 .GRDECL 文件。')
    }
    const text = await files[0].text()
    deviationGridReference.value = { ...parseEclipseGridReference(text), fileName: files[0].name }
  } catch (exception) {
    deviationGridReferenceError.value = exception?.message || 'GRDECL 网格参考读取失败。'
  } finally {
    if (event?.target) event.target.value = ''
  }
}

const clearDeviationSurvey = () => {
  deviationSurvey.value = null
  deviationSurveyError.value = ''
  deviationGridReference.value = null
  deviationGridReferenceError.value = ''
  deviationMapUnits.value = ''
  tvdZeroGridZ.value = ''
  tvdDirection.value = ''
  deviationOverlayRuntimeError.value = ''
  selectedSurveyWell.value = ''
  deviationMappings.value = {}
}

const axisRangeText = range => Array.isArray(range) ? `${range[0]} – ${range[1]}` : '不可用'

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

const readValues = async (field, context, isCurrent) => {
  const artifact = context.artifacts.find(artifact => artifact?.name === `eclipse-output-${field.file}` &&
    artifact?.contentType === 'application/octet-stream' && Number.isSafeInteger(artifact.id) && artifact.id > 0)
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
      if (!isCurrent()) return []
      const payload = await softwareIntegrationApi.downloadArtifactRange(context.runId, artifact.id, offset, length)
      const blob = payload instanceof Blob ? payload : new Blob([payload])
      const bytes = new Uint8Array(await blob.arrayBuffer())
      if (!isCurrent()) return []
      values.push(...decode(bytes, field))
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
  geometryCenter = null
  geometryBounds = null
}

const resetCamera = () => {
  if (!initialCameraState || !camera || !controls) return
  camera.position.copy(initialCameraState.position)
  controls.target.copy(initialCameraState.target)
  camera.up.set(0, 1, 0)
  controls.update()
}
const fitDistance = (scale = exaggeration.value, aspect = camera?.aspect || 1) => {
  if (!geometryBounds) return (geometrySummary.value?.span || 1) * 2
  const size = geometryBounds.getSize(new THREE.Vector3())
  const half = THREE.MathUtils.degToRad((camera?.fov || 45) / 2)
  return Math.max(1, Math.hypot(size.x, size.y, size.z * scale) / 2) / Math.sin(Math.min(half, Math.atan(Math.tan(half) * aspect))) * 1.12
}
const presetView = name => {
  if (!camera || !controls || !initialCameraState) return
  if (name === '立体') { resetCamera(); return }
  const direction = { 俯视: [0, 1, 0], 正视: [0, 0, 1], 侧视: [1, 0, 0] }[name]
  if (!direction) return
  controls.target.copy(initialCameraState.target)
  camera.up.set(...(name === '俯视' ? [0, 0, -1] : [0, 1, 0]))
  camera.position.copy(controls.target).add(new THREE.Vector3(...direction).multiplyScalar(fitDistance()))
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
  return new THREE.Color(gridValueColor(value, { min, max }))
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
        const corners = zcornCornerIndices(nx, ny, i, j, k).map(index => zcorn[index])
        const pointValues = [
          [pillar00, corners[0]], [pillar10, corners[1]], [pillar01, corners[2]], [pillar11, corners[3]],
          [pillar00, corners[4]], [pillar10, corners[5]], [pillar01, corners[6]], [pillar11, corners[7]]
        ]
        for (const [pillar, z] of pointValues) {
          const offset = pillar * 6
          const dz = coord[offset + 5] - coord[offset + 2]
          const ratio = Math.abs(dz) > 1e-9 ? (z - coord[offset + 2]) / dz : 0
          rawPoints.push(new THREE.Vector3(
            coord[offset] + (coord[offset + 3] - coord[offset]) * ratio,
            coord[offset + 1] + (coord[offset + 4] - coord[offset + 1]) * ratio, z
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
        const corners = zcornCornerIndices(nx, ny, i, j, k).map(index => zcorn[index])
        const pillar00 = j * (nx + 1) + i
        const pillar10 = pillar00 + 1
        const pillar01 = (j + 1) * (nx + 1) + i
        const pillar11 = pillar01 + 1
        const points = [
          pillarPoint(pillar00, corners[0], coord, center), pillarPoint(pillar10, corners[1], coord, center),
          pillarPoint(pillar01, corners[2], coord, center), pillarPoint(pillar11, corners[3], coord, center),
          pillarPoint(pillar00, corners[4], coord, center), pillarPoint(pillar10, corners[5], coord, center),
          pillarPoint(pillar01, corners[6], coord, center), pillarPoint(pillar11, corners[7], coord, center)
        ]
        const centerPoint = points.reduce((sum, point) => sum.add(point), new THREE.Vector3()).multiplyScalar(1 / 8)
        normalized.push({ index: base, i: i + 1, j: j + 1, k: k + 1, points, center: centerPoint, value: null, rawCursor: rawCursor++ })
      }
    }
  }
  return {
    cells: normalized,
    center,
    bounds: rawBounds,
    span: Math.max(...rawBounds.getSize(new THREE.Vector3()).toArray(), 1)
  }
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

const addDeviationTrajectory = group => {
  deviationOverlayRuntimeError.value = ''
  const sourcePoints = mappedSurveyTrack.value.points
  if (sourcePoints.length < 2 || !geometryCenter || !geometryBounds) return
  const span = geometrySummary.value?.span || 1
  const points = sourcePoints.map(point => normalizePoint(point.x, point.y, point.z, geometryCenter))
  if (points.some(point => Math.max(Math.abs(point.x), Math.abs(point.y), Math.abs(point.z)) > span * 5)) {
    deviationOverlayRuntimeError.value = '变换后的轨迹远离当前网格；请核对 GRDECL 是否同源、MAPUNITS 和 TVD 基准。未绘制。'
    return
  }
  const pathBounds = new THREE.Box3().setFromPoints(sourcePoints.map(point => new THREE.Vector3(point.x, point.y, point.z)))
  const gridBounds = geometryBounds.clone().expandByScalar(span * 0.15)
  if (!pathBounds.intersectsBox(gridBounds)) {
    deviationOverlayRuntimeError.value = '变换后的轨迹与当前 EGRID 范围不相交；请核对地图单位、坐标参考和深度基准。未绘制。'
    return
  }
  const trajectory = new THREE.Group()
  const line = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints(points),
    new THREE.LineBasicMaterial({ color: 0xd946ef, depthTest: false })
  )
  line.renderOrder = 10
  line.userData.kind = 'measured-deviation-survey'
  trajectory.add(line)
  for (const [index, color] of [[0, 0x0f766e], [points.length - 1, 0xd946ef]]) {
    const marker = new THREE.Mesh(
      new THREE.SphereGeometry(Math.max(span / 90, 0.16), 12, 8),
      new THREE.MeshBasicMaterial({ color, depthTest: false })
    )
    marker.position.copy(points[index])
    marker.renderOrder = 11
    trajectory.add(marker)
  }
  trajectory.userData.kind = 'measured-deviation-survey'
  trajectory.userData.well = selectedSurveyTrack.value?.name
  group.add(trajectory)
}

const renderGrid = () => {
  if (!geometryReady.value || !cells.length) return
  cells.forEach(cell => { cell.value = fieldValueByIndex(cell.index) })
  displayStats.value = gridValueStats(cells.map(cell => cell.value))
  distribution.value = gridValueHistogram(cells.map(cell => cell.value), displayStats.value)
  const values = cells.map(cell => valueForCell(cell)).filter(value => typeof value === 'number' && Number.isFinite(value))
  const bounds = gridValueStats(values)
  displayBounds.value = bounds ? lockedBounds.value || { min: bounds.min, max: bounds.max } : null
  const min = displayBounds.value?.min ?? 0, max = displayBounds.value?.max ?? 1
  const renderCells = (showAllLayers.value ? cells : currentLayerCells.value).filter(cell => cutAxis.value === 'all' || cell[cutAxis.value] === cutPosition.value)
  visibleCells.value = renderCells.length
  if (!scene) return
  removeRenderedGrid()
  const positions = []
  const colors = []
  const faceOrder = [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]]
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
  gridGroup.scale.y = exaggeration.value
  gridGroup.add(mesh)
  const edgePositions = []
  const edgeOrder = [[0, 1], [1, 3], [3, 2], [2, 0], [4, 5], [5, 7], [7, 6], [6, 4], [0, 4], [1, 5], [2, 6], [3, 7]]
  for (const cell of renderCells) for (const [from, to] of edgeOrder) edgePositions.push(...cell.points[from].toArray(), ...cell.points[to].toArray())
  const edgeGeometry = new THREE.BufferGeometry()
  edgeGeometry.setAttribute('position', new THREE.Float32BufferAttribute(edgePositions, 3))
  const edges = new THREE.LineSegments(edgeGeometry, new THREE.LineBasicMaterial({ color: 0x64748b, transparent: true, opacity: 0.32 }))
  edges.visible = wireframe.value
  gridGroup.add(edges)
  addWellMarkers(gridGroup)
  if (!deviationOverlayNeeds.value) addDeviationTrajectory(gridGroup)
  else deviationOverlayRuntimeError.value = ''
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
  locateI.value = cell.i
  locateJ.value = cell.j
  locateK.value = cell.k
  locateError.value = ''
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

const locateCell = () => {
  if (!geometryReady.value || loading.value || fieldLoading.value || !fieldValues.value.length) return
  const coordinates = [locateI.value, locateJ.value, locateK.value]
  const limits = [props.grid.nx, props.grid.ny, props.grid.nz]
  const valid = coordinates.every((value, index) => Number.isSafeInteger(value) && value >= 1 && value <= limits[index])
  const cell = valid ? cells.find(item => item.i === coordinates[0] && item.j === coordinates[1] && item.k === coordinates[2]) : null
  if (!cell) {
    selectedCell.value = null
    editValue.value = null
    locateError.value = valid ? '该坐标不是可定位的活动单元，请核对 ACTNUM。' : 'I/J/K 必须为模型范围内的整数。'
    renderGrid()
    return
  }
  showAllLayers.value = true
  selectCell(cell)
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
      const ratio = fitDistance(exaggeration.value, width / height) / fitDistance()
      camera.position.sub(controls.target).multiplyScalar(ratio).add(controls.target)
      initialCameraState?.position.multiplyScalar(ratio)
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
  pausePlayback(); lockedBounds.value = null; cutAxis.value = 'all'; cutPosition.value = 1
  displayStats.value = null; displayBounds.value = null; distribution.value = []; visibleCells.value = 0
  const generation = ++loadGeneration
  const isCurrent = () => generation === loadGeneration
  const context = snapshotRangeContext(props.runId, props.artifacts)
  const indexes = Object.fromEntries(Object.entries(geometryFields.value).map(([key, field]) => [key, snapshotField(field)]))
  fieldRequests.invalidate()
  fieldValues.value = []
  fieldLoading.value = false
  fieldError.value = ''
  selectedCell.value = null
  locateI.value = 1
  locateJ.value = 1
  locateK.value = 1
  locateError.value = ''
  editValue.value = null
  draftOverrides.value = {}
  regionBounds.value = { i1: 1, i2: 1, j1: 1, j2: 1, k1: 1, k2: 1 }
  regionError.value = ''
  regionBatchName.value = ''
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
      readValues(indexes.coord, context, isCurrent),
      readValues(indexes.zcorn, context, isCurrent),
      readValues(indexes.actnum, context, isCurrent)
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
    geometryCenter = result.center
    geometryBounds = result.bounds
    geometrySummary.value = { activeCells: cells.length, span: result.span }
    geometryReady.value = true
    initialCameraState = {
      position: new THREE.Vector3(1.08, .85, 1.08).normalize().multiplyScalar(fitDistance()),
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
  clearTimeout(playbackTimer)
  const isCurrent = fieldRequests.begin()
  const field = snapshotField(selectedField.value)
  const context = snapshotRangeContext(props.runId, props.artifacts)
  fieldValues.value = []
  fieldLoading.value = false
  fieldError.value = ''
  selectedCell.value = null
  editValue.value = null
  cells.forEach(cell => { cell.value = null })
  renderGrid()
  if (!geometryReady.value || !field) {
    fieldValues.value = []
    return
  }
  fieldLoading.value = true
  fieldError.value = ''
  try {
    const values = await readValues(field, context, isCurrent)
    if (!isCurrent()) return
    fieldValues.value = values
    cells.forEach(cell => { cell.value = fieldValueByIndex(cell.index) })
    renderGrid()
  } catch (exception) {
    if (!isCurrent()) return
    fieldValues.value = []
    fieldError.value = exception?.message || '场值读取失败。'
    pausePlayback()
  } finally {
    if (isCurrent()) { fieldLoading.value = false; schedulePlayback() }
  }
}
const toggleBounds = () => { lockedBounds.value = lockedBounds.value ? null : displayBounds.value ? { ...displayBounds.value } : null; renderGrid() }
const exportScene = () => {
  if (!renderer || !camera || !scene || !displayBounds.value || fieldLoading.value || loading.value) return
  renderer.render(scene, camera)
  const output = document.createElement('canvas'); output.width = renderer.domElement.width; output.height = renderer.domElement.height + 145
  const ctx = output.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, output.width, output.height); ctx.drawImage(renderer.domElement, 0, 90)
  ctx.fillStyle = '#17375b'; ctx.font = 'bold 20px sans-serif'; ctx.fillText(`ECLIPSE · ${selectedField.value.keyword} · 报告步 ${selectedField.value.timeStep ?? '静态'}`, 22, 30)
  ctx.font = '13px sans-serif'; ctx.fillText(`Run #${props.runId} | ${selectedField.value.file} | ${displayUnit.value}`, 22, 54)
  ctx.fillText(`纵向 ×${exaggeration.value} | ${cutAxis.value === 'all' ? showAllLayers.value ? '全部层' : `K=${layer.value}` : `${cutAxis.value.toUpperCase()}=${cutPosition.value}`} | ${draftEntries.value.length ? '含展示草案' : '原始计算场值'}`, 22, 76)
  const gradient = ctx.createLinearGradient(22, 0, 550, 0)
  for (let i = 0; i <= 20; i++) gradient.addColorStop(i / 20, gridValueColor(i / 20, { min: 0, max: 1 }))
  ctx.fillStyle = gradient; ctx.fillRect(22, output.height - 38, Math.min(528, output.width - 44), 9)
  ctx.fillStyle = '#475569'; ctx.fillText(`${formatGridValue(displayBounds.value.min)} — ${formatGridValue(displayBounds.value.max)} ${displayUnit.value}`, 22, output.height - 10)
  const filename = `eclipse-${props.runId}-${selectedField.value.keyword}-${selectedField.value.timeStep ?? 'static'}.png`
  output.toBlob(blob => { if (!blob) return; const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = filename; a.click(); setTimeout(() => URL.revokeObjectURL(url), 0) }, 'image/png')
}

const applyEdit = () => {
  if (fieldLoading.value || !fieldValues.value.length || !selectedCell.value || !selectedField.value || !Number.isFinite(Number(editValue.value))) return
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

const runPermxScenario = () => {
  if (fieldLoading.value || !fieldValues.value.length || !canCreatePermxRun.value || !selectedCell.value || !editablePermxField.value) {
    ElMessage.warning('请先选择成功的完整基线运行、INIT/PERMX 字段和活动网格单元。')
    return
  }
  const originalValue = fieldValues.value[selectedCell.value.index]
  const multiplier = Number(permxMultiplier.value)
  if (!Number.isFinite(originalValue) || originalValue <= 0 || !Number.isFinite(multiplier)
    || multiplier < 0.000001 || multiplier > 100 || multiplier === 1) {
    ElMessage.warning('PERMX 原值必须为正数；乘数须在 0.000001–100 之间且不能为 1。')
    return
  }
  emit('create-grid-field-run', {
    schemaVersion: 'eclipse-grid-field-parameters/1',
    baselineRunId: Number(props.run.id),
    fieldFile: editablePermxField.value.file,
    fieldArtifactSha256: baselineFieldArtifact.value.sha256.toLowerCase(),
    nx: props.grid.nx,
    ny: props.grid.ny,
    nz: props.grid.nz,
    i: selectedCell.value.i,
    j: selectedCell.value.j,
    k: selectedCell.value.k,
    originalValue,
    multiplier
  })
}

const clearEdits = () => {
  draftOverrides.value = {}
  renderGrid()
}

const runRegionScenario = (batch = false) => {
  regionError.value = ''
  if (fieldLoading.value || loading.value || !geometryReady.value) return
  try {
    const parameters = buildGridRegionParameters({ run: props.run, grid: props.grid, field: selectedField.value,
      initArtifact: fieldArtifact(selectedField.value), gridArtifact: props.artifacts.find(a => a.name === `eclipse-output-${props.grid.fileName}`),
      bounds: regionBounds.value, values: fieldValues.value, actnum: actnumValues, multiplier: regionMultiplier.value })
    if (batch) {
      if (!batchBaselineAvailable(props.run)) throw new Error('请选择未过期的完整无参数 E100 基线。')
      const name = regionBatchName.value.trim()
      if (!name || name.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(name)) throw new Error('请填写1–80个字符的区域方案名称。')
      emit('add-grid-batch-case', { name, parameters })
    } else emit('create-grid-field-run', parameters)
  } catch (exception) { regionError.value = exception.message }
}

const exportDraft = () => {
  if (fieldLoading.value || !fieldValues.value.length || !draftEntries.value.length || !selectedField.value) return
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
watch(() => [props.runId, props.grid, props.fields, props.artifacts], loadGeometry, { immediate: true, deep: true, flush: 'sync' })
watch(() => props.runId, clearDeviationSurvey)
watch(() => [selectedFieldKey.value, selectedField.value, layer.value], loadSelectedField, { deep: true, flush: 'sync' })
watch(showAllLayers, value => { if (!value) cutAxis.value = 'all'; renderGrid() })
watch(() => gridSeriesKey(selectedField.value), () => { pausePlayback(); lockedBounds.value = null }, { flush: 'sync' })
watch(cutAxis, () => { cutPosition.value = 1; selectedCell.value = null; if (cutAxis.value !== 'all') showAllLayers.value = true; renderGrid() })
watch([cutPosition, wireframe], renderGrid)
watch(exaggeration, (value, old) => { if (camera && controls) { const distance = fitDistance(value); camera.position.sub(controls.target).multiplyScalar(distance / fitDistance(old)).add(controls.target); initialCameraState?.position.setLength(distance); controls.update() }; renderGrid() })
watch(() => props.wellCompletions, renderGrid, { deep: true })
watch([selectedSurveyWell, deviationGridReference, deviationMapUnits, tvdZeroGridZ, tvdDirection], renderGrid)

onMounted(() => {
  initializeScene()
  if (geometryReady.value) {
    resetCamera()
    renderGrid()
  }
})
onBeforeUnmount(() => {
  pausePlayback()
  loadGeneration++
  fieldRequests.dispose()
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
      <div><span class="kicker">ECLIPSE EGRID</span><h3>三维几何、井轨迹定位与场值编辑</h3><p>几何与场值来自本次真实 Artifact；单格 INIT/PERMX 可绑定完整基线并真实重算，其他场值编辑仍是后处理草案。</p></div>
      <span>{{ grid.nx }} × {{ grid.ny }} × {{ grid.nz }}</span>
    </div>
    <div class="display-metrics" aria-label="三维原始场值统计"><div><small>当前字段</small><strong>{{ selectedField?.keyword || '—' }}</strong><span>报告步 {{ selectedField?.timeStep ?? '静态' }}</span></div><div v-for="entry in [{ label: '最低原值', value: displayStats?.min }, { label: '最高原值', value: displayStats?.max }, { label: '活动格算术均值', value: displayStats?.mean }]" :key="entry.label"><small>{{ entry.label }}</small><strong>{{ formatGridValue(entry.value) }}</strong><span>{{ displayUnit }}</span></div><div><small>可见 / 活动单元</small><strong>{{ visibleCells }} / {{ geometrySummary?.activeCells ?? 0 }}</strong><span>均值非体积加权</span></div></div>
    <div class="grid-3d-controls">
      <el-select v-model="selectedFieldKey" aria-label="ECLIPSE 三维场值字段" placeholder="选择三维场值字段" :disabled="!gridFields.length" @change="pausePlayback">
        <el-option v-for="field in gridFields" :key="fieldKey(field)" :value="fieldKey(field)" :label="`${field.keyword} · ${field.file} · 时间步 ${field.timeStep ?? '静态'}`" />
      </el-select>
      <el-input-number v-model="layer" :min="1" :max="grid.nz" :step="1" controls-position="right" aria-label="ECLIPSE 三维当前层" />
      <el-checkbox v-model="showAllLayers">显示全部层</el-checkbox>
      <el-button @click="resetCamera">重置视角</el-button>
      <el-tag v-if="geometryReady" type="success">真实几何已加载 {{ geometrySummary?.activeCells ?? 0 }} 个活动单元</el-tag>
      <el-tag v-else-if="loading" type="info">读取 EGRID 几何中</el-tag>
    </div>
    <div v-if="reportFrames.length > 1" class="display-playback" aria-label="三维报告步播放"><el-button type="primary" :disabled="!playing && (loading || fieldLoading)" @click="togglePlayback">{{ playing ? '暂停播放' : '播放演变' }}</el-button><el-button :disabled="frameIndex <= 0 || fieldLoading" aria-label="上一个三维报告步" @click="selectFrame(frameIndex - 1)">‹</el-button><el-slider :model-value="frameIndex" :min="0" :max="reportFrames.length - 1" :disabled="loading || fieldLoading" :format-tooltip="index => `报告步 ${reportFrames[index]?.timeStep}`" aria-label="三维报告步进度" @change="selectFrame"/><el-button :disabled="frameIndex >= reportFrames.length - 1 || fieldLoading" aria-label="下一个三维报告步" @click="selectFrame(frameIndex + 1)">›</el-button><span>报告步 {{ selectedField?.timeStep }}<small>{{ frameIndex + 1 }} / {{ reportFrames.length }} · 仅真实报告，不插值</small></span></div>
    <div class="display-tools"><div><el-button v-for="view in ['立体', '俯视', '正视', '侧视']" :key="view" size="small" @click="presetView(view)">{{ view }}</el-button></div><el-select v-model="cutAxis" aria-label="三维剖面方向"><el-option value="all" label="完整网格"/><el-option v-for="axis in ['i', 'j', 'k']" :key="axis" :value="axis" :label="`${axis.toUpperCase()} 剖面`"/></el-select><el-input-number v-if="cutAxis !== 'all'" v-model="cutPosition" :min="1" :max="cutLimit" :step="1" aria-label="三维剖面位置"/><label>纵向 <el-select v-model="exaggeration" aria-label="三维纵向放大"><el-option v-for="scale in [1, 3, 5, 10]" :key="scale" :value="scale" :label="`×${scale}`"/></el-select></label><el-checkbox v-model="wireframe">网格线</el-checkbox><el-button :disabled="!displayBounds || fieldLoading || loading" @click="exportScene">导出演示图片</el-button></div>
    <el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <el-alert v-else-if="fieldError" type="warning" :closable="false" :title="fieldError" />
    <div class="grid-3d-layout">
      <div ref="canvasHost" class="grid-3d-canvas" :data-deviation-track-ready="geometryReady && !deviationOverlayNeeds && !deviationOverlayRuntimeError && mappedSurveyTrack.points.length > 1 ? 'true' : 'false'" v-loading="loading || fieldLoading">
        <div v-if="!loading && !error && !geometryReady" class="scene-empty">暂无可绘制的 EGRID 三维几何</div>
        <span class="axis-caption">X：红　Y：蓝　Z：绿　橙色：COMPDAT 完井定位</span>
        <div class="display-context">RUN #{{ runId }} · {{ selectedField?.keyword }} · 报告步 {{ selectedField?.timeStep ?? '静态' }}<small>纵向 ×{{ exaggeration }}{{ draftEntries.length ? ' · 含展示草案' : '' }}</small></div>
        <div v-if="displayBounds && !fieldLoading" class="display-legend" aria-label="三维数值色标"><div><strong>{{ selectedField?.keyword }} · {{ displayUnit }}</strong><button @click="toggleBounds">{{ lockedBounds ? '解除固定色标' : '固定当前色标' }}</button></div><div class="display-gradient" :style="{ background: `linear-gradient(to right, ${Array.from({ length: 21 }, (_, i) => gridValueColor(i / 20, { min: 0, max: 1 })).join(',')})` }"/><div class="display-ticks"><span v-for="(tick, index) in gridLegendTicks(displayBounds)" :key="index">{{ formatGridValue(tick) }}</span></div><small>{{ lockedBounds ? '固定范围；范围外使用端点色' : '按当前全部活动格取色' }}{{ draftEntries.length ? '（含展示草案）' : '' }}</small></div>
      </div>
      <aside class="grid-3d-inspector">
        <div v-if="displayStats" class="inspector-block" aria-label="三维原始场值分布"><strong>活动格原值分布 · {{ displayStats.count }} 个有效值</strong><div class="display-histogram"><div v-for="bin in distribution" :key="bin.index" :style="{ height: `${Math.max(1, bin.height)}%`, background: gridValueColor((bin.low + bin.high) / 2, displayBounds) }" :title="`${bin.low}–${bin.high}：${bin.count}个`"><span>{{ bin.count }}</span></div></div><small>单元计数 · 不含展示草案</small></div>
        <div class="inspector-block"><strong>当前场值</strong><span>{{ selectedFieldLabel }}</span><small>蓝 → 红表示当前场值由低到高；“加入编辑草案”仅生成草案，PERMX 可通过下方方案真实重算。</small></div>
        <div class="inspector-block" aria-label="按 IJK 定位活动单元">
          <strong>按坐标选择网格</strong>
          <el-input-number v-model="locateI" :min="1" :max="grid.nx" :step="1" controls-position="right" aria-label="定位网格 I" />
          <el-input-number v-model="locateJ" :min="1" :max="grid.ny" :step="1" controls-position="right" aria-label="定位网格 J" />
          <el-input-number v-model="locateK" :min="1" :max="grid.nz" :step="1" controls-position="right" aria-label="定位网格 K" />
          <el-button :disabled="!geometryReady || loading || fieldLoading || !fieldValues.length" @click="locateCell">定位活动单元</el-button>
          <small v-if="locateError" role="alert">{{ locateError }}</small>
        </div>
        <div v-if="selectedCell" class="inspector-block selected-cell">
          <strong>选中网格单元</strong>
          <dl><div><dt>I</dt><dd>{{ selectedCell.i }}</dd></div><div><dt>J</dt><dd>{{ selectedCell.j }}</dd></div><div><dt>K</dt><dd>{{ selectedCell.k }}</dd></div><div><dt>原值</dt><dd>{{ selectedCell.value ?? '-' }}</dd></div><div><dt>当前值</dt><dd>{{ valueForCell(selectedCell) ?? '-' }}</dd></div></dl>
          <el-input-number v-model="editValue" controls-position="right" :disabled="!selectedField" aria-label="编辑选中网格场值" />
          <div class="inspector-actions"><el-button type="primary" size="small" :disabled="!selectedField" @click="applyEdit">加入编辑草案</el-button><el-button size="small" @click="clearEdits">清空草案</el-button></div>
        </div>
        <div v-if="selectedCell && editablePermxField" class="inspector-block selected-cell" aria-label="PERMX 单格真实重算方案">
          <strong>PERMX 单格输入方案</strong>
          <small>PERMX单位为mD（毫达西），乘数无量纲；只修改所选单元的X方向渗透率，PERMY、孔隙度和其它单元保持原输入。</small>
          <small>仅对当前完整成功基线的全网格 INIT/PERMX 生效；服务端和 Worker 会复核 Artifact、原值及坐标。</small>
          <el-input-number v-model="permxMultiplier" :min="0.000001" :max="100" :step="0.1" :precision="6" controls-position="right" aria-label="PERMX 单元乘数" />
          <el-button type="warning" :disabled="!canCreatePermxRun" @click="runPermxScenario">修改此单元并真实重算</el-button>
          <small v-if="!canCreatePermxRun">当前运行不满足完整无参数基线，或缺少匹配的全网格 PERMX INIT Artifact。</small>
        </div>
        <div class="inspector-block"><strong>编辑草案</strong><span>{{ draftEntries.length }} 个当前字段单元</span><small>不会修改原始运行结果；可导出给后续审批或输入转换流程。</small><el-button v-if="draftEntries.length" size="small" @click="exportDraft">导出 JSON</el-button></div>
        <div v-if="editableRegionField" class="inspector-block" aria-label="ECLIPSE 区域物性真实重算方案">
          <strong>{{ editableRegionField.keyword }} 区域输入方案</strong>
          <small>单位：{{ gridRegionUnit(editableRegionField.keyword) }}；乘数无量纲。仅修改以下矩形选区，最多4096个活动单元；范围外原输入保持。</small>
          <template v-for="(axis, index) in ['i', 'j', 'k']" :key="axis">
            <el-input-number v-model="regionBounds[axis + '1']" :min="1" :max="[grid.nx, grid.ny, grid.nz][index]" :step="1" controls-position="right" :aria-label="`区域 ${axis.toUpperCase()} 起点`" />
            <el-input-number v-model="regionBounds[axis + '2']" :min="1" :max="[grid.nx, grid.ny, grid.nz][index]" :step="1" controls-position="right" :aria-label="`区域 ${axis.toUpperCase()} 终点`" />
          </template>
          <span>选区 {{ regionCount }} 个单元</span>
          <el-input-number v-model="regionMultiplier" :min="0.000001" :max="100" :precision="6" :step="0.1" controls-position="right" aria-label="区域物性乘数" />
          <el-button type="warning" :disabled="!canCreateRegionRun" @click="runRegionScenario(false)">修改选区并真实重算</el-button>
          <el-input v-model="regionBatchName" maxlength="80" placeholder="区域方案名称" aria-label="批量区域方案名称" />
          <el-button :disabled="!canCreateRegionRun || !batchBaselineAvailable(run)" @click="runRegionScenario(true)">将区域加入批量清单</el-button>
          <small v-if="regionError" role="alert">{{ regionError }}</small>
          <small>需要本版本无参数成功基线、完整INIT与EGRID；非活动格、陈旧原值、孔隙度超过1及复杂输入会拒绝。</small>
        </div>
        <div class="inspector-block"><strong>井轨迹定位</strong><span>{{ completions.length }} 条 COMPDAT/COMPDATM 完井记录</span><small>当前使用真实 I/J/K 完井坐标定位；没有 COMPDAT 时不会补画虚构轨迹。</small></div>
      </aside>
    </div>
    <div v-if="completions.length" class="completion-table">
      <div class="subheading"><strong>完井定位清单</strong><span>橙色标记对应下列 I/J/K 区间</span></div>
      <el-table :data="completions" border size="small" max-height="220"><el-table-column prop="well" label="井" min-width="150" /><el-table-column prop="i" label="I" width="70" /><el-table-column prop="j" label="J" width="70" /><el-table-column prop="k1" label="K1" width="70" /><el-table-column prop="k2" label="K2" width="70" /><el-table-column prop="status" label="状态" width="100" /></el-table>
    </div>
    <section class="deviation-import-panel" aria-label="ECLIPSE 偏斜调查只读导入" data-testid="eclipse-deviation-import">
      <div class="subheading"><strong>实测偏斜调查只读导入</strong><span>GRID CONTROL `.CNT` + 数据 `.DEV`</span></div>
      <p class="deviation-import-note">依据 Schedule 官方 EX3 格式读取 X/Y/TVD/MD。CNT/DEV 仅在浏览器内存中读取；仅在另行提供与本次 EGRID 同源的 GRDECL、确认地图单位并显式给出 TVD 深度基准后才绘制实测轨迹，不修改模型。</p>
        <div class="deviation-import-actions">
          <input type="file" multiple accept=".cnt,.dev" aria-label="选择 deviation CNT 和 DEV 文件" data-testid="eclipse-deviation-files" @change="importDeviationSurvey" />
          <el-button v-if="deviationSurvey" size="small" @click="clearDeviationSurvey">清除本次只读导入</el-button>
        </div>
      <el-alert v-if="deviationSurveyError" type="error" :closable="false" :title="deviationSurveyError" />
      <template v-if="deviationSurvey">
        <div class="deviation-survey-summary" data-testid="eclipse-deviation-summary">
          <div><span>读取井数</span><strong>{{ deviationSurvey.wellCount }}</strong></div>
          <div><span>测点总数</span><strong>{{ deviationSurvey.totalPoints }}</strong></div>
          <div><span>平面坐标单位</span><strong>{{ deviationSurvey.xyUnits }}</strong></div>
          <div><span>TVD / MD 单位</span><strong>{{ deviationSurvey.depthUnits }}</strong></div>
        </div>
        <el-alert v-if="deviationSurvey.warnings.length" type="warning" :closable="false" :title="deviationSurvey.warnings.join('；')" />
        <el-alert v-else type="success" :closable="false" title="文件结构、井分段、测点数值及 MD 顺序检查通过；三维叠加仍需同源 GRDECL 与已核实深度基准。" />
        <el-table :data="deviationSurvey.wells" class="deviation-survey-wells" border size="small" max-height="260" data-testid="eclipse-deviation-wells">
          <el-table-column prop="name" label="调查文件井名" min-width="145" />
          <el-table-column label="测点数" width="90"><template #default="{ row }">{{ row.points.length }}</template></el-table-column>
          <el-table-column label="MD 范围" min-width="145"><template #default="{ row }">{{ axisRangeText(row.ranges.md) }} {{ deviationSurvey.units.md }}</template></el-table-column>
          <el-table-column label="TVD 范围" min-width="145"><template #default="{ row }">{{ axisRangeText(row.ranges.tvd) }} {{ deviationSurvey.units.tvd }}</template></el-table-column>
          <el-table-column label="映射到当前完井井名" min-width="220">
            <template #default="{ row }">
              <el-select v-model="deviationMappings[row.name]" clearable placeholder="未映射" :aria-label="`映射井名 ${row.name}`">
                <el-option v-for="option in surveyMappingOptions(row.name)" :key="option.name" :value="option.name" :label="option.name" :disabled="option.disabled" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <div class="deviation-survey-detail">
          <label>只读选择井
            <el-select v-model="selectedSurveyWell" aria-label="选择偏斜调查井">
              <el-option v-for="well in deviationSurvey.wells" :key="well.name" :value="well.name" :label="well.name" />
            </el-select>
          </label>
          <div v-if="selectedSurveyTrack" class="deviation-survey-plan" role="img" :aria-label="`${selectedSurveyTrack.name} 原始 X/Y 平面投影`" data-testid="eclipse-deviation-plan">
            <svg viewBox="0 0 600 220" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
              <path v-if="selectedSurveyPlot.length > 1" :d="selectedSurveyPath" fill="none" stroke="#7c3aed" stroke-width="2.5" />
              <circle v-for="(point, index) in selectedSurveyPlot" :key="index" :cx="point.x" :cy="point.y" :r="index === 0 ? 5 : 3" :fill="index === 0 ? '#0f766e' : '#7c3aed'" />
            </svg>
            <small>X/Y 原始投影（{{ deviationSurvey.xyUnits }}）；仅显示相对路径形状，非 EGRID 叠加。</small>
          </div>
          <div class="deviation-survey-point-table">
            <el-table v-if="selectedSurveyTrack" :data="selectedSurveyTrack.points" border size="small" max-height="260">
              <el-table-column prop="lineNumber" label="DEV 行" width="85" />
              <el-table-column prop="md" :label="`MD (${deviationSurvey.units.md})`" min-width="110" />
              <el-table-column prop="tvd" :label="`TVD (${deviationSurvey.units.tvd})`" min-width="110" />
              <el-table-column prop="x" :label="`XPOS (${deviationSurvey.units.xpos})`" min-width="110" />
              <el-table-column prop="y" :label="`YPOS (${deviationSurvey.units.ypos})`" min-width="110" />
            </el-table>
          </div>
        </div>
        <div class="deviation-registration" data-testid="eclipse-deviation-registration">
          <strong>真实 EGRID 配准（显式基准，不自动猜测）</strong>
          <div class="deviation-import-actions">
            <input type="file" accept=".grdecl" aria-label="选择与当前 EGRID 同源的 GRDECL" data-testid="eclipse-grid-reference-file" @change="importDeviationGridReference" />
            <span v-if="deviationGridReference" class="deviation-reference-meta">
              {{ deviationGridReference.fileName }} · GRIDUNIT {{ deviationGridReference.gridUnit }} ·
              MAPUNITS {{ deviationGridReference.mapUnit || '缺失' }} ·
              MAPAXES 原点 ({{ deviationGridReference.mapAxes.origin.x }}, {{ deviationGridReference.mapAxes.origin.y }})
            </span>
          </div>
          <el-alert v-if="deviationGridReferenceError" type="error" :closable="false" :title="deviationGridReferenceError" />
          <div v-if="deviationGridReference" class="deviation-registration-fields">
            <label v-if="deviationGridReference.mapUnitNeedsConfirmation">地图坐标单位（必须按该 Schedule 工程配置确认）
              <el-select v-model="deviationMapUnits" placeholder="未声明 MAPUNITS；请选择已核实单位" aria-label="确认地图坐标单位" data-testid="eclipse-map-unit">
                <el-option value="FEET" label="FEET" /><el-option value="METRES" label="METRES" />
              </el-select>
            </label>
            <label>EGRID 中 survey TVD=0 对应的 Z（{{ deviationGridReference.gridUnit }}）
              <el-input v-model="tvdZeroGridZ" type="number" placeholder="从该井的已核实深度基准输入，不自动设为 0" aria-label="EGRID 中 TVD 零点对应 Z" data-testid="eclipse-tvd-zero-z" />
            </label>
            <label>TVD 增加方向（对应 GRDECL Z）
              <el-select v-model="tvdDirection" placeholder="请选择已核实方向" aria-label="选择 TVD 增加方向" data-testid="eclipse-tvd-direction">
                <el-option value="POSITIVE" label="TVD 增加时 EGRID Z 增加" />
                <el-option value="NEGATIVE" label="TVD 增加时 EGRID Z 减少" />
              </el-select>
            </label>
          </div>
          <el-alert v-if="deviationOverlayNeeds" type="warning" :closable="false" :title="deviationOverlayNeeds" />
          <el-alert v-else-if="deviationOverlayRuntimeError" type="error" :closable="false" :title="deviationOverlayRuntimeError" />
          <el-alert v-else-if="geometryReady && mappedSurveyTrack.points.length > 1" type="success" :closable="false" :title="`已将 ${selectedSurveyTrack?.name || '当前井'} 的实测 Survey 按 MAPAXES 与显式深度基准叠加到当前 EGRID；请与 Schedule 连接点逐点核验。`" data-testid="eclipse-deviation-overlay-ready" />
          <small class="deviation-coordinate-warning">依据 Schedule gridpropdata：MAPAXES 定义 grid/map 原点及 X/Y 轴，旧 GRDECL 的 MAPUNITS 可能缺失且 Schedule 会按配置默认。必须选择与当前 Run 同源的 GRDECL，并按真实 Schedule 配置确认缺失单位及 TVD 零点/方向；超出网格安全范围或没有空间交集时拒绝绘图。EGRID 与源文件是否同源仍需人工确认；此步骤只显示轨迹，不生成 IJK、COMPDAT 或修改 DATA。</small>
        </div>
      </template>
    </section>
  </section>
</template>

<style lang="scss" scoped>
.display-metrics { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); margin: 14px 0; border: 1px solid #dce7f3; border-radius: 10px; overflow: hidden; background: linear-gradient(110deg, #edf5ff, #fff); }.display-metrics > div { padding: 14px; border-right: 1px solid #dce7f3; }.display-metrics small, .display-metrics span { font-size: 11px; color: #7a8fa5; }.display-metrics strong { display: block; margin: 8px 0; font-size: 21px; color: #173b60; overflow-wrap: anywhere; }.display-playback { display: flex; align-items: center; gap: 12px; padding: 12px; background: #eff5fc; border-radius: 8px; margin-bottom: 12px; }.display-playback .el-slider { flex: 1; min-width: 50px; }.display-playback span { color: #254f77; font-size: 12px; }.display-playback small { display: block; margin-top: 4px; color: #8193a7; font-size: 10px; }.display-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 10px 0; }.display-tools .el-select { width: 115px; }.display-tools label { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #6b7e92; }.display-context { position: absolute; left: 18px; top: 54px; color: #426685; font-size: 12px; pointer-events: none; }.display-context small { display: block; margin-top: 7px; color: #8797aa; }.display-legend { position: absolute; left: 18px; bottom: 16px; width: min(410px, calc(100% - 36px)); box-sizing: border-box; padding: 12px; background: #ffffffed; border: 1px solid #dce7f1; border-radius: 8px; }.display-legend > div:first-child { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 11px; color: #31597d; }.display-legend button { border: 0; background: #eff5fc; color: #3874ad; padding: 4px 8px; border-radius: 4px; cursor: pointer; font-size: 10px; }.display-gradient { height: 9px; border-radius: 3px; margin: 10px 0 7px; }.display-ticks { display: flex; justify-content: space-between; color: #5d7993; font-size: 10px; }.display-legend > small { display: block; font-size: 9px; color: #8a9caf; margin-top: 7px; }.display-histogram { display: flex; gap: 3px; align-items: end; height: 95px; padding-top: 18px; }.display-histogram > div { flex: 1; min-width: 0; border-radius: 3px 3px 0 0; position: relative; }.display-histogram span { position: absolute; top: -16px; left: 50%; transform: translateX(-50%); white-space: nowrap; font-size: 9px; color: #7890a6; }.eclipse-grid-3d-panel .grid-3d-layout { align-items: start; }.eclipse-grid-3d-panel .grid-3d-canvas { height: 570px; min-height: 0; border-radius: 9px; }.grid-3d-canvas :deep(canvas) { display: block; width: 100%; height: 100%; }.eclipse-grid-3d-panel .grid-3d-inspector { max-height: 572px; overflow-y: auto; }.eclipse-grid-3d-panel .inspector-block { border-radius: 7px; overflow-wrap: anywhere; }
@media(max-width: 900px) { .display-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); }.eclipse-grid-3d-panel .grid-3d-inspector { max-height: none; overflow: visible; }.eclipse-grid-3d-panel .grid-3d-canvas { height: 470px; } } @media(max-width:600px) { .display-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }.display-playback { flex-wrap: wrap; }.display-playback > span { width: 100%; }.eclipse-grid-3d-panel .grid-3d-canvas { height: 420px; } }
.eclipse-grid-3d-panel { padding: 16px; border: 1px solid #e1e7ef; background: #fff; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.panel-heading h3 { margin: 3px 0 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }.kicker { color: #2b6cb3; font-size: 11px; font-weight: 700; letter-spacing: .08em; }.grid-3d-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }.grid-3d-controls .el-select { width: min(360px, 100%); }.grid-3d-layout { display: grid; grid-template-columns: minmax(0, 1fr) 260px; gap: 12px; }.grid-3d-canvas { position: relative; min-height: 440px; border: 1px solid #e5eaf1; background: #f8fafc; overflow: hidden; }.grid-3d-canvas canvas { display: block; width: 100%; height: 100%; }.axis-caption { position: absolute; top: 8px; left: 10px; padding: 4px 7px; color: #475569; background: rgba(255,255,255,.82); font-size: 11px; pointer-events: none; }.scene-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #909399; }.grid-3d-inspector { display: flex; flex-direction: column; gap: 8px; }.inspector-block { display: flex; flex-direction: column; gap: 6px; padding: 10px; border: 1px solid #e5eaf1; background: #f8fafc; color: #475569; font-size: 12px; }.inspector-block strong { color: #1e293b; }.inspector-block small { color: #94a3b8; line-height: 1.5; }.selected-cell dl { display: grid; grid-template-columns: repeat(2, 1fr); gap: 5px; margin: 0; }.selected-cell dl div { padding: 5px 6px; background: #fff; }.selected-cell dt { color: #94a3b8; font-size: 11px; }.selected-cell dd { margin: 2px 0 0; color: #1e293b; font-weight: 600; }.inspector-actions { display: flex; flex-wrap: wrap; gap: 6px; }.completion-table { margin-top: 12px; border-top: 1px solid #e5eaf1; padding-top: 12px; }.subheading { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 8px; color: #475569; font-size: 12px; }.subheading span { color: #94a3b8; }
.deviation-import-panel { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; border-top: 1px solid #e5eaf1; padding-top: 14px; }.deviation-import-note, .deviation-coordinate-warning { margin: 0; color: #737a84; font-size: 12px; line-height: 1.6; }.deviation-import-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }.deviation-import-actions input { max-width: 100%; color: #475569; font-size: 12px; }.deviation-survey-summary { display: grid; grid-template-columns: repeat(4, minmax(110px, 1fr)); border: 1px solid #e5eaf1; }.deviation-survey-summary div { display: flex; flex-direction: column; gap: 4px; padding: 9px 11px; border-right: 1px solid #e5eaf1; background: #f8fafc; }.deviation-survey-summary div:last-child { border-right: 0; }.deviation-survey-summary span { color: #909399; font-size: 11px; }.deviation-survey-summary strong { color: #334155; font-size: 14px; }.deviation-import-panel .el-select { width: 100%; }.deviation-survey-detail { display: grid; grid-template-columns: minmax(260px, .8fr) minmax(350px, 1.2fr); gap: 12px; }.deviation-survey-detail > label { display: flex; flex-direction: column; gap: 6px; color: #606266; font-size: 12px; }.deviation-survey-detail > label .el-select { max-width: 360px; }.deviation-survey-plan { display: flex; flex-direction: column; gap: 4px; min-width: 0; padding: 8px; border: 1px solid #e5eaf1; background: #faf9ff; }.deviation-survey-plan svg { width: 100%; height: 180px; }.deviation-survey-plan small { color: #737a84; font-size: 11px; }.deviation-survey-point-table { grid-column: 1 / -1; min-width: 0; }.deviation-registration { display: flex; flex-direction: column; gap: 8px; border: 1px solid #e5eaf1; padding: 10px; background: #fbfcfe; color: #475569; font-size: 12px; }.deviation-registration > strong { color: #334155; }.deviation-reference-meta { color: #64748b; overflow-wrap: anywhere; }.deviation-registration-fields { display: grid; grid-template-columns: repeat(3, minmax(180px, 1fr)); gap: 10px; }.deviation-registration-fields label { display: flex; flex-direction: column; gap: 5px; color: #606266; font-size: 11px; }.deviation-coordinate-warning { padding: 8px 10px; border-left: 3px solid #d4a017; background: #fff9e6; }
@media (max-width: 900px) { .grid-3d-layout { grid-template-columns: 1fr; }.grid-3d-inspector { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }.grid-3d-canvas { min-height: 360px; } }
@media (max-width: 900px) { .deviation-registration-fields { grid-template-columns: repeat(2, minmax(180px, 1fr)); } }
@media (max-width: 600px) { .grid-3d-inspector { grid-template-columns: 1fr; }.deviation-survey-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }.deviation-survey-detail { grid-template-columns: 1fr; }.deviation-survey-point-table { grid-column: 1; }.deviation-registration-fields { grid-template-columns: 1fr; } }
</style>
