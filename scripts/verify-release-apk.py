"""Read-only checks for optimized APKs; no installation, Root commands or publishing."""
import argparse
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED

from native_artifacts import elf_runtime


def dex_classes(data):
    _, strings = struct.unpack_from('<II', data, 56)
    _, types = struct.unpack_from('<II', data, 64)
    count, classes = struct.unpack_from('<II', data, 96)
    for n in range(count):
        type_index = struct.unpack_from('<I', data, classes + 32 * n)[0]
        string_index = struct.unpack_from('<I', data, types + 4 * type_index)[0]
        start = struct.unpack_from('<I', data, strings + 4 * string_index)[0]
        while data[start] & 128:
            start += 1
        start += 1
        yield data[start:data.index(b'\0', start)].decode('utf-8', 'replace')


def scan(data, secrets, depth=0):
    if any(secret in data for secret in secrets):
        raise ValueError('Private map key found')
    if data.startswith(b'PK\x03\x04') and depth < 4:
        with ZipFile(io.BytesIO(data)) as archive:
            for name in archive.namelist():
                scan(archive.read(name), secrets, depth + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--abis', default='arm64-v8a,armeabi-v7a')
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--allow-root-loader-change', action='store_true',
                        help='Allow an intentionally updated loader, while verifying the shared library runtime and stripped state')
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools/36.1.0'
    suffix = '.exe' if os.name == 'nt' else ''
    java = Path(os.environ['JAVA_HOME']) / ('bin/java' + suffix)

    def manifest(path):
        return subprocess.check_output([str(tools / ('aapt' + suffix)), 'dump', 'xmltree', str(path),
                                        'AndroidManifest.xml'], text=True, encoding='utf-8')

    def certificate(path):
        text = subprocess.check_output([str(java), '-jar', str(tools / 'lib/apksigner.jar'),
                                        'verify', '--print-certs', str(path)], text=True, encoding='utf-8')
        return re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', text).group(1)

    text = manifest(args.apk)
    assert 'package="com.mockgeo.app"' in text
    assert not re.search(r'android:debuggable[^\n]*0xffffffff', text), 'Debug APK is not a release artifact'
    assert '"android.permission.ACCESS_MOCK_LOCATION"' in text
    assert re.search(r'android:extractNativeLibs[^\n]*0xffffffff', text), 'Compressed native libraries require extraction'
    cert = certificate(args.apk)
    assert cert == certificate(args.baseline), 'Certificate changed: installed users cannot update'
    secrets = []
    private = project / 'secrets.properties'
    if private.exists():
        for line in private.read_text(encoding='utf-8-sig').splitlines():
            if '=' in line and line.split('=', 1)[0].strip() == 'amap.apiKey':
                value = line.split('=', 1)[1].strip()
                if value:
                    secrets.extend((value.encode(), value.encode('utf-16-le')))
    if os.environ.get('AMAP_API_KEY'):
        value = os.environ['AMAP_API_KEY']
        secrets.extend((value.encode(), value.encode('utf-16-le')))
    blob = args.apk.read_bytes()
    scan(blob, secrets)
    groups = Counter()
    temp_parent = project / 'build/tmp/release-verification'
    temp_parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.apk) as apk, ZipFile(args.baseline) as baseline, \
            tempfile.TemporaryDirectory(dir=temp_parent) as directory:
        directory = Path(directory)
        assert directory.resolve().is_relative_to(temp_parent.resolve())
        abis = {n.split('/')[1] for n in apk.namelist() if n.startswith('lib/') and n.endswith('.so')}
        assert abis == set(args.abis.split(',')), f'Unexpected native ABIs: {abis}'
        names = []
        for entry in apk.infolist():
            name = entry.filename
            if name.startswith('lib/'):
                assert entry.compress_type == ZIP_DEFLATED, 'Uncompressed native library in download APK'
                # Third-party binaries are only recompressed, never modified.
                # mockgeo_art is a separate legacy diagnostic helper built per variant.
                if name.endswith('.so') and not name.endswith('/libmockgeo_art.so'):
                    assert apk.read(name) == baseline.read(name), 'Third-party native library changed: ' + name
                group = '/'.join(name.split('/')[:2])
            elif name.endswith('.dex'):
                names.extend(dex_classes(apk.read(name)))
                group = 'dex'
            elif name.startswith('assets/root/'):
                group = 'root'
            elif name.startswith('assets/'):
                group = 'other assets'
            else:
                group = 'resources/metadata'
            groups[group] += entry.compress_size
        icons = sum(n.startswith('Landroidx/compose/material/icons/') for n in names)
        assert icons < 300, f'Unused icon library retained: {icons} classes'
        assert groups['dex'] < 12 * 1024 * 1024, 'DEX size regression: check shrinking configuration'
        assert not any(n.startswith('Landroidx/compose/ui/tooling/ComposeViewAdapter') for n in names)
        props = dict(line.split('=', 1) for line in apk.read('assets/root/manifest.properties').decode().splitlines()
                     if line and not line.startswith('#'))
        native_changes = []
        for name in ('payload.apk', 'libmockgeo_root.so', 'loader'):
            data = apk.read('assets/root/' + name)
            assert hashlib.sha256(data).hexdigest() == props[name], 'Root artifact digest mismatch'
            target = directory / name
            target.write_bytes(data)
            if name != 'payload.apk':
                original = directory / ('baseline-' + name)
                original.write_bytes(baseline.read('assets/root/' + name))
                old_sections, old_segments, _ = elf_runtime(original)
                sections, segments, debug = elf_runtime(target)
                assert not debug, 'Root artifact contains debug sections'
                if old_sections != sections or old_segments != segments:
                    assert name == 'loader' and args.allow_root_loader_change, 'Unexpected Root runtime change'
                    native_changes.append(name)
        payload_manifest = manifest(directory / 'payload.apk')
        assert re.search(r'android:versionName[^\n]*"' + re.escape(props['version']) + '"', payload_manifest)
        with ZipFile(directory / 'payload.apk') as payload:
            assert not any(n.startswith('lib/') for n in payload.namelist()), 'Duplicate native payload library'
            classes = {c for n in payload.namelist() if n.endswith('.dex') for c in dex_classes(payload.read(n))}
            for cls in ('com/mockgeo/root/NativeHooks', 'com/mockgeo/root/PayloadBootstrap',
                        'com/mockgeo/module/HookInstaller', 'com/mockgeo/root/PlantBridge$Record'):
                assert 'L' + cls + ';' in classes, f'Reflective payload entry missing: {cls}'
        result = dict(apk=str(args.apk), bytes=len(blob), sha256=hashlib.sha256(blob).hexdigest(),
                      baselineBytes=args.baseline.stat().st_size, certificate=cert, debuggable=False,
                      abis=sorted(abis), compressedNativeLibraries=True, privateKeyAbsent=True,
                      compressedGroups=dict(groups), iconClasses=icons, rootVersion=props['version'],
                      rootNativeRuntimeUnchanged=not native_changes, rootNativeChangedArtifacts=native_changes,
                      thirdPartyNativeBinariesUnchanged=True,
                      rootArtifactsVerified=True, deviceTested=False)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
