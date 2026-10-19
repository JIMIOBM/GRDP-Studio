<script setup>
import { computed, ref, watch } from 'vue'
import { thermalInjectionRecords, thermalInjectionLabels, buildThermalInjection, thermalStageDays, thermalStageField } from './eclipseThermalInjection'
const props = defineProps({ inspection: Object, run: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => thermalInjectionRecords(props.inspection))
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null && records.value.length > 0)
const startTimeDays=ref(0),stageDays=computed(()=>thermalStageField(field.value)?thermalStageDays(props.inspection):[])
const selected = ref(''), field = ref('STEAM_QUALITY'), target = ref(null), error = ref('')
const record = computed(() => records.value.find(r => r.well === selected.value))
const unitLabel = computed(() => field.value === 'STEAM_QUALITY' ? '无量纲，0–1' : field.value === 'SURFACE_INJECTION_RATE' ? record.value?.rateUnit : field.value === 'BHP_UPPER_LIMIT' ? props.inspection?.unitSystem === 'FIELD' ? 'psia' : 'barsa' : record.value?.temperatureUnit === 'degF' ? '°F' : '°C')
const sourceLine = computed(() => ['SURFACE_INJECTION_RATE', 'BHP_UPPER_LIMIT'].includes(field.value) ? record.value?.controlLineNumber : record.value?.lineNumber)
watch([() => props.run?.id, () => props.inspection], () => { startTimeDays.value=0; selected.value = ''; field.value = 'STEAM_QUALITY'; target.value = null; error.value = '' }, { immediate: true })
watch([selected, field], () => { startTimeDays.value=0; target.value = null; error.value = '' })
watch(startTimeDays,()=>{target.value=null;error.value=''})
const submit = () => {
  if (props.disabled || !available.value) return
  const p = buildThermalInjection({ inspection: props.inspection, baselineRunId: props.run.id, well: selected.value, field: field.value, targetValue: target.value, startTimeDays:startTimeDays.value })
  if (!p) { error.value = '请选择注入井；新值须不同于原值，干度为0–1，温度使用模型原单位并高于绝对零度，地面注入目标和BHP上限须大于0且不超过100000000。'; return }
  error.value = ''; emit('run-scenario', p)
}
</script>
<template>
  <section v-if="available" aria-label="E300热采注汽参数编辑">
    <h3>修改热采注入参数</h3>
    <p>以当前完整成功运行 #{{ run.id }} 为基线，每次只修改一项，保留其余参数与原注采计划。</p>
    <el-form label-position="top" :disabled="disabled">
      <el-form-item label="注入井"><el-select v-model="selected" aria-label="E300热采注入井" placeholder="选择已有注入井"><el-option v-for="r in records" :key="r.well" :value="r.well" :label="r.well" /></el-select></el-form-item>
      <el-form-item label="修改项目"><el-select v-model="field" aria-label="E300热采修改项目"><el-option v-for="(label, key) in thermalInjectionLabels" :key="key" :value="key" :label="label" /></el-select></el-form-item>
      <el-form-item v-if="stageDays.length" label="生效时间"><el-select v-model="startTimeDays" aria-label="E300热采生效时间"><el-option :value="0" label="从模拟开始"/><el-option v-for="day in stageDays" :key="day" :value="day" :label="`第${day}天报告后`"/></el-select></el-form-item>
      <p v-if="startTimeDays">第{{ startTimeDays }}天报告后的时段使用新值；此前保持原方案，原终点和报告间隔保持。</p>
      <template v-if="record">
        <p>原干度 {{ record.originalSteamQuality }}；原输入温度 {{ record.originalTemperature }} {{ record.temperatureUnit === 'degF' ? '°F' : '°C' }}。</p>
        <p>原地面注入目标 {{ record.surfaceInjectionRate }} {{ record.rateUnit }}、BHP上限 {{ record.bhpUpperLimit }} {{ inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
        <p>来源 {{ inspection.thermalInjectionContext.sourceFile }}:{{ sourceLine }}；注入控制第{{ record.controlLineNumber }}行。</p>
        <el-form-item :label="`新${thermalInjectionLabels[field]}（${unitLabel}）`"><el-input-number v-model="target" :controls="false" aria-label="E300热采注汽新值" /></el-form-item>
      </template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button type="primary" :disabled="!record || target == null" @click="submit">修改注汽参数并真实重算</el-button>
    </el-form>
    <p>输入干度和温度用于定义注入焓，实际井温随压力变化；温度还应符合原模型热物性范围。计算后查看实际地面注入量、累计注入量、井压、井温、热损失与产量响应；压力触及上限时实际注入量可能低于目标。</p>
  </section>
</template>
