import test from 'node:test'
import assert from 'node:assert/strict'
import {pvtModelColumns,pvtModelRows,pvtModelComposition,pvtModelIssues,pvtModelTemplateRows,parsePvtModelRows,pipelinePvtChanged,pipelinePvtSourceStamp} from './pipelinePvtModel.js'
import {normalizeTopologyPvtReferences} from './pipelineTopology.js'
import {assertTemperatureImportSheet,temperatureImportCsv,parseTemperatureImportCsv} from './pipelineTemperatureImport.js'
const codes=['CH4','C2H6','C3H8','IC4','NC4','IC5','NC5','NC6','NC7','NC8','NC9','NC10','N2','CO2','H2S']
const catalog=codes.map((code,index)=>({code,name:code,criticalTemperatureK:190+index,criticalPressurePa:4e6+index*1000,acentricFactor:0.01+index/100,molarMassKgMol:0.016+index/1000}))
const composition=[{code:'CH4',moleFraction:0.98},{code:'CO2',moleFraction:0.012},{code:'N2',moleFraction:0.008}]
test('all fifteen catalog components are shown with absent species explicitly zero and fractions converted to percent',()=>{
 const rows=pvtModelRows(catalog,composition)
 assert.deepEqual(rows.map(row=>row.code),codes)
 assert.equal(rows[0].percent,98);assert.equal(rows.find(row=>row.code==='CO2').percent,1.2)
 assert.equal(rows.find(row=>row.code==='NC10').percent,0)
 assert.equal(rows[0].criticalPressureMpa,4)
 assert.deepEqual(pvtModelIssues(rows,'PR'),[])
 assert.deepEqual(pvtModelComposition(rows).filter(row=>row.moleFraction>0),[composition[0],composition[2],composition[1]])
})
test('cleared fractions remain missing instead of becoming zero or silently filled methane',()=>{
 const rows=pvtModelRows(catalog,[{code:'CH4',moleFraction:null}])
 assert.equal(rows[0].percent,null);assert.equal(pvtModelComposition(rows)[0].moleFraction,null)
 assert.match(pvtModelIssues(rows,'PR').join(' '),/CH4.*不含该组分请明确填写 0/)
 rows[0].percent=0
 assert.match(pvtModelIssues(rows,'PR').join(' '),/0\.000000%.*100%/)
})
test('save validation rejects unsupported methods, invalid fractions and totals without renormalizing',()=>{
 const rows=pvtModelRows(catalog,composition)
 for(const value of [-1,101,Infinity,NaN]){rows[0].percent=value;assert.match(pvtModelIssues(rows,'PR').join(' '),/0～100%/)}
 rows[0].percent=97;assert.match(pvtModelIssues(rows,'SRK').join(' '),/99\.000000%/)
 rows[0].percent=98;assert.match(pvtModelIssues(rows,'unknown').join(' '),/PR、SRK 或 BWRS/)
})
test('composition template matches the data-list table and restores only gas composition',()=>{
 const table=pvtModelTemplateRows(catalog,composition),before=structuredClone(table)
 assert.deepEqual(table[0],pvtModelColumns.map(column=>column.label))
 assert.equal(table.length,16);assert.equal(table[1][3],98)
 const restored=parsePvtModelRows([table[0],...table.slice(1).reverse()],catalog)
 assert.deepEqual(restored,pvtModelComposition(pvtModelRows(catalog,composition)))
 assert.deepEqual(table,before)
 assert.deepEqual(parsePvtModelRows(parseTemperatureImportCsv(temperatureImportCsv(table)),catalog),restored)
})
test('imports reject altered constants, unsupported fractions, repeated or missing components and old PVT templates',()=>{
 for(const change of [table=>{table[1][4]+=1},table=>{table[1][5]*=1e6},table=>{table[1][3]=101},
   table=>{table[1][1]='C7+'},table=>{table.push([...table[1]])},table=>{table.pop()},table=>{table[0][3]='质量含量（%）'}]) {
  const table=pvtModelTemplateRows(catalog,composition);change(table);assert.throws(()=>parsePvtModelRows(table,catalog))
 }
})
test('real Excel workbooks preserve fifteen fractions and constant precision; formulas remain rejected',async()=>{
 const XLSX=await import('xlsx'),table=pvtModelTemplateRows(catalog,composition),book=XLSX.utils.book_new()
 XLSX.utils.book_append_sheet(book,XLSX.utils.aoa_to_sheet(table),'气体组成')
 for(const bookType of ['xlsx','biff8']) {
  const imported=XLSX.read(XLSX.write(book,{type:'buffer',bookType}),{type:'buffer',cellFormula:true})
  const sheet=imported.Sheets[imported.SheetNames[0]];assertTemperatureImportSheet(sheet)
  assert.deepEqual(parsePvtModelRows(XLSX.utils.sheet_to_json(sheet,{header:1,raw:true,defval:null}),catalog),pvtModelComposition(pvtModelRows(catalog,composition)))
  sheet.D2={t:'n',f:'100-2',v:98};assert.throws(()=>assertTemperatureImportSheet(sheet),/公式/)
 }
})
test('PVT authority follows identity, model revision, method and source fingerprint, never the old checkpoint revision',()=>{
 const model={pvtId:1,revision:3,method:'PR',compositionRevision:'pipeline-v1-new'}
 assert.equal(pipelinePvtChanged(model,{...model}),false)
 for(const changed of [{pvtId:2},{revision:2},{method:'SRK'},{compositionRevision:'legacy-data-pvt'}, {issue:'缺组成'}]) {
  assert.equal(pipelinePvtChanged({...model,...changed},model),true)
  if(!changed.issue)assert.notEqual(pipelinePvtSourceStamp(model),pipelinePvtSourceStamp({...model,...changed}))
 }
 assert.equal(pipelinePvtChanged(null,model),true);assert.equal(pipelinePvtChanged(model,null),true)
})
test('topology normalization removes legacy per-edge PVT IDs without editing source geometry or other fields',()=>{
 const graph={nodes:[{id:'well'}],edges:[{id:'a',parameters:{pvtId:1,diameterMm:100,lengthM:120}}]}
 const before=structuredClone(graph),clean=normalizeTopologyPvtReferences(graph)
 assert.equal(Object.hasOwn(clean.edges[0].parameters,'pvtId'),false)
 assert.equal(clean.edges[0].parameters.diameterMm,100);assert.deepEqual(graph,before)
})
