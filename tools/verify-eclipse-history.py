"""Actual persisted CSV and statistical API validation against independently solved model series."""
import argparse
import copy
import csv
import hashlib
import io
import json
import math
from pathlib import Path
from eclipse_console_acceptance import ConsoleAcceptance


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def oracle(raw_csv, summary):
    curves = {(s['objectName'] or '', s['keyword']): s for s in summary['series'] if s['unit']}
    points = []; groups = {}
    for line, row in enumerate(csv.DictReader(io.StringIO(raw_csv.lstrip('\ufeff'))), 2):
        well, keyword, unit = row['well'], row['keyword'], row['unit']; clock, observed = float(row['timeDays']), float(row['value'])
        curve = curves.get((well, keyword)); actual = None; reason = ''
        if not curve: reason = '模型没有该曲线'
        elif curve['unit'] != unit: reason = '单位不一致'
        else:
            values = {p['timeDays']: p['value'] for p in curve['points']}
            if clock not in values: reason = '没有同一报告时刻'
            else: actual = values[clock]
        point = dict(well=well, keyword=keyword, unit=unit, timeDays=clock, observed=observed, simulated=actual, difference=None if actual is None else actual-observed, sourceLine=line, reason=reason)
        points.append(point); groups.setdefault((well, keyword, unit), []).append(point)
    metrics = []
    for (well, keyword, unit), rows in groups.items():
        matched = [p for p in rows if p['difference'] is not None]
        deltas = [p['difference'] for p in matched]; percentages = [abs(p['difference']/p['observed'])*100 for p in matched if p['observed'] != 0]
        overflow = sum(not math.isfinite(p) for p in percentages)
        metrics.append(dict(well=well, keyword=keyword, unit=unit, observedCount=len(rows), matchedCount=len(matched), missingCount=len(rows)-len(matched), coverage=len(matched)/len(rows),
                            mae=sum(abs(d) for d in deltas)/len(deltas) if deltas else None, rmse=math.sqrt(sum(d*d for d in deltas)/len(deltas)) if deltas else None, bias=sum(deltas)/len(deltas) if deltas else None,
                            mape=sum(percentages)/len(percentages) if percentages and not overflow else None, mapeCount=len(percentages), mapeOverflowCount=overflow))
    return dict(points=points, metrics=metrics)


