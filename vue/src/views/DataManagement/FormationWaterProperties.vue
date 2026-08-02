<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { waterPvtApi } from '@/api/waterPvt'

const props = defineProps({
  wellName: { type: String, required: true },
  projectId: { type: [Number, String], required: true },
  gasRows: { type: Array, default: () => [] },
  importedRows: { type: Array, default: () => [] },
  importedResultRows: { type: Array, default: () => [] }
})

const emit = defineEmits(['result-tab-change', 'calculated'])

const SOURCE_FALLBACK_COLUMNS = [
  { key: 'gasType', label: '天然气类型' },
  { key: 'relativeDensity', label: '天然气比重(dless)' },
  { key: 'h2sContent', label: 'H₂S摩尔百分含量(%)' },
  { key: 'co2Content', label: 'CO₂摩尔百分含量(%)' },
  { key: 'nitrogenContent', label: 'N₂摩尔百分含量(%)' }
]
const waterTableColumns = ['序号', ...SOURCE_FALLBACK_COLUMNS.map(column => column.label)]
const waterGridTemplateColumns = computed(
  () => `48px repeat(${SOURCE_FALLBACK_COLUMNS.length}, minmax(145px, 1fr))`
)

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

const DEFAULT_CALCULATION_INPUT = {
  pressure: 40,
  temperature: 119.85,
  salinity: 25000
}

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
const curveResults = ref({})
const chartEl = ref(null)
let chart = null
let calculationSequence = 0

// 左侧计算、重置状态及已创建的工具箱实例 ID。
const calculating = ref(false)
const calculationMode = ref('')
const calculationResult = ref({
  gasSolubilityInWater: '',
  volumeFactor: '',
  isothermalCompressionCoefficient: '',
  viscosity: '',
  density: ''
})

// 根据当前选中的曲线名称返回对应的曲线配置
const activeCurveOption = computed(
  () => CURVE_OPTIONS.find(option => option.name === activeCurve.value) ?? CURVE_OPTIONS[0]
)

// 提取真正数据
const unwrapResponse = (response) =>
  response?.data?.data ?? response?.data ?? response ?? {}

// 将单个计算输出统一转换为结果组件要求的两位小数字符串。
const formatCalculationOutput = (value) =>
  Number.isFinite(Number(value)) ? Number(value).toFixed(2) : ''

// 把工具箱详情中的输入、输出和字段元数据同步到左侧表单。
const applyCalculationResult = (result, { syncInput = false } = {}) => {
  if (syncInput && result?.input) {
    const pressure = Number(result.input.pressure)
    const temperature = Number(result.input.temperature)
    const waterSalinity = Number(result.input.salinity)
    if (Number.isFinite(pressure)) initialPressure.value = pressure
    if (Number.isFinite(temperature)) reservoirTemperature.value = temperature
    if (Number.isFinite(waterSalinity)) salinity.value = waterSalinity
  }

  calculationResult.value = {
    gasSolubilityInWater: formatCalculationOutput(result?.output?.gasSolubilityInWater),
    volumeFactor: formatCalculationOutput(result?.output?.volumeFactor),
    isothermalCompressionCoefficient: formatCalculationOutput(
      result?.output?.isothermalCompressionCoefficient
    ),
    viscosity: formatCalculationOutput(result?.output?.viscosity),
    density: formatCalculationOutput(result?.output?.density)
  }
}

