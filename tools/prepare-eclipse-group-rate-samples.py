"""Bounded GRAT/LRAT reference decks; all changes to installed BIG3D3P are recorded."""
from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
source = Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
raw = source.read_bytes()
assert hashlib.sha256(raw).hexdigest() == '940a5e181cc9dc65b4d8ff2ae9ffed21547c94a69057a5a5605aeade27fc8a1a'
original = raw.decode('utf-8')
nl = '\r\n' if '\r\n' in original else '\n'
members = ['PRODUCR1', 'PRODUCR2', 'PRODUCR3', 'PRODUCR4']
for mode, group_key, well_key, defaults, baseline, positive, limited in [
    ('GRAT', 'GGPR', 'WGPR', '2*', 60000, 30000, 250000),
    ('LRAT', 'GLPR', 'WLPR', '3*', 2000, 1000, 80000),
]:
    summary = re.search(r'(?m)^SUMMARY[^\r\n]*\r?\n', original)
    schedule = re.search(r'(?m)^SCHEDULE[^\r\n]*\r?\n', original)
    assert summary and schedule
    block = (f"SUMMARY\n{group_key}\n 'G' /\n{well_key}\n 'PRODUCR1' 'PRODUCR2' 'PRODUCR3' 'PRODUCR4' /\n"
             "WBHP\n 'PRODUCR1' 'PRODUCR2' 'PRODUCR3' 'PRODUCR4' /\n\n").replace('\n', nl)
    text = original[:summary.start()] + block + original[schedule.start():]
    for well in members:
        text, count = re.subn(r"('%s'\s+'OPEN'\s+)'ORAT'" % well, r"\1'GRUP'", text)
        assert count == 1
    injection = re.search(r'(?m)^WCONINJE[ \t]*\r?$', text)
    assert injection
    text = text[:injection.start()] + f"GCONPROD\n 'G' '{mode}' {defaults} {baseline} /\n/\n\n".replace('\n', nl) + text[injection.start():]
    payload = text.encode('utf-8')
    probe = Path(f'C:/GRDP-TestOutput/ECLIPSE-P03-GROUP-RATE-probe-a{1 if mode == "GRAT" else 2}/{mode}/baseline/CASE.DATA')
    assert payload == probe.read_bytes(), 'Published reference must equal the licensed native baseline byte for byte'
    sample = repo / f'test-data/ECLIPSE_GROUP_{mode}.DATA'
    sample.write_bytes(payload)
    receipt = {'source': str(source), 'sourceSha256': hashlib.sha256(raw).hexdigest(), 'sourceUnchanged': source.read_bytes() == raw,
               'sample': sample.name, 'sampleSha256': hashlib.sha256(payload).hexdigest(), 'originalOfficialModel': False,
               'changes': [f'SUMMARY requests {group_key} G, {well_key} and WBHP for four existing producers.',
                           'Four existing OPEN ORAT producers changed to GRUP; all per-well oil limits 20000 STB/day and BHP lower limits 2500 psia preserved.',
                           f'Initial GCONPROD G {mode} {baseline} inserted in official target slot. All reservoir/fluid physics, well specifications, completions, injectors and TSTEP preserved.'],
               'mode': mode, 'group': 'G', 'members': members, 'unit': 'Mscf/day' if mode == 'GRAT' else 'STB/day',
               'baselineTarget': baseline, 'positiveTarget': positive, 'pressureLimitedTarget': limited,
               'nativeBaseline': str(probe), 'nativeByteEquivalent': True,
               'warnings': 'Installed reference endpoint/PVT/default injector BHP and convergence warnings must remain visible.'}
    sample.with_suffix('.provenance.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps(receipt))
