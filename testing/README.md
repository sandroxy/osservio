# Shared native fixtures

The opt-in [sustained capture workload](capture-load.md) complements the focused
fixtures below with actual-clock admission/eviction checks and raw resource
measurements. It is separate from UI, storage, and device acceptance.

`log-text-cases.tsv` is consumed by the Swift and Java tests. Each row contains a
case name, privacy classification, message byte limit, Base64-encoded UTF-8 input,
Base64-encoded expected text, and the expected truncation flag. Empty fields
represent empty strings. All data is synthetic.

The fixtures verify private text removal, exact boundaries, and truncation at
Unicode scalar boundaries. A scalar boundary can split a grapheme cluster.
These fixtures define local native behavior; they are not a serialized event
protocol or an archive format.

`system-diagnostic-cases.tsv` defines source classification and evidence
validation. Columns are name, source family, changed field, input, and expected
kind or `invalid`. It covers exact integers, unknown Android exit reasons,
missing memory, MetricKit reporting intervals and crash/hang field combinations.
Native tests also verify bounded public metadata, strict restoration, immutable
batches, and source/budget consistency. Protocol checks consume the same source
invariants independently and also validate document-specific combinations.

`apple-signal-cases.tsv` defines address-only native crash evidence. Columns are
name, changed field, input, and `valid` or `invalid`. Both native models check
signal/code combinations, exact integer bounds, canonical identities, original
timestamp precision, stack/image budgets, missing thread/CPU fields, image ranges
and metadata exclusions. Additional tests verify immutable collections, byte
accounting and completeness flags. This is a native value contract, not a claim
of Android crash capture or an extension of the portable document protocol.

`android-tombstone-cases.tsv` defines the separate Android address-evidence
value. Both native models check architecture/signal/thread constraints, exact
addresses, bounded hexadecimal ELF build IDs, absent evidence and frame limits.
The Runtime decoder also uses the attributed real crash bytes described in
[the Epitaph vendor record](../native/android/vendor/epitaph/README.md), plus
synthetic malformed, privacy and work-budget cases. `android-tombstone-states.tsv`
checks source/read-state/evidence consistency, and `tombstone-corruption.tsv`
checks private archive restoration. `incident-v5.sql` preserves legacy signal
and exit records for additive migration tests. Both native export encoders use
the same revision-4 documents, including absent and available tombstones.
These fixture checks do not establish real Android OS delivery.

`capture-lifecycle.tsv` is a sequential scenario consumed by both native test
suites. Rows contain a case name, monotonic milliseconds, control operation,
argument, expected mode, remaining detailed-session milliseconds, and whether
the visible session identity changed. It covers baseline fallback, expiry,
replacement, idempotent continuous debug controls, and stopping all capture.
Zero remaining time for an enabled continuous session means no deadline. Native
tests separately check host defaults, configured deadlines, invalid limits,
body admission after disable and native crash permission without handler installation. Contention and shared
recorder ordering are verified separately with bounded concurrency tests.

`json-reader-cases.tsv` covers strict container syntax, exact number tokens,
duplicate members, Unicode, escapes, incomplete input and non-JSON text. Its
columns are a case name, expected result (`ok`, `invalid`, `notJSON`), and Base64
UTF-8 source. Comment lines begin with `#`. Both UI model suites consume it;
separate tests exercise input, depth and node budgets, cancellation, expansion
and source offsets. These are reader checks, not capture or redaction rules.

`http-url-cases.tsv` covers origin normalization and removal of credentials,
paths, queries, and fragments. Each row contains a name, whether paths are
approved, raw input, and the expected display URL.

`http-body-cases.tsv` covers UTF-8 boundaries, invalid encodings, empty content,
and unsupported formats. Rows contain a name, prefix byte limit, Base64 input,
content type, expected body state, and Base64 output. `-` means that no blob may
be produced; `~` means an available empty string. Both native suites
also test stale callbacks, independent metadata/body eviction, clearing,
capacity, ordering, concurrency, and sink failure.

`websocket-text-cases.tsv` contains a case name, byte limit, Base64 input,
Base64 output, and truncation flag; `~` denotes empty text. The suites also
verify that omitted text is not retained, binary data is represented by count
only, and the full size of truncated text is not invented.

`websocket-lifecycle.tsv` is a sequential observation scenario: case name,
operation, result, retained event count, and active connection count. It covers
duplicate open rejection, callback capacity gaps, close request/peer close/final
completion distinctions, and stale terminal callbacks. Native tests independently
exercise connection/rate limits, session invalidation, terminal sink rejection,
independent payload eviction, and send-result correlation after a submission has
left the history window. Invalid result outcomes and stale completions are rejected.

`breadcrumb-cases.tsv` describes label validation, privacy classification, UTF-8
prefix limits, and retention intent. Columns are name, static label, privacy,
Base64 input, byte limit, result, Base64 output (`-` for rejection, `~` for empty),
truncation, and retention. Tests also verify baseline/detail/off transitions,
shared sequence ordering, elapsed session time, rate windows, and bounded storage.
Retention intent alone does not establish persistence.

`breadcrumb-restoration.tsv` exercises the boundary for reconstructing sanitized
evidence: name, event UUID, sequence, label, Base64 message (`~` for empty),
privacy flag, truncation flag, and validity. Readers must not regenerate identity,
truncate oversized persisted messages, or accept private fields containing raw
text. This fixture describes native values, not a serialized archive format.

`environment-values.tsv` covers typed facts, explicit unavailable/unsupported
states, Unicode truncation, exact 64-bit integers, and invalid types/ranges.
Columns are name, key, fact type, Base64 input, validity, Base64 expected display
value, and truncation. Empty text uses `~`. Both platforms require every known
field to have an explicit state and preserve snapshot immutability.

