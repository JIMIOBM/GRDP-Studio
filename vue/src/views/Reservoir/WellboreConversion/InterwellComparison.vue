<script setup>
/**
 * 库 → 井筒折算 → 井间对比：多口井在同一时间节点的压力折算（HB/MB）对比。
 *
 * 输入（左侧参数栏）：
 *  - 折算方法：HB（Hagedorn & Brown）/ MB（Mukherjee & Brill）单选；
 *  - 折算时间节点：一个日期，确定各井取哪一天的注采记录；
 *  - 井身参数：测井深度、计算步长、油管内径、粗糙度、井斜角、地温梯度，统一应用到所有参与井；
 *  - 参与对比的井：库成员井勾选。
 *
 * 数据来源（每口井，运行时获取）：
 *  - 井列表：GET /reservoir-loss/storages/{storageId}/wells（project_storage_well 关联井头）；
 *  - 注采记录：原平台生产数据接口（/docker-api 代理），取所选日期记录得到井口压力（油管）、
 *    井口温度、日产气量、日产水量；无当日记录、缺温压或气水量的井只标记原因；
 *  - PVT：GET /pvt/records 取该井第一条已保存方案（pvtId），后端按它评价流体物性。
 *
 * 计算方法：复用单井压力折算接口 POST /wellbore/pressure/calculate 逐井并发调用
 * （边界位置固定井口 wellhead、采气工况 production）。后端先按 pvtId 打开该井 PVT
 * 会话并覆盖气体比重/水密度/水黏度，再由 PressureCalculator 从井口向井底按步长分段迭代
 * （每段最多 10 次、容差 0.0001 MPa），用所选方法的压降关联式逐段累加压力。
 *
 * 结果：每口井一条深度升序的剖面 profile（深度/温度/压力/梯度/收敛标记等）；
 * 井顶压力 = profile[0]（井深 0，即输入边界压力），井底压力 = 最深点折算压力。
 * 右侧绘制多井深度-压力剖面对比曲线（y 轴井深倒序，端点标注井顶/井底压力），
 * 下方摘要表列出各井输入（日期/气水量/井口温压/PVT）与输出（井顶/井底压力、收敛状态）。
 * 任一井失败只记原因不阻断其余井；全部失败时弹窗汇总各原因及井数。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { storageCatalogApi } from '@/api/storageCatalog'
import { wellborePressureApi } from '@/api/wellborePressure'
import { pvtStorageApi } from '@/api/pvtStorage'
import { dataManagementApi } from '@/api/docker'
import {
  productionRecords as buildProductionRecords,
  productionValues,
  rowsOf,
  unpack,
  wellRows
} from '@/utils/temperatureSources'
import { buildWellboreComparisonChart } from '@/utils/wellboreComparisonChart'

const props = defineProps({
  storageId: { type: [Number, String], default: null },
  storageName: { type: String, default: '' },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const comparisonMethods = [
  { value: 'HB', label: 'Hagedorn & Brown' },
  { value: 'MB', label: 'Mukherjee & Brill' }
]
const methodLabel = code => comparisonMethods.find(item => item.value === code)?.label || code

// 成员井、勾选和请求状态独立于单井页面，读取方式与库级产能对比一致。
const storageWells = ref([])
const selectedWellIds = ref([])
const wellKeyword = ref('')
const wellError = ref('')
const loadingWells = ref(false)
const filteredWells = computed(() => storageWells.value.filter(well =>
  well.wellName.toLowerCase().includes(wellKeyword.value.trim().toLowerCase())))
const allWellsSelected = computed(() => filteredWells.value.length > 0
  && filteredWells.value.every(well => selectedWellIds.value.includes(well.id)))
const someWellsSelected = computed(() => !allWellsSelected.value
  && filteredWells.value.some(well => selectedWellIds.value.includes(well.id)))
const toggleAllWells = event => {
  const selected = new Set(selectedWellIds.value)
  filteredWells.value.forEach(well => event.target.checked ? selected.add(well.id) : selected.delete(well.id))
  selectedWellIds.value = [...selected]
}
const selectedWells = computed(() => storageWells.value.filter(well => selectedWellIds.value.includes(well.id)))
let wellVersion = 0
const errorText = error => error?.response?.data?.msg || error?.msg || error?.message || '请求失败'
const unwrap = response => response?.data ?? response
const formatNumber = value => Number.isFinite(Number(value))
  ? Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) : '—'

// 折算条件：HB/MB 单选、单一时间节点，井身参数统一应用到所有参与井。
const method = ref('HB')
const selectedDate = ref('')
const depth = ref('3100')
const step = ref('50')
const idTubing = ref('62')
const roughness = ref('0.016')
const angle = ref('0')
const tGrad = ref('3')

const calculating = ref(false)
const comparisonResults = ref([])
const resultTitle = computed(() => [props.storageName, '井筒折算', '井间对比', '分析结果'].filter(Boolean).join('-'))
const chartEmptyText = computed(() => {
  if (calculating.value) return '正在计算…'
  if (!comparisonResults.value.length) return '请选择井并计算'
  if (!comparisonResults.value.some(row => row.ok)) return '所选井在当前条件下没有可展示的折算结果'
  return ''
})
const summaryRows = computed(() => comparisonResults.value.map(row => row.ok ? {
  wellId: row.wellId,
  wellName: row.wellName,
  ok: true,
  date: selectedDate.value,
  qGas: row.qGas,
  qLiq: row.qLiq,
  fWh: row.fWh,
  tWh: row.tWh,
  pvtName: row.pvtName,
  topPressure: row.topPressure,
  bottomPressure: row.bottomPressure,
  status: row.converged ? '收敛' : '部分井段不收敛'
} : { wellId: row.wellId, wellName: row.wellName, ok: false, status: row.error }))

// 图表实例与渲染函数必须先于 clearResult/loadStorageWells 声明：这两个函数会在
// setup 阶段的 watch(immediate) 中被同步调用，后置声明会触发 TDZ 错误导致井列表永不加载。
const chartEl = ref(null)
let chart = null
const renderChart = () => {
  if (!chartEl.value) return
  chart ??= echarts.init(chartEl.value)
  chart.setOption(buildWellboreComparisonChart(
    comparisonResults.value.filter(row => row.ok),
    { methodLabel: methodLabel(method.value), date: selectedDate.value, emptyText: chartEmptyText.value }
  ), true)
}

let calculationVersion = 0
const clearResult = () => {
  calculationVersion++
  calculating.value = false
  comparisonResults.value = []
  renderChart()
}
const loadStorageWells = async () => {
  const version = ++wellVersion
  storageWells.value = []; selectedWellIds.value = []; wellError.value = ''; clearResult()
  if (![props.projectId, props.gasReservoirId, props.storageId].every(id => Number(id) > 0)) {
    wellError.value = '请先选择具体储气库'; loadingWells.value = false; return
  }
  loadingWells.value = true
  try {
    const data = unwrap(await storageCatalogApi.wells(props.storageId, props.projectId, props.gasReservoirId))
    if (version !== wellVersion) return
    if (!Array.isArray(data)) throw new Error('单井列表格式不正确')
    storageWells.value = data.map(well => ({ id: Number(well.id), wellName: String(well.wellName || '') }))
    selectedWellIds.value = storageWells.value.map(well => well.id)
  } catch (error) {
    if (version === wellVersion) wellError.value = errorText(error)
  } finally {
    if (version === wellVersion) loadingWells.value = false
  }
}

const paramsCollapsed = ref(false)
const panelWidth = ref(238)
const workspaceEl = ref(null)
let resizeObserver = null
let resizeFrame = 0
const scheduleResize = () => {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    chart?.resize()
  })
}
let dragStartX = 0
let dragStartWidth = 0
const maxPanelWidth = () => Math.max(238, Math.min(520, (workspaceEl.value?.clientWidth || 780) - 260))
const setPanelWidth = width => { panelWidth.value = Math.max(238, Math.min(maxPanelWidth(), width)) }
function startResize(event) {
  if (event.button !== 0) return
  event.preventDefault()
  dragStartX = event.clientX
  dragStartWidth = panelWidth.value
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', stopResize, { once: true })
  window.addEventListener('pointercancel', stopResize, { once: true })
}
function resizePanel(event) { setPanelWidth(dragStartWidth + event.clientX - dragStartX) }
function stopResize() {
  window.removeEventListener('pointermove', resizePanel)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
}
function resizeWithKeyboard(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  setPanelWidth(event.key === 'Home' ? 238 : event.key === 'End' ? maxPanelWidth() : panelWidth.value + (event.key === 'ArrowRight' ? 20 : -20))
}
// 切换库时重新读取成员并清空旧条件；切方法或参数时仅作废旧结果。
watch(() => [props.projectId, props.gasReservoirId, props.storageId], () => {
  method.value = 'HB'; selectedDate.value = ''
  depth.value = '3100'; step.value = '50'; idTubing.value = '62'
  roughness.value = '0.016'; angle.value = '0'; tGrad.value = '3'
  wellKeyword.value = ''
  loadStorageWells()
}, { immediate: true })
watch(() => [method.value, selectedDate.value, depth.value, step.value, idTubing.value,
  roughness.value, angle.value, tGrad.value, ...selectedWellIds.value], clearResult, { flush: 'sync' })

onMounted(async () => {
  await nextTick()
  renderChart()
  // 观察图表容器自身：拖动参数栏或调整窗口时容器尺寸变化即触发图表重排。
  resizeObserver = new ResizeObserver(() => {
    if (panelWidth.value > maxPanelWidth()) setPanelWidth(panelWidth.value)
    scheduleResize()
  })
  resizeObserver.observe(chartEl.value)
  window.addEventListener('resize', scheduleResize)
})
onBeforeUnmount(() => {
  wellVersion++
  calculationVersion++
  stopResize()
  resizeObserver?.disconnect()
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  chart?.dispose()
  chart = null
})

// 单口井的折算输入来源：所选日期的注采记录（井口温压与气水量）＋该井第一条已保存PVT。
const loadWellSource = async well => {
  const [production, pvts] = await Promise.allSettled([
    dataManagementApi.getProductionData(Number(props.projectId), Number(props.gasReservoirId), well.wellName),
    pvtStorageApi.list(Number(props.projectId), Number(props.gasReservoirId), well.wellName)
  ])
  if (production.status !== 'fulfilled') return { error: '注采数据读取失败' }
  const rows = wellRows(rowsOf(production.value), well.wellName, true)
  const record = buildProductionRecords(rows).find(item => item.date === selectedDate.value)
  if (!record) return { error: `所选日期无注采记录` }
  const fields = unpack(production.value).fields ?? []
  // 与单井压力折算一致：井口模式读取油管通道的温压与当日气水量。
  const values = productionValues(record.row, 'wellhead', fields, 'tubing')
  if (!Number.isFinite(values.fWh) || !Number.isFinite(values.tWh)) {
    return { error: '所选日期记录缺少井口压力或井口温度' }
  }
  if (!Number.isFinite(values.qGas) || !Number.isFinite(values.qLiq)
    || Number(values.qGas) + Number(values.qLiq) <= 0) {
    return { error: '所选日期记录缺少有效的日产气量或日产水量' }
  }
  if (pvts.status !== 'fulfilled') return { error: 'PVT记录读取失败' }
  const pvt = rowsOf(pvts.value).sort((a, b) => a.pvtNo - b.pvtNo)[0]
  if (!pvt?.pvtId) return { error: '当前井暂无可选择的PVT性质' }
  return {
    record,
    values,
    pvtId: Number(pvt.pvtId),
    pvtName: pvt.pvtName || `PVT性质${pvt.pvtNo}`
  }
}

const validPositive = value => value !== '' && Number.isFinite(Number(value)) && Number(value) > 0
const handleCalculate = async () => {
  if (loadingWells.value || calculating.value) return
  if (!selectedWellIds.value.length) return ElMessage.warning('请选择参与对比的井')
  if (!selectedDate.value) return ElMessage.warning('请选择折算时间节点')
  if (!validPositive(depth.value) || Number(depth.value) > 100000) return ElMessage.warning('测井深度必须大于0且不超过100000 m')
  if (!validPositive(step.value) || Number(step.value) > 100000) return ElMessage.warning('计算步长必须大于0且不超过100000 m')
  if (!validPositive(idTubing.value) || Number(idTubing.value) > 2000) return ElMessage.warning('油管内径必须大于0且不超过2000 mm')
  if (roughness.value === '' || !Number.isFinite(Number(roughness.value)) || Number(roughness.value) < 0 || Number(roughness.value) > 100)
    return ElMessage.warning('管内壁粗糙度必须为0至100 mm')
  if (angle.value === '' || !Number.isFinite(Number(angle.value)) || Number(angle.value) < 0 || Number(angle.value) > 90)
    return ElMessage.warning('井斜角必须为0至90°')
  if (tGrad.value === '' || !Number.isFinite(Number(tGrad.value)) || Number(tGrad.value) < 0 || Number(tGrad.value) > 100)
    return ElMessage.warning('地温梯度必须为0至100 ℃/100m')

  const version = ++calculationVersion
  calculating.value = true
  comparisonResults.value = []
  renderChart()
  try {
    // 先并发读取各井来源数据，再并发折算；任一环节失败的井只记录原因，不阻断其余井。
    const sources = await Promise.all(selectedWells.value.map(async well => {
      try {
        const source = await loadWellSource(well)
        if (source.error) return { well, error: source.error }
        const calculation = {
          projectId: Number(props.projectId),
          gasReservoirId: Number(props.gasReservoirId),
          wellName: well.wellName,
          operationMode: 'production',
          boundaryPosition: 'wellhead',
          boundaryPressure: source.values.fWh,
          tWh: source.values.tWh,
          qGas: source.values.qGas,
          qLiq: source.values.qLiq,
          depth: Number(depth.value),
          step: Number(step.value),
          idTubing: Number(idTubing.value),
          roughness: Number(roughness.value),
          angle: Number(angle.value),
          tGrad: Number(tGrad.value),
          // 流体物性由后端按所选PVT重新计算并覆盖，此处仅提供默认初值。
          gammaG: 0.65,
          rhoL: 1000,
          muL: 0.9,
          pvtId: source.pvtId,
          models: [method.value],
          productionRecordKey: source.record.key,
          productionDate: selectedDate.value,
          productionChannel: 'tubing'
        }
        const result = unwrap(await wellborePressureApi.calculate(calculation))
        if (version !== calculationVersion) return null
        const methodResult = result?.methods?.[method.value]
        if (!methodResult?.profile?.length) return { well, error: '压力折算接口返回格式不正确' }
        return {
          well,
          ok: true,
          profile: methodResult.profile,
          topPressure: methodResult.profile[0].pressure,
          bottomPressure: methodResult.profile.at(-1).pressure,
          converged: methodResult.allSegmentsConverged,
          qGas: source.values.qGas,
          qLiq: source.values.qLiq,
          fWh: source.values.fWh,
          tWh: source.values.tWh,
          pvtName: source.pvtName
        }
      } catch (error) {
        return { well, error: errorText(error) }
      }
    }))
    if (version !== calculationVersion) return
    comparisonResults.value = sources.filter(Boolean).map((row, index) => ({
      wellId: row.well?.id ?? index,
      wellName: row.well?.wellName || '未知井',
      ...row
    }))
    await nextTick()
    renderChart()
    if (!comparisonResults.value.some(row => row.ok)) {
      // 全部失败时汇总各原因及井数，避免用户只能去摘要表逐行查看。
      const reasonCounts = comparisonResults.value.reduce((map, row) => {
        if (row.error) map.set(row.error, (map.get(row.error) || 0) + 1)
        return map
      }, new Map())
      const reasonText = [...reasonCounts.entries()]
        .map(([reason, count]) => `${reason}（${count}口）`).join('；')
      ElMessage.warning(`没有可展示的折算结果：${reasonText}`)
    }
  } catch (error) {
    if (version === calculationVersion) ElMessage.error(errorText(error))
  } finally {
    if (version === calculationVersion) calculating.value = false
  }
}
</script>

<template>
  <div ref="workspaceEl" class="comparison-workspace">
    <aside class="params-panel water-parameter-theme" :class="{ collapsed: paramsCollapsed }"
      :style="paramsCollapsed ? undefined : { width: panelWidth + 'px', minWidth: panelWidth + 'px', flexBasis: panelWidth + 'px' }">
      <button v-if="paramsCollapsed" class="panel-collapsed-tab" type="button" aria-label="展开参数设置" @click="paramsCollapsed = false">参数设置</button>
      <template v-else>
        <div class="panel-head">
          <span>参数设置</span>
          <button class="panel-toggle" type="button" title="收起参数设置" aria-label="收起参数设置" @click="paramsCollapsed = true">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="#777" aria-hidden="true"><path d="M16,12V4H17V2H7V4H8V12L6,14V16H11.2V22H12.8V16H18V14L16,12Z" /></svg>
          </button>
        </div>
        <div class="panel-body">

          <fieldset class="operation-group">
            <legend>折算方法</legend>
            <div class="operation-options">
              <label v-for="item in comparisonMethods" :key="item.value">
                <input v-model="method" type="radio" name="wellbore-comparison-method" :value="item.value" />{{ item.label }}
              </label>
            </div>
          </fieldset>
          <div class="sec-label">其它数据</div>
          <div class="field-grid">
            <label class="field"><span>折算时间节点</span><input v-model="selectedDate" type="date" /></label>
            <label class="field"><span>测井深度 (m)</span><input v-model="depth" type="text" inputmode="decimal" autocomplete="off" /></label>
            <label class="field"><span>计算步长 (m)</span><input v-model="step" type="text" inputmode="decimal" autocomplete="off" /></label>
            <label class="field"><span>油管内径 (mm)</span><input v-model="idTubing" type="text" inputmode="decimal" autocomplete="off" /></label>
            <label class="field"><span>管内壁粗糙度 (mm)</span><input v-model="roughness" type="text" inputmode="decimal" autocomplete="off" /></label>
            <label class="field"><span>井斜角 (°)</span><input v-model="angle" type="text" inputmode="decimal" autocomplete="off" /></label>
            <label class="field"><span>地温梯度 (℃/100m)</span><input v-model="tGrad" type="text" inputmode="decimal" autocomplete="off" /></label>
          </div>
          <p class="field-hint">井口温压与气水量按所选日期读取各井注采记录；PVT取各井第一条已保存方案。</p>

          <fieldset class="field-section records-section storage-wells-section">
            <legend class="sec-label">参与对比的井（{{ selectedWellIds.length }}）
              <label class="select-all-records"><input type="checkbox" :checked="allWellsSelected" :indeterminate="someWellsSelected"
              :disabled="loadingWells || !filteredWells.length" @change="toggleAllWells" />{{ wellKeyword.trim() ? '全选搜索结果' : '全选' }}</label>
            </legend>
            <label class="field"><input v-model="wellKeyword" type="search" placeholder="搜索井名" aria-label="搜索参与对比的井" /></label>
            <div v-if="loadingWells" class="record-status" role="status">正在读取单井…</div>
            <div v-else-if="wellError" class="record-status record-error" role="alert">{{ wellError }} <button type="button" @click="loadStorageWells">重试</button></div>
            <div v-else-if="!storageWells.length" class="record-status">当前储气库暂无单井</div>
            <div v-else-if="!filteredWells.length" class="record-status">没有匹配的井</div>
            <div v-else class="record-list storage-well-list">
              <label v-for="well in filteredWells" :key="well.id" class="record-row">
                <input v-model="selectedWellIds" type="checkbox" :value="well.id" :aria-label="well.wellName" />
                <span class="record-info"><span class="record-name" :title="well.wellName">{{ well.wellName }}</span></span>
              </label>
            </div>
          </fieldset>
          <div class="form-actions"><button type="button" class="calculate-button" :disabled="(calculating || loadingWells)" @click="handleCalculate">{{ calculating ? '计算中…' : '计算' }}</button></div>
        </div>
        <div class="params-resizer" role="separator" tabindex="0" aria-label="调整参数栏宽度" aria-orientation="vertical"
          :aria-valuenow="panelWidth" :aria-valuemin="238" :aria-valuemax="maxPanelWidth()" @pointerdown="startResize" @keydown="resizeWithKeyboard" />
      </template>
    </aside>
    <main class="result-area">
      <div class="result-tabs"><div class="result-tab active" :title="resultTitle">{{ resultTitle }}</div></div>
      <div class="chart-area"><div ref="chartEl" class="comparison-chart"></div></div>
      <div v-if="summaryRows.length" class="summary-panel">
        <div class="summary-head">折算结果摘要</div>
        <div class="summary-scroll">
          <table class="summary-table">
            <thead>
              <tr>
                <th class="text-left">井名</th>
                <th>折算日期</th>
                <th>日产气量<br />(×10⁴ m³/d)</th>
                <th>日产水量<br />(m³/d)</th>
                <th>井口压力<br />(MPa)</th>
                <th>井口温度<br />(℃)</th>
                <th class="text-left">PVT性质</th>
                <th>井顶压力<br />(MPa)</th>
                <th>井底压力<br />(MPa)</th>
                <th class="text-left">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in summaryRows" :key="row.wellId" :class="{ 'row-error': !row.ok }">
                <td class="text-left">{{ row.wellName }}</td>
                <td>{{ row.date || '—' }}</td>
                <td>{{ formatNumber(row.qGas) }}</td>
                <td>{{ formatNumber(row.qLiq) }}</td>
                <td>{{ formatNumber(row.fWh) }}</td>
                <td>{{ formatNumber(row.tWh) }}</td>
                <td class="text-left">{{ row.pvtName || '—' }}</td>
                <td>{{ formatNumber(row.topPressure) }}</td>
                <td>{{ formatNumber(row.bottomPressure) }}</td>
                <td class="text-left">{{ row.status }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
// 布局与库级产能对比页面保持一致：238px 初始参数栏、34px 标题栏和黄黑交互色。
.comparison-workspace { flex: 1; min-width: 0; min-height: 0; display: flex; overflow: hidden; background: #fff; color: #252525; font: 13px/1.5 "Microsoft YaHei", "Segoe UI", Arial, sans-serif; }
.params-panel { position: relative; min-height: 0; display: flex; flex-direction: column; overflow: hidden; border-right: 1px solid #d7d7d7; background: #fff; }
.params-panel.collapsed { width: 34px; min-width: 34px; flex: 0 0 34px; }
.panel-collapsed-tab { width: 100%; height: 76px; padding: 8px 0; border: 0; border-bottom: 1px solid #e2e6ea; background: #fff; color: #333; font: inherit; writing-mode: vertical-rl; cursor: pointer; }
.panel-head { height: 34px; padding: 0 12px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #d7d7d7; background: #f2f2f2; box-sizing: border-box; }
.panel-toggle { width: 20px; height: 20px; padding: 0; border: 0; display: flex; align-items: center; justify-content: center; background: transparent; cursor: pointer; }
.panel-toggle:hover { background: #fff8d8; }
.panel-body { flex: 1; min-height: 0; padding: 10px 12px 16px; overflow-y: auto; }
.field-section { border: 0; padding: 0; margin: 0 0 9px; min-width: 0; }
.sec-label { width: 100%; height: 22px; padding: 0; margin: 8px 0 7px; display: flex; align-items: center; gap: 8px; font-weight: 500; }
.sec-label::after { content: ''; height: 1px; flex: 1; background: #999; }
.checkbox-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; padding: 3px 0 8px; }
.checkbox-grid label, .operation-options label { display: inline-flex; align-items: center; gap: 5px; font-size: 13px; font-weight: 400; white-space: nowrap; cursor: pointer; }
.checkbox-grid input, .operation-options input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.operation-group { min-width: 0; margin: 8px 0 12px; padding: 0; border: 0; }
.operation-group legend { margin-bottom: 4px; padding: 0; font-size: 13px; font-weight: 400; }
.operation-options { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.field-hint { margin: -2px 0 8px; color: #888; font-size: 11px; line-height: 1.6; }
.record-list { border: 1px solid #ddd; }
.storage-well-list { max-height: 240px; overflow-y: auto; margin-top: 6px; }
.select-all-records { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; font-weight: 400; cursor: pointer; }
.select-all-records input { width: 13px; height: 13px; margin: 0; accent-color: #333; cursor: pointer; }
.select-all-records:has(input:disabled) { color: #999; cursor: default; }
.select-all-records input:disabled { cursor: default; }
.record-row { display: flex; align-items: flex-start; gap: 7px; padding: 7px; border-bottom: 1px solid #eee; cursor: pointer; }
.record-row:last-child { border-bottom: 0; }
.record-row input { accent-color: #333; margin: 3px 0 0; width: 13px; height: 13px; flex-shrink: 0; }
.record-info { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.record-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.record-status { color: #777; font-size: 12px; padding: 8px 0; }
.record-error { color: #b24b37; font-size: 12px; overflow-wrap: anywhere; }
.record-error button { font: inherit; cursor: pointer; }
.calculate-button:disabled { opacity: .6; cursor: wait; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); column-gap: 24px; }
.field { display: block; min-width: 0; margin-bottom: 9px; }
.field span { display: block; margin-bottom: 3px; color: #252525; font-size: 13px; }
.field input { display: block; width: 100%; min-width: 0; height: 24px; padding: 0 8px; box-sizing: border-box; border: 1px solid #aaa; border-radius: 3px; background: #fff; color: #333; font: inherit; }
.field input:focus-visible { outline: 1px solid #d5b900; outline-offset: 1px; }
.form-actions { display: flex; align-items: center; gap: 10px; }
.calculate-button { min-width: 86px; height: 30px; margin: 14px 0 4px; padding: 0 22px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #222; font: inherit; cursor: pointer; }
.calculate-button:hover { background: #ffe033; }
button:focus-visible { outline: 2px solid #555; outline-offset: -2px; }
.params-resizer { position: absolute; z-index: 4; top: 0; right: 0; width: 5px; height: 100%; cursor: col-resize; touch-action: none; }
.params-resizer:hover, .params-resizer:focus-visible { background: #f4d000; outline: none; }
.result-area { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; background: #fff; }
.result-tabs { height: 34px; flex: 0 0 34px; display: flex; align-items: center; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.result-tab { height: 34px; line-height: 34px; max-width: min(430px, 100%); padding: 0 12px; border-right: 1px solid #e4e7ed; box-sizing: border-box; background: #f4d000; color: #202020; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.chart-area { flex: 1; min-height: 0; position: relative; }
.comparison-chart { position: absolute; inset: 0; }
// 摘要表在图表下方，最多占结果区一半高度，超出后内部滚动。
.summary-panel { flex: 0 0 auto; max-height: 46%; min-height: 120px; display: flex; flex-direction: column; border-top: 1px solid #e4e7ed; background: #fff; }
.summary-head { flex: 0 0 30px; line-height: 30px; padding: 0 12px; font-weight: 600; color: #252525; border-bottom: 1px solid #e4e7ed; background: #fafafa; }
.summary-scroll { flex: 1; min-height: 0; overflow: auto; }
.summary-table { width: 100%; border-collapse: collapse; font-size: 12px; color: #333; }
.summary-table th, .summary-table td { padding: 5px 8px; border: 1px solid #e4e7ed; text-align: right; white-space: nowrap; }
.summary-table th { position: sticky; top: 0; background: #f2f2f2; font-weight: 500; color: #555; }
.summary-table .text-left { text-align: left; }
.summary-table tbody tr:nth-child(even) { background: #fafafa; }
.summary-table .row-error td { color: #b24b37; }
</style>
