# Native log capture

The native logging modules record text explicitly submitted by the host. They do
not intercept `Logcat`, `print`, `NSLog`, JavaScript, network traffic, or crashes.
There is no disk storage, export service, or automatic startup hook.

## Ownership and lifecycle

Create a `MemoryLogStore`, pass it to a `LogRecorder`, and retain both for the
desired lifetime. The optional inspector receives the same recorder and history.
The host owns initialization, access to the inspector, and presentation. A view
does not create another recorder or store.

An independently constructed recorder starts inactive. `start` creates a new
detailed session with a unique identifier and resets its sequence and rate
window, without clearing history. The default
session lasts 15 minutes; allowed durations range from 1 millisecond to 24 hours.
Expiry uses a monotonic clock and requires no background timer. `stop` prevents
new logs without deleting earlier events. Starting an active recorder replaces
its session; it does not create a concurrent capture pipeline.

For shared lifecycle control, create a `CaptureController` and pass it to each
recorder. Its states are off, baseline, and detailed. Logs require detailed
capture; enabling baseline alone does not admit logs or install collectors.
Detailed capture expires back to the configured baseline or off state.
`setBaselineEnabled` changes that fallback without restarting an active detailed
session. `stopAll` disables both modes without clearing retained data.

Calling `start` or `stop` on any attached recorder changes detailed capture for
all recorders sharing its controller. They share session identity and sequence
ordering, while retaining independent rate budgets and histories. Recorders
using different controllers have independent lifecycles. Changing state outside
an active detailed session creates a new session identity; repeating an already
satisfied stop or baseline setting does not.

The inspector exposes explicit start and stop controls. Opening, hiding, closing,
or pausing it does not change capture. Scrolling pauses the displayed snapshot;
resuming loads current retained history. Clearing history affects the shared
store, including other viewers, but does not stop capture. Existing snapshots
remain immutable and may still contain cleared or evicted events until released.

## Privacy boundary

Every message must use one of two explicit classifications:

- `privateText` immediately discards its input and substitutes `[private]`.
  The original text is not retained by the message, recorder, store, or inspector.
  This does not erase the caller's own copies from memory.
- `publicText` means the host has reviewed the text and permits it to be recorded
  and displayed. It does **not** detect or remove credentials, personal data, or
  arbitrary secrets. Do not pass raw user input, request payloads, or exception
  descriptions without a suitable host sanitization policy.

Categories are static labels such as `app.lifecycle`, containing 1–64 ASCII
letters, digits, periods, underscores, or hyphens. The character restriction is
not a privacy filter: never embed a user identifier, token, URL, or other runtime
data in a category.

Only the recorder constructs `LogEvent` values through the public API. Storage
accepts these admitted events rather than raw message text. Future automatic
collectors require their own bounded redaction policies before entering this
boundary; explicit public classification is not a substitute for those policies.

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

Both native inspectors provide newest-first logs, level filtering, message and
category search, details, copy, clear, capture controls, and view pause/resume.
Periodic refresh runs every 250 milliseconds while visible; user controls can
refresh immediately. Viewers reuse their current snapshot during contention.
On iOS, an inactive application or window scene suspends periodic refresh;
returning does not undo a paused view or change capture.
Copy writes only the displayed, admitted message to
the system clipboard after an explicit action.

English and Simplified Chinese strings and system appearance are provided.
Android embeds a programmatic view; the host owns its window, back navigation,
and system/keyboard insets. iOS provides a UIKit view controller intended for a
navigation controller; the host owns its presentation and dismissal entry.

The current inspector shows retained, evicted, and rate-limited counts.
Other rejections are exposed to the calling host through return values, not yet
aggregated in the UI. Filtering and paused views operate on a bounded snapshot;
they cannot recover data already evicted from the store. Process termination
loses all of this history. Persistent incident evidence is a separate capability
that is not implemented by these modules.
