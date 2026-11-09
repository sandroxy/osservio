# Third-party notices

## Material Symbols

The optional Android UI module includes a small subset of Google's Material
Symbols rounded vectors under Apache 2.0, pinned to commit
`10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`. The
[vendor record](native/android/vendor/material-symbols/README.md) identifies each
source and the resource-name/tint adaptations. The
[upstream license](native/android/osservio-ui/src/main/resources/META-INF/osservio/material-symbols-LICENSE.txt)
is included in the UI AAR. No icon font or icon-library runtime is distributed.

## Epitaph

Android Runtime vendors Epitaph 0.2.0's protobuf wire reader, commit
`660898c859055491d8a258664aee27b2555073cb`, copyright (c) 2026 Above Vacant,
under the [MIT license](native/android/osservio-runtime/src/main/resources/META-INF/osservio/epitaph-LICENSE.txt).
The license is included in Runtime's Java resources. The
[vendor record](native/android/vendor/epitaph/README.md) describes namespace
isolation, bounded reads, retained regression tests, an upstream test fixture,
original source identities and local changes. The full Epitaph report model and
decoder are not distributed by Osservio. This source subset adds no runtime
dependency or crash handler.

## PLCrashReporter

The private iOS crash backend vendors PLCrashReporter 1.12.2 at commit
`0254f941c646b1ed17b243654723d0f071e990d0`, copyright Microsoft Corporation,
Plausible Labs Cooperative, Inc., and the additional authors identified in
[the retained upstream license](native/ios/Vendor/PLCrashReporter/LICENSE).
Its recording profile, namespace isolation, exact upstream hashes and local
changes are maintained in [the vendor directory](native/ios/Vendor/PLCrashReporter/README.md).
It is private to the optional `OsservioCrash` product. Linking the product does
not install a handler; the host explicitly requests installation.

Most upstream code uses the MIT-style terms in that license.
`PLCrashLogWriterEncoding.c` uses [Apache 2.0](native/ios/Vendor/PLCrashReporter/Apache-2.0.txt).
Its embedded protobuf-c runtime is updated to version 1.5.2, commit
`4719fdd7760624388c2c5b9d6759eb6a47490626`; see
[the pinned source](https://github.com/protobuf-c/protobuf-c/tree/4719fdd7760624388c2c5b9d6759eb6a47490626).
It carries
[BSD 2-Clause notices](native/ios/Vendor/PLCrashReporter/protobuf-c-LICENSE.txt),
including their own copyright statements. Original source and test notices
remain in place. The source directory also retains the upstream privacy manifest;
Osservio's support target separately declares uptime access used for capture
expiry and app-container file metadata used for pending-file retention. The
declared reasons follow [Apple's required-reason API definitions](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons).
These declarations do not replace a host application's disclosures.

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

## AndroidX Core

The optional Android Export module uses AndroidX Core 1.17.0's FileProvider,
copyright the Android Open Source Project and its contributors, under the Apache
License, Version 2.0. See [the license text](licenses/AndroidX-Core-Apache-2.0.txt),
[the exact upstream source artifact](https://dl.google.com/dl/android/maven2/androidx/core/core/1.17.0/core-1.17.0-sources.jar),
and [the FileProvider contract](https://developer.android.com/reference/androidx/core/content/FileProvider).
No modified or vendored upstream source is distributed here. The module AAR
declares a dependency rather than embedding AndroidX classes.

Osservio subclasses FileProvider to restrict operations to completed read-only
exports. AndroidX owns canonical path containment and URI resolution. The module
lockfile records the resolved AndroidX, Kotlin, and other transitive dependencies;
applications distributing them must retain their corresponding notices. Runtime
and manifest implications are described in [file export](doc/incident-export.md).
