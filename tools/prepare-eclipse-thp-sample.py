"""Keep official HORZWFRI physics and VFP table; request unique acceptance outputs."""
from pathlib import Path
import hashlib
import json
import re

source = Path('C:/ecl/2024.1/eclipse/data/HORZWFRI.DATA')
destination = Path(__file__).resolve().parent.parent / 'test-data/ECLIPSE_THP.DATA'
original = source.read_bytes()
text = original.decode('utf-8')
newline = '\r\n' if b'\r\n' in original else '\n'
summary = re.search(r'(?m)^SUMMARY\b[^\r\n]*', text)
schedule = re.search(r'(?m)^SCHEDULE\b[^\r\n]*', text)
assert summary and schedule
requests = ['SUMMARY']
for keyword in ['WTHP', 'WBHP', 'WLPR', 'WOPR', 'WWPR', 'WGPR']:
    requests += [keyword, " 'HORIZ' /"]
payload = (text[:summary.start()] + newline.join(requests + ['']) + text[schedule.start():]).encode('utf-8')
if destination.exists():
    assert destination.read_bytes() == payload
else:
    destination.write_bytes(payload)
provenance = {'officialSource': str(source), 'officialSha256': hashlib.sha256(original).hexdigest(),
    'sampleSha256': hashlib.sha256(payload).hexdigest(), 'changes': ['Replace SUMMARY requests only; preserve reservoir, fluid, horizontal completions, friction, VFP table 1, THP 500 psia, BHP limit 400 psia and original time steps.'],
    'physicsAndScheduleUnchanged': True}
destination.with_suffix('.provenance.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
assert source.read_bytes() == original
print(provenance['sampleSha256'])

# Explicit reference reservoir variant for in-table operation; the original remains an extrapolation case.
reference = destination.with_name('ECLIPSE_THP_IN_RANGE.DATA')
reference_text = payload.decode('utf-8')
for keyword, old, new in [('PERMX', '100', '5'), ('PERMY', '100', '5'), ('PERMZ', '10', '0.5')]:
    pattern = r'(?m)(^' + keyword + r'\s+504\*)' + old + r'(\s+/)'
    reference_text, count = re.subn(pattern, r'\g<1>' + new + r'\2', reference_text)
    assert count == 1
reference_bytes = reference_text.encode('utf-8')
if reference.exists():
    assert reference.read_bytes() == reference_bytes
else:
    reference.write_bytes(reference_bytes)
reference.with_suffix('.provenance.json').write_text(json.dumps({
    **provenance, 'sampleSha256': hashlib.sha256(reference_bytes).hexdigest(), 'physicsAndScheduleUnchanged': False,
    'changes': provenance['changes'] + ['Explicit GRID PERMX/PERMY 100 to 5 mD and PERMZ 10 to 0.5 mD across 504 cells, to create a separate lower-productivity reference with rates inside the original VFP axes. VFP, completions, friction, fluid, controls and time steps unchanged. Not an unmodified official example.']
}, indent=2), encoding='utf-8')
print(reference, hashlib.sha256(reference_bytes).hexdigest())