// 提交三个地层水输入参数，由本地后端按压力序列聚合五项 PVT 结果。
const handleCalculate = async () => {
  if (calculating.value) return

  const calculationPressure = Number(initialPressure.value)
  const calculationTemperature = Number(reservoirTemperature.value)
  const calculationSalinity = Number(salinity.value)
  if (
    !Number.isFinite(calculationPressure) ||
    calculationPressure <= 0 ||
    !Number.isFinite(calculationTemperature) ||
    !Number.isFinite(calculationSalinity) ||
    calculationSalinity < 0
  ) {
    ElMessage.error('请输入有效的地层压力、地层温度和地层水矿化度')
    return
  }

  const sequence = ++calculationSequence
  calculating.value = true
  calculationMode.value = 'point'
  try {
    const request = {
      projectId: Number(props.projectId),
      originalPressure: calculationPressure,
      temperature: calculationTemperature,
      salinity: calculationSalinity,
      pressureStart: 5,
      pressureEnd: 200,
      pressureStep: 5,
      volumeFactorMethod: ['McCain方法', 'Standing方法'].indexOf(volumeFactorMethod.value),
      compressibilityMethod: ['Meehan方法', 'Dodson-Standing方法'].indexOf(compressibilityMethod.value)
    }
    const [curveOneResponse, curveTwoResponse, curveThreeResponse, curveFourResponse] =
      await Promise.all([
        waterPvtApi.calculateCurveOne(request),
        waterPvtApi.calculateCurveTwo(request),
        waterPvtApi.calculateCurveThree(request),
        waterPvtApi.calculateViscosityCurve(request)
      ])
    if (sequence !== calculationSequence) return

    const curveOne = unwrapResponse(curveOneResponse)
    const curveTwo = unwrapResponse(curveTwoResponse)
    const curveThree = unwrapResponse(curveThreeResponse)
    const curveFour = unwrapResponse(curveFourResponse)
    const resultSets = [
      curveOne?.items,
      curveTwo?.items,
      curveThree?.items,
      curveFour?.items
    ]
    if (resultSets.some(items => !Array.isArray(items) || !items.length)) {
      throw new Error('地层水曲线接口未返回完整数据')
    }

    curveResults.value = {
      曲线1: { rows: curveOne.items },
      曲线2: { rows: curveTwo.items },
      曲线3: { rows: curveThree.items },
      曲线4: { rows: curveFour.items }
    }
    const pointRows = mergeSeriesRows(resultSets)
    const nearestPoint = pointRows.reduce((nearest, row) =>
      Math.abs(Number(row.pressure) - calculationPressure) <
      Math.abs(Number(nearest?.pressure ?? Number.POSITIVE_INFINITY) - calculationPressure)
        ? row
        : nearest
    , null)
    applyCalculationResult({
      input: {
        pressure: calculationPressure,
        temperature: calculationTemperature,
        salinity: calculationSalinity
      },
      output: nearestPoint || {}
    })
    emit('calculated', {
      inputRows: [[calculationSalinity, calculationPressure, calculationTemperature]],
      resultRows: pointRows,
      settings: {
        volumeFactorMethod: volumeFactorMethod.value,
        compressibilityMethod: compressibilityMethod.value
      }
    })
    ElMessage.success('地层水性质计算完成')
  } catch (error) {
    if (sequence !== calculationSequence) return
    ElMessage.error(error.response?.data?.message || error.message || '地层水性质计算失败')
  } finally {
    if (sequence === calculationSequence) {
      calculating.value = false
      calculationMode.value = ''
    }
  }
}

// 与天然气模块一致，重置页面参数和已生成的曲线，不修改已导入基础数据。
const handleReset = () => {
  if (calculating.value) return
  calculationSequence += 1
  initialPressure.value = DEFAULT_CALCULATION_INPUT.pressure
  reservoirTemperature.value = DEFAULT_CALCULATION_INPUT.temperature
  salinity.value = DEFAULT_CALCULATION_INPUT.salinity
  volumeFactorMethod.value = 'McCain方法'
  compressibilityMethod.value = 'Meehan方法'
  Object.keys(calculationResult.value).forEach((key) => {
    calculationResult.value[key] = ''
  })
  curveResults.value = {}
  activeCurve.value = '曲线1'
  analysisTableCollapsed.value = false
}

const filteredSourceRows = computed(() => sourceRows.value)
const waterDataCells = computed(() => {
  const rowCount = 27
  const columnCount = waterTableColumns.length
  return Array.from({ length: rowCount * columnCount }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / columnCount)
    const columnIndex = cellIndex % columnCount
    const row = filteredSourceRows.value[rowIndex]
    const value = columnIndex === 0
      ? String(rowIndex + 1)
      : row?.[columnIndex - 1]
    return {
      key: `${rowIndex}-${columnIndex}`,
      value: value ?? '',
      columnIndex,
      imported: columnIndex > 0 && value !== undefined && value !== null && value !== ''
    }
  })
})

