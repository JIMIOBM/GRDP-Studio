<script setup>
import { computed, ref, watch } from 'vue'
import { multiWaterPvtContext, buildMultiWaterPvt, waterPvtValue } from './eclipseMultiWaterPvt'
import { pvtwLabels, pvtwIndex, pvtwUnit } from './eclipsePvtw'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-pvtw-run'])
const context=computed(()=>multiWaterPvtContext(props.inspection)),regionNumber=ref(null),field=ref('VISCOSITY'),target=ref(null),error=ref('')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run.status==='SUCCEEDED'&&props.run.resultContract==='VALID_FULL'&&props.run.parameters==null)
const region=computed(()=>context.value?.regions.find(r=>r.number===regionNumber.value))
const pureWater=computed(()=>props.inspection?.phases?.length===1&&props.inspection.phases[0]==='WATER')
const unit=computed(()=>pvtwUnit(props.inspection?.unitSystem,field.value))
const original=computed(()=>region.value?waterPvtValue(region.value.recordValues,field.value):null)
const preview=computed(()=>region.value?Object.entries(pvtwLabels).map(([key,label],i)=>({label,value:i===4&&region.value.recordValues[i]==='1*'?'0（官方默认，源记录1*）':region.value.recordValues[i]??'0（官方默认，源文件省略）',unit:pvtwUnit(props.inspection.unitSystem,key)})):[])
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context],()=>{regionNumber.value=null;target.value=null;error.value=''}, {immediate:true,deep:true})
watch([regionNumber,field],()=>{target.value=null;error.value=''})
function submit(){try{const p=buildMultiWaterPvt({run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,field:field.value,targetValue:target.value});error.value='';emit('create-pvtw-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 分区水物性调整">
    <h2>分区水物性 · PVTW</h2>
    <p>比较不同流体区的水样物性与不确定性对注采压力和产水的影响。选定PVT区的水物性独立调整，{{pureWater?'其它水区、地质网格和井控保持。':'油气表、其它水区、地质网格和井控保持。'}}</p>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="水物性PVT区"><el-select v-model="regionNumber" aria-label="水物性PVT区"><el-option v-for="r in context.regions" :key="r.number" :value="r.number" :label="`PVT区 ${r.number} · ${r.cellCount} 格`" /></el-select></el-form-item>
      <template v-if="region">
        <el-table :data="preview" border size="small" aria-label="分区水物性原记录"><el-table-column prop="label" label="物性"/><el-table-column prop="value" label="原值"/><el-table-column prop="unit" label="单位"/></el-table>
        <el-form-item label="调整项目"><el-select v-model="field" aria-label="分区水物性调整项目"><el-option v-for="(label,key) in pvtwLabels" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <p>基线 #{{run.id}} · PVT区 {{region.number}} · {{pvtwLabels[field]}}原值 {{original}} {{unit}}</p>
        <el-form-item :label="`新${pvtwLabels[field]}（${unit}）`"><el-input-number v-model="target" :controls="false" aria-label="分区水物性新值" /></el-form-item>
      </template>
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-button type="primary" :disabled="!region || target==null" @click="submit">创建分区水物性方案并计算</el-button>
    </el-form>
    <p>填写绝对新值；每次只改选定区的一项物性。参考压力改变但其它参考值保持，会改变整条水物性关系。定产量井请同时比较井底压力。当前支持明确初始压力的纯水模型及明确初始化的死油/干气模型，PVTW前四项必须明确，第五项可省略或明确写1*并按官方默认0解析；调整第五项时仅在所选区补入或替换原1*，其它字段编辑保留原星号。其它星号默认、前四项缺失、INCLUDE、热模型及平衡初始化尚未开放。</p>
  </section>
</template>
