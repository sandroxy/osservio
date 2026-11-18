#!/usr/bin/env python3
"""Freeze native distribution inputs. This command never uploads or publishes."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SUFFIXES = ('.aar', '.pom', '.module', '-sources.jar', '-javadoc.jar')
SOURCE_PATHS = ('Package.swift', 'Package.resolved', 'plugin.json', 'release-policy.json',
                'LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'README.md', 'README-EN.md',
                'native/ios', 'testing', 'protocol', 'doc')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def read_json(path):
    return json.loads(path.read_text())


def encode(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode()


def safe_file(root, name):
    if not isinstance(name, str):
        raise ValueError('Artifact path must be text')
    relative = PurePosixPath(name)
    if (relative.is_absolute() or '\\' in name
            or any(p in ('', '.', '..') for p in name.split('/'))):
        raise ValueError('Unsafe artifact path: ' + str(name))
    path = root / name
    if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file():
        raise ValueError('Artifact must be a regular file without symbolic links: ' + str(path))
    return path


def checked_artifacts(manifest, spec, commit, fingerprint=None):
    record = read_json(manifest)
    expected = {'schemaVersion': 2, 'kind': 'local-android-artifacts',
                'sourceRevision': commit, 'sourceDirty': False,
                'version': spec['version'], 'group': spec['native']['android']['group']}
    if any(record.get(key) != value for key, value in expected.items()):
        raise ValueError('Android manifest must match the clean current source and version')
    signed = record.get('signed')
    if type(signed) is not bool:
        raise ValueError('Android signing state must be explicit')
    if signed and (not isinstance(fingerprint, str) or not re.fullmatch(r'[0-9A-F]{40}|[0-9A-F]{64}', fingerprint)):
        raise ValueError('Signed inputs require the explicit full public signing fingerprint')
    group = record['group'].replace('.', '/')
    names = {f'{group}/{module}/{record["version"]}/{module}-{record["version"]}{suffix}'
             for module in spec['native']['android']['artifacts'] for suffix in SUFFIXES}
    if signed:
        names |= {name + '.asc' for name in names}
    entries = record.get('artifacts', [])
    if len(entries) != len(names) or {e['path'] for e in entries} != names:
        raise ValueError('Android artifact set is incomplete or contains unexpected files')
    result = {}
    for entry in entries:
        path = safe_file(manifest.parent, entry['path'])
        data = path.read_bytes()
        if len(data) != entry['bytes'] or sha(data) != entry['sha256']:
            raise ValueError('Artifact bytes differ: ' + entry['path'])
        if signed and path.suffix == '.asc':
            status = subprocess.run(['gpg', '--batch', '--status-fd', '1', '--verify', str(path), str(path)[:-4]],
                                    check=True, capture_output=True, text=True).stdout
            signatures = [line.split() for line in status.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
            if len(signatures) != 1 or fingerprint not in (signatures[0][2], signatures[0][-1]):
                raise ValueError('Artifact signature does not match the selected release key')
        result[entry['path']] = data
    for name, data in result.items():
        if name.endswith('.pom'):
            pom = ET.fromstring(data)
            ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
            for field in ('name', 'description', 'url', 'licenses/license/name',
                          'licenses/license/url', 'developers/developer/id', 'scm/connection'):
                node = pom.find('/'.join('m:' + part for part in field.split('/')), ns)
                if node is None or not node.text:
                    raise ValueError('POM metadata is missing: ' + field)
        if name.endswith('.aar'):
            module = PurePosixPath(name).parts[-3]
            with zipfile.ZipFile(io.BytesIO(data)) as aar:
                with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
                    for legal in ('LICENSE', 'NOTICE'):
                        if classes.read(f'META-INF/osservio/{module}-{legal}.txt') != (ROOT / legal).read_bytes():
                            raise ValueError('Packaged original license differs: ' + module)
    return record, result


def zip_bytes(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return stream.getvalue()


def source_files(commit):
    archive = git('archive', '--format=tar', commit, '--', *SOURCE_PATHS)
    files = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        for item in source:
            if item.isdir():
                continue
            if not item.isfile():
                raise ValueError('Source archive cannot contain links or special files')
            files[item.name] = source.extractfile(item).read()
    for required in ('Package.swift', 'Package.resolved', 'LICENSE', 'NOTICE', 'plugin.json'):
        if required not in files:
            raise ValueError('Missing Swift package input: ' + required)
    return files


def freeze(manifest, output, state, fingerprint=None):
    spec = read_json(ROOT / 'plugin.json')
    policy = read_json(ROOT / 'release-policy.json')
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', spec['version']):
        raise ValueError('Choose a stable candidate version explicitly before freezing')
    commit = git('rev-parse', 'HEAD').decode().strip()
    if git('status', '--porcelain', '--untracked-files=all').strip():
        raise ValueError('Commit the reviewed source before freezing native artifacts')
    if output.exists() or any(p.is_symlink() for p in (output, *output.parents)):
        raise ValueError('Output must be a new directory without symbolic links; preserve frozen outputs')
    record, maven = checked_artifacts(manifest, spec, commit, fingerprint)
    if not record['signed'] and fingerprint is not None:
        raise ValueError('Unsigned inputs cannot declare a signing fingerprint')
    if state == 'candidate' and not record['signed']:
        raise ValueError('Unsigned artifacts are rehearsal-only; no candidate acceptance is allowed')
    source = source_files(commit)
    # Repository checksums are generated from the bound bytes, never stale Gradle output.
    repository = dict(maven)
    for name, data in maven.items():
        if not name.endswith('.asc'):
            for algorithm in ('md5', 'sha1', 'sha256'):
                repository[name + '.' + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
    central = zip_bytes(repository) if record['signed'] else None
    repository['osservio-artifacts.json'] = manifest.read_bytes()
    for name in ('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md'):
        repository[name] = (ROOT / name).read_bytes()
    artifacts = {
        'native-android-maven-repository': ('osservio-android-maven.zip', zip_bytes(repository)),
        'native-ios-swift-package': ('osservio-swift-package.zip', zip_bytes(source))}
    if central is not None:
        artifacts['native-android-maven-central-bundle'] = ('osservio-maven-central.zip', central)
    build = {
        'schemaVersion': 1, 'kind': 'osservio-native-build', 'version': spec['version'],
        'sourceRevision': commit, 'sourceRepository': spec['repository'],
        'signingFingerprint': fingerprint,
        'android': record, 'ios': {'products': spec['native']['ios']['modules'],
            'minimumVersion': spec['native']['ios']['minimumVersion'],
            'files': {name: sha(data) for name, data in sorted(source.items())}},
        'archives': {name: sha(data) for name, data in artifacts.values()}}
    artifacts['native-build-manifest'] = ('osservio-native-build.json', encode(build))
    checksums = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(artifacts.values()))
    artifacts['native-build-checksums'] = ('SHA256SUMS', checksums.encode())
    entries = sorted(({'role': role, 'file': name, 'bytes': len(data), 'sha256': sha(data)}
                      for role, (name, data) in artifacts.items()), key=lambda entry: entry['role'])
    expected_roles = set(policy['artifactRoles']['required'])
    if record['signed']:
        expected_roles.update(policy['artifactRoles']['conditional']['androidMavenSigned'])
    if {e['role'] for e in entries} != expected_roles:
        raise ValueError('Generated roles differ from release policy')
    identity = sha(''.join('\t'.join(str(e[k]) for k in ('role', 'file', 'bytes', 'sha256')) + '\n'
                           for e in entries).encode())
    candidate = {'schemaVersion': policy['candidateSchemaVersion'], 'kind': 'plugin-release-candidate',
        'plugin': spec['id'], 'version': spec['version'], 'state': state, 'acceptanceEligible': state == 'candidate',
        'source': {'repository': spec['repository'], 'commit': commit, 'dirty': False},
        'candidateId': f'osservio-{spec["version"]}-{commit[:12]}-{identity[:12]}',
        'artifactSetSha256': identity, 'artifacts': entries, 'acceptance': policy['acceptance'],
        'qualifications': {'androidMavenSigned': record['signed'], 'artifactProvenanceVerified': True}}
    # Detect source or producer changes during packaging, before writing a frozen identity.
    if git('rev-parse', 'HEAD').decode().strip() != commit or git('status', '--porcelain').strip():
        raise ValueError('Source changed during packaging')
    checked_artifacts(manifest, spec, commit, fingerprint)
    output.mkdir(parents=True)
    for name, data in artifacts.values():
        (output / name).write_bytes(data)
    (output / 'candidate.json').write_bytes(encode(candidate))
    print(output / 'candidate.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--state', choices=('candidate', 'rehearsal'), default='rehearsal')
    parser.add_argument('--signing-fingerprint', help='Full public GPG fingerprint selected by the release owner')
    args = parser.parse_args()
    if not args.manifest.is_absolute() or not args.output.is_absolute():
        parser.error('Manifest and output must be explicit absolute paths')
    if not args.output.is_relative_to(ROOT / 'dist') or '..' in args.output.parts:
        parser.error('Frozen output must stay below the repository dist directory')
    try:
        freeze(args.manifest, args.output, args.state, args.signing_fingerprint)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile, ET.ParseError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
