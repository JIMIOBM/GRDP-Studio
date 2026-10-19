<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import { computed, ref, watch } from 'vue'
import { buildWetGasPvt, wetGasPvtRecord, wetGasRvUnit, gasPressureUnit, gasVolumeUnit } from './eclipseWetGasPvt'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-gas-pvt-run'])
const record=computed(()=>wetGasPvtRecord(props.inspection)),multiplier=ref(null),error=ref(''),field=ref('VISCOSITY')
const volume=computed(()=>field.value==='FORMATION_VOLUME_FACTOR')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null&&!props.run?.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.records.flatMap((branch,index)=>branch.rows.map((row,i)=>{const start=i===0?1:0;return {branch:index+1,pressure:branch.rows[0].values[0],state:i===0?'饱和':'欠饱和',line:row.lineNumber,rv:row.values[start],bg:row.values[start+1],viscosity:row.values[start+2],target:multiplier.value==null?null:Number(row.values[start+(volume.value?1:2)].replace(/[dD]/,'e'))*multiplier.value}}))||[])
function buildParameters(){return buildWetGasPvt({run:props.run,inspection:props.inspection,multiplier:multiplier.value,field:field.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-gas-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && inspection?.wetGasPvtContext" class="result-panel" aria-label="ECLIPSE 湿气物性调整">
    <h2>湿气物性 · PVTG</h2>
    <p>按压力查看饱和及欠饱和湿气分支，统一调整全部分支的气体积系数或黏度；保留压力、汽化油气比、另一物性列、油相表与井控。</p>
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA、单PVT区完整明确PVTG；最高压力必须具有欠饱和数据。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · {{ record.records.length }} 分支 · {{ preview.length }} 行</p>
      <EclipseInputCurvePreview :tables="[{ keyword: 'PVTG', keywordLineNumber: record.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="record.sourceFile" region-kind="PVT" />
      <el-table :data="preview" border size="small" max-height="320" aria-label="ECLIPSE PVTG分支与目标预览">
        <el-table-column prop="branch" label="分支" /><el-table-column prop="state" label="状态" /><el-table-column prop="line" label="源行" />
        <el-table-column prop="rv" :label="`汽化油气比 Rv（${wetGasRvUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="pressure" :label="`压力（${gasPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bg" :label="`原Bg（${gasVolumeUnit(inspection.unitSystem)}）`" /><el-table-column prop="viscosity" label="原黏度（cP）" />
        <el-table-column prop="target" :label="volume ? `目标Bg（${gasVolumeUnit(inspection.unitSystem)}）` : '目标黏度（cP）'" />
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整湿气物性"><el-select v-model="field" aria-label="调整湿气物性"><el-option label="气黏度" value="VISCOSITY" /><el-option label="气体积系数 Bg" value="FORMATION_VOLUME_FACTOR" /></el-select></el-form-item>
        <el-form-item label="全部分支统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="PVTG物性乘数" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建湿气物性方案并计算</el-button>
      </el-form>
      <p>原井控可能使产气量或井压保持不变，请核对油、气、水、累计量及注入压力。当前只开放E100单区明确分支；多区、默认项、INCLUDE及E300物性编辑继续扩展。</p>
    </template>
  </section>
</template>
