# Android integration

Osservio currently provides source-level integration with three Android library
modules. There are no published Maven artifacts. The build uses JDK 17, Gradle
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
