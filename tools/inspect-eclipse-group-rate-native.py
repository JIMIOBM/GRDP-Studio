"""Read independent native Fortran summary records; retain every ordered point and diagnostic."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

def read(path):
    data = path.read_bytes(); pos = 0; output = []
    def record():
        nonlocal pos
        size = struct.unpack_from('>i', data, pos)[0]
        assert size >= 0 and pos + size + 8 <= len(data)
        block = data[pos+4:pos+4+size]
        assert struct.unpack_from('>i', data, pos+4+size)[0] == size
        pos += size + 8
        return block
    while pos < len(data):
        header = record(); assert len(header) == 16
        key = header[:8].decode().strip(); count = struct.unpack('>i', header[8:12])[0]; kind = header[12:16].decode()
        size = {'INTE':4, 'REAL':4, 'DOUB':8, 'CHAR':8, 'LOGI':4, 'MESS':0}[kind]; block = b''
        while len(block) < count*size: block += record()
        assert len(block) == count*size
        values = [block[i:i+8].decode().strip() for i in range(0,len(block),8)] if kind == 'CHAR' else list(struct.unpack('>'+str(count)+{'INTE':'i','REAL':'f','DOUB':'d','LOGI':'i'}.get(kind,'i'), block))
        output.append((key, values))
    return output

def main():
    p = argparse.ArgumentParser(); p.add_argument('--evidence', required=True); args = p.parse_args()
    root = Path(args.evidence); proof = {}
    for mode in ['LRAT','GRAT','WRAT']:
        if not (root/mode).is_dir(): continue
        proof[mode] = {}
        for label in ['baseline','target','limited']:
            d = root/mode/label; spec = dict(read(d/'CASE.SMSPEC')); time = spec['KEYWORDS'].index('TIME')
            files = sorted(d.glob('CASE.S[0-9]*'))
            unified = d/'CASE.UNSMRY'
            if unified.is_file():
                assert not files, 'Mixed unified and separate summary output is ambiguous'
                files = [unified]
            points = [v for file in files for key,v in read(file) if key == 'PARAMS' and v[time] > 0]
            curves = [{'keyword':key,'objectName':obj,'unit':unit,'points':[{'timeDays':v[time],'value':v[i]} for v in points]}
                for i,(key,obj,unit) in enumerate(zip(spec['KEYWORDS'],spec['WGNAMES'],spec['UNITS'])) if key in ['GLPR','WLPR','GGPR','WGPR','GWPR','WWPR','WBHP'] or mode == 'WRAT' and key == 'WOPR']
            end = (d/'CASE.ECLEND').read_text(encoding='utf-8')
            counts = {key.lower():int(re.search(key+r'\s+(\d+)',end).group(1)) for key in ['Comments','Warnings','Problems','Errors','Bugs']}
            assert all(counts[key] == 0 for key in ['problems','errors','bugs']) and curves and points
            proof[mode][label] = {'curves':curves,'eclEnd':counts,'inputSha256':hashlib.sha256((d/'CASE.DATA').read_bytes()).hexdigest()}
            group = curves[0]; first = group['points'][0]
            print(mode,label,'first',first,'range',min(p['value'] for p in group['points']),max(p['value'] for p in group['points']),
                'wells',[c['points'][0]['value'] for c in curves[1:5]],'BHP',[c['points'][0]['value'] for c in curves[5:]])
    with (root/'native-acceptance.json').open('x',encoding='utf-8') as f: json.dump(proof,f,indent=2)

if __name__ == '__main__': main()
