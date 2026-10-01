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
const hasAzimuth = computed(() => points.value.some(point => typeof point.azimuth === 'number' && Number.isFinite(point.azimuth)))
const selectedPoint = computed(() => points.value[selectedIndex.value] || points.value[0] || null)
const coordinates = computed(() => {
  let x = 0
  let y = 0
  const values = []
  points.value.forEach((point, index) => {
    if (index > 0) {
      const previous = points.value[index - 1]
      const measuredDelta = Math.max(0, point.measuredDepth - previous.measuredDepth)
      const verticalDelta = Math.max(0, point.trueVerticalDepth - previous.trueVerticalDepth)
      const horizontalDelta = Math.sqrt(Math.max(0, measuredDelta ** 2 - verticalDelta ** 2))
      const azimuth = typeof point.azimuth === 'number' ? point.azimuth : (typeof previous.azimuth === 'number' ? previous.azimuth : 0)
      const azimuthRadians = azimuth * Math.PI / 180
      x += horizontalDelta * Math.sin(azimuthRadians)
      y += horizontalDelta * Math.cos(azimuthRadians)
    }
    values.push({ ...point, x, y, z: -point.trueVerticalDepth })
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
  const linePoints = coordinates.value.map(point => new THREE.Vector3(point.x, point.z, point.y))
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
  const direction = viewVector().normalize()
  camera.position.copy(center.clone().add(direction.multiplyScalar(span * 2.2)))
  camera.near = Math.max(span / 10000, 0.01)
  camera.far = Math.max(span * 20, 1000)
  camera.updateProjectionMatrix()
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
  const header = ['producer', 'measuredDepth', 'trueVerticalDepth', 'inclination', 'azimuth', 'maxDogLegSeverity', 'derivedX', 'derivedY']
  const rows = coordinates.value.map(point => [props.result?.producer || '', point.measuredDepth, point.trueVerticalDepth, point.inclination, point.azimuth ?? '', point.maxDogLegSeverity ?? '', point.x, point.y])
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
        <h3>{{ result?.producer }} · 官方 get_trajectory 只读结果</h3>
        <p class="muted">轨迹点和单位来自官方 Toolkit；三维坐标为基于 MD/TVD/方位的派生显示，不写回模型。</p>
      </div>
      <div class="heading-actions">
        <el-radio-group v-model="projection" size="small" aria-label="轨迹视图">
          <el-radio-button label="3d">三维</el-radio-button>
          <el-radio-button label="top">俯视</el-radio-button>
          <el-radio-button label="side">侧视</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="resetCamera">重置视角</el-button>
        <el-button size="small" type="primary" plain @click="downloadCsv">导出轨迹 CSV</el-button>
      </div>
    </div>
    <div class="trajectory-summary">
      <div><span>轨迹点</span><strong>{{ points.length }}</strong><small>点</small></div>
      <div><span>最大 MD</span><strong>{{ fmt(points.at(-1)?.measuredDepth) }}</strong><small>{{ result?.units?.measuredDepth }}</small></div>
      <div><span>最大 TVD</span><strong>{{ fmt(Math.max(...points.map(point => point.trueVerticalDepth), 0)) }}</strong><small>{{ result?.units?.trueVerticalDepth }}</small></div>
      <div><span>方位信息</span><strong>{{ hasAzimuth ? '有' : '未提供' }}</strong><small>{{ hasAzimuth ? result?.units?.azimuth : '官方原始为空' }}</small></div>
    </div>
    <div class="trajectory-layout">
      <div class="trajectory-canvas-wrap">
        <div ref="canvasHost" class="trajectory-canvas" aria-label="井轨迹三维图"></div>
        <el-alert v-if="renderError" type="warning" :closable="false" :title="renderError" />
        <span v-else-if="!ready" class="loading-hint">正在生成三维轨迹…</span>
        <p class="canvas-caption">橙色点为官方轨迹站点；坐标轴为派生 X / TVD / Y。拖动旋转，滚轮缩放。</p>
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
          <span>选中点派生坐标</span>
          <strong>X {{ fmt(coordinates[selectedIndex]?.x) }} · Y {{ fmt(coordinates[selectedIndex]?.y) }} · Z {{ fmt(coordinates[selectedIndex]?.z) }}</strong>
        </div>
      </div>
    </div>
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
@media (max-width: 900px) {
  .result-heading, .trajectory-layout { flex-direction: column; }
  .trajectory-canvas-wrap, .point-table-wrap { min-width: 0; }
  .trajectory-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
