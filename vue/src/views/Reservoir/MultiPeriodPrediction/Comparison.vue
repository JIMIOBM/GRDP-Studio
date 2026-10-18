<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { PREDICTION_METRICS } from '@/api/reservoirPredictionApi'
import { storagePredictionSchemeApi } from '@/api/storagePredictionSchemeApi'

const props = defineProps({
    projectId: { type: [Number, String], required: true },
    gasReservoirId: { type: [Number, String], required: true },
    storageId: { type: [Number, String], required: true },
    storageName: { type: String, default: '' }
})
// 仅使用数据库已保存的方案和生成时的边界快照，不读取临时方案或当前约束草稿。
const solutions = ref([])
const details = ref({})
const selectedSolutions = ref([])
const metricKey = ref('gasProduction')
const listLoading = ref(false)
const errorText = ref('')
const comparisonChartRef = ref(null)
const colors = ['#e6194b', '#3cb44b', '#4363d8', '#f58231', '#911eb4', '#008080', '#800000', '#000075']
let comparisonChart = null
let resizeObserver = null
let scopeVersion = 0
let listVersion = 0
const detailVersions = new Map()
const currentMetric = computed(() =>
    PREDICTION_METRICS.find(metric => metric.key === metricKey.value) || PREDICTION_METRICS[0]
)
const loading = computed(() => listLoading.value || selectedSolutions.value.some(id => details.value[id]?.state === 'loading'))
const selectedEntries = computed(() => selectedSolutions.value.map(id => ({
    ...solutions.value.find(item => item.id === id),
    ...details.value[id], id, color: solutionColor(id)
})))
const hasComparisonData = computed(() => selectedEntries.value.some(item =>
    item.state === 'ready' && item.comparison.charts[metricKey.value]?.some(point => point[1] !== null)
))
const canCompare = computed(() => selectedSolutions.value.length >= 2)
const selectableIds = computed(() => solutions.value.slice(0, 5).map(solution => solution.id))
const allSolutionsSelected = computed(() => selectableIds.value.length > 0 &&
    selectableIds.value.every(id => selectedSolutions.value.includes(id)))
