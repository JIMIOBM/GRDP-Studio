<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { productivityCoefficientApi } from '@/api/productivityCoefficient'
import {
  calculateBinomialCoefficientCurve,
  calculateBinomialCoefficientIprFamily,
  calculateExponentialCoefficientCurve,
  calculateExponentialCoefficientOpenFlow,
  calculateExponentialCoefficientIprFamily,
  exponentialCoefficientFitReference,
  exponentialCoefficientPvtIssue,
  coefficientInputNumber,
  normalizeCoefficientPressureMethod,
  normalizeCoefficientFitPoint
} from '@/utils/productivityCoefficientCalculation'


const props = defineProps({
  recordId: { type: [Number, String], default: null },
  wellName: { type: String, default: '' },
  maximumFormationPressure: { type: [String, Number], default: '' },
  formationTemperature: { type: [String, Number], default: '' },
  productivityCoefficientC: { type: [String, Number], default: '' },
  productivityExponentN: { type: [String, Number], default: '' },
  correctedCoefficientC: { type: [String, Number], default: '' },
  correctedExponentN: { type: [String, Number], default: '' },
  fittedFormationPressure: { type: [String, Number], default: '' },
  fittedFlowRate: { type: [String, Number], default: '' },
  openFlowRate: { type: [String, Number], default: '' },

  pvtRecord: { type: Object, default: null },
  pvtTableOptions: { type: Array, default: () => [] },
  selectedPvtTable: { type: String, default: '' },
  pvtLoading: { type: Boolean, default: false },
  operationType: {
    type: String,
    default: 'production',
    validator: value => ['production', 'injection'].includes(value)
  },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  methodType: {
    type: String,
    default: '指数式',
    validator: value => ['指数式', '二项式'].includes(value)
  }
})

const emit = defineEmits([
  'saved',
  'restore',
  'select-pvt',
  'update:coefficient-c',
  'update:exponent-n',
  'update:corrected-c',
  'update:corrected-n',
  'update:fitted-pressure',
  'update:fitted-flow-rate',
  'update:open-flow-rate',
  'update:operation-type',
  'update:maximum-formation-pressure',
  'update:formation-temperature'
])

const chartEl = ref(null)
const paramsCollapsed = ref(false)
let chartResizeObserver = null
const chartType = ref('production-fit')
const calculationMethod = ref('拟压力')
const darcyCoefficient = ref('')
const nonDarcyCoefficient = ref('')
const calculatedRequest = ref(null)
const inputSnapshot = ref(null)
const saving = ref(false)
const loadingRecord = ref(false)
const storedId = ref(null)
const storedName = ref('')
const scopeKey = computed(() => JSON.stringify([Number(props.projectId), Number(props.gasReservoirId), props.wellName]))
const pvtIssue = computed(() => props.methodType === '指数式' ? exponentialCoefficientPvtIssue({
  selectedPvtTable: props.pvtRecord?.pvtId ?? props.selectedPvtTable,
  pvtResultRows: props.pvtRecord?.gasResultRows || [],
  maximumFormationPressure: props.maximumFormationPressure,
  pvtLoading: props.pvtLoading,
  // 原PVT删除后仍允许用已保存记录的快照恢复，不能把有效快照误判为空数据。
  allowSnapshot: Boolean(props.recordId && storedId.value === Number(props.recordId))
}) : '')
const calculationBlocker = computed(() => props.methodType === '指数式' &&
  (props.pvtLoading || normalizeCoefficientPressureMethod(calculationMethod.value) === 'pseudo-pressure') ? pvtIssue.value : '')
let recordLoadSequence = 0
const numberOrNull = coefficientInputNumber
const collectInput = () => ({
  projectId: Number(props.projectId), gasReservoirId: Number(props.gasReservoirId), wellName: props.wellName,
  method: props.methodType, operation: props.operationType, pressureMethod: calculationMethod.value,
  pvtId: numberOrNull(props.pvtRecord?.pvtId),
  parameters: {
    pressure: numberOrNull(props.maximumFormationPressure), temperature: numberOrNull(props.formationTemperature),
    a: props.methodType === '二项式' ? numberOrNull(binomialCoefficientA.value) : null,
    b: props.methodType === '二项式' ? numberOrNull(binomialCoefficientB.value) : null,
    correctedA: props.methodType === '二项式' ? numberOrNull(correctedBinomialA.value) : null,
    correctedB: props.methodType === '二项式' ? numberOrNull(correctedBinomialB.value) : null,
    c: props.methodType === '指数式' ? numberOrNull(props.productivityCoefficientC) : null,
    n: props.methodType === '指数式' ? numberOrNull(props.productivityExponentN) : null,
    correctedC: props.methodType === '指数式' ? numberOrNull(props.correctedCoefficientC) : null,
    correctedN: props.methodType === '指数式' ? numberOrNull(props.correctedExponentN) : null,
    pointPressure: numberOrNull(props.fittedFormationPressure), pointRate: numberOrNull(props.fittedFlowRate)
  },
  pvtSnapshot: { pvtId: props.pvtRecord?.pvtId ?? null, pvtName: props.pvtRecord?.pvtName || '', gasResultRows: props.pvtRecord?.gasResultRows || [] }
})
const canSave = computed(() => !loadingRecord.value && !saving.value && !calculationBlocker.value && !!calculatedRequest.value &&
  !!props.wellName?.trim() && Number.isSafeInteger(Number(props.projectId)) && Number(props.projectId) > 0 &&
  Number.isSafeInteger(Number(props.gasReservoirId)) && Number(props.gasReservoirId) > 0 &&
  (!props.recordId || storedId.value === Number(props.recordId)) &&
  !!inputSnapshot.value && JSON.stringify(collectInput()) === JSON.stringify(inputSnapshot.value))
