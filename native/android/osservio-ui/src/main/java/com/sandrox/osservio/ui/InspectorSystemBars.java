package com.sandrox.osservio.ui;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import java.lang.ref.WeakReference;

/** App subwindows share their owner's status-bar appearance. Restore only the bits we still own. */
final class InspectorSystemBars {
    private WeakReference<Window> owner = new WeakReference<>(null);
    private int previous, applied;

    void apply(Window window, boolean dark) {
        if (owner.get() != window) {
            restore(); previous = appearance(window); owner = new WeakReference<>(window);
        } else {
            int current = appearance(window), changed = (current ^ applied) & mask();
            previous = (previous & ~changed) | (current & changed);
        }
        applied = dark ? 0 : mask(); write(window, applied);
    }
    void restore() {
        Window window = owner.get();
        if (window != null) {
            int current = appearance(window), unchanged = ~(current ^ applied) & mask();
            write(window, (current & ~unchanged) | (previous & unchanged));
        }
        owner.clear();
    }
    @SuppressWarnings("deprecation") // API 23–29 use the same scoped contrast bits through View flags.
    private static int mask() {
        if (Build.VERSION.SDK_INT >= 30) return WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        return View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
    }
    @SuppressWarnings("deprecation")
    private static int appearance(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            return controller == null ? 0 : controller.getSystemBarsAppearance() & mask();
        }
        return window.getDecorView().getSystemUiVisibility() & mask();
    }
    @SuppressWarnings("deprecation")
    private static void write(Window window, int value) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(value, mask());
        } else {
            View decor = window.getDecorView(); decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~mask()) | value);
        }
    }
}
