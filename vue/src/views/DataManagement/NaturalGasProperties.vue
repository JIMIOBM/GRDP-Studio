<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { gasPvtApi } from '@/api/gasPvt'

const props = defineProps({
  // importedRows 是“数据列表”中的基础气体数据；目前每个 PVT 性质只使用一条有效数据。
  importedRows: { type: Array, default: () => [] },
  // importedResultRows 来自“结果分析图”导入，用于直接回填四条曲线，不再请求后端计算。
  importedResultRows: { type: Array, default: () => [] },
  projectId: { type: [Number, String], required: true }
})

const emit = defineEmits(['result-tab-change', 'calculated'])

const DEFAULT_GAS_CORRECTION_METHOD = 'Wichert-Aziz 修正方法'
const DEFAULT_DEVIATION_FACTOR_METHOD = 'Dranchuk-Abu-Kassem 方法'
const DEFAULT_VISCOSITY_METHOD = 'Lee-Gonzalez-Eakin 方法'
const DEFAULT_TEMPERATURE = '39.35'

const activeResultTab = ref('数据列表')
const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const gasCorrectionMethod = ref(DEFAULT_GAS_CORRECTION_METHOD)
const deviationFactorMethod = ref(DEFAULT_DEVIATION_FACTOR_METHOD)
const viscosityMethod = ref(DEFAULT_VISCOSITY_METHOD)
const reservoirTemperature = ref(DEFAULT_TEMPERATURE)
const calculating = ref(false)
// 每个数组代表一种图表，数组元素代表不同来源行的一组 series。
// 目前业务只允许一条基础数据，保留 series 结构可让图表与表格切换逻辑保持统一。
const curveOneSeries = ref([])
const selectedCurveOneSourceRow = ref(null)
const curveTwoSeries = ref([])
const selectedCurveTwoSourceRow = ref(null)
const curveThreeSeries = ref([])
const selectedCurveThreeSourceRow = ref(null)
const viscosityCurveSeries = ref([])
const selectedViscositySourceRow = ref(null)
const selectedViscositySeries = computed(
  () => viscosityCurveSeries.value.find(
    curve => curve.sourceRow === selectedViscositySourceRow.value
  ) ?? viscosityCurveSeries.value[0] ?? null
)
const viscosityCurveRows = computed(
  () => selectedViscositySeries.value?.items ?? []
)
const selectedCurveOneSeries = computed(
  () => curveOneSeries.value.find(
    curve => curve.sourceRow === selectedCurveOneSourceRow.value
  ) ?? curveOneSeries.value[0] ?? null
)
const curveOneRows = computed(
  () => selectedCurveOneSeries.value?.items ?? []
)
const selectedCurveTwoSeries = computed(
  () => curveTwoSeries.value.find(
    curve => curve.sourceRow === selectedCurveTwoSourceRow.value
  ) ?? curveTwoSeries.value[0] ?? null
)
const curveTwoRows = computed(
  () => selectedCurveTwoSeries.value?.items ?? []
)
const selectedCurveThreeSeries = computed(
  () => curveThreeSeries.value.find(
    curve => curve.sourceRow === selectedCurveThreeSourceRow.value
  ) ?? curveThreeSeries.value[0] ?? null
)
const curveThreeRows = computed(
  () => selectedCurveThreeSeries.value?.items ?? []
)
const activeAnalysisRows = computed(() => {
  if (activeCurve.value === '曲线1') return curveOneRows.value
  if (activeCurve.value === '曲线2') return curveTwoRows.value
  if (activeCurve.value === '曲线3') return curveThreeRows.value
  if (activeCurve.value === '曲线4') return viscosityCurveRows.value
  return []
})
const activeCurveHasData = computed(() => {
  if (activeCurve.value === '曲线1') return curveOneSeries.value.length > 0
  if (activeCurve.value === '曲线2') return curveTwoSeries.value.length > 0
  if (activeCurve.value === '曲线3') return curveThreeSeries.value.length > 0
  if (activeCurve.value === '曲线4') return viscosityCurveSeries.value.length > 0
  return false
})
const chartEl = ref(null)
let chart = null
let chartRenderFrame = null
let chartResizeTimer = null

const gasPropertyColumns = [
  '天然气类型',
  '天然气比重(dless)',
  'H₂S摩尔百分含量(%)',
  'CO₂摩尔百分含量(%)',
  'N₂摩尔百分含量(%)'
]
const gasTableColumns = ['序号', ...gasPropertyColumns]
// 多组数据曾使用不同颜色；即使当前只有一组，统一色板仍由图表渲染逻辑管理。
const curveColors = [
  '#1677ff',
  '#f56c6c',
  '#67c23a',
  '#e6a23c',
  '#8b5cf6',
  '#13c2c2',
  '#eb2f96',
  '#fa8c16'
]

