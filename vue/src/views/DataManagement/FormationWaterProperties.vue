<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { dataManagementApi, toolboxApi } from '@/api/docker'

const props = defineProps({
  wellName: { type: String, required: true },
  projectId: { type: [Number, String], required: true }
})

const SOURCE_FALLBACK_COLUMNS = [
  { key: 'A', label: '井号' },
  { key: 'B', label: '天然气类型' },
  { key: 'C', label: '天然气比重(dless)' },
  { key: 'D', label: 'H₂S摩尔百分含量(%)' },
  { key: 'E', label: 'CO₂摩尔百分含量(%)' },
  { key: 'F', label: 'N₂摩尔百分含量(%)' }
]

const CURVE_OPTIONS = [
  {
    name: '曲线1',
    title: '天然气在水中的溶解度',
    series: [{
      key: 'gasSolubilityInWater',
      name: '天然气在水中的溶解度',
      unit: 'dless',
      algorithm: 'WaterPVT_GasSolubilityInWater'
    }]
  },
  {
    name: '曲线2',
    title: '地层水体积系数与地层水密度',
    series: [
      {
        key: 'volumeFactor',
        name: '地层水体积系数',
        unit: 'dless',
        algorithm: 'WaterPVT_VolumeFactor',
        yAxisIndex: 0
      },
      {
        key: 'density',
        name: '地层水密度',
        unit: 'kg/m³',
        algorithm: 'WaterPVT_Density',
        yAxisIndex: 1
      }
    ]
  },
  {
    name: '曲线3',
    title: '地层水等温压缩系数',
    series: [{
      key: 'isothermalCompressionCoefficient',
      name: '地层水等温压缩系数',
      unit: 'MPa⁻¹',
      algorithm: 'WaterPVT_IsothermalCompressionCoefficient'
    }]
  },
  {
    name: '曲线4',
    title: '地层水粘度',
    series: [{
      key: 'viscosity',
      name: '地层水粘度',
      unit: 'mPa·s',
      algorithm: 'WaterPVT_Viscosity'
    }]
  }
]

const OUTPUT_KEYS = new Set([
  'gasSolubilityInWater',
  'volumeFactor',
  'density',
  'isothermalCompressionCoefficient',
  'viscosity'
])

const activeResultTab = ref('数据列表')
const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const volumeFactorMethod = ref('McCain方法')
const compressibilityMethod = ref('Meehan方法')
const salinity = ref(25000)
const initialPressure = ref(40)
const reservoirTemperature = ref(119.85)
const sourceLoading = ref(false)
const analysisLoading = ref(false)
const sourceRows = ref([])
const sourceFields = ref([])
const curveResults = ref({})
const toolFields = ref([])
const chartEl = ref(null)
let chart = null
let sourceSequence = 0
let analysisSequence = 0

// 根据当前选中的曲线名称返回对应的曲线配置
const activeCurveOption = computed(
  () => CURVE_OPTIONS.find(option => option.name === activeCurve.value) ?? CURVE_OPTIONS[0]
)

// 提取真正数据
const unwrapResponse = (response) =>
  response?.data?.data ?? response?.data ?? response ?? {}

// 将 Excel 单元格形式的接口数据转换为可直接供表格使用的行对象
const normalizeExcelRows = (payload) => {
  const items = Array.isArray(payload?.items)
    ? payload.items
    : Array.isArray(payload?.rows)
      ? payload.rows
      : Array.isArray(payload)
        ? payload
        : []

  return items.map((item, index) => {
    if (!Array.isArray(item?.excelCells)) return { ...item, _rowKey: item?.id ?? index }
    // 按 Excel 列号把同一行中的单元格合并为一个对象
    return item.excelCells.reduce((row, cell) => {
      row[cell.column || cell.location] = cell.value
      return row
    }, { _rowKey: item.location ?? index })
  })
}

// 优先使用接口字段元数据生成基础数据表列，缺失时使用预设列
const sourceColumns = computed(() => {
  if (!sourceFields.value.length) return SOURCE_FALLBACK_COLUMNS
  return sourceFields.value.map((field, index) => {
    const key = field.name || field.column || String.fromCharCode(65 + index)
    const name = field.name_cn || field.label || key
    return {
      key,
      label: field.unit_label ? `${name}(${field.unit_label})` : name
    }
  })
})

