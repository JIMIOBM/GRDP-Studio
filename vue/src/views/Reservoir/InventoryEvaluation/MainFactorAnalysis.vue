<script setup>
/**
 * 库级「主控因素分析」。
 *
 * 对四个因素同时展示理论值与实际值并给出差异：
 *   ① 地层压力 —— 理论值来自原平台「物质平衡方程 → 计算地层压力」工具箱，实际值取实测静压
 *   ② 动用的孔隙体积 —— 实际值由 Vp = G·Bg/(1−Swi) 计算
 *   ③ 天然气 —— 理论值是设计地质储量（手输），实际值是动态地质储量
 *   ④ 气体饱和度 —— 实际值由 Sg = (G−Gp)·Bg/Vp 计算
 *
 * 单位换算全部在后端完成：左侧参数栏里的入参是**原平台口径**（Pa / K / 小数 / 1/Pa），
 * 右侧结果表里是工程单位（MPa / 10⁸m³ / 小数）。前端只做展示格式化。
 *
 * 本页即算即看、不落库，与库级物质平衡、库级诊断曲线一致。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { storageMainFactorApi } from '@/api/storageMainFactor'
import {
  FACTOR_LABELS,
  FACTOR_UNITS,
  buildAbsoluteSeries,
  buildRelativeSeries,
  deviationPercent,
  differenceDirection,
  formatFactorValue,
  sourceLabel,
  toNumberOrNull
} from '@/utils/storageMainFactor'

const props = defineProps({
  reservoir: { type: Object, default: null }
})

const unwrap = response => response?.data ?? response

const scope = computed(() => ({
  projectId: props.reservoir?.projectId,
  gasReservoirId: props.reservoir?.gasReservoirId,
  storageId: props.reservoir?.storageId
}))

const loading = ref(false)
const calculating = ref(false)
const error = ref('')
const warnings = ref([])
const factors = ref([])
// 必须始终是对象：参数栏在 loading 分支之外渲染，inputs 为 null 时模板里
// 的 inputs[field.key] 会直接抛错，整个页面白屏。
const inputs = ref({ gasPvtParam: {} })
const volumeFactor = ref('')
const chartMode = ref('relative')
const theoreticalDraft = ref({})
const actualDraft = ref({})

let controller = null

/** 参数栏里可编辑的入参；单位即原平台口径，标签上写清楚，避免被当成 MPa 误解。 */
const INPUT_FIELDS = [
  { key: 'originalPressure', label: '原始地层压力', unit: 'Pa' },
  { key: 'formationTemperature', label: '地层温度', unit: 'K' },
  { key: 'originalGasInPlace', label: '动态地质储量', unit: '10⁸m³' },
  { key: 'cumulativeGasProduction', label: '累产气量', unit: '10⁸m³' },
  { key: 'rockCompressionCoefficient', label: '岩石压缩系数', unit: '1/Pa' },
  { key: 'waterCompressionCoefficient', label: '地层水压缩系数', unit: '1/Pa' },
  { key: 'waterSaturation', label: '束缚水饱和度', unit: '小数' },
  { key: 'gasReservoirType', label: '气藏类型', unit: '0/1/2' }
]

const PVT_FIELDS = [
  { key: 'gasType', label: '天然气类型', unit: '0干气/1湿气/2凝析气' },
  { key: 'specificGravity', label: '天然气比重', unit: '小数' },
  { key: 'modificationMethod', label: '非烃修正方法', unit: '0 Wichert-Aziz / 1 Carr' },
  { key: 'deviationFactorMethod', label: '偏差系数方法', unit: '0 DAK / 1 DPR / 2 Hall' },
  { key: 'h2SMoleFraction', label: 'H₂S 摩尔百分含量', unit: '%' },
  { key: 'co2MoleFraction', label: 'CO₂ 摩尔百分含量', unit: '%' },
  { key: 'n2MoleFraction', label: 'N₂ 摩尔百分含量', unit: '%' }
]