const gasGridTemplateColumns = computed(
  () => `48px repeat(${gasPropertyColumns.length}, minmax(145px, 1fr))`
)

// 同一份配置同时决定结果表头、ECharts 左/右 Y 轴名称和当前曲线。
const gasCurveOptions = [
  {
    name: '曲线1',
    leftYAxis: '天然气偏差系数 Z(dless)',
    rightYAxis: '气体拟压力 m(p)(MPa²/(mPa·s))',
    leftTableColumn: '天然气偏差系数(dless)',
    rightTableColumn: '气体拟压力(MPa²/(mPa·s))'
  },
  {
    name: '曲线2',
    leftYAxis: '天然气体积系数 Bg(dless)',
    rightYAxis: '天然气密度 ρg(kg/m³)',
    leftTableColumn: '天然气体积系数(dless)',
    rightTableColumn: '天然气密度(kg/m³)'
  },
  {
    name: '曲线3',
    leftYAxis: '天然气压缩系数 Cg(MPa⁻¹)',
    leftTableColumn: '天然气压缩系数(MPa⁻¹)'
  },
  {
    name: '曲线4',
    leftYAxis: '天然气粘度 μg(mPa·s)',
    leftTableColumn: '天然气粘度(mPa·s)'
  }
]

const activeCurveOption = computed(
  () => gasCurveOptions.find((curve) => curve.name === activeCurve.value) ?? gasCurveOptions[0]
)

const analysisTableColumns = computed(() => [
  '序号',
  '压力(MPa)',
  '温度(℃)',
  activeCurveOption.value.leftTableColumn,
  ...(activeCurveOption.value.rightTableColumn ? [activeCurveOption.value.rightTableColumn] : [])
])

const analysisGridTemplateColumns = computed(
  () => `48px repeat(${analysisTableColumns.value.length - 1}, minmax(0, 1fr))`
)

const analysisDataCells = computed(() => {
  // 结果表保留至少 25 个可视行；更多压力点通过容器滚动展示。
  const columnCount = analysisTableColumns.value.length
  const rowCount = Math.max(25, activeAnalysisRows.value.length)
  return Array.from({ length: columnCount * rowCount }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / columnCount)
    const columnIndex = cellIndex % columnCount
    const row = activeAnalysisRows.value[rowIndex] ?? null
    const values = [
      String(rowIndex + 1),
      ...(row
        ? [
          Number(row.pressure).toFixed(2),
          Number(row.temperature).toFixed(2),
          ...(activeCurve.value === '曲线1'
            ? [
                Number(row.deviationFactor).toFixed(6),
                Number(row.pseudoPressure).toFixed(6)
              ]
            : (activeCurve.value === '曲线2'
                ? [
                    Number(row.volumeFactor).toFixed(6),
                    Number(row.density).toFixed(6)
                  ]
                : (activeCurve.value === '曲线3'
                    ? [Number(row.compressibility).toExponential(6)]
                    : [Number(row.viscosity).toFixed(6)])))
        ]
        : [])
    ]
    return {
      key: `${activeCurve.value}-${rowIndex}-${columnIndex}`,
      value: values[columnIndex] ?? '',
      columnIndex
    }
  })
})

const gasDataCells = computed(() => {
  // 27 行只是为了保持原数据表格样式，真正参与计算的是 importedRows 中的有效行。
  const rowCount = 27
  return Array.from({ length: rowCount * gasTableColumns.length }, (_, cellIndex) => {
    const rowIndex = Math.floor(cellIndex / gasTableColumns.length)
    const columnIndex = cellIndex % gasTableColumns.length
    const importedValue = columnIndex === 0
      ? undefined
      : props.importedRows[rowIndex]?.[columnIndex - 1]
    return {
      key: `${rowIndex}-${columnIndex}`,
      value: columnIndex === 0 ? String(rowIndex + 1) : (importedValue ?? ''),
      columnIndex,
      imported: importedValue !== undefined && importedValue !== null && importedValue !== ''
    }
  })
})

const gasTypeIndexes = {
  干气: 0,
  湿气: 1,
  凝析气: 2
}
const gasTypeNames = ['干气', '湿气', '凝析气']

// request 拦截器和后端 ApiResponse 的包装方式可能不同，因此兼容常见的两层 data。
const unwrapResponse = (response) =>
  response?.data?.data ?? response?.data ?? response ?? {}

