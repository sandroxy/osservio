# Architecture

This document defines the intended design of Osservio. The implemented native
subset consists of shared capture lifecycle, explicit logging, HTTP and
WebSocket observation in Core, bounded memory history in Store, and log/HTTP/WebSocket
inspection in UI. WebSocket accepts explicit host observations and optional
OkHttp/URLSession wrappers;
see [its contract](websocket.md).
Optional collectors observe host-owned OkHttp and URLSession requests. See
[the logging contract](logging.md), [HTTP observation](http.md), and
[network coverage](network-integration.md) for current behavior.
Baseline breadcrumbs, one-shot environment queries, and optional native runtime
signals are described in [runtime context](runtime-context.md).
Runtime also reads bounded [system diagnostic facts](system-diagnostics.md),
with explicit OS coverage and no fatal handlers. Hosts can explicitly save a
diagnostic through the incident archive, with independent manual/system count
limits. An explicit batch recovery port processes repeated history atomically
with bounded acknowledgements. The native Problems browser and Export include
these reviewed facts; automatic recovery triggers are not connected yet.
Store also supplies opt-in [SQLite breadcrumb persistence](breadcrumb-storage.md)
through Core's archive and status contracts, with independent live and durable
retention. The local database is not a cross-platform interchange protocol.
Native tabs expose logs, HTTP history, WebSocket connections, runtime context,
and explicit [manual problem snapshots](incidents.md) without changing capture
on presentation. Snapshot persistence and inspection are independent of live
histories. Native APIs can persist bounded log/network attachments with separate
payload selection. Native composers freeze metadata and optionally copy referenced
content once. The immutable browser exposes these attachments. Optional Export
modules encode them into [portable JSON documents](../protocol/README.md) and
prepare [bounded temporary ZIP files](incident-export.md) through Core's export
port. An optional host-owned exporter enables explicit native system sharing of
saved evidence; UI depends on Core and rejects stale preparation callbacks.
The broader boundaries below do not establish implemented
capabilities, platform support, or performance guarantees.

## Product scope

Osservio is designed for both frequent development debugging and diagnosis in
released applications. Its local inspector is intended to work without a desktop
connection, account, or diagnostics server.

The inspection model covers logs and errors, HTTP exchanges, WebSocket activity,
device and application context, selected lifecycle and performance signals,
manual problem markers, and retained incident evidence. Native, JavaScript,
and Dart observations retain their source and capability limits.

The inspector is organized around logs, network activity, incidents, and
environment views.
Live workflows require filtering, search, structured and raw detail, copying,
problem marking, and deliberate export. Pausing rendering, stopping capture,
clearing a live view, and deleting an archive have different effects and must
be presented as different operations. Successful requests can be as useful as
failed requests when investigating ordering and application state.

Request replay, mocking, network simulation, screenshots, and other capabilities
that change behavior or capture additional private content are separate optional
extensions. Continuous screen recording and unrestricted object or storage
inspection are outside the baseline design. Step-through debugging and backend
fleet management are not required for the local inspection workflow.

## Runtime controls

The host explicitly initializes the SDK and chooses how users open the inspector.
The SDK must not couple recording to inspector visibility or rely on the build
configuration alone to decide what can be enabled.

| Capture state | Intended behavior |
| --- | --- |
| Off | No new observations are recorded; access to existing evidence and deletion are controlled separately. |
| Baseline | Selected breadcrumbs and incident mechanisms operate within a low-overhead budget. |
| Detailed session | Explicitly selected collectors add richer detail within time and resource limits. |

A development preset may start detailed capture during initialization. Its
limits still apply. Ending a detailed session restores the configured baseline
or off state. Closing the inspector does not implicitly change either state.

The host controls access policy, available capabilities, and privacy settings.
Opening the inspector must not silently enable mutation tools or export data.
Capture coverage, remaining session lifetime, and degradation must be observable.

## Capture and retention

Retention depends on evidence value, cost, sensitivity, and policy. It cannot be
decided solely from an event category or an assumption that a request is easy to
repeat.

| Data | Role | Lifetime |
| --- | --- | --- |
| Live detail | Rich logs, allowed HTTP content, and selected WebSocket frames for interactive debugging | Bounded memory window; temporary persistence only when explicitly configured |
| Selected breadcrumbs | Compact context such as lifecycle transitions, operation outcomes, timings, and request summaries | Bounded recent window, with a small durable journal for selected restart-relevant context |
| Incident evidence | Crash records, relevant breadcrumbs, manual problem captures, and explicitly selected detail | Separate archives with count, age, and disk limits |

Each layer has explicit byte and count limits. Cleanup, pending writes,
temporary exports, and recovery work also consume budgets. Repeated events and
oversized observations must not create unbounded queues or files.

