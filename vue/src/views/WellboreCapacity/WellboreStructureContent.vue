<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { dataManagementApi } from '@/api/docker'

const props = defineProps({
  wellName: { type: String, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const HIDDEN_FIELDS = new Set(['-', 'id', 'ProjectId', 'ProjectGasReservoirId', 'edges'])

const loading = ref(false)
const errorMessage = ref('')
const rows = ref([])
const responseFields = ref([])
const canvasHost = ref(null)
const selectedRow = ref(null)

let scene
let camera
let renderer
let controls
let resizeObserver
let animationFrame
let trajectoryGroup
let selectedMarker
let initialCameraState
let pointByRow = new Map()

// 根据接口字段元数据生成表格列，并排除项目内部管理字段。
const columns = computed(() => responseFields.value
  .filter(field => field?.name && !HIDDEN_FIELDS.has(field.name))
  .map(field => ({...field, key: field.name, label: field.unit_label ? `${field.name_cn || field.name}(${field.unit_label})` : (field.name_cn || field.name)})))

// 按接口指定的小数位格式化单元格，空值保持为空。
const formatCellValue = (row, column) => {
  const value = row[column.key]
  if (value === null || value === undefined) return ''

  const numberValue = Number(value)
  if (Number.isFinite(numberValue) && column.displayDecimal !== undefined) {
    return numberValue.toFixed(column.displayDecimal)
  }
  return String(value)
}

// 将 Axios 响应或已解包响应统一整理为 fields/items 结构。
const normalizeResponse = response => {
  const payload = response?.data?.data ?? response?.data ?? response ?? {}
  return {
    items: Array.isArray(payload.items) ? payload.items : [],
    fields: Array.isArray(payload.fields) ? payload.fields : []
  }
}

// 根据最大井深生成接近 1、2、5 倍数量级的整洁刻度间隔。
const getDepthTickStep = maxDepth => {
  const roughStep = maxDepth / 6
  const magnitude = 10 ** Math.floor(Math.log10(roughStep))
  const normalizedStep = roughStep / magnitude
  const niceStep = normalizedStep <= 1 ? 1 : normalizedStep <= 2 ? 2 : normalizedStep <= 5 ? 5 : 10
  return niceStep * magnitude
}

// 将井深文字绘制到 CanvasTexture，并包装成始终面向相机的 Three.js Sprite。
const createTextSprite = (text, worldHeight) => {
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 128
  const context = canvas.getContext('2d')
  context.font = '52px "Microsoft YaHei", sans-serif'
  context.fillStyle = '#25313c'
  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.fillText(text, canvas.width / 2, canvas.height / 2)

  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({
    map: texture,
    transparent: true,
    depthTest: false
  }))
  sprite.scale.set(worldHeight * 4, worldHeight, 1)
  sprite.renderOrder = 10
  return sprite
}

// 在井轨迹侧面添加向下为正的 Z 轴井深标尺、刻度线和米制标签。
const addDepthAxis = (group, bounds, points, maximumSpan) => {
  const maximumDepth = Math.abs(Math.min(...points.map(point => point.y), 0))
  if (maximumDepth === 0) return

  const axisX = bounds.min.x - maximumSpan * 0.12
  const axisZ = bounds.max.z + maximumSpan * 0.06
  const tickLength = maximumSpan * 0.025
  const textHeight = maximumSpan * 0.028
  const axisMaterial = new THREE.LineBasicMaterial({ color: 0x25884b })
  const axisGeometry = new THREE.BufferGeometry().setFromPoints([
    new THREE.Vector3(axisX, 0, axisZ),
    new THREE.Vector3(axisX, -maximumDepth, axisZ)
  ])
  group.add(new THREE.Line(axisGeometry, axisMaterial))

  const tickStep = getDepthTickStep(maximumDepth)
  for (let depth = 0; depth <= maximumDepth; depth += tickStep) {
    const y = -depth
    const tickGeometry = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(axisX - tickLength, y, axisZ),
      new THREE.Vector3(axisX + tickLength, y, axisZ)
    ])
    group.add(new THREE.Line(tickGeometry, axisMaterial.clone()))

    const label = createTextSprite(`${depth.toFixed(0)} m`, textHeight)
    label.position.set(axisX - tickLength * 3.1, y, axisZ)
    group.add(label)
  }

  const axisTitle = createTextSprite('Z / 井深 (m)', textHeight * 1.08)
  axisTitle.position.set(axisX, textHeight * 1.2, axisZ)
  group.add(axisTitle)
}

