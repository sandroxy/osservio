# Android integration

Osservio provides source-level integration with Core, Store, UI, and an optional
OkHttp adapter. There are no published Maven artifacts. The build uses JDK 17, Gradle
8.14.5, Android Gradle Plugin 8.12.3, and Android SDK 36; library bytecode targets
Java 11 and the minimum Android API is 23. These minimums are build settings,
not evidence that every OS version has been tested.

For local development, add the SDK build to the consumer's `settings.gradle`.
This example assumes the two repositories are siblings; adjust the path to your
checkout:

```groovy
includeBuild('../osservio/native/android') {
    dependencySubstitution {
        substitute(module('io.github.sandroxy:osservio-core')).using(project(':osservio-core'))
        substitute(module('io.github.sandroxy:osservio-store')).using(project(':osservio-store'))
        substitute(module('io.github.sandroxy:osservio-ui')).using(project(':osservio-ui'))
        substitute(module('io.github.sandroxy:osservio-okhttp')).using(project(':osservio-okhttp'))
        substitute(module('io.github.sandroxy:osservio-runtime')).using(project(':osservio-runtime'))
    }
}
```

The app can then declare the following local development dependencies. They are
resolved through that explicit substitution, not downloaded from a release
repository:

```groovy
dependencies {
    implementation 'io.github.sandroxy:osservio-core:0.1.0-dev.0'
    implementation 'io.github.sandroxy:osservio-store:0.1.0-dev.0'
    implementation 'io.github.sandroxy:osservio-ui:0.1.0-dev.0'
}
```

Omit UI if the host supplies its own inspector. UI and Store depend on Core;
Core has no Store or UI dependency. These libraries add no manifest permissions
or automatic startup components.

Create and retain the following objects in a host-owned composition root:

```java
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogMessage;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.store.MemoryLogStore;
import com.sandrox.osservio.ui.LogInspectorView;

MemoryLogStore history = new MemoryLogStore();
LogRecorder recorder = new LogRecorder(history);
recorder.start(900_000L);

LogRecorder.Result result = recorder.record(LogLevel.INFO, "app.lifecycle",
    LogMessage.publicText("App entered the foreground"));
// Inspect result when the host needs to know whether this observation was recorded.
```

On the main thread, construct `new LogInspectorView(activity, recorder, history)`
and attach it to an activity or dialog with a bounded height. This is a
programmatic view requiring explicit dependencies; XML inflation is not
supported. The host owns dismissal, back navigation, and system/keyboard insets.
Use a context with the appropriate light or dark theme.

Detaching the view stops its refresh callbacks and leaves capture running until
stopped or expired. Check `recorder.stop()` when stopping explicitly; false means
the recorder was busy and the operation must be retried. Clearing `history` is
a separate operation.

Use `LogMessage.privateText(value)` for text that must not enter history.
`publicText` is explicit permission to record that text, not an automatic secret
scrubber. Read [the logging contract](../../doc/logging.md) for privacy, limits,
concurrency, rejection results, and the absence of persistent storage or
automatic capture.

## OkHttp requests

The optional adapter is compiled and tested against OkHttp 5.5.1. The host owns
its OkHttp runtime dependency; the adapter uses a compile-only dependency so it
does not silently replace the host's networking version. Other OkHttp versions
are not implied to be compatible. The 5.5.1 Android runtime requires the host
to compile with SDK 37 or later and a corresponding supported Android build
toolchain. Core, Store, and UI do not acquire that requirement. The SDK does not
select or upgrade the host runtime. The following runtime declaration is for
a host that already meets its requirements:

```groovy
implementation 'io.github.sandroxy:osservio-okhttp:0.1.0-dev.0'
implementation 'com.squareup.okhttp3:okhttp:5.5.1'
```

Reuse the log recorder's capture controller, and add one application interceptor
to the host's configured client builder:

```java
import com.sandrox.osservio.core.HTTPPrivacyPolicy;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.okhttp.OsservioInterceptor;
import com.sandrox.osservio.store.MemoryHTTPStore;

MemoryHTTPStore requests = new MemoryHTTPStore();
HTTPRecorder http = new HTTPRecorder(requests, recorder.captureController,
    new HTTPPrivacyPolicy());
// builder is the OkHttpClient.Builder already configured by the host.
builder.addInterceptor(new OsservioInterceptor(http));
```