function solutionColor(id) {
    const index = Math.max(0, solutions.value.findIndex(solution => solution.id === id))
    return colors[index] || `hsl(${(index * 137.508) % 360}, 58%, 44%)`
}
async function toggleSelectAll() {
    if (allSolutionsSelected.value) { selectedSolutions.value = []; return }
    selectedSolutions.value = [...selectableIds.value]
    await Promise.allSettled(selectedSolutions.value.map(id => loadSolutionDetail(id)))
}
function solutionStatus(id) {
    const detail = details.value[id]
    if (!detail) return '勾选后读取保存结果'
    if (detail.state === 'loading') return '加载中…'
    if (detail.state === 'error') return detail.error
    const comparison = detail.comparison
    return comparison.visibleSegmentCount ? `界限内 · ${formatLimit(comparison.lower)}～${formatLimit(comparison.upper)}（10⁴m³/d）` : '无界限内曲段'
}
function schemeId(value) {
    const id = Number(value)
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('方案记录 ID 无效')
    return String(id)
}
function assertScope(record) {
    for (const [camel, snake] of [
        ['projectId', 'project_id'], ['gasReservoirId', 'gas_reservoir_id'], ['storageId', 'storage_id']
    ]) {
        const actual = record[camel] ?? record[snake]
        if (actual != null && String(actual) !== String(props[camel])) throw new Error('方案不属于当前项目、气藏和库')
    }
}
function unwrapSchemeResponse(raw, list = false) {
    let value = raw
    for (let depth = 0; depth < 7; depth++) {
        if (!value || typeof value !== 'object') break
        if (value.success === false || Number(value.code) >= 400) throw new Error(value.msg || value.message || '读取方案失败')
        if (list && Array.isArray(value)) return value
        if (!list && value.id != null && (value.chartsJson != null || value.charts_json != null ||
            value.boundariesJson != null || value.boundaries_json != null || value.originalPredictionId != null)) return value
        value = list
            ? value.records ?? value.content ?? value.list ?? value.data ?? value.result
            : value.record ?? value.data ?? value.result
    }
    throw new Error(list ? '方案列表格式不正确' : '方案详情缺少已保存的结果')
}
function parseObject(raw, label) {
    let value = raw
    for (let depth = 0; depth < 3 && typeof value === 'string'; depth++) {
        try { value = JSON.parse(value) } catch { throw new Error(`${label}不是有效 JSON`) }
    }
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`缺少有效的${label}`)
    return value
}
function finiteValue(raw) {
    if ((typeof raw !== 'number' && typeof raw !== 'string') || String(raw).trim() === '') return null
    const value = Number(raw)
    return Number.isFinite(value) ? value : null
}
function timestamp(raw) {
    if (raw == null || (typeof raw !== 'number' && typeof raw !== 'string') || String(raw).trim() === '') return null
    const value = typeof raw === 'number' || /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : Date.parse(raw)
    return Number.isFinite(value) && Number.isFinite(new Date(value).getTime()) ? value : null
}
function normalizeSeries(raw) {
    if (!Array.isArray(raw)) return []
    const values = new Map()
    for (const row of raw) {
        if (!Array.isArray(row)) continue
        const time = timestamp(row[0])
        if (time === null) continue
        const value = finiteValue(row[1])
        if (values.has(time) && values.get(time) !== value) throw new Error('同一日期存在冲突的预测值，不能可靠比选')
        values.set(time, value)
    }
    return [...values].sort((a, b) => a[0] - b[0])
}
// 使用保形单调三次插值：通过原始点，连续段斜率连续，且不超出相邻样本的值域。
// 裁剪在插值函数上求上下界交点，保留真实越界/缺测造成的间隔。
function interpolationRuns(points) {
    const gaps = points.slice(1).flatMap((point, index) =>
        point[1] !== null && points[index][1] !== null ? [point[0] - points[index][0]] : [])
        .filter(gap => gap > 0).sort((a, b) => a - b)
    const maximumGap = Math.max((gaps[Math.floor(gaps.length / 2)] || 0) * 3, 30 * 86400000)
    const runs = []
    let current = []
    const flush = () => {
        if (current.length) runs.push({ points: current, slopes: monotoneSlopes(current) })
        current = []
    }
    for (const point of points) {
        if (point[1] === null) { flush(); continue }
        if (current.length && point[0] - current[current.length - 1][0] > maximumGap) flush()
        current.push(point)
    }
    flush()
    return runs
}
function monotoneSlopes(points) {
    const count = points.length
    if (count === 1) return [0]
    const h = [], delta = []
    for (let i = 0; i < count - 1; i++) {
        h.push(points[i + 1][0] - points[i][0])
        delta.push((points[i + 1][1] - points[i][1]) / h[i])
    }
    if (count === 2) return [delta[0], delta[0]]
    const slopes = Array(count).fill(0)
    for (let i = 1; i < count - 1; i++) {
        if (delta[i - 1] * delta[i] <= 0) continue
        const w1 = 2 * h[i] + h[i - 1], w2 = h[i] + 2 * h[i - 1]
        slopes[i] = (w1 + w2) / (w1 / delta[i - 1] + w2 / delta[i])
    }
    const endpoint = (h1, h2, d1, d2) => {
        const slope = ((2 * h1 + h2) * d1 - h1 * d2) / (h1 + h2)
        if (Math.sign(slope) !== Math.sign(d1)) return 0
        return Math.sign(d1) !== Math.sign(d2) && Math.abs(slope) > Math.abs(3 * d1) ? 3 * d1 : slope
    }
    slopes[0] = endpoint(h[0], h[1], delta[0], delta[1])
    slopes[count - 1] = endpoint(h[count - 2], h[count - 3], delta[count - 2], delta[count - 3])
    return slopes
}
function intervalValue(run, index, ratio) {
    const left = run.points[index], right = run.points[index + 1]
    const h = right[0] - left[0], t = Math.max(0, Math.min(1, ratio)), t2 = t * t, t3 = t2 * t
    const value = (2 * t3 - 3 * t2 + 1) * left[1] + (t3 - 2 * t2 + t) * h * run.slopes[index] +
        (-2 * t3 + 3 * t2) * right[1] + (t3 - t2) * h * run.slopes[index + 1]
    return Math.max(Math.min(left[1], right[1]), Math.min(Math.max(left[1], right[1]), value))
}
function interpolatedValue(run, time) {
    const points = run.points
    if (time < points[0][0] || time > points[points.length - 1][0]) return null
    if (points.length === 1) return time === points[0][0] ? points[0][1] : null
    let lo = 0, hi = points.length - 1
    while (hi - lo > 1) {
        const middle = Math.floor((lo + hi) / 2)
        if (points[middle][0] <= time) lo = middle
        else hi = middle
    }
    return intervalValue(run, lo, (time - points[lo][0]) / (points[lo + 1][0] - points[lo][0]))
}
function boundaryRatio(run, index, boundary) {
    const ascending = run.points[index + 1][1] >= run.points[index][1]
    let lo = 0, hi = 1
    for (let i = 0; i < 52; i++) {
        const middle = (lo + hi) / 2
        if ((intervalValue(run, index, middle) < boundary) === ascending) lo = middle
        else hi = middle
    }
    return (lo + hi) / 2
}
function feasibleWindows(runs, lower, upper) {
    const windows = []
    runs.forEach((run, runIndex) => {
        const points = run.points
        if (points.length === 1) {
            if (points[0][1] >= lower && points[0][1] <= upper) windows.push({ start: points[0][0], end: points[0][0], runIndex })
            return
        }
        for (let i = 0; i < points.length - 1; i++) {
            const left = points[i], right = points[i + 1]
            if (Math.max(left[1], right[1]) < lower || Math.min(left[1], right[1]) > upper) continue
            let start = 0, end = 1
            if (left[1] < lower) start = boundaryRatio(run, i, lower)
            if (left[1] > upper) start = boundaryRatio(run, i, upper)
            if (right[1] < lower) end = boundaryRatio(run, i, lower)
            if (right[1] > upper) end = boundaryRatio(run, i, upper)
            const duration = right[0] - left[0]
            const window = { start: left[0] + duration * start, end: left[0] + duration * end, runIndex }
            const previous = windows[windows.length - 1]
            if (previous && previous.runIndex === runIndex && previous.end === window.start) previous.end = window.end
            else windows.push(window)
        }
    })
    return windows
}
function knotTimes(points, start, end) {
    let lo = 0, hi = points.length
    while (lo < hi) {
        const middle = Math.floor((lo + hi) / 2)
        if (points[middle][0] <= start) lo = middle + 1
        else hi = middle
    }
    const times = []
    for (let index = lo; index < points.length && points[index][0] < end; index++) times.push(points[index][0])
    return times
}
function clippedMetricSegments(runs, gasRuns, windows, subdivisions, gasMetric = false, lower = 0, upper = 0) {
    const segments = []
    let firstRun = 0
    for (const window of windows) {
        while (firstRun < runs.length && runs[firstRun].points[runs[firstRun].points.length - 1][0] < window.start) firstRun++
        for (let runIndex = firstRun; runIndex < runs.length; runIndex++) {
            const run = runs[runIndex]
            if (run.points[0][0] > window.end) break
            const start = Math.max(window.start, run.points[0][0])
            const end = Math.min(window.end, run.points[run.points.length - 1][0])
            if (start > end) continue
            const knots = [...new Set([start, end,
                ...knotTimes(run.points, start, end),
                ...knotTimes(gasRuns[window.runIndex].points, start, end)
            ])].sort((a, b) => a - b)
            const segment = []
            const add = time => {
                let value = interpolatedValue(run, time)
                if (value === null) return
                // 仅消除交点求解的浮点误差，不把越界原始点压入界限。
                if (gasMetric) value = Math.max(lower, Math.min(upper, value))
                segment.push([time, value])
            }
            add(knots[0])
            for (let i = 1; i < knots.length; i++) {
                for (let step = 1; step <= subdivisions; step++) {
                    add(step === subdivisions ? knots[i] : knots[i - 1] + (knots[i] - knots[i - 1]) * step / subdivisions)
                }
            }
            if (segment.length) segments.push(segment)
        }
    }
    return segments
}
function seriesFromSegments(segments) {
    const data = []
    for (const segment of segments) {
        if (data.length) data.push([segment[0][0], null])
        data.push(...segment)
    }
    return data
}

