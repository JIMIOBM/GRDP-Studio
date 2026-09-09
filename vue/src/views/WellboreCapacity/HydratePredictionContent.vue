<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { hydrateApi } from '@/api/wellboreRisk'
import { pvtStorageApi } from '@/api/pvtStorage'
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

const defaultComposition = {
  CH4: 80.52,
  C2H6: 0.04,
  HE: 0.05,
  H2: 0.02,
  N2: 0.75,
  CO2: 6.97,
  H2S: 11.68
}

const form = reactive({
  pressureMpa: 50,
  actualTemperatureC: 40,
  fugacityScale: 2,
  pvtId: null,
  temperatureId: null,
  pressureConversionId: null,
  composition: Object.fromEntries(
    components.map(([key]) => [key, defaultComposition[key] || 0])
  )
})

const result = ref(null)
const busy = ref(false)

const context = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: props.node.wellName
})

const payload = () => ({
  ...context(),
  pvtId: form.pvtId,
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
    result.value = await hydrateApi.calculate(payload())
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!result.value) {
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
      calculation: payload()
    })
    ElMessage.success('水合物预测已保存')
  } finally {
    busy.value = false
  }
}

async function useLatestSources () {
  const current = context()
  const [pvts, pressures, temperatures] = await Promise.allSettled([
    pvtStorageApi.list(
      current.projectId,
      current.gasReservoirId,
      current.wellName
    ),
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

  const pvtRows = pvts.value?.data ?? pvts.value ?? []
  const latestPvt = pvtRows.at(-1)

  if (latestPvt?.pvtId || latestPvt?.id) {
    form.pvtId = latestPvt.pvtId || latestPvt.id

    const detail = (
      await pvtStorageApi.getDetail(
        form.pvtId,
        current.projectId,
        current.gasReservoirId,
        current.wellName
      )
    )?.data
    const gas = detail?.gasInput

    if (gas) {
      form.composition.H2S = Number(gas.hydrogenSulfide) || 0
      form.composition.CO2 = Number(gas.carbonDioxide) || 0
      form.composition.N2 = Number(gas.nitrogen) || 0
      form.composition.CH4 = Math.max(
        0,
        100 -
          form.composition.H2S -
          form.composition.CO2 -
          form.composition.N2
      )
    }
  }

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

    form.actualTemperatureC =
      detail?.record?.bottomFluidTemperatureC ??
      detail?.record?.predictedWellheadTemperatureC ??
      form.actualTemperatureC
  }

  ElMessage.success('已关联当前井最新PVT、压力和温度方案')
}
</script>

<template>
  <section class="hydrate" v-loading="busy">
    <header>
      <span>{{ node.wellName }} · 水合物单点预测</span>
    </header>

    <div class="toolbar">
      <el-button size="small" @click="useLatestSources">
        读取最新井数据
      </el-button>
      <el-button size="small" type="primary" @click="calculate">
        计算
      </el-button>
      <el-button size="small" :disabled="!result" @click="save">
        保存结果
      </el-button>
    </div>

    <div class="top">
      <div class="panel">
        <h3>工况参数</h3>

        <div class="conditions">
          <label>
            <span>压力 (MPa)</span>
            <el-input-number
              v-model="form.pressureMpa"
              :controls="false"
              :min="0.000001"
            />
          </label>
          <label>
            <span>温度 (℃)</span>
            <el-input-number
              v-model="form.actualTemperatureC"
              :controls="false"
            />
          </label>
<!--          <label>-->
<!--            <span>逸度缩放系数</span>-->
<!--            <el-input-number-->
<!--              v-model="form.fugacityScale"-->
<!--              :controls="false"-->
<!--              :min="0.000001"-->
<!--            />-->
<!--          </label>-->
        </div>

        <h3>天然气组分</h3>
        <p class="hint">
          可输入摩尔百分数或摩尔分数；算法自动归一化。灰色标记的组分由原模型识别但不参与 hydT2 计算。
        </p>

        <div class="composition">
          <label
            v-for="([key, label]) in components"
            :key="key"
            :class="{ dropped: droppedComponentKeys.includes(key) }"
          >
            <span>{{ label }}</span>
            <el-input-number
              v-model="form.composition[key]"
              :controls="false"
              :min="0"
            />
          </label>
        </div>
      </div>

      <div class="panel result">
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

        <el-empty v-else description="请输入参数并计算" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.hydrate {
  height: 100%;
  overflow: auto;
  background: #fff;
  color: #333;
  font: 13px "Microsoft YaHei", "Segoe UI", sans-serif;
}

.hydrate > header {
  display: flex;
  align-items: center;
  height: 34px;
  padding: 0;
  border-bottom: 1px solid #e5e7eb;
  background: #fafafa;
}

.hydrate > header span {
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

.toolbar {
  display: flex;
  gap: 8px;
  padding: 12px 16px 0;
}

.toolbar :deep(.el-button + .el-button) {
  margin-left: 0;
}

.toolbar :deep(.el-button) {
  height: 24px;
  padding: 0 13px;
  border: 0;
  border-radius: 3px;
  background: #1d070c;
  color: #fff;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 12px;
  font-weight: 700;
}

.toolbar :deep(.el-button:hover),
.toolbar :deep(.el-button:focus),
.toolbar :deep(.el-button--primary:hover),
.toolbar :deep(.el-button--primary:focus) {
  border: 0;
  background: #321018;
  color: #fff;
}

.toolbar :deep(.el-button.is-disabled),
.toolbar :deep(.el-button.is-disabled:hover) {
  border: 0;
  background: #b7afb1;
  color: #fff;
  cursor: not-allowed;
}

.top {
  display: grid;
  grid-template-columns: minmax(550px, 1.35fr) minmax(400px, 1fr);
  gap: 14px;
  padding: 14px 16px 18px;
}

.panel {
  border: 1px solid #e1e4e8;
  border-radius: 4px;
}

.panel h3 {
  margin: 0;
  padding: 9px 12px;
  border-bottom: 1px solid #eceff2;
  color: #333;
  font-size: 13px;
  font-weight: 500;
}

.conditions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  padding: 14px;
}

.conditions label span,
.composition label span {
  display: block;
  margin-bottom: 4px;
  color: #555;
  font-size: 12px;
}

.conditions :deep(.el-input-number),
.composition :deep(.el-input-number) {
  width: 100%;
}

.hydrate :deep(.el-input-number .el-input__wrapper) {
  box-sizing: border-box;
  min-height: 23px;
  height: 23px;
  padding: 0 6px;
  border-radius: 0;
  background: #fff;
  box-shadow: 0 0 0 1px #bfc1c4 inset;
}

.hydrate :deep(.el-input-number .el-input__wrapper:hover),
.hydrate :deep(.el-input-number .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px #8e8f92 inset;
}

.hydrate :deep(.el-input__inner) {
  height: 21px;
  line-height: 21px;
  color: #555;
  font-family: "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 12px;
  text-align: left !important;
}

.hint {
  margin: 10px 12px 0;
  color: #777;
  font-size: 12px;
}

.composition {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px 14px;
  padding: 12px;
}

.composition .dropped {
  opacity: 0.62;
}

.result {
  padding-bottom: 14px;
}

.risk {
  padding: 18px 14px 4px;
  font-size: 24px;
  font-weight: 700;
}

.risk.high {
  color: #c83838;
}

.risk.critical {
  color: #b77900;
}

.risk.safe {
  color: #18864b;
}

.result dl {
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin: 8px 14px 12px;
  border: 1px solid #edf0f2;
}

.result dt,
.result dd {
  margin: 0;
  padding: 8px;
  border-bottom: 1px solid #edf0f2;
}

.result dd {
  font-weight: 600;
}

.result p {
  margin: 8px 14px;
}

.result .method {
  color: #777;
  font-size: 12px;
}

.result :deep(.el-table) {
  width: calc(100% - 28px);
  margin: 12px 14px;
}

@media (max-width: 1050px) {
  .top {
    grid-template-columns: 1fr;
  }

  .composition {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
