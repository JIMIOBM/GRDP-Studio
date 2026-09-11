<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import NaturalGasProperties from './NaturalGasProperties.vue'
import FormationWaterProperties from './FormationWaterProperties.vue'
import RockProperties from './RockProperties.vue'
import NaturalGasImportDialog from './NaturalGasImportDialog.vue'
import FormationWaterImportDialog from './FormationWaterImportDialog.vue'
import { getPvtRecord, savePvtCalculation, savePvtImport } from '@/utils/pvtRecords'
import { pvtStorageApi } from '@/api/pvtStorage'

const props = defineProps({
  wellName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  pvtIndex: { type: [Number, String], default: 1 },
  initialRecord: { type: Object, default: null },
  initialPropertyTab: { type: String, default: '天然气性质' }
})

const emit = defineEmits(['property-tab-change', 'saved'])
// 每个性质编号使用独立快照初始化输入与结果，切换旧节点不会读取当前节点的状态
const storedRecord = props.initialRecord || getPvtRecord(
  props.projectId,
  props.gasReservoirId,
  props.wellName,
  props.pvtIndex
)

const createInitialGasRows = () =>
  (storedRecord?.gasRows || []).map(row => [...row])

const createInitialWaterRows = () =>
  (storedRecord?.waterRows || [[25000, 40, 119.85]]).map(row => [...row])

const propertyTabs = [
  { name: '天然气性质', component: NaturalGasProperties },
  { name: '地层水性质', component: FormationWaterProperties },
  { name: '岩石性质', component: RockProperties }
]

const activePropertyTab = ref(
  propertyTabs.some(tab => tab.name === props.initialPropertyTab)
    ? props.initialPropertyTab
    : '天然气性质'
)
const activeGasResultTab = ref('数据列表')
const gasImportKind = computed(() =>
  activeGasResultTab.value === '结果分析图' ? 'result' : 'data'
)
const activeWaterResultTab = ref('数据列表')
const importDialogVisible = ref(false)

const rockImportDialogVisible = ref(false)
const waterImportDialogVisible = ref(false)
const importedGasRows = ref(createInitialGasRows())
const importedGasResultRows = ref(storedRecord?.gasResultRows || [])
const currentGasSettings = ref(storedRecord?.gasSettings || {})
const importedRockRows = ref(storedRecord?.rockRows || [])
const importedRockResultRows = ref(storedRecord?.rockResultRows || [])
const rockCalculationState = ref(storedRecord?.rockState || null)
const saving = ref(false)

const handleGasResultTabChange = (tabName) => {
  activeGasResultTab.value = tabName
}

const handlePropertyTabChange = tabName => {
  activePropertyTab.value = tabName
  emit('property-tab-change', tabName)
}
const importedWaterRows = ref(createInitialWaterRows())
const importedWaterResultRows = ref(storedRecord?.waterResultRows || [])
const currentWaterSettings = ref(storedRecord?.waterSettings || {})
const activeWaterImportKind = computed(
  () => activeWaterResultTab.value === '结果分析图' ? 'result' : 'data'
)

const hasValue = value => value !== null && value !== undefined && String(value).trim() !== ''

const requiredNumber = (value, field) => {
  if (!hasValue(value) || !Number.isFinite(Number(value))) {
    throw new Error(`${field}必须填写有效数字`)
  }
  return Number(value)
}

const optionalNumber = value =>
  hasValue(value) && Number.isFinite(Number(value)) ? Number(value) : null

const firstDataRow = rows =>
  (rows || []).find(row => Array.isArray(row) && row.some(hasValue)) || null

