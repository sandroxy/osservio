# Osservio

[简体中文](README.md)

Osservio aims to provide in-app debugging and diagnostics for applications across
platforms, from development and acceptance testing to investigating problems in
released apps and reviewing evidence after a failure.

Native Android and iOS source integrations include logs, HTTP, WebSocket,
runtime context, problem snapshots, failure-evidence recovery and native
inspection. No SDK artifacts have been published. OS coverage and prerequisites
differ between platforms; see [native capability coverage](doc/native-capabilities.md).
React Native, Uni and Flutter adapters are not implemented.

## Available now

- Explicit host logging with levels, static categories, and a choice between
  public text and private text replaced with `[private]`. Public classification
  does not automatically detect or remove sensitive data.
- Time-limited capture sessions, per-message and admission-rate limits, and
  in-memory history bounded by both event count and UTF-8 text bytes.
- An Android View and an iOS UIKit inspector organized into Logs, Network,
  Problems and App, with combined filters, layered details and on-demand content
  reading and search. Language defaults to Simplified Chinese, with English
  available; appearance defaults to Light, with Dark available. Both preferences
  are independent of the host. See [the native inspector guide](doc/inspector.md).
- Independent capture and presentation lifetimes: closing the inspector does
  not stop capture, and stopping capture does not clear history.
- HTTP observation with request states, privacy policy, and independent body
  budgets, with optional integration for host-owned OkHttp clients and URLSession
  data requests. Late responses cannot recreate cleared or evicted records.
  Native HTTP inspection includes search, state filters, metadata details, and
  lazy body lookup with explicit truncation, missing-content, and coverage notes.
- Bounded breadcrumbs and host markers work in baseline capture. Optional native
  modules observe lifecycle signals and query device, app, and memory facts on
  demand. Native context views provide snapshot refresh, breadcrumb search,
  details, copying, and separate baseline controls. Choose memory-only history or
  opt-in SQLite persistence for restart-relevant breadcrumbs. Native saved-run
  browsers provide search, details, copying, storage health, and confirmed deletion. See [runtime context](doc/runtime-context.md) and
  [breadcrumb persistence](doc/breadcrumb-storage.md).

WebSocket observation supports explicit host events and opt-in OkHttp/URLSession
wrappers, with a bounded timeline and independent text budget. Submission, queue
admission, send results, and close events remain distinct; none proves peer
receipt. Native inspectors group events by connection and provide timeline
filters and on-demand content lookup; see the
[WebSocket observation contract](doc/websocket.md).

Native APIs and the Problems view support explicit manual snapshots with optional
descriptions, environment information, bounded breadcrumbs, logs, HTTP requests,
and WebSocket activity. Saved snapshots remain browsable after restart, with
search, copying, and confirmed deletion. Marking freezes the time and recent
history windows. Network content is excluded by default; the first explicit
selection reads a bounded copy, which is reused on reselection or save retry.
Description text also requires opt-in. It is public diagnostic content and is
not automatically scrubbed for secrets. Snapshots have independent count, age,
and storage limits. See [manual problem snapshots](doc/incidents.md). Optional
Export modules encode a frozen snapshot into separate metadata and payload JSON
documents and asynchronously package them into temporary ZIP files, preserving
observation times, exact integers, and missing-content states. Preparation supports
cancellation and independent cache limits. Supplying an exporter to the native
inspector enables an explicit system share action on a saved snapshot. Cancelling
or dismissing it preserves the snapshot and capture state; see
[file export](doc/incident-export.md).

Optional Runtime APIs read historical main-process exits on Android 11+ and
delivered MetricKit crash/hang summaries on iOS 14+, with an explicit subscriber
for new iOS reports. They extract only allowlisted numeric/build/system facts,
preserving reporting intervals and missing values. Hosts can explicitly persist
them as local incidents, inspect and copy them in Problems, and share them with
the optional Export module. Manual and system records have separate count limits.
An explicit batch recovery API atomically saves eligible reports with bounded
acknowledgements to reduce duplicates from repeated reads. Matching summaries
do not prove that two reports describe the same failure.
Hosts can bind startup reads and report delivery to capture-gated recovery. Off
rejects new batches; an already admitted persistence operation may finish.
The viewer distinguishes failure time from report read time and does not relabel
signal termination or a hang as a crash. Immediate next-launch delivery is not
guaranteed. See [system diagnostics](doc/system-diagnostics.md).