// 根据当前井名筛选基础数据，页面只展示一口井的数据
const filteredSourceRows = computed(() => {
  const wellName = String(props.wellName || '').trim()
  return sourceRows.value.filter(row => {
    const value = row.wellName ?? row.well ?? row.wellId ?? row.A
    return String(value ?? '').trim() === wellName
  })
})

// 按字段映射
const fieldMetadata = computed(() =>
  new Map(toolFields.value.filter(field => field?.name).map(field => [field.name, field]))
)

// 取得当前曲线对应的输入、输出数据行
const activeRows = computed(() => curveResults.value[activeCurve.value]?.rows || [])

// 生成分析表列
const analysisColumns = computed(() => [
  { key: 'pressure', label: '压力(MPa)' },
  { key: 'temperature', label: '温度(℃)' },
  { key: 'salinity', label: '地层水矿化度(mg/L)' },
  ...activeCurveOption.value.series.map(series => ({
    key: series.key,
    label: `${series.name}(${series.unit})`
  }))
])

// 输出固定两位小数
const formatValue = (row, key) => {
  const value = row?.[key]
  if (value === null || value === undefined || value === '') return ''
  if (!Number.isFinite(Number(value))) return value
  if (OUTPUT_KEYS.has(key)) return Number(value).toFixed(2)

  const field = fieldMetadata.value.get(key)
  if (field?.isScientificNotation) return Number(value).toExponential(field.displayDecimal ?? 2)
  return Number(value).toFixed(field?.displayDecimal ?? (key === 'temperature' || key === 'salinity' ? 2 : 4))
}

// 将曲线输出值统一格式化为两位小数
const formatOutputValue = (value) =>
  Number.isFinite(Number(value)) ? Number(value).toFixed(2) : ''

// 从原平台 Excel 接口加载基础数据
const loadSourceData = async () => {
  const sequence = ++sourceSequence
  sourceLoading.value = true
  try {
    const payload = unwrapResponse(await dataManagementApi.getFormationWaterSource())
    if (sequence !== sourceSequence) return
    sourceRows.value = normalizeExcelRows(payload)
    sourceFields.value = Array.isArray(payload?.fields) ? payload.fields : []
  } catch (error) {
    if (sequence !== sourceSequence) return
    sourceRows.value = []
    sourceFields.value = []
    ElMessage.error(error.response?.data?.message || error.message || '地层水性质数据加载失败')
  } finally {
    if (sequence === sourceSequence) sourceLoading.value = false
  }
}

// 合并不同工具箱结果中的字段元数据，按字段名去重
const collectFields = (fields) => {
  if (!Array.isArray(fields)) return
  const merged = new Map(toolFields.value.map(field => [field?.name, field]))
  fields.forEach(field => {
    if (field?.name) merged.set(field.name, field)
  })
  toolFields.value = [...merged.values()]
}

// 从工具箱结果中提取指定输出序列，统一转换为压力数据行
const extractRows = (result, series) => {
  const collections = [
    result?.items,
    result?.rows,
    result?.points,
    result?.output?.items,
    result?.output?.rows,
    result?.output?.points
  ].find(Array.isArray)

  if (collections) {
    return collections.map(item => ({
      pressure: item?.pressure ?? item?.input?.pressure,
      temperature: item?.temperature ?? item?.input?.temperature ?? result?.input?.temperature,
      salinity: item?.salinity ?? item?.input?.salinity ?? result?.input?.salinity,
      [series.key]: item?.[series.key] ?? item?.output?.[series.key]
    })).filter(row => Number.isFinite(Number(row.pressure)))
  }

  const pressure = result?.input?.pressure ?? Number(initialPressure.value)
  return [{
    pressure,
    temperature: result?.input?.temperature ?? Number(reservoirTemperature.value),
    salinity: result?.input?.salinity ?? Number(salinity.value),
    [series.key]: result?.output?.[series.key]
  }]
}

// 按压力合并同一曲线中的多个输出序列，并按压力升序排列。
const mergeSeriesRows = (seriesResults) => {
  const rowsByPressure = new Map()
  seriesResults.flat().forEach(row => {
    const pressure = Number(row.pressure)
    if (!Number.isFinite(pressure)) return
    const key = String(pressure)
    rowsByPressure.set(key, { ...(rowsByPressure.get(key) || {}), ...row, pressure })
  })
  return [...rowsByPressure.values()].sort((left, right) => left.pressure - right.pressure)
}

