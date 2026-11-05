# Native runtime context

Core provides compact breadcrumbs and typed environment snapshots. The optional
`OsservioRuntime` / `osservio-runtime` modules supply explicit native lifecycle
observers and one-shot device/application queries. They add no permissions,
startup provider, view inspection, or framework dependency. Native viewers query
these contracts through Core; breadcrumb storage is currently memory-only.

## Breadcrumbs

`BreadcrumbRecorder` uses the same `CaptureController` as logs and networking.
It accepts observations in baseline and detailed modes and rejects them when
off. `setBaselineEnabled` controls the fallback without interrupting a running
detailed session; `CaptureStatus.baselineEnabled` exposes that setting even
during detail capture. Shared session identity and sequence establish admission
order, not causality. Events retain wall time and elapsed monotonic time within
their capture session, not device boot time. An invalid clock ordering leaves
the elapsed value unavailable.

Kinds identify host markers, observed application/activity lifecycle callbacks,
memory signals, thermal changes, and host-supplied responsiveness observations.
Labels are static ASCII identifiers, at most 64 bytes, using letters, digits,
periods, underscores, and hyphens. They are not URL, account, or user-data fields.
Optional text uses `LogMessage` classification: private input is replaced by
`[private]`; public input is explicit approval, not automatic secret removal.
Optional integer values are measured quantities or platform-defined codes whose
meaning depends on the kind and label.

| Budget | Default | Configurable range |
| --- | --- | --- |
| Message prefix | 1,024 UTF-8 bytes | 9–4,096 |
| Observations per second | 20 | 1–1,000 |
| Memory history count | 200 | 1–2,000 |
| Retained label/message bytes | 65,536 | 1–1,048,576 |

Admission precedes text inspection. Capture uses try-locks and never schedules
an unbounded queue. Rate rejection, invalid labels/messages, contention, oversized
events, and sink failures are explicit results. The rate-limited count is not a
complete measure of all losses. Memory snapshots are immutable and clearing
does not stop observation. These budgets are not measured production guarantees.

Each event carries `memoryOnly` or `restartRelevant` retention intent. The default
is memory-only; platform lifecycle observers request restart relevance. The
current `MemoryBreadcrumbStore` persists **neither** kind. An accepted event is
not proof of a durable write or of evidence surviving a crash.

## Native signals

Construct `RuntimeSignals` with the host's breadcrumb recorder. Call `start` and
`stop` on the main thread/actor. Repeated controls are idempotent. Stopping
unregisters observers; callbacks belonging to the old registration cannot enter
a restarted registration. A previously submitted event can finish in its sink.
Starting observation does not invent callbacks that occurred earlier.

Android also takes the host `Application`. It records started, resumed, paused,
stopped, and destroyed activity callbacks with the public compiled class name.
It never reads saved-state bundles, view hierarchies, intents, or page input.
Activity callbacks are not reconstructed into a process-wide foreground state.
Memory-trim callbacks retain the native integer level; a trim notification does
not prove an out-of-memory condition. Android no longer emits several memory
pressure levels starting with API 34; see the [platform callback
contract](https://developer.android.com/reference/android/content/ComponentCallbacks2).
The obsolete `onLowMemory` callback is intentionally unused.

iOS observes UIKit application active/inactive/foreground/background and memory
warning notifications, plus ProcessInfo thermal-state changes. These are
application notifications, not per-scene navigation or layout evidence. The SDK
does not detect a generic white screen, infer a crash from a warning, or install
a fatal handler through this module. Hosts can supply meaningful readiness or
operation markers without exposing user values.

Observers remain installed until explicitly stopped (or deallocated on iOS).
Capture off rejects their observations; it does not unregister the observers.
Android hosts must stop a `RuntimeSignals` instance when releasing its ownership.

## Environment queries

`RuntimeEnvironment` implements Core's `EnvironmentProvider`. Call `read` for
one snapshot. It admits at most one pending query, returning busy for another.
Package/system queries execute in background work; iOS battery access finishes
on the main queue. Completions can arrive on any thread, including immediately
on rejection. Update native UI on its required thread. On Android, call `close`
when releasing the provider; an already admitted read still completes.

The snapshot contains typed public facts: OS version, app identifier/version/
build, manufacturer, reported hardware model, process architecture and ID,
physical memory, available process-memory measurements, battery percentage,
power-saving mode, and thermal state. It does not read user-assigned device names,
advertising/vendor identifiers, account data, network addresses, or storage
contents. No battery monitoring or permissions are enabled for the query. iOS
battery data is unavailable unless the host already enabled monitoring.

Memory measurements retain their native meaning. Android exposes proportional
set size (PSS) and managed runtime heap usage/limit; iOS exposes resident size and
physical footprint. They are separate fields, not interchangeable values. These
are point-in-time readings, not an atomic process-wide measurement or an estimate
of how much more memory the app can safely allocate. Simulator hardware facts
describe what that runtime reports, which may differ from a physical device.

Each of the 17 keys has an explicit typed value, unavailable state, or unsupported
state. Environment capture validates types and nonnegative numeric ranges;
battery percentage is 0–100. Text is capped at 256 UTF-8 bytes with a truncation
flag. Missing data never silently becomes zero or an empty success. The public
snapshot factory accepts only facts the host classifies as public system/app
metadata, not arbitrary diagnostic context. Integers retain 64-bit precision.

Environment reads are deliberate local queries and remain usable when event
capture is off. They do not enter a history or create an archive by themselves.

## Native inspection

`RuntimeInspectorView` (Android) and `RuntimeInspectorViewController` (iOS)
provide Environment and Breadcrumbs sections. Supply the environment provider,
breadcrumb recorder, and history explicitly, or pass them to the combined
inspector to add its Context tab. UI imports Core only; it neither creates
collectors nor takes ownership of their shutdown.

Environment is read once when first shown and on explicit refresh. Copy exports
only the displayed sanitized snapshot to the local clipboard. Read errors keep
the prior snapshot visibly dated; unsupported and unavailable values remain
distinct. The viewer never polls process memory in the background.

The breadcrumb timeline refreshes at 250 ms while visible. Scrolling pauses
rendering without changing capture. Search matches kind, static label, and
sanitized message. Details preserve the opened event snapshot, with session,
sequence, elapsed time, native value, privacy/truncation, and retention intent.
Clearing affects only breadcrumb history. Baseline controls explicitly describe
their fallback behavior; ending detailed capture is a separate control on the
log or network tabs. No control or retention label claims a durable write.
