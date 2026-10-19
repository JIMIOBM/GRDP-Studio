<script setup>
import EclipseSpatialDifference3D from './EclipseSpatialDifference3D.vue'
import EclipseSpatialCellHistory from './EclipseSpatialCellHistory.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { createFieldRequestGate } from './eclipseFieldRequest'
import { spatialSnapshot, spatialChoices, loadSpatialComparison, differenceColor, spatialCsv } from './eclipseSpatialComparison.js'
import { formatGridValue, gridValueColor, gridFieldUnit } from './eclipseGridPresentation.js'

const props = defineProps({ run: Object, baseline: Object, unitSystem: String })
const selectedKey = ref(''), layer = ref(1), selectedIndex = ref(null), loading = ref(false), error = ref(''), data = ref(null)
const gate = createFieldRequestGate()
const show3D = ref(false)
const eligibility = computed(() => { try { return { choices: spatialChoices(props.run, props.baseline), error: '' } } catch (e) { return { choices: [], error: e.message } } })
const choice = computed(() => eligibility.value.choices.find(item => item.key === selectedKey.value))
const unit = computed(() => gridFieldUnit({ keyword: data.value?.keyword }, props.unitSystem))
const layerCells = computed(() => data.value?.cells.filter(c => c.k === layer.value) || [])
const selectedCell = computed(() => data.value?.cells.find(c => c.index === selectedIndex.value))
const magnitude = computed(() => data.value ? Math.max(Math.abs(data.value.differenceStats.min), Math.abs(data.value.differenceStats.max)) : 0)
const rawScale = computed(() => data.value ? { min: Math.min(data.value.baselineStats.min, data.value.currentStats.min), max: Math.max(data.value.baselineStats.max, data.value.currentStats.max) } : null)
const maps = computed(() => [{ key: 'baselineValue', title: `原基线 #${props.baseline?.id}`, difference: false }, { key: 'value', title: `当前方案 #${props.run?.id}`, difference: false }, { key: 'difference', title: '空间差值 · 方案 − 基线', difference: true }])
const color = (cell, map) => map.difference ? differenceColor(cell.difference, magnitude.value) : gridValueColor(cell[map.key], rawScale.value)
watch(() => [props.run?.id, props.baseline?.id], () => {
  layer.value = 1; selectedIndex.value = null; show3D.value = false
  const choices = eligibility.value.choices
  selectedKey.value = choices.filter(item => item.keyword === 'TEMP').at(-1)?.key || choices.at(-1)?.key || ''
}, { immediate: true })
async function load() {
  const current = gate.begin()
  data.value = null; selectedIndex.value = null; loading.value = false; error.value = ''
  if (!choice.value) return
  const run = spatialSnapshot(props.run), baseline = spatialSnapshot(props.baseline), selected = { ...choice.value }, unitSystem = props.unitSystem
  loading.value = true
  try {
    const result = await loadSpatialComparison({ run, baseline, choice: selected, unitSystem, request: softwareIntegrationApi.downloadArtifactRange, isCurrent: current })
    if (current()) data.value = result
  } catch (e) { if (current()) error.value = e.message || '空间比较读取失败。' }
  finally { if (current()) loading.value = false }
}
watch(() => [props.run?.id, props.baseline?.id, props.run?.artifacts, props.baseline?.artifacts, props.unitSystem, selectedKey.value], load, { immediate: true })
watch(layer, () => { selectedIndex.value = null })
onBeforeUnmount(() => gate.dispose())
function download(blob, filename) {
  const url = URL.createObjectURL(blob), link = document.createElement('a')
  link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 0)
}
const exportCsv = () => data.value && download(new Blob([spatialCsv(data.value, props.run.id, props.baseline.id, unit.value)], { type: 'text/csv;charset=utf-8' }), `eclipse-${props.run.id}-${data.value.keyword}-spatial-difference.csv`)
const exportPng = () => {
  if (!data.value || loading.value) return
  const result = data.value, canvas = document.createElement('canvas'), context = canvas.getContext('2d')
  canvas.width = 1440; canvas.height = 760
  context.fillStyle = '#f3f7fc'; context.fillRect(0, 0, 1440, 760)
  context.fillStyle = '#173d62'; context.font = 'bold 28px sans-serif'; context.fillText(`ECLIPSE · ${result.keyword} · 热采方案空间对比`, 32, 48)
  context.font = '15px sans-serif'; context.fillStyle = '#6b849b'; context.fillText(`基线 #${props.baseline.id} → 方案 #${props.run.id} | 第${result.timeDays}天 | 报告${result.report} | K=${layer.value} | ${unit.value}`, 32, 82)
  const visible = layerCells.value
  for (const [index, map] of maps.value.entries()) {
    const left = 32 + index * 472, top = 160, width = 432, height = Math.min(420, width * result.grid.ny / result.grid.nx)
    context.fillStyle = '#fff'; context.fillRect(left - 12, top - 52, 456, 530)
    context.fillStyle = '#254b6c'; context.font = 'bold 17px sans-serif'; context.fillText(map.title, left, top - 20)
    for (const cell of visible) {
      context.fillStyle = color(cell, map)
      const x = left + (cell.i - 1) * width / result.grid.nx, y = top + (result.grid.ny - cell.j) * height / result.grid.ny
      context.fillRect(x, y, width / result.grid.nx, height / result.grid.ny)
      context.strokeStyle = '#ffffff70'; context.strokeRect(x, y, width / result.grid.nx, height / result.grid.ny)
    }
    context.font = '14px sans-serif'; context.fillStyle = '#5c7894'
    context.fillText(map.difference ? `差值范围 ${formatGridValue(-magnitude.value)} — +${formatGridValue(magnitude.value)} ${unit.value}` : `共享色标 ${formatGridValue(rawScale.value.min)} — ${formatGridValue(rawScale.value.max)} ${unit.value}`, left, top + height + 34)
  }
  context.fillStyle = '#4e6e8c'; context.font = '15px sans-serif'
  context.fillText(`全活动格：${result.cells.length} | 变化：${result.changedCells} | 平均差值：${formatGridValue(result.differenceStats.mean)} ${unit.value}`, 32, 690)
  context.fillText('I → / J ↑ · 按网格索引剖面 · 相同EGRID与实际报告时间已核对 · 不插值', 32, 724)
  const filename = `eclipse-${props.run.id}-${result.keyword}-layer-${layer.value}-difference.png`
  canvas.toBlob(blob => blob && download(blob, filename), 'image/png')
}
</script>

