# Development

The repository contains native Android and iOS log capture, HTTP observation,
memory storage, optional OkHttp/URLSession integration, and log inspection modules.
Current work is native only; framework adapters follow
native behavior and interaction validation. No artifacts have been published.

## Sources of truth

- [Architecture](doc/architecture.md) defines the intended runtime boundaries.
- [Logging](doc/logging.md) describes the implemented native subset and limits.
- [HTTP observation](doc/http.md) describes admission, privacy, body ownership,
  lifecycle, and storage limits. [Network coverage](doc/network-integration.md)
  describes the optional integrations and their different observation points.
- [Engineering standards](doc/engineering-standards.md) define implementation,
  documentation, verification, and third-party reuse requirements.
- The root README and its English counterpart introduce the product to consumers.
  Integration guides must describe the actual public APIs and artifacts when
  those exist.

## Repository ownership

This repository owns the SDK's implementation, public contracts, protocol
definitions and fixtures, focused tests, package metadata, and build tooling.

Consumer applications and device acceptance orchestration belong to a separate
integration repository. Consumers use public SDK entry points. Local source
development, candidate artifact acceptance, and demonstrations of released
versions must have distinct inputs and evidence. Generated hosts and copied
implementation files are not authoritative sources.

Add directories when they have maintained content and an identifiable owner.
Do not create empty targets, manifests, or runnable-looking examples for
unimplemented capabilities. Keep tools with their actual responsibility and
avoid duplicate build or verification entry points.

## Native builds and tests

Use Xcode with Swift 5.9 or later for iOS. From the repository root:

```sh
swift test -Xswiftc -warnings-as-errors
xcodebuild -scheme OsservioUI -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-sdk CODE_SIGNING_ALLOWED=NO \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES build
xcodebuild -scheme OsservioURLSession -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-network CODE_SIGNING_ALLOWED=NO \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES build
```

`swift test` exercises Core and Store on the development Mac. The UIKit source
is conditionally excluded there, so the iOS build is a separate required check.
The simulator build compiles UI and resources without launching an application.
Device behavior and supported-OS coverage require separate acceptance.

For Android, install JDK 17 and Android SDK 36. Set `ANDROID_HOME` to your SDK or
use the ignored `native/android/local.properties` with `sdk.dir`. From
`native/android`:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

The wrapper pins Gradle 8.14.5 with a distribution checksum. Android Gradle Plugin
8.12.3 matches the native consumer toolchain. Java compiler warnings and Android
lint warnings fail the build. The version-notification detector
`AndroidGradlePluginVersion` is excluded deliberately: toolchain upgrades are
validated with consumers, not performed automatically when a newer release is
advertised. All correctness checks remain enabled. The inspector's
`ViewConstructor` exclusion documents that its dependencies require programmatic
construction; it is not an XML-inflatable widget.

The Android pipeline tests are owned by Store because they exercise the concrete
store with Core. Both platforms consume [shared text fixtures](testing/README.md)
and test lifecycle, rate limits, capacity, immutable snapshots, rejection, sink
failure, and contention. The Android unit tests are JVM tests, not device UI tests.
The optional OkHttp tests use loopback MockWebServer and instrumented streams.
URLSession tests use Foundation with a host-configured synthetic URLProtocol.
Their coverage and limits are described in the network integration contract.

The OkHttp module locks its resolved development and test dependencies. Normal
checks must not rewrite the lockfile. Use Gradle's `--write-locks` only while
reviewing an intentional dependency change, then run the integration tests.

Build outputs, local configuration, and temporary verification evidence are
ignored. The Gradle wrapper JAR is an intentional build-tool input; its provenance
and license are recorded in [third-party notices](THIRD_PARTY_NOTICES.md).

## Review and acceptance

For documentation and repository configuration changes, check relative links,
required translation parity, terminology, whitespace, and ignore rules. Verify
claims against the repository state. These checks do not establish runtime
correctness.

For implementation changes, run the affected native checks and add meaningful
boundary tests when behavior changes. Add protocol conformance checks when a
serialized protocol is introduced.
Report what ran, what it proves, and what still requires device or integration
verification. Do not report an unavailable command as passing.

Consumer hosts are maintained in the integration repository and consume local
sources through its development entry point. Do not add placeholder hosts or
framework adapters here. Acceptance must cover capture versus visibility, view
pause versus capture stop, loss and truncation, long sessions, constrained-device
performance, localization, large text, keyboard and safe areas, appearance,
navigation, and host lifecycle.

Publication instructions belong in a maintained release guide when the release
workflow exists. Publication must use the same immutable artifacts that were
accepted; source-level checks alone cannot establish release readiness. A
license for original Osservio code has not been selected; third-party notices
describe only their named components.