const buildGasSavePayload = section => {
  if (section === 'input') {
    const row = firstDataRow(importedGasRows.value)
    if (!row) throw new Error('天然气数据列表中没有可保存的数据')
    if (!hasValue(row[0])) throw new Error('天然气类型不能为空')
    return {
      gasInput: {
        gasType: String(row[0]).trim(),
        specificGravity: requiredNumber(row[1], '天然气比重'),
        hydrogenSulfide: requiredNumber(row[2], 'H₂S'),
        carbonDioxide: requiredNumber(row[3], 'CO₂'),
        nitrogen: requiredNumber(row[4], 'N₂'),
        condensateOilDensity: optionalNumber(row[5])
      }
    }
  }

  const rows = (importedGasResultRows.value || []).filter(row =>
    row && hasValue(row.pressure) && hasValue(row.temperature)
  )
  if (!rows.length) throw new Error('天然气结果分析图中没有可保存的数据')
  return {
    settings: currentGasSettings.value,
    gasResults: rows.map(row => ({
      pressure: requiredNumber(row.pressure, '压力'),
      temperature: requiredNumber(row.temperature, '温度'),
      deviationFactor: optionalNumber(row.deviationFactor),
      pseudoPressure: optionalNumber(row.pseudoPressure),
      volumeFactor: optionalNumber(row.volumeFactor),
      density: optionalNumber(row.density),
      compressibility: optionalNumber(row.compressibility),
      viscosity: optionalNumber(row.viscosity)
    }))
  }
}

const buildWaterSavePayload = section => {
  if (section === 'input') {
    const row = firstDataRow(importedWaterRows.value)
    if (!row) throw new Error('地层水数据列表中没有可保存的数据')
    return {
      waterInput: {
        salinity: requiredNumber(row[0], '矿化度'),
        formationPressure: requiredNumber(row[1], '地层压力'),
        formationTemperature: requiredNumber(row[2], '地层温度')
      }
    }
  }

  const rows = (importedWaterResultRows.value || []).filter(row =>
    row && hasValue(row.pressure) && hasValue(row.temperature) && hasValue(row.salinity)
  )
  if (!rows.length) throw new Error('地层水结果分析图中没有可保存的数据')
  return {
    settings: currentWaterSettings.value,
    waterResults: rows.map(row => ({
      pressure: requiredNumber(row.pressure, '压力'),
      temperature: requiredNumber(row.temperature, '温度'),
      salinity: requiredNumber(row.salinity, '矿化度'),
      gasSolubility: optionalNumber(row.gasSolubilityInWater ?? row.gasSolubility),
      volumeFactor: optionalNumber(row.volumeFactor),
      density: optionalNumber(row.density),
      isothermalCompressibility: optionalNumber(
        row.isothermalCompressionCoefficient ?? row.isothermalCompressibility
      ),
      viscosity: optionalNumber(row.viscosity)
    }))
  }
}

const buildRockSavePayload = section => {
  const importedRow = firstDataRow(importedRockRows.value)
  const state = rockCalculationState.value
  const porosityValue = importedRow?.[0] ?? state?.porosity
  if (!hasValue(porosityValue)) throw new Error('岩石性质中没有可保存的数据')
  const payload = {
    rockInput: {
      porosity: requiredNumber(porosityValue, '岩石孔隙度'),
      rockType: state?.rockType || '胶结砂岩/碳酸盐岩',
      calculationMethod: state?.calculationMethod || '导入数据'
    },
    settings: state?.settings || {}
  }
  if (section === 'result') {
    const rows = (importedRockResultRows.value || []).filter(row =>
      row && hasValue(row.curveType) && hasValue(row.porosity) && hasValue(row.compressibilityFactor)
    )
    if (!rows.length) throw new Error('岩石结果分析图中没有可保存的数据')
    payload.rockResults = rows.map((row, index) => ({
      curveType: String(row.curveType).trim(),
      pointNo: Number(row.pointNo) > 0 ? Number(row.pointNo) : index + 1,
      porosity: requiredNumber(row.porosity, '岩石孔隙度'),
      compressibilityFactor: requiredNumber(row.compressibilityFactor, '岩石压缩系数')
    }))
  }
  return payload
}

const handleRockCalculation = payload => {
  const { resultRows = [], ...state } = payload || {}
  rockCalculationState.value = state
  importedRockResultRows.value = resultRows
}

const handleRockResultsCleared = () => {
  importedRockResultRows.value = []
  rockCalculationState.value = null
}