const renderChart = () => {
  // ECharts 直接挂载到右侧坐标区；折叠左表或窗口缩放后会重新 resize。
  const element = chartEl.value
  if (activeResultTab.value !== '结果分析图' || !element) return
  if (element.clientWidth <= 0 || element.clientHeight <= 0) return
  if (!chart || chart.getDom() !== element) {
    chart?.dispose()
    chart = echarts.init(element)
  }

  let chartSeries = []
  if (activeCurve.value === '曲线1') {
    chartSeries = curveOneSeries.value.flatMap((curve, index) => {
      const color = curve.color || curveColors[index % curveColors.length]
      return [
        {
          name: `序号${curve.sourceRow}-Z`,
          metric: 'deviationFactor',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          smooth: true,
          lineStyle: { color, width: 1.5, type: 'solid' },
          itemStyle: { color },
          data: curve.items.map(row => [
            Number(row.pressure),
            Number(row.deviationFactor)
          ])
        },
        {
          name: `序号${curve.sourceRow}-m(p)`,
          metric: 'pseudoPressure',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          smooth: true,
          lineStyle: { color, width: 1.5, type: 'dashed' },
          itemStyle: { color },
          data: curve.items.map(row => [
            Number(row.pressure),
            Number(row.pseudoPressure)
          ])
        }
      ]
    })
  } else if (activeCurve.value === '曲线2') {
    chartSeries = curveTwoSeries.value.flatMap((curve, index) => {
      const color = curve.color || curveColors[index % curveColors.length]
      return [
        {
          name: `序号${curve.sourceRow}-Bg`,
          metric: 'volumeFactor',
          type: 'line',
          yAxisIndex: 0,
          showSymbol: false,
          smooth: true,
          lineStyle: { color, width: 1.5, type: 'solid' },
          itemStyle: { color },
          data: curve.items.map(row => [
            Number(row.pressure),
            Number(row.volumeFactor)
          ])
        },
        {
          name: `序号${curve.sourceRow}-ρg`,
          metric: 'density',
          type: 'line',
          yAxisIndex: 1,
          showSymbol: false,
          smooth: true,
          lineStyle: { color, width: 1.5, type: 'dashed' },
          itemStyle: { color },
          data: curve.items.map(row => [
            Number(row.pressure),
            Number(row.density)
          ])
        }
      ]
    })
  } else if (activeCurve.value === '曲线3') {
    chartSeries = curveThreeSeries.value.map((curve, index) => {
      const color = curve.color || curveColors[index % curveColors.length]
      return {
        name: curve.name,
        metric: 'compressibility',
        type: 'line',
        showSymbol: false,
        smooth: true,
        lineStyle: { color, width: 1.5 },
        itemStyle: { color },
        data: curve.items.map(row => [
          Number(row.pressure),
          Number(row.compressibility)
        ])
      }
    })
  } else if (activeCurve.value === '曲线4') {
    chartSeries = viscosityCurveSeries.value.map((curve, index) => {
        const color = curve.color || curveColors[index % curveColors.length]
        return {
          name: curve.name,
          metric: 'viscosity',
          type: 'line',
          showSymbol: false,
          smooth: true,
          lineStyle: { color, width: 1.5 },
          itemStyle: { color },
          data: curve.items.map(row => [
            Number(row.pressure),
            Number(row.viscosity)
          ])
        }
      })
  }
  const hasMultipleSeries = chartSeries.length > 1

  chart.setOption({
    animation: false,
    color: chartSeries.map(series => series.lineStyle.color),
    title: {
      text: activeCurve.value === '曲线1'
        ? '天然气偏差系数与气体拟压力随压力变化曲线'
        : (activeCurve.value === '曲线2'
            ? '天然气体积系数与天然气密度随压力变化曲线'
            : (activeCurve.value === '曲线3'
                ? '天然气压缩系数随压力变化曲线'
                : (activeCurve.value === '曲线4' ? '天然气粘度随压力变化曲线' : activeCurve.value))),
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    legend: {
      show: hasMultipleSeries,
      type: 'scroll',
      top: 30,
      left: 62,
      right: 92,
      data: chartSeries.map(series => series.name)
    },
    grid: { left: 62, right: 92, top: hasMultipleSeries ? 70 : 44, bottom: 56 },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: '#d936d0', type: 'solid', width: 1 }
      },
      formatter: params => {
        const items = (Array.isArray(params) ? params : [params])
          .filter(item => item?.value)
        if (!items.length) return ''
        if (activeCurve.value === '曲线1') {
          return [
            `压力：${Number(items[0].value[0]).toFixed(2)} MPa`,
            ...items.map(item => {
              const unit = item.seriesName.endsWith('-Z')
                ? 'dless'
                : 'MPa²/(mPa·s)'
              return `${item.marker}${item.seriesName}：${Number(item.value[1]).toFixed(6)} ${unit}`
            })
          ].join('<br/>')
        }
        if (activeCurve.value === '曲线2') {
          return [
            `压力：${Number(items[0].value[0]).toFixed(2)} MPa`,
            ...items.map(item => {
              const unit = item.seriesName.endsWith('-Bg') ? 'dless' : 'kg/m³'
              return `${item.marker}${item.seriesName}：${Number(item.value[1]).toFixed(6)} ${unit}`
            })
          ].join('<br/>')
        }
        if (activeCurve.value === '曲线3') {
          return [
            `压力：${Number(items[0].value[0]).toFixed(2)} MPa`,
            ...items.map(item =>
              `${item.marker}${item.seriesName}：${Number(item.value[1]).toExponential(6)} MPa⁻¹`
            )
          ].join('<br/>')
        }
        return [
          `压力：${Number(items[0].value[0]).toFixed(2)} MPa`,
          ...items.map(item =>
            `${item.marker}${item.seriesName}：${Number(item.value[1]).toFixed(6)} mPa·s`
          )
        ].join('<br/>')
      }
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
    yAxis: [
      {
        type: 'value',
        name: activeCurveOption.value.leftYAxis,
        nameLocation: 'middle',
        nameGap: 44,
        axisLabel: {
          formatter: value => activeCurve.value === '曲线3'
            ? Number(value).toExponential(2)
            : Number(value).toFixed(4)
        },
        minorTick: { show: true },
        minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
        splitLine: { lineStyle: { color: '#dce5f2' } }
      },
      ...(activeCurveOption.value.rightYAxis
        ? [{
            type: 'value',
            name: activeCurveOption.value.rightYAxis,
            nameLocation: 'middle',
            nameGap: 44,
            axisLabel: { formatter: value => Number(value).toFixed(4) },
            splitLine: { show: false }
          }]
        : [])
    ],
    series: chartSeries
  }, true)
  chart.resize()
}

