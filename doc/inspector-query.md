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
includes sanitized headers; content includes request and response bodies. An
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

## Resource and evidence boundaries

Search performs body lookup on a bounded background worker, only on demand.
There is one running job and one pending job across viewers. Cancellation stops
subsequent reads and suppresses stale completion delivery. Jobs use an immutable
metadata window; they never refresh or clear the supplied histories.

A job examines at most 4,000 records and 2,097,152 UTF-16 content units. It checks
a 750 ms elapsed-work budget between records; an individual bounded lookup or
scan can complete after that check. JSON parsing keeps the reader's 65,536-unit,
4,096-node and 48-level limits. Results retain IDs and at most a 180-unit preview
per matching record, not a second body store or an unbounded search index.

Body matches identify the request, response or message content that supplied the
excerpt. Saved attachments use their immutable payload map, never a current live
history, even when a saved body is absent. This preserves the meaning of a frozen
snapshot after live records have changed or been evicted.

Missing, omitted, busy, truncated and uninspectable content, and exhausted query
budgets, must be reported as incomplete coverage. A result count is not a claim
about all historical application activity. Resource limits are safeguards, not
physical-device performance measurements.

An exclusion condition requires complete searchable content. A record with missing
or truncated content is not returned as proof that the excluded text is absent;
the partial-coverage notice remains visible.