// 按需加载当前曲线的工具箱结果
const loadActiveCurve = async ({ force = false } = {}) => {
  const curve = activeCurveOption.value
  if (!force && curveResults.value[curve.name]) {
    await nextTick()
    renderChart()
    return
  }
  if (analysisLoading.value) return

  const sequence = ++analysisSequence
  analysisLoading.value = true
  try {
    // 每个算法严格只创建一次实例并读取一次结果
    // 并行加载当前曲线包含的一个或多个输出序列
    const seriesResults = await Promise.all(curve.series.map(async series => {
      const created = unwrapResponse(
        await toolboxApi.create(series.algorithm, Number(props.projectId))
      )
      const id = created?.id
      if (id === null || id === undefined || id === '') {
        throw new Error(`${series.algorithm} 未返回工具箱 id`)
      }
      const result = unwrapResponse(await toolboxApi.getResult(id))
      collectFields(result?.fields)
      return extractRows(result, series)
    }))

    if (sequence !== analysisSequence) return
    curveResults.value = {
      ...curveResults.value,
      [curve.name]: {
        rows: mergeSeriesRows(seriesResults)
      }
    }
    await nextTick()
    renderChart()
  } catch (error) {
    if (sequence !== analysisSequence) return
    ElMessage.error(error.response?.data?.message || error.message || '地层水性质曲线加载失败')
  } finally {
    if (sequence === analysisSequence) {
      analysisLoading.value = false
      if (activeCurve.value !== curve.name && !curveResults.value[activeCurve.value]) {
        loadActiveCurve()
      }
    }
  }
}

const chartOption = computed(() => {
  const curve = activeCurveOption.value
  const hasRightAxis = curve.series.some(series => series.yAxisIndex === 1)
  const yAxis = hasRightAxis
    ? curve.series.map((series, index) => ({
        type: 'value',
        name: `${series.name}(${series.unit})`,
        position: index === 0 ? 'left' : 'right',
        nameLocation: 'middle',
        nameGap: index === 0 ? 52 : 62,
        axisLine: { show: true },
        // 所有输出坐标轴刻度统一保留两位小数。
        axisLabel: { formatter: value => Number(value).toFixed(2) },
        splitLine: { show: index === 0, lineStyle: { color: '#e8edf2' } }
      }))
    : {
        type: 'value',
        name: `${curve.series[0].name}(${curve.series[0].unit})`,
        nameLocation: 'middle',
        nameGap: 54,
        axisLine: { show: true },
        // 单 Y 轴曲线同样固定显示两位小数
        axisLabel: { formatter: value => Number(value).toFixed(2) },
        splitLine: { lineStyle: { color: '#e8edf2' } }
      }

  return {
    animation: false,
    color: ['#1677ff', '#ef7d00'],
    tooltip: {
      trigger: 'axis',
      // 组合压力和各输出序列，生成统一保留两位小数的提示内容
      formatter: (params) => {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ''
        const lines = [`压力：${items[0]?.value?.[0] ?? ''} MPa`]
        items.forEach(item => {
          const series = curve.series.find(entry => entry.name === item.seriesName)
          if (!series) return
          lines.push(`${item.marker}${series.name}：${formatOutputValue(item.value?.[1])} ${series.unit}`)
        })
        return lines.join('<br/>')
      }
    },
    legend: {
      show: curve.series.length > 1,
      top: 10
    },
    grid: {
      left: 86,
      right: hasRightAxis ? 96 : 36,
      top: curve.series.length > 1 ? 52 : 30,
      bottom: 58
    },
    xAxis: {
      type: 'value',
      name: '压力 P(MPa)',
      nameLocation: 'middle',
      nameGap: 34,
      min: value => Math.min(0, value.min),
      axisLine: { show: true },
      splitLine: { lineStyle: { color: '#e8edf2' } }
    },
    yAxis,
    series: curve.series.map(series => ({
      name: series.name,
      type: 'line',
      yAxisIndex: series.yAxisIndex || 0,
      showSymbol: true,
      symbolSize: 7,
      smooth: activeRows.value.length > 2,
      connectNulls: false,
      // 过滤无效输出，并转换为 ECharts 所需的 [压力, 输出值] 数据点
      data: activeRows.value
        .filter(row => Number.isFinite(Number(row[series.key])))
        .map(row => [Number(row.pressure), Number(row[series.key])])
    }))
  }
})

