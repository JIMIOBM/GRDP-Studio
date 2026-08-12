<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import WellDataImportDialog from './WellDataImportDialog.vue'
import {
  getRelativePermeabilityRecord,
  saveRelativePermeabilityRecord
} from '@/utils/relativePermeabilityRecords'

const props = defineProps({
  wellName: { type: String, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true },
  relativePermeabilityIndex: { type: [Number, String], default: 1 }
})
const emit = defineEmits(['imported'])

const TABLE_COLUMNS = [
  { key: 'waterSaturation', label: '含水饱和度', aliases: ['含水饱和度', '含水饱和度(%)', 'Sw', 'waterSaturation'] },
  {
    key: 'gasRelativePermeability',
    label: '气相/油相相对渗透率',
    aliases: ['气相/油相相对渗透率', '气相相对渗透率', '油相相对渗透率', 'Krg', 'Kro', 'gasRelativePermeability']
  },
  { key: 'waterRelativePermeability', label: '水相相对渗透率', aliases: ['水相相对渗透率', '水相相对渗透率(dless)', 'Krw', 'waterRelativePermeability'] }
]

const activeCurve = ref('曲线1')
const rows = ref([])
const importing = ref(false)
const importDialogVisible = ref(false)
const chartEl = ref(null)
let chart = null
let chartFrame = null

const pageTitle = computed(() =>
  `${props.wellName} 相渗数据${props.relativePermeabilityIndex || 1}`.trim()
)

const dataTemplateRows = computed(() => [TABLE_COLUMNS.map(column => column.label)])
const dataTemplateFileName = computed(() =>
  `${props.wellName}-相渗数据${props.relativePermeabilityIndex || 1}-导入模板.csv`
)

const visibleRows = computed(() => {
  const rowCount = Math.max(27, rows.value.length)
  return Array.from({ length: rowCount }, (_, index) => rows.value[index] || null)
})

const normalizeHeader = value =>
  String(value ?? '')
    .trim()
    .replace(/^\uFEFF/, '')
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .toLowerCase()

const validateNumber = (value, rowNumber, column) => {
  if (value === '' || value === null || value === undefined || !Number.isFinite(Number(value))) {
    throw new Error(`第 ${rowNumber} 行${column}必须填写数字`)
  }
  return Number(value)
}

const isEmptyCell = value =>
  value === null || value === undefined || String(value).trim() === ''

const isZeroCell = value =>
  !isEmptyCell(value) && Number.isFinite(Number(value)) && Number(value) === 0

const cleanImportTable = (sourceRows, options = {}) => {
  let table = sourceRows.map(row => [...row])
  if (options.removeEmptyRows) {
    table = table.filter(row => row.some(value => !isEmptyCell(value)))
  }
  if (!table.length) return table

  const columnCount = Math.max(...table.map(row => row.length))
  table = table.map(row =>
    Array.from({ length: columnCount }, (_, index) => row[index] ?? '')
  )
  if (options.removeEmptyColumns) {
    const keptIndexes = Array.from({ length: columnCount }, (_, index) => index)
      .filter(index => table.some(row => !isEmptyCell(row[index])))
    table = table.map(row => keptIndexes.map(index => row[index]))
  }
  return table
}

