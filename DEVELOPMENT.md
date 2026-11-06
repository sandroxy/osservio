# Development

The repository contains native Android and iOS log capture, HTTP observation,
memory storage, optional OkHttp/URLSession integration, and native log/HTTP inspection modules.
WebSocket Core and Store accept explicit host observations and optional native
client wrappers, with native connection groups, event timelines, and message details.
Core/Store also provide baseline breadcrumbs and typed environment facts. The
optional Runtime module observes native lifecycle callbacks and queries system
metadata. Native context views expose these queries. Store optionally commits
restart-relevant breadcrumbs through platform SQLite with bounded asynchronous
admission and archive queries.
Core and Store also provide explicit manual incident snapshots with independent
SQLite retention, strict restoration, asynchronous queries, and deletion.
Native problem views provide explicit attachment selection, a bounded description
editor, and an immutable snapshot browser through Core archive contracts.
Core also provides bounded frozen log/HTTP/WebSocket windows with separate body
inclusion states. Store persists these as optional incident attachments and
upgrades existing incident databases without replacing reports. Their problem-view
integration is not implemented yet.
Current work is native only; framework adapters follow
native behavior and interaction validation. No artifacts have been published.

## Sources of truth

- [Architecture](doc/architecture.md) defines the intended runtime boundaries.
- [Logging](doc/logging.md) describes the implemented native subset and limits.
- [HTTP observation](doc/http.md) describes admission, privacy, body ownership,
  lifecycle, and storage limits. [Network coverage](doc/network-integration.md)
  describes the optional integrations and their different observation points.
- [WebSocket observations](doc/websocket.md) describe bounded message evidence,
  lifecycle validation, privacy, and independent payload retention.
- [Runtime context](doc/runtime-context.md) describes baseline breadcrumbs,
  native signal ownership, environment privacy, and platform-specific metrics.
- [Breadcrumb storage](doc/breadcrumb-storage.md) defines persistence ownership,
  budgets, recovery, and the private database revision.
- [Manual problem snapshots](doc/incidents.md) defines explicit attachment
  selection, incident persistence, privacy, and failure semantics.
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
xcodebuild -scheme OsservioRuntime -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-runtime CODE_SIGNING_ALLOWED=NO \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES build
```

`swift test` exercises Core and Store on the development Mac. The UIKit source
is conditionally excluded there, so the iOS build is a separate required check.
The simulator build compiles UI and resources without launching an application.
Device behavior and supported-OS coverage require separate acceptance.

Run Runtime's UIKit notification ownership and system-query tests on an available
iOS simulator using the package's test scheme (replace the simulator ID):

```sh
xcodebuild -scheme Osservio-Package \
  -destination 'platform=iOS Simulator,id=SIMULATOR_ID,arch=arm64' \
  -derivedDataPath .build/ios-runtime -only-testing:OsservioRuntimeTests \
  CODE_SIGNING_ALLOWED=NO SWIFT_TREAT_WARNINGS_AS_ERRORS=YES test
```

The product-specific `OsservioRuntime` scheme is a build scheme, not a test
scheme. Runtime tests on macOS cover callback leases only; UIKit checks compile
and execute exclusively in an iOS test run.

Use the same package test scheme with `-only-testing:OsservioUITests` and
`-derivedDataPath .build/ios-ui` for composer regressions. These checks exercise
delayed environment delivery, input preservation, optional attachments, and
original mark time. A running consumer separately verifies actual event routing
and navigation; controller tests do not replace visual acceptance.

Run the SQLite store tests on the simulator with the same package test scheme,
using `-only-testing:OsservioCoreTests/SQLiteConnectionTests`,
`-only-testing:OsservioCoreTests/BreadcrumbDatabaseTests`, and
`-only-testing:OsservioCoreTests/SQLiteBreadcrumbStoreTests`. Include
`-only-testing:OsservioCoreTests/IncidentDatabaseTests`,
`-only-testing:OsservioCoreTests/IncidentDetailDatabaseTests`, and
`-only-testing:OsservioCoreTests/SQLiteIncidentStoreTests` for manual snapshot
storage and shared SQLite policy changes. These tests use
fresh, test-owned temporary directories and remove only their own fixtures.

For Android, install JDK 17 and Android SDK 36. Set `ANDROID_HOME` to your SDK or
use the ignored `native/android/local.properties` with `sdk.dir`. From
`native/android`:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :osservio-store:connectedDebugAndroidTest
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
Store instrumentation tests use actual Android SQLite on the selected device or
emulator, with isolated test-owned directories. They verify reopen, transactional
rollback, budgets, corruption, additive incident migration, unsupported revisions, exclusive ownership,
asynchronous admission, and close ordering. They do not establish physical-device
performance or fatal crash capture.
The optional OkHttp tests use loopback MockWebServer and instrumented streams.
URLSession tests use Foundation with a host-configured synthetic URLProtocol.
WebSocket tests use actual client connections to loopback MockWebServer and
Apple's Network.framework server.
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
