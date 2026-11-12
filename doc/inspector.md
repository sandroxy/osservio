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
active query visible; the navigation trash action clears the destination with confirmation.
Language and Theme open dedicated settings pages. Filters use a temporary
sheet with Reset and Apply: Reset edits the draft, and only Apply changes the
active query. Swipe the handle, tap outside, or use the platform's dismissal
mechanism to discard it. Problem composition uses a tall temporary sheet with
Cancel and Save. It has a handle and no Minimize action; its content scrolls with
large text or a keyboard.
Unmodified drafts dismiss immediately. Changed attachment choices or a nonempty
note require confirmation before user dismissal. Saving prevents duplicate
submission and user dismissal; failure preserves the draft for retry. Explicit
host collapse or hide discards temporary drafts without prompting or saving. A
write already admitted by Save may still finish after the view is removed.
Platform alerts and system share interfaces keep their native behavior. Tapping
outside an SDK input dismisses its keyboard without consuming
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
polling and pending parsing. Temporary panels cover the tab bar. Android bottom
sheets have rounded top corners and square bottom corners; the surface continues through the navigation
inset while controls remain within the safe area. More panels dismiss by native
swipe/backdrop or an accessible dismiss action; Android also provides a draggable
handle. Filter drafts remain provisional
until Apply. Copy and Back use icons with contextual
accessibility labels; Reset, Apply, Cancel and Save retain concise text. The
problem sheet places Cancel on the left and Save on the right. Android sheet
actions use neutral secondary capsules and filled primary capsules, with explicit
disabled states. Titles
name the page's function; URLs, event categories and observation states stay in
the content instead of competing for navigation space.

Minimize is always the rightmost visible action. Beside it, one page action is
shown directly; two or more are collected together under More. Without a fixed
exit, up to two page actions are visible; three or more all move into More.
A standalone combined inspector's Close is also a fixed exit. Disabled actions
keep their place and state. Temporary menus have no Minimize or retained draft.

## Reading and filtering

Logs, Network and the current runtime timeline show search, filters, result counts and a direct trash action.
They have no permanent play/pause controls. Search, filters and summaries scroll
with the feed so short windows and large text retain reading space. Clearing
stays in the navigation bar. Saved runs also exposes deletion directly,
with confirmation; it does not open a one-item More menu. Capacity eviction and rate
limits appear as loss notices when relevant. Clearing affects the destination's
entire live history, including hidden records, independently of filters. Network
clears both supplied histories. Connection-only pages do not offer clearing.
Problems deletes saved evidence with a separate confirmation.

Scrolling freezes the reading window while capture continues. View latest is a
floating upward-arrow capsule above the tab bar, independent of the scrolling
header. It reads the newest retained local snapshot, returns to the top and
resumes updates without resetting the query. The top stays anchored through
self-sizing rows and deferred search results until the user scrolls again. An
explicit jump stops any pending Android fling. Ending a scroll at the top also
resumes updates. There is no separate unbounded queue or unread counter. Bounded stores may still evict
content while a reader is paused. Hidden lists stop polling and cancel content
search. Failed metadata reads keep the previous window with an explicit notice.
Network details preserve the selected entry-time record, without a refresh action.
Return to the list and reopen for the latest retained state. Opening any detail
never resends a request or a Socket message.

Network has one search and one filter draft. Choose All, HTTP or WebSocket at
the top of the sheet; only the selected protocol's conditions appear below.
Switching scope clears incompatible conditions while preserving common text,
host and time criteria. Switching tabs, minimizing, or changing language and
theme preserves the applied query and reading position. Reset clears filter
conditions while retaining the main search text. Closing the sheet discards its
draft; Apply commits the entire draft, including choices made in a nested picker.

Short, stable choices for log levels, Socket activity and message direction are
directly visible above the other common conditions. They keep the query's
selection rules: levels and directions allow multiple values, activity is
exclusive, and All removes that dimension's restriction. Columns adapt to the
available width and label lengths; large text lays these choices out vertically.
Time and other dimensions remain summary rows; small
choice sets expand in place and long sets open a searchable picker within the
same sheet. Back returns to the overview with its draft and scroll position.
Precise conditions remain under Refine filters, whose collapsed summary names
the selected criteria and values. Applied conditions stay visible as removable
chips above the results, including a restricted text-search scope. Live arrivals
never expand or collapse a draft's groups.

| View | Common conditions | Precise conditions |
| --- | --- | --- |
| Logs | Level, time, host-supplied category | Text scope, exclusion, JSON field |
| Network | HTTP/Socket scope, time, host | Collector source when relevant, text scope, exclusion, JSON field |
| HTTP | Method, status code or family, transport state, minimum duration | Response content type |
| Socket | Messages/send results or connection events, message direction | Observed event kind, message outcome, close code, connection |
| Problems | Record/recovery time, observed record kind | None; note and identifier lookup use the main search |
| Runtime timeline | Observation time and observed kind | None |

