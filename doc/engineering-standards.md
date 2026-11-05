# Engineering standards

Osservio runs inside another application. Its implementation must preserve the
host's stability, behavior, privacy, and performance. These requirements apply
to runtime code, public contracts, tests, build tools, and documentation.

## Documentation and language

| Material | Required language |
| --- | --- |
| Root README | Simplified Chinese in `README.md`, English in `README-EN.md` |
| Uni README and integration or operating instructions | Simplified Chinese and English |
| Uni changelog | Chinese |
| Other documentation | English initially; add Chinese when requested |

Use the same README filenames for Uni when its integration guide is created.
Splitting Uni instructions into additional pages does not remove their bilingual
requirement. Do not create integration instructions before the corresponding
integration exists.

Required translations change together. Translate meaning naturally in each
language while preserving prerequisites, steps, defaults, restrictions, and
capability boundaries. Preserve API names, commands, configuration fields,
versions, error codes, and other exact technical identifiers. Examples in both
languages must describe the same behavior.

English is a reviewed deliverable. The author must check grammar, terminology,
clarity, examples, and agreement with implementation; review must not depend on
the repository owner finding mistakes in English. Code and API comments use
clear English by default. Comments explain contracts, invariants, lifecycle or
concurrency constraints, and non-obvious choices instead of restating the code.

Write consumer documentation for someone who has not seen the development
conversation. Explain integration and observable behavior. Keep development
instructions in `DEVELOPMENT.md`, release procedures in a release guide, and
user-visible version changes in the appropriate changelog as those materials
become necessary. Link to the authoritative source rather than duplicating
facts across documents.

Do not publish conversation transcripts, temporary plans, progress diaries,
machine-specific paths, private references, or investigation evidence. Do not
turn internal comparisons with unrelated products into consumer instructions.
Relevant third-party attribution, dependency details, and technical sources
remain necessary and must be preserved.

Distinguish design intent, implemented behavior, verified integration, and
released support. Do not advertise planned capabilities as working features,
fabricate installation commands, or claim that all activity can be captured.

## Naming and boundaries

Use durable names that express responsibility. Stable files, APIs, and modules
must not encode roadmap order, development stages, priorities, or replacement
history. Product versions and protocol compatibility revisions are explicit
and independent.

Decompose by responsibility, dependency direction, lifecycle, concurrency
requirements, and reason to change. File length is a review signal, not a
splitting rule. Keep cohesive state machines and algorithms together when
splitting would obscure their invariants. Avoid forwarding-only modules and
abstractions without a concrete use.

Follow the contracts and dependency direction in the
[architecture](architecture.md). Core declares storage and query ports; the
composition root wires implementations. Native cores do not depend on framework
adapters or application business models. Optional modules must not silently add
permissions, dependencies, hooks, or initialization to the base package.

## Public behavior and lifecycle

Keep public APIs small and document their error, threading, ownership,
cancellation, and lifecycle behavior. Reject invalid configuration explicitly.
Report unsupported capabilities instead of returning fabricated values, empty
successes, or silent no-ops. Do not guess field aliases or hide failures behind
unrelated fallback behavior.

Treat visibility, capture, subscription lifetime, and retention as distinct
states. Unsubscribing a viewer must not stop capture. Ending detailed capture
must not implicitly delete retained incidents. Reject stale callbacks after a
collector or session stops; teardown and repeated initialization must have
defined behavior.

Preserve host return values, exceptions, stream ownership, cancellation, and
callback ordering. Diagnostic overload or internal failure must not turn a
successful host operation into a failure or conceal the host's own error.

## Capture, privacy, and resources

- Keep capture non-blocking and bounded. Do not perform blocking disk or network
  I/O on the caller or main thread.
- Bound queue count, retained bytes, individual items, work per item, and rate.
  Apply admission limits before parsing, copying large payloads, or transferring
  data across a runtime bridge. Avoid arbitrary object traversal, getters, or
  unbounded stringification in a capture hook.
- Sanitize at the source before bridge transfer and validate at the receiving
  boundary. Raw observations and sanitized events are distinct types. Stores,
  bridges, exporters, and optional remote sinks accept sanitized events only.
- Treat metadata, URLs, headers, bodies, errors, and custom context as possible
  sources of sensitive data. Unknown or unsupported payload formats must not
  bypass redaction. Omit content when safe bounded handling is unavailable.
