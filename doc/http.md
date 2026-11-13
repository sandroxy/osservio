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
before its response headers. Valid HTTP token methods retain their original spelling; invalid or over-limit methods become `OTHER`; invalid or
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

## Data policy

The local operator is trusted within the host's inspection scope. See
[diagnostic data policy](data-policy.md) for host processing, account ownership
and explicit output boundaries.

The default policy retains the observed URL, including query, credentials and
fragment, valid header names and values, and supported UTF-8 bodies. Nothing is
redacted just because it is named Authorization, Cookie or token. The recorder
still requires detailed capture. The initial capture state remains off.

An explicit `publicHeaderNames` set selects values (nil/null includes all valid
headers). `publicURLPath`, `publicURLQuery` and body policy allow host-selected
omission. `DiagnosticTextPolicy` supports selective URL, header and body
processing before any recorded value reaches a store. Errors, nil/null and
oversized output omit content without retaining raw fallback data.

At most 32 headers are examined. Values default to 4,096 UTF-8 bytes, configurable
from 1 to 16,384; `headersLimited` reports truncation or invalid/excess entries.
Header-name token validation and the 64-byte name budget remain independent of
privacy. Content-Type parameters and custom headers are retained, without
rewriting their values for display.

`publicUTF8` / `PUBLIC_UTF8` is the default body policy. Supported types remain
UTF-8 text, JSON, XML and forms. Unsupported media, non-UTF-8 charsets and encoded
bytes that have not been decoded remain unsupported. This is a format/ownership
boundary, not a ban on sensitive field names. The SDK never decompresses or
forces reads from a host stream.

Raw prefixes exist only in bounded recorder-owned memory before finalization.
Recorded events contain validated, host-processed values. A modified body has
`isProcessed = true`; original observed byte counts and retained text byte counts
remain distinct. Rejected or incomplete input is not recorded as complete text.
Dropping references does not guarantee physical memory zeroization or erase
copies owned by the host networking stack.

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

Live histories are memory-only. Explicit incident attachments can persist the
admitted metadata and selected bodies; portable export has its own compatibility
revision. See [manual snapshots](incidents.md).

## Native inspection

Network combines HTTP exchanges and Socket events with one search and filter
sheet. The root bug icon controls shared debugging; visibility and list reading
remain independent. Metadata and bounded body search run only while needed.
Reading older rows preserves their position; View latest applies the newest
retained window and scrolls to it. See [inspector behavior](inspector.md).

Details keep their entry-time metadata. Request and Response provide body previews,
capture/inclusion states and header previews. A long header opens an exact-value
reader with search and copy; full URL reading preserves the admitted URL rather
than its readable list projection. Body readers support source and bounded JSON
structure. Saved readers query only their saved payloads, never current traffic.
Copy is explicit. Host omission, processing, incomplete capture, unsupported
formats, truncation, contention and eviction remain distinct.

Observation cleanup does not consume a sequence or cancel a host call. Expiry
remains lazy. Neither the UI nor an HTTP status establishes business success,
user intent or complete traffic coverage. See [network coverage](network-integration.md).

## Phase observations

`finish` accepts optional `HTTPMetrics` without changing the exchange lifecycle
or adding late revisions. Metrics are frozen with the terminal observation;
there is no timer, retry, or wait for a later callback. Old records and collectors
without phase evidence keep `metrics` absent.

The immutable contract permits at most 32 phase occurrences. Each records its
phase, nonnegative signed-64-bit start offset in milliseconds and an optional
end offset no earlier than the start. Offsets share that collector's call origin.
Repeated DNS, connection and request phases remain separate, including failed
attempts; an observed end means the phase ended, not necessarily that it succeeded.
A missing end is incomplete. Millisecond rounding can produce a real observed
zero duration; it is not a substitute for absent evidence. TLS may overlap the
connection interval, and requests may overlap responses. Do not sum phases to
invent total or server time, or equate per-phase occurrence numbers across types.

Protocol (up to 32 printable ASCII bytes), cache origin and optional connection
reuse describe the **final observed response/transaction**. Missing facts remain
unknown. `limited` discloses bounded collection loss or ambiguous pairing.
The metrics contain no addresses, certificate objects, request content or raw
errors. The inspector's collapsed Timing and connection group shows these facts
on demand. Copied metadata, frozen incidents and explicit exports retain them.
Native collector differences are documented in [network coverage](network-integration.md).
