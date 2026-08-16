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
    label: '气相相对渗透率',
    aliases: ['气相相对渗透率', '气相相对渗透率(dless)', 'Krg', 'gasRelativePermeability']
  },
  {
    key: 'oilRelativePermeability',
    label: '油相相对渗透率',
    aliases: ['油相相对渗透率', '油相相对渗透率(dless)', 'Kro', 'oilRelativePermeability']
  },
  { key: 'waterRelativePermeability', label: '水相相对渗透率', aliases: ['水相相对渗透率', '水相相对渗透率(dless)', 'Krw', 'waterRelativePermeability'] }
]

const activeCurve = ref('曲线1')
const analysisTableCollapsed = ref(false)
const rows = ref([])
const importing = ref(false)
const importDialogVisible = ref(false)
const chartEl = ref(null)
let chart = null
let chartFrame = null
let chartResizeTimer = null

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

const visibleTableColumns = computed(() => [
  TABLE_COLUMNS[0],
  activeCurve.value === '曲线1' ? TABLE_COLUMNS[1] : TABLE_COLUMNS[2],
  TABLE_COLUMNS[3]
])

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
      gasRelativePermeability: validateNumber(values[1], rowNumber, '气相相对渗透率'),
      oilRelativePermeability: validateNumber(values[2], rowNumber, '油相相对渗透率'),
      waterRelativePermeability: validateNumber(values[3], rowNumber, '水相相对渗透率')
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
  rows.value = Array.isArray(record?.rows)
    ? record.rows.map(row => ({
        ...row,
        // 兼容旧版三列表格；重新导入四列数据后将使用独立油相数据。
        oilRelativePermeability: row.oilRelativePermeability ?? row.gasRelativePermeability
      }))
    : []
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
  const hasChartData = rows.value.length > 0
  const curveColor = '#1677ff'

  chart.setOption({
    animation: false,
    color: [curveColor, curveColor],
    title: {
      text: activeCurve.value === '曲线1'
        ? '气相与水相相对渗透率随含水饱和度变化曲线'
        : '油相与水相相对渗透率随含水饱和度变化曲线',
      left: 'center',
      top: 8,
      textStyle: { fontSize: 14, fontWeight: 600, color: '#333' }
    },
    legend: {
      show: hasChartData,
      type: 'scroll',
      top: 30,
      left: 62,
      right: 92,
      data: [
        activeCurve.value === '曲线1' ? '气相相对渗透率' : '油相相对渗透率',
        '水相相对渗透率'
      ]
    },
    grid: { left: 62, right: 92, top: hasChartData ? 70 : 44, bottom: 56 },
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: '#d936d0', type: 'solid', width: 1 }
      },
      formatter: params => {
        const items = Array.isArray(params) ? params : [params]
        if (!items.length) return ''
        return [
          `含水饱和度：${Number(items[0].value[0]).toFixed(2)} %`,
          ...items.map(item => `${item.marker}${item.seriesName}：${Number(item.value[1]).toFixed(6)}`)
        ].join('<br/>')
      }
    },
    xAxis: {
      type: 'value',
      name: '含水饱和度 Sw(%)',
      nameLocation: 'middle',
      nameGap: 34,
      min: 0,
      minorTick: { show: true },
      minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
      splitLine: { show: true, lineStyle: { color: '#dce5f2' } }
    },
    yAxis: [
      {
        type: 'value',
        name: activeCurve.value === '曲线1'
          ? '气相相对渗透率'
          : '油相相对渗透率',
        nameLocation: 'middle',
        nameGap: 44,
        position: 'left',
        min: 0,
        axisLabel: { formatter: value => Number(value).toFixed(4) },
        minorTick: { show: true },
        minorSplitLine: { show: true, lineStyle: { color: '#f1f5fb' } },
        splitLine: { lineStyle: { color: '#dce5f2' } }
      },
      {
        type: 'value',
        name: '水相相对渗透率',
        nameLocation: 'middle',
        nameGap: 44,
        position: 'right',
        min: 0,
        axisLabel: { formatter: value => Number(value).toFixed(4) },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: activeCurve.value === '曲线1' ? '气相相对渗透率' : '油相相对渗透率',
        type: 'line',
        yAxisIndex: 0,
        showSymbol: false,
        smooth: true,
        lineStyle: { color: curveColor, width: 1.5, type: 'solid' },
        itemStyle: { color: curveColor },
        data: rows.value.map(row => [
          row.waterSaturation,
          activeCurve.value === '曲线1'
            ? row.gasRelativePermeability
            : row.oilRelativePermeability
        ])
      },
      {
        name: '水相相对渗透率',
        type: 'line',
        yAxisIndex: 1,
        showSymbol: false,
        smooth: true,
        lineStyle: { color: curveColor, width: 1.5, type: 'dashed' },
        itemStyle: { color: curveColor },
        data: rows.value.map(row => [row.waterSaturation, row.waterRelativePermeability])
      }
    ]
  }, true)
  chart.resize()
}

const scheduleChart = async () => {
  await nextTick()
  if (chartFrame !== null) cancelAnimationFrame(chartFrame)
  chartFrame = requestAnimationFrame(() => {
    chartFrame = requestAnimationFrame(() => {
      chartFrame = null
      renderChart()
    })
  })
}

