<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import { computed, ref, watch } from 'vue'
import { buildOilPvt, oilPvtRecord, oilPressureUnit, oilVolumeUnit } from './eclipseOilPvt'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-oil-pvt-run'])
const record=computed(()=>oilPvtRecord(props.inspection)),multiplier=ref(null),error=ref(''),field=ref('VISCOSITY')
const volume=computed(()=>field.value==='FORMATION_VOLUME_FACTOR')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.rows.map(r=>({line:r.lineNumber,pressure:r.values[0],bo:r.values[1],viscosity:r.values[2],target:multiplier.value==null?null:Number(r.values[volume.value?1:2].replace(/[dD]/,'e'))*multiplier.value}))||[])
function buildParameters(){return buildOilPvt({run:props.run,inspection:props.inspection,multiplier:multiplier.value,field:field.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-oil-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline" class="result-panel" aria-label="ECLIPSE 油物性调整">
    <h2>死油物性 · PVDO</h2>
    <p>比较油体积系数或油黏度的不确定性对产油、产水、总液量和压力的影响。统一调整唯一油PVT区的所选列，保留压力、另一物性列和井控。</p>
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA死油模型及唯一明确PVDO表。多PVT区、默认字段、INCLUDE、溶解气及热模型尚未开放。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · {{ record.rows.length }} 行 · {{ volume ? `油体积系数 Bo ${oilVolumeUnit(inspection.unitSystem)}` : '油黏度 cP' }}</p>
      <EclipseInputCurvePreview :tables="[{ keyword: 'PVDO', keywordLineNumber: record.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="record.sourceFile" region-kind="PVT" />
      <el-table :data="preview" border size="small" max-height="280" aria-label="ECLIPSE 原油物性与目标预览">
        <el-table-column prop="line" label="源行" /><el-table-column prop="pressure" :label="`压力（${oilPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bo" :label="`油体积系数（${oilVolumeUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="viscosity" label="原油黏度（cP）" /><el-table-column prop="target" :label="volume ? `目标油体积系数（${oilVolumeUnit(inspection.unitSystem)}）` : '目标油黏度（cP）'" />
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整油物性"><el-select v-model="field" aria-label="调整油物性"><el-option label="油黏度" value="VISCOSITY" /><el-option label="油体积系数 Bo" value="FORMATION_VOLUME_FACTOR" /></el-select></el-form-item>
        <el-form-item :label="volume ? '油体积系数统一乘数' : '油黏度统一乘数'"><el-input-number v-model="multiplier" :controls="false" :aria-label="volume ? '油体积系数乘数' : '油黏度乘数'" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建油物性方案并计算</el-button>
      </el-form>
      <p>定压或受井底压力下限约束时，压力可能不变，需比较实际液量和相产量。黏度加倍不代表产油量减半；E100在表格点之间插值Bo及Bo×黏度的倒数。</p>
    </template>
  </section>
</template>