const handleSave = async () => {
  if (saving.value) return
  const kindByTab = {
    天然气性质: 'gas',
    地层水性质: 'water',
    岩石性质: 'rock'
  }
  const propertyKind = kindByTab[activePropertyTab.value]
  const resultRows = propertyKind === 'gas'
    ? importedGasResultRows.value
    : propertyKind === 'water'
      ? importedWaterResultRows.value
      : importedRockResultRows.value

  try {
    saving.value = true
    const basePayload = {
      projectId: Number(props.projectId),
      gasReservoirId: Number(props.gasReservoirId),
      wellName: props.wellName,
      pvtNo: Number(props.pvtIndex),
      pvtName: `PVT性质${props.pvtIndex}`,
      propertyKind
    }
    const buildPayload = section => propertyKind === 'gas'
      ? buildGasSavePayload(section)
      : propertyKind === 'water'
        ? buildWaterSavePayload(section)
        : buildRockSavePayload(section)

    // 顶部“保存”代表保存当前性质的完整快照，不能因底部停留在“数据列表”而丢掉已计算结果。
    const inputResponse = await pvtStorageApi.save({
      ...basePayload,
      section: 'input',
      sourceType: 'manual',
      ...buildPayload('input')
    })
    let finalResponse = inputResponse
    let resultSavedRows = 0
    if (Array.isArray(resultRows) && resultRows.length) {
      finalResponse = await pvtStorageApi.save({
        ...basePayload,
        section: 'result',
        sourceType: 'calculation',
        ...buildPayload('result')
      })
      resultSavedRows = finalResponse?.data?.savedRows ?? 0
    }
    const inputSavedRows = inputResponse?.data?.savedRows ?? 0
    emit('saved', {
      pvtId: finalResponse?.data?.pvtId ?? inputResponse?.data?.pvtId,
      pvtNo: Number(props.pvtIndex),
      wellName: props.wellName
    })
    const resultMessage = resultSavedRows ? `，计算结果${resultSavedRows}条` : ''
    ElMessage.success(`${activePropertyTab.value}已保存（数据${inputSavedRows}条${resultMessage}）`)
  } catch (error) {
    ElMessage.error(error?.msg || error?.response?.data?.msg || error.message || 'PVT数据保存失败')
  } finally {
    saving.value = false
  }
}

const handleImport = () => {
  if (activePropertyTab.value === '天然气性质') {
    importDialogVisible.value = true
    return
  }
  if (activePropertyTab.value === '地层水性质') {
    waterImportDialogVisible.value = true
    return
  }
  if (activePropertyTab.value === '岩石性质') {
    rockImportDialogVisible.value = true
    return
  }
  ElMessage.info(`${activePropertyTab.value}导入功能暂未接入`)
}

const GAS_IMPORT_COLUMNS = [
  '天然气类型',
  '天然气比重(dless)',
  'H₂S摩尔百分含量(%)',
  'CO₂摩尔百分含量(%)',
  'N₂摩尔百分含量(%)'
]

const GAS_RESULT_IMPORT_COLUMNS = [
  '压力(MPa)',
  '温度(℃)',
  '天然气偏差系数(dless)',
  '气体拟压力(MPa²/(mPa·s))',
  '天然气体积系数(dless)',
  '天然气密度(kg/m³)',
  '天然气压缩系数(MPa⁻¹)',
  '天然气粘度(mPa·s)'
]

const WATER_RESULT_IMPORT_COLUMNS = [
  '压力(MPa)',
  '温度(℃)',
  '地层水矿化度(mg/L)',
  '天然气在水中的溶解度(dless)',
  '地层水体积系数(dless)',
  '地层水密度(kg/m³)',
  '地层水等温压缩系数(MPa⁻¹)',
  '地层水粘度(mPa·s)'
]

const normalizeHeader = (value) => String(value ?? '')
  .trim()
  .replace(/^\uFEFF/, '')
  .replace(/\s+/g, '')
  .replace(/2/g, '₂')

const isEmptyCell = (value) => value === null
  || value === undefined
  || String(value).trim() === ''

