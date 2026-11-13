# Inspector queries

The native query model operates on sanitized retained evidence. It does not read
host streams, replay traffic, infer business success, or recognize host-specific
protocols. This is a viewer contract, independent of the exported event revision.

## Criteria

Values within a dimension match any selection; independent dimensions intersect.
HTTP and WebSocket share one query. Selecting an HTTP-only condition selects HTTP
and removes Socket-only conditions; the reverse also applies. Selecting All
removes protocol-specific conditions while keeping text, host and time criteria.
Reset preserves the main search text and clears filter conditions.

Time means the observation time for individual records. A connection's recent
activity uses its last retained observation, not its first retained observation.
It cannot establish whether an unobserved connection is still active. Incident
time means record/recovery time, not an inferred historical failure time.

Literal text matches metadata, localized visible labels or retained content,
ignoring case. HTTP metadata
includes admitted request and response headers; Socket metadata includes observed
handshake headers. Content includes request and response bodies and messages.
An optional text scope limits both the literal term and excluded text to addresses,
headers, a request body, a response body, messages, identifiers or record metadata.
It does not change capture, load another account or query another archive.
Metadata hits retain a bounded excerpt and their source, just as body hits do;
searching only headers does not read unrelated bodies. Limited header observations
cannot establish the absence of an excluded term. An
excluded term removes a record when observed in either metadata or content.
Unavailable content cannot establish that an excluded term was absent; search
coverage must remain visible. No regular expression is executed from user input.

An optional JSON Pointer selects an exact scalar value. Paths start with `/`,
use `/` between members or array indices, and escape `~` as `~0` and `/` as `~1`.
Paths are limited to 256 UTF-16 units and 16 components. String values are
decoded; numbers, booleans and null compare their original textual spelling.
Thus `1` and `1.0` differ, and large numeric identifiers never pass through a
floating-point conversion. Duplicate object members are ambiguous. Malformed,
truncated, unsupported or over-budget JSON is not treated as a definite mismatch.
HTTP matches when one body satisfies the field condition and the text condition
(or the text was already matched by metadata).
Text scope restricts the text condition; an independent JSON condition still
examines retained bodies. For example, a request-header match can be combined
with a JSON field in its response without parsing the header as JSON.

HTTP status selection accepts exact codes from 100 through 599 and status
families in the same union. A code in an address or payload is not an exact status match.
Transport outcome remains independent of HTTP status and neither establishes
business success. Socket activity distinguishes messages and their send results
from connection events. Direction and message-result criteria do not apply to
the connection-only group. Switching activity removes incompatible kind selections;
message-only views also remove close-code conditions. Specific event kinds, close codes and connection
identities remain available for precise inspection; they are not business event
classifications.

## Resource and evidence boundaries

Search performs body lookup on a bounded background worker, only on demand.
There is one running job and one pending job across viewers. Cancellation stops
subsequent reads and suppresses stale completion delivery. Jobs use an immutable
metadata window; they never refresh or clear the supplied histories.

A job visits its entire retained metadata window in cooperative slices. It yields
after 256 records or approximately 8 ms of work, checked between records. These
are scheduling boundaries, not a limit on total search coverage; an individual
lookup or scan can finish after the time check. Each content item remains limited
to 65,536 UTF-16 units. JSON parsing also keeps the reader's 4,096-node and
48-level limits. Results retain matched positions, IDs and at most a 180-unit
preview per match, not a second body store or a persistent search index.

The first slice can publish partial matches; subsequent progress is throttled
to roughly 100 ms and coalesced into one pending UI delivery. Progress reports
records examined separately from matches. New observations do not restart an
unfinished window. While reading initial results, older matches may be appended
without changing the reading anchor. Later live-window refreshes pause when the
user scrolls away from the newest record. Hidden or detached viewers cancel work;
returning to a paused view searches the same retained metadata window. Content
already evicted by the store is reported as unavailable.

Android captures a resource configuration before scheduling work; address and
localized search preparation use that context on the worker, without accessing
Views or retaining an Activity. Changing a query or presentation generation
rejects stale deliveries on both platforms.

A connection-context page restricts its source window independently of removable
query conditions. Reset must not expand either live or saved evidence to another
connection.

Body matches identify the request, response or message content that supplied the
excerpt. Saved attachments use their immutable payload map, never a current live
history, even when a saved body is absent. This preserves the meaning of a frozen
snapshot after live records have changed or been evicted.

Missing, omitted, busy, truncated and oversized content must be reported as
incomplete content coverage, even after all records have been examined. A result count is not a claim
about all historical application activity. Resource limits are safeguards, not
physical-device performance measurements.

An exclusion condition requires complete searchable content. A record with missing
or truncated content is not returned as proof that the excluded text is absent;
the partial-coverage notice remains visible.
