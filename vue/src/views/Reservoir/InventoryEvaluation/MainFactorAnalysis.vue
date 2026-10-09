<script setup>
/**
 * 库级「主控因素分析」。
 *
 * 只比较**地层压力**一个因素：
 *   理论值 —— 原平台「物质平衡方程 → 计算地层压力」工具箱
 *   实际值 —— 库内实测静压（没有则退到静态压力数据表）
 *   差异   —— 实际 − 理论，另附百分比偏差
 *
 * 布局与交互照「微观损耗」：顶部功能区（导入PVT / 保存）+ 参数网格 + 计算/重置 + 计算结果。
 *
 * 参数在界面上用**界面口径**（MPa / ℃ / 10⁸m³ / % / MPa⁻¹），与微观损耗以及原平台
 * 自己的表单一致——用户看到的量级应该是 50 MPa，而不是 50000000 Pa。
 * 提交前由 toAppInputs 换算回后端口径；**提交给原平台的载荷仍由后端
 * StorageMainFactorCalculator.toolboxPayload 一处组装**，前端不参与。
 */
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { storageMainFactorApi } from '@/api/storageMainFactor'
import {
  buildPressureChartSeries,
  differenceDirection,
  formatComputedValue,
  toAppInputs,
  toDisplayInputs
} from '@/utils/storageMainFactor'
import NaturalGasImportDialog from '@/views/DataManagement/NaturalGasImportDialog.vue'

const props = defineProps({
  reservoir: { type: Object, default: null }
})

const scope = computed(() => ({
  projectId: props.reservoir?.projectId,
  gasReservoirId: props.reservoir?.gasReservoirId,
  storageId: props.reservoir?.storageId
}))

const title = computed(() => `${props.reservoir?.label || '未选择库'}-主控因素分析`)

/**
 * 参数一律是界面口径。下拉框用原生 select（与微观损耗一致），
 * 取值就是后端 GasPvtParam 的枚举码。
 */
const form = reactive({
  gasReservoirType: 0,
  originalPressure: '',
  formationTemperature: '',
  originalGasInPlace: '',
  cumulativeGasProduction: '',
  rockCompressionCoefficient: '',
  waterCompressionCoefficient: '',
  waterSaturation: '',
  gasType: 0,
  specificGravity: '',
  h2SMoleFraction: '',
  co2MoleFraction: '',
  n2MoleFraction: '',
  modificationMethod: 0,
  deviationFactorMethod: 0
})

const importDialogVisible = ref(false)
const importedFileName = ref('')
const loading = ref(false)
const calculating = ref(false)
const saving = ref(false)
const error = ref('')
/** 上次计算的结果（CalculateResult）。理论值、实际值、差异只在点过「计算」之后才有。 */
const result = ref(null)
/** 结果是否因为参数被改过而作废（与"从没算过"要分开说，用户的下一步动作不同）。 */
const resultStale = ref(false)

/** 地层压力对比图：相对理论值 / 绝对值两口径可切换。序列由 utils 的纯函数给出。 */
const chartMode = ref('relative')
const chartRef = ref(null)
let chart = null

const renderChart = () => {
  if (!chartRef.value) return
  chart?.dispose()
  chart = echarts.init(chartRef.value)
  const series = buildPressureChartSeries(
    result.value?.formationPressure,
    result.value?.measuredPressure?.value,
    chartMode.value
  )
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, data: ['理论值', '实际值'] },
    grid: { left: 64, right: 24, top: 40, bottom: 44 },
    xAxis: { type: 'category', data: ['地层压力'] },
    yAxis: { type: 'value', name: series.yName },
    series: [
      { name: '理论值', type: 'bar', barWidth: 44, data: [series.theoretical] },
      { name: '实际值', type: 'bar', barWidth: 44, data: [series.actual] }
    ]
  })
}

const setChartMode = async mode => {
  chartMode.value = mode
  await nextTick()
  renderChart()
}

let active = true
let loadVersion = 0
onBeforeUnmount(() => {
  active = false
  loadVersion++
  chart?.dispose()
  chart = null
})

const unwrap = response => response?.data ?? response

const applyInputs = inputs => {
  const display = toDisplayInputs(inputs)
  if (display) Object.assign(form, display)
}

/** 重置只撤销本次计算结果，保留用户已填写或已导入的全部参数。 */
const resetCalculation = () => {
  result.value = null
  error.value = ''
}