const rows = computed(() => factors.value.map(factor => ({
  ...factor,
  label: FACTOR_LABELS[factor.key] || factor.key,
  unit: FACTOR_UNITS[factor.key] || '',
  theoreticalText: formatFactorValue(factor.theoretical?.value, null),
  actualText: formatFactorValue(factor.actual?.value, null),
  theoreticalSource: sourceLabel(factor.theoretical?.source),
  actualSource: sourceLabel(factor.actual?.source),
  theoreticalSourceType: factor.theoretical?.source || 'MISSING',
  actualSourceType: factor.actual?.source || 'MISSING',
  differenceText: formatFactorValue(factor.difference, null),
  direction: differenceDirection(factor.difference),
  deviationText: factor.deviationPercent === null || factor.deviationPercent === undefined
    ? '—'
    : `${factor.deviationPercent.toFixed(2)} %`
})))

const chartRef = ref(null)
let chart = null

const disposeChart = () => {
  if (chart) {
    chart.dispose()
    chart = null
  }
}

const renderChart = async () => {
  await nextTick()
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)

  if (chartMode.value === 'relative') {
    const series = buildRelativeSeries(factors.value)
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['理论值', '实际值'] },
      grid: { left: 60, right: 24, top: 40, bottom: 40 },
      xAxis: { type: 'category', data: series.map(item => item.label) },
      // 四个因素单位不同，绝对值不可同图比较，因此以理论值 100% 为基准
      yAxis: { type: 'value', name: '相对理论值 (%)' },
      series: [
        { name: '理论值', type: 'bar', data: series.map(item => item.theoretical) },
        { name: '实际值', type: 'bar', data: series.map(item => item.actual) }
      ]
    }, true)
    return
  }

  const series = buildAbsoluteSeries(factors.value)
  // 绝对值模式下按因素分面：每个因素一张小图，各自带单位
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['理论值', '实际值'] },
    grid: { left: 60, right: 24, top: 40, bottom: 40 },
    xAxis: { type: 'category', data: series.map(item => `${item.label}\n(${item.unit})`) },
    yAxis: { type: 'value' },
    series: [
      { name: '理论值', type: 'bar', data: series.map(item => item.theoretical) },
      { name: '实际值', type: 'bar', data: series.map(item => item.actual) }
    ]
  }, true)
}

const applyContext = data => {
  factors.value = data?.factors || []
  inputs.value = data?.inputs || { gasPvtParam: {} }
  warnings.value = data?.warnings || []
  theoreticalDraft.value = {}
  actualDraft.value = {}
}

const load = async () => {
  if (controller) controller.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    applyContext(unwrap(await storageMainFactorApi.context(scope.value, controller.signal)))
    await renderChart()
  } catch (cause) {
    // 后端用 silentError，页面自己显示中文原因
    error.value = cause?.msg || cause?.message || '读取主控因素分析数据失败'
  } finally {
    loading.value = false
  }
}

const buildInputs = () => {
  if (!inputs.value) return null
  const merged = { ...inputs.value }
  INPUT_FIELDS.forEach(field => {
    const value = toNumberOrNull(merged[field.key])
    merged[field.key] = value === null ? null : value
  })
  const pvt = { ...(merged.gasPvtParam || {}) }
  PVT_FIELDS.forEach(field => {
    const value = toNumberOrNull(pvt[field.key])
    pvt[field.key] = value === null ? 0 : value
  })
  merged.gasPvtParam = pvt
  return merged
}

/**
 * 汇总某一侧的取值：用户改过的用手输值，否则回传当前已有的值。
 *
 * 必须回传已有值，不能只发手输值：calculate 不回读数据库，
 * 只发手输值会把 context 读到的"实际地层压力""实际天然气量"变成 MISSING，
 * 表格里的实际值会在点一次计算之后凭空消失。
 */
const effectiveValues = (draft, side) => {
  const collected = {}
  factors.value.forEach(factor => {
    const typed = toNumberOrNull(draft[factor.key])
    if (typed !== null) {
      collected[factor.key] = typed
      return
    }
    const current = toNumberOrNull(factor[side]?.value)
    if (current !== null) collected[factor.key] = current
  })
  return collected
}

