package com.sandrox.osservio.runtime;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.sandrox.osservio.core.SystemDiagnostic;
import com.sandrox.osservio.core.SystemDiagnosticBatch;
import com.sandrox.osservio.core.SystemDiagnosticProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SystemDiagnosticPlatformTest {
    @Test public void actualHistoryReadReportsPlatformSupportWithoutInstallingAHandler() throws Exception {
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        AtomicReference<SystemDiagnosticBatch> result = new AtomicReference<>();
        AtomicReference<SystemDiagnosticProvider.Error> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (SystemDiagnostics query = new SystemDiagnostics(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            query.read((batch, failure) -> { result.set(batch); error.set(failure); done.countDown(); });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            if (Build.VERSION.SDK_INT < 30) {
                assertNull(result.get()); assertEquals(SystemDiagnosticProvider.Error.UNSUPPORTED, error.get());
            } else {
                assertNull(error.get()); assertNotNull(result.get());
                assertEquals(SystemDiagnostic.Source.ANDROID_EXIT_INFO, result.get().source);
                assertTrue(result.get().diagnostics.size() <= 32);
            }
        }
        assertSame(handler, Thread.getDefaultUncaughtExceptionHandler());
    }
}
