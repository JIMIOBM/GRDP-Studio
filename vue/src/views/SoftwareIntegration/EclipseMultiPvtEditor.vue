<script setup>
import EclipseInputCurvePreview from './EclipseInputCurvePreview.vue'
import {computed,ref,watch} from 'vue'
import {buildMultiPvt,multiPvtContext,multiPvtChoices,multiPvtUnit} from './eclipseMultiPvt'
import {oilPressureUnit} from './eclipseOilPvt'
import {multiFamilyIIContext} from './eclipseMultiSatFamilyII.js'
import {multiSatRelpermContext} from './eclipseMultiSatRelperm.js'
const props=defineProps({run:{type:Object,default:null},inspection:{type:Object,default:null}})
const emit=defineEmits(['create-pvt-run'])
const context=computed(()=>multiPvtContext(props.inspection)),regionNumber=ref(1),kind=ref('PVT_OIL_MU'),multiplier=ref(null),error=ref('')
const combined=computed(()=>!!(multiFamilyIIContext(props.inspection)||multiSatRelpermContext(props.inspection)) && !!context.value)
const region=computed(()=>context.value?.regions.find(r=>r.number===regionNumber.value)),choice=computed(()=>multiPvtChoices[kind.value])
const baseline=computed(()=>props.run?.runType==='eclipse'&&props.run.status==='SUCCEEDED'&&props.run.resultContract==='VALID_FULL'&&props.run.parameters==null&&!props.run.resultExpired)
watch([()=>props.run?.id,()=>props.run?.modelVersionId,context],()=>{regionNumber.value=context.value?.regions[0]?.number||1},{immediate:true,deep:true})
watch([()=>props.run?.id,()=>props.run?.modelVersionId,region,kind],()=>{multiplier.value=null;error.value=''},{immediate:true,deep:true})
const preview=computed(()=>region.value?.tables.map(t=>({...t,rows:t.rows.map(r=>({line:r.lineNumber,pressure:r.values[0],volume:r.values[1],viscosity:r.values[2],target:multiplier.value==null||t.keyword!==choice.value.keyword?null:Number(r.values[choice.value.field==='VISCOSITY'?2:1].replace(/[dD]/,'e'))*multiplier.value}))}))||[])
function buildParameters(){return buildMultiPvt({run:props.run,inspection:props.inspection,regionNumber:regionNumber.value,...choice.value,multiplier:multiplier.value})}
function submit(){try{const p=buildParameters();error.value='';emit('create-pvt-run',p)}catch(e){error.value=e.message}}
</script>
<template>
  <section v-if="baseline && context" class="result-panel" aria-label="ECLIPSE 多区PVT调整">
    <h2>PVDO / PVDG · 分区流体物性</h2>
    <p>用于比较不同流体区域的油、气黏度和体积系数不确定性。仅改变所选PVT区的目标列；其它区、压力列、水与岩石物性、PVTNUM地图、初始状态及井控保留。连通区域的其它井也可能响应。</p>
    <el-form-item label="流体PVT区"><el-select v-model="regionNumber" aria-label="流体PVT区"><el-option v-for="r in context.regions" :key="r.number" :value="r.number" :label="`PVT区 ${r.number} · ${r.cellCount} 格`"/></el-select></el-form-item>
    <el-form-item label="分区PVT调整项目"><el-select v-model="kind" aria-label="分区PVT调整项目"><el-option v-for="(c,value) in multiPvtChoices" :key="value" :value="value" :label="c.label"/></el-select></el-form-item>
    <p>基线 #{{run.id}} · {{context.sourceFile}} · PVT区 {{regionNumber}} · 目标单位 {{multiPvtUnit(inspection.unitSystem,choice.keyword,choice.field)}}</p>
    <EclipseInputCurvePreview :tables="preview" :unit-system="inspection.unitSystem" :multiplier="multiplier" :field="choice.field" :focus-keyword="choice.keyword" :validate="buildParameters" :run-id="run.id" :model-version-id="run.modelVersionId" :source-file="context.sourceFile" region-kind="PVT" :region="regionNumber" />
    <template v-for="table in preview" :key="table.keyword">
      <h3>{{table.keyword}} · {{table.rows.length}} 行 · 源行 {{table.keywordLineNumber}}</h3>
      <el-table :data="table.rows" border size="small" max-height="240" :aria-label="`ECLIPSE 分区${table.keyword}原表与目标`">
        <el-table-column prop="line" label="源行"/><el-table-column prop="pressure" :label="`原压力（${oilPressureUnit(inspection.unitSystem)}）`"/>
        <el-table-column prop="volume" :label="`原${table.keyword==='PVDO'?'Bo':'Bg'}（${multiPvtUnit(inspection.unitSystem,table.keyword,'FORMATION_VOLUME_FACTOR')}）`"/><el-table-column prop="viscosity" label="原黏度（cP）"/><el-table-column prop="target" label="所选项目目标值"/>
      </el-table>
    </template>
    <el-form label-position="top" @submit.prevent="submit"><el-form-item label="分区PVT统一乘数"><el-input-number v-model="multiplier" :controls="false" aria-label="分区PVT乘数"/></el-form-item><el-alert v-if="error" type="error" :closable="false" :title="error"/><el-button type="primary" :disabled="multiplier==null" @click="submit">创建分区PVT方案并计算</el-button></el-form>
    <p v-if="combined">此模型同时包含SAT区与PVT区。本面板按流体PVT区调整油气物性；岩性相渗在SAT面板操作，两张区域图分别选择。</p>
    <p v-else>当前支持单DATA、单SAT、2–8个PVT区的E100死油/干气模型，需明确PRESSURE/SWAT/SGAS初始数组与各区完整辅助物性。EQUIL初始化、多区PVTO/PVTG及INCLUDE继续扩展。跨PVT区连接可能产生原生警告，请结合运行诊断判断模型设置。</p>
  </section>
</template>
