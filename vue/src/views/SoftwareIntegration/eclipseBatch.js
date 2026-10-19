import { buildWetGasPvt, wetGasPvtRecord, validWetGasPvt } from './eclipseWetGasPvt.js'
import { buildLiveOilPvt, liveOilPvtRecord, validLiveOilPvt } from './eclipseLiveOilPvt.js'
import { buildPvtw, pvtwRecord, pvtwLabels, pvtwUnit, validPvtw } from './eclipsePvtw.js'
import { buildOilPvt, oilPvtRecord, validOilPvt } from './eclipseOilPvt.js'
import { buildGasPvt, gasPvtRecord, validGasPvt } from './eclipseGasPvt.js'
import {buildMultiLiveOilPvt,multiLiveOilPvtContext,multiLiveOilChoices,validMultiLiveOilPvt} from './eclipseMultiLiveOilPvt.js'
import {buildMultiWaterPvt,multiWaterPvtContext,validMultiWaterPvt} from './eclipseMultiWaterPvt.js'
import {buildMultiWetGasPvt,multiWetGasPvtContext,multiWetGasChoices,validMultiWetGasPvt} from './eclipseMultiWetGasPvt.js'
import {buildMultiPvt,multiPvtContext,multiPvtChoices,validMultiPvt} from './eclipseMultiPvt.js'
import {buildMultiFamilyII,multiFamilyIIContext,multiFamilyIILabels,validMultiFamilyII} from './eclipseMultiSatFamilyII.js'
import {buildMultiSatRelperm,multiSatRelpermContext,validMultiSatRelperm} from './eclipseMultiSatRelperm.js'
import {buildCoupledRelperm,coupledRelpermContext,coupledRelpermLabels,validCoupledRelperm} from './eclipseCoupledRelperm.js'
import { buildRelperm, relpermContext, validRelperm } from './eclipseRelperm.js'
import { validCompositionalMetadata,validCompositionalInspection,compositionalResult } from './eclipseCompositional.js'
import { compositionalFluidPropertyRecords,buildCompositionalFluidProperty,validCompositionalFluidProperty,compositionalFluidPropertyResponse } from './eclipseCompositionalFluidProperty.js'
import { gridRegionResponse, validGridRegionParameters } from './eclipseGridRegion.js'

export const batchStatusLabels = { QUEUED: '排队中', RUNNING: '计算中', SUCCEEDED: '全部完成', PARTIAL_SUCCEEDED: '部分完成', FAILED: '失败', CANCELLED: '已取消' }
export const batchPending = batch => ['QUEUED', 'RUNNING'].includes(batch?.status)
export const batchBaselineAvailable = run => Number.isSafeInteger(run?.id) && Number.isSafeInteger(run?.modelVersionId)
  && run.runType === 'eclipse' && run.status === 'SUCCEEDED' && run.resultContract === 'VALID_FULL'
  && run.parameters == null && !run.resultExpired && (run.result?.schemaVersion === 'eclipse-summary-result/1' && run.result.modelKind === 'eclipse_100' || run.result?.schemaVersion === 'eclipse-summary-result/2' && run.result.modelKind === 'eclipse_300' && validCompositionalMetadata(run.result.simulation))
  && ['problems', 'errors', 'bugs'].every(key => run.result.eclEnd?.[key] === 0)