const isZeroCell = (value) => !isEmptyCell(value)
  && Number.isFinite(Number(value))
  && Number(value) === 0

const cleanImportRows = (sourceRows, options, requiredColumns) => {
  // 按弹窗选项清理空行/空列；requiredColumns 可防止必需字段因全为 0 被误删。
  let rows = sourceRows.map(row => [...row])

  if (options.removeEmptyRows) {
    rows = rows.filter(row => row.some(value => !isEmptyCell(value)))
  }

  if (!rows.length) return rows

  const columnCount = Math.max(...rows.map(row => row.length))
  rows = rows.map(row => Array.from(
    { length: columnCount },
    (_, index) => row[index] ?? ''
  ))

  if (options.removeEmptyColumns) {
    const keptIndexes = Array.from({ length: columnCount }, (_, index) => index)
      .filter(index => rows.some(row => !isEmptyCell(row[index])))
    rows = rows.map(row => keptIndexes.map(index => row[index]))
  }

  if (options.removeZeroColumns && rows.length > 1) {
    const headers = rows[0].map(normalizeHeader)
    const requiredHeaders = new Set(requiredColumns.map(normalizeHeader))
    const keptIndexes = headers.map((_, index) => index).filter(index => {
      if (requiredHeaders.has(headers[index])) return true
      const values = rows.slice(1).map(row => row[index])
      return !values.length || !values.every(isZeroCell)
    })
    rows = rows.map(row => keptIndexes.map(index => row[index]))
  }

  if (options.removeZeroRows && rows.length > 1) {
  rows = [
    rows[0],
    ...rows.slice(1).filter(row => {
      const headerRow = rows[0]
      const numericValues = row.filter((value, index) => {
        const header = String(headerRow[index] ?? '').trim()
        const isTextColumn = ['类型', '名称', '单位'].some(keyword =>
          header.includes(keyword)
        )
        if (isTextColumn || isEmptyCell(header)) return false
        return !isEmptyCell(value)
      })
      return !numericValues.length || !numericValues.every(isZeroCell)
    })
  ]
}

  return rows
}

const assertStrictHeaders = (rows, expectedColumns, templateName) => {
  // 两种模板不可混用：列名、列数或顺序任一不一致都会提示格式错误。
  const actualHeaders = rows[0].map(normalizeHeader)
  const expectedHeaders = expectedColumns.map(normalizeHeader)
  const headersMatch = actualHeaders.length === expectedHeaders.length
    && expectedHeaders.every((header, index) => actualHeaders[index] === header)

  if (!headersMatch) {
    throw new Error(
      `${templateName}格式不正确。表头必须严格依次为：${expectedColumns.join('、')}`
    )
  }
}

const parseDataImportRows = (rows, options) => {
  // 一个 PVT 性质只接受一条基础数据，气体类型只能是干气、湿气或凝析气。
  assertStrictHeaders(rows, GAS_IMPORT_COLUMNS, '数据模板')
  const dataRows = rows.slice(1).filter(row =>
    row.some(value => !isEmptyCell(value))
  )
  if (!dataRows.length) throw new Error('数据模板中没有可导入的数据行')
  if (dataRows.length > 1) {
    throw new Error('每次 PVT 性质只能导入 1 条天然气基础数据，请删除多余数据行')
  }

  return dataRows.map((row, rowIndex) => {
    const values = GAS_IMPORT_COLUMNS.map((_, columnIndex) => {
      const value = row[columnIndex] ?? ''
      if (columnIndex > 0 && options.fillEmptyWithZero && isEmptyCell(value)) return 0
      return value
    })
    const gasType = String(values[0] ?? '').trim()
    if (!['干气', '湿气', '凝析气'].includes(gasType)) {
      throw new Error(`数据模板第 ${rowIndex + 2} 行：天然气类型只能为干气、湿气或凝析气`)
    }
    values[0] = gasType
    GAS_IMPORT_COLUMNS.slice(1).forEach((column, numericIndex) => {
      const value = values[numericIndex + 1]
      if (isEmptyCell(value) || !Number.isFinite(Number(value))) {
        throw new Error(`数据模板第 ${rowIndex + 2} 行：${column}必须填写数字`)
      }
      values[numericIndex + 1] = Number(value)
    })
    return values
  })
}

