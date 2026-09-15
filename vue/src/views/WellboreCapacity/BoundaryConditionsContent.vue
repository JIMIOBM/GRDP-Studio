<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { loadTemperatureSources } from '@/api/temperatureSources'
import { productionValues } from '@/utils/temperatureSources'
import {
  applyWellboreBoundaryDefaults,
  commitWellboreBoundaryValues,
  getWellboreBoundaryState,
  wellboreBoundaryLabels
} from '@/utils/wellboreBoundaryState'

const props = defineProps({
  node: { type: Object, required: true },
  projectId: { type: [Number, String], required: true },
  gasReservoirId: { type: [Number, String], required: true }
})

const context = {
  projectId: Number(props.projectId),
  gasReservoirId: Number(props.gasReservoirId),
  wellName: String(props.node?.wellName ?? '').trim()
}
const boundary = getWellboreBoundaryState(context)
const draft = reactive({ ...boundary.values })
const labels = computed(() => wellboreBoundaryLabels(draft.boundaryPosition))
const loading = ref(false)
const error = ref('')

const fields = computed(() => [
  ['pressure', labels.value.pressure, 0.000001],
  ['temperature', labels.value.temperature, null],
  ['qGas', '日产气量 (×10⁴ m³/d)', 0],
  ['qLiq', '日产水量 (m³/d)', 0]
])

async function loadDefaults () {
  loading.value = true
  error.value = ''
  try {
    const source = await loadTemperatureSources(
      context.projectId,
      context.gasReservoirId,
      context.wellName
    )
    const production = productionValues(source.production, 'wellhead', source.productionFields)
    applyWellboreBoundaryDefaults(boundary, {
      pressure: production.fWh ?? 3.8,
      temperature: production.tWh ?? 30,
      qGas: production.qGas ?? 2.5,
      qLiq: production.qLiq ?? 2
    })
    Object.assign(draft, boundary.values)
    if (source.errors.length) error.value = source.errors.join('；')
  } catch (sourceError) {
    error.value = sourceError?.msg || sourceError?.message || '生产数据读取失败'
  } finally {
    loading.value = false
  }
}

function confirm () {
  commitWellboreBoundaryValues(boundary, draft)
  ElMessage.success('边界条件已同步到温度模型和压力折算')
}

onMounted(loadDefaults)
</script>

<template>
  <section class="boundary-workspace" v-loading="loading">
    <header class="result-tabs">
      <div class="result-tab">{{ node.wellName }} · 边界条件</div>
    </header>

    <div class="form-canvas">
      <div class="form-title">请输入边界条件</div>
      <div class="parameter-grid">
        <label v-for="[key, label, min] in fields" :key="key" class="field">
          <span>{{ label }}</span>
          <input
            :value="draft[key]"
            type="number"
            :min="min"
            step="any"
            placeholder="从生产数据获取"
            @input="draft[key] = $event.target.value === '' ? null : Number($event.target.value)"
          >
        </label>

        <div class="field position-field">
          <span>压力／温度位置</span>
          <el-radio-group
            v-model="draft.boundaryPosition"
            size="small"
          >
            <el-radio-button value="wellhead">井口</el-radio-button>
            <el-radio-button value="bottomhole">井底</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div class="form-actions">
        <button class="confirm-button" type="button" :disabled="loading" @click="confirm">确 定</button>
      </div>

      <el-alert v-if="error" class="source-error" :title="error" type="error" :closable="false" />
    </div>
  </section>
</template>

<style scoped>
.boundary-workspace { width: 100%; height: 100%; min-width: 0; overflow: auto; background: #fff; color: #202020; font: 13px Arial, sans-serif; }
.result-tabs { position: sticky; top: 0; z-index: 2; display: flex; align-items: center; min-height: 34px; border-bottom: 1px solid #e4e7ed; background: #fafafa; box-sizing: border-box; }
.result-tab { display: flex; align-self: stretch; align-items: center; justify-content: center; min-width: 190px; padding: 0 12px; min-height: 34px; border-right: 1px solid #e4e7ed; background: #f4d000; font-weight: 600; box-sizing: border-box; }
.form-canvas { padding: 20px 18px 34px; }
.form-title { margin-bottom: 20px; font-weight: 600; }
.parameter-grid { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 20px 24px; }
.field { display: grid; gap: 8px; min-width: 0; color: #333; }
.field input { width: 100%; height: 34px; padding: 0 11px; border: 1px solid #d4d7dc; border-radius: 4px; background: #fff; box-sizing: border-box; color: #303133; font: inherit; outline: none; }
.field input:focus { border-color: #b49a00; box-shadow: 0 0 0 2px rgba(244,208,0,.14); }
.field input[type="number"] { appearance: textfield; }
.field input::-webkit-inner-spin-button, .field input::-webkit-outer-spin-button { appearance: none; margin: 0; }
.field input::placeholder { color: #a8abb2; }
.position-field { grid-column-start: 1; width: max-content; }
.position-field :deep(.el-radio-group) { height: 34px; }
.position-field :deep(.el-radio-button__inner) { min-width: 64px; }
.form-actions { display: flex; margin-top: 24px; }
.confirm-button { height: 32px; min-width: 72px; padding: 0 16px; border: 1px solid #d5b900; border-radius: 4px; background: #f4d000; color: #292929; font: inherit; cursor: pointer; }
.confirm-button:hover { border-color: #b49a00; }
.confirm-button:disabled { cursor: not-allowed; opacity: .45; }
.source-error { margin-top: 24px; }
@media (max-width: 1250px) { .parameter-grid { grid-template-columns: repeat(3, minmax(170px, 1fr)); } }
@media (max-width: 920px) { .parameter-grid { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
@media (max-width: 460px) { .parameter-grid { grid-template-columns: 1fr; } }
</style>
