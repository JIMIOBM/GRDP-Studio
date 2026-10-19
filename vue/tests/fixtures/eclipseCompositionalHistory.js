import { compositionalBatchFixture } from './eclipseCompositionalBatch.js'
export function compositionalHistoryFixture(){
 const f=compositionalBatchFixture(),oils=f.baseline.result.summary.series.filter(s=>s.keyword==='FOPT'),observations=oils.flatMap(s=>[s.points[0],s.points.at(-1)].map((p,j)=>({well:'',keyword:s.keyword,unit:s.unit,timeDays:p.timeDays,value:p.value,sourceLine:2+oils.indexOf(s)*2+j,vectorIndex:s.vectorIndex})))
 const csv='well,keyword,unit,timeDays,value,vectorIndex\n'+observations.map(o=>[o.well,o.keyword,o.unit,o.timeDays,o.value,o.vectorIndex].join(',')).join('\n')
 const dataset={metadata:{id:10301,modelVersionId:f.baseline.modelVersionId,baselineRunId:f.baseline.id,name:'合成观察演示',sourceFile:'synthetic-observations.csv',sourceSha256:'a'.repeat(64),observationCount:observations.length,createdAt:'2026-10-18T10:00:00'},csv,observations}
 const evaluate=run=>{
  const points=observations.map(o=>{const s=run.result.summary.series.find(s=>s.vectorIndex===o.vectorIndex&&s.keyword===o.keyword&&s.unit===o.unit&&(s.objectName||'')===o.well),p=s?.points.find(p=>p.timeDays===o.timeDays),value=p?.value??null;return{well:o.well,keyword:o.keyword,unit:o.unit,timeDays:o.timeDays,observed:o.value,simulated:value,difference:value==null?null:value-o.value,sourceLine:o.sourceLine,reason:value==null?'没有同一报告时刻':'',vectorIndex:o.vectorIndex}})
  const metrics=oils.map(s=>{const group=points.filter(p=>p.vectorIndex===s.vectorIndex),matched=group.filter(p=>p.simulated!=null),d=matched.map(p=>p.difference),ratios=matched.filter(p=>p.observed!==0).map(p=>Math.abs(p.difference/p.observed)*100);return{well:'',keyword:'FOPT',unit:s.unit,vectorIndex:s.vectorIndex,observedCount:group.length,matchedCount:d.length,missingCount:group.length-d.length,coverage:d.length/group.length,mae:d.length?d.reduce((a,b)=>a+Math.abs(b),0)/d.length:null,rmse:d.length?Math.sqrt(d.reduce((a,b)=>a+b*b,0)/d.length):null,bias:d.length?d.reduce((a,b)=>a+b,0)/d.length:null,mape:ratios.length?ratios.reduce((a,b)=>a+b,0)/ratios.length:null,mapeCount:ratios.length,mapeOverflowCount:0}})
  return{dataset:dataset.metadata,runId:run.id,comparison:{points,metrics}}
 }
 return {...f,dataset,evaluate}
}
