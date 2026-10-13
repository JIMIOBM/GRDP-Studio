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
    parser.add_argument('--mode',choices=['TREE','GUIDE','EFFICIENCY'],required=True)
    parser.add_argument('--resume',action='store_true')
    args=parser.parse_args();repo=Path(__file__).resolve().parent.parent;out=Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    if args.resume:assert out.is_dir()
    else:out.mkdir(parents=True,exist_ok=False)
    source=repo/f'test-data/ECLIPSE_GROUP_{args.mode}.DATA';original=source.read_bytes()
    provenance=json.loads(source.with_suffix('.provenance.json').read_text())
    native=json.loads(Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-STRUCTURE-native-proof-a4/native-acceptance.json').read_text())[args.mode.lower()]
    assert hashlib.sha256(original).hexdigest()==native['baseline']['inputSha256']
    api='http://127.0.0.1:5173/api/software-integration'
    report={'apiVerified':False,'manualUiVerified':False,'mode':args.mode,'sourceSha256':hashlib.sha256(original).hexdigest()}
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
        project=request('/projects',{'name':out.name+'-'+name,'description':'Group structure source-bound verification and complete package replay.'})
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
        inspection=version['inspection'];assert inspection['schemaVersion']=='eclipse-data-inspection/12'
        keyword='GCONPROD' if args.mode=='TREE' else 'WGRUPCON' if args.mode=='GUIDE' else 'GEFAC'
        record,=[c for c in inspection['groupStructureControls'] if c['keyword']==keyword and c['objectName']==provenance['editedObject']]
        assert record['parentGroup']=='P' and record['parentTarget']==2000
        assert [l['group'] for l in record['leaves']]==['GA','GB']
        members=[m['well'] for l in record['leaves'] for m in l['members']];assert members==provenance['members']
        assert all(m['bhpLimit']==2500 and m['oilRateLimit']==20000 for l in record['leaves'] for m in l['members'])
        params={'schemaVersion':'eclipse-group-structure-parameters/1','baselineRunId':baseline['id'],'phase':'INITIAL','parentGroup':record['parentGroup'],
          'objectName':record['objectName'],'keyword':keyword,'sourceFile':source.name,'lineNumber':record['lineNumber'],'unit':record['unit'],
          'originalValue':record['originalValue'],'targetValue':provenance['positiveTarget'],'members':members}
        save('request.json',params);before=request(f"/model-versions/{version['id']}/runs")
        faults=[{'parentGroup':'OTHER'},{'objectName':'OTHER'},{'keyword':'GUIDERAT'},{'sourceFile':'OTHER.DATA'},{'lineNumber':record['lineNumber']+1},{'phase':'DATES'},
          {'unit':'RB/day'},{'originalValue':record['originalValue']+1},{'targetValue':0},{'targetValue':record['originalValue']},{'extra':0},
          {'members':members[:-1]},{'members':list(reversed(members))},{'schemaVersion':'eclipse-advanced-injection-parameters/1'}]
        if keyword=='GEFAC':faults.append({'targetValue':1.1})
        for fault in faults:request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,**fault}},expected=400)
        request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'baselineRunId':331}},expected=409)
        assert before==request(f"/model-versions/{version['id']}/runs")
        bad_name='UNSUPPORTED_TREE.DATA';unsupported=original.replace(b"'GB' 'P' /",b"'GB' 'GA' /",1);assert unsupported!=original
        bad=upload(bad_name,unsupported);assert bad['inspection']['schemaVersion']!='eclipse-data-inspection/12'
        prior=request(f"/model-versions/{bad['id']}/runs")
        request(f"/model-versions/{bad['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'sourceFile':bad_name}},expected=400)
        assert prior==request(f"/model-versions/{bad['id']}/runs")
        derived=run(version,params)
        parent,=[c for c in inspection['groupStructureControls'] if c['keyword']=='GCONPROD']
        limited_params={**params,'objectName':parent['objectName'],'keyword':'GCONPROD','lineNumber':parent['lineNumber'],'unit':parent['unit'],
          'originalValue':parent['originalValue'],'targetValue':native['limited']['target']}
        save('limited-request.json',limited_params)
        limited=run(version,limited_params,partial=args.mode=='EFFICIENCY');native_paths=0
        for label,item in [('baseline',baseline),('target',derived),('limited',limited)]:
            for expected in native[label]['curves']:
                actual,=[s for s in series(item) if s['keyword']==expected['keyword'] and s.get('objectName')==expected['objectName']]
                assert (actual.get('unit') or '').strip().upper()==expected['unit'].strip().upper()
                assert [p for p in actual['points'] if p['timeDays']>0]==expected['points'],(label,expected['keyword'],expected['objectName'])
                native_paths+=2*len(expected['points'])
        artifact,=[a for a in derived['artifacts'] if a['name']=='eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(f"{api}/runs/{derived['id']}/artifacts/{artifact['id']}/download",timeout=30) as response:package=response.read()
        assert len(package)==artifact['sizeBytes'] and hashlib.sha256(package).hexdigest()==artifact['sha256'].lower();(out/'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:assert z.namelist()==[source.name];changed=z.read(source.name)
        lines=original.splitlines(keepends=True);line=lines[record['lineNumber']-1];old=format(record['originalValue'],'.15g').encode();new=format(params['targetValue'],'.17g').encode()
        edited,count=re.subn(rb'(?<![\w.])'+re.escape(old)+rb'(?![\w.])',lambda _:new,line);assert count==1
        lines[record['lineNumber']-1]=edited;assert changed==b''.join(lines),'unrelated input edit'
        replay=run(upload('applied.zip',package),None);assert series(replay)==series(derived),'ordered replay differs';assert source.read_bytes()==original
        report.update(apiVerified=True,runIds=[baseline['id'],derived['id'],replay['id']],limitedRunId=limited['id'],limitedTarget=native['limited']['target'],limitedParameters=limited_params,parameters=params,
          limitedResultContract=limited['resultContract'],rejectedRequests=len(faults)+2,rejectedRequestCreatedRun=False,unsupportedTreeRejected=True,sourceUnchanged=True,downloadSha256=artifact['sha256'],
          nativeNumericPaths=native_paths,nativeMaxDifference=0,reimportNumericPaths=sum(2*len(s['points']) for s in series(derived)),reimportMaxDifference=0,
          diagnostics={str(r['id']):r['result']['eclEnd'] for r in [baseline,derived,limited,replay]})
        print(json.dumps(report))
    except Exception as error:report['error']=str(error);raise
    finally:save('acceptance-summary.json',report)
if __name__=='__main__':main()
