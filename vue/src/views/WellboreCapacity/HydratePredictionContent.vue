<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hydrateApi } from '@/api/wellboreRisk'
import request from '@/utils/request'
import { firstPvtSource, pvtComposition } from './pvtSource'
import { wellborePressureApi } from '@/api/wellborePressure'
import { wellboreTemperatureApi } from '@/api/wellboreTemperature'

const props = defineProps({
  node: {
    type: Object,
    required: true
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

const components = [
  ['CH4', 'CH₄'],
  ['C2H6', 'C₂H₆'],
  ['C3H8', 'C₃H₈'],
  ['IC4', 'i-C₄'],
  ['NC4', 'n-C₄'],
  ['IC5', 'i-C₅'],
  ['NC5', 'n-C₅'],
  ['C6', 'C₆'],
  ['C7+', 'C₇+'],
  ['HE', 'He'],
  ['N2', 'N₂'],
  ['O2', 'O₂'],
  ['H2', 'H₂'],
  ['CO2', 'CO₂'],
  ['CO', 'CO'],
  ['H2S', 'H₂S'],
  ['H2O', 'H₂O']
]

const droppedComponentKeys = [
  'IC5',
  'NC5',
  'C6',
  'C7+',
  'HE',
  'H2',
  'CO',
  'H2O'
]


const form = reactive({
  pressureMpa: 50,
  actualTemperatureC: 40,
  fugacityScale: 2,
  pvtId: null,
  pvtSnapshot: null,
  temperatureId: null,
  pressureConversionId: null,
  composition: Object.fromEntries(
    components.map(([key]) => [key, 0])
  )
})

const result = ref(null)
const calculatedInput = ref(null)
watch(form, () => { calculatedInput.value = null }, { deep: true })
const busy = ref(false)
let loadSequence = 0

const context = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: props.node.wellName
})

const payload = () => ({
  ...context(),
  pvtId: form.pvtId,
  pvtSnapshot: form.pvtSnapshot,
  temperatureId: form.temperatureId,
  pressureConversionId: form.pressureConversionId,
  pressureMpa: form.pressureMpa,
  actualTemperatureC: form.actualTemperatureC,
  fugacityScale: form.fugacityScale,
  composition: Object.fromEntries(
    Object.entries(form.composition).filter(([, value]) => Number(value) > 0)
  )
})

const resultComponents = computed(() => {
  if (!result.value) return []

  const matched = Object.entries(result.value.matchedComponents || {}).map(
    ([name, value]) => ({ name, value, kind: '参与' })
  )
  const dropped = Object.entries(result.value.droppedComponents || {}).map(
    ([name, value]) => ({ name, value, kind: '剔除' })
  )

  return [...matched, ...dropped]
})

async function calculate () {
  busy.value = true
  try {
    const calculation = payload()
    const currentResult = await hydrateApi.calculate(calculation)
    await nextTick()
    if (JSON.stringify(calculation) !== JSON.stringify(payload())) return
    result.value = currentResult
    calculatedInput.value = calculation
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!calculatedInput.value) {
    return ElMessage.warning('请先计算')
  }

  const { value } = await ElMessageBox.prompt(
    '请输入方案名称',
    '保存水合物预测',
    { inputValue: '水合物方案' }
  )

  busy.value = true
  try {
    await hydrateApi.save({
      calculationName: value,
      calculation: calculatedInput.value
    })
    ElMessage.success('水合物预测已保存')
  } finally {
    busy.value = false
  }
}

async function useLatestSources () {
  const sequence = ++loadSequence
  busy.value = true
  try {
  const current = context()
  const [pvts, pressures, temperatures] = await Promise.allSettled([
    firstPvtSource(request, current),
    wellborePressureApi.list(
      current.projectId,
      current.gasReservoirId,
      current.wellName
    ),
    wellboreTemperatureApi.list(
      current.projectId,
      current.gasReservoirId,
      current.wellName
    )
  ])

  if (sequence !== loadSequence) return
  if (pvts.status !== 'fulfilled') throw pvts.reason
  const pvtSource = pvts.value
  Object.assign(form.composition, Object.fromEntries(components.map(([key]) => [key, 0])),
    pvtComposition(pvtSource.gasInput))
  form.pvtId = pvtSource.pvtId
  form.pvtSnapshot = pvtSource.pvtSnapshot

  const pressureRows = pressures.value?.data ?? pressures.value ?? []
  const latestPressure = pressureRows[0]

  if (latestPressure?.id) {
    form.pressureConversionId = latestPressure.id

    const detail = (
      await wellborePressureApi.detail(
        latestPressure.id,
        current.projectId,
        current.gasReservoirId,
        current.wellName
      )
    )?.data

    if (sequence !== loadSequence) return
    form.pressureMpa =
      detail?.record?.boundaryPressureMpa ?? form.pressureMpa
  }

  const temperatureRows = temperatures.value?.data ?? temperatures.value ?? []
  const latestTemperature = temperatureRows[0]

  if (latestTemperature?.id) {
    form.temperatureId = latestTemperature.id

    const detail = (
      await wellboreTemperatureApi.detail(
        latestTemperature.id,
        current.projectId,
        current.gasReservoirId,
        current.wellName
      )
    )?.data

    if (sequence !== loadSequence) return
    form.actualTemperatureC =
      detail?.record?.boundaryTemperatureC ??
      form.actualTemperatureC
  }

  ElMessage.success('已关联当前井第一条PVT及温压边界，请补充真实烃组成')
  } catch (error) {
    if (sequence === loadSequence) ElMessage.error(error?.msg || error?.message || '第一条PVT读取失败')
  } finally {
    if (sequence === loadSequence) busy.value = false
  }
}
watch(() => [props.projectId, props.gasReservoirId, props.node.wellName], () => {
  Object.assign(form.composition, Object.fromEntries(components.map(([key]) => [key, 0])))
  form.pvtId = null
  form.pvtSnapshot = null
  form.temperatureId = form.pressureConversionId = null
  result.value = calculatedInput.value = null
  useLatestSources()
}, { immediate: true })
</script>

<template>
  <section class="risk-workspace" v-loading="busy">
    <header class="result-tabs">
      <div class="result-tab">{{ node.wellName }} · 水合物预测结果</div>
      <div class="header-actions">
        <button type="button" @click="useLatestSources">读取最新井数据</button>
        <button class="primary" type="button" :disabled="!calculatedInput || busy" @click="save">保存</button>
      </div>
    </header>

    <main class="form-canvas">
      <div class="form-title">请输入计算参数</div>
      <div class="parameter-grid">
          <label class="field">
            <span>压力 (MPa)</span>
            <el-input-number
              v-model="form.pressureMpa"
              :controls="false"
              :min="0.000001"
            />
          </label>
          <label class="field">
            <span>温度 (℃)</span>
            <el-input-number
              v-model="form.actualTemperatureC"
              :controls="false"
            />
          </label>
        </div>

        <h3 class="subheading">天然气组分</h3>
        <p class="hint">
          H₂S、CO₂、N₂来自当前井第一条PVT性质；其余组成需按真实数据补充，单位为摩尔百分数，总和应为100%。灰色组分不参与hydT2计算。
        </p>

        <div class="parameter-grid composition">
          <label
            v-for="([key, label]) in components"
            :key="key"
            class="field"
            :class="{ dropped: droppedComponentKeys.includes(key) }"
          >
            <span>{{ label }}</span>
            <el-input-number
              v-model="form.composition[key]"
              :disabled="['H2S', 'CO2', 'N2'].includes(key)"
              :controls="false"
              :min="0"
            />
          </label>
        </div>
      <div class="calculation-actions">
        <button class="calculate" type="button" :disabled="busy" @click="calculate">{{ busy ? '计算中…' : '计 算' }}</button>
        <button type="button" @click="result = null">重 置</button>
      </div>
      <section class="result-card hydrate-result" aria-live="polite">
        <h3>预测结果</h3>

        <template v-if="result">
          <div
            class="risk"
            :class="{
              high: result.riskLevel === '高风险',
              critical: result.riskLevel === '临界风险',
              safe: result.riskLevel === '较安全'
            }"
          >
            {{ result.riskLevel }}
          </div>

          <dl>
            <dt>水合物生成温度</dt><dd>{{ result.hydrateTemperatureC.toFixed(2) }} ℃</dd>
            <dt>现场温度</dt><dd>{{ result.actualTemperatureC.toFixed(2) }} ℃</dd>
            <dt>温度裕量</dt><dd>{{ result.temperatureMarginC >= 0 ? '+' : '' }}{{ result.temperatureMarginC.toFixed(2) }} ℃</dd>
            <dt>原始平衡温度</dt><dd>{{ result.rawHydrateTemperatureC.toFixed(2) }} ℃</dd>
          </dl>

          <p>{{ result.riskDescription }}</p>
          <p class="method">
            {{ result.method }}；修正系数 {{ result.temperatureCorrectionFactor }}
          </p>

          <el-table :data="resultComponents" size="small" border>
            <el-table-column prop="name" label="归一化组分" />
            <el-table-column prop="value" label="摩尔分数" />
            <el-table-column prop="kind" label="模型状态" />
          </el-table>
        </template>

        <div v-else class="empty-result">请输入参数并计算</div>
      </section>
    </main>
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
