# iOS integration

Osservio currently provides source-level integration through the root Swift
package. There is no published release or binary distribution. The package uses
Swift 5.9 or later and targets iOS 13 or later. Minimum targets describe build
configuration, not completed testing on every supported OS version.

In Xcode, add this checkout as a local Swift package. Link `OsservioCore` and
`OsservioStore` to your app target; add `OsservioUI` when using the inspector.
The UI depends on Core, and Store depends on Core. Neither Core nor Store imports
UIKit or starts capture automatically.

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

The package also declares a macOS target version so Core and Store tests can run
on the development machine. The UIKit source is excluded on macOS; this does
not provide a macOS inspector or establish macOS product support.