On Android 12+, Runtime can also extract bounded address stacks, ELF build IDs,
signal and thread facts from system native-crash traces for retention, inspection
and export. It installs no additional signal handler. OS traces may be missing
or overwritten; unsupported, unavailable, invalid and budget-limited results
remain visible. This does not include Java exception stacks or raw ANR traces,
and resolving function names still requires matching symbols. See
[Android native-crash evidence](doc/android-tombstone.md).

The optional iOS `OsservioCrash` product supports explicit fatal-signal recorder
installation and bounded pending-file reads on a later launch. Recording follows
baseline and detailed capture independently of inspector visibility. A debugger
or conflicting handler rejects installation without preventing old reports from
being read. Original time, signal, instruction addresses and binary identities
use the incident archive, recovery deduplication, native viewer and export.
Addresses are not symbolicated. SIGKILL, watchdog and OOM termination are outside
its coverage, as is the original Objective-C exception throw stack. See
[Apple fatal-signal evidence](doc/crash-evidence.md). The Java model supports the
same evidence contract; it does not provide the corresponding native crash
recorder on Android.

The SDK does not automatically capture system logs or install crash handlers.
Global network interception and framework adapters are not implemented.
Network observation covers only explicitly integrated methods; see
[network coverage](doc/network-integration.md). Core behavior and
native interaction will be refined on Android and iOS before expanding to React
Native, Uni, and Flutter.

Source integration: [Android](native/android/README.md) ·
[iOS](native/ios/README.md) · [Logging behavior and limits](doc/logging.md) ·
[HTTP observation contract](doc/http.md).
These detailed guides are currently in English. Native builds, automated tests,
and device acceptance establish different kinds of evidence; the implementation
and default resource limits do not imply completed production performance testing.

## Intended capabilities

- **Live debugging:** inspect, filter, and search native, JavaScript, and Dart
  logs and errors; examine HTTP requests and responses, WebSocket connections,
  and messages.
- **Runtime context:** inspect the device, operating system, host app, and
  runtimes, with application lifecycle events, page markers, and significant
  operations to help explain what happened.
- **Failure investigation:** retain evidence and relevant context for crashes,
  abnormal termination, and other significant failures where the platform
  permits, so they can be reviewed after a subsequent launch.
- **Problem reports:** mark a problem manually, select diagnostic evidence to
  preserve, and export a sanitized incident archive when needed.

The intended integrations include native Android and iOS applications, React
Native, Uni, and Flutter. Each integration will document its capture coverage,
requirements, and limitations. Support claims depend on verified integration
guides.

## Capture and inspection

The host controls initialization and access to the inspector. Inspector
visibility, active capture, and data retention are managed separately.

Baseline capture is designed to collect selected runtime breadcrumbs within
explicit resource limits. A detailed capture session adds richer observations
with a time limit and resource budget. Development setups can enable these
sessions directly; released apps can also enable them on the device when needed.

Live inspection uses bounded data windows. Breadcrumbs that must survive a
restart and retained incident archives have separate retention policies. A
request or log is retained according to its diagnostic value and capture policy.
Filtered, uncaptured, or evicted data cannot be reconstructed later, and the
inspector must make these gaps visible.

On-device inspection should work offline without a desktop companion, account,
or diagnostics server. Additional capabilities such as screenshots or request
modification require separate activation and a clear explanation of their
effects.

## Design and maintenance

- [Architecture and runtime semantics](doc/architecture.md)
- [Engineering and documentation standards](doc/engineering-standards.md)
- [Development guide](DEVELOPMENT.md)