const calculate = async () => {
  calculating.value = true
  error.value = ''
  try {
    const result = unwrap(await storageMainFactorApi.calculate({
      ...scope.value,
      volumeFactor: toNumberOrNull(volumeFactor.value),
      theoretical: effectiveValues(theoreticalDraft.value, 'theoretical'),
      actual: effectiveValues(actualDraft.value, 'actual'),
      inputs: buildInputs()
    }))
    factors.value = result?.factors || factors.value
    warnings.value = result?.warnings || []
    await renderChart()
  } catch (cause) {
    error.value = cause?.msg || cause?.message || '计算失败'
  } finally {
    calculating.value = false
  }
}

const onResize = () => chart?.resize()

watch(() => [props.reservoir?.projectId, props.reservoir?.gasReservoirId, props.reservoir?.storageId], () => {
  load()
}, { immediate: true })

watch(chartMode, () => renderChart())

if (typeof window !== 'undefined') window.addEventListener('resize', onResize)

onBeforeUnmount(() => {
  if (controller) controller.abort()
  if (typeof window !== 'undefined') window.removeEventListener('resize', onResize)
  disposeChart()
})
</script>

<template>
  <section class="storage-main-factor" aria-label="主控因素分析">
    <div class="module-tabs">
      <div class="module-title">主控因素分析</div>
    </div>

    <div class="workspace">
      <aside class="params-panel">
        <div class="panel-head">参数设置</div>
        <div class="panel-body">
          <p class="panel-note">以下为原平台入参口径（Pa / K / 小数 / 1/Pa），不是 MPa。</p>

          <label v-for="field in INPUT_FIELDS" :key="field.key" class="field">
            <span>{{ field.label }}（{{ field.unit }}）</span>
            <input v-model="inputs[field.key]" inputmode="decimal" autocomplete="off" />
          </label>

          <div class="group-title">气体 PVT 参数</div>
          <label v-for="field in PVT_FIELDS" :key="field.key" class="field">
            <span>{{ field.label }}（{{ field.unit }}）</span>
            <input v-model="inputs.gasPvtParam[field.key]" inputmode="decimal" autocomplete="off" />
          </label>

          <div class="group-title">② ④ 计算所需的 Bg</div>
          <label class="field">
            <span>天然气体积系数 Bg（小数，必填）</span>
            <input v-model="volumeFactor" inputmode="decimal" autocomplete="off" placeholder="例如 0.0065" />
          </label>

          <button class="calculate" :disabled="loading || calculating" @click="calculate">
            {{ calculating ? '计算中…' : '读取并计算' }}
          </button>
        </div>
      </aside>

      <main class="result-area">
        <div class="result-tabs">
          <div class="result-tab">{{ reservoir?.label || '当前库' }} · 主控因素分析</div>
        </div>

        <div class="result-body">
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
          <div v-else-if="loading" class="status" role="status">正在读取库内井的物质平衡输入…</div>

          <template v-else>
            <el-alert v-for="(warning, index) in warnings" :key="index" :title="warning"
              type="warning" :closable="false" show-icon class="warning" />

            <table class="factor-table">
              <thead>
                <tr>
                  <th>因素</th>
                  <th>理论值</th>
                  <th>实际值</th>
                  <th>差异（实际 − 理论）</th>
                  <th>百分比偏差</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.key">
                  <td class="factor-name">
                    {{ row.label }}
                    <span class="unit">（{{ row.unit }}）</span>
                  </td>
                  <td>
                    <template v-if="row.theoreticalSourceType === 'MISSING'">
                      <input v-model="theoreticalDraft[row.key]" inputmode="decimal" autocomplete="off" placeholder="待填写" />
                    </template>
                    <template v-else>{{ row.theoreticalText }}</template>
                    <span class="source" :class="row.theoreticalSourceType.toLowerCase()">{{ row.theoreticalSource }}</span>
                  </td>
                  <td>
                    <template v-if="row.actualSourceType === 'MISSING'">
                      <input v-model="actualDraft[row.key]" inputmode="decimal" autocomplete="off" placeholder="待填写" />
                    </template>
                    <template v-else>{{ row.actualText }}</template>
                    <span class="source" :class="row.actualSourceType.toLowerCase()">{{ row.actualSource }}</span>
                  </td>
                  <td :class="['difference', row.direction ? row.direction.toLowerCase() : '']">
                    {{ row.differenceText }}
                  </td>
                  <td>{{ row.deviationText }}</td>
                </tr>
              </tbody>
            </table>

            <div class="chart-head">
              <span>理论 vs 实际</span>
              <button class="mode" :class="{ active: chartMode === 'relative' }" @click="chartMode = 'relative'">相对理论值</button>
              <button class="mode" :class="{ active: chartMode === 'absolute' }" @click="chartMode = 'absolute'">绝对值</button>
            </div>
            <p class="chart-note">
              四个因素单位不同（MPa / 10⁸m³ / 小数），绝对值不能同图比较，因此默认以理论值为 100% 基准显示。
            </p>
            <div ref="chartRef" class="chart"></div>
          </template>
        </div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.storage-main-factor {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}
