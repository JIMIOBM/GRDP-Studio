<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import { computed, ref, watch } from 'vue'
import {wetGasRvUnit, gasPressureUnit, gasVolumeUnit} from './eclipseWetGasPvt'
import {multiSatRelpermContext} from './eclipseMultiSatRelperm.js'
import {multiFamilyIIContext} from './eclipseMultiSatFamilyII.js'
import {liveOilRsUnit, oilVolumeUnit} from './eclipseLiveOilPvt'
import {buildMultiWetGasPvt,multiWetGasPvtContext} from './eclipseMultiWetGasPvt'

const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-gas-pvt-run'])
const context=computed(()=>multiWetGasPvtContext(props.inspection)),regionNumber=ref(1)
const combined=computed(()=>!!context.value && !!(multiFamilyIIContext(props.inspection)||multiSatRelpermContext(props.inspection)))
const record=computed(()=>{const r=context.value?.regions.find(r=>r.number===regionNumber.value);return r?{...r,sourceFile:context.value.sourceFile}:null}),multiplier=ref(null),error=ref(''),field=ref('VISCOSITY')
const volume=computed(()=>field.value==='FORMATION_VOLUME_FACTOR')
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run?.status==='SUCCEEDED'&&props.run?.resultContract==='VALID_FULL'&&props.run?.parameters==null&&!props.run?.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context],()=>{regionNumber.value=combined.value?null:context.value?.regions[0]?.number||1},{immediate:true,deep:true})
watch([()=>props.run?.id,()=>props.run?.modelVersionId,record,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>record.value?.records.flatMap((branch,index)=>branch.rows.map((row,i)=>{const start=i===0?1:0;return {branch:index+1,pressure:branch.rows[0].values[0],state:i===0?'饱和':'欠饱和',line:row.lineNumber,rv:row.values[start],bg:row.values[start+1],viscosity:row.values[start+2],target:multiplier.value==null?null:Number(row.values[start+(volume.value?1:2)].replace(/[dD]/,'e'))*multiplier.value}}))||[])
const oilPreview=computed(()=>record.value?.oilTable.records.flatMap((branch,index)=>branch.rows.map((row,i)=>{const start=i===0?1:0;return {branch:index+1,rs:branch.rows[0].values[0],line:row.lineNumber,pressure:row.values[start],volume:row.values[start+1],viscosity:row.values[start+2]}}))||[])

function buildParameters(){return buildMultiWetGasPvt({run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,multiplier:multiplier.value,field:field.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-gas-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 多区湿气物性调整">
    <h2>湿气分区物性 · PVTG</h2>
    <p>按压力查看饱和及欠饱和气分支，调整所选PVT区全部分支的气体积系数 Bg 或黏度；保留压力、汽化油气比 Rv、另一物性列、同区油表、其它区及井控。</p>
    <p v-if="combined">本面板按流体PVT/EQL区选区；下方相渗面板按岩性SAT区选区，两张区域图独立。请先选择流体区，再填写乘数。</p>
    <el-form-item label="PVTG流体区"><el-select v-model="regionNumber" aria-label="PVTG流体区"><el-option v-for="r in context.regions" :key="r.number" :value="r.number" :label="`PVT/EQL区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
    <el-alert v-if="run.result?.eclEnd?.warnings > 0" type="warning" :closable="false" :title="`原基线有${run.result.eclEnd.warnings}条原生警告，请查看诊断后评估物性方案。`" />
    <el-alert v-if="!record" type="info" :closable="false" title="请选择PVT/EQL流体区；需要完整明确PVTG，最高压力必须具有欠饱和数据。" />
    <template v-else>
      <p>基线 #{{ run.id }} · {{ record.sourceFile }}:{{ record.keywordLineNumber }} · PVT/EQL区 {{regionNumber}} · {{ record.records.length }} 分支 · {{ preview.length }} 行</p>
      <EclipseInputCurvePreview :tables="[{ keyword: 'PVTG', keywordLineNumber: record.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="record.sourceFile" region-kind="PVT/EQL" :region="regionNumber" />
      <el-table :data="preview" border size="small" max-height="320" aria-label="ECLIPSE 分区PVTG分支与目标预览">
        <el-table-column prop="branch" label="分支" /><el-table-column prop="state" label="状态" /><el-table-column prop="line" label="源行" />
        <el-table-column prop="rv" :label="`汽化油气比（${wetGasRvUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="pressure" :label="`压力（${gasPressureUnit(inspection.unitSystem)}）`" />
        <el-table-column prop="bg" :label="`原Bg（${gasVolumeUnit(inspection.unitSystem)}）`" /><el-table-column prop="viscosity" label="原黏度（cP）" />
        <el-table-column prop="target" :label="volume ? `目标Bg（${gasVolumeUnit(inspection.unitSystem)}）` : '目标黏度（cP）'" />
      </el-table>
      <h3>同区PVTO原油表保持</h3>
      <el-table :data="oilPreview" border size="small" max-height="220" aria-label="ECLIPSE 分区PVTG原油表">
        <el-table-column prop="branch" label="分支"/><el-table-column prop="line" label="源行"/><el-table-column prop="rs" :label="`溶解气油比（${liveOilRsUnit(inspection.unitSystem)}）`"/>
        <el-table-column prop="pressure" :label="`压力（${gasPressureUnit(inspection.unitSystem)}）`"/><el-table-column prop="volume" :label="`原Bo（${oilVolumeUnit(inspection.unitSystem)}）`"/><el-table-column prop="viscosity" label="原油黏度（cP）"/>
      </el-table>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="调整分区湿气物性"><el-select v-model="field" aria-label="调整分区湿气物性"><el-option label="气黏度" value="VISCOSITY" /><el-option label="气体积系数 Bg" value="FORMATION_VOLUME_FACTOR" /></el-select></el-form-item>
        <el-form-item label="全部分支统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="分区PVTG物性乘数" /></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error" />
        <el-button type="primary" :disabled="multiplier==null" @click="submit">创建分区湿气物性方案并计算</el-button>
      </el-form>
      <p>当前支持E100单SAT或SWOF/SGOF、SWFN/SGFN/SOF3多SAT、2–8个同号PVT/EQL区、完整PVTG/PVTO及明确EQUIL/RSVD/RVVD。初始化源值保持；Bg调整可能改变计算得到的初始压力。定气量井的气产量可能保持不变，请同时比较井压、凝析油、水及累计量。其它表族、复杂默认项、INCLUDE及E300物性编辑继续扩展。</p>
    </template>
  </section>
</template>