const disposeChart = () => {
  chart?.dispose()
  chart = null
}

const scheduleRenderChart = async () => {
  await nextTick()
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  chartRenderFrame = requestAnimationFrame(() => {
    chartRenderFrame = requestAnimationFrame(() => {
      chartRenderFrame = null
      renderChart()
    })
  })
}

const handleCalculate = async () => {
  // “计算”是整体曲线计算：先校验基础数据，再一次生成曲线 1～4。
  if (calculating.value) return

  const calculationTemperature = Number(reservoirTemperature.value)
  if (!Number.isFinite(calculationTemperature)) {
    ElMessage.error('请检查温度是否为有效数字')
    return
  }

  const sourceRows = props.importedRows
    .map((row, rowIndex) => ({ row, rowNumber: rowIndex + 1 }))
    .filter(({ row }) =>
      Array.isArray(row) && row.some(value => String(value ?? '').trim() !== '')
    )
  if (!sourceRows.length) {
    ElMessage.error('右侧表格中没有可计算的天然气数据')
    return
  }

  const rowsToCalculate = sourceRows
  const calculationRows = []
  for (const { row, rowNumber } of rowsToCalculate) {
    const gasTypeCell = String(row?.[0] ?? '').trim()
    const numericGasType = gasTypeCell === '' ? Number.NaN : Number(gasTypeCell)
    const gasType = Object.prototype.hasOwnProperty.call(gasTypeIndexes, gasTypeCell)
      ? gasTypeIndexes[gasTypeCell]
      : ([0, 1, 2].includes(numericGasType) ? numericGasType : undefined)
    if (gasType === undefined) {
      ElMessage.error(`右侧表格序号 ${rowNumber} 的天然气类型必须为干气、湿气或凝析气`)
      return
    }

    const values = {
      specificGravity: Number(row?.[1]),
      h2SMoleFraction: Number(row?.[2]),
      co2MoleFraction: Number(row?.[3]),
      n2MoleFraction: Number(row?.[4])
    }
    if (Object.values(values).some(value => !Number.isFinite(value)) ||
        values.specificGravity <= 0) {
      ElMessage.error(`请检查右侧表格序号 ${rowNumber} 是否为有效数据`)
      return
    }
    calculationRows.push({
      rowNumber,
      gasType,
      gasTypeName: gasTypeNames[gasType],
      ...values
    })
  }

  const modificationMethod = [
    'Wichert-Aziz 修正方法',
    'Carr-Kobayashi-Burrous 修正方法'
  ].indexOf(gasCorrectionMethod.value)
  const deviationMethod = [
    'Dranchuk-Abu-Kassem 方法',
    'Dranchuk-Purvis-Robinson 方法',
    'Hall-Yarborough 方法'
  ].indexOf(deviationFactorMethod.value)
  const selectedViscosityMethod = [
    'Lee-Gonzalez-Eakin 方法',
    'Carr-Kobayashi-Burrous 方法',
    'Sutton 方法'
  ].indexOf(viscosityMethod.value)

  // 四个后端接口使用同一套公共参数；压力固定为 5～200 MPa，间隔 5 MPa。
  const buildCurveRequest = row => ({
    projectId: Number(props.projectId),
    gasType: row.gasType,
    specificGravity: row.specificGravity,
    h2SMoleFraction: row.h2SMoleFraction,
    co2MoleFraction: row.co2MoleFraction,
    n2MoleFraction: row.n2MoleFraction,
    temperature: calculationTemperature,
    pressureStart: 5,
    pressureEnd: 200,
    pressureStep: 5,
    modificationMethod,
    deviationFactorMethod: deviationMethod,
    viscosityMethod: selectedViscosityMethod
  })

  calculating.value = true
  try {
    const calculatedCurveOneSeries = []
    const calculatedCurveTwoSeries = []
    const calculatedCurveThreeSeries = []
    const calculatedSeries = []
    for (const [index, row] of calculationRows.entries()) {
      try {
        const curveRequest = buildCurveRequest(row)
        // 四条曲线彼此独立，并行请求可缩短等待时间。
        // 曲线 1、2 在各自后端接口内部还会分别调用两种原平台算法。
        const [
          curveOneResponse,
          curveTwoResponse,
          curveThreeResponse,
          viscosityResponse
        ] = await Promise.all([
          gasPvtApi.calculateCurveOne(curveRequest),
          gasPvtApi.calculateCurveTwo(curveRequest),
          gasPvtApi.calculateCurveThree(curveRequest),
          gasPvtApi.calculateViscosityCurve(curveRequest)
        ])

        const curveOneResult = unwrapResponse(curveOneResponse)
        const curveOneItems = Array.isArray(curveOneResult?.items)
          ? curveOneResult.items
          : []
        if (!curveOneItems.length) {
          throw new Error('曲线1接口未返回数据')
        }
        calculatedCurveOneSeries.push({
          sourceRow: row.rowNumber,
          name: `序号${row.rowNumber}-${row.gasTypeName}`,
          color: curveColors[index % curveColors.length],
          items: curveOneItems
        })

        const curveTwoResult = unwrapResponse(curveTwoResponse)
        const curveTwoItems = Array.isArray(curveTwoResult?.items)
          ? curveTwoResult.items
          : []
        if (!curveTwoItems.length) {
          throw new Error('曲线2接口未返回数据')
        }
        calculatedCurveTwoSeries.push({
          sourceRow: row.rowNumber,
          name: `序号${row.rowNumber}-${row.gasTypeName}`,
          color: curveColors[index % curveColors.length],
          items: curveTwoItems
        })

        const curveThreeResult = unwrapResponse(curveThreeResponse)
        const curveThreeItems = Array.isArray(curveThreeResult?.items)
          ? curveThreeResult.items
          : []
        if (!curveThreeItems.length) {
          throw new Error('曲线3接口未返回数据')
        }
        calculatedCurveThreeSeries.push({
          sourceRow: row.rowNumber,
          name: `序号${row.rowNumber}-${row.gasTypeName}`,
          color: curveColors[index % curveColors.length],
          items: curveThreeItems
        })

        const curveResult = unwrapResponse(viscosityResponse)
        const items = Array.isArray(curveResult?.items) ? curveResult.items : []
        if (!items.length) {
          throw new Error('接口未返回曲线数据')
        }
        calculatedSeries.push({
          sourceRow: row.rowNumber,
          name: `序号${row.rowNumber}-${row.gasTypeName}`,
          color: curveColors[index % curveColors.length],
          items
        })
      } catch (error) {
        const message = error.response?.data?.message ||
          error.response?.data?.msg ||
          error.message ||
          '曲线计算失败'
        throw new Error(`序号 ${row.rowNumber}：${message}`)
      }
    }

    curveOneSeries.value = calculatedCurveOneSeries
    selectedCurveOneSourceRow.value = calculatedCurveOneSeries[0]?.sourceRow ?? null
    curveTwoSeries.value = calculatedCurveTwoSeries
    selectedCurveTwoSourceRow.value = calculatedCurveTwoSeries[0]?.sourceRow ?? null
    curveThreeSeries.value = calculatedCurveThreeSeries
    selectedCurveThreeSourceRow.value = calculatedCurveThreeSeries[0]?.sourceRow ?? null
    viscosityCurveSeries.value = calculatedSeries
    selectedViscositySourceRow.value = calculatedSeries[0]?.sourceRow ?? null
    activeCurve.value = '曲线1'
    const rowsByPressure = new Map()
    ;[
      ...calculatedCurveOneSeries.flatMap(series => series.items),
      ...calculatedCurveTwoSeries.flatMap(series => series.items),
      ...calculatedCurveThreeSeries.flatMap(series => series.items),
      ...calculatedSeries.flatMap(series => series.items)
    ].forEach(item => {
      const pressure = Number(item?.pressure)
      if (!Number.isFinite(pressure)) return
      rowsByPressure.set(pressure, {
        ...(rowsByPressure.get(pressure) || {}),
        ...item,
        pressure
      })
    })
    emit('calculated', {
      inputRows: props.importedRows.map(row => [...row]),
      resultRows: [...rowsByPressure.values()].sort((left, right) => left.pressure - right.pressure),
      settings: {
        gasCorrectionMethod: gasCorrectionMethod.value,
        deviationFactorMethod: deviationFactorMethod.value,
        viscosityMethod: viscosityMethod.value,
        reservoirTemperature: calculationTemperature
      }
    })
    ElMessage.success(`${calculatedSeries.length} 条天然气数据计算完成`)
  } catch (error) {
    ElMessage.error(
      error.response?.data?.message ||
      error.response?.data?.msg ||
      error.message ||
      '天然气性质计算失败'
    )
  } finally {
    calculating.value = false
  }
}

