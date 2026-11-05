package com.sandrox.osservio.runtime;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.EnvironmentSnapshot;
import com.sandrox.osservio.core.EnvironmentSnapshot.Fact;
import com.sandrox.osservio.core.EnvironmentSnapshot.Key;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot reads with one pending request. No stable device IDs, permission prompts, or monitoring. */
public final class RuntimeEnvironment implements EnvironmentProvider, AutoCloseable {
    private final Context context;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Osservio-environment"); thread.setDaemon(true); return thread;
    });
    public RuntimeEnvironment(Context context) { this.context = Objects.requireNonNull(context.getApplicationContext(), "applicationContext"); }
    @Override public void read(Completion completion) {
        Objects.requireNonNull(completion, "completion");
        if (closed.get()) { completion.complete(null, EnvironmentProvider.Error.UNAVAILABLE); return; }
        if (!pending.compareAndSet(false, true)) { completion.complete(null, EnvironmentProvider.Error.BUSY); return; }
        try {
            worker.execute(() -> {
                EnvironmentSnapshot snapshot = null;
                EnvironmentProvider.Error error = null;
                try { snapshot = sample(); }
                catch (RuntimeException failure) { error = EnvironmentProvider.Error.UNAVAILABLE; }
                finally { pending.set(false); }
                // Keep the host callback outside the diagnostic catch; it is never retried.
                completion.complete(snapshot, error);
            });
        } catch (RejectedExecutionException failure) {
            pending.set(false); completion.complete(null, EnvironmentProvider.Error.UNAVAILABLE);
        }
    }
    /** Stops accepting queries; an already admitted query still completes normally. */
    @Override public void close() { closed.set(true); worker.shutdown(); }
    private EnvironmentSnapshot sample() {
        Map<Key, Fact> facts = new EnumMap<>(Key.class);
        for (Key key : Key.values()) facts.put(key, Fact.UNSUPPORTED);
        facts.put(Key.OS_VERSION, text(Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"));
        facts.put(Key.APP_IDENTIFIER, text(context.getPackageName()));
        facts.put(Key.MANUFACTURER, text(Build.MANUFACTURER)); facts.put(Key.DEVICE_MODEL, text(Build.MODEL));
        facts.put(Key.ARCHITECTURE, text(System.getProperty("os.arch")));
        facts.put(Key.PROCESS_ID, Fact.number(android.os.Process.myPid()));
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo app = Build.VERSION.SDK_INT >= 33 ? manager.getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0))
                : legacyPackage(manager, context.getPackageName());
            facts.put(Key.APP_VERSION, text(app.versionName));
            facts.put(Key.APP_BUILD, Fact.text(Long.toString(Build.VERSION.SDK_INT >= 28 ? app.getLongVersionCode() : legacyVersion(app))));
        } catch (PackageManager.NameNotFoundException failure) {
            facts.put(Key.APP_VERSION, Fact.UNAVAILABLE); facts.put(Key.APP_BUILD, Fact.UNAVAILABLE);
        }
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) facts.put(Key.PHYSICAL_MEMORY_BYTES, Fact.UNAVAILABLE);
        else {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(memory);
            facts.put(Key.PHYSICAL_MEMORY_BYTES, memory.totalMem > 0 ? Fact.number(memory.totalMem) : Fact.UNAVAILABLE);
        }
        long pss = Debug.getPss();
        facts.put(Key.PROCESS_PROPORTIONAL_BYTES, pss > 0 && pss <= Long.MAX_VALUE / 1024 ? Fact.number(pss * 1024) : Fact.UNAVAILABLE);
        Runtime runtime = Runtime.getRuntime();
        long heap = runtime.totalMemory() - runtime.freeMemory();
        facts.put(Key.RUNTIME_HEAP_BYTES, heap >= 0 ? Fact.number(heap) : Fact.UNAVAILABLE);
        facts.put(Key.RUNTIME_HEAP_LIMIT_BYTES, Fact.number(runtime.maxMemory()));
        BatteryManager battery = context.getSystemService(BatteryManager.class);
        int percent = battery == null ? -1 : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        facts.put(Key.BATTERY_PERCENT, percent >= 0 && percent <= 100 ? Fact.number(percent) : Fact.UNAVAILABLE);
        PowerManager power = context.getSystemService(PowerManager.class);
        facts.put(Key.LOW_POWER_MODE, power == null ? Fact.UNAVAILABLE : Fact.flag(power.isPowerSaveMode()));
        if (Build.VERSION.SDK_INT >= 29) {
            String[] states = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
            int state = power == null ? -1 : power.getCurrentThermalStatus();
            facts.put(Key.THERMAL_STATE, state >= 0 && state < states.length ? Fact.text(states[state]) : Fact.UNAVAILABLE);
        }
        return EnvironmentSnapshot.capture(EnvironmentSnapshot.Platform.ANDROID, facts, System.currentTimeMillis());
    }
    private static Fact text(String value) { return value == null || value.isEmpty() ? Fact.UNAVAILABLE : Fact.text(value); }
    // Required on supported API levels before the typed flag and long-version APIs were introduced.
    @SuppressWarnings("deprecation")
    private static PackageInfo legacyPackage(PackageManager manager, String name) throws PackageManager.NameNotFoundException {
        return manager.getPackageInfo(name, 0);
    }
    @SuppressWarnings("deprecation")
    private static long legacyVersion(PackageInfo app) { return app.versionCode; }
}
