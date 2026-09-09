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
  <section class="liquid-loading-page" v-loading="busy">
    <header>
      <span>{{ node.wellName }} · 井筒积液</span>
    </header>

    <div class="liquid-loading-body">
      <div class="form-grid four-columns">
      <label class="field">
        <span>压力（P，MPa，绝压）</span>
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
        <span>温度（Tc，℃）</span>
        <input
          v-model.number="form.temperatureC"
          type="number"
          step="any"
          placeholder="请输入"
          @change="refreshLiquidDensity()"
        >
      </label>
      <label class="field">
        <span>气液界面张力（sigma，mN/m）</span>
        <input v-model.number="form.surfaceTensionMnM" type="number" min="0" step="any" placeholder="请输入">
      </label>
      </div>

      <div class="form-grid four-columns lower-row">
      <label class="field">
        <span>标况产气量（qg，10⁴ m³/d）</span>
        <input v-model.number="form.qg" type="number" min="0" step="any" placeholder="从生产数据获取">
      </label>
      <label class="field">
        <span>日产水量（qw，m³/d，可选）</span>
        <input v-model.number="form.qw" type="number" min="0" step="any" placeholder="从生产数据获取">
      </label>
      <label class="field">
        <span>油管内径（d，mm）</span>
        <input v-model.number="form.tubingIdMm" type="number" min="0" step="any" placeholder="从完井数据获取">
      </label>
      <label class="field">
        <span>气体相对密度（gamma_g）</span>
        <input v-model.number="form.gasSpecificGravity" type="number" min="0" step="any" placeholder="从PVT获取">
      </label>
      </div>

      <div class="form-grid four-columns lower-row">
      <label class="field">
        <span>液体密度（rhoL，kg/m³，PVT计算）</span>
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

      <button
        class="calculate-button"
        type="button"
        :disabled="densityBusy"
        @click="calculate"
      >计算</button>

      <div class="result-title">计算结果</div>
      <div class="result-grid">
      <article class="result-card">
        <div class="result-label">临界携液流量</div>
        <div class="result-value">{{ displayValue(result?.criticalRate1e4M3d ?? result?.qCritical) }}</div>
        <button
          class="copy-button"
          type="button"
          aria-label="复制临界携液流量"
          :disabled="!result"
          @click="copyResult(result?.criticalRate1e4M3d ?? result?.qCritical)"
        ><i></i></button>
      </article>
      <article class="result-card">
        <div class="result-label">临界携液流速</div>
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

      <div v-if="result" class="result-actions">
      <button type="button" @click="save">保存结果</button>
      <button type="button" @click="historyVisible = true">历史记录（{{ history.length }}）</button>
      <button type="button" @click="useLatestSources">读取最新井数据</button>
      </div>

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
.liquid-loading-page {
  box-sizing: border-box;
  width: 100%;
  min-height: 100%;
  overflow: auto;
  background: #fff;
  color: #2f2f2f;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 13px;
}

.liquid-loading-page > header {
  display: flex;
  align-items: center;
  height: 34px;
  padding: 0;
  border-bottom: 1px solid #e5e7eb;
  background: #fafafa;
}

.liquid-loading-page > header span {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  height: 34px;
  max-width: 340px;
  padding: 0 14px;
  overflow: hidden;
  border-right: 1px solid #e4e7ed;
  background: #f4d000;
  color: #202020;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.liquid-loading-body {
  padding: 19px 18px 36px;
}

.form-grid { display: grid; gap: 10px; }
.four-columns { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.lower-row { margin-top: 17px; }
.field { display: block; min-width: 0; }
.field > span {
  display: block;
  height: 17px;
  color: #555;
  font-size: 12px;
  line-height: 17px;
}

.field input {
  box-sizing: border-box;
  width: 100%;
  height: 23px;
  padding: 1px 6px;
  border: 1px solid #bfc1c4;
  border-radius: 0;
  outline: none;
  background: #fff;
  color: #555;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 12px;
}

.field input:focus { border-color: #8e8f92; }
.field input[readonly] { background: #f7f7f7; color: #666; }
.field input[type="number"] { appearance: textfield; }
.field input[type="number"]::-webkit-inner-spin-button,
.field input[type="number"]::-webkit-outer-spin-button { margin: 0; appearance: none; }
.field input::placeholder { color: #aaa; }

.calculate-button {
  width: 53px;
  height: 24px;
  margin-top: 18px;
  border: 0;
  border-radius: 3px;
  background: #1d070c;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.calculate-button:hover { background: #321018; }
.calculate-button:disabled { background: #b7afb1; cursor: wait; }
.result-title {
  margin-top: 18px;
  margin-bottom: 7px;
  color: #333;
  font-size: 13px;
  font-weight: 500;
  line-height: 17px;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  width: calc((100% - 10px) / 2);
}

.result-card {
  position: relative;
  box-sizing: border-box;
  height: 64px;
  padding: 13px 12px 8px;
  border: 1px solid #bfc1c4;
  background: #fff;
}

.result-label { color: #555; font-size: 12px; line-height: 16px; }
.result-value { margin-top: 6px; line-height: 16px; font-weight: 600; }

.copy-button {
  position: absolute;
  right: 10px;
  bottom: 11px;
  width: 15px;
  height: 15px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.copy-button::before,
.copy-button i {
  position: absolute;
  box-sizing: border-box;
  width: 10px;
  height: 10px;
  border: 1px solid #8b8d90;
  border-radius: 2px;
  content: "";
}

.copy-button::before { right: 0; bottom: 0; }
.copy-button i { top: 0; left: 0; background: #fff; }
.copy-button:disabled { cursor: default; opacity: .65; }
.result-actions { display: flex; gap: 16px; margin-top: 10px; }

.result-actions button {
  padding: 0;
  border: 0;
  background: transparent;
  color: #6e4c15;
  font: inherit;
  cursor: pointer;
}

.result-actions button:hover { text-decoration: underline; }

@media (max-width: 900px) {
  .four-columns { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .result-grid { width: 100%; }
}
</style>
