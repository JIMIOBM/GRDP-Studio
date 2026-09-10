<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { liquidLoadingApi } from '@/api/wellboreRisk'
import { pvtStorageApi } from '@/api/pvtStorage'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { waterPvtApi } from '@/api/waterPvt'
import { wellborePressureApi } from '@/api/wellborePressure'
import { wellboreTemperatureApi } from '@/api/wellboreTemperature'
import { numberOf, productionValues } from '@/utils/temperatureSources'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const form = reactive({
  qg: null,
  qw: null,
  pressureMpa: null,
  temperatureC: null,
  gasSpecificGravity: null,
  liquidDensityKgM3: null,
  surfaceTensionMnM: null,
  tubingIdMm: null
})

const result = ref(null)
const history = ref([])
const busy = ref(false)
const densityBusy = ref(false)
const historyVisible = ref(false)
const pvtWaterSource = reactive({
  pvtId: null,
  salinity: null,
  volumeFactorMethod: 0,
  compressibilityMethod: 0
})
let densityCalculationSequence = 0

const context = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: props.node.wellName
})
const payload = () => ({
  ...context(),
  qg: Number(form.qg),
  qw: form.qw == null ? null : Number(form.qw),
  pressureMpa: Number(form.pressureMpa),
  temperatureC: Number(form.temperatureC),
  gasSpecificGravity: Number(form.gasSpecificGravity),
  liquidDensityKgM3: Number(form.liquidDensityKgM3),
  surfaceTensionMnM: Number(form.surfaceTensionMnM),
  tubingIdMm: Number(form.tubingIdMm)
})

const parseJson = value => typeof value === 'string' ? JSON.parse(value) : value
const parseResult = detail => parseJson(detail?.result_json ?? detail?.resultJson)
const unwrapResponse = response => response?.data?.data ?? response?.data ?? response ?? {}

const methodIndex = (value, options) => {
  const index = options.indexOf(value)
  return index >= 0 ? index : 0
}

const displayValue = value => {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '-'
  return String(value)
}

function applyResult (value) {
  result.value = value
}

function applyInput (input) {
  if (!input) return
  form.qg = input.qg ?? null
  form.qw = input.qw ?? null
  form.pressureMpa = input.pressureMpa ?? input.P ?? null
  form.temperatureC = input.temperatureC ?? input.Tc ?? null
  form.gasSpecificGravity = input.gasSpecificGravity ?? input.gamma_g ?? null
  form.liquidDensityKgM3 = input.liquidDensityKgM3 ?? input.rhoL ?? null
  form.surfaceTensionMnM = input.surfaceTensionMnM ?? input.sigma ?? null
  form.tubingIdMm = input.tubingIdMm ?? input.d ?? null
}

async function refreshLiquidDensity (options = {}) {
  const notifyFailure = options.notifyFailure === true
  const pressure = Number(form.pressureMpa)
  const temperature = Number(form.temperatureC)
  const salinity = Number(pvtWaterSource.salinity)

  if (
    !pvtWaterSource.pvtId ||
    !Number.isFinite(pressure) ||
    pressure <= 0 ||
    !Number.isFinite(temperature) ||
    !Number.isFinite(salinity) ||
    salinity < 0
  ) {
    return false
  }

  const sequence = ++densityCalculationSequence
  form.liquidDensityKgM3 = null
  densityBusy.value = true
  try {
    const response = await waterPvtApi.calculateCurveTwo({
      projectId: Number(props.projectId),
      salinity,
      originalPressure: pressure,
      temperature,
      pressureStart: pressure,
      pressureEnd: pressure,
      pressureStep: 1,
      volumeFactorMethod: pvtWaterSource.volumeFactorMethod,
      compressibilityMethod: pvtWaterSource.compressibilityMethod
    })
    if (sequence !== densityCalculationSequence) return false

    const density = numberOf(unwrapResponse(response)?.items?.[0]?.density)
    if (density == null || density <= 0) {
      throw new Error('PVT密度接口未返回有效的液体密度')
    }
    form.liquidDensityKgM3 = density
    return true
  } catch (error) {
    if (notifyFailure && sequence === densityCalculationSequence) {
      ElMessage.error(error?.msg || error?.message || '液体密度计算失败')
    }
    return false
  } finally {
    if (sequence === densityCalculationSequence) densityBusy.value = false
  }
}

