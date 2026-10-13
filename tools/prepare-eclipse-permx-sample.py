"""Prepare a full-grid INIT/EGRID tracer water-drive sample, changing only output requests."""
import hashlib
import json
from pathlib import Path

repo = Path(__file__).resolve().parent.parent
source = repo / 'test-data/ECLIPSE_WRAT.DATA'
original = source.read_bytes()
assert hashlib.sha256(original).hexdigest() == 'a14f8c7a49713f97732bf46c7b4c2c7e8c4588b209b0fc1c122b57d4ffae986c'
newline = b'\r\n' if b'\r\n' in original else b'\n'
noggf = b'NOGGF' + newline
assert original.count(noggf) == 1
main = original.replace(noggf, b'INIT' + newline, 1)
assert main.count(b'METRIC' + newline) == 1
main = main.replace(b'METRIC' + newline, b'METRIC' + newline + newline + b'UNIFOUT' + newline, 1)
old = newline.join([b'WOPR', b" 'P' /", b''])
new = newline.join([b'WWPT', b" 'P' /", b''])
assert main.count(old) == 1
main = main.replace(old, new, 1)
reversed_bytes = main.replace(b'INIT' + newline, noggf, 1).replace(newline + b'UNIFOUT' + newline, b'', 1).replace(new, old, 1)
assert reversed_bytes == original, 'Every input physics/control byte must remain unchanged'
target = repo / 'test-data/ECLIPSE_PERMX_WATER.DATA'
assert not target.exists(), 'Do not overwrite a prepared input fixture'
target.write_bytes(main)
proof = dict(source=str(source), sourceSha256=hashlib.sha256(original).hexdigest(), sampleSha256=hashlib.sha256(main).hexdigest(),
             officialSource='C:/ecl/2024.1/eclipse/data/TRACERDISPERS.DATA', physicsAndScheduleUnchanged=True,
             changes=['Replace NOGGF with INIT to retain full EGRID and static input', 'Add UNIFOUT', 'Request WWPT instead of nonexistent water-only WOPR'],
             scenario='METRIC quarter-five-spot water drive; 15x15x1, uniform PERMX/PERMY 100 mD; P/I opposite corners; 300-day unchanged control.')
target.with_suffix('.provenance.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
print(json.dumps(proof))
