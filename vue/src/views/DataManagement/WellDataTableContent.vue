<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataManagementApi } from '@/api/docker'
import WellDataImportDialog from './WellDataImportDialog.vue'

const props = defineProps({
  dataType: {
    type: String,
    required: true
  },
  wellName: {
    type: String,
    default: ''
  },
  wellNames: {
    type: Array,
    default: () => []
  },
  projectId: {
    type: [Number, String],
    required: true
  },
  gasReservoirId: {
    type: [Number, String],
    required: true
  }
})

const STATIC_PRESSURE_TABS = [
  { dataType: 'calculatedStaticPressure', label: '计算静压' },
  { dataType: 'measuredStaticPressure', label: '实测静压' }
]

const isStaticPressureDataType = dataType =>
  dataType === 'staticPressure' ||
  STATIC_PRESSURE_TABS.some(tab => tab.dataType === dataType)

const activeStaticPressureType = ref(
  props.dataType === 'measuredStaticPressure'
    ? 'measuredStaticPressure'
    : 'calculatedStaticPressure'
)

const DATA_CONFIG = {
  wellhead: {
    title: '井头数据',
    keys: [
      'wellName',
      'xCoordinate',
      'yCoordinate',
      'kellyBushing',
      'measuredDepth',
      'trueVerticalDepth'
    ]
  },
  deviation: {
    title: '井斜数据',
    keys: [
      'wellName',
      'measuredDepth',
      'inclination',
      'azimuth',
      'xCoordinate',
      'yCoordinate',
      'zCoordinate',
      'xDeviation',
      'yDeviation',
      'trueVerticalDepth'
    ]
  },
  wellcompletion: {
    title: '完井数据',
    schema: 'core_well_completion_data',
    importFields: [
      {
        key: 'wellName',
        name: '井名',
        coreKey: 'well_name',
        aliases: ['井名', 'wellName', 'well_name']
      },
      {
        key: 'date',
        name: '日期',
        coreKey: 'date',
        aliases: ['日期', 'date'],
        date: true
      },
      {
        key: 'type',
        name: '套管类型',
        coreKey: 'casing_type',
        aliases: ['套管类型', 'type', 'casing_type']
      },
      {
        key: 'topMeasuredDepth',
        name: '顶部测量深度',
        coreKey: 'top_measured_depth',
        aliases: ['顶部测量深度', 'topMeasuredDepth', 'top_measured_depth']
      },
      {
        key: 'bottomMeasuredDepth',
        name: '底部测量深度',
        coreKey: 'bottom_measured_depth',
        aliases: ['底部测量深度', 'bottomMeasuredDepth', 'bottom_measured_depth']
      },
      {
        key: 'innerDiameter',
        name: '内径',
        coreKey: 'inner_diameter',
        aliases: ['内径', 'innerDiameter', 'inner_diameter']
      },
      {
        key: 'innerRoughness',
        name: '内壁粗糙度',
        coreKey: 'inner_roughness',
        aliases: ['内壁粗糙度', 'innerRoughness', 'inner_roughness'],
        optional: true
      },
      {
        key: 'outerDiameter',
        name: '外径',
        coreKey: 'outer_diameter',
        aliases: ['外径', 'outerDiameter', 'outer_diameter']
      },
      {
        key: 'outerRoughness',
        name: '外壁粗糙度',
        coreKey: 'outer_roughness',
        aliases: ['外壁粗糙度', 'outerRoughness', 'outer_roughness'],
        optional: true
      }
    ],
    keys: [
      'wellName',
      'date',
      'type',
      'topMeasuredDepth',
      'bottomMeasuredDepth',
      'innerDiameter',
      'innerRoughness',
      'outerDiameter',
      'outerRoughness'
    ]
  },
  logging: {
    title: '测井数据',
    keys: [
      'wellName',
      'interpretationNumber',
      'stratigraphy',
      'topMeasuredDepth',
      'bottomMeasuredDepth',
      'thickness',
      'porosity',
      'permeability',
      'irreducibleWaterSaturation',
      'interpretationConclusion',
      'topVerticalDepth',
      'bottomVerticalDepth',
      'VvrticalThickness'
    ]
  },
  deliverability: {
    title: '产能测试',
    schema: 'core_deliverability_test_data',
    importFields: [
      {
        key: 'wellName',
        name: '井名',
        coreKey: 'well_name',
        aliases: ['井名', 'wellName', 'well_name']
      },
      {
        key: 'productivityWellTestDate',
        name: '产能试井日期',
        coreKey: 'productivity_well_test_date',
        aliases: ['产能试井日期', '日期', 'productivityWellTestDate', 'productivity_well_test_date'],
        date: true
      },
      {
        key: 'productivityWellTestType',
        name: '产能试井类型',
        coreKey: 'productivity_well_test_type',
        aliases: ['产能试井类型', 'productivityWellTestType', 'productivity_well_test_type']
      },
      {
        key: 'testPointNumber',
        name: '测点序号',
        coreKey: 'test_point_number',
        aliases: ['测点序号', 'testPointNumber', 'test_point_number']
      },
      {
        key: 'reserviorPressure',
        name: '地层/恢复压力',
        coreKey: 'reservior_pressure',
        aliases: ['地层/恢复压力', '地层压力', 'reserviorPressure', 'reservior_pressure']
      },
      {
        key: 'testDailyGasProduction',
        name: '测试气产量',
        coreKey: 'test_daily_gas_production',
        aliases: ['测试气产量', 'testDailyGasProduction', 'test_daily_gas_production']
      },
      {
        key: 'testDailyOilProduction',
        name: '测试油产量',
        coreKey: 'test_daily_oil_production',
        aliases: ['测试油产量', 'testDailyOilProduction', 'test_daily_oil_production'],
        optional: true
      },
      {
        key: 'testBottomHoleFlowingPressure',
        name: '测试流压',
        coreKey: 'test_bottom_hole_flowing_pressure',
        aliases: ['测试流压', 'testBottomHoleFlowingPressure', 'test_bottom_hole_flowing_pressure']
      }
    ],
    keys: [
      'wellName',
      'productivityWellTestDate',
      'reserviorPressure',
      'productivityWellTestType',
      'testPointNumber',
      'testDailyGasProduction',
      'testBottomHoleFlowingPressure'
    ]
  },
  measuredStaticPressure: {
    title: '实测静压',
    schema: 'core_static_pressure_data',
    importFields: [
      {
        key: 'wellName',
        name: '井名',
        coreKey: 'well_name',
        aliases: ['井名', 'wellName', 'well_name']
      },
      {
        key: 'date',
        name: '日期',
        coreKey: 'date',
        aliases: ['日期', 'date'],
        date: true
      },
      {
        key: 'reserviorPressure',
        name: '地层压力',
        coreKey: 'reservior_pressure',
        aliases: ['地层压力', '地层/恢复压力', 'reserviorPressure', 'reservior_pressure']
      }
    ],
    fields: {
      reserviorPressure: { name_cn: '地层压力', unit_label: 'MPa', displayDecimal: 4 }
    },
    keys: [
      'wellName',
      'date',
      'reserviorPressure'
    ]
  },
  calculatedStaticPressure: {
    title: '计算静压',
    fields: {
      calculatedBottomHolePressure: {
        name_cn: '计算井底压力',
        unit_label: 'MPa',
        displayDecimal: 4
      }
    },
    keys: [
      'wellName',
      'date',
      'calculatedBottomHolePressure'
    ]
  },
  otherdata: {
    title: '其他数据',
    schema: 'core_other_data',
    importFields: [
      {
        key: 'wellName',
        name: '井名',
        coreKey: 'well_name',
        aliases: ['井名', 'wellName', 'well_name']
      },
      {
        key: 'wellType',
        name: '井型',
        coreKey: 'well_type',
        aliases: ['井型', 'wellType', 'well_type']
      },
      {
        key: 'isFractured',
        name: '是否压裂',
        coreKey: 'is_fractured',
        aliases: ['是否压裂', 'isFractured', 'is_fractured']
      },
      {
        key: 'flowPath',
        name: '流动路径',
        coreKey: 'flow_path',
        aliases: ['流动路径', 'flowPath', 'flow_path']
      },
      {
        key: 'originalFormationPressure',
        name: '原始地层压力',
        coreKey: 'original_formation_pressure',
        aliases: ['原始地层压力', 'originalFormationPressure', 'original_formation_pressure']
      },
      {
        key: 'formationTemperature',
        name: '地层温度',
        coreKey: 'formation_temperature',
        aliases: ['地层温度', 'formationTemperature', 'formation_temperature']
      },
      {
        key: 'fracturingSegment',
        name: '压裂段数',
        coreKey: 'fracturing_segment',
        aliases: ['压裂段数', 'fracturingSegment', 'fracturing_segment']
      },
      {
        key: 'horizontalSectionOfGasWellLength',
        name: '水平段长度',
        coreKey: 'horizontal_section_length',
        aliases: ['水平段长度', 'horizontalSectionOfGasWellLength', 'horizontal_section_length']
      },
      {
        key: 'singleWellOriginalGasInplace',
        name: '单井控制储量',
        coreKey: 'single_well_original_gas_inplace',
        aliases: ['单井控制储量', 'singleWellOriginalGasInplace', 'single_well_original_gas_inplace']
      }
    ],
    keys: [
      'wellName',
      'wellType',
      'isFractured',
      'flowPath',
      'originalFormationPressure',
      'formationTemperature',
      'fracturingSegment',
      'horizontalSectionOfGasWellLength',
      'singleWellOriginalGasInplace'
    ]
  },
  productiondata: {
    title: '注采数据',
    schema: 'core_production_data',
    importFields: [
      {
        key: 'wellName',
        name: '井名',
        coreKey: 'well_name',
        aliases: ['井名', 'wellName', 'well_name']
      },
      {
        key: 'date',
        name: '日期',
        coreKey: 'date',
        aliases: ['日期', 'date'],
        date: true
      },
      {
        key: 'wellHeadTubingPressure',
        name: '井口油压',
        coreKey: 'well_head_tubing_pressure',
        aliases: ['井口油压', 'wellHeadTubingPressure', 'well_head_tubing_pressure']
      },
      {
        key: 'wellHeadCasingPressure',
        name: '井口套压',
        coreKey: 'well_head_casing_pressure',
        aliases: ['井口套压', 'wellHeadCasingPressure', 'well_head_casing_pressure']
      },
      {
        key: 'wellHeadTubingTemperature',
        name: '井口油温',
        coreKey: 'well_head_tubing_temperature',
        aliases: ['井口油温', 'wellHeadTubingTemperature', 'well_head_tubing_temperature']
      },
      {
        key: 'wellHeadCasingTemperature',
        name: '井口套温',
        coreKey: 'well_head_casing_temperature',
        aliases: ['井口套温', 'wellHeadCasingTemperature', 'well_head_casing_temperature']
      },
      {
        key: 'dailyGasProduction',
        name: '气产量',
        coreKey: 'daily_gas_production',
        aliases: ['气产量', 'dailyGasProduction', 'daily_gas_production']
      },
      {
        key: 'dailyWaterProduction',
        name: '水产量',
        coreKey: 'daily_water_production',
        aliases: ['水产量', 'dailyWaterProduction', 'daily_water_production']
      },
      {
        key: 'measuredBottomHolePressure',
        name: '井底压力（实测）',
        coreKey: 'measured_bottom_hole_pressure',
        aliases: ['井底压力（实测）', 'measuredBottomHolePressure', 'measured_bottom_hole_pressure'],
        optional: true
      },
      {
        key: 'cumulativeGasProduction',
        name: '累产气量',
        coreKey: 'cumulative_gas_production',
        aliases: ['累产气量', 'cumulativeGasProduction', 'cumulative_gas_production'],
        optional: true
      },
      {
        key: 'cumulativeWaterProduction',
        name: '累产水量',
        coreKey: 'cumulative_water_production',
        aliases: ['累产水量', 'cumulativeWaterProduction', 'cumulative_water_production'],
        optional: true
      },
      {
        key: 'waterGasRatio',
        name: '水气比',
        coreKey: 'water_gas_ratio',
        aliases: ['水气比', 'waterGasRatio', 'water_gas_ratio'],
        optional: true
      },
      {
        key: 'liquidGasRatio',
        name: '液气比',
        coreKey: 'liquid_gas_ratio',
        aliases: ['液气比', 'liquidGasRatio', 'liquid_gas_ratio'],
        optional: true
      },
      {
        key: 'calculatedBottomHolePressure',
        name: '井底压力（计算）',
        coreKey: 'calculated_bottom_hole_pressure',
        aliases: ['井底压力（计算）', 'calculatedBottomHolePressure', 'calculated_bottom_hole_pressure'],
        optional: true
      }
    ],
    keys: [
      'date',
      'wellHeadTubingPressure',
      'wellHeadCasingPressure',
      'wellHeadTubingTemperature',
      'wellHeadCasingTemperature',
      'dailyGasProduction',
      'dailyWaterProduction',
      'measuredBottomHolePressure',
      'cumulativeGasProduction',
      'cumulativeWaterProduction',
      'waterGasRatio',
      'liquidGasRatio',
      'calculatedBottomHolePressure'
    ]
  }
}