// 创建或更新图表实例
const renderChart = () => {
  if (activeResultTab.value !== '结果分析图' || !chartEl.value) return
  if (chart && chart.getDom() !== chartEl.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartEl.value)
  chart.setOption(chartOption.value, true)
  chart.resize()
}

// 切换到数据列表，并释放当前不可见的图表实例
const showDataList = () => {
  activeResultTab.value = '数据列表'
  chart?.dispose()
  chart = null
}

// 切换到结果分析页，初始化图表并加载当前曲线数据
const showAnalysis = async () => {
  activeResultTab.value = '结果分析图'
  await nextTick()
  renderChart()
  loadActiveCurve()
}

// 清除当前曲线缓存并重新向工具箱获取最新结果
const reloadActiveCurve = () => {
  const nextResults = { ...curveResults.value }
  delete nextResults[activeCurve.value]
  curveResults.value = nextResults
  loadActiveCurve({ force: true })
}

// 浏览器尺寸变化时同步调整 ECharts 画布尺寸
const handleResize = () => chart?.resize()

// 监听曲线选择变化，切换图表并按需加载对应数据
watch(activeCurve, async () => {
  if (activeResultTab.value !== '结果分析图') return
  await nextTick()
  renderChart()
  loadActiveCurve()
})

// 监听当前井变化，清除旧井曲线缓存并重新加载基础数据
watch(() => props.wellName, () => {
  analysisSequence += 1
  curveResults.value = {}
  loadSourceData()
}, { immediate: true })

window.addEventListener('resize', handleResize)