const parseWorkbook = async (file, options = {}) => {
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['xlsx', 'xls', 'csv'].includes(extension)) {
    throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
  }

  const XLSX = await import('xlsx')
  const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
  const worksheet = workbook.Sheets[workbook.SheetNames[0]]
  if (!worksheet) throw new Error('文件中没有可读取的工作表')

  const table = cleanImportTable(
    XLSX.utils.sheet_to_json(worksheet, {
      header: 1,
      raw: true,
      defval: ''
    }),
    options
  )
  if (!table.length) throw new Error('文件中没有可导入的数据')

  const aliasMap = new Map()
  TABLE_COLUMNS.forEach(column => {
    column.aliases.forEach(alias => aliasMap.set(normalizeHeader(alias), column.key))
  })

  let headerIndex = -1
  let columnIndexes = {}
  for (let index = 0; index < Math.min(table.length, 10); index += 1) {
    const indexes = {}
    table[index].forEach((value, columnIndex) => {
      const key = aliasMap.get(normalizeHeader(value))
      if (key && indexes[key] === undefined) indexes[key] = columnIndex
    })
    if (TABLE_COLUMNS.every(column => indexes[column.key] !== undefined)) {
      headerIndex = index
      columnIndexes = indexes
      break
    }
  }
  if (headerIndex < 0) {
    throw new Error(`表头必须包含：${TABLE_COLUMNS.map(column => column.label).join('、')}`)
  }

  const parsedRows = table.slice(headerIndex + 1).flatMap((sourceRow, offset) => {
    const values = TABLE_COLUMNS.map(column => {
      const value = sourceRow[columnIndexes[column.key]]
      return options.fillEmptyWithZero && isEmptyCell(value) ? 0 : value
    })
    if (values.every(value => value === '' || value === null || value === undefined)) return []
    if (options.removeZeroRows && values.every(isZeroCell)) return []

    const rowNumber = headerIndex + offset + 2
    return [{
      waterSaturation: validateNumber(values[0], rowNumber, '含水饱和度'),
      gasRelativePermeability: validateNumber(values[1], rowNumber, '气相/油相相对渗透率'),
      waterRelativePermeability: validateNumber(values[2], rowNumber, '水相相对渗透率')
    }]
  })

  if (!parsedRows.length) throw new Error('文件中没有有效数据行')
  return parsedRows.sort((left, right) => left.waterSaturation - right.waterSaturation)
}

const chooseImportFile = () => {
  if (!importing.value) importDialogVisible.value = true
}

const loadStoredRows = () => {
  const record = getRelativePermeabilityRecord(
    props.projectId,
    props.gasReservoirId,
    props.wellName,
    props.relativePermeabilityIndex
  )
  rows.value = Array.isArray(record?.rows) ? record.rows : []
}

const handleImport = async ({ file, options }) => {
  if (!file) return

  importing.value = true
  try {
    rows.value = await parseWorkbook(file, options)
    const record = saveRelativePermeabilityRecord({
      projectId: props.projectId,
      gasReservoirId: props.gasReservoirId,
      wellName: props.wellName,
      index: props.relativePermeabilityIndex,
      rows: rows.value
    })
    emit('imported', record)
    ElMessage.success(`成功导入 ${rows.value.length} 条相渗数据`)
  } catch (error) {
    ElMessage.error(error.message || '相渗数据导入失败')
  } finally {
    importing.value = false
  }
}

const disposeChart = () => {
  chart?.dispose()
  chart = null
}

const renderChart = () => {
  const element = chartEl.value
  if (!element || element.clientWidth <= 0 || element.clientHeight <= 0) {
    return
  }
  if (!chart || chart.getDom() !== element) {
    disposeChart()
    chart = echarts.init(element)
  }

  chart.setOption({
    animation: false,
    color: [activeCurve.value === '曲线1' ? '#1677ff' : '#e5484d'],
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: params => {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ''
        return [
          `含水饱和度：${Number(items[0].value[0]).toFixed(2)} %`,
          ...items.map(item => `${item.marker}${item.seriesName}：${Number(item.value[1]).toFixed(6)}`)
        ].join('<br/>')
      }
    },
    grid: { left: 84, right: 42, top: 28, bottom: 66 },
    xAxis: {
      type: 'value',
      name: '含水饱和度 Sw(%)',
      nameLocation: 'middle',
      nameGap: 36,
      min: 0,
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      name: '气相/油相相对渗透率、水相相对渗透率',
      nameLocation: 'middle',
      nameGap: 52,
      min: 0,
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    series: [{
      name: activeCurve.value === '曲线1' ? '气相/油相相对渗透率' : '水相相对渗透率',
      type: 'line',
      showSymbol: true,
      symbolSize: 5,
      data: rows.value.map(row => [
        row.waterSaturation,
        activeCurve.value === '曲线1'
          ? row.gasRelativePermeability
          : row.waterRelativePermeability
      ])
    }]
  }, true)
  chart.resize()
}

const scheduleChart = async () => {
  await nextTick()
  if (chartFrame !== null) cancelAnimationFrame(chartFrame)
  chartFrame = requestAnimationFrame(() => {
    chartFrame = null
    renderChart()
  })
}

watch([activeCurve, rows], scheduleChart, { deep: true })
watch(
  () => [
    props.projectId,
    props.gasReservoirId,
    props.wellName,
    props.relativePermeabilityIndex
  ],
  loadStoredRows,
  { immediate: true }
)

