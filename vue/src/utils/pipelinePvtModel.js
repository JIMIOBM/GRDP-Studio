import { temperatureImportTableRows, temperatureImportNumber } from './pipelineTemperatureImport.js'

export const pipelinePvtMethods = ['PR', 'SRK', 'BWRS']
export const pipelinePvtDrafts = new Map()
export const pvtModelColumns = [
  {key:'sequence',label:'序号'}, {key:'code',label:'组分代码'}, {key:'name',label:'组分名称'},
  {key:'percent',label:'摩尔含量（%）'}, {key:'criticalTemperatureK',label:'临界温度（K）'},
  {key:'criticalPressureMpa',label:'临界压力（MPa）'}, {key:'acentricFactor',label:'偏心因子'},
  {key:'molarMassKgMol',label:'摩尔质量（kg/mol）'}
]
export function pvtModelRows(catalog, composition = []) {
  const values=new Map(composition.map(row=>[row.code,row.moleFraction]))
  return catalog.map((component,index)=>({...component,sequence:index+1,
    criticalPressureMpa:component.criticalPressurePa/1e6,
    percent:values.has(component.code)?values.get(component.code)==null?null:values.get(component.code)*100:0}))
}
export function pvtModelComposition(rows) {return rows.map(row=>({code:row.code,moleFraction:row.percent==null?null:Number(row.percent)/100}))}
export function pvtModelIssues(rows, method) {
  const errors=[]
  if(!rows.length)errors.push('组分常数未加载，请重新加载页面。')
  if(!pipelinePvtMethods.includes(method))errors.push('请选择 PR、SRK 或 BWRS 计算方法。')
  const missing=rows.filter(row=>row.percent==null||row.percent==='')
  if(missing.length)errors.push(`请填写${missing.map(row=>row.name||row.code).join('、')}的摩尔含量；不含该组分请明确填写 0。`)
  const invalid=rows.filter(row=>row.percent!=null&&row.percent!==''&&(!Number.isFinite(Number(row.percent))||row.percent<0||row.percent>100))
  if(invalid.length)errors.push('各组分摩尔含量必须是 0～100% 的有限数值。')
  const total=rows.reduce((sum,row)=>sum+Number(row.percent),0)
  if(!missing.length&&!invalid.length&&Math.abs(total-100)>0.0001)errors.push(`全部组分摩尔含量合计为 ${total.toFixed(6)}%，应为 100%。`)
  return errors
}
export function pvtModelTemplateRows(catalog, composition=[]) {
  return [pvtModelColumns.map(column=>column.label),...pvtModelRows(catalog,composition).map(row=>pvtModelColumns.map(column=>row[column.key]??''))]
}
export function parsePvtModelRows(table,catalog) {
  if(!catalog.length)throw new Error('组分常数未加载，请重新打开导入窗口。')
  const rows=pvtModelRows(catalog),found=new Map()
  for(const {row,line} of temperatureImportTableRows(table,pvtModelColumns,'PVT 气体组成')) {
    const sequence=temperatureImportNumber(row[0],'序号',line,{minExclusive:0})
    const expected=rows[sequence-1]
    if(!Number.isInteger(sequence)||!expected||row[1]!==expected.code||row[2]!==expected.name)throw new Error(`第 ${line} 行：组分序号、代码与名称不匹配，请使用当前模板。`)
    if(found.has(expected.code))throw new Error(`第 ${line} 行：组分 ${expected.code} 重复。`)
    for(let index=4;index<pvtModelColumns.length;index++) {
      const value=temperatureImportNumber(row[index],pvtModelColumns[index].label,line)
      if(Math.abs(value-expected[pvtModelColumns[index].key])>1e-10*Math.max(1,Math.abs(value)))throw new Error(`第 ${line} 行：${pvtModelColumns[index].label}为内置纯组分常数，请勿修改。`)
    }
    const percent=temperatureImportNumber(row[3],'摩尔含量',line,{optional:true})
    if(percent!=null&&(percent<0||percent>100))throw new Error(`第 ${line} 行：摩尔含量应为 0～100%。`)
    found.set(expected.code,{code:expected.code,moleFraction:percent==null?null:percent/100})
  }
  if(found.size!==rows.length)throw new Error(`请保留全部 ${rows.length} 个组分，不含的组分填写 0。`)
  return rows.map(row=>found.get(row.code))
}

/** IDs from the historical data-management PVT table never identify this model source. */
export const pipelinePvtSourceStamp = model => JSON.stringify({source:'pipeline-pvt-model-v1',pvtId:model?.pvtId??null,
  revision:model?.revision??0,compositionRevision:model?.compositionRevision??'',method:model?.method??null})
export const pipelinePvtChanged = (model,snapshot) => !model || !!model.issue || !snapshot
  || Number(snapshot.pvtId)!==Number(model.pvtId)||snapshot.revision!==model.revision
  || snapshot.compositionRevision!==model.compositionRevision||snapshot.method!==model.method