function buildFeasibleComparison(rawCharts, rawBoundaries) {
    const boundaries = parseObject(rawBoundaries, '方案边界快照，请选择约束后重新生成并保存')
    if (boundaries.complete === false) throw new Error('保存的约束边界尚未完成')
    const limits = {}
    for (const key of ['liquidMin', 'exportPressure', 'erosionMax', 'sandMax']) {
        const value = finiteValue(boundaries[key])
        if (value === null || value < 0) throw new Error('方案边界快照不完整，请重新生成并保存')
        limits[key] = value
    }
    // 已确认的业务规则：外输压力数值参与气产量下界计算。
    const lower = Math.max(limits.liquidMin, limits.exportPressure)
    const upper = Math.min(limits.erosionMax, limits.sandMax)
    if (lower > upper) throw new Error(`气量下界 ${lower} 大于上界 ${upper}，没有合理区间`)
    const charts = parseObject(rawCharts, '已保存的预测曲线')
    const gas = normalizeSeries(charts.gasProduction)
    if (!gas.length) throw new Error('方案没有已保存的气产量曲线，无法判断有效时段')
    const validCount = gas.filter(([, value]) => value !== null && value >= lower && value <= upper).length
    const gasRuns = interpolationRuns(gas)
    const windows = feasibleWindows(gasRuns, lower, upper)
    const filtered = {}
    for (const metric of PREDICTION_METRICS) {
        const source = metric.key === 'gasProduction' ? gas : normalizeSeries(charts[metric.key])
        const runs = metric.key === 'gasProduction' ? gasRuns : interpolationRuns(source)
        const subdivisions = Math.max(1, Math.min(12, Math.floor(6000 / Math.max(gas.length, source.length, 1))))
        filtered[metric.key] = seriesFromSegments(clippedMetricSegments(runs, gasRuns, windows,
            subdivisions, metric.key === 'gasProduction', lower, upper))
    }
    return {
        lower, upper, charts: filtered, validCount, totalCount: gas.length,
        visibleSegmentCount: windows.length, firstTime: windows[0]?.start ?? null, lastTime: windows[windows.length - 1]?.end ?? null
    }

}
function normalizeSavedScheme(raw, id) {
    const record = unwrapSchemeResponse(raw)
    if (schemeId(record.id) !== id) throw new Error('接口返回了其它方案记录')
    assertScope(record)
    const comparison = buildFeasibleComparison(record.chartsJson ?? record.charts_json,
        record.boundariesJson ?? record.boundaries_json)
    return { name: String(record.title || record.name || `方案${id}`), comparison }
}
async function loadSolutionDetail(id, force = false) {
    if (!solutions.value.some(item => item.id === id)) return
    if (!force && ['ready', 'loading'].includes(details.value[id]?.state)) return
    const version = (detailVersions.get(id) || 0) + 1
    detailVersions.set(id, version)
    const scope = scopeVersion
    details.value[id] = { state: 'loading' }
    try {
        const raw = await storagePredictionSchemeApi.detail(id)
        if (scope !== scopeVersion || version !== detailVersions.get(id) || !solutions.value.some(item => item.id === id)) return
        details.value[id] = { state: 'ready', ...normalizeSavedScheme(raw, id) }
    } catch (error) {
        if (scope === scopeVersion && version === detailVersions.get(id)) {
            details.value[id] = { state: 'error', error: error?.message || '读取已保存方案失败' }
        }
    }
}
async function loadSavedSolutions({ changedId = null } = {}) {
    const version = ++listVersion
    const scope = scopeVersion
    listLoading.value = true
    errorText.value = ''
    try {
        const raw = await storagePredictionSchemeApi.list(props.projectId, props.gasReservoirId, props.storageId)
        if (version !== listVersion || scope !== scopeVersion) return
        const records = unwrapSchemeResponse(raw, true).map(record => {
            assertScope(record)
            const id = schemeId(record.id)
            return {
                id, name: String(record.title || record.name || `方案${id}`),
                createdAt: record.updatedAt ?? record.updated_at ?? record.createdAt ?? record.created_at ?? ''
            }
        })
        if (new Set(records.map(record => record.id)).size !== records.length) throw new Error('方案列表存在重复 ID')
        solutions.value = records
        const allowed = new Set(records.map(record => record.id))
        selectedSolutions.value = selectedSolutions.value.filter(id => allowed.has(id))
        for (const id of Object.keys(details.value)) if (!allowed.has(id)) {
            detailVersions.set(id, (detailVersions.get(id) || 0) + 1)
            delete details.value[id]
        }
        await Promise.allSettled(selectedSolutions.value.map(id => loadSolutionDetail(id, !changedId || id === changedId)))
    } catch (error) {
        if (version === listVersion && scope === scopeVersion) errorText.value = error?.message || '加载已保存方案失败'
    } finally {
        if (version === listVersion && scope === scopeVersion) listLoading.value = false
    }
}
function getSeries(id, key) { return details.value[id]?.comparison?.charts?.[key] || [] }
function finalValue(id, key) {
    const end = details.value[id]?.comparison?.lastTime
    const value = getSeries(id, key).find(point => point[0] === end && point[1] !== null)?.[1]
    return value == null ? '—' : value.toFixed(key === 'reservoirRecoveryDegree' ? 2 : 4)
}
function displayDate(value) {
    const time = timestamp(value)
    return time === null ? '—' : new Date(time).toISOString().slice(0, 10)
}
function formatLimit(value) { return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
async function toggleSolution(id) {
    const i = selectedSolutions.value.indexOf(id)
    if (i >= 0) selectedSolutions.value.splice(i, 1)
    else {
        if (selectedSolutions.value.length >= 5) { ElMessage.warning('最多同时比较5个方案'); return }
        selectedSolutions.value.push(id)
        await loadSolutionDetail(id)
    }
}
function selectMetric(key) { metricKey.value = key }
function clearChart() {
    resizeObserver?.disconnect()
    resizeObserver = null
    comparisonChart?.dispose()
    comparisonChart = null
}
function renderComparisonChart() {
    if (!comparisonChartRef.value || !canCompare.value || !hasComparisonData.value) { clearChart(); return }
    if (comparisonChart && comparisonChart.getDom() !== comparisonChartRef.value) clearChart()
    if (!comparisonChart) {
        comparisonChart = echarts.init(comparisonChartRef.value)
        if (typeof ResizeObserver !== 'undefined') {
            resizeObserver = new ResizeObserver(() => comparisonChart?.resize())
            resizeObserver.observe(comparisonChartRef.value)
        }
    }
    const metric = currentMetric.value
    comparisonChart.setOption({
        animation: false, useUTC: true,
        tooltip: {
            trigger: 'axis', valueFormatter: value => {
                const number = finiteValue(Array.isArray(value) ? value[1] : value)
                return number === null ? '—' : `${number.toFixed(4)} ${metric.unit}`
            }
        },
        grid: { left: 70, right: 22, top: 24, bottom: 34, containLabel: true },
        xAxis: { type: 'time', boundaryGap: false, axisLabel: { hideOverlap: true, formatter: '{yyyy}-{MM}' }, splitLine: { show: false } },
        yAxis: {
            type: 'value', name: metric.unit, min: 0, nameTextStyle: { padding: [0, 0, 0, 8] },
            axisLabel: { hideOverlap: true }, splitLine: { lineStyle: { color: '#e8edf2' } }
        },
        series: selectedEntries.value.flatMap(entry => {
            const data = getSeries(entry.id, metric.key)
            if (entry.state !== 'ready' || !data.some(point => point[1] !== null)) return []
            return [{
                id: entry.id, name: `${entry.name}（${entry.id}）`,
                // 曲线已按单调三次插值加密，不再让 ECharts 二次平滑导致越界。
                type: 'line', showSymbol: true, symbol: 'circle',
                symbolSize: (_value, params) => {
                    const index = params.dataIndex
                    return (index === 0 || data[index - 1][1] === null) &&
                        (index === data.length - 1 || data[index + 1][1] === null) ? 4 : 0
                },
                smooth: false, connectNulls: false, data,
                lineStyle: { color: entry.color, width: 2 }, itemStyle: { color: entry.color },
                emphasis: { focus: 'series', lineStyle: { width: 3 } }
            }]
        })
    }, true)
    comparisonChart.resize()
}
const handleResize = () => comparisonChart?.resize()
function onSolutionSaved(event) {
    const detail = event.detail || {}
    if (['projectId', 'gasReservoirId', 'storageId'].some(key => String(detail[key]) !== String(props[key]))) return
    // 立即撤下旧版本，随后重新读取保存成功的记录。
    const id = String(detail.id ?? '')
    if (details.value[id]) {
        detailVersions.set(id, (detailVersions.get(id) || 0) + 1)
        delete details.value[id]
    }
    loadSavedSolutions({ changedId: id })
}
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
    scopeVersion++
    listVersion++
    detailVersions.clear()
    selectedSolutions.value = []
    solutions.value = []
    details.value = {}
    errorText.value = ''
    clearChart()
    loadSavedSolutions()
})
watch([selectedSolutions, metricKey, details], async () => {
    await nextTick()
    renderComparisonChart()
}, { deep: true })
onMounted(() => {
    loadSavedSolutions()
    window.addEventListener('resize', handleResize)
    window.addEventListener('grdp:reservoir-solution-saved', onSolutionSaved)
})
onBeforeUnmount(() => {
    scopeVersion++
    listVersion++
    window.removeEventListener('resize', handleResize)
    window.removeEventListener('grdp:reservoir-solution-saved', onSolutionSaved)
    clearChart()
})
</script>