const FALLBACK_FIELDS = {
  wellName: { name_cn: '井名', unit_label: '' },
  xCoordinate: { name_cn: '横坐标', unit_label: 'm' },
  yCoordinate: { name_cn: '纵坐标', label: 'm' },
  kellyBushing: { name_cn: '补心海拔', unit_label: 'm' },
  measuredDepth: { name_cn: '测量深度', unit_label: 'm' },
  trueVerticalDepth: { name_cn: '真实垂直深度', unit_label: 'm' },
  inclination: { name_cn: '井斜角', unit_label: '°' },
  azimuth: { name_cn: '方位角', unit_label: '°' },
  zCoordinate: { name_cn: 'Z坐标', unit_label: 'm' },
  xDeviation: { name_cn: 'X方向偏移量', unit_label: 'm' },
  yDeviation: { name_cn: 'Y方向偏移量', unit_label: 'm' },
  interpretationNumber: { name_cn: '解释序号', unit_label: '' },
  stratigraphy: { name_cn: '层位', unit_label: '' },
  topMeasuredDepth: { name_cn: '储层顶部深度', unit_label: 'm' },
  bottomMeasuredDepth: { name_cn: '储层底部深度', unit_label: 'm' },
  thickness: { name_cn: '储层厚度', unit_label: 'm' },
  porosity: { name_cn: '储层孔隙度', unit_label: '%' },
  permeability: { name_cn: '储层渗透率', unit_label: 'mD' },
  irreducibleWaterSaturation: { name_cn: '储层束缚水饱和度', unit_label: '%' },
  interpretationConclusion: { name_cn: '解释结论', unit_label: '' },
  topVerticalDepth: { name_cn: '储层顶部垂直深度', unit_label: 'm' },
  bottomVerticalDepth: { name_cn: '储层底部垂直深度', unit_label: 'm' },
  VvrticalThickness: { name_cn: '储层垂直厚度', unit_label: 'm' },
  date: { name_cn: '日期', unit_label: '' },
  type: { name_cn: '套管类型', unit_label: '' },
  topMeasuredDepth: { name_cn: '顶部测量深度', unit_label: 'm', displayDecimal: 2 },
  bottomMeasuredDepth: { name_cn: '底部测量深度', unit_label: 'm', displayDecimal: 2 },
  innerDiameter: { name_cn: '内径', unit_label: 'mm', displayDecimal: 2 },
  innerRoughness: { name_cn: '内壁粗糙度', unit_label: '', displayDecimal: 4 },
  outerDiameter: { name_cn: '外径', unit_label: 'mm', displayDecimal: 2 },
  outerRoughness: { name_cn: '外壁粗糙度', unit_label: '', displayDecimal: 4 },
  productivityWellTestDate: { name_cn: '产能试井日期', unit_label: '' },
  productivityWellTestType: { name_cn: '产能试井类型', unit_label: '' },
  testPointNumber: { name_cn: '测点序号', unit_label: '' },
  reserviorPressure: { name_cn: '地层/恢复压力', unit_label: 'MPa', displayDecimal: 4 },
  testDailyGasProduction: { name_cn: '测试气产量', unit_label: '10⁴m³/d', displayDecimal: 4 },
  testDailyOilProduction: { name_cn: '测试油产量', unit_label: 'm³/d', displayDecimal: 2 },
  testBottomHoleFlowingPressure: { name_cn: '测试流压', unit_label: 'MPa', displayDecimal: 4 },
  equivalentTestDailyGasProduction: { name_cn: '折算测试气产量', unit_label: '10⁴m³/d', displayDecimal: 4 },
  date: { name_cn: '日期', unit_label: '' },
  cumulativeGasProduction: { name_cn: '累产气量', unit_label: '10⁸m³', displayDecimal: 4 },
  cumulativeWaterProduction: { name_cn: '累产水量', unit_label: '10⁴m³', displayDecimal: 4 },
  cumulativeOilProduction: { name_cn: '累产油量', unit_label: '10⁴m³', displayDecimal: 4 },
  equivalentCumulativeGasProduction: { name_cn: '折算累产气量', unit_label: '10⁸m³', displayDecimal: 4 },
  wellType: { name_cn: '井型', unit_label: '' },
  flowPath: { name_cn: '流动路径', unit_label: '' },
  isFractured: { name_cn: '是否压裂', unit_label: '' },
  originalFormationPressure: { name_cn: '原始地层压力', unit_label: 'MPa', displayDecimal: 0 },
  formationTemperature: { name_cn: '地层温度', unit_label: '℃', displayDecimal: 0 },
  fracturingSegment: { name_cn: '压裂段数', unit_label: '', displayDecimal: 0 },
  horizontalSectionOfGasWellLength: { name_cn: '水平段长度', unit_label: 'm', displayDecimal: 2 },
  singleWellOriginalGasInplace: { name_cn: '单井控制储量', unit_label: '10⁸m³' },
  wellHeadTubingPressure: { name_cn: '井口油压', unit_label: 'MPa' },
  wellHeadCasingPressure: { name_cn: '井口套压', unit_label: 'MPa' },
  wellHeadTubingTemperature: { name_cn: '井口油温', unit_label: '℃' },
  wellHeadCasingTemperature: { name_cn: '井口套温', unit_label: '℃' },
  dailyGasProduction: { name_cn: '气产量', unit_label: '10⁴m³/d' },
  dailyWaterProduction: { name_cn: '水产量', unit_label: 'm³/d' },
  measuredBottomHolePressure: { name_cn: '井底压力（实测）', unit_label: '' },
  cumulativeGasProduction: { name_cn: '累产气量', unit_label: '10⁸m³' },
  cumulativeWaterProduction: { name_cn: '累产水量', unit_label: '10⁴m³' },
  waterGasRatio: { name_cn: '水气比', unit_label: 'm³/10⁴m³' },
  liquidGasRatio: { name_cn: '液气比', unit_label: 'm³/10⁴m³' },
  calculatedBottomHolePressure: { name_cn: '井底压力（计算）', unit_label: '' }
}