Values within a field are alternatives; fields intersect. Categories, hosts,
connections and observed record kinds come from retained records; selected values
remain editable even after their examples disappear. Groups without any candidate
or selected value are omitted. A singleton category or host stays available under
Refine filters. Candidate lists and their placement are frozen while a draft is
open. Fixed semantic choices, such as levels and time ranges, can describe future
observations. HTTP status selection includes families and observed exact codes;
typing a code from 100 through 599 also offers that exact choice, even before it
has appeared. A number in a URL, header or body is not a status-code match.

Search text defaults to all retained searchable content. Refine filters can limit
literal inclusion and exclusion to addresses, headers, request/response bodies,
messages, identifiers or record information. An independent JSON field condition
still examines retained bodies. Result excerpts name the matching source. Opening
an HTTP header/body result selects its request or response section. A metadata
match does not replace a log's message with its category or identifier. Search and
its coverage limits are specified in [Inspector queries](inspector-query.md).

Connection context opened from a Socket detail is fixed for that page, including
saved evidence. Reset can clear user criteria but cannot expand it to other
connections. A category is an exact host-defined identifier, not a runtime or
content-format classification. The SDK does not recognize sample hosts or
interpret application protocols. Framework log provenance awaits its adapters.

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
the archive or providing a permanent refresh control. Reading existing incidents
or saved runs shows activity inside an existing control area without inserting a
loading paragraph above the rows. Initial empty reads and failures remain explicit.

## Details and content

Dense event feeds use an inset continuous group with rounded outside corners and
fine dividers. Android scrollable pages extend behind the floating tab bar with
bottom scroll padding and a small canvas-colored gradient. The last item can be
scrolled fully above the controls, and requested controls account for the same
inset. Sheets cover the tabs and do not inherit this padding. This treatment does
not sample or blur host content. Metadata and settings use grouped cards. Text and JSON readers
share the page canvas with reading margins, rather than a full-height white panel.

Live list searches include retained, host-policy-processed log content, HTTP request/response
bodies and Socket payloads. Matches can show a short content excerpt. Search is
literal and case-insensitive; optional JSON Pointer conditions match an explicitly
specified scalar exactly without guessing its business meaning. A partial-coverage
notice distinguishes missing, truncated or budget-limited content from a complete
no-match result. Search does not collect additional content or create a persistent
body index. See [query semantics and limits](inspector-query.md).

HTTP details separate Overview, Request and Response. Opening a content section
reads a bounded preview before the headers. Socket message details start with
their content; lifecycle observations show only relevant connection facts. A
readable preview opens the full reader. Missing, omitted or unselected content
has an explanation instead of a dead reader link. Details keep the entry-time
metadata; reopening a reader may observe payload eviction but never refreshes
or replays a request.

Live and saved lists use the same record summaries. Logs emphasize their message;
HTTP emphasizes method, status, path, host and duration; Socket messages emphasize
the captured content, direction and connection identity. Millisecond timestamps
use the same format on both platforms. Connection identities are labeled and
abbreviated in the feed, with exact identifiers in details. An abbreviation is a
reading aid, not a unique lookup key.

Addresses have a separate display representation: safe visible characters are
decoded once, while encoded separators, controls and malformed escapes remain
escaped. Hidden paths and queries are labeled instead of displaying encoded
redaction markers. Opening or copying the exact URL preserves the admitted,
source after host policy. Display formatting does not change filtering, storage or export.

Visible Socket message rows may load short previews in the background. At most
16 visible rows are considered per job and 64 previews are cached; each preview
holds about 180 UTF-16 units. They share the bounded search worker and cancel on
navigation, scrolling or query changes. No JSON parsing or full-history body
index is needed for this preview. HTTP bodies are read on demand in details or
content search rather than prefetched for every list row.

Reading pages share the same information hierarchy on Android and iOS: page
titles identify the selected evidence, navigation owns Back and copy/share
actions, and related facts appear in labeled groups. Environment reads and saved
runs expose their read times separately from event times. Saved problem pages
group the record, environment, attached logs/network records and breadcrumbs;
unattached evidence has no disclosure action. System diagnostics retain structured
source facts and open address lists separately. Platform-specific evidence stays
explicit rather than being replaced with a common placeholder value.

Saved problem and retained-run breadcrumbs use one continuous group: heading,
count, search, results and any empty-state explanation share a surface. Search
keeps its editor when results change; records remain reusable rows in the page's
single scrolling list. An attached empty window and an unattached window keep
different explanations. Page-wide controls and simple settings do not gain
extra containers merely for visual consistency.

