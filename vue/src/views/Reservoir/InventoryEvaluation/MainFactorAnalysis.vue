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
  differenceDirection,
  formatComputedValue,
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
// 只有点过「读取并计算」才有柱子；之前保留坐标轴、分类、图例，但不出数据
const calculated = ref(false)

/**
 * 画图用的因素数据：没点过计算就把理论/实际值清空，
 * 分类（四个因素名）仍来自 factors，所以坐标轴和图例照常显示。
 */
const chartFactors = computed(() => calculated.value ? factors.value : factors.value.map(factor => ({
  ...factor,
  theoretical: { ...factor.theoretical, value: null },
  actual: { ...factor.actual, value: null }
})))
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
  differenceText: formatComputedValue(factor.difference),
  direction: differenceDirection(factor.difference),
  deviationText: factor.deviationPercent === null || factor.deviationPercent === undefined
    ? '—'
    : `${factor.deviationPercent.toFixed(2)} %`
})))

/**
 * 表格里每个格子都是输入框：自动读取来的值也允许改。
 *
 * 原来只有"待填写"的格子才是输入框，一旦填过、点过计算，那一格就变成只读文本，
 * 打错一个数字只能刷新页面——这对"人工对比"的用法是硬伤。
 */
const draftOf = side => (side === 'theoretical' ? theoreticalDraft : actualDraft)

const cellValue = (row, side) => {
  const draft = draftOf(side).value
  if (draft[row.key] !== undefined) return draft[row.key]
  const current = row[side]?.value
  return current === null || current === undefined ? '' : String(current)
}

const onCellInput = (side, key, event) => {
  const draft = draftOf(side)
  draft.value = { ...draft.value, [key]: event.target.value }
}

/** 用户动过的格子标成"手动填写"，没动过的沿用自动读取/待填写。 */
const cellSourceType = (row, side) => {
  const text = draftOf(side).value[row.key]
  if (text !== undefined && String(text).trim() !== '') return 'MANUAL'
  return row[`${side}SourceType`]
}

const cellSourceLabel = (row, side) => sourceLabel(cellSourceType(row, side))

/**
 * 例子值只用于输入框占位提示，来源是本库真实数据，不是随便写的：
 *  - 地层压力·理论值 ≈ 原始压力 50 × (1 − 累产气量 12.306 / 动态地质储量 23.398) ≈ 23.70 MPa
 *  - 动用孔隙体积·实际值由 G·Bg/(1−Swi) 算出，Bg 取 0.0065 时约 0.206（这里提示设计值量级）
 *  - 天然气·实际值 23.4，设计值通常略大
 *  - 气体饱和度·理论值 = 1 − 束缚水饱和度 = 1 − 0.2616 ≈ 0.74
 */
const PLACEHOLDER_HINTS = {
  formationPressure: '如 23.70',
  poreVolume: '如 0.220',
  gas: '如 25.0',
  gasSaturation: '如 0.74'
}

const placeholderFor = key => PLACEHOLDER_HINTS[key] || '待填写'

/**
 * 理论值没值时的提示：①地层压力既可能手填、也可能由原平台自动读回，
 * ②动用孔隙体积与③天然气库里根本没有，只能手填。
 */
const missingHint = key => (key === 'formationPressure' ? '需手填/自动读取' : '需手填')

const chartRef = ref(null)
const facetRefs = ref([])
let chart = null
let facetCharts = []

const disposeChart = () => {
  if (chart) {
    chart.dispose()
    chart = null
  }
  facetCharts.forEach(instance => instance.dispose())
  facetCharts = []
}

const setFacetRef = (el, index) => {
  if (el) facetRefs.value[index] = el
}

/**
 * ECharts 的画布尺寸在 init 时定死，容器之后变宽就会把画布拉伸，文字发虚。
 * 只监听 window.resize 不够：滚动条出现、参数栏稳定、标签页切换都会改变容器宽度。
 * 用 ResizeObserver 盯住容器本身，尺寸一变就重算画布。
 */
let chartObserver = null
const observeChartSize = el => {
  chartObserver?.disconnect()
  chartObserver = null
  if (!el || typeof ResizeObserver === 'undefined') return
  chartObserver = new ResizeObserver(() => {
    chart?.resize()
    facetCharts.forEach(instance => instance?.resize())
  })
  chartObserver.observe(el)
}

