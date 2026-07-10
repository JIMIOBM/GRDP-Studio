<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  node: Object,
  projectId: [Number, String],
  gasReservoirId: [Number, String]
})

const emit = defineEmits(['refresh-tree'])

const loading = ref(false)
const chartEl = ref(null)
const activeParamTab = ref('input')
const paramsCollapsed = ref(false)
const paramsPanelWidth = ref(320)

let chart = null

const currentWellName = computed(() =>
  props.node?.wellName ||
  props.node?.label ||
  ''
)

const wellDataMap = {
  'X-1': {
    input: {
      gasType: '干气',
      gasSpecificGravity: 0.58,
      h2sMolePercent: 4.62,
      co2MolePercent: 3.96,
      n2MolePercent: 0,
      nonHydrocarbonCorrection: 'Wichert-Aziz 修正方法',
      deviationCoefficientMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
      originalReservoirPressure: 50,
      reservoirTemperature: 80,
      materialBalanceType: '封闭气藏',
      transientFlowTime: 180,
      thinningPoints: 300,
      wgrEnabled: true,
      waterGasRatioLimit: 0.0602,
      gasReservoirVolume: 500,
      irreducibleWaterSaturation: 26.16,
      rockCompressibility: 0.0001,
      waterCompressibility: 3.7445E-4,
      productionData: [
        { date: '2004-04-21', bottomHolePressure: 27.5262, gasProduction: 12.9180, cumulativeGas: 0.0013, cumulativeWater: 0.0013 },
        { date: '2004-04-22', bottomHolePressure: 31.1019, gasProduction: 41.3820, cumulativeGas: 0.0054, cumulativeWater: 0.0013 },
        { date: '2004-04-22', bottomHolePressure: 31.1019, gasProduction: 41.3820, cumulativeGas: 0.0054, cumulativeWater: 0.0013 },
        { date: '2004-04-23', bottomHolePressure: 32.6322, gasProduction: 38.4780, cumulativeGas: 0.0093, cumulativeWater: 0.0013 },
        { date: '2004-04-24', bottomHolePressure: 27.2643, gasProduction: 42.3960, cumulativeGas: 0.0136, cumulativeWater: 0.0014 },
        { date: '2004-04-25', bottomHolePressure: 28.1918, gasProduction: 43.3560, cumulativeGas: 0.0179, cumulativeWater: 0.0014 },
        { date: '2004-04-26', bottomHolePressure: 29.1193, gasProduction: 44.3160, cumulativeGas: 0.0223, cumulativeWater: 0.0015 },
        { date: '2004-04-27', bottomHolePressure: 30.0468, gasProduction: 45.2760, cumulativeGas: 0.0268, cumulativeWater: 0.0015 },
        { date: '2004-04-28', bottomHolePressure: 30.9743, gasProduction: 46.2360, cumulativeGas: 0.0315, cumulativeWater: 0.0016 },
        { date: '2004-04-29', bottomHolePressure: 31.9018, gasProduction: 47.1960, cumulativeGas: 0.0362, cumulativeWater: 0.0016 },
        { date: '2004-04-30', bottomHolePressure: 32.8293, gasProduction: 48.1560, cumulativeGas: 0.0410, cumulativeWater: 0.0017 },
        { date: '2004-05-01', bottomHolePressure: 33.7568, gasProduction: 49.1160, cumulativeGas: 0.0459, cumulativeWater: 0.0017 },
        { date: '2004-05-02', bottomHolePressure: 34.6843, gasProduction: 50.0760, cumulativeGas: 0.0509, cumulativeWater: 0.0018 },
        { date: '2004-05-03', bottomHolePressure: 35.6118, gasProduction: 51.0360, cumulativeGas: 0.0560, cumulativeWater: 0.0018 },
        { date: '2004-05-04', bottomHolePressure: 36.5393, gasProduction: 51.9960, cumulativeGas: 0.0612, cumulativeWater: 0.0019 },
        { date: '2004-05-05', bottomHolePressure: 37.4668, gasProduction: 52.9560, cumulativeGas: 0.0665, cumulativeWater: 0.0019 },
        { date: '2004-05-06', bottomHolePressure: 38.3943, gasProduction: 53.9160, cumulativeGas: 0.0719, cumulativeWater: 0.0020 },
        { date: '2004-05-07', bottomHolePressure: 39.3218, gasProduction: 54.8760, cumulativeGas: 0.0774, cumulativeWater: 0.0020 },
        { date: '2004-05-08', bottomHolePressure: 40.2493, gasProduction: 55.8360, cumulativeGas: 0.0830, cumulativeWater: 0.0021 },
        { date: '2004-05-09', bottomHolePressure: 41.1768, gasProduction: 56.7960, cumulativeGas: 0.0887, cumulativeWater: 0.0021 }
      ]
    },
    data: [
      [600, 0.32], [630, 0.34], [660, 0.36], [690, 0.38], [720, 0.40],
      [750, 0.42], [780, 0.43], [810, 0.44], [840, 0.46], [870, 0.45],
      [900, 0.48], [930, 0.44], [960, 0.47], [990, 0.42], [1020, 0.49],
      [1050, 0.45], [1080, 0.47], [1110, 0.43], [1140, 0.48], [1170, 0.46],
      [1200, 0.44], [1450, 0.85]
    ],
    result: {
      dynamicReserves: 29.2298,
      intercept: '1.5670E-1',
      slope: '3.1330E-4',
      rsquared: 0.6323,
      reliability: '分析结果可靠性偏低'
    }
  },
  'X-4': {
    input: {
      gasType: '干气',
      gasSpecificGravity: 0.58,
      h2sMolePercent: 4.62,
      co2MolePercent: 3.96,
      n2MolePercent: 0,
      nonHydrocarbonCorrection: 'Wichert-Aziz 修正方法',
      deviationCoefficientMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
      originalReservoirPressure: 50,
      reservoirTemperature: 80,
      materialBalanceType: '封闭气藏',
      transientFlowTime: 180,
      thinningPoints: 300,
      wgrEnabled: true,
      waterGasRatioLimit: 0.0602,
      gasReservoirVolume: 500,
      irreducibleWaterSaturation: 16.99,
      rockCompressibility: 0.0001,
      waterCompressibility: 3.7445E-4,
      productionData: [
        { date: '2004-08-04', bottomHolePressure: 29.2260, gasProduction: 5.3160, cumulativeGas: 0.0005, cumulativeWater: 0.0001 },
        { date: '2004-08-05', bottomHolePressure: 31.6884, gasProduction: 30.4500, cumulativeGas: 0.0036, cumulativeWater: 0.0005 },
        { date: '2004-08-06', bottomHolePressure: 30.3399, gasProduction: 3.8580, cumulativeGas: 0.0040, cumulativeWater: 0.0005 },
        { date: '2004-08-07', bottomHolePressure: 30.3201, gasProduction: 6.3240, cumulativeGas: 0.0046, cumulativeWater: 0.0005 },
        { date: '2004-08-08', bottomHolePressure: 29.5569, gasProduction: 12.6960, cumulativeGas: 0.0059, cumulativeWater: 0.0007 },
        { date: '2004-08-09', bottomHolePressure: 31.6327, gasProduction: 30.2040, cumulativeGas: 0.0089, cumulativeWater: 0.0013 },
        { date: '2004-08-10', bottomHolePressure: 30.5838, gasProduction: 22.0440, cumulativeGas: 0.0111, cumulativeWater: 0.0017 },
        { date: '2004-08-11', bottomHolePressure: 31.1342, gasProduction: 26.4540, cumulativeGas: 0.0137, cumulativeWater: 0.0020 },
        { date: '2004-08-12', bottomHolePressure: 31.3058, gasProduction: 27.6660, cumulativeGas: 0.0165, cumulativeWater: 0.0024 },
        { date: '2004-08-13', bottomHolePressure: 31.1595, gasProduction: 26.6640, cumulativeGas: 0.0192, cumulativeWater: 0.0026 },
        { date: '2004-08-14', bottomHolePressure: 30.9080, gasProduction: 13.3560, cumulativeGas: 0.0205, cumulativeWater: 0.0028 },
        { date: '2004-08-15', bottomHolePressure: 30.1377, gasProduction: 32.7300, cumulativeGas: 0.0238, cumulativeWater: 0.0033 },
        { date: '2004-08-16', bottomHolePressure: 30.6620, gasProduction: 26.5860, cumulativeGas: 0.0264, cumulativeWater: 0.0037 },
        { date: '2004-08-22', bottomHolePressure: 30.2666, gasProduction: 5.1960, cumulativeGas: 0.0270, cumulativeWater: 0.0037 },
        { date: '2004-09-22', bottomHolePressure: 30.7664, gasProduction: 21.2400, cumulativeGas: 0.0291, cumulativeWater: 0.0042 },
        { date: '2004-09-23', bottomHolePressure: 31.4642, gasProduction: 31.2480, cumulativeGas: 0.0322, cumulativeWater: 0.0046 },
        { date: '2004-09-24', bottomHolePressure: 31.9119, gasProduction: 33.9900, cumulativeGas: 0.0356, cumulativeWater: 0.0049 },
        { date: '2004-09-25', bottomHolePressure: 32.5357, gasProduction: 37.4520, cumulativeGas: 0.0393, cumulativeWater: 0.0051 },
        { date: '2004-09-26', bottomHolePressure: 32.0613, gasProduction: 34.8720, cumulativeGas: 0.0428, cumulativeWater: 0.0054 },
        { date: '2004-09-27', bottomHolePressure: 32.0486, gasProduction: 34.8720, cumulativeGas: 0.0463, cumulativeWater: 0.0057 },
        { date: '2004-09-28', bottomHolePressure: 32.0962, gasProduction: 35.1540, cumulativeGas: 0.0498, cumulativeWater: 0.0059 },
        { date: '2004-09-29', bottomHolePressure: 31.7928, gasProduction: 33.3960, cumulativeGas: 0.0532, cumulativeWater: 0.0061 },
        { date: '2004-09-30', bottomHolePressure: 31.9064, gasProduction: 34.0140, cumulativeGas: 0.0566, cumulativeWater: 0.0064 },
        { date: '2004-10-01', bottomHolePressure: 32.3927, gasProduction: 35.2860, cumulativeGas: 0.0601, cumulativeWater: 0.0069 },
        { date: '2004-10-02', bottomHolePressure: 30.5862, gasProduction: 15.0480, cumulativeGas: 0.0616, cumulativeWater: 0.0071 },
        { date: '2004-10-17', bottomHolePressure: 30.5592, gasProduction: 14.5980, cumulativeGas: 0.0631, cumulativeWater: 0.0072 },
        { date: '2004-10-22', bottomHolePressure: 29.6939, gasProduction: 3.1740, cumulativeGas: 0.0634, cumulativeWater: 0.0072 },
        { date: '2004-10-30', bottomHolePressure: 20.4987, gasProduction: 14.6100, cumulativeGas: 0.0648, cumulativeWater: 0.0074 },
        { date: '2004-11-08', bottomHolePressure: 26.8078, gasProduction: 21.6300, cumulativeGas: 0.0670, cumulativeWater: 0.0075 },
        { date: '2004-11-09', bottomHolePressure: 29.8549, gasProduction: 7.6680, cumulativeGas: 0.0678, cumulativeWater: 0.0076 },
        { date: '2004-11-22', bottomHolePressure: 29.8480, gasProduction: 7.3020, cumulativeGas: 0.0685, cumulativeWater: 0.0076 },
        { date: '2004-11-24', bottomHolePressure: 27.2174, gasProduction: 3.7020, cumulativeGas: 0.0689, cumulativeWater: 0.0077 },
        { date: '2004-11-25', bottomHolePressure: 32.0863, gasProduction: 37.1040, cumulativeGas: 0.0726, cumulativeWater: 0.0079 },
        { date: '2004-11-26', bottomHolePressure: 30.9112, gasProduction: 29.9640, cumulativeGas: 0.0756, cumulativeWater: 0.0082 },
        { date: '2004-11-27', bottomHolePressure: 31.1551, gasProduction: 31.4760, cumulativeGas: 0.0787, cumulativeWater: 0.0084 },
        { date: '2004-11-28', bottomHolePressure: 31.1757, gasProduction: 31.3260, cumulativeGas: 0.0818, cumulativeWater: 0.0087 },
        { date: '2004-11-29', bottomHolePressure: 29.3825, gasProduction: 40.2240, cumulativeGas: 0.0859, cumulativeWater: 0.0091 },
        { date: '2004-11-30', bottomHolePressure: 33.6144, gasProduction: 47.3460, cumulativeGas: 0.0906, cumulativeWater: 0.0094 },
        { date: '2004-12-01', bottomHolePressure: 31.9547, gasProduction: 40.1700, cumulativeGas: 0.0946, cumulativeWater: 0.0097 },
        { date: '2004-12-02', bottomHolePressure: 30.3854, gasProduction: 15.5220, cumulativeGas: 0.0962, cumulativeWater: 0.0099 },
        { date: '2004-12-04', bottomHolePressure: 29.1792, gasProduction: 10.1580, cumulativeGas: 0.0972, cumulativeWater: 0.0101 },
        { date: '2004-12-05', bottomHolePressure: 30.6284, gasProduction: 24.6840, cumulativeGas: 0.0997, cumulativeWater: 0.0104 },
        { date: '2004-12-06', bottomHolePressure: 30.7455, gasProduction: 30.6420, cumulativeGas: 0.1027, cumulativeWater: 0.0106 },
        { date: '2004-12-07', bottomHolePressure: 30.7251, gasProduction: 30.5160, cumulativeGas: 0.1058, cumulativeWater: 0.0108 },
        { date: '2004-12-08', bottomHolePressure: 31.0830, gasProduction: 31.2060, cumulativeGas: 0.1089, cumulativeWater: 0.0110 },
        { date: '2004-12-09', bottomHolePressure: 30.9988, gasProduction: 30.7440, cumulativeGas: 0.1120, cumulativeWater: 0.0113 },
        { date: '2004-12-10', bottomHolePressure: 30.5230, gasProduction: 16.7820, cumulativeGas: 0.1137, cumulativeWater: 0.0115 },
        { date: '2004-12-21', bottomHolePressure: 28.9590, gasProduction: 14.8860, cumulativeGas: 0.1152, cumulativeWater: 0.0116 },
        { date: '2004-12-22', bottomHolePressure: 31.7006, gasProduction: 35.0940, cumulativeGas: 0.1187, cumulativeWater: 0.0118 },
        { date: '2004-12-23', bottomHolePressure: 30.3005, gasProduction: 39.5340, cumulativeGas: 0.1226, cumulativeWater: 0.0125 },
        { date: '2004-12-24', bottomHolePressure: 31.4999, gasProduction: 46.6140, cumulativeGas: 0.1273, cumulativeWater: 0.0135 },
        { date: '2004-12-25', bottomHolePressure: 33.0520, gasProduction: 48.0360, cumulativeGas: 0.1321, cumulativeWater: 0.0143 },
        { date: '2004-12-26', bottomHolePressure: 32.2034, gasProduction: 46.5300, cumulativeGas: 0.1367, cumulativeWater: 0.0147 },
        { date: '2004-12-27', bottomHolePressure: 32.0533, gasProduction: 46.1580, cumulativeGas: 0.1414, cumulativeWater: 0.0151 }
      ]
    },
    data: [
      [360, 0.34], [390, 0.36], [420, 0.38], [450, 0.41], [480, 0.43],
      [510, 0.45], [540, 0.48], [570, 0.51], [600, 0.46], [630, 0.49],
      [660, 0.53], [690, 0.56], [720, 0.50], [750, 0.55], [780, 0.62],
      [810, 0.58], [840, 0.64], [870, 0.60], [900, 0.63], [930, 0.59],
      [960, 0.65], [990, 0.61], [1020, 0.66], [1050, 0.62], [1080, 0.64],
      [1110, 0.60], [1140, 0.63], [1170, 0.61], [1200, 0.65],[1350, 0.72], [1500, 0.90], [1700, 1.14]
    ],
    result: {
      dynamicReserves: 19.8548,
      intercept: '1.1150E-1',
      slope: '4.6424E-4',
      rsquared: 0.8844,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-2': {
    input: {
      gasType: '干气',
      gasSpecificGravity: 0.6,
      h2sMolePercent: 2.5,
      co2MolePercent: 3.2,
      n2MolePercent: 1.0,
      nonHydrocarbonCorrection: 'Wichert-Aziz 修正方法',
      deviationCoefficientMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
      originalReservoirPressure: 55,
      reservoirTemperature: 85,
      materialBalanceType: '封闭气藏',
      transientFlowTime: 200,
      thinningPoints: 350,
      wgrEnabled: true,
      waterGasRatioLimit: 0.05,
      gasReservoirVolume: 600,
      irreducibleWaterSaturation: 28.0,
      rockCompressibility: 0.00008,
      waterCompressibility: 3.8E-4,
      productionData: []
    },
    data: [],
    result: {
      dynamicReserves: 25.6780,
      intercept: '1.3200E-1',
      slope: '3.8500E-4',
      rsquared: 0.9250,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-3': {
    input: {
      gasType: '干气',
      gasSpecificGravity: 0.55,
      h2sMolePercent: 1.8,
      co2MolePercent: 2.5,
      n2MolePercent: 0.8,
      nonHydrocarbonCorrection: 'Wichert-Aziz 修正方法',
      deviationCoefficientMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
      originalReservoirPressure: 48,
      reservoirTemperature: 78,
      materialBalanceType: '封闭气藏',
      transientFlowTime: 160,
      thinningPoints: 280,
      wgrEnabled: false,
      waterGasRatioLimit: 0,
      gasReservoirVolume: 450,
      irreducibleWaterSaturation: 25.5,
      rockCompressibility: 0.00011,
      waterCompressibility: 3.6E-4,
      productionData: []
    },
    data: [],
    result: {
      dynamicReserves: 22.4560,
      intercept: '1.2500E-1',
      slope: '3.6800E-4',
      rsquared: 0.8960,
      reliability: '分析结果可靠性较高'
    }
  },
  'X-5': {
    input: {
      gasType: '干气',
      gasSpecificGravity: 0.58,
      h2sMolePercent: 4.62,
      co2MolePercent: 3.96,
      n2MolePercent: 0,
      nonHydrocarbonCorrection: 'Wichert-Aziz 修正方法',
      deviationCoefficientMethod: 'Dranchuk-Abu-Kassem 方法',
      viscosityMethod: 'Lee-Gonzalez-Eakin 方法',
      originalReservoirPressure: 50,
      reservoirTemperature: 80,
      materialBalanceType: '封闭气藏',
      transientFlowTime: 180,
      thinningPoints: 300,
      wgrEnabled: true,
      waterGasRatioLimit: 0.0602,
      gasReservoirVolume: 500,
      irreducibleWaterSaturation: 24.53,
      rockCompressibility: 0.0001,
      waterCompressibility: 3.7445E-4,
      productionData: [
        { date: '2005-01-16', bottomHolePressure: 27.3997, gasProduction: 8.8800, cumulativeGas: 0.0009, cumulativeWater: 0.0000 },
        { date: '2005-01-17', bottomHolePressure: 28.5118, gasProduction: 45.2520, cumulativeGas: 0.0054, cumulativeWater: 0.0006 },
        { date: '2005-01-18', bottomHolePressure: 28.9682, gasProduction: 43.2120, cumulativeGas: 0.0097, cumulativeWater: 0.0011 },
        { date: '2005-01-19', bottomHolePressure: 28.6602, gasProduction: 43.4760, cumulativeGas: 0.0141, cumulativeWater: 0.0016 },
        { date: '2005-01-20', bottomHolePressure: 28.8479, gasProduction: 43.0440, cumulativeGas: 0.0181, cumulativeWater: 0.0022 },
        { date: '2005-01-21', bottomHolePressure: 28.7965, gasProduction: 43.3860, cumulativeGas: 0.0225, cumulativeWater: 0.0027 },
        { date: '2005-01-22', bottomHolePressure: 28.7760, gasProduction: 49.9380, cumulativeGas: 0.0275, cumulativeWater: 0.0031 },
        { date: '2005-01-23', bottomHolePressure: 28.7986, gasProduction: 50.0100, cumulativeGas: 0.0325, cumulativeWater: 0.0035 },
        { date: '2005-01-24', bottomHolePressure: 28.5974, gasProduction: 49.1040, cumulativeGas: 0.0374, cumulativeWater: 0.0040 },
        { date: '2005-01-25', bottomHolePressure: 28.3284, gasProduction: 56.4300, cumulativeGas: 0.0430, cumulativeWater: 0.0043 },
        { date: '2005-01-26', bottomHolePressure: 28.6115, gasProduction: 60.9720, cumulativeGas: 0.0491, cumulativeWater: 0.0047 },
        { date: '2005-01-27', bottomHolePressure: 28.6650, gasProduction: 61.5720, cumulativeGas: 0.0553, cumulativeWater: 0.0051 },
        { date: '2005-01-28', bottomHolePressure: 28.5818, gasProduction: 60.6900, cumulativeGas: 0.0614, cumulativeWater: 0.0052 },
        { date: '2005-01-29', bottomHolePressure: 28.6162, gasProduction: 62.5500, cumulativeGas: 0.0677, cumulativeWater: 0.0055 },
        { date: '2005-01-30', bottomHolePressure: 28.5261, gasProduction: 60.8460, cumulativeGas: 0.0737, cumulativeWater: 0.0057 },
        { date: '2005-01-31', bottomHolePressure: 26.6606, gasProduction: 31.7820, cumulativeGas: 0.0769, cumulativeWater: 0.0058 },
        { date: '2005-02-01', bottomHolePressure: 28.5880, gasProduction: 60.7440, cumulativeGas: 0.0829, cumulativeWater: 0.0059 },
        { date: '2005-02-02', bottomHolePressure: 29.2194, gasProduction: 53.3400, cumulativeGas: 0.0883, cumulativeWater: 0.0062 },
        { date: '2005-02-03', bottomHolePressure: 28.6016, gasProduction: 44.4900, cumulativeGas: 0.0927, cumulativeWater: 0.0066 },
        { date: '2005-02-04', bottomHolePressure: 29.0004, gasProduction: 48.5280, cumulativeGas: 0.0976, cumulativeWater: 0.0068 },
        { date: '2005-02-05', bottomHolePressure: 28.2900, gasProduction: 45.2760, cumulativeGas: 0.1021, cumulativeWater: 0.0071 },
        { date: '2005-02-06', bottomHolePressure: 29.0324, gasProduction: 57.1680, cumulativeGas: 0.1078, cumulativeWater: 0.0075 },
        { date: '2005-02-07', bottomHolePressure: 28.3441, gasProduction: 53.5620, cumulativeGas: 0.1132, cumulativeWater: 0.0077 },
        { date: '2005-02-08', bottomHolePressure: 28.4901, gasProduction: 45.5160, cumulativeGas: 0.1185, cumulativeWater: 0.0083 },
        { date: '2005-02-09', bottomHolePressure: 28.4499, gasProduction: 45.5160, cumulativeGas: 0.1230, cumulativeWater: 0.0083 },
        { date: '2005-02-10', bottomHolePressure: 28.4743, gasProduction: 46.9800, cumulativeGas: 0.1277, cumulativeWater: 0.0085 },
        { date: '2005-02-11', bottomHolePressure: 28.1461, gasProduction: 49.4160, cumulativeGas: 0.1327, cumulativeWater: 0.0088 },
        { date: '2005-02-12', bottomHolePressure: 28.1003, gasProduction: 55.5600, cumulativeGas: 0.1377, cumulativeWater: 0.0089 },
        { date: '2005-02-13', bottomHolePressure: 28.8522, gasProduction: 55.9560, cumulativeGas: 0.1433, cumulativeWater: 0.0092 },
        { date: '2005-02-14', bottomHolePressure: 28.8368, gasProduction: 55.7520, cumulativeGas: 0.1489, cumulativeWater: 0.0094 },
        { date: '2005-02-15', bottomHolePressure: 29.2276, gasProduction: 51.7560, cumulativeGas: 0.1541, cumulativeWater: 0.0095 },
        { date: '2005-02-16', bottomHolePressure: 29.2323, gasProduction: 51.8100, cumulativeGas: 0.1592, cumulativeWater: 0.0097 }
      ]
    },
    data: [
      [300, 0.37], [330, 0.38], [360, 0.39], [390, 0.40], [420, 0.41],
      [450, 0.39], [480, 0.42], [510, 0.43], [540, 0.41], [570, 0.44],
      [600, 0.42], [630, 0.45], [660, 0.46], [690, 0.44], [720, 0.47],
      [750, 0.48], [780, 0.46], [810, 0.49], [840, 0.50], [870, 0.48],
      [900, 0.51], [930, 0.52], [960, 0.50], [990, 0.53], [1020, 0.54],
      [1050, 0.52], [1080, 0.55], [1110, 0.56], [1140, 0.54], [1170, 0.57],
      [1200, 0.58], [1230, 0.56], [1260, 0.60], [1290, 0.61], [1320, 0.59],
      [1350, 0.62], [1380, 0.63], [1410, 0.61], [1440, 0.64], [1470, 0.65],
      [1500, 0.63], [1530, 0.66], [1560, 0.67], [1590, 0.65], [1620, 0.71],
      [1680, 0.68], [1740, 0.70], [1800, 0.73], [1860, 0.75], [1920, 0.77],
      [1980, 0.86], [2040, 0.87], [2400, 0.99], [2640, 1.11], [2940, 1.21],
      [3000, 1.22], [3120, 1.27]
    ],
    result: {
      dynamicReserves: 33.0492,
      intercept: '2.3200E-1',
      slope: '2.7744E-4',
      rsquared: 0.8873,
      reliability: '分析结果可靠性较高'
    }
  }
}

const currentWellData = computed(() => {
  const wellName = currentWellName.value
  return wellDataMap[wellName] || wellDataMap['X-1']
})

const inputData = computed(() => currentWellData.value.input)
const output = computed(() => currentWellData.value.result)
const chartPoints = computed(() => currentWellData.value.data)

const hasOutputResults = computed(() => output.value.dynamicReserves !== undefined)

const regression = computed(() => {
  const result = output.value
  const slope = parseFloat(result.slope) || 0.0003133
  const intercept = parseFloat(result.intercept) || 0.1567
  const r2 = parseFloat(result.rsquared) || 0.6323
  return { slope, intercept, r2 }
})

const chartXRange = computed(() => ({ xMin: 0, xMax: 3200 }))
const chartYRange = computed(() => ({ yMin: 0.2, yMax: 1.2 }))

const renderChart = () => {
  if (!chart) return
  
  const { slope, intercept } = regression.value
  const { xMin, xMax } = chartXRange.value
  const wellName = currentWellName.value
  const showRegression = !['X-2', 'X-3'].includes(wellName)
  
  const series = [
    {
      name: '数据点 (MPa/(10⁴m³/d))',
      type: 'scatter',
      data: chartPoints.value,
      symbolSize: 6,
      itemStyle: { 
        color: '#3b82f6', 
        opacity: 0.8 
      }
    }
  ]
  
  if (showRegression) {
    series.push({
      name: '回归线 (MPa/(10⁴m³/d))',
      type: 'line',
      data: [
        [xMin, slope * xMin + intercept],
        [xMax, slope * xMax + intercept]
      ],
      symbol: 'none',
      lineStyle: { color: '#1f2937', width: 2, type: 'solid' },
      tooltip: { show: false }
    })
  }

  chart.setOption({
    animation: false,
    title: {
      text: '动态物质平衡',
      left: 'center',
      top: 8,
      textStyle: { fontSize: 16, fontWeight: 600, color: '#333' }
    },
    tooltip: {
      trigger: 'item',
      axisPointer: {
        type: 'cross',
        crossStyle: { color: '#999', type: 'dashed', width: 1 },
        label: { backgroundColor: '#999' }
      },
      formatter: p => `${p.marker}${p.seriesName}: ${Number(p.value?.[1] ?? p.value).toFixed(4)}`
    },
    legend: {
      data: showRegression ? ['数据点 (MPa/(10⁴m³/d))', '回归线 (MPa/(10⁴m³/d))'] : ['数据点 (MPa/(10⁴m³/d))'],
      top: 35,
      left: 'center'
    },
    grid: {
      left: 80,
      right: 60,
      top: 80,
      bottom: 60
    },
    xAxis: {
      type: 'value',
      name: '累计产气量 (10⁴m³)',
      min: chartXRange.value.xMin,
      max: chartXRange.value.xMax,
      nameLocation: 'middle',
      nameGap: 35,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#eee' } },
      splitLine: { lineStyle: { color: '#ddd' } },
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: '(Pi - Pwf)/qsc (MPa/(10⁴m³/d))',
      min: chartYRange.value.yMin,
      max: chartYRange.value.yMax,
      nameLocation: 'middle',
      nameGap: 60,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#eee' } },
      splitLine: { lineStyle: { color: '#ddd' } },
      axisLabel: { fontSize: 11 }
    },
    series
  }, true)
}

const fetchData = async () => {
  const wellName = currentWellName.value
  if (!wellName) return

  loading.value = true
  try {
    await nextTick()
    renderChart()
    emit('refresh-tree')
  } catch (e) {
    console.error('[DynamicBalanceContent] 数据加载失败', e)
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

const startParamsPanelResize = (e) => {
  e.preventDefault()
  const startX = e.clientX
  const startWidth = paramsPanelWidth.value
  
  const onMouseMove = (e) => {
    const deltaX = e.clientX - startX
    const newWidth = Math.max(260, Math.min(400, startWidth + deltaX))
    paramsPanelWidth.value = newWidth
  }
  
  const onMouseUp = () => {
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
  }
  
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}

watch(() => props.node?.wellName, fetchData, { immediate: true })

onMounted(() => {
  chart = echarts.init(chartEl.value)
  window.addEventListener('resize', () => chart?.resize())
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', () => chart?.resize())
  chart?.dispose()
})
</script>

<template>
  <div v-loading="loading" class="db-wrap">
    <div v-if="!paramsCollapsed && !['X-2', 'X-3'].includes(currentWellName)" class="params-panel" :style="{ width: paramsPanelWidth + 'px', minWidth: paramsPanelWidth + 'px' }">
      <div class="panel-head">
        <span>参数设置</span>
        <button class="panel-toggle" @click="paramsCollapsed = true">
          <span>‹</span>
        </button>
      </div>
      
      <div v-if="activeParamTab === 'input'" class="panel-body">
        <div class="sec-label">气体性质</div>
        <div class="field-grid">
          <div class="field">
            <label>天然气类型</label>
            <el-select size="small" :model-value="inputData.gasType" disabled>
              <el-option label="干气" value="干气" />
              <el-option label="湿气" value="湿气" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气比重 (dless)</label>
            <el-input size="small" readonly :model-value="inputData.gasSpecificGravity" />
          </div>
          <div class="field">
            <label>H₂S摩尔百分含量 (%)</label>
            <el-input size="small" readonly :model-value="inputData.h2sMolePercent" />
          </div>
          <div class="field">
            <label>CO₂摩尔百分含量 (%)</label>
            <el-input size="small" readonly :model-value="inputData.co2MolePercent" />
          </div>
          <div class="field">
            <label>N₂摩尔百分含量 (%)</label>
            <el-input size="small" readonly :model-value="inputData.n2MolePercent" />
          </div>
        </div>

        <div class="sec-label">计算方法</div>
        <div class="field-grid">
          <div class="field">
            <label>非烃气体修正方法</label>
            <el-select size="small" :model-value="inputData.nonHydrocarbonCorrection" disabled>
              <el-option label="Wichert-Aziz 修正方法" value="Wichert-Aziz 修正方法" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气偏差系数计算方法</label>
            <el-select size="small" :model-value="inputData.deviationCoefficientMethod" disabled>
              <el-option label="Dranchuk-Abu-Kassem 方法" value="Dranchuk-Abu-Kassem 方法" />
            </el-select>
          </div>
          <div class="field">
            <label>天然气粘度计算方法</label>
            <el-select size="small" :model-value="inputData.viscosityMethod" disabled>
              <el-option label="Lee-Gonzalez-Eakin 方法" value="Lee-Gonzalez-Eakin 方法" />
            </el-select>
          </div>
        </div>

        <div class="sec-label">其他数据</div>
        <div class="field-grid">
          <div class="field">
            <label>原始地层压力 (MPa)</label>
            <el-input size="small" readonly :model-value="inputData.originalReservoirPressure" />
          </div>
          <div class="field">
            <label>地层温度 (°C)</label>
            <el-input size="small" readonly :model-value="inputData.reservoirTemperature" />
          </div>
          <div class="field">
            <label>物质平衡方程类型</label>
            <el-select size="small" :model-value="inputData.materialBalanceType" disabled>
              <el-option label="封闭气藏" value="封闭气藏" />
            </el-select>
          </div>
          <div class="field">
            <label>不稳定流动段时间 (d)</label>
            <el-input size="small" readonly :model-value="inputData.transientFlowTime" />
          </div>
          <div class="field">
            <label>抽稀点数</label>
            <el-input size="small" readonly :model-value="inputData.thinningPoints" />
          </div>
          <div class="field field-with-switch">
            <div class="wgr-label-row">
              <span>生产水气比上限 (m³/10⁴m³)</span>
              <el-switch
                  :model-value="inputData.wgrEnabled"
                  disabled
                  style="--el-switch-on-color:#e8a000;--el-switch-off-color:#ccc"
                  size="small"
              />
            </div>
            <el-input
                size="small"
                readonly
                :disabled="!inputData.wgrEnabled"
                :model-value="inputData.waterGasRatioLimit"
            />
          </div>
          <div class="field">
            <label>气藏地质储量 (10⁸m³)</label>
            <el-input size="small" readonly :model-value="inputData.gasReservoirVolume" />
          </div>
          <div class="field">
            <label>束缚水饱和度(%)</label>
            <el-input size="small" readonly :model-value="inputData.irreducibleWaterSaturation" />
          </div>
          <div class="field">
            <label>储层岩石压缩系数 (MPa⁻¹)</label>
            <el-input size="small" readonly :model-value="inputData.rockCompressibility" />
          </div>
          <div class="field">
            <label>地层水压缩系数 (MPa⁻¹)</label>
            <el-input size="small" readonly :model-value="inputData.waterCompressibility" />
          </div>
        </div>

        <div class="sec-label">生产数据</div>
        <div class="btn-row">
          <el-button size="small">模板下载</el-button>
          <el-button size="small">导入</el-button>
        </div>
        
        <div v-if="inputData.productionData && inputData.productionData.length" class="production-table-wrap">
          <table class="production-table">
            <thead>
              <tr>
                <th>A 日期</th>
                <th>B 井底流压 (MPa)</th>
                <th>C 气产量 (10⁴m³/d)</th>
                <th>D 累产气 (10⁴m³)</th>
                <th>E 累产水 (10⁴m³)</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in inputData.productionData" :key="index">
                <td>{{ row.date }}</td>
                <td>{{ row.bottomHolePressure }}</td>
                <td>{{ row.gasProduction }}</td>
                <td>{{ row.cumulativeGas }}</td>
                <td>{{ row.cumulativeWater }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-else class="panel-body">
        <div class="sec-label">输出结果</div>
        <div class="field">
          <label>动态储量 (10⁸m³)</label>
          <el-input size="small" readonly :model-value="output.dynamicReserves" />
        </div>
        <div class="field">
          <label>回归分析截距 (MPa/(10⁴m³/d))</label>
          <el-input size="small" readonly :model-value="output.intercept" />
        </div>
        <div class="field">
          <label>回归分析斜率 ([MPa/(10⁴m³/d)]/d)</label>
          <el-input size="small" readonly :model-value="output.slope" />
        </div>
        <div class="field">
          <label>R² (无)</label>
          <el-input size="small" readonly :model-value="output.rsquared" />
        </div>
        <div class="field">
          <label>结果可靠性</label>
          <el-input size="small" readonly :model-value="output.reliability" />
        </div>
      </div>

      <div class="param-tabs">
        <div
          class="param-tab"
          :class="{ active: activeParamTab === 'input' }"
          @click="activeParamTab = 'input'"
        >
          输入
        </div>
        <div
          v-if="hasOutputResults"
          class="param-tab"
          :class="{ active: activeParamTab === 'output' }"
          @click="activeParamTab = 'output'"
        >
          输出
        </div>
      </div>
      <div class="params-resizer" @mousedown="startParamsPanelResize"></div>
    </div>

    <div v-if="paramsCollapsed && !['X-2', 'X-3'].includes(currentWellName)" class="panel-collapsed-tab" @click="paramsCollapsed = false">参数设置</div>

    <div class="chart-area">
      <div ref="chartEl" class="chart-instance" />
    </div>
  </div>
</template>

<style lang="scss" scoped>
.db-wrap {
  display: flex;
  height: 100%;
  background: #fff;
  overflow: hidden;
}

.params-panel {
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  transition: width 0.16s ease, min-width 0.16s ease;

  &.collapsed {
    background: transparent;
    border-right: 0;
  }
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 12px 6px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
  font-size: 13px;
  color: #333;
}

.panel-toggle {
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  border-radius: 2px;
  font-size: 16px;
  color: #666;

  &:hover {
    background: #eef4ff;
  }
}

.panel-collapsed-tab {
  width: 22px;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 13px;
  color: #333;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-left: 0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);

  &:hover {
    background: #eef4ff;
    color: #1f6fd6;
  }
}

.params-resizer {
  position: absolute;
  top: 0;
  right: -3px;
  width: 6px;
  height: 100%;
  cursor: col-resize;
  z-index: 4;

  &:hover {
    background: rgba(64, 132, 217, 0.18);
  }
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 4px 12px 14px;
}

.param-tabs {
  display: flex;
  height: 30px;
  border-top: 1px solid #e0e0e0;
  flex-shrink: 0;
}

.param-tab {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border-right: 1px solid #e0e0e0;

  &:last-child {
    border-right: none;
  }

  &.active {
    background-color: #f4d000;
    color: #1a1a1a;
    font-weight: 600;
  }
}

.sec-label {
  font-weight: 500;
  color: #333;
  font-size: 13px;
  margin: 10px 0 7px;
  &:first-child { margin-top: 4px; }
}

.field {
  margin-bottom: 9px;
  label {
    display: block;
    color: #555;
    font-size: 12px;
    margin-bottom: 3px;
  }
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  column-gap: 12px;
}

.field-with-switch {
  .el-input {
    margin-top: 3px;
  }
}

.wgr-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 3px;
  span { color: #555; font-size: 12px; }
}

.btn-row { display: flex; gap: 8px; margin-bottom: 8px; }

.production-table-wrap {
  max-height: 200px;
  overflow-y: auto;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
}

.production-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;

  th, td {
    border-bottom: 1px solid #f0f0f0;
    padding: 4px 6px;
    text-align: left;
    white-space: nowrap;
  }

  th {
    background: #fafafa;
    font-weight: 500;
    color: #666;
  }

  tr:last-child td {
    border-bottom: none;
  }
}

.chart-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #e0e0e0;
}

.chart-instance {
  flex: 1;
  min-height: 0;
  width: 100%;
}
</style>