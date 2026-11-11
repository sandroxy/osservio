# System diagnostics

The optional Runtime modules read OS-owned diagnostic history through Core's
`SystemDiagnosticProvider`. iOS also supplies an explicit MetricKit subscriber.
These APIs install no fatal signal, Mach exception, or Java exception handler.
They do not persist records automatically. Hosts can explicitly save a reviewed
record through the incident archive, inspect it in Problems, and export it through
the optional Export module.

## Sources and coverage

| Source | Evidence | Limits |
| --- | --- | --- |
| Android `ApplicationExitInfo`, API 30+ | Main process exit time, reason/status, PID, importance, last PSS/RSS samples | OS-owned bounded history; incomplete coverage is possible |
| Android native tombstone, API 31+ | Faulting-thread addresses, ELF build IDs, signal/code and optional fault address | Native exits only; separate OS trace buffer can be overwritten or unavailable |
| iOS MetricKit, iOS 14+ | Delivered crash/hang report intervals, historical metadata, numeric crash codes, hang duration | OS-controlled delivery may be delayed or absent; an interval is not an exact occurrence time |

Older OS versions return `unsupported`, not an empty success. Empty supported
history does not prove that no failure occurred. MetricKit CPU exceptions,
disk-write and launch diagnostics, MetricKit stack trees, Java exception stacks
and raw ANR traces are not read. This is not complete fatal recording or a
guarantee of next-launch recovery.

Android queries its package with a maximum of 32 records and excludes records
whose process name or package UID does not match the host's declared main process.
This avoids attributing isolated/external service exits to the main process.
Known reason codes distinguish Java crashes, native crashes, ANRs, low-memory
termination, and signalled termination. Other and unknown future codes remain
`otherExit`, preserving the original code. A signal alone does not establish an
out-of-memory kill; normal exits and user stops are not relabeled as crashes.
Zero PSS/RSS becomes unavailable. Nonzero values are the last system samples in
KiB, not memory at termination. This API supplies no historical app build; the
current build must not be substituted for it.

Native exits additionally expose a trace-read state and, when available, bounded
[Android tombstone evidence](android-tombstone.md). API 30 reports unsupported
native traces while preserving its exit facts. API 31+ reads only
`REASON_CRASH_NATIVE` streams; an ANR trace attached to another exit is never
reinterpreted as a native crash. A missing or invalid trace does not discard a
valid exit record. No raw system report enters the archive.

MetricKit keeps each diagnostic's historical metadata and the payload's reporting
interval. Numeric exception codes preserve exact signed or unsigned 64-bit
decimal values; floating-point and boolean codes are rejected. A hang establishes
unresponsiveness, not necessarily termination. No inference links these records
to a saved breadcrumb run or the latest application launch.