async function calculate () {
  await refreshLiquidDensity({ notifyFailure: true })
  const requiredFields = [
    ['标况产气量', form.qg],
    ['压力', form.pressureMpa],
    ['温度', form.temperatureC],
    ['气体相对密度', form.gasSpecificGravity],
    ['液体密度', form.liquidDensityKgM3],
    ['气液界面张力', form.surfaceTensionMnM],
    ['油管内径', form.tubingIdMm]
  ]
  const missing = requiredFields.find(([, value]) => value == null || value === '')
  if (missing) return ElMessage.warning(`请输入${missing[0]}`)
  if (Number(form.qg) < 0 || (form.qw != null && Number(form.qw) < 0)) {
    return ElMessage.warning('产气量和日产水量不能小于 0')
  }
  if ([form.pressureMpa, form.gasSpecificGravity, form.liquidDensityKgM3, form.surfaceTensionMnM, form.tubingIdMm].some(value => Number(value) <= 0)) {
    return ElMessage.warning('压力、气体相对密度、液体密度、界面张力和油管内径必须大于 0')
  }

  busy.value = true
  try {
    applyResult(await liquidLoadingApi.calculate(payload()))
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!result.value) return ElMessage.warning('请先计算')
  const { value } = await ElMessageBox.prompt('请输入方案名称', '保存积液计算', {
    inputValue: `积液方案${history.value.length + 1}`
  })
  busy.value = true
  try {
    await liquidLoadingApi.save({ calculationName: value, calculation: payload() })
    await loadHistory()
    ElMessage.success('积液计算已保存')
  } finally {
    busy.value = false
  }
}

async function loadHistory () {
  history.value = await liquidLoadingApi.list(...Object.values(context())) || []
}

async function openRecord (row) {
  const detail = await liquidLoadingApi.detail(row.id, ...Object.values(context()))
  applyResult(parseResult(detail))
  applyInput(parseJson(detail?.input_json ?? detail?.inputJson))
  historyVisible.value = false
}

async function removeRecord (row) {
  await ElMessageBox.confirm(`确认删除“${row.calculationName}”？`, '删除方案', { type: 'warning' })
  await liquidLoadingApi.delete(row.id, ...Object.values(context()))
  await loadHistory()
}

async function copyResult (value) {
  if (value == null) return
  const text = String(value)
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const input = document.createElement('textarea')
    input.value = text
    input.style.position = 'fixed'
    input.style.opacity = '0'
    document.body.appendChild(input)
    input.select()
    document.execCommand('copy')
    input.remove()
  }
  ElMessage.success('已复制')
}

