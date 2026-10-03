"""Check runtime generic annotations in the actual R8 APK without dumping strings/secrets."""
import argparse, json, re, struct, zipfile
from pathlib import Path

class Dex:
    def __init__(self, data):
        self.data = data
        count, offset = self.u32(56), self.u32(60)
        self.strings = []
        for i in range(count):
            _, pos = self.uleb(self.u32(offset + i * 4))
            end = data.index(b'\0', pos)
            self.strings.append(data[pos:end].decode('utf-8', 'replace'))
        self.types = [self.strings[self.u32(self.u32(68) + i * 4)] for i in range(self.u32(64))]
    def u32(self, pos):
        return struct.unpack_from('<I', self.data, pos)[0]
    def uleb(self, pos):
        value, shift = 0, 0
        while True:
            byte = self.data[pos]; pos += 1
            value |= (byte & 127) << shift
            if not byte & 128: return value, pos
            shift += 7
    def value(self, pos):
        header = self.data[pos]; pos += 1
        kind, width = header & 31, (header >> 5) + 1
        if kind == 28:
            size, pos = self.uleb(pos); result = []
            for _ in range(size):
                value, pos = self.value(pos); result.append(value)
            return result, pos
        if kind == 29: return self.annotation(pos)
        if kind in (30, 31): return None, pos
        number = int.from_bytes(self.data[pos:pos + width], 'little')
        return (self.strings[number] if kind == 23 else number), pos + width
    def annotation(self, pos):
        type_id, pos = self.uleb(pos); size, pos = self.uleb(pos); values = {}
        for _ in range(size):
            name, pos = self.uleb(pos); value, pos = self.value(pos)
            values[self.strings[name]] = value
        return {'type': self.types[type_id], 'values': values}, pos
    def annotations(self, offset):
        if not offset: return []
        return [self.annotation(self.u32(offset + 4 + i * 4) + 1)[0] for i in range(self.u32(offset))]
    def classes(self):
        for i in range(self.u32(96)):
            offset = self.u32(100) + i * 32
            yield self.types[self.u32(offset)], self.u32(offset + 20)
    def signatures(self, annotations):
        return [''.join(item['values']['value']) for item in annotations if item['type'] == 'Ldalvik/annotation/Signature;']

def verify(apk, mapping, api):
    symbols = Path(mapping).read_text(encoding='utf-8')
    originals = ['kotlin.coroutines.Continuation', api]
    descriptors = {}
    for original in originals:
        match = re.search(r'^' + re.escape(original) + r' -> (.+):$', symbols, re.M)
        if not match: raise ValueError('Required Retrofit runtime class missing: ' + original)
        descriptors[original] = 'L' + match[1].replace('.', '/') + ';'
    found, methods = {}, []
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r'classes\d*\.dex', name): continue
            dex = Dex(archive.read(name))
            for descriptor, directory in dex.classes():
                if descriptor not in descriptors.values(): continue
                original = next(k for k, v in descriptors.items() if v == descriptor)
                annotations = dex.annotations(dex.u32(directory)) if directory else []
                found[original] = dex.signatures(annotations)
                if original == api and directory:
                    offset = directory + 16 + dex.u32(directory + 4) * 8
                    for i in range(dex.u32(directory + 8)):
                        entries = dex.annotations(dex.u32(offset + i * 8 + 4))
                        if any(a['type'].startswith('Lretrofit2/http/') for a in entries):
                            methods.append(dex.signatures(entries))
    continuation = found.get(originals[0], [])
    if not any('<T:' in s for s in continuation): raise ValueError('R8 stripped Continuation<T> runtime generic signature')
    valid = [s for signatures in methods for s in signatures if descriptors[originals[0]][:-1] + '<' in s]
    if len(valid) < 10 or len(valid) != len(methods): raise ValueError('R8 stripped a suspend Retrofit response type')
    return {'continuationGenericPreserved': True, 'suspendEndpointSignaturesPreserved': len(valid), 'apk': Path(apk).name}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--apk', required=True)
    parser.add_argument('--mapping', required=True); parser.add_argument('--api', required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.mapping, args.api)))