const handleReset = () => {
  // 重置计算方法、温度和曲线状态，但不删除已经导入的基础数据。
  gasCorrectionMethod.value = DEFAULT_GAS_CORRECTION_METHOD
  deviationFactorMethod.value = DEFAULT_DEVIATION_FACTOR_METHOD
  viscosityMethod.value = DEFAULT_VISCOSITY_METHOD
  reservoirTemperature.value = DEFAULT_TEMPERATURE
  curveOneSeries.value = []
  selectedCurveOneSourceRow.value = null
  curveTwoSeries.value = []
  selectedCurveTwoSourceRow.value = null
  curveThreeSeries.value = []
  selectedCurveThreeSourceRow.value = null
  viscosityCurveSeries.value = []
  selectedViscositySourceRow.value = null
  activeCurve.value = '曲线1'
  analysisTableCollapsed.value = false
}

watch(
  () => props.importedResultRows,
  (rows) => {
    // 结果模板的一行含有四条曲线的全部 Y 轴字段。
    // 导入后将同一批压力点装入四个 series，切换曲线即可显示对应字段。
    if (!Array.isArray(rows) || !rows.length) return

    const items = rows.map(row => ({
      pressure: Number(row.pressure),
      temperature: Number(row.temperature),
      deviationFactor: Number(row.deviationFactor),
      pseudoPressure: Number(row.pseudoPressure),
      volumeFactor: Number(row.volumeFactor),
      density: Number(row.density),
      compressibility: Number(row.compressibility),
      viscosity: Number(row.viscosity)
    }))
    const importedSeries = {
      sourceRow: 1,
      name: '导入结果',
      color: curveColors[0],
      items
    }

    curveOneSeries.value = [{ ...importedSeries }]
    selectedCurveOneSourceRow.value = 1
    curveTwoSeries.value = [{ ...importedSeries }]
    selectedCurveTwoSourceRow.value = 1
    curveThreeSeries.value = [{ ...importedSeries }]
    selectedCurveThreeSourceRow.value = 1
    viscosityCurveSeries.value = [{ ...importedSeries }]
    selectedViscositySourceRow.value = 1
    activeCurve.value = '曲线1'
    scheduleRenderChart()
  },
  { deep: true }
)

