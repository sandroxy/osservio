# Incident documents

`OsservioExport` on iOS and `osservio-export` on Android encode one immutable,
sanitized `IncidentSnapshot` into two UTF-8 JSON documents:

| Document | Content |
| --- | --- |
| `incident.json` | Identity, mark time, optional attachments, event metadata, loss counters, and body inclusion states |
| `payloads.json` | Available copied HTTP and WebSocket text, keyed by the referenced body identity |

The format identifiers are `osservio.incident` and `osservio.incident.payloads`.
Both declare compatibility revision `1`, independently of SDK and private
SQLite schema versions. The metadata's `payloadFile` is the fixed literal
`payloads.json`, not an arbitrary path. The schema URNs identify local definitions;
validation does not fetch a remote schema.

Encoding performs no history lookup, collection, storage query, file write, or
transport. It never includes raw observations or reads the host's database.
These APIs produce documents. The separate asynchronous
[file exporter](../doc/incident-export.md) packages them into a temporary ZIP.
Native inspectors can use that exporter for explicit system sharing. Importing
documents into Osservio is not implemented.

## Integration

Link the optional export module and invoke encoding on a background worker,
using a previously obtained snapshot:

```swift
import OsservioExport

let documents = try IncidentDocuments.encode(snapshot)
// documents.metadataJSON and documents.payloadsJSON are UTF-8 Data.
```

```java
import com.sandrox.osservio.export.IncidentDocuments;

IncidentDocuments documents = IncidentDocuments.encode(snapshot);
// metadataJSON and payloadsJSON are immutable JSON strings. Encode as UTF-8 for I/O.
```

The caller owns the result and any subsequent destination or transport. Encoding
is synchronous and must not run in capture hooks, fatal handlers, or on the UI
thread. Swift reports `IncidentDocumentError.tooLarge` or `invalidRecord`;
Android reports `IllegalArgumentException`. Invalid or oversized output is
rejected instead of silently dropping attachments. Core's public snapshot and
restoration constructors enforce the input's typed evidence invariants.

## Values and evidence

- UUID strings retain their original spelling. Compare identities without
  case sensitivity; body map keys and references use the same exact spelling.
- Signed 64-bit timestamps, sequence numbers, source eviction counters, byte
  observations, environment numbers, and elapsed durations use canonical decimal
  **strings**. JSON numbers cannot preserve every value in common JavaScript
  readers. Do not convert these strings through floating-point numbers.
- Wall times are Unix epoch milliseconds and can be negative. Each group's
  `observedAtMillis` belongs to that query; environment time belongs to its
  separate observation. The mark is not an atomic measurement of every group.
- `sessionElapsedMillis` and `durationMillis` are monotonic elapsed intervals,
  not absolute timestamps. The portable format permits unsigned 64-bit elapsed
  values; Android's native values are limited to nonnegative signed 64-bit.
  Event sequence numbers provide ordering only in their originating session.
- Bounded collection counts, retained UTF-8 byte totals, HTTP status codes, and
  close codes remain JSON integers. The schemas define their finite ranges.
- `null` means an attachment or optional fact is absent. An attached group with
  zero events is present and empty. Environment `unavailable` and `unsupported`
  remain different states. Required keys are never omitted to represent absence.
- Body observation state and incident inclusion state describe different facts.
  A captured body can have been excluded from this incident, evicted before
  selection, busy during lookup, or rejected by its inclusion budget. Only
  inclusion state `available` has an entry in `payloads.json`; empty text is an
  available entry whose value is `""`. Other states must not have content.
- Inclusion byte totals count decoded UTF-8 text, not JSON escaping or compressed
  size. Original HTTP observation sizes and truncation remain unchanged. A
  truncated WebSocket message does not invent its original full byte count.
- An event referring to an earlier WebSocket submission does not require that
  submission to remain in the bounded window. A send result does not prove
  peer receipt. Missing events are not reconstructed.

Metadata search does not require loading body text. Metadata itself can contain
public log text, descriptions, and approved URL paths: it is diagnostic content,
not a public index safe for unrestricted distribution. Privacy classification
and source sanitization still apply. Export does not detect secrets mistakenly
classified as public.

## Bounds

Encoding admits at most 2,097,152 counted UTF-8 text bytes across all attachments.
Core also bounds each group, field, and body before encoding. The combined JSON
documents may not exceed 16,777,216 UTF-8 bytes; escaping and structural fields
count toward this output limit. These are resource admission limits, not measured
peak-memory or latency guarantees. The output is buffered; callers must also
bound concurrent encodes and the lifetime of returned data.

## Compatibility and conformance

The schemas describe strict revision-1 writers. A reader may ignore unknown
optional object fields within revision 1, while still validating every known
field, required field, enum, identifier, privacy constraint, and relationship.
New enum meanings and incompatible layouts require an explicit revision decision.
Unsupported revisions are rejected; no export revision migration exists yet.
The private incident database's revision-1 to revision-2 migration is separate
from its revision-2 to revision-3 diagnostic extension; both are tested in the
native Store suites. Portable revision 1 cannot encode system diagnostics.
The native encoder rejects such snapshots instead of dropping that evidence.

JSON Schema alone does not express cross-document identities, event accounting,
UTF-8 byte totals, or transport state relationships. `validate.py` checks those
semantics in addition to the schemas. Its registered numeric and `utf8-*` formats
are required for complete validation; a validator that ignores them is weaker.
This is a development conformance checker, not a runtime deserializer or a safe
archive-import API. It does not extract ZIP files or accept remote data.

The independent fixtures in `testing/incident-export` cover absent and empty
groups, exact 64-bit values, redaction, Unicode and escaping, body states, HTTP
lifecycles, corruption, and compatibility. Both native encoders compare their
output with the same golden documents. See [development checks](../DEVELOPMENT.md).
