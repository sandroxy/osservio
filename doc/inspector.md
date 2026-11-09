# Native inspector

The combined Android and iOS inspectors organize the same evidence into four
destinations. They use native navigation and controls; the host supplies the
recorders, histories, environment provider and optional archives.

| Destination | Contents |
| --- | --- |
| Logs | Live log records, search, level/category filters and individual details |
| Network | All, HTTP and WebSocket scopes; HTTP exchanges and WebSocket connections |
| Problems | Saved manual snapshots and recovered system reports, with immutable evidence |
| App | App/device facts, the current breadcrumb timeline, saved runs, inspector preferences and capture status |

WebSocket connections open an observation timeline, then individual event
details. They are not separate top-level destinations. A missing optional
provider or archive is shown as unconfigured; opening a page does not initialize
a collector or enable persistence.

The full-screen workspace separates Back from Minimize. Search and filtering
share a row; capture/reading status and icon actions form a separate row.
Protocol scopes use a single-choice segmented control. Language and appearance
open dedicated settings pages. Short contextual actions use panels; filters and
problem forms use editing panels with explicit Apply or Save. Destructive actions
retain confirmation. Platform alerts and system share interfaces keep their own
native dismissal behavior.

## Reading and filtering

Live lists show their current capture state, pause control, search, filters and
result count. These controls scroll with the list so that short windows and
large text retain space for records. More actions contains capture controls,
capacity information and confirmed clearing of the relevant live history.
In Network, All clears both supplied histories, HTTP clears requests, and
WebSocket clears socket observations. Clearing from a connection timeline affects
the entire WebSocket history, not only the displayed connection.

Scrolling pauses live updates for reading. Return to latest resumes them.
Pausing or closing the inspector does not stop capture; records can still be
evicted from their bounded stores. Hidden lists stop polling. A failed metadata
read keeps the last available window with an explicit notice. Network details
preserve the record selected on entry, without polling or a refresh/retry action.
Return to the list and reopen a record to inspect its latest retained state.
Returning from a body reader or restoring a minimized session keeps that same
detail snapshot. Opening a detail never resends an HTTP request or socket message.

The three Network scopes keep separate search text, filters and scroll positions
while the inspector remains open. HTTP-only criteria never silently filter the
All or WebSocket scope. Switching tabs or changing the inspector language or
appearance preserves the current list queries and pause state.

| View | Filter dimensions |
| --- | --- |
| Logs | Level, category, time |
| Network: All | Host, source when relevant, time |
| Network: HTTP | Host, source when relevant, method, observed state, HTTP status class, time |
| Network: WebSocket | Host, source when relevant, last observed connection state, time |
| WebSocket timeline | Event type, direction or connection events, time |
| Problems | Record type, manual/system source, record time |
| Current breadcrumbs | Event type, time |

Select any number of values within a dimension: they are joined with **OR**.
Different dimensions and the search text are joined with **AND**. For example,
WARN or ERROR in category `checkout` means `(WARN OR ERROR) AND checkout`.
All, or no selected values, removes that dimension's restriction. Time is a
single choice: any time, last 5 minutes, last 30 minutes, last hour or last day.
Changes in the filter sheet remain a draft until Apply filters. Cancel discards
them. Reset filters resets that draft without clearing the list's search text.
Searching the sheet narrows its options, not the records themselves.

Category, host and method choices come from the retained metadata. An active
choice remains visible even after its records leave that window. Source is
offered when multiple sources are present or a source filter is already active.
Search and filtering never recover uncaptured or evicted evidence.

An HTTP status class and an observed request state are independent. A completed
503 response can match both Completed and 5xx; it is not a transport failure.
No status received differs from a non-success response. WebSocket connection
state is the last retained lifecycle observation. Missing lifecycle evidence is
Unknown, and message results do not establish whether a socket is still open.

Time uses the record's wall-clock timestamp. Connection lists use the first
retained observation, which is not necessarily the original connection start.
A paused view keeps its time-window anchor fixed. Problems uses the snapshot's
record/mark time, or the recovery record time for system evidence. The underlying
failure time or reporting interval remains separate in the saved detail.

## Details and content

Lists search log text or network metadata, never network bodies. HTTP details
separate Overview, Request and Response. WebSocket details separate event
metadata and payload. Selecting a section displays metadata and content state;
Open reader performs the explicit body or payload lookup.

The reader holds that one bounded, sanitized content result. Capture and saved
inclusion notices appear separately from the payload, including truncated,
omitted, missing, busy and empty states. Copy all copies the payload text, not
those explanations. Long metadata values can also open the reader.

Complete JSON objects and arrays in HTTP bodies, WebSocket text and log messages
open in Structure mode after on-demand background parsing. Tap a nonempty
object or array row to expand or collapse it. The root stays expanded; empty
containers and scalar values need no expansion. Indentation, brackets and token
colors show hierarchy without disclosure arrows or key/value columns. Long
values and collapsed containers use abbreviated previews. Touch and hold a node
to copy its complete source value (iOS uses a native context menu). Source mode
provides the exact captured text with native selection and copying. Display
formatting never converts numbers, sorts fields or merges duplicate keys.

