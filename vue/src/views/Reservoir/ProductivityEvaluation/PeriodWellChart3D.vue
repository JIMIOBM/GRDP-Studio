<script setup>
/** 库级三种对比的共用绘图组件：一次一周期，按井分组。各业务页仍独立管理参数和请求。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import { ElMessage } from 'element-plus'
import { comparisonMethods, pressureForms } from '@/utils/productivityComparisonChart'
import { dataManagementApi } from '@/api/docker'
import SpatialBasemapControls from './SpatialBasemapControls.vue'
import { readCoordinateRows, matchWellCoordinates, coordinateBounds, spatialLayout, buildSimulatedGeology,
  paintSpatialMap, imagePlacement, PRODUCTIVITY_COLORS, GEOLOGY_COLORS } from '@/utils/storageSpatialChart'

const props = defineProps({ result: { type: Object, default: null }, busy: Boolean, title: { type: String, default: '' },
  projectId: { type: [Number, String], default: null }, gasReservoirId: { type: [Number, String], default: null },
  storageId: { type: [Number, String], default: null },
  comparisonType: { type: String, default: 'period', validator: value => ['period', 'method', 'direction'].includes(value) } })
const host = ref(null)
const periodOffset = ref(0)
const view = ref('chart')
const renderError = ref('')
const labels = ref([])
const axisLabels = ref([])
const missingLabels = ref([])
const hovered = ref(null)
const hiddenSeries = ref([])
const mapMode = ref('geology'), showFill = ref(true), showContours = ref(true), showBars = ref(true), topView = ref(false)
const fieldSeries = ref('mean'), coordinateRows = shallowRef([]), coordinateLoading = ref(false), coordinateError = ref('')
const mapSource = shallowRef({ kind: 'simulation', opacity: .85 }), field = shallowRef(null), fieldBusy = ref(false)
const scopeKey = computed(() => [props.projectId, props.gasReservoirId, props.storageId].every(id => Number(id) > 0)
  ? [props.projectId, props.gasReservoirId, props.storageId].map(Number).join(':') : '')
let coordinateVersion = 0, coordinateRequest, spatialWorker, fieldVersion = 0
// 井位读取与库的项目/气藏范围绑定；切库时中止旧请求，避免旧坐标覆盖新图。
const loadCoordinates = async () => {
  const version = ++coordinateVersion
  coordinateRequest?.abort(); coordinateRows.value = []; coordinateError.value = ''; coordinateLoading.value = false
  if (!scopeKey.value) return
  coordinateRequest = new AbortController(); coordinateLoading.value = true
  try {
    const response = await dataManagementApi.getWellHead(props.projectId, props.gasReservoirId,
      { signal: coordinateRequest.signal, silentError: true })
    if (version === coordinateVersion && !disposed) coordinateRows.value = readCoordinateRows(response)
  } catch (error) {
    if (version === coordinateVersion && !disposed) coordinateError.value = error?.response?.data?.message || error.message || '读取井坐标失败'
  } finally { if (version === coordinateVersion && !disposed) coordinateLoading.value = false }
}
const grouped = computed(() => props.comparisonType !== 'period')
const chartTitle = computed(() => props.comparisonType === 'method' ? '单周期多方法平均无阻流量对比图'
  : props.comparisonType === 'direction' ? '单周期注采平均无阻流量对比图' : '单周期平均无阻流量对比图')
// 颜色绑定方法/方向，不绑定井，也不随切换周期或隐藏图例重新分配。
const methodColors = { 'back-pressure': '#f0bd16', isochronal: '#626870', stable: '#b9bec5',
  'modified-isochronal': '#b49136', 'one-point': '#898f98', unstable: '#30363f' }
const series = computed(() => props.comparisonType === 'direction'
  ? [{ key: 'production', label: '采气', color: '#f0bd16' }, { key: 'injection', label: '注气', color: '#626870' }]
  : props.comparisonType === 'method' ? (props.result?.methods || []).map(method => ({ key: method,
    label: comparisonMethods.find(item => item.value === method)?.label || method, color: methodColors[method] || '#898f98' }))
    : [{ key: 'mean', label: '平均无阻流量', color: '#f0bd16' }])
const visibleSeries = computed(() => props.comparisonType === 'method' ? series.value
  : series.value.filter(item => !hiddenSeries.value.includes(item.key)))
const toggleSeries = key => {
  if (hiddenSeries.value.includes(key)) hiddenSeries.value = hiddenSeries.value.filter(item => item !== key)
  else if (visibleSeries.value.length > 1) hiddenSeries.value = [...hiddenSeries.value, key]
}
const cellsOf = well => props.comparisonType === 'method' ? well?.methods || []
  : props.comparisonType === 'direction' ? well?.directions || [] : well ? [well] : []
const keyOf = cell => props.comparisonType === 'method' ? cell.method : props.comparisonType === 'direction' ? cell.operationType : 'mean'
const periods = computed(() => props.result?.periods || [])
const period = computed(() => periods.value[periodOffset.value] || null)
const format = value => value == null ? '—' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
const wells = computed(() => {
  const values = new Map((period.value?.wells || []).map(well => [String(well.wellId), well]))
  // 始终按成员列表排列，不因某期缺失数据而把另一口井移到该位置。
  return matchWellCoordinates(props.result?.wells || [], coordinateRows.value).map(well => {
    const cells = new Map(cellsOf(values.get(String(well.wellId))).map(cell => [keyOf(cell), cell]))
    const means = series.value.map(item => {
      const mean = cells.get(item.key)
      const available = Number.isFinite(mean?.averageOpenFlow) && mean.averageOpenFlow >= 0 && mean.recordCount > 0
      return { ...item, available, averageOpenFlow: available ? mean.averageOpenFlow : null, recordCount: available ? mean.recordCount : 0 }
    })
    return { ...well, ...means[0], wellId: well.wellId, wellName: well.wellName, values: means,
      available: means.some(mean => mean.available && (props.comparisonType === 'method' || !hiddenSeries.value.includes(mean.key))) }
  })
})
// 无有效产能的井仍在数据表中，但不应把当前图面的真实井位和产能柱挤到角落。
const chartWells = computed(() => props.comparisonType === 'method'
  ? wells.value.filter(well => well.values.some(value => value.available && visibleSeries.value.some(item => item.key === value.key)))
  : wells.value)
const wellBounds = computed(() => coordinateBounds(wells.value))
const bounds = computed(() => coordinateBounds(chartWells.value, mapMode.value === 'geology' && mapSource.value.kind === 'image' ? mapSource.value : null))
const layout = computed(() => spatialLayout(chartWells.value, bounds.value))
const plottedWells = computed(() => layout.value?.wells || [])
const missingCoordinates = computed(() => chartWells.value.filter(well => well.coordinateIssue))
const fieldPoints = computed(() => plottedWells.value.flatMap(well => {
  const cell = well.values.find(value => value.key === fieldSeries.value)
  return cell?.available ? [{ x: well.xCoordinate, y: well.yCoordinate, value: cell.averageOpenFlow }] : []
}))
const contourSeriesLabel = computed(() => series.value.find(item => item.key === fieldSeries.value)?.label || '')
const mapCaption = computed(() => mapMode.value === 'geology'
  ? mapSource.value.kind === 'simulation' ? ''
    : `底图：${mapSource.value.attribute || '地质属性'}${mapSource.value.unit ? `（${mapSource.value.unit}）` : ''} · ${mapSource.value.name || ''}`
  : `${contourSeriesLabel.value} · IDW 插值估计，仅覆盖有效井位凸包；井间非实测数据`)
const mapPalette = computed(() => mapMode.value === 'geology' ? GEOLOGY_COLORS : PRODUCTIVITY_COLORS)
const showMapLegend = computed(() => mapMode.value === 'geology' ? mapSource.value.kind === 'simulation' : !!field.value?.values)
// 插值放在 Worker 中计算；版本号使换周期、换方法后迟到的结果失效。
const requestSpatialField = () => {
  const version = ++fieldVersion
  spatialWorker?.terminate(); spatialWorker = null; field.value = null; fieldBusy.value = false
  if (mapMode.value !== 'productivity' || !bounds.value || !props.result) return
  fieldBusy.value = true
  try {
    spatialWorker = new Worker(new URL('../../../utils/storageSpatialChart.worker.js', import.meta.url), { type: 'module' })
    spatialWorker.onmessage = ({ data }) => {
      if (disposed || version !== fieldVersion || data.id !== version) return
      field.value = data.field; fieldBusy.value = false; spatialWorker?.terminate(); spatialWorker = null
    }
    spatialWorker.onerror = () => {
      if (disposed || version !== fieldVersion) return
      field.value = { message: '空间插值失败，当前仍可查看井位和产能柱' }; fieldBusy.value = false
      spatialWorker?.terminate(); spatialWorker = null
    }
    spatialWorker.postMessage({ id: version, points: fieldPoints.value.map(point => ({ ...point })), bounds: { ...bounds.value }, resolution: 96 })
  } catch {
    field.value = { message: '当前浏览器无法启动空间插值，请使用井位柱图或数据列表' }; fieldBusy.value = false
  }
}
// 一个实例对应“井＋方法/方向”，井名则只在整组前方标一次。
const points = computed(() => plottedWells.value.flatMap((well, wellIndex) => visibleSeries.value.map((item, seriesIndex) => ({
  ...well, ...well.values.find(value => value.key === item.key),
  color: item.color,
  wellIndex, seriesIndex
}))))
const filteredChart = computed(() => view.value === 'chart' && props.comparisonType !== 'method' && hiddenSeries.value.length > 0)
const validCount = computed(() => !filteredChart.value && period.value?.validWellCount != null ? period.value.validWellCount
  : wells.value.filter(well => well.values.some(value => value.available && (!filteredChart.value
    || visibleSeries.value.some(item => item.key === value.key)))).length)
const recordCount = computed(() => !filteredChart.value && period.value?.recordCount != null ? period.value.recordCount
  : wells.value.reduce((total, well) => total + well.values.reduce((sum, value) => sum
    + ((!filteredChart.value || visibleSeries.value.some(item => item.key === value.key)) ? value.recordCount : 0), 0), 0))
const subtitle = computed(() => {
  if (!props.result) return ''
  const result = props.result
  const method = comparisonMethods.find(item => item.value === result.method)?.label || ''
  const form = pressureForms.find(item => item.value === result.pressureMethod)?.label || ''
  if (props.comparisonType === 'direction') return `${method} · ${form} · 地层压力 ${format(result.formationPressure)} MPa · 注气压力 ${format(result.injectionPressure)} MPa`
  const injecting = result.operationType === 'injection'
  return `${props.comparisonType === 'method' ? '' : method + ' · '}${injecting ? '注气' : '采气'} · ${form} · ${injecting ? '注气压力' : '地层压力'} ${format(injecting ? result.injectionPressure : result.formationPressure)} MPa`
})
const periodText = computed(() => period.value ? `第${period.value.index}周期　${period.value.startDate} — ${period.value.endDate}` : '')
// 各周期共享纵轴量程，避免切换后同样高的柱子实际代表不同的流量。
const maximum = computed(() => {
  let max = 0
  for (const item of periods.value) for (const well of item.wells || []) for (const cell of cellsOf(well)) {
    if (Number.isFinite(cell.averageOpenFlow) && cell.recordCount > 0) max = Math.max(max, cell.averageOpenFlow)
  }
  if (max <= 0) return 1
  const step = max / 5, power = 10 ** Math.floor(Math.log10(step))
  const nice = [1, 2, 2.5, 5, 10].find(value => value * power >= step) * power
  // 极大值不因进位溢出，极小值也不能产生 0 量程。
  const rounded = Math.ceil(max / nice) * nice
  return Number.isFinite(rounded) && rounded > 0 ? rounded : max
})
const wellCeilings = computed(() => {
  const ceilings = new Map()
  for (const item of periods.value) for (const well of item.wells || []) for (const cell of cellsOf(well)) {
    if (Number.isFinite(cell.averageOpenFlow) && cell.recordCount > 0)
      ceilings.set(String(well.wellId), Math.max(ceilings.get(String(well.wellId)) || 0, cell.averageOpenFlow))
  }
  return ceilings
})
const emptyMessage = computed(() => props.busy ? '正在计算…' : !props.result ? '请选择井并计算'
  : !periods.value.length ? '暂无可展示的周期' : !wells.value.length ? '当前周期暂无参与对比的井' : '')

let renderer, scene, camera, controls, group, bars, wellMarkers, outline, keyLight, observer, environmentTarget
let frame = 0, disposed = false, activeIndex = -1, dragging = false
let width = 0, height = 0, axisHeight = 8, planeWidth = 12, planeDepth = 12
let positions = [], wellPositions = [], ticks = []
const raycaster = new THREE.Raycaster()
const missing = new THREE.Color('#b7bbc0'), highlight = new THREE.Color('#ffda24')
const pointer = new THREE.Vector2()

const clearGeometry = () => {
  if (group) {
    scene.remove(group)
    group.traverse(object => {
      object.geometry?.dispose()
      if (object.material) for (const material of Array.isArray(object.material) ? object.material : [object.material]) { material.map?.dispose(); material.dispose() }
      if (object.isInstancedMesh) object.dispose()
    })
  }
  group = null; bars = null; wellMarkers = null; outline = null; positions = []; wellPositions = []; ticks = []
  labels.value = []; axisLabels.value = []; missingLabels.value = []; hovered.value = null; activeIndex = -1
}
const project = position => {
  const point = position.clone().project(camera)
  return { x: (point.x + 1) * width / 2, y: (1 - point.y) * height / 2, visible: Math.abs(point.z) < 1 }
}
const draw = () => {
  if (disposed || !renderer || renderError.value || !width || !height) return
  renderer.render(scene, camera)
  // 密集井名按屏幕空间避让；未标出的井仍可悬停查看，数据列表始终保留全部井。
  const occupied = new Set(), visible = []
  axisLabels.value = ticks.map(tick => {
    const point = project(tick.position)
    if (tick.kind === 'x') point.y += 12
    if (tick.kind === 'y') { point.x += 14; point.y -= 7 }
    return { ...point, text: tick.text, kind: tick.kind }
  })
  for (const tick of axisLabels.value) if (tick.visible) {
    const size = tick.text.length * 6.5 + 8
    const left = tick.kind === 'x' ? tick.x - size / 2 : tick.kind === 'y' ? tick.x : tick.x - size
    for (let x = Math.floor(left / 24); x <= Math.ceil((left + size) / 24); x++)
      for (let y = Math.floor((tick.y - 9) / 20); y <= Math.ceil((tick.y + 9) / 20); y++) occupied.add(`${x}:${y}`)
  }
  for (const [index, position] of wellPositions.entries()) {
    const text = plottedWells.value[index]?.wellName || ''
    const labelWidth = Math.min(140, Math.max(48, text.length * 7 + 12))
    // 分组较宽时可在同一井格内侧移井名，避免后排名字被前排柱体遮住。
    for (const candidate of [position.label, ...(position.alternatives || [])]) {
      const point = project(candidate)
      if (!point.visible || point.x < labelWidth / 2 || point.x > width - labelWidth / 2 || point.y < 12 || point.y > height - 18) continue
      if (bars?.visible && points.value.length <= 100) {
        // 普通井数下隐藏被前排实体挡住的井名，避免把后排名字直接贴在另一根柱面上。
        pointer.set(point.x / width * 2 - 1, 1 - point.y / height * 2)
        raycaster.setFromCamera(pointer, camera)
        const hit = raycaster.intersectObject(bars)[0]
        const distance = candidate.clone().sub(raycaster.ray.origin).dot(raycaster.ray.direction)
        if (hit && hit.distance < distance - .01) continue
      }
      const left = Math.floor((point.x - labelWidth / 2) / 24), right = Math.ceil((point.x + labelWidth / 2) / 24)
      const top = Math.floor((point.y - 9) / 20), bottom = Math.ceil((point.y + 9) / 20)
      const cells = []
      for (let x = left; x <= right; x++) for (let y = top; y <= bottom; y++) cells.push(`${x}:${y}`)
      if (cells.some(cell => occupied.has(cell))) continue
      cells.forEach(cell => occupied.add(cell))
      visible.push({ ...point, index, text, missing: !plottedWells.value[index]?.available })
      break
    }
  }
  labels.value = visible
  missingLabels.value = showBars.value && grouped.value && points.value.length <= 100 ? positions.flatMap((position, index) => {
    if (points.value[index]?.available) return []
    const point = project(position.top)
    return point.visible && point.x > 12 && point.x < width - 12 && point.y > 12 && point.y < height - 12 ? [{ ...point, index }] : []
  }) : []
}
const scheduleDraw = () => {
  if (frame || disposed) return
  frame = requestAnimationFrame(() => { frame = 0; draw() })
}
const fitCamera = () => {
  if (!camera || !width || !height) return
  camera.updateMatrixWorld(true)
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity
  const extent = []
  for (const x of [-planeWidth / 2, planeWidth / 2]) for (const z of [-planeDepth / 2, planeDepth / 2])
    extent.push(new THREE.Vector3(x, -.1, z))
  if (!topView.value) extent.push(new THREE.Vector3(-planeWidth / 2, axisHeight, planeDepth / 2))
  // 以固定井位和跨周期共用量程取景，换周期不因当前柱高变化而缩放整张图。
  if (showBars.value) extent.push(...plottedWells.value.map(well => new THREE.Vector3(well.position.x,
    (wellCeilings.value.get(String(well.wellId)) || 0) / maximum.value * axisHeight, well.position.z)))
  for (const position of extent) {
      const point = position.applyMatrix4(camera.matrixWorldInverse)
      minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x)
      minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y)
    }
  // 按投影后的实际边界居中，边距用像素保留给刻度/井名，避免宽屏下仍缩成一小块。
  const left = Math.max(88, Math.min(120, width * .065)), right = Math.max(95, Math.min(130, width * .09))
  const top = Math.max(10, Math.min(24, height * .05)), bottom = Math.max(38, Math.min(68, height * .1))
  const scale = Math.max((maxX - minX) / Math.max(1, width - left - right),
    (maxY - minY) / Math.max(1, height - top - bottom))
  const centerX = (minX + maxX) / 2 + (right - left) * scale / 2
  const centerY = (minY + maxY) / 2 + (top - bottom) * scale / 2
  camera.left = centerX - width * scale / 2; camera.right = centerX + width * scale / 2
  camera.top = centerY + height * scale / 2; camera.bottom = centerY - height * scale / 2
  camera.updateProjectionMatrix()
}
const resetView = () => {
  if (!camera || !controls) return
  const span = Math.max(planeWidth, planeDepth, axisHeight)
  controls.target.set(0, topView.value ? 0 : axisHeight * .24, 0)
  const elevation = grouped.value ? 1.15 : .95
  camera.position.set(topView.value ? 0 : span * .8, span * (topView.value ? 3 : elevation), topView.value ? .001 : span * 1.25)
  camera.zoom = 1
  camera.lookAt(controls.target)
  controls.update(); fitCamera(); hovered.value = null; scheduleDraw()
}
const resize = () => {
  if (!renderer || !host.value) return
  const nextWidth = host.value.clientWidth, nextHeight = host.value.clientHeight
  if (!nextWidth || !nextHeight || (nextWidth === width && nextHeight === height)) return
  width = nextWidth; height = nextHeight
  renderer.setSize(width, height)
  // 窗口变化重设取景和刻度疏密，不改变实际井位或距离比例。
  if (group) renderData()
}
const addLines = (points, color) => {
  const geometry = new THREE.BufferGeometry().setFromPoints(points.map(point => new THREE.Vector3(...point)))
  group.add(new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({ color, toneMapped: false })))
}
let simulatedCanvas
const mapCanvas = () => {
  if (mapMode.value === 'productivity') return paintSpatialMap({ field: field.value, maximum: maximum.value, fill: showFill.value, lines: showContours.value })
  const canvas = document.createElement('canvas'); canvas.width = 1024; canvas.height = 1024
  const context = canvas.getContext('2d'); context.fillStyle = '#f5f7f8'; context.fillRect(0, 0, 1024, 1024)
  context.globalAlpha = props.comparisonType === 'method' ? .85 : mapSource.value.opacity ?? .85
  if (mapSource.value.kind === 'simulation') {
    simulatedCanvas ||= paintSpatialMap({ field: buildSimulatedGeology(), simulated: true })
    context.drawImage(simulatedCanvas, 0, 0)
  } else if (mapSource.value.image && bounds.value) {
    const rectangle = imagePlacement(mapSource.value, bounds.value)
    if (rectangle) {
      context.save()
      context.translate((rectangle.x + (mapSource.value.flipX ? rectangle.width : 0)) * 1024,
        (rectangle.y + (mapSource.value.flipY ? rectangle.height : 0)) * 1024)
      context.scale(mapSource.value.flipX ? -1 : 1, mapSource.value.flipY ? -1 : 1)
      context.drawImage(mapSource.value.image, 0, 0, rectangle.width * 1024, rectangle.height * 1024); context.restore()
    }
  }
  return canvas
}
const renderData = (reset = false) => {
  if (!renderer || !scene || renderError.value) return
  clearGeometry()
  group = new THREE.Group(); scene.add(group)
  const seriesCount = Math.max(1, visibleSeries.value.length)
  const clusterColumns = props.comparisonType === 'method' ? Math.min(3, seriesCount) : seriesCount
  const clusterRows = Math.ceil(seriesCount / clusterColumns)
  planeWidth = layout.value?.planeWidth || 16; planeDepth = layout.value?.planeDepth || 11; axisHeight = 6.4
  let spacing = 3
  for (let i = 0; i < plottedWells.value.length; i++) for (let j = i + 1; j < plottedWells.value.length; j++) {
    const a = plottedWells.value[i].position, b = plottedWells.value[j].position
    const distance = Math.hypot(a.x - b.x, a.z - b.z)
    if (distance > .001) spacing = Math.min(spacing, distance)
  }
  // 多方法按井位排列成最多三列的柱组；符号尺寸不按六个系列连续挤压成细线。
  const methodCluster = props.comparisonType === 'method'
  const minBarSide = methodCluster ? .36 : .14
  const barSide = Math.max(minBarSide, Math.min(methodCluster ? .54 : .66,
    spacing * .52 / (clusterColumns * 1.18)))
  const texture = new THREE.CanvasTexture(mapCanvas()); texture.colorSpace = THREE.SRGBColorSpace
  texture.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy())
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(planeWidth, planeDepth),
    new THREE.MeshBasicMaterial({ map: texture, side: THREE.DoubleSide, toneMapped: false }))
  floor.rotation.x = -Math.PI / 2; floor.position.y = -.03; group.add(floor)
  const base = new THREE.Mesh(new THREE.BoxGeometry(planeWidth, .045, planeDepth),
    new THREE.MeshBasicMaterial({ color: '#c8ccd1', toneMapped: false }))
  base.position.y = -.057; group.add(base)
  // 底面与柔影分层：保持白灰配色，不让灯光把整块底面照成白色。
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(planeWidth, planeDepth),
    new THREE.ShadowMaterial({ color: '#353c48', opacity: .13, depthWrite: false, toneMapped: false }))
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = -.018; shadow.receiveShadow = true; group.add(shadow)
  // 只有底部平面；不再生成背墙、侧墙或竖直网格。
  const grid = []
  const horizontalTicks = height < 360 || width < 700 ? 2 : 4
  for (let i = 0; i <= horizontalTicks; i++) {
    const x = -planeWidth / 2 + i * planeWidth / horizontalTicks, z = planeDepth / 2 - i * planeDepth / horizontalTicks
    grid.push([x, -.01, -planeDepth / 2], [x, -.01, planeDepth / 2], [-planeWidth / 2, -.01, z], [planeWidth / 2, -.01, z])
    if (bounds.value) {
      ticks.push({ position: new THREE.Vector3(x, -.02, planeDepth / 2 + .18), text: format(bounds.value.minX + (bounds.value.maxX - bounds.value.minX) * i / horizontalTicks), kind: 'x' })
      ticks.push({ position: new THREE.Vector3(planeWidth / 2 + .22, -.02, z), text: format(bounds.value.minY + (bounds.value.maxY - bounds.value.minY) * i / horizontalTicks), kind: 'y' })
    }
  }
  ticks.push({ position: new THREE.Vector3(0, -.02, planeDepth / 2 + 1.8), text: 'X（m）', kind: 'x' },
    { position: new THREE.Vector3(planeWidth / 2 + 2.5, -.02, 0), text: 'Y（m）', kind: 'y' })
  addLines(grid, '#d2dce1')
  const axisX = -planeWidth / 2, axisZ = planeDepth / 2
  const axes = [[axisX, 0, axisZ], [planeWidth / 2, 0, axisZ],
    [planeWidth / 2, 0, axisZ], [planeWidth / 2, 0, -planeDepth / 2]]
  if (!topView.value) axes.push([axisX, 0, axisZ], [axisX, axisHeight, axisZ])
  const tickCount = height < 360 ? 2 : [5, 6, 4].find(count => {
    const step = maximum.value / count, unit = step / 10 ** Math.floor(Math.log10(step))
    return [1, 2, 2.5, 5, 10].some(nice => Math.abs(unit - nice) < 1e-8)
  }) || 5
  for (let index = 0; !topView.value && index <= tickCount; index++) {
    const y = axisHeight * index / tickCount, value = maximum.value * (index / tickCount)
    axes.push([axisX - .12, y, axisZ], [axisX, y, axisZ])
    ticks.push({ position: new THREE.Vector3(axisX - .3, y, axisZ),
      text: value !== 0 && (value >= 1e7 || value < .01) ? value.toExponential(1) : format(value) })
  }
  addLines(axes, '#7b8189')
  const span = Math.max(planeWidth, planeDepth, axisHeight)
  keyLight.position.set(-span * .9, span * 3.5, span * 1.2)
  keyLight.shadow.camera.left = -span; keyLight.shadow.camera.right = span
  keyLight.shadow.camera.top = span; keyLight.shadow.camera.bottom = -span
  keyLight.shadow.camera.far = span * 6; keyLight.shadow.camera.updateProjectionMatrix()
  keyLight.shadow.normalBias = span * .0003
  renderer.shadowMap.needsUpdate = true
  if (points.value.length) {
    // 微小倒角让棱边接光，保留方柱外形。实例化绘制与缓存阴影用于大井数场景。
    const geometry = new RoundedBoxGeometry(1, 1, 1, points.value.length > 300 ? 1 : 2, .025)
    const vertices = geometry.getAttribute('position'), shades = new Float32Array(vertices.count * 3)
    for (let index = 0; index < vertices.count; index++) {
      // 柱面有轻微的上下明暗过渡，顶面仍保留金黄色高光，不改变柱高或数据映射。
      const shade = (.64 + (vertices.getY(index) + .5) * .36) * (.9 + (.5 - vertices.getX(index)) * .1)
      shades.set([shade, shade, shade], index * 3)
    }
    geometry.setAttribute('color', new THREE.BufferAttribute(shades, 3))
    bars = new THREE.InstancedMesh(geometry,
      new THREE.MeshPhysicalMaterial({ color: '#ffffff', vertexColors: true, roughness: .3, metalness: .4,
        clearcoat: .12, clearcoatRoughness: .32, envMapIntensity: .4 }), points.value.length)
    bars.castShadow = true; bars.visible = showBars.value
    // 柱脚的软接触阴影与定向投影分开，缩放后仍有贴地感；空值/零值不生成实体柱的阴影。
    const contactShadows = new THREE.InstancedMesh(new THREE.PlaneGeometry(1, 1), new THREE.ShaderMaterial({
      transparent: true, depthWrite: false,
      vertexShader: `
        varying vec2 vUv;
        void main() {
          vUv = uv;
          gl_Position = projectionMatrix * modelViewMatrix * instanceMatrix * vec4(position, 1.0);
        }`,
      fragmentShader: `
        varying vec2 vUv;
        void main() {
          float d = length(max(abs(vUv - 0.5) - vec2(0.25), 0.0));
          float alpha = (1.0 - smoothstep(0.0, 0.24, d)) * 0.2;
          gl_FragColor = vec4(vec3(0.22), alpha);
          #include <colorspace_fragment>
        }`,
    }), points.value.length)
    const contact = new THREE.Object3D(); contact.rotation.x = -Math.PI / 2
    contactShadows.renderOrder = 1
    const matrix = new THREE.Object3D()
    wellMarkers = new THREE.InstancedMesh(new THREE.CylinderGeometry(.045, .045, .025, 12),
      new THREE.MeshBasicMaterial({ color: '#354557', toneMapped: false }), plottedWells.value.length)
    plottedWells.value.forEach((well, index) => {
      const { x, z } = well.position
      matrix.position.set(x, .005, z); matrix.scale.set(1, 1, 1); matrix.updateMatrix(); wellMarkers.setMatrixAt(index, matrix.matrix)
      const labelZ = z + clusterRows * barSide * 1.18 / 2 + .65
      wellPositions.push({ label: new THREE.Vector3(x, .03, labelZ), alternatives:
        [...[-1, 1].map(side => new THREE.Vector3(x + side * Math.max(1.2, barSide * clusterColumns), .03, z)),
          new THREE.Vector3(x, .03, z - .9)] })
    })
    wellMarkers.instanceMatrix.needsUpdate = true; wellMarkers.computeBoundingSphere(); group.add(wellMarkers)
    points.value.forEach((point, index) => {
      const column = point.seriesIndex % clusterColumns, row = Math.floor(point.seriesIndex / clusterColumns)
      const x = point.position.x + (column - (clusterColumns - 1) / 2) * barSide * 1.18
      const z = point.position.z + (row - (clusterRows - 1) / 2) * barSide * 1.18
      const barHeight = point.available ? point.averageOpenFlow / maximum.value * axisHeight : 0
      const contactSize = barHeight > 0 ? barSide * 1.8 : 0
      contact.position.set(x, -.008, z); contact.scale.set(contactSize, contactSize, 1); contact.updateMatrix()
      contactShadows.setMatrixAt(index, contact.matrix)
      // 零值/无记录只画贴地的底标，不给缺失数据伪造柱高。
      matrix.position.set(x, barHeight / 2 + .012, z)
      matrix.scale.set(barSide, Math.max(barHeight, .024), barSide); matrix.updateMatrix()
      const color = point.available ? new THREE.Color(point.color) : missing
      bars.setMatrixAt(index, matrix.matrix); bars.setColorAt(index, color)
      positions.push({ top: new THREE.Vector3(x, barHeight + .035, z), matrix: matrix.matrix.clone(), color })
    })
    bars.instanceMatrix.needsUpdate = true; bars.instanceColor.needsUpdate = true
    bars.computeBoundingSphere(); group.add(bars)
    contactShadows.visible = showBars.value
    contactShadows.instanceMatrix.needsUpdate = true; contactShadows.computeBoundingSphere(); group.add(contactShadows)
    const box = new THREE.BoxGeometry(1, 1, 1)
    outline = new THREE.LineSegments(new THREE.EdgesGeometry(box), new THREE.LineBasicMaterial({ color: '#30343a' }))
    box.dispose(); outline.matrixAutoUpdate = false; outline.visible = false; group.add(outline)
  }
  if (reset) resetView()
  else { fitCamera(); scheduleDraw() }
}
const clearHover = () => {
  if (bars && activeIndex >= 0 && positions[activeIndex]) {
    bars.setColorAt(activeIndex, positions[activeIndex].color)
    bars.instanceColor.needsUpdate = true
  }
  if (outline) outline.visible = false
  activeIndex = -1; hovered.value = null; scheduleDraw()
}
const onPointerMove = event => {
  if (!bars || !camera || dragging || view.value !== 'chart') return
  const rect = host.value.getBoundingClientRect(), x = event.clientX - rect.left, y = event.clientY - rect.top
  pointer.set(x / rect.width * 2 - 1, -(y / rect.height) * 2 + 1)
  raycaster.setFromCamera(pointer, camera)
  const hit = raycaster.intersectObject(bars.visible ? bars : wellMarkers)[0]
  const index = bars.visible ? hit?.instanceId : points.value.findIndex(point => point.wellIndex === hit?.instanceId && point.key === fieldSeries.value)
  if (index == null || !positions[index] || !points.value[index]) return clearHover()
  if (activeIndex !== index) {
    clearHover(); activeIndex = index
    bars.setColorAt(index, grouped.value ? positions[index].color.clone().lerp(new THREE.Color('#ffffff'), .22) : highlight)
    bars.instanceColor.needsUpdate = true
    outline.matrix.copy(positions[index].matrix); outline.visible = showBars.value
  }
  const point = points.value[index]
  const anchor = project(showBars.value ? positions[index].top : new THREE.Vector3(point.position.x, .03, point.position.z))
  hovered.value = { well: points.value[index], anchor,
    x: Math.max(8, Math.min(anchor.x + 22, width - 248)), y: Math.max(8, Math.min(anchor.y - 114, height - 112)) }
  scheduleDraw()
}
const onControlStart = () => { dragging = true; clearHover() }
const onControlEnd = () => { dragging = false }
const onContextLost = event => {
  event.preventDefault(); renderError.value = '三维绘图已中断，请刷新页面恢复；当前结果仍可在数据列表查看。'; clearHover()
}
const initialize = () => {
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    renderer.setClearColor('#ffffff')
    renderer.outputColorSpace = THREE.SRGBColorSpace
    renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFShadowMap
    renderer.shadowMap.autoUpdate = false
    renderer.domElement.setAttribute('aria-label', chartTitle.value)
    renderer.domElement.setAttribute('role', 'img')
    host.value.appendChild(renderer.domElement)
    scene = new THREE.Scene()
    // 内置摄影棚环境只生成一次，提供宽而柔的反光；无需外部贴图，也不随旋转重新生成。
    const studio = new RoomEnvironment(), pmrem = new THREE.PMREMGenerator(renderer)
    try { environmentTarget = pmrem.fromScene(studio, .04); scene.environment = environmentTarget.texture }
    finally { studio.dispose(); pmrem.dispose() }
    scene.add(new THREE.HemisphereLight('#ffffff', '#dce0e6', .9))
    keyLight = new THREE.DirectionalLight('#fff5dd', 2.4)
    keyLight.castShadow = true; keyLight.shadow.mapSize.set(2048, 2048)
    keyLight.shadow.radius = 8; keyLight.shadow.bias = -.00005
    scene.add(keyLight)
    camera = new THREE.OrthographicCamera(-10, 10, 10, -10, .1, 10000)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = false; controls.enablePan = false
    controls.minPolarAngle = .001; controls.maxPolarAngle = Math.PI / 2 - .08
    controls.minZoom = .3; controls.maxZoom = 12
    controls.addEventListener('change', scheduleDraw)
    controls.addEventListener('start', onControlStart); controls.addEventListener('end', onControlEnd)
    renderer.domElement.addEventListener('pointermove', onPointerMove)
    renderer.domElement.addEventListener('pointerleave', clearHover)
    renderer.domElement.addEventListener('webglcontextlost', onContextLost)
    observer = new ResizeObserver(resize); observer.observe(host.value)
    resize(); renderData(true)
  } catch {
    renderError.value = '当前浏览器不支持三维绘图，请使用数据列表查看结果，或启用浏览器硬件加速。'
  }
}
const exportImage = () => {
  if (!renderer || !period.value || renderError.value) return
  try {
    clearHover(); draw()
    // 合成界面文字和 WebGL 画布，导出中明确标注库、周期、日期和单位。
    const canvas = document.createElement('canvas'), scale = Math.min(window.devicePixelRatio || 1, 2)
    const context = canvas.getContext('2d')
    context.font = '12px "Microsoft YaHei", sans-serif'
    let legendX = 16, legendY = 98
    const legendItems = grouped.value ? visibleSeries.value.map(item => {
      const itemWidth = context.measureText(item.label).width + 42
      if (legendX > 16 && legendX + itemWidth > width - 16) { legendX = 16; legendY += 22 }
      const entry = { ...item, x: legendX, y: legendY }; legendX += itemWidth
      return entry
    }) : []
    const headerHeight = (legendItems.length ? legendY + 16 : 90) + 60
    canvas.width = width * scale; canvas.height = (height + headerHeight + 64) * scale
    context.scale(scale, scale)
    context.fillStyle = '#fff'; context.fillRect(0, 0, width, height + headerHeight + 64)
    context.fillStyle = '#222'; context.textAlign = 'center'; context.font = 'bold 15px "Microsoft YaHei", sans-serif'
    context.fillText(props.title, width / 2, 25)
    context.font = '13px "Microsoft YaHei", sans-serif'; context.fillText(periodText.value, width / 2, 50)
    context.fillStyle = '#666'; context.fillText(subtitle.value, width / 2, 74)
    context.font = '12px "Microsoft YaHei", sans-serif'; context.textAlign = 'left'
    for (const item of legendItems) {
      context.fillStyle = item.color; context.fillRect(item.x, item.y - 9, 10, 10)
      context.fillStyle = '#555'; context.fillText(item.label, item.x + 16, item.y)
    }
    context.textAlign = 'center'; context.fillStyle = '#665c40'
    if (mapCaption.value) context.fillText(mapCaption.value, width / 2, headerHeight - 39, width - 24)
    if (showMapLegend.value) {
      const palette = mapPalette.value, startX = width / 2 - 95, gradient = context.createLinearGradient(startX, 0, startX + 160, 0)
      palette.forEach((color, index) => gradient.addColorStop(index / (palette.length - 1), color))
      context.fillStyle = gradient; context.fillRect(startX, headerHeight - 27, 160, 10)
      context.fillStyle = '#555'; context.textAlign = 'right'; context.fillText(mapMode.value === 'geology' ? '20 m' : '0', startX - 8, headerHeight - 17)
      context.textAlign = 'left'; context.fillText(mapMode.value === 'geology' ? '100 m' : `${format(maximum.value)}（10⁴m³/d）`, startX + 170, headerHeight - 17)
    }
    context.drawImage(renderer.domElement, 0, headerHeight, width, height)
    context.font = '13px "Microsoft YaHei", sans-serif'
    context.textAlign = 'center'
    for (const label of labels.value) {
      context.fillStyle = label.missing ? '#999' : '#333'; context.fillText(label.text, label.x, label.y + headerHeight + 4, 140)
    }
    context.fillStyle = '#777'
    for (const label of missingLabels.value) context.fillText('—', label.x, label.y + headerHeight + 4)
    context.font = '12px "Microsoft YaHei", sans-serif'
    context.textAlign = 'right'; context.fillStyle = '#666'
    for (const tick of axisLabels.value) if (tick.visible) {
      context.textAlign = tick.kind === 'x' ? 'center' : tick.kind === 'y' ? 'left' : 'right'
      context.fillText(tick.text, tick.x, tick.y + headerHeight + 4)
    }
    context.textAlign = 'left'; context.fillText('平均无阻流量（10⁴m³/d）', 12, height + headerHeight + 25)
    context.textAlign = 'right'; context.fillText(`${filteredChart.value ? '当前显示 · ' : ''}有效井 ${validCount.value}/${wells.value.length} · 有效记录 ${recordCount.value} 条`, width - 12, height + headerHeight + 25)
    context.textAlign = 'left'; context.fillStyle = '#777'
    context.fillText([`有坐标井 ${plottedWells.value.length}/${wells.value.length}`, coordinateError.value,
      mapMode.value === 'productivity' ? field.value?.message || (field.value?.constant ? '产能相同，无分级等值线' : '井间数值为插值估计，非实测') : '',
      grouped.value ? '分组柱在井位中心附近错开显示' : ''].filter(Boolean).join(' · '), 12, height + headerHeight + 49, width - 24)
    const link = document.createElement('a')
    link.download = `${props.title}-第${period.value.index}周期.png`.replace(/[\\/:*?"<>|]/g, '-')
    link.href = canvas.toDataURL('image/png'); link.click()
  } catch { ElMessage.error('图片导出失败，请重置视角后重试') }
}
watch([() => props.result, () => props.comparisonType], () => {
  periodOffset.value = 0; hiddenSeries.value = []
  if (props.comparisonType === 'method') { showBars.value = true; topView.value = false }
  clearHover()
  renderer?.domElement.setAttribute('aria-label', chartTitle.value)
  nextTick(() => renderData(true))
}, { immediate: true })
watch(periodOffset, () => { clearHover(); nextTick(() => renderData()) })
watch(hiddenSeries, () => { clearHover(); nextTick(() => renderData()) })
watch(scopeKey, () => { loadCoordinates(); fieldVersion++; spatialWorker?.terminate(); spatialWorker = null; field.value = null; fieldBusy.value = false }, { immediate: true })
watch(visibleSeries, items => { if (!items.some(item => item.key === fieldSeries.value)) fieldSeries.value = items[0]?.key || 'mean' }, { immediate: true })
watch([mapMode, fieldPoints, bounds], requestSpatialField, { immediate: true })
watch([layout, mapMode, mapSource, field, showFill, showContours, showBars], () => nextTick(() => renderData()))
watch(topView, () => nextTick(() => renderData(true)))
watch(view, () => { clearHover(); nextTick(resize) })
onMounted(initialize)
onBeforeUnmount(() => {
  disposed = true; coordinateVersion++; coordinateRequest?.abort(); fieldVersion++; spatialWorker?.terminate()
  cancelAnimationFrame(frame); observer?.disconnect()
  controls?.removeEventListener('change', scheduleDraw)
  controls?.removeEventListener('start', onControlStart); controls?.removeEventListener('end', onControlEnd); controls?.dispose()
  if (renderer) {
    renderer.domElement.removeEventListener('pointermove', onPointerMove)
    renderer.domElement.removeEventListener('pointerleave', clearHover)
    renderer.domElement.removeEventListener('webglcontextlost', onContextLost)
    clearGeometry(); keyLight?.shadow.dispose(); environmentTarget?.dispose(); renderer.dispose(); renderer.domElement.remove()
  }
  renderer = null; scene = null; camera = null; controls = null; keyLight = null; environmentTarget = null
})
</script>

<template>
  <section class="period-chart" :aria-label="chartTitle">
    <div class="chart-toolbar">
      <div class="period-picker">
        <label for="storage-display-period">展示周期</label>
        <button type="button" aria-label="上一周期" :disabled="!period || periodOffset === 0 || busy" @click="periodOffset--">‹</button>
        <select id="storage-display-period" v-model.number="periodOffset" :disabled="!periods.length || busy">
          <option v-if="!periods.length" :value="0">暂无周期</option>
          <option v-for="(item, index) in periods" :key="item.index" :value="index">第 {{ item.index }} 周期</option>
        </select>
        <button type="button" aria-label="下一周期" :disabled="!period || periodOffset >= periods.length - 1 || busy" @click="periodOffset++">›</button>
        <span class="period-dates" aria-live="polite">{{ period ? `${period.startDate} — ${period.endDate}` : '计算后选择展示周期' }}</span>
      </div>
      <div class="chart-actions">
        <button type="button" :class="{ selected: view === 'chart' }" @click="view = 'chart'">三维柱图</button>
        <button type="button" :class="{ selected: view === 'table' }" @click="view = 'table'">数据列表</button>
        <button v-if="comparisonType !== 'method'" type="button" :class="{ selected: topView }" :disabled="view !== 'chart' || !!renderError" @click="topView = !topView">{{ topView ? '立体视角' : '俯视查看' }}</button>
        <button type="button" :disabled="view !== 'chart' || !!renderError" @click="resetView">重置视角</button>
        <button type="button" :disabled="!period || view !== 'chart' || !!renderError || fieldBusy || coordinateLoading" @click="exportImage">导出图片</button>
      </div>
    </div>
    <div v-show="view === 'chart'" class="spatial-toolbar" :class="{ 'method-spatial-toolbar': comparisonType === 'method' }">
      <div class="map-mode-row">
        <button type="button" :class="{ selected: mapMode === 'geology' }" @click="mapMode = 'geology'">地质底图叠加</button>
        <button type="button" :class="{ selected: mapMode === 'productivity' }" @click="mapMode = 'productivity'">产能等值线</button>
        <template v-if="mapMode === 'productivity'">
          <label v-if="grouped">底图系列
            <select v-model="fieldSeries" aria-label="等值线产能系列"><option v-for="item in visibleSeries" :key="item.key" :value="item.key">{{ item.label }}</option></select>
          </label>
          <label><input v-model="showContours" type="checkbox" />等值线</label>
          <label><input v-model="showFill" type="checkbox" />填色</label>
        </template>
        <label v-if="comparisonType !== 'method'"><input v-model="showBars" type="checkbox" />产能柱</label>
        <button v-if="comparisonType !== 'method'" type="button" :disabled="coordinateLoading || !scopeKey" @click="loadCoordinates">{{ coordinateLoading ? '读取井位…' : '刷新井位' }}</button>
      </div>
      <SpatialBasemapControls v-show="mapMode === 'geology'" :scope-key="scopeKey" :bounds="wellBounds"
        :show-opacity="comparisonType !== 'method'" :show-coordinate-settings="comparisonType !== 'method'"
        @change="mapSource = $event" />
      <div class="map-description" :class="{ simulated: mapMode === 'geology' && mapSource.kind === 'simulation' }">
        <span v-if="mapCaption">{{ mapCaption }}</span>
        <span v-if="showMapLegend" class="map-color-legend">
          <span>{{ mapMode === 'geology' ? '20 m' : '0' }}</span>
          <i :style="{ background: `linear-gradient(90deg, ${mapPalette.join(',')})` }" />
          <span>{{ mapMode === 'geology' ? '100 m' : format(maximum) + '（10⁴m³/d）' }}</span>
        </span>
      </div>
      <div v-if="coordinateError" class="spatial-warning" role="alert">井位读取失败：{{ coordinateError }}。数据列表不受影响。</div>
      <div v-else-if="!coordinateLoading && missingCoordinates.length" class="spatial-warning" role="status">
        未绘制 {{ missingCoordinates.length }} 口井（缺少有效或唯一坐标）：{{ missingCoordinates.map(well => well.wellName).join('、') }}；仍保留在数据列表中。
      </div>
      <div v-if="mapMode === 'productivity' && (fieldBusy || field?.message || field?.constant)" class="spatial-warning" role="status">
        {{ fieldBusy ? '正在生成等值线…' : field?.message || '当前有效井产能相同，仅显示统一填色，没有分级等值线。' }}
      </div>
    </div>
    <div class="chart-heading"><h3>{{ chartTitle }}</h3><p>{{ subtitle || (grouped ? '每口井一组柱，按周期查看' : '每口井一根柱，按周期查看') }}</p></div>
    <div v-if="grouped && view === 'chart' && series.length" class="series-legend" aria-label="显示系列">
      <span v-for="item in comparisonType === 'method' ? series : []" :key="item.key" class="series-key">
        <i :style="{ background: item.color }" />{{ item.label }}
      </span>
      <button v-for="item in comparisonType !== 'method' ? series : []" :key="item.key" type="button" :aria-pressed="!hiddenSeries.includes(item.key)"
        :class="{ muted: hiddenSeries.includes(item.key) }" :disabled="!hiddenSeries.includes(item.key) && visibleSeries.length === 1"
        :title="hiddenSeries.includes(item.key) ? '显示' + item.label : visibleSeries.length === 1 ? '至少保留一个系列' : '隐藏' + item.label" @click="toggleSeries(item.key)">
        <i :style="{ background: item.color }" />{{ item.label }}
      </button>
    </div>
    <div v-show="view === 'chart'" class="chart-content">
      <div ref="host" class="canvas-host" />
      <span v-if="!topView" class="axis-title">平均无阻流量（10⁴m³/d）</span>
      <div class="chart-labels" aria-hidden="true">
        <span v-for="label in labels" :key="label.index" class="well-label" :class="{ missing: label.missing }" :style="{ left: label.x + 'px', top: label.y + 'px' }">{{ label.text }}</span>
        <span v-for="(tick, index) in axisLabels" v-show="tick.visible" :key="'tick-' + index" class="tick-label" :class="tick.kind ? 'tick-' + tick.kind : ''" :style="{ left: tick.x + 'px', top: tick.y + 'px' }">{{ tick.text }}</span>
        <span v-for="label in missingLabels" :key="'missing-' + label.index" class="missing-label" :style="{ left: label.x + 'px', top: label.y + 'px' }">—</span>
      </div>
      <div v-if="renderError || emptyMessage" class="chart-message" role="status">
        <span>{{ renderError || emptyMessage }}</span>
        <button v-if="renderError" type="button" @click="view = 'table'">查看数据列表</button>
      </div>
      <div v-else-if="!validCount" class="empty-period" role="status">当前周期无有效记录，灰色底标表示无数据</div>
      <svg v-if="hovered" class="hover-leader" aria-hidden="true">
        <line :x1="hovered.anchor.x" :y1="hovered.anchor.y" :x2="hovered.x + 12" :y2="hovered.y + 96" />
        <circle :cx="hovered.anchor.x" :cy="hovered.anchor.y" r="3.5" />
      </svg>
      <div v-if="hovered" class="chart-tooltip" :style="{ left: hovered.x + 'px', top: hovered.y + 'px' }" role="status">
        <strong>{{ hovered.well.wellName }}{{ comparisonType === 'method' ? ' · ' + hovered.well.label : '' }}</strong>
        <template v-if="comparisonType === 'direction'">
          <div v-for="value in hovered.well.values" :key="value.key" class="direction-value" :class="{ active: value.key === hovered.well.key }">
            <span><i :style="{ background: value.color }" />{{ value.label }}</span>
            <b>{{ value.available ? format(value.averageOpenFlow) : '无数据' }}</b><span>{{ value.recordCount }} 条</span>
          </div>
        </template>
        <template v-else>
          <div>平均无阻流量 <b>{{ hovered.well.available ? format(hovered.well.averageOpenFlow) : '无数据' }}</b></div>
          <div>有效记录 {{ hovered.well.recordCount }} 条</div>
        </template>
        <small>单位：10⁴m³/d</small>
        <div class="tooltip-coordinates">X {{ format(hovered.well.xCoordinate) }} m · Y {{ format(hovered.well.yCoordinate) }} m</div>
      </div>
    </div>
    <div v-if="view === 'table'" class="table-content">
      <div v-if="emptyMessage" class="table-empty" role="status">{{ emptyMessage }}</div>
      <table v-else-if="grouped" class="grouped-table" :style="{ minWidth: (410 + series.length * 240) + 'px' }" aria-label="当前周期各井分组平均无阻流量">
        <thead>
          <tr><th rowspan="2">井名</th><th rowspan="2">X（m）</th><th rowspan="2">Y（m）</th><th v-for="item in series" :key="item.key" colspan="2">{{ item.label }}</th></tr>
          <tr><template v-for="item in series" :key="item.key"><th>平均无阻流量（10⁴m³/d）</th><th>记录（条）</th></template></tr>
        </thead>
        <tbody><tr v-for="well in wells" :key="well.wellId"><td>{{ well.wellName }}<small v-if="well.coordinateIssue" class="coordinate-issue">{{ well.coordinateIssue }}</small></td><td>{{ format(well.xCoordinate) }}</td><td>{{ format(well.yCoordinate) }}</td>
          <template v-for="value in well.values" :key="value.key"><td>{{ value.available ? format(value.averageOpenFlow) : '无数据' }}</td><td>{{ value.recordCount }}</td></template>
        </tr></tbody>
      </table>
      <table v-else aria-label="当前周期各井平均无阻流量">
        <thead><tr><th>井名</th><th>X（m）</th><th>Y（m）</th><th>平均无阻流量（10⁴m³/d）</th><th>有效记录（条）</th></tr></thead>
        <tbody><tr v-for="well in wells" :key="well.wellId"><td>{{ well.wellName }}<small v-if="well.coordinateIssue" class="coordinate-issue">{{ well.coordinateIssue }}</small></td><td>{{ format(well.xCoordinate) }}</td><td>{{ format(well.yCoordinate) }}</td><td>{{ well.available ? format(well.averageOpenFlow) : '无数据' }}</td><td>{{ well.recordCount }}</td></tr></tbody>
      </table>
    </div>
    <div class="chart-footer">
      <span v-if="!grouped" class="legend"><i />平均无阻流量 <i class="no-data" />无数据</span>
      <span v-else class="legend"><i class="no-data" />— 无数据</span>
      <span v-if="period" class="period-summary">{{ filteredChart ? '当前显示 · ' : '' }}有效井 {{ validCount }} / {{ wells.length }} · 有效记录 {{ recordCount }} 条</span>
      <span v-if="view === 'chart'" class="interaction-hint">井位 {{ plottedWells.length }}/{{ wells.length }} · {{ grouped ? '分组柱围绕真实井位错开 · ' : '' }}拖动旋转 · 滚轮缩放</span>
    </div>
  </section>
</template>

<style scoped>
.period-chart { display: flex; flex: 1; flex-direction: column; min-height: 0; min-width: 0; background: #fff; color: #303133; font: 13px/1.5 "Microsoft YaHei", sans-serif; }
.chart-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; padding: 9px 14px; border-bottom: 1px solid #e4e7ed; }
.period-picker, .chart-actions { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
.chart-toolbar button, .chart-toolbar select, .chart-message button { height: 28px; border: 1px solid #d5d7da; border-radius: 3px; background: #fff; padding: 0 9px; color: #333; font: inherit; cursor: pointer; }
.period-picker label { margin-right: 3px; }
.period-picker button { font: 21px/24px Arial, sans-serif; min-width: 28px; }
.period-picker select { min-width: 104px; }
.period-dates { margin-left: 5px; font-size: 12px; color: #555; font-variant-numeric: tabular-nums; }
.chart-toolbar button:hover:not(:disabled) { border-color: #b5a044; background: #fffcef; }
.chart-toolbar button.selected { background: #fff8cf; border-color: #dcc342; }
.chart-toolbar :disabled { color: #b2b2b2; background: #fafafa; cursor: not-allowed; }
button:focus-visible, select:focus-visible { outline: 2px solid #a58e00; outline-offset: 1px; }
.chart-heading { flex-shrink: 0; padding: 13px 12px 2px; text-align: center; }
.chart-heading h3 { font-size: 14px; color: #333; margin: 0 0 3px; font-weight: 600; }
.chart-heading p { margin: 0; min-height: 18px; color: #777; font-size: 12px; }
.series-legend { flex-shrink: 0; display: flex; flex-wrap: wrap; justify-content: center; gap: 3px 18px; padding: 6px 16px 0; }
.series-legend button { display: inline-flex; align-items: center; gap: 6px; padding: 3px 5px; border: 0; background: transparent; color: #555; font: inherit; font-size: 12px; cursor: pointer; }
.series-legend .series-key { display: inline-flex; align-items: center; gap: 6px; padding: 3px 5px; color: #555; font-size: 12px; }
.series-legend button.muted { opacity: .4; text-decoration: line-through; }
.series-legend button:disabled { cursor: default; }
.series-legend i, .direction-value i { display: inline-block; width: 10px; height: 10px; flex-shrink: 0; }
.chart-content { flex: 1; position: relative; min-height: 230px; overflow: hidden; }
.canvas-host { width: 100%; height: 100%; position: absolute; inset: 0; }
.canvas-host :deep(canvas) { display: block; width: 100%; height: 100%; touch-action: none; }
.axis-title { position: absolute; left: 8px; top: 50%; writing-mode: vertical-rl; transform: translateY(-50%) rotate(180deg); color: #666; font-size: 12px; pointer-events: none; }
.chart-labels { position: absolute; inset: 0; pointer-events: none; }
.well-label { position: absolute; transform: translate(-50%, -50%); max-width: 140px; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; font: 13px/20px "Microsoft YaHei", sans-serif; color: #343d4e; text-shadow: 0 1px 2px #fff; }
.well-label.missing { color: #999; }
.missing-label { position: absolute; transform: translate(-50%, -50%); font-size: 12px; color: #777; text-shadow: 0 1px 2px #fff; }
.tick-label { position: absolute; transform: translate(-100%, -50%); padding-right: 4px; color: #586172; font-size: 12px; white-space: nowrap; }
.tick-x { transform: translate(-50%, 0); font-size: 11px; }
.tick-y { transform: translate(0, -50%); font-size: 11px; }
.spatial-toolbar { flex-shrink: 0; padding: 8px 14px; border-bottom: 1px solid #e4e7ed; display: flex; flex-direction: column; gap: 7px; max-height: 210px; overflow: auto; }
.method-spatial-toolbar { flex-direction: row; flex-wrap: wrap; align-items: center; gap: 6px 12px; max-height: none; padding-top: 7px; padding-bottom: 7px; }
.method-spatial-toolbar .map-mode-row, .method-spatial-toolbar :deep(.basemap-controls) { flex: 0 0 auto; }
.method-spatial-toolbar .map-description { flex: 0 0 auto; margin-left: auto; }
.method-spatial-toolbar .spatial-warning { flex: 1 0 100%; }
.map-mode-row { display: flex; align-items: center; flex-wrap: wrap; gap: 7px 12px; }
.map-mode-row button, .map-mode-row select { height: 27px; border: 1px solid #d5d7da; border-radius: 3px; background: #fff; padding: 0 9px; color: #333; font: inherit; cursor: pointer; }
.map-mode-row button.selected { background: #fff8cf; border-color: #dcc342; }
.map-mode-row button:disabled { color: #aaa; cursor: not-allowed; }
.map-mode-row label { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; }
.map-mode-row input { accent-color: #d1ad00; }
.map-description { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 20px; font-size: 12px; color: #69757e; }
.map-description.simulated { color: #9a751e; }
.map-color-legend { display: inline-flex; align-items: center; gap: 6px; }
.map-color-legend i { display: inline-block; width: 130px; height: 10px; border: 1px solid #d8dde1; }
.spatial-warning { font-size: 12px; color: #9b642c; overflow-wrap: anywhere; }
.tooltip-coordinates { font-size: 11px; color: #777; margin-top: 4px; }
.coordinate-issue { display: block; font-size: 11px; color: #ad742e; }
.hover-leader { position: absolute; inset: 0; width: 100%; height: 100%; overflow: hidden; pointer-events: none; z-index: 2; }
.hover-leader line { stroke: #656c78; stroke-width: 1; }
.hover-leader circle { fill: #30343a; }
.chart-tooltip { position: absolute; z-index: 3; box-sizing: border-box; width: 240px; max-width: calc(100% - 16px); padding: 10px 13px; border: 1px solid #bec4ce; border-radius: 5px; box-shadow: 0 4px 14px #28354a18; background: #fff; pointer-events: none; font-size: 12px; color: #343d4e; }
.chart-tooltip strong { display: block; margin-bottom: 6px; overflow-wrap: anywhere; font-size: 13px; }
.chart-tooltip b { margin-left: 10px; font-weight: 600; }
.chart-tooltip small { color: #888; }
.direction-value { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin: 3px 0; }
.direction-value span:first-child { display: inline-flex; align-items: center; gap: 5px; }
.direction-value b { flex: 1; text-align: right; margin-left: 0; }
.direction-value span:last-child { width: 34px; text-align: right; color: #888; }
.direction-value.active { color: #111; }
.chart-message { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; padding: 30px; text-align: center; color: #888; background: #ffffffdb; }
.empty-period { position: absolute; top: 10px; left: 50%; transform: translateX(-50%); background: #ffffffdf; color: #888; font-size: 12px; text-align: center; }
.chart-footer { flex-shrink: 0; padding: 8px 16px 12px; display: flex; flex-wrap: wrap; justify-content: space-between; gap: 7px 18px; color: #888; font-size: 12px; }
.legend { display: inline-flex; align-items: center; gap: 7px; color: #666; }
.legend i { width: 10px; height: 10px; background: #e4b900; display: inline-block; }
.legend .no-data { margin-left: 10px; background: #b7bbc0; }
.table-content { flex: 1; min-height: 0; overflow: auto; margin: 16px; border: 1px solid #e1e1e1; }
table { width: 100%; border-collapse: collapse; text-align: left; font-variant-numeric: tabular-nums; }
th { position: sticky; top: 0; background: #f6f6f6; font-weight: 500; }
th, td { padding: 9px 16px; border-bottom: 1px solid #eee; }
th:not(:first-child), td:not(:first-child) { text-align: right; }
tbody tr:hover { background: #fffcef; }
.grouped-table thead tr:first-child th { height: 38px; padding-top: 0; padding-bottom: 0; text-align: center; z-index: 2; }
.grouped-table thead tr:nth-child(2) th { top: 39px; text-align: right; }
.grouped-table th, .grouped-table td { white-space: nowrap; border-right: 1px solid #eee; }
.grouped-table tbody td:first-child, .grouped-table th[rowspan]:first-child { position: sticky; left: 0; background: #fafafa; }
.grouped-table th[rowspan]:first-child { z-index: 3; }
.table-empty { padding: 40px 16px; text-align: center; color: #888; }
@media (max-width: 1100px) { .chart-toolbar { padding: 7px 10px; } .chart-actions { margin-left: auto; } .interaction-hint { display: none; } }
</style>
