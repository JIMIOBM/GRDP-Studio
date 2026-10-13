<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'

const props = defineProps({
  result: { type: Object, default: null }
})

const canvasHost = ref(null)
const projection = ref('3d')
const selectedIndex = ref(0)
const renderError = ref('')
const ready = ref(false)

let scene
let camera
let renderer
let controls
let animationFrame
let resizeObserver
let trajectoryGroup
let initialCamera

const points = computed(() => Array.isArray(props.result?.points) ? props.result.points : [])
const hasAnyAzimuth = computed(() => points.value.some(point => typeof point.azimuth === 'number' && Number.isFinite(point.azimuth)))
const hasSpatialAzimuth = computed(() => points.value.length >= 2 && points.value.slice(1).every((point, index) => {
  const previous = points.value[index]
  return [point?.azimuth, previous?.azimuth].some(value => typeof value === 'number' && Number.isFinite(value))
}))
const selectedPoint = computed(() => points.value[selectedIndex.value] || points.value[0] || null)
const trajectoryApplication = computed(() => props.result?.schemaVersion === 'pipesim-well-trajectory-result/2' ? props.result.application : null)
const profileComparison = computed(() => props.result?.schemaVersion === 'pipesim-well-trajectory-result/2' ? props.result.profileComparison : null)
const ambiguousComparisonDepthCount = computed(() => {
  const comparison = profileComparison.value
  if (!comparison) return 0
  const ambiguous = new Set()
  for (const curve of [comparison.baseline, comparison.target]) {
    const counts = new Map()
    for (const point of curve) {
      const key = Math.round(point.depth * 1e6)
      counts.set(key, (counts.get(key) || 0) + 1)
    }
    for (const [key, count] of counts) if (count > 1) ambiguous.add(key)
  }
  return ambiguous.size
})
const profileCharts = computed(() => {
  const comparison = profileComparison.value
  if (!comparison) return null
  const depths = [...comparison.baseline, ...comparison.target].map(point => point.depth)
  const depthMin = Math.min(...depths)
  const depthMax = Math.max(...depths)
  const build = field => {
    const values = [...comparison.baseline, ...comparison.target].map(point => point[field])
    const min = Math.min(...values)
    const max = Math.max(...values)
    const x = depth => 42 + (depthMax === depthMin ? 0.5 : (depth - depthMin) / (depthMax - depthMin)) * 730
    const y = value => 190 - (max === min ? 0.5 : (value - min) / (max - min)) * 160
    return {
      min, max,
      baseline: comparison.baseline.map(point => `${x(point.depth)},${y(point[field])}`).join(' '),
      target: comparison.target.map(point => `${x(point.depth)},${y(point[field])}`).join(' ')
    }
  }
  return { depthMin, depthMax, pressure: build('pressure'), temperature: build('temperature') }
})
const coordinates = computed(() => {
  let x = 0
  let y = 0
  const values = []
  points.value.forEach((point, index) => {
    if (index > 0 && hasSpatialAzimuth.value) {
      const previous = points.value[index - 1]
      const measuredDelta = Math.max(0, point.measuredDepth - previous.measuredDepth)
      const verticalDelta = Math.max(0, point.trueVerticalDepth - previous.trueVerticalDepth)
      const horizontalDelta = Math.sqrt(Math.max(0, measuredDelta ** 2 - verticalDelta ** 2))
      const azimuth = typeof point.azimuth === 'number' ? point.azimuth : previous.azimuth
      const azimuthRadians = azimuth * Math.PI / 180
      x += horizontalDelta * Math.sin(azimuthRadians)
      y += horizontalDelta * Math.cos(azimuthRadians)
    }
    values.push(hasSpatialAzimuth.value
      ? { ...point, x, y, z: -point.trueVerticalDepth }
      : { ...point, x: null, y: null, z: null, profileX: point.measuredDepth, profileY: -point.trueVerticalDepth })
  })
  return values
})

const fmt = value => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(3) : '—'

const dispose = object => object?.traverse(child => {
  child.geometry?.dispose()
  if (Array.isArray(child.material)) child.material.forEach(material => material.dispose())
  else child.material?.dispose()
})

const clearScene = () => {
  if (!trajectoryGroup || !scene) return
  scene.remove(trajectoryGroup)
  dispose(trajectoryGroup)
  trajectoryGroup = null
}

