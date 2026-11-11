# Native capability coverage

Osservio currently has native Android and iOS source integrations. This page
describes implemented behavior and its limits, not a release or device-validation
claim. The base modules target Android API 23+ and iOS 13+; individual OS sources
and networking APIs have additional requirements. See the
[Android](../native/android/README.md) and [iOS](../native/ios/README.md) guides for
integration, and [network coverage](network-integration.md) for client versions
and observation points.

## Interactive debugging

| Capability | Android | iOS | Boundary |
| --- | --- | --- | --- |
| Capture controls | Off, baseline, finite detailed session | Same states | Core defaults to off. The host selects collectors and entry policy; presenting an inspector does not enable capture. |
| Text logs | Explicit `LogRecorder` calls | Explicit `LogRecorder` calls | Levels, categories, text limits and private-text replacement. No automatic Logcat, `Log.*`, console or `os_log` interception. |
| HTTP | Host observations and optional OkHttp application interceptor | Host observations and optional URLSession data-task wrapper | Only integrated methods are observed. Redirect/retry and streaming semantics differ. Body content is omitted by default. |
| WebSocket | Host observations and optional OkHttp factory wrapper | Host observations and optional URLSession task wrapper | The iOS host forwards selected delegate events explicitly. Submission, queue acceptance, completion and peer receipt are distinct. Binary content is never copied. |
| Environment | Explicit app, OS, device and process queries | Corresponding native queries | Allowlisted fields only. Unavailable and unsupported are separate from zero. No stable device identifier or user-assigned device name. |
| Runtime breadcrumbs | Optional activity and memory callbacks; explicit host markers | Optional application and memory notifications; explicit host markers | Native lifecycle is not business-page navigation or proof that a screen rendered correctly. |
| Live inspection | Native Views | UIKit controllers | Logs, Network, Problems and App; combined filters, details, explicit content reading and independent view pause. Simplified Chinese/English and Light/Dark settings are independent of the host. See [inspector](inspector.md). |

A host's explicit private log text becomes `[private]` before storage. Default
HTTP and WebSocket policies preserve valid URLs, header values and supported
UTF-8 content within bounds while debugging is enabled. Hosts can selectively
omit or transform data before recording; no credential header is permanently
forbidden. See [data policy and account ownership](data-policy.md),
[logging](logging.md), [HTTP](http.md), [WebSocket](websocket.md), and
[runtime context](runtime-context.md).

## Retained evidence

Both platforms provide:

- Optional SQLite persistence for restart-relevant breadcrumbs, with bounded
  asynchronous admission and independent live/durable retention. An accepted
  observation may still be lost before a queued write commits.
- Explicit manual problem snapshots with a fixed mark time and bounded history
  windows. Environment facts keep their own observation time. Descriptions and
  captured network content require separate selection.
- Independent manual/system incident limits, strict restoration, recovery
  acknowledgements, browsing and confirmed deletion.
- Explicit ZIP export and native system sharing of saved evidence. The portable
  documents preserve source facts, exact integers and missing-content states.

Live log/network history is memory-only until selected for a manual snapshot.
Opening the inspector after a problem does not recover observations that were
disabled, dropped, evicted or never committed. System reports are saved with
their historical evidence; current logs or environment are not silently attached
as though they described the failed process. Retained breadcrumb runs remain
separately browsable and are not automatically classified as crashes.

See [breadcrumb persistence](breadcrumb-storage.md), [problem snapshots](incidents.md),
[system diagnostics](system-diagnostics.md), and [export](incident-export.md).

## Failure sources

| Source | Implemented evidence | Explicit gaps |
| --- | --- | --- |
| Android 11+ exit history | Own main-process exit time, reason, status, PID and available memory/importance facts | OS history can be absent or overwritten. A Java/Kotlin crash classification does not include the original exception message or stack. ANR classification does not include the raw ANR trace. |
| Android 12+ native-crash trace | Bounded faulting-thread address frames, architecture, signal/code, optional fault address and ELF build IDs | No additional signal handler. Missing/rejected traces keep valid exit facts with an explicit read state. Older Android has no corresponding native-stack source in this SDK. |
| iOS 14+ MetricKit | Delivered crash/hang summaries, historical allowlisted metadata and report intervals | Delivery can be delayed or absent. Raw report text, stack trees and original exception messages are not imported. |
| Optional iOS signal recorder | Explicit installation, permitted fatal signals, original time, address frames and binary identities; bounded next-launch recovery | Debugger/handler conflicts reject installation. No SIGKILL, watchdog or OOM recording, and no original Objective-C exception throw stack. |

Android owns its exit/trace history independently of Osservio. Off gates recovery
admission; it cannot erase OS history or prevent a later active read from finding
a failure that occurred while capture was off. The optional iOS recorder instead
gates new pending-file recording through Core permission. Reading prior files is
independent of installing a handler. Existing retained incidents have a separate
lifetime from both mechanisms.

Address evidence is not symbolicated. Matching symbols and build identities are
needed to resolve function names; the current app build must not be substituted
for missing historical build facts. An unexplained exit, a signal termination,
an ANR and a confirmed crash remain different classifications. See
[Android native-crash evidence](android-tombstone.md) and
[Apple fatal-signal evidence](crash-evidence.md).

## Outside the current native implementation

There is no automatic detector for white screens, malformed layouts or business
state errors. Host readiness/navigation markers and explicit problem snapshots
can provide evidence for these cases. Automatic original Java/Kotlin exception
stacks and Objective-C throw stacks are also not provided.

Framework collectors for React Native, Uni and Flutter are not implemented.
Request replay/mocking, traffic alteration, screenshots or screen recording,
unrestricted storage/object inspection, remote control and a diagnostics backend
are outside the current native modules. Local inspection requires no account or
server. Resource limits are implemented admission/retention bounds; they do not
establish a universal CPU, process-memory, energy or capture-completeness guarantee.
