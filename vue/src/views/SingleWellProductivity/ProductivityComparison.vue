<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  methodType: { type: String, default: '多周期', validator: value => ['多周期', '多方法', '注采对比'].includes(value) }
})
// 保留现有对比参数；本次只统一界面，不改动计算和数据来源。
const testBackPressure = ref(false)
const testIsochronal = ref(false)
const testModifiedIsochronal = ref(false)
const testSinglePoint = ref(false)
const theoryStableFlow = ref(false)
const theoryUnstableFlow = ref(false)
const startDate = ref('')
const endDate = ref('')
// 注采类型使用独立复选框，可同时勾选，不能用单值把复选框变成互斥单选。
const comparisonTypes = ref(['注气'])
const formationPressure = ref('')
const paramsCollapsed = ref(false)
const panelWidth = ref(300)
const resultTitle = computed(() => [props.wellName, '产能对比', props.methodType, '分析结果'].filter(Boolean).join('-'))
const workspaceEl = ref(null)
const chartEl = ref(null)
let chart = null
let resizeObserver = null
let resizeFrame = 0
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => { resizeFrame = 0; chart?.resize() })
}
const renderChart = () => {
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  // 对齐稳定流的图表留白、标题和细网格；尚未接入结果，不绘制虚构曲线和数值。
  const axis = {
    type: 'value', min: 0, max: 1, nameLocation: 'middle',
    nameTextStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 13, color: '#555' },
    axisLine: { show: true, lineStyle: { color: '#555' } },
    axisTick: { show: false }, axisLabel: { show: false },
    splitLine: { show: true, lineStyle: { color: '#dfe6f1' } },
    minorTick: { show: true }, minorSplitLine: { show: true, lineStyle: { color: '#f2f5fa' } }
  }
  chart.setOption({
    animation: false,
    title: { text: props.methodType + '产能对比图', left: 'center', top: 10, textStyle: { fontFamily: 'Microsoft YaHei, Segoe UI, sans-serif', fontSize: 14, fontWeight: 600, color: '#333' } },
    grid: { left: 82, right: 48, top: 64, bottom: 64 },
    xAxis: { ...axis, name: '回压试井', nameGap: 40 },
    yAxis: { ...axis, name: '无阻流量', nameGap: 52 },
    series: []
  }, true)
  scheduleResize()
}
let dragStartX = 0
let dragStartWidth = 0
const maxPanelWidth = () => Math.max(280, Math.min(480, (workspaceEl.value?.clientWidth || 780) - 260))
const setPanelWidth = width => { panelWidth.value = Math.max(280, Math.min(maxPanelWidth(), width)) }
function startResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  dragStartX = event.clientX
  dragStartWidth = panelWidth.value
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
  window.addEventListener('pointercancel', stopResize, { once: true })
}
function resizePanel(event) { setPanelWidth(dragStartWidth + event.clientX - dragStartX) }
function stopResize() {
  window.removeEventListener('pointermove', resizePanel)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
}
function resizeWithKeyboard(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  setPanelWidth(event.key === 'Home' ? 280 : event.key === 'End' ? maxPanelWidth() : panelWidth.value + (event.key === 'ArrowRight' ? 20 : -20))
}
watch(() => props.methodType, async () => { await nextTick(); renderChart() })
watch([paramsCollapsed, panelWidth], async () => { await nextTick(); scheduleResize() })
onMounted(async () => {
  await nextTick()
  renderChart()
  resizeObserver = new ResizeObserver(() => {
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    scheduleResize()
  })
  resizeObserver.observe(chartEl.value)
  resizeObserver.observe(workspaceEl.value)
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})
const handleCalculate = () => { console.log('产能对比计算:', props.methodType) }
</script>

