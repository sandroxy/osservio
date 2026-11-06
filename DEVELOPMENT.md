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
upgrades existing incident databases without replacing reports. The immutable problem browser exposes
these groups, metadata search, and saved body states without querying live history.
The composer freezes metadata at the mark and optionally copies captured network
content once, preserving that copy through editing and failed-save retry.
Optional Export modules encode a frozen snapshot into portable metadata and
payload JSON documents and prepare temporary ZIP files on a bounded worker.
Native saved-snapshot views offer system sharing when the host supplies an
exporter, with cancellable preparation and stale-callback rejection.
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
- [System diagnostics](doc/system-diagnostics.md) describes bounded OS history
  reads, MetricKit subscription, and explicit incident persistence, without fatal
  handlers or automatic recovery.
- [Native signal evidence](doc/crash-evidence.md) defines the reviewed address-stack
  model, safe-launch decoder, archive, native browsing and portable export, plus
  optional iOS recorder installation and independent pending-file recovery.
- [Breadcrumb storage](doc/breadcrumb-storage.md) defines persistence ownership,
  budgets, recovery, and the private database revision.
- [Manual problem snapshots](doc/incidents.md) defines explicit attachment
  selection, incident persistence, privacy, and failure semantics.
- [Incident documents](protocol/README.md) defines the portable export encoding,
  independent compatibility revision, and conformance rules.
- [Incident file export](doc/incident-export.md) defines asynchronous preparation,
  cancellation, cache retention, and file ownership.
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
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES SWIFT_SUPPRESS_WARNINGS=NO build
xcodebuild -scheme OsservioURLSession -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-network CODE_SIGNING_ALLOWED=NO \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES SWIFT_SUPPRESS_WARNINGS=NO build
xcodebuild -scheme OsservioRuntime -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath .build/ios-runtime CODE_SIGNING_ALLOWED=NO \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES SWIFT_SUPPRESS_WARNINGS=NO build
```

`swift test` exercises Core and Store on the development Mac. The UIKit source
is conditionally excluded there, so the iOS build is a separate required check.
`SWIFT_SUPPRESS_WARNINGS=NO` keeps warnings enabled in Xcode package dependencies
as well as local targets. It also avoids Xcode combining suppression with
`-warnings-as-errors`; no warning is waived.
The simulator build compiles UI and resources without launching an application.
Device behavior and supported-OS coverage require separate acceptance.

The internal crash backend has separate
[non-fatal package and isolated-process checks](testing/crash-backend/README.md).
Run the iOS package test scheme below with
`-only-testing:OsservioCrashBackendTests` when changing its C/Objective-C support,
vendored recording profile, or decoding behavior. Use
`GCC_TREAT_WARNINGS_AS_ERRORS=YES` alongside the Swift warning settings. These
backend targets remain private to the optional `OsservioCrash` product. Package
tests do not install a handler in their shared test runner; fatal installation
and recovery use separate child-process fixtures and explicit consumer scenarios.
Include `-only-testing:OsservioCrashTests` and
`-only-testing:OsservioCoreTests/CapturePermissionTests` when changing its
Core-to-native permission lifecycle.

Run Runtime's UIKit notification ownership and system-query tests on an available
iOS simulator using the package's test scheme (replace the simulator ID):

```sh
xcodebuild -scheme Osservio-Package \
  -destination 'platform=iOS Simulator,id=SIMULATOR_ID,arch=arm64' \
  -derivedDataPath .build/ios-runtime -only-testing:OsservioRuntimeTests \
  CODE_SIGNING_ALLOWED=NO SWIFT_TREAT_WARNINGS_AS_ERRORS=YES SWIFT_SUPPRESS_WARNINGS=NO test
