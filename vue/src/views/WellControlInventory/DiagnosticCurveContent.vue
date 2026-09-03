<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { pvtStorageApi } from '@/api/pvtStorage'

const props = defineProps({
    node: Object,
    projectId: [Number, String],
    gasReservoirId: [Number, String]
})

const emit = defineEmits(['recalculate'])

const unwrap = response => response?.data ?? response ?? {}

const activePanel = ref('input')
const importing = ref(false)
const selectedPvtId = ref('')
const pvtOptions = ref([])
const rows = ref([])
const importedFileName = ref('')
const fileInput = ref(null)
const inputUpperLimit = ref('')
const inputLowerLimit = ref('')
const chartEl = ref(null)
let chart = null

const wellName = computed(() => props.node?.wellName || '')

const loadPvtOptions = async () => {
    pvtOptions.value = []
    selectedPvtId.value = ''
    if (!wellName.value) return
    try {
        const summaries = unwrap(await pvtStorageApi.list(
            props.projectId, props.gasReservoirId, wellName.value
        )) || []
        pvtOptions.value = Array.isArray(summaries) ? summaries : []
        if (pvtOptions.value.length > 0) {
            selectedPvtId.value = String(pvtOptions.value[0].pvtId)
        }
    } catch (error) {
        console.warn('加载PVT性质失败', error)
        ElMessage.error('加载PVT性质失败')
    }
}

watch(() => props.node?.wellName, () => {
    loadPvtOptions()
}, { immediate: true })

const chooseFile = () => fileInput.value?.click()

const handleFile = async (event) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    importing.value = true
    try {
        const XLSX = await import('xlsx')
        const data = await new Promise((resolve, reject) => {
            const reader = new FileReader()
            reader.onload = (e) => {
                try {
                    const workbook = XLSX.read(e.target.result, { type: 'array' })
                    const sheetName = workbook.SheetNames[0]
                    const sheet = workbook.Sheets[sheetName]
                    const json = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false })
                    resolve(json)
                } catch (err) { reject(err) }
            }
            reader.onerror = reject
            reader.readAsArrayBuffer(file)
        })
        if (!data || data.length < 2) {
            ElMessage.warning('文件内容为空或格式不正确')
            return
        }
        const header = data[0].map(h => String(h || '').trim())
        const timeIdx = header.findIndex(h => h.includes('时间'))
        const gasIdx = header.findIndex(h => h.includes('注') || h.includes('采') || h.includes('气'))
        const cycleIdx = header.findIndex(h => h.includes('周期'))
        if (timeIdx === -1 || gasIdx === -1 || cycleIdx === -1) {
            ElMessage.error('表头需包含：时间、注/采气、周期')
            return
        }
        rows.value = data.slice(1).filter(row => row.some(cell => cell !== undefined && cell !== null && cell !== '')).map((row, i) => ({
            sequence: i + 1,
            time: row[timeIdx] ?? '',
            gas: row[gasIdx] ?? '',
            cycle: row[cycleIdx] ?? ''
        }))
        importedFileName.value = file.name
        ElMessage.success(`成功导入 ${rows.value.length} 行数据`)
    } catch (error) {
        console.error('文件解析失败', error)
        ElMessage.error('文件解析失败，请检查文件格式')
    } finally {
        importing.value = false
    }
}

const addRow = () => {
    rows.value.push({ sequence: rows.value.length + 1, time: '', gas: '', cycle: '' })
}
const handleRecalculate = () => emit('recalculate', {})
const switchPanel = (panel) => {
    activePanel.value = panel
    if (panel === 'analysis') nextTick(() => initChart())
}

const initChart = () => {
    if (!chartEl.value) return
    if (chart) chart.dispose()
    chart = echarts.init(chartEl.value)
    chart.setOption({
        xAxis: {
            name: '库存',
            type: 'value',
            nameLocation: 'middle',
            nameGap: 28,
            nameTextStyle: { fontSize: 12 }
        },
        yAxis: {
            name: '地层压力(MPa)',
            type: 'value',
            nameLocation: 'middle',
            nameGap: 42,
            nameTextStyle: { fontSize: 12 }
        },
        series: [{
            type: 'line',
            data: [],
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { color: '#409eff' },
            itemStyle: { color: '#409eff' }
        }]
    })
}

onMounted(() => {
    if (activePanel.value === 'analysis') nextTick(() => initChart())
})

watch(() => activePanel.value, (val) => {
    if (val === 'analysis') nextTick(() => initChart())
})

onBeforeUnmount(() => {
    if (chart) { chart.dispose(); chart = null }
})
</script>