const saveRecord = async () => {
  if (!canSave.value) return
  const input = inputSnapshot.value
  const request = calculatedRequest.value
  const sequence = recordLoadSequence
  saving.value = true
  try {
    const response = await productivityCoefficientApi.save({ ...input, id: storedId.value,
      name: storedName.value || null, result: request.outputRate })
    const saved = response?.data ?? response
    if (sequence !== recordLoadSequence) return
    if (!Number.isSafeInteger(Number(saved?.id)) || Number(saved.id) <= 0) throw new Error('保存接口未返回有效记录编号')
    if (calculatedRequest.value !== request || inputSnapshot.value !== input ||
        JSON.stringify(collectInput()) !== JSON.stringify(input)) {
      ElMessage.success(`${input.wellName}的结果已保存；参数已变化，请重新计算`)
      return
    }
    storedId.value = saved.id
    storedName.value = saved.name
    emit('saved', { ...saved, wellName: input.wellName })
    ElMessage.success(`${input.wellName}：保存成功`)
  } catch (error) { ElMessage.error(`${input.wellName}：${error.response?.data?.msg || error.message || '保存失败'}`) }
  finally { saving.value = false }
}

const binomialCoefficientA = ref('')
const binomialCoefficientB = ref('')
const correctedBinomialA = ref('')
const correctedBinomialB = ref('')
let chart = null

const isInjection = computed(() => props.operationType === 'injection')
const operationLabel = computed(() => isInjection.value ? '注气' : '采气')
const formatRate = value => value !== 0 && Math.abs(value) < 0.0001 ? value.toExponential(4) : value.toFixed(4)
const fitPointHint = computed(() => {
  const reference = calculatedRequest.value?.fitReference
  if (!reference) return ''
  const actual = calculatedRequest.value.fitPoint.flowRate
  const deviation = reference.original === 0 ? '0.00' : ((actual - reference.original) / reference.original * 100).toFixed(2)
  return `同一井底压力下，原系数参考气量 ${formatRate(reference.original)}，修正系数参考气量 ${formatRate(reference.corrected)}（10⁴m³/d）；输入点相对原曲线偏差 ${deviation}%。拟合点保留输入值，不自动调整系数。`
})

const calculateCurve = (coefficient, exponent) => calculateExponentialCoefficientCurve({
  reservoirPressure: isInjection.value ? Number(props.maximumFormationPressure) / 10 : props.maximumFormationPressure,
  coefficient,
  exponent,
  calculationMethod: calculationMethod.value,
  operationType: props.operationType,
  maximumFlowingPressure: isInjection.value ? props.maximumFormationPressure : null,
  pvtResultRows: props.pvtRecord?.gasResultRows || []
})

const calculateBinomialCurve = (
  darcyCoefficient,
  nonDarcyCoefficient
) =>
  calculateBinomialCoefficientCurve({
    // 注气拟合取十等份曲线中的最低压力级别（Pr/10），井底压力上限为输入 Pr。
    reservoirPressure:
      isInjection.value ? Number(props.maximumFormationPressure) / 10 : props.maximumFormationPressure,
    darcyCoefficient,
    nonDarcyCoefficient,
    calculationMethod: calculationMethod.value,
    operationType: props.operationType,
    fittedFlowingPressure: props.fittedFormationPressure,
    maximumFlowingPressure: isInjection.value ? props.maximumFormationPressure : null,

    pvtResultRows:
      props.pvtRecord?.gasResultRows || []
  })


const curveToChartData = curve =>
  curve?.points?.map(point => [
    point.flowRate,
    point.flowingPressure
  ]) || []

const formatPressure = value =>
  Number(Number(value).toFixed(3)).toString()

const isZeroFlowIprPoint = item => item.curve?.points?.length > 0 &&
  item.curve.points.every(point => point.flowRate === 0 && point.flowingPressure === item.curve.points[0].flowingPressure)
const iprSeriesName = item =>
  `Pᵣ${item.level}=${formatPressure(item.reservoirPressure)} MPa${isZeroFlowIprPoint(item) ? '（零流量）' : ''}`


const generateFitPoint = () => {
  const point =
    calculatedRequest.value?.fitPoint

  return point
    ? [[point.flowRate, point.flowingPressure]]
    : []
}


const initChart = () => {
  if (!chartEl.value) return

  if (chart) {
    chart.dispose()
  }

  chart = echarts.init(chartEl.value)
  updateChart()
}