const loading = ref(false)
const importing = ref(false)
const importDialogVisible = ref(false)
const rows = ref([])
const responseFields = ref([])
let loadSequence = 0

const productionDataCache = new Map()

const getProductionCacheKey = (projectId, gasReservoirId, wellName) =>
  `${projectId}_${gasReservoirId}_${wellName}`

const getCachedProductionData = (cacheKey) => {
  if (productionDataCache.has(cacheKey)) {
    const cached = productionDataCache.get(cacheKey)
    const now = Date.now()
    if (now - cached.timestamp < 5 * 60 * 1000) {
      return cached.data
    }
    productionDataCache.delete(cacheKey)
  }
  return null
}

const setCachedProductionData = (cacheKey, data) => {
  productionDataCache.set(cacheKey, {
    data,
    timestamp: Date.now()
  })
}

const isStaticPressureView = computed(() => isStaticPressureDataType(props.dataType))
const effectiveDataType = computed(() =>
  isStaticPressureView.value ? activeStaticPressureType.value : props.dataType
)
const config = computed(() => DATA_CONFIG[effectiveDataType.value] || DATA_CONFIG.wellhead)
const importConfig = computed(() =>
  isStaticPressureView.value ? DATA_CONFIG.measuredStaticPressure : config.value
)
const isImportableData = computed(() =>
  Boolean(importConfig.value.schema && importConfig.value.importFields?.length)
)
const useDarkImportButton = computed(() =>
  effectiveDataType.value === 'deliverability' ||
  effectiveDataType.value === 'wellcompletion' ||
  effectiveDataType.value === 'otherdata' ||
  effectiveDataType.value === 'productiondata' ||
  isStaticPressureView.value
)
const tabTitle = computed(() => `${props.wellName} ${config.value.title}`.trim())
const getStaticPressureTabTitle = tab => `${props.wellName} ${tab.label}`.trim()