.module-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
}
.module-title {
  height: 34px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  background: #f4d000;
  color: #202020;
  font: 600 14px Arial, sans-serif;
}
.workspace {
  flex: 1;
  min-height: 0;
  display: flex;
}
.params-panel {
  width: 280px;
  flex: 0 0 280px;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.panel-head {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: center;
  padding: 0 12px;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
  font-size: 13px;
}
.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
}
.panel-note {
  margin: 0 0 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
.group-title {
  margin: 14px 0 8px;
  color: #606266;
  font-size: 12px;
  font-weight: 600;
}
.field {
  display: block;
  margin-bottom: 10px;
}
.field > span {
  display: block;
  margin-bottom: 4px;
  color: #606266;
  font-size: 12px;
}
.field input,
.factor-table input {
  width: 100%;
  height: 28px;
  box-sizing: border-box;
  padding: 0 8px;
  border: 1px solid #dcdfe6;
  border-radius: 2px;
  font-size: 12px;
}
.calculate {
  width: 100%;
  height: 32px;
  margin-top: 12px;
  border: none;
  border-radius: 2px;
  background: #f4d000;
  color: #202020;
  font-weight: 600;
  cursor: pointer;
}
.calculate:disabled { opacity: .6; cursor: default; }
.result-area {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.result-tabs {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  background: #fafafa;
  border-bottom: 1px solid #e4e7ed;
}
.result-tab {
  height: 34px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  background: #f4d000;
  color: #202020;
  font: 600 14px Arial, sans-serif;
}
.result-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px;
}
.status { color: #606266; font-size: 13px; }
.warning { margin-bottom: 8px; }
.factor-table {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0 20px;
  font-size: 13px;
}
.factor-table th,
.factor-table td {
  border: 1px solid #e4e7ed;
  padding: 8px 10px;
  text-align: left;
  vertical-align: middle;
}
.factor-table th { background: #fafafa; font-weight: 600; }
.factor-name .unit { color: #909399; font-size: 12px; }
.source {
  display: inline-block;
  margin-left: 6px;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  background: #f0f2f5;
  color: #606266;
}
.source.auto { background: #e8f5e9; color: #2e7d32; }
.source.manual { background: #fff8e1; color: #8d6e00; }
.source.missing { background: #fdecea; color: #c62828; }
.difference.positive { color: #c62828; }
.difference.negative { color: #2e7d32; }
.chart-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}
.mode {
  height: 24px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 2px;
  background: #fff;
  font-size: 12px;
  cursor: pointer;
}
.mode.active { background: #f4d000; border-color: #f4d000; color: #202020; }
.chart-note { margin: 6px 0 8px; color: #909399; font-size: 12px; }
.chart { width: 100%; height: 320px; }
</style>
