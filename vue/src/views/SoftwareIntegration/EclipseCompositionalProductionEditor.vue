<script setup>
import { computed, ref, watch } from 'vue'
import { buildCompositionalProduction, compositionalProductionRecords } from './eclipseCompositionalProduction'
const props = defineProps({ inspection: Object, run: Object, disabled: Boolean })
const emit = defineEmits(['run-scenario'])
const records = computed(() => compositionalProductionRecords(props.inspection))
const available = computed(() => props.run?.status === 'SUCCEEDED' && props.run?.resultContract === 'VALID_FULL' && props.run?.parameters == null && records.value.length > 0)
const selected = ref(''), target = ref(null), error = ref('')
const record = computed(() => records.value.find(r => r.well === selected.value))
watch([() => props.run?.id, () => props.inspection], () => { selected.value = ''; target.value = null; error.value = '' }, { immediate: true })
watch(selected, () => { target.value = null; error.value = '' })
const submit = () => {
  if (props.disabled || !available.value) return
  const p = buildCompositionalProduction({ inspection: props.inspection, baselineRunId: props.run.id, well: selected.value, targetGasRate: target.value })
  if (!p) { error.value = '请选择生产井，新产气目标必须为不同于原值的正数。'; return }
  error.value = ''; emit('run-scenario', p)
}
</script>
<template>
  <section v-if="available" aria-label="E300产气目标编辑">
    <h3>修改组分模型产气目标</h3>
    <p>以当前完整成功运行 #{{ run.id }} 为基线，保留原组分物性、状态方程、回注与后续计划。</p>
    <el-form label-position="top" :disabled="disabled">
      <el-form-item label="生产井"><el-select v-model="selected" aria-label="E300产气目标井" placeholder="选择已有GRAT生产井"><el-option v-for="r in records" :key="r.well" :label="r.well" :value="r.well" /></el-select></el-form-item>
      <template v-if="record">
        <p>原目标 {{ record.originalGasRate }} {{ record.unit }}；BHP下限 {{ record.bhpLimit }} {{ inspection.unitSystem === 'FIELD' ? 'psia' : 'barsa' }}。</p>
        <p>来源 {{ inspection.compositionalProductionContext.sourceFile }}:{{ record.lineNumber }}；压力限制来源第{{ record.bhpLineNumber }}行。</p>
        <el-form-item :label="`新产气目标（${record.unit}）`"><el-input-number v-model="target" :controls="false" aria-label="E300新产气目标" /></el-form-item>
      </template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button type="primary" :disabled="!record || target == null" @click="submit">修改产气目标并真实重算</el-button>
    </el-form>
    <p>实际产量仍受原井底压力约束。每次从原基线比较；修改后可下载完整工程复算。</p>
  </section>
</template>
