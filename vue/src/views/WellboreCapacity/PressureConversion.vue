<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { wellborePressureApi } from '@/api/wellborePressure'
import { pvtStorageApi } from '@/api/pvtStorage'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { numberOf, productionValues } from '@/utils/temperatureSources'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const defaults = {
  operationMode: 'production',
  boundaryPosition: 'wellhead',
  boundaryPressure: 3.8,
  depth: 3100,
  step: 50,
  idTubing: 62,
  roughness: 0.016,
  angle: 0,
  tWh: 30,
  tGrad: 3,
  gammaG: 0.65,
  rhoL: 1000,
  muL: 0.9,
  qGas: 2.5,
  qLiq: 2,
  models: ['HB', 'MB']
}

const form = reactive({ ...defaults, models: [...defaults.models] })
const result = ref(null)
const calculatedInput = ref(null)
const error = ref('')
const sourceLoading = ref(false)
const busy = ref(false)
const chartEl = ref(null)
const chartAreaEl = ref(null)
const legendEl = ref(null)
const legendPosition = ref(null)
const draggingLegend = ref(false)
const legendItems = computed(() => Object.keys(result.value?.methods || {}).map((code, index) => ({
  name: code === 'HB' ? 'Hagedorn & Brown' : 'Mukherjee & Brill',
  color: ['#5470c6', '#91cc75'][index % 2]
})))
const legendSelected = reactive({ 'Hagedorn & Brown': true, 'Mukherjee & Brill': true })
const legendStyle = computed(() => legendPosition.value
  ? { left: legendPosition.value.x + 'px', top: legendPosition.value.y + 'px' }
  : { right: '38px', top: '52px' })
let legendDragOffset = { x: 0, y: 0 }

function clampLegendPosition (x, y) {
  const area = chartAreaEl.value
  const legend = legendEl.value
  if (!area || !legend) return
  legendPosition.value = {
    x: Math.max(0, Math.min(Math.max(0, area.clientWidth - legend.offsetWidth), x)),
    y: Math.max(0, Math.min(Math.max(0, area.clientHeight - legend.offsetHeight), y))
  }
}
function moveLegend (event) {
  if (!draggingLegend.value || !chartAreaEl.value) return
  const area = chartAreaEl.value.getBoundingClientRect()
  clampLegendPosition(event.clientX - area.left - legendDragOffset.x, event.clientY - area.top - legendDragOffset.y)
}
function stopLegendDrag () {
  draggingLegend.value = false
  window.removeEventListener('pointermove', moveLegend)
  window.removeEventListener('pointerup', stopLegendDrag)
  window.removeEventListener('pointercancel', stopLegendDrag)
}
function startLegendDrag (event) {
  if (event.button !== 0 || !legendEl.value) return
  event.preventDefault()
  const rect = legendEl.value.getBoundingClientRect()
  legendDragOffset = { x: event.clientX - rect.left, y: event.clientY - rect.top }
  draggingLegend.value = true
  window.addEventListener('pointermove', moveLegend)
  window.addEventListener('pointerup', stopLegendDrag)
  window.addEventListener('pointercancel', stopLegendDrag)
}
function toggleLegend (name) {
  legendSelected[name] = !legendSelected[name]
  chart?.dispatchAction({ type: legendSelected[name] ? 'legendSelect' : 'legendUnSelect', name })
}
const panel = ref(null)
const panelWidth = ref(238)
const paramsCollapsed = ref(false)
const activeParamTab = ref('input')
watch(paramsCollapsed, async () => { await nextTick(); chart?.resize() })

let chart
let observer
let disposed = false
let loadSequence = 0
let oldCursor = ''
let oldSelect = ''

const context = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: String(props.node?.wellName ?? '').trim()
})

const unwrap = value => value?.data ?? value