watch([activeCurve, rows], scheduleChart, { deep: true })
watch(analysisTableCollapsed, () => {
  // 与天然气结果分析图保持一致：折叠动画开始、结束后各重绘一次。
  scheduleChart()
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  chartResizeTimer = setTimeout(() => {
    chartResizeTimer = null
    scheduleChart()
  }, 180)
})
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
  scheduleChart()
}
window.addEventListener('resize', handleResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (chartFrame !== null) cancelAnimationFrame(chartFrame)
  if (chartResizeTimer !== null) clearTimeout(chartResizeTimer)
  disposeChart()
})
</script>

<template>
  <section class="relative-permeability">
    <header class="relative-toolbar">
      <div class="relative-title">{{ pageTitle }}</div>
    </header>

    <div class="relative-workspace" :class="{ 'table-collapsed': analysisTableCollapsed }">
      <aside class="relative-data-panel" :class="{ collapsed: analysisTableCollapsed }">
        <button
          v-if="analysisTableCollapsed"
          class="relative-collapsed-tab"
          type="button"
          title="展开图表数据"
          @click="analysisTableCollapsed = false"
        >
          图表数据
        </button>

        <div v-else class="relative-data-expanded">
          <div class="relative-data-heading">
            <span>图表数据</span>
            <div class="relative-heading-actions">
              <button
                type="button"
                class="relative-import-button"
                :disabled="importing"
                @click="chooseImportFile"
              >
                {{ importing ? '导入中...' : '导入' }}
              </button>
              <button
                class="relative-data-toggle"
                type="button"
                title="收起图表数据"
                @click="analysisTableCollapsed = true"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true">
                  <path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" />
                </svg>
              </button>
            </div>
          </div>

          <div class="relative-table-wrap">
            <table class="relative-table">
              <thead>
                <tr>
                  <th class="index-column">序号</th>
                  <th v-for="column in visibleTableColumns" :key="column.key">{{ column.label }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in visibleRows" :key="index">
                  <td class="index-column">{{ String(index + 1).padStart(2, '0') }}</td>
                  <td v-for="column in visibleTableColumns" :key="column.key">
                    {{ row?.[column.key] ?? '' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </aside>

      <section class="relative-chart-panel">
        <div class="curve-selector" role="radiogroup" aria-label="相渗曲线">
          <label>
            <input v-model="activeCurve" type="radio" value="曲线1" />
            <span>曲线1</span>
          </label>
          <label>
            <input v-model="activeCurve" type="radio" value="曲线2" />
            <span>曲线2</span>
          </label>
        </div>
        <div class="relative-chart-body">
          <div class="relative-chart-wrap">
            <div ref="chartEl" class="relative-chart"></div>
            <div v-if="!rows.length" class="chart-empty">暂无相渗数据</div>
          </div>
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
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 0;
  padding: 10px 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.relative-data-panel {
  width: 760px;
  flex: 0 0 760px;
  height: 100%;
  display: flex;
  min-height: 0;
  align-self: stretch;
  position: relative;
  transition: width 0.16s ease, flex-basis 0.16s ease;

  &.collapsed {
    width: 34px;
    flex-basis: 34px;
    border: 1px solid #d4d7db;
    border-right: 0;
    box-sizing: border-box;
    background: #fff;
  }
}

.relative-data-expanded {
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

.relative-data-heading {
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

.relative-heading-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.relative-import-button {
  height: 26px;
  min-width: 54px;
  padding: 0 12px;
  border: 1px solid #777;
  border-radius: 4px;
  background: #fff;
  color: #222;
  font: inherit;
  cursor: pointer;

  &:hover:not(:disabled) {
    border-color: #333;
    background: #f5f5f5;
  }

  &:disabled {
    border-color: #c8c8c8;
    background: #f3f3f3;
    color: #999;
    cursor: not-allowed;
  }
}

.relative-data-toggle {
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
    background: #fff8d8;
  }
}

.relative-collapsed-tab {
  width: 100%;
  height: 76px;
  padding: 8px 0 0;
  border: 0;
  border-bottom: 1px solid #e2e6ea;
  background: #fff;
  color: #222;
  cursor: pointer;
  font: inherit;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  box-sizing: border-box;
  writing-mode: vertical-rl;
  text-orientation: upright;
  line-height: 1.05;

  &:hover {
    background: #fff8d8;
    color: #202020;
    box-shadow: inset -2px 0 0 #f2c811;
  }
}

.relative-table-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.relative-table {
  width: 100%;
  min-width: 720px;
  border-collapse: collapse;
  table-layout: fixed;

  th,
  td {
    height: 30px;
    box-sizing: border-box;
    border-right: 1px solid #d4d7db;
    border-bottom: 1px solid #d4d7db;
    padding: 0 8px;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  th {
    position: sticky;
    top: 0;
    z-index: 1;
    height: 42px;
    padding: 4px 6px;
    background: #f4f4f4;
    color: #333;
    font-weight: 400;
    line-height: 1.35;
    text-align: center;
    white-space: nowrap;
  }

  td {
    color: #3f4650;
  }

}

.index-column {
  width: 52px;
  background: #f4f4f4;
  color: #333;
  text-align: center !important;
}

.relative-chart-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid #d4d7db;
  background: #fff;
}

.curve-selector {
  display: flex;
  height: 36px;
  flex: 0 0 36px;
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
    accent-color: #303133;
  }
}

.relative-chart-body {
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 8px;
  box-sizing: border-box;
}

.relative-chart-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
}

.relative-chart {
  position: absolute;
  inset: 0;
}

.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  z-index: 1;
  pointer-events: none;
}

@media (max-width: 1280px) {
  .relative-data-panel:not(.collapsed) {
    width: 46%;
    flex-basis: 46%;
  }
}
</style>