watch(activeResultTab, (value) => {
  emit('result-tab-change', value)
  if (value !== '结果分析图') {
    disposeChart()
    return
  }
  scheduleRenderChart()
}, { immediate: true })

watch([
  activeCurve,
  curveOneRows,
  curveOneSeries,
  curveTwoRows,
  curveTwoSeries,
  curveThreeRows,
  curveThreeSeries,
  viscosityCurveRows,
  viscosityCurveSeries
], () => {
  if (activeResultTab.value === '结果分析图') scheduleRenderChart()
})

watch(analysisTableCollapsed, () => {
  // 折叠动画完成前后都安排重绘，避免图表仍使用折叠前的容器宽度。
  if (activeResultTab.value !== '结果分析图') return
  scheduleRenderChart()
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  chartResizeTimer = setTimeout(() => {
    chartResizeTimer = null
    scheduleRenderChart()
  }, 180)
})

const handleResize = () => {
  if (activeResultTab.value === '结果分析图') scheduleRenderChart()
}
window.addEventListener('resize', handleResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartRenderFrame !== null) cancelAnimationFrame(chartRenderFrame)
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  disposeChart()
})
</script>

<template>
  <div class="gas-properties-view">
    <div v-if="activeResultTab === '数据列表'" class="gas-workspace">
      <aside class="gas-parameter-panel">
        <div class="gas-parameter-section">
          <div class="gas-section-heading">
            <span>计算方法</span>
            <span class="gas-section-rule"></span>
          </div>

          <label class="gas-field-group">
            <span>非烃气体修正方法</span>
            <select v-model="gasCorrectionMethod">
              <option>Wichert-Aziz 修正方法</option>
              <option>Carr-Kobayashi-Burrous 修正方法</option>
            </select>
          </label>

          <label class="gas-field-group">
            <span>天然气偏差系数计算方法</span>
            <select v-model="deviationFactorMethod">
              <option>Dranchuk-Abu-Kassem 方法</option>
              <option>Dranchuk-Purvis-Robinson 方法</option>
              <option>Hall-Yarborough 方法</option>
            </select>
          </label>

          <label class="gas-field-group">
            <span>天然气粘度计算方法</span>
            <select v-model="viscosityMethod">
              <option>Lee-Gonzalez-Eakin 方法</option>
              <option>Carr-Kobayashi-Burrous 方法</option>
              <option>Sutton 方法</option>
            </select>
          </label>

        </div>

        <div class="gas-parameter-section">
          <div class="gas-section-heading">
            <span>其他数据</span>
            <span class="gas-section-rule"></span>
          </div>

          <label class="gas-field-group">
            <span>温度（℃）</span>
            <input v-model="reservoirTemperature" inputmode="decimal" />
          </label>
        </div>

        <div class="gas-parameter-actions">
          <button type="button" :disabled="calculating" @click="handleCalculate">
            {{ calculating ? '计算中...' : '计算' }}
          </button>
          <button type="button" :disabled="calculating" @click="handleReset">重置</button>
        </div>

      </aside>

      <div
        class="gas-data-grid"
        aria-label="天然气性质数据表格"
        :style="{ gridTemplateColumns: gasGridTemplateColumns }"
      >
        <div
          v-for="column in gasTableColumns"
          :key="column"
          class="gas-grid-cell header"
        >
          {{ column }}
        </div>
        <div
          v-for="cell in gasDataCells"
          :key="cell.key"
          class="gas-grid-cell"
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

    <div
      v-else
      class="gas-analysis-workspace"
      :class="{ 'table-collapsed': analysisTableCollapsed }"
    >
      <aside class="gas-analysis-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button
          v-if="analysisTableCollapsed"
          class="gas-analysis-collapsed-tab"
          type="button"
          title="展开分析数据表"
          @click="analysisTableCollapsed = false"
        >
          图表数据
        </button>

        <template v-else>
          <div class="gas-analysis-expanded">
            <div class="gas-analysis-panel-heading">
              <span>图表数据</span>
              <div class="gas-analysis-heading-actions">
                <select
                  v-if="activeCurve === '曲线1' && curveOneSeries.length"
                  v-model="selectedCurveOneSourceRow"
                  class="gas-analysis-series-select"
                  aria-label="选择曲线1图表数据"
                >
                  <option
                    v-for="curve in curveOneSeries"
                    :key="curve.sourceRow"
                    :value="curve.sourceRow"
                  >
                    {{ curve.name }}
                  </option>
                </select>
                <select
                  v-if="activeCurve === '曲线2' && curveTwoSeries.length"
                  v-model="selectedCurveTwoSourceRow"
                  class="gas-analysis-series-select"
                  aria-label="选择曲线2图表数据"
                >
                  <option
                    v-for="curve in curveTwoSeries"
                    :key="curve.sourceRow"
                    :value="curve.sourceRow"
                  >
                    {{ curve.name }}
                  </option>
                </select>
                <select
                  v-if="activeCurve === '曲线3' && curveThreeSeries.length"
                  v-model="selectedCurveThreeSourceRow"
                  class="gas-analysis-series-select"
                  aria-label="选择曲线3图表数据"
                >
                  <option
                    v-for="curve in curveThreeSeries"
                    :key="curve.sourceRow"
                    :value="curve.sourceRow"
                  >
                    {{ curve.name }}
                  </option>
                </select>
                <select
                  v-if="activeCurve === '曲线4' && viscosityCurveSeries.length"
                  v-model="selectedViscositySourceRow"
                  class="gas-analysis-series-select"
                  aria-label="选择图表数据曲线"
                >
                  <option
                    v-for="curve in viscosityCurveSeries"
                    :key="curve.sourceRow"
                    :value="curve.sourceRow"
                  >
                    {{ curve.name }}
                  </option>
                </select>
                <button
                  class="gas-analysis-toggle"
                  type="button"
                  title="收起图表数据"
                  @click="analysisTableCollapsed = true"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="#777">
                    <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                  </svg>
                </button>
              </div>
            </div>
            <div
              class="gas-analysis-grid"
              aria-label="天然气分析数据表格"
              :style="{ gridTemplateColumns: analysisGridTemplateColumns }"
            >
              <div
                v-for="column in analysisTableColumns"
                :key="column"
                class="gas-analysis-grid-cell header"
              >
                {{ column }}
              </div>
              <div
                v-for="cell in analysisDataCells"
                :key="cell.key"
                class="gas-analysis-grid-cell"
                :class="{
                  numeric: cell.value !== '',
                  'row-index': cell.columnIndex === 0
                }"
              >
                {{ cell.value }}
              </div>
            </div>
          </div>
        </template>
      </aside>

      <section class="gas-chart-panel" aria-label="天然气结果分析图">
        <div class="gas-curve-selector">
          <label v-for="curve in gasCurveOptions" :key="curve.name">
            <input v-model="activeCurve" type="radio" :value="curve.name" />
            <span>{{ curve.name }}</span>
          </label>
        </div>

        <div class="gas-chart">
          <div class="gas-chart-plot-shell">
            <div ref="chartEl" class="gas-chart-plot"></div>
            <div
              v-if="!activeCurveHasData"
              class="gas-chart-empty"
            >
              {{ ['曲线1', '曲线2', '曲线3', '曲线4'].includes(activeCurve) ? '暂无计算结果' : '当前曲线尚未完成' }}
            </div>
          </div>
        </div>
      </section>
    </div>

    <footer class="gas-result-tabs">
      <button
        type="button"
        class="gas-result-tab"
        :class="{ active: activeResultTab === '数据列表' }"
        @click="activeResultTab = '数据列表'"
      >
        数据列表
      </button>
      <button
        type="button"
        class="gas-result-tab"
        :class="{ active: activeResultTab === '结果分析图' }"
        @click="activeResultTab = '结果分析图'"
      >
        结果分析图
      </button>
    </footer>
  </div>
