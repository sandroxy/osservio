# Shared native fixtures

`log-text-cases.tsv` is consumed by the Swift and Java tests. Each row contains a
case name, privacy classification, message byte limit, Base64-encoded UTF-8 input,
Base64-encoded expected text, and the expected truncation flag. Empty fields
represent empty strings. All data is synthetic.

The fixtures verify private text removal, exact boundaries, and truncation at
Unicode scalar boundaries. A scalar boundary can split a grapheme cluster.
These fixtures define local native behavior; they are not a serialized event
protocol or an archive format.