const selectStaticPressureType = dataType => {
  if (activeStaticPressureType.value === dataType) return
  activeStaticPressureType.value = dataType
}

const dataTemplateRows = computed(() => {
  const importFields = importConfig.value.importFields || []
  return [
    importFields.map(field => field.name),
    importFields.map(field => field.key === 'wellName' ? props.wellName : '')
  ]
})

const dataTemplateFileName = computed(() =>
  `${isStaticPressureView.value ? '静压' : config.value.title}数据模板.csv`
)

const fields = computed(() => {
  const fieldMap = new Map(
    responseFields.value
      .filter(field => field?.name)
      .map(field => [field.name, field])
  )

  return config.value.keys
    .filter(key => key !== 'wellName' || !props.wellName)
    .map(key => {
      const apiField = fieldMap.get(key) || {}
      const customField = config.value.fields?.[key] || {}
      const fallbackField = FALLBACK_FIELDS[key] || { name_cn: key, unit_label: '' }

      const field = {
        key,
        ...apiField,
        ...customField,
        ...fallbackField
      }

      return isImportableData.value || effectiveDataType.value === 'calculatedStaticPressure'
        ? { ...field, ...customField }
        : field
    })
})

const getFieldLabel = (field) => {
  const name = field.name_cn || field.key
  const unit = field.unit_label
  return unit ? `${name}(${unit})` : name
}

