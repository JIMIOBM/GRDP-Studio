<script setup>
import { computed, ref, watch } from 'vue'
import { buildPvtw, pvtwRecord, pvtwIndex, pvtwUnit, pvtwLabels } from './eclipsePvtw'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-pvtw-run'])
const field=ref('VISCOSITY')
const unit=computed(()=>pvtwUnit(props.inspection?.unitSystem,field.value))
const record=computed(()=>pvtwRecord(props.inspection)),target=ref(null),error=ref('')
const originalViscosity=computed(()=>record.value ? Number(record.value.recordValues[pvtwIndex(field.value)].replace(/[dD]/,'e')) : null)
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{target.value=null;error.value=''}, {immediate:true,deep:true})
const pressureUnit=computed(()=>props.inspection?.unitSystem==='FIELD'?'psia':['LAB','PVT-M'].includes(props.inspection?.unitSystem)?'atma':'barsa')
const inversePressureUnit=computed(()=>props.inspection?.unitSystem==='FIELD'?'psi':['LAB','PVT-M'].includes(props.inspection?.unitSystem)?'atm':'bar')
function submit(){try{const p=buildPvtw({run:props.run,inspection:props.inspection,targetValue:target.value,field:field.value});error.value='';emit('create-pvtw-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline" class="result-panel" aria-label="ECLIPSE 水物性调整">
    <h2>水物性 · PVTW</h2>
    <p>用于比较水样物性或不确定性对注采压力的影响。作用于唯一PVT区全部水相；定产量井可能只改变压力。压缩性与黏压系数本期支持非负值。</p>
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA纯水模型及唯一明确五项PVTW。多PVT区、默认字段、INCLUDE和热模型尚未开放。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ pvtwLabels[field] }}原值 {{ originalViscosity }} {{ unit }} · {{ record.sourceFile }}:{{ record.lineNumber }}</p>
      <p>参考压力 {{ record.recordValues[0] }} {{ pressureUnit }}；水体积系数 {{ record.recordValues[1] }} {{ pvtwUnit(inspection.unitSystem,'FORMATION_VOLUME_FACTOR') }}；水压缩性 {{ record.recordValues[2] }} 1/{{ inversePressureUnit }}；黏压系数 {{ record.recordValues[4] }} 1/{{ inversePressureUnit }}。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整项目"><el-select v-model="field" aria-label="水物性调整项目"><el-option v-for="(label,key) in pvtwLabels" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <el-form-item :label="`新${pvtwLabels[field]}（${unit}）`"><el-input-number v-model="target" :controls="false" aria-label="水物性新值" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="target==null" @click="submit">创建水物性方案并计算</el-button>
      </el-form>
      <p>每次仅改选定一个物性字段，其余四项及井控保留。参考压力应接近储层压力；改变参考压力但保留其余参考值，会改变整条物性关系，并非无影响的重新定基准。</p>
    </template>
  </section>
</template>
