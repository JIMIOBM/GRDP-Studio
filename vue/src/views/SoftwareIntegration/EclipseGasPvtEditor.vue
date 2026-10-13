<script setup>
import { computed, ref, watch } from 'vue'
import { buildGasPvt, gasPvtRecord, gasPressureUnit, gasVolumeUnit } from './eclipseGasPvt'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-gas-pvt-run'])
const record=computed(()=>gasPvtRecord(props.inspection)),multiplier=ref(null),error=ref('')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.rows.map(r=>({line:r.lineNumber,pressure:r.values[0],bo:r.values[1],viscosity:r.values[2],target:multiplier.value==null?null:Number(r.values[2].replace(/[dD]/,'e'))*multiplier.value}))||[])
function submit(){try{const p=buildGasPvt({run:props.run,inspection:props.inspection,multiplier:multiplier.value});error.value='';emit('create-gas-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline" class="result-panel" aria-label="ECLIPSE 气物性调整">
    <h2>干气物性 · PVDG</h2>
    <p>用于比较气样黏度的不确定性对注气量、产气量、累计气量、产水和压力的影响。统一调整唯一气PVT区的全部表格行，保留压力、气体积系数和井控。</p>
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA干气模型及唯一明确PVDG表。多PVT区、默认字段、INCLUDE、挥发油及热模型尚未开放。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · {{ record.rows.length }} 行 · 气黏度 cP</p>
      <el-table :data="preview" border size="small" max-height="280" aria-label="ECLIPSE 原气物性与目标预览">
        <el-table-column prop="line" label="源行" /><el-table-column prop="pressure" :label="`压力（${gasPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bo" :label="`气体积系数（${gasVolumeUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="viscosity" label="原气黏度（cP）" /><el-table-column prop="target" label="目标气黏度（cP）" />
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="气黏度统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="气黏度乘数" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建气物性方案并计算</el-button>
      </el-form>
      <p>定压或受井底压力下限约束时，压力可能不变，需比较实际注采气量、累计量及水量。黏度加倍不代表产气量减半；E100在表格点之间插值Bg及Bg×黏度的倒数。</p>
    </template>
  </section>
</template>
