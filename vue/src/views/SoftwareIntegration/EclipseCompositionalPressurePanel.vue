<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { compositionalProductionRecords } from './eclipseCompositionalProduction'
import { compositionalResult } from './eclipseCompositional'
import { buildCompositionalPressure, matchesCompositionalPressure, compositionalPressureResponse } from './eclipseCompositionalPressure'
const props = defineProps({ run: Object, inspection: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => compositionalProductionRecords(props.inspection))
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null && records.value.length > 0)
const selected = ref(''), target = ref(null), error = ref('')
const record = computed(() => records.value.find(r => r.well === selected.value))
const unit = computed(() => props.inspection?.unitSystem === 'FIELD' ? 'psia' : 'barsa')
watch([() => props.run?.id, () => props.inspection], () => { selected.value = ''; target.value = null; error.value = '' }, { immediate: true })
watch(selected, () => { target.value = null; error.value = '' })
const submit = () => {
  if (props.disabled || !available.value) return
  const p = buildCompositionalPressure({ inspection: props.inspection, baselineRunId: props.run.id, well: selected.value, targetBhpLowerLimit: target.value })
  if (!p) { error.value = '请选择生产井；新BHP下限须不同于原值，大于0且不超过100000000，使用原模型压力单位。'; return }
  error.value = ''; emit('run-scenario', p)
}
const scenario = computed(() => matchesCompositionalPressure(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const result = computed(() => compositionalResult(props.run, props.inspection))
const baseline = ref(null), loading = ref(false), baselineError = ref('')
let generation = 0
const loadBaseline = async () => {
  const current = ++generation
  baseline.value = null; loading.value = false; baselineError.value = ''
  if (!scenario.value) return
  const id = scenario.value.baselineRunId
  loading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (current !== generation) return
    const b = response?.data
    if (response?.code !== 200 || b?.id !== id || b.modelId !== props.run?.modelId || b.modelVersionId !== props.run?.modelVersionId || b.runType !== 'eclipse' ||
      b.status !== 'SUCCEEDED' || b.resultContract !== 'VALID_FULL' || b.parameters != null || !compositionalResult(b, props.inspection)) {
      baselineError.value = '生产压力方案的基线来源或完整结果核对未通过。'; return
    }
    baseline.value = b
  } catch { if (current === generation) baselineError.value = '生产压力方案基线读取失败，请重新选择运行。' }
  finally { if (current === generation) loading.value = false }
}
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], loadBaseline, { immediate: true })
onBeforeUnmount(() => { generation++ })
const response = computed(() => scenario.value && result.value && baseline.value ? compositionalPressureResponse(result.value.summary, baseline.value.result.summary, scenario.value, props.inspection) : null)
</script>
<template>
  <section v-if="available" aria-label="E300生产BHP下限编辑">
    <h3>修改组分模型生产井BHP下限</h3>
    <p>以当前完整成功运行 #{{ run.id }} 为基线，只改已有压力下限，保留产气目标、组分物性、状态方程、回注及原计划。</p>
    <el-form label-position="top" :disabled="disabled">
      <el-form-item label="生产井"><el-select v-model="selected" aria-label="E300生产压力井" placeholder="选择已有GRAT生产井"><el-option v-for="r in records" :key="r.well" :label="r.well" :value="r.well" /></el-select></el-form-item>
      <template v-if="record">
        <p>原BHP下限 {{ record.bhpLimit }} {{ unit }}；保留产气目标 {{ record.originalGasRate }} {{ record.unit }}。</p>
        <p>来源 {{ inspection.compositionalProductionContext.sourceFile }}:{{ record.bhpLineNumber }}；产气目标来源第{{ record.lineNumber }}行。</p>
        <el-form-item :label="`新BHP下限（${unit}）`"><el-input-number v-model="target" :controls="false" aria-label="E300新生产BHP下限" /></el-form-item>
      </template>
      <el-alert v-if="error" type="error" :title="error" :closable="false" />
      <el-button type="primary" :disabled="!record || target == null" @click="submit">修改生产BHP下限并真实重算</el-button>
    </el-form>
    <p>提高压力下限可能限制产气。每项从原基线独立比较，实际产量读取原生WGPR；下载完整输入工程可另项目复算。</p>
  </section>
  <section v-if="scenario && result" aria-label="E300生产压力方案验收">
    <h3>{{ scenario.well }} BHP下限 {{ scenario.originalBhpLowerLimit }} → {{ scenario.targetBhpLowerLimit }} {{ scenario.unit }}</h3>
    <p>基线 #{{ scenario.baselineRunId }}；来源 {{ scenario.sourceFile }}:{{ scenario.lineNumber }}。</p>
    <p v-if="loading">正在读取原组分模型基线…</p>
    <el-alert v-else-if="baselineError || !response" type="warning" :closable="false" :title="baselineError || '缺少唯一且同源的实际产气或井压曲线，无法核对压力响应。'" />
    <template v-else>
      <el-alert :type="run.resultContract === 'VALID_FULL' && response.verified ? 'success' : 'warning'" :closable="false"
        :title="run.resultContract !== 'VALID_FULL' ? '部分成功，不能判定完整压力方案通过' : !response.consistent ? '实际井压或产气超出方案约束，请核对诊断' : response.inactive ? '新BHP下限未触及，实际产气仍达到原目标' : response.verified ? '新BHP下限与实际产气、井压响应已核对' : '未观察到可核对的共有时刻响应'" />
      <p>保留产气目标 {{ response.originalGasTarget }} {{ response.flowUnit }}；本方案BHP下限 {{ response.bhpLowerLimit }} {{ response.pressureUnit }}。共{{ response.rows.length }}个实际时刻，压力受限{{ response.limited }}点，共有时刻响应{{ response.changed }}点。</p>
      <p>末次第{{ response.finalTime }}天：实际产气 {{ response.finalGasRate }} {{ response.flowUnit }}，井底压力 {{ response.finalBhp }} {{ response.pressureUnit }}。</p>
      <p v-if="response.cumulative">原生WGPT累计产气 {{ response.cumulative.value }} {{ response.cumulative.unit }}（第{{ response.cumulative.timeDays }}天）。</p>
      <p v-else>原工程未请求WGPT，不根据产气目标补算累计量。</p>
      <el-table :data="response.rows" max-height="300" border size="small" aria-label="E300生产压力实际响应">
        <el-table-column prop="timeDays" label="实际天数" /><el-table-column prop="baselineGasRate" :label="`基线产气 ${response.flowUnit}`" /><el-table-column prop="gasRate" :label="`实际产气 ${response.flowUnit}`" />
        <el-table-column prop="baselineBhp" :label="`基线井压 ${response.pressureUnit}`" /><el-table-column prop="bhp" :label="`实际井压 ${response.pressureUnit}`" /><el-table-column prop="pressureLimited" label="压力受限" />
      </el-table>
      <p>仅比较原生共有时刻，缺失时刻留空且不插值。下载eclipse-schedule-applied-package.zip，另项目上传无参数直接计算，应复现物理曲线。</p>
    </template>
  </section>
</template>