<template>
    <section class="diagnostic-workspace">
        <aside class="params-panel">
            <div class="panel-head">参数设置</div>
            <div class="panel-body">
                <label class="field"><span>选择PVT表</span>
                    <select v-model="selectedPvtId">
                        <option value="" disabled>{{ pvtOptions.length ? '请选择PVT性质' : '当前井暂无PVT性质' }}</option>
                        <option v-for="item in pvtOptions" :key="item.pvtId" :value="String(item.pvtId)">{{ item.pvtName
                            || `PVT性质${item.pvtNo}` }}</option>
                    </select>
                </label>
                                <label class="field"><span>选择数据表</span>
                    <button type="button" class="local-import-button" :disabled="importing" @click="chooseFile">
                        {{ importing ? '正在导入…' : '本地导入' }}
                    </button>
                    <input ref="fileInput" class="hidden-file" type="file" accept=".xlsx,.xls,.csv" @change="handleFile" />
                    <small class="imported-data-name">{{ importedFileName || '未导入文件' }}</small>
                </label>
                <label class="field"><span>输入上限</span>
                    <input v-model="inputUpperLimit" placeholder="请输入上限" />
                </label>
                <label class="field"><span>下限</span>
                    <input v-model="inputLowerLimit" placeholder="请输入下限" />
                </label>
                <div class="action-buttons">
                    <button type="button" class="calculate" @click="handleRecalculate">计算</button>
                </div>
            </div>
        </aside>

        <main class="result-area">
            <div v-show="activePanel === 'input'" class="editable-data-grid">
                <!-- <div class="data-toolbar">
                    <span>生产数据表 · 可直接编辑</span>
                </div> -->
                <el-table :data="rows" border height="100%">
                    <el-table-column label="序号" width="60" align="center">
                        <template #default="{ row }">{{ row.sequence }}</template>
                    </el-table-column>
                    <el-table-column label="时间" min-width="160" align="center">
                        <template #default="{ row }">{{ row.time }}</template>
                    </el-table-column>
                    <el-table-column label="注/采气" min-width="160" align="center">
                        <template #default="{ row }">{{ row.gas }}</template>
                    </el-table-column>
                    <el-table-column label="周期" min-width="160" align="center">
                        <template #default="{ row }">{{ row.cycle }}</template>
                    </el-table-column>
                    <!-- <el-table-column label="操作" width="70" align="center" /> -->
                </el-table>
            </div>

            <div v-show="activePanel === 'analysis'" class="analysis-view">
                <div ref="chartEl" class="chart" />
            </div>

            <div class="bottom-tabs">
                <button :class="{ active: activePanel === 'input' }" @click="switchPanel('input')">数据列表</button>
                <button :class="{ active: activePanel === 'analysis' }" @click="switchPanel('analysis')">结果分析</button>
            </div>
        </main>
    </section>
</template>

<style lang="scss" scoped>
.diagnostic-workspace {
    display: flex;
    height: 100%;
    min-height: 0;
    background: #fff
}

.params-panel {
    width: 360px;
    min-width: 360px;
    display: flex;
    flex-direction: column;
    border-right: 1px solid #ddd
}

.panel-head {
    height: 34px;
    padding: 0 12px;
    display: flex;
    align-items: center;
    background: #f2f2f2;
    border-bottom: 1px solid #ddd;
    font-size: 13px
}

.panel-body {
    flex: 1;
    overflow: auto;
    padding: 10px 14px
}

.field {
    display: block;
    margin-bottom: 11px;
    font-size: 12px
}

.field>span {
    display: block;
    margin-bottom: 4px
}

.field select,
.field input:not(.hidden-file) {
    width: 100%;
    height: 28px;
    box-sizing: border-box;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    padding: 0 8px;
    font-size: 13px;
    outline: none
}

.field select:focus,
.field input:not(.hidden-file):focus {
    border-color: #b99500;
    box-shadow: 0 0 0 2px rgba(242, 200, 17, 0.16)
}

.hidden-file {
    display: none
}

.local-import-button {
    width: 100%;
    height: 26px;
    padding: 0 8px;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    color: #333;
    text-align: left;
    cursor: pointer;
}
.local-import-button:hover {
    border-color: #777;
}
.local-import-button:disabled {
    color: #999;
    cursor: wait;
}

.imported-data-name {
    display: block;
    margin-top: 4px;
    overflow: hidden;
    color: #777;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap
}

.action-buttons {
    display: flex;
    gap: 8px;
    margin-top: 4px
}

.calculate {
    height: 30px;
    padding: 0 24px;
    border: 0;
    border-radius: 3px;
    color: #fff;
    cursor: pointer;
    background: #111
}

.calculate:disabled {
    opacity: .6;
    cursor: not-allowed
}

.result-area {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    flex-direction: column
}

.editable-data-grid,
.analysis-view {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column
}

.data-toolbar {
    height: 38px;
    padding: 0 12px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    border-bottom: 1px solid #ddd;
    color: #666;
    font-size: 12px
}

.toolbar-actions {
    display: flex;
    gap: 8px
}

.template-btn,
.add-btn {
    height: 26px;
    padding: 0 10px;
    border: 1px solid #aaa;
    border-radius: 3px;
    background: #fff;
    color: #333;
    cursor: pointer;
    font-size: 12px
}

.template-btn:hover,
.add-btn:hover {
    background: #f5f5f5
}

.chart {
    flex: 1;
    min-height: 0
}

:deep(.el-table .cell) {
    padding: 0;
    text-align: center
}

:deep(.el-table th.el-table__cell>.cell) {
    padding: 0 10px
}

:deep(.el-table td.el-table__cell) {
    padding: 0;
    background: #fff
}

:deep(.el-table__row:hover>td.el-table__cell) {
    background: #fff !important
}

.bottom-tabs {
    height: 31px;
    display: flex;
    flex-shrink: 0;
    border-top: 1px solid #ddd
}

.bottom-tabs button {
    min-width: 110px;
    border: 0;
    border-right: 1px solid #ddd;
    background: #fff2f4;
    color: #999;
    cursor: pointer
}

.bottom-tabs button.active {
    color: #222;
    box-shadow: inset 0 -2px #2b171a;
    font-weight: 600
}
</style>