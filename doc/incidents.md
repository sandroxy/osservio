# Manual problem snapshots

`IncidentSnapshot` captures a deliberate problem marker with optional note,
environment, breadcrumb, log, HTTP, and WebSocket attachments. `IncidentArchive` is the
Core persistence contract; Store implements it with `SQLiteIncidentStore`.
No collector, capture session, network request, or environment query starts
implicitly when a snapshot is made or saved.

This API records a manual observation. It is not a crash detector, and the
snapshot's existence does not establish a failure or its cause. All attachments
can be selected in the native composer or supplied through native APIs, then
inspected in the native browser. Optional Export modules encode a frozen snapshot
into [portable JSON documents](../protocol/README.md) and prepare
[temporary ZIP files](incident-export.md). Supplying an exporter to a native
problem view enables explicit system sharing of the saved snapshot.

## Evidence and privacy

The caller supplies already sanitized attachment values. A note
uses the same explicit `LogMessage` privacy classification as logging. Private
text becomes `[private]` before entering a snapshot. Public text is not
automatically scrubbed for secrets. Notes retain at most 4,096 UTF-8 bytes,
ending at a Unicode scalar boundary and exposing truncation.

Capture preserves attachment identities, timestamps, sequence numbers, privacy,
and truncation flags. The environment retains its own observation time; it must
not be presented as an atomic measurement at the problem's timestamp. An absent
attachment is distinct from an attached empty history.

By default, the breadcrumb attachment is the newest contiguous suffix within
200 events and 65,536 text bytes. Configurable limits are 1–2,000 events and
64–1,048,576 text bytes. Selection stops before the first event that would exceed
either limit. `omittedEventCount` describes source events excluded from this
snapshot; `sourceEvictedCount` preserves the source history's separate eviction
counter. Neither counts all possible capture losses. A manual snapshot may
include `memoryOnly` breadcrumbs because the caller is explicitly preserving
that context; it does not change automatic breadcrumb retention.

`restore` methods are strict boundaries for previously sanitized evidence, not
ways to classify raw input as safe. They reject malformed identities, duplicated
breadcrumb identities, private fields containing raw text, inconsistent flags,
oversized stored values, and invalid environment types. They preserve original
times and truncation rather than silently repairing records.

## Integration

Android:

```java
SQLiteIncidentStore incidents = new SQLiteIncidentStore(applicationContext);
IncidentSnapshot snapshot = IncidentSnapshot.capture(
    LogMessage.publicText("Layout changed after rotating the screen"),
    previouslyObservedEnvironment, breadcrumbHistory.snapshot());
incidents.save(snapshot, error -> {
    // Success is error == null. Dispatch to the main thread before updating UI.
});
```

iOS:

```swift
let incidents = try SQLiteIncidentStore(directory: privateIncidentDirectory)
let snapshot = try IncidentSnapshot.capture(
    note: .publicText("Layout changed after rotating the screen"),
    environment: previouslyObservedEnvironment,
    breadcrumbs: breadcrumbHistory.snapshot())
incidents.save(snapshot) { result in
    // Success follows commit. Dispatch to the main actor before updating UI.
}
```

The examples assume an existing environment observation and a Core
`BreadcrumbHistory`. A busy live history can return no snapshot; this produces
an absent attachment, not a fabricated empty history. Query a new environment
explicitly if needed and display its actual observation time.

Keep one store for the application lifetime. Android uses
`Context.getNoBackupFilesDir()/osservio/incidents`. On iOS, provide a dedicated
app-private directory, normally below Application Support. It is excluded from
backup and uses `completeUntilFirstUserAuthentication` protection. Existing
group- or world-accessible directories are rejected. Initialization and all
filesystem operations run on a private serial background worker.

## Native problem workflow