`incident-note-cases.tsv` describes manual snapshot note privacy and the fixed
4,096-byte UTF-8 prefix. Columns are name, privacy, Base64 input unit, input
repetitions, Base64 expected unit, expected repetitions, Base64 expected suffix,
and truncation. `~` means empty text. Both suites also verify missing versus empty
breadcrumb context, newest-window selection, omitted versus source-evicted counts,
preserved observation times, duplicate rejection, and strict environment
restoration without silently repairing oversized values.

`evidence-restoration.tsv` exercises log, HTTP, and WebSocket restoration with
valid and invalid identities, exact sequence values, text/header bounds, URL
privacy, and incompatible observation states. Columns are name, evidence family,
field, value, and validity. `~` denotes empty text. Native tests also round-trip
actual captured URL/body/lifecycle fixtures, validate body references, reject
unpaired UTF-16 surrogates on Android, and preserve send-result correlation.

`incident-window.tsv` describes newest contiguous log-window selection. Columns
are name, comma-separated message byte counts (`-` for no events), event limit,
text-byte limit, source eviction count, expected source sequences (`-` for no
retained events), and validity. Each event has a one-byte category. Independent
network tests cover content opt-out without lookup, unavailable and busy bodies,
payload budgets, available empty text, live-history clearing, immutable snapshots,
duplicate identities, and references that do not match their supplied content.

`incident-v1.sql` is an independent synthetic revision-1 incident database fixture,
including a note, typed environment facts, and a private breadcrumb. Both native
SQLite suites execute it in fresh test-owned directories and verify additive
schema migration, unchanged identity and observation/save times, new writes,
and rollback when a schema addition fails. It is not an import format.

`incident-v2.sql` extends that fixture with independently specified revision-2
tables and a private log with an exact large sequence number. Both native engines
verify direct revision-2 to the current schema migration, preserved attachments/save time,
new diagnostic writes, and rollback of the added column and tables on failure.
`incident-v3.sql` extends those fixtures with independently specified system
evidence. Both SQLite suites verify migration to the current revision, preservation of
manual and system records, and rollback on an acknowledgement-table conflict.
`incident-v4.sql` adds an independently specified acknowledgement for the retained
MetricKit report. Signal storage tests verify that upgrading to revision 5 keeps
that acknowledgement, old report identities, save times and attachments, and
rolls back all new tables if a signal-table name conflicts.
`diagnostic-fingerprints.tsv` gives independent SHA-256 vectors for reviewed
facts, including Unicode, framing delimiters, missing fields, and exact integers.
The signal vector additionally covers report identity, frame/image order, full
unsigned addresses, paired CPU values, historical metadata and cache loss.
`diagnostic-acknowledgement-corruption.tsv` defines invalid acknowledgement rows
that both stores must reject without modifying the file, even after expiry.
Recovery tests separately cover duplicate multiplicity, deletion/reopen,
eligibility and capacity selection, atomic save/acknowledgement rollback,
bounded acknowledgements, admission counters, callback reentry, and ordered close.
Coordinator tests cover off admission, stop/restart and detailed-session expiry,
late source callbacks, bounded overlap, close during a read versus an admitted
save, typed source/storage failures, and completion reentry.
`incident-diagnostic-corruption.tsv` supplies name, source family, and SQL mutation
for strict diagnostic reads. Cases cover missing records, source/kind conflicts,
invalid numbers/text, inconsistent metadata, and byte accounting. Additional tests
cover separate manual/system retention, complete deletion, asynchronous reopening,
and rollback of retention and partial evidence when a metadata write fails.
`signal-crash-corruption.tsv` adds name/SQL mutations for normalized signal facts:
missing children, gaps/counts, source conflicts, exact values, optional CPU/UUID
fields, metadata privacy and text accounting. Both SQLite engines reject them
without repair. Tests also check image-write rollback, cascading deletion, missing
stacks, reopen and repeated recovery after deleting a report.

`incident-detail-corruption.tsv` contains a case name and a SQL mutation applied
to a valid synthetic attachment snapshot. Both SQLite suites require rejection
without changing the damaged file. Cases cover detached payloads, mismatched
counts, missing/unknown groups, privacy violations, invalid types/UTF-8, oversized
text, orphan headers, and inconsistent transport metadata. Independent positive
tests preserve empty versus absent groups/content, all payload inclusion states,
exact integers, source loss counters, optional columns, and whole-report retention.
Capacity failures also verify that pruning and partial attachment writes roll back
together. Asynchronous store tests verify committed attachments after close/reopen.

`incident-export/{absent,empty,complete,diagnostic-android,diagnostic-crash,diagnostic-hang,diagnostic-signal}.json` contains independent golden pairs
of metadata and content documents. Both native Export suites construct typed
snapshots and compare their encoded JSON values with these files. Object key
order is irrelevant. `cases.json` describes strict-writer and tolerant-reader
expectations after explicit mutations; `http-lifecycle.json` covers valid
observation states and invalid revisions. Protocol tests separately exercise
UTF-8 limits, duplicate identities, collection bounds, source/kind/time conflicts,
exact diagnostic codes, metadata bounds, signal privacy and reader extensions.
The signal protocol suite reuses the 44 model boundary cases independently of
the native encoders. These are portable revision-3 documents, separate from the
private SQLite migration fixtures.
The unchanged goldens in `incident-export/revisions/1` and `revisions/2` verify supported old reads;
old schemas reject new documents and mixed/unsupported revisions fail explicitly.
