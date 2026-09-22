<script setup>
/** 库级三种对比的共用绘图组件：一次一周期，按井分组。各业务页仍独立管理参数和请求。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js'
import { ElMessage } from 'element-plus'
import { comparisonMethods, pressureForms } from '@/utils/productivityComparisonChart'

const props = defineProps({ result: { type: Object, default: null }, busy: Boolean, title: { type: String, default: '' },
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
const visibleSeries = computed(() => series.value.filter(item => !hiddenSeries.value.includes(item.key)))
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
  return (props.result?.wells || []).map(well => {
    const cells = new Map(cellsOf(values.get(String(well.wellId))).map(cell => [keyOf(cell), cell]))
    const means = series.value.map(item => {
      const mean = cells.get(item.key)
      const available = Number.isFinite(mean?.averageOpenFlow) && mean.averageOpenFlow >= 0 && mean.recordCount > 0
      return { ...item, available, averageOpenFlow: available ? mean.averageOpenFlow : null, recordCount: available ? mean.recordCount : 0 }
    })
    return { ...means[0], wellId: well.wellId, wellName: well.wellName, values: means,
      available: means.some(mean => mean.available && !hiddenSeries.value.includes(mean.key)) }
  })
})
// 一个实例对应“井＋方法/方向”，井名则只在整组前方标一次。
const points = computed(() => wells.value.flatMap((well, wellIndex) => visibleSeries.value.map((item, seriesIndex) => ({
  ...well, ...well.values.find(value => value.key === item.key), wellIndex, seriesIndex
}))))
const filteredChart = computed(() => view.value === 'chart' && hiddenSeries.value.length > 0)
const validCount = computed(() => !filteredChart.value && period.value?.validWellCount != null ? period.value.validWellCount
  : wells.value.filter(well => well.values.some(value => value.available && (!filteredChart.value || !hiddenSeries.value.includes(value.key)))).length)
const recordCount = computed(() => !filteredChart.value && period.value?.recordCount != null ? period.value.recordCount
  : wells.value.reduce((total, well) => total + well.values.reduce((sum, value) => sum
    + ((!filteredChart.value || !hiddenSeries.value.includes(value.key)) ? value.recordCount : 0), 0), 0))
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
const emptyMessage = computed(() => props.busy ? '正在计算…' : !props.result ? '请选择井并计算'
  : !periods.value.length ? '暂无可展示的周期' : !wells.value.length ? '当前周期暂无参与对比的井' : '')

let renderer, scene, camera, controls, group, bars, outline, keyLight, observer, environmentTarget
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
      if (object.material) for (const material of Array.isArray(object.material) ? object.material : [object.material]) material.dispose()
      if (object.isInstancedMesh) object.dispose()
    })
  }
  group = null; bars = null; outline = null; positions = []; wellPositions = []; ticks = []
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
  for (const [index, position] of wellPositions.entries()) {
    const text = wells.value[index]?.wellName || ''
    const labelWidth = Math.min(140, Math.max(48, text.length * 7 + 12))
    // 分组较宽时可在同一井格内侧移井名，避免后排名字被前排柱体遮住。
    for (const candidate of [position.label, ...(position.alternatives || [])]) {
      const point = project(candidate)
      if (!point.visible || point.x < labelWidth / 2 || point.x > width - labelWidth / 2 || point.y < 12 || point.y > height - 18) continue
      if (bars && points.value.length <= 100) {
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
      visible.push({ ...point, index, text, missing: !wells.value[index]?.available })
      break
    }
  }
  labels.value = visible
  axisLabels.value = ticks.map(tick => ({ ...project(tick.position), text: tick.text }))
  missingLabels.value = grouped.value && points.value.length <= 100 ? positions.flatMap((position, index) => {
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
  for (const x of [-planeWidth / 2, planeWidth / 2])
    for (const y of [-.25, axisHeight]) for (const z of [-planeDepth / 2, planeDepth / 2]) {
      const point = new THREE.Vector3(x, y, z).applyMatrix4(camera.matrixWorldInverse)
      minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x)
      minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y)
    }
  // 按投影后的实际边界居中，边距用像素保留给刻度/井名，避免宽屏下仍缩成一小块。
  const left = Math.max(88, Math.min(120, width * .065)), right = Math.max(30, Math.min(96, width * .06))
  const top = Math.max(24, height * .06), bottom = Math.max(36, height * .08)
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
  controls.target.set(0, axisHeight * .36, 0)
  const elevation = grouped.value ? (visibleSeries.value.length >= 3 ? 1.5 : 1.1) : .72
  camera.position.set(span * .95, span * elevation, span * 1.65)
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
  // 拖动参数栏、改变窗口大小时同时重排底面，不仅缩放原来的正方形场景。
  if (group) renderData()
}
const addLines = (points, color) => {
  const geometry = new THREE.BufferGeometry().setFromPoints(points.map(point => new THREE.Vector3(...point)))
  group.add(new THREE.LineSegments(geometry, new THREE.LineBasicMaterial({ color, toneMapped: false })))
}
const renderData = (reset = false) => {
  if (!renderer || !scene || renderError.value) return
  clearGeometry()
  group = new THREE.Group(); scene.add(group)
  const count = wells.value.length
  const aspect = Math.max(.8, Math.min(2.4, (width - 100) / Math.max(1, height - 80)))
  // 少井横向排开，多井保留有纵深的方阵，不把所有井挤成长条；周期间井位固定。
  let columns = count <= 4 && aspect >= 1.35 ? Math.max(1, count)
    : Math.max(1, Math.ceil(Math.sqrt(count * Math.min(1.15, aspect))))
  const rows = Math.max(1, Math.ceil(count / columns))
  // 不增加行数的前提下压紧列数，避免末行只剩一根柱、大半底面空着。
  while (columns > 1 && Math.ceil(count / (columns - 1)) === rows) columns--
  const seriesCount = Math.max(1, visibleSeries.value.length)
  planeWidth = Math.max(columns * Math.max(3.8, seriesCount * 1.25 + 1.2), aspect * 7)
  // 分组柱占用更宽的前景，增加行间纵深，给后排井名留出可见的底面。
  planeDepth = Math.max(5.2, rows * (grouped.value ? 4.5 : 2.8))
  axisHeight = Math.max(4.2, planeWidth * .34)
  const cellWidth = planeWidth / columns, cellDepth = planeDepth / rows
  const barSide = Math.min(1.2, cellWidth * (grouped.value ? .76 / (seriesCount + (seriesCount - 1) * .18) : .32), cellDepth * .44)
  // 细薄底板和轻微灰阶过渡提供空间层次，坐标零面与柱高映射保持不变。
  const floorGeometry = new THREE.PlaneGeometry(planeWidth, planeDepth, 1, 8)
  const floorVertices = floorGeometry.getAttribute('position'), floorColors = new Float32Array(floorVertices.count * 3)
  for (let index = 0; index < floorVertices.count; index++) {
    const ratio = (floorVertices.getY(index) / planeDepth + .5)
    const color = new THREE.Color('#e3e5e8').lerp(new THREE.Color('#f3f4f5'), ratio)
    floorColors.set([color.r, color.g, color.b], index * 3)
  }
  floorGeometry.setAttribute('color', new THREE.BufferAttribute(floorColors, 3))
  const floor = new THREE.Mesh(floorGeometry,
    new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.DoubleSide, toneMapped: false }))
  floor.rotation.x = -Math.PI / 2; floor.position.y = -.03; group.add(floor)
  const base = new THREE.Mesh(new THREE.BoxGeometry(planeWidth, .045, planeDepth),
    new THREE.MeshBasicMaterial({ color: '#c8ccd1', toneMapped: false }))
  base.position.y = -.057; group.add(base)
  // 底面与柔影分层：保持白灰配色，不让灯光把整块底面照成白色。
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(planeWidth, planeDepth),
    new THREE.ShadowMaterial({ color: '#353c48', opacity: .13, depthWrite: false, toneMapped: false }))
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = -.018; shadow.receiveShadow = true; group.add(shadow)
  const backWall = new THREE.Mesh(new THREE.PlaneGeometry(planeWidth, axisHeight),
    new THREE.MeshBasicMaterial({ color: '#fafbfd', toneMapped: false }))
  backWall.position.set(0, axisHeight / 2, -planeDepth / 2 - .02); group.add(backWall)
  const sideWall = new THREE.Mesh(new THREE.PlaneGeometry(planeDepth, axisHeight),
    new THREE.MeshBasicMaterial({ color: '#fcfcfd', toneMapped: false }))
  sideWall.rotation.y = Math.PI / 2; sideWall.position.set(-planeWidth / 2 - .02, axisHeight / 2, 0); group.add(sideWall)
  const grid = []
  for (let column = 0; column <= columns; column++) {
    const x = -planeWidth / 2 + column * planeWidth / columns
    grid.push([x, 0, -planeDepth / 2], [x, 0, planeDepth / 2])
  }
  for (let row = 0; row <= rows; row++) {
    const z = -planeDepth / 2 + row * planeDepth / rows
    grid.push([-planeWidth / 2, 0, z], [planeWidth / 2, 0, z])
  }
  addLines(grid, '#cdd1d6')
  const axisX = -planeWidth / 2, axisZ = planeDepth / 2
  const axes = [[axisX, 0, axisZ], [axisX, axisHeight, axisZ],
    [axisX, 0, axisZ], [planeWidth / 2, 0, axisZ],
    [planeWidth / 2, 0, axisZ], [planeWidth / 2, 0, -planeDepth / 2]]
  const wall = []
  const tickCount = [5, 6, 4].find(count => {
    const step = maximum.value / count, unit = step / 10 ** Math.floor(Math.log10(step))
    return [1, 2, 2.5, 5, 10].some(nice => Math.abs(unit - nice) < 1e-8)
  }) || 5
  for (let index = 0; index <= tickCount; index++) {
    const y = axisHeight * index / tickCount, value = maximum.value * (index / tickCount)
    wall.push([axisX, y, axisZ], [axisX, y, -planeDepth / 2], [axisX, y, -planeDepth / 2], [planeWidth / 2, y, -planeDepth / 2])
    axes.push([axisX - .12, y, axisZ], [axisX, y, axisZ])
    ticks.push({ position: new THREE.Vector3(axisX - .3, y, axisZ),
      text: value !== 0 && (value >= 1e7 || value < .01) ? value.toExponential(1) : format(value) })
  }
  // 两侧的竖网格与底面格子对齐；密集井仍限制网格数量，避免背景变成密纹。
  const columnStep = Math.max(1, Math.ceil(columns / 12)), rowStep = Math.max(1, Math.ceil(rows / 12))
  for (let column = 0; column <= columns; column += columnStep) {
    const x = axisX + column * cellWidth
    wall.push([x, 0, -planeDepth / 2], [x, axisHeight, -planeDepth / 2])
  }
  for (let row = 0; row <= rows; row += rowStep) {
    const z = -planeDepth / 2 + row * cellDepth
    wall.push([axisX, 0, z], [axisX, axisHeight, z])
  }
  addLines(wall, '#d9dde3'); addLines(axes, '#7b8189')
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
    bars.castShadow = true
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
    wells.value.forEach((well, index) => {
      const x = (index % columns - (columns - 1) / 2) * cellWidth
      const z = ((rows - 1) / 2 - Math.floor(index / columns)) * cellDepth
      const labelZ = z + barSide / 2 + .4
      wellPositions.push({ label: new THREE.Vector3(x, -.12, labelZ), alternatives: grouped.value
        ? [-1, 1].map(side => new THREE.Vector3(x + side * cellWidth * .42, -.12, labelZ)) : [] })
    })
    points.value.forEach((point, index) => {
      const x = (point.wellIndex % columns - (columns - 1) / 2) * cellWidth
        + (point.seriesIndex - (seriesCount - 1) / 2) * barSide * 1.18
      const z = ((rows - 1) / 2 - Math.floor(point.wellIndex / columns)) * cellDepth
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
  const hit = raycaster.intersectObject(bars)[0]
  const index = hit?.instanceId
  if (index == null || !positions[index] || !points.value[index]) return clearHover()
  if (activeIndex !== index) {
    clearHover(); activeIndex = index
    bars.setColorAt(index, grouped.value ? positions[index].color.clone().lerp(new THREE.Color('#ffffff'), .22) : highlight)
    bars.instanceColor.needsUpdate = true
    outline.matrix.copy(positions[index].matrix); outline.visible = true
  }
  const anchor = project(positions[index].top)
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
    controls.minPolarAngle = .12; controls.maxPolarAngle = Math.PI / 2 - .08
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
    const headerHeight = legendItems.length ? legendY + 16 : 90
    canvas.width = width * scale; canvas.height = (height + headerHeight + 36) * scale
    context.scale(scale, scale)
    context.fillStyle = '#fff'; context.fillRect(0, 0, width, height + headerHeight + 36)
    context.fillStyle = '#222'; context.textAlign = 'center'; context.font = 'bold 15px "Microsoft YaHei", sans-serif'
    context.fillText(props.title, width / 2, 25)
    context.font = '13px "Microsoft YaHei", sans-serif'; context.fillText(periodText.value, width / 2, 50)
    context.fillStyle = '#666'; context.fillText(subtitle.value, width / 2, 74)
    context.font = '12px "Microsoft YaHei", sans-serif'; context.textAlign = 'left'
    for (const item of legendItems) {
      context.fillStyle = item.color; context.fillRect(item.x, item.y - 9, 10, 10)
      context.fillStyle = '#555'; context.fillText(item.label, item.x + 16, item.y)
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
    for (const tick of axisLabels.value) if (tick.visible) context.fillText(tick.text, tick.x - 4, tick.y + headerHeight + 4)
    context.textAlign = 'left'; context.fillText('平均无阻流量（10⁴m³/d）', 12, height + headerHeight + 25)
    context.textAlign = 'right'; context.fillText(`${filteredChart.value ? '当前显示 · ' : ''}有效井 ${validCount.value}/${wells.value.length} · 有效记录 ${recordCount.value} 条`, width - 12, height + headerHeight + 25)
    const link = document.createElement('a')
    link.download = `${props.title}-第${period.value.index}周期.png`.replace(/[\\/:*?"<>|]/g, '-')
    link.href = canvas.toDataURL('image/png'); link.click()
  } catch { ElMessage.error('图片导出失败，请重置视角后重试') }
}
watch([() => props.result, () => props.comparisonType], () => {
  periodOffset.value = 0; hiddenSeries.value = []; clearHover()
  renderer?.domElement.setAttribute('aria-label', chartTitle.value)
  nextTick(() => renderData(true))
})
watch(periodOffset, () => { clearHover(); nextTick(() => renderData()) })
watch(hiddenSeries, () => { clearHover(); nextTick(() => renderData()) })
watch(view, () => { clearHover(); nextTick(resize) })
onMounted(initialize)
onBeforeUnmount(() => {
  disposed = true; cancelAnimationFrame(frame); observer?.disconnect()
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
        <button type="button" :disabled="view !== 'chart' || !!renderError" @click="resetView">重置视角</button>
        <button type="button" :disabled="!period || view !== 'chart' || !!renderError" @click="exportImage">导出图片</button>
      </div>
    </div>
    <div class="chart-heading"><h3>{{ chartTitle }}</h3><p>{{ subtitle || (grouped ? '每口井一组柱，按周期查看' : '每口井一根柱，按周期查看') }}</p></div>
    <div v-if="grouped && view === 'chart' && series.length" class="series-legend" aria-label="显示系列">
      <button v-for="item in series" :key="item.key" type="button" :aria-pressed="!hiddenSeries.includes(item.key)"
        :class="{ muted: hiddenSeries.includes(item.key) }" :disabled="!hiddenSeries.includes(item.key) && visibleSeries.length === 1"
        :title="hiddenSeries.includes(item.key) ? '显示' + item.label : visibleSeries.length === 1 ? '至少保留一个系列' : '隐藏' + item.label" @click="toggleSeries(item.key)">
        <i :style="{ background: item.color }" />{{ item.label }}
      </button>
    </div>
    <div v-show="view === 'chart'" class="chart-content">
      <div ref="host" class="canvas-host" />
      <span class="axis-title">平均无阻流量（10⁴m³/d）</span>
      <div class="chart-labels" aria-hidden="true">
        <span v-for="label in labels" :key="label.index" class="well-label" :class="{ missing: label.missing }" :style="{ left: label.x + 'px', top: label.y + 'px' }">{{ label.text }}</span>
        <span v-for="(tick, index) in axisLabels" v-show="tick.visible" :key="'tick-' + index" class="tick-label" :style="{ left: tick.x + 'px', top: tick.y + 'px' }">{{ tick.text }}</span>
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
      </div>
    </div>
    <div v-if="view === 'table'" class="table-content">
      <div v-if="emptyMessage" class="table-empty" role="status">{{ emptyMessage }}</div>
      <table v-else-if="grouped" class="grouped-table" :style="{ minWidth: (150 + series.length * 240) + 'px' }" aria-label="当前周期各井分组平均无阻流量">
        <thead>
          <tr><th rowspan="2">井名</th><th v-for="item in series" :key="item.key" colspan="2">{{ item.label }}</th></tr>
          <tr><template v-for="item in series" :key="item.key"><th>平均无阻流量（10⁴m³/d）</th><th>记录（条）</th></template></tr>
        </thead>
        <tbody><tr v-for="well in wells" :key="well.wellId"><td>{{ well.wellName }}</td>
          <template v-for="value in well.values" :key="value.key"><td>{{ value.available ? format(value.averageOpenFlow) : '无数据' }}</td><td>{{ value.recordCount }}</td></template>
        </tr></tbody>
      </table>
      <table v-else aria-label="当前周期各井平均无阻流量">
        <thead><tr><th>井名</th><th>平均无阻流量（10⁴m³/d）</th><th>有效记录（条）</th></tr></thead>
        <tbody><tr v-for="well in wells" :key="well.wellId"><td>{{ well.wellName }}</td><td>{{ well.available ? format(well.averageOpenFlow) : '无数据' }}</td><td>{{ well.recordCount }}</td></tr></tbody>
      </table>
    </div>
    <div class="chart-footer">
      <span v-if="!grouped" class="legend"><i />平均无阻流量 <i class="no-data" />无数据</span>
      <span v-else class="legend"><i class="no-data" />— 无数据</span>
      <span v-if="period" class="period-summary">{{ filteredChart ? '当前显示 · ' : '' }}有效井 {{ validCount }} / {{ wells.length }} · 有效记录 {{ recordCount }} 条</span>
      <span v-if="view === 'chart'" class="interaction-hint">拖动旋转 · 滚轮缩放</span>
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
.grouped-table tbody td:first-child, .grouped-table th[rowspan] { position: sticky; left: 0; background: #fafafa; }
.grouped-table th[rowspan] { z-index: 3; }
.table-empty { padding: 40px 16px; text-align: center; color: #888; }
@media (max-width: 1100px) { .chart-toolbar { padding: 7px 10px; } .chart-actions { margin-left: auto; } .interaction-hint { display: none; } }
</style>
