"""Derive a direct GAS group demonstrator from the installed official black-oil model."""
from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
raw = source.read_bytes()
text = raw.decode('utf-8')
nl = '\r\n' if '\r\n' in text else '\n'
summary = re.search(r'(?m)^SUMMARY[^\r\n]*\r?\n', text)
schedule = re.search(r'(?m)^SCHEDULE[^\r\n]*\r?\n', text)
assert summary and schedule
text = text[:summary.start()] + "SUMMARY\nGGIR\n 'I' /\nWGIR\n 'INJECTR1' 'INJECTR2' 'INJECTR3' 'INJECTR4' /\nWBHP\n 'INJECTR1' 'INJECTR2' 'INJECTR3' 'INJECTR4' /\n\n".replace('\n', nl) + text[schedule.start():]
assert text.count('10    4    2    6 /') == 1
text = text.replace('10    4    2    6 /', '10    4    3    6 /')
for n in (5, 6):
    text, count = re.subn(r"('INJECTR%d'\s+)'I'" % n, r"\1'WI'", text)
    assert count == 1
for n in (1, 2, 3, 4):
    text, count = re.subn(r"('INJECTR%d'\s+'GAS'\s+'OPEN'\s+)'RATE'\s+50000\s+/" % n, r"\1'GRUP' 50000 1* 4900 /", text)
    assert count == 1
tstep = re.search(r'(?m)^TSTEP[ \t]*\r?$', text)
assert tstep
text = text[:tstep.start()] + "GCONINJE\n 'I' 'GAS' 'RATE' 50000 /\n/\n\n".replace('\n', nl) + text[tstep.start():]
sample = repo / 'test-data/ECLIPSE_GROUP_GAS_INJECTION.DATA'
payload = text.encode('utf-8')
sample.write_bytes(payload)
receipt = dict(source=str(source), sha256=hashlib.sha256(raw).hexdigest(), sourceUnchanged=source.read_bytes() == raw,
    sample=sample.name, sampleSha256=hashlib.sha256(payload).hexdigest(), originalOfficialModel=False,
    changes=['SUMMARY requests GGIR I, WGIR and WBHP for existing INJECTR1-4.',
        'WELLDIMS group capacity 2->3; existing water injectors assigned to separate WI group with original RATE controls unchanged.',
        'Existing four gas injectors RATE->GRUP; original surface limit 50000 preserved; explicit BHP upper limit 4900 psia replaces default 100000.',
        'Insert GCONINJE I GAS RATE 50000 Mscf/day. All other reservoir/fluid physics, wells, completions and time plan preserved.'],
    baselineTarget=50000, positiveTarget=25000, pressureLimitedTarget=150000, bhpUpperLimit=4900,
    limitations='Native a2 proves baseline 50000, target 25000 and initial pressure-constrained 150000 -> 110880.5390625 Mscf/day. Later distribution varies with well potentials. Not field operating recommendations. Original PVT/endpoints/convergence warnings remain.')
sample.with_suffix('.provenance.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
print(json.dumps(receipt))