// 取得当前曲线对应的输入、输出数据行
const activeRows = computed(() => curveResults.value[activeCurve.value]?.rows || [])
const activeCurveHasData = computed(() =>
  activeCurveOption.value.series.some(series =>
    activeRows.value.some(row => Number.isFinite(Number(row?.[series.key])))
  )
)

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
const waterAnalysisTableColumns = computed(() => [
  '序号',
  ...analysisColumns.value.map(column => column.label)
])
const waterAnalysisGridTemplateColumns = computed(
  () => `48px repeat(${waterAnalysisTableColumns.value.length - 1}, minmax(0, 1fr))`
)
const waterAnalysisDataCells = computed(() => {
  const columns = analysisColumns.value
  const columnCount = waterAnalysisTableColumns.value.length
  const rowCount = Math.max(25, activeRows.value.length)
  return Array.from({ length: rowCount * columnCount }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / columnCount)
    const columnIndex = cellIndex % columnCount
    const row = activeRows.value[rowIndex]
    return {
      key: `${activeCurve.value}-${rowIndex}-${columnIndex}`,
      value: columnIndex === 0
        ? String(rowIndex + 1)
        : (row ? formatValue(row, columns[columnIndex - 1].key) : ''),
      columnIndex
    }
  })
})

// 输出固定两位小数
const formatValue = (row, key) => {
  const value = row?.[key]
  if (value === null || value === undefined || value === '') return ''
  if (!Number.isFinite(Number(value))) return value
  if (key === 'isothermalCompressionCoefficient') return Number(value).toExponential(6)
  if (OUTPUT_KEYS.has(key)) return Number(value).toFixed(6)
  return Number(value).toFixed(key === 'temperature' || key === 'salinity' ? 2 : 4)
}

const formatOutputValue = (value, key) => {
  if (!Number.isFinite(Number(value))) return ''
  return key === 'isothermalCompressionCoefficient'
    ? Number(value).toExponential(6)
    : Number(value).toFixed(6)
}