// 释放指定 Three.js 对象树中的几何体和材质，避免 WebGL 资源残留。
const disposeObject = object => {
  object.traverse(child => {
    child.geometry?.dispose()
    if (Array.isArray(child.material)) {
      child.material.forEach(material => {
        material.map?.dispose()
        material.dispose()
      })
    } else {
      child.material?.map?.dispose()
      child.material?.dispose()
    }
  })
}

// 从场景中移除当前井轨迹及其测点映射，为重新绘制做准备。
const clearTrajectory = () => {
  if (!trajectoryGroup || !scene) return
  scene.remove(trajectoryGroup)
  disposeObject(trajectoryGroup)
  trajectoryGroup = null
  selectedMarker = null
  pointByRow = new Map()
}

// 恢复根据当前井轨迹包围盒计算出的初始相机位置和观察中心。
const resetCamera = () => {
  if (!initialCameraState || !camera || !controls) return
  camera.position.copy(initialCameraState.position)
  controls.target.copy(initialCameraState.target)
  controls.update()
}

// 将有效井斜坐标转换为局部坐标，并创建井筒、端点、网格和坐标轴。
const renderTrajectory = () => {
  if (!scene) return
  clearTrajectory()

  const drawableRows = rows.value.filter(row =>
    Number.isFinite(Number(row.xCoordinate)) &&
    Number.isFinite(Number(row.yCoordinate)) &&
    Number.isFinite(Number(row.zCoordinate))
  )
  if (!drawableRows.length) return

  const origin = drawableRows[0]
  const last = drawableRows[drawableRows.length - 1]
  const verticalSign = Number(last.zCoordinate) > Number(origin.zCoordinate) ? -1 : 1
  const points = drawableRows.map(row => {
    const point = new THREE.Vector3(
      Number(row.xCoordinate) - Number(origin.xCoordinate),
      verticalSign * (Number(row.zCoordinate) - Number(origin.zCoordinate)),
      -(Number(row.yCoordinate) - Number(origin.yCoordinate))
    )
    pointByRow.set(row, point)
    return point
  })

  trajectoryGroup = new THREE.Group()
  scene.add(trajectoryGroup)

  const bounds = new THREE.Box3().setFromPoints(points)
  const size = bounds.getSize(new THREE.Vector3())
  const center = bounds.getCenter(new THREE.Vector3())
  const maximumSpan = Math.max(size.x, size.y, size.z, 1)
  const tubeRadius = Math.max(maximumSpan / 320, 0.2)

  if (points.length > 1) {
    const curve = new THREE.CatmullRomCurve3(points)
    const geometry = new THREE.TubeGeometry(curve, Math.max(points.length * 4, 48), tubeRadius, 10, false)
    const material = new THREE.MeshStandardMaterial({ color: 0xf2c811, roughness: 0.42, metalness: 0.08 })
    trajectoryGroup.add(new THREE.Mesh(geometry, material))
  }

  const markerGeometry = new THREE.SphereGeometry(tubeRadius * 2.2, 20, 14)
  const wellhead = new THREE.Mesh(
    markerGeometry,
    new THREE.MeshStandardMaterial({ color: 0x25a56a })
  )
  wellhead.position.copy(points[0])
  trajectoryGroup.add(wellhead)

  const bottom = new THREE.Mesh(
    markerGeometry.clone(),
    new THREE.MeshStandardMaterial({ color: 0xd84a3a })
  )
  bottom.position.copy(points[points.length - 1])
  trajectoryGroup.add(bottom)

  selectedMarker = new THREE.Mesh(
    new THREE.SphereGeometry(tubeRadius * 2.8, 20, 14),
    new THREE.MeshStandardMaterial({ color: 0x1677ff, emissive: 0x0b315d })
  )
  selectedMarker.visible = false
  trajectoryGroup.add(selectedMarker)

  const gridSize = Math.max(maximumSpan * 1.3, 10)
  const grid = new THREE.GridHelper(gridSize, 10, 0x888888, 0xd8d8d8)
  grid.position.set(center.x, 0, center.z)
  trajectoryGroup.add(grid)

  const axes = new THREE.AxesHelper(Math.max(maximumSpan * 0.22, 5))
  trajectoryGroup.add(axes)
  addDepthAxis(trajectoryGroup, bounds, points, maximumSpan)

  const distance = maximumSpan * 1.45
  initialCameraState = {
    position: center.clone().add(new THREE.Vector3(distance, distance * 0.65, distance)),
    target: center.clone()
  }
  resetCamera()
}

