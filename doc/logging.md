# Native log capture

The native logging modules record text explicitly submitted by the host. They do
not intercept `Logcat`, `print`, `NSLog`, JavaScript, network traffic, or crashes.
There is no disk storage, export service, or automatic startup hook.

## Ownership and lifecycle

Create a `MemoryLogStore`, pass it to a `LogRecorder`, and retain both for the
desired lifetime. The optional inspector receives the same recorder and history.
The host owns initialization, access to the inspector, and presentation. A view
does not create another recorder or store.

An independently constructed recorder starts inactive. For a shared debug switch,
create one `CaptureController` and pass it to the log, HTTP and WebSocket recorders.
Debugging defaults to off. The host may explicitly initialize it with
`debugEnabled: true` (Swift) or the `debugEnabled` constructor argument (Java).
The SDK never infers this choice from the build type or server environment.

`setDebugEnabled` applies a process-local on/off state. Repeating the current
setting preserves session identity, rate windows, active exchanges and deadlines.
Enabling starts subsequent observations only. Disabling releases active capture
buffers and returns to the configured baseline or off state, without clearing
history or cancelling host requests or sockets. Existing wrapped sockets can
attach to a later session; this does not recreate their original handshake.
The setting is not persisted; the next process uses the host's initial value.

The default debug session lasts until disabled or process termination. Hosts may
supply `debugAutoOffAfterMillis` at construction (1 millisecond to 24 hours) to
limit each off-to-on transition. A repeated enable does not renew that deadline.
`status` distinguishes enabled, time-limited and expired states; `remainingMillis`
is zero for untimed debugging as well as inactive capture. Expiry uses a
monotonic clock and requires no background timer. Invalid duration or contention
is reported as an error; rejected control must not be displayed as successful.

The lower-level `startDetailed` API, also exposed by `LogRecorder.start`, still
explicitly replaces the session and accepts a finite duration (15 minutes by
default, 1 millisecond to 24 hours). Use `setDebugEnabled` for an idempotent switch.
`stopDetailed` / `LogRecorder.stop` ends either kind of debug session.

Baseline policy is independent: `setBaselineEnabled` changes the fallback without
interrupting active debugging; `stopAll` disables both. Baseline alone does not
admit ordinary logs, including error-level logs, or detailed network records.
It allows configured breadcrumb and diagnosis mechanisms; no collectors or
fatal handlers are installed by constructing a controller.

Recorders sharing a controller share session identity and sequence ordering,
with independent rate budgets and histories. Separate controllers have separate
lifecycles. All capture admission remains bounded before payload copying or
formatting; continuous debugging does not remove privacy or resource limits.

The combined inspector exposes a global debug switch on its root pages. Opening, hiding, closing,
or pausing it does not change capture. Scrolling pauses the displayed snapshot;
resuming loads current retained history. Clearing history affects the shared
store, including other viewers, but does not stop capture. Existing snapshots
remain immutable and may still contain cleared or evicted events until released.

## Content ownership

`LogMessage.publicText` retains the supplied diagnostic text within the log budget.
It does not mean publicly distributable or automatically scrubbed. The authorized
local operator can read, search and copy it. `privateText` is an explicit host
choice to discard the original immediately and record `[private]` with a typed
privacy flag. Existing private records cannot be unmasked later.

The host can process text before calling the recorder. Future automatic adapters
must apply the same host-selected policy before bridge transfer. Stores accept
admitted events, not raw observations. See [diagnostic data policy](data-policy.md)
for the trust model, account lifecycle and output boundaries.

Categories remain static grouping labels with a 64-byte token budget. Runtime
identities, URLs or parameters belong in the message so they do not create an
unbounded set of filter categories. This organization rule is independent of
which content the host permits.

## Resource limits

| Limit | Default | Allowed configuration |
| --- | --- | --- |
| Message UTF-8 bytes | 4,096 | 9–65,536 |
| Admission attempts per rate window | 200 | 1–10,000 |
| Rate window | 1,000 milliseconds | Fixed |
| Retained events | 500 | 1–10,000 |
| Retained message and category UTF-8 bytes | 524,288 | 1–16,777,216 |