<template>
  <section class="spatial-panel" aria-label="E300热采空间差值分析" :data-ready="data && !loading ? 'true' : 'false'" :data-run-id="run?.id">
    <header><div><span>THERMAL SCENARIO / SPATIAL RESPONSE</span><h3>热采方案 · 温度与压力在哪里发生变化</h3><p>把同一网格、同一实际报告时刻的基线与方案逐格对比。</p></div><el-tag v-if="data" type="success">实际第 {{ data.timeDays }} 天</el-tag></header>
    <el-alert v-if="eligibility.error || error" :title="error || eligibility.error" type="warning" :closable="false"/>
    <div class="spatial-controls"><el-select v-model="selectedKey" aria-label="热采空间比较字段报告" :disabled="!eligibility.choices.length"><el-option v-for="item in eligibility.choices" :key="item.key" :value="item.key" :label="`${item.keyword} · 报告 ${item.report}`"/></el-select><label>层 K <el-input-number v-model="layer" :min="1" :max="run?.result?.grid?.nz || 1" :step="1" aria-label="热采空间比较层"/></label><el-button :disabled="!data || loading" @click="exportCsv">导出逐格差值CSV</el-button><el-button :disabled="!data || loading" @click="exportPng">导出空间对比图片</el-button></div>
    <div v-if="loading" class="spatial-loading">正在读取两个真实运行的网格场值并核对报告时间…</div>
    <template v-if="data && !loading">
      <div class="spatial-kpis" aria-label="空间差值统计"><div><small>全活动格变化数</small><strong>{{ data.changedCells }} <em>/ {{ data.cells.length }}</em></strong><span>逐格原值精确比较</span></div><div><small>平均差值</small><strong>{{ formatGridValue(data.differenceStats.mean) }} <em>{{ unit }}</em></strong><span>按格算术平均，非体积加权</span></div><div><small>最大降低</small><strong>{{ formatGridValue(Math.min(0, data.differenceStats.min)) }} <em>{{ unit }}</em></strong><span>负值表示方案低于基线</span></div><div><small>最大升高</small><strong>{{ formatGridValue(Math.max(0, data.differenceStats.max)) }} <em>{{ unit }}</em></strong><span>正值表示方案高于基线</span></div></div>
      <div v-if="layerCells.length <= 10000" class="spatial-maps"><article v-for="map in maps" :key="map.key"><h4>{{ map.title }}</h4><svg :viewBox="`0 0 ${data.grid.nx} ${data.grid.ny}`" role="img" :aria-label="`${map.title} ${data.keyword} K${layer}`"><rect v-for="cell in layerCells" :key="cell.index" :x="cell.i - 1" :y="data.grid.ny - cell.j" width="1" height="1" :fill="color(cell, map)" :stroke="selectedIndex === cell.index ? '#192c46' : '#ffffff80'" :stroke-width="selectedIndex === cell.index ? .1 : .025" tabindex="0" role="button" :aria-label="`I${cell.i} J${cell.j} K${cell.k} ${formatGridValue(cell[map.key])}`" @click="selectedIndex = cell.index" @keydown.enter="selectedIndex = cell.index"><title>I={{ cell.i }} J={{ cell.j }} K={{ cell.k }}：{{ cell[map.key] }} {{ unit }}</title></rect></svg><div class="map-scale" :class="{ diverging: map.difference }"/><div class="scale-ends"><span>{{ formatGridValue(map.difference ? -magnitude : rawScale.min) }}</span><span>{{ unit }}</span><span>{{ formatGridValue(map.difference ? magnitude : rawScale.max) }}</span></div><small>{{ map.difference ? '蓝：降低 / 白：不变 / 红：升高' : '两幅原值图使用相同色标' }}</small></article></div>
      <el-alert v-else title="当前层超过1万活动格，请使用逐格CSV；大网格交互图继续优化。" type="info" :closable="false"/>
      <p v-if="!layerCells.length">该层没有活动格；未补画非活动单元。</p>
      <div v-if="selectedCell" class="spatial-cell" aria-label="空间差值选中单元"><strong>I={{ selectedCell.i }} · J={{ selectedCell.j }} · K={{ selectedCell.k }}</strong><span>基线 {{ selectedCell.baselineValue }} {{ unit }}</span><span>方案 {{ selectedCell.value }} {{ unit }}</span><b>差值 {{ selectedCell.difference }} {{ unit }}</b></div>
      <el-button type="primary" plain @click="show3D = !show3D">{{ show3D ? '收起三维空间差值' : '查看三维空间差值' }}</el-button>
      <EclipseSpatialDifference3D v-if="show3D" :run="run" :baseline="baseline" :comparison="data" :unit="unit" :selected-index="selectedIndex" @select-cell="selectedIndex = $event"/>
      <EclipseSpatialCellHistory v-if="selectedCell" :run="run" :baseline="baseline" :index="selectedCell.index" :keyword="data.keyword" :unit-system="unitSystem"/>
      <p class="spatial-note">I → / J ↑，这是按IJK索引排列的层剖面；非活动格留白。相同EGRID、活动格及二进制报告实际时间已核对，差值为方案减基线，不插值。选择已有方案即可展示，原模型不被修改。</p>
    </template>
  </section>