async function useLatestSources (options = {}) {
  const notify = options.notify !== false
  const current = context()
  const loaded = []
  const errors = []
  busy.value = true
  pvtWaterSource.pvtId = null
  pvtWaterSource.salinity = null

  const [sourceResult, pressures, temperatures] = await Promise.allSettled([
    loadTemperatureSources(current.projectId, current.gasReservoirId, current.wellName),
    wellborePressureApi.list(current.projectId, current.gasReservoirId, current.wellName),
    wellboreTemperatureApi.list(current.projectId, current.gasReservoirId, current.wellName)
  ])

  const source = sourceResult.status === 'fulfilled' ? sourceResult.value : null
  if (source) {
    errors.push(...(source.errors || []))

    try {
      const production = productionValues(
        source.production,
        'wellhead',
        source.productionFields
      )
      if (production.qGas != null) {
        form.qg = production.qGas
        loaded.push('产气量')
      }
      if (production.qLiq != null) {
        form.qw = production.qLiq
        loaded.push('产水量')
      }
    } catch (error) {
      errors.push(error?.message || '生产数据读取失败')
    }

    if (source.input?.idTubing != null) {
      form.tubingIdMm = source.input.idTubing
      loaded.push('油管内径')
    }

    const pvt = source.pvtRecords?.at(-1)
    if (pvt?.pvtId || pvt?.id) {
      try {
        const pvtId = pvt.pvtId || pvt.id
        const detail = (
          await pvtStorageApi.getDetail(
            pvtId,
            current.projectId,
            current.gasReservoirId,
            current.wellName
          )
        )?.data
        const gasSpecificGravity = numberOf(detail?.gasInput?.specificGravity)
        const salinity = numberOf(detail?.waterInput?.salinity)
        const waterSettings = parseJson(detail?.settings?.water) || {}
        pvtWaterSource.pvtId = pvtId
        pvtWaterSource.salinity = salinity
        pvtWaterSource.volumeFactorMethod = methodIndex(
          waterSettings.volumeFactorMethod,
          ['McCain方法', 'Standing方法']
        )
        pvtWaterSource.compressibilityMethod = methodIndex(
          waterSettings.compressibilityMethod,
          ['Meehan方法', 'Dodson-Standing方法']
        )
        if (gasSpecificGravity != null) {
          form.gasSpecificGravity = gasSpecificGravity
          loaded.push('气体相对密度')
        }
        if (salinity == null) {
          errors.push('最新PVT缺少地层水矿化度，无法按当前压力和温度计算液体密度')
        }
      } catch (error) {
        errors.push(error?.msg || error?.message || 'PVT物性读取失败')
      }
    }
  } else {
    errors.push(sourceResult.reason?.msg || sourceResult.reason?.message || '生产、完井及PVT数据读取失败')
  }

  try {
    const pressureRows = pressures.value?.data ?? pressures.value ?? []
    if (pressureRows[0]?.id) {
      const detail = (await wellborePressureApi.detail(pressureRows[0].id, current.projectId, current.gasReservoirId, current.wellName))?.data
      if (detail?.record?.boundaryPressureMpa) {
        form.pressureMpa = detail.record.boundaryPressureMpa
        loaded.push('压力')
      }
    }
  } catch (error) {
    errors.push(error?.msg || error?.message || '压力数据读取失败')
  }

  try {
    const temperatureRows = temperatures.value?.data ?? temperatures.value ?? []
    if (temperatureRows[0]?.id) {
      const detail = (await wellboreTemperatureApi.detail(temperatureRows[0].id, current.projectId, current.gasReservoirId, current.wellName))?.data
      if (detail?.record?.bottomFluidTemperatureC != null) {
        form.temperatureC = detail.record.bottomFluidTemperatureC
        loaded.push('温度')
      }
    }
  } catch (error) {
    errors.push(error?.msg || error?.message || '温度数据读取失败')
  }

  if (await refreshLiquidDensity()) loaded.push('液体密度')

  busy.value = false
  if (notify) {
    if (loaded.length) ElMessage.success(`已读取：${[...new Set(loaded)].join('、')}`)
    if (errors.length) ElMessage.warning(errors.join('；'))
  }
}

onMounted(() => {
  loadHistory()
  useLatestSources({ notify: false })
})
</script>

