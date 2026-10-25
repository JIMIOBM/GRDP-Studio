<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { preprocessingWellLocations } from './eclipsePreprocessing.js'

const props = defineProps({ preview: { type: Object, required: true } })
const host = ref(null),
  attribute = ref('permx'),
  selected = ref(null),
  error = ref('')
const labels = {
  permx: 'X向渗透率 · mD',
  permy: 'Y向渗透率 · mD',
  permz: 'Z向渗透率 · mD',
  porosity: '孔隙度 · fraction',
}
const active = computed(() => props.preview.cells.filter((cell) => cell.active))
const extent = computed(() => {
  const values = active.value.map((cell) => cell[attribute.value]).filter(Number.isFinite)
  return values.length ? [Math.min(...values), Math.max(...values)] : [0, 0]
})
let renderer, scene, camera, controls, mesh, group, observer, frame, clickHandler

function disposeGeometry() {
  if (!group) return
  group.traverse((object) => {
    object.geometry?.dispose()
    if (Array.isArray(object.material)) object.material.forEach((value) => value.dispose())
    else object.material?.dispose()
  })
  scene?.remove(group)
  group = null
  mesh = null
}

function draw() {
  if (!renderer) return
  disposeGeometry()
  selected.value = null
  group = new THREE.Group()
  const cells = active.value
  if (!cells.length) return
  const width = Math.max(...cells.map((cell) => cell.x + cell.dx))
  const height = Math.max(...cells.map((cell) => cell.y + cell.dy))
  const top = Math.min(...cells.map((cell) => cell.top))
  const bottom = Math.max(...cells.map((cell) => cell.top + cell.dz))
  const scale = 250 / Math.max(width, height, bottom - top)
  const material = new THREE.MeshStandardMaterial({ roughness: 0.6, metalness: 0.05 })
  mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(1, 1, 1), material, cells.length)
  const dummy = new THREE.Object3D(),
    color = new THREE.Color()
  const [low, high] = extent.value
  for (let index = 0; index < cells.length; index++) {
    const cell = cells[index]
    dummy.position.set(
      (cell.x + cell.dx / 2 - width / 2) * scale,
      -(cell.top + cell.dz / 2 - (top + bottom) / 2) * scale,
      (cell.y + cell.dy / 2 - height / 2) * scale,
    )
    dummy.scale.set(cell.dx * scale * 0.96, cell.dz * scale * 0.96, cell.dy * scale * 0.96)
    dummy.updateMatrix()
    mesh.setMatrixAt(index, dummy.matrix)
    const fraction = high === low ? 0.5 : (cell[attribute.value] - low) / (high - low)
    mesh.setColorAt(index, color.setHSL(0.59 - fraction * 0.55, 0.7, 0.57))
  }
  mesh.instanceMatrix.needsUpdate = true
  mesh.instanceColor.needsUpdate = true
  group.add(mesh)
  for (const well of props.preview.wells) {
    const { head: surface, completions } = preprocessingWellLocations(props.preview, well)
    for (const { cell, status } of completions) {
      const segment = new THREE.Mesh(
        new THREE.CylinderGeometry(1.2, 1.2, cell.dz * scale, 12),
        new THREE.MeshBasicMaterial({ color: status === 'SHUT' ? '#8a8a85' : '#e49a23', depthTest: false, transparent: true, opacity: 0.9 }),
      )
      segment.position.set((cell.x + cell.dx / 2 - width / 2) * scale,
        -(cell.top + cell.dz / 2 - (top + bottom) / 2) * scale, (cell.y + cell.dy / 2 - height / 2) * scale)
      segment.renderOrder = 2
      group.add(segment)
    }
    if (!surface) continue
    const length = 24
    const tube = new THREE.Mesh(
      new THREE.CylinderGeometry(0.7, 0.7, length, 12),
      new THREE.MeshStandardMaterial({ color: '#253c58' }),
    )
    tube.position.set(
      (surface.x + surface.dx / 2 - width / 2) * scale,
      -(surface.top - (top + bottom) / 2) * scale + 24 - length / 2,
      (surface.y + surface.dy / 2 - height / 2) * scale,
    )
    group.add(tube)
    const marker = new THREE.Mesh(
      new THREE.SphereGeometry(2, 12, 12),
      new THREE.MeshStandardMaterial({ color: '#f3a941' }),
    )
    marker.position.copy(tube.position)
    marker.position.y += length / 2
    group.add(marker)
  }
  scene.add(group)
  reset()
}

function reset() {
  if (!camera) return
  camera.position.set(270, 220, 310)
  controls.target.set(0, 0, 0)
  controls.update()
}

