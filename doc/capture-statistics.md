# Capture statistics

Each `CaptureController` owns fixed counters for logs, HTTP observations,
WebSocket observations and runtime breadcrumbs. Read `statistics.snapshot()`
directly or open a controller's status in the inspector's Capture settings.
The page reads a snapshot on entry, without a timer or capture subscription.

Counters start when the controller is created. Clearing a history or starting
a new capture session does not reset them. Account-scoped owners should create
a new controller when replacing their other capture and storage resources.
Statistics contain no content, identifiers, URLs or dynamic labels.

## Interpretation

Counts describe **recorder operations**, not unique records, HTTP requests or
durable writes. For example, beginning an HTTP request, observing a body chunk
and receiving a response are separate operations. Automatic HTTP observation
endings are also counted; polling `maintenance()` without expiring an exchange
is not. Capture-control and status-read operations are not counted.

| Outcome | Meaning |
| --- | --- |
| Accepted | The recorder accepted the operation; it may only update an in-memory observation. |
| Inactive | Required capture was not enabled. This is expected control behavior, not storage loss. |
| Busy | Capture or its sink could not admit the operation without waiting. |
| Rate limited | The applicable per-second budget was exhausted. |
| Active limit | The HTTP exchange or WebSocket connection budget was exhausted. |
| Oversized | A sink's item capacity could not accommodate the observation. |
| Protected capacity | Recent error retention left insufficient eligible log capacity. |
| Invalid | The recorder rejected malformed input or an invalid observation transition. |
| Missing | A storage update could not find its original record, for example after eviction. |
| Stale | A callback no longer belonged to an active observation/session. |
| Sink failure | The sink failed to accept the operation. |
| Sequence exhausted | No further sequence number was available in the session. |

These counters do not replace history eviction counts, payload truncation or
omission markers, collector-specific coverage, or the existing asynchronous
persistence status. An accepted operation is not proof of a disk write or of
surviving termination. A host-selected omission is not a failed capture.
Observations never submitted to a recorder cannot appear in these counters.

## Bounds and concurrency

Four sources and twelve outcomes bound storage independently of traffic volume.
An update uses at most four atomic compare-and-swap attempts; it does not wait
for the capture lock, enqueue work, allocate payloads or recursively log.
The iOS implementation uses a small internal C11 target with compile-time
lock-free requirements. Android uses fixed atomic counters. Neither adds a
third-party runtime dependency.

Counters saturate instead of wrapping. `isIncomplete` indicates saturation,
skipped bookkeeping under contention, or inability to allocate the iOS counter
block. Values then represent lower bounds. Snapshot counters are read
independently rather than as one transaction, even when `isIncomplete` is false.
Do not derive a transactional acceptance ratio from a snapshot while capture
is active. The accounting is diagnostic evidence, not a delivery guarantee.