const updateChart = () => {
  if (!chart) return

  // 图表只读取最近一次计算快照，编辑表单或切换图形不会混入未计算的参数。
  const snapshot = calculatedRequest.value
  const exponential = (snapshot?.methodType || props.methodType) === '指数式'
  const isInjection = { value: (snapshot?.operationType || props.operationType) === 'injection' }
  const operationLabel = { value: isInjection.value ? '注气' : '采气' }
  const iprMode = chartType.value === 'ipr-curve'
  const flowAxisName =
    `${operationLabel.value}量 qsc(10⁴m³/d)`
  const fittedCurveName =
    `${operationLabel.value}拟合曲线（C、n）`
  const correctedCurveName =
    `${operationLabel.value}修正拟合曲线（C′、n′）`
  const fitPointName =
    `${operationLabel.value}拟合点`
  const iprFamily =
    calculatedRequest.value?.iprFamily || []
  const familyNames =
    iprFamily.map(iprSeriesName)
  const familyColors = [
    '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
    '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#00a6b4'
  ]
  const iprPressureLimit = snapshot?.pressureLimit
  const iprYAxisMax =
    Number.isFinite(iprPressureLimit) && iprPressureLimit > 0
      ? (isInjection.value ? iprPressureLimit : Math.ceil(iprPressureLimit / 5) * 5)
      : undefined

  const exponentialSeries = iprMode
    ? iprFamily.map((item, index) => ({
      name: iprSeriesName(item),
      type: isZeroFlowIprPoint(item) ? 'scatter' : 'line',
      data: isZeroFlowIprPoint(item) ? curveToChartData(item.curve).slice(0, 1) : curveToChartData(item.curve),
      smooth: !isInjection.value,
      showSymbol: isZeroFlowIprPoint(item),
      symbol: isZeroFlowIprPoint(item) ? 'circle' : 'none',
      symbolSize: 10,
      clip: !isZeroFlowIprPoint(item),
      z: isZeroFlowIprPoint(item) ? 5 : 2,
      label: { show: isZeroFlowIprPoint(item), position: 'right', formatter: '零流量（地层压力等于上限）' },
      lineStyle: {
        color: familyColors[index % familyColors.length],
        width: 2
      },
      itemStyle: {
        color: familyColors[index % familyColors.length]
      }
    }))
    : [
      {
        name: fittedCurveName,
        type: 'line',
        data: curveToChartData(
          calculatedRequest.value?.fittedCurve
        ),
        smooth: false,
        symbol: 'none',
        lineStyle: {
          color: '#333',
          width: 2,
          type: 'solid'
        },
        itemStyle: {
          color: '#333'
        }
      },
      {
        name: correctedCurveName,
        type: 'line',
        data: curveToChartData(
          calculatedRequest.value?.iprCurve
        ),
        smooth: false,
        symbol: 'none',
        lineStyle: {
          color: '#333',
          width: 2,
          type: 'dashed'
        },
        itemStyle: {
          color: '#333'
        }
      },
      {
        name: fitPointName,
        type: 'scatter',
        data: generateFitPoint(),
        symbol: 'circle',
        symbolSize: 10,
        itemStyle: {
          color: '#5470c6'
        },
        z: 5
      }
    ]

  const series = iprMode
    ? exponentialSeries
    : exponential
      ? exponentialSeries
      : [
      // =========================
      // 二项式第一条线：
      // A、B
      // =========================
      {
        name: 'A、B拟合曲线',

        type: 'line',

        data: curveToChartData(
          calculatedRequest.value?.fittedCurve
        ),

        smooth: false,
        symbol: 'none',

        lineStyle: {
          color: '#333',
          width: 2,
          type: 'solid'
        },

        itemStyle: {
          color: '#333'
        }
      },

      // =========================
      // 二项式第二条线：
      // A'、B'
      // =========================
      {
        name: "A'、B'修正曲线",

        type: 'line',

        data: curveToChartData(
          calculatedRequest.value?.iprCurve
        ),

        smooth: false,
        symbol: 'none',

        lineStyle: {
          color: '#333',
          width: 2,
          type: 'dashed'
        },

        itemStyle: {
          color: '#333'
        }
      },

      // =========================
      // 二项式参数点：
      // (q, Pwf)
      // =========================
      {
        name: '参数点',

        type: 'scatter',

        data: generateFitPoint(),

        symbol: 'circle',
        symbolSize: 10,

        z: 10,

        itemStyle: {
          color: '#5470c6'
        }
      }
    ]

  const option = {
    animation: false,
    color: familyColors,
    title: {
      show: true,
      text: iprMode ? `${operationLabel.value}IPR曲线` : `${operationLabel.value}量拟合`,
      left: 'center',
      top: 8,
      textStyle: {
        fontSize: 16,
        fontWeight: 600,
        color: '#333'
      }
    },
    grid: {
      left: 82,
      right: !iprMode && exponential ? 250 : 190,
      top: 64,
      bottom: 64,
      containLabel: true
    },
    legend: {
          show: Boolean(calculatedRequest.value),
          type: 'scroll',
          orient: 'vertical',
          top: 52,
          right: 20,
          backgroundColor: 'rgba(255,255,255,.9)',
          borderColor: '#e5e9f0',
          borderWidth: 1,
          padding: 8,
          data: iprMode ? familyNames : exponential
            ? [fittedCurveName, correctedCurveName, fitPointName]
            : ['A、B拟合曲线', "A'、B'修正曲线", '参数点']
        },

    xAxis: {
      type: 'value',
      min: 0,

      name: flowAxisName,

      nameLocation: 'middle',
      nameGap: 40,

      nameTextStyle: {
        fontSize: 12,
        color: '#555'
      },

      axisLine: {
        lineStyle: {
          color: '#d0d0d0'
        }
      },

      axisTick: {
        show: true
      },

      axisLabel: {
        show: true
      },

      splitLine: {
        show: true,
        lineStyle: {
          color: '#dbe4f1'
        }
      },
      minorTick: {
        show: true,
        splitNumber: 5
      },
      minorSplitLine: {
        show: true,
        lineStyle: {
          color: '#edf2f8'
        }
      }
    },

    yAxis: {
      type: 'value',
      scale: isInjection.value,
      min: 0,
      max: iprYAxisMax,

      name: 'pwf(MPa)',

      nameLocation: 'middle',
      nameGap: 52,

      nameTextStyle: {
        fontSize: 12,
        color: '#555'
      },

      axisLine: {
        lineStyle: {
          color: '#d0d0d0'
        }
      },

      axisTick: {
        show: true
      },

      axisLabel: {
        show: true
      },

      splitLine: {
        show: true,
        lineStyle: {
          color: '#dbe4f1'
        }
      },
      minorTick: {
        show: true,
        splitNumber: 5
      },
      minorSplitLine: {
        show: true,
        lineStyle: {
          color: '#edf2f8'
        }
      }
    },

    tooltip: {
      show: true,
      trigger: 'axis',
      formatter: (params) => {
        const items = Array.isArray(params) ? params : [params]
        return items
          .filter(p => Number.isFinite(p.data[0]) && Number.isFinite(p.data[1]))
          .map(p => `<b>${p.seriesName}</b><br/>${operationLabel.value}量 q: ${formatRate(p.data[0])} 10⁴m³/d<br/>井底压力 Pwf: ${p.data[1].toFixed(4)} MPa`)
          .join('<br/><br/>')
      }
    },

    graphic:
      !calculatedRequest.value
        ? [
          {
            type: 'text',
            left: 'center',
            top: 'middle',
            silent: true,

            style: {
              text: '请填写参数后点击计算',
              fill: '#999',
              fontSize: 14
            }
          }
        ]
        : [],

    series
  }

  chart.setOption(option, true)
}


