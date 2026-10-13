import { historyIdentity, historyRunAvailable, validHistoryDataset, validHistoryEvaluation } from './eclipseHistory.js'
const terminal = new Set(['SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED'])
export function historyBatchCandidates(batch, dataset) {
  if (!validHistoryDataset(dataset, batch?.modelVersionId) || batch?.baselineRunId !== dataset.metadata.baselineRunId
    || batch.baseline?.id !== batch.baselineRunId || batch.baseline.modelVersionId !== batch.modelVersionId
    || !Array.isArray(batch.members) || batch.members.length < 2 || batch.members.length > 8) throw new Error('批量方案必须使用观察记录对应的原模型版本和基线。')
  const rows = [{ name: '原基线', run: batch.baseline }, ...batch.members], ids = new Set()
  for (const row of rows) {
    if (!Number.isSafeInteger(row.run?.id) || row.run.id <= 0 || row.run.modelVersionId !== batch.modelVersionId || ids.has(row.run.id)
      || typeof row.name !== 'string' || !row.name.trim() || row.run.id !== batch.baselineRunId && row.run.parameters?.baselineRunId !== batch.baselineRunId) throw new Error('批量记录的子运行身份或来源不一致。')
    ids.add(row.run.id)
  }
  return rows
}
function metricMatchesPoints(metric, points) {
  const matched = points.filter(p => p.simulated != null)
  if (!matched.length) return metric.mae === null && metric.rmse === null && metric.bias === null
  let mae = 0, square = 0, bias = 0
  for (const [i,p] of matched.entries()) {
    mae += (Math.abs(p.difference)-mae)/(i+1); square += (p.difference*p.difference-square)/(i+1); bias += (p.difference-bias)/(i+1)
  }
  return [['mae',mae],['rmse',Math.sqrt(square)],['bias',bias]].every(([key,value]) => Number.isFinite(value) && Math.abs(metric[key]-value) <= Math.max(1e-12, Math.abs(value)*1e-12))
}
export function historyBatchComparison({ batch, dataset, evaluations, failures = {}, metricKey }) {
  try {
    const candidates=historyBatchCandidates(batch,dataset),rows=[]
    let incomplete=false
    for (const {name,run} of candidates) {
      const row={name,runId:run.id,status:run.status,rank:null,metric:null,reason:''}
      if (!historyRunAvailable(run)) row.reason=run.resultExpired?'结果已过期':run.error?.message || run.error?.code || '无完整成功结果'
      else {
        const evaluation=evaluations[run.id]
        if (!validHistoryEvaluation(evaluation,dataset,run)) { row.reason=failures[run.id] || '校核结果未能核对，请重试';incomplete=true }
        else {
          const metric=evaluation.comparison.metrics.find(m=>historyIdentity(m)===metricKey)
          const points=evaluation.comparison.points.filter(p=>historyIdentity(p)===metricKey)
          if (!metric || !metricMatchesPoints(metric,points)) { row.reason='误差指标与实际逐点结果不一致';incomplete=true }
          else {
            row.metric=metric
            row.matchedSignature=JSON.stringify(points.filter(p=>p.simulated != null).map(p=>[p.sourceLine,p.timeDays]).sort((a,b)=>a[0]-b[0] || a[1]-b[1]))
            if (!metric.matchedCount) row.reason='该指标没有共同报告时刻'
          }
        }
      }
      rows.push(row)
    }
    const available=rows.filter(row=>row.metric?.matchedCount>0)
    const aligned=available.length>=2 && available.every(row=>row.matchedSignature===available[0].matchedSignature)
    const zeroCoverage=rows.some(row=>row.metric && row.metric.matchedCount===0)
    const canRank=terminal.has(batch.status) && !incomplete && !zeroCoverage && aligned
    let message=''
    if (canRank) {
      const sorted=[...available].sort((a,b)=>a.metric.rmse-b.metric.rmse || a.runId-b.runId)
      for (let i=0;i<sorted.length;i++) sorted[i].rank=i>0 && sorted[i].metric.rmse===sorted[i-1].metric.rmse?sorted[i-1].rank:i+1
      rows.sort((a,b)=>(a.rank??Infinity)-(b.rank??Infinity))
      message='按本指标 RMSE 比较；所有参与排序的方案使用完全相同的观察点。排名不代表其它指标或物理模型已通过验收。'
    } else message=!terminal.has(batch.status)?'批量任务尚未结束，暂不排序。':incomplete?'部分成功运行尚未完成校核，请重试。':available.length<2?'至少需要两项有可比点的完整结果才能比较。':'方案覆盖的观察时刻不同，暂不排序；请检查报告时刻和缺失点。'
    return {rows,canRank,message}
  } catch (error) { return {rows:[],canRank:false,message:error.message} }
}
export function historyBatchCsv(rows, dataset) {
  const cell=value=>{const s=String(value??''),safe=typeof value==='string' && /^[=+\-@\t\r]/.test(s)?"'"+s:s;return '"'+safe.replaceAll('"','""')+'"'}
  return '\uFEFF'+[['观察源SHA256','方案','运行','状态','井/对象','指标','单位','观察点','可比点','覆盖率','MAE','RMSE','平均误差','排名','原因'],
    ...rows.map(r=>[dataset.metadata.sourceSha256,r.name,r.runId,r.status,r.metric?.well,r.metric?.keyword,r.metric?.unit,r.metric?.observedCount,r.metric?.matchedCount,r.metric?.coverage,r.metric?.mae,r.metric?.rmse,r.metric?.bias,r.rank,r.reason])].map(row=>row.map(cell).join(',')).join('\r\n')
}
