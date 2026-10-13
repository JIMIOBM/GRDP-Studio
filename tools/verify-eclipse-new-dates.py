"""5173 real E100: source-bound new dates, all-zero ECLEND, exact downloaded ZIP replay."""
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
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = Path(args.evidence).resolve()
    assert not out.is_relative_to(repo) and not out.is_relative_to(Path('C:/GRDP-Data'))
    out.mkdir(parents=True,exist_ok=False)
    source = repo / 'test-data/ECLIPSE_NEW_DATES.DATA'
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
        project = request('/projects',{'name':'ECLIPSE-P03-NewDates-'+out.name+'-'+name,'description':'Real outage dates and complete package replay.'})
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
        created = request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':parameters},expected=201)
        for _ in range(300):
            item = request(f"/runs/{created['id']}")
            if item['status'] in ('SUCCEEDED','FAILED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT'):
                save(f"run-{item['id']}.json",item)
                assert item['status']=='SUCCEEDED' and item['resultContract']=='VALID_FULL',item.get('error')
                assert all(item['result']['eclEnd'][k]==0 for k in ('warnings','problems','errors','bugs'))
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
    def rates(item):
        row, = [s for s in series(item) if s['keyword']=='WOPR' and s.get('objectName')=='P1']
        assert row['unit'].strip().upper()=='STB/DAY'
        return row['points']
    try:
        version = upload(source.name,original)
        baseline = run(version,None)
        first,second = version['inspection']['scheduleTimeline'][:2]
        params = {'schemaVersion':'eclipse-new-dates-parameters/1','baselineRunId':baseline['id'],'well':'P1','sourceFile':source.name,
            'beforeLineNumber':first['lineNumber'],'beforeDate':'1996-01-06','afterLineNumber':second['lineNumber'],'afterDate':'1996-01-26',
            'shutDate':'1996-01-11','openDate':'1996-01-21'}
        save('request.json',params)
        before = request(f"/model-versions/{version['id']}/runs")
        for fault in [{'beforeLineNumber':first['lineNumber']+1},{'beforeDate':'1996-01-05'},{'afterLineNumber':second['lineNumber']+1},
                      {'shutDate':'1996-02-30'},{'openDate':'1996-01-11'},{'openDate':'1996-01-26'},{'well':'UNKNOWN'},{'sourceFile':'OTHER.DATA'}]:
            request(f"/model-versions/{version['id']}/runs",{'runType':'eclipse','study':None,'parameters':{**params,**fault}},expected=400)
        assert before==request(f"/model-versions/{version['id']}/runs")
        derived = run(version,params)
        old,actual = rates(baseline),rates(derived)
        assert any(p['timeDays']==5 and p['value']>0 for p in old)
        shut = [p for p in actual if 10<p['timeDays']<=20]
        reopened = [p for p in actual if 20<p['timeDays']<=25]
        assert shut and all(p['value']==0 for p in shut),shut
        assert reopened and any(p['value']>0 for p in reopened),reopened
        assert actual[-1]['timeDays']==old[-1]['timeDays']==100
        artifact, = [a for a in derived['artifacts'] if a['name']=='eclipse-schedule-applied-package.zip']
        with urllib.request.urlopen(f"{api}/runs/{derived['id']}/artifacts/{artifact['id']}/download",timeout=30) as response: package = response.read()
        assert len(package)==artifact['sizeBytes'] and hashlib.sha256(package).hexdigest()==artifact['sha256'].lower()
        (out/'applied.zip').write_bytes(package)
        with zipfile.ZipFile(io.BytesIO(package)) as z:
            assert z.namelist()==[source.name];changed=z.read(source.name)
        insertion = re.search(rb"-- GRDP-Studio new DATES[^\r\n]*\r?\nDATES\r?\n 11 'JAN' 1996 /\r?\n/\r?\nWELOPEN\r?\n 'P1' 'SHUT' /\r?\n/\r?\nDATES\r?\n 21 'JAN' 1996 /\r?\n/\r?\nWELOPEN\r?\n 'P1' 'OPEN' /\r?\n/\r?\n",changed)
        assert insertion and changed[:insertion.start()]+changed[insertion.end():]==original,'unrelated input edit'
        assert changed[insertion.end():].startswith(b'DATES\n 26'), 'wrong insertion position'
        replay = run(upload('applied.zip',package),None)
        assert series(replay)==series(derived),'ordered replay differs'
        assert source.read_bytes()==original
        report.update(apiVerified=True,runIds=[baseline['id'],derived['id'],replay['id']],outagePoints=shut,reopenedPoints=reopened,
            rejectedRequests=8,rejectedRequestCreatedRun=False,sourceUnchanged=True,downloadSha256=artifact['sha256'],
            reimportNumericPaths=sum(2*len(s['points']) for s in series(derived)),reimportMaxDifference=0)
        print(json.dumps(report))
    except Exception as error:
        report['error']=str(error);raise
    finally: save('acceptance-summary.json',report)

if __name__=='__main__': main()