<template>
    <div class="comparison-workspace">
        <aside class="cmp-sidebar">
            <div class="panel-head">
                <span>方案列表</span>
                <button type="button" class="cmp-refresh" :disabled="loading" @click="loadSavedSolutions()">刷新</button>
            </div>
            <div class="cmp-sidebar-body">
                <div v-if="listLoading && !solutions.length" class="cmp-loading">加载中…</div>
                <div v-else-if="errorText && !solutions.length" class="cmp-error" role="alert">{{ errorText }}</div>
                <div v-else-if="!solutions.length" class="cmp-empty">
                    <p>暂无已保存的方案</p>
                    <p class="cmp-empty-hint">请先在「方案生成」中保存方案</p>
                </div>
                <template v-else>
                    <div class="cmp-select-all">
                        <label>
                            <input type="checkbox" :checked="allSolutionsSelected" @change="toggleSelectAll" />
                            <span>{{ solutions.length > 5 ? '选择前5个 / 取消全选' : '全选 / 取消全选' }}</span>
                        </label>
                    </div>
                    <div class="cmp-list">
                        <label v-for="solution in solutions" :key="solution.id" class="cmp-item"
                            :class="{
                                'cmp-item--checked': selectedSolutions.includes(solution.id),
                                'cmp-item--warn': details[solution.id]?.state === 'error' ||
                                    (details[solution.id]?.state === 'ready' && !details[solution.id].comparison.visibleSegmentCount)
                            }"
                            :title="`${solution.name}：${solutionStatus(solution.id)}`">
                            <input type="checkbox" :checked="selectedSolutions.includes(solution.id)"
                                @change="toggleSolution(solution.id)" />
                            <span class="cmp-item-swatch"
                                :style="{ backgroundColor: selectedSolutions.includes(solution.id) ? solutionColor(solution.id) : '#c8cdd2' }"></span>
                            <span class="cmp-item-content">
                                <span class="cmp-item-name">{{ details[solution.id]?.name || solution.name }}</span>
                                <span class="cmp-item-status" :class="{
                                    'status-eligible': details[solution.id]?.state === 'ready' && details[solution.id].comparison.visibleSegmentCount,
                                    'status-excluded': details[solution.id]?.state === 'error'
                                }">{{ solutionStatus(solution.id) }}</span>
                            </span>
                        </label>
                    </div>
                    <p v-if="errorText" class="cmp-error" role="alert">{{ errorText }}</p>
                </template>
            </div>
            <div class="cmp-selection-info">已选择 {{ selectedSolutions.length }} / 5 个方案</div>
        </aside>
        <main class="cmp-chart-area">
            <div class="cmp-tab">{{ storageName ? `${storageName} - 方案比选` : '方案比选' }}</div>
            <div class="cmp-chart-body">
                <div v-if="!canCompare" class="chart-placeholder">
                    <strong>{{ solutions.length < 2 ? '至少需要两个已保存方案' : '勾选至少两个方案' }}</strong>
                            <p>选中后自动对比各项指标的界限内曲线</p>
                </div>
                <template v-else>
                    <section class="cmp-plot-panel">
                        <div class="cmp-shared-legend" aria-label="所选方案图例">
                            <span v-for="entry in selectedEntries" :key="entry.id" :title="`记录 ${entry.id}`">
                                <i :style="{ backgroundColor: entry.color }"></i>{{ entry.name }}
                            </span>
                        </div>
                        <nav class="chart-switch-tabs" aria-label="比选指标">
                            <button v-for="metric in PREDICTION_METRICS" :key="metric.key" class="chart-switch-tab"
                                :class="{ active: metricKey === metric.key }" type="button"
                                @click="selectMetric(metric.key)">{{ metric.name }}</button>
                        </nav>
                        <div class="comparison-chart-shell">
                            <div v-if="hasComparisonData" ref="comparisonChartRef" class="comparison-chart" role="img"
                                :aria-label="`界限内${currentMetric.name}方案对比`"></div>
                            <div v-else class="chart-empty-overlay">
                                {{ loading ? '正在读取保存结果…' : '所选方案没有可显示的界限内曲段，或缺少有效气量上下限。' }}
                            </div>
                        </div>
                    </section>
                    <details class="cmp-metrics">
                        <summary>有效末期指标</summary>
                        <p class="cmp-metrics-note">按各方案最后有效时刻取值；边界交点值按相邻预测曲段插值。</p>
                        <div class="cmp-table-scroll">
                            <table>
                                <thead>
                                    <tr>
                                        <th>方案名称</th>
                                        <th>有效截止日期</th>
                                        <th v-for="metric in PREDICTION_METRICS" :key="metric.key">{{ metric.name }}({{
                                            metric.unit }})</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr v-for="entry in selectedEntries" :key="entry.id">
                                        <td>{{ entry.name }}</td>
                                        <td>{{ displayDate(entry.comparison?.lastTime) }}</td>
                                        <td v-for="metric in PREDICTION_METRICS" :key="metric.key">{{
                                            finalValue(entry.id, metric.key) }}</td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    </details>
                </template>
            </div>
        </main>
    </div>
