<script setup>
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { sandProductionApi } from '@/api/sandProduction'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const PROPPANT_OPTIONS = [
  { label: '70/140目石英砂', value: 'quartz-sand', density: 1.7 },
  { label: '复合压裂砂（70/140目石英砂:40/70目陶粒=6:4）', value: 'composite-sand', density: 1.34 }
]

const form = reactive({
  proppantType: 'quartz-sand',
  massT: null,
  densityGCm3: 1.7,
  halfLengthM: null,
  closurePressureMpa: null,
  velocityOverride: false,
  criticalVelocityOverrideMS: null,
  actualRate1e4M3d: null
})

const result = ref(null)
const calculatedInput = ref(null)
watch(form, () => { calculatedInput.value = null }, { deep: true })
const history = ref([])
const busy = ref(false)
const historyVisible = ref(false)

const context = () => ({
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: props.node.wellName
})
const payload = () => ({
  ...context(),
  proppantType: form.proppantType,
  massT: Number(form.massT),
  densityGCm3: Number(form.densityGCm3),
  halfLengthM: Number(form.halfLengthM),
  closurePressureMpa: Number(form.closurePressureMpa),
  criticalVelocityOverrideMS: form.velocityOverride ? Number(form.criticalVelocityOverrideMS) : null,
  actualRate1e4M3d: form.actualRate1e4M3d == null || form.actualRate1e4M3d === '' ? null : Number(form.actualRate1e4M3d)
})

const parseJson = value => typeof value === 'string' ? JSON.parse(value) : value
const parseResult = detail => parseJson(detail?.result_json ?? detail?.resultJson)

const displayValue = value => {
  if (value == null || value === '' || Number.isNaN(Number(value))) return '-'
  return String(value)
}

const riskClass = levelKey => ({ danger: 'high', warn: 'critical', ok: 'safe' })[levelKey] || ''

const defaultDensityOf = type => PROPPANT_OPTIONS.find(item => item.value === type)?.density

function onProppantTypeChange () {
  const density = defaultDensityOf(form.proppantType)
  if (density != null) form.densityGCm3 = density
}

function applyInput (input) {
  if (!input) return
  form.proppantType = input.proppantType ?? 'quartz-sand'
  form.massT = input.massT ?? null
  form.densityGCm3 = input.densityGCm3 ?? defaultDensityOf(form.proppantType) ?? null
  form.halfLengthM = input.halfLengthM ?? null
  form.closurePressureMpa = input.closurePressureMpa ?? null
  form.actualRate1e4M3d = input.actualRate1e4M3d ?? null
  form.velocityOverride = input.criticalVelocityOverrideMS != null
  form.criticalVelocityOverrideMS = input.criticalVelocityOverrideMS ?? null
}

async function calculate () {
  const requiredFields = [
    ['支撑剂质量', form.massT],
    ['堆积密度', form.densityGCm3],
    ['平均裂缝半长', form.halfLengthM],
    ['闭合压力', form.closurePressureMpa]
  ]
  const missing = requiredFields.find(([, value]) => value == null || value === '')
  if (missing) return ElMessage.warning(`请输入${missing[0]}`)
  if ([form.massT, form.densityGCm3, form.halfLengthM, form.closurePressureMpa].some(value => Number(value) <= 0)) {
    return ElMessage.warning('支撑剂质量、堆积密度、平均裂缝半长和闭合压力必须大于 0')
  }
  if (form.velocityOverride) {
    if (form.criticalVelocityOverrideMS == null || form.criticalVelocityOverrideMS === '') {
      return ElMessage.warning('勾选手动覆盖后请输入临界出砂流速')
    }
    if (Number(form.criticalVelocityOverrideMS) <= 0) {
      return ElMessage.warning('临界出砂流速必须大于 0')
    }
  }
  if (form.actualRate1e4M3d != null && form.actualRate1e4M3d !== '' && Number(form.actualRate1e4M3d) < 0) {
    return ElMessage.warning('实际日产气量不能小于 0')
  }

  busy.value = true
  try {
    const calculation = payload()
    const currentResult = await sandProductionApi.calculate(calculation)
    await nextTick()
    if (JSON.stringify(calculation) !== JSON.stringify(payload())) return
    result.value = currentResult
    calculatedInput.value = calculation
  } finally {
    busy.value = false
  }
}