def same(actual, expected):
    if isinstance(expected, dict):
        assert set(actual) == set(expected)
        for key in expected: same(actual[key], expected[key])
    elif isinstance(expected, list):
        assert len(actual) == len(expected)
        for a, e in zip(actual, expected): same(a, e)
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        assert isinstance(actual, (int, float)) and math.isfinite(actual) and math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12), (actual, expected)
    else: assert actual == expected, (actual, expected)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--batch-evidence',required=True);parser.add_argument('--gas-evidence',required=True);parser.add_argument('--evidence',required=True);args=parser.parse_args()
    console=ConsoleAcceptance(args.evidence);repo=Path(__file__).resolve().parent.parent
    batch=load(Path(args.batch_evidence)/'batch-completed.json');failed_batch=load(Path(args.batch_evidence)/'batch-isolated.json')
    gas=load(Path(args.gas_evidence)/'run-baseline.json');gas_target=load(Path(args.gas_evidence)/'run-target.json')
    raw=(repo/'test-data/ECLIPSE_HISTORY_ALGORITHM_SAMPLE.csv').read_bytes();text=raw.decode('utf-8');baseline=batch['baseline']
    body=dict(name='算法验算样例（非现场实测）',baselineRunId=baseline['id'],sourceFile='ECLIPSE_HISTORY_ALGORITHM_SAMPLE.csv',csv=text)
    path=f'/model-versions/{baseline["modelVersionId"]}/eclipse-history-datasets'
    proof=dict(apiVerified=False,manualUiVerified=False,exampleIsFieldObservation=False)
    try:
        before=console.request(path);run_ids={r['id'] for r in console.request(f'/model-versions/{baseline["modelVersionId"]}/runs?limit=100')}
        rejected=0
        for mutate in [lambda b:b.update(extra=1),lambda b:b.update(sourceFile='../data.csv'),lambda b:b.update(csv=text.replace('STB/DAY','SM3/DAY')),
                       lambda b:b.update(csv=text.replace('P1','UNKNOWN')),lambda b:b.update(csv=text.replace('102.5','5')),lambda b:b.update(csv=text.replace('timeDays','date')),
                       lambda b:b.update(csv=text.replace('800','NaN')),lambda b:b.update(csv=text.replace('102.5','-1')),lambda b:b.update(csv='x'*1048577),lambda b:b.update(baselineRunId=str(baseline['id']))]:
            bad=copy.deepcopy(body);mutate(bad);console.request(path,bad,expected=400);rejected+=1
        for bad in [{**body,'baselineRunId':batch['members'][0]['run']['id']},{**body,'baselineRunId':gas['id']}]:
            console.request(path,bad,expected=409);rejected+=1
        assert console.request(path)==before
        dataset=console.request(path,body,expected=201);console.save('dataset-main',dataset)
        assert dataset['csv'].encode('utf-8')==raw and dataset['metadata']['sourceSha256']==hashlib.sha256(raw).hexdigest()
        assert console.request(path,body,expected=201)==dataset
        assert console.request('/eclipse-history-datasets/'+str(dataset['metadata']['id']))==dataset
        comparisons=[]
        for run in [baseline,*[m['run'] for m in batch['members']]]:
            actual=console.request(f'/eclipse-history-datasets/{dataset["metadata"]["id"]}/comparison?runId={run["id"]}')
            expected=oracle(text,run['result']['summary']);same(actual['comparison'],expected)
            assert actual['runId']==run['id'] and actual['dataset']==dataset['metadata']
            console.save('comparison-'+str(run['id']),actual);comparisons.append(run['id'])
        water_base=failed_batch['baseline'];water_path=f'/model-versions/{water_base["modelVersionId"]}/eclipse-history-datasets'
        water_body=dict(name='失败隔离算法验算',baselineRunId=water_base['id'],sourceFile='water-algorithm.csv',csv='well,keyword,unit,timeDays,value\r\nP,WWPR,SM3/DAY,300,1000\r\n')
        water=console.request(water_path,water_body,expected=201);console.save('dataset-water',water)
        console.request(f'/eclipse-history-datasets/{water["metadata"]["id"]}/comparison?runId={failed_batch["members"][0]["run"]["id"]}',expected=409);rejected+=1
        success=failed_batch['members'][1]['run'];result=console.request(f'/eclipse-history-datasets/{water["metadata"]["id"]}/comparison?runId={success["id"]}')
        same(result['comparison'],oracle(water_body['csv'],success['result']['summary']));console.save('comparison-water',result)
        # Native gas base and scenario have different report clocks; preserve every unmatched observation.
        gas_curve=next(s for s in gas['result']['summary']['series'] if s['keyword']=='WGIT' and s['objectName']=='I')
        stream=io.StringIO(newline='');writer=csv.writer(stream,lineterminator='\r\n');writer.writerow(['well','keyword','unit','timeDays','value'])
        writer.writerows(['I','WGIT','MSCF',p['timeDays'],p['value']] for p in gas_curve['points']);gas_csv=stream.getvalue()
        gas_body=dict(name='真实气体报告时刻验算（非现场实测）',baselineRunId=gas['id'],sourceFile='gas-clock-algorithm.csv',csv=gas_csv)
        gas_dataset=console.request(f'/model-versions/{gas["modelVersionId"]}/eclipse-history-datasets',gas_body,expected=201);console.save('dataset-gas',gas_dataset)
        gas_result=console.request(f'/eclipse-history-datasets/{gas_dataset["metadata"]["id"]}/comparison?runId={gas_target["id"]}')
        same(gas_result['comparison'],oracle(gas_csv,gas_target['result']['summary']));console.save('comparison-gas',gas_result)
        assert gas_result['comparison']['metrics'][0]['missingCount']>0
        extreme_body={**body,'name':'极端百分比算法验算','sourceFile':'extreme-algorithm.csv','csv':'well,keyword,unit,timeDays,value\nP1,WOPR,STB/DAY,5,1e-308'}
        extreme=console.request(path,extreme_body,expected=201);console.save('dataset-extreme',extreme)
        extreme_result=console.request(f'/eclipse-history-datasets/{extreme["metadata"]["id"]}/comparison?runId={baseline["id"]}')
        same(extreme_result['comparison'],oracle(extreme_body['csv'],baseline['result']['summary']));console.save('comparison-extreme',extreme_result)
        assert extreme_result['comparison']['metrics'][0]['mape'] is None and extreme_result['comparison']['metrics'][0]['mapeOverflowCount']==1
        assert {r['id'] for r in console.request(f'/model-versions/{baseline["modelVersionId"]}/runs?limit=100')}==run_ids, 'Statistical comparison queued a solver run'
        console.save('request-main',body)
        proof.update(apiVerified=True,datasetIds=[d['metadata']['id'] for d in [dataset,water,gas_dataset,extreme]],baselineRunId=baseline['id'],comparisonRunIds=comparisons,rejectedRequests=rejected,
                     originalCsvBytesVerified=True,idempotentImportVerified=True,failedRunHasNoFabricatedFit=True,gasCoverage=gas_result['comparison']['metrics'][0],maxNumericDifferenceTolerance=1e-12,solverRunCountUnchanged=True)
        print(json.dumps(proof))
    except Exception as error:proof['error']=str(error);raise
    finally:console.save('acceptance-summary',proof)


if __name__=='__main__':main()
