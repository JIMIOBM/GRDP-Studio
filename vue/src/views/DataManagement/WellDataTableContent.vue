<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { dataManagementApi } from '@/api/docker'

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
      'productivityWellTestType',
      'testPointNumber',
      'reserviorPressure',
      'testDailyGasProduction',
      'testBottomHoleFlowingPressure'
    ]
  },
  staticPressure: {
    title: '静压数据',
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
      'reserviorPressure',
      'cumulativeGasProduction',
      'cumulativeWaterProduction'
    ]
  }
}

const FALLBACK_FIELDS = {
  wellName: { name_cn: '井名', unit_label: '' },
  xCoordinate: { name_cn: '横坐标', unit_label: 'm' },
  yCoordinate: { name_cn: '纵坐标', unit_label: 'm' },
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
  equivalentCumulativeGasProduction: { name_cn: '折算累产气量', unit_label: '10⁸m³', displayDecimal: 4 }
}

const loading = ref(false)
const importing = ref(false)
const importFileInput = ref(null)
const rows = ref([])
const responseFields = ref([])
let loadSequence = 0

const config = computed(() => DATA_CONFIG[props.dataType] || DATA_CONFIG.wellhead)
const isImportableData = computed(() =>
  props.dataType === 'deliverability' || props.dataType === 'staticPressure'
)
const tabTitle = computed(() => `${props.wellName} ${config.value.title}`.trim())

const fields = computed(() => {
  const fieldMap = new Map(
    responseFields.value
      .filter(field => field?.name)
      .map(field => [field.name, field])
  )

  return config.value.keys
    .filter(key => key !== 'wellName' || !props.wellName)
    .map(key => ({
    key,
    ...(FALLBACK_FIELDS[key] || { name_cn: key, unit_label: '' }),
    ...(fieldMap.get(key) || {}),
    ...(config.value.fields?.[key] || {})
    }))
})

const getFieldLabel = (field) => {
  const name = field.name_cn || field.key
  const unit = field.unit_label
  return unit ? `${name}(${unit})` : name
}

const formatCellValue = (row, _column, value, _index, field) => {
  if (value === undefined || value === null || value === '') return ''
  if (field.key === 'date' || field.key === 'productivityWellTestDate') {
    return String(value).slice(0, 10).replaceAll('-', '/')
  }
  return value
}

const normalizeHeader = (value) =>
  String(value ?? '')
    .trim()
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .toLowerCase()

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

const parseImportWorkbook = async (file) => {
  const XLSX = await import('xlsx')
  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true })
  const worksheet = workbook.Sheets[workbook.SheetNames[0]]
  if (!worksheet) throw new Error('Excel 中没有可读取的工作表')

  const table = XLSX.utils.sheet_to_json(worksheet, {
    header: 1,
    raw: true,
    defval: ''
  })
  const importFields = config.value.importFields || []
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
  const dataRows = table.slice(headerIndex + 1).flatMap((sourceRow, offset) => {
    const row = {}
    columnFields.forEach((field, columnIndex) => {
      if (!field) return
      const sourceValue = sourceRow[columnIndex]
      row[field.key] = field.date ? normalizeExcelDate(XLSX, sourceValue) : sourceValue
    })

    if (Object.values(row).every(value => value === '' || value === null || value === undefined)) {
      return []
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

  const includedFields = importFields.filter(
    field => foundKeys.has(field.key) || (field.key === 'wellName' && props.wellName)
  )
  const normalizedRows = [
    includedFields.map(field => field.name),
    ...dataRows.map(row => includedFields.map(field => row[field.key] ?? ''))
  ]
  const normalizedSheet = XLSX.utils.aoa_to_sheet(normalizedRows)
  const normalizedWorkbook = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(normalizedWorkbook, normalizedSheet, config.value.title)
  const buffer = XLSX.write(normalizedWorkbook, { bookType: 'xlsx', type: 'array' })
  const normalizedFile = new File(
    [buffer],
    `${config.value.title}-${Date.now()}.xlsx`,
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
  importFileInput.value?.click()
}

const importExcel = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return

  importing.value = true
  try {
    const parsed = await parseImportWorkbook(file)
    const uploadResponse = await dataManagementApi.uploadExcel(parsed.file)
    const filePath =
      uploadResponse?.data?.filepath ||
      uploadResponse?.data?.data?.filepath ||
      uploadResponse?.filepath
    if (!filePath) throw new Error('文件上传成功，但后端没有返回文件路径')

    await dataManagementApi.importCoreData(
      props.gasReservoirId,
      config.value.schema,
      filePath,
      parsed.mappings
    )
    ElMessage.success(`成功导入 ${parsed.rowCount} 条${config.value.title}`)
    await loadData()
  } catch (error) {
    ElMessage.error(
      error.response?.data?.message ||
      error.response?.data?.msg ||
      error.message ||
      `${config.value.title}导入失败`
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
    if (props.dataType === 'wellhead') {
      response = await dataManagementApi.getWellHead(props.projectId, props.gasReservoirId)
    } else if (props.dataType === 'deviation') {
      response = await dataManagementApi.getWellDeviation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    } else if (props.dataType === 'logging') {
      response = await dataManagementApi.getLogInterpretation(
        props.projectId,
        props.gasReservoirId,
        props.wellName
      )
    } else if (props.dataType === 'deliverability') {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames
      const responses = await Promise.all(
        targetWellNames.map(wellName =>
          dataManagementApi.getDeliverabilityTest(
            props.projectId,
            props.gasReservoirId,
            wellName
          )
        )
      )
      if (sequence !== loadSequence) return
      const results = responses.map(getItems)
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    } else {
      const targetWellNames = props.wellName ? [props.wellName] : props.wellNames
      const responses = await Promise.all(
        targetWellNames.map(wellName =>
          dataManagementApi.getStaticPressure(
            props.projectId,
            props.gasReservoirId,
            wellName
          )
        )
      )
      if (sequence !== loadSequence) return
      const results = responses.map(getItems)
      rows.value = results.flatMap(result => result.items)
      responseFields.value = results.find(result => result.fields.length)?.fields || []
      return
    }

    if (sequence !== loadSequence) return

    const result = getItems(response)

    rows.value = props.dataType === 'wellhead'
      ? result.items.filter(item => String(item?.wellName) === String(props.wellName))
      : result.items
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
  () => [props.dataType, props.wellName, props.wellNames, props.projectId, props.gasReservoirId],
  loadData,
  { immediate: true }
)
</script>

<template>
  <section class="well-data-content">
    <div class="data-tabs">
      <div class="data-tab active">{{ tabTitle }}</div>
    </div>

    <div class="data-toolbar">
      <el-button size="small" :loading="importing" @click="chooseImportFile">导入</el-button>
      <input
        ref="importFileInput"
        class="hidden-file-input"
        type="file"
        accept=".xlsx,.xls"
        @change="importExcel"
      />
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
          :prop="field.key"
          :label="getFieldLabel(field)"
          :formatter="(row, column, value, index) => formatCellValue(row, column, value, index, field)"
          min-width="140"
          show-overflow-tooltip
        />
      </el-table>
    </div>
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

.data-toolbar {
  display: flex;
  flex: 0 0 39px;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid #ebeef5;
}

.hidden-file-input {
  display: none;
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
