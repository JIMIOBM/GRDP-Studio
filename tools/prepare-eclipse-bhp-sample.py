"""Prepare traceable BASE_WATER acceptance decks while preserving the official source."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ap = argparse.ArgumentParser()
ap.add_argument('--source', default='C:/ecl/2024.1/eclipse/data/BASE_WATER.DATA')
ap.add_argument('--destination', default='test-data/ECLIPSE_BASE_WATER_BHP.DATA')
ap.add_argument('--injection-reference', action='store_true')
args = ap.parse_args()
source = Path(args.source).resolve(strict=True)
target = Path(args.destination).resolve()
assert target != source and target.suffix.upper() == '.DATA'
original = source.read_bytes()
# ALL plus explicit quantities repeats identities; FNQR/ANQR require a NUMS-aware reader.
# Request just the four business curves without changing physics or well constraints.
newline = b'\r\n' if b'\r\n' in original else b'\n'
summary = newline.join([b'SUMMARY', b'', b'WOPR', b" 'P1' /", b'WBHP', b" 'P1' 'I1' /", b'WWIR', b" 'I1' /", b'', b''])
pattern = rb'(?m)^SUMMARY[^\r\n]*\r?\n[\s\S]*?(?=^SCHEDULE[^\r\n]*\r?$)'
prepared, count = re.subn(pattern, lambda _: summary, original)
assert count == 1
before = re.search(pattern, original).span()
after = re.search(pattern, prepared).span()
assert original[:before[0]] == prepared[:after[0]] and original[before[1]:] == prepared[after[1]:]
if args.injection_reference:
    prepared, count = re.subn(rb"('I1'\s+WATER\s+OPEN\s+)RESV(\s+1\*\s+)1000(\s+)6000(?=/)",
        lambda m: m[1] + b'BHP' + m[2] + b'1*' + m[3] + b'5000', prepared)
    assert count == 1
if target.exists():
    assert target.read_bytes() == prepared, 'refuse to replace a different sample'
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(prepared)
assert source.read_bytes() == original
report = {'source': str(source), 'sourceSha256': hashlib.sha256(original).hexdigest(), 'sample': target.name,
    'sampleSha256': hashlib.sha256(prepared).hexdigest(), 'sourceUnchanged': True, 'physicsAndScheduleUnchanged': not args.injection_reference,
    'reservoirAndWellGeometryUnchanged': True,
    'changes': ['SUMMARY requests: WOPR P1, WBHP P1/I1, WWIR I1. Formatted output retained.'] +
        (['I1 reference operation: RESV=1000 / BHP limit=6000 -> BHP=5000 with default surface/reservoir rate limits. This is a derived operating scenario, not the unchanged official deck.'] if args.injection_reference else [])}
target.with_suffix('.provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report))
