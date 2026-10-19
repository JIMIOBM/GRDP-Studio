<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { compositionalResult } from './eclipseCompositional'
import { compositionalReinjectionRecords } from './eclipseCompositionalReinjection'
import { buildCompositionalInjectionPressure, matchesCompositionalInjectionPressure, compositionalInjectionPressureResponse } from './eclipseCompositionalInjectionPressure'
const props = defineProps({ run: Object, inspection: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => compositionalReinjectionRecords(props.inspection))
const record = computed(() => records.value[0])
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null && records.value.length === 1)
const target = ref(null), error = ref('')
watch([() => props.run?.id, () => props.inspection], () => { target.value = null; error.value = '' }, { immediate: true })
const submit = () => {
  if (!available.value || props.disabled) return
  const p = buildCompositionalInjectionPressure({ inspection: props.inspection, baselineRunId: props.run.id, well: record.value.injectionWell, targetBhpUpperLimit: target.value })
  if (!p) { error.value = '新注气BHP上限须为大于0且不超过100000000的数字，且不同于原值，单位psia。'; return }
  error.value = ''; emit('run-scenario', p)
}
const scenario = computed(() => matchesCompositionalInjectionPressure(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const result = computed(() => scenario.value ? compositionalResult(props.run, props.inspection) : null)
const baseline = ref(null), loading = ref(false), baselineError = ref('')
let generation = 0
const loadBaseline = async () => {
  const current = ++generation
  baseline.value = null; baselineError.value = ''; loading.value = false
  if (!scenario.value) return
  const id = scenario.value.baselineRunId
  loading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (current !== generation) return
    const b = response?.data
    if (response?.code !== 200 || b?.id !== id || b.modelId !== props.run?.modelId || b.modelVersionId !== props.run?.modelVersionId || b.runType !== 'eclipse' ||
      b.status !== 'SUCCEEDED' || b.resultContract !== 'VALID_FULL' || b.parameters != null || !compositionalResult(b, props.inspection)) {
      baselineError.value = '注气压力方案的基线来源或完整结果核对未通过。'; return
    }
    baseline.value = b
  } catch { if (current === generation) baselineError.value = '注气压力方案基线读取失败，请重新选择运行。' }
  finally { if (current === generation) loading.value = false }
}
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], loadBaseline, { immediate: true })
onBeforeUnmount(() => { generation++ })
const response = computed(() => scenario.value && result.value && baseline.value ? compositionalInjectionPressureResponse(result.value.summary, baseline.value.result.summary, scenario.value, props.inspection) : null)
</script>
<template>
  <section v-if="available" aria-label="E300注气BHP上限编辑">
    <h3>修改注气井BHP上限</h3>
    <p>注气井{{ record.injectionWell }}，原BHP上限{{ record.injectionBhpUpperLimit }} {{ record.pressureUnit }}。原回注比例{{ record.originalFraction }}、销售目标{{ record.salesTarget }} {{ record.rateUnit }}保留。</p>
    <el-form label-position="top" :disabled="disabled" @submit.prevent="submit">
      <el-form-item label="新注气BHP上限（psia）"><el-input-number v-model="target" :controls="false" aria-label="E300新注气BHP上限" /></el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :disabled="target == null" @click="submit">修改注气BHP上限并真实重算</el-button>
    </el-form>
    <p>基线 #{{ run.id }}；来源 {{ inspection.compositionalReinjectionContext.sourceFile }}:{{ record.injectionLineNumber }}。保留原回注比例、气源、分离器和第{{ record.shutAfterDays }}天关井计划。</p>
    <p>注入井{{ record.injectionWell }}上限{{ record.injectionBhpUpperLimit }} {{ record.pressureUnit }}；生产井{{ record.productionWell }}目标{{ record.productionGasTarget }} {{ record.rateUnit }}、压力下限{{ record.productionBhpLowerLimit }} {{ record.pressureUnit }}保留。</p>
  </section>
  <section v-if="scenario && result" aria-label="E300注气压力方案验收">
    <h3>{{ scenario.well }} 注气BHP上限 {{ scenario.originalBhpUpperLimit }} → {{ scenario.targetBhpUpperLimit }} psia</h3>
    <p>基线 #{{ scenario.baselineRunId }}；来源 {{ scenario.sourceFile }}:{{ scenario.lineNumber }}。</p>
    <p v-if="loading">正在读取原注气压力基线…</p>
    <el-alert v-else-if="baselineError || !response" type="warning" :closable="false" :title="baselineError || '缺少唯一且同源的产气、回注、井压或累计曲线，无法核对。'" />
    <template v-else>
      <el-alert :type="run.resultContract === 'VALID_FULL' && response.verified ? response.changed ? 'success' : 'info' : 'warning'" :closable="false"
        :title="run.resultContract !== 'VALID_FULL' ? '部分成功，不能判定完整注气压力方案通过' : !response.consistent ? '实际回注、井压或原关井状态不符合约束，请核对诊断' : !response.completePeriod ? '结果未覆盖原计划全时段，不能确认完整注气压力方案' : !response.verified ? '存在尚未核对的目标与实际差异' : response.changed ? '注气BHP上限已修改，实际回注与压力约束核对通过' : '新注入BHP上限未触及，所核对回注、产气与井压未变化'" />
      <p>原固定销售需求{{ response.salesTarget }} {{ response.flowUnit }}保留；原回注比例{{ record.originalFraction }}保留；第{{ response.shutAfterDays }}天后按原计划关井。注入上限{{ response.injectionBhpUpperLimit }} {{ response.pressureUnit }}、生产下限{{ response.productionBhpLowerLimit }} {{ response.pressureUnit }}保留。</p>
      <p>{{ response.rows.length }}个实际时刻：达到回注目标{{ response.attained }}点、压力受限{{ response.limited }}点、原计划关井{{ response.closed }}点；共有{{ response.common }}点，响应{{ response.changed }}点。</p>
      <p>原生累计回注 {{ response.baselineCumulativeInjection }} → {{ response.final.cumulativeInjection }} {{ response.cumulativeUnit }}。第{{ response.final.timeDays }}天实际回注{{ response.final.actualInjection }} {{ response.flowUnit }}，产气{{ response.final.producedGas }} {{ response.flowUnit }}，生产井压{{ response.final.productionBhp }} {{ response.pressureUnit }}。</p>
      <el-table :data="response.rows" max-height="320" border size="small" aria-label="E300注气压力实际响应">
        <el-table-column prop="timeDays" label="实际天数" /><el-table-column prop="producedGas" :label="`实际产气 ${response.flowUnit}`" />
        <el-table-column prop="availableGas" label="扣销售后可用气" /><el-table-column prop="plannedTarget" label="按比例计算的组目标" />
        <el-table-column prop="baselineInjection" label="基线实际回注" /><el-table-column prop="actualInjection" label="方案实际回注" />
        <el-table-column prop="injectionBhp" :label="`注入井压 ${response.pressureUnit}`" /><el-table-column prop="productionBhp" :label="`生产井压 ${response.pressureUnit}`" />
        <el-table-column prop="state" label="目标与约束核对" />
      </el-table>
      <p v-if="response.rawVectorDifferences === null">原始向量身份或时间不同，不计算全向量差异数。</p>
      <p v-else>完整原始向量中{{ response.rawVectorDifferences.length }}条存在数值差异，包含末位变化，保留原值；共有时刻回注、产气及井压核对不代表全部指标完全相同。</p>
      <el-table v-if="response.rawVectorDifferences?.length" :data="response.rawVectorDifferences" max-height="200" size="small" aria-label="E300注气压力原始向量差异">
        <el-table-column prop="vectorIndex" label="原始列号" /><el-table-column prop="keyword" label="指标" /><el-table-column prop="objectName" label="对象" /><el-table-column prop="changedPoints" label="不同点数" />
      </el-table>
      <p>实际回注按原销售需求和回注比例核对；新上限是限制，不要求全部时刻井压等于上限。共有时刻直接对齐，缺失留空且不插值；累计量取原生FGIT/WGIT。原关井后实际注入为零，组目标仍按原定义展示。下载eclipse-schedule-applied-package.zip，另项目上传直接复算。</p>
    </template>
  </section>
</template>