const formatCellValue = (row, field) => {
  const key = field.key
  let value = row[key]

  if (value === undefined || value === null) return ''

  if (key === 'date' || key === 'productivityWellTestDate') {
    return String(value).slice(0, 10).replaceAll('-', '/')
  }

  const numValue = Number(value)
  if (!Number.isNaN(numValue)) {
    if (field.displayDecimal !== undefined) {
      const fixedValue = numValue.toFixed(field.displayDecimal)
      if (Number.isInteger(numValue)) {
        return String(numValue)
      }
      return fixedValue
    }
    if (Number.isInteger(numValue)) {
      return String(numValue)
    }
    return String(value)
  }

  return String(value)
}

const normalizeHeader = (value) =>
  String(value ?? '')
    .trim()
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .toLowerCase()

const isEmptyImportCell = value =>
  value === null || value === undefined || String(value).trim() === ''

const isZeroImportCell = value =>
  !isEmptyImportCell(value) &&
  Number.isFinite(Number(value)) &&
  Number(value) === 0

const cleanImportTable = (sourceRows, options = {}) => {
  let table = sourceRows.map(row => [...row])

  if (options.removeEmptyRows) {
    table = table.filter(row => row.some(value => !isEmptyImportCell(value)))
  }
  if (!table.length) return table

  const columnCount = Math.max(...table.map(row => row.length))
  table = table.map(row =>
    Array.from({ length: columnCount }, (_, index) => row[index] ?? '')
  )

  if (options.removeEmptyColumns) {
    const keptIndexes = Array.from({ length: columnCount }, (_, index) => index)
      .filter(index => table.some(row => !isEmptyImportCell(row[index])))
    table = table.map(row => keptIndexes.map(index => row[index]))
  }

  return table
}

const normalizeExcelDate = (XLSX, value) => {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    const year = value.getFullYear()
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }

  if (typeof value === 'number') {
    const parsed = XLSX.SSF.parse_date_code(value)
    if (parsed) {
      return `${parsed.y}-${String(parsed.m).padStart(2, '0')}-${String(parsed.d).padStart(2, '0')}`
    }
  }

  const text = String(value ?? '').trim()
  const match = text.match(/^(\d{4})[年./-](\d{1,2})[月./-](\d{1,2})日?/)
  if (!match) return ''
  return `${match[1]}-${match[2].padStart(2, '0')}-${match[3].padStart(2, '0')}`
}