const parseResultImportRows = (rows) => {
  // 结果数据允许多个压力点，但每个字段都必须是有效数值。
  assertStrictHeaders(rows, GAS_RESULT_IMPORT_COLUMNS, '结果数据模板')
  const dataRows = rows.slice(1).filter(row =>
    row.some(value => !isEmptyCell(value))
  )
  if (!dataRows.length) throw new Error('结果数据模板中没有可导入的数据行')

  return dataRows.map((row, rowIndex) => {
    const values = GAS_RESULT_IMPORT_COLUMNS.map((column, columnIndex) => {
      const value = row[columnIndex] ?? ''
      if (isEmptyCell(value) || !Number.isFinite(Number(value))) {
        throw new Error(`结果数据模板第 ${rowIndex + 2} 行：${column}必须填写数字`)
      }
      return Number(value)
    })
    return {
      pressure: values[0],
      temperature: values[1],
      deviationFactor: values[2],
      pseudoPressure: values[3],
      volumeFactor: values[4],
      density: values[5],
      compressibility: values[6],
      viscosity: values[7]
    }
  })
}

const handleGasImport = async ({ file, options, kind }) => {
  // kind 由当前底部页签决定：data 回填数据列表，result 回填结果表和图表。
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) {
      throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    }

    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const firstSheet = workbook.Sheets[workbook.SheetNames[0]]
    const sourceRows = XLSX.utils.sheet_to_json(firstSheet, {
      header: 1,
      raw: true,
      defval: ''
    })
    const expectedColumns = kind === 'result'
      ? GAS_RESULT_IMPORT_COLUMNS
      : GAS_IMPORT_COLUMNS
    const rows = cleanImportRows(sourceRows, options, expectedColumns)

    if (!rows.length) throw new Error('文件中没有可导入的数据')

    if (kind === 'result') {
      const parsedResultRows = parseResultImportRows(rows)
      importedGasResultRows.value = parsedResultRows
      persistImportedData('gas', 'result', parsedResultRows)
      ElMessage.success(`成功导入 ${parsedResultRows.length} 条天然气结果数据`)
      return
    }

    const parsedRows = parseDataImportRows(rows, options)
    importedGasRows.value = parsedRows
    persistImportedData('gas', 'data', parsedRows)
    ElMessage.success(`成功导入 ${parsedRows.length} 条天然气性质数据`)
  } catch (error) {
    ElMessage.error(error.message || '天然气性质数据导入失败')
  }
}

const ROCK_IMPORT_COLUMNS = [
  '岩石孔隙度（%）'
]

const handleRockImport = async ({ file, options }) => {
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) {
      throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    }


const XLSX = await import('xlsx')
const buffer = await file.arrayBuffer()

const workbook = XLSX.read(buffer, {
  type: 'array'
})
const firstSheet = workbook.Sheets[workbook.SheetNames[0]]
    const sourceRows = XLSX.utils.sheet_to_json(firstSheet, {
      header: 1,
      raw: true,
      defval: ''
    })

    const rows = cleanImportRows(sourceRows, options, ROCK_IMPORT_COLUMNS)

    if (!rows.length) throw new Error('文件中没有可导入的数据')

    const headers = rows[0].map(normalizeHeader)

    const columnIndexes = ROCK_IMPORT_COLUMNS.map(column =>
      headers.indexOf(normalizeHeader(column))
    )

    console.log('列索引匹配结果:', columnIndexes)

    const missingColumns = ROCK_IMPORT_COLUMNS.filter((_, index) => columnIndexes[index] < 0)

    if (missingColumns.length) {
      console.error('匹配失败的列:', missingColumns)
      throw new Error(`缺少字段：${missingColumns.join('、')}（请检查CSV表头是否为：${ROCK_IMPORT_COLUMNS.join('、')}）`)
    }

    const parsedRows = rows.slice(1).map(row =>
      columnIndexes.map((index, columnIndex) => {
        const value = row[index] ?? ''
        if (options.fillEmptyWithZero && isEmptyCell(value)) return 0
        return value
      })
    )

    if (!parsedRows.length || !parsedRows.some(row => row.some(value => !isEmptyCell(value)))) {
      throw new Error('文件中没有可导入的数据行')
    }

    if (parsedRows.length > 1) {
  throw new Error('每次岩石性质只能导入1条基础数据，请删除多余数据行')
}

    importedRockRows.value = parsedRows.slice(0, 27)
    // 新导入孔隙度后，旧曲线已经失效，必须重新计算后才能保存结果。
    importedRockResultRows.value = []
    rockCalculationState.value = null
    ElMessage.success(`成功导入 ${importedRockRows.value.length} 条岩石性质数据`)
  } catch (error) {
    console.error('岩石导入错误详情:', error)
    ElMessage.error(error.message || '岩石性质数据导入失败')
  }
}

