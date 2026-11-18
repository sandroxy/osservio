# Native release delivery

Osservio distributes six Android Maven modules and seven Swift Package products.
The authoritative version and coordinates are in [plugin.json](../plugin.json).
Release tags use the exact product version, independently of document revisions.
No framework adapter is included in the native release contract.

## Build and freeze

Commit reviewed inputs before preparing a distribution. Use the declared Android
toolchain from [DEVELOPMENT.md](../DEVELOPMENT.md). Generate the local repository:

```sh
native/android/gradlew -p native/android writeArtifactManifest
python3 scripts/native-release.py \
  --manifest /absolute/path/to/osservio/native/android/build/repository/osservio-artifacts.json \
  --output /absolute/path/to/osservio/dist/native/rehearsal --state rehearsal
```

This creates a Maven repository ZIP, a Swift Package source ZIP, a build manifest,
SHA-256 checksums and `candidate.json`. The Swift archive contains committed
package inputs, pinned dependencies, tests, fixtures and applicable notices.
The Maven archive includes all module dependency descriptors, source archives,
Javadoc and original/third-party attribution. Freezing rejects a dirty source,
stale Android source identity, changed bytes, missing modules or missing notices.
Existing output directories are never overwritten.

Unsigned inputs remain a **rehearsal**, including successful consumer builds.
For an acceptance-eligible candidate, configure the Gradle signing plugin's GPG
command and release key in private local Gradle properties, then build with
`-PsignArtifacts=true`. Verify and freeze that repository with `--state candidate`.
Pass `--signing-fingerprint` with the full public release-key fingerprint. The
freezing command verifies every detached signature against that key with GPG; it does not
create a signing identity or grant publication authority. Keep keys, passphrases,
repository tokens and private properties out of Git. Signer ownership and the
Maven Central namespace must be confirmed before publication.

The signed Central bundle contains the same accepted Maven payload and signatures,
with repository checksums. It excludes the local source manifest and unrelated
files. Signing is required by [Maven Central](https://central.sonatype.org/publish/requirements/).
No packaging command uploads, creates a remote tag, or publishes a release.

## Consume the immutable inputs

The separate integration repository independently registers the
[release policy](../release-policy.json). Its common resolver checks the candidate
schema, repository, roles, qualifications, source identity and every artifact hash.
Use its Osservio stage/verification entry points with the absolute candidate path.
Android resolves external Maven modules without source substitution. iOS builds
the maintained consumer against the frozen Swift Package. Neither entry rebuilds
the SDK source repository or silently falls back to a development checkout.

Formal automated receipts require a clean verifier commit and a signed candidate.
Device acceptance remains separate from compilation. Record Android and iOS
device results, limitations and exact installed bytes before completing the
release receipt. A rehearsal cannot become accepted by changing a flag: rebuild,
sign and freeze valid inputs, then verify that new candidate identity.

## Publication

Publication requires separate owner authorization after review of the candidate,
automated receipts, manual device results and any unresolved acceptance gates.

- Upload the accepted signed Maven bundle to Maven Central; verify the public
  coordinates, POM, dependencies, signatures and byte identity.
- Create the exact version tag at the candidate's source commit for Swift Package
  consumers. Verify an external package consumer resolves that tag to that commit.
- Attach the frozen archives, build manifest and checksums to the matching GitHub
  Release. Publish the accepted bytes without rebuilding replacements.
- Verify public download identities before updating the integration repository's
  stable lock. Until then its unpublished state remains unchanged.

Original code is Apache-2.0. Keep [LICENSE](../LICENSE), [NOTICE](../NOTICE) and
[third-party notices](../THIRD_PARTY_NOTICES.md) with distributions. Source archive
attribution does not authorize omitting required notices from a redistributed app.
