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
const panel = ref(null)
const panelWidth = ref(238)

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
    color: ['#0037b5', '#333'],
    title: {
      text: '压力分布曲线',
      left: 'center',
      top: 12,
      textStyle: { fontSize: 16, color: '#333' }
    },
    legend: { top: 43 },
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
    grid: { left: 70, right: 28, top: 80, bottom: 58 },
    xAxis: {
      type: 'value',
      name: '压力 (MPa)',
      scale: true,
      nameLocation: 'middle',
      nameGap: 34,
      splitLine: { lineStyle: { color: '#dfe7f2' } }
    },
    yAxis: {
      type: 'value',
      name: '井深 (m)',
      inverse: true,
      min: 0,
      max: current.depth?.at(-1),
      nameLocation: 'middle',
      nameGap: 48,
      splitLine: { lineStyle: { color: '#dfe7f2' } }
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
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(chartEl.value)
})

onBeforeUnmount(() => {
  disposed = true
  loadSequence++
  stop()
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <section class="pressure-workspace">
    <aside
      ref="panel"
      class="params-panel"
      :style="{ width: panelWidth + 'px', minWidth: panelWidth + 'px' }"
    >
      <div class="panel-head">参数设置</div>

      <div class="panel-body">
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
              <label>{{ label }}</label>
              <el-input-number
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
<!--          <span class="boundary-label">压力/温度位置</span>-->
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

      <div class="resizer" @pointerdown="resize" />
    </aside>

    <main v-loading="busy">
      <header>{{ node.wellName }} · 压力折算 · 折算方法</header>

      <div v-if="result" class="summary">
        <span v-for="(method, code) in result.methods" :key="code">
          {{ code }}：井底
          {{ method.profile.at(-1)?.pressure?.toFixed(4) }} MPa；
          {{ method.allSegmentsConverged
            ? '全部井段收敛'
            : `未收敛井段 ${method.nonconvergedSegmentCount}` }}
        </span>
      </div>

      <div ref="chartEl" class="chart" />
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
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
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
  margin: 11px 0 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.section-title:first-child {
  margin-top: 4px;
}

.parameter-section {
  margin-top: 10px;
  padding-top: 1px;
  border-top: 1px solid #ebeef5;
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

.parameter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  column-gap: 24px;
}

.el-input-number {
  width: 100%;
}

:deep(.el-input-number .el-input__inner) {
  text-align: left !important;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

.boundary-selector {
  display: flex;
  align-items: center;
  justify-content: space-between;
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

main header {
  padding: 7px 12px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafafa;
  color: #409eff;
  font-size: 13px;
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 8px 12px;
  font-size: 12px;
}

.chart {
  flex: 1;
  min-height: 250px;
}

:global(.pressure-tooltip-value) {
  float: right;
  margin-left: 24px;
  font-weight: 600;
}
</style>