const parseWaterResultImportRows = (rows) => {
  assertStrictHeaders(rows, WATER_RESULT_IMPORT_COLUMNS, '地层水结果数据模板')
  const dataRows = rows.slice(1).filter(row =>
    row.some(value => !isEmptyCell(value))
  )
  if (!dataRows.length) throw new Error('地层水结果数据模板中没有可导入的数据行')

  return dataRows.map((row, rowIndex) => {
    const values = WATER_RESULT_IMPORT_COLUMNS.map((column, columnIndex) => {
      const value = row[columnIndex] ?? ''
      if (isEmptyCell(value) || !Number.isFinite(Number(value))) {
        throw new Error(`地层水结果数据模板第 ${rowIndex + 2} 行：${column}必须填写数字`)
      }
      return Number(value)
    })
    return {
      pressure: values[0],
      temperature: values[1],
      salinity: values[2],
      gasSolubilityInWater: values[3],
      volumeFactor: values[4],
      density: values[5],
      isothermalCompressionCoefficient: values[6],
      viscosity: values[7]
    }
  })
}

const handleWaterImport = async ({ file, options, kind }) => {
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) {
      throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    }

    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const firstSheet = workbook.Sheets[workbook.SheetNames[0]]
    const sourceRows = XLSX.utils.sheet_to_json(firstSheet, {
      header: 1,
      raw: true,
      defval: ''
    })
    const expectedColumns = kind === 'result'
      ? WATER_RESULT_IMPORT_COLUMNS
      : GAS_IMPORT_COLUMNS
    const rows = cleanImportRows(sourceRows, options, expectedColumns)
    if (!rows.length) throw new Error('文件中没有可导入的数据')

    if (kind === 'data') {
      // 地层水数据列表使用当前编号的天然气五列基础数据。
      const parsedRows = parseDataImportRows(rows, options)
      importedGasRows.value = parsedRows
      persistImportedData('gas', 'data', parsedRows)
      ElMessage.success(`成功导入 ${parsedRows.length} 条地层水基础数据`)
      return
    }

    const parsedResultRows = parseWaterResultImportRows(rows)
    importedWaterResultRows.value = parsedResultRows
    persistImportedData('water', 'result', parsedResultRows)
    ElMessage.success(`成功导入 ${parsedResultRows.length} 条地层水结果数据`)
  } catch (error) {
    ElMessage.error(error.message || '地层水性质数据导入失败')
  }
}

const persistImportedData = (kind, importKind, rows) => {
  const record = savePvtImport({
    projectId: props.projectId,
    gasReservoirId: props.gasReservoirId,
    wellName: props.wellName,
    currentIndex: props.pvtIndex,
    kind,
    importKind,
    rows
  })
  return record
}