export const compositionalBatchLabels = { PCRIT:'临界压力',TCRIT:'临界温度',ZCRIT:'临界Z因子',ACF:'偏心因子',MW:'分子量',OMEGAA:'Ωa系数',OMEGAB:'Ωb系数' }
export function batchChoices(inspection) {
  const options = []
  if(validCompositionalInspection(inspection))for(const [field,label] of Object.entries(compositionalBatchLabels)){const records=compositionalFluidPropertyRecords(inspection,field);if(records.length===9)options.push({kind:'E300_'+field,label:`组分${label} · ${field}`,unit:records[0].unit})}
  if(multiWaterPvtContext(inspection))for(const [field,label] of Object.entries(pvtwLabels))options.push({kind:`REGION_PVTW_${field}`,label:`分区水物性 · ${label}`,unit:pvtwUnit(inspection.unitSystem,field)})
  if (pvtwRecord(inspection)) for (const [field, label] of Object.entries(pvtwLabels)) options.push({ kind: field, label: `水物性 · ${label}`, unit: pvtwUnit(inspection.unitSystem, field) })
  if (wetGasPvtRecord(inspection)) options.push({ kind: 'PVTG', label: '湿气黏度乘数', unit: '倍' }, { kind: 'PVTG_BG', label: '湿气体积系数 Bg 乘数', unit: '倍' })
  if (liveOilPvtRecord(inspection)) options.push({ kind: 'PVTO', label: '含溶解气原油黏度乘数', unit: '倍' }, { kind: 'PVTO_BO', label: '含溶解气原油体积系数 Bo 乘数', unit: '倍' })
  if (oilPvtRecord(inspection)) options.push({ kind: 'PVDO', label: '死油黏度乘数', unit: '倍' }, { kind: 'PVDO_BO', label: '死油体积系数 Bo 乘数', unit: '倍' })
  if (gasPvtRecord(inspection)) options.push({ kind: 'PVDG', label: '干气黏度乘数', unit: '倍' }, { kind: 'PVDG_BG', label: '干气体积系数 Bg 乘数', unit: '倍' })
  if (relpermContext(inspection)) options.push({ kind: 'SWFN', label: '水相 Krw 乘数', unit: '倍' }, { kind: 'SGFN', label: '气相 Krg 乘数', unit: '倍' }, { kind: 'SOF3', label: '油相两列相渗乘数', unit: '倍' })
  if (coupledRelpermContext(inspection)) for(const [kind,label] of Object.entries(coupledRelpermLabels)) options.push({kind,label: `${label} 乘数`,unit: '倍'})
  if (multiSatRelpermContext(inspection)) for(const [field,label] of Object.entries(coupledRelpermLabels)) options.push({kind: `MULTI_${field}`,label: `分区 ${label} 乘数`,unit: '倍'})
  if (multiFamilyIIContext(inspection)) for(const [field,label] of Object.entries(multiFamilyIILabels)) options.push({kind: `FAMILY_II_${field}`,label: `分区 ${label} 乘数`,unit: '倍'})
  if(multiPvtContext(inspection))for(const [kind,c] of Object.entries(multiPvtChoices))options.push({kind,label:`分区 ${c.label} 乘数`,unit:'倍'})
  if(multiLiveOilPvtContext(inspection))for(const [kind,c] of Object.entries(multiLiveOilChoices))options.push({kind,label:c.label,unit:'倍'})
  if(multiWetGasPvtContext(inspection))for(const [kind,c] of Object.entries(multiWetGasChoices))options.push({kind,label:c.label,unit:'倍'})
  return options
}
export function buildBatchCase({ run, inspection, kind, value, name, regionNumber, componentIndex }) {
  if (!batchBaselineAvailable(run)) throw new Error('请先选择当前版本、结果未过期的完整无参数 E100 或组分 E300 基线。')
  const label = typeof name === 'string' ? name.trim() : ''
  if (!label || label.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(label)) throw new Error('方案名称须为1–80个字符。')
  if (!batchChoices(inspection).some(option => option.kind === kind)) throw new Error('原模型没有可编辑的对应物性表。')
  if(kind.startsWith('E300_')!==(run.result.modelKind==='eclipse_300'))throw new Error('方案类型与基线求解器不一致。')
  if(kind.startsWith('E300_')){const parameters=buildCompositionalFluidProperty({inspection,baselineRunId:run.id,componentIndex,targetValue:value,field:kind.slice(5)});if(!parameters)throw new Error('请选择组分并填写不同于原值的有效物性数值；ACF允许零和负值，其余项目须为正数。');return {name:label,parameters}}
  const args = { run, inspection, multiplier: value, targetValue: value, field: kind, keyword: kind }
  const parameters = kind.startsWith('REGION_PVTW_') ? buildMultiWaterPvt({...args,regionNumber,field:kind.slice(12)}) : Object.hasOwn(multiWetGasChoices,kind) ? buildMultiWetGasPvt({...args,regionNumber,...multiWetGasChoices[kind]}) : Object.hasOwn(multiLiveOilChoices,kind) ? buildMultiLiveOilPvt({...args,regionNumber,...multiLiveOilChoices[kind]}) : Object.hasOwn(multiPvtChoices,kind) ? buildMultiPvt({...args,regionNumber,...multiPvtChoices[kind]}) : kind.startsWith('FAMILY_II_') ? buildMultiFamilyII({...args,regionNumber,field:kind.slice(10)}) : kind.startsWith('MULTI_') ? buildMultiSatRelperm({...args,regionNumber,field:kind.slice(6)}) : ['WATER_KR','GAS_KR','OIL_KR'].includes(kind) ? buildCoupledRelperm(args) : ['PVTG', 'PVTG_BG'].includes(kind) ? buildWetGasPvt({ ...args, field: kind === 'PVTG_BG' ? 'FORMATION_VOLUME_FACTOR' : 'VISCOSITY' }) : ['PVTO', 'PVTO_BO'].includes(kind) ? buildLiveOilPvt({ ...args, field: kind === 'PVTO_BO' ? 'FORMATION_VOLUME_FACTOR' : 'VISCOSITY' }) : ['PVDO', 'PVDO_BO'].includes(kind) ? buildOilPvt({ ...args, field: kind === 'PVDO_BO' ? 'FORMATION_VOLUME_FACTOR' : 'VISCOSITY' }) : ['PVDG', 'PVDG_BG'].includes(kind) ? buildGasPvt({ ...args, field: kind === 'PVDG_BG' ? 'FORMATION_VOLUME_FACTOR' : 'VISCOSITY' })
    : ['SWFN', 'SGFN', 'SOF3'].includes(kind) ? buildRelperm(args) : buildPvtw(args)
  return { name: label, parameters }
}
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object'
  ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
