import test from 'node:test'
import assert from 'node:assert/strict'
import { tstepRecords, buildTstep, validTstep, tstepResponse } from '../src/views/SoftwareIntegration/eclipseTstep.js'
const inspection = () => ({ schemaVersion:'eclipse-data-inspection/4',caseName:'CASE.DATA',unitSystem:'FIELD',packageFiles:[{relativePath:'CASE.DATA'}],scheduleTimeline:[{kind:'TSTEP',sourceFile:'CASE.DATA',lineNumber:7,steps:Array(10).fill('5')},{kind:'TSTEP',sourceFile:'CASE.DATA',lineNumber:12,steps:Array(10).fill('5')}] })
test('source-bound TSTEP edits retain total duration, require positive integer days and reject unchanged steps',()=>{
 const i=inspection(),input={inspection:i,baselineRunId:1,lineNumber:7,stepsText:'1 4 5 10 30'}
 const p=buildTstep(input);assert.ok(validTstep(p));assert.equal(tstepRecords(i).length,2)
 for(const text of ['1 2','0 50','-1 51','1.5 48.5','10*5','5 5 5 5 5 5 5 5 5 5'])assert.equal(buildTstep({...input,stepsText:text}),null)
 i.unitSystem='LAB';assert.deepEqual(tstepRecords(i),[])
})
test('requested reports must exist in real solver outputs; hash changes are insufficient',()=>{
 const p=buildTstep({inspection:inspection(),baselineRunId:1,lineNumber:7,stepsText:'1 4 5 10 30'})
 const s=times=>({series:[{keyword:'WOPR',objectName:'P1',unit:'STB/DAY',points:times.map(timeDays=>({timeDays,value:100}))}]})
 assert.equal(tstepResponse(s([0,1,5,10,20,50,100]),p,inspection()).verified,true)
 assert.equal(tstepResponse(s([0,5,50,100]),p,inspection()).verified,false)
})
