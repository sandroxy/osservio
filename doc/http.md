# Native HTTP observation

`HTTPRecorder` admits HTTP observations supplied by a native collector or the
host. `MemoryHTTPStore` provides bounded metadata history and a separate body
budget. These APIs do not install an interceptor, create a networking client,
read a response stream, or make requests. Optional [network integrations](network-integration.md)
observe selected host clients. The native inspector provides a metadata list and explicit body lookup.

## Ownership and lifecycle

Construct a store, an `HTTPPrivacyPolicy`, and a recorder using the same
`CaptureController` as related log recorders. Retain these objects for the
desired lifetime. HTTP observations require detailed capture; baseline alone
does not admit them. At most 16 live HTTP recorders can register with one
controller. Construction reports invalid limits or contention explicitly.

`begin` returns a result and, only after successful admission and storage, an
opaque exchange token. Supply response headers once through `receiveResponse`,
then end the observation through `finish`. A response body must not be submitted
before its response headers. Unknown methods are shown as `OTHER`; invalid or
unsupported URLs are shown as `[unavailable]`. Status codes must be 100–599.

The host owns transport behavior. It must preserve original request bodies,
callbacks, cancellation, exceptions, and stream ownership. Retry, redirect, and
cache coverage depends on the collector's observation point and must be
documented by that collector. Do not claim that these APIs observe every network
stack or every wire transaction.

Each exchange has a stable identity, its capture session's initial sequence,
increasing metadata revisions, and an explicit observation source. Initial and
final response URLs use the same privacy policy. It can move from pending to receiving, then
completed, failed, cancelled, or closed early. Completion means the collector confirmed the response body finished, such as
through EOF or a networking completion callback. It does not mean that the
server accepted a business operation.
Failure categories are predefined; raw exception messages are not recorded.
Duration measures monotonic time from admission to the terminal observation.
It may include host processing time and is not a measurement of server latency.

Stopping, replacing, or expiring detailed capture invalidates its tokens,
releases recorder-owned body buffers, and attempts to mark retained exchanges
as `captureEnded`. It does not cancel host requests. An exchange also expires
after its configured observation lifetime. Expiry is lazy: it is checked by
subsequent capture operations; `maintenance` allows a viewer to trigger that
check without new traffic. There is no background cleanup timer. Idle buffers
remain bounded but are not guaranteed to be released at a wall-clock deadline.
Explicit stop or session replacement releases them during that control call.

`stopObserving` ends one SDK observation without asserting a transport outcome
or cancelling the host request. It releases that observation's buffers and
attempts to record `captureEnded`.

## Privacy

The default policy records HTTP method, sanitized URL origin, selected header
names, status, timing, and observed byte counts. It removes URL credentials and
fragments, replaces paths and query contents, redacts header values, omits
unrecognized header names, and never copies body bytes. Content type is reduced
to its media type; a valid nonnegative content length is normalized to a number.
An origin or media type can still identify a service. This is a defined field
policy, not a detector for all personal data or secrets.

The host may explicitly approve URL paths and up to 16 header names. Query
contents remain redacted. Authorization, proxy authorization, cookies,
set-cookie, and x-api-key cannot be approved. A public header's value is limited
to 512 UTF-8 bytes. Header processing examines at most 32 input entries, and
unrecognized, invalid, or truncated entries are reflected in `headersLimited`.
Header names in configuration must be static labels, not runtime data.

`publicUTF8` / `PUBLIC_UTF8` is an explicit host classification of every body
handled by that recorder as safe to retain and display. It is not automatic
redaction and does not remove JSON fields or credentials. Use the default omit
policy when that classification cannot be made. The recorder supports UTF-8
text, JSON, XML, and form media types; other formats and declared non-UTF-8
charsets are omitted. It does not decompress content. A collector may declare
response bytes already decoded only when its networking API guarantees that.

Raw prefixes exist only in bounded recorder-owned memory before finalization.
Stores receive admitted metadata and explicitly permitted UTF-8 body values,
never raw observations. Rejected, malformed, or incomplete body input is not
written to the store. Dropping references does not guarantee physical memory
zeroization or erase copies owned by the host networking stack.

## Body observation

