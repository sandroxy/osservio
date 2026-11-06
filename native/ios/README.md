# iOS integration

Osservio currently provides source-level integration through the root Swift
package. There is no published release or binary distribution. The package uses
Swift 5.9 or later and targets iOS 13 or later. Minimum targets describe build
configuration, not completed testing on every supported OS version.

In Xcode, add this checkout as a local Swift package. Link `OsservioCore` and
`OsservioStore` to your app target; add `OsservioUI` when using the inspector.
The UI depends on Core, and Store depends on Core. Neither Core nor Store imports
UIKit or starts capture automatically.

`OsservioURLSession` is an optional product for observing data requests and
WebSocket operations submitted through a host-owned URLSession. It depends only
on Core and Foundation.

Create these objects in a host-owned composition root and retain them across
the screens that should share history:

```swift
import OsservioCore
import OsservioStore
import OsservioUI
import UIKit

let history = try MemoryLogStore()
let recorder = try LogRecorder(sink: history)
try recorder.start(durationMillis: 900_000)

let result = recorder.record(.info, category: "app.lifecycle",
                             message: .publicText("App entered the foreground"))
// Inspect result when the host needs to know whether this observation was recorded.
```

On the main actor, present the shared history in a navigation controller:

```swift
let inspector = LogInspectorViewController(recorder: recorder, history: history)
let navigation = UINavigationController(rootViewController: inspector)
present(navigation, animated: true)
```

The presentation code belongs in a host view controller. Provide the appropriate
dismissal control for your presentation style. Dismissing this UI leaves capture
running until stopped or expired. To stop explicitly, check the result of
`recorder.stop()`; false means the recorder was busy and the operation must be
retried. Clearing `history` is a separate operation.

Use `.privateText(value)` for text that must not enter history. `.publicText`
is explicit permission to record that text, not an automatic secret scrubber.
Read [the logging contract](../../doc/logging.md) for privacy, limits, concurrency,
rejection results, and the absence of persistent storage or automatic capture.

## URLSession data requests

Link `OsservioURLSession` and reuse the log recorder's capture controller:

```swift
import Foundation
import OsservioURLSession

let requests = try MemoryHTTPStore()
let http = try HTTPRecorder(sink: requests, captureController: recorder.captureController,
                            privacy: HTTPPrivacyPolicy())
let observed = ObservedURLSession(session: URLSession.shared, recorder: http)
```

Submit the host's existing `URLRequest` through `observed.dataTask(with:completionHandler:)`
and resume the returned task normally. The original completion arguments are
forwarded. On iOS 15 or later, `try await observed.data(for: request)` also
preserves native task cancellation. The wrapper does not invalidate the session,
replace its delegate, install a URL protocol, or modify requests.

Only requests submitted through these methods are observed. The default policy
omits bodies and sensitive URL/header content. On the main actor, present
`InspectorViewController(logRecorder: recorder, logHistory: history,
httpRecorder: http, httpHistory: requests)` directly for native log/network tabs
with a Done control. For network-only inspection, embed
`HTTPInspectorViewController(recorder: http, history: requests)` in a navigation
controller and supply dismissal appropriate to your presentation. Read
[network coverage](../../doc/network-integration.md) and
[HTTP privacy and limits](../../doc/http.md) before enabling body content.

## WebSocket tasks

Use the same optional product and capture controller:

```swift
let messages = try MemoryWebSocketStore()
let sockets = try WebSocketRecorder(sink: messages, captureController: recorder.captureController,
                                  privacy: WebSocketPrivacyPolicy())
let observedSockets = try ObservedWebSocketSession(session: URLSession.shared, recorder: sockets)
// Use the host's request, then retain the wrapper for the connection's lifetime.
let socket = observedSockets.webSocketTask(with: request)
socket.resume()
```

Use wrapper `send(_:completionHandler:)` and `receive(completionHandler:)` for
message observation. The host still creates its receive loop. To observe
handshake and final completion, supply your existing delegated session instead
of `.shared` and forward its callbacks to the matching wrapper with `didOpen()`,
`didClose(with:)`, and `didComplete(with:)`. Keep this mapping in host code;
Osservio does not replace the delegate or retain all tasks in a registry.
The default policy omits text content. See [WebSocket coverage and callback
limits](../../doc/network-integration.md#urlsession-websocket-tasks).

Embed `WebSocketInspectorViewController(recorder: sockets, history: messages)`
in a navigation controller, or pass `webSocketRecorder: sockets,
webSocketHistory: messages` to the combined `InspectorViewController` initializer.
Connection groups open event timelines and details with explicit content lookup.

## Runtime context

Link `OsservioRuntime` to opt into native lifecycle observations and environment
queries. Retain these objects in the host composition root. On the main actor:

```swift
import OsservioRuntime

let breadcrumbs = try MemoryBreadcrumbStore()
let breadcrumbRecorder = try BreadcrumbRecorder(sink: breadcrumbs, captureController: recorder.captureController)
let signals = RuntimeSignals(recorder: breadcrumbRecorder)
let environment = RuntimeEnvironment()
signals.start()
// Check the result and retry if the controller is busy.
let enabled = recorder.captureController.setBaselineEnabled(true)
```

The SDK default remains off. This explicit baseline choice permits compact
breadcrumbs outside detailed sessions. Call `signals.stop()` when releasing
observer ownership. Environment queries do not enable battery monitoring.

Embed `RuntimeInspectorViewController(provider: environment, recorder:
breadcrumbRecorder, history: breadcrumbs)` in a navigation controller with
host-owned dismissal. Alternatively, add `environment`, `breadcrumbRecorder`,
and `breadcrumbHistory` arguments to the combined inspector's WebSocket
initializer. Its Context tab provides environment snapshots and a searchable
breadcrumb timeline. The example uses memory-only history. To retain selected
contexts across launches, substitute `SQLiteBreadcrumbStore` as described in
[breadcrumb persistence](../../doc/breadcrumb-storage.md), and pass it as
`breadcrumbArchive` to the combined inspector (or `archive` to the runtime
inspector) for saved-run browsing. See
[runtime context](../../doc/runtime-context.md) for native
coverage, privacy, limits, and collector ownership.

## Manual problem snapshots

Core's `IncidentSnapshot` accepts an optional privacy-classified note, an existing
environment observation, and bounded breadcrumb, log, HTTP, and WebSocket
attachments. Network bodies require a separate explicit choice. Store's
`SQLiteIncidentStore(directory:)` saves these explicitly and exposes asynchronous
list, read, delete, and close operations. Supply a dedicated private directory
and retain the store at application scope. It does not change live histories or
capture controls. Success follows the database commit; dispatch callbacks to the
main actor before updating views. See [manual problem snapshots](../../doc/incidents.md)
for examples, budgets, retention, and failure behavior.

On the main actor, embed `IncidentInspectorViewController(archive: incidents,
environment: environment, breadcrumbs: breadcrumbHistory)` in a navigation
controller, or pass `incidentArchive: incidents` to the full combined inspector
initializer. Problems provides an explicit composer and immutable archive browser.
Marking freezes the current context; saving is explicit, and failure retains the
draft. Description text requires an explicit opt-in and must be safe to store.
The UI uses Core contracts and does not own or close the supplied archive. The
composer and browser currently expose notes, environment information, and
breadcrumbs; saved log/network attachments are available through the archive API.
Export is not implemented yet.

The package also declares a macOS target version so Core and Store tests can run
on the development machine. The UIKit source is excluded on macOS; this does
not provide a macOS inspector or establish macOS product support.