const persistCalculation = (kind, payload) => {
  // 参数变化后的计算结果直接覆盖当前编号
  const { record } = savePvtCalculation({
    projectId: props.projectId,
    gasReservoirId: props.gasReservoirId,
    wellName: props.wellName,
    currentIndex: props.pvtIndex,
    kind,
    inputRows: payload.inputRows,
    resultRows: payload.resultRows,
    settings: payload.settings
  })

  // 保存函数返回记录包装对象，必须读取 record 才能用本次输入刷新表单。
  if (kind === 'gas') {
    importedGasRows.value = record?.gasRows || []
    importedGasResultRows.value = record?.gasResultRows || []
    currentGasSettings.value = record?.gasSettings || {}
  } else {
    importedWaterRows.value = record?.waterRows || [[25000, 40, 119.85]]
    importedWaterResultRows.value = record?.waterResultRows || []
    currentWaterSettings.value = record?.waterSettings || {}
  }
}
</script>

<template>
  <section class="pvt-properties">
    <header class="pvt-toolbar">
      <nav class="property-tabs" aria-label="PVT 性质分类">
        <button v-for="tab in propertyTabs" :key="tab.name" type="button" class="property-tab"
          :class="{ active: activePropertyTab === tab.name }" @click="handlePropertyTabChange(tab.name)">
          {{ tab.name }}
        </button>
      </nav>

      <div class="toolbar-actions">
        <button type="button" class="toolbar-button" :disabled="saving" @click="handleSave">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button type="button" class="toolbar-button" @click="handleImport">导入</button>
      </div>
    </header>

    <NaturalGasProperties
      v-if="activePropertyTab === '天然气性质'"
      :imported-rows="importedGasRows"
      :imported-result-rows="importedGasResultRows"
      :project-id="projectId"
      :initial-settings="currentGasSettings"
      @result-tab-change="handleGasResultTabChange"
      @calculated="persistCalculation('gas', $event)"
    />
    <FormationWaterProperties
      v-else-if="activePropertyTab === '地层水性质'"
      :well-name="wellName"
      :project-id="projectId"
      :gas-rows="importedGasRows"
      :imported-rows="importedWaterRows"
      :imported-result-rows="importedWaterResultRows"
      :initial-settings="currentWaterSettings"
      @result-tab-change="activeWaterResultTab = $event"
      @calculated="persistCalculation('water', $event)"
    />
    <RockProperties
      v-else
      :imported-rows="importedRockRows"
      :imported-result-rows="importedRockResultRows"
      :initial-state="rockCalculationState"
      :project-id="projectId"
      @calculation-complete="handleRockCalculation"
      @results-cleared="handleRockResultsCleared"
    />

    <NaturalGasImportDialog
      v-model="importDialogVisible"
      :import-kind="gasImportKind"
      @confirm="handleGasImport"
    />

    <RockImportDialog v-model="rockImportDialogVisible" @confirm="handleRockImport" />
    <FormationWaterImportDialog
      v-model="waterImportDialogVisible"
      :import-kind="activeWaterImportKind"
      @confirm="handleWaterImport"
    />
  </section>
</template>

<style lang="scss" scoped>
.pvt-properties {
  width: 100%;
  height: 100%;
  min-width: 720px;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 14px;
}

.pvt-toolbar {
  height: 48px;
  flex: 0 0 48px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 7px 11px 0;
  box-sizing: border-box;
}

.property-tabs {
  display: flex;
  align-items: stretch;
}

.property-tab {
  min-width: 94px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid #222;
  border-right: 0;
  background: #fff;
  color: #222;
  font: inherit;
  cursor: pointer;

  &:last-child {
    border-right: 1px solid #222;
  }

  &.active {
    background: #050505;
    color: #fff;
    font-weight: 700;
  }

  &:focus-visible {
    outline: 2px solid #2f74c0;
    outline-offset: 2px;
  }
}

.toolbar-actions {
  display: flex;
  gap: 9px;
}

.toolbar-button {
  min-width: 70px;
  height: 30px;
  padding: 0 17px;
  border: 1px solid #111;
  border-radius: 6px;
  background: #fff;
  color: #111;
  font: inherit;
  cursor: pointer;

  &:hover {
    background: #f5f5f5;
  }

  &:active {
    background: #ebebeb;
  }
}
</style>