Use `IncidentInspectorView(context, archive, environment, breadcrumbs)` on Android,
or embed `IncidentInspectorViewController(archive:environment:breadcrumbs:)` in
a navigation controller on iOS. Supply the same long-lived archive, a Core
`EnvironmentProvider`, and a Core `BreadcrumbHistory`. The full combined
inspector also accepts an optional incident archive and adds a Problems section,
using its existing log, HTTP, and WebSocket histories. For the standalone view,
supply these as the optional `logs`, `http`, and `webSockets` arguments after
`breadcrumbs`. Omitted histories remain unavailable for new marks; browsing
already saved attachments does not require live histories.

Marking a problem freezes its identity, time, and current bounded breadcrumb,
log, HTTP, and WebSocket metadata windows immediately. A separate one-shot environment query keeps its own time.
The composer waits up to five seconds for that query; an unavailable attachment
is disclosed rather than replaced with empty or zero-valued data. Unchecking
environment information permits saving without waiting. Late results cannot
alter an already saved snapshot. Neither opening the browser nor marking a
problem enables capture or flushes pending breadcrumb persistence.

The user chooses which groups to attach. Available history groups are initially
selected; unavailable groups are disclosed and disabled. Network bodies are
excluded by default. The first explicit content selection looks up only the
frozen metadata references and keeps that copy, including unavailable/busy/budget
states. Deselecting a group excludes it from the saved report; reselecting or
retrying a failed save does not query metadata or bodies again. Content may have
been evicted before that first selection. New activity is never added to a draft.
Description text is also excluded by default and requires an explicit choice. Included text is
treated as public diagnostic content, with a visible reminder that secrets are
not automatically removed. The editor rejects input beyond 4,096 UTF-8 bytes.
Cancellation writes nothing. A failed save keeps the draft for retry, and a
successful save is reported only after the archive completes its commit.

The native composer uses smaller combined budgets than the independent Core
window defaults:

| Attachment | Event limit | Sanitized text limit |
| --- | --- | --- |
| Breadcrumbs | 200 | 32 KiB |
| Logs | 200 | 16 KiB |
| HTTP metadata | 50 | 16 KiB |
| WebSocket metadata | 100 | 16 KiB |
| Selected HTTP bodies | Referenced bodies only | 12 KiB |
| Selected WebSocket text | Referenced payloads only | 12 KiB |

Together with the bounded description and environment, these fit the default
128 KiB per-snapshot text limit. They are allocation limits, not performance
guarantees. A host archive with stricter limits can still reject a save; the UI
reports the error and preserves the draft. Use the Core API for custom budgets.

The browser reads summaries on entry or explicit refresh, with a read time and
per-instance storage status. It does not continuously poll the database. Details
are immutable: environment inspection, event search, and network details never
query live sources again. Log, HTTP, and WebSocket groups show their observation
times, retained and omitted counts, and source eviction counters. Missing groups
and attached empty windows remain distinct. Search covers log text and network
metadata, not body content. Body sections distinguish empty text from unselected,
unavailable, busy, or over-budget content, and have no live refresh control.
Copy actions use the displayed sanitized evidence. Individual
and all-snapshot deletion require a native confirmation; cancelling leaves the
archive untouched. iOS exposes individual deletion as a list-row swipe action;
Android also offers it from the snapshot detail.

## Frozen log and network windows

Core's `IncidentLogs`, `IncidentHTTP`, and `IncidentWebSockets` capture explicit,
immutable windows from their corresponding live snapshots. These types do not
start recording, query a host transport, clear history, or persist anything by
themselves. Pass them as the optional `logs`, `http`, and `webSockets` fields of
`IncidentSnapshot` to save a deliberate copy through `IncidentArchive`. Clearing
or evicting live history after capture does not alter this copy. Saving does not
perform another body lookup or enable automatic persistence of live traffic.

