"""Verify release packaging and inspect the generated Home methods under the same CPU cap."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('release_limits', HERE / 'build-lite.py')
limits = importlib.util.module_from_spec(spec)
spec.loader.exec_module(limits)
limits.STATUS = HERE / 'verification-status.json'
limits.limit_cpu()
apk = Path(limits.CONFIG['output']) / 'NetflixProTV-28.9.2026-release.apk'
sdk = Path(limits.CONFIG['existing_sdk']) / 'build-tools/35.0.0'
env = os.environ.copy()
env['JAVA_HOME'] = limits.CONFIG['java_home']
env['JAVA_OPTS'] = '-Xmx256m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC'
env['PATH'] = str(Path(env['JAVA_HOME']) / 'bin') + os.pathsep + env['PATH']


def run(arguments, shell=False):
    command = subprocess.list2cmdline([str(value) for value in arguments]) if shell else [str(value) for value in arguments]
    result = subprocess.run(command, shell=shell, env=env, capture_output=True, text=True, timeout=90,
                            creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def uleb(data, offset):
    result, shift = 0, 0
    for _ in range(5):
        byte = data[offset]
        offset += 1
        result |= (byte & 127) << shift
        if byte < 128:
            return result, offset
        shift += 7
    raise ValueError('Invalid DEX integer')


def home_methods(data, name):
    if data[:4] != b'dex\n':
        raise ValueError('Invalid DEX header')
    string_count, string_offset, type_count, type_offset, _, _, _, _, method_count, method_offset, class_count, class_offset = struct.unpack_from('<12I', data, 56)
    strings = []
    for i in range(string_count):
        offset = struct.unpack_from('<I', data, string_offset + i * 4)[0]
        _, offset = uleb(data, offset)
        strings.append(data[offset:data.index(b'\0', offset)].decode('utf-8', errors='replace'))
    types = [strings[struct.unpack_from('<I', data, type_offset + i * 4)[0]] for i in range(type_count)]
    result = []
    for i in range(class_count):
        entry = struct.unpack_from('<8I', data, class_offset + i * 32)
        if types[entry[0]] != 'Lcom/example/ui/screens/HomeScreenKt;':
            continue
        offset = entry[6]
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(data, offset)
            _, offset = uleb(data, offset)
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, offset = uleb(data, offset)
                index += delta
                _, offset = uleb(data, offset)
                code, offset = uleb(data, offset)
                method = struct.unpack_from('<HHI', data, method_offset + index * 8)
                method_name = strings[method[2]]
                if code and method_name in ['HomeScreen', 'HomeScene', 'HomeKidsBackdrop',
                                            'HomeNavigationBar', 'HomeBrowseTab', 'HomeKidsProfileTransition']:
                    registers, incoming, outgoing, tries, debug, instructions = struct.unpack_from('<4H2I', data, code)
                    result.append({'name': method_name, 'dex': name, 'registers': registers,
                                   'code_bytes': instructions * 2})
    return result


signature = run([sdk / 'apksigner.bat', 'verify', '--verbose', '--print-certs', apk], shell=True)
badging = run([sdk / 'aapt.exe', 'dump', 'badging', apk])
manifest = json.loads((limits.WORKSPACE / 'app/src/main/assets/kids_characters/catalog.json').read_text(encoding='utf-8'))
checks, methods = [], []
with zipfile.ZipFile(apk) as archive:
    invalid_entry = archive.testzip()
    if invalid_entry:
        raise RuntimeError('APK ZIP integrity failure: ' + invalid_entry)
    for entry in manifest['artworks']:
        source = limits.WORKSPACE / 'app/src/main/assets/kids_characters' / entry['file']
        packaged = archive.read('assets/kids_characters/' + entry['file'])
        match = hashlib.sha256(packaged).digest() == hashlib.sha256(source.read_bytes()).digest()
        assert match, 'Packaged character image differs from source'
        checks.append({'file': entry['file'], 'matches_source': match})
    for name in archive.namelist():
        if name.startswith('classes') and name.endswith('.dex'):
            methods += home_methods(archive.read(name), name)
assert any(method['name'] == 'HomeScreen' for method in methods)
assert "name='com.netflixprotv.apk'" in badging
assert 'application-debuggable' not in badging
report = {'apk': str(apk), 'apk_bytes': apk.stat().st_size,
          'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
          'signature_verified': True, 'zip_integrity_verified': True,
          'package_info': [line for line in badging.splitlines() if line.startswith(('package:', 'sdkVersion:',
                                                                                   'targetSdkVersion:', 'launchable-activity:'))],
          'character_assets': checks, 'home_methods': methods,
          'tv_runtime_verified': False}
(HERE / 'apk-verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
(HERE / 'apk-signature.txt').write_text(signature, encoding='utf-8')
limits.status('verification_complete', **report)
