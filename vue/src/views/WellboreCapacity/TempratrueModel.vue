<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { wellboreTemperatureApi } from '@/api/wellboreTemperature'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { pvtStorageApi } from '@/api/pvtStorage'
import { numberOf, productionValues } from '@/utils/temperatureSources'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const defaults = {
  tempModel: 'alves',
  boundaryPosition: 'wellhead',
  depth: 3100,
  step: 50,
  idTubing: 62,
  tGrad: 3,
  angle: 0,
  gammaG: 0.65,
  rhoL: 1000,
  muL: 0.9,
  roughness: 0.016,
  tSurf: 20,
  uTo: 8,
  wallMm: 6.35,
  muJt: 9,
  cpGas: 2200,
  formationK: 2.5,
  formationRhoCp: 2.3,
  referencePressure: 3.8,
  tWh: 30,
  qGas: 2.5,
  qLiq: 2
}
const form = reactive({ ...defaults })
const result = ref(null)
const calculatedInput = ref(null)
const error = ref('')
const busy = ref(false)
const sourceLoading = ref(false)
const chartEl = ref(null)
const panel = ref(null)
const panelWidth = ref(238)

let chart
let observer
let disposed = false
let loadSequence = 0
let oldCursor = ''
let oldSelect = ''

const ctx = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: String(props.node?.wellName ?? '').trim()
})
const data = value => value?.data ?? value

const normalizeStoredResult = detail => {
  const stored = data(detail)
  if (!stored?.record || !Array.isArray(stored.profile)) return stored

  const record = stored.record
  return {
    record,
    tempModel: String(record.modelCode || 'alves').toLowerCase(),
    depth: stored.profile.map(point => point.depthM),
    temp: stored.profile.map(point => point.fluidTemperatureC),
    tempFormation: stored.profile.map(point => point.formationTemperatureC),
    inferredBottomTemperature: record.bottomFluidTemperatureC,
    predictedWellheadTemperature: record.predictedWellheadTemperatureC,
    gravityGradient: record.gravityGradient,
    thermal: {
      relaxationDistance: record.relaxationDistanceM,
      dimensionlessTime: record.dimensionlessTime,
      timeFunction: record.timeFunction,
      mixtureHeatCapacity: record.mixtureHeatCapacityJKgk
    }
  }
}