const load = async () => {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  result.value = null
  importedFileName.value = ''
  resultStale.value = false
  try {
    // 这一趟**只用来校验库范围**，并在库数据异常时立刻报错；
    // 刻意**不预填表单**：参数表与原平台 MBE 表单对齐后，用户从"请输入"开始自己填
    // （与「微观损耗」一致）。理论值、实际值、差异也都要点过「计算」才出现。
    await storageMainFactorApi.context(scope.value)
    if (!active || version !== loadVersion) return

    // 保存过就用保存值覆盖预填：用户上次看到的参数与结果要能原样恢复。
    //
    // 这一段**单独 catch**：读"已保存记录"失败（迁移脚本还没执行、或该库从未保存过）
    // 是正常状态，不能把上面已经读到的库内数据一起判死——否则每次打开页面都是一片红。
    try {
      const saved = unwrap(await storageMainFactorApi.saved(scope.value))
      if (!active || version !== loadVersion || !saved) return
      applyInputs(saved.inputs)
      // 差异与百分比偏差由后端在读取时重算（它们不入库）：
      // 不带上这两个字段，重新打开就会看到"两侧都有值、差异却是 —"。
      result.value = {
        formationPressure: saved.theoreticalPressure,
        measuredPressure: { value: saved.actualPressure, source: 'AUTO', note: null },
        difference: saved.difference,
        deviationPercent: saved.deviationPercent
      }
    } catch (e) {
      // 忽略：没有已保存记录不影响本次读取
    }
  } catch (e) {
    if (active && version === loadVersion) error.value = e?.message || '读取库内物质平衡输入失败'
  } finally {
    if (active && version === loadVersion) loading.value = false
  }
}

const calculate = async () => {
  if (calculating.value || saving.value) return
  if (!(scope.value.storageId > 0)) {
    ElMessage.warning('请先选择具体储气库')
    return
  }
  // 与「微观损耗」同一套校验：先看必填、再看数值格式，不过就同一句提示。
  // 放在调接口之前，是为了不用户等一次 400 往返才知道少填了东西。
  const raw = Object.values(form)
  if (raw.some(value => value === '' || value === null || value === undefined)
    || !raw.every(value => Number.isFinite(Number(value)))) {
    ElMessage.warning('请完整填写计算参数')
    return
  }
  calculating.value = true
  error.value = ''
  result.value = null
  try {
    const response = unwrap(await storageMainFactorApi.calculate({
      ...scope.value,
      inputs: toAppInputs(form)
    }))
    if (!active) return
    result.value = response
    resultStale.value = false
  } catch (e) {
    if (active) error.value = e?.message || '计算失败'
  } finally {
    if (active) calculating.value = false
  }
}

const save = async () => {
  if (!result.value) {
    // 两种"没结果"要说清楚是哪一种：参数改过（旧结果已作废）与从没算过，
    // 用户的下一步动作不一样。
    ElMessage.warning(resultStale.value ? '参数已修改，请重新计算后再保存' : '请先计算，再保存')
    return
  }
  saving.value = true
  try {
    await storageMainFactorApi.save({
      ...scope.value,
      inputs: toAppInputs(form),
      theoreticalPressure: result.value.formationPressure ?? null,
      actualPressure: result.value.measuredPressure?.value ?? null
    })
    // 理论值为空也允许保存（平台可能只是暂时不可用），但要说明存下去的是什么。
    if (result.value.formationPressure === null || result.value.formationPressure === undefined) {
      ElMessage.warning('已保存；本次没有理论地层压力（原平台不可用），该字段存为空')
    } else {
      ElMessage.success('已保存')
    }
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * 导入的是「天然气基础数据」：第一列气型，随后四列比重与三个组分（百分数）。 */
const handleGasImport = async ({ file, options }) => {
  try {
    const extension = file.name.split('.').pop()?.toLowerCase()
    if (!['xlsx', 'xls', 'csv'].includes(extension)) throw new Error('仅支持 .xlsx、.xls、.csv 表格文件')
    const XLSX = await import('xlsx')
    const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
    const sheet = workbook.Sheets[workbook.SheetNames[0]]
    let rows = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: true, defval: '' })
    if (options?.removeEmptyRows) rows = rows.filter(row => row.some(value => String(value).trim()))
    const row = rows.slice(1).find(item => item.some(value => String(value).trim()))
    if (!row) throw new Error('文件中没有可导入的天然气基础数据')
    const gasTypes = { 干气: 0, 湿气: 1, 凝析气: 2 }
    if (!(String(row[0]).trim() in gasTypes)) throw new Error('天然气类型只能为干气、湿气或凝析气')
    const numbers = row.slice(1, 5).map(Number)
    if (!numbers.every(Number.isFinite)) throw new Error('天然气比重及气体组分必须是有效数字')
    Object.assign(form, {
      gasType: gasTypes[String(row[0]).trim()],
      specificGravity: numbers[0],
      h2SMoleFraction: numbers[1],
      co2MoleFraction: numbers[2],
      n2MoleFraction: numbers[3]
    })
    importedFileName.value = file.name
    result.value = null
    ElMessage.success('PVT基础数据导入成功')
  } catch (e) {
    ElMessage.error(e?.message || 'PVT数据导入失败')
  }
}

