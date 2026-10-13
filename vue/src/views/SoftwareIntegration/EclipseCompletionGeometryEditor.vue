<script setup>
import { computed, ref, watch } from 'vue'
import { buildCompletionGeometry, completionGeometryLabels, completionGeometryRecords, completionGeometryUnit } from './eclipseCompletionGeometry'
const props = defineProps({ run: { type:Object,default:null }, inspection: { type:Object,default:null } })
const emit = defineEmits(['create-completion-geometry-run'])
const records = computed(() => completionGeometryRecords(props.inspection))
const selectedLine = ref(null)
const field = ref('DIAMETER')
const target = ref(null)
const error = ref('')
const selected = computed(() => records.value.find(r => r.lineNumber === selectedLine.value))
const original = computed(() => selected.value?.values?.[{ DIAMETER:8,KH:9,SKIN:10 }[field.value]])
const unit = computed(() => completionGeometryUnit(props.inspection?.unitSystem,field.value))
const baseline = computed(() => props.run?.runType === 'eclipse' && props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null)
watch([() => props.run?.id, () => props.inspection], () => { selectedLine.value = records.value[0]?.lineNumber ?? null; target.value = null; error.value = '' }, { immediate:true })
watch([field,selectedLine], () => { target.value = null; error.value = '' })
const submit = () => {
  try { const parameters = buildCompletionGeometry({run:props.run,inspection:props.inspection,record:selected.value,field:field.value,targetValue:target.value}); error.value = ''; emit('create-completion-geometry-run',parameters) }
  catch (failure) { error.value = failure.message }
}
</script>
<template>
  <section v-if="baseline" class="result-panel" aria-label="ECLIPSE 完井数值调整">
    <h2>井径、KH 和皮肤系数调整</h2>
    <p>用于比较已有单连接井的完井条件。仅修改一个原始数值，CF保留自动计算；其余井控保留。请选择原记录，按模型单位填写新值。</p>
    <el-alert v-if="!records.length" type="info" :closable="false" title="当前模型没有可调整记录。需要单DATA、初始单连接OPEN、自动CF，且井径/KH/皮肤系数均明确给出。多连接、后期覆盖及INCLUDE工程尚未开放。" />
    <template v-else>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="原始完井记录"><el-select v-model="selectedLine" aria-label="完井数值原始记录"><el-option v-for="r in records" :key="r.lineNumber" :value="r.lineNumber" :label="`${r.values[0].trim()} · ${r.sourceFile}:${r.lineNumber} · I${r.values[1]}/J${r.values[2]}/K${r.values[3]}`" /></el-select></el-form-item>
        <el-form-item label="调整项目"><el-select v-model="field" aria-label="完井数值调整项目"><el-option v-for="(label,key) in completionGeometryLabels" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <p>基线 #{{ run.id }} · 原值 {{ original }} {{ unit === '1' ? '（无量纲）' : unit }}</p>
        <el-form-item :label="`新值（${unit === '1' ? '无量纲' : unit}）`"><el-input-number v-model="target" :controls="false" aria-label="完井数值新值" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="!selected || target == null" @click="submit">创建完井数值方案并计算</el-button>
      </el-form>
      <p>皮肤系数本期支持0–100；负皮肤暂未开放。KH是渗透率与完井厚度的乘积。指定正CF的记录请使用已有连接因子调整。</p>
    </template>
  </section>
</template>