const normalizeStoredResult = detail => {
  const stored = unwrap(detail)
  if (!stored?.record || !Array.isArray(stored.methods) || !stored.profiles) {
    return stored
  }

  const methods = {}
  for (const method of stored.methods) {
    methods[method.methodCode] = {
      methodCode: method.methodCode,
      profile: (stored.profiles[method.id] || []).map(point => ({
        depth: point.depthM,
        temperature: point.temperatureC,
        pressure: point.pressureMpa,
        segmentAveragePressure: point.segmentAvgPressureMpa,
        segmentAverageTemperature: point.segmentAvgTemperatureC,
        gasVolumeFactor: point.gasVolumeFactor,
        gasDensity: point.gasDensityKgM3,
        gasViscosity: point.gasViscosityMpas,
        liquidDensity: point.liquidDensityKgM3,
        liquidViscosity: point.liquidViscosityMpas,
        gradient: point.pressureGradientMpaPerM,
        iterationCount: point.segmentIterationCount,
        segmentConverged: point.segmentConverged
      })),
      allSegmentsConverged: method.converged,
      maxIterationCount: method.maxSegmentIterationCount,
      nonconvergedSegmentCount: method.nonconvergedSegmentCount
    }
  }

  return {
    record: stored.record,
    methods,
    depth: Object.values(methods)[0]?.profile.map(point => point.depth) || []
  }
}

async function loadLatestResult (currentContext) {
  const history = unwrap(await wellborePressureApi.list(
    currentContext.projectId,
    currentContext.gasReservoirId,
    currentContext.wellName
  )) || []
  const latest = history[0]
  if (!latest?.id) return

  result.value = normalizeStoredResult(await wellborePressureApi.detail(
    latest.id,
    currentContext.projectId,
    currentContext.gasReservoirId,
    currentContext.wellName
  ))
  await draw()
}

const parameterGroups = computed(() => [
  {
    title: '井身结构及物性',
    fields: [
      ['depth', '测井深度 (m)', 0.001, 100000],
      ['step', '计算步长 (m)', 0.001, 100000],
      ['idTubing', '油管内径 (mm)', 0.001, 2000],
      ['roughness', '管内壁粗糙度 (mm)', 0, 100],
      ['angle', '井斜角 (°)', 0, 90],
      ['tGrad', '地温梯度 (℃/100m)', 0, 100],
      ['gammaG', '气体相对密度', 0.001, 10],
      ['rhoL', '液体密度 (kg/m³)', 0.001, 10000],
      ['muL', '液体黏度 (mPa·s)', 0.000001, 100000]
    ]
  },
  {
    title: '生产数据',
    fields: [
      [
        'boundaryPressure',
        form.boundaryPosition === 'wellhead' ? '井口油压 (MPa)' : '井底油压 (MPa)',
        0.000001,
        1000
      ],
      [
        'tWh',
        form.boundaryPosition === 'wellhead' ? '井口温度 (℃)' : '井底温度 (℃)',
        -273.14,
        1000
      ],
      ['qGas', '日产气量 (×10⁴ m³/d)', 0, 1000000],
      ['qLiq', '日产水量 (m³/d)', 0, 1000000]
    ]
  }
])

async function refresh () {
  const sequence = ++loadSequence
  const currentContext = context()
  if (!currentContext.wellName) {
    error.value = '请先在左侧目录选择井'
    return
  }

  sourceLoading.value = true
  error.value = ''
  result.value = null
  calculatedInput.value = null
  chart?.clear()
  try {
    const source = await loadTemperatureSources(
      currentContext.projectId,
      currentContext.gasReservoirId,
      currentContext.wellName
    )
    if (disposed || sequence !== loadSequence) return

    const production = productionValues(
      source.production,
      'wellhead',
      source.productionFields
    )
    const initial = {
      ...defaults,
      ...source.input,
      ...production,
      boundaryPressure: production.fWh ?? defaults.boundaryPressure,
      models: [...defaults.models]
    }
    delete initial.fWh

    const latestPvt = source.pvtRecords?.at(-1)
    if (latestPvt?.pvtId) {
      try {
        const detail = unwrap(await pvtStorageApi.getDetail(
          latestPvt.pvtId,
          currentContext.projectId,
          currentContext.gasReservoirId,
          currentContext.wellName
        ))
        initial.gammaG = numberOf(detail?.gasInput?.specificGravity) ?? initial.gammaG
        initial.rhoL = numberOf(detail?.waterResults?.[0]?.density) ?? initial.rhoL
        initial.muL = numberOf(detail?.waterResults?.[0]?.viscosity) ?? initial.muL
      } catch (pvtError) {
        source.errors.push(`PVT物性读取失败：${pvtError?.msg || pvtError?.message || '接口异常'}`)
      }
    }

    for (const key of Object.keys(defaults)) {
      if (key === 'models') continue
      if (initial[key] == null || !Number.isFinite(Number(initial[key]))) {
        initial[key] = defaults[key]
      }
    }

    Object.assign(form, initial, { models: [...initial.models] })

    try {
      await loadLatestResult(currentContext)
    } catch (historyError) {
      source.errors.push(`压力历史读取失败：${historyError?.msg || historyError?.message || '接口异常'}`)
    }
    if (source.errors.length) {
      error.value = source.errors.join('；')
    }
  } catch (sourceError) {
    if (!disposed && sequence === loadSequence) {
      error.value = sourceError?.msg || sourceError?.message || '源数据加载失败'
    }
  } finally {
    if (sequence === loadSequence) {
      sourceLoading.value = false
    }
  }
}