const parseImportWorkbook = async (file, options = {}) => {
  const XLSX = await import('xlsx')
  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true })
  const worksheet = workbook.Sheets[workbook.SheetNames[0]]
  if (!worksheet) throw new Error('Excel 中没有可读取的工作表')

  const table = cleanImportTable(
    XLSX.utils.sheet_to_json(worksheet, {
      header: 1,
      raw: true,
      defval: ''
    }),
    options
  )
  const importFields = importConfig.value.importFields || []
  const aliases = new Map()
  importFields.forEach(field => {
    field.aliases.forEach(alias => aliases.set(normalizeHeader(alias), field))
  })

  let headerIndex = -1
  let columnFields = []
  for (let index = 0; index < Math.min(table.length, 10); index += 1) {
    const candidates = table[index].map(value => aliases.get(normalizeHeader(value)) || null)
    if (candidates.filter(Boolean).length >= 2) {
      headerIndex = index
      columnFields = candidates
      break
    }
  }
  if (headerIndex < 0) throw new Error('没有识别到井名、日期等有效表头')

  const foundKeys = new Set(columnFields.filter(Boolean).map(field => field.key))
  const isRequiredField = field =>
    !field.optional && !(field.key === 'wellName' && props.wellName)
  const missing = importFields.filter(field => isRequiredField(field) && !foundKeys.has(field.key))
  if (missing.length) {
    throw new Error(`缺少必填列：${missing.map(field => field.name).join('、')}`)
  }

  const requiredFields = importFields.filter(isRequiredField)
  const dateField = importFields.find(field => field.date)
  let sourceDataRows = table.slice(headerIndex + 1)

  if (options.removeZeroColumns && sourceDataRows.length) {
    columnFields = columnFields.map((field, columnIndex) => {
      if (!field || !field.optional) return field
      const values = sourceDataRows.map(row => row[columnIndex])
      const nonEmptyValues = values.filter(value => !isEmptyImportCell(value))
      return nonEmptyValues.length && nonEmptyValues.every(isZeroImportCell)
        ? null
        : field
    })
  }

  const dataRows = sourceDataRows.flatMap((sourceRow, offset) => {
    const row = {}
    columnFields.forEach((field, columnIndex) => {
      if (!field) return
      const sourceValue = sourceRow[columnIndex]
      if (
        options.fillEmptyWithZero &&
        !field.date &&
        field.key !== 'wellName' &&
        isEmptyImportCell(sourceValue)
      ) {
        row[field.key] = 0
        return
      }
      row[field.key] = field.date ? normalizeExcelDate(XLSX, sourceValue) : sourceValue
    })

    if (Object.values(row).every(value => value === '' || value === null || value === undefined)) {
      return []
    }

    if (options.removeZeroRows) {
      const numericValues = Object.entries(row)
        .filter(([key, value]) =>
          key !== 'wellName' &&
          key !== 'date' &&
          key !== 'productivityWellTestDate' &&
          !isEmptyImportCell(value) &&
          Number.isFinite(Number(value))
        )
        .map(([, value]) => value)
      if (numericValues.length && numericValues.every(isZeroImportCell)) return []
    }

    const missingFields = requiredFields.filter(field => {
      const value = row[field.key]
      return value === '' || value === null || value === undefined
    })
    if (missingFields.length) {
      const isTemplateMetadataRow = offset < 2 && dateField && !row[dateField.key]
      if (isTemplateMetadataRow) return []
      throw new Error(
        `第 ${headerIndex + offset + 2} 行缺少：${missingFields.map(field => field.name).join('、')}`
      )
    }

    if (props.wellName) {
      const importedWellName = String(row.wellName ?? '').trim()
      if (foundKeys.has('wellName')) {
        if (!importedWellName) {
          throw new Error(`第 ${headerIndex + offset + 2} 行井名为空`)
        }
        if (importedWellName !== String(props.wellName)) {
          throw new Error(
            `第 ${headerIndex + offset + 2} 行井名为 ${importedWellName}，与当前选择的井 ${props.wellName} 不一致`
          )
        }
      }
      row.wellName = props.wellName
    }
    return [row]
  })
  if (!dataRows.length) throw new Error('Excel 中没有有效数据行')

  const retainedKeys = new Set(columnFields.filter(Boolean).map(field => field.key))
  const includedFields = importFields.filter(
    field => retainedKeys.has(field.key) || (field.key === 'wellName' && props.wellName)
  )
  const normalizedRows = [
    includedFields.map(field => field.name),
    ...dataRows.map(row => includedFields.map(field => row[field.key] ?? ''))
  ]
  const normalizedSheet = XLSX.utils.aoa_to_sheet(normalizedRows)
  const normalizedWorkbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(normalizedWorkbook, normalizedSheet, importConfig.value.title)
  const buffer = XLSX.write(normalizedWorkbook, { bookType: 'xlsx', type: 'array' })
  const normalizedFile = new File(
    [buffer],
    `${importConfig.value.title}-${Date.now()}.xlsx`,
    { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }
  )

  return {
    file: normalizedFile,
    rowCount: dataRows.length,
    mappings: Object.fromEntries(includedFields.map(field => [field.name, field.coreKey]))
  }
}