const viewVector = () => {
  if (projection.value === 'top') return new THREE.Vector3(0, 1, 0)
  if (projection.value === 'side') return new THREE.Vector3(1, 0.35, 0)
  return new THREE.Vector3(1.2, 0.9, 1.4)
}

const resetCamera = () => {
  if (!camera || !controls || !initialCamera) return
  camera.position.copy(initialCamera.position)
  controls.target.copy(initialCamera.target)
  controls.update()
}

const renderTrajectory = () => {
  if (!scene || !camera || !renderer) return
  clearScene()
  ready.value = false
  renderError.value = ''
  if (coordinates.value.length < 2) {
    renderError.value = '官方轨迹点不足 2 个，无法生成三维折线。'
    return
  }
  const group = new THREE.Group()
  const linePoints = coordinates.value.map(point => hasSpatialAzimuth.value
    ? new THREE.Vector3(point.x, point.z, point.y)
    : new THREE.Vector3(point.profileX, point.profileY, 0))
  const geometry = new THREE.BufferGeometry().setFromPoints(linePoints)
  group.add(new THREE.Line(geometry, new THREE.LineBasicMaterial({ color: 0x2563eb, linewidth: 3 })))
  const markerGeometry = new THREE.BufferGeometry().setFromPoints(linePoints)
  group.add(new THREE.Points(markerGeometry, new THREE.PointsMaterial({ color: 0xf97316, size: 8, sizeAttenuation: false })))
  const bounds = new THREE.Box3().setFromPoints(linePoints)
  const center = bounds.getCenter(new THREE.Vector3())
  const size = bounds.getSize(new THREE.Vector3())
  const span = Math.max(size.x, size.y, size.z, 1)
  group.add(new THREE.AxesHelper(span * 0.35))
  scene.add(group)
  trajectoryGroup = group
  controls.target.copy(center)
  const direction = (hasSpatialAzimuth.value ? viewVector() : new THREE.Vector3(0, 0, 1)).normalize()
  camera.position.copy(center.clone().add(direction.multiplyScalar(span * 2.2)))
  camera.near = Math.max(span / 10000, 0.01)
  camera.far = Math.max(span * 20, 1000)
  camera.updateProjectionMatrix()
  controls.enableRotate = hasSpatialAzimuth.value
  controls.update()
  initialCamera = { position: camera.position.clone(), target: center.clone() }
  ready.value = true
}

const resize = () => {
  if (!canvasHost.value || !camera || !renderer) return
  const width = Math.max(320, canvasHost.value.clientWidth)
  const height = Math.max(260, canvasHost.value.clientHeight)
  camera.aspect = width / height
  camera.updateProjectionMatrix()
  renderer.setSize(width, height, false)
}

const downloadCsv = () => {
  const header = ['producer', 'measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity', 'coordinateMode', 'derivedX', 'derivedY', 'derivedZ']
  const mode = hasSpatialAzimuth.value ? 'relative-3d-from-azimuth' : 'md-tvd-profile-no-spatial-azimuth'
  const rows = coordinates.value.map(point => [props.result?.producer || '', point.measuredDepth, point.trueVerticalDepth, point.inclination, point.azimuth ?? '', point.maxDogLegSeverity ?? '', mode, point.x ?? '', point.y ?? '', point.z ?? ''])
  const csv = [header, ...rows].map(row => row.map(value => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\n')
  const href = URL.createObjectURL(new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = href
  anchor.download = `${props.result?.producer || 'pipesim-well'}-trajectory.csv`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(href), 0)
}

onMounted(async () => {
  await nextTick()
  if (!canvasHost.value) return
  try {
    scene = new THREE.Scene()
    scene.background = new THREE.Color(0xf8fafc)
    camera = new THREE.PerspectiveCamera(45, 1, 0.01, 100000)
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    canvasHost.value.appendChild(renderer.domElement)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.addEventListener('change', () => renderer.render(scene, camera))
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(canvasHost.value)
    const animate = () => {
      controls?.update()
      renderer?.render(scene, camera)
      animationFrame = requestAnimationFrame(animate)
    }
    animate()
    resize()
    renderTrajectory()
  } catch (error) {
    renderError.value = error?.message || '三维轨迹初始化失败。'
  }
})

watch(() => props.result, () => {
  selectedIndex.value = 0
  nextTick(renderTrajectory)
}, { deep: true })

watch(projection, () => {
  renderTrajectory()
})

onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  resizeObserver?.disconnect()
  controls?.dispose()
  clearScene()
  renderer?.dispose()
})
</script>