</template>

<style lang="scss" scoped>
.comparison-workspace {
    flex: 1;
    min-width: 0;
    min-height: 0;
    display: flex;
    overflow: hidden;
    background: #fff;
    color: #252525;
    font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif;
}

.cmp-tab {
    height: 34px;
    line-height: 34px;
    max-width: min(430px, 100%);
    padding: 0 12px;
    border-right: 1px solid #e4e7ed;
    box-sizing: border-box;
    background: #f4d000;
    color: #202020;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 13px;
}

.cmp-sidebar {
    flex: 0 0 260px;
    border-right: 1px solid #d7d7d7;
    background: #fff;
    display: flex;
    flex-direction: column;
    min-height: 0;
}

.panel-head {
    display: flex;
    flex: 0 0 34px;
    align-items: center;
    padding: 0 12px;
    border-bottom: 1px solid #d7d7d7;
    background: #f2f2f2;
    font-weight: 600;
    font-size: 13px;
}

.cmp-sidebar-body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 8px 10px;
}

.cmp-loading {
    text-align: center;
    color: #909399;
    padding: 20px 0;
}

.cmp-empty {
    text-align: center;
    color: #909399;
    padding: 20px 0;

    p {
        margin: 4px 0;
        font-size: 12px;
    }
}

.cmp-empty-hint {
    font-size: 11px;
    color: #c0c4cc;
}

