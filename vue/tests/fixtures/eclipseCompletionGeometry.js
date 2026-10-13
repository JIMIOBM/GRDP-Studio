import { readFileSync } from 'node:fs'
const root=new URL('./eclipseCompletionGeometry/',import.meta.url)
const load=name=>JSON.parse(readFileSync(new URL(name+'.json',root),'utf8'))
const native=load('native-acceptance'); if(!native.nativeVerified||!native.physicalEquivalenceVerified)throw new Error('Native completion equivalence not verified')
export function completionGeometryBrowserFixture(label='diameter') {
  const inspection=load('inspection'),result=load('baseline-result')
  const baseline={id:8966,projectId:1,modelId:22,modelVersionId:201,versionNo:1,modelName:'ECLIPSE演示模型',study:null,runType:'eclipse',status:'SUCCEEDED',resultContract:'VALID_FULL',parameters:null,events:[],artifacts:[],result}
  const row=inspection.scheduleMetadata.records.find(r=>r.values[0]==='P       ')
  const [field,unit,originalValue,targetValue]={diameter:['DIAMETER','m',1,.2],kh:['KH','mD.m',1000,500],skin:['SKIN','1',0,2]}[label]
  const parameters={schemaVersion:'eclipse-completion-geometry-parameters/1',baselineRunId:baseline.id,well:row.values[0],sourceFile:row.sourceFile,lineNumber:row.lineNumber,
    i:row.values[1],j:row.values[2],k1:row.values[3],k2:row.values[4],field,unit,originalValue,targetValue,recordValues:[...row.values]}
  const target={...structuredClone(baseline),id:8967,parameters:structuredClone(parameters)}
  target.result.summary.series=native[label].series;target.result.eclEnd=native[label].eclEnd
  return{inspection,baseline,target,parameters}
}
