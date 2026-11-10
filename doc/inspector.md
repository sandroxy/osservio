# Native inspector

The combined Android and iOS inspectors organize the same evidence into four
destinations. They use native navigation and controls; the host supplies the
recorders, histories, environment provider and optional archives.

| Destination | Contents |
| --- | --- |
| Logs | Live log records, search, level/category filters and individual details |
| Network | HTTP exchanges and Socket observations in one searchable feed |
| Problems | Saved manual snapshots and recovered system reports, with immutable evidence |
| App | App/device facts, the current breadcrumb timeline, saved runs, inspector preferences and capture settings |

Socket messages are searchable across retained connections. An individual message
opens its evidence details, with a link to records from that connection. A missing
optional provider or archive is shown as unconfigured; opening a page does not
initialize a collector or enable persistence.

The full-screen workspace separates Back from Minimize. Search and its filter
button share one row. Removable criteria and a compact result summary keep the
active query visible; the trash action clears the destination with confirmation.
Language and appearance open dedicated settings pages. Filters use a temporary
sheet with Reset and Apply: Reset edits the draft, and only Apply changes the
active query. Swipe the handle, tap outside, or use the platform's dismissal
mechanism to discard it. Problem composition uses a retained full-screen page
with explicit Save. Platform alerts and system share interfaces keep their native
behavior. Tapping outside an SDK input dismisses its keyboard without consuming
the target control's action; host application inputs are unaffected.

## Debug control and navigation

A bug button at the top left of each root destination controls the same debug
state. It is absent from child pages; Back occupies that position instead.
Only the bug glyph changes color; its neutral container stays the same. Localized
action and state descriptions, together with App capture status, expose an
optional auto-off countdown and expiry. Busy controls are not treated as off.
The host chooses the initial state and optional deadline through
[`CaptureController`](logging.md); UI choices are process-local and do not change
baseline diagnosis. Opening or revealing the inspector never enables debugging.

Supply one shared controller for a consistent log/network lifecycle. If the host
supplies separate controllers, the switch sets each independently; it displays
partial state or reports rejected control instead of claiming atomic success.
Standalone log and network viewers rely on the host's capture controls.
App > Capture settings controls baseline capture independently; the standalone
runtime viewer provides the same settings. Disabling baseline does not end an
active debug session or change the host's storage configuration.

Both platforms keep the tab bar available for ordinary read-only details. Each
tab retains its own navigation stack, while covered lists and readers suspend
polling and pending parsing. Temporary panels and full-screen editing tasks cover
the tab bar. More panels dismiss by native swipe/backdrop or an accessible dismiss
action; Android also provides a draggable handle. Filter drafts remain provisional
until Apply. Copy and Back use icons with contextual
accessibility labels; Apply and Save retain their primary-action text.

## Reading and filtering

Logs, Network and the current runtime timeline show search, filters, result counts and a direct trash action.
They have no permanent play/pause or More controls. Controls scroll with the feed
so short windows and large text retain reading space. Capacity eviction and rate
limits appear as loss notices when relevant. Clearing affects the destination's
entire live history, including hidden records, independently of filters. Network
clears both supplied histories. Connection-only pages do not offer clearing.
Problems deletes saved evidence with a separate confirmation.

Scrolling freezes the reading window while capture continues. Back to latest,
or ending a scroll at the top, resumes updates. Bounded stores may still evict
content while a reader is paused. Hidden lists stop polling and cancel content
search. Failed metadata reads keep the previous window with an explicit notice.
Network details preserve the selected entry-time record, without a refresh action.
Return to the list and reopen for the latest retained state. Opening any detail
never resends a request or a Socket message.

Network has one search and one filter draft. Type, host, time and protocol-specific
criteria are edited together. Selecting an HTTP-specific condition chooses HTTP
and removes Socket-only conditions, and vice versa. All clears protocol-specific
criteria while preserving common text, host and time conditions. Switching tabs,
minimizing, or changing language and appearance preserves the query and reading
position. Reset clears filters and content conditions while retaining the main
search text.

