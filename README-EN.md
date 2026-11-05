# Osservio

[简体中文](README.md)

Osservio aims to provide in-app debugging and diagnostics for applications across
platforms, from development and acceptance testing to investigating problems in
released apps and reviewing evidence after a failure.

Native Android and iOS logging and HTTP observation modules are available for
local source integration. No SDK artifacts have been published. The current
implementation covers explicit host logging, bounded in-memory history, native
log and HTTP inspection, and opt-in OkHttp and URLSession request observation. Parts of
the broader product described below remain unimplemented.

## Available now

- Explicit host logging with levels, static categories, and a choice between
  public text and private text replaced with `[private]`. Public classification
  does not automatically detect or remove sensitive data.
- Time-limited capture sessions, per-message and admission-rate limits, and
  in-memory history bounded by both event count and UTF-8 text bytes.
- An Android View and an iOS UIKit inspector with search, level filters, details,
  copy, clear, and view pause/resume, in English and Simplified Chinese.
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
  browsers provide search, details, copying, and storage health. See [runtime context](doc/runtime-context.md) and
  [breadcrumb persistence](doc/breadcrumb-storage.md).

WebSocket observation supports explicit host events and opt-in OkHttp/URLSession
wrappers, with a bounded timeline and independent text budget. Submission, queue
admission, send results, and close events remain distinct; none proves peer
receipt. Native inspectors group events by connection and provide timeline
filters and on-demand content lookup; see the
[WebSocket observation contract](doc/websocket.md).

Automatic system-log and crash capture, global network interception, incident
export, and framework adapters are not implemented.
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
