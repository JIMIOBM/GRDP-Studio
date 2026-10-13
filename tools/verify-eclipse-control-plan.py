"""Real 5173 E100 verification: native binary equality, rejected source edits and actual ZIP replay."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request
import uuid
import zipfile

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',required=True)
    parser.add_argument('--resume',action='store_true')
    args=parser.parse_args();repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    if args.resume:assert out.is_dir()
    else:out.mkdir(parents=True,exist_ok=False)
    source=repo/'test-data/ECLIPSE_CONTROL_PLAN.DATA';original=source.read_bytes()
    native=json.loads(Path('C:/GRDP-TestOutput/ECLIPSE-P03-CONTROL-PLAN-probe-a2/native-acceptance.json').read_text(encoding='utf-8'))
    assert hashlib.sha256(original).hexdigest()==native['baseline']['inputSha256']
    api='http://127.0.0.1:5173/api/software-integration'
    report={'apiVerified':False,'manualUiVerified':False,'sourceSha256':hashlib.sha256(original).hexdigest()}
    def save(name,value):(out/name).write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8')
    def request(path,body=None,content_type='application/json',expected=200):
        payload=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
        req=urllib.request.Request(api+path,data=payload,headers={'Content-Type':content_type} if payload is not None else {})
        try:response=urllib.request.urlopen(req,timeout=30)
        except urllib.error.HTTPError as error:response=error
        with response:
            raw=response.read();assert response.status==expected,(path,response.status,raw[:600])
            value=json.loads(raw);assert value['code']==expected,value
            return value.get('data')
    def upload(name,payload):
        if args.resume:
            for file in out.glob('version-*.json'):
                saved=json.loads(file.read_text(encoding='utf-8'))
                if saved['originalName']==name:return saved
        project=request('/projects',{'name':out.name+'-'+name,'description':'Phased target source-bound verification and complete package replay.'})
        save('project-'+name+'.json',project)
        boundary='grdp-'+uuid.uuid4().hex
        multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+payload+(f'\r\n--{boundary}--\r\n').encode()
        request(f"/projects/{project['id']}/models",multipart,'multipart/form-data; boundary='+boundary)
        for _ in range(90):
            detail=request(f"/projects/{project['id']}");versions=[v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status']=='READY':save(f"version-{versions[0]['id']}.json",versions[0]);return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID','FAILED'),versions
            time.sleep(1)
        raise AssertionError('inspection timeout')
    def run(version,parameters,partial=False):
        created=None
        if args.resume:
            for file in out.glob('run-*.json'):
                saved=json.loads(file.read_text(encoding='utf-8'))
                if saved['modelVersionId']==version['id'] and saved.get('parameters')==parameters:created=request(f"/runs/{saved['id']}");break
        if created is None:
            created=request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':parameters},expected=201);save(f"run-{created['id']}.json",created)
        for _ in range(300):
            item=request(f"/runs/{created['id']}")
            if item['status'] in ('SUCCEEDED','FAILED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT'):
                save(f"run-{item['id']}.json",item)
                assert item['status']==('PARTIAL_SUCCEEDED' if partial else 'SUCCEEDED') and item['resultContract']==('VALID_PARTIAL' if partial else 'VALID_FULL'),item.get('error')
                assert item['result']['eclEnd']['problems']==(1 if partial else 0) and all(item['result']['eclEnd'][k]==0 for k in ('errors','bugs'))
                assert all(item['cleanup'][k] for k in ('processTreeExitConfirmed','inputDeleted','workDirectoryDeleted'))
                return item
            time.sleep(1)
        raise AssertionError('calculation timeout')
    def series(item):
        rows=item['result']['summary']['series'];keys=[(r['keyword'],r.get('objectName'),r['unit']) for r in rows]
        assert rows and len(keys)==len(set(keys))
        for r in rows:assert len(r['points'])==len({p['timeDays'] for p in r['points']})
        return rows

    try:
        version=upload(source.name,original);baseline=run(version,None)
        inspection=version['inspection'];assert inspection['schemaVersion']=='eclipse-data-inspection/13'
        records=inspection['productionControlPlan']['records'];assert len(records)==10
        assert inspection['productionControlPlan']['startDate']=='1982-10-19' and inspection['productionControlPlan']['endTimeDays']==3650
        fields=['well','sourceFile','lineNumber','date','timeDays','unit','originalOilRate']
        def edit(record,target):return {**{k:record[k] for k in fields},'targetOilRate':target}
        edits=[edit(r,{365:250,730:350,1095:450}[r['timeDays']]) for r in records if r['timeDays']>0]
        params={'schemaVersion':'eclipse-control-plan-parameters/1','baselineRunId':baseline['id'],'edits':edits}
        save('request.json',params);before=request(f"/model-versions/{version['id']}/runs")
        faults=[{'well':'OTHER'},{'sourceFile':'OTHER.DATA'},{'lineNumber':edits[0]['lineNumber']+1},{'date':'1983-10-20'},
                {'timeDays':366},{'unit':'RB/day'},{'originalOilRate':501},{'targetOilRate':0},{'targetOilRate':500},{'extra':1}]
        count=0
        for fault in faults:
            bad={**params,'edits':[{**edits[0],**fault},*edits[1:]]}
            request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':bad},expected=400);count+=1
        for bad in [{**params,'edits':[]},{**params,'edits':edits[::-1]},{**params,'edits':[edits[0],edits[0]]},
                    {**params,'extra':1},{**params,'edits':[{k:v for k,v in edits[0].items() if k!='date'}]},
                    {**params,'edits':[edit(records[0],400),{**edits[1],'timeDays':731}]}]:
            request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':bad},expected=400);count+=1
        request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'baselineRunId':331}},expected=409);count+=1
        assert before==request(f"/model-versions/{version['id']}/runs")
        derived=run(version,params)
        limited_params={**params,'edits':[{**e,'targetOilRate':100000} for e in edits]}
        initial_params={**params,'edits':[edit(records[0],400)]}
        save('limited-request.json',limited_params);save('initial-request.json',initial_params)
        limited=run(version,limited_params);initial=run(version,initial_params);native_paths=0
        for label,item in [('baseline',baseline),('target',derived),('limited',limited),('initial',initial)]:
            for expected in native[label]['curves']:
                actual,=[s for s in series(item) if s['keyword']==expected['keyword'] and s.get('objectName')==expected['objectName']]
                assert actual['unit'].strip().upper()==expected['unit'].strip().upper()
                assert [p for p in actual['points'] if p['timeDays']>0]==expected['points'],(label,expected['keyword'],expected['objectName'])
                native_paths+=2*len(expected['points'])
        replays=[];downloads=[];replay_paths=0
        for label,item,p in [('target',derived,params),('initial',initial,initial_params)]:
            artifact,=[a for a in item['artifacts'] if a['name']=='eclipse-schedule-applied-package.zip']
            with urllib.request.urlopen(f"{api}/runs/{item['id']}/artifacts/{artifact['id']}/download",timeout=30) as response:package=response.read()
            assert len(package)==artifact['sizeBytes'] and hashlib.sha256(package).hexdigest()==artifact['sha256'].lower()
            (out/f'{label}-applied.zip').write_bytes(package)
            with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[source.name];changed=z.read(source.name)
            lines=original.splitlines(keepends=True)
            for e in p['edits']:
                index=e['lineNumber']-1;old=format(e['originalOilRate'],'.15g').encode();new=format(e['targetOilRate'],'.17g').encode()
                lines[index],matches=re.subn(rb'(?<![\w.])'+re.escape(old)+rb'(?![\w.])',lambda _:new,lines[index]);assert matches==1
            assert changed==b''.join(lines),'unrelated input edit'
            replay=run(upload(f'{label}-applied.zip',package),None);assert series(replay)==series(item),'ordered replay differs'
            replays.append(replay);downloads.append({'runId':item['id'],'replayRunId':replay['id'],'artifactId':artifact['id'],'sha256':artifact['sha256'],'file':f'{label}-applied.zip'})
            replay_paths+=sum(2*len(c['points']) for c in series(item))
        assert source.read_bytes()==original
        report.update(apiVerified=True,runIds=[baseline['id'],derived['id'],limited['id'],initial['id'],*[r['id'] for r in replays]],
          baselineRunId=baseline['id'],positiveRunId=derived['id'],limitedRunId=limited['id'],initialRunId=initial['id'],parameters=params,
          limitedParameters=limited_params,initialParameters=initial_params,rejectedRequests=count,rejectedRequestCreatedRun=False,sourceUnchanged=True,
          downloads=downloads,nativeNumericPaths=native_paths,nativeMaxDifference=0,reimportNumericPaths=replay_paths,reimportMaxDifference=0,
          diagnostics={str(r['id']):r['result']['eclEnd'] for r in [baseline,derived,limited,initial,*replays]})
        print(json.dumps(report))
    except Exception as error:report['error']=str(error);raise
    finally:save('acceptance-summary.json',report)
if __name__=='__main__':main()
