<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { compositionalResult } from './eclipseCompositional'
import { compositionalEosPropertyContext, compositionalEosFields, compositionalFluidPropertyRecords, buildCompositionalFluidProperty, matchesCompositionalFluidProperty, compositionalFluidPropertyResponse } from './eclipseCompositionalFluidProperty'
const props = defineProps({ run: Object, inspection: Object, disabled: Boolean, field: { type: String, default: 'PCRIT' } })
const emit = defineEmits(['run-scenario'])
const propertyName = computed(() => ({ MW: '分子量', OMEGAA: 'Ωa系数', OMEGAB: 'Ωb系数' })[props.field] || (props.field === 'ACF' ? '偏心因子' : props.field === 'ZCRIT' ? '临界Z因子' : props.field === 'TCRIT' ? '临界温度' : '临界压力'))
const propertyUnit = computed(() => props.field === 'MW' ? 'lb/lb-M' : ['ACF', 'ZCRIT', 'OMEGAA', 'OMEGAB'].includes(props.field) ? 'dimensionless' : props.field === 'TCRIT' ? 'degR' : 'psia')
const propertyRole = computed(() => props.field === 'MW' ? '平均分子量用于组分摩尔量与质量换算及原模型流体计算。' : ['OMEGAA', 'OMEGAB'].includes(props.field) ? '状态方程系数用于PR流体计算，建议围绕原模型值做小幅调整。' : props.field === 'ACF' ? '偏心因子用于PR状态方程的温度相关项，影响流体相态与产量、压力响应。' : props.field === 'ZCRIT' ? '临界Z因子用于计算组分临界摩尔体积，参与原模型的黏度相关式。' : `${propertyName.value}用于PR状态方程的相态计算。`)
const sourceContext = computed(() => compositionalEosFields.includes(props.field) ? compositionalEosPropertyContext(props.inspection, props.field) : props.inspection?.[props.field === 'ACF' ? 'compositionalAcentricFactorContext' : props.field === 'ZCRIT' ? 'compositionalCriticalZContext' : props.field === 'TCRIT' ? 'compositionalTemperatureContext' : 'compositionalFluidPropertyContext'])
const records = computed(() => compositionalFluidPropertyRecords(props.inspection, props.field))
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null && records.value.length === 9)
const componentIndex = ref(null), target = ref(null), error = ref('')
const selected = computed(() => records.value.find(r => r.componentIndex === componentIndex.value))
watch([() => props.run?.id, () => props.inspection], () => { componentIndex.value = null; target.value = null; error.value = '' }, { immediate: true })
watch(componentIndex, () => { target.value = null; error.value = '' })
const submit = () => {
  if (!available.value || props.disabled) return
  const p = buildCompositionalFluidProperty({ inspection: props.inspection, baselineRunId: props.run.id, componentIndex: componentIndex.value, targetValue: target.value, field: props.field })
  if (!p) { error.value = `请选择组分，新${propertyName.value}${props.field === 'ACF' ? '须为有限数值，绝对值不超过100000000且不同于原值' : '须大于0、不超过100000000且不同于原值'}，单位${propertyUnit.value}。`; return }
  error.value = ''; emit('run-scenario', p)
}
const scenario = computed(() => props.run?.parameters?.field === props.field && matchesCompositionalFluidProperty(props.run?.parameters, props.inspection) ? props.run.parameters : null)
const result = computed(() => scenario.value ? compositionalResult(props.run, props.inspection) : null)
const baseline = ref(null), loading = ref(false), baselineError = ref('')
let generation = 0
const loadBaseline = async () => {
  const current = ++generation
  baseline.value = null; loading.value = false; baselineError.value = ''
  if (!scenario.value) return
  const id = scenario.value.baselineRunId; loading.value = true
  try {
    const response = await softwareIntegrationApi.getRun(id)
    if (current !== generation) return
    const b = response?.data
    if (response?.code !== 200 || b?.id !== id || b.modelId !== props.run?.modelId || b.modelVersionId !== props.run?.modelVersionId || b.runType !== 'eclipse' ||
      b.status !== 'SUCCEEDED' || b.resultContract !== 'VALID_FULL' || b.parameters != null || !compositionalResult(b, props.inspection)) {
      baselineError.value = '组分物性方案的基线来源或完整结果核对未通过。'; return
    }
    baseline.value = b
  } catch { if (current === generation) baselineError.value = '组分物性基线读取失败，请重新选择运行。' }
  finally { if (current === generation) loading.value = false }
}
watch([() => props.run?.id, () => props.run?.parameters, () => props.inspection], loadBaseline, { immediate: true })
onBeforeUnmount(() => { generation++ })
const response = computed(() => scenario.value && result.value && baseline.value ? compositionalFluidPropertyResponse(result.value.summary, baseline.value.result.summary, scenario.value, props.inspection) : null)
</script>
<template>
  <section v-if="available" :aria-label="`E300组分${propertyName}编辑`">
    <h3>修改组分{{ propertyName }} {{ field }}</h3>
    <p>{{ propertyRole }}选择一个组分，只修改它的{{ field }}；其余8个{{ propertyName }}、其它流体属性、初始组分和全部井控保持。</p>
    <p v-if="field === 'ACF'">ACF是无量纲数值，允许零和负值；按原模型的组分定义填写。</p>
    <p v-if="field === 'ZCRIT'">ZCRIT是无量纲数值。当前入口适用于未另设临界体积或专用黏度临界参数的原模型。</p>
    <p v-if="field === 'TCRIT'">FIELD临界温度使用Rankine绝对温标（degR）。原模型数值直接以degR输入，不能按华氏或摄氏温度填写。</p>
    <el-form label-position="top" :disabled="disabled" @submit.prevent="submit">
      <el-form-item label="组分"><el-select v-model="componentIndex" :aria-label="`E300${propertyName}组分`"><el-option v-for="r in records" :key="r.componentIndex" :label="`${r.componentName} · #${r.componentIndex}`" :value="r.componentIndex" /></el-select></el-form-item>
      <p v-if="selected">原{{ selected.componentName }}{{ propertyName }}{{ selected.originalValue }} {{ selected.unit }}；来源 {{ sourceContext.sourceFile }}:{{ selected.lineNumber }}。</p>
      <el-form-item :label="`新${propertyName}（${propertyUnit}）`"><el-input-number v-model="target" :controls="false" :aria-label="`E300新组分${propertyName}`" /></el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :disabled="!selected || target == null" @click="submit">修改组分{{ propertyName }}并真实重算</el-button>
    </el-form>
    <el-table :data="records" max-height="240" size="small" :aria-label="`E300九组分原始${propertyName}`">
      <el-table-column prop="componentIndex" label="组分序号" /><el-table-column prop="componentName" label="组分" />
      <el-table-column prop="originalValue" :label="`原${field}`" /><el-table-column prop="unit" label="单位" /><el-table-column prop="lineNumber" label="来源行" />
    </el-table>
    <p>基线 #{{ run.id }}；当前仅明确单EOS/FIELD九组分PR数组，缺省、多EOS、CO2STORE和复杂跨文件物性另行验证。</p>
  </section>
  <section v-if="scenario && result" aria-label="E300组分物性方案验收">
    <h3>{{ scenario.componentName }} {{ propertyName }} {{ scenario.originalValue }} → {{ scenario.targetValue }} {{ scenario.unit }}</h3>
    <p>{{ field }} · 第{{ scenario.componentIndex }}组分；基线 #{{ scenario.baselineRunId }}；来源 {{ scenario.sourceFile }}:{{ scenario.lineNumber }}。</p>
    <p v-if="loading">正在读取原组分物性基线…</p>
    <el-alert v-else-if="baselineError || !response" type="warning" :closable="false" :title="baselineError || '缺少同源、同单位且按独立列号匹配的原生指标，无法核对。'" />
    <template v-else>
      <el-alert :type="run.resultContract === 'VALID_FULL' && response.verified ? response.changedMetrics ? 'success' : 'info' : 'warning'" :closable="false"
        :title="run.resultContract !== 'VALID_FULL' ? '部分成功，不能判定完整组分物性方案通过' : !response.completePeriod ? '结果未覆盖原基线全时段，不能确认完整对比' : !response.verified ? '缺少可对齐的实际共有时刻' : response.changedMetrics ? `组分${propertyName}已修改，原生结果与基线按列号对齐` : `组分${propertyName}已修改，共有时刻未见所列指标响应`" />
      <p>完整{{ response.rawVectorCount }}条原始曲线保留，{{ response.metrics.length }}个独立产油/压力列参与对比，{{ response.changedMetrics }}列在共有时刻有变化；终止时刻{{ response.endDays }}天。</p>
      <el-table :data="response.metrics" max-height="320" border size="small" aria-label="E300组分物性实际响应">
        <el-table-column prop="vectorIndex" label="原始列号" /><el-table-column prop="keyword" label="指标" />
        <el-table-column label="对象"><template #default="{row}">{{ row.objectName || '全场' }}</template></el-table-column>
        <el-table-column prop="unit" label="单位" /><el-table-column prop="baselineFinal.value" label="基线末值" /><el-table-column prop="currentFinal.value" label="方案末值" />
        <el-table-column label="同终止时刻差值"><template #default="{row}">{{ row.finalDifference ?? '未对齐' }}</template></el-table-column>
        <el-table-column prop="commonPoints" label="共有时刻" /><el-table-column prop="changedPoints" label="不同点数" />
      </el-table>
      <p>同名指标保留独立原始列号；只比较实际共有时刻，不插值、不补零。完整曲线和已有组分结果继续在下方查看，未请求的组分输出不补造。</p>
      <p>下载eclipse-schedule-applied-package.zip，新项目选主SPE3_GASCOND.DATA直接复算。仅所选{{ field }}数字变化，其余物性、组分、几何与完整控制时刻保持。</p>
    </template>
  </section>
</template>