```

The product-specific `OsservioRuntime` scheme is a build scheme, not a test
scheme. Runtime tests on macOS cover callback leases and diagnostic query
admission/close/reentry; UIKit and MetricKit checks compile
and execute exclusively in an iOS test run.
Core's `SystemDiagnosticRecoveryTests` separately exercises capture-gated history
and delivery admission, stale query rejection, close during read/save, typed
failures, and reentry. Run it on iOS with the same scheme and
`-only-testing:OsservioCoreTests/SystemDiagnosticRecoveryTests` when changing
recovery lifecycle behavior.

Use the same package test scheme with `-only-testing:OsservioUITests` and
`-derivedDataPath .build/ios-ui` for composer regressions. These checks exercise
delayed environment delivery, input preservation, optional attachments, and
original mark time. A running consumer separately verifies actual event routing
and navigation; controller tests do not replace visual acceptance.
System-diagnostic view tests cover immutable navigation, distinct report/read
times, historical metadata, exact codes, missing memory, hang duration, and
copy-text formatting. System clipboard delivery requires an application host.

Run the SQLite store tests on the simulator with the same package test scheme,
using `-only-testing:OsservioCoreTests/SQLiteConnectionTests`,
`-only-testing:OsservioCoreTests/BreadcrumbDatabaseTests`, and
`-only-testing:OsservioCoreTests/SQLiteBreadcrumbStoreTests`. Include
`-only-testing:OsservioCoreTests/IncidentDatabaseTests`,
`-only-testing:OsservioCoreTests/IncidentDetailDatabaseTests`,
`-only-testing:OsservioCoreTests/IncidentDiagnosticDatabaseTests`,
`-only-testing:OsservioCoreTests/SignalCrashDatabaseTests`,
`-only-testing:OsservioCoreTests/SQLiteIncidentStoreTests`,
`-only-testing:OsservioCoreTests/DiagnosticRecoveryDatabaseTests`, and
`-only-testing:OsservioCoreTests/SQLiteDiagnosticRecoveryTests` for snapshot
storage, repeated system-history recovery, and shared SQLite policy changes. These tests use
fresh, test-owned temporary directories and remove only their own fixtures.

For Android, install JDK 17 and Android SDK 36. Set `ANDROID_HOME` to your SDK or
use the ignored `native/android/local.properties` with `sdk.dir`. From
`native/android`:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew :osservio-store:connectedDebugAndroidTest
./gradlew :osservio-export:connectedDebugAndroidTest
./gradlew :osservio-runtime:connectedDebugAndroidTest
./gradlew :osservio-ui:connectedDebugAndroidTest
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
asynchronous admission, and close ordering. Recovery tests also cover repeated
material, multiplicity, deletion/reopen, atomic acknowledgements, source windows,
capacity selection, and corrupt acknowledgements. They do not establish physical-device
performance or fatal crash capture.
Runtime instrumentation reads the actual exit-history API without installing a
Java handler. iOS Runtime tests independently map synthetic MetricKit subclasses,
check allowlisted fields and work limits, and exercise actual history reads and
subscription lifecycle. These checks do not establish real-world crash delivery.
Export instrumentation compares Android's platform JSON encoding with shared
independent fixtures and exercises ZIP preparation, cancellation, exclusive
ownership, cache recovery, retention, and rejection. Swift's Export tests cover
the corresponding contracts; also run them with `-only-testing:OsservioExportTests`
on the iOS package test scheme. Android tests also check content-provider
containment and read-only access. UIKit share-state checks exercise cancellation,
immediate rejection, retry, appearance callbacks, and late completion; package
tests drive appearance explicitly because they have no application event
dispatcher. These checks do not establish system share-sheet behavior or delivery.
Set `ANDROID_SERIAL` to the intended device or emulator
when multiple devices are connected; do not inadvertently install test APKs on
another device.
For a target with existing test data, append
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` and
`-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false` to connected
test commands. These settings preserve installed test packages and prevent an
incompatible-install fallback from uninstalling them. They do not authorize a
data reset. Store fixtures use their own fresh directories. UI instrumentation
checks address precision, missing evidence and virtualized frame/image lists;
it does not replace visual or physical-device acceptance.
The optional OkHttp tests use loopback MockWebServer and instrumented streams.
URLSession tests use Foundation with a host-configured synthetic URLProtocol.
WebSocket tests use actual client connections to loopback MockWebServer and
Apple's Network.framework server.
Their coverage and limits are described in the network integration contract.

The OkHttp and Export modules lock their resolved development and test dependencies.
Normal checks must not rewrite the lockfile. Use Gradle's `--write-locks` only while
reviewing an intentional dependency change, then run the integration tests.
The optional iOS Export product pins ZIPFoundation with an exact dependency and
`Package.resolved`. Review its version, behavior, license, and privacy manifest
before an intentional update. It is not linked by Core, Store, UI, or collectors.
Android Export pins AndroidX Core 1.17.0 for the existing API 36 toolchain. Its
scoped `GradleDependency` notification exclusion records that Core 1.18+ requires
compileSdk 36.1; correctness lint checks remain enabled. Review the dependency's
transitive runtime and merged manifest as well as the provider code when updating.

Build outputs, local configuration, and temporary verification evidence are
ignored. The Gradle wrapper JAR is an intentional build-tool input; its provenance
and license are recorded in [third-party notices](THIRD_PARTY_NOTICES.md).

## Portable document checks

Use Python 3.10 or later in an isolated development environment:

```sh
python3 -m venv .work/protocol
.work/protocol/bin/python -m pip install -r protocol/requirements.txt
.work/protocol/bin/python -m unittest discover -s protocol -v
```

This checks the export schemas, cross-document semantics, strict writers,
tolerant readers, corruption cases, UTF-8 bounds, and transport lifecycle states.
The Python tools are not shipped in the native SDK. Native encoding is tested
separately by `swift test` and Android Export instrumentation.

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