| View | Filter dimensions |
| --- | --- |
| Logs | Level, host-supplied category, time, text exclusion and optional JSON field |
| Network, common | HTTP/Socket type, host, source when relevant, time, content search and exclusion, optional JSON field |
| HTTP | Method, status class, observed transport state, response type, minimum observed duration |
| Socket | Message direction, observed record kind and connection ID |
| Problems | Observed problem kind, record time and note/identifier search |

Values within a field are alternatives; fields intersect. Categories, hosts,
connections and observed record kinds come from retained records; selected values
remain editable even after their examples disappear. Groups without any candidate
or selected value are omitted. Candidate lists are frozen while a draft is open;
choosing a condition does not make the other sections disappear. Fixed semantic
choices, such as levels and time ranges, can also describe future observations.
Content filters expand inline into text exclusion and JSON field matching;
minimum HTTP duration appears with the HTTP conditions. A category is an exact
host-defined identifier, not a runtime or content-format classification. The SDK
does not recognize sample hosts or interpret application protocols. Framework log
provenance awaits the corresponding adapters.

HTTP response types use retained response Content-Type metadata only. Missing,
malformed or conflicting values remain Unknown. No URL suffix or body shape is
used to infer Fetch/XHR, an API call, or a business result. A completed 503 can
match Completed and 5xx simultaneously; it is not a transport failure. Socket
send admission, send completion, incoming messages and lifecycle events remain
distinct observations. They do not prove peer receipt or current connection state.

Time uses each observation's wall-clock timestamp. A paused window keeps its time
anchor fixed. Problems filters use record/recovery time; historical failure time
remains separate in saved details. Problems refreshes on appearance, foregrounding,
save and deletion, with Retry available after a read failure, rather than polling
the archive or providing a permanent refresh control.

## Details and content

Live list searches include retained sanitized log content, HTTP request/response
bodies and Socket payloads. Matches can show a short content excerpt. Search is
literal and case-insensitive; optional JSON Pointer conditions match an explicitly
specified scalar exactly without guessing its business meaning. A partial-coverage
notice distinguishes missing, truncated or budget-limited content from a complete
no-match result. Search does not collect additional content or create a persistent
body index. See [query semantics and limits](inspector-query.md).

HTTP details separate Overview, Request and Response. WebSocket details separate event
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

Runtime event details group the event, content and retention intent. Event and
session identifiers expand on demand. Live history, retained runs and saved
problems share this immutable presentation, with exact full-snapshot copying and
a separate bounded content reader. Retention intent alone is not proof of a
committed write.

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
Expansion and collapse use a native circular reveal anchored to the current entry
position. The temporary touch blocker, animation callbacks and clipping are
released on completion, cancellation, geometry changes or host detachment.
Retained tab pages rebind SDK labels and colors when preferences change without
replacing their content, JSON expansion or search state.

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
Minimize remains available in SDK navigation pages, readers and full-screen editors.
Temporary filters and action panels have no inspector-wide Minimize action.
The session restores host keyboard focus when collapsing and suspends polling
before its short exit animation. Reduce Motion, Low Power Mode and elevated
thermal state reduce motion; Reduce Transparency uses an opaque entry.
The workspace expands from and contracts toward the docked entry using a bounded
layer mask and opacity animation. The mask covers presented full-screen editors
as well as navigation pages. Interruption removes clipping and touch blockers.
Neither platform captures per-frame screenshots or runs a persistent full-screen
blur. The diagnostic-panel entry mark is distinct from the debug toggle's bug.

`collapse()` shows the entry, while `hideEntry()` hides both surfaces. Both retain
the in-process tab, navigation, applied query, reading position and unfinished
problem draft. Temporary filter drafts, action panels and SDK notices are
dismissed; they are not restored. Background interruption alone does not discard
an open draft. These operations never apply a filter or save a problem. A single retained
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