const validateBinomialFitPoint = () => {
  const pressureText =
    String(
      props.fittedFormationPressure ?? ''
    ).trim()

  const flowRateText =
    String(
      props.fittedFlowRate ?? ''
    ).trim()

  if (
    !pressureText ||
    !flowRateText
  ) {
    throw new Error(
      '二项式参数点需要同时填写井底压力 Pwf 和注/采气量 q'
    )
  }

  const pressure =
    Number(pressureText)

  const flowRate =
    Number(flowRateText)

  const reservoirPressure =
    Number(
      props.maximumFormationPressure
    )

  if (
    !Number.isFinite(
      reservoirPressure
    ) ||
    reservoirPressure <= 0
  ) {
    throw new Error(
      '地层压力 Pr 必须是大于 0 的有效数值'
    )
  }

  if (
    !Number.isFinite(pressure) ||
    pressure < 0
  ) {
    throw new Error(
      '二项式参数点井底压力 Pwf 必须是非负有效数值'
    )
  }

  if (
    !Number.isFinite(flowRate) ||
    flowRate < 0
  ) {
    throw new Error(
      '二项式参数点注/采气量 q 必须是非负有效数值'
    )
  }

  // 注气拟合采用最低压力级别，零气量对应 Pr/10。
  if (isInjection.value) {
    const lowerPressure = reservoirPressure / 10
    if (pressure < lowerPressure || pressure > reservoirPressure) {
      throw new Error(`参数点井底压力 Pwf 应在 ${lowerPressure}～${reservoirPressure} MPa 之间`)
    }
    if (flowRate === 0 && Math.abs(pressure - lowerPressure) > 1e-9) {
      throw new Error(`当注气量 q = 0 时，参数点井底压力 Pwf 必须为 Pr/10（${lowerPressure} MPa）`)
    }
    if (flowRate > 0 && pressure <= lowerPressure) {
      throw new Error(`当注气量 q > 0 时，参数点井底压力 Pwf 必须大于 Pr/10（${lowerPressure} MPa）`)
    }
    return
  }

  // 采气保持绝对井底压力：零气量对应 Pwf = Pr。
  if (flowRate === 0) {
    if (
      Math.abs(
        pressure -
        reservoirPressure
      ) > 1e-9
    ) {
      throw new Error(
        `当 q = 0 时，参数点 Pwf 必须等于地层压力 ` +
        `Pr（${reservoirPressure} MPa）`
      )
    }

    return
  }

  /*
   * 采气：
   *
   * F(Pr)-F(Pwf)>0
   *
   * 所以 Pwf < Pr
   */
  if (
    props.operationType ===
    'production' &&
    pressure >=
    reservoirPressure
  ) {
    throw new Error(
      `采气时参数点 Pwf 必须小于地层压力 ` +
      `Pr（${reservoirPressure} MPa）`
    )
  }

}