Each window records its supplied observation time, source event count, retained
events, omitted count, and source eviction counters. Select the newest contiguous
suffix within both count and metadata-text budgets. Selection stops before the
first event that would not fit; it does not skip that event to collect older
ones. Defaults are 200 logs, 50 HTTP exchanges, or 200 WebSocket observations,
each within 65,536 metadata-text bytes. All three accept 1–2,000 events and
64–1,048,576 metadata-text bytes. An attached empty window is different from
not having a window. Counters describe that source, not every possible capture
loss or all activity in the application.

Network content selection is independent and defaults off. Unselected content
is never queried. `IncidentHTTP.includingBodies` and
`IncidentWebSockets.includingPayloads` can create a new content-selected copy of
an existing metadata window. They preserve its events, observation time, and
source counters, and never call the history's `snapshot` method. Each explicit
API call performs a new bounded lookup; callers that need a frozen editing draft
must retain the returned copy, as the native composer does. When enabled, each referenced body is looked up once without
retry, within a default 65,536-byte payload budget (accepted range 0–262,144).
Allocation prefers newer observations; HTTP responses precede their requests.
A captured body is either included whole or excluded for budget, preserving its
original truncation state instead of truncating it again. Text stays separate
from searchable event metadata. An available empty body can fit a zero-byte
budget; it is not a missing body.

`IncidentPayload` distinguishes `available`, `notSelected`, `unavailable`, `busy`,
and `budgetExceeded`. These states describe snapshot inclusion. The original
event still describes what was observed, including omitted, binary, incomplete,
or truncated content. A body may leave live storage between metadata selection
and lookup; an unavailable result does not rewrite the event or invent text.
The selection is bounded but not an atomic read across sources or body lookups.
Missing WebSocket handshakes and send submissions are not reconstructed.

For example, attach HTTP metadata while leaving bodies unselected on Android:

```java
HTTPSnapshot liveHTTP = httpHistory.snapshot();
IncidentHTTP copiedHTTP = liveHTTP == null ? null : IncidentHTTP.capture(
    liveHTTP, LogClock.SYSTEM.wallTimeMillis(), httpHistory, false);
IncidentSnapshot snapshot = IncidentSnapshot.capture(null, null, null, null, copiedHTTP, null);
```

The equivalent on iOS is:

```swift
let copiedHTTP = try httpHistory.snapshot().map {
    try IncidentHTTP.capture($0, observedAtMillis: SystemLogClock().wallTimeMillis(), history: httpHistory)
}
let snapshot = try IncidentSnapshot.capture(http: copiedHTTP)
```

Both examples use an existing Core `HTTPHistory`; call `save` explicitly as
shown above. Choose attachment budgets together: the sum must fit the archive's
per-snapshot and pending-text limits. The independent default window budgets can
exceed the default 131,072-byte snapshot limit when combined. The store rejects
an oversized save; it does not silently discard selected attachments.

`restore` methods on log, HTTP, and WebSocket evidence validate existing
identities, sequence/revision constraints, state relationships, field bounds,
privacy markers, and body references. They preserve timestamps and counters,
reject oversized text rather than repairing it, and require referenced payload
entries to match the selected metadata. They are for already sanitized evidence;
they do not establish that arbitrary public text or custom headers are safe.

## Operations and lifecycle

- `save` completes successfully only after a transaction commits. Saving an
  identity that is already retained fails with `invalidRecord`; it does not
  overwrite the original. The same identity can be saved after explicit deletion
  or retention eviction.
- `readIncidents` returns newest-saved summaries without loading event or body
  content. Optional log, HTTP, and WebSocket counts distinguish absent groups
  from attached empty windows.
  `readIncident` loads a validated immutable snapshot on demand. A successful
  absent result means the requested identity is no longer retained.
- `deleteIncident` and `deleteAllIncidents` delete only this archive, including
  its copied attachments. They leave live histories, breadcrumb-run persistence,
  and capture policy unchanged. Deleting an absent identity succeeds.
- Concrete stores expose asynchronous `close`. It stops admission, finishes
  accepted operations in order, and releases the database lease. Close before
  replacing a store; a competing owner fails with `busy`. A second close after
  completion succeeds, while close already in progress reports `busy`.