`observeBody` takes the number of bytes the host actually observed and a copy
callback. The callback is invoked only when permitted, with exactly the maximum
prefix still needed. It must copy that many bytes from already-observed memory,
without reading, consuming, replaying, or draining a host stream. It must be
bounded, non-blocking, and non-reentrant. The recorder cannot enforce the
performance of host-supplied callbacks.

If any body callback is rejected, the collector must pass `false` for that
body's completeness when finishing. A missing chunk cannot be treated as a
contiguous prefix. Short or excessive callback results are rejected. After the
prefix budget fills, further copy callbacks are skipped while observed byte
counts continue to accumulate, saturating at the maximum signed 64-bit value.

Final body references distinguish available, truncated, omitted, unsupported,
incomplete, and invalid UTF-8. Truncation preserves scalar boundaries, though it
may split a grapheme cluster. An empty body is distinct from missing content.
An available reference does not keep its blob alive: `body` can subsequently
report unavailable after eviction or clear, or busy during contention.

## Resource budgets

| Limit | Default | Allowed configuration |
| --- | --- | --- |
| Concurrent observations per recorder | 32 | 1–256 |
| Request admission attempts per 1,000 ms window | 50 | 1–1,000 |
| Observation lifetime | 120,000 ms | 1–600,000 ms |
| Prefix bytes per body | 16,384 | 1–65,536 |
| Retained exchanges | 200 | 1–2,000 |
| Retained metadata text bytes | 262,144 | 1–16,777,216 |
| Retained body text bytes | 524,288 | 0–16,777,216 |

The admission limiter uses the same tumbling-window semantics as logging.
Attempts reaching admission consume budget even when the active limit or store
rejects them. Request and response prefixes have independent per-body limits.
The active count bounds their combined retained size; buffers, temporary copies,
object overhead, snapshots, and caller-owned data also consume memory. These
defaults have not been established as production performance guarantees.

The store evicts oldest metadata when count or text budgets require it, removing
associated blobs. Body-budget eviction can remove content while retaining its
metadata. A growing metadata update may evict other exchanges, but never its own
record merely to make room. An oversized record is rejected without eviction.
Updates cannot recreate cleared or evicted exchanges. Existing snapshots remain
immutable, and their owners are responsible for releasing them.

## Results and failures

Capture and storage writes use try-lock admission and do not queue work or
perform I/O. A busy operation is rejected instead of waiting. Results distinguish
inactive capture, contention, rate or active limits, invalid input, stale tokens,
oversized metadata, missing history, sink failure, and sequence exhaustion.
Inspect these results when completeness matters; this is best-effort diagnosis.

A failed final storage update is not retried with raw body content. A retained
record may therefore remain pending or receiving after observation ends. Such a
state means no terminal evidence was stored; it does not prove that the host
request is still running. Clearing history does not stop active host requests
or capture. As with logs, `clear` is a synchronous control operation that may
wait for the store's bounded critical section; do not call it from capture.

The current records are native in-memory types, not a serialized interchange
protocol or a persistent archive format. No network data survives termination
through these modules.

## Native inspection

The combined native inspector has independent Logs and Network sections. HTTP
controls start or end detailed capture on the supplied shared controller. Ending
detail returns to its configured baseline/off state; closing the inspector and
pausing rendering do not change capture. Clearing requests affects HTTP history
only, not the log store or host calls.

The visible list refreshes at most every 250 ms; hidden sections stop polling.
On iOS, polling also stops when its application or window scene becomes inactive.
Scrolling pauses rendering to keep rows stable. Search covers initial/final URL,
method, and status code, never body content. Filters separate awaiting-completion
entries from HTTP errors and incomplete outcomes. An HTTP 503 response can be
fully received; it is not presented as a transport failure.

Details are snapshots with an explicit refresh action. Headers and observation
provenance are available in Overview. Request/response bodies are queried only
when the user selects them, displayed as bounded selectable raw text, and copied
only on explicit action. Body policy, incomplete capture, unsupported format,
truncation, storage contention, and independent eviction remain distinguishable.
If metadata has been removed, the detail view labels its last viewed snapshot; it
does not resurrect it. Previously displayed text can remain visible until the
view is refreshed or closed.

Observation cleanup during refresh does not consume an event sequence or cancel
a host call. Expiry remains lazy, as described above. Neither the UI nor an
HTTP status code establishes business success, user intent, or complete traffic
coverage. See [network coverage](network-integration.md) for collector differences.