export const sameBatchCase = (a, b) => JSON.stringify(canonical(a.parameters)) === JSON.stringify(canonical(b.parameters))
export function buildGridBatchCase({ run, name, parameters }) {
  if (!batchBaselineAvailable(run) || run.result.modelKind !== 'eclipse_100' || parameters?.baselineRunId !== run.id || !validGridRegionParameters(parameters)) throw new Error('区域方案必须来自当前完整无参数 E100 或组分 E300 基线。')
  const label = typeof name === 'string' ? name.trim() : ''
  if (!label || label.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(label)) throw new Error('方案名称须为1–80个字符。')
  return { name: label, parameters: JSON.parse(JSON.stringify(parameters)) }
}
export function buildBatchRequest({ run, name, cases, requestKey }) {
  if (!batchBaselineAvailable(run)) throw new Error('完整基线不可用。')
  const request = { requestKey, name: typeof name === 'string' ? name.trim() : '', baselineRunId: run.id, cases: JSON.parse(JSON.stringify(cases)) }
  if(validCompositionalFluidProperty(request.cases[0]?.parameters)!==(run.result.modelKind==='eclipse_300'))throw new Error('方案类型与基线求解器不一致。')
  if (!validBatchRequest(request)) throw new Error('需要2–8个名称和参数各不重复、使用同一基线的有效方案。')
  return request
}
export function validBatchRequest(request) {
  const keys = ['requestKey', 'name', 'baselineRunId', 'cases']
  if (!request || Object.keys(request).length !== 4 || !keys.every(key => Object.hasOwn(request, key))
    || typeof request.requestKey !== 'string' || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(request.requestKey)
    || typeof request.name !== 'string' || !request.name.trim() || request.name !== request.name.trim() || request.name.length > 100
    || /[\u0000-\u001f\u007f-\u009f]/.test(request.name) || !Number.isSafeInteger(request.baselineRunId) || request.baselineRunId <= 0
    || !Array.isArray(request.cases) || request.cases.length < 2 || request.cases.length > 8 || new TextEncoder().encode(JSON.stringify(request)).length > 262144) return false
  return request.cases.every((item, index) => item && Object.keys(item).length === 2 && typeof item.name === 'string'
    && item.name === item.name.trim() && item.name.length > 0 && item.name.length <= 80 && !/[\u0000-\u001f\u007f-\u009f]/.test(item.name)
    && item.parameters?.baselineRunId === request.baselineRunId && [validCompositionalFluidProperty, validMultiWaterPvt, validMultiWetGasPvt, validMultiLiveOilPvt, validMultiPvt, validMultiFamilyII, validMultiSatRelperm, validCoupledRelperm, validPvtw, validWetGasPvt, validLiveOilPvt, validOilPvt, validGasPvt, validRelperm, validGridRegionParameters].some(validate => validate(item.parameters))
    && validCompositionalFluidProperty(item.parameters) === validCompositionalFluidProperty(request.cases[0]?.parameters)
    && request.cases.slice(0, index).every(old => old.name.toLowerCase() !== item.name.toLowerCase() && !sameBatchCase(old, item)))
}
export function batchResponse(batch, inspection = null) {
  if (!batchBaselineAvailable(batch?.baseline) || batch.baseline.id !== batch.baselineRunId || batch.baseline.modelVersionId !== batch.modelVersionId) return { rows: [], message: '基线缺失或已过期，无法对比。' }
  const rows = [], messages = []
  for (const member of batch.members || []) {
    const run = member.run
    if (run?.modelVersionId !== batch.modelVersionId || run.status !== 'SUCCEEDED' || run.resultContract !== 'VALID_FULL' || run.resultExpired || !run.result) continue
    if(batch.baseline.result.modelKind==='eclipse_300'){
      if(!Number.isSafeInteger(run.modelId)||!Number.isSafeInteger(batch.baseline.modelId)||run.parameters?.baselineRunId!==batch.baselineRunId||run.modelId!==batch.baseline.modelId||!compositionalResult(batch.baseline,inspection)||!compositionalResult(run,inspection)){messages.push(`${member.name}：组分结果来源核对未通过。`);continue}
      const checked=compositionalFluidPropertyResponse(run.result.summary,batch.baseline.result.summary,run.parameters,inspection)
      if(!checked?.verified){messages.push(`${member.name}：缺少完整同源、同单位的原始物性响应。`);continue}
      for(const metric of checked.metrics){
        const old=batch.baseline.result.summary.series.find(v=>v.vectorIndex===metric.vectorIndex),next=run.result.summary.series.find(v=>v.vectorIndex===metric.vectorIndex)
        const before=new Map(old.points.map(p=>[p.timeDays,p.value])),after=new Map(next.points.map(p=>[p.timeDays,p.value]))
        for(const timeDays of [...new Set([...before.keys(),...after.keys()])].sort((a,b)=>a-b)){const baselineValue=before.get(timeDays)??null,currentValue=after.get(timeDays)??null;rows.push({name:member.name,runId:run.id,vectorIndex:metric.vectorIndex,keyword:metric.keyword,well:metric.objectName||'全场',unit:metric.unit,timeDays,baseline:baselineValue,current:currentValue,difference:baselineValue===null||currentValue===null?null:currentValue-baselineValue})}
      }
      continue
    }
    const response = gridRegionResponse(run.result.summary, batch.baseline.result.summary, ['WBHP','WOPR','WWPR','WGPR','WLPR','WOIR','WWIR','WGIR','WOPT','WWPT','WGPT','WOIT','WWIT','WGIT'])
    if (!response.available) messages.push(`${member.name}：${response.message}`)
    else rows.push(...response.rows.map(row => ({ ...row, name: member.name, runId: run.id })))
  }
  return { rows, message: messages.join('；') || (rows.length ? '' : '成功方案产生实际结果后显示对比。') }
}