</template>

<style lang="scss" scoped>
.gas-properties-view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.gas-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.gas-analysis-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.gas-analysis-workspace.table-collapsed {
  padding: 10px 12px;
}

.gas-analysis-panel {
  width: 760px;
  flex: 0 0 760px;
  height: 100%;
  min-height: 0;
  align-self: stretch;
  display: flex;
  position: relative;
  transition: width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    flex-basis: 34px;
    height: 100%;
    border: 1px solid #d4d7db;
    border-right: 0;
    box-sizing: border-box;
    background: #fff;
  }
}

.gas-analysis-expanded {
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

.gas-analysis-panel-heading {
  height: 36px;
  flex: 0 0 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  border-bottom: 1px solid #e2e6ea;
  color: #222;
  font-weight: 400;
}

.gas-analysis-heading-actions {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
}

.gas-analysis-series-select {
  width: 150px;
  height: 26px;
  padding: 0 24px 0 8px;
  border: 1px solid #d4d7db;
  border-radius: 3px;
  background: #fff;
  color: #333;
  font: inherit;
  outline: none;

  &:focus {
    border-color: #1677ff;
  }
}

.gas-analysis-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-rows: 42px;
  grid-auto-rows: max(30px, calc((100% - 42px) / 25));
  min-height: 0;
  overflow: auto;
}

