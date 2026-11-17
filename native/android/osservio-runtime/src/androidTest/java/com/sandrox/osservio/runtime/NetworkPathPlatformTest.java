package com.sandrox.osservio.runtime;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NetworkPathPlatformTest {
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