// 地层水模块右侧数据列表与天然气模块共用天然气组成数据。
const loadSourceData = () => {
  sourceRows.value = props.gasRows.map(row => [...row])
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

// 曲线数据由“计算”或“结果数据导入”一次性写入，切换曲线只负责重绘。
const loadActiveCurve = async () => {
  await nextTick()
  renderChart()
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
        nameGap: 44,
        axisLabel: {
          formatter: value => series.key === 'isothermalCompressionCoefficient'
            ? Number(value).toExponential(2)
            : Number(value).toFixed(4)
        },
        minorTick: { show: index === 0 },
        minorSplitLine: {
          show: index === 0,
          lineStyle: { color: '#f1f5fb' }
        },
        splitLine: {
          show: index === 0,
          lineStyle: { color: '#dce5f2' }
        }
      }))
    : {
        type: 'value',
        name: `${curve.series[0].name}(${curve.series[0].unit})`,
        nameLocation: 'middle',
        nameGap: 44,
        axisLabel: {
          formatter: value => curve.series[0].key === 'isothermalCompressionCoefficient'
            ? Number(value).toExponential(2)
            : Number(value).toFixed(4)
        },
        minorTick: { show: true },
        minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
        splitLine: { lineStyle: { color: '#dce5f2' } }
      }

  return {
    animation: false,
    color: curve.series.map(() => '#1677ff'),
    title: {
      text: `${curve.title}随压力变化曲线`,
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: '#d936d0', type: 'solid', width: 1 }
      },
      // 组合压力和各输出序列，生成统一保留两位小数的提示内容
      formatter: (params) => {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ''
        const lines = [`压力：${items[0]?.value?.[0] ?? ''} MPa`]
        items.forEach(item => {
          const series = curve.series.find(entry => entry.name === item.seriesName)
          if (!series) return
          lines.push(`${item.marker}${series.name}：${formatOutputValue(item.value?.[1], series.key)} ${series.unit}`)
        })
        return lines.join('<br/>')
      }
    },
    legend: {
      show: curve.series.length > 1,
      type: 'scroll',
      top: 30,
      left: 62,
      right: 92,
      data: curve.series.map(series => series.name)
    },
    grid: {
      left: 62,
      right: 92,
      top: curve.series.length > 1 ? 70 : 44,
      bottom: 56
    },
    xAxis: {
      type: 'value',
      min: 5,
      name: '压力 P(MPa)',
      nameLocation: 'middle',
      nameGap: 34,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis,
    series: curve.series.map(series => {
      const isCurveTwoDensity = curve.name === '曲线2' && series.key === 'density'
      return {
        name: series.name,
        type: 'line',
        yAxisIndex: series.yAxisIndex || 0,
        showSymbol: false,
        symbol: 'none',
        smooth: true,
        connectNulls: false,
        itemStyle: { color: '#1677ff' },
        lineStyle: {
          color: '#1677ff',
          width: 1.5,
          type: isCurveTwoDensity ? 'dashed' : 'solid'
        },
        // 过滤无效输出，并转换为 ECharts 所需的 [压力, 输出值] 数据点
        data: activeRows.value
          .filter(row => Number.isFinite(Number(row[series.key])))
          .map(row => [Number(row.pressure), Number(row[series.key])])
      }
    })
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

// 使用当前三项输入重新计算全部四类曲线。
const reloadActiveCurve = () => {
  handleCalculate()
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

watch(() => props.gasRows, () => {
  curveResults.value = {}
  loadSourceData()
}, { immediate: true, deep: true })

watch(
  () => props.importedRows,
  (rows) => {
    const firstRow = rows?.[0]
    if (!firstRow) return
    salinity.value = Number(firstRow[0])
    initialPressure.value = Number(firstRow[1])
    reservoirTemperature.value = Number(firstRow[2])
  },
  { immediate: true, deep: true }
)

watch(
  () => props.importedResultRows,
  (rows) => {
    if (!Array.isArray(rows) || !rows.length) return
    // 节点页面首次打开时直接恢复已保存的四类曲线，避免刷新后要求重复计算。
    const items = rows.map(row => ({
      pressure: Number(row.pressure),
      temperature: Number(row.temperature),
      salinity: Number(row.salinity),
      gasSolubilityInWater: Number(row.gasSolubilityInWater),
      volumeFactor: Number(row.volumeFactor),
      density: Number(row.density),
      isothermalCompressionCoefficient: Number(row.isothermalCompressionCoefficient),
      viscosity: Number(row.viscosity)
    }))
    curveResults.value = {
      曲线1: { rows: items },
      曲线2: { rows: items },
      曲线3: { rows: items },
      曲线4: { rows: items }
    }
    activeCurve.value = '曲线1'
    loadActiveCurve()
  },
  { immediate: true, deep: true }
)

watch(activeResultTab, (value) => {
  emit('result-tab-change', value)
}, { immediate: true })

window.addEventListener('resize', handleResize)

// 组件卸载时终止旧请求结果写入，并移除事件和图表实例
onBeforeUnmount(() => {
  calculationSequence += 1
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
          <div class="water-parameter-actions">
            <button type="button" :disabled="calculating" @click="handleCalculate">
              {{ calculationMode === 'point' ? '计算中...' : '计算' }}
            </button>
            <button type="button" :disabled="calculating" @click="handleReset">
              {{ calculationMode === 'reset' ? '重置中...' : '重置' }}
            </button>
          </div>
        </div>

<!--        <div class="water-parameter-section">-->
<!--          <div class="water-section-heading">-->
<!--            <span>地层水计算结果</span>-->
<!--            <span class="water-section-rule"></span>-->
<!--          </div>-->

<!--          <label class="water-field-group water-result-field">-->
<!--            <span>天然气在地层水中的溶解度(dless)</span>-->
<!--            <input v-model="calculationResult.gasSolubilityInWater" type="text" readonly />-->
<!--          </label>-->

<!--          <label class="water-field-group water-result-field">-->
<!--            <span>地层水体积系数(dless)</span>-->
<!--            <input v-model="calculationResult.volumeFactor" type="text" readonly />-->
<!--          </label>-->

<!--          <label class="water-field-group water-result-field">-->
<!--            <span>地层水等温压缩系数(MPa⁻¹)</span>-->
<!--            <input v-model="calculationResult.isothermalCompressionCoefficient" type="text" readonly />-->
<!--          </label>-->

<!--          <label class="water-field-group water-result-field">-->
<!--            <span>地层水粘度(mPa·s)</span>-->
<!--            <input v-model="calculationResult.viscosity" type="text" readonly />-->
<!--          </label>-->

<!--          <label class="water-field-group water-result-field">-->
<!--            <span>地层水密度(kg/m³)</span>-->
<!--            <input v-model="calculationResult.density" type="text" readonly />-->
<!--          </label>-->
<!--        </div>-->

      </aside>

      <div
        v-loading="sourceLoading"
        class="water-data-grid"
        aria-label="地层水性质数据表格"
        :style="{ gridTemplateColumns: waterGridTemplateColumns }"
      >
        <div
          v-for="column in waterTableColumns"
          :key="column"
          class="water-grid-cell header"
        >
          {{ column }}
        </div>
        <div
          v-for="cell in waterDataCells"
          :key="cell.key"
          class="water-grid-cell"
          :class="{
            imported: cell.imported,
            numeric: cell.columnIndex > 1,
            'row-index': cell.columnIndex === 0
          }"
        >
          {{ cell.value }}
        </div>
      </div>
    </div>

    <div v-else class="water-analysis-workspace" :class="{ 'table-collapsed': analysisTableCollapsed }">
      <aside class="water-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button v-if="analysisTableCollapsed" class="water-analysis-collapsed-tab" type="button" title="展开分析数据表" @click="analysisTableCollapsed = false">图表数据</button>

        <div v-else class="water-analysis-expanded">
          <div class="water-analysis-panel-heading">
            <span>图表数据</span>
            <div class="water-analysis-heading-actions">
              <el-button size="small" :loading="calculating" @click="reloadActiveCurve">重新计算</el-button>
              <button class="water-analysis-toggle" type="button" title="收起图表数据" @click="analysisTableCollapsed = true">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
          </div>
          <div
            v-loading="analysisLoading"
            class="water-analysis-grid"
            aria-label="地层水分析数据表格"
            :style="{ gridTemplateColumns: waterAnalysisGridTemplateColumns }"
          >
            <div
              v-for="column in waterAnalysisTableColumns"
              :key="column"
              class="water-analysis-grid-cell header"
            >
              {{ column }}
            </div>
            <div
              v-for="cell in waterAnalysisDataCells"
              :key="cell.key"
              class="water-analysis-grid-cell"
              :class="{
                numeric: cell.value !== '',
                'row-index': cell.columnIndex === 0
              }"
            >
              {{ cell.value }}
            </div>
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
        <div class="water-chart">
          <div class="water-chart-plot-shell">
            <div ref="chartEl" class="water-chart-plot"></div>
            <div v-if="!activeCurveHasData" class="water-chart-empty">
              暂无计算结果
            </div>
          </div>
        </div>
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

.water-result-field {
  input[readonly] {
    background: #f5f7fa;
    color: #303133;
    cursor: default;
  }
}

.water-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-rows: 36px repeat(27, minmax(30px, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
}

.water-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;
  padding: 0 8px;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.imported {
    background: #fbfdff;
  }

  &.numeric {
    justify-content: flex-end;
    font-variant-numeric: tabular-nums;
  }

  &.header {
    justify-content: center;
    padding: 0 8px;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    text-align: center;
  }

  &.row-index {
    justify-content: center;
    padding: 0 6px;
    background: #f4f4f4;
    color: #333;
  }
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

.water-analysis-grid {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-rows: 42px;
  grid-auto-rows: max(30px, calc((100% - 42px) / 25));
  overflow: auto;
}

.water-analysis-grid-cell {
  min-width: 0;
  border-right: 1px solid #d4d7db;
  border-bottom: 1px solid #d4d7db;
  background: #fff;

  &.numeric {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    padding: 0 8px;
    box-sizing: border-box;
    font-variant-numeric: tabular-nums;
  }

  &.header {
    position: sticky;
    top: 0;
    z-index: 2;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 4px 6px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
    line-height: 1.35;
    text-align: center;
    white-space: nowrap;
  }

  &.row-index {
    justify-content: center;
    padding: 0 6px;
    background: #f4f4f4;
    color: #333;
  }
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
  display: flex;
  padding: 8px;
  box-sizing: border-box;
}

.water-chart-plot-shell {
  flex: 1;
  position: relative;
  min-width: 0;
  min-height: 0;
}

.water-chart-plot {
  position: absolute;
  inset: 0;
}

.water-chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  color: #999;
  z-index: 1;
  pointer-events: none;
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

.water-parameter-actions {
  display: flex;
  gap: 10px;
  margin-top: 22px;

  button {
    flex: 1;
    height: 32px;
    padding: 0 12px;
    border: 1px solid #777;
    border-radius: 5px;
    background: #fff;
    color: #222;
    font: inherit;
    cursor: pointer;

    &:hover {
      border-color: #333;
      background: #f5f5f5;
    }

    &:focus-visible {
      outline: 2px solid rgba(47, 116, 192, 0.25);
      outline-offset: 2px;
    }

    &:active {
      background: #ebebeb;
    }

    &:disabled {
      border-color: #c8c8c8;
      background: #f3f3f3;
      color: #999;
      cursor: not-allowed;
    }
  }
}
</style>
