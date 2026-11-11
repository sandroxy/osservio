# Diagnostic data policy

Osservio trusts the local operator whom the host allows to open its inspector.
It is a tool for reading the host's diagnostic data, including request parameters,
authentication headers and message content when those are useful to debugging.
It does not attempt to protect the host from that authorized local operator.
OS permissions and application isolation still apply; the SDK does not bypass
them or inspect other applications.

## Capture and presentation

Debugging remains off at SDK initialization unless the host enables it. Merely
showing the floating entry or inspector does not enable capture. While debugging
is on, the default HTTP and WebSocket policies preserve valid observed URLs,
header values and supported UTF-8 content within their resource budgets. No
header name, including Authorization or Cookie, is permanently forbidden.
Query parameters, URL credentials and fragments are retained when supplied by
the observed client. The SDK does not reconstruct data that client never exposed.

Lists show compact identifying information. Details provide provenance, exact
metadata and separate content readers. Long header values have a short preview
and an exact, searchable, copyable reader. Abbreviating a list or a preview does
not modify the stored value. Reading a URL or HTML body does not navigate to it,
execute it, or make another request.

`LogMessage.publicText` means the host permits diagnostic retention; it is not a
claim that the text is suitable for public distribution. `privateText` remains
an explicit host decision to discard the original immediately. Existing private
records and redacted historical URLs cannot be recovered by changing a policy.
Their absence is explained in the UI; records are neither deleted nor unmasked
by guessing at placeholder text.

The reviewed system-diagnostic and fatal-recording profiles remain separate.
This policy does not enable arbitrary memory dumps, registers, file inspection,
additional OS permissions, binary body decoding, or unrestricted crash-handler
work. Missing coverage, unsupported formats, truncation and eviction remain
observable facts rather than privacy switches.

## Host processing

`HTTPPrivacyPolicy` and `WebSocketPrivacyPolicy` are immutable recorder settings.
The defaults need no allowlist. An explicit header-name set restricts values;
nil/null means all valid names. Path, query, HTTP body and Socket text collection
can be disabled independently. Header values default to 4,096 UTF-8 bytes and
can be configured up to 16,384; body and message budgets remain independent.

For more selective processing, supply `DiagnosticTextPolicy`:

```swift
let policy = DiagnosticTextPolicy { input in
    if input.field == .header("authorization") { return nil }
    return input.text
}
let http = try HTTPPrivacyPolicy(textPolicy: policy)
let socket = try WebSocketPrivacyPolicy(textPolicy: policy)
```

```java
DiagnosticTextPolicy policy = input -> {
    if (input.field == DiagnosticTextPolicy.Field.HEADER
            && "authorization".equals(input.headerName)) return null;
    return input.text;
};
HTTPPrivacyPolicy http = new HTTPPrivacyPolicy(true, null,
    HTTPPrivacyPolicy.BodyPolicy.PUBLIC_UTF8, 16384, true, 4096, policy);
WebSocketPrivacyPolicy socket = new WebSocketPrivacyPolicy(true, null,
    true, 4096, true, 4096, policy);
```

These examples demonstrate an optional host choice, not a recommended global
ban on authentication data. The callback receives a bounded URL, header value,
HTTP request/response body or Socket text. Header context includes its lowercase
name; `isTruncated` identifies prefix input. A host may return modified text or
nil/null to omit it. Exceptions, invalid Unicode and oversized output omit the
value without falling back to the raw input. URL output must also remain a valid
supported URL. An omitted URL becomes unavailable, an omitted header value gets
an omission marker, and omitted bodies keep their explicit content state.

Callbacks run under bounded capture admission on the observing thread. A policy
shared by recorders must be thread-safe. Callbacks
must not wait, perform I/O, reenter capture, retain input beyond their task, or
attempt an unbounded parse. The SDK bounds input and output, but cannot bound a
host callback's allocations or execution time. Incomplete JSON prefixes require
an explicit host decision; a failed parser must not silently return raw content.