async function loadLatestResult (context) {
  const history = data(await wellboreTemperatureApi.list(
    context.projectId,
    context.gasReservoirId,
    context.wellName
  )) || []
  const latest = history[0]
  if (!latest?.id) return

  result.value = normalizeStoredResult(await wellboreTemperatureApi.detail(
    latest.id,
    context.projectId,
    context.gasReservoirId,
    context.wellName
  ))
  await draw()
}
const groups = computed(() => [
  {
    title: '井身结构及物性',
    fields: [
      ['depth', '测井深度 (m)', 0.001, 100000],
      ['idTubing', '油管内径 (mm)', 0.001, 2000],
      ['tGrad', '地温梯度 (℃/100m)', 0, 100],
      ['angle', '井斜角 (°)', 0, 90],
      ['step', '计算步长 (m)', 0.001, 100000],
      ['gammaG', '气体相对密度', 0.001, 10],
      ['rhoL', '液体密度 (kg/m³)', 0.001, 10000],
      ['muL', '液体黏度 (mPa·s)', 0.000001, 100000],
      ['roughness', '管内壁粗糙度 (mm)', 0, 100]
    ]
  },
  {
    title: '温度模型参数',
    fields: [
      ['tSurf', '地表温度 (℃)', -273.14, 1000],
      ['uTo', '总传热系数 (W/m²·K)', 0.001, 100000],
      ['wallMm', '油管壁厚 (mm)', 0, 500],
      ['muJt', '焦耳–汤姆逊系数 (K/MPa)', -1000, 1000],
      ['cpGas', '气体定压比热 (J/kg·K)', 0.001, 100000],
      ['formationK', '地层导热系数 (W/m·K)', 0.001, 1000],
      ['formationRhoCp', '地层体积热容 (MJ/m³·K)', 0.001, 1000]
    ]
  },
  {
    title: '生产数据',
    fields: [
      [
        'referencePressure',
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
  const context = ctx()
  if (!context.wellName) {
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
      context.projectId,
      context.gasReservoirId,
      context.wellName
    )
    if (disposed || sequence !== loadSequence) return
    const production = productionValues(source.production, 'wellhead', source.productionFields)
    const initial = {
      ...defaults,
      ...source.input,
      ...production,
      referencePressure: production.fWh ?? defaults.referencePressure
    }
    delete initial.fWh
    const defaultPvt = source.pvtRecords?.[0]
    if (defaultPvt?.pvtId) {
      try {
        const detail = data(await pvtStorageApi.getDetail(
          defaultPvt.pvtId,
          context.projectId,
          context.gasReservoirId,
          context.wellName
        ))
        initial.gammaG = numberOf(detail?.gasInput?.specificGravity) ?? initial.gammaG
        initial.rhoL = numberOf(detail?.waterResults?.[0]?.density) ?? initial.rhoL
        initial.muL = numberOf(detail?.waterResults?.[0]?.viscosity) ?? initial.muL
      } catch (pvtError) {
        source.errors.push(`PVT物性读取失败：${pvtError?.msg || pvtError?.message || '接口异常'}`)
      }
    }
    for (const key of Object.keys(defaults)) {
      if (
        initial[key] == null
        || (typeof defaults[key] === 'number' && !Number.isFinite(Number(initial[key])))
      ) {
        initial[key] = defaults[key]
      }
    }
    Object.assign(form, initial)

    try {
      await loadLatestResult(context)
    } catch (historyError) {
      source.errors.push(`温度历史读取失败：${historyError?.msg || historyError?.message || '接口异常'}`)
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
      text: '温度分布曲线',
      left: 'center',
      top: 12,
      textStyle: { color: '#333', fontSize: 16, fontWeight: 600 }
    },
    legend: { right: 38, top: 58, orient: 'vertical' },
    tooltip: {
      trigger: 'axis',
      axisPointer: { axis: 'y' },
      formatter: points => {
        if (!points?.length) return ''
        const depth = Number(points[0].value?.[1])
        const title = Number.isFinite(depth)
          ? `井深 ${depth.toLocaleString('zh-CN', {
              minimumFractionDigits: 2,
              maximumFractionDigits: 2
            })} m`
          : ''
        const values = points.map(point => {
          const temperature = Number(point.value?.[0])
          const temperatureText = Number.isFinite(temperature)
            ? temperature.toFixed(2)
            : '-'
          return `${point.marker}${point.seriesName}　${temperatureText} ℃`
        }).join('<br/>')
        return `${title}<br/>${values}`
      }
    },
    grid: { left: 70, right: 28, top: 58, bottom: 58 },
    xAxis: {
      type: 'value',
      name: '温度 (℃)',
      scale: true,
      min: Math.min(current.temp[0], current.tempFormation[0]),
      nameLocation: 'middle',
      nameGap: 34,
      splitLine: { lineStyle: { color: '#dfe7f2' } }
    },
    yAxis: {
      type: 'value',
      name: '井深 (m)',
      inverse: true,
      min: 0,
      max: current.depth.at(-1),
      nameLocation: 'middle',
      nameGap: 48,
      splitLine: { lineStyle: { color: '#dfe7f2' } }
    },
    series: [
      {
        name: '流体温度',
        type: 'line',
        showSymbol: false,
        data: current.depth.map((depth, index) => [current.temp[index], depth])
      },
      {
        name: '地层温度',
        type: 'line',
        showSymbol: false,
        lineStyle: { type: 'dashed' },
        data: current.depth.map((depth, index) => [current.tempFormation[index], depth])
      }
    ]
  }, true)
}

async function calculate () {
  error.value = ''
  busy.value = true
  try {
    const calculation = { ...ctx(), ...form }
    result.value = data(await wellboreTemperatureApi.calculate(calculation))
    calculatedInput.value = calculation
    await draw()
  } catch (calculateError) {
    error.value = calculateError?.msg || calculateError?.response?.data?.msg || calculateError?.message || '温度计算失败'
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!calculatedInput.value) {
    error.value = '请先计算温度分布，再保存结果'
    return
  }

  error.value = ''
  busy.value = true
  try {
    result.value = normalizeStoredResult(await wellboreTemperatureApi.save({
      calculation: calculatedInput.value
    }))
    calculatedInput.value = null
    await draw()
    ElMessage.success('温度模型计算结果已保存')
  } catch (saveError) {
    error.value = saveError?.msg
      || saveError?.response?.data?.msg
      || saveError?.message
      || '温度模型保存失败'
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
watch(form, () => {
  calculatedInput.value = null
}, { deep: true })

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
  <section class="temperature-workspace">
    <div class="layout">
      <aside
        ref="panel"
        class="params-panel"
        :style="{ width: panelWidth + 'px', minWidth: panelWidth + 'px' }"
      >
        <div class="panel-head">参数设置</div>

        <div class="panel-body">
          <div class="field model-field">
            <label>温度模型</label>
            <el-select
              v-model="form.tempModel"
              :disabled="busy || sourceLoading"
              size="small"
            >
              <el-option value="alves" label="Alves分段能量平衡" />
              <el-option value="linear" label="井口锚定线性模型" />
            </el-select>
          </div>

          <section
            v-for="group in groups"
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
<!--            <span class="boundary-label">压力/温度位置</span>-->
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
              计算温度分布
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
        <header>{{ node.wellName }} · 温度模型</header>

        <div v-if="result" class="summary">
          <span>
            {{ result.tempModel === 'linear'
              ? '井口锚定线性模型'
              : 'Alves分段能量平衡' }}
          </span>
          <span>
            井底流体温度
            <b>{{ result.inferredBottomTemperature?.toFixed(2) }} ℃</b>
          </span>
          <template v-if="result.tempModel === 'alves'">
            <span>
              热松弛距离
              <b>{{ result.thermal?.relaxationDistance?.toFixed(1) }} m</b>
            </span>
            <span>
              无量纲时间
              <b>{{ result.thermal?.dimensionlessTime?.toFixed(3) }}</b>
            </span>
          </template>
        </div>

        <el-alert
          title="当前温度模块独立计算，未启用压力耦合和JT压力项。"
          type="info"
          :closable="false"
        />
        <div ref="chartEl" class="chart" />
      </main>
    </div>
  </section>
</template>

<style scoped>
.temperature-workspace {
  height: 100%;
  min-height: 0;
  background: #fff;
  color: #333;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
}

.layout {
  display: flex;
  height: 100%;
  min-height: 0;
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

.model-field {
  margin-top: 4px;
}

.section-title {
  margin: 10px 0 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.parameter-section {
  margin-top: 10px;
  padding-top: 1px;
  border-top: 1px solid #ebeef5;
}

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

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
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
  gap: 18px;
  padding: 7px 12px;
  font-size: 12px;
}

.chart {
  flex: 1;
  min-height: 250px;
}

main :deep(.el-alert) {
  border-radius: 0;
}
</style>