Explicitly private descriptions explain that the content was omitted at capture instead of making
`[private]` the problem title. The list retains the problem type, record time and
positive attachment counts. Attached empty windows remain distinct from missing
attachments in details. Private
log and breadcrumb content is labeled consistently in live and saved views; a
body whose original was discarded has no reader action. These are presentation choices based on typed
privacy flags, not text matching. They do not recover discarded text, delete
records or change sanitized storage and portable exports.

Log details show a bounded message preview that opens the reader. The reader's
search field is directly available; copy remains in the page navigation. A saved
attachment uses the same search/filter row as live lists, without a live capture
or clear control. Its content search consults only the saved payload map, including
when that map is empty; it never falls back to current live history. Native address
and evidence lists reuse rows as they scroll. Breadcrumb search in a saved problem
is placed beside that specific evidence group.

The reader holds that one bounded, sanitized content result. Capture and saved
inclusion notices appear separately from the payload, including truncated,
omitted, missing, busy and empty states. Copy all copies the payload text, not
those explanations or the parent record context shown above the reader. Long
header values use bounded previews and open exact, searchable, copyable readers.
Host-transformed bodies carry a processing notice separately from their content.
Other long metadata values can also open the reader. Technical fields expand on demand.
Environment sizes use readable binary units, with exact byte counts available
separately; unavailable platform metrics are grouped without inventing values.

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

Saved runs open their own grouped detail page, preserving the archive list's
reading position. The run summary opens labeled recording times, retained and
evicted counts, platform, process ID and full run identity. Saved problem and run
details offer single-record deletion in the navigation actions alongside copying
and, when configured, sharing. Overflow groups all actions under More and keeps
Minimize at the right edge. Deletion requires confirmation; a storage failure
keeps the snapshot readable and allows retry. The iOS list swipe is an additional
shortcut, not the only deletion entry.

Saved environment pages copy only their frozen attachment and identify its
original observation time and platform. They never fill missing facts from the
live provider. Empty attached evidence remains distinct from absent attachments.

Saved problem details never refresh from current histories. Attached groups
retain their observation times, source loss counters and body-selection states.
Marking a problem does not save it automatically: select attachments and save
explicitly. Network content and the description each require opt-in. See the
[problem workflow](incidents.md) for frozen windows and retention, and
[file export](incident-export.md) for optional sharing.

## Language, theme and accessibility

App > Inspector offers Simplified Chinese and English. Theme separates Display
mode (Light or Dark) from Palette (Violet, Blue, Teal, Forest or Rose).
The defaults are **Simplified Chinese, Light and Violet**, independently of the host's
language or appearance. Preferences persist locally for subsequent inspector
openings. Switching them does not change capture, retention, host defaults or
host UI configuration. Language selection stays on its settings page and updates
the checkmark and labels in place. Each palette has light/dark accent, tint and pressed
colors. Error/warning/success and JSON value-type colors keep their semantic
meaning. Palette swatches use a ring and checkmark, localized accessible names,
and full-size touch targets; the grid wraps on narrow viewports. Switching
palette updates retained pages without restarting capture or reparsing JSON.
Diagnostic values such as URLs, identifiers, platform
names and submitted text are preserved rather than translated.

Controls support native text scaling, readable multiline labels and accessibility
names. Navigation titles stay centered, with bounded action slots; longer form controls reflow at larger text sizes. Content remains
scrollable with the keyboard visible; the reader keeps search controls and text
in separate scrollable areas. Platform navigation, focus, keyboard and screen
reader behavior still require acceptance in the integrating host.

Android uses shared typography, grouped selection rows and metadata surfaces
across the inspector. Icon buttons retain 48 dp touch targets independently of
their visible circular surfaces. Navigation titles reserve space for actions;
long titles and content can wrap with larger text. Rendering uses native views
and drawables, without continuously sampling or blurring the host behind them.

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
While its app subwindow is visible, it also scopes the host system bars' icon
contrast to the inspector appearance. Dismissal restores the previous contrast
unless the host has changed those flags independently.
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
Minimize remains available in SDK navigation pages and readers.
Temporary filters, action panels and the problem sheet have no inspector-wide Minimize action.
The session restores host keyboard focus when collapsing and suspends polling
before its short exit animation. Reduce Motion, Low Power Mode and elevated
thermal state reduce motion; Reduce Transparency uses an opaque entry.
The workspace expands from and contracts toward the docked entry using a bounded
layer mask and opacity animation. The mask covers the full-screen navigation
workspace. Interruption removes clipping and touch blockers.
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