const handleResize = () => {
  chart?.resize()
}
window.addEventListener('resize', handleResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartFrame !== null) cancelAnimationFrame(chartFrame)
  disposeChart()
})
</script>

<template>
  <section class="relative-permeability">
    <header class="relative-toolbar">
      <div class="relative-title">{{ pageTitle }}</div>
    </header>

    <div class="relative-workspace">
      <aside class="relative-data-panel">
        <div class="data-actions">
          <el-button
            size="small"
            class="import-button"
            :loading="importing"
            @click="chooseImportFile"
          >
            导入
          </el-button>
        </div>

        <div class="relative-table-wrap">
          <table class="relative-table">
            <thead>
              <tr>
                <th class="index-column"></th>
                <th v-for="column in TABLE_COLUMNS" :key="column.key">{{ column.label }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in visibleRows" :key="index">
                <td class="index-column">{{ String(index + 1).padStart(2, '0') }}</td>
                <td>{{ row?.waterSaturation ?? '' }}</td>
                <td>{{ row?.gasRelativePermeability ?? '' }}</td>
                <td>{{ row?.waterRelativePermeability ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </aside>

      <section class="relative-chart-panel">
        <div class="curve-selector" role="radiogroup" aria-label="相渗曲线">
          <label>
            <input v-model="activeCurve" type="radio" value="曲线1" />
            <span>曲线 1</span>
          </label>
          <label>
            <input v-model="activeCurve" type="radio" value="曲线2" />
            <span>曲线 2</span>
          </label>
        </div>
        <div class="relative-chart-wrap">
          <div ref="chartEl" class="relative-chart"></div>
          <div v-if="!rows.length" class="chart-empty">暂无相渗数据</div>
        </div>
      </section>
    </div>

    <WellDataImportDialog
      v-model="importDialogVisible"
      :data-template-rows="dataTemplateRows"
      :data-template-file-name="dataTemplateFileName"
      @confirm="handleImport"
    />
  </section>
</template>

<style lang="scss" scoped>
.relative-permeability {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 720px;
  min-height: 420px;
  overflow: hidden;
  background: #fff;
  color: #252525;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 14px;
}

.relative-toolbar {
  display: flex;
  flex: 0 0 48px;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  padding: 0 11px;
  border-bottom: 1px solid #dcdfe6;
}

.relative-title {
  font-weight: 700;
}

.relative-workspace {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(520px, 46%) minmax(420px, 1fr);
  min-height: 0;
}

.relative-data-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border-right: 1px solid #dcdfe6;
}

.data-actions {
  display: flex;
  flex: 0 0 39px;
  align-items: center;
  padding: 0 10px;
  border-bottom: 1px solid #ebeef5;
}

.import-button {
  --el-button-bg-color: #000;
  --el-button-border-color: #000;
  --el-button-text-color: #fff;
  --el-button-hover-bg-color: #000;
  --el-button-hover-border-color: #000;
  --el-button-hover-text-color: #fff;
  --el-button-active-bg-color: #000;
  --el-button-active-border-color: #000;
}

.relative-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.relative-table {
  width: 100%;
  min-width: 520px;
  border-collapse: collapse;
  table-layout: fixed;

  th,
  td {
    height: 32px;
    box-sizing: border-box;
    border-right: 1px solid #d9dee7;
    border-bottom: 1px solid #d9dee7;
    padding: 0 10px;
    text-align: center;
  }

  th {
    position: sticky;
    top: 0;
    z-index: 1;
    background: #f4f6f9;
    color: #303133;
    font-weight: 600;
  }

  td {
    color: #3f4650;
  }

  tbody tr:hover td {
    background: #f6faff;
  }
}

.index-column {
  width: 58px;
  background: #fafafa;
  color: #606266;
}

.relative-chart-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.curve-selector {
  display: flex;
  flex: 0 0 46px;
  align-items: center;
  gap: 16px;
  padding: 0 20px;

  label {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    cursor: pointer;
  }
}

.relative-chart-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
}

.relative-chart {
  width: 100%;
  height: 100%;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  pointer-events: none;
}

@media (max-width: 1100px) {
  .relative-workspace {
    grid-template-columns: minmax(480px, 46%) minmax(360px, 1fr);
  }
}
</style>
