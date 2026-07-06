<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { analyticMethodApi } from '@/api/docker'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const loading = ref(false)
const activeTab = ref('chart')
const resultData = ref(null)
const chartEl = ref(null)
let chart = null

const normalizePayload = (res) => res?.data?.data ?? res?.data ?? res

const rawNode = computed(() => props.node?.raw || {})
const wellName = computed(() => props.node?.wellName || rawNode.value?.wellName || rawNode.value?.nodeTitle || '')
const fields = computed(() => resultData.value?.fields || [])
const analysis = computed(() => resultData.value?.analysis || {})
const input = computed(() => resultData.value?.input || {})
const output = computed(() => ({
  ...(resultData.value?.output || {}),
  ...(resultData.value?.result || {}),
  ...analysis.value
}))
const outputItems = computed(() => resultData.value?.outputItems || [])
const chartItems = computed(() => resultData.value?.chartItems || [])

const getResultIdFromNode = () => {
  const raw = rawNode.value
  return raw.resultId || raw.analysisId || raw.analysisID || raw.analysis_id ||
    raw.AnalysisMethodsId || raw.analysisMethodsId || raw.id || props.node?.resultId
}

const getFieldUnit = (name) => fields.value.find(item => item.name === name)?.unit_label || ''

const resultFields = computed(() => [
  { label: '井型', value: output.value.wellType },
  { label: '是否压裂', value: output.value.isFractured },
  { label: `渗透率${getFieldUnit('permeability') ? `(${getFieldUnit('permeability')})` : ''}`, value: output.value.permeability },
  { label: `表皮系数${getFieldUnit('skinFactor') ? `(${getFieldUnit('skinFactor')})` : ''}`, value: output.value.skinFactor },
  { label: `裂缝半长${getFieldUnit('fractureHalfLength') ? `(${getFieldUnit('fractureHalfLength')})` : ''}`, value: output.value.fractureHalfLength },
  { label: `裂缝渗透率${getFieldUnit('fracturePermeability') ? `(${getFieldUnit('fracturePermeability')})` : ''}`, value: output.value.fracturePermeability }
])

const inputFields = computed(() => [
  { label: '天然气类型', value: input.value.gasType },
  { label: `天然气比重${getFieldUnit('specificGravity') ? `(${getFieldUnit('specificGravity')})` : ''}`, value: input.value.specificGravity },
  { label: `原始地层压力${getFieldUnit('originalFormationPressure') ? `(${getFieldUnit('originalFormationPressure')})` : ''}`, value: input.value.originalFormationPressure },
  { label: `地层温度${getFieldUnit('formationTemperature') ? `(${getFieldUnit('formationTemperature')})` : ''}`, value: input.value.formationTemperature },
  { label: `井筒半径${getFieldUnit('wellboreRadius') ? `(${getFieldUnit('wellboreRadius')})` : ''}`, value: input.value.wellboreRadius },
  { label: `动态地质储量${getFieldUnit('originalGasVolume') ? `(${getFieldUnit('originalGasVolume')})` : ''}`, value: input.value.originalGasVolume },
  { label: `生产水气比上限${getFieldUnit('waterGasRatioLimit') ? `(${getFieldUnit('waterGasRatioLimit')})` : ''}`, value: input.value.waterGasRatioLimit }
])

const getPoint = (item) => {
  const xField = item.xAxisField || 'date'
  const yField = item.yAxisField
  return (item.data || [])
    .filter(row => !row?.isDeleted)
    .map(row => {
      const x = row.xValue ?? row[xField]
      const y = Number(row.yValue ?? row[yField])
      return [x, Number.isFinite(y) ? y : null]
    })
    .filter(point => point[0] !== undefined && point[1] !== null)
}

const isPressureSeries = (name = '', field = '') =>
  name.includes('压力') || field.includes('Pressure')

const formatDate = (_row, _column, value) => value ? String(value).slice(0, 10) : ''

const renderChart = () => {
  if (!chart || activeTab.value !== 'chart') return
  if (!chartItems.value.length) {
    chart.clear()
    return
  }

  const legend = chartItems.value.map(item => item.name || item.yAxisField)
  const series = chartItems.value.map(item => ({
    name: item.name || item.yAxisField,
    type: 'line',
    showSymbol: false,
    smooth: true,
    yAxisIndex: isPressureSeries(item.name, item.yAxisField) ? 1 : 0,
    data: getPoint(item)
  }))

  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { top: 8, type: 'scroll', data: legend },
    grid: { top: 46, left: 58, right: 70, bottom: 44, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false },
    yAxis: [
      { type: 'value', name: '产量' },
      { type: 'value', name: '压力', position: 'right' }
    ],
    series
  }, true)
}