Every completion may run on any thread or queue, including synchronously for
rejection. Admission permits are returned before calling host code, permitting
callback reentry. Do not block a callback: it occupies the storage worker until
it returns. Host callbacks are invoked once and never retried by the SDK.
Closing or hiding an inspector is unrelated to storage lifetime.

`persistenceStatus` is a nonblocking best-effort snapshot and may be unavailable
during lock contention. Its queued, committed, discarded, and failed-batch
counters refer to manual snapshots, not attached events. They describe this
store instance. Pre-admission invalid input and lock-contention rejection are
reported through completion and are not included in these counters. Read and
delete jobs consume admission permits but are not counted as queued snapshots.

## Budgets and failure behavior

| Limit | Default | Accepted range |
| --- | --- | --- |
| Retained snapshots | 10 | 1–16 |
| Age since successful save | 7 days | 1 minute–30 days |
| Sanitized text per new snapshot | 131,072 bytes | 64–2,097,152 bytes |
| Database page allocation | 16 MiB | 1–64 MiB |
| Admitted snapshot text, including in-flight work | 524,288 bytes | 64–4,194,304 bytes |
| Admitted save/read/delete operations | 4 | Fixed |

Text budgets count note text, textual environment values, breadcrumb labels and
messages, log categories and messages, HTTP methods/URLs/headers, WebSocket
URLs/headers, and included body text. They exclude object overhead, IDs, indices,
and structural data.
Evidence types also impose independent count and field bounds. A per-snapshot
text limit applies to new saves; reducing it does not rewrite existing reports.
These defaults are implementation limits, not measured performance guarantees.

Count and age cleanup runs at open and save, not continuously. Count eviction
uses save order; age uses wall time at save. A backward wall-clock adjustment
can delay age eviction. Cleanup removes whole snapshots in a transaction. If
that transaction fails, prior committed reports remain intact, including any
reports selected for eviction. A crash before the save callback may leave either
a committed or uncommitted transaction; inspect the snapshot identity on the
next launch before deciding to retry.

The page limit bounds the database file, not all disk usage. SQLite's rollback
journal temporarily needs additional space. Before recovery, the SDK rejects a
database above its configured limit, or a rollback journal above twice that
limit plus 65,536 bytes. SQLite may exhaust pages or device storage before the
logical text budget. `storageFull` is explicit; no automatic retry follows.
The archive remains available for explicit deletion and later retry after a
transactional capacity failure. Logical deletion is not a secure erase and does
not promise file shrinkage.

Corruption, unsupported schemas, and invalid evidence read from disk disable
that store instance. Files are preserved, not silently reset or deleted. Close
and investigate the failure before constructing a replacement. Capture and live
history do not depend on this archive remaining available.

## Private database compatibility

The SQLite file is `incidents.sqlite`, with an exclusive `incidents.owner` lease,
application ID `0x4f535649`, and private schema revision `2`. Product versions
are independent of this revision. Normalized tables store snapshot metadata,
typed environment facts, copied breadcrumbs, and frozen event windows. Network
headers and payload text use separate tables; list queries do not load them.
This database is an internal format, not a portable export or interchange protocol.

The SDK uses the platform SQLite engine with rollback journals, full synchronous
commits, foreign keys, bounded cache, and zero busy wait. Only a new empty
unowned database is initialized. Unrelated databases and unsupported revisions
are refused without reset. Readers ignore additional optional columns within
the supported revision while validating every known field, type, count, and
privacy invariant. Revision `1` upgrades by adding attachment tables in one
transaction with the revision change. Existing report identities, save times,
and evidence remain unchanged, with new attachment groups absent. A failed
upgrade rolls back its schema changes; it never resets the database. A previous
SDK supporting only revision `1` refuses a revision `2` database rather than
attempting a destructive downgrade.