const handleCalculate = ({ restoring = false } = {}) => {
  if (saving.value || (loadingRecord.value && !restoring)) return
  try {
    if (calculationBlocker.value) throw new Error(calculationBlocker.value)
    let fittedCurve
    let iprCurve
    let iprFamily = []
    let fitPoint = null
    let fitReference = null

    /*
     * =====================
     * 指数式
     * =====================
     */
    if (props.methodType === '指数式') {
      const temperature = numberOrNull(props.formationTemperature)
      if (temperature == null || !Number.isFinite(temperature) || temperature <= -273.15) throw new Error('请填写有效地层温度（大于 -273.15℃）')
      fittedCurve =
        calculateCurve(
          props.productivityCoefficientC,
          props.productivityExponentN
        )

      try {
        iprCurve =
          calculateCurve(
            props.correctedCoefficientC,
            props.correctedExponentN
          )
      } catch (error) {
        const message =
          String(error?.message || '')
            .replace(
              '注气能力系数 C',
              '修正注气能力系数 C′'
            )
            .replace(
              '产能系数 C',
              '修正产能系数 C′'
            )
            .replace(
              '注气指数 n',
              '修正注气指数 n′'
            )
            .replace(
              '产能指数 n',
              '修正产能指数 n′'
            )

        throw new Error(
          message ||
          '修正参数无法生成IPR曲线'
        )
      }

      iprFamily =
        calculateExponentialCoefficientIprFamily({
          reservoirPressure:
            props.maximumFormationPressure,
          coefficient:
            props.correctedCoefficientC,
          exponent:
            props.correctedExponentN,
          calculationMethod:
            calculationMethod.value,
          operationType:
            props.operationType,
          maximumFlowingPressure:
            isInjection.value ? props.maximumFormationPressure : null,
          pvtResultRows:
            props.pvtRecord?.gasResultRows || []
        })

      fitPoint =
        normalizeCoefficientFitPoint({
          operationType:
            props.operationType,
          flowRate:
            props.fittedFlowRate,
          flowingPressure:
            props.fittedFormationPressure,
          curve:
            fittedCurve,
          enforceFlowRange: true
        })
    }

    /*
     * =====================
     * 二项式
     * =====================
     */
    else {
      if ([binomialCoefficientA, binomialCoefficientB, correctedBinomialA, correctedBinomialB]
        .some(coefficient => String(coefficient.value ?? '').trim() === '')) {
        throw new Error('请填写产能系数 A、B 及修正产能系数 A′、B′')
      }
      validateBinomialFitPoint()

      fittedCurve =
        calculateBinomialCurve(
          binomialCoefficientA.value,
          binomialCoefficientB.value
        )

      try {
        iprCurve =
          calculateBinomialCurve(
            correctedBinomialA.value,
            correctedBinomialB.value
          )
      } catch (error) {
        const message =
          String(
            error?.message || ''
          )
            .replace(
              '二项式系数 A',
              '修正二项式系数 A′'
            )
            .replace(
              '二项式系数 B',
              '修正二项式系数 B′'
            )

        throw new Error(
          message ||
          '修正二项式参数无法生成IPR曲线'
        )
      }


      iprFamily =
        calculateBinomialCoefficientIprFamily({
          reservoirPressure:
            props.maximumFormationPressure,
          darcyCoefficient:
            correctedBinomialA.value,
          nonDarcyCoefficient:
            correctedBinomialB.value,
          calculationMethod:
            calculationMethod.value,
          operationType:
            props.operationType,
          fittedFlowingPressure:
            props.fittedFormationPressure,
          maximumFlowingPressure:
            isInjection.value ? props.maximumFormationPressure : null,
          pvtResultRows:
            props.pvtRecord?.gasResultRows || []
        })
    }


    if (props.methodType === '二项式') {
      fitPoint = {
        flowRate: Number(props.fittedFlowRate),
        flowingPressure: Number(props.fittedFormationPressure)
      }
    }

    // 指数式无阻流量使用修正系数，不以注气曲线终点流量替代。
    const outputRate = props.methodType === '指数式'
      ? calculateExponentialCoefficientOpenFlow({
        reservoirPressure: props.maximumFormationPressure,
        coefficient: props.correctedCoefficientC,
        exponent: props.correctedExponentN,
        calculationMethod: calculationMethod.value,
        pvtResultRows: props.pvtRecord?.gasResultRows || []
      })
      : iprCurve.limitRate

    if (props.methodType === '指数式') {
      fitReference = {
        original: exponentialCoefficientFitReference({ curve: fittedCurve,
          coefficient: props.productivityCoefficientC, exponent: props.productivityExponentN,
          flowingPressure: fitPoint.flowingPressure, pvtResultRows: props.pvtRecord?.gasResultRows || [] }),
        corrected: exponentialCoefficientFitReference({ curve: iprCurve,
          coefficient: props.correctedCoefficientC, exponent: props.correctedExponentN,
          flowingPressure: fitPoint.flowingPressure, pvtResultRows: props.pvtRecord?.gasResultRows || [] })
      }
    }
    inputSnapshot.value = JSON.parse(JSON.stringify(collectInput()))
    calculatedRequest.value = {
      methodType: props.methodType,
      operationType: props.operationType,
      pressureLimit: Number(props.maximumFormationPressure),
      fittedCurve,
      iprCurve,
      iprFamily,
      fitPoint,
      fitReference,
      outputRate
    }

    emit(
      'update:open-flow-rate',
      outputRate !== 0 && outputRate < 0.0001 ? outputRate.toExponential(4) : Number(
        outputRate.toFixed(4)
      ).toString()
    )

    updateChart()
  } catch (error) {
    calculatedRequest.value = null
    inputSnapshot.value = null

    emit(
      'update:open-flow-rate',
      ''
    )

    updateChart()

    ElMessage.error(
      error.message ||
      '当前参数无法生成IPR曲线'
    )
  }
}


