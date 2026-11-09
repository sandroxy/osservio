package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-height native reading surfaces with their own scoped theme and safe area. */
final class InspectorDialogs {
    private InspectorDialogs() { }
    static AlertDialog screen(Context context, String title, View content) {
        LinearLayout root = new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(InspectorTheme.canvas(context));
        LinearLayout bar = new LinearLayout(context); bar.setGravity(Gravity.CENTER_VERTICAL);
        int inset = InspectorTheme.dp(context, 16); bar.setPadding(inset, 0, inset, 0);
        TextView heading = InspectorTheme.text(context, 19, true); heading.setText(title); heading.setMaxLines(2);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = InspectorWidgets.button(context, R.string.osservio_close, () -> { });
        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2)); root.addView(InspectorWidgets.divider(context));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(context, InspectorPreferences.dark(context)
            ? R.style.OsservioScreenDark : R.style.OsservioScreenLight).create();
        dialog.setView(root, 0, 0, 0, 0);
        close.setOnClickListener(ignored -> dialog.dismiss());
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(InspectorTheme.canvas(context)));
                window.setLayout(-1, -1); configureWindow(window, InspectorPreferences.dark(context));
                root.setOnApplyWindowInsetsListener((view, insets) -> {
                    applyInsets(view, insets); return insets;
                });
                root.requestApplyInsets();
            }
        });
        return dialog;
    }
    @SuppressWarnings("deprecation") // Window APIs differ across supported API 23–36 hosts.
    private static void configureWindow(Window window, boolean dark) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!dark && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }
    @SuppressWarnings("deprecation") // Legacy insets keep API 23–29 supported.
    private static void applyInsets(View root, WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
        } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
    }
}