.gas-analysis-toggle {
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

.gas-analysis-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  background: #fff;
  color: #222;
  cursor: pointer;
  font: inherit;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding-top: 8px;
  box-sizing: border-box;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;
  letter-spacing: 0;

  &:hover {
    background: #eef4ff;
    color: #1677ff;
  }
}

.gas-analysis-grid-cell {
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

.gas-chart-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.gas-curve-selector {
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

.gas-chart {
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 8px;
  box-sizing: border-box;
}

.gas-chart-plot-shell {
  flex: 1;
  position: relative;
  min-width: 0;
  min-height: 0;
}

.gas-chart-plot {
  position: absolute;
  inset: 0;
}

.gas-chart-empty {
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

.gas-parameter-panel {
  width: 260px;
  flex: 0 0 260px;
  padding: 12px 14px;
  box-sizing: border-box;
  background: #fff;
  border: 1px solid #d4d7db;
  border-right: 0;
  overflow-y: auto;
}

.gas-parameter-section + .gas-parameter-section {
  margin-top: 16px;
}

.gas-section-heading {
  height: 22px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.gas-section-rule {
  height: 1px;
  flex: 1;
  background: #c8cdd3;
}

.gas-field-group {
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

.gas-parameter-actions {
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

.gas-data-grid {
  flex: 1;
  min-width: 0;
  display: grid;
  grid-template-rows: 36px repeat(27, minmax(30px, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
}

.gas-grid-cell {
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
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 8px;
    box-sizing: border-box;
    background: #f4f4f4;
    color: #333;
    font-size: inherit;
    font-weight: 400;
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

.gas-result-tabs {
  height: 32px;
  flex: 0 0 32px;
  display: flex;
  align-items: flex-end;
  padding-left: 12px;
  box-sizing: border-box;
  border-top: 1px solid #e4e7ed;
  background: #fff;
}

.gas-result-tab {
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

@media (max-width: 950px) {
  .gas-parameter-panel {
    width: 240px;
    flex-basis: 240px;
  }

  .gas-analysis-panel {
    width: 640px;
    flex-basis: 640px;
  }

}
</style>
