<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { softwareIntegrationApi } from '@/api/softwareIntegration'
import { createFieldRequestGate } from './eclipseFieldRequest.js'
import { spatialSnapshot } from './eclipseSpatialComparison.js'
import { loadSpatialCellHistory, spatialCellHistoryCsv } from './eclipseSpatialCellHistory.js'
import { gridFieldUnit } from './eclipseGridPresentation.js'
import { exportEclipseCharts } from './eclipseChartExport.js'
const props = defineProps({ run:Object,baseline:Object,index:Number,keyword:String,unitSystem:String })
const data=ref(null),loading=ref(false),error=ref(''),progress=ref(''),host=ref(null),gate=createFieldRequestGate()
const unit=computed(()=>gridFieldUnit({keyword:props.keyword},props.unitSystem)),last=computed(()=>data.value?.rows.at(-1))
const fmt=value=>Number.isFinite(value)?value.toLocaleString('zh-CN',{maximumFractionDigits:6}):'—'
async function load(){
  const current=gate.begin();data.value=null;error.value='';loading.value=false;progress.value=''
  if(!Number.isSafeInteger(props.index)||props.index<0)return
  loading.value=true
  try{const result=await loadSpatialCellHistory({run:spatialSnapshot(props.run),baseline:spatialSnapshot(props.baseline),index:props.index,keyword:props.keyword,unitSystem:props.unitSystem,request:softwareIntegrationApi.downloadArtifactRange,isCurrent:current,onProgress:(done,total)=>{if(current())progress.value=`${done} / ${total}`}});if(current())data.value=result}
  catch(e){if(current())error.value=e.message||'单格历史读取失败。'}finally{if(current())loading.value=false}
}
watch([()=>props.run?.id,()=>props.baseline?.id,()=>props.index,()=>props.keyword,()=>props.unitSystem,()=>props.run?.artifacts,()=>props.baseline?.artifacts],load,{immediate:true})
let chart,observer,generation=0
async function render(){
  const token=++generation;await nextTick();if(token!==generation)return
  if(!host.value||!data.value){observer?.disconnect();chart?.dispose();chart=null;return}
  if(!chart||chart.getDom()!==host.value){observer?.disconnect();chart?.dispose();chart=echarts.init(host.value);observer=new ResizeObserver(()=>chart?.resize());observer.observe(host.value)}
  const d=data.value,series=[{name:`基线 #${d.baselineId}`,type:'line',showSymbol:true,itemStyle:{color:'#8da5bd'},lineStyle:{type:'dashed'},data:d.rows.map(r=>[r.timeDays,r.baselineValue])},{name:`方案 #${d.runId}`,type:'line',showSymbol:true,itemStyle:{color:'#287fbe'},lineStyle:{width:3},data:d.rows.map(r=>[r.timeDays,r.value])},{name:'差值 · 方案−基线',type:'line',showSymbol:true,yAxisIndex:1,itemStyle:{color:'#d98c40'},data:d.rows.map(r=>[r.timeDays,r.difference])}]
  if(Number.isFinite(props.run?.parameters?.startTimeDays))series[0].markLine={symbol:'none',label:{formatter:'后期调整生效后'},lineStyle:{color:'#aa996d',type:'dashed'},data:[{xAxis:props.run.parameters.startTimeDays}]}
  chart.setOption({animation:false,title:{text:`${d.keyword} · I${d.i} / J${d.j} / K${d.k}`,subtext:`实际报告 ${d.rows.length} 个 · ${unit.value} · 同网格逐格原值`,left:12,top:10,textStyle:{fontSize:13,color:'#315b78'},subtextStyle:{fontSize:10}},legend:{type:'scroll',top:56,textStyle:{fontSize:10}},tooltip:{trigger:'axis',renderMode:'richText'},grid:{left:18,right:35,top:104,bottom:50,containLabel:true},xAxis:{type:'value',name:'实际天数',nameLocation:'middle',nameGap:28,splitLine:{show:false}},yAxis:[{type:'value',name:unit.value,scale:true,splitLine:{lineStyle:{color:'#edf3f8'}}},{type:'value',name:`Δ ${unit.value}`,scale:true,splitLine:{show:false},axisLabel:{color:'#b27e45'}}],dataZoom:[{type:'inside'}],series},true);chart.resize()
}
watch([data,unit,()=>props.run?.parameters?.startTimeDays],render,{immediate:true})
onBeforeUnmount(()=>{generation++;gate.dispose();observer?.disconnect();chart?.dispose()})
const exportPng=()=>chart&&exportEclipseCharts([chart],`eclipse-${props.run.id}-${props.keyword}-cell-${data.value.i}-${data.value.j}-${data.value.k}.png`)
function exportCsv(){if(!data.value)return;const url=URL.createObjectURL(new Blob([spatialCellHistoryCsv(data.value,unit.value)],{type:'text/csv;charset=utf-8'})),a=document.createElement('a');a.href=url;a.download=`eclipse-${props.run.id}-${props.keyword}-cell-history.csv`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
</script>
<template>
  <section class="cell-history" aria-label="ECLIPSE单格实际历史曲线" :data-ready="data&&!loading?'true':'false'" :data-cell-index="index" :data-keyword="keyword" :data-run-id="run?.id">
    <header><div><span>ONE CELL / REAL REPORTS</span><h4>同一个位置，随时间发生了什么</h4></div><div><el-button size="small" :disabled="!data||loading" @click="exportPng">导出单格历史PNG</el-button><el-button size="small" :disabled="!data||loading" @click="exportCsv">导出单格历史CSV</el-button></div></header>
    <p v-if="loading" class="cell-loading">正在读取所选活动格的实际报告 {{progress}}…</p><el-alert v-if="error" type="warning" :closable="false" :title="error"/>
    <template v-if="data&&!loading"><div class="cell-history-cards"><div><small>位置</small><strong>I{{data.i}} · J{{data.j}} · K{{data.k}}</strong><span>{{data.rows.length}}个共同实际报告</span></div><div><small>末报告基线</small><strong>{{fmt(last.baselineValue)}}</strong><span>{{unit}} · 第{{last.timeDays}}天</span></div><div><small>末报告方案</small><strong>{{fmt(last.value)}}</strong><span>{{unit}} · 第{{last.timeDays}}天</span></div><div><small>末报告差值</small><strong>{{fmt(last.difference)}}</strong><span>{{unit}} · 方案−基线</span></div></div><div ref="host" class="cell-history-chart" role="img" aria-label="选中单元基线方案及差值实际历史"/><p class="cell-history-note">左轴是原始场值，右轴是差值。只读取当前活动格的真实二进制数据；逐报告核对实际时间、网格和单位。共同报告最多256个，不补时刻、不插值；切换单元后旧请求不会覆盖新位置。</p></template>
  </section>
</template>
<style scoped>
.cell-history{margin-top:18px;padding:20px;border:1px solid #d7e5f0;border-radius:11px;background:linear-gradient(125deg,#f0f7ff,#fff 70%);color:#577b96;min-width:0}.cell-history header{display:flex;justify-content:space-between;align-items:start;gap:12px;flex-wrap:wrap}.cell-history header span{font-size:10px;letter-spacing:.1em;color:#4282b2;font-weight:700}.cell-history h4{font-size:18px;color:#315774;margin:8px 0}.cell-history-cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin:16px 0}.cell-history-cards>div{min-width:0;padding:15px;border:1px solid #dfe9f2;background:#fff;border-radius:8px}.cell-history-cards small,.cell-history-cards span{font-size:10px;color:#839caf;display:block}.cell-history-cards strong{font-size:20px;color:#2d648c;display:block;margin:10px 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.cell-history-chart{height:350px;min-width:0;background:#fff;border:1px solid #e2ebf3;border-radius:9px}.cell-history-note{font-size:11px;line-height:1.8;color:#8aa0b1}.cell-loading{text-align:center;padding:40px;font-size:12px}@media(max-width:1000px){.cell-history-cards{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:550px){.cell-history{padding:12px}.cell-history-cards strong{font-size:17px}.cell-history header .el-button{margin:4px 4px 0 0}}
</style>
