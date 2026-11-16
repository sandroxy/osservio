package com.sandrox.osservio.ui;

import android.os.Build;
import android.graphics.Color;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import java.lang.ref.WeakReference;

/** App subwindows share their owner's status-bar appearance. Restore only the bits we still own. */
final class InspectorSystemBars {
    private WeakReference<Window> owner = new WeakReference<>(null);
    private int previous, applied;
    private int previousStatusColor, previousNavigationColor, appliedNavigationColor;

    @SuppressWarnings("deprecation") // API 23–34 app subwindows share the owner's opaque bar colors.
    void apply(Window window, boolean dark) {
        if (owner.get() != window) {
            restore(); previous = appearance(window); owner = new WeakReference<>(window);
            previousStatusColor = window.getStatusBarColor(); previousNavigationColor = window.getNavigationBarColor();
        } else {
            int current = appearance(window), changed = (current ^ applied) & mask();
            previous = (previous & ~changed) | (current & changed);
            if (window.getStatusBarColor() != Color.TRANSPARENT) previousStatusColor = window.getStatusBarColor();
            if (window.getNavigationBarColor() != appliedNavigationColor) previousNavigationColor = window.getNavigationBarColor();
        }
        appliedNavigationColor = Build.VERSION.SDK_INT >= 26 ? Color.TRANSPARENT : Color.BLACK;
        window.setStatusBarColor(Color.TRANSPARENT); window.setNavigationBarColor(appliedNavigationColor);
        applied = dark ? 0 : mask(); write(window, applied);
    }
    @SuppressWarnings("deprecation")
    void restore() {
        Window window = owner.get();
        if (window != null) {
            int current = appearance(window), unchanged = ~(current ^ applied) & mask();
            write(window, (current & ~unchanged) | (previous & unchanged));
            if (window.getStatusBarColor() == Color.TRANSPARENT) window.setStatusBarColor(previousStatusColor);
            if (window.getNavigationBarColor() == appliedNavigationColor) window.setNavigationBarColor(previousNavigationColor);
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
