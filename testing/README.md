# Shared native fixtures

`log-text-cases.tsv` is consumed by the Swift and Java tests. Each row contains a
case name, privacy classification, message byte limit, Base64-encoded UTF-8 input,
Base64-encoded expected text, and the expected truncation flag. Empty fields
represent empty strings. All data is synthetic.

The fixtures verify private text removal, exact boundaries, and truncation at
Unicode scalar boundaries. A scalar boundary can split a grapheme cluster.
These fixtures define local native behavior; they are not a serialized event
protocol or an archive format.

`capture-lifecycle.tsv` is a sequential scenario consumed by both native test
suites. Rows contain a case name, monotonic milliseconds, control operation,
argument, expected mode, remaining detailed-session milliseconds, and whether
the visible session identity changed. It covers baseline fallback, expiry,
replacement, repeated controls, and stopping all capture. Contention and shared
recorder ordering are verified separately with bounded concurrency tests.

`http-url-cases.tsv` covers origin normalization and removal of credentials,
paths, queries, and fragments. Each row contains a name, whether paths are
approved, raw input, and the expected display URL.

`http-body-cases.tsv` covers UTF-8 boundaries, invalid encodings, empty content,
and unsupported formats. Rows contain a name, prefix byte limit, Base64 input,
content type, expected body state, and Base64 output. `-` means that no blob may
be produced; `~` means an available empty string. Both native suites
also test stale callbacks, independent metadata/body eviction, clearing,
capacity, ordering, concurrency, and sink failure.