const resolveResultIdByWellName = async () => {
  const targetWell = wellName.value
  if (!targetWell) return null

  const batches = []
  for (let start = 1; start <= 160; start += 10) {
    batches.push(Array.from({ length: 10 }, (_, index) => start + index))
  }

  for (const batch of batches) {
    const results = await Promise.all(batch.map(async (id) => {
      try {
        const res = await analyticMethodApi.getResult(props.projectId, props.gasReservoirId, id, { silent: true })
        const data = normalizePayload(res)
        return data?.analysis?.wellName === targetWell ? id : null
      } catch {
        return null
      }
    }))
    const matched = results.find(Boolean)
    if (matched) return matched
  }

  return null
}

const fetchData = async () => {
  if (!props.projectId || !props.gasReservoirId || !wellName.value) return
  loading.value = true
  resultData.value = null

  try {
    const resultId = getResultIdFromNode() || await resolveResultIdByWellName()
    if (!resultId) throw new Error('没有找到该井的解析法结果 ID')

    const res = await analyticMethodApi.getResult(props.projectId, props.gasReservoirId, resultId)
    const data = normalizePayload(res)
    resultData.value = {
      ...data,
      result: {
        ...(data.output || {}),
        ...(data.analysis || {})
      }
    }
    await nextTick()
    renderChart()
  } catch (error) {
    console.error('[AnalyticMethodContent] 数据加载失败', error)
  } finally {
    loading.value = false
  }
}

watch(() => [props.node?.id, props.node?.wellName], fetchData, { immediate: true })
watch(activeTab, () => nextTick(renderChart))

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', chart.resize)
  renderChart()
})

onBeforeUnmount(() => {
  if (chart) {
    window.removeEventListener('resize', chart.resize)
    chart.dispose()
  }
})
</script>

<template>
  <div v-loading="loading" class="analytic-wrap">
    <aside class="result-panel">
      <div class="panel-title">解析法结果</div>
      <div class="section-title">输出结果</div>
      <div v-for="item in resultFields" :key="item.label" class="field">
        <label>{{ item.label }}</label>
        <el-input size="small" readonly :model-value="item.value ?? ''" />
      </div>

      <div class="section-title">输入参数</div>
      <div v-for="item in inputFields" :key="item.label" class="field">
        <label>{{ item.label }}</label>
        <el-input size="small" readonly :model-value="item.value ?? ''" />
      </div>
    </aside>

    <section class="main-panel">
      <el-tabs v-model="activeTab" class="tabs">
        <el-tab-pane label="曲线图" name="chart">
          <div ref="chartEl" class="chart"></div>
        </el-tab-pane>
        <el-tab-pane label="数据列表" name="table">
          <el-table :data="outputItems" size="small" height="100%" border>
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column prop="date" label="生产时间" min-width="130" :formatter="formatDate" />
            <el-table-column prop="dailyGasProduction" label="气产量(10^4m³/d)" min-width="150" />
            <el-table-column prop="bottomPressure" label="真实井底流压(MPa)" min-width="160" />
            <el-table-column prop="theoreticalBottomPressure" label="理论井底流压(MPa)" min-width="170" />
            <el-table-column prop="qa" label="qa" min-width="110" />
            <el-table-column prop="qf" label="qf" min-width="110" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<style lang="scss" scoped>
.analytic-wrap {
  height: 100%;
  display: flex;
  min-height: 0;
  background: #fff;
}

.result-panel {
  width: 300px;
  min-width: 300px;
  border-right: 1px solid #e0e0e0;
  padding: 10px 12px;
  overflow-y: auto;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
  color: #222;
}

.section-title {
  margin: 12px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: #333;
}

.field {
  margin-bottom: 8px;

  label {
    display: block;
    margin-bottom: 3px;
    font-size: 12px;
    color: #555;
  }
}

.main-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.tabs {
  height: 100%;
  display: flex;
  flex-direction: column;

  :deep(.el-tabs__content) {
    flex: 1;
    min-height: 0;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }
}

.chart {
  height: 100%;
  min-height: 420px;
}
</style>