</template>

<style scoped>
.spatial-panel { margin: 20px 0; padding: 22px; background: linear-gradient(135deg,#f0f6fe,#fff 50%); border: 1px solid #dce7f2; border-radius: 12px; }.spatial-panel header { display: flex; justify-content: space-between; gap: 14px; }.spatial-panel header span { font-size: 10px; color: #3b78b3; font-weight: 700; letter-spacing: .1em; }.spatial-panel h3 { margin: 8px 0; font-size: 19px; color: #173b5d; }.spatial-panel p { font-size: 12px; color: #7b8da3; line-height: 1.7; }.spatial-controls { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: 18px 0; }.spatial-controls > .el-select { width: 240px; }.spatial-controls label { display: flex; align-items: center; gap: 10px; color: #54718c; font-size: 12px; }.spatial-kpis { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12px; margin: 16px 0; }.spatial-kpis > div { border: 1px solid #dfe8f2; border-radius: 8px; padding: 16px; background: #fff; }.spatial-kpis small { color: #8093a7; font-size: 11px; }.spatial-kpis strong { display: block; font-size: 24px; color: #254b70; margin: 9px 0; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }.spatial-kpis em { font-size: 11px; font-weight: 400; font-style: normal; color: #7890a7; }.spatial-kpis span { font-size: 10px; color: #92a0b1; }.spatial-maps { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 14px; }.spatial-maps article { padding: 14px; border: 1px solid #dfe7f1; background: #fff; border-radius: 9px; min-width: 0; }.spatial-maps h4 { font-size: 12px; margin: 0 0 16px; color: #355773; }.spatial-maps svg { width: 100%; max-height: 340px; display: block; background: #f5f8fc; border: 1px solid #e7edf5; }.spatial-maps rect { cursor: pointer; }.map-scale { height: 7px; margin-top: 16px; border-radius: 4px; background: linear-gradient(90deg,#1c50e3,#1ce4d1,#6ee51b,#e6da1c,#e51c1c); }.map-scale.diverging { background: linear-gradient(90deg,#266dd9,#f5f7fb,#e63d2a); }.scale-ends { display: flex; justify-content: space-between; color: #7290ab; font-size: 10px; margin-top: 7px; }.spatial-maps article > small { display: block; color: #91a0b0; font-size: 10px; margin-top: 10px; }.spatial-cell { display: flex; flex-wrap: wrap; gap: 18px; background: #e9f2fd; color: #315e87; padding: 13px; border-radius: 7px; margin-top: 14px; font-size: 12px; }.spatial-loading { padding: 45px 0; text-align: center; color: #718da8; font-size: 13px; }
@media(max-width:1000px) { .spatial-kpis { grid-template-columns: repeat(2,minmax(0,1fr)); }.spatial-maps { grid-template-columns: 1fr; }.spatial-maps svg { max-height: 300px; } } @media(max-width:550px) { .spatial-panel { padding: 12px; }.spatial-panel h3 { font-size: 16px; }.spatial-kpis strong { font-size: 18px; }.spatial-controls > .el-select { width: 100%; } }
</style>