onMounted(() => {
  try {
    scene = new THREE.Scene()
    scene.background = new THREE.Color('#ffffff')
    camera = new THREE.PerspectiveCamera(40, 1, 0.1, 3000)
    renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: false })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    host.value.appendChild(renderer.domElement)
    controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    scene.add(new THREE.HemisphereLight(0xffffff, 0x7b91a6, 2.8))
    const light = new THREE.DirectionalLight(0xffffff, 2.5)
    light.position.set(150, 300, 100)
    scene.add(light)
    observer = new ResizeObserver(() => {
      if (!host.value) return
      const width = host.value.clientWidth,
        height = host.value.clientHeight
      renderer.setSize(width, height)
      camera.aspect = width / Math.max(height, 1)
      camera.updateProjectionMatrix()
    })
    observer.observe(host.value)
    clickHandler = (event) => {
      if (!mesh) return
      const box = renderer.domElement.getBoundingClientRect(),
        pointer = new THREE.Vector2(
          ((event.clientX - box.left) / box.width) * 2 - 1,
          (-(event.clientY - box.top) / box.height) * 2 + 1,
        )
      const ray = new THREE.Raycaster()
      ray.setFromCamera(pointer, camera)
      const hit = ray.intersectObject(mesh)[0]
      selected.value = hit && Number.isInteger(hit.instanceId) ? active.value[hit.instanceId] : null
    }
    renderer.domElement.addEventListener('click', clickHandler)
    const animate = () => {
      controls.update()
      renderer.render(scene, camera)
      frame = requestAnimationFrame(animate)
    }
    animate()
    draw()
  } catch {
    error.value = '当前浏览器无法创建三维视图，参数和二维物性预览仍可使用。'
  }
})
watch([() => props.preview, attribute], draw)
onBeforeUnmount(() => {
  cancelAnimationFrame(frame)
  observer?.disconnect()
  controls?.dispose()
  disposeGeometry()
  renderer?.domElement.removeEventListener('click', clickHandler)
  renderer?.dispose()
  renderer?.domElement.remove()
})
</script>

<template>
  <section class="input-grid" aria-label="前处理储层与井输入预览">
    <header>
      <div><span>输入预览</span><strong>储层与井</strong></div>
      <el-select
        popper-class="preprocessing-select-menu"
        v-model="attribute"
        aria-label="输入网格属性"
        style="width: 210px"
        ><el-option
          v-for="(label, key) in labels"
          :key="key"
          :label="label"
          :value="key" /></el-select
      ><el-button @click="reset">重置视角</el-button>
    </header>
    <div ref="host" class="grid-canvas">
      <div class="input-watermark">表格输入 · 尚未求解</div>
      <p v-if="error" class="grid-error">{{ error }}</p>
    </div>
    <footer>
      <div>
        <span class="color-scale" /><span
          >{{ extent[0].toLocaleString() }} — {{ extent[1].toLocaleString() }}</span
        >
      </div>
      <span
        >{{ preview.activeCells }} 个活动单元 · {{ preview.wells.length }} 口井 ·
        橙色为完井连接，灰色为关闭连接；仅定位，不推测井轨迹 · 拖动旋转，滚轮缩放</span
      >
    </footer>
    <div v-if="selected" class="cell-detail">
      I {{ selected.i }} / J {{ selected.j }} / K {{ selected.k }}　孔隙度
      {{ selected.porosity }}　X向渗透率 {{ selected.permx }} mD　顶深 {{ selected.top }} ft
    </div>
    <div class="well-list">
      <span v-for="well in preview.wells" :key="well.name"
        ><i />{{ well.name }} · 井口 I{{ well.i }}/J{{ well.j }}
        <span v-for="(connection, index) in well.completions" :key="index">
          · 完井 I{{ connection.i }}/J{{ connection.j }}/K{{ connection.k1 }}–{{ connection.k2 }}
          {{ connection.status === 'SHUT' ? '关闭' : connection.status === 'OPEN' ? '开启' : connection.status }}
        </span></span
      >
    </div>
  </section>
</template>

<style scoped>
.input-grid {
  overflow: hidden;
  border: 1px solid #deded9;
  background: #fff;
}
header {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 10px;
  border-bottom: 1px solid #e1e1dc;
  background: #f5f5f1;
}
header > div:first-child {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-right: auto;
}
header span {
  color: #777;
  font-size: 11px;
}
header strong {
  color: #333;
  font-size: 13px;
  font-weight: 500;
}
.grid-canvas {
  height: 370px;
  position: relative;
  background: #fff;
}
.input-watermark {
  position: absolute;
  z-index: 1;
  top: 12px;
  left: 12px;
  color: #777;
  pointer-events: none;
  font-size: 12px;
}
.grid-error {
  position: absolute;
  top: 45%;
  left: 10%;
  right: 10%;
  color: #9c652f;
  text-align: center;
}
footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border-top: 1px solid #eee;
  color: #777;
  font-size: 12px;
}
footer > div {
  display: flex;
  align-items: center;
  gap: 8px;
}
.color-scale {
  width: 120px;
  height: 7px;
  background: linear-gradient(90deg, #4b8de0, #52c6cb, #d9cc54, #e27d4b);
}
.cell-detail {
  padding: 8px 10px;
  border-top: 1px solid #e1e1dc;
  color: #555;
  font-size: 12px;
}
.well-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding: 0 10px 9px;
  font-size: 11px;
  color: #777;
}
.well-list i {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  background: #d99731;
}
@media (max-width: 700px) {
  header {
    flex-wrap: wrap;
  }
  .grid-canvas {
    height: 300px;
  }
}
</style>