<template>
  <section class="trajectory-result" aria-label="PIPESIM 井轨迹结果">
    <div class="result-heading">
      <div>
        <p class="eyebrow">PIPESIM WELL TRAJECTORY</p>
        <h3>{{ result?.producer }} · {{ trajectoryApplication ? '隔离副本倾角方案与 PT Profile 复算' : '官方 get_trajectory 只读结果' }}</h3>
        <p class="muted">轨迹点和单位来自官方 Toolkit；空间坐标仅在每段都有可用方位时才派生。方案编辑保存在 Worker 隔离副本，不写回上传模型。</p>
      </div>
      <div class="heading-actions">
        <el-radio-group v-if="hasSpatialAzimuth" v-model="projection" size="small" aria-label="轨迹视图">
          <el-radio-button label="3d">三维</el-radio-button>
          <el-radio-button label="top">俯视</el-radio-button>
          <el-radio-button label="side">侧视</el-radio-button>
        </el-radio-group>
        <el-tag v-else type="info">仅 MD/TVD 剖面 · 无完整方位</el-tag>
        <el-button size="small" @click="resetCamera">重置视角</el-button>
        <el-button size="small" type="primary" plain @click="downloadCsv">导出轨迹 CSV</el-button>
      </div>
    </div>
    <div class="trajectory-summary">
      <div><span>轨迹点</span><strong>{{ points.length }}</strong><small>点</small></div>
      <div><span>最大 MD</span><strong>{{ fmt(points.at(-1)?.measuredDepth) }}</strong><small>{{ result?.units?.measuredDepth }}</small></div>
      <div><span>最大 TVD</span><strong>{{ fmt(Math.max(...points.map(point => point.trueVerticalDepth), 0)) }}</strong><small>{{ result?.units?.trueVerticalDepth }}</small></div>
      <div><span>方位信息</span><strong>{{ hasSpatialAzimuth ? '可用于空间定位' : (hasAnyAzimuth ? '不完整' : '未提供') }}</strong><small>{{ hasSpatialAzimuth ? result?.units?.azimuth : '不推断绝对 X/Y' }}</small></div>
    </div>
    <div class="trajectory-layout">
      <div class="trajectory-canvas-wrap">
        <div ref="canvasHost" class="trajectory-canvas" :aria-label="hasSpatialAzimuth ? '井轨迹三维图' : 'MD/TVD 井剖面图'"></div>
        <el-alert v-if="renderError" type="warning" :closable="false" :title="renderError" />
        <span v-else-if="!ready" class="loading-hint">正在生成轨迹视图…</span>
        <p v-if="hasSpatialAzimuth" class="canvas-caption">橙色点为官方轨迹站点；显示基于 MD/TVD/方位计算的相对三维坐标，不代表测量得到的绝对井口坐标。拖动旋转，滚轮缩放。</p>
        <p v-else class="canvas-caption">当前仅显示官方 MD–TVD 剖面：横轴 MD、纵轴 TVD；官方未提供完整方位，不生成或暗示空间 X/Y 定位。</p>
      </div>
      <div class="point-table-wrap">
        <div class="table-heading"><strong>轨迹原始点</strong><span>{{ selectedPoint ? `当前第 ${selectedIndex + 1} 点` : '' }}</span></div>
        <el-table :data="points" height="330" size="small" @row-click="row => { selectedIndex = points.indexOf(row) }">
          <el-table-column type="index" label="#" width="52" />
          <el-table-column prop="measuredDepth" label="MD" min-width="90">
            <template #default="scope">{{ fmt(scope.row.measuredDepth) }}</template>
          </el-table-column>
          <el-table-column prop="trueVerticalDepth" label="TVD" min-width="90">
            <template #default="scope">{{ fmt(scope.row.trueVerticalDepth) }}</template>
          </el-table-column>
          <el-table-column prop="inclination" label="倾角" min-width="72">
            <template #default="scope">{{ fmt(scope.row.inclination) }}</template>
          </el-table-column>
          <el-table-column prop="azimuth" label="方位" min-width="72">
            <template #default="scope">{{ fmt(scope.row.azimuth) }}</template>
          </el-table-column>
        </el-table>
        <div class="selected-point" v-if="selectedPoint">
          <template v-if="hasSpatialAzimuth">
            <span>选中点相对派生坐标</span>
            <strong>X {{ fmt(coordinates[selectedIndex]?.x) }} · Y {{ fmt(coordinates[selectedIndex]?.y) }} · Z {{ fmt(coordinates[selectedIndex]?.z) }}</strong>
          </template>
          <template v-else>
            <span>选中点 MD/TVD 剖面位置</span>
            <strong>MD {{ fmt(selectedPoint.measuredDepth) }} {{ result?.units?.measuredDepth }} · TVD {{ fmt(selectedPoint.trueVerticalDepth) }} {{ result?.units?.trueVerticalDepth }}</strong>
          </template>
        </div>
      </div>
    </div>
    <section v-if="trajectoryApplication && profileComparison && profileCharts" class="trajectory-scenario" aria-label="轨迹方案及剖面对比">
      <div class="scenario-heading">
        <div><p class="eyebrow">PIPESIM PT PROFILE</p><h3>基线与倾角方案的真实计算对比</h3></div>
        <el-tag type="success">隔离副本已保存并重新打开核对</el-tag>
      </div>
      <div class="scenario-facts">
        <span>0 基站点索引 <b>{{ trajectoryApplication.stationIndex }}</b></span>
        <span>倾角 <b>{{ fmt(trajectoryApplication.originalInclinationDeg) }}° → {{ fmt(trajectoryApplication.targetInclinationDeg) }}°</b></span>
        <span>末站 TVD <b>{{ fmt(trajectoryApplication.maxTvdBeforeFt) }} → {{ fmt(trajectoryApplication.maxTvdAfterFt) }} ft</b></span>
        <span>共同深度点 <b>{{ profileComparison.matchedPointCount }} / {{ profileComparison.baselinePointCount }} 基线 · {{ profileComparison.targetPointCount }} 方案</b></span>
      </div>
      <div class="profile-charts">
        <div v-for="metric in ['pressure', 'temperature']" :key="metric" class="profile-chart">
          <strong>{{ metric === 'pressure' ? '压力' : '温度' }}曲线 <small>（Toolkit 原始单位未明确）</small></strong>
          <svg viewBox="0 0 800 230" role="img" :aria-label="`${metric === 'pressure' ? '压力' : '温度'}基线和方案曲线`">
            <line x1="42" y1="30" x2="42" y2="190" stroke="#94a3b8" />
            <line x1="42" y1="190" x2="772" y2="190" stroke="#94a3b8" />
            <line x1="42" y1="110" x2="772" y2="110" stroke="#e2e8f0" />
            <polyline :points="profileCharts[metric].baseline" fill="none" stroke="#64748b" stroke-width="2" />
            <polyline :points="profileCharts[metric].target" fill="none" stroke="#2563eb" stroke-width="2" />
            <text x="42" y="215" fill="#64748b" font-size="12">depth {{ fmt(profileCharts.depthMin) }}</text>
            <text x="700" y="215" fill="#64748b" font-size="12">{{ fmt(profileCharts.depthMax) }}</text>
            <text x="48" y="25" fill="#64748b" font-size="12">{{ fmt(profileCharts[metric].max) }}</text>
            <text x="48" y="185" fill="#64748b" font-size="12">{{ fmt(profileCharts[metric].min) }}</text>
          </svg>
          <div class="chart-legend"><span><i class="baseline-dot" />基线</span><span><i class="target-dot" />方案</span><small>只连接各次运行真实采样点；不插值，不表示两次采用相同网格。</small></div>
        </div>
      </div>
      <div class="comparison-summary">
        <span>同深度点最大 |Δ压力|：<b>{{ fmt(profileComparison.maxAbsPressureDelta) }}</b></span>
        <span>同深度点最大 |Δ温度|：<b>{{ fmt(profileComparison.maxAbsTemperatureDelta) }}</b></span>
        <span>对齐方法：精确深度交集（无插值）</span>
        <span v-if="ambiguousComparisonDepthCount > 0">重复深度坐标 {{ ambiguousComparisonDepthCount }} 组未参与精确配对</span>
      </div>
      <el-table :data="profileComparison.matchedPoints" size="small" max-height="260" aria-label="同深度 Profile 对比">
        <el-table-column prop="depth" label="共同深度" min-width="100"><template #default="scope">{{ fmt(scope.row.depth) }}</template></el-table-column>
        <el-table-column prop="pressureDelta" label="Δ 压力" min-width="100"><template #default="scope">{{ fmt(scope.row.pressureDelta) }}</template></el-table-column>
        <el-table-column prop="temperatureDelta" label="Δ 温度" min-width="100"><template #default="scope">{{ fmt(scope.row.temperatureDelta) }}</template></el-table-column>
      </el-table>
      <p class="muted">派生工程 Artifact：{{ trajectoryApplication.artifactName }}。原始模型 SHA-256 由 Worker 前后复核；若共同深度点不足，差异统计只代表该交集，不推断全曲线误差。</p>
    </section>
  </section>
