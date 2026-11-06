# Third-party notices

## Gradle Wrapper

The wrapper scripts and `gradle-wrapper.jar` under `native/android/` are generated
by Gradle 8.14.5 and distributed under the Apache License, Version 2.0. Original
script headers are preserved. See [the license text](licenses/Gradle-Apache-2.0.txt)
and [upstream source](https://github.com/gradle/gradle/tree/v8.14.5).

The Gradle distribution is downloaded separately with a pinned SHA-256 checksum.
It is build tooling and is not included in the SDK libraries. This notice does
not select a license for Osservio's original source.

## OkHttp and MockWebServer

The optional Android adapter compiles against OkHttp 5.5.1, copyright Square,
Inc., under the Apache License, Version 2.0. Tests use MockWebServer 3 from the
same release. See [the license text](licenses/OkHttp-Apache-2.0.txt) and
[the pinned upstream source](https://github.com/lysine-dev/okhttp/tree/be5151ac3b63d3db405b9dbb62632c36cc6a657e).

Osservio does not vendor OkHttp source or embed its runtime classes in the
adapter's AAR. The host supplies its networking dependency. The module's Gradle
lockfile records the resolved development and test dependencies, including Okio
and the Kotlin standard library. Applications distributing those runtimes must
retain their corresponding notices. No upstream source modifications are
distributed by this adapter.

## ZIPFoundation

The optional iOS Export module uses ZIPFoundation 0.9.20, copyright 2017–2025
Thomas Zoechling, under the MIT License. The exact version and commit
`22787ffb59de99e5dc1fbfe80b19c97a904ad48d` are recorded in `Package.swift` and
`Package.resolved`. See [the license text](licenses/ZIPFoundation-MIT.txt) and
[the pinned upstream source](https://github.com/weichsel/ZIPFoundation/tree/22787ffb59de99e5dc1fbfe80b19c97a904ad48d).
No modified or vendored upstream source is distributed here. Applications linking
the Export product must retain the dependency's license and bundled privacy
manifest. That manifest declares file-timestamp access, without tracking or
collected-data categories; it does not replace the application's own disclosures.

ZIPFoundation uses Apple's platform compression implementation on iOS. Osservio
uses its bounded data-provider and extraction APIs for two fixed entries, with
cancellation checks and readback verification before exposing an export. Other
Osservio products do not link ZIPFoundation. Android Export uses the platform
`java.util.zip` implementation instead.
