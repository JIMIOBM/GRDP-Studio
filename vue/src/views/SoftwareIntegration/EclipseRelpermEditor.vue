<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import {computed,ref,watch} from 'vue'
import {buildRelperm,relpermContext,capillaryPressureUnit} from './eclipseRelperm'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-relperm-run'])
const context=computed(()=>relpermContext(props.inspection)),keyword=ref('SWFN'),multiplier=ref(null),error=ref('')
const table=computed(()=>context.value?.tables.find(t=>t.keyword===keyword.value))
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run.status==='SUCCEEDED'&&props.run.resultContract==='VALID_FULL'&&props.run.parameters==null)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,table],()=>{multiplier.value=null;error.value=''}, {immediate:true,deep:true})
const preview=computed(()=>table.value?.rows.map(r=>({line:r.lineNumber,saturation:r.values[0],kr:r.values[1],third:r.values[2],target:multiplier.value==null?null:Number(r.values[1].replace(/[dD]/,'e'))*multiplier.value,targetOilGas:keyword.value==='SOF3'&&multiplier.value!=null?Number(r.values[2].replace(/[dD]/,'e'))*multiplier.value:null}))||[])
function buildParameters(){return buildRelperm({run:props.run,inspection:props.inspection,keyword:keyword.value,multiplier:multiplier.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-relperm-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && !inspection?.coupledRelpermContext && !inspection?.multiSatRelpermContext" class="result-panel" aria-label="ECLIPSE 相渗调整">
    <h2>相对渗透率 · 水相、气相与油相</h2>
    <p>用于比较岩心相渗曲线的不确定性对产油、产水、总液量及井压力的影响。相渗是0–1无量纲比例，保留饱和度、毛管压力、PVT、网格及井控。</p>
    <el-alert v-if="!context" type="info" :closable="false" title="需要单DATA三相、唯一明确SAT区和SWFN/SGFN/SOF3原表；默认值、多区、滞后、端点缩放、INCLUDE及混合表格式尚未开放。" />
    <template v-else>
      <el-form-item label="相渗调整项目"><el-select v-model="keyword" aria-label="相渗调整项目"><el-option label="水相相渗 · SWFN" value="SWFN"/><el-option label="气相相渗 · SGFN" value="SGFN"/><el-option label="油相两列联动 · SOF3" value="SOF3"/></el-select></el-form-item>
      <p>基线 #{{run.id}} · {{context.sourceFile}}:{{table.keywordLineNumber}} · {{table.rows.length}} 行 · {{keyword}} · 相渗无量纲</p>
      <EclipseInputCurvePreview :tables="[{ keyword, keywordLineNumber: table.keywordLineNumber, rows: preview }]" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="keyword" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="context.sourceFile" region-kind="SAT" />
      <el-table :data="preview" border size="small" max-height="280" aria-label="ECLIPSE 原相渗与目标预览">
        <el-table-column prop="line" label="源行"/><el-table-column prop="saturation" :label="keyword==='SWFN'?'水饱和度 Sw':keyword==='SGFN'?'气饱和度 Sg':'油饱和度 So'"/>
        <el-table-column prop="kr" :label="keyword==='SWFN'?'原水相渗 Krw':keyword==='SGFN'?'原气相渗 Krg':'原油水相渗 Krow'"/><el-table-column prop="target" :label="keyword==='SWFN'?'目标 Krw':keyword==='SGFN'?'目标 Krg':'目标 Krow'"/>
        <el-table-column prop="third" :label="keyword!=='SOF3'?`原毛管压力（${capillaryPressureUnit(inspection.unitSystem)}）`:'原油气相渗 Krog'"/>
        <el-table-column v-if="keyword==='SOF3'" prop="targetOilGas" label="目标 Krog"/>
      </el-table>
      <el-form label-position="top" @submit.prevent="submit"><el-form-item label="相渗统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="相渗乘数"/></el-form-item>
        <el-alert v-if="error" type="error" :closable="false" :title="error"/><el-button type="primary" :disabled="multiplier==null" @click="submit">创建相渗方案并计算</el-button>
      </el-form>
      <p>油相方案同时调整Krow/Krog，保证最大油饱和度处末端值相等。水相只调整Krw，气相只调整Krg，均保留毛管压力列。实际产量由三相流动和原井限制共同决定，不按乘数推算产量。</p>
    </template>
  </section>
</template>