<template>
  <section class="risk-workspace" v-loading="busy">
    <header class="result-tabs">
      <div class="result-tab">{{ node.wellName }} · 井筒积液计算结果</div>
      <div class="header-actions">
        <button type="button" @click="useLatestSources">读取最新井数据</button>
        <button type="button" @click="historyVisible = true">历史记录（{{ history.length }}）</button>
        <button class="primary" type="button" :disabled="!result || busy" @click="save">保存</button>
      </div>
    </header>

    <div class="form-canvas">
      <div class="form-title">请输入计算参数</div>
      <div class="parameter-grid">
      <label class="field">
        <span>压力（MPa，绝压）</span>
        <input
          v-model.number="form.pressureMpa"
          type="number"
          min="0"
          step="any"
          placeholder="请输入"
          @change="refreshLiquidDensity()"
        >
      </label>
      <label class="field">
        <span>温度（℃）</span>
        <input
          v-model.number="form.temperatureC"
          type="number"
          step="any"
          placeholder="请输入"
          @change="refreshLiquidDensity()"
        >
      </label>
      <label class="field">
        <span>气液界面张力（mN/m）</span>
        <input v-model.number="form.surfaceTensionMnM" type="number" min="0" step="any" placeholder="请输入">
      </label>

      <label class="field">
        <span>标况产气量（10⁴m³/d）</span>
        <input v-model.number="form.qg" type="number" min="0" step="any" placeholder="从生产数据获取">
      </label>
      <label class="field">
        <span>日产水量（m³/d，可选）</span>
        <input v-model.number="form.qw" type="number" min="0" step="any" placeholder="从生产数据获取">
      </label>
      <label class="field">
        <span>油管内径（mm）</span>
        <input v-model.number="form.tubingIdMm" type="number" min="0" step="any" placeholder="从完井数据获取">
      </label>
      <label class="field">
        <span>气体相对密度（dless）</span>
        <input v-model.number="form.gasSpecificGravity" type="number" min="0" step="any" placeholder="从PVT获取">
      </label>

      <label class="field">
        <span>液体密度（kg/m³）</span>
        <input
          v-model.number="form.liquidDensityKgM3"
          type="number"
          min="0"
          step="any"
          :readonly="densityBusy"
          :placeholder="densityBusy ? 'PVT计算中...' : '由PVT自动计算'"
        >
      </label>
      </div>

      <div class="calculation-actions">
      <button
        class="calculate"
        type="button"
        :disabled="densityBusy"
        @click="calculate"
      >{{ busy ? '计算中…' : '计 算' }}</button>
      <button type="button" @click="result = null">重 置</button>
      </div>

      <section class="result-card" aria-live="polite">
      <h3>计算结果</h3>
      <div class="result-grid">
      <article class="result-metric">
        <div class="result-label">临界携液流量（10⁴m³/d）</div>
        <div class="result-value">{{ displayValue(result?.criticalRate1e4M3d ?? result?.qCritical) }}</div>
        <button
          class="copy-button"
          type="button"
          aria-label="复制临界携液流量"
          :disabled="!result"
          @click="copyResult(result?.criticalRate1e4M3d ?? result?.qCritical)"
        ><i></i></button>
      </article>
      <article class="result-metric">
        <div class="result-label">临界携液流速（m/s）</div>
        <div class="result-value">{{ displayValue(result?.criticalVelocityMs ?? result?.vCritical) }}</div>
        <button
          class="copy-button"
          type="button"
          aria-label="复制临界携液流速"
          :disabled="!result"
          @click="copyResult(result?.criticalVelocityMs ?? result?.vCritical)"
        ><i></i></button>
      </article>
      </div>

      </section>

      <el-drawer v-model="historyVisible" title="井筒积液历史记录" size="520px">
      <el-table :data="history" size="small" border empty-text="暂无保存记录">
        <el-table-column prop="calculationNo" label="编号" width="70" />
        <el-table-column prop="calculationName" label="方案名称" />
        <el-table-column prop="status" label="判断" width="100" />
        <el-table-column label="操作" width="130">
          <template #default="scope">
            <el-button link @click="openRecord(scope.row)">查看</el-button>
            <el-button link type="danger" @click="removeRecord(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      </el-drawer>
    </div>
  </section>
</template>

