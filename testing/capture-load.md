# Sustained capture measurements

`CaptureLoadTest` (Android Store instrumentation) and `CaptureLoadTests` (Swift
Core/Store tests) provide an opt-in, reproducible in-memory workload. They are
skipped unless a duration is supplied explicitly. They do not install crash
handlers, contact a server, open an inspector, or read or delete consumer data.

Each run uses fresh stores for four sequential modes: off, baseline, detailed,
and detailed with metadata snapshots approximately every 250 ms (`queried`).
The last mode measures history queries, **not a visible UI**. No GUI rendering,
filtering, formatting, clipboard or sharing cost is included.

Each batch offers five mixed ticks followed by 10 ms idle, without catching up
after scheduler delays. A tick offers a log, breadcrumb, HTTP exchange and
WebSocket connection/message/close. The recorders use their normal rate limits.
Logs alternate public 8 KiB synthetic text and private text. Synthetic HTTP and
WebSocket content is explicitly approved with a 1 KiB prefix. URLs and secret
headers exercise redaction. This intentionally overloads admission and fills
retained windows repeatedly; the offered load is not a production traffic model.

The store budgets match on both platforms: 200 logs, 200 breadcrumbs, 100 HTTP
entries and 200 WebSocket events, each with a 64 KiB metadata text budget. HTTP
bodies have 32 KiB and WebSocket payloads have 16 KiB. Assertions check bounds,
off/baseline admission, repeated eviction, private-text/header removal, completed
HTTP exchanges, and release of WebSocket slots despite rate-limited terminals.
Concurrency, collector/transport behavior, disk retention and crash recovery
have separate functional tests.

## Run

Use 5–900 seconds **per mode**. Do not run other builds or workloads concurrently
when collecting measurements. Preserve the complete test result and log, source
commit and dirty status, OS/device identity, build configuration, and installed
artifact hashes. A source experiment is not candidate-artifact acceptance.

From `native/android`, with the intended emulator or device already selected:

```sh
ANDROID_SERIAL=DEVICE_ID ./gradlew \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.experimental.testOptions.uninstallIncompatibleApks=false \
  -Pandroid.testInstrumentationRunnerArguments.class=com.sandrox.osservio.store.CaptureLoadTest \
  -Pandroid.testInstrumentationRunnerArguments.osservio.loadSeconds=60 \
  :osservio-store:connectedDebugAndroidTest
```

The result's device log contains JSON lines tagged `OsservioLoad`. This command
uses the Debug instrumentation variant. Its results must not be relabeled as
release or physical-device performance.

From the repository root, for an available iOS simulator:

```sh
TEST_RUNNER_OSSERVIO_LOAD_SECONDS=60 xcodebuild \
  -scheme Osservio-Package -configuration Release \
  -destination 'platform=iOS Simulator,id=SIMULATOR_ID,arch=arm64' \
  -derivedDataPath .build/ios-load -only-testing:OsservioCoreTests/CaptureLoadTests \
  CODE_SIGNING_ALLOWED=NO ENABLE_TESTABILITY=YES \
  SWIFT_TREAT_WARNINGS_AS_ERRORS=YES SWIFT_SUPPRESS_WARNINGS=NO test
```

Xcode passes the prefixed variable to the test runner as
`OSSERVIO_LOAD_SECONDS`; see Apple's [Xcode 13 release notes](https://developer.apple.com/documentation/xcode-release-notes/xcode-13-release-notes).
The test console contains `OsservioLoad` JSON lines. A Mac-only check is also
available with `OSSERVIO_LOAD_SECONDS=60 swift test -c release --filter
CaptureLoadTests`; it is not an iOS runtime measurement.
`ENABLE_TESTABILITY=YES` permits the package's existing `@testable` tests to
compile while retaining Release optimization; this is a test build, not a
distribution artifact.

## Interpret

Each mode emits its elapsed time, tick count, process CPU time, outcome counts,
query count, a tick-latency histogram and maximum. Histogram bounds are inclusive
microseconds; the final bucket is overflow. A tick includes all admitted work,
the harness's result accounting and assertions. It is not the latency of a
single SDK call. CPU time includes the harness and other test-process threads.

Memory is sampled approximately once per second without forcing collection:
Android reports PSS, occupied managed heap and allocated native heap; Apple
reports resident size and physical footprint. These are different OS metrics,
not directly comparable measurements of SDK allocations. The baseline includes
test-runner/runtime overhead; sampling itself also has a cost. Samples can miss
short-lived peaks, and a flat short run does not prove absence of leaks.

Keep raw observations rather than inventing a universal pass/fail latency or
memory threshold. Before release, repeat longer sessions on constrained physical
devices in release consumers and measure visible-inspector responsiveness,
background behavior, storage growth and energy separately. Store caps and this
synthetic workload do not establish a total process-memory or energy guarantee.