The durable journal is best effort; a sudden termination may occur before queued
context reaches storage. Only data already captured and available can be attached
to an incident. A crash cannot recover an evicted body or convert an entire
in-memory window into guaranteed durable evidence. Preserve capture and loss
information so the inspector does not imply completeness.

Body blobs are separate from indexed metadata. References must distinguish
available, omitted, truncated, evicted, and unavailable content. Formatting and
expensive content inspection occur on demand.

## Responsibilities and dependencies

These are logical responsibilities. They become separate packages or targets
only where dependency, lifecycle, or delivery requirements justify that split.

| Responsibility | Owns |
| --- | --- |
| Protocol | Versioned event and archive semantics, capability descriptions, codecs, and conformance fixtures |
| Core | Session lifecycle, ingestion, correlation, and the storage and query port contracts |
| Policy | Admission budgets, sanitization, sampling, and recording decisions |
| Collectors | Supported platform observations and hook lifecycle |
| Store | Bounded live storage, durable journals, body blobs, incident archives, and recovery |
| Query | Paging, filtering, search, and immutable projections through Core's query contracts |
| UI | Native inspection and explicit user controls through public query and control contracts |
| Adapters | Framework runtime integration and translation into the native public contracts |
| Exporters | Bounded export of sanitized query results and selected available blobs |
| Composition root | Selection, construction, and lifecycle wiring of concrete implementations |

Protocol sits below platform modules and has no UI, framework, or storage
dependency. Core depends on contracts, not concrete stores or collectors. Store
and query implementations satisfy Core's ports. Collectors submit observations
through admission and sanitization; they do not own databases. UI and exporters
consume public query results rather than reading storage internals. Policy may
remain an internal Core component where that preserves cohesion.

The ordinary data path is:

```text
Observation
  -> bounded admission and source sanitization
  -> receiving-boundary validation, when crossing a runtime bridge
  -> sanitized event ingestion
  -> policy-selected live window, journal, or incident archive
  -> immutable query result
  -> inspector or explicit export
```

Framework collectors must bound work and sanitize before bridge transfer; native
validation must not assume an adapter is trustworthy. Native collectors use the
same admission and privacy contracts. No normal store, bridge, or exporter
accepts raw observations.

Fatal recording uses a separate reviewed backend with predefined permitted
fields and crash-safe operations. It does not call this ordinary pipeline.
During a later safe launch, recovery validates and sanitizes permissible data
for ingestion and links available context to an incident. Backend files must
already meet the recording privacy policy before that recovery step.

## Platform integration

Android and iOS own native capture, storage, recovery, and inspector components.
React Native, Uni, and Flutter adapters connect their runtimes to those native
components instead of creating independent archives and viewers in every
framework.

Shared behavior is expressed through protocol definitions, fixtures, and public
contracts. A shared C++ or Rust runtime is not an initial requirement. Native UI
may follow platform conventions while preserving the same inspection semantics.

Implementation currently targets Android and iOS only. Native behavior and
interaction must be exercised before adding framework adapters or adapter
prototypes. The log modules share text-boundary fixtures, and Export modules
implement the document protocol using platform JSON encoders. Core declares
`LogSink` and `LogHistory`;
Store implements them, UI consumes them, and the host wires the concrete store
and recorder. The optional Runtime module supplies explicitly started lifecycle
observers and one-shot environment queries. SQLite persistence accepts only
sanitized restart-relevant breadcrumbs. No fatal crash backend is implemented yet.

Publish a capability description for each supported runtime, network stack, and
integration mode. It must identify prerequisites, available observations,
limitations, and hook or crash-handler conflicts. Native logs do not imply access
to all device logs; instrumenting one network stack does not cover every request
or WebSocket implementation. Uni variants require separate coverage statements.

Events observed at both framework and native layers need explicit correlation
to avoid misleading duplicates. Preserve source provenance and uncertainty;
similar URLs and timestamps alone do not establish identity. Preserve session,
process, runtime, clock, and ordering information across boundaries.

## Incident evidence

Use platform evidence to distinguish confirmed crashes, hangs, ANRs, watchdog
termination, possible memory pressure, and unexplained exits. Each report must
state what supports the classification and what remains unknown. Preserve app,
build, and runtime identities so later symbolication can use matching symbols
and source maps. Recovery and symbolication must not manufacture missing stacks
or causes.

White screens and layout problems cannot be identified reliably from generic
system signals alone. Hosts may provide page readiness, navigation, or other
semantic markers; a user may mark a problem and explicitly capture additional
context. Detection rules must identify their inputs and limits.

Only one configured owner should manage each crash mechanism unless coexistence
has been verified. Backend selection must account for existing SDKs, privacy at
capture time, supported OS versions, and recovery behavior. Native recorders,
storage engines, and stack-specific network integrations remain implementation
choices to validate against these contracts.
