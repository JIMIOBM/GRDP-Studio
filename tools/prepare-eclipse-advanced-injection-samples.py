"""Reference inputs for independently bound E100 injection controls, with every derivation recorded."""
from pathlib import Path
import hashlib
import json
import re

repo = Path(__file__).resolve().parent.parent
seed = repo/'test-data/ECLIPSE_GROUP_GAS_INJECTION.DATA'
raw = seed.read_bytes()
assert hashlib.sha256(raw).hexdigest() == '636793487195691b612abc0042b85036ff5bc9d364179bf7b632878592dc668c'
source = Path('C:/ecl/2024.1/eclipse/data/BIG3D3P.DATA')
assert hashlib.sha256(source.read_bytes()).hexdigest() == '940a5e181cc9dc65b4d8ff2ae9ffed21547c94a69057a5a5605aeade27fc8a1a'
text = raw.decode('utf-8')
summary = re.search(r'(?m)^SUMMARY[^\n]*\n', text)
schedule = re.search(r'(?m)^SCHEDULE[^\n]*\n', text)
assert summary and schedule
members = ' '.join("'INJECTR%d'" % n for n in range(1, 5))
producers = ' '.join("'PRODUCR%d'" % n for n in range(1, 5))
for name, phase, mode, record, baseline, target, limited, probe in [
    ('OIL_INJECTION', 'OIL', 'RATE', "'I' 'OIL' 'RATE' {value} /", 5000, 2500, 100000, 'a3'),
    ('GAS_RESV', 'GAS', 'RESV', "'I' 'GAS' 'RESV' 1* {value} /", 10000, 5000, 150000, 'a3'),
    ('GAS_REIN', 'GAS', 'REIN', "'I' 'GAS' 'REIN' 2* {value} 4* 'G' /", 0.2, 0.1, 5, 'a4'),
    ('GAS_VREP', 'GAS', 'VREP', "'I' 'GAS' 'VREP' 3* {value} 4* 'G' /", 0.2, 0.1, 5, 'a4'),
]:
    group_key, well_key = ('GOIR', 'WOIR') if phase == 'OIL' else ('GGIR', 'WGIR')
    block = ("SUMMARY\r\n%s\r\n 'I' /\r\n%s\r\n %s /\r\nGVIR\r\n 'I' /\r\nWVIR\r\n %s /\r\nGGPR\r\n 'G' /\r\nGVPR\r\n 'G' /\r\nWGPR\r\n %s /\r\nWVPR\r\n %s /\r\nWBHP\r\n %s /\r\n\r\n") % (group_key, well_key, members, members, producers, producers, members)
    payload = text[:summary.start()] + block + text[schedule.start():]
    if phase == 'OIL':
        for n in range(1, 5):
            payload, count = re.subn(r"('INJECTR%d'\s+)'GAS'" % n, r"\1'OIL'", payload)
            assert count == 1
            payload, count = re.subn(r"('INJECTR%d'\s+'I'[^\r\n]*?)'GAS'" % n, r"\1'OIL'", payload)
            assert count == 1
    assert payload.count("'I' 'GAS' 'RATE' 50000 /") == 1
    payload = payload.replace("'I' 'GAS' 'RATE' 50000 /", record.format(value=baseline)).encode('utf-8')
    native = Path(f'C:/GRDP-TestOutput/ECLIPSE-P03-ADVANCED-INJECTION-probe-{probe}/{phase}-{mode}/baseline/CASE.DATA')
    assert payload == native.read_bytes(), 'Sample must match the actual commercial native baseline byte for byte'
    sample = repo/f'test-data/ECLIPSE_GROUP_{name}.DATA'
    sample.write_bytes(payload)
    receipt = {
        'source': str(source), 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'seed': str(seed), 'seedSha256': hashlib.sha256(raw).hexdigest(), 'seedUnchanged': seed.read_bytes() == raw,
        'originalOfficialModel': False, 'sampleSha256': hashlib.sha256(payload).hexdigest(),
        'mode': mode, 'injectionPhase': phase, 'group': 'I', 'members': [f'INJECTR{n}' for n in range(1, 5)],
        'referenceGroup': 'G' if mode in ['REIN', 'VREP'] else None,
        'referenceMembers': [f'PRODUCR{n}' for n in range(1, 5)] if mode in ['REIN', 'VREP'] else [],
        'baselineTarget': baseline, 'positiveTarget': target, 'limitedTarget': limited,
        'bhpUpperLimit': 4900, 'surfaceRateUpperLimit': 50000,
        'unit': 'STB/day' if mode == 'RATE' else 'RB/day' if mode == 'RESV' else 'fraction',
        'changes': [
            'All seed derivations retained; see ECLIPSE_GROUP_GAS_INJECTION.provenance.json (explicit BHP4900 and separate water group).',
            'SUMMARY additionally requests complete injection surface/reservoir group and member flows, source production group/member flows, and BHP.',
            'OIL/RATE only: existing four injector types and preferred phase GAS -> OIL; existing surface limit50000 now has oil units STB/day. This is an explicit derived injection scenario, not a phase-neutral edit.' if phase == 'OIL' else 'Original four GAS injector controls, phases and all limits preserved.',
            f'Group mode explicitly derived as {mode}, target {baseline}' + (' with immutable production reference G.' if mode in ['REIN', 'VREP'] else '.'),
            'No new wells or completion edits. Reservoir, fluid, initial conditions, other well controls and 10*365 report days unchanged.'
        ],
        'nativeBaseline': str(native), 'nativeByteEquivalent': True, 'manualUiVerified': False,
        'limitations': 'E100 only; these are demonstrators, not field recommended targets. Source warnings retained. Higher targets may be constrained. Original0.5 fraction probes retained separately.'
    }
    sample.with_suffix('.provenance.json').write_text(json.dumps(receipt, indent=2), encoding='utf-8')
    print(json.dumps({'sample': sample.name, 'sha256': receipt['sampleSha256'], 'native': str(native)}))