const renderChart = async () => {
  await nextTick()
  disposeChart()
  chartObserver?.disconnect()
  chartObserver = null

  if (chartMode.value === 'relative') {
    if (!chartRef.value) return
    chart = echarts.init(chartRef.value)
    const series = buildRelativeSeries(chartFactors.value)
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['理论值', '实际值'] },
      grid: { left: 60, right: 24, top: 40, bottom: 40 },
      xAxis: { type: 'category', data: series.map(item => item.label) },
      // 四个因素单位不同，绝对值不可同图比较，因此以理论值 100% 为基准
      yAxis: { type: 'value', name: '相对理论值 (%)' },
      series: [
        { name: '理论值', type: 'bar', data: series.map(item => item.theoretical) },
        { name: '实际值', type: 'bar', data: series.map(item => item.actual) }
      ]
    }, true)
    observeChartSize(chartRef.value)
    return
  }

  // 绝对值模式**按因素分面**：MPa 的 32 与小数的 0.74 画在同一根轴上，小的那几根根本看不见，
  // 所以每个因素一张小图，各自带自己的单位与量纲。
  const series = buildAbsoluteSeries(chartFactors.value)
  facetCharts = series.map((item, index) => {
    const el = facetRefs.value[index]
    if (!el) return null
    const instance = echarts.init(el)
    instance.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 56, right: 12, top: 28, bottom: 26 },
      xAxis: { type: 'category', data: ['理论值', '实际值'] },
      yAxis: { type: 'value', name: item.unit },
      series: [{
        type: 'bar',
        barWidth: 26,
        data: [
          { value: item.theoretical, itemStyle: { color: '#909399' } },
          { value: item.actual, itemStyle: { color: '#f4d000' } }
        ]
      }]
    })
    return instance
  }).filter(Boolean)
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
  let loaded = false
  try {
    applyContext(unwrap(await storageMainFactorApi.context(scope.value, controller.signal)))
    loaded = true
  } catch (cause) {
    // 后端用 silentError，页面自己显示中文原因
    error.value = cause?.msg || cause?.message || '读取主控因素分析数据失败'
  } finally {
    loading.value = false
  }
  // 必须在 loading 关掉之后才画图：图表所在的 v-else 分支在 loading 期间根本不存在，
  // 提前调用会拿到空的 chartRef 然后静默 return，图表永远不出现。
  if (loaded) await renderChart()
}

/**
 * 构造要回传的工具箱入参。
 *
 * 必须深拷一层再转换：直接改 inputs.value.gasPvtParam 会把用户清空的输入框就地改成 0，
 * 界面上看起来"自己变成了 0"，而且之后再提交就是那个 0。空值保持 null，
 * 由后端（Jackson 对原始类型）按 0 处理。
 */
const buildInputs = () => {
  const current = inputs.value
  if (!current) return null
  const merged = { ...current }
  INPUT_FIELDS.forEach(field => {
    merged[field.key] = toNumberOrNull(merged[field.key])
  })
  const pvt = { ...(current.gasPvtParam || {}) }
  PVT_FIELDS.forEach(field => {
    pvt[field.key] = toNumberOrNull(pvt[field.key])
  })
  merged.gasPvtParam = pvt
  return merged
}

/**
 * 汇总某一侧要回传的取值：用户改过的按"手动填写"，否则把当前值**连同来源**原样回传。
 *
 * 两个必须点：一是不能只发手输值，否则 context 读到的实际地层压力/实际天然气量
 * 会在点一次计算后变成 MISSING；二是必须带上 source/note，
 * 否则"实测静压：X-1"这类溯源信息会被统一冲成"手动填写"。
 */