The rate limiter uses a tumbling window beginning at session start or the first
attempt after the previous window expires. It is not a rolling one-second quota;
bursts can occur on either side of a window boundary. Attempts reaching this
stage consume the budget even if the store subsequently rejects them.

Message truncation preserves complete Unicode scalars within the byte limit.
It may split a multi-scalar grapheme, such as a combined emoji. An event indicates
whether truncation occurred. Android rejects null messages, null levels, and
unpaired UTF-16 surrogates encountered while scanning the retained prefix.
Swift's input types exclude these representations.

The store evicts the oldest events until both count and text-byte limits fit.
An individual event that cannot fit is rejected without evicting existing
history. Eviction and rate-limit counters accumulate for the lifetime of their
store and recorder, respectively; clearing history or starting a new session
does not reset those counters.

The byte limit measures message and category UTF-8 content, **not total heap
usage**. Object overhead, temporary encoding buffers, immutable snapshots, UI
projections, and caller-owned strings also use memory. Count and input limits
bound individual SDK operations; retaining arbitrary numbers of snapshots is
the consumer's responsibility. The defaults are development defaults, not a
measured production performance guarantee.

## Results and concurrency

Capture is synchronous and uses try-lock admission, with no background queue,
disk I/O, or network I/O. A concurrent or reentrant operation on the same capture controller returns
busy instead of waiting. The supplied store also uses try-lock writes and
snapshots. Capture may lose events under contention; it is not a lossless log.

Inspect the result of `record` when completeness matters. Results distinguish
recorded, inactive, rate-limited, busy, invalid category or message, oversized
event, sink failure, and sequence exhaustion. Android additionally reports an
invalid level. A sink exception is converted into sink failure; the SDK does not
attempt to recover from process-wide failures such as an out-of-memory error.
Do not recursively log a rejection into the same recorder.

`start` reports invalid configuration or contention as an error. `stop` returns
false when busy; `status` and `snapshot` return nil/null when busy so a caller can
retry later. These are unavailable results, not an empty history or stopped
session. `clear` is a synchronous control operation and may wait for the store's
bounded critical section. It must not be called from a capture callback.

Custom sinks and clocks must be thread-safe, bounded, and non-blocking. Clocks
must provide monotonic elapsed time for lifecycle decisions and Unix epoch
milliseconds for display. The recorder cannot enforce the performance of
consumer-supplied implementations. Native UI construction and access belong on
the main thread; Core and Store may be called from other threads.

Sequence numbers order admitted attempts within a session and may have gaps
after rejection. Wall time is for display and can change when the device clock
changes. These fields establish ordering only within the same capture controller
and session, not across independent controllers or processes.
The native events are not a serialized interchange protocol.

## Inspection scope

Both native inspectors provide newest-first logs, combined level/category/time
filters, message and category search, details, copy, confirmed clear, capture
controls, and view pause/resume. See [the native inspector guide](inspector.md)
for filter semantics and navigation.
Periodic refresh runs every 250 milliseconds while visible; user controls can
refresh immediately. Viewers reuse their current snapshot during contention.
On iOS, an inactive application or window scene suspends periodic refresh;
returning does not undo a paused view or change capture.
Copy writes only the displayed, admitted message to
the system clipboard after an explicit action.

The combined inspector defaults to Simplified Chinese and Light, with independent
English and Dark preferences. Android embeds a programmatic view; the host owns
its window, back dispatch and system/keyboard insets. iOS provides UIKit
controllers with native navigation. Standalone views require host-owned
presentation and dismissal.

The current inspector shows retained, evicted, and rate-limited counts.
Other rejections are exposed to the calling host through return values, not yet
aggregated in the UI. Filtering and paused views operate on a bounded snapshot;
they cannot recover data already evicted from the store. Process termination
loses this live history. Saving a selected log window as a
[problem snapshot](incidents.md) is a separate, explicit workflow with its own
retention policy.
