<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import NaturalGasProperties from './NaturalGasProperties.vue'
import FormationWaterProperties from './FormationWaterProperties.vue'
import RockProperties from './RockProperties.vue'
import NaturalGasImportDialog from './NaturalGasImportDialog.vue'
import RockImportDialog from './RockImportDialog.vue'

const props = defineProps({
  wellName: { type: String, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  pvtIndex: { type: [Number, String], required: true }
})

// 当前阶段使用静态挂载：每口井的 PVT 性质 1、2 各自只有一条基础气体数据。
const STATIC_PVT_GAS_ROWS = {
  1: [['干气', 0.58, 0.02, 1.2, 0.8]],
  2: [['湿气', 0.65, 0.03, 1.5, 0.9]]
}

const createInitialGasRows = () =>
  (STATIC_PVT_GAS_ROWS[Number(props.pvtIndex)] || []).map(row => [...row])

const propertyTabs = [
  { name: '天然气性质', component: NaturalGasProperties },
  { name: '地层水性质', component: FormationWaterProperties },
  { name: '岩石性质', component: RockProperties }
]

const activePropertyTab = ref('天然气性质')
const activeGasResultTab = ref('数据列表')
const gasImportKind = computed(() =>
  activeGasResultTab.value === '结果分析图' ? 'result' : 'data'
)
const importDialogVisible = ref(false)

const rockImportDialogVisible = ref(false)        // ← 新增
const importedGasRows = ref(createInitialGasRows())
const importedGasResultRows = ref([])
const importedRockRows = ref([])

const handleGasResultTabChange = (tabName) => {
  activeGasResultTab.value = tabName
}

const handleSave = () => {
  ElMessage.success(`${activePropertyTab.value}参数已保存`)
}

const handleImport = () => {
  if (activePropertyTab.value === '天然气性质') {
    importDialogVisible.value = true
    return
  }
  if (activePropertyTab.value === '岩石性质') {
    rockImportDialogVisible.value = true
    return
  }
  ElMessage.info(`${activePropertyTab.value}导入功能暂未接入`)
}

// “数据列表”模板只描述气体类型与组成，不包含计算结果。
const GAS_IMPORT_COLUMNS = [
  '天然气类型',
  '天然气比重(dless)',
  'H₂S摩尔百分含量(%)',
  'CO₂摩尔百分含量(%)',
  'N₂摩尔百分含量(%)'
]

// “结果分析图”模板包含压力、温度及曲线 1～4 的全部 Y 轴字段。
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
      ElMessage.success(`成功导入 ${parsedResultRows.length} 条天然气结果数据`)
      return
    }

    const parsedRows = parseDataImportRows(rows, options)
    importedGasRows.value = parsedRows
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

    importedRockRows.value = parsedRows.slice(0, 27)
    ElMessage.success(`成功导入 ${importedRockRows.value.length} 条岩石性质数据`)
  } catch (error) {
    console.error('岩石导入错误详情:', error)
    ElMessage.error(error.message || '岩石性质数据导入失败')
  }
}
</script>

<template>
  <section class="pvt-properties">
    <header class="pvt-toolbar">
      <nav class="property-tabs" aria-label="PVT 性质分类">
        <button v-for="tab in propertyTabs" :key="tab.name" type="button" class="property-tab"
          :class="{ active: activePropertyTab === tab.name }" @click="activePropertyTab = tab.name">
          {{ tab.name }}
        </button>
      </nav>

      <div class="toolbar-actions">
        <button type="button" class="toolbar-button" @click="handleSave">保存</button>
        <button type="button" class="toolbar-button" @click="handleImport">导入</button>
      </div>
    </header>

    <NaturalGasProperties
      v-if="activePropertyTab === '天然气性质'"
      :imported-rows="importedGasRows"
      :imported-result-rows="importedGasResultRows"
      :project-id="projectId"
      @result-tab-change="handleGasResultTabChange"
    />
    <FormationWaterProperties v-else-if="activePropertyTab === '地层水性质'" :well-name="wellName" :project-id="projectId" />
    <RockProperties v-else-if="activePropertyTab === '岩石性质'" :imported-rows="importedRockRows"
      :project-id="projectId" />

    <NaturalGasImportDialog
      v-model="importDialogVisible"
      :import-kind="gasImportKind"
      @confirm="handleGasImport"
    />

    <RockImportDialog v-model="rockImportDialogVisible" @confirm="handleRockImport" />
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
