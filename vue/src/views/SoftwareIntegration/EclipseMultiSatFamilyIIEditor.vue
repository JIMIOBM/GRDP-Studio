<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import {computed,ref,watch} from 'vue'
import {buildMultiFamilyII,multiFamilyIIContext,multiFamilyIILabels} from './eclipseMultiSatFamilyII'
import {capillaryPressureUnit} from './eclipseRelperm'
import {multiWetGasPvtContext} from './eclipseMultiWetGasPvt.js'
import {multiLiveOilPvtContext} from './eclipseMultiLiveOilPvt.js'
import {multiPvtContext} from './eclipseMultiPvt.js'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-relperm-run'])
const context=computed(()=>multiFamilyIIContext(props.inspection)),regionNumber=ref(1),field=ref('WATER_KR'),multiplier=ref(null),error=ref('')
const liveCombined=computed(()=>!!(multiLiveOilPvtContext(props.inspection)||multiWetGasPvtContext(props.inspection)) && !!context.value)
const combined=computed(()=>!!(multiPvtContext(props.inspection)||liveCombined.value) && !!context.value)
const region=computed(()=>context.value?.regions.find(r=>r.number===regionNumber.value)),key=computed(()=>({WATER_KR:'SWFN',GAS_KR:'SGFN',OIL_KR:'SOF3'})[field.value])
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run.status==='SUCCEEDED'&&props.run.resultContract==='VALID_FULL'&&props.run.parameters==null&&!props.run.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context],()=>{regionNumber.value=liveCombined.value?null:context.value?.regions[0]?.number||1},{immediate:true,deep:true})
watch([()=>props.run?.id,()=>props.run?.modelVersionId,region,field],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>region.value?.tables.map(t=>({...t,rows:t.rows.map(r=>({line:r.lineNumber,saturation:r.values[0],kr:r.values[1],third:r.values[2],target:multiplier.value==null||t.keyword!==key.value?null:Number(r.values[1].replace(/[dD]/,'e'))*multiplier.value,targetOilGas:multiplier.value==null||t.keyword!=='SOF3'||field.value!=='OIL_KR'?null:Number(r.values[2].replace(/[dD]/,'e'))*multiplier.value}))}))||[])
function buildParameters(){return buildMultiFamilyII({run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,field:field.value,multiplier:multiplier.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-relperm-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 多区三表相渗调整">
    <h2>SWFN / SGFN / SOF3 · 分区相渗</h2>
    <p>用于比较不同岩性区域的水、气和油相渗实验不确定性。仅修改所选SAT区；其它区原表、SATNUM地图、饱和度、毛管压力、PVT、网格和井控保持。连通油藏的其它井仍可能响应变化。</p>
    <el-form-item label="三表相渗SAT区"><el-select v-model="regionNumber" aria-label="三表相渗SAT区"><el-option v-for="r in context.regions" :key="r.number" :value="r.number" :label="`SAT区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
    <el-form-item label="三表相渗调整项目"><el-select v-model="field" aria-label="三表相渗调整项目"><el-option v-for="(label,value) in multiFamilyIILabels" :key="value" :value="value" :label="label"/></el-select></el-form-item>
    <p>基线 #{{run.id}} · {{context.sourceFile}} · SAT区 {{regionNumber}} · 相渗无量纲</p>
    <EclipseInputCurvePreview :tables="preview" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="field" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="context.sourceFile" region-kind="SAT" :region="regionNumber" />
    <template v-for="table in preview" :key="table.keyword">
      <h3>{{table.keyword}} · {{table.rows.length}} 行 · 源行 {{table.keywordLineNumber}}</h3>
      <el-table :data="table.rows" border size="small" max-height="240" :aria-label="`ECLIPSE 分区${table.keyword}原表与目标`">
        <el-table-column prop="line" label="源行"/><el-table-column prop="saturation" :label="table.keyword==='SWFN'?'水饱和度 Sw':table.keyword==='SGFN'?'气饱和度 Sg':'油饱和度 So'"/>
        <el-table-column prop="kr" :label="table.keyword==='SWFN'?'原水相渗 Krw':table.keyword==='SGFN'?'原气相渗 Krg':'原油水相渗 Krow'"/><el-table-column prop="target" label="所选相目标相渗"/>
        <el-table-column prop="third" :label="table.keyword==='SOF3'?'原油气相渗 Krog':`原毛管压力（${capillaryPressureUnit(inspection.unitSystem)}）`"/><el-table-column v-if="table.keyword==='SOF3'" prop="targetOilGas" label="目标 Krog"/>
      </el-table>
    </template>
    <el-form label-position="top" @submit.prevent="submit"><el-form-item label="三表相渗统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="三表相渗乘数"/></el-form-item><el-alert v-if="error" type="error" :closable="false" :title="error"/><el-button type="primary" :disabled="multiplier==null" @click="submit">创建分区三表相渗方案并计算</el-button></el-form>
    <p v-if="combined">此模型同时包含SAT区与PVT区。本面板按岩性SAT区调整相渗；油气物性在PVT面板操作，两张区域图分别选择。</p>
    <p v-else>SOF3油相方案统一调整Krow/Krog两列，保持最大油饱和度处末端值相同。当前支持明确单DATA、单PVT、2–8个SAT区的三相原表；默认项、INCLUDE、滞后、端点缩放及E300相渗编辑继续扩展。</p>
  </section>
</template>
