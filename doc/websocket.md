# Native WebSocket observations

Core and Store accept explicit native WebSocket observations. Optional wrappers
observe host-owned OkHttp sockets and URLSession WebSocket tasks. Native viewers
show connection groups and event timelines. No global hook is installed.
Osservio does not implement the WebSocket
protocol or replace a host's network stack. Integration and platform differences
are documented in [network coverage](network-integration.md#websocket-clients).

## Evidence and lifecycle

`WebSocketRecorder` shares a `CaptureController` with other recorders and requires
detailed capture. A successful `begin` returns an opaque token. Use a stable
UUID supplied by the host when one observed socket spans multiple capture
sessions; the default creates a new identity. `attached` records that observation
began after creation, without inventing an earlier handshake or connection state.

The history is an append-only timeline: connecting/attached, opened, messages,
send results, close requests, peer closing, closed, failure, cancellation, and
explicit end or limitation of observation. A connecting event is not proof of a completed handshake. The
observer can start after a handshake, and retained events may have missing
predecessors. The store does not reconstruct missing state transitions.

A token is rejected after a terminal observation, capture replacement, or expiry.
Stopping capture releases the recorder's connection slots but does not close any
socket or append a synthetic close. Even if a terminal event is rate-limited or
its sink rejects it, the slot is released once the operation acquires admission.
Contention can prevent the terminal operation itself; such a slot remains
bounded by connection capacity and is cleared when the capture session ends.
A visible connection without a
retained terminal event therefore has an unknown outcome. Expiry is checked
lazily on capture operations and status reads.

`incoming/received` means the collector received a complete message from its
client API. Outgoing outcomes distinguish submitted, queued, sent, rejected, and failed.
These outcomes are observations of the client API, **not acknowledgements from
the remote application**. In particular, OkHttp's send result describes queue
admission; URLSession's completion describes writing to the kernel. Native
client APIs may assemble or decompress messages before observation; these are
messages, not wire frames, packet counts, or compressed transfer sizes.

An asynchronous submission uses an explicit message UUID. Its later
`messageResult` references that ID without copying its body or keeping the
original message alive. A result may outlive the submission in history; it must
not recreate evicted content. `unobserved` reports that the wrapper lacked a
callback permit, not that sending failed. Results require the same active token;
late completions cannot enter a replacement capture session. Host-supplied IDs
must identify their actual operation; the recorder does not infer correlations.

Lifecycle validation rejects duplicate open events, incompatible fields,
invalid HTTP/close code ranges, and failures without a failure category.
Close codes are observed API evidence; reserved status values may represent a
local outcome rather than a code transmitted on the wire. `closeRequested`
describes a host API call; `closing` describes a peer close callback. A
`cancelRequested` event ends observation and does not prove that the peer
completed a close handshake. `cancelled` records cancellation whose initiator
is unknown, for example native task completion after direct session invalidation.

## Privacy and bounded work

URLs and handshake headers follow the same source sanitization rules as
[HTTP metadata](http.md). Default policy omits URL paths/query values and
sensitive header values. Raw close reasons and exception descriptions are not
accepted by the recording API. Handshake headers belong to their specific
connecting/opened event rather than being copied onto every message. A rejected
handshake can retain a sanitized response status and headers on its failure event.

Text content is omitted by default. `publicMessageText` explicitly classifies
**every text message** handled by that recorder as safe to retain. It is not a
JSON secret scrubber. Capture scans only the permitted UTF-8 prefix and preserves
scalar boundaries. Truncated text has an unknown full byte count; omitted text
is not scanned just to calculate a size. A complete captured text payload can
report its byte count. Binary observations accept only a known byte count and
never receive or copy binary data.

| Limit | Default | Configurable range |
| --- | --- | --- |
| Active observed connections per recorder | 16 | 1–64 |
| Admitted observations per second, including lifecycle | 100 | 1–1,000 |
| Text prefix per message | 4,096 UTF-8 bytes | 1–65,536 |
| Store events | 500 | 1–2,000 |
| Store metadata text | 262,144 bytes | 1–16,777,216 |
| Store payload text | 262,144 bytes | 0–16,777,216 |

Admission precedes content inspection. The rate window is anchored to the
capture session and resets after at least one second of monotonic time. The
status exposes active slots and a cumulative rate-limited count; it is not a
complete count of every possible capture failure. Call results distinguish
inactive, contention, stale token, invalid input, capacity/rate rejection, and
sink failure. A nil/null status or snapshot means contention, not stopped or
empty capture. All connected recorders share the controller's observer limit.

These are implementation budgets, not production performance guarantees. They
do not cap the host network client's own queues, complete incoming messages, or
snapshots deliberately retained by consumers.

## Storage and clearing

`MemoryWebSocketStore` accepts sanitized observations and separate immutable
payloads. Metadata and payload budgets evict independently; lookup distinguishes
available, unavailable, and busy. Payload lookup and formatting are explicit.
An old metadata snapshot may outlive its payload. Oversized metadata is rejected
without first evicting retained records. Duplicate retained event IDs are
idempotent and do not replace evidence.

Clearing this timeline does not stop observation. Later messages on a still
observed connection are **new events** and may be retained; they do not recreate
its cleared handshake. This differs from HTTP's updating request record. The
new messages retain connection identity, source, URL, session, and sequence, so
their limited context remains explicit. No WebSocket data survives process exit.

Shared Unicode and lifecycle fixtures, plus native tests for privacy, invalid
transitions, overload, stale tokens, eviction, clearing, and sink rejection,
verify this contract. Separate wrapper tests exercise native client behavior;
neither set establishes physical-device performance.

## Native inspection

`WebSocketInspectorView` on Android and `WebSocketInspectorViewController` on
iOS group the retained timeline by connection ID. A group shows matching event
count and the latest matching observation, not a live socket state. Open a group
to browse its events. Search uses sanitized URLs, identifiers, and event labels;
message bodies are not loaded or searched by the list. Filters select all
events, errors/observation gaps, or messages and send results.

Event detail separates metadata from explicit content lookup and copying. It
preserves correlation IDs, source, capture session, sequence, and observed time.
Missing submissions, omitted or truncated content, and independently evicted
payloads remain visible. Viewing a historical close code does not establish the
current state of another capture session or connection.

Visible lists refresh at most four times per second. Scrolling pauses rendering;
resuming refreshes the retained window. Detail is refreshed manually. Hiding the
viewer stops its refresh work without stopping capture. Clear removes the entire
WebSocket history, including when invoked from one connection's timeline; it
does not close host sockets. Detailed capture controls are shared with the other
recorders on the same controller. The combined inspector accepts an additional
recorder/history pair to expose the WebSocket section. Both viewers follow the
host's native theme and provide English and Simplified Chinese copy.