Processing happens before a recorded event reaches any history, archive or
exporter. Bodies are still finalized once, separate from metadata. If processing
changes a body, its `isProcessed` flag stays with the body reference through
SQLite and export. Observed byte counts continue to describe the original
observation; included text byte counts describe the processed copy. No second
raw copy is retained for later unmasking. Reconfigure by retiring the old owner
and constructing new recorders; a new policy does not rewrite existing evidence.

## Account ownership

The SDK cannot infer login, logout, tenant changes or account identity. A host
with accounts must own a separate diagnostic composition for each active
account, including the signed-out state. Use fresh live stores, recorders,
inspector sessions and recovery coordinators; use separate app-private archive
paths and export namespaces. Use opaque host-assigned names, not raw tokens or
user-entered path components. Reopening old evidence must be a deliberate choice
for the corresponding account, never a global fallback when its store is busy.

Complete an account transition in this order:

1. Prevent new host diagnostic submissions to the old owner and dispose **all**
   of its `InspectorSession` instances. Hiding or collapsing preserves reading
   state and is therefore insufficient. Dismiss any host-owned share UI too.
2. Call `CaptureController.close()`. This permanently disables admission and
   retires active tokens; the old debug switch cannot restart it. False means
   contention: retry later before completing the transition, without spinning
   or blocking a capture callback. Stop the owner's optional observers and
   recovery coordinator as well.
3. Close the old exporter and archives and await their completion. Queued work
   may finish in the old namespace; it must never be redirected to the new one.
   Release retained readers, frozen drafts and snapshot references owned by the
   host. No deletion is needed to establish isolation.
4. Wire the new owner with its own storage paths and new capture controller,
   applying the host's initial debug setting. Bind asynchronous operations to
   the owner that started them; do not resolve a global current-account logger
   when a previous account's operation completes.

iOS archives and file exporters accept explicit directories. Android offers
`SQLiteIncidentStore(context, UUID namespace, options)` and
`SQLiteBreadcrumbStore(context, UUID namespace, options)` under a dedicated
`osservio/scoped/<uuid>/` root in the app's no-backup directory. Exporters use
`IncidentFileExporter(context, UUID namespace, options)` in a separate scoped
cache root. Reuse the host's stable UUID only when deliberately reopening that
same owner's evidence. The legacy unscoped constructors remain appropriate for
a single owner or separately authorized app-wide system diagnosis. These APIs do not expose a cross-account browser. A scope is an ownership
boundary supplied by the host, not a second authentication system.

System crash history is process-wide and can arrive after a restart or logout.
Do not assign it to the currently logged-in account by time proximity alone.
Keep recovery in a separately authorized device/app diagnostic namespace unless
the host has reliable historical ownership evidence. The iOS fatal recorder is
process-owned; account changes do not install another signal handler.

## Copy, snapshots and export

Capture, persistence and external delivery are separate actions. Live log and
network histories stay in bounded memory. Manual snapshots retain the selected
groups; body inclusion remains explicit and bounded. Metadata can itself contain
credentials, parameters and log text. Excluding bodies does not make a snapshot
anonymous. Snapshot descriptions are diagnostic text, not automatically scrubbed.

Copy occurs only on an explicit action. Sharing requires a host-supplied exporter
and a user action on a saved snapshot; the system chooser owns the destination.
The default exporter preserves the snapshot's admitted values. It does not apply
an undocumented second redaction pass, upload automatically, start a remote
inspection endpoint, or fetch remote content. Hosts can omit the exporter or
provide an `IncidentExporter` implementation to apply their delivery policy.
Pre-export transformations must preserve evidence identities and distinguish
modified content from original observations.

Disposing an inspector cancels pending preparation and rejects late UI callbacks.
Temporary files use dedicated app-private storage, finite retention and one
in-flight export per instance. Android exposes completed ZIPs only, through a
non-exported provider with explicit read-only URI grants; it does not grant a
whole directory. Closing an owner does not revoke data already copied to the
clipboard, saved by a recipient, or passed to a system share extension. Those
deliberate external copies are outside SDK account isolation. Archive deletion
and export cache retention remain separate host decisions.