// 初始化 Three.js 场景、相机、光源、轨道控制器和自适应渲染循环。
const initializeScene = () => {
  const host = canvasHost.value
  if (!host) return

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0xf8fafc)

  camera = new THREE.PerspectiveCamera(45, host.clientWidth / host.clientHeight, 0.1, 1000000)
  renderer = new THREE.WebGLRenderer({ antialias: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.setSize(host.clientWidth, host.clientHeight)
  host.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true

  scene.add(new THREE.HemisphereLight(0xffffff, 0x5f6874, 1.7))
  const directionalLight = new THREE.DirectionalLight(0xffffff, 2.2)
  directionalLight.position.set(1, 1, 1)
  scene.add(directionalLight)

  // 容器尺寸改变时同步画布尺寸和相机宽高比。
  resizeObserver = new ResizeObserver(() => {
    if (!canvasHost.value || !camera || !renderer) return
    const width = canvasHost.value.clientWidth
    const height = canvasHost.value.clientHeight
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    renderer.setSize(width, height)
  })
  resizeObserver.observe(host)

  // 持续更新带阻尼的轨道控制器并渲染当前场景。
  const animate = () => {
    animationFrame = requestAnimationFrame(animate)
    controls.update()
    renderer.render(scene, camera)
  }
  animate()
}

// 响应表格当前行变化，在井轨迹上显示对应测点标记。
const handleRowChange = row => {
  selectedRow.value = row
  if (!selectedMarker) return
  const point = pointByRow.get(row)
  selectedMarker.visible = Boolean(point)
  if (point) selectedMarker.position.copy(point)
}

// 获取当前井的井斜数据，按测量深度排序后刷新表格和三维轨迹。
const loadData = async () => {
  loading.value = true
  errorMessage.value = ''
  selectedRow.value = null
  try {
    const response = await dataManagementApi.getWellDeviation(
      props.projectId,
      props.gasReservoirId,
      props.wellName
    )
    const result = normalizeResponse(response)
    rows.value = result.items.slice().sort(
      (left, right) => Number(left.measuredDepth) - Number(right.measuredDepth)
    )
    responseFields.value = result.fields
    await nextTick()
    renderTrajectory()
  } catch (error) {
    rows.value = []
    responseFields.value = []
    clearTrajectory()
    errorMessage.value = error.response?.data?.message || error.message || '井斜数据加载失败'
  } finally {
    loading.value = false
  }
}

// 切换项目、气藏或井时重新请求对应井斜数据。
watch(
  () => [props.projectId, props.gasReservoirId, props.wellName],
  loadData
)

// 组件挂载后先建立三维场景，再加载当前井数据。
onMounted(() => {
  initializeScene()
  loadData()
})

// 组件卸载时停止动画并释放监听器、控制器和 WebGL 资源。
onBeforeUnmount(() => {
  cancelAnimationFrame(animationFrame)
  resizeObserver?.disconnect()
  controls?.dispose()
  clearTrajectory()
  renderer?.dispose()
  renderer?.domElement.remove()
})
</script>

<template>
  <section class="wellbore-structure">
    <header class="structure-header">
      <div>
        <h2>{{ wellName }} 井身结构</h2>
        <span>井斜数据 {{ rows.length }} 条</span>
      </div>
      <button type="button" class="reset-button" @click="resetCamera">重置视角</button>
    </header>

    <div class="structure-content" v-loading="loading">
      <section class="data-panel">
        <div class="panel-title">数据列表</div>
        <el-table
          v-if="rows.length"
          :data="rows"
          height="100%"
          size="small"
          border
          stripe
          highlight-current-row
          @current-change="handleRowChange"
        >
          <el-table-column type="index" label="序号" width="62" fixed />
          <el-table-column
            v-for="column in columns"
            :key="column.key"
            :prop="column.key"
            :label="column.label"
            :min-width="column.key === 'wellName' ? 100 : 128"
          >
            <template #default="scope">{{ formatCellValue(scope.row, column) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading && !errorMessage" description="暂无井斜数据" />
        <div v-if="errorMessage" class="error-state">
          <span>{{ errorMessage }}</span>
          <button type="button" @click="loadData">重新加载</button>
        </div>
      </section>

      <section class="scene-panel">
        <div class="panel-title scene-title">
          <span>三维井身结构示意图</span>
          <span class="axis-caption">X：红色　Z：绿色　Y：蓝色</span>
        </div>
        <div ref="canvasHost" class="canvas-host">
          <div v-if="!loading && !errorMessage && !rows.length" class="scene-empty">暂无可绘制的井斜数据</div>
          <div v-if="selectedRow" class="point-info">
            MD {{ formatCellValue(selectedRow, { key: 'measuredDepth', displayDecimal: 2 }) }} m
          </div>
        </div>
      </section>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.wellbore-structure {
  width: 100%;
  height: 100%;
  min-width: 920px;
  min-height: 480px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
}

.structure-header {
  height: 48px;
  flex: 0 0 48px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #dedede;

  div {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }

  h2 {
    margin: 0;
    font-size: 16px;
    font-weight: 600;
  }

  span {
    color: #777;
    font-size: 12px;
  }
}

.reset-button,
.error-state button {
  height: 30px;
  padding: 0 16px;
  border: 1px solid #222;
  border-radius: 5px;
  background: #fff;
  color: #222;
  cursor: pointer;

  &:hover { background: #fff8d8; }
}

.structure-content {
  flex: 1;
  min-height: 0;
  display: flex;
}

.data-panel,
.scene-panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.data-panel {
  width: 42%;
  min-width: 390px;
  border-right: 1px solid #d8d8d8;
}

.scene-panel {
  width: 58%;
  min-width: 480px;
}

.panel-title {
  position: relative;
  height: 36px;
  flex: 0 0 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid #dedede;
  background: #f4d000;
  color: #222;
  font-size: 14px;
  font-weight: 600;
}

.scene-title {
  text-align: center;
}

.axis-caption {
  position: absolute;
  right: 12px;
  font-size: 12px;
  font-weight: 400;
  color: #555;
}

.canvas-host {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.scene-empty,
.error-state {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.scene-empty {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
}

.error-state {
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 24px;
  text-align: center;
}

.point-info {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 6px 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.92);
  color: #333;
  font-size: 12px;
  pointer-events: none;
}

:deep(.el-table) {
  --el-table-header-bg-color: #f4f4f4;
  --el-table-row-hover-bg-color: #fff8d8;
  font-size: 13px;
}

:deep(.el-table th.el-table__cell) {
  height: 36px;
  color: #333;
  font-weight: 400;
}

:deep(.el-table td.el-table__cell) {
  height: 30px;
}

:deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: #fff3b0;
}
</style>