- Keep payload blobs separate from indexed metadata. Format, highlight,
  decompress, and search payload content lazily within explicit limits.
- Observation must not consume, replay, or force additional reads from a host
  response stream. Do not modify requests to correlate events by default.
- Make drops, truncation, sampling, missing payloads, and capture limitations
  visible. Accounting for overload must itself be bounded and must not cause
  recursive logging.
- Use explicit memory, disk, count, and age limits, with bounded cleanup and
  recovery. Disk failure, corruption, or missing blobs must not crash the host.
- Make export and optional transport explicit. Local inspection must not require
  an account or an external service. Apply the same privacy rules to diagnostics
  produced by the SDK itself.

Select actual limits from measurements and supported device constraints. Do not
turn unmeasured numbers into production guarantees.

## Fatal failures and recovery

Use reviewed platform crash mechanisms and define ownership when another crash
SDK is present. Do not install competing handlers without verified coexistence.
Review a backend's recording format and on-disk data before integration; export
redaction alone cannot protect an already-written report.

Define permitted crash fields in advance and prepare sanitized application
context while normal execution is available. A fatal handler must not call the
normal ingestion pipeline, allocate through ordinary application facilities,
take ordinary locks, query a database, render UI, or perform complex formatting
or serialization. Use only the backend's documented crash-safe operations.

Process crash records during a later safe launch. Recover incomplete writes
defensively. Keep confirmed crashes, ANRs, watchdog termination, possible memory
pressure, and unexplained termination distinct. An unclean exit alone does not
prove a crash or an out-of-memory kill.

## Protocol and evidence integrity

Writers validate their declared revision strictly. Readers may ignore unknown
optional fields within a supported compatibility revision, but must still
validate known fields and reject unsupported revisions. Tolerance is a defined
compatibility rule, not a general excuse to accept malformed data.

Preserve identifier and timestamp precision across languages. Retain clock and
runtime provenance; do not claim exact cross-runtime ordering without evidence.
Correlation must be explicit or marked as uncertain. Missing observations must
not be reconstructed as facts.

Shared semantic changes update schemas, documentation, valid and invalid
fixtures, lifecycle sequences, and relevant migration fixtures together. Test
strict writers, tolerant readers, semantic invariants, and migrations
independently. Product release versions must not stand in for schema revisions.

## Reuse and dependencies

Evaluate mature implementations before building equivalent infrastructure.
Prefer a maintained dependency with a narrow integration when its behavior and
footprint fit. Source reuse or a small fork is appropriate when there is a
documented need and a practical maintenance path.

Before adopting a component, review host behavior, supported platform versions,
resource use, data handling, dependencies, maintenance, tests, and license terms.
Preserve applicable license text, copyright notices, attribution, and relevant
regression tests. Record the upstream version or commit and local changes.
Publicly readable source is not, by itself, permission to redistribute it.

Pin build inputs through package metadata and lockfiles as appropriate. Keep
third-party notices accurate for the code and artifacts actually distributed.
Do not claim a project license or redistribute third-party source without the
corresponding licensing decision and required notices.

## Verification and delivery

Add focused tests for meaningful state transitions, boundaries, overload,
cancellation, stale callbacks, recovery, and corruption where relevant. Use
deterministic clocks, identifiers, and fixtures. Avoid tests that merely mirror
implementation details or assert that a file exists.

Run the documented checks for affected modules, shared contracts, and relevant
native targets. Keep compiler and lint output clean; do not suppress a warning
without understanding and documenting the reason. Report unavailable tooling,
missing devices, failed checks, and untested behavior accurately.

Measure disabled, baseline, detailed-capture, and visible-inspector operation
separately. Cover long sessions and constrained devices in release builds.
Evaluate capture latency, host responsiveness, peak memory, storage growth, and
relevant energy impact. A store size cap alone does not bound capture memory.

Keep implementation, automated verification, device acceptance, and publication
as separate claims. Candidate acceptance must identify the source and exact
artifact bytes; publish the accepted artifacts without rebuilding replacements.
Do not manufacture stable versions, release history, or verification evidence.

Keep changes complete within their actual scope. Do not ship placeholder
behavior as a completed feature. Do not create package metadata, release
declarations, or platform support claims merely to satisfy a check.
