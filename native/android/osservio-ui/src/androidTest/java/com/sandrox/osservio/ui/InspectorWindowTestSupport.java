package com.sandrox.osservio.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.ParcelFileDescriptor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import static org.junit.Assert.assertNotNull;

/** Launch through the instrumentation shell so device background-start policy is not mistaken for UI failure. */
final class InspectorWindowTestSupport {
    private InspectorWindowTestSupport() { }
    static <T extends Activity> T start(Class<T> type) throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor = runner.addMonitor(type.getName(), null, false);
        String component = runner.getTargetContext().getPackageName() + "/" + type.getName();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                runner.getUiAutomation().executeShellCommand("am start -W -n " + component))) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            Activity activity = monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Activity launch: " + output.toString("UTF-8"), activity);
            return type.cast(activity);
        } finally { runner.removeMonitor(monitor); }
    }
}
