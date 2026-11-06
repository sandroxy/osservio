package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.ListView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.AppleSignalCrash;
import com.sandrox.osservio.core.AndroidTombstone;
import com.sandrox.osservio.core.SystemDiagnostic;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SignalCrashViewTest {
    @Test public void androidTraceShowsExactAddressesAndDistinctMissingStates() {
        AndroidTombstone trace = AndroidTombstone.restore(1, 6, -6, 99, null, Collections.nCopies(128,
            AndroidTombstone.Frame.restore("18446744073709551615", "9223372036854775808", "ab01")));
        SystemDiagnostic diagnostic = SystemDiagnostic.androidExit(1000, 5, 6, 123, 100, null, null, SystemDiagnostic.TombstoneState.AVAILABLE, trace);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(), android.R.style.Theme_Material_Light_NoActionBar);
            String summary = SystemDiagnosticPresentation.summary(context, diagnostic);
            assertTrue(summary.contains("ARM64")); assertTrue(summary.contains(context.getString(R.string.osservio_tombstone_available)));
            assertTrue(summary.contains(context.getString(R.string.osservio_tombstone_thread_id) + ": 99"));
            assertTrue(summary.contains("128 · " + context.getString(R.string.osservio_crash_limit_reached)));
            assertTrue(summary.contains(context.getString(R.string.osservio_crash_fault_address) + ": " + context.getString(R.string.osservio_runtime_unavailable)));
            assertFalse(summary.contains(context.getString(R.string.osservio_crash_report_id)));
            SignalCrashView frames = new SignalCrashView(context, trace, () -> { });
            assertEquals(129, ((ListView) frames.getChildAt(2)).getAdapter().getCount());
            String row = frames.copyText(127);
            assertTrue(row.startsWith("#127\n")); assertTrue(row.contains("0xffffffffffffffff")); assertTrue(row.contains("0x8000000000000000"));
            assertTrue(row.contains(context.getString(R.string.osservio_tombstone_build_id) + ": ab01"));
            java.util.Set<String> states = new java.util.HashSet<>();
            for (SystemDiagnostic.TombstoneState state : SystemDiagnostic.TombstoneState.values()) assertTrue(states.add(TombstonePresentation.state(context, state)));
            assertEquals("#999", TombstonePresentation.architecture(999));
        });
    }
    @Test public void signalOverviewAndListsPreserveExactEvidenceAndLimits() {
        AppleSignalCrash crash = AppleSignalCrash.restore("12345678-1234-1234-1234-abcdef123456", 1700000000000L,
            AppleSignalCrash.Signal.SEGMENTATION_VIOLATION, "SEGV_MAPERR", "18446744073709551615", 4294967295L,
            Collections.nCopies(128, "18446744073709551615"),
            Collections.nCopies(512, AppleSignalCrash.Image.restore("18446744073709551615", "0", "12345678123412341234abcdef123456", "16777228", "18446744073709551615")),
            Collections.singletonMap(SystemDiagnostic.MetadataKey.APP_BUILD, SystemDiagnostic.Text.capturePublic("historical-build")), true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(), android.R.style.Theme_Material_Light_NoActionBar);
            String summary = SystemDiagnosticPresentation.summary(context, SystemDiagnostic.appleSignal(crash));
            assertTrue(summary.contains(context.getString(R.string.osservio_diagnostic_source_apple_signal)));
            assertTrue(summary.contains("0xffffffffffffffff")); assertTrue(summary.contains("4294967295")); assertTrue(summary.contains("historical-build"));
            assertTrue(summary.contains("128 · " + context.getString(R.string.osservio_crash_limit_reached)));
            assertTrue(summary.contains(context.getString(R.string.osservio_crash_image_cache_excluded)));
            assertFalse(summary.contains(context.getString(R.string.osservio_diagnostic_metric_hint)));
            SignalCrashView frames = new SignalCrashView(context, crash, false, () -> { });
            assertEquals(129, ((ListView) frames.getChildAt(2)).getAdapter().getCount()); // 128 frames and a nonselectable footer.
            assertEquals("#127\n0xffffffffffffffff", frames.copyText(127));
            SignalCrashView images = new SignalCrashView(context, crash, true, () -> { });
            assertEquals(513, ((ListView) images.getChildAt(2)).getAdapter().getCount());
            String image = images.copyText(511);
            assertTrue(image.contains("#511 · 12345678123412341234abcdef123456")); assertTrue(image.contains("18446744073709551615"));
            assertTrue(image.contains(context.getString(R.string.osservio_crash_image_size) + ": " + context.getString(R.string.osservio_runtime_unavailable)));
            assertFalse(image.contains("0 B"));
        });
    }
    @Test public void missingEvidenceDoesNotBecomeZeroThreadOrCompleteCache() {
        AppleSignalCrash crash = AppleSignalCrash.restore("12345678-1234-1234-1234-abcdef123456", 1000,
            AppleSignalCrash.Signal.ABORT, "#0", "0", null, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), false);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String summary = SystemDiagnosticPresentation.summary(context, SystemDiagnostic.appleSignal(crash));
        assertTrue(summary.contains(context.getString(R.string.osservio_crash_thread) + ": " + context.getString(R.string.osservio_runtime_unavailable)));
        assertTrue(summary.contains(context.getString(R.string.osservio_crash_fault_address) + ": 0x0"));
        assertTrue(summary.contains(context.getString(R.string.osservio_crash_image_cache_no_exclusion)));
    }
}