<style scoped>
/* 与库损耗评价保持相同的标题栏、表单及结果区基线，仅作用于井筒风险页面。 */
.risk-workspace { width: 100%; height: 100%; min-width: 0; overflow: auto; background: #fff; color: #202020; font: 13px Arial, sans-serif; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; justify-content: space-between; min-height: 34px; padding-right: 8px; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; gap: 12px; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; min-width: 190px; padding: 0 12px; min-height: 34px; border-right: 1px solid #e4e7ed; background: #f4d000; font-weight: 600; box-sizing: border-box; }
.header-actions, .calculation-actions { display: flex; gap: 10px; flex-wrap: wrap; }
button { height: 27px; padding: 0 16px; border: 1px solid #c9cdd3; border-radius: 4px; background: #fff; color: #292929; font: inherit; cursor: pointer; }
button:hover { border-color: #b49a00; }
button:disabled { cursor: not-allowed; opacity: .45; }
button.primary { min-width: 74px; border-color: #202020; background: #202020; color: #fff; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin-bottom: 20px; font-weight: 600; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.field input[readonly] { background: #f5f6f7; color: #606266; }
.field input[type="number"] { appearance: textfield; }
.field input::-webkit-inner-spin-button, .field input::-webkit-outer-spin-button { appearance: none; margin: 0; }
.field input::placeholder { color: #a8abb2; }
.field :deep(.el-input-number) { width: 100%; line-height: 34px; }
.field :deep(.el-input__wrapper) { height: 34px; padding: 0 11px; border-radius: 4px; box-sizing: border-box; box-shadow: 0 0 0 1px #d4d7dc inset; }
.field :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #b49a00 inset, 0 0 0 2px rgba(244,208,0,.14); }
.field :deep(.el-input__inner) { height: 32px; text-align: left; font: 13px Arial, sans-serif; color: #303133; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; min-width: 72px; }
.calculate { border-color: #d5b900; background: #f4d000; }
.subheading { margin: 26px 0 16px; padding-top: 18px; border-top: 1px solid #eceef1; font-size: 13px; font-weight: 600; }
.hint { margin: 0 0 18px; color: #777; font-size: 12px; line-height: 1.6; }
.composition .dropped { opacity: .62; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.result-metric { position: relative; padding-right: 30px; }
.result-value { margin-top: 12px; font-size: 15px; font-weight: 500; }
.copy-button { position: absolute; right: 4px; bottom: 0; width: 20px; height: 20px; padding: 0; border: 0; background: transparent; }
.copy-button::before, .copy-button i { position: absolute; width: 10px; height: 10px; border: 1px solid #8b8d90; border-radius: 2px; content: ""; }
.copy-button::before { right: 0; bottom: 0; }
.copy-button i { top: 3px; left: 3px; background: #f5f5f5; }
.empty-result { color: #909399; }
.risk { margin-bottom: 18px; font-size: 20px; font-weight: 600; }
.risk.high { color: #c83838; }
.risk.critical { color: #b77900; }
.risk.safe { color: #18864b; }
.hydrate-result dl { display: grid; grid-template-columns: minmax(130px, 1fr) minmax(100px, 1fr) minmax(130px, 1fr) minmax(100px, 1fr); margin: 0 0 18px; border-top: 1px solid #e4e7ed; }
.hydrate-result dt, .hydrate-result dd { margin: 0; padding: 12px 8px; border-bottom: 1px solid #e4e7ed; }
.hydrate-result dd { font-size: 15px; }
.hydrate-result p { line-height: 1.6; }
.hydrate-result .method { color: #777; font-size: 12px; }
.hydrate-result :deep(.el-table) { margin-top: 18px; width: 100%; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } .result-tabs { flex-wrap: wrap; } .header-actions { padding: 4px 8px; } .hydrate-result dl { grid-template-columns: 1fr 1fr; } }
@media (max-width: 460px) { .parameter-grid, .result-grid { grid-template-columns: 1fr; } }
</style>
