<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import { computed, ref, watch } from 'vue'
import { buildLiveOilPvt, liveOilPvtRecord, liveOilRsUnit, oilPressureUnit, oilVolumeUnit } from './eclipseLiveOilPvt'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-oil-pvt-run'])
const record=computed(()=>liveOilPvtRecord(props.inspection)),multiplier=ref(null),error=ref(''),field=ref('VISCOSITY')
const volume=computed(()=>field.value==='FORMATION_VOLUME_FACTOR')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null&&!props.run?.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.records.flatMap((branch,index)=>branch.rows.map((row,i)=>{const start=i===0?1:0;return {branch:index+1,rs:branch.rows[0].values[0],state:i===0?'饱和':'欠饱和',line:row.lineNumber,pressure:row.values[start],bo:row.values[start+1],viscosity:row.values[start+2],target:multiplier.value==null?null:Number(row.values[start+(volume.value?1:2)].replace(/[dD]/,'e'))*multiplier.value}}))||[])
function buildParameters(){return buildLiveOilPvt({run:props.run,inspection:props.inspection,multiplier:multiplier.value,field:field.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-oil-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && inspection?.liveOilPvtContext" class="result-panel" aria-label="ECLIPSE 含溶解气油物性调整">
    <h2>含溶解气原油物性 · PVTO</h2>
    <p>按溶解气油比查看饱和及欠饱和分支，统一调整全部分支的油体积系数或黏度；保留溶解气油比、泡点压力、欠饱和压力、另一物性列与井控。</p>
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA、单PVT区完整明确PVTO；最高溶解气油比必须具有欠饱和数据。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · {{ record.records.length }} 分支 · {{ preview.length }} 行</p>
      <EclipseInputCurvePreview :tables="[{ keyword: 'PVTO', keywordLineNumber: record.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="record.sourceFile" region-kind="PVT" />
      <el-table :data="preview" border size="small" max-height="320" aria-label="ECLIPSE PVTO分支与目标预览">
        <el-table-column prop="branch" label="分支" /><el-table-column prop="state" label="状态" /><el-table-column prop="line" label="源行" />
        <el-table-column prop="rs" :label="`溶解气油比（${liveOilRsUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="pressure" :label="`压力（${oilPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bo" :label="`原Bo（${oilVolumeUnit(inspection.unitSystem)}）`" /><el-table-column prop="viscosity" label="原黏度（cP）" />
        <el-table-column prop="target" :label="volume ? `目标Bo（${oilVolumeUnit(inspection.unitSystem)}）` : '目标黏度（cP）'" />
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整含溶解气油物性"><el-select v-model="field" aria-label="调整含溶解气油物性"><el-option label="油黏度" value="VISCOSITY" /><el-option label="油体积系数 Bo" value="FORMATION_VOLUME_FACTOR" /></el-select></el-form-item>
        <el-form-item label="全部分支统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="PVTO物性乘数" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建含溶解气油物性方案并计算</el-button>
      </el-form>
      <p>原井控可能使产量或井压保持不变，请核对油、气、水、累计量及注入压力。当前只开放E100单区明确分支；多区、默认项、INCLUDE及E300物性编辑继续扩展。</p>
    </template>
  </section>
</template>