async function save () {
  if (!calculatedInput.value) return ElMessage.warning('请重新计算后保存')
  const { value } = await ElMessageBox.prompt('请输入方案名称', '保存出砂计算', {
    inputValue: `出砂方案${history.value.length + 1}`
  })
  busy.value = true
  try {
    await sandProductionApi.save({ calculationName: value, calculation: calculatedInput.value })
    await loadHistory()
    ElMessage.success('出砂计算已保存')
  } finally {
    busy.value = false
  }
}

async function loadHistory () {
  const current = context()
  const rows = await sandProductionApi.list(...Object.values(current)) || []
  if (JSON.stringify(current) === JSON.stringify(context())) history.value = rows
}

async function openRecord (row) {
  const detail = await sandProductionApi.detail(row.id, ...Object.values(context()))
  result.value = parseResult(detail)
  applyInput(parseJson(detail?.input_json ?? detail?.inputJson))
  historyVisible.value = false
}

async function removeRecord (row) {
  await ElMessageBox.confirm(`确认删除“${row.calculationName}”？`, '删除方案', { type: 'warning' })
  await sandProductionApi.delete(row.id, ...Object.values(context()))
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

function reset () {
  result.value = null
  calculatedInput.value = null
}

onMounted(() => {
  loadHistory()
})
watch(() => [props.projectId, props.gasReservoirId, props.node.wellName], () => {
  result.value = calculatedInput.value = null
  history.value = []
  loadHistory()
})
</script>

<template>
  <section class="risk-workspace" v-loading="busy">
    <header class="result-tabs">
      <div class="result-tab">{{ node.wellName }} · 临界出砂</div>
      <div class="header-actions">
        <button type="button" @click="historyVisible = true">历史记录（{{ history.length }}）</button>
        <button class="primary" type="button" :disabled="!calculatedInput || busy" @click="save">保存</button>
      </div>
    </header>

    <div class="form-canvas">
      <div class="form-title">请输入计算参数</div>
      <div class="parameter-grid">
      <label class="field">
        <span>支撑剂类型</span>
        <el-select v-model="form.proppantType" @change="onProppantTypeChange">
          <el-option
            v-for="option in PROPPANT_OPTIONS"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </label>
      <label class="field">
        <span>支撑剂质量（t）</span>
        <input v-model.number="form.massT" type="number" min="0" step="any" placeholder="请输入">
      </label>
      <label class="field">
        <span>堆积密度（g/cm³）</span>
        <input v-model.number="form.densityGCm3" type="number" min="0" step="any" placeholder="请输入">
      </label>
      <label class="field">
        <span>平均裂缝半长（m）</span>
        <input v-model.number="form.halfLengthM" type="number" min="0" step="any" placeholder="请输入">
      </label>

      <label class="field parameter-row-start">
        <span>闭合压力（MPa）</span>
        <input
          v-model.number="form.closurePressureMpa"
          type="number"
          min="0"
          step="any"
          placeholder="5~50 查表线性插值"
        >
      </label>
      <label class="field">
        <span>临界出砂流速（m/s）</span>
        <div class="velocity-field">
          <el-checkbox v-model="form.velocityOverride">手动覆盖</el-checkbox>
          <input
            v-if="form.velocityOverride"
            v-model.number="form.criticalVelocityOverrideMS"
            type="number"
            min="0"
            step="any"
            placeholder="请输入"
          >
          <span v-else class="velocity-hint">按闭合压力查表插值</span>
        </div>
      </label>
      <label class="field">
        <span>实际日产气量（10⁴m³/d，可选）</span>
        <input v-model.number="form.actualRate1e4M3d" type="number" min="0" step="any" placeholder="用于出砂风险判断">
      </label>
      </div>

      <div class="calculation-actions">
      <button
        class="calculate"
        type="button"
        @click="calculate"
      >{{ busy ? '计算中…' : '计 算' }}</button>
      <button type="button" @click="reset">重 置</button>
      </div>

      <section class="result-card" aria-live="polite">
      <h3>计算结果</h3>
      <template v-if="result">
      <div v-if="result.riskLevel && result.levelKey !== 'none'" class="risk" :class="riskClass(result.levelKey)">
        {{ result.riskLevel }}
      </div>
      <p v-if="result.riskDescription" class="risk-description">{{ result.riskDescription }}</p>
      <div class="result-grid">
        <article class="result-metric">
          <div class="result-label">临界出砂日产气量（10⁴m³/d）</div>
          <div class="result-value">{{ displayValue(result.criticalRate1e4M3d) }}</div>
          <button
            class="copy-button"
            type="button"
            aria-label="复制临界出砂日产气量"
            @click="copyResult(result.criticalRate1e4M3d)"
          ><i></i></button>
        </article>
        <article class="result-metric">
          <div class="result-label">临界出砂流速（m/s）</div>
          <div class="result-value">
            {{ displayValue(result.criticalVelocityMS) }}
            <span class="tag">{{ result.velocityOverridden ? '手动覆盖' : '查表插值' }}</span>
          </div>
        </article>
        <article class="result-metric">
          <div class="result-label">支撑剂体积（m³）</div>
          <div class="result-value">{{ displayValue(result.proppantVolumeM3) }}</div>
        </article>
        <article class="result-metric">
          <div class="result-label">裂缝张开面总面积（m²）</div>
          <div class="result-value">{{ displayValue(result.fractureAreaM2) }}</div>
        </article>
        <article class="result-metric">
          <div class="result-label">实际/临界比值（%）</div>
          <div class="result-value">{{ displayValue(result.ratioPercent) }}</div>
        </article>
      </div>
      <p v-if="result.interpolationNote" class="method">{{ result.interpolationNote }}</p>
      <p class="method">Qg = 8.64 × Ss × vg，公式引自殷洪川等《页岩气井临界出砂产量预测方法》（特种油气藏，2023）</p>
      </template>
      </section>

      <el-drawer v-model="historyVisible" title="出砂计算历史记录" size="520px">
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
/* 与井筒积液/水合物页面保持相同的标题栏、表单及结果区基线。 */
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
.parameter-row-start { grid-column-start: 1; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.field input[readonly] { background: #f5f6f7; color: #606266; }
.field input[type="number"] { appearance: textfield; }
.field input::-webkit-inner-spin-button, .field input::-webkit-outer-spin-button { appearance: none; margin: 0; }
.field input::placeholder { color: #a8abb2; }
.field :deep(.el-select) { width: 100%; }
.field :deep(.el-select__wrapper) { min-height: 34px; padding: 0 11px; border-radius: 4px; box-sizing: border-box; box-shadow: 0 0 0 1px #d4d7dc inset; }
.field :deep(.el-select__wrapper.is-focused) { box-shadow: 0 0 0 1px #b49a00 inset, 0 0 0 2px rgba(244,208,0,.14); }
.velocity-field { display: flex; align-items: center; gap: 10px; min-height: 34px; }
.velocity-field input { flex: 1; }
.velocity-hint { color: #a8abb2; }
.calculation-actions { margin-top: 24px; }
.calculation-actions button { height: 32px; min-width: 72px; }
.calculate { border-color: #d5b900; background: #f4d000; }
.result-card { min-height: 142px; margin-top: 36px; padding: 18px 18px 26px; border: 1px solid #ececec; border-radius: 4px; background: #f5f5f5; box-sizing: border-box; }
.result-card h3 { margin: 0 0 24px; font-size: 13px; }
.result-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.result-metric { position: relative; padding-right: 30px; }
.result-value { margin-top: 12px; font-size: 15px; font-weight: 500; }
.copy-button { position: absolute; right: 4px; bottom: 0; width: 20px; height: 20px; padding: 0; border: 0; background: transparent; }
.copy-button::before, .copy-button i { position: absolute; width: 10px; height: 10px; border: 1px solid #8b8d90; border-radius: 2px; content: ""; }
.copy-button::before { right: 0; bottom: 0; }
.copy-button i { top: 3px; left: 3px; background: #f5f5f5; }
.risk { margin-bottom: 12px; font-size: 20px; font-weight: 600; }
.risk.high { color: #c83838; }
.risk.critical { color: #b77900; }
.risk.safe { color: #18864b; }
.risk-description { margin: 0 0 18px; line-height: 1.6; }
.tag { margin-left: 8px; padding: 1px 6px; border: 1px solid #d4d7dc; border-radius: 3px; color: #777; font-size: 11px; font-weight: 400; vertical-align: 2px; }
.method { margin: 16px 0 0; color: #777; font-size: 12px; line-height: 1.6; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } .result-tabs { flex-wrap: wrap; } .header-actions { padding: 4px 8px; } }
@media (max-width: 460px) { .parameter-grid, .result-grid { grid-template-columns: 1fr; } }
</style>
