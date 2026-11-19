package com.sandrox.osservio.ui;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** Owns visible UI resources only. A renderer failure remains local to this inspector. */
final class InspectorGlassSession implements ComponentCallbacks2 {
    interface Surface {
        void updateRegistration();
        void release();
        void invalidate();
    }
    interface Thermal { void close(); }
    interface ThermalChanged { void accept(int status); }
    final InspectorGlassBudget budget = new InspectorGlassBudget();
    private final Context context;
    private final Set<Surface> surfaces = new LinkedHashSet<>();
    private final Set<Surface> candidates = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private boolean failed, restricted, thermalLimited, enabled, disposed;
    private boolean observerRegistered, receiverRegistered, callbacksRegistered;
    private Thermal thermal;
    private float animationScale = 1;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refreshPolicy(); }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshPolicy(); }
    };
    InspectorGlassSession(Context context) { this.context = context.getApplicationContext(); }
    boolean allowed() { return enabled && !disposed && !failed && !restricted; }
    boolean enabled() { return enabled && !disposed && !failed; }
    void watch(Surface surface) { candidates.add(surface); }
    void refresh() { for (Surface surface : new ArrayList<>(candidates)) { surface.updateRegistration(); surface.invalidate(); } }
    float animationScale() { return animationScale; }
    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        for (Surface surface : new ArrayList<>(surfaces)) surface.updateRegistration();
        refresh();
    }
    void attach(Surface surface) {
        if (disposed || !enabled || !surfaces.add(surface)) return;
        if (surfaces.size() == 1 && !failed) {
            try {
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                if (manager == null || manager.isLowRamDevice()) { fail(); return; }
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo(); manager.getMemoryInfo(info);
                if (info.lowMemory) { fail(); return; }
                context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer);
                observerRegistered = true;
                context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer);
                // The caller admits only API 33+ hardware views; keep older-class loading safe.
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), Context.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
                context.registerComponentCallbacks(this); callbacksRegistered = true;
                if (Build.VERSION.SDK_INT >= 29) thermal = InspectorGlassThermal.register(context, this::thermalChanged);
            } catch (RuntimeException | LinkageError | OutOfMemoryError unavailable) { fail(); }
        }
        refreshPolicy();
    }
    void detach(Surface surface) {
        surfaces.remove(surface); budget.release(surface);
        if (surfaces.isEmpty()) unregister();
    }
    private void refreshPolicy() {
        boolean next;
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            animationScale = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
            next = power == null || power.isPowerSaveMode() || thermalLimited
                || Float.isNaN(animationScale) || Float.isInfinite(animationScale) || animationScale <= 0
                || Settings.Secure.getInt(context.getContentResolver(), "high_text_contrast_enabled", 0) == 1;
        } catch (RuntimeException unavailable) { next = true; }
        if (restricted == next) return;
        restricted = next;
        for (Surface surface : new ArrayList<>(surfaces)) { surface.release(); surface.invalidate(); }
    }
    void thermalChanged(int status) {
        if (status >= 3) thermalLimited = true;
        else if (status <= 1) thermalLimited = false;
        refreshPolicy();
    }
    void fail() {
        if (failed) return;
        failed = true;
        while (!surfaces.isEmpty()) {
            java.util.Iterator<Surface> iterator = surfaces.iterator();
            Surface surface = iterator.next(); iterator.remove();
            surface.updateRegistration(); surface.invalidate();
        }
        unregister();
    }
    private void unregister() {
        // Unregister each successfully acquired listener even after partial registration failure.
        try { if (observerRegistered) context.getContentResolver().unregisterContentObserver(observer); }
        catch (RuntimeException unavailable) { failed = true; }
        try { if (receiverRegistered) context.unregisterReceiver(receiver); }
        catch (RuntimeException unavailable) { failed = true; }
        try { if (callbacksRegistered) context.unregisterComponentCallbacks(this); }
        catch (RuntimeException unavailable) { failed = true; }
        try { if (thermal != null) thermal.close(); }
        catch (RuntimeException | LinkageError unavailable) { failed = true; }
        observerRegistered = receiverRegistered = callbacksRegistered = false; thermal = null;
    }
    void dispose() {
        if (disposed) return;
        disposed = true;
        for (Surface surface : new ArrayList<>(surfaces)) surface.updateRegistration();
        unregister();
    }
    @Override public void onConfigurationChanged(Configuration configuration) { refreshPolicy(); }
    @SuppressWarnings("deprecation")
    @Override public void onLowMemory() { fail(); }
    @SuppressWarnings("deprecation")
    @Override public void onTrimMemory(int level) {
        if (level == TRIM_MEMORY_UI_HIDDEN) { for (Surface surface : new ArrayList<>(surfaces)) surface.release(); }
        else if (level >= TRIM_MEMORY_RUNNING_LOW) fail();
    }
}
