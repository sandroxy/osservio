# Breadcrumb persistence

`SQLiteBreadcrumbStore` is an opt-in Store implementation for sanitized
breadcrumbs. It implements Core's `BreadcrumbSink`, `BreadcrumbHistory`, and
`BreadcrumbArchive` contracts. It keeps a live memory window and asynchronously
commits only events marked `restartRelevant`. Logs, HTTP bodies, WebSocket
payloads, and environment snapshots do not enter this database.

The implementation uses the operating system's SQLite engine, with a DELETE
rollback journal and FULL synchronization. SQLite owns atomic commit and
recovery; Osservio does not implement a crash-time database writer. See
[SQLite atomic commit](https://sqlite.org/atomiccommit.html). No fatal handler is
installed. A retained run is **not evidence that a crash occurred**.

## Ownership and integration

Create one store for the process and retain it independently of the inspector.
Android uses the application's no-backup directory, under
`osservio/breadcrumbs`; its constructor retains only the application context.

```java
SQLiteBreadcrumbStore breadcrumbs = new SQLiteBreadcrumbStore(application);
BreadcrumbRecorder breadcrumbRecorder = new BreadcrumbRecorder(
    breadcrumbs, captureController);
```

iOS takes a dedicated app-private directory URL. Resolve the location in the
host's composition root; construction performs no synchronous filesystem work.
Directory creation, backup exclusion, protection configuration, and database
opening run on the store's private worker.

```swift
let directory = applicationSupportURL.appendingPathComponent("Osservio/Breadcrumbs")
let breadcrumbs = try SQLiteBreadcrumbStore(directory: directory)
let breadcrumbRecorder = try BreadcrumbRecorder(
    sink: breadcrumbs, captureController: captureController)
```

These examples assume an existing capture controller and the Core/Store imports
from the platform integration guides. The store does not enable capture. Native
lifecycle observers already request restart relevance; host markers default to
memory-only and need an explicit retention choice.

Directories must be private to the app. An existing group/world-accessible
directory is rejected. iOS creates its directory with protection until first
user authentication and excludes it from backup; a locked device before its
first unlock can leave storage unavailable. Android uses its credential-protected
app storage. A directory lease rejects a second live store with `busy`, including
another process. The failed instance still provides live history. Do not remove
the owner file or open the database through another writer.

## Admission, durability, and shutdown

The capture path uses try-locks, bounded text, and bounded memory. It does not
wait for disk. A successful append means admission to **live history**, not a
durable commit. Persistence admission can fail independently when its queue is
full or storage is unavailable. `persistenceStatus()` exposes state, pending
event/text budgets, committed events, persistence drops, failed batches, and the
last stable error category; contention returns no status snapshot.

One worker opens the database, batches writes, answers queries, and closes
resources. Reservations include writes already in progress. At most four query
or flush operations can be pending. Callback permits are returned before host
code runs; callbacks may run on any thread, including synchronously on rejection.
Keep callbacks short and dispatch UI updates to the main thread.

`flush` is an asynchronous barrier for persistence work admitted before it. It
does not make previously discarded observations durable. `close(completion:)`
stops admission, finishes admitted operations, attempts the final batch, releases
the directory lease, and then calls its completion. Contention or a concurrent
close returns `busy`; a completed close is idempotent. Keep a process-owned
instance alive until shutdown. Android callers must close an instance before
replacing it. iOS deallocation releases resources asynchronously but does not
promise a final flush.

An abrupt termination can lose the uncommitted queue. Never flush or call any
normal store API from a fatal crash handler. Closing the inspector, disabling
capture, or clearing live history does not delete committed evidence.

## Budgets

`BreadcrumbPersistenceOptions` uses the following defaults. They are explicit
implementation limits, not measured production performance guarantees.

| Budget | Default | Configurable range |
| --- | --- | --- |
| Events per retained run and live window | 200 | 1–2,000 |
| Label/message bytes per run and live window | 65,536 | 64–1,048,576 |
| Retained runs | 4 | 1–16 |
| Prior-run age since last write | 7 days | 1 minute–30 days |
| Database page bytes | 4 MiB | 1–64 MiB |
| Pending persistence events | 64 | 1–256 |
| Pending label/message bytes | 131,072 | 64–1,048,576 |
| Batch interval | 1 second | 100 ms–5 seconds |
| Pending query/flush operations | 4 | Fixed |

Oldest events are evicted to satisfy count and text budgets. A run reports its
evicted-event count. Age and run-count cleanup execute when opening the store
and committing events; the current run is exempt from age eviction. There is no
background cleanup timer. Changing the clock can affect wall-time age decisions.
Lower per-run budgets are applied when reopening. No empty run is created until
the first batch is committed.

The database page limit is not a total filesystem quota. Rollback journals,
filesystem allocation, SQLite caches, and row/index overhead are additional.
Before recovery, the database file must fit its configured limit and the journal
must fit twice that limit plus 64 KiB. Oversized or nonregular files are refused.
SQLite may need free pages during a transaction before eviction frees older
rows; storage-full failure can therefore occur before the logical event budget.

## Read contract and local schema

`readRuns` returns committed runs newest first. `readRun(id:)` returns a bounded
immutable snapshot, or no result and no error when that run is no longer retained.
The live window is not repopulated from prior runs. A run identifies one store
instance, with its platform, process ID, recording start, last event wall time,
event count, and eviction count. It is distinct from a capture session. Multiple
capture sessions may occur within a run. This schema has no crash classification,
unclean-exit inference, or saved environment snapshot.

`breadcrumbs.sqlite` is a private storage format, not an import/export protocol.
Its SQLite `application_id` is `0x4f53564f` and `user_version` is **1**, independent
of the SDK release version. Only a new empty database with both values zero can
be initialized. Unsupported revisions and unrelated databases are refused without
resetting their contents. There is no migration from an older released schema.

| Table | Retained fields |
| --- | --- |
| `recording_runs` | Ordered ID, platform, process ID, recording start, last event time, last write time, eviction count |
| `breadcrumbs` | Ordered ID, run/session IDs, sequence, wall time, elapsed time, kind, static label, sanitized message, privacy/truncation flags, optional integer value, text-byte count |

Run/event IDs are exact UUID strings. Timestamps, sequence, values, and counters
use SQLite integers; elapsed monotonic milliseconds use canonical decimal text
to preserve each native Core model's range (nonnegative signed 64-bit on Android,
unsigned 64-bit on iOS). This database is not interchangeable between platforms.
Explicit readers tolerate unknown optional columns within revision 1, but reject
unknown kinds, malformed UTF-8, wrong types, invalid IDs or privacy flags, and
inconsistent byte counts. Restoration preserves the original IDs and times;
it never silently truncates or repairs persisted evidence.

Database initialization, validation, or write failure disables persistence for
that store instance while live recording continues. The file is preserved,
including corruption and unsupported schema failures; no automatic delete,
reset, or retry is performed. Readers surface errors instead of showing an empty
successful history. Current archive APIs provide read and flush access. Explicit archive deletion,
incident capture, and export are not implemented by this storage component.

## Native archive inspection

Pass the store as `breadcrumbArchive` to the combined inspector, or as `archive`
to the standalone runtime inspector (the final constructor argument on Android).
The Saved section queries committed runs on entry and on explicit refresh. It
does not poll disk, flush, enable capture, or reconstruct missing data. Storage
state and per-instance queued/committed/discarded counts remain visible.

Open a run for a dated immutable event snapshot with search, details, and
explicit clipboard actions. Current and prior runs are labeled separately;
no run is identified as a crash without independent evidence. A failed refresh
preserves the previous displayed snapshot with its read time and visible error.
Clear in the live Breadcrumbs section affects only memory. Hiding the archive
invalidates pending UI deliveries; the bounded storage query may still finish.