/** 参数一改，上次结果就作废，避免"保存"把改动前的计算值一起存下去。 */
watch(form, () => {
  if (result.value) resultStale.value = true
  result.value = null
}, { deep: true, flush: 'sync' })

// 结果一出现就画图；结果作废时容器被 v-if 移除，实例同时释放。
watch(result, async value => {
  if (!value) {
    chart?.dispose()
    chart = null
    return
  }
  await nextTick()
  renderChart()
})
watch(() => [scope.value.projectId, scope.value.gasReservoirId, scope.value.storageId], load, { immediate: true })
</script>

<template>
  <section class="main-factor">
    <!-- 顶部功能区：标题、PVT 导入入口、保存。与「微观损耗」同一套结构。 -->
    <header class="result-tabs">
      <div class="result-tab" :title="title"><span>{{ title }}</span></div>
      <div class="header-actions">
        <button class="secondary" type="button" @click="importDialogVisible = true">导入PVT</button>
        <button class="primary" type="button" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
      </div>
    </header>

    <main class="form-canvas">
      <div class="form-title">请输入计算参数</div>

      <div v-if="error" class="form-error" role="alert">{{ error }}</div>
      <div v-else-if="loading" class="form-loading" role="status">正在读取库内井的物质平衡输入…</div>

      <div class="parameter-grid">
        <!-- 气藏类型与天然气物性用下拉框，其余按公式需要的量手填或由库内预填。 -->
        <label class="field"><span>气藏类型</span>
          <select v-model.number="form.gasReservoirType">
            <option :value="0">封闭气藏</option>
            <option :value="1">定容气藏</option>
            <option :value="2">页岩气藏</option>
          </select>
        </label>
        <label class="field"><span>原始地层压力（MPa）</span><input v-model="form.originalPressure" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>地层温度（℃）</span><input v-model="form.formationTemperature" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>动态地质储量（10⁸m³）</span><input v-model="form.originalGasInPlace" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>累产气量（10⁸m³）</span><input v-model="form.cumulativeGasProduction" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>岩石压缩系数（MPa⁻¹）</span><input v-model="form.rockCompressionCoefficient" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>地层水压缩系数（MPa⁻¹）</span><input v-model="form.waterCompressionCoefficient" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>束缚水饱和度（%）</span><input v-model="form.waterSaturation" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>天然气类型</span>
          <select v-model.number="form.gasType">
            <option :value="0">干气</option>
            <option :value="1">湿气</option>
            <option :value="2">凝析气</option>
          </select>
        </label>
        <label class="field"><span>天然气比重（dless）</span><input v-model="form.specificGravity" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>H₂S摩尔百分含量（%）</span><input v-model="form.h2SMoleFraction" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>CO₂摩尔百分含量（%）</span><input v-model="form.co2MoleFraction" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>N₂摩尔百分含量（%）</span><input v-model="form.n2MoleFraction" placeholder="请输入" inputmode="decimal" /></label>
        <label class="field"><span>非烃气体修正方法</span>
          <select v-model.number="form.modificationMethod">
            <option :value="0">Wichert-Aziz 修正方法</option>
            <option :value="1">Carr-Kobayashi-Burrows 方法</option>
          </select>
        </label>
        <label class="field"><span>天然气偏差系数计算方法</span>
          <select v-model.number="form.deviationFactorMethod">
            <option :value="0">Dranchuk-Abu-Kassem 方法</option>
            <option :value="1">Dranchuk-Purvis-Robinson 方法</option>
            <option :value="2">Hall-Yarborough 方法</option>
          </select>
        </label>
      </div>

      <div v-if="importedFileName" class="import-note">已导入：{{ importedFileName }}</div>

      <div class="calculation-actions">
        <button class="calculate" type="button" :disabled="calculating" @click="calculate">{{ calculating ? '计算中…' : '计 算' }}</button>
        <button class="reset" type="button" @click="resetCalculation">重 置</button>
      </div>

      <!-- 计算结果：只保留地层压力的理论值 / 实际值 / 差异。 -->
      <section class="result-card">
        <h3>计算结果</h3>
        <div class="result-line">
          <span>地层压力（MPa）：</span>
          <span>理论值 <strong>{{ formatComputedValue(result?.formationPressure) }}</strong></span>
          <span>实际值 <strong>{{ formatComputedValue(result?.measuredPressure?.value) }}</strong></span>
          <span class="difference" :class="(differenceDirection(result?.difference) || '').toLowerCase()">
            差异 <strong>{{ formatComputedValue(result?.difference) }}</strong>
            <template v-if="result?.deviationPercent !== null && result?.deviationPercent !== undefined">
              （{{ formatComputedValue(result.deviationPercent) }}%）
            </template>
          </span>
        </div>
        <!-- 结果区只保留数值与对比图：不显示任何解释性文字。 -->
        <template v-if="result">
          <div class="chart-head">
            <span>理论 vs 实际</span>
            <button class="mode" :class="{ active: chartMode === 'relative' }" type="button" @click="setChartMode('relative')">相对理论值</button>
            <button class="mode" :class="{ active: chartMode === 'absolute' }" type="button" @click="setChartMode('absolute')">绝对值</button>
          </div>
          <div ref="chartRef" class="chart"></div>
        </template>
      </section>
    </main>

    <NaturalGasImportDialog v-model="importDialogVisible" import-kind="data" @confirm="handleGasImport" />
  </section>
