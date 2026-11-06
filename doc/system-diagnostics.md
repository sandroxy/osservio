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
| iOS MetricKit, iOS 14+ | Delivered crash/hang report intervals, historical metadata, numeric crash codes, hang duration | OS-controlled delivery may be delayed or absent; an interval is not an exact occurrence time |

Older OS versions return `unsupported`, not an empty success. Empty supported
history does not prove that no failure occurred. CPU exceptions, disk-write and
launch diagnostics, stack trees, native tombstones, and raw ANR traces are not
read. This is not complete fatal recording or a guarantee of next-launch recovery.

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

The provider does not subscribe automatically. These APIs are independent of
`CaptureController`; a host must bind subscription lifetime to its capture/access
policy. Automatic recovery is not connected yet. Provider batches preserve
repeated entries; identical symptoms do not receive invented stable identities.

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

Acknowledgements store a SHA-256 fingerprint of all reviewed diagnostic facts,
with the highest observed multiplicity of identical material in one batch.
Two identical entries in the first batch can produce two records; reading that
batch again produces none. A later batch with three matching entries can add
one. This is best-effort deduplication of report material, not proof of unique
real-world occurrences: separate failures can have identical available facts.
The fingerprint is private storage metadata, not a public incident identity.

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

Portable document revision 2 includes the reviewed system evidence, original
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
Source-specific explanations also state that this evidence has no stack trace.

Copy summary includes these facts and their interpretation limits. With an
exporter supplied, the existing Share action exports the same frozen snapshot.
The viewer does not implicitly associate current logs, traffic, environment, or
the latest saved breadcrumb run with an older diagnostic.

## Privacy and bounds

Only the declared numeric fields and four public metadata fields are accepted:
app version, app build, OS version, and device model. Each text value is bounded
to 256 UTF-8 bytes at scalar boundaries, with explicit truncation. Empty metadata
is absent. Restoration rejects oversized values rather than repairing them.
These fields accept public build/system metadata, not arbitrary host context.

The adapters never request free-form termination descriptions, Objective-C
exception reasons, process-state summaries, signposts, virtual-memory descriptions,
or JSON representations of raw reports. They do not read or persist stack trees
or trace streams. Only sanitized facts cross the Core port.

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