Literal case-insensitive search covers the entire captured source, including
collapsed nodes. Previous/next match navigation reveals the containing node and
its ancestors in Structure mode; Source mode highlights the exact text range.
For a match inside an abbreviated value, switch to Source to read it in full.
Search indexes at most 200 matches and reports when more exist. It does not
search other records or refresh the open payload. Mode, query and expansion
state survive in-process minimization and restoration.

The structural index admits at most 65,536 UTF-16 code units, 4,096 value nodes
and 48 nested levels below the root. Each platform has one parse worker and at
most one queued job; leaving the reader cancels pending work and rejects stale
completion. Native lists reuse visible rows, and only those rows format previews.
Malformed, incomplete or over-budget content remains readable as source with
an explanation; the reader does not repair JSON, strip arbitrary log prefixes
or decode a JSON string as a second document. These implementation limits are
resource bounds, not physical-device performance guarantees.

Saved problem details never refresh from current histories. Attached groups
retain their observation times, source loss counters and body-selection states.
Marking a problem does not save it automatically: select attachments and save
explicitly. Network content and the description each require opt-in. See the
[problem workflow](incidents.md) for frozen windows and retention, and
[file export](incident-export.md) for optional sharing.

## Language, appearance and accessibility

App > Inspector offers Simplified Chinese and English, and Light and Dark.
The defaults are **Simplified Chinese and Light**, independently of the host's
language or appearance. Preferences persist locally for subsequent inspector
openings. Switching them does not change capture, retention, host defaults or
host UI configuration. Diagnostic values such as URLs, identifiers, platform
names and submitted text are preserved rather than translated.

Controls support native text scaling, readable multiline labels and accessibility
names. At larger text sizes, action groups arrange vertically. Content remains
scrollable with the keyboard visible; the reader keeps search controls and text
in separate scrollable areas. Platform navigation, focus, keyboard and screen
reader behavior still require acceptance in the integrating host.

## Host presentation

Use the combined inspector when supplying the full diagnostic workflow.
Standalone log, HTTP, WebSocket, runtime and problem views remain available for
hosts that own their navigation. Retain providers and stores independently of
the viewer, and release them according to their own ownership contracts.

On Android, an application-owned `InspectorSession(application, factory)` provides
the app-local floating entry and a retained full-screen workspace. Call
`attach(activity)` from each participating Activity's `onResume`; its factory
uses the supplied application-backed context and must not capture an Activity.
The session handles Back, keyboard and system-bar insets. It releases its window
while minimized or backgrounded, retaining the page hierarchy for restoration.
Reattaching after Activity recreation preserves that hierarchy without retaining
the old Activity. It follows one attached Activity; simultaneous display-specific
workspaces are outside this API. Call `dispose()` when releasing the session.
Dragging the entry docks it to an edge within the host's safe area, including
keyboard avoidance. Its dock position persists independently of ordinary reading
content. Entry and page motion is short and non-repeating; disabled system
animations, power saving and severe thermal pressure disable SDK transitions.

Hosts that own presentation can still use `InspectorView` directly. Attach
it with a bounded height and apply the host window's system-bar, display-cutout
and keyboard insets. Register `setOnCloseListener` for the explicit Close action.
Forward back navigation to `navigateBack()`; close the host presentation only
when it returns false. `setAppearanceListener` reports the inspector's current
dark/light choice and subsequent changes so the host can style its own system
bars, restoring their previous appearance on dismissal. It does not change the
host theme or window flags. Standalone views use their supplied context's
configuration; the combined inspector supplies its own scoped configuration.

On iOS, retain an `InspectorSession(window:makeInspector:)` per host window.
Its app-local entry starts hidden; the host chooses when to call `showEntry()`.
The entry can be dragged to either edge and remembers its normalized position.
It passes touches outside the entry through to the host and does not become the
key window. Tapping it expands a full-screen workspace in the same scene.
Minimize remains available in SDK navigation pages, readers and editing panels.
The session restores host keyboard focus when collapsing and suspends polling
before its short exit animation. Reduce Motion, Low Power Mode and elevated
thermal state reduce motion; Reduce Transparency uses an opaque entry.

`collapse()` shows the entry, while `hideEntry()` hides both surfaces. Both retain
the in-process tab, navigation, query, reading position and unfinished filter or
problem draft. They never apply a filter or save a problem. A single retained
hierarchy reuses the existing content limits; it does not create a new history
or keep ordinary diagnostics across process restarts. `dispose()` releases the
hierarchy independently of capture and evidence; scene disconnection disposes it
automatically. All session operations are main-actor only. Revealing or expanding
a disposed session or one whose host window has gone returns false.

Hosts that own presentation can still present `InspectorViewController` directly
with its Close action. Custom containers must forward appearance transitions.
Standalone controllers need host-provided navigation and dismissal. See the
[Android](../native/android/README.md) and [iOS](../native/ios/README.md) integration
guides for source dependencies and collector setup.
