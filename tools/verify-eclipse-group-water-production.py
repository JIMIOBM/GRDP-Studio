"""5173 real E100: source-bound GCONPROD, preserved diagnostics, exact downloaded ZIP replay."""
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
    parser.add_argument('--mode',choices=['WRAT'],required=True)
    parser.add_argument('--resume',action='store_true',help='Recheck saved API runs and continue an interrupted ZIP replay.')
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    if args.resume:
        assert out.is_dir() and (out/'request.json').is_file()
        prior = out/'acceptance-summary.json'
        if prior.is_file():
            archived = out/'acceptance-summary-at-zip-rejection.json'
            assert not archived.exists()
            archived.write_bytes(prior.read_bytes())
    else: out.mkdir(parents=True,exist_ok=False)
    mode = args.mode
    unit = 'STB/day'
    group_key, well_key = 'GWPR','WWPR'
    base_target, positive_target, limited_target = 2000,1000,20000
    defaults = '1*'
    source = repo / f'test-data/ECLIPSE_GROUP_{mode}.DATA'
    original = source.read_bytes()
    api = 'http://127.0.0.1:5173/api/software-integration'
    report = {'apiVerified':False,'manualUiVerified':False,'mode':mode,'sourceSha256':hashlib.sha256(original).hexdigest()}
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
        project = request('/projects',{'name':'ECLIPSE-P03-GROUP-RATE-'+out.name+'-'+name,'description':'Real production group allocation and complete package replay.'})
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
        if created is None:
            created = request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':parameters},expected=201)
            save(f"run-{created['id']}.json",created)
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
        assert row['unit'].strip().upper()==('PSIA' if keyword=='WBHP' else unit.upper())
        return {p['timeDays']:p['value'] for p in row['points']}
    try:
        version = upload(source.name,original)
        baseline = run(version,None)
        record, = version['inspection']['waterProductionGroupControls']
        assert version['inspection']['schemaVersion']=='eclipse-data-inspection/10'
        members=[m['well'] for m in record['members']]
        assert members==['P20','P24'] and all(m['bhpLimit']==1000 for m in record['members']) and [m['oilRateLimit'] for m in record['members']]==[8000,4000]
        params = {'schemaVersion':'eclipse-group-water-production-parameters/1','baselineRunId':baseline['id'],'group':'G2','phase':'INITIAL','sourceFile':source.name,
            'lineNumber':record['lineNumber'],'controlMode':mode,'unit':unit,'originalTargetRate':base_target,'targetRate':positive_target,'members':members}
        save('request.json',params)
        before = request(f"/model-versions/{version['id']}/runs")
        faults=[{'lineNumber':record['lineNumber']+1},{'originalTargetRate':50000},{'targetRate':0},{'controlMode':'GRAT'},
            {'members':members[:-1]},{'members':members[:3]+['INJECTR1']},{'unit':'sm3/day'},{'unit':'STB/day' if mode=='GRAT' else 'Mscf/day'},{'controlMode':'LRAT' if mode=='GRAT' else 'GRAT'},{'targetRate':base_target},{'extra':0},{'sourceFile':'OTHER.DATA'},{'group':'I'},{'phase':'DATES'}]
        for fault in faults:
            request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,**fault}},expected=400)
        request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'baselineRunId':320}},expected=409)
        assert before==request(f"/model-versions/{version['id']}/runs")
        tree = upload('GROUP_TREE.DATA', original.replace(b'GCONPROD',b'GRUPTREE\n G2 FIELD /\n/\nGCONPROD',1))
        assert tree['inspection']['schemaVersion']!='eclipse-data-inspection/10'
        treeRuns = request(f"/model-versions/{tree['id']}/runs")
        request(f"/model-versions/{tree['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,'sourceFile':'GROUP_TREE.DATA'}},expected=400)
        assert treeRuns==request(f"/model-versions/{tree['id']}/runs")
        derived = run(version,params)
        limited = run(version,{**params,'targetRate':limited_target})
        g=curve(derived,group_key,'G2');old=curve(baseline,group_key,'G2');limit=curve(limited,group_key,'G2')
        t=max(time for time in g if time>0 and time in old and time in limit)
        allocation=[]
        for well in members:
            current=curve(derived,well_key,well)[t];originalRate=curve(baseline,well_key,well)[t];pressure=curve(derived,'WBHP',well)[t]
            assert current>0 and abs(current-originalRate)>1 and pressure>1000
            oil_limit = next(m['oilRateLimit'] for m in record['members'] if m['well']==well)
            assert abs(curve(limited,'WOPR',well)[t]-oil_limit)<0.1
            assert curve(limited,'WBHP',well)[t]>=1000
            allocation.append({'well':well,'baselineRate':originalRate,'rate':current,'bhp':pressure,'bhpLimit':1000,'limitedOilRate':curve(limited,'WOPR',well)[t],'oilRateLimit':oil_limit})
        assert abs(g[t]-positive_target)<0.1 and abs(g[t]-sum(m['rate'] for m in allocation))<0.1
        assert abs(old[t]-base_target)<0.1 and limit[t]<limited_target
        # Cross-check the platform parser against the independent native binary reader,
        # including every ordered time/value rather than only the first acceptance point.
        native_root = Path('C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-WRAT-probe-a3')
        native = json.loads((native_root/'native-acceptance.json').read_text(encoding='utf-8'))[mode]
        native_paths = 0
        for label, item in [('baseline',baseline),('target',derived),('limited',limited)]:
            for expected in native[label]['curves']:
                actual, = [s for s in series(item) if s['keyword']==expected['keyword'] and s.get('objectName')==expected['objectName']]
                assert actual['unit'].strip().upper()==expected['unit'].strip().upper()
                assert [p for p in actual['points'] if p['timeDays']>0]==expected['points'], (label,expected['keyword'],expected['objectName'])
                native_paths += 2*len(expected['points'])
        artifact, = [a for a in derived['artifacts'] if a['name']=='eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(f"{api}/runs/{derived['id']}/artifacts/{artifact['id']}/download",timeout=30) as response: package = response.read()
        assert len(package)==artifact['sizeBytes'] and hashlib.sha256(package).hexdigest()==artifact['sha256'].lower()
        (out/'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert z.namelist()==[source.name];changed=z.read(source.name)
        assert changed == original.replace(f"'G2' '{mode}' {defaults} {base_target} /".encode(),f"'G2' '{mode}' {defaults} {positive_target} /".encode(),1), 'unrelated input edit'
        replay = run(upload('applied.zip',package),None)
        assert series(replay)==series(derived),'ordered replay differs'
        assert source.read_bytes()==original
        report.update(apiVerified=True,runIds=[baseline['id'],derived['id'],replay['id']],limitedRunId=limited['id'],timeDays=t,
            baselineGroupRate=old[t],targetGroupRate=positive_target,actualGroupRate=g[t],limitedTarget=limited_target,limitedActualRate=limit[t],groupKeyword=group_key,wellKeyword=well_key,unit=unit,members=allocation,
            rejectedRequests=len(faults)+2,unsupportedTreeRejected=True,rejectedRequestCreatedRun=False,sourceUnchanged=True,downloadSha256=artifact['sha256'],
            diagnostics={str(r['id']):r['result']['eclEnd'] for r in [baseline,derived,limited,replay]},
            reimportNumericPaths=sum(2*len(s['points']) for s in series(derived)),reimportMaxDifference=0)
        report.update(nativeNumericPaths=native_paths,nativeMaxDifference=0)
        print(json.dumps(report))
    except Exception as error:
        report['error']=str(error);raise
    finally: save('acceptance-summary.json',report)

if __name__=='__main__': main()