Use `addInterceptor`, not `addNetworkInterceptor`. Retain the recorder and store
with the client. The default policy omits bodies and sensitive URL/header
content. Use `HTTPInspectorView(activity, http, requests)` for network-only
inspection, or `InspectorView(activity, recorder, history, http, requests)` for
native log and network sections. Import these types from
`com.sandrox.osservio.ui`. Attach the view with a bounded height and the same
host-owned presentation/inset handling described above. Read [network coverage](../../doc/network-integration.md) and
[HTTP privacy and limits](../../doc/http.md) before enabling body content.

## OkHttp WebSockets

The HTTP interceptor does not observe socket messages. Wrap the host's factory
explicitly with the same optional adapter:

```java
import com.sandrox.osservio.core.WebSocketPrivacyPolicy;
import com.sandrox.osservio.core.WebSocketRecorder;
import com.sandrox.osservio.okhttp.ObservedWebSocketFactory;
import com.sandrox.osservio.store.MemoryWebSocketStore;

MemoryWebSocketStore messages = new MemoryWebSocketStore();
WebSocketRecorder sockets = new WebSocketRecorder(messages, recorder.captureController,
    new WebSocketPrivacyPolicy());
// client is the host's existing configured OkHttpClient.
ObservedWebSocketFactory observedSockets = new ObservedWebSocketFactory(client, sockets);
// request and listener belong to the host.
okhttp3.WebSocket socket = observedSockets.newWebSocket(request, listener);
```

Retain and operate on the returned socket normally. Host listeners receive this
same wrapper; original callback data and native return values are preserved.
The default policy omits message text. See [WebSocket
coverage](../../doc/network-integration.md#okhttp-sockets) for lifecycle, privacy,
late enablement, and the distinction between queue acceptance and peer receipt.

Use `WebSocketInspectorView(activity, sockets, messages)` alone, or add `sockets,
messages` as the final constructor arguments to `InspectorView` for the combined
log, HTTP, and WebSocket interface. Connection groups lead to event timelines;
payload lookup and copying happen in event detail. The same presentation and
theme requirements apply.

## Runtime context

Add `implementation 'io.github.sandroxy:osservio-runtime:0.1.0-dev.0'` for native
lifecycle observations and environment queries. It adds no manifest permission
or automatic startup. Retain these objects in a host composition root:

```java
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.store.MemoryBreadcrumbStore;
import com.sandrox.osservio.runtime.RuntimeEnvironment;
import com.sandrox.osservio.runtime.RuntimeSignals;

MemoryBreadcrumbStore breadcrumbs = new MemoryBreadcrumbStore();
BreadcrumbRecorder breadcrumbRecorder = new BreadcrumbRecorder(breadcrumbs, recorder.captureController);
RuntimeSignals signals = new RuntimeSignals(application, breadcrumbRecorder);
RuntimeEnvironment environment = new RuntimeEnvironment(application);
// Controls run on the main thread. Check enabled and retry if capture is busy.
signals.start();
boolean enabled = recorder.captureController.setBaselineEnabled(true);
```

The SDK default remains off. The explicit baseline choice permits compact
breadcrumbs outside detailed sessions. Stop `signals` and close `environment`
when releasing their ownership; do not tie these lifetimes to inspector
visibility. Activity callbacks retain compiled class names, not view contents.

Attach `RuntimeInspectorView(activity, environment, breadcrumbRecorder,
breadcrumbs)` with host-owned presentation and insets, or append `environment,
breadcrumbRecorder, breadcrumbs` after the WebSocket arguments of
`InspectorView`. Its Context section provides one-shot environment reads and a
searchable breadcrumb timeline. The example uses memory-only history. To retain
selected contexts across launches, substitute `SQLiteBreadcrumbStore` as
described in [breadcrumb persistence](../../doc/breadcrumb-storage.md). Pass
the same store as an additional final constructor argument to the combined or
runtime inspector for saved-run browsing. See
[runtime context](../../doc/runtime-context.md) for native
coverage, privacy, and metric definitions.

## Manual problem snapshots

Core's `IncidentSnapshot` accepts an optional privacy-classified note, an existing
environment observation, and a bounded breadcrumb snapshot. Store's
`SQLiteIncidentStore(applicationContext)` saves these explicitly and exposes
asynchronous list, read, delete, and close operations. It uses independent private
storage; no live histories or capture controls are changed. Success follows the
database commit. Keep this store at application scope and marshal its callbacks
to the main thread before updating views. See [manual problem snapshots](../../doc/incidents.md)
for examples, budgets, retention, and failure behavior. Native incident UI and
export are not implemented yet.
