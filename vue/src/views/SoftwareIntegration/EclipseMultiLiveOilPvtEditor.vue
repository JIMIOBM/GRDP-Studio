<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import { computed, ref, watch } from 'vue'
import {liveOilRsUnit, oilPressureUnit, oilVolumeUnit} from './eclipseLiveOilPvt'
import {buildMultiLiveOilPvt,multiLiveOilPvtContext} from './eclipseMultiLiveOilPvt'
import {multiSatRelpermContext} from './eclipseMultiSatRelperm.js'
import {multiFamilyIIContext} from './eclipseMultiSatFamilyII.js'
import {gasVolumeUnit} from './eclipseGasPvt'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-oil-pvt-run'])
const context=computed(()=>multiLiveOilPvtContext(props.inspection)),regionNumber=ref(1)
const combined=computed(()=>!!context.value && !!(multiFamilyIIContext(props.inspection)||multiSatRelpermContext(props.inspection)))
const record=computed(()=>{const r=context.value?.regions.find(r=>r.number===regionNumber.value);return r?{...r,sourceFile:context.value.sourceFile}:null}),multiplier=ref(null),error=ref(''),field=ref('VISCOSITY')
const volume=computed(()=>field.value==='FORMATION_VOLUME_FACTOR')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null&&!props.run?.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context],()=>{regionNumber.value=combined.value?null:context.value?.regions[0]?.number||1},{immediate:true,deep:true})
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.records.flatMap((branch,index)=>branch.rows.map((row,i)=>{const start=i===0?1:0;return {branch:index+1,rs:branch.rows[0].values[0],state:i===0?'饱和':'欠饱和',line:row.lineNumber,pressure:row.values[start],bo:row.values[start+1],viscosity:row.values[start+2],target:multiplier.value==null?null:Number(row.values[start+(volume.value?1:2)].replace(/[dD]/,'e'))*multiplier.value}}))||[])
function buildParameters(){return buildMultiLiveOilPvt({run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,multiplier:multiplier.value,field:field.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-oil-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 多区含溶解气油物性调整">
    <h2>含溶解气原油分区物性 · PVTO</h2>
    <p>按溶解气油比查看饱和及欠饱和分支，统一调整所选PVT区全部分支的油体积系数或黏度；保留溶解气油比、泡点压力、欠饱和压力、另一物性列、原气表、其它区及井控。</p>
    <p v-if="combined">本面板按流体PVT/EQL区调整PVTO；相渗按独立岩性SAT区选择。同号区不一定覆盖相同网格。</p>
    <el-form-item label="PVTO流体区"><el-select v-model="regionNumber" aria-label="PVTO流体区"><el-option v-for="r in context.regions" :key="r.number" :value="r.number" :label="`PVT/EQL区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
    <el-alert v-if="run.result?.eclEnd?.warnings > 0" type="warning" :closable="false" :title="`原基线有${run.result.eclEnd.warnings}条原生警告，请查看诊断后评估物性方案。`" />
    <el-alert v-if="!record" type="info" :closable="false" title="需要单DATA、单PVT区完整明确PVTO；最高溶解气油比必须具有欠饱和数据。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · PVT/EQL区 {{regionNumber}} · {{ record.records.length }} 分支 · {{ preview.length }} 行</p>
      <EclipseInputCurvePreview :tables="[{ keyword: 'PVTO', keywordLineNumber: record.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="record.sourceFile" region-kind="PVT/EQL" :region="regionNumber" />
      <el-table :data="preview" border size="small" max-height="320" aria-label="ECLIPSE 分区PVTO分支与目标预览">
        <el-table-column prop="branch" label="分支" /><el-table-column prop="state" label="状态" /><el-table-column prop="line" label="源行" />
        <el-table-column prop="rs" :label="`溶解气油比（${liveOilRsUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="pressure" :label="`压力（${oilPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bo" :label="`原Bo（${oilVolumeUnit(inspection.unitSystem)}）`" /><el-table-column prop="viscosity" label="原黏度（cP）" />
        <el-table-column prop="target" :label="volume ? `目标Bo（${oilVolumeUnit(inspection.unitSystem)}）` : '目标黏度（cP）'" />
      </el-table>
      <h3>同区PVDG原气表保持</h3>
      <el-table :data="record.gasTable.rows.map(r=>({line:r.lineNumber,pressure:r.values[0],volume:r.values[1],viscosity:r.values[2]}))" border size="small" max-height="220" aria-label="ECLIPSE 分区PVTO原气表">
        <el-table-column prop="line" label="源行"/><el-table-column prop="pressure" :label="`压力（${oilPressureUnit(inspection.unitSystem)}）`"/><el-table-column prop="volume" :label="`原Bg（${gasVolumeUnit(inspection.unitSystem)}）`"/><el-table-column prop="viscosity" label="原气黏度（cP）"/>
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整分区含溶解气油物性"><el-select v-model="field" aria-label="调整分区含溶解气油物性"><el-option label="油黏度" value="VISCOSITY" /><el-option label="油体积系数 Bo" value="FORMATION_VOLUME_FACTOR" /></el-select></el-form-item>
        <el-form-item label="全部分支统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="分区PVTO物性乘数" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建分区含溶解气油物性方案并计算</el-button>
      </el-form>
      <p>原井控可能使产量或井压保持不变，请核对油、气、水、累计量及注入压力。当前支持E100单SAT或SWOF/SGOF或SWFN/SGFN/SOF3多SAT、2–8个PVT区与同号EQL区、完整PVTO/PVDG及明确EQUIL/RSVD源。EQUIL采用中心点初始化，油Rs由深度表给出；输入保持，但Bo调整可能改变平衡计算得到的初始压力。默认项、其它初始化、湿气、其它SAT表族组合、INCLUDE及E300物性编辑继续扩展。</p>
    </template>
  </section>
</template>
