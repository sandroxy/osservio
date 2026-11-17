package com.sandrox.osservio.ui;

import android.graphics.Color;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
@SuppressWarnings("deprecation") // Explicitly exercise the API 23–34 owned-window color contract.
public final class InspectorSystemBarsTest {
    @Test public void inspectorWindowDoesNotPaintThemeBarsOverItsCanvas() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT >= 30);
        InspectorMaterialTestActivity host = InspectorWindowTestSupport.start(InspectorMaterialTestActivity.class);
        Dialog[] dialog = new Dialog[1]; FrameLayout[] canvas = new FrameLayout[1];
        try {
            for (boolean dark : new boolean[]{false, true}) {
                int color = dark ? Color.rgb(21, 22, 28) : Color.rgb(240, 241, 249);
                int[] top = new int[1];
                main(() -> {
                    if (dialog[0] != null) dialog[0].dismiss();
                    dialog[0] = new Dialog(host, dark ? R.style.OsservioScreenDark : R.style.OsservioScreenLight);
                    canvas[0] = new FrameLayout(host); canvas[0].setBackgroundColor(color);
                    canvas[0].setOnApplyWindowInsetsListener((view, insets) -> {
                        top[0] = insets.getInsets(WindowInsets.Type.statusBars()).top;
                        InspectorDialogs.applyInsets(view, insets); return insets;
                    });
                    dialog[0].setContentView(canvas[0]);
                    Window window = dialog[0].getWindow();
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL);
                    WindowManager.LayoutParams attributes = window.getAttributes();
                    attributes.token = host.getWindow().getDecorView().getWindowToken(); window.setAttributes(attributes);
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setWindowAnimations(0);
                    dialog[0].show(); window.setLayout(-1, -1); InspectorDialogs.configureWindow(window, dark);
                    canvas[0].requestApplyInsets();
                    if (Build.VERSION.SDK_INT < 35) {
                        assertEquals(Color.TRANSPARENT, window.getStatusBarColor());
                        assertEquals(Color.TRANSPARENT, window.getNavigationBarColor());
                    }
                    assertFalse(window.isStatusBarContrastEnforced());
                    assertFalse(window.isNavigationBarContrastEnforced());
                });
                boolean matches = false; long deadline = SystemClock.uptimeMillis() + 5000;
                while (!matches && SystemClock.uptimeMillis() < deadline) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    Bitmap screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                    if (screenshot != null) {
                        try {
                            if (top[0] > 0) matches = screenshot.getPixel(screenshot.getWidth() / 2, top[0] / 2) == color;
                        } finally { screenshot.recycle(); }
                    }
                    if (!matches) Thread.sleep(25);
                }
                assertTrue("Status bar must reveal the inspector canvas in both appearances", matches);
            }
        } finally { main(() -> { if (dialog[0] != null) dialog[0].dismiss(); host.finish(); }); }
    }
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