</template>

<style scoped>
.trajectory-result { padding: 18px; background: #fff; }
.result-heading, .trajectory-layout, .heading-actions, .table-heading { display: flex; gap: 16px; }
.result-heading { justify-content: space-between; align-items: flex-start; }
.heading-actions { align-items: center; flex-wrap: wrap; justify-content: flex-end; }
.eyebrow { margin: 0 0 4px; color: #2563eb; font-size: 12px; letter-spacing: .08em; }
h3 { margin: 0; color: #0f172a; }
.muted, .canvas-caption, .table-heading span { color: #64748b; font-size: 13px; }
.trajectory-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin: 18px 0; }
.trajectory-summary > div { padding: 12px; background: #f1f5f9; border-radius: 6px; }
.trajectory-summary span, .trajectory-summary small { display: block; color: #64748b; }
.trajectory-summary strong { display: inline-block; margin: 4px 6px 0 0; color: #0f172a; font-size: 20px; }
.trajectory-layout { align-items: stretch; }
.trajectory-canvas-wrap { position: relative; flex: 1 1 60%; min-width: 420px; }
.trajectory-canvas { height: 430px; overflow: hidden; border: 1px solid #dbe3ee; border-radius: 8px; }
.trajectory-canvas canvas { display: block; width: 100%; height: 100%; }
.loading-hint { position: absolute; top: 48%; left: 42%; color: #64748b; }
.canvas-caption { margin: 8px 0 0; }
.point-table-wrap { flex: 1 1 40%; min-width: 390px; }
.table-heading { justify-content: space-between; align-items: center; margin-bottom: 8px; }
.selected-point { display: grid; gap: 4px; margin-top: 10px; padding: 10px; background: #eff6ff; color: #1e3a8a; }
.trajectory-scenario { margin-top: 24px; padding: 16px; border: 1px solid #dbe3ee; border-radius: 8px; }
.scenario-heading, .scenario-facts, .chart-legend, .comparison-summary { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 10px 18px; }
.scenario-facts { margin: 14px 0; padding: 12px; background: #f1f5f9; }
.scenario-facts span, .comparison-summary span { color: #64748b; }
.scenario-facts b, .comparison-summary b { color: #0f172a; }
.profile-charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.profile-chart { min-width: 0; }
.profile-chart svg { width: 100%; height: auto; background: #fff; }
.profile-chart > strong small { color: #64748b; font-weight: 400; }
.chart-legend { justify-content: flex-start; color: #64748b; font-size: 12px; }
.chart-legend span { display: inline-flex; align-items: center; gap: 5px; }
.chart-legend small { flex-basis: 100%; }
.chart-legend i { width: 12px; height: 3px; display: inline-block; background: #64748b; }
.chart-legend .target-dot { background: #2563eb; }
.comparison-summary { justify-content: flex-start; margin: 12px 0; }
@media (max-width: 900px) {
  .result-heading, .trajectory-layout { flex-direction: column; }
  .trajectory-canvas-wrap, .point-table-wrap { min-width: 0; }
  .trajectory-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .profile-charts { grid-template-columns: 1fr; }
}
</style>
