# Manual problem snapshots

`IncidentSnapshot` captures a deliberate problem marker with an optional note,
environment snapshot, and bounded breadcrumb context. `IncidentArchive` is the
Core persistence contract; Store implements it with `SQLiteIncidentStore`.
No collector, capture session, network request, or environment query starts
implicitly when a snapshot is made or saved.

This API records a manual observation. It is not a crash detector, and the
snapshot's existence does not establish a failure or its cause. Log, HTTP, and
WebSocket attachments, native snapshot inspection, and export are not yet part
of this API.

## Evidence and privacy

The caller supplies already sanitized environment and breadcrumb values. A note
uses the same explicit `LogMessage` privacy classification as logging. Private
text becomes `[private]` before entering a snapshot. Public text is not
automatically scrubbed for secrets. Notes retain at most 4,096 UTF-8 bytes,
ending at a Unicode scalar boundary and exposing truncation.

Capture preserves attachment identities, timestamps, sequence numbers, privacy,
and truncation flags. The environment retains its own observation time; it must
not be presented as an atomic measurement at the problem's timestamp. An absent
environment or breadcrumb attachment is distinct from an attached empty history.

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

## Operations and lifecycle

- `save` completes successfully only after a transaction commits. Saving an
  identity that is already retained fails with `invalidRecord`; it does not
  overwrite the original. The same identity can be saved after explicit deletion
  or retention eviction.
- `readIncidents` returns newest-saved summaries without loading attachments.
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

Text budgets count note text, textual environment values, and breadcrumb labels
and messages. They exclude object overhead, IDs, indices, and structural data.
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
application ID `0x4f535649`, and private schema revision `1`. Product versions
are independent of this revision. Normalized tables store snapshot metadata,
typed environment facts, and copied breadcrumbs. This database is an internal
format, not a portable export or interchange protocol.

The SDK uses the platform SQLite engine with rollback journals, full synchronous
commits, foreign keys, bounded cache, and zero busy wait. Only a new empty
unowned database is initialized. Unrelated databases and unsupported revisions
are refused without reset. Readers ignore additional optional columns within
the supported revision while validating every known field, type, count, and
privacy invariant. There is no migration from another incident revision yet.