<template>
  <div ref="workspaceEl" class="comparison-workspace">
    <aside class="params-panel" :class="{ collapsed: paramsCollapsed }"
      :style="paramsCollapsed ? undefined : { width: panelWidth + 'px', minWidth: panelWidth + 'px', flexBasis: panelWidth + 'px' }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" aria-label="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>
        <div class="panel-body">
          <fieldset class="field-section">
            <legend class="sec-label">产能试井</legend>
            <div class="checkbox-grid">
              <label><input v-model="testBackPressure" type="checkbox" />回压试井</label>
              <label><input v-model="testIsochronal" type="checkbox" />等时试井</label>
              <label><input v-model="testModifiedIsochronal" type="checkbox" />修正等时</label>
              <label><input v-model="testSinglePoint" type="checkbox" />一点法</label>
            </div>
          </fieldset>
          <fieldset class="field-section">
            <legend class="sec-label">理论计算</legend>
            <div class="checkbox-grid">
              <label><input v-model="theoryStableFlow" type="checkbox" />稳定流</label>
              <label><input v-model="theoryUnstableFlow" type="checkbox" />不稳定流</label>
            </div>
          </fieldset>
          <fieldset v-if="methodType !== '注采对比'" class="operation-group">
            <legend>注采类型</legend>
            <div class="operation-options">
              <label v-for="direction in ['采气', '注气']" :key="direction">
                <input v-model="comparisonTypes" type="checkbox" :value="direction" />{{ direction }}
              </label>
            </div>
          </fieldset>
          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <label class="field"><span>开始日期</span><input v-model="startDate" type="date" /></label>
            <label class="field"><span>结束日期</span><input v-model="endDate" type="date" /></label>
            <label class="field pressure-field"><span>计算无阻流量的地层压力</span><input v-model="formationPressure" type="text" inputmode="decimal" /></label>
          </div>
          <div class="form-actions"><button type="button" class="calculate-button" @click="handleCalculate">计算</button></div>
        </div>
        <div class="params-resizer" role="separator" tabindex="0" aria-label="调整参数栏宽度" aria-orientation="vertical"
          :aria-valuenow="panelWidth" :aria-valuemin="280" :aria-valuemax="maxPanelWidth()" @pointerdown="startResize" @keydown="resizeWithKeyboard" />
      </template>
    </aside>
    <main class="result-area">
      <div class="result-tabs"><div class="result-tab active" :title="resultTitle">{{ resultTitle }}</div></div>
      <div class="chart-placeholder" :aria-label="methodType + '产能对比结果分析图'"><div ref="chartEl" class="comparison-chart" /></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 沿用理论稳定流的视觉规范：300px 白色参数栏、34px 标题栏和黄黑交互色。
.comparison-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; overflow: hidden; background: #fff; color: #252525; font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.params-panel { position: relative; min-height: 0; display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #d7d7d7; background: #fff; }
.params-panel.collapsed { width: 34px; min-width: 34px; flex: 0 0 34px; }
.panel-collapsed-tab { width: 100%; height: 76px; padding: 8px 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #333; font: inherit; writing-mode: vertical-rl; cursor: pointer; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; display: flex; align-items: center; justify-content: center; background: transparent; cursor: pointer; }
.panel-toggle:hover { background: #fff8d8; }
.panel-body { flex: 1; min-height: 0; padding: 10px 12px 16px; overflow-y: auto; }
.field-section { border: 0; padding: 0; margin: 0 0 9px; min-width: 0; }
.sec-label { width: 100%; height: 22px; padding: 0; margin: 8px 0 7px; display: flex; align-items: center; gap: 8px; font-weight: 500; }
.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; padding: 3px 0 8px; }
.checkbox-grid label, .operation-options label { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; font-weight: 400; white-space: nowrap; cursor: pointer; }
.checkbox-grid input, .operation-options input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.operation-group { min-width: 0; margin: 8px 0 12px; padding: 0; border: 0; }
.operation-group legend { margin-bottom: 4px; padding: 0; font-size: 13px; font-weight: 400; }
.operation-options { display: flex; align-items: center; gap: 12px; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); column-gap: 24px; }
.field { display: block; min-width: 0; margin-bottom: 9px; }
.field span { display: block; margin-bottom: 3px; color: #252525; font-size: 13px; }
.field input { display: block; width: 100%; min-width: 0; height: 24px; padding: 0 8px; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }
.field input:focus-visible { outline: 1px solid #d5b900; outline-offset: 1px; }
.pressure-field { grid-column: 1 / -1; }
.form-actions { display: flex; align-items: center; gap: 10px; }
.calculate-button { min-width: 86px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #222; font: inherit; cursor: pointer; }
.calculate-button:hover { background: #ffe033; }
button:focus-visible { outline: 2px solid #555; outline-offset: -2px; }
.params-resizer { position: absolute; z-index: 4; top: 0; right: 0; width: 5px; height: 100%; cursor: col-resize; touch-action: none; }
.params-resizer:hover, .params-resizer:focus-visible { background: #f4d000; outline: none; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.result-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.result-tab { height: 34px; line-height: 34px; max-width: min(430px, 100%); padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; color: #202020; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-placeholder { flex: 1; min-height: 0; position: relative; overflow: hidden; }
.comparison-chart { width: 100%; height: 100%; }
</style>