.cmp-select-all {
    margin-bottom: 6px;
    padding-bottom: 6px;
    border-bottom: 1px solid #d7d7d7;

    label {
        display: flex;
        align-items: center;
        gap: 4px;
        cursor: pointer;
        font-size: 12px;
        color: #252525;

        input[type="checkbox"] {
            accent-color: #333;
        }
    }
}

.cmp-list {
    display: flex;
    flex-direction: column;
    gap: 1px;
}

.cmp-item {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 5px 6px;
    cursor: pointer;
    font-size: 12px;
    border-bottom: 1px solid #eee;

    &:hover {
        background: #fafafa;
    }

    &--checked {
        background: #fffdf0;
    }

    input[type="checkbox"] {
        accent-color: #333;
        flex-shrink: 0;
    }
}

.cmp-item--warn {
    border-left: 2px solid #d98b32;
}

.cmp-item-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.cmp-item-content {
    display: flex;
    flex: 1;
    min-width: 0;
    flex-direction: column;
    gap: 1px;
}

.cmp-item-swatch {
    width: 9px;
    height: 9px;
    flex: 0 0 9px;
    border-radius: 50%;
}

.cmp-item-status {
    overflow: hidden;
    color: #737d86;
    font-size: 11px;
    line-height: 1.35;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.status-eligible {
    color: #22835d;
}

.status-excluded {
    color: #b45b4b;
}

.cmp-hint {
    margin: 8px 0 0;
    font-size: 11px;
    color: #909399;
    text-align: center;
}

.cmp-excluded-hint {
    margin: 7px 0 0;
    font-size: 11px;
    color: #909399;
    text-align: center;
    line-height: 1.4;
}

.cmp-error {
    color: #a56a15;
    font-size: 12px;
    line-height: 1.5;
    padding: 8px;
    background: #fdf6ec;
    border-radius: 3px;
}

.cmp-chart-area {
    min-width: 0;
    min-height: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
}

.cmp-chart-body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: auto;
    padding: 12px 16px;
}