const effectiveValues = (draft, side) => {
  const collected = {}
  factors.value.forEach(factor => {
    const typed = toNumberOrNull(draft[factor.key])
    if (typed !== null) {
      collected[factor.key] = { value: typed, source: 'MANUAL', note: '手动填写' }
      return
    }
    const current = factor[side]
    if (current && current.value !== null && current.value !== undefined) {
      collected[factor.key] = { value: current.value, source: current.source, note: current.note }
    }
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
    calculated.value = true
    warnings.value = result?.warnings || []
    await renderChart()
  } catch (cause) {
    error.value = cause?.msg || cause?.message || '计算失败'
  } finally {
    calculating.value = false
  }
}

const onResize = () => {
  chart?.resize()
  facetCharts.forEach(instance => instance.resize())
}

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
          <!-- 左侧唯一必须手填的就是 Bg，放最前面 -->
          <label class="field">
            <span>天然气体积系数 Bg（必填项）</span>
            <input v-model="volumeFactor" inputmode="decimal" autocomplete="off" placeholder="例如 0.0065" />
          </label>

          <label v-for="field in INPUT_FIELDS" :key="field.key" class="field">
            <span>{{ field.label }}（{{ field.unit }}）</span>
            <input v-model="inputs[field.key]" inputmode="decimal" autocomplete="off" />
          </label>

          <div class="group-title">气体 PVT 参数</div>
          <label v-for="field in PVT_FIELDS" :key="field.key" class="field">
            <span>{{ field.label }}（{{ field.unit }}）</span>
            <input v-model="inputs.gasPvtParam[field.key]" inputmode="decimal" autocomplete="off" />
          </label>

        </div>

        <!-- 按钮与滚动区分离：滚动的只有 .panel-body -->
        <div class="panel-footer">
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
                    <input :value="cellValue(row, 'theoretical')"
                      @input="onCellInput('theoretical', row.key, $event)"
                      inputmode="decimal" autocomplete="off" :placeholder="placeholderFor(row.key)" />
                    <!-- 理论值：没有值就明说"需手填"，有值才显示来源 -->
                    <span v-if="cellSourceType(row, 'theoretical') === 'MISSING'"
                      class="source missing">{{ missingHint(row.key) }}</span>
                    <span v-else class="source" :class="cellSourceType(row, 'theoretical').toLowerCase()">
                      {{ cellSourceLabel(row, 'theoretical') }}
                    </span>
                  </td>
                  <td>
                    <input :value="cellValue(row, 'actual')"
                      @input="onCellInput('actual', row.key, $event)"
                      inputmode="decimal" autocomplete="off" />
                    <!-- 实际值都是自动算出来的，只标"自动读取"；手改过的就不标了 -->
                    <span v-if="cellSourceType(row, 'actual') === 'AUTO'" class="source auto">自动读取</span>
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
            <div v-if="chartMode === 'relative'" ref="chartRef" class="chart"></div>
            <div v-else class="facet-grid">
              <div v-for="(item, index) in buildAbsoluteSeries(factors)" :key="item.key" class="facet">
                <div class="facet-title">{{ item.label }}（{{ item.unit }}）</div>
                <div :ref="el => setFacetRef(el, index)" class="facet-chart"></div>
              </div>
            </div>
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
  /* 上级容器有时给不出确定高度，整栏会比视口高一点，最下方的按钮就被裁掉。
     这里留出底部余量，把按钮往上提，保证完整可见。 */
  padding-bottom: 24px;
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
/* 参数栏页脚：与滚动区分离，按钮始终完整可见 */
.panel-footer {
  flex: 0 0 auto;
  padding: 10px 12px;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}
.panel-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  /* 覆盖式滚动条会浮在内容之上：按钮 width:100% 会把它底部压住，
     看起来"只有上方箭头"。预留滚动条槽位即可。 */
  scrollbar-gutter: stable;
  padding: 12px;
}
.panel-note {
  margin: 0 0 10px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
/* 整栏里只有 Bg 必须手输，单独框出来，避免用户以为整栏都要填 */
.required-block {
  margin-bottom: 16px;
  padding: 10px;
  border: 1px solid #f4d000;
  border-radius: 2px;
  background: #fffdf0;
}
.required-title {
  margin-bottom: 8px;
  color: #8d6e00;
  font-size: 12px;
  font-weight: 600;
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
  height: 36px;
  /* 用 flex 居中：原来只给了 height，标签位置由继承行高决定，文字会贴底被裁 */
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin: 12px 0 0;
  border: none;
  border-radius: 2px;
  background: #f4d000;
  color: #202020;
  font-size: 13px;
  line-height: 1;
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
/* 绝对值模式：四个因素单位不同，分面显示，各自带单位 */
.facet-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.facet {
  border: 1px solid #e4e7ed;
  border-radius: 2px;
  padding: 8px;
}
.facet-title {
  margin-bottom: 4px;
  color: #606266;
  font-size: 12px;
  font-weight: 600;
}
.facet-chart { width: 100%; height: 200px; }
</style>
