package com.sandrox.osservio.ui;

import android.graphics.Color;
import android.os.Build;
import android.view.Window;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
@SuppressWarnings("deprecation") // Explicitly exercise the API 23–34 owned-window color contract.
public final class InspectorSystemBarsTest {
    @Test public void opaqueOwnerColorsRestoreWithoutOverwritingLaterHostChanges() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT < 35);
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        try {
            main(() -> {
                Window window = host.getWindow(); window.setStatusBarColor(Color.BLUE); window.setNavigationBarColor(Color.GREEN);
                InspectorSystemBars bars = new InspectorSystemBars(); bars.apply(window, false);
                assertEquals(Color.TRANSPARENT, window.getStatusBarColor());
                assertEquals(Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK, window.getNavigationBarColor());
                bars.restore(); assertEquals(Color.BLUE, window.getStatusBarColor()); assertEquals(Color.GREEN, window.getNavigationBarColor());
                bars.apply(window, false); window.setStatusBarColor(Color.RED); window.setNavigationBarColor(Color.YELLOW);
                bars.restore(); assertEquals(Color.RED, window.getStatusBarColor()); assertEquals(Color.YELLOW, window.getNavigationBarColor());
                bars.apply(window, false); window.setStatusBarColor(Color.CYAN); window.setNavigationBarColor(Color.MAGENTA);
                bars.apply(window, true); bars.restore();
                assertEquals(Color.CYAN, window.getStatusBarColor()); assertEquals(Color.MAGENTA, window.getNavigationBarColor());
            });
        } finally { main(host::finish); }
    }
    private static void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
}
