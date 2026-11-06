# Epitaph wire reader

Osservio Runtime vendors the small protobuf `WireReader` from
[Epitaph 0.2.0](https://github.com/abovevacant/epitaph/tree/660898c859055491d8a258664aee27b2555073cb),
commit `660898c859055491d8a258664aee27b2555073cb`, under the MIT license,
copyright (c) 2026 Above Vacant. The
[license](../../osservio-runtime/src/main/resources/META-INF/osservio/epitaph-LICENSE.txt)
is also packaged in the Runtime AAR's Java resources.

The decoder's wire primitives benefit from upstream's malformed-varint, tag,
length and subreader tests. The complete upstream tombstone decoder is not
included: it constructs raw memory, register, path, message and other report
models that Osservio does not retain. This source subset adds no runtime library
dependency and does not install a crash handler.

## Local changes

- Relocate the reader into `com.sandrox.osservio.runtime.internal.epitaph` to
  avoid collisions with a host's Epitaph dependency.
- Remove the unbounded stream-loading factory. Runtime admits the byte budget
  before invoking the reader.
- Make embedded messages bounded slices of the same input array. The input is
  worker-owned and immutable for the duration of decoding; reviewed results do
  not reference it.
- Add a byte-field limit checked before allocation. Only bounded hexadecimal
  ELF identifiers are converted to strings by Osservio's mapping layer.
- Retain both upstream reader test suites, adapting their grouping to the
  repository's JUnit 4 runner. Remove the two tests for the deleted stream
  factory; add Osservio-specific allocation and slice-bound tests separately.

[UPSTREAM.json](UPSTREAM.json) records original Git blob identities and local
SHA-256 hashes. [changes.patch](changes.patch) records the reader/test changes
against the pinned files, including namespace and license headers. Updates
must re-review the boundary behavior, run the retained and local tests, refresh
the provenance and diff, and verify the packaged license.

## Test fixture

The unchanged compressed
[Android 17 tombstone](../../../../testing/android-tombstone/tombstone_android17_abort.pb.gz)
comes from Epitaph's deliberate SIGABRT in a fresh, account-free emulator. It is
test data, not an SDK resource. The upstream
[provenance and reproduction instructions](https://github.com/abovevacant/epitaph/blob/660898c859055491d8a258664aee27b2555073cb/src/test/resources/README.md)
identify its producer, emulator and independent debuggerd/protoc checks.
The original report contains memory and text that the selective decoder must
discard. Tests check the faulting thread, original address precision, relative
PCs and ELF build IDs; synthetic tests cover excluded fields and corrupt input.
Reading this fixture is not a claim of device capture or API delivery coverage.