async function draw () {
  await nextTick()
  if (!result.value || disposed) return

  chart ??= echarts.init(chartEl.value)
  const current = result.value
  chart.setOption({
    animation: false,
    color: ['#5470c6', '#91cc75'],
    title: {
      text: '压力分布曲线',
      left: 'center',
      top: 2,
      textStyle: { fontSize: 14, color: '#333', fontWeight: 600 }
    },
    legend: { show: false, selected: { ...legendSelected } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { axis: 'y' },
      formatter: points => {
        if (!points?.length) return ''
        const depth = Number(points[0].value?.[1])
        const depthText = Number.isFinite(depth)
          ? depth.toLocaleString('zh-CN', {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2
            })
          : '-'
        const methodRows = points.map(point => {
          const pressure = Number(point.value?.[0])
          const pressureText = Number.isFinite(pressure)
            ? `${pressure.toFixed(4)} MPa`
            : '-'
          return `${point.marker}${point.seriesName}<span class="pressure-tooltip-value">${pressureText}</span>`
        }).join('<br/>')
        return `<strong>井深 ${depthText} m</strong><br/>${methodRows}`
      }
    },
    grid: { left: 64, right: 26, top: 40, bottom: 46 },
    xAxis: {
      type: 'value',
      name: '压力 (MPa)',
      scale: true,
      nameLocation: 'middle',
      nameGap: 34,
      axisLine: { show: true, lineStyle: { color: '#888' } },
      axisLabel: { color: '#555', fontSize: 12 },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    yAxis: {
      type: 'value',
      name: '井深 (m)',
      inverse: true,
      min: 0,
      max: current.depth?.at(-1),
      nameLocation: 'middle',
      nameGap: 48,
      axisLine: { show: true, lineStyle: { color: '#888' } },
      axisLabel: { color: '#555', fontSize: 12 },
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { lineStyle: { color: '#dce5f2' } }
    },
    series: Object.entries(current.methods || {}).map(([code, method]) => ({
      name: code === 'HB' ? 'Hagedorn & Brown' : 'Mukherjee & Brill',
      type: 'line',
      showSymbol: false,
      data: (method.profile || []).map(point => [point.pressure, point.depth])
    }))
  }, true)
}

async function calculate () {
  error.value = ''
  if (!form.models.length) {
    error.value = '请选择HB或MB折算方法'
    return
  }

  busy.value = true
  try {
    const calculation = {
      ...context(),
      ...form,
      models: [...form.models]
    }
    result.value = unwrap(await wellborePressureApi.calculate(calculation))
    calculatedInput.value = calculation
    await draw()
  } catch (calculateError) {
    error.value = calculateError?.msg
      || calculateError?.response?.data?.msg
      || calculateError?.message
      || '压力折算失败'
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!calculatedInput.value) {
    error.value = '请先计算压力折算，再保存结果'
    return
  }

  error.value = ''
  busy.value = true
  try {
    result.value = normalizeStoredResult(await wellborePressureApi.save({
      calculation: calculatedInput.value
    }))
    calculatedInput.value = null
    await draw()
    ElMessage.success('压力折算计算结果已保存')
  } catch (saveError) {
    error.value = saveError?.msg
      || saveError?.response?.data?.msg
      || saveError?.message
      || '压力折算保存失败'
  } finally {
    busy.value = false
  }
}

function move (event) {
  const left = panel.value?.getBoundingClientRect().left ?? 0
  panelWidth.value = Math.max(238, Math.min(520, event.clientX - left))
}

function stop () {
  window.removeEventListener('pointermove', move)
  window.removeEventListener('pointerup', stop)
  document.body.style.cursor = oldCursor
  document.body.style.userSelect = oldSelect
}

function resize (event) {
  event.preventDefault()
  oldCursor = document.body.style.cursor
  oldSelect = document.body.style.userSelect
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', stop, { once: true })
}

watch(
  () => props.node?.wellName,
  (current, previous) => {
    if (current && current !== previous) refresh()
  }
)
watch(form, () => { calculatedInput.value = null }, { deep: true })

onMounted(() => {
  refresh()
  observer = new ResizeObserver(() => {
    chart?.resize()
    if (legendPosition.value) clampLegendPosition(legendPosition.value.x, legendPosition.value.y)
  })
  observer.observe(chartEl.value)
})

onBeforeUnmount(() => {
  disposed = true
  loadSequence++
  stop()
  stopLegendDrag()
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <section class="pressure-workspace">
    <aside
      ref="panel"
      class="params-panel"
      :class="{ collapsed: paramsCollapsed }"
      :style="{ width: paramsCollapsed ? '22px' : panelWidth + 'px', minWidth: paramsCollapsed ? '22px' : panelWidth + 'px' }"
    >
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" @click="paramsCollapsed = false">参数设置</button>
      <div v-show="!paramsCollapsed" class="panel-head">
        <span>参数设置</span>
        <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z"/></svg>
        </button>
      </div>

      <div v-show="!paramsCollapsed && activeParamTab === 'input'" class="panel-body">
        <section class="method-section">
          <div class="section-title">折算方法</div>
          <el-checkbox-group v-model="form.models" :disabled="busy || sourceLoading">
            <el-checkbox value="HB">Hagedorn &amp; Brown</el-checkbox>
            <el-checkbox value="MB">Mukherjee &amp; Brill</el-checkbox>
          </el-checkbox-group>
        </section>

        <section
          v-for="group in parameterGroups"
          :key="group.title"
          class="parameter-section"
        >
          <div class="section-title">{{ group.title }}</div>
          <div class="parameter-grid">
            <div
              v-for="[key, label, min, max] in group.fields"
              :key="key"
              class="field"
            >
              <label :for="`pressure-${key}`">{{ label }}</label>
              <el-input-number
                :id="`pressure-${key}`"
                v-model="form[key]"
                :min="min"
                :max="max"
                :controls="false"
                :disabled="busy || sourceLoading"
                size="small"
              />
            </div>
          </div>
        </section>

        <div class="boundary-selector">
          <span class="boundary-label">压力／温度位置</span>
          <el-radio-group
            v-model="form.boundaryPosition"
            :disabled="busy || sourceLoading"
            size="small"
          >
            <el-radio-button value="wellhead">井口</el-radio-button>
            <el-radio-button value="bottomhole">井底</el-radio-button>
          </el-radio-group>
        </div>

        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
        />

        <div class="actions">
          <el-button
            :loading="busy"
            :disabled="sourceLoading"
            size="small"
            class="calculate-button"
            @click="calculate"
          >
            计算压力折算
          </el-button>
          <el-button
            :disabled="busy || sourceLoading || !calculatedInput"
            size="small"
            @click="save"
          >
            保存
          </el-button>
        </div>
      </div>

      <div v-show="!paramsCollapsed && activeParamTab === 'output'" class="panel-body">
        <div v-if="!result" class="section-title">请先计算压力分布</div>
        <section v-for="(method, code) in result?.methods || {}" :key="code" class="parameter-section">
          <div class="section-title">{{ code === 'HB' ? 'Hagedorn & Brown' : 'Mukherjee & Brill' }}</div>
          <div class="field">
            <label>井底压力（MPa）</label>
            <el-input :model-value="method.profile?.at(-1)?.pressure?.toFixed(4) ?? '—'" readonly size="small" />
          </div>
          <div class="field">
            <label>井段收敛状态</label>
            <el-input :model-value="method.allSegmentsConverged ? '全部井段收敛' : `未收敛井段 ${method.nonconvergedSegmentCount}`" readonly size="small" />
          </div>
        </section>
      </div>
      <div v-show="!paramsCollapsed" class="param-tabs" role="tablist" aria-label="参数面板">
        <button v-for="tab in [['input', '输入'], ['output', '输出']]" :key="tab[0]" type="button" role="tab" class="param-tab" :class="{ active: activeParamTab === tab[0] }" :aria-selected="activeParamTab === tab[0]" @click="activeParamTab = tab[0]">{{ tab[1] }}</button>
      </div>
      <div v-if="!paramsCollapsed" class="resizer" @pointerdown="resize" />
    </aside>

    <main v-loading="busy">
      <div class="dynamic-result-tabs">
        <div class="dynamic-result-tab active" :title="`${node.wellName}-压力折算-折算方法-分析结果`">{{ node.wellName }}-压力折算-折算方法-分析结果</div>
      </div>

      <div v-if="result" class="summary">
        <span v-for="(method, code) in result.methods" :key="code">
          {{ code }}：井底
          {{ method.profile.at(-1)?.pressure?.toFixed(4) }} MPa；
          {{ method.allSegmentsConverged
            ? '全部井段收敛'
            : `未收敛井段 ${method.nonconvergedSegmentCount}` }}
        </span>
      </div>

      <div ref="chartAreaEl" class="chart-container">
          <div ref="chartEl" class="chart" />
          <div v-if="result" ref="legendEl" class="floating-chart-legend" :class="{ dragging: draggingLegend }" :style="legendStyle" title="拖动调整图例位置" @pointerdown="startLegendDrag">
            <button v-for="item in legendItems" :key="item.name" type="button" class="floating-legend-item" :class="{ hidden: !legendSelected[item.name] }" :aria-pressed="legendSelected[item.name]" :title="legendSelected[item.name] ? '点击隐藏曲线' : '点击显示曲线'" @pointerdown.stop @click="toggleLegend(item.name)">
              <span class="legend-dot" :style="{ backgroundColor: legendSelected[item.name] ? item.color : 'transparent', borderColor: item.color }"></span>
              <span>{{ item.name }}</span>
            </button>
          </div>
          <div v-if="!result" class="chart-empty">请输入参数并计算压力分布</div>
        </div>
    </main>
  </section>
</template>

<style scoped>
.pressure-workspace {
  display: flex;
  height: 100%;
  min-height: 0;
  background: #fff;
  color: #333;
  font-family: Arial, sans-serif;
  font-size: 13px;
  overflow: hidden;
}

.params-panel {
  position: relative;
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #e0e0e0;
  transition: width 0.16s ease, min-width 0.16s ease;
}

.panel-head {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  padding: 7px 12px 6px;
  justify-content: space-between;
  border-bottom: 1px solid #f0f0f0;
  color: #333;
  font-size: 13px;
}

.panel-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 4px 12px 14px;
}

.section-title {
  margin: 10px 0 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.parameter-section { margin: 0; padding: 0; border: 0; }
.section-title:first-child { margin-top: 4px; }

.parameter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.field {
  margin-bottom: 9px;
}

.field label {
  display: block;
  margin-bottom: 3px;
  color: #555;
  font-size: 12px;
}

.el-select,
.el-input-number {
  width: 100%;
}

:deep(.el-input-number .el-input__inner) {
  text-align: left !important;
}

/* 数字框、普通输出框和下拉框统一基线，覆盖数字控件默认的 15px 留白。 */
.params-panel :deep(.el-input__wrapper),
.params-panel :deep(.el-input-number.is-without-controls .el-input__wrapper),
.params-panel :deep(.el-select__wrapper) {
  box-sizing: border-box;
  min-height: 24px;
  height: 24px;
  padding: 0 6px;
  font-family: Arial, sans-serif;
  font-size: 12px;
}
.params-panel :deep(.el-input__inner),
.params-panel :deep(.el-select__selected-item),
.params-panel :deep(.el-radio-button__inner),
.params-panel :deep(.el-button) {
  font-family: Arial, sans-serif;
  font-size: 12px;
  font-weight: 400;
}
.params-panel :deep(.el-input__inner) {
  height: 22px;
  line-height: 22px;
  text-align: left;
}
.params-panel :deep(.el-select__selected-item) { line-height: 22px; }

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.boundary-selector {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.boundary-label {
  flex-shrink: 0;
  color: #555;
  font-size: 12px;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

.resizer {
  position: absolute;
  z-index: 2;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
}

.resizer:hover {
  background: rgba(64, 132, 217, 0.18);
}

main {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.chart {
  flex: 1;
  min-height: 0;
  height: 100%;
  width: 100%;
}

main :deep(.el-alert) {
  border-radius: 0;
}

/* 参数栏沿用水侵分析的分组间距与控件默认样式，右侧仅保留原有摘要和曲线。 */
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.panel-collapsed-tab { width: 22px; height: 76px; padding: 0; writing-mode: vertical-rl; border: 1px solid #e0e0e0; border-left: 0; background: #fff; color: #333; font: inherit; cursor: pointer; }
.params-panel.collapsed { border-right: 0; }
.panel-toggle:hover, .panel-collapsed-tab:hover { background: #fff8d8; }
.param-tabs { display: flex; height: 30px; flex-shrink: 0; border-top: 1px solid #e0e0e0; }
.param-tab { flex: 1; border: 0; border-right: 1px solid #e0e0e0; background: #fff; color: #555; font: inherit; cursor: pointer; }
.param-tab.active { background: #f4d000; color: #1a1a1a; font-weight: 600; }
.dynamic-result-tabs { display: flex; height: 34px; flex-shrink: 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.dynamic-result-tab { display: flex; align-items: center; max-width: 340px; padding: 0 12px; background: #f4d000; color: #202020; font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-container { position: relative; flex: 1; min-height: 0; overflow: hidden; }
.floating-chart-legend { position: absolute; z-index: 5; display: flex; flex-direction: column; gap: 5px; max-width: 280px; padding: 7px 10px; border: 1px solid #eee; background: rgba(255,255,255,.9); color: #333; font-size: 12px; line-height: 1.2; cursor: move; touch-action: none; user-select: none; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.floating-chart-legend.dragging { box-shadow: 0 4px 12px rgba(0,0,0,.14); }
.floating-legend-item { display: flex; align-items: center; gap: 5px; padding: 0; border: 0; background: transparent; color: inherit; font: inherit; white-space: nowrap; cursor: pointer; }
.floating-legend-item.hidden { color: #999; opacity: .55; }
.legend-dot { width: 10px; height: 10px; border: 1px solid transparent; border-radius: 50%; flex-shrink: 0; box-sizing: border-box; }
.chart-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: #999; pointer-events: none; }
.summary { display: flex; flex-shrink: 0; flex-wrap: wrap; align-items: center; gap: 4px 12px; padding: 5px 12px; font-size: 12px; line-height: 18px; color: #333; background: #fafafa; border-bottom: 1px solid #eef0f3; }
.summary > span + span { padding-left: 12px; border-left: 1px solid #dfe3e8; }
.actions :deep(.el-button) { margin-left: 0; border-radius: 4px; color: #202020; background: #fff; border-color: #c9cdd3; }
.actions :deep(.calculate-button) { background: #f4d000; border-color: #d5b900; }
.boundary-selector :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: #f4d000; border-color: #d5b900; color: #202020; box-shadow: -1px 0 0 #d5b900; }
.method-section :deep(.el-checkbox) {
  display: flex;
  height: 24px;
  margin-right: 0;
}

.method-section :deep(.el-checkbox__label) {
  color: #303133;
  font-size: 13px;
}

.method-section :deep(.el-checkbox__inner) {
  border-color: #c0c4cc;
}

.method-section :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  border-color: #303133;
  background-color: #303133;
}

.method-section :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: #303133;
}


:global(.pressure-tooltip-value) { float: right; margin-left: 24px; font-weight: 600; }
</style>
