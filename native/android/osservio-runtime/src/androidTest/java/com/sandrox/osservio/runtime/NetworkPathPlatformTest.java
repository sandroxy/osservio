package com.sandrox.osservio.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

@RunWith(AndroidJUnit4.class)
public final class NetworkPathPlatformTest {
    @Test @SdkSuppress(minSdkVersion = 24)
    public void legacyAvailabilityRecordsRecoveryWithoutACapabilityCallback() {
        Network network = fixtureNetwork();
        List<String> messages = new ArrayList<>();
        NetworkPathSignals.Lease lease = fixtureLease(messages);
        Api24NetworkPathRegistration.Callback callback = new Api24NetworkPathRegistration.Callback(lease, false);
        callback.onAvailable(network);
        assertEquals(Arrays.asList("status=available; capabilities=unknown"), messages);
        callback.onLost(network);
        callback.onAvailable(network);
        assertEquals(Arrays.asList("status=available; capabilities=unknown", "status=unavailable",
            "status=available; capabilities=unknown"), messages);
        lease.invalidate(); // stop invalidates the old registration before unregistering it.
        callback.onLost(network); callback.onAvailable(network);
        callback.onCapabilitiesChanged(network, emptyCapabilities());
        assertEquals(3, messages.size());
    }
    @Test @SdkSuppress(minSdkVersion = 24)
    public void laterCapabilitiesRefineLegacyAvailabilityWithoutInventingInitialFlags() {
        Network network = fixtureNetwork();
        List<String> messages = new ArrayList<>();
        NetworkPathSignals.Lease lease = fixtureLease(messages);
        Api24NetworkPathRegistration.Callback callback = new Api24NetworkPathRegistration.Callback(lease, false);
        callback.onAvailable(network);
        lease.record("status=unavailable", true); // A delayed initial snapshot cannot replace recovery.
        callback.onCapabilitiesChanged(network, emptyCapabilities());
        callback.onCapabilitiesChanged(network, emptyCapabilities());
        assertEquals(Arrays.asList("status=available; capabilities=unknown",
            "status=available; interfaces=other; validated=false; metered=true"), messages);
        callback.onLost(network);
        callback.onCapabilitiesChanged(network, emptyCapabilities());
        assertEquals(3, messages.size());
        assertEquals("status=unavailable", messages.get(2));
    }
    @Test @SdkSuppress(minSdkVersion = 24)
    public void modernAvailabilityWaitsForItsGuaranteedCapabilities() {
        Network network = fixtureNetwork();
        List<String> messages = new ArrayList<>();
        Api24NetworkPathRegistration.Callback callback = new Api24NetworkPathRegistration.Callback(fixtureLease(messages), true);
        callback.onAvailable(network); assertTrue(messages.isEmpty());
        callback.onCapabilitiesChanged(network, emptyCapabilities());
        assertEquals(Arrays.asList("status=available; interfaces=other; validated=false; metered=true"), messages);
    }
    private static Network fixtureNetwork() {
        ConnectivityManager manager = (ConnectivityManager) InstrumentationRegistry.getInstrumentation()
            .getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager.getActiveNetwork();
        assumeNotNull(network); // Only the opaque identity is used; callbacks do not query it.
        return network;
    }
    private static NetworkCapabilities emptyCapabilities() {
        return new NetworkCapabilities();
    }
    private static NetworkPathSignals.Lease fixtureLease(List<String> messages) {
        return new NetworkPathSignals.Lease(new BreadcrumbRecorder(event -> {
            messages.add(event.message); return BreadcrumbSink.WriteResult.STORED;
        }, new CaptureController(true, LogClock.SYSTEM)));
    }
    @Test public void actualRegistrationDeclaresAvailabilityAndCanBeRestarted() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        CountDownLatch first = new CountDownLatch(1);
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> {
            assertEquals(BreadcrumbEvent.Kind.NETWORK_PATH, event.kind); assertTrue(event.message.length() <= 256);
            first.countDown(); return BreadcrumbSink.WriteResult.STORED;
        }, new CaptureController(true, LogClock.SYSTEM));
        NetworkPathSignals signals = new NetworkPathSignals(context, recorder);
        try {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NetworkPathSignals.StartResult result = signals.start();
            assertEquals(Build.VERSION.SDK_INT < 24 ? NetworkPathSignals.StartResult.UNSUPPORTED : NetworkPathSignals.StartResult.STARTED, result);
            if (Build.VERSION.SDK_INT >= 24) assertEquals(NetworkPathSignals.StartResult.ALREADY_STARTED, signals.start());
        });
        if (Build.VERSION.SDK_INT >= 24) assertTrue(first.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(signals.stop()); assertTrue(signals.stop());
            if (Build.VERSION.SDK_INT >= 24) assertEquals(NetworkPathSignals.StartResult.STARTED, signals.start());
            assertTrue(signals.stop());
        });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> assertTrue(signals.stop()));
        }
    }
    @Test public void permissionDenialIsExplicitAndDoesNotRegister() {
        Context denied = new ContextWrapper(InstrumentationRegistry.getInstrumentation().getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
        };
        BreadcrumbRecorder recorder = new BreadcrumbRecorder(event -> { throw new AssertionError("Unavailable source must not record"); }, new CaptureController(true, LogClock.SYSTEM));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            NetworkPathSignals signals = new NetworkPathSignals(denied, recorder);
            assertEquals(Build.VERSION.SDK_INT < 24 ? NetworkPathSignals.StartResult.UNSUPPORTED : NetworkPathSignals.StartResult.PERMISSION_DENIED, signals.start());
            assertTrue(signals.stop());
        });
    }
}