const chooseImportFile = () => {
  if (!isImportableData.value || importing.value) return
  importDialogVisible.value = true
}

const importExcel = async ({ file, options }) => {
  if (!file) return

  importing.value = true
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) {
      throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    }

    const parsed = await parseImportWorkbook(file, options)
    const uploadResponse = await dataManagementApi.uploadExcel(parsed.file)
    const filePath =
      uploadResponse?.data?.filepath ||
      uploadResponse?.data?.data?.filepath ||
      uploadResponse?.filepath
    if (!filePath) throw new Error('文件上传成功，但后端没有返回文件路径')

    await dataManagementApi.importCoreData(
      props.gasReservoirId,
      importConfig.value.schema,
      filePath,
      parsed.mappings
    )
    ElMessage.success(`成功导入 ${parsed.rowCount} 条${importConfig.value.title}`)
    await loadData()
  } catch (error) {
    ElMessage.error(
      error.response?.data?.message ||
      error.response?.data?.msg ||
      error.message ||
      `${importConfig.value.title}导入失败`
    )
  } finally {
    importing.value = false
  }
}

const getItems = (response) => {
  const payload = response?.data?.data ?? response?.data ?? response ?? {}
  const items = Array.isArray(payload.items)
    ? payload.items
    : Array.isArray(payload.rows)
      ? payload.rows
      : Array.isArray(payload)
        ? payload
        : []

  if (items.length > 0 && items[0]?.wellName) {
  }

  return {
    items,
    fields: Array.isArray(payload.fields) ? payload.fields : []
  }
}