Platform contracts:
[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo),
[exit-history queries](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)),
[MetricKit payloads](https://developer.apple.com/documentation/metrickit/mxdiagnosticpayload),
and [MetricKit subscriptions](https://developer.apple.com/documentation/metrickit/mxmetricmanager).

## Integration and ownership

Android:

```java
import com.sandrox.osservio.runtime.SystemDiagnostics;

SystemDiagnostics diagnostics = new SystemDiagnostics(applicationContext);
diagnostics.read((batch, error) -> {
    // Inspect sanitized batch.diagnostics or the explicit error on this callback thread.
});
// When the owning component is disposed:
diagnostics.close();
```

iOS:

```swift
import OsservioRuntime

let diagnostics = SystemDiagnostics()
diagnostics.read { result in
    // Inspect sanitized, previously delivered OS evidence.
}
// When the owning component is disposed:
diagnostics.close()
```

Retain providers in the host composition root. Each admits one query on its
utility worker; a second pending query returns `busy`. `close` rejects new queries
with `closed`, while an admitted read finishes normally. Source failures return
`unavailable` without retaining or exposing exception text. Completion may run on
any thread, including the caller for immediate rejection. Dispatch to the native
UI thread before updating views.

On iOS 14+, retain `MetricDiagnosticObserver` and call `start(delivery:)` on the
main actor to receive new diagnostics. Repeated starts keep the original callback;
stop before replacing it. Stopping or releasing the observer invalidates and
removes its subscriber, without affecting other subscribers or system history.
Mapping runs on MetricKit's callback queue, with one mapping in flight per
observer. Overlapping delivery reports `busy`. An already admitted sanitized
callback may finish during stop; subsequently received payloads are ignored.

The provider does not subscribe automatically. Source lifetime is independent of
`CaptureController`; the host owns subscription/access policy. Use the capture-gated
recovery workflow below when saving reports automatically. Provider batches preserve
repeated entries; identical symptoms do not receive invented stable identities.

## Capture-gated recovery

Core's `SystemDiagnosticRecovery` combines a host-owned `CaptureController` and
`DiagnosticRecoveryArchive`. It admits one source read or delivered batch at a
time, with no waiting queue, polling, or automatic retries. Baseline and detailed
capture allow recovery; off rejects new work with `inactive` before querying a
provider. Construction installs no source and changes no capture state.

Given a configured controller and incident store, retain a coordinator in the
composition root and invoke it at the host's chosen startup point:

```swift
let recovery = SystemDiagnosticRecovery(captureController: capture, archive: incidents)
recovery.recover(from: diagnostics) { result in
    // Handle the receipt or typed recovery error on this callback queue.
}
```

```java
SystemDiagnosticRecovery recovery = new SystemDiagnosticRecovery(capture, incidents);
recovery.recover(diagnostics, (receipt, error) -> {
    // Handle the receipt or typed recovery error on this callback thread.
});
```

For new MetricKit delivery, pass each successful sanitized batch from
`MetricDiagnosticObserver.start(delivery:)` to `recovery.recover(batch, completion:)`.
Handle the observer's own delivery error separately. The host must retain the
observer and remove it when that subscription is no longer wanted. A subscriber
can remain installed while capture is off, but its deliveries are not admitted
to storage; OS delivery and bounded mapping can still run. Stopping capture
does not remove OS history or another component's subscriber.

Before sending a read result to storage, the coordinator checks the current
capture session again. Off returns `inactive`; a changed active session returns
`captureChanged`. This includes replacing detailed capture or returning to
baseline on expiry. A host can explicitly retry in the new session. A stop and
restart therefore cannot let an older pending query silently enter the archive.
No current session context is attached to historical system evidence.

The capture check is the admission boundary: a batch admitted before stop may
finish saving afterward. `close` rejects future work and completes a pending
read with `closed`; late source callbacks are ignored. An already admitted
storage operation completes normally. It does not close the provider or archive;
the host still owns both. Completions run once outside coordinator
locks and can reenter. They may run synchronously for rejection.

Recovery errors preserve whether failure came from the source or persistence.
Unsupported OS coverage is a source error, not an empty successful recovery.
Coordinator contention/overlap reports `busy`; inactive, changed-session, and
closed states remain distinct. Hosts must present receipts and failures rather
than treating every zero saved count as a crash-free run.

## Explicit persistence

Use `IncidentSnapshot.captureDiagnostic(record, observedAtMillis:)` on Swift, or
`IncidentSnapshot.captureDiagnostic(record, observedAtMillis)` on Java, then pass
the result to `IncidentArchive.save`. Supply the batch's `observedAtMillis` as the
recovery observation time. The diagnostic retains its separate original time or
reporting interval. This factory attaches no current environment, breadcrumbs,
logs, or traffic and does not infer a matching historical run.

Each call creates a new snapshot identity. Do not repeatedly save an entire OS
history this way; use the batch recovery port below for repeated reads.
Save completion confirms commitment; honor the archive's admission and text
limits when sequencing operations. `IncidentSummary.diagnosticKind` identifies
system records without loading their detailed fields. Separate manual/system
count limits prevent count-based cross-eviction, while disk and age limits remain
shared. See [persistence and migration](incidents.md).

## Bounded batch recovery

`SQLiteIncidentStore` also implements Core's `DiagnosticRecoveryArchive`.
Pass a sanitized `SystemDiagnosticBatch` to `recoverDiagnostics` to process one
history read or delivered batch. This explicit asynchronous operation installs
no source and does not change capture policy. Its completion returns a
`DiagnosticRecoveryReceipt` or the archive's explicit persistence error.

The store applies these rules in one transaction:

- Ignore `otherExit`, which includes normal and unclassified Android exits.
  Signal termination remains eligible without being classified as a crash.
- Consider only reports whose original time or interval end is within the
  archive's `maximumAgeMillis` window, ending at the storage worker's current
  wall time. Older or future-dated evidence is counted separately.
- Reject new records exceeding `textBytesPerIncident`. From the remaining new
  candidates, select up to `retainedDiagnostics`, newest interval end first.
  Insert older selected candidates first. This orders one batch; archive-wide
  retention still follows save order, including delayed system reports.
- Save selected records and acknowledge processed report material atomically.
  A failed transaction rolls back records, retention cleanup, and acknowledgements.

Acknowledgements store a SHA-256 fingerprint of reviewed diagnostic material,
with the highest observed multiplicity of identical material in one batch.
Two identical entries in the first batch can produce two records; reading that
batch again produces none. A later batch with three matching entries can add
one. This is best-effort deduplication of report material, not proof of unique
real-world occurrences: separate failures can have identical available facts.
The fingerprint is private storage metadata, not a public incident identity.

Android tombstone read states do not create new failure material. A newly
available trace may add a richer immutable report after an earlier summary.
It also acknowledges that summary, preventing later OS trace eviction from
creating a degraded duplicate. The original summary fingerprints remain
compatible; each richer trace uses an additional acknowledgement. Existing
snapshots are never rewritten, and two material reports need not mean two crashes.

At most 256 acknowledgements are retained. They expire using the archive's age
window from first processing and are evicted by insertion order. Deleting an
incident or all incidents leaves acknowledgements intact, so an immediately
repeated history read does not restore deleted evidence. Eligible candidates
excluded by the batch count limit are also acknowledged; per-record text
exclusions, ignored exits, and out-of-window entries are not. Bounded history,
acknowledgement eviction, and clock changes prevent an exactly-once guarantee.

The receipt accounts separately for saved, already processed, ignored-exit,
outside-window, and capacity-excluded entries. These sum to the input diagnostic
count. It also preserves source rejection/exclusion counts and the unknown-loss
`sourceLimitReached` flag. Saved counts confirm commitment, not completeness of
OS evidence. A successful repeated or empty batch may save zero records.

Recovery shares the archive's four-operation queue and pending text budget;
the entire sanitized batch must fit admission. A smaller configured budget can
reject a batch with `busy` even when the queue is empty. No automatic retry or
unbounded queue is created. Close finishes admitted recovery before releasing
storage. As with other archive operations, completions may run immediately on
rejection and must not block the worker. Invalid stored acknowledgement data
disables that store instance and preserves its files.

## Portable export

Portable document revision 5 includes the reviewed system evidence, original
time/range, historical metadata, exact numeric codes, and missing-value states.
It preserves recovery observation time separately and adds no current context.
See the [document contract](../protocol/README.md) and
[file export](incident-export.md) for explicit encoding and sharing.

## Native inspection

Problems distinguishes system classifications from manual marks. System rows show
the report read time explicitly; the saved record opens a System diagnostic page
on iOS or a selectable detail dialog on Android. Opening it performs no system
query, environment refresh, archive write, or change to capture.

The detail preserves Android exit time/reason/status, last memory samples, or the
MetricKit report interval, historical metadata and available crash codes or hang
duration. Missing facts remain unavailable; truncated historical text is labeled.
Signal termination is not relabeled as low-memory termination. Report intervals
are not exact occurrence times, and a hang does not prove process termination.
Native-crash trace states and address lists remain distinct from Java crash and
ANR summaries, which have no stack evidence in this provider.

Copy summary includes these facts and their interpretation limits. With an
exporter supplied, the existing Share action exports the same frozen snapshot.
The viewer does not implicitly associate current logs, traffic, environment, or
the latest saved breadcrumb run with an older diagnostic.

## Privacy and bounds

In addition to the reviewed tombstone addresses and ELF identifiers, the
declared numeric fields and four public metadata fields are accepted:
app version, app build, OS version, and device model. Each text value is bounded
to 256 UTF-8 bytes at scalar boundaries, with explicit truncation. Empty metadata
is absent. Restoration rejects oversized values rather than repairing them.
These fields accept public build/system metadata, not arbitrary host context.

The adapters never request free-form termination descriptions, Objective-C
exception reasons, process-state summaries, signposts, virtual-memory descriptions,
or JSON representations of raw reports. Android reads native tombstone streams
within the separate byte/traversal limits, retaining only reviewed fields;
raw memory, paths, text and registers are discarded. MetricKit stack trees and
non-native Android trace streams are not read. Only sanitized facts cross the
Core port.

A batch contains at most 32 inspected records, including rejected or excluded
records. iOS visits at most four payloads, considering crashes before hangs within
each payload. Source order is preserved without assuming undocumented chronology.
`rejectedCount` counts malformed inspected records; `excludedCount` counts Android
records outside the main-process scope. `sourceLimitReached` means a budget was
reached, not that a known number of failures was lost. OS buffers can omit records
independently. OS-owned array creation and retention are outside SDK control;
the SDK does not serialize or enqueue raw payloads.

These are admission bounds, not measured memory or latency guarantees. Shared
fixtures cover classifications, invalid combinations, exact integers, and strict
restoration. Runtime tests cover query admission/close/reentry, platform reads,
allowlisted mapping, and invalidated subscribers. Synthetic mapping tests do not
establish real-world MetricKit delivery or crash recovery.
