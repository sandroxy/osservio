"""Negative provenance checks for native distribution inputs, using synthetic artifacts only."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

module = importlib.util.spec_from_file_location('native_release', Path(__file__).with_name('native-release.py'))
release = importlib.util.module_from_spec(module)
module.loader.exec_module(release)


class NativeReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.spec = {'version': '1.0.0', 'native': {'android': {'group': 'io.github.sandroxy', 'artifacts': ['osservio-core']}}}
        self.commit = 'a' * 40
        classes = release.zip_bytes({f'META-INF/osservio/osservio-core-{name}.txt': (release.ROOT / name).read_bytes()
                                     for name in ('LICENSE', 'NOTICE')})
        pom = b'''<project xmlns="http://maven.apache.org/POM/4.0.0"><name>Core</name><description>Core</description>
            <url>https://example.invalid</url><licenses><license><name>Apache-2.0</name><url>https://example.invalid</url></license></licenses>
            <developers><developer><id>test</id></developer></developers><scm><connection>scm:git:test</connection></scm></project>'''
        payloads = {'.aar': release.zip_bytes({'classes.jar': classes}), '.pom': pom,
                    '.module': b'{}', '-sources.jar': release.zip_bytes({'Example.java': b'// fixture'}),
                    '-javadoc.jar': release.zip_bytes({'index.html': b'fixture'})}
        self.entries = []
        for suffix, data in payloads.items():
            name = 'io/github/sandroxy/osservio-core/1.0.0/osservio-core-1.0.0' + suffix
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            self.entries.append({'path': name, 'bytes': len(data), 'sha256': release.sha(data)})
        self.record = {'schemaVersion': 2, 'kind': 'local-android-artifacts', 'sourceRevision': self.commit,
                       'sourceDirty': False, 'version': '1.0.0', 'group': 'io.github.sandroxy',
                       'signed': False, 'artifacts': self.entries}
        self.manifest = self.root / 'osservio-artifacts.json'

    def check(self):
        self.manifest.write_text(json.dumps(self.record))
        return release.checked_artifacts(self.manifest, self.spec, self.commit, 'F' * 40)

    def test_complete_input_and_packaged_license(self):
        self.assertEqual(len(self.check()[1]), 5)

    def test_dirty_or_other_commit_or_version_is_rejected(self):
        for key, value in [('sourceDirty', True), ('sourceRevision', 'b' * 40), ('version', '2.0.0')]:
            with self.subTest(key=key):
                previous = self.record[key]
                self.record[key] = value
                with self.assertRaises(ValueError): self.check()
                self.record[key] = previous

    def test_changed_bytes_are_rejected(self):
        (self.root / self.entries[0]['path']).write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'bytes differ'): self.check()

    def test_incomplete_duplicate_and_unsigned_signature_claim(self):
        self.record['artifacts'] = self.entries[:-1]
        with self.assertRaisesRegex(ValueError, 'incomplete'): self.check()
        self.record['artifacts'] = self.entries + [self.entries[0]]
        with self.assertRaisesRegex(ValueError, 'incomplete'): self.check()
        self.record['artifacts'] = self.entries
        self.record['signed'] = True
        with self.assertRaisesRegex(ValueError, 'incomplete'): self.check()

    def test_traversal_and_links_are_rejected(self):
        for name in ('../outside', '/absolute', 'a/../b', 'a\\b', 'a//b'):
            with self.subTest(name=name), self.assertRaises(ValueError): release.safe_file(self.root, name)
        (self.root / 'link').symlink_to(self.root / self.entries[0]['path'])
        with self.assertRaises(ValueError): release.safe_file(self.root, 'link')

    def test_missing_license_is_rejected_even_when_outer_hash_matches(self):
        entry = self.entries[0]
        data = release.zip_bytes({'classes.jar': release.zip_bytes({'test': b'fixture'})})
        (self.root / entry['path']).write_bytes(data)
        entry.update(bytes=len(data), sha256=release.sha(data))
        with self.assertRaises(KeyError): self.check()

    def test_archives_have_stable_order_and_time(self):
        first = release.zip_bytes({'b': b'2', 'a': b'1'})
        self.assertEqual(first, release.zip_bytes({'a': b'1', 'b': b'2'}))
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertEqual(archive.namelist(), ['a', 'b'])
            self.assertEqual(archive.read('b'), b'2')


if __name__ == '__main__':
    unittest.main()