const loadData = async () => {
  const sequence = ++loadSequence
  loading.value = true

  try {
    let response
    const dataType = effectiveDataType.value
    if (dataType === 'wellhead') {
      response = await dataManagementApi.getWellHead(props.projectId, props.gasReservoirId)
    } else if (dataType === 'deviation') {
      response = await dataManagementApi.getWellDeviation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    } else if (dataType === 'wellcompletion') {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames
      const responses = await Promise.allSettled(
        targetWellNames.map(wellName =>
          dataManagementApi.getWellCompletion(
            props.projectId,
            props.gasReservoirId,
            wellName,
            { silentError: true }
          )
        )
      )
      if (sequence !== loadSequence) return
      const failures = responses.filter(result => result.status === 'rejected')
      if (responses.length > 0 && failures.length === responses.length) {
        throw failures[0].reason
      }
      failures.forEach(result => {
        console.warn('部分井的完井数据加载失败', result.reason)
      })
      const results = responses
        .filter(result => result.status === 'fulfilled')
        .map(result => getItems(result.value))
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    } else if (dataType === 'logging') {
      response = await dataManagementApi.getLogInterpretation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    } else if (dataType === 'otherdata') {
      response = await dataManagementApi.getOtherData(props.projectId, props.gasReservoirId)
    } else if (dataType === 'productiondata') {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames

      const results = await Promise.all(
        targetWellNames.map(async wellName => {
          const cacheKey = getProductionCacheKey(props.projectId, props.gasReservoirId, wellName)

          let cachedResult = getCachedProductionData(cacheKey)

          if (!cachedResult) {
            try {
              const response = await dataManagementApi.getProductionData(
                props.projectId,
                props.gasReservoirId,
                wellName
              )
              cachedResult = getItems(response)
              setCachedProductionData(cacheKey, cachedResult)
            } catch (error) {
              console.warn(`加载${wellName}的注采数据失败:`, error)
              return { items: [], fields: [] }
            }
          }

          return cachedResult
        })
      )

      if (sequence !== loadSequence) return
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    } else if (dataType === 'deliverability') {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames
      const responses = await Promise.allSettled(
        targetWellNames.map(wellName =>
          dataManagementApi.getDeliverabilityTest(
            props.projectId,
            props.gasReservoirId,
            wellName,
            { silentError: true }
          )
        )
      )
      if (sequence !== loadSequence) return
      const failures = responses.filter(result => result.status === 'rejected')
      if (responses.length > 0 && failures.length === responses.length) {
        throw failures[0].reason
      }
      failures.forEach(result => {
        console.warn('部分井的产能测试数据加载失败', result.reason)
      })
      const results = responses
        .filter(result => result.status === 'fulfilled')
        .map(result => getItems(result.value))
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    } else if (
      dataType === 'measuredStaticPressure' ||
      dataType === 'calculatedStaticPressure'
    ) {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames
      const isCalculated = dataType === 'calculatedStaticPressure'
      const getPressureData = isCalculated
        ? dataManagementApi.getCalculatedStaticPressure
        : dataManagementApi.getStaticPressure
      const responses = await Promise.allSettled(
        targetWellNames.map(wellName =>
          getPressureData(
            props.projectId,
            props.gasReservoirId,
            wellName,
            { silentError: true }
          ).then(response => ({ response, wellName }))
        )
      )
      if (sequence !== loadSequence) return
      const failures = responses.filter(result => result.status === 'rejected')
      if (responses.length > 0 && failures.length === responses.length) {
        throw failures[0].reason
      }
      failures.forEach(result => {
        console.warn('部分井的静压数据加载失败', result.reason)
      })
      const results = responses
        .filter(result => result.status === 'fulfilled')
        .map(result => {
          const { response, wellName } = result.value
          const parsed = getItems(response)
          return {
            ...parsed,
            items: parsed.items.map(row => ({
              ...row,
              wellName: row.wellName || wellName,
              ...(isCalculated
                ? {
                    calculatedBottomHolePressure:
                      row.calculatedBottomHolePressure ??
                      row.calculateBottomHolePressure ??
                      row.reserviorPressure
                  }
                : {})
            }))
          }
        })
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    }

    if (sequence !== loadSequence) return

    const result = getItems(response)

    const finalItems = (dataType === 'wellhead' || dataType === 'otherdata')
      ? result.items.filter(item => !props.wellName || String(item?.wellName) === String(props.wellName))
      : result.items

    rows.value = finalItems
    responseFields.value = result.fields
  } catch (error) {
    if (sequence !== loadSequence) return
    rows.value = []
    responseFields.value = []
    ElMessage.error(error.response?.data?.message || error.message || `${config.value.title}加载失败`)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

watch(
  () => props.dataType,
  dataType => {
    if (dataType === 'measuredStaticPressure') {
      activeStaticPressureType.value = 'measuredStaticPressure'
    } else if (dataType === 'staticPressure' || dataType === 'calculatedStaticPressure') {
      activeStaticPressureType.value = 'calculatedStaticPressure'
    }
  },
  { immediate: true }
)

watch(
  () => [
    effectiveDataType.value,
    props.wellName,
    props.wellNames,
    props.projectId,
    props.gasReservoirId
  ],
  loadData,
  { immediate: true }
)
</script>

<template>
  <section class="well-data-content">
    <div class="data-tabs">
      <template v-if="isStaticPressureView">
        <div
          v-for="tab in STATIC_PRESSURE_TABS"
          :key="tab.dataType"
          class="data-tab data-tab--selectable"
          :class="{ active: activeStaticPressureType === tab.dataType }"
          @click="selectStaticPressureType(tab.dataType)"
        >
          {{ getStaticPressureTabTitle(tab) }}
        </div>
      </template>
      <div v-else class="data-tab active">{{ tabTitle }}</div>
    </div>

    <div class="data-toolbar">
      <el-button
        v-if="isImportableData"
        size="small"
        class="import-button"
        :class="{ 'import-button--dark': useDarkImportButton }"
        :loading="importing"
        @click="chooseImportFile"
      >
        导入
      </el-button>
    </div>

    <div class="data-table-wrap">
      <el-table
        v-loading="loading"
        :data="rows"
        border
        height="100%"
        empty-text="暂无数据"
        row-key="id"
      >
        <el-table-column type="index" label="序号" width="64" fixed />
        <el-table-column
          v-for="field in fields"
          :key="field.key"
          :label="getFieldLabel(field)"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ formatCellValue(row, field) }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <WellDataImportDialog
      v-model="importDialogVisible"
      :data-template-rows="dataTemplateRows"
      :data-template-file-name="dataTemplateFileName"
      @confirm="importExcel"
    />
  </section>
</template>

<style scoped>
.well-data-content {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  background: #fff;
  color: #303133;
}

.data-tabs {
  display: flex;
  flex: 0 0 31px;
  align-items: stretch;
  border-bottom: 1px solid #dcdfe6;
  background: #fff;
}

.data-tab {
  display: flex;
  align-items: center;
  padding: 0 13px;
  border-right: 1px solid #dcdfe6;
  font-size: 14px;
  white-space: nowrap;
}

.data-tab.active {
  background: #ffd800;
  color: #303133;
}

.data-tab--selectable {
  cursor: pointer;
}

.data-toolbar {
  display: flex;
  flex: 0 0 39px;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid #ebeef5;
}

.import-button--dark {
  --el-button-bg-color: #000;
  --el-button-border-color: #000;
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: #000;
  --el-button-hover-border-color: #000;
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: #000;
  --el-button-active-border-color: #000;
}

.data-table-wrap {
  flex: 1;
  min-height: 0;
  padding: 0 1px 1px;
}

:deep(.el-table) {
  --el-table-header-bg-color: #fff;
  --el-table-row-hover-bg-color: #f5f7fa;
  font-size: 13px;
}

:deep(.el-table th.el-table__cell) {
  height: 39px;
  color: #303133;
  font-weight: 400;
}

:deep(.el-table td.el-table__cell) {
  height: 39px;
}

:deep(.el-table .cell) {
  padding: 0 10px;
}
</style>