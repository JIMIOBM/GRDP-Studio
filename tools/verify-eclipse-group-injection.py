"""5173 real E100: source-bound GCONINJE, preserved diagnostics, exact downloaded ZIP replay."""
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
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence',required=True)
    parser.add_argument('--resume',action='store_true',help='Recheck saved API runs and continue an interrupted ZIP replay.')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    if args.resume:
        assert out.is_dir() and (out/'request.json').is_file()
        prior = out/'acceptance-summary.json'
        if prior.is_file():
            archived = out/'acceptance-summary-interrupted.json'
            assert not archived.exists()
            archived.write_bytes(prior.read_bytes())
    else: out.mkdir(parents=True,exist_ok=False)
    source = repo / 'test-data/ECLIPSE_GROUP_INJECTION.DATA'
    original = source.read_bytes()
    api = 'http://127.0.0.1:5173/api/software-integration'
    report = {'apiVerified':False,'manualUiVerified':False,'sourceSha256':hashlib.sha256(original).hexdigest()}
    def save(name,value): (out/name).write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8')
    def request(path,body=None,content_type='application/json',expected=200):
        payload = body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(api+path,data=payload,headers={'Content-Type':content_type} if payload is not None else {})
        try: response = urllib.request.urlopen(req,timeout=30)
        except urllib.error.HTTPError as error: response = error
        with response:
            raw = response.read(); assert response.status == expected,(path,response.status,raw[:400])
            value = json.loads(raw); assert value['code'] == expected,value
            return value.get('data')
    def upload(name,payload):
        if args.resume:
            for file in out.glob('version-*.json'):
                saved = json.loads(file.read_text(encoding='utf-8'))
                if saved['originalName']==name: return saved
        project = request('/projects',{'name':'ECLIPSE-P03-GCONINJE-'+out.name+'-'+name,'description':'Real water injection group allocation and complete package replay.'})
        boundary = 'grdp-'+uuid.uuid4().hex
        multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()+payload+(f'\r\n--{boundary}--\r\n').encode()
        request(f"/projects/{project['id']}/models",multipart,'multipart/form-data; boundary='+boundary)
        for _ in range(90):
            detail = request(f"/projects/{project['id']}")
            versions = [v for m in detail['models'] for v in m['versions']]
            if versions and versions[0]['status']=='READY':
                save(f"version-{versions[0]['id']}.json",versions[0]);return versions[0]
            assert not versions or versions[0]['status'] not in ('INVALID','FAILED'),versions
            time.sleep(1)
        raise AssertionError('inspection timeout')
    def run(version,parameters):
        created = None
        if args.resume:
            for file in out.glob('run-*.json'):
                saved = json.loads(file.read_text(encoding='utf-8'))
                if saved['modelVersionId']==version['id'] and saved.get('parameters')==parameters:
                    created = request(f"/runs/{saved['id']}");break
        if created is None: created = request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':parameters},expected=201)
        for _ in range(300):
            item = request(f"/runs/{created['id']}")
            if item['status'] in ('SUCCEEDED','FAILED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT'):
                save(f"run-{item['id']}.json",item)
                assert item['status']=='SUCCEEDED' and item['resultContract']=='VALID_FULL',item.get('error')
                assert all(item['result']['eclEnd'][k]==0 for k in ('problems','errors','bugs'))
                assert all(item['cleanup'][k] for k in ('processTreeExitConfirmed','inputDeleted','workDirectoryDeleted'))
                return item
            time.sleep(1)
        raise AssertionError('calculation timeout')
    def series(item):
        s = item['result']['summary']['series']
        keys = [(r['keyword'],r.get('objectName'),r['unit']) for r in s]
        assert s and len(keys)==len(set(keys))
        for r in s:
            assert len(r['points'])==len({p['timeDays'] for p in r['points']})
        return s
    def curve(item,keyword,name):
        row, = [s for s in series(item) if s['keyword']==keyword and s.get('objectName')==name]
        assert row['unit'].strip().upper()==('PSIA' if keyword=='WBHP' else 'STB/DAY')
        return {p['timeDays']:p['value'] for p in row['points']}
    try:
        version = upload(source.name,original)
        baseline = run(version,None)
        record, = version['inspection']['injectionGroupControls']
        assert version['inspection']['schemaVersion']=='eclipse-data-inspection/7'
        members=[m['well'] for m in record['members']]
        assert len(members)==2 and all(m['bhpLimit']==6000 for m in record['members'])
        params = {'schemaVersion':'eclipse-group-injection-parameters/1','baselineRunId':baseline['id'],'group':'WI','phase':'INITIAL','sourceFile':source.name,
            'lineNumber':record['lineNumber'],'controlMode':'RATE','injectionPhase':'WATER','unit':'STB/day','originalTargetRate':2,'targetRate':1,'members':members}
        save('request.json',params)
        before = request(f"/model-versions/{version['id']}/runs")
        faults=[{'lineNumber':record['lineNumber']+1},{'originalTargetRate':50000},{'targetRate':0},{'controlMode':'WRAT'},
            {'members':members[:-1]},{'members':members[:1]+['INJECTR1']},{'unit':'sm3/day'},{'sourceFile':'OTHER.DATA'},{'group':'I'},{'phase':'DATES'},{'injectionPhase':'GAS'},{'injectionPhase':'OIL'}]
        for fault in faults:
            request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,**fault}},expected=400)
        assert before==request(f"/model-versions/{version['id']}/runs")
        tree = upload('GROUP_TREE.DATA', original.replace(b'GCONINJE',b'GRUPTREE\n G FIELD /\n/\nGCONINJE',1))
        assert tree['inspection']['schemaVersion']!='eclipse-data-inspection/7'
        treeRuns = request(f"/model-versions/{tree['id']}/runs")
        request(f"/model-versions/{tree['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'sourceFile':'GROUP_TREE.DATA'}},expected=400)
        assert treeRuns==request(f"/model-versions/{tree['id']}/runs")
        derived = run(version,params)
        limited = run(version,{**params,'targetRate':10})
        g=curve(derived,'GWIR','WI');old=curve(baseline,'GWIR','WI');limit=curve(limited,'GWIR','WI')
        t=min(time for time in g if time>0 and time in old and time in limit)
        allocation=[]
        for well in members:
            current=curve(derived,'WWIR',well)[t];originalRate=curve(baseline,'WWIR',well)[t];pressure=curve(derived,'WBHP',well)[t]
            assert abs(current-0.5)<0.0001 and abs(current-originalRate)>0.1 and pressure<6000
            assert abs(curve(limited,'WBHP',well)[t]-6000)<0.1
            allocation.append({'well':well,'baselineRate':originalRate,'rate':current,'bhp':pressure,'bhpLimit':6000})
        assert abs(g[t]-1)<0.0001 and abs(g[t]-sum(m['rate'] for m in allocation))<0.0001
        assert abs(old[t]-2)<0.0001 and limit[t]<10
        artifact, = [a for a in derived['artifacts'] if a['name']=='eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(f"{api}/runs/{derived['id']}/artifacts/{artifact['id']}/download",timeout=30) as response: package = response.read()
        assert len(package)==artifact['sizeBytes'] and hashlib.sha256(package).hexdigest()==artifact['sha256'].lower()
        (out/'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert z.namelist()==[source.name];changed=z.read(source.name)
        assert changed == original.replace(b"'WI' 'WATER' 'RATE' 2 /",b"'WI' 'WATER' 'RATE' 1 /",1), 'unrelated input edit'
        replay = run(upload('applied.zip',package),None)
        assert series(replay)==series(derived),'ordered replay differs'
        assert source.read_bytes()==original
        report.update(apiVerified=True,runIds=[baseline['id'],derived['id'],replay['id']],limitedRunId=limited['id'],timeDays=t,
            baselineGroupRate=old[t],targetGroupRate=1,actualGroupRate=g[t],limitedTarget=10,limitedActualRate=limit[t],members=allocation,
            rejectedRequests=len(faults)+1,unsupportedTreeRejected=True,rejectedRequestCreatedRun=False,sourceUnchanged=True,downloadSha256=artifact['sha256'],
            diagnostics={str(r['id']):r['result']['eclEnd'] for r in [baseline,derived,limited,replay]},
            reimportNumericPaths=sum(2*len(s['points']) for s in series(derived)),reimportMaxDifference=0)
        print(json.dumps(report))
    except Exception as error:
        report['error']=str(error);raise
    finally: save('acceptance-summary.json',report)

if __name__=='__main__': main()
