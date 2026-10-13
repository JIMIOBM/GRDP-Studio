"""5173 regional property edits: every INIT cell, real business vectors, exact input ZIP replay."""
import argparse
import hashlib
import importlib.util
import io
import json
import time
import zipfile
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True); parser.add_argument('--native-directory', required=True); parser.add_argument('--resume', action='store_true')
    args = parser.parse_args(); console = ConsoleAcceptance(args.evidence, args.resume)
    repo = Path(__file__).resolve().parent.parent
    source = (repo / 'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()
    assert hashlib.sha256(source).hexdigest() == '97b5fdebe5df1c4e5c85b9b67d6d8bb747ee48aff0bf1e3668cfdc0e8fa43222'
    native = json.loads((Path(args.native_directory) / 'native-acceptance.json').read_text(encoding='utf-8')); assert native['nativeVerified']
    spec = importlib.util.spec_from_file_location('oracle', repo / 'tools/inspect-eclipse-compositional-native.py'); oracle = importlib.util.module_from_spec(spec); spec.loader.exec_module(oracle)
    base = 'ECLIPSE_PERMX_WATER'; proof = dict(apiVerified=False, manualUiVerified=False)
    def arrays(run):
        content, artifact = console.download(run, 'eclipse-output-' + base + '.INIT')
        out = console.out / ('native-run-' + str(run['id'])); out.mkdir(exist_ok=True); path = out / (base + '.INIT')
        if path.exists(): assert path.read_bytes() == content
        else: path.write_bytes(content)
        records = oracle.records(path)
        values = {}
        for name in ['PERMX','PERMY','PORO','DX','DY','DZ','DEPTH']:
            values[name], = [v for key,kind,v in records if key == name and kind in ['REAL','DOUB']]
            assert len(values[name]) == 225
        return values, artifact
    def failed_stale(version, parameters):
        saved = console.out / 'run-stale-region.json'
        request = {**parameters, 'originalValues': [100,100,100,100,100,101]}
        run = json.loads(saved.read_text(encoding='utf-8')) if args.resume and saved.exists() else console.request(
            f"/model-versions/{version['id']}/runs", dict(runType='eclipse', study=None, parameters=request), expected=201)
        console.save('run-stale-region', run)
        for _ in range(120):
            current = console.request('/runs/' + str(run['id']))
            if current['status'] in ['FAILED','SUCCEEDED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT','WORKER_LOST']:
                console.save('run-stale-region', current)
                assert current['status'] == 'FAILED' and current['error']['code'] == 'ECLIPSE_GRID_REGION_STALE_VALUE'
                assert current['result'] is None and not any(a['name'].endswith('-applied-package.zip') for a in current['artifacts'])
                assert not any(e['status'] == 'RUNNING_ECLIPSE' for e in current['events'])
                assert all(current['cleanup'][k] for k in ['inputDeleted','workDirectoryDeleted','processTreeExitConfirmed'])
                return current['id']
            time.sleep(1)
        raise AssertionError('Stale selected last-cell rejection timeout')
    try:
        version = console.upload('original', source, base + '.DATA')
        baseline = console.run(version, 'baseline')
        before, init = arrays(baseline)
        assert before == native['baseline']['arrays'] and baseline['result']['summary']['series'] == native['baseline']['series']
        geometry, grid = console.download(baseline, 'eclipse-output-' + base + '.EGRID')
        common = dict(schemaVersion='eclipse-grid-region-parameters/1', baselineRunId=baseline['id'], fieldFile=base+'.INIT', fieldArtifactSha256=init['sha256'],
                      gridFile=base+'.EGRID', gridArtifactSha256=grid['sha256'], nx=15,ny=15,nz=1,i1=1,i2=3,j1=1,j2=2,k1=1,k2=1)
        permx = dict(**common, keyword='PERMX',originalValues=[100]*6,multiplier=0.5)
        invalid = [{**permx,key:value} for key,value in [('i2',16),('i1',4),('i1',1.5),('k2',2),('multiplier','0.5'),('multiplier',1),('originalValues',[100]*5),('originalValues',[100]*5+['100']),('keyword','PRESSURE')]]
        invalid.extend([{**permx,'unknown':1}, {**permx,'keyword':'PORO','originalValues':[1]*6,'multiplier':2}])
        console.reject_without_run(version, invalid)
        mismatches = [{**permx,key:value} for key,value in [('gridArtifactSha256','0'*64),('fieldArtifactSha256','0'*64),('nx',14),('baselineRunId',1)]]
        console.reject_without_run(version, mismatches, expected=409)
        rejected = failed_stale(version, permx)
        results = {}
        for label,keyword,multiplier,old in [('permx','PERMX',0.5,100),('poro','PORO',0.8,1)]:
            parameters = dict(**common,keyword=keyword,originalValues=[old]*6,multiplier=multiplier)
            target = console.run(version,label,parameters); actual,target_init = arrays(target)
            assert actual == native[label]['arrays'] and target['result']['summary']['series'] == native[label]['series']
            actual_grid,_ = console.download(target,'eclipse-output-'+base+'.EGRID'); assert actual_grid == geometry
            portable,descriptor = console.download(target,'eclipse-grid-field-applied-package.zip')
            (console.out / (label+'-input-package.zip')).write_bytes(portable)
            newline = b'\r\n' if b'\r\n' in source else b'\n'
            scalar = '0.5' if keyword=='PERMX' else '0.80000000000000004'
            block = newline.join([b'-- GRDP controlled grid-region multiplier; generated in the isolated run copy.',b'MULTIPLY',f' {keyword} {scalar} 1 3 1 2 1 1 /'.encode(),b'/',b''])
            with zipfile.ZipFile(io.BytesIO(portable)) as z:
                assert z.namelist() == [base+'.DATA'] and z.read(base+'.DATA') == source.replace(b'PROPS     =',block+b'PROPS     =',1)
            replay_version = console.upload(label+'-portable',portable,label+'-input-package.zip',base+'.DATA')
            replay = console.run(replay_version,label+'-portable'); replay_arrays,_ = arrays(replay)
            assert replay_arrays == actual and replay['result']['summary']['series'] == target['result']['summary']['series']
            def last(run,key):
                series, = [s for s in run['result']['summary']['series'] if s['keyword']==key and s['objectName']=='P']
                return series['points'][-1]['value']
            assert last(target,'WWPR') == 1000 and last(target,'WWPT') == 300000 and abs(last(target,'WBHP')-last(baseline,'WBHP'))>0.1
            results[label] = dict(runId=target['id'],portableRunId=replay['id'],portableVersionId=replay_version['id'],portableArtifact=descriptor,targetInit=target_init,
                                 changedCells=6,unchangedOutsideCells=219,fullArraysVerified=True,fullSummaryVerified=True,geometryUnchanged=True,lastProducerBhp=last(target,'WBHP'))
        assert (repo/'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()==source
        proof.update(apiVerified=True,baselineRunId=baseline['id'],originalVersionId=version['id'],rejectedRequests=len(invalid)+len(mismatches),
                     rejectedRequestCreatedRun=False,staleRegionFailedRunId=rejected,sourceUnchanged=True,cellCount=225,selectedIndices=native['selectedIndices'],
                     baselineLastProducerBhp=last(baseline,'WBHP'),bhpUnit='BARSA',waterRateUnit='SM3/DAY',cumulativeUnit='SM3',scenarios=results)
        print(json.dumps(proof))
    except Exception as error: proof['error']=str(error); raise
    finally: console.save('acceptance-summary',proof)


if __name__=='__main__':main()