.chart-placeholder {
    flex: 1;
    min-height: 300px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    color: #909399;
    text-align: center;

    strong {
        color: #606266;
        font-size: 14px;
    }

    p {
        font-size: 12px;
        margin: 6px 0 0;
    }
}

.chart-switch-tabs {
    display: flex;
    flex: 0 0 auto;
    border-bottom: 1px solid #e4e7ed;
    background: #fafafa;
    overflow-x: auto;
}

.chart-switch-tab {
    height: 34px;
    padding: 0 16px;
    border: 0;
    border-right: 1px solid #e4e7ed;
    border-bottom: 2px solid transparent;
    border-radius: 0;
    background: transparent;
    color: #555;
    font: inherit;
    font-size: 13px;
    cursor: pointer;
    white-space: nowrap;

    &:hover {
        color: #202020;
        background: #fff8d8;
    }

    &.active {
        color: #202020;
        border-bottom-color: #f2c811;
        box-shadow: inset 0 -3px 0 #f2c811;
        background: #fff;
        font-weight: 600;
    }
}

.cmp-shared-legend {
    display: flex;
    flex: 0 0 auto;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 14px;
    padding: 6px 2px 8px;
    border-bottom: 1px solid #e4e7ed;

    span {
        display: inline-flex;
        align-items: center;
        gap: 5px;
        min-width: 0;
        color: #4b5560;
        font-size: 12px;
    }

    i {
        width: 9px;
        height: 9px;
        flex: 0 0 9px;
        border-radius: 50%;
    }
}

