<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import {computed,ref,watch} from 'vue'
import {buildCoupledRelperm,coupledRelpermContext,coupledRelpermLabels} from './eclipseCoupledRelperm'
import {multiSatRelpermContext,buildMultiSatRelperm} from './eclipseMultiSatRelperm'
import {capillaryPressureUnit} from './eclipseRelperm'
import {multiLiveOilPvtContext} from './eclipseMultiLiveOilPvt.js'
import {multiWetGasPvtContext} from './eclipseMultiWetGasPvt.js'
import {multiPvtContext} from './eclipseMultiPvt.js'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-relperm-run'])
const regionNumber=ref(1),multiContext=computed(()=>multiSatRelpermContext(props.inspection))
const equilibriumCombined=computed(()=>!!(multiWetGasPvtContext(props.inspection)||multiLiveOilPvtContext(props.inspection)) && !!multiContext.value)
const combined=computed(()=>!!(multiPvtContext(props.inspection)||equilibriumCombined.value) && !!multiContext.value)
const context=computed(()=>coupledRelpermContext(props.inspection)||(()=>{const c=multiContext.value,r=c?.regions.find(r=>r.number===regionNumber.value);return r?{schemaVersion:'eclipse-coupled-relperm-context/1',sourceFile:c.sourceFile,tables:r.tables}:null})()),field=ref('WATER_KR'),multiplier=ref(null),error=ref('')
watch([()=>props.run?.id,()=>props.run?.modelVersionId,multiContext],()=>{regionNumber.value=equilibriumCombined.value?null:multiContext.value?.regions[0]?.number||1},{immediate:true,deep:true})
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run.status==='SUCCEEDED'&&props.run.resultContract==='VALID_FULL'&&props.run.parameters==null&&!props.run.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>context.value?.tables.map(t=>({...t,rows:t.rows.map(r=>({line:r.lineNumber,saturation:r.values[0],phase:r.values[1],oil:r.values[2],pc:r.values[3],target:multiplier.value==null||field.value!=='OIL_KR'&&t.keyword!==(field.value==='WATER_KR'?'SWOF':'SGOF')?null:Number(r.values[field.value==='OIL_KR'?2:1].replace(/[dD]/,'e'))*multiplier.value}))}))||[])
function buildParameters(){const args={run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,field:field.value,multiplier:multiplier.value};return multiContext.value?buildMultiSatRelperm(args):buildCoupledRelperm(args)}
function submit(){try{const p=buildParameters();error.value='';emit('create-relperm-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && (context || multiContext)" class="result-panel" aria-label="ECLIPSE SWOF SGOF 相渗调整">
    <h2>SWOF / SGOF · 水、气、油相渗</h2>
    <p>比较岩心相渗实验的不确定性对井压力、产油产水、产气和累计量的影响。油相统一修改两张表的Krow/Krog，保持共同首端值；保留饱和度、毛管压力、PVT、网格和井控。</p>
    <el-form-item v-if="multiContext" label="相渗SAT区"><el-select v-model="regionNumber" aria-label="相渗SAT区"><el-option v-for="r in multiContext.regions" :key="r.number" :value="r.number" :label="`SAT区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
    <template v-if="context">
    <el-form-item label="两表相渗调整项目"><el-select v-model="field" aria-label="两表相渗调整项目"><el-option v-for="(label,value) in coupledRelpermLabels" :key="value" :label="label" :value="value"/></el-select></el-form-item>
    <p>基线 #{{run.id}} · {{context.sourceFile}} · <span v-if="multiContext">SAT区 {{regionNumber}} · </span>相渗无量纲</p>
    <EclipseInputCurvePreview :tables="preview" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="context.sourceFile" region-kind="SAT" :region="regionNumber" />
    <template v-for="table in preview" :key="table.keyword">
      <h3>{{table.keyword}} · {{table.rows.length}} 行 · 源行 {{table.keywordLineNumber}}</h3>
      <el-table :data="table.rows" border size="small" max-height="240" :aria-label="`ECLIPSE ${table.keyword} 原表与目标`">
        <el-table-column prop="line" label="源行"/><el-table-column prop="saturation" :label="table.keyword==='SWOF'?'水饱和度 Sw':'气饱和度 Sg'"/>
        <el-table-column prop="phase" :label="table.keyword==='SWOF'?'原水相渗 Krw':'原气相渗 Krg'"/><el-table-column prop="oil" :label="table.keyword==='SWOF'?'原油水相渗 Krow':'原油气相渗 Krog'"/>
        <el-table-column prop="pc" :label="`原毛管压力（${capillaryPressureUnit(inspection.unitSystem)}）`"/><el-table-column prop="target" label="所选相目标相渗"/>
      </el-table>
    </template>
    <el-form label-position="top" @submit.prevent="submit"><el-form-item label="两表相渗统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="两表相渗乘数"/></el-form-item>
      <el-alert v-if="error" type="error" :closable="false" :title="error"/><el-button type="primary" :disabled="multiplier==null" @click="submit">创建两表相渗方案并计算</el-button>
    </el-form>
    </template>
    <el-alert v-else type="info" :closable="false" title="请先选择岩性SAT区，再预览原表和填写相渗乘数。" />
    <p v-if="combined">此模型同时包含SAT区与PVT区。本面板按岩性SAT区调整相渗；油气物性在PVT面板操作，两张区域图分别选择。</p>
    <p v-else>当前支持单DATA、1–8个明确SAT区的三相原表。分区方案仅修改所选区，SATNUM地图与其它区保持；默认项、滞后、端点缩放及INCLUDE继续扩展。产量由实际流动和井控共同决定，不按相渗乘数推算。</p>
  </section>
</template>