const handleCoefficientCChange = e => {
  emit('update:coefficient-c', e.target.value)
}

const handleExponentNChange = e => {
  emit('update:exponent-n', e.target.value)
}

const handleCorrectedCChange = e => {
  emit('update:corrected-c', e.target.value)
}

const handleCorrectedNChange = e => {
  emit('update:corrected-n', e.target.value)
}

const handleFittedPressureChange = e => {
  emit(
    'update:fitted-pressure',
    e.target.value
  )
}

const handleFittedFlowRateChange = e => {
  emit(
    'update:fitted-flow-rate',
    e.target.value
  )
}


watch(() => JSON.stringify(collectInput()), () => {
  if (loadingRecord.value) return
  calculatedRequest.value = null
  inputSnapshot.value = null

  emit(
    'update:open-flow-rate',
    ''
  )

  nextTick(updateChart)
}, { flush: 'sync' })


watch(chartType, () => nextTick(updateChart))

watch([() => props.recordId, scopeKey, () => props.methodType], async () => {
  const sequence = ++recordLoadSequence
  if (Number(props.recordId) === storedId.value && storedId.value && inputSnapshot.value?.wellName === props.wellName && inputSnapshot.value?.method === props.methodType &&
      inputSnapshot.value?.projectId === Number(props.projectId) && inputSnapshot.value?.gasReservoirId === Number(props.gasReservoirId)) return
  storedId.value = null
  storedName.value = ''
  inputSnapshot.value = null
  calculatedRequest.value = null
  emit('update:open-flow-rate', '')
  if (!props.recordId) { loadingRecord.value = false; nextTick(updateChart); return }
  loadingRecord.value = true
  try {
    const response = await productivityCoefficientApi.detail(props.recordId, {
      projectId: props.projectId, gasReservoirId: props.gasReservoirId, wellName: props.wellName
    })
    if (sequence !== recordLoadSequence) return
    const record = response?.data ?? response
    if (record.method !== props.methodType || record.version !== 'coefficient-v1') throw new Error('记录方法或计算版本不匹配，无法恢复')
    storedId.value = Number(record.id)
    storedName.value = record.name
    const p = record.parameters
    binomialCoefficientA.value = p.a ?? ''
    binomialCoefficientB.value = p.b ?? ''
    correctedBinomialA.value = p.correctedA ?? ''
    correctedBinomialB.value = p.correctedB ?? ''
    calculationMethod.value = record.pressureMethod
    emit('restore', record)
    await nextTick()
    if (sequence === recordLoadSequence) handleCalculate({ restoring: true })
  } catch (error) {
    if (sequence === recordLoadSequence) ElMessage.error(error.response?.data?.msg || error.message || '产能系数记录读取失败')
  } finally { if (sequence === recordLoadSequence) loadingRecord.value = false }
}, { immediate: true, flush: 'sync' })

onMounted(() => {
  nextTick(() => initChart())
  chartResizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartEl.value) chartResizeObserver.observe(chartEl.value)
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  ++recordLoadSequence
  chartResizeObserver?.disconnect()
  if (chart) {
    chart.dispose()
    chart = null
  }
  window.removeEventListener('resize', handleResize)
})

const handleResize = () => {
  chart?.resize()
}
</script>