// 组件卸载时终止旧请求结果写入，并移除事件和图表实例
onBeforeUnmount(() => {
  sourceSequence += 1
  analysisSequence += 1
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="water-properties-view">
    <div v-if="activeResultTab === '数据列表'" class="water-workspace">
      <aside class="water-parameter-panel">
        <div class="water-parameter-section">
          <div class="water-section-heading">
            <span>计算方法</span>
            <span class="water-section-rule"></span>
          </div>

          <label class="water-field-group">
            <span>地层水体积系数计算方法</span>
            <select v-model="volumeFactorMethod">
              <option>McCain方法</option>
              <option>Standing方法</option>
            </select>
          </label>

          <label class="water-field-group">
            <span>地层水压缩系数计算方法</span>
            <select v-model="compressibilityMethod">
              <option>Meehan方法</option>
              <option>Dodson-Standing方法</option>
            </select>
          </label>
        </div>

        <div class="water-parameter-section">
          <div class="water-section-heading">
            <span>地层水数据</span>
            <span class="water-section-rule"></span>
          </div>

          <label class="water-field-group">
            <span>地层水矿化度（mg/L）</span>
            <input v-model.number="salinity" inputmode="decimal" />
          </label>
          <label class="water-field-group">
            <span>原始地层压力（MPa）</span>
            <input v-model.number="initialPressure" inputmode="decimal" />
          </label>
          <label class="water-field-group">
            <span>地层温度（℃）</span>
            <input v-model.number="reservoirTemperature" inputmode="decimal" />
          </label>
        </div>
      </aside>

      <div class="water-data-table">
        <el-table v-loading="sourceLoading" :data="filteredSourceRows" border height="100%" empty-text="当前井暂无数据" :row-key="row => row._rowKey">
          <el-table-column v-for="column in sourceColumns" :key="column.key" :prop="column.key" :label="column.label" min-width="145" show-overflow-tooltip/>
        </el-table>
      </div>
    </div>

    <div v-else class="water-analysis-workspace" :class="{ 'table-collapsed': analysisTableCollapsed }">
      <aside class="water-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button v-if="analysisTableCollapsed" class="water-analysis-collapsed-tab" type="button" title="展开分析数据表" @click="analysisTableCollapsed = false">图表数据</button>

        <div v-else class="water-analysis-expanded">
          <div class="water-analysis-panel-heading">
            <span>图表数据</span>
            <div class="water-analysis-heading-actions">
              <el-button size="small" :loading="analysisLoading" @click="reloadActiveCurve">重新获取</el-button>
              <button class="water-analysis-toggle" type="button" title="收起图表数据" @click="analysisTableCollapsed = true">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
          </div>
          <div class="water-analysis-table-body">
            <el-table v-loading="analysisLoading" :data="activeRows" border height="100%" empty-text="暂无分析数据">
              <el-table-column v-for="column in analysisColumns" :key="column.key" :prop="column.key" :label="column.label" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ formatValue(row, column.key) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </aside>

      <section v-loading="analysisLoading" class="water-chart-panel">
        <div class="water-curve-selector">
          <label v-for="curve in CURVE_OPTIONS" :key="curve.name">
            <input v-model="activeCurve" type="radio" :value="curve.name" />
            <span>{{ curve.name }}</span>
          </label>
        </div>
        <div ref="chartEl" class="water-chart"></div>
      </section>
    </div>

    <footer class="water-result-tabs">
      <button type="button" class="water-result-tab" :class="{ active: activeResultTab === '数据列表' }" @click="showDataList">数据列表</button>
      <button type="button" class="water-result-tab" :class="{ active: activeResultTab === '结果分析图' }" @click="showAnalysis">结果分析图</button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.water-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.water-workspace,
.water-analysis-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.water-parameter-panel {
  width: 260px;
  flex: 0 0 260px;
  padding: 12px 14px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #d4d7db;
  border-right: 0;
  overflow-y: auto;
}

.water-parameter-section + .water-parameter-section {
  margin-top: 16px;
}

.water-section-heading {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.water-section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.water-field-group {
  display: block;
  margin-top: 10px;
  color: #404040;

  > span {
    display: block;
    margin-bottom: 4px;
    line-height: 18px;
  }

  select,
  input {
    width: 100%;
    height: 30px;
    padding: 2px 8px;
    box-sizing: border-box;
    border: 1px solid #aeb6bf;
    border-radius: 3px;
    background: #fff;
    color: #333;
    font: inherit;
    outline: none;

    &:focus {
      border-color: #4c81b6;
      box-shadow: 0 0 0 1px rgba(76, 129, 182, 0.18);
    }
  }
}

.water-data-table {
  flex: 1;
  min-width: 0;
  border: 1px solid #d4d7db;
  overflow: hidden;
}

.water-analysis-panel {
  width: 760px;
  flex: 0 0 760px;
  height: 100%;
  min-height: 0;
  display: flex;
  position: relative;
  transition: width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    flex-basis: 34px;
    border: 1px solid #d4d7db;
    border-right: 0;
    background: #fff;
  }
}

.water-analysis-expanded {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #d4d7db;
  border-right: 0;
  background: #fff;
  overflow: hidden;
}

.water-analysis-panel-heading {
  height: 36px;
  flex: 0 0 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  color: #222;
}

.water-analysis-heading-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.water-analysis-table-body {
  flex: 1;
  min-height: 0;
}

.water-analysis-toggle {
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 2px;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:hover {
    background: #eef4ff;
  }
}

.water-analysis-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 8px 0 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  background: #fff;
  color: #222;
  cursor: pointer;
  font: inherit;
  writing-mode: vertical-rl;
  text-orientation: upright;

  &:hover {
    background: #eef4ff;
    color: #1677ff;
  }
}

.water-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.water-curve-selector {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 14px;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  background: #fafbfc;

  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #222;
    cursor: pointer;
    white-space: nowrap;
  }

  input {
    width: 14px;
    height: 14px;
    margin: 0;
    accent-color: #1677ff;
  }
}

.water-chart {
  flex: 1;
  min-height: 0;
  width: 100%;
}

.water-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: flex-end;
  padding-left: 12px;
  box-sizing: border-box;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.water-result-tab {
  min-width: 88px;
  height: 32px;
  padding: 0 16px;
  border: 0;
  border-right: 1px solid #e4e7ed;
  border-top: 2px solid #e4e7ed;
  background: #fff;
  color: #555;
  font: inherit;
  cursor: pointer;
  white-space: nowrap;

  &:hover {
    color: #111;
  }

  &.active {
    color: #111;
    border-top-color: #111;
    font-weight: 600;
  }
}

:deep(.el-table) {
  --el-table-header-bg-color: #f4f4f4;
  --el-table-row-hover-bg-color: #f5f7fa;
  font-size: inherit;
}

:deep(.el-table th.el-table__cell) {
  height: 36px;
  color: #333;
  font-weight: 400;
}

:deep(.el-table td.el-table__cell) {
  height: 30px;
}

@media (max-width: 950px) {
  .water-parameter-panel {
    width: 240px;
    flex-basis: 240px;
  }

  .water-analysis-panel {
    width: 640px;
    flex-basis: 640px;
  }
}
</style>