.comparison-chart-shell {
    position: relative;
    display: flex;
    flex: 1;
    min-height: 350px;
}

.comparison-chart {
    width: 100%;
    flex: 1;
    min-height: 350px;
}

.chart-empty-overlay {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    padding: 20px;
    background: rgb(255 255 255 / 88%);
    color: #737d86;
    text-align: center;
    font-size: 13px;
    pointer-events: none;
}

@media (max-width: 900px) {
    .comparison-workspace {
        flex-direction: column;
        overflow: auto;
    }

    .cmp-sidebar {
        width: 100%;
        max-height: 300px;
        flex: 0 0 auto;
        border-right: 0;
        border-bottom: 1px solid #d7d7d7;
    }

    .cmp-chart-area {
        min-height: 600px;
    }

    .cmp-chart-body {
        padding: 10px;
    }
}

.panel-head {
    justify-content: space-between;
}

.cmp-refresh {
    border: 1px solid #d7d7d7;
    background: #fff;
    padding: 3px 10px;
    font: inherit;
    font-size: 12px;
    cursor: pointer;
}

.cmp-refresh:disabled {
    cursor: default;
    opacity: 0.6;
}

.cmp-selection-info {
    padding: 8px 10px;
    border-top: 1px solid #e4e7ed;
    color: #737d86;
    font-size: 11px;
    text-align: center;
}

.cmp-plot-panel {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-height: 420px;
}

.cmp-metrics {
    flex: 0 0 auto;
    margin-top: 10px;
    border-top: 1px solid #e4e7ed;
    padding-top: 9px;
    color: #606266;
    font-size: 12px;
}

.cmp-metrics summary {
    cursor: pointer;
}

.cmp-metrics-note {
    color: #909399;
    font-size: 11px;
}

.cmp-table-scroll {
    overflow-x: auto;
}

.cmp-metrics table {
    width: 100%;
    border-collapse: collapse;
}

.cmp-metrics th,
.cmp-metrics td {
    padding: 8px 10px;
    text-align: left;
    white-space: nowrap;
    border-bottom: 1px solid #e4e7ed;
}

.cmp-metrics th {
    background: #fafafa;
    font-weight: 500;
}
</style>