<template>
  <div class="exponential-workspace">
    <aside v-resizable-parameter-panel="paramsCollapsed" class="parameter-panel water-parameter-theme" :class="{ collapsed: paramsCollapsed }">
      <button v-if="paramsCollapsed" class="parameter-collapsed-tab" type="button" title="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <div v-show="!paramsCollapsed" class="panel-header">
        <h3 class="panel-title">参数设置</h3>
        <button class="parameter-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
        </button>
      </div>
      
      <div v-show="!paramsCollapsed" class="parameter-form">
        <div v-if="methodType === '指数式'" class="field-group" role="status">
          <span>当前计算/保存井：{{ wellName || '未选择' }}</span>
          <small>项目 {{ projectId }} / 气藏 {{ gasReservoirId }}</small>
        </div>
        <label class="field-group">
          <span>选择PVT表</span>
          <select :value="selectedPvtTable" :disabled="pvtLoading || !pvtTableOptions.length"
            @change="emit('select-pvt', $event.target.value)">
            <option value="" disabled>{{ pvtLoading ? '正在加载PVT…' : pvtTableOptions.length ? '请选择PVT性质' : '当前井暂无已保存PVT性质'
            }}</option>
            <option v-for="option in pvtTableOptions" :key="option.value" :value="option.value">{{ option.label }}
            </option>
          </select>
        </label>

        <div v-if="pvtIssue" class="field-group" role="status">
          <small>{{ pvtIssue }}。{{ pvtLoading ? '' : '拟压力法暂不可用；压力法和压力平方法可使用手工参数。' }}</small>
        </div>
        <div class="section-heading">
          <span>其他数据</span>
          <i></i>
        </div>

        <label class="field-group">
          <span>地层压力 Pr(MPa)</span>

          <input :value="maximumFormationPressure" placeholder="请输入地层压力" inputmode="decimal"
            @input="emit('update:maximum-formation-pressure', $event.target.value)" />
        </label>

        <label class="field-group">
          <span>地层温度(℃)</span>
          <input :value="formationTemperature" placeholder="请输入地层温度" inputmode="decimal"
            @input="emit('update:formation-temperature', $event.target.value)" />
        </label>

        <!-- ========== 指数式========== -->
        <template v-if="methodType === '指数式'">
          <label class="field-group">
            <span>{{ isInjection ? '注气能力系数C' : '产能系数C' }}</span>
            <input :value="productivityCoefficientC" placeholder="请输入实际系数" @input="handleCoefficientCChange"
              inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? '注气指数n' : '产能指数n' }}</span>
            <input :value="productivityExponentN" placeholder="请输入实际指数" @input="handleExponentNChange"
              inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? "修正注气能力系数C'" : "修正产能系数C'" }}</span>
            <input :value="correctedCoefficientC" placeholder="请输入实际修正系数" @input="handleCorrectedCChange"
              inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>{{ isInjection ? "修正注气指数n'" : "修正产能指数n'" }}</span>
            <input :value="correctedExponentN" placeholder="请输入实际修正指数" @input="handleCorrectedNChange"
              inputmode="decimal" />
          </label>

        </template>

        <!-- ========== 二项式 ========== -->
        <template v-else>
          <label class="field-group">
            <span>
              {{ isInjection
                ? '注气系数A'
                : '产能系数A'
              }}
            </span>

            <input v-model="binomialCoefficientA" placeholder="请输入系数 A" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>
              {{ isInjection
                ? '注气系数B'
                : '产能系数B'
              }}
            </span>

            <input v-model="binomialCoefficientB" placeholder="请输入系数 B" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>
              {{ isInjection
                ? "修正注气系数A'"
                : "修正产能系数A'"
              }}
            </span>

            <input v-model="correctedBinomialA" placeholder="请输入修正系数 A′" inputmode="decimal" />
          </label>

          <label class="field-group">
            <span>
              {{ isInjection
                ? "修正注气系数B'"
                : "修正产能系数B'"
              }}
            </span>

            <input v-model="correctedBinomialB" placeholder="请输入修正系数 B′" inputmode="decimal" />
          </label>

        </template>

          <!-- 两种方法共用参数点布局，气量与压力均必填。 -->
          <label class="field-group">
            <span>
              {{ isInjection
                ? '参数点井底注入压力 Pwf(MPa)'
                : '参数点井底流压 Pwf(MPa)'
              }}
            </span>

            <input :value="fittedFormationPressure" required :placeholder="isInjection
                ? `请输入 Pr/10～Pr 的井底压力`
                : `请输入小于 Pr=${maximumFormationPressure} MPa 的 Pwf`
              " inputmode="decimal" @input="
    handleFittedPressureChange
  " />

          </label>

          <!-- 参数点横坐标 -->
          <label class="field-group">
            <span>
              {{ isInjection
                ? '参数点注气量 q(10⁴m³/d)'
                : '参数点采气量 q(10⁴m³/d)'
              }}
            </span>

            <input :value="fittedFlowRate" required placeholder="请输入参数点 q" inputmode="decimal"
              @input="handleFittedFlowRateChange" />
          </label>



        <!-- 注采类型：指数式和二项式都显示 -->
        <fieldset class="radio-group">
          <legend>注采类型</legend>

          <label>
            <input :checked="operationType === 'production'
              " type="radio" value="production" @change="
                emit(
                  'update:operation-type',
                  'production'
                )
                " />
            采气
          </label>

          <label>
            <input :checked="operationType === 'injection'
              " type="radio" value="injection" @change="
                emit(
                  'update:operation-type',
                  'injection'
                )
                " />
            注气
          </label>
        </fieldset>

        <fieldset class="radio-group">
          <legend>计算方法</legend>
          <label>
            <input v-model="calculationMethod" type="radio" value="拟压力" />拟压力
          </label>
          <label>
            <input v-model="calculationMethod" type="radio" value="压力平方法" />压力平方法
          </label>
          <label>
            <input v-model="calculationMethod" type="radio" value="压力法" />压力法
          </label>
        </fieldset>

        <div class="form-actions">
          <button type="button" class="calculate-button" :disabled="loadingRecord || saving || Boolean(calculationBlocker)" @click="handleCalculate">
            计算
          </button>
          <button type="button" class="calculate-button save-button" :disabled="!canSave" @click="saveRecord">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>

        <div v-if="methodType === '指数式'" class="field-group" role="status">
          <small>切换注采类型或计算方法后，请核对对应系数和拟合点；不自动换算输入值。</small>
          <small v-if="fitPointHint">{{ fitPointHint }}</small>
        </div>
        <label class="field-group">
          <span>
            {{ methodType === '二项式' && isInjection
              ? '最大注气量'
              : '无阻流量'
            }}(10⁴m³/d)
          </span>

          <input :value="calculatedRequest
            ? openFlowRate
            : ''
            " readonly inputmode="decimal" />
        </label>

      </div>
    </aside>

    <main class="chart-panel">
      <div class="chart-toolbar">
        <div class="toolbar-left">
          <label class="chart-type-toggle">
            <input v-model="chartType" type="radio" name="coefficient-chart-type" value="production-fit" />
            <span>{{ `${operationLabel}量拟合` }}</span>
          </label>
          <label class="chart-type-toggle">
            <input v-model="chartType" type="radio" name="coefficient-chart-type" value="ipr-curve" />
            <span>{{ `${operationLabel}IPR曲线` }}</span>
          </label>
        </div>
      </div>

      <div ref="chartEl" class="chart-container"></div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.exponential-workspace { flex: 1; display: flex; min-width: 0; min-height: 0; background: #fff; color: #333; font-size: 13px; }
.parameter-panel { width: 280px; min-width: 280px; flex: 0 0 280px; min-height: 0; display: flex; flex-direction: column; border-right: 1px solid #d7d7d7; background: #fff; overflow: hidden; }
.parameter-panel.collapsed { width: 34px; min-width: 34px; flex-basis: 34px; }
.panel-header { height: 34px; flex: 0 0 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; box-sizing: border-box; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; }
.panel-title { margin: 0; font: inherit; font-size: 13px; font-weight: 400; }
.parameter-toggle { width: 20px; height: 20px; padding: 0; border: 0; background: transparent; display: flex; align-items: center; justify-content: center; cursor: pointer; }
.parameter-toggle:hover, .parameter-collapsed-tab:hover { background: #fff8d9; }
.parameter-collapsed-tab { width: 100%; height: 76px; padding: 8px 0 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #222; font: inherit; writing-mode: vertical-rl; text-orientation: upright; display: flex; align-items: center; cursor: pointer; }
.parameter-form { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 12px 14px; }
.field-group { display: block; margin-bottom: 9px; color: #333;
  > span { display: block; margin-bottom: 3px; font-size: 12px; line-height: 18px; }
  input, select { width: 100%; height: 24px; padding: 0 8px; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; box-sizing: border-box; font: inherit; font-size: 13px; outline: none;
    &:focus { border-color: #b99500; box-shadow: 0 0 0 2px rgba(242,200,17,.16); }
    &[readonly] { background: #f5f5f5; }
    &:disabled { color: #999; background: #f5f5f5; cursor: not-allowed; }
  }
}
.section-heading { height: 22px; margin: 10px 0 7px; display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 500;
  i { flex: 1; height: 1px; background: #999; }
}
.radio-group { margin: 0 0 10px; padding: 0; border: 0;
  legend { margin-bottom: 7px; padding: 0; font-size: 13px; font-weight: 500; }
  label { display: inline-flex; align-items: center; gap: 4px; margin-right: 10px; font-size: 13px; white-space: nowrap; cursor: pointer; }
}
.radio-group input, .chart-type-toggle input { width: 14px; height: 14px; margin: 0; accent-color: #303133; }
.calculate-button { min-width: 86px; height: 32px; margin-bottom: 10px; padding: 0 22px; border: 0; border-radius: 5px; background: #252525; color: #fff; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer;
  &:hover { background: #050505; }
}
.chart-panel { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fff; }
.chart-toolbar { min-height: 40px; flex: 0 0 auto; display: flex; align-items: center; padding: 0 12px; background: #fff; box-sizing: border-box; }
.toolbar-left { display: flex; flex-wrap: wrap; gap: 12px; }
.chart-type-toggle { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: #333; cursor: pointer; }
.chart-container { flex: 1; min-height: 0; }
</style>
