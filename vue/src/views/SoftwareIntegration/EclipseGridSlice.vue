<script setup>
import { computed, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'

const props = defineProps({
  runId: { type: [Number, String], default: null },
  grid: { type: Object, default: null },
  fields: { type: Array, default: () => [] },
  artifacts: { type: Array, default: () => [] },
  timeStep: { type: [String, Number], default: 'all' }
})
const emit = defineEmits(['update:timeStep'])

const MAX_RANGE_BYTES = 4 * 1024 * 1024
const MAX_ACTNUM_VALUES = 4 * 1024 * 1024
const selectedFieldKey = ref('')
const selectedTimeStep = computed({
  get: () => String(props.timeStep ?? 'all'),
  set: value => emit('update:timeStep', String(value ?? 'all'))
})
const layer = ref(1)
const loading = ref(false)
const error = ref('')
const slice = ref(null)

const totalCells = computed(() => {
  const grid = props.grid
  return grid && Number.isInteger(grid.nx) && Number.isInteger(grid.ny) && Number.isInteger(grid.nz)
    ? grid.nx * grid.ny * grid.nz
    : 0
})
const layerCells = computed(() => props.grid ? props.grid.nx * props.grid.ny : 0)
const activeCellCount = computed(() => Number.isInteger(props.grid?.activeCells) ? props.grid.activeCells : null)
const actnumField = computed(() => props.fields.find(field => field.file?.toUpperCase().endsWith('.EGRID') &&
  field.keyword === 'ACTNUM' && field.dataType === 'INTE' && field.count === totalCells.value) || null)
const timeSteps = computed(() => [...new Set(props.fields.map(field => field.timeStep).filter(value => Number.isSafeInteger(value) && value > 0))].sort((left, right) => left - right))
const matchesTimeStep = field => {
  const selected = selectedTimeStep.value
  if (selected === 'all') return true
  if (selected === 'static') return field.timeStep === undefined
  return field.timeStep === undefined || Number(selected) === field.timeStep
}
const sliceFields = computed(() => props.fields.filter(field => ['REAL', 'DOUB', 'INTE', 'LOGI'].includes(field.dataType) &&
  field.keyword !== 'ACTNUM' && (field.count === totalCells.value ||
    (activeCellCount.value !== null && field.count === activeCellCount.value && actnumField.value)) &&
    matchesTimeStep(field)))
const selectedField = computed(() => sliceFields.value.find(field => fieldKey(field) === selectedFieldKey.value) || sliceFields.value[0] || null)
const sliceRows = computed(() => slice.value?.rows || [])
const sliceScale = computed(() => {
  const values = sliceRows.value.map(row => row.value).filter(value => typeof value === 'number' && Number.isFinite(value))
  if (!values.length) return null
  return values.reduce((scale, value) => ({ min: Math.min(scale.min, value), max: Math.max(scale.max, value) }), { min: values[0], max: values[0] })
})
const fieldKey = field => `${field.file}:${field.keyword}:${field.dataType}:${field.segments?.[0]?.offset ?? 0}`
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
const readValues = async (field, startValue, valueCount) => {
  const artifact = fieldArtifact(field)
  if (!artifact) throw new Error('对应的 ECLIPSE 二进制 Artifact 不可用。')
  const values = []
  let skippedValues = startValue
  let remainingValues = valueCount
  for (const segment of field.segments || []) {
    if (remainingValues <= 0) break
    const segmentValues = segment.length / field.elementSize
    if (skippedValues >= segmentValues) {
      skippedValues -= segmentValues
      continue
    }
    let segmentValueOffset = skippedValues
    while (segmentValueOffset < segmentValues && remainingValues > 0) {
      const chunkValues = Math.min(
        segmentValues - segmentValueOffset,
        remainingValues,
        Math.max(1, Math.floor(MAX_RANGE_BYTES / field.elementSize))
      )
      const offset = segment.offset + segmentValueOffset * field.elementSize
      const length = chunkValues * field.elementSize
      const payload = await softwareIntegrationApi.downloadArtifactRange(props.runId, artifact.id, offset, length)
      const blob = payload instanceof Blob ? payload : new Blob([payload])
      values.push(...decode(new Uint8Array(await blob.arrayBuffer()), field))
      segmentValueOffset += chunkValues
      remainingValues -= chunkValues
    }
    skippedValues = 0
  }
  if (values.length !== valueCount) throw new Error('二进制字段返回长度与索引不一致。')
  return values
}
const loadSlice = async () => {
  const field = selectedField.value
  const grid = props.grid
  if (!props.runId || !field || !grid || !totalCells.value || layer.value < 1 || layer.value > grid.nz) return
  loading.value = true
  error.value = ''
  slice.value = null
  try {
    const layerStart = (layer.value - 1) * layerCells.value
    let values
    let activeMask = null
    if (field.count === totalCells.value) {
      values = await readValues(field, layerStart, layerCells.value)
    } else {
      if (!actnumField.value || layerStart + layerCells.value > MAX_ACTNUM_VALUES) {
        throw new Error('该字段使用活动单元压缩格式，当前网格规模暂不支持安全切片。')
      }
      const actnum = await readValues(actnumField.value, 0, layerStart + layerCells.value)
      activeMask = actnum.slice(layerStart, layerStart + layerCells.value).map(value => value > 0)
      const activeBefore = actnum.slice(0, layerStart).filter(value => value > 0).length
      const activeInLayer = activeMask.filter(Boolean).length
      const compressed = await readValues(field, activeBefore, activeInLayer)
      values = []
      let cursor = 0
      activeMask.forEach(isActive => { values.push(isActive ? compressed[cursor++] : null) })
    }
    const rows = values.map((value, index) => ({
      i: index % grid.nx + 1,
      j: Math.floor(index / grid.nx) + 1,
      value: value === null || (typeof value === 'number' && !Number.isFinite(value)) ? null : value
    }))
    slice.value = { field, layer: layer.value, rows, activeCount: rows.filter(row => row.value !== null).length }
  } catch (exception) {
    error.value = exception?.message || 'ECLIPSE 网格层切片读取失败。'
  } finally {
    loading.value = false
  }
}
const interpolateColor = (from, to, ratio) => {
  const channel = index => Math.round(from[index] + (to[index] - from[index]) * ratio)
  return `rgb(${channel(0)}, ${channel(1)}, ${channel(2)})`
}
const cellStyle = row => {
  if (row.value === null || !sliceScale.value) return {}
  const range = sliceScale.value.max - sliceScale.value.min
  const ratio = range > 0 ? Math.max(0, Math.min(1, (row.value - sliceScale.value.min) / range)) : 0.5
  if (ratio <= 0.5) return { backgroundColor: interpolateColor([43, 108, 176], [246, 224, 94], ratio * 2), color: ratio > 0.34 ? '#303133' : '#fff' }
  return { backgroundColor: interpolateColor([246, 224, 94], [197, 48, 48], (ratio - 0.5) * 2), color: ratio > 0.78 ? '#fff' : '#303133' }
}
const csvCell = value => {
  const text = value == null ? '' : String(value)
  const safe = typeof value === 'string' && /^[\s]*[=+\-@]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}
const exportSliceCsv = () => {
  if (!slice.value) return
  const { field, layer: selectedLayer, rows } = slice.value
  const content = [
    ['文件', '关键字', '数据类型', '时间步', '层', 'I', 'J', '值'],
    ...rows.map(row => [field.file, field.keyword, field.dataType, field.timeStep ?? '静态', selectedLayer, row.i, row.j, row.value])
  ].map(row => row.map(csvCell).join(',')).join('\r\n')
  const url = URL.createObjectURL(new Blob([`\uFEFF${content}`], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `eclipse-${field.keyword}-layer-${selectedLayer}.csv`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
watch(sliceFields, fields => {
  if (!fields.some(field => fieldKey(field) === selectedFieldKey.value)) selectedFieldKey.value = fields[0] ? fieldKey(fields[0]) : ''
  if (props.grid && layer.value > props.grid.nz) layer.value = props.grid.nz
  slice.value = null
  error.value = ''
}, { immediate: true })
watch(timeSteps, steps => {
  if (selectedTimeStep.value === 'static' || selectedTimeStep.value === 'all') return
  if (!steps.includes(Number(selectedTimeStep.value))) selectedTimeStep.value = 'all'
})
watch(() => [props.runId, props.grid], () => {
  layer.value = 1
  emit('update:timeStep', 'all')
  slice.value = null
  error.value = ''
}, { deep: true })
</script>

<template>
  <section v-if="grid && sliceFields.length" class="grid-slice-panel">
    <div class="panel-heading">
      <div><h3>二维层切片</h3><p>只读取选定字段和层的真实范围；活动单元压缩字段会按 ACTNUM 还原位置。</p></div>
      <span>{{ grid.nx }} × {{ grid.ny }} × {{ grid.nz }}</span>
    </div>
    <div class="grid-slice-controls">
      <el-select v-if="timeSteps.length" v-model="selectedTimeStep" aria-label="ECLIPSE 层切片时间步" placeholder="选择时间步">
        <el-option value="all" label="全部时间步（含静态字段）" />
        <el-option value="static" label="静态字段" />
        <el-option v-for="timeStep in timeSteps" :key="timeStep" :value="String(timeStep)" :label="`时间步 ${timeStep}（含静态字段）`" />
      </el-select>
      <el-select v-model="selectedFieldKey" aria-label="ECLIPSE 层切片字段" placeholder="选择字段">
        <el-option v-for="field in sliceFields" :key="fieldKey(field)" :value="fieldKey(field)" :label="`${field.keyword} · ${field.dataType} · ${field.file} · 时间步 ${field.timeStep ?? '静态'} · 偏移 ${field.segments?.[0]?.offset ?? '-'}`" />
      </el-select>
      <el-input-number v-model="layer" :min="1" :max="grid.nz" :step="1" controls-position="right" aria-label="ECLIPSE 层号" />
      <el-button type="primary" :loading="loading" :disabled="!selectedField" @click="loadSlice">读取二维切片</el-button>
    </div>
    <el-alert v-if="error" type="warning" :closable="false" :title="error" />
    <div v-if="slice" class="grid-slice-result">
      <div class="slice-summary">
        <p>字段 {{ slice.field.keyword }} · 第 {{ slice.layer }} 层 · {{ slice.activeCount }} 个有效单元</p>
        <div class="slice-summary-actions">
          <el-button size="small" @click="exportSliceCsv">导出二维切片 CSV</el-button>
          <div v-if="sliceScale" class="slice-scale" aria-label="网格切片数值色标">
            <span>{{ sliceScale.min }}</span><i /><span>{{ sliceScale.max }}</span>
          </div>
        </div>
      </div>
      <div class="grid-slice-grid" :style="{ gridTemplateColumns: `repeat(${grid.nx}, minmax(48px, 1fr))` }">
        <div v-for="row in slice.rows" :key="`${row.i}-${row.j}`" class="grid-slice-cell" :class="{ unavailable: row.value === null }" :style="cellStyle(row)" :title="`I=${row.i}, J=${row.j}`">
          <small>{{ row.i }},{{ row.j }}</small><strong>{{ row.value === null ? '-' : row.value }}</strong>
        </div>
      </div>
    </div>
  </section>
</template>

<style lang="scss" scoped>
.grid-slice-panel { padding: 16px; border: 1px solid #e1e7ef; background: #fff; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }.panel-heading h3 { margin: 0; font-size: 15px; }.panel-heading p { margin: 4px 0 0; color: #909399; font-size: 12px; }.panel-heading > span { color: #737a84; font-size: 12px; }
.grid-slice-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }.grid-slice-controls .el-select { width: min(330px, 100%); }.grid-slice-controls .el-input-number { width: 130px; }
.slice-summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }.grid-slice-result > .slice-summary p { margin: 0; color: #606266; font-size: 12px; }.slice-summary-actions { display: flex; align-items: center; gap: 12px; }.slice-scale { display: flex; align-items: center; gap: 6px; color: #737a84; font-size: 11px; }.slice-scale i { display: inline-block; width: 110px; height: 10px; border-radius: 5px; background: linear-gradient(90deg, #2b6cb0, #f6e05e 50%, #c53030); }.grid-slice-grid { display: grid; gap: 3px; overflow: auto; padding: 3px; border: 1px solid #e5eaf1; background: #f5f7fa; }.grid-slice-cell { min-height: 48px; padding: 5px; overflow: hidden; text-align: center; border: 1px solid rgba(255,255,255,.65); background: #eaf3fb; }.grid-slice-cell small { display: block; color: rgba(0,0,0,.58); font-size: 10px; }.grid-slice-cell strong { display: block; margin-top: 3px; color: inherit; font-size: 12px; }.grid-slice-cell.unavailable { background: #f3f4f6; }.grid-slice-cell.unavailable small, .grid-slice-cell.unavailable strong { color: #a0a5ad; }
@media (max-width: 680px) { .slice-summary { align-items: flex-start; flex-direction: column; gap: 5px; } }
</style>
