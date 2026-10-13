"""Materialize explicit default skin/KH for controlled COMPDAT numerical editing; physics proof remains separate."""
import hashlib
import argparse
import json
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--native-evidence');args=parser.parse_args()
    repo=Path(__file__).resolve().parent.parent
    original=(repo/'test-data/ECLIPSE_PERMX_WATER.DATA').read_bytes()
    assert hashlib.sha256(original).hexdigest()=='97b5fdebe5df1c4e5c85b9b67d6d8bb747ee48aff0bf1e3668cfdc0e8fa43222'
    before=b"'P       '   1   1   1   1 'OPEN'   0  .0   1.0 /"
    after=b"'P       '   1   1   1   1 'OPEN'   0  .0   1.0  1000  0 /"
    assert original.count(before)==1
    source=original.replace(before,after)
    assert source.replace(after,before)==original
    path=repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.DATA'
    if path.exists():assert path.read_bytes()==source
    else:path.write_bytes(source)
    provenance=dict(source='Official SLB TRACERDISPERS.DATA via maintained ECLIPSE_PERMX_WATER output-only fixture',
                    sourceSha256=hashlib.sha256(original).hexdigest(),sampleSha256=hashlib.sha256(source).hexdigest(),
                    changes=["Only P COMPDAT adds explicit KH=1000 mD.m and SKIN=0 after original diameter=1 m; original CF=0 remains calculated."],
                    rationale='100 mD Cartesian X/Y permeability and 10 m thickness; must verify explicit KH equivalence against complete original native outputs before acceptance.',
                    physicalEquivalenceVerified=False,manualUiVerified=False)
    if args.native_evidence:
        evidence=Path(args.native_evidence).resolve();proof=json.loads(evidence.read_text(encoding='utf-8'))
        assert proof['nativeVerified'] and proof['physicalEquivalenceVerified'] and proof['sourceUnchanged']
        assert proof['baseline']['inputSha256']==provenance['sampleSha256']
        provenance.update(physicalEquivalenceVerified=True,nativeEvidence=str(evidence),nativeEvidenceSha256=hashlib.sha256(evidence.read_bytes()).hexdigest())
    (repo/'test-data/ECLIPSE_COMPLETION_GEOMETRY.provenance.json').write_text(json.dumps(provenance,indent=2),encoding='utf-8')
    print(json.dumps(provenance))


if __name__=='__main__':main()