</template>

<style scoped>
/* 与「微观损耗」同一套字体基线与控件尺寸，两个页面看起来才是同一套设计。 */
.main-factor { height: 100%; min-width: 760px; overflow: auto; background: #fff; color: #202020; font-family: Arial, sans-serif; font-size: 13px; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; height: 34px; padding: 0 8px 0 0; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; max-width: 430px; min-width: 190px; padding: 0 12px; overflow: hidden; border: 0; border-right: 1px solid #e4e7ed; background: #f4d000; color: #202020; font: 600 13px Arial, sans-serif; text-align: center; text-overflow: ellipsis; white-space: nowrap; box-sizing: border-box; }
.result-tab > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.header-actions, .calculation-actions { display: flex; gap: 10px; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: wait; opacity: .65; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin-bottom: 20px; font-size: 13px; font-weight: 600; }
.form-error { margin-bottom: 16px; padding: 8px 12px; border: 1px solid #fde2e2; border-radius: 4px; background: #fef0f0; color: #f56c6c; }
.form-loading { margin-bottom: 16px; color: #909399; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input, .field select { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus, .field select:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.import-note { margin-top: 14px; color: #777; font-size: 12px; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; }
.calculate { min-width: 72px; border-color: #d5b900; background: #f4d000; }
.reset { min-width: 72px; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-line { display: flex; align-items: baseline; flex-wrap: wrap; gap: 6px 22px; color: #333; }
.result-line strong { font-size: 15px; font-weight: 500; }
/* 实际低于理论为负：绿色；高于理论为正：红色。 */
.difference.negative strong { color: #2f9e44; }
.difference.positive strong { color: #e03131; }
.result-note { margin: 14px 0 0; color: #909399; font-size: 12px; }
/* 对比图压缩到左侧：只有一个因素，铺满整行会留下大片空白，横线也拉得过长。 */
.chart-head { display: flex; align-items: center; gap: 8px; margin: 24px 0 0; color: #333; }
.chart-head > span { margin-right: 4px; }
button.mode { height: 24px; padding: 0 12px; font-size: 12px; }
button.mode.active { border-color: #d5b900; background: #f4d000; }
.chart { width: 380px; max-width: 100%; height: 260px; margin-top: 10px; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
</style>